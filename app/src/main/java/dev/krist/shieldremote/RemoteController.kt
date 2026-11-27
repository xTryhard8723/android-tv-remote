package dev.krist.shieldremote

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import dev.krist.shieldremote.data.Prefs
import dev.krist.shieldremote.data.SavedDevice
import dev.krist.shieldremote.net.CertStore
import dev.krist.shieldremote.net.Discovery
import dev.krist.shieldremote.net.HandshakeFailed
import dev.krist.shieldremote.net.PairingClient
import dev.krist.shieldremote.net.RemoteSession
import dev.krist.shieldremote.net.TlsConnector
import dev.krist.shieldremote.net.closeQuietly
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.protocol.Backoff
import dev.krist.shieldremote.protocol.CommandQueue
import dev.krist.shieldremote.protocol.KeyTyping
import dev.krist.shieldremote.data.Launch
import dev.krist.shieldremote.data.KeyMacro
import kotlinx.coroutines.flow.first
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

enum class Link { NO_DEVICE, CONNECTING, CONNECTED, RECONNECTING, NO_WIFI, NEEDS_PAIRING, PAUSED }
enum class Voice { IDLE, STARTING, LISTENING }

data class VolumeInfo(val level: Int, val max: Int, val muted: Boolean)

data class ImeState(
    val visible: Boolean = false,
    val text: String = "",
    val label: String = "",
    /** Bumped whenever the TV (or a voice result) pushes field content, so the UI resyncs its editor. */
    val tvVersion: Long = 0,
    val fromTv: Boolean = false,
)

data class RemoteState(
    val link: Link = Link.NO_DEVICE,
    val device: SavedDevice? = null,
    val devices: List<SavedDevice> = emptyList(),
    val tvModel: String = "",
    val detail: String = "",
    val volume: VolumeInfo? = null,
    /** Mute shown on the remote: toggled optimistically on press, corrected whenever the TV reports a change. */
    val muted: Boolean = false,
    val currentApp: String = "",
    val isOn: Boolean? = null,
    val ime: ImeState = ImeState(),
    val queued: Int = 0,
    val voice: Voice = Voice.IDLE,
    val voiceSupported: Boolean = false,
)

/** Power/connection status shown as the colored dot on the SHIELD pill's TV icon. */
enum class TvStatus { ON, ASLEEP, OFF }

/**
 * Maps the live link and the last [Remote.Msg.Start] (`started`) to the 3-state status dot.
 * Connected + `started == true` (or Start not yet received) → On; connected + `started == false`
 * → Asleep; any other link (disconnected, connecting, paused, no Wi-Fi, …) → Off, even if a
 * stale `isOn` is still sitting on the state object.
 */
fun tvStatus(link: Link, isOn: Boolean?): TvStatus = when {
    link != Link.CONNECTED -> TvStatus.OFF
    isOn == false -> TvStatus.ASLEEP
    else -> TvStatus.ON
}

/** Apply a link change and drop [RemoteState.isOn] once the session is no longer live. */
fun RemoteState.withLink(link: Link, detail: String = this.detail, queued: Int = this.queued): RemoteState =
    copy(link = link, detail = detail, queued = queued, isOn = if (link == Link.CONNECTED) isOn else null)

sealed class UiEvent {
    data class Toast(val text: String) : UiEvent()
    /** TV-side voice isn't available: use the phone's speech recogniser instead. */
    object UsePhoneSpeech : UiEvent()
    /** The TV opened a voice session but RECORD_AUDIO isn't granted yet: ask, then call micPermissionResult. */
    object RequestMic : UiEvent()
    data class EditShortcut(val shortcut: Shortcut) : UiEvent()
}

class RemoteController(private val context: Context) {
    companion object {
        const val REMOTE_PORT = 6466
        const val PAIRING_PORT = 6467
        private const val SAMPLE_RATE = 8000 // androidtvremote2: 16-bit PCM, mono, 8 kHz
        private const val MAX_VOICE_MS = 12_000L
        private const val VOICE_ACK_MS = 1_500L
    }

    val prefs = Prefs(context).also { p ->
        // Default device (Settings › Connection) wins over the last used one at launch.
        val def = p.settings.defaultDeviceId
        if (def.isNotBlank() && p.devices.any { it.id == def }) p.activeId = def
    }
    private val _settings = MutableStateFlow(prefs.settings)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun updateSettings(f: (Settings) -> Settings) {
        val old = _settings.value
        val new = f(old)
        if (new == old) return
        prefs.settings = new
        _settings.value = new
        dev.krist.shieldremote.ui.Haptics.level = new.haptics
        dev.krist.shieldremote.ui.Haptics.sound = new.sound
        if ((new.graceMinutes != old.graceMinutes || new.notifications != old.notifications) && !foreground && wanted.value) startGrace()
    }

    /** Applies an imported settings document; returns false if it wasn't valid. */
    fun importSettings(text: String): Boolean {
        val st = prefs.importJson(text) ?: return false
        updateSettings { st }
        return true
    }

