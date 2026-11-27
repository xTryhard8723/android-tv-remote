package dev.krist.shieldremote

import dev.krist.shieldremote.protocol.VoiceStreamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamerTest {
    private fun field(b: ByteArray): Int {
        var v = 0; var shift = 0; var i = 0
        while (true) { val x = b[i++].toInt() and 0xFF; v = v or ((x and 0x7F) shl shift); if (x and 0x80 == 0) break; shift += 7 }
        return v ushr 3
    }

    private fun pcm(ms: Int, amp: Int): ByteArray {
        val n = 8 * ms; val b = ByteArray(n * 2)
        for (i in 0 until n) { val v = if (amp == 0) 0 else (if (i % 16 < 8) amp else -amp); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        return b
    }
    private fun reader(data: ByteArray): (ByteArray, Int, Int) -> Int { var pos = 0
        return { b, o, l -> val n = minOf(l, 1600, data.size - pos); if (n <= 0) 0 else { System.arraycopy(data, pos, b, o, n); pos += n; n } } }

    @Test fun acceptsSessionStreamsAndEndsAfterSilence() {
        val sent = mutableListOf<ByteArray>(); val log = mutableListOf<String>()
        val audio = pcm(1_000, 4_000) + pcm(3_000, 0) // 1 s speech then silence
        val r = VoiceStreamer({ sent += it }, { log += it }).streamSession(1, { reader(audio) }, { false })
        assertTrue("$r", r is VoiceStreamer.Result.Done)
        r as VoiceStreamer.Result.Done
        assertEquals("silence after speech", r.reason)
        val f = sent.map { field(it) }
        assertEquals(30, f.first()); assertEquals(32, f.last()); assertTrue(f.drop(1).dropLast(1).all { it == 31 })
        assertTrue("${r.bytes}", r.bytes in 40_000..49_152) // 2.5 s of 8 kHz PCM16 (16 000 B/s) in 8 KiB payloads
    }

    @Test fun noSpeechTimesOut() {
        val r = VoiceStreamer({}, {}).streamSession(1, { reader(pcm(10_000, 0)) }, { false }) as VoiceStreamer.Result.Done
        assertEquals("no speech for 8 s", r.reason)
    }

    @Test fun micFailureStillEndsTheTvSession() {
        val sent = mutableListOf<ByteArray>(); val log = mutableListOf<String>()
        val r = VoiceStreamer({ sent += it }, { log += it }).streamSession(7, { throw IllegalStateException("busy") }, { false })
        assertTrue(r is VoiceStreamer.Result.Failed)
        assertEquals(listOf(30, 32), sent.map { field(it) }) // voice_begin echo, voice_end
        assertTrue(log.any { it.contains("microphone failed") })
    }

    @Test fun shieldVolumeWithoutMaxShowsNoLevel() {
        assertEquals("", dev.krist.shieldremote.service.volumeSuffix(dev.krist.shieldremote.VolumeInfo(0, 0, false)))
        assertEquals("", dev.krist.shieldremote.service.volumeSuffix(dev.krist.shieldremote.VolumeInfo(12, 100, false)))
        assertEquals("", dev.krist.shieldremote.service.volumeSuffix(dev.krist.shieldremote.VolumeInfo(15, 15, false)))
        assertEquals(" · Muted", dev.krist.shieldremote.service.volumeSuffix(dev.krist.shieldremote.VolumeInfo(0, 0, true)))
    }
}
