package dev.krist.shieldremote.protocol

import dev.krist.shieldremote.proto.Remote

/**
 * Everything between the phone text field and the TV's text box, free of Android so it can be tested
 * against a mock Shield. Inbound TV messages NEVER cause a send; only [userEdit] + [flush] do, and they
 * send deltas (DEL presses + inserted characters) against the TV's last reported value.
 */
class ImeBridge(
    private val clock: () -> Long,
    private val sendStroke: (KeyTyping.Stroke) -> Unit,
    private val sendBatch: (imeCounter: Int, fieldCounter: Int, text: String, cursor: Int) -> Unit,
    private val trace: (String) -> Unit,
    private val ui: Ui,
) {
    interface Ui {
        val panelVisible: Boolean
        val autoKeyboard: Boolean
        /** Show (or just refresh, if [show] is false) the panel with the TV field's text. */
        fun open(text: String, label: String, show: Boolean)
        fun close()
    }

    val sender = ImeSender()
    val gate = AutoOpenGate()
    @Volatile var imeCounter = 0; private set
    @Volatile var fieldCounter = 0; private set
    @Volatile var seen = false; private set          // TV reported live IME state this session
    /** A TV text box is focused right now (as last reported). */
    @Volatile var fieldOpen = false; private set
    @Volatile private var fieldFromKeyInject = false
    @Volatile private var lastLocalEdit = Long.MIN_VALUE / 2
    @Volatile private var lastSend = Long.MIN_VALUE / 2

    /** New session (first connect or reconnect): forget counters, never pop the keyboard right away. */
    fun sessionStarted() {
        seen = false; fieldOpen = false; fieldFromKeyInject = false; imeCounter = 0; fieldCounter = 0
        sender.reset("")
        gate.suppress(clock())
    }

    /** User pressed a shortcut / key sequence: the TV re-reporting its field must not pop the keyboard. */
    fun suppressAutoOpen() = gate.suppress(clock())
    fun userOk() = gate.userOk(clock())

    fun onKeyInject(msg: Remote.Msg.ImeKeyInject) {
        seen = true
        if (msg.appCounter != 0) imeCounter = msg.appCounter
        val f = msg.field ?: return
        fieldOpen = true
        fieldFromKeyInject = true
        val newField = f.counter != fieldCounter
        fieldCounter = f.counter
        if (newField || !ui.panelVisible) fromTv("ime_key_inject(text_field_status)", f, newField)
    }

    fun onBatchEdit(msg: Remote.Msg.ImeBatchEdit) {
        if (!seen) imeCounter = msg.imeCounter
        if (!fieldFromKeyInject && msg.fieldCounter != 0) fieldCounter = msg.fieldCounter
        val ownEcho = clock() - lastSend < 1_500
        if (!ownEcho && msg.fieldCounter > 0 && !ui.panelVisible && msg.text != null)
            fromTv("ime_batch_edit(field=${msg.fieldCounter})", Remote.TextField(msg.fieldCounter, msg.text, msg.start, msg.end), false)
    }

    fun onShowRequest(msg: Remote.Msg.ImeShowRequest) {
        val f = msg.field
        if (f == null) { fieldOpen = false; if (ui.panelVisible) { trace("IME close ← ime_show_request(no field)"); ui.close() }; return }
        seen = true
        fieldOpen = true
        // counter+1 is the TV acknowledging our own edit, not a new field
        val newField = f.counter != 0 && f.counter != fieldCounter && f.counter != fieldCounter + 1
        if (f.counter != 0) fieldCounter = f.counter
        fromTv("ime_show_request", f, newField)
    }

    private fun fromTv(trigger: String, f: Remote.TextField, newField: Boolean) {
        val now = clock()
        if (ui.panelVisible && now - lastLocalEdit < 3_000) {
            val adopted = sender.sync(f.value, f.counter, now)
            trace("IME echo ← $trigger: TV has \"${f.value.takeLast(30)}\"" + if (adopted) "" else " (stale, ignored)")
            return
        }
        sender.reset(f.value) // prefill: the baseline IS the TV text, so it can never be sent back
        val gateOk = gate.allows(now, newField)
        val show = (ui.autoKeyboard && gateOk) || ui.panelVisible
        trace("IME open ← $trigger" + when { show -> ""; !gateOk -> " (not popped: shortcut / reconnect just now)"; else -> " (auto-open off)" })
        ui.open(f.value, f.label, show)
    }

    /** The user opened the panel by hand with [text] already in it (= what the TV has). */
    fun userOpened(text: String) = sender.reset(text)

    /** A real keystroke in the phone field. */
    fun userEdit(new: String) { lastLocalEdit = clock(); sender.userEdit(new) }

    /** Sends at most one delta. Returns true if more is pending (caller flushes again ~100 ms later). */
    fun flush(useKeys: Boolean, onPaused: () -> Unit = {}): Boolean {
        val wasPaused = sender.paused
        when (val out = sender.flush(clock(), useKeys, fieldCounter)) {
            is ImeSender.Out.Delta -> {
                lastSend = clock()
                if (out.dels > 0) { trace("→ DEL ×${out.dels}"); repeat(out.dels) { sendStroke(KeyTyping.Stroke(KeyTyping.DEL)) } }
                if (out.insert.isNotEmpty()) {
                    trace("→ ime_batch_edit ime=$imeCounter field=$fieldCounter insert \"${out.insert.take(40)}\"")
                    sendBatch(imeCounter, fieldCounter, out.insert, sender.tvText.length - 1)
                }
            }
            is ImeSender.Out.Keys -> {
                lastSend = clock()
                trace("→ keys ×${out.strokes.size}")
                out.strokes.forEach(sendStroke)
            }
            null -> if (sender.paused && !wasPaused) { trace("! typing paused (rate/size cap)"); onPaused() }
        }
        return sender.hasPending
    }
}