    fun resetSettings() = updateSettings { Settings() }

    @Volatile private var firstForeground = true
    val certs = CertStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        RemoteState(device = prefs.activeDevice, devices = prefs.devices, link = if (prefs.activeDevice == null) Link.NO_DEVICE else Link.PAUSED)
    )
    val state: StateFlow<RemoteState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val wanted = MutableStateFlow(false)
    /** True while the process should hold a connection (foreground or grace period). */
    val keepAlive: StateFlow<Boolean> = wanted.asStateFlow()
    private val blocked = MutableStateFlow(false) // pairing in progress or pairing required
    private val deviceFlow = MutableStateFlow(prefs.activeDevice)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val queue = CommandQueue()
    @Volatile private var session: RemoteSession? = null
    @Volatile private var sessionNetwork: Network? = null
    @Volatile private var wifi: Network? = null
    @Volatile private var foreground = false
    private var graceJob: Job? = null
    private var pairing: PairingClient? = null
    private var everConnected = false

    // IME bookkeeping (androidtvremote2: ime_counter / ime_field_counter from remote_ime_batch_edit)
    @Volatile private var imeApp = ""
    @Volatile private var imeShownWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var appWaiter: Pair<String, CompletableDeferred<Unit>>? = null

    // Diagnostics: last protocol messages, shown on the hidden debug screen.
    private val _trace = MutableStateFlow<List<String>>(emptyList())
    val trace: StateFlow<List<String>> = _trace.asStateFlow()
    @Volatile private var pings = 0
    private val clockFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun trace(line: String) {
        val entry = clockFmt.format(java.util.Date()) + "  " + line
        _trace.update { (it + entry).takeLast(80) }
    }

    private fun traceInbound(msg: Remote.Msg) {
        if (msg is Remote.Msg.Error) launchFailed?.complete(Unit)
        if (msg is Remote.Msg.PingRequest) { pings++; return }
        val text = "← " + msg.toString().take(300)
        if (msg is Remote.Msg.ImeKeyInject || msg is Remote.Msg.ImeBatchEdit || msg is Remote.Msg.ImeShowRequest || msg is Remote.Msg.Configure) {
            android.util.Log.d("ShieldRemote", text)
        }
        trace(text + if (pings > 0) "   (pings so far: $pings)" else "")
    }

    // Voice
    @Volatile private var voiceBeginWaiter: CompletableDeferred<Int>? = null
    @Volatile private var voiceStop = false

    init {
        registerNetworkCallback()
        scope.launch { superviseLoop() }
        scope.launch { wanted.collect { if (!it) session?.close() } }
    }

    // ---------------------------------------------------------------- lifecycle

    fun onForeground() {
        foreground = true
        graceJob?.cancel(); graceJob = null
        val first = firstForeground; firstForeground = false
        if (first && !_settings.value.autoConnect) { // Settings › Connection › Auto-connect off: wait for a tap
            _state.update { it.withLink(if (it.device == null) Link.NO_DEVICE else Link.PAUSED, "Tap to connect") }
            return
        }
        wanted.value = true
        // A socket that went quiet while we were in the background is probably dead: replace it now.
        session?.let { s ->
            val idle = SystemClock.elapsedRealtime() - maxOf(s.lastRx, s.lastTx)
            if (idle > 7_000) s.close()
        }
        kick.trySend(Unit)
    }

    fun onBackground() {
        foreground = false
        voiceStop = true
        startGrace()
    }

    private fun startGrace() {
        graceJob?.cancel()
        // No notifications allowed = no foreground service = disconnect when leaving the app.
        val minutes = if (_settings.value.notifications) _settings.value.graceMinutes else 0
        if (minutes < 0) { graceJob = null; return } // "always while the notification is shown"
        graceJob = scope.launch {
            if (minutes > 0) delay(minutes.coerceAtMost(60) * 60_000L) else delay(1_500) // brief debounce for app switches
            wanted.value = false
            _state.update { it.withLink(if (it.device == null) Link.NO_DEVICE else Link.PAUSED, "") }
        }
    }

    /** A widget / notification / tile button was used while the app is not on screen. */
    fun wakeForBackgroundCommand() {
        if (deviceFlow.value == null) return
        wanted.value = true
        if (!foreground) startGrace()
        kick.trySend(Unit)
    }

    fun disconnectNow() {
        graceJob?.cancel()
        wanted.value = false
        _state.update { it.withLink(if (it.device == null) Link.NO_DEVICE else Link.PAUSED) }
    }

    /**
     * Power button. Connected: send [code] (POWER toggles Shield sleep/wake; CEC follows on the TV).
     * Link down (Shield likely asleep): reconnect, and send WAKEUP instead of a toggle, queued until the
     * session is ready, so a press never puts a waking Shield back to sleep.
     */
    fun power(code: Int = Remote.Key.POWER) {
        val up = _state.value.link == Link.CONNECTED && session?.ready == true
        val k = if (!up && code == Remote.Key.POWER) Remote.Key.WAKEUP else code
        trace("power: ${when (k) { Remote.Key.POWER -> "POWER"; Remote.Key.SLEEP -> "SLEEP"; else -> "WAKEUP" }}" + if (up) "" else " (link down → reconnect, queued)")
        if (!up) reconnectNow()
        key(k)
    }

    fun reconnectNow() {
        session?.close()
        if (pairing == null) blocked.value = false
        wanted.value = true
        kick.trySend(Unit)
    }

    // ---------------------------------------------------------------- connection supervisor

    private fun setLink(link: Link, detail: String = "") = _state.update { it.withLink(link, detail, queue.size()) }

    private suspend fun superviseLoop() {
        var attempt = 0
        var authFails = 0
        var netFails = 0
        while (true) {
            combine(wanted, deviceFlow, blocked) { w, d, b -> w && d != null && !b }.first { it }
            val dev = deviceFlow.value ?: continue
            if (wifi == null && attempt > 2) setLink(Link.NO_WIFI, "Waiting for Wi-Fi")
            else setLink(if (everConnected || attempt > 0) Link.RECONNECTING else Link.CONNECTING, dev.host)

            val net = wifi
            val result = runCatching {
                runInterruptible { TlsConnector.connect(certs.sslContext(), dev.host, REMOTE_PORT, net?.socketFactory) }
            }
            val sock = result.getOrNull()
            if (deviceFlow.value?.id != dev.id) { sock?.closeQuietly(); attempt = 0; continue } // switched meanwhile
            if (sock != null) {
                netFails = 0
                var gotConfigure = false
                val s = RemoteSession(sock, Remote.Feature.WITH_VOICE, object : RemoteSession.Listener {
                    override fun onReady() = onSessionReady()
                    override fun onDeviceInfo(info: Remote.DeviceInfo) {
                        gotConfigure = true
                        _state.update { it.copy(tvModel = listOf(info.vendor, info.model).filter { s -> s.isNotBlank() }.joinToString(" ")) }
                    }
                    override fun onMessage(msg: Remote.Msg) = handle(msg)
                    override fun onRaw(msg: Remote.Msg) = traceInbound(msg)
                })
                flushJob?.cancel(); ime.sessionStarted()
                trace("connected to ${dev.host}, requesting features ${Remote.Feature.WITH_VOICE}")
                session = s; sessionNetwork = net
                val reason = runInterruptible { s.run() }
                trace("session ended: $reason (active features ${s.activeFeatures})")
                launchFailed?.complete(Unit)
                session = null; sessionNetwork = null
                voiceStop = true
                if (gotConfigure) { authFails = 0; attempt = 1 } else { authFails++; attempt++ }
                if (authFails >= 3) { requirePairing("The Shield closed the connection. Pair again."); authFails = 0; attempt = 0; continue }
                if (!wanted.value || deviceFlow.value?.id != dev.id) { attempt = 0; continue }
                setLink(Link.RECONNECTING, reason)
            } else {
                val e = result.exceptionOrNull()
                val timeout = e is HandshakeFailed && e.cause is SocketTimeoutException
                if (e is HandshakeFailed && !timeout) {
                    authFails++
                    if (authFails >= 3) { requirePairing("The Shield doesn't recognise this phone. Pair again."); authFails = 0; attempt = 0; continue }
                } else {
                    netFails++
                    if (netFails % 3 == 0) scope.launch { rediscover(dev) }
                }
                attempt++
                setLink(if (wifi == null) Link.NO_WIFI else Link.RECONNECTING, e?.message ?: "")
            }
            withTimeoutOrNull(Backoff.delayFor(attempt)) { kick.receive() }
        }
    }

    private fun requirePairing(msg: String) {
        blocked.value = true
        setLink(Link.NEEDS_PAIRING, msg)
    }

    private fun onSessionReady() {
        everConnected = true
        val s = session ?: return
        val pending = queue.drain(SystemClock.elapsedRealtime())
        pending.forEach { s.send(it) }
        _state.update { it.copy(voiceSupported = s.activeFeatures and Remote.Feature.VOICE != 0) }
        setLink(Link.CONNECTED)
    }

    private fun handle(msg: Remote.Msg) {
        when (msg) {
            is Remote.Msg.Volume -> _state.update {
                val changed = it.volume == null || it.volume.muted != msg.muted
                it.copy(volume = VolumeInfo(msg.level, msg.max, msg.muted), muted = if (changed) msg.muted else it.muted)
            }
            is Remote.Msg.Start -> _state.update { it.copy(isOn = msg.started) }
            is Remote.Msg.ImeKeyInject -> {
                val app = msg.appPackage
                appWaiter?.let { (pkg, d) -> if (app == pkg) d.complete(Unit) }
                ime.onKeyInject(msg)
                _state.update { st ->
                    // Switching apps closes whatever text field the user was in.
                    val hide = st.ime.visible && st.ime.fromTv && imeApp.isNotEmpty() && app.isNotEmpty() && app != imeApp
                    st.copy(currentApp = app.ifEmpty { st.currentApp }, ime = if (hide) st.ime.copy(visible = false) else st.ime)
                }
            }
            is Remote.Msg.ImeBatchEdit -> ime.onBatchEdit(msg)
            is Remote.Msg.ImeShowRequest -> ime.onShowRequest(msg)
            is Remote.Msg.VoiceBegin -> onVoiceBegin(msg.sessionId, msg.packageName)
            is Remote.Msg.VoiceEnd -> { trace("voice: ← remote_voice_end(${msg.sessionId}) from the TV (stops recording)"); voiceStop = true }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- commands

    private fun send(payload: ByteArray) {
        val s = session
        if (s != null && s.ready && s.isOpen) {
            s.send(payload)
        } else {
            queue.add(payload, SystemClock.elapsedRealtime())
            _state.update { it.copy(queued = queue.size()) }
            if (!wanted.value && deviceFlow.value != null) wakeForBackgroundCommand()
            kick.trySend(Unit)
        }
    }

    private fun sendKey(code: Int) = send(Remote.keyInject(code, Remote.Direction.SHORT))

    fun key(code: Int) {
        if (code == Remote.Key.DPAD_CENTER && !macroRunning) ime.userOk()
        when (code) {
            Remote.Key.VOLUME_MUTE -> _state.update { it.copy(muted = !it.muted) }
            Remote.Key.VOLUME_UP, Remote.Key.VOLUME_DOWN -> if (_state.value.muted) _state.update { it.copy(muted = false) }
        }
        send(Remote.keyInject(code, Remote.Direction.SHORT))
    }
    fun keyDown(code: Int) = send(Remote.keyInject(code, Remote.Direction.START_LONG))
    fun keyUp(code: Int) = send(Remote.keyInject(code, Remote.Direction.END_LONG))
    fun launch(link: String) = send(Remote.appLink(link))

    /** Completed when the TV answers an app link with remote_error or drops the session (= launch failed). */
    @Volatile private var macroRunning = false
    @Volatile private var launchFailed: CompletableDeferred<Unit>? = null
    private var launchJob: Job? = null

    /**
     * Launches a shortcut. Key-sequence shortcuts replay their keys. Otherwise links are tried ONE at a
     * time: the Shield answers an unresolvable app link with remote_error and closes the session, so the
     * next link is only sent after that error and a reconnect. Success = the TV reports the package in
     * the foreground (remote_ime_key_inject.app_info.app_package); no report and no error = assume it
     * opened (no false "couldn't open").
     */
    fun launchShortcut(sc: Shortcut) = launchShortcut(sc, test = false)

    /** [test] = the editor's Test button: also tries market:// and reports every outcome. */
    fun launchShortcut(sc: Shortcut, test: Boolean) {
        ime.suppressAutoOpen()
        if (sc.keys.isNotBlank()) { runMacro(sc); return }
        val pkg = sc.packageName
        val list = Launch.candidates(sc, pkg.takeIf { it.isNotBlank() }?.let { prefs.launchMethod(it) }, if (test) emptySet() else prefs.rejectedLinks).toMutableList()
        if (list.isEmpty()) {
            trace("✗ ${sc.name}: no usable link, nothing sent")
            _events.tryEmit(UiEvent.Toast("${sc.name} can't be opened by a link on the Shield. Record it once: in the editor tap HOME, then the arrows to reach ${sc.name}, then OK, and Save."))
            if (!test) _events.tryEmit(UiEvent.EditShortcut(sc))
            return
        }
        launchJob?.cancel()
        launchJob = scope.launch {
            var errors = 0
            for ((i, uri) in list.withIndex()) {
                if (!awaitConnected(8_000)) { _events.tryEmit(UiEvent.Toast("Not connected to the Shield")); return@launch }
                val opened = CompletableDeferred<Unit>()
                val failed = CompletableDeferred<Unit>()
                if (pkg.isNotBlank()) appWaiter = pkg to opened
                launchFailed = failed
                trace("→ app_link $uri  (attempt ${i + 1}/${list.size})")
                send(Remote.appLink(uri))
                val outcome = withTimeoutOrNull(3_000) {
                    kotlinx.coroutines.selects.select<Int> { opened.onAwait { 1 }; failed.onAwait { 2 } }
                } ?: 0
                appWaiter = null; launchFailed = null
                when (outcome) {
                    1 -> {
                        trace("✓ ${sc.name} is in the foreground"); if (pkg.isNotBlank()) prefs.setLaunchMethod(pkg, uri)
                        if (test) _events.tryEmit(UiEvent.Toast("✓ ${sc.name} opened via $uri (saved)"))
                        return@launch
                    }
                    0 -> {
                        trace("… no error from the TV; assuming ${sc.name} opened")
                        if (test) _events.tryEmit(UiEvent.Toast("Sent $uri. The TV accepted it but didn't report ${sc.name} in front; check the TV."))
                        return@launch
                    }
                    else -> { errors++; prefs.addRejectedLink(uri); trace("✗ TV rejected $uri (won't be sent again)") }
                }
            }
            if (errors > 0) {
                val hint = if (list.any { it.startsWith("market://") }) " The Play Store no longer opens apps for remotes; install \"AVA Connect\" on the Shield to fix package launches, add the app's deep link, or use a key sequence." else ""
                _events.tryEmit(UiEvent.Toast("The Shield couldn't open ${sc.name}${if (pkg.isNotBlank()) " ($pkg)" else ""}.$hint"))
            }
        }
    }

    private suspend fun awaitConnected(ms: Long): Boolean = withTimeoutOrNull(ms) {
        state.first { it.link == Link.CONNECTED && session?.ready == true }; true
    } ?: false

    private fun runMacro(sc: Shortcut) {
        val steps = KeyMacro.parse(sc.keys)
        if (steps == null) { _events.tryEmit(UiEvent.Toast("Unknown key in \"${sc.keys}\"")); return }
        launchJob?.cancel()
        launchJob = scope.launch {
            trace("→ key sequence ${sc.keys}")
            macroRunning = true
            try {
                for (st in steps) when (st) {
                    is KeyMacro.Step.Key -> { key(st.code); delay(if (st.code == Remote.Key.HOME) 900 else 220) }
                    is KeyMacro.Step.Wait -> delay(st.ms)
                }
            } finally { macroRunning = false; ime.suppressAutoOpen() }
        }
    }

    private val ime = dev.krist.shieldremote.protocol.ImeBridge(
        clock = { SystemClock.elapsedRealtime() },
        sendStroke = { k -> if (k.shift) { keyDown(Remote.Key.SHIFT_LEFT); sendKey(k.code); keyUp(Remote.Key.SHIFT_LEFT) } else sendKey(k.code) },
        sendBatch = { ic, fc, text, cursor -> send(Remote.imeBatchEdit(ic, fc, text, cursor)) },
        trace = { trace(it) },
        ui = object : dev.krist.shieldremote.protocol.ImeBridge.Ui {
            override val panelVisible get() = _state.value.ime.visible
            override val autoKeyboard get() = _settings.value.autoKeyboard
            override fun open(text: String, label: String, show: Boolean) {
                unsentBase = null
                imeApp = _state.value.currentApp
                _state.update { st -> st.copy(ime = ImeState(show, text, label, st.ime.tvVersion + 1, fromTv = true)) }
                imeShownWaiter?.complete(Unit)
                deliverPendingSearch()
            }
            override fun close() = _state.update { st -> st.copy(ime = st.ime.copy(visible = false)) }
        },
    )
    @Volatile private var unsentBase: String? = null
    private var flushJob: Job? = null

    /**
     * A real keystroke in the phone field (the only path that sends typing). Edits are coalesced for
     * ~100 ms and sent by [ImeSender] as one batch edit with the live counters, or as key events when
     * the user chose key typing / the key fallback is on and the TV never reported IME state.
     */
    fun imeEdit(old: String, new: String, selectionEnd: Int) {
        if (old == new) return
        _state.update { it.copy(ime = it.ime.copy(text = new)) }
        ime.userEdit(new)
        if (!_settings.value.liveTyping) { if (unsentBase == null) unsentBase = old; return } // sent on Enter
        if (flushJob?.isActive != true) flushJob = scope.launch { delay(100); flushIme() }
    }

    private fun flushIme() {
        val st = _settings.value
        val useKeys = st.alwaysKeys || (st.keyFallback && !ime.seen)
        val more = ime.flush(useKeys) { _events.tryEmit(UiEvent.Toast("Typing paused: too many edits. Close and reopen the keyboard.")) }
        // More typed (or waiting for the TV's echo): one more flush later (never more than one in flight).
        if (more) flushJob = scope.launch { delay(100); flushIme() }
    }

    fun imeEnter() {
        if (unsentBase != null) { // "send on Enter": push the text first, then Enter
            unsentBase = null
            flushIme()
            scope.launch { delay(300); key(Remote.Key.ENTER) }
            _state.update { it.copy(ime = it.ime.copy(visible = false)) }
            return
        }
        flushJob?.cancel(); flushIme()
        key(Remote.Key.ENTER)
        _state.update { it.copy(ime = it.ime.copy(visible = false)) }
    }

    fun openKeyboard() {
        ime.userOpened(_state.value.ime.text)
        _state.update { it.copy(ime = it.ime.copy(visible = true, tvVersion = it.ime.tvVersion + 1)) }
    }
    fun closeKeyboard() = _state.update { it.copy(ime = it.ime.copy(visible = false)) }

    // ---------------------------------------------------------------- voice

    /**
     * Push-to-talk voice search streamed to the TV (androidtvremote2 start_voice): send KEYCODE_SEARCH,
     * wait for remote_voice_begin, echo it, stream 8 kHz mono PCM16 chunks, then remote_voice_end.
     * Falls back to the phone's recogniser ([UiEvent.UsePhoneSpeech]) when the TV doesn't offer voice.
     */
    @Volatile private var voiceRequestAt = 0L
    @Volatile private var voiceJob: Job? = null
    @Volatile private var pendingVoiceId: Int? = null
    /** Session id of the capture in progress (null until the TV's remote_voice_begin arrives). */
    @Volatile private var voiceSessionId: Int? = null
    val lastVoiceWav: java.io.File get() = java.io.File(context.filesDir, "last_voice.wav")

    /**
     * Mic button (like the Google TV app): start the mic AT ONCE (pre-buffer, so the first words aren't lost
     * while the Shield wakes its Assistant), press KEYCODE_SEARCH and wait for remote_voice_begin. No answer
     * within 1.5 s -> mic released, phone recogniser.
     */
    fun startVoice() {
        if (_state.value.voice != Voice.IDLE) { trace("voice: mic button while listening → stop"); stopVoice(); return }
        val s = session
        val featureOk = s != null && s.ready && s.activeFeatures and Remote.Feature.VOICE != 0
        trace("voice: mic button (stream mode, upload=${if (_settings.value.voiceLive) "live" else "buffered"}, TV voice feature=$featureOk, active features=${s?.activeFeatures})")
        if (s == null || !s.ready) { usePhoneSpeech("not connected"); return }
        if (!featureOk) { usePhoneSpeech("the TV didn't enable the voice feature"); return }
        val ask = SystemClock.elapsedRealtime()
        voiceRequestAt = ask
        voiceSessionId = null
        startCapture()
        trace("voice: → KEYCODE_SEARCH, waiting ${VOICE_ACK_MS} ms for remote_voice_begin (mic already recording)")
        s.send(Remote.keyInject(Remote.Key.SEARCH))
        scope.launch {
            delay(VOICE_ACK_MS)
            if (voiceRequestAt == ask && voiceSessionId == null) {
                voiceRequestAt = 0
                trace("voice: ✗ no remote_voice_begin within ${VOICE_ACK_MS} ms → releasing mic")
                voiceStop = true; voiceJob?.join()
                usePhoneSpeech("no remote_voice_begin within ${VOICE_ACK_MS} ms")
            }
        }
    }

    /** Every remote_voice_begin is accepted (requested by us or started by the Shield / Assistant). */
    private fun onVoiceBegin(id: Int, pkg: String) {
        trace("voice: ← remote_voice_begin(session=$id, from ${pkg.ifBlank { "?" }}) → accepting")
        voiceRequestAt = 0
        val s = session ?: return
        if (voiceSessionId != null && voiceJob?.isActive == true) { trace("voice: already in session $voiceSessionId; duplicate begin($id) ignored"); return }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) { pendingVoiceId = id; trace("voice: RECORD_AUDIO not granted → asking"); _events.tryEmit(UiEvent.RequestMic); return }
        voiceSessionId = id
        dev.krist.shieldremote.protocol.VoiceStreamer({ s.send(it) }, { trace(it) }).begin(id)
        if (voiceJob?.isActive != true) startCapture() // the Shield started it on its own
        else if (_settings.value.voiceLive) flushLive()
    }

    fun micPermissionResult(granted: Boolean) {
        trace("voice: RECORD_AUDIO permission ${if (granted) "granted" else "denied"}")
        val id = pendingVoiceId; pendingVoiceId = null
        when {
            granted && id != null -> onVoiceBegin(id, "after permission")
            granted -> startVoice()
            else -> {
                if (id != null) send(Remote.voiceEnd(id))
                _state.update { it.copy(voice = Voice.IDLE) }
                usePhoneSpeech("microphone permission denied")
            }
        }
    }

    private val liveBuf = java.io.ByteArrayOutputStream()
    private var liveSent = 0
    /** Live upload (Settings): send whole 8 KiB blocks of what was actually recorded so far. */
    @Synchronized private fun flushLive(final: Boolean = false) {
        val id = voiceSessionId ?: return
        val s = session ?: return
        val all = liveBuf.toByteArray()
        while (all.size - liveSent >= Remote.VOICE_CHUNK_MIN_SIZE || (final && all.size > liveSent)) {
            val end = if (all.size - liveSent >= Remote.VOICE_CHUNK_MIN_SIZE) liveSent + Remote.VOICE_CHUNK_MIN_SIZE else all.size
            Remote.voiceChunks(all.copyOfRange(liveSent, end)).forEach { s.send(Remote.voicePayload(id, it)) }
            liveSent = end
        }
    }

    private fun startCapture() {
        voiceStop = false
        liveBuf.reset(); liveSent = 0
        _state.update { it.copy(voice = Voice.STARTING) }
        voiceJob = scope.launch {
            val live = _settings.value.voiceLive
            val cap = try {
                dev.krist.shieldremote.voice.VoiceCapture { trace(it) }.record(
                    shouldStop = { voiceStop || session?.isOpen != true },
                    endpointer = dev.krist.shieldremote.protocol.Endpointer(),
                    onStarted = { _state.update { it.copy(voice = Voice.LISTENING) } },
                    onPcm = { if (live) { synchronized(this@RemoteController) { liveBuf.write(it) }; flushLive() } },
                )
            } catch (e: Exception) {
                trace("voice: ✗ microphone: ${e.message ?: e.javaClass.simpleName}")
                voiceSessionId?.let { send(Remote.voiceEnd(it)); trace("voice: → remote_voice_end($it)") }
                voiceSessionId = null
                _state.update { it.copy(voice = Voice.IDLE) }
                _events.tryEmit(UiEvent.Toast("Microphone failed (${e.message}); using the phone recogniser"))
                usePhoneSpeech("mic error")
                return@launch
            }
            _state.update { it.copy(voice = Voice.IDLE) }
            trace("voice: captured ${cap.pcm.size} bytes (${cap.pcm.size / 16} ms) from ${cap.source}, ${cap.level}, ended: ${cap.reason}")
            runCatching { lastVoiceWav.writeBytes(dev.krist.shieldremote.protocol.Pcm.wav(cap.pcm)) }
            if (cap.level.peakDb < -45) {
                trace("voice: ⚠ almost silent recording (peak ${"%.1f".format(cap.level.peakDb)} dBFS)")
                _events.tryEmit(UiEvent.Toast("The microphone recorded almost nothing. Try Settings › Diagnostics › Test microphone."))
            }
            val id = voiceSessionId
            val s = session
            if (id == null || s == null) { trace("voice: no TV session for this recording; nothing sent"); return@launch }
            if (live) {
                flushLive(final = true)
                s.send(Remote.voiceEnd(id)); trace("voice: → remote_voice_end($id) (live, $liveSent bytes)")
            } else dev.krist.shieldremote.protocol.VoiceStreamer({ s.send(it) }, { trace(it) }).sendBuffered(id, cap.pcm)
            voiceSessionId = null
        }
    }

    /** Settings › Diagnostics › Test microphone: 3 s through the same capture path, then played back. */
    fun testMicrophone(onDone: (String) -> Unit) {
        scope.launch {
            val until = SystemClock.elapsedRealtime() + 3_000
            val msg = try {
                trace("voice: mic test: recording 3 s")
                val cap = dev.krist.shieldremote.voice.VoiceCapture { trace(it) }.record({ SystemClock.elapsedRealtime() > until },
                    dev.krist.shieldremote.protocol.Endpointer(noSpeechMs = 60_000, maxMs = 60_000, silenceMs = 60_000))
                lastVoiceWav.writeBytes(dev.krist.shieldremote.protocol.Pcm.wav(cap.pcm))
                trace("voice: mic test: ${cap.pcm.size} bytes from ${cap.source}, ${cap.level}")
                playPcm(cap.pcm)
                "Mic test (${cap.source}): ${cap.level}. Playing it back now."
            } catch (e: Exception) { "Mic test failed: ${e.message}" }
            onDone(msg)
        }
    }

    fun playLastVoice(): Boolean {
        val f = lastVoiceWav
        if (!f.exists()) return false
        scope.launch { playPcm(f.readBytes().copyOfRange(44, f.length().toInt())) }
        return true
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val t = android.media.AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size); t.play()
        Thread.sleep(pcm.size / 16L + 300); t.release()
    }

    private fun usePhoneSpeech(reason: String) {
        trace("voice: → phone speech recogniser ($reason)")
        _events.tryEmit(UiEvent.UsePhoneSpeech)
    }

    fun stopVoice() {
        voiceStop = true
        if (voiceJob?.isActive != true) { voiceRequestAt = 0; _state.update { it.copy(voice = Voice.IDLE) } }
    }

    @Volatile private var pendingSearch: Pair<String, Long>? = null

    /**
     * Phone-recognised speech -> TV. If a TV text box is open, type into it (delta typing); otherwise press
     * SEARCH and type into the box the TV opens. If none opens, the text waits ~30 s and is typed into the
     * next text box the TV focuses.
     */
    fun searchText(text: String) {
        if (text.isBlank()) { trace("voice(phone): nothing recognised"); return }
        trace("voice(phone): recognised \"$text\"")
        if (ime.fieldOpen) { trace("voice(phone): TV text box open → typing"); typeIntoTv(text); return }
        // No SEARCH press here: on the Shield it starts the Assistant, which grabs it as a voice session.
        val uri = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(text, "UTF-8")
        trace("voice(phone): no TV text box → app link $uri")
        pendingSearch = text to SystemClock.elapsedRealtime() + 30_000 // also typed if a box opens soon
        send(Remote.appLink(uri))
    }

    /** Called when the TV opens a text box: deliver a pending phone-voice search. */
    private fun deliverPendingSearch() {
        val (text, until) = pendingSearch ?: return
        pendingSearch = null
        if (SystemClock.elapsedRealtime() > until) { trace("voice(phone): pending text expired"); return }
        scope.launch { delay(150); typeIntoTv(text) }
    }

    private fun typeIntoTv(text: String) {
        scope.launch {
            val old = _state.value.ime.text
            _state.update { st -> st.copy(ime = st.ime.copy(text = text, tvVersion = st.ime.tvVersion + 1)) }
            imeEdit(old, text, text.length) // delta typing against the TV's value (replaces what's there)
            delay(600)
            imeEnter()
            trace("voice(phone): typed \"$text\" + Enter")
        }
    }

    // ---------------------------------------------------------------- devices

    private fun publishDevices() = _state.update { it.copy(devices = prefs.devices, device = deviceFlow.value) }

    fun selectDevice(id: String) {
        val dev = prefs.devices.firstOrNull { it.id == id } ?: return
        if (dev.id == deviceFlow.value?.id) return
        prefs.activeId = id
        queue.clear()
        everConnected = false
        deviceFlow.value = dev
        session?.close()
        blocked.value = false
        _state.update { it.copy(device = dev, link = Link.CONNECTING, volume = null, tvModel = "", currentApp = "", ime = ImeState(), detail = "") }
        wanted.value = true
        kick.trySend(Unit)
    }

    fun renameDevice(id: String, name: String) {
        val dev = prefs.devices.firstOrNull { it.id == id } ?: return
        val updated = dev.copy(name = name.trim().ifEmpty { dev.name })
        prefs.update(updated)
        if (deviceFlow.value?.id == id) deviceFlow.value = updated
        publishDevices()
    }

    fun forgetDevice(id: String) {
        val wasActive = deviceFlow.value?.id == id
        prefs.remove(id)
        if (wasActive) {
            session?.close()
            queue.clear()
            val next = prefs.activeDevice
            deviceFlow.value = next
            blocked.value = false
            everConnected = false
            _state.value = RemoteState(device = next, devices = prefs.devices, link = if (next == null) Link.NO_DEVICE else Link.CONNECTING)
            kick.trySend(Unit)
        } else publishDevices()
    }

    // ---------------------------------------------------------------- pairing

    suspend fun beginPairing(host: String) = withContext(Dispatchers.IO) {
        cancelPairingInternal()
        blocked.value = true
        session?.close()
        val client = PairingClient(certs, host, PAIRING_PORT)
        pairing = client
        try {
            client.start("Shield Remote (${Build.MODEL})", wifi?.socketFactory)
            client.serverName
        } catch (e: Exception) {
            cancelPairingInternal()
            throw e
        }
    }

    suspend fun finishPairing(code: String, host: String, name: String, serviceName: String?) = withContext(Dispatchers.IO) {
        val client = pairing ?: throw IllegalStateException("Pairing session expired, start again")
        client.finish(code) // throws InvalidCode without closing the session, so the user can retry
        cancelPairingInternal()
        val dev = prefs.upsert(host, name, serviceName)
        deviceFlow.value = dev
        everConnected = false
        queue.clear()
        _state.update { it.copy(device = dev, devices = prefs.devices, link = Link.CONNECTING, detail = "", volume = null, ime = ImeState()) }
        blocked.value = false
        wanted.value = true
        kick.trySend(Unit)
    }

    fun cancelPairing() {
        scope.launch {
            cancelPairingInternal()
            if (_state.value.link != Link.NEEDS_PAIRING) blocked.value = false
            kick.trySend(Unit)
        }
    }

    private fun cancelPairingInternal() { pairing?.close(); pairing = null }

    // ---------------------------------------------------------------- network

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi = network
                if (!_settings.value.reconnectOnWifi) return
                val s = session
                if (s != null && sessionNetwork != null && sessionNetwork != network) s.close()
                kick.trySend(Unit)
            }
            override fun onLost(network: Network) {
                if (wifi == network) {
                    wifi = null
                    if (sessionNetwork == network && _settings.value.reconnectOnWifi) session?.close()
                }
            }
        })
    }

    /** The Shield's DHCP address may change; find it again by its mDNS name. */
    private suspend fun rediscover(dev: SavedDevice) {
        val target = dev.serviceName ?: dev.name
        val found = CompletableDeferred<String>()
        val d = withContext(Dispatchers.Main) {
            Discovery(context).also { it.start({ tv -> if (tv.serviceName == target) found.complete(tv.host) }, {}) }
        }
        val host = withTimeoutOrNull(6_000) { found.await() }
        withContext(Dispatchers.Main) { d.stop() }
        if (host != null && host != dev.host) {
            val updated = dev.copy(host = host)
            prefs.update(updated)
            if (deviceFlow.value?.id == dev.id) deviceFlow.value = updated
            publishDevices()
            kick.trySend(Unit)
        }
    }
}
