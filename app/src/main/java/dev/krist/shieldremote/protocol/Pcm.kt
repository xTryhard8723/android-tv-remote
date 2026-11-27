package dev.krist.shieldremote.protocol

import kotlin.math.log10
import kotlin.math.sqrt

/** Little-endian 16-bit mono PCM helpers (Android AudioRecord byte[] output is little-endian). */
object Pcm {
    fun sample(b: ByteArray, i: Int): Int = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()

    data class Level(val peakDb: Double, val rmsDb: Double) {
        override fun toString() = "peak %.1f dBFS, rms %.1f dBFS".format(peakDb, rmsDb)
    }

    fun level(b: ByteArray, off: Int = 0, len: Int = b.size): Level {
        var peak = 0; var sum = 0.0; var n = 0; var i = off
        while (i + 1 < off + len) { val s = sample(b, i); val a = kotlin.math.abs(s); if (a > peak) peak = a; sum += s.toDouble() * s; n++; i += 2 }
        fun db(x: Double) = if (x <= 0.0) -96.0 else 20 * log10(x / 32768.0)
        return Level(db(peak.toDouble()), db(if (n == 0) 0.0 else sqrt(sum / n)))
    }

    /** 16 kHz -> 8 kHz: average each pair (a simple low-pass) and keep one sample. */
    fun halve(b: ByteArray, len: Int = b.size): ByteArray {
        val pairs = len / 4
        val out = ByteArray(pairs * 2)
        for (p in 0 until pairs) {
            val v = (sample(b, p * 4) + sample(b, p * 4 + 2)) / 2
            out[p * 2] = v.toByte(); out[p * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** Standard 44-byte RIFF/WAVE header + data. */
    fun wav(pcm: ByteArray, rate: Int = 8000): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
