package dev.krist.shieldremote.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.krist.shieldremote.protocol.Endpointer
import dev.krist.shieldremote.protocol.Pcm

/**
 * Microphone capture for the Shield's Assistant: records at 16 kHz (supported everywhere; Samsung's 8 kHz
 * path can be very quiet) from VOICE_RECOGNITION, falls back to MIC if that can't start or delivers pure
 * zeros, and returns 8 kHz 16-bit mono little-endian PCM made only of bytes actually read.
 */
class VoiceCapture(private val trace: (String) -> Unit) {
    data class Captured(val pcm: ByteArray, val reason: String, val level: Pcm.Level, val source: String)

    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { trace("voice: mic: 16 kHz not supported ($min)"); return null }
        val r = runCatching { AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32 * 1024)) }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); return null }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null }
        return r
    }

    /** Blocking. [onStarted] fires once the mic runs; [onPcm] gets every 8 kHz block (for live mode). */
    fun record(shouldStop: () -> Boolean, endpointer: Endpointer, onStarted: () -> Unit = {}, onPcm: (ByteArray) -> Unit = {}): Captured {
        var srcName = "VOICE_RECOGNITION"
        var rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (rec == null) { trace("voice: mic: VOICE_RECOGNITION failed, trying MIC"); srcName = "MIC"; rec = open(MediaRecorder.AudioSource.MIC) }
        if (rec == null) throw IllegalStateException("microphone could not start (in use by another app?)")
        trace("voice: mic started OK ($srcName, 16 kHz → 8 kHz mono PCM16 LE)")
        onStarted()
        val out = java.io.ByteArrayOutputStream()
        val block = ByteArray(3_200) // 100 ms at 16 kHz
        var reason = "stopped"
        var sinceLog = java.io.ByteArrayOutputStream()
        var allZeroMs = 0
        try {
            while (true) {
                if (shouldStop()) { reason = "stopped"; break }
                val n = rec!!.read(block, 0, block.size)
                if (n < 0) { trace("voice: ✗ mic read error $n"); reason = "mic error $n"; break }
                if (n == 0) continue
                val pcm8 = Pcm.halve(block, n)
                // Samsung quirk guard: a source that only delivers digital silence -> switch to MIC once.
                if (srcName == "VOICE_RECOGNITION" && Pcm.level(pcm8).peakDb <= -95.0) {
                    allZeroMs += 100
                    if (allZeroMs >= 800 && out.size() <= 8 * 2 * 900) {
                        trace("voice: mic: VOICE_RECOGNITION gives pure zeros → switching to MIC")
                        rec.stop(); rec.release(); rec = open(MediaRecorder.AudioSource.MIC) ?: throw IllegalStateException("MIC source failed")
                        srcName = "MIC"; allZeroMs = 0; continue
                    }
                } else allZeroMs = 0
                out.write(pcm8); sinceLog.write(pcm8); onPcm(pcm8)
                if (sinceLog.size() >= 8_000) { trace("voice: level ${Pcm.level(sinceLog.toByteArray())}"); sinceLog = java.io.ByteArrayOutputStream() }
                endpointer.feed(pcm8, 0, pcm8.size)?.let { reason = it }
                if (endpointer.done) break
            }
        } finally { runCatching { rec?.stop() }; runCatching { rec?.release() } }
        val pcm = out.toByteArray()
        return Captured(pcm, reason, Pcm.level(pcm), srcName)
    }
}
