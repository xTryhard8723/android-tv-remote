package dev.krist.shieldremote.protocol

/** Reconnect delays: first retry immediately, then exponential, capped at 5 s. */
object Backoff {
    const val CAP_MS = 5_000L
    fun delayFor(attempt: Int): Long = when {
        attempt <= 1 -> 0L
        else -> minOf(CAP_MS, 250L shl minOf(attempt - 2, 10))
    }
}

/**
 * Holds key presses made while the link is (re)connecting so they are delivered once the
 * session is ready instead of being dropped. Old entries expire so a stale burst of
 * presses never fires seconds later.
 */
class CommandQueue(private val maxItems: Int = 40, private val maxAgeMs: Long = 4_000) {
    private data class Item(val at: Long, val payload: ByteArray)
    private val items = ArrayDeque<Item>()

    @Synchronized fun add(payload: ByteArray, now: Long) {
        items.addLast(Item(now, payload))
        while (items.size > maxItems) items.removeFirst()
    }

    @Synchronized fun drain(now: Long): List<ByteArray> {
        val out = items.filter { now - it.at <= maxAgeMs }.map { it.payload }
        items.clear()
        return out
    }

    @Synchronized fun size() = items.size
    @Synchronized fun clear() = items.clear()
}
