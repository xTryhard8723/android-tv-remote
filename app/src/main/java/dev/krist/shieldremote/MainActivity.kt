package dev.krist.shieldremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.ui.DevicesSheet
import dev.krist.shieldremote.ui.Haptics
import dev.krist.shieldremote.ui.MoreKeysSheet
import dev.krist.shieldremote.ui.PairingScreen
import dev.krist.shieldremote.ui.RemoteActions
import dev.krist.shieldremote.ui.RemoteScreen
import dev.krist.shieldremote.ui.SettingsScreen
import dev.krist.shieldremote.ui.SettingsActions
import dev.krist.shieldremote.ui.Palette
import dev.krist.shieldremote.ui.LONG_PRESS_MS
import dev.krist.shieldremote.ui.REPEAT_EVERY_MS
import dev.krist.shieldremote.ui.REPEAT_START_MS
import dev.krist.shieldremote.ui.TOUCHPAD_SENSITIVITY
import dev.krist.shieldremote.data.Settings
import android.view.WindowManager
import dev.krist.shieldremote.ui.ShieldTheme
import dev.krist.shieldremote.ui.ShortcutsSheet

private enum class SheetKind { NONE, MORE, DEVICES, SHORTCUTS, DEBUG }

class MainActivity : ComponentActivity() {
    private val controller get() = (application as App).controller

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Copies a picked gallery image into app storage as a 256 px centre-cropped square PNG; returns its path. */
    private fun copyIcon(uri: android.net.Uri): String? = runCatching {
        val src = contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }!!
        val side = minOf(src.width, src.height)
        val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = android.graphics.Bitmap.createScaledBitmap(sq, 256, 256, true)
        val dir = java.io.File(filesDir, "icons").apply { mkdirs() }
        val f = java.io.File(dir, "icon_${System.currentTimeMillis()}.png")
        f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        f.absolutePath
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val settings by controller.settings.collectAsStateWithLifecycle()
            // Appearance + timing settings take effect immediately.
            Palette.apply(settings.accent, settings.amoled)
            LONG_PRESS_MS = settings.longPressMs.toLong()
            REPEAT_EVERY_MS = settings.repeatMs.toLong()
            REPEAT_START_MS = maxOf(250L, settings.longPressMs - 70L)
            TOUCHPAD_SENSITIVITY = settings.touchpadSensitivity
            Haptics.level = settings.haptics; Haptics.sound = settings.sound
            volumeKeys = settings.volumeKeys
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ShieldTheme(settings.textScale) {
                val state by controller.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    if (uri != null) runCatching {
                        contentResolver.openOutputStream(uri)!!.use { it.write(controller.prefs.exportJson().toByteArray()) }
                    }.onSuccess { Toast.makeText(this, "Settings exported", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show() }
                }
                var shortcutsVersion by remember { mutableIntStateOf(0) }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
                        val ok = text != null && controller.importSettings(text)
                        if (ok) shortcutsVersion++
                        Toast.makeText(this, if (ok) "Settings imported" else "That file isn't an Android TV Remote export", Toast.LENGTH_SHORT).show()
                    }
                }
                var pairing by remember { mutableStateOf(false) }
                var repairHost by remember { mutableStateOf<String?>(null) }
                val touchpad = settings.touchpad
                var sheet by remember { mutableStateOf(SheetKind.NONE) }
                var editIndex by remember { mutableStateOf<Int?>(null) }
                var shortcuts by remember(shortcutsVersion) { mutableStateOf(controller.prefs.shortcuts) }
                var voiceStartedByPress by remember { mutableStateOf(false) }

                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                var imagePicked by remember { mutableStateOf<((String) -> Unit)?>(null) }
                val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val cb = imagePicked; imagePicked = null
                    if (uri != null && cb != null) copyIcon(uri)?.let(cb) ?: Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
                }
                val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                    val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    controller.trace("voice(phone): recogniser returned resultCode=${res.resultCode}, text=${text?.let { "\"$it\"" } ?: "none"}")
                    if (!text.isNullOrBlank()) controller.searchText(text)
                    else if (res.resultCode != RESULT_CANCELED) Toast.makeText(this, "Didn't catch that, tap the mic and try again", Toast.LENGTH_SHORT).show()
                }
                fun phoneSpeech() {
                    // The system speech recogniser with its own visible "Listening…" UI (Google / Samsung).
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search on ${controller.state.value.device?.name ?: "Android TV"}")
                    controller.trace("voice(phone): opening speech recogniser (available=${android.speech.SpeechRecognizer.isRecognitionAvailable(this)})")
                    runCatching { speechLauncher.launch(i) }.onFailure {
                        controller.trace("voice(phone): ✗ no recogniser activity: ${it.javaClass.simpleName}")
                        Toast.makeText(this, "No speech recogniser on this phone", Toast.LENGTH_SHORT).show()
                    }
                }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    controller.micPermissionResult(ok)
                }

                LaunchedEffect(Unit) {
                    controller.events.collect { e ->
                        when (e) {
                            is UiEvent.Toast -> Toast.makeText(this@MainActivity, e.text, Toast.LENGTH_SHORT).show()
                            UiEvent.UsePhoneSpeech -> phoneSpeech()
                            UiEvent.RequestMic -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            is UiEvent.EditShortcut -> { editIndex = controller.prefs.shortcuts.indexOf(e.shortcut).takeIf { it >= 0 }; sheet = SheetKind.SHORTCUTS }
                        }
                    }
                }
                LaunchedEffect(state.device != null) {
                    if (state.device != null && Build.VERSION.SDK_INT >= 33 && !controller.prefs.askedNotifications && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
                        controller.prefs.askedNotifications = true
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (state.device == null || pairing) {
                    BackHandler(enabled = pairing && state.device != null) { pairing = false; controller.cancelPairing() }
                    PairingScreen(
                        controller = controller,
                        canGoBack = state.device != null,
                        preselectHost = repairHost,
                        onDone = { pairing = false; repairHost = null; (application as App).startKeepAliveService() },
                        onBack = { pairing = false; repairHost = null; controller.cancelPairing() },
                    )
                } else {
                    BackHandler(enabled = state.ime.visible || showSettings) { if (state.ime.visible) controller.closeKeyboard() else showSettings = false }
                    val actions = object : RemoteActions {
                        override fun short(code: Int) = controller.key(code)
                        override fun power(code: Int) = controller.power(code)
                        override fun down(code: Int) = controller.keyDown(code)
                        override fun up(code: Int) = controller.keyUp(code)
                        override fun launch(shortcut: dev.krist.shieldremote.data.Shortcut) = controller.launchShortcut(shortcut)
                        override fun imeEdit(old: String, new: String, selectionEnd: Int) = controller.imeEdit(old, new, selectionEnd)
                        override fun imeEnter() = controller.imeEnter()
                        override fun openKeyboard() = controller.openKeyboard()
                        override fun closeKeyboard() = controller.closeKeyboard()
                        override fun reconnect() = controller.reconnectNow()
                        override fun pairNew() {
                            repairHost = if (state.link == Link.NEEDS_PAIRING) state.device?.host else null
                            pairing = true
                        }
                        override fun setTouchpad(enabled: Boolean) = controller.updateSettings { it.copy(touchpad = enabled) }
                        override fun voicePress() {
                            voiceStartedByPress = false
                            val st = controller.state.value
                            when {
                                st.voice != Voice.IDLE -> { controller.trace("voice: mic button → stop"); controller.stopVoice() }
                                !controller.settings.value.voiceStreaming -> { controller.trace("voice: mic button (phone recogniser mode)"); phoneSpeech() }
                                !hasPerm(Manifest.permission.RECORD_AUDIO) -> { controller.trace("voice: mic button, RECORD_AUDIO not granted → asking"); micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                else -> { controller.startVoice(); voiceStartedByPress = true }
                            }
                        }
                        override fun voiceRelease(heldMs: Long) {
                            // Hold = push-to-talk; a quick tap keeps listening until tapped again / TV ends it.
                            if (voiceStartedByPress && heldMs > 600) controller.stopVoice()
                        }
                        override fun openDevices() { sheet = SheetKind.DEVICES }
                        override fun openSettings() { showSettings = true }
                        override fun openMoreKeys() { sheet = SheetKind.MORE }
                        override fun editShortcuts() { sheet = SheetKind.SHORTCUTS }
                    }
                    if (showSettings) {
                        SettingsScreen(settings, state.devices, state.tvModel, appVersion(), object : SettingsActions {
                            override fun update(f: (Settings) -> Settings) = controller.updateSettings(f)
                            override fun back() { showSettings = false }
                            override fun openNotificationSettings() {
                                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
                            }
                            override fun manageShortcuts() { sheet = SheetKind.SHORTCUTS }
                            override fun manageDevices() { sheet = SheetKind.DEVICES }
                            override fun pairNew() { showSettings = false; repairHost = null; pairing = true }
                            override fun diagnostics() { sheet = SheetKind.DEBUG }
                            override fun testMicrophone() {
                                if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
                                Toast.makeText(this@MainActivity, "Recording 3 s… speak now", Toast.LENGTH_SHORT).show()
                                controller.testMicrophone { msg -> runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } }
                            }
                            override fun playLastVoice() {
                                if (!controller.playLastVoice()) Toast.makeText(this@MainActivity, "No voice search recorded yet", Toast.LENGTH_SHORT).show()
                            }
                            override fun exportSettings() { exportLauncher.launch("android-tv-remote-settings.json") }
                            override fun importSettings() { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                            override fun resetSettings() { controller.resetSettings(); Toast.makeText(this@MainActivity, "Settings reset", Toast.LENGTH_SHORT).show() }
                        })
                    } else {
                        RemoteScreen(state, touchpad, shortcuts, actions, settings)
                    }
                    val close = { sheet = SheetKind.NONE }
                    when (sheet) {
                        SheetKind.MORE -> MoreKeysSheet(actions, close)
                        SheetKind.DEVICES -> DevicesSheet(
                            state.devices, state.device?.id,
                            onSelect = controller::selectDevice, onRename = controller::renameDevice,
                            onForget = controller::forgetDevice, onAdd = { repairHost = null; pairing = true }, onDismiss = close,
                        )
                        SheetKind.SHORTCUTS -> ShortcutsSheet(shortcuts, onChange = { shortcuts = it; controller.prefs.shortcuts = it }, onDismiss = { editIndex = null; close() }, startEditing = editIndex,
                            onPickImage = { cb -> imagePicked = cb; photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onTest = { controller.launchShortcut(it, test = true) }, onRecordKey = controller::key,
                            methodOf = { dev.krist.shieldremote.data.Launch.describe(it, controller.prefs.launchMethod(it.packageName)) })
                        SheetKind.DEBUG -> {
                            val lines by controller.trace.collectAsState()
                            dev.krist.shieldremote.ui.DebugSheet(lines, close)
                        }
                        SheetKind.NONE -> {}
                    }
                }
            }
        }
    }

    @Volatile private var volumeKeys = true

    private fun appVersion(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
    }.getOrDefault("")

    /** Phone volume rocker controls the TV while the app is open (Settings › Input). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Remote.Key.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Remote.Key.VOLUME_DOWN
            else -> return super.dispatchKeyEvent(event)
        }
        if (!volumeKeys || controller.state.value.device == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            controller.key(code) // includes auto-repeat events while held
            if (event.repeatCount == 0) Haptics.play(window.decorView, Haptics.Kind.PRESS)
        }
        return true
    }
}
