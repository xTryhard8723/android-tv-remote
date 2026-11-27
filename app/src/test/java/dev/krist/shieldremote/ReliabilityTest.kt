package dev.krist.shieldremote

import dev.krist.shieldremote.protocol.Backoff
import dev.krist.shieldremote.protocol.CommandQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    @Test fun backoffIsImmediateThenExponentialCapped() {
        assertEquals(0L, Backoff.delayFor(0))
        assertEquals(0L, Backoff.delayFor(1))
        assertEquals(250L, Backoff.delayFor(2))
        assertEquals(500L, Backoff.delayFor(3))
        assertEquals(1000L, Backoff.delayFor(4))
        assertEquals(5000L, Backoff.delayFor(10))
        assertEquals(5000L, Backoff.delayFor(1000))
        for (i in 0..100) assertTrue(Backoff.delayFor(i) <= Backoff.CAP_MS)
    }

    @Test fun queueKeepsOrderAndExpiresOldPresses() {
        val q = CommandQueue(maxItems = 3, maxAgeMs = 1000)
        q.add(byteArrayOf(1), 0); q.add(byteArrayOf(2), 500); q.add(byteArrayOf(3), 900); q.add(byteArrayOf(4), 1000)
        assertEquals(3, q.size())
        val out = q.drain(1600)
        assertEquals(listOf(3, 4), out.map { it[0].toInt() })
        assertEquals(0, q.size())
    }
}
