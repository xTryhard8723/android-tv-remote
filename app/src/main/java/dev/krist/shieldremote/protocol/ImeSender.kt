package dev.krist.shieldremote.protocol

/**
 * Loop-proof outbound typing that mirrors the phone field onto the TV field by sending DELTAS only.
 *
 * The Shield INSERTS the text of an ime_batch_edit at its cursor (it does not replace the field), so
 * sending the full phone text each time doubled it ("ttetest…test1test12"). We keep [tvText], the last
 * known content of the TV field (from the TV's own reports, updated optimistically after each send),
 * and on every user change send only: N × DEL (back to where the texts diverge) + the new characters.
 *
 * Only [userEdit] (a real keystroke) can lead to a send. Inbound TV messages may only [reset] (new
 * field / prefill) or [sync] (echo of the field value) the baseline; neither ever sends anything.
 */
class ImeSender(private val maxPerSecond: Int = 20, private val ackWaitMs: Long = 400, private val settleMs: Long = 300) {
    sealed class Out {
        /** Press DEL [dels] times, then insert [insert] via ime_batch_edit. */
        data class Delta(val dels: Int, val insert: String) : Out()
        data class Keys(val strokes: List<KeyTyping.Stroke>) : Out()
    }

    /** What we believe the TV field contains right now. */
    var tvText = ""; private set
    private var target: String? = null
    private val sends = ArrayDeque<Long>()
    var paused = false; private set
    private var awaitingAck = false
    private var sentCounter = 0
    private var sentAt = 0L

    val hasPending: Boolean get() = target != null && target != tvText

    fun userEdit(text: String) { target = text }

    /** New / refocused TV field (or prefill): adopt its text as the baseline, drop anything pending. */
    fun reset(fieldText: String) {
        tvText = fieldText; target = null; paused = false; sends.clear(); awaitingAck = false
    }

    /**
     * The TV reported its field value (ImeShowRequest / key-inject echo). Adopted as the truth unless it
     * predates our last send (counter not past the one we sent with) or is an intermediate state that
     * arrived right after a send. Returns true if adopted. Never sends.
     */
    fun sync(value: String, counter: Int, nowMs: Long): Boolean {
        if (awaitingAck) {
            if (counter in 1..sentCounter) return false
            if (value != tvText && nowMs - sentAt < settleMs) return false
        }
        tvText = value; awaitingAck = false
        return true
    }

    fun flush(nowMs: Long, useKeys: Boolean, fieldCounter: Int = 0): Out? {
        val t = target ?: return null
        if (paused || t == tvText) { target = null; return null }
        if (awaitingAck && nowMs - sentAt < ackWaitMs) return null // wait for the TV's echo; caller retries
        target = null
        while (sends.isNotEmpty() && nowMs - sends.first() > 1_000) sends.removeFirst()
        if (sends.size >= maxPerSecond) { paused = true; return null }
        var p = 0
        while (p < tvText.length && p < t.length && tvText[p] == t[p]) p++
        val dels = tvText.length - p
        val ins = t.substring(p)
        if (dels + ins.length > maxOf(tvText.length, t.length) + 5) { paused = true; return null }
        sends.addLast(nowMs)
        awaitingAck = true; sentCounter = fieldCounter; sentAt = nowMs
        if (!useKeys && dels > 0 && ins.isNotEmpty()) {
            // Mid-text edit: delete first, insert after the TV confirmed (its field counter moves on).
            tvText = t.substring(0, p); target = t
            return Out.Delta(dels, "")
        }
        tvText = t
        return if (useKeys) Out.Keys(List(dels) { KeyTyping.Stroke(KeyTyping.DEL) } + ins.mapNotNull { KeyTyping.strokeFor(it) })
        else Out.Delta(dels, ins)
    }
}

/**
 * Bug 2 guard: after a shortcut / key sequence / reconnect the TV re-reports whatever text box is
 * focused. That must not pop the phone keyboard (and can never send). It only auto-opens again after
 * [windowMs], or when a NEW field appears after the user pressed OK on the D-pad.
 */
class AutoOpenGate(private val windowMs: Long = 2_000) {
    private var since = Long.MIN_VALUE / 2
    private var okAt = Long.MIN_VALUE / 2
    fun suppress(nowMs: Long) { since = nowMs }
    fun userOk(nowMs: Long) { okAt = nowMs }
    fun allows(nowMs: Long, newField: Boolean): Boolean =
        nowMs - since >= windowMs || (newField && okAt > since)
}
