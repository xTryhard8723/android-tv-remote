package dev.krist.shieldremote

import dev.krist.shieldremote.protocol.ImeSender
import dev.krist.shieldremote.protocol.ImeSender.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "twwt" flood: inbound echoes must never produce outbound typing. */
class ImeSenderTest {
    /** Simulates a TV that echoes every batch edit (sometimes text=null) and re-reports the field. */
    private fun runSession(edits: List<String>, echoText: (String) -> String?): Int {
        val s = ImeSender()
        var now = 0L
        var outbound = 0
        s.reset("")
        for (e in edits) {
            s.userEdit(e)
            now += 150
            now += 400
            val out = s.flush(now, useKeys = false)
            if (out != null) outbound++
            // TV echo arrives: the controller only ever calls reset() on a NEW field, never on echoes,
            // and inbound handling never calls userEdit/flush. Extra flushes (timers) must send nothing.
            echoText(e)
            repeat(10) { now += 10; if (s.flush(now, useKeys = false) != null) outbound++ }
        }
        return outbound
    }

    @Test fun echoesNeverCauseSends() {
        val edits = listOf("t", "tw", "twe", "twee", "tweet")
        assertEquals(edits.size, runSession(edits) { it })
        assertEquals(edits.size, runSession(edits) { null })
    }

    @Test fun sameTextIsNeverResent() {
        val s = ImeSender(); s.reset("")
        s.userEdit("abc"); assertTrue(s.flush(0, false) is Out.Delta)
        s.userEdit("abc"); assertNull(s.flush(500, false))
        assertNull(s.flush(900, false))
    }

    @Test fun rapidEditsAreCoalesced() {
        val s = ImeSender(); s.reset("")
        listOf("a", "ab", "abc").forEach { s.userEdit(it) }
        assertEquals(Out.Delta(0, "abc"), s.flush(100, false))
        assertNull(s.flush(200, false))
    }

    @Test fun keyFallbackTypesOnlyTheDelta() {
        val s = ImeSender(); s.reset("tw")
        s.userEdit("twe")
        val out = s.flush(0, useKeys = true) as Out.Keys
        assertEquals(1, out.strokes.size)
        s.userEdit("tw")
        assertEquals(1, (s.flush(500, useKeys = true) as Out.Keys).strokes.size) // one DEL
    }

    @Test fun rateCapPausesInsteadOfFlooding() {
        val s = ImeSender(maxPerSecond = 20); s.reset("")
        var sent = 0
        for (i in 1..100) { s.userEdit("x".repeat(i)); s.sync("x".repeat(i - 1), 1000 + i, i.toLong()); if (s.flush(i.toLong(), false) != null) sent++ }
        assertEquals(20, sent)
        assertTrue(s.paused)
        s.reset("") // new field unpauses
        s.userEdit("y"); assertTrue(s.flush(5_000, false) != null)
    }

    /** Mock Shield: inserts batch text at its cursor (end), DEL deletes, echoes counter+1 with the full value. */
    private class AppendingTv { var value = ""; var counter = 22
        fun apply(o: Out?): Boolean { when (o) { is Out.Delta -> { value = value.dropLast(o.dels) + o.insert; counter++ }; is Out.Keys -> {}; null -> return false }; return true } }

    @Test fun exactUserLogNoLongerDoubles() {
        // 17:57:53 log: TV echoes after each edit; the old code sent "test1","test12","test123" -> appended.
        val tv = AppendingTv(); val s = ImeSender(); s.reset(tv.value)
        var now = 0L
        for (w in listOf("t", "te", "tes", "test", "test1", "test12", "test123")) {
            s.userEdit(w)
            var guard = 0
            while (s.hasPending && guard++ < 10) { now += 100; if (tv.apply(s.flush(now, false, tv.counter))) { now += 40; s.sync(tv.value, tv.counter, now) } }
            assertEquals(w, tv.value)
        }
        // stale echo (counter not past the one we sent with) must not rewind the baseline
        s.userEdit("test1234"); tv.apply(s.flush(now + 500, false, tv.counter))
        assertTrue(!s.sync("test12", tv.counter - 1, now + 510)); assertEquals("test1234", s.tvText)
    }

    @Test fun deletionsAndMidEditsMirrorExactly() {
        val tv = AppendingTv(); val s = ImeSender(); s.reset("")
        var now = 0L
        for (w in listOf("hello", "hell", "he", "hXe", "hXey", "abc", "", "z")) {
            s.userEdit(w)
            var guard = 0
            while (s.hasPending && guard++ < 10) { now += 500; if (tv.apply(s.flush(now, false, tv.counter))) s.sync(tv.value, tv.counter, now + 30) }
            assertEquals(w, tv.value)
        }
    }

    @Test fun prefillAndEchoesNeverSend() {
        // Bug 2: TV box already has "stark"; prefill + repeated echoes + keyboard re-commit -> zero sends.
        val s = ImeSender(); s.reset("stark")
        var sends = 0
        repeat(20) { i ->
            s.sync("stark", 30 + i, i * 100L)
            s.userEdit("stark") // keyboard re-commits the prefilled text
            if (s.flush(i * 100L + 50, false, 30 + i) != null) sends++
        }
        assertEquals(0, sends)
    }

    @Test fun autoOpenGateSuppressesAfterShortcut() {
        val g = dev.krist.shieldremote.protocol.AutoOpenGate(2_000)
        assertTrue(g.allows(10_000, false))
        g.suppress(10_000)
        assertTrue(!g.allows(10_500, true))      // reconnect re-reports the old field: no popup
        assertTrue(!g.allows(11_500, false))
        g.userOk(11_000)
        assertTrue(g.allows(11_200, true))       // user pressed OK into a NEW field: popup
        assertTrue(g.allows(12_100, false))      // window over
    }
}
