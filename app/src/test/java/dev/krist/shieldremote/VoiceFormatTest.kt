package dev.krist.shieldremote

import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.protocol.Pcm
import dev.krist.shieldremote.protocol.VoiceStreamer
import dev.krist.shieldremote.protocol.bufferedPayloads
import dev.krist.shieldremote.ui.ShortcutIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFormatTest {
    @Test fun bufferedUploadFollowsLibraryChunking() {
        val pcm = ByteArray(57_000) { (it % 7).toByte() }
        val parts = VoiceStreamer.bufferedPayloads(pcm)
        // 57000 + 4096 trailing silence = 61096 -> 20480, 20480, 20136 (all within 8..20 KiB)
        assertEquals(listOf(20480, 20480, 20136), parts.map { it.size })
        assertTrue(parts.all { it.size in 8192..20480 })
        assertTrue(parts.last().takeLast(4096).all { it == 0.toByte() })
        assertEquals(pcm.toList(), (parts[0] + parts[1] + parts[2]).copyOf(pcm.size).toList()) // only real bytes, in order
    }

    @Test fun pcmHelpers() {
        val s16 = ByteArray(8) // samples 1000, 3000, -2000, -4000 (LE)
        intArrayOf(1000, 3000, -2000, -4000).forEachIndexed { i, v -> s16[2 * i] = v.toByte(); s16[2 * i + 1] = (v shr 8).toByte() }
        val h = Pcm.halve(s16)
        assertEquals(2000, Pcm.sample(h, 0)); assertEquals(-3000, Pcm.sample(h, 2))
        assertEquals(-96.0, Pcm.level(ByteArray(100)).peakDb, 0.01)
        assertEquals(-18.3, Pcm.level(s16).peakDb, 0.1) // 4000/32768
        val w = Pcm.wav(h)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals(44 + h.size, w.size)
    }

    @Test fun iconAutoMatch() {
        assertEquals("brand:netflix", ShortcutIcons.auto(Shortcut("Netflix", "", 0, "com.netflix.ninja")))
        assertEquals("brand:smarttube", ShortcutIcons.auto(Shortcut("SmartTube", "", 0, "com.teamsmart.videomanager.tv")))
        assertEquals("brand:youtube", ShortcutIcons.auto(Shortcut("YouTube", "", 0, "com.google.android.youtube.tv")))
        assertEquals("brand:wholphin", ShortcutIcons.auto(Shortcut("Wholphin", "", 0, "")))
        assertEquals("brand:hbo", ShortcutIcons.auto(Shortcut("HBO Max", "", 0, "com.wbd.stream")))
        assertEquals(null, ShortcutIcons.auto(Shortcut("Steam Link", "", 0, "com.valvesoftware.steamlink")))
        assertEquals("mi:movie", ShortcutIcons.resolve(Shortcut("Netflix", "", 0, "", icon = "mi:movie")))
    }
}
