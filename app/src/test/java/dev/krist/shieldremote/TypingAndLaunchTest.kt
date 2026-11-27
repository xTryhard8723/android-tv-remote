package dev.krist.shieldremote

import dev.krist.shieldremote.data.Launch
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.proto.ProtoWriter
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.protocol.KeyTyping
import dev.krist.shieldremote.protocol.KeyTyping.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TypingAndLaunchTest {
    @Test fun keyMapping() {
        assertEquals(Stroke(29), KeyTyping.strokeFor('a'))
        assertEquals(Stroke(54), KeyTyping.strokeFor('z'))
        assertEquals(Stroke(29, true), KeyTyping.strokeFor('A'))
        assertEquals(Stroke(7), KeyTyping.strokeFor('0'))
        assertEquals(Stroke(16), KeyTyping.strokeFor('9'))
        assertEquals(Stroke(62), KeyTyping.strokeFor(' '))
        assertEquals(Stroke(8, true), KeyTyping.strokeFor('!'))
        assertEquals(Stroke(76, true), KeyTyping.strokeFor('?'))
        assertEquals(Stroke(77), KeyTyping.strokeFor('@'))
        assertEquals(null, KeyTyping.strokeFor('ž'))
    }

    @Test fun diffAppendsDeletesAndReplaces() {
        assertEquals(listOf(Stroke(29 + 2)), KeyTyping.diff("ab", "abc"))
        assertEquals(listOf(Stroke(67)), KeyTyping.diff("abc", "ab"))
        assertEquals(listOf(Stroke(67), Stroke(67), Stroke(29 + 23), Stroke(29 + 24)), KeyTyping.diff("abc", "axy"))
        assertEquals(emptyList<Stroke>(), KeyTyping.diff("same", "same"))
        assertEquals(List(4) { Stroke(67) }, KeyTyping.diff("test", ""))
    }

    @Test fun imeKeyInjectWithFieldParsesLiveCounters() {
        // remote_ime_key_inject { app_info { counter=37 app_package="pkg" } text_field_status { counter_field=12 value="hi" start=2 end=2 label="Search" } }
        val payload = ProtoWriter().messageField(20) {
            messageField(1) { intField(1, 37); stringField(12, "pkg") }
            messageField(2) { intField(1, 12); stringField(2, "hi"); intField(3, 2); intField(4, 2); stringField(6, "Search") }
        }.toByteArray()
        val m = Remote.parse(payload) as Remote.Msg.ImeKeyInject
        assertEquals(37, m.appCounter); assertEquals("pkg", m.appPackage)
        assertNotNull(m.field); val f = m.field!!
        assertEquals(12, f.counter); assertEquals("hi", f.value); assertEquals("Search", f.label)
    }

    @Test fun launchCandidatesOrder() {
        val w = Shortcut("Wholphin", "", 0, "com.github.damontecres.wholphin")
        assertEquals(listOf("wholphin://"), Launch.candidates(w))   // deep link only: no market://, no intent:
        val n = Shortcut("Netflix", "netflix://", 0, "com.netflix.ninja")
        assertEquals(listOf("netflix://"), Launch.candidates(n))
        val unknown = Shortcut("X", "", 0, "com.example.tv")
        assertEquals(emptyList<String>(), Launch.candidates(unknown))  // no market:// (it disconnects the Shield)
        val old = Shortcut("Spotify", "market://launch?id=com.spotify.tv.android", 0)
        assertEquals("com.spotify.tv.android", old.packageName)
        assertEquals(listOf("https://x"), Launch.candidates(Shortcut("X", "https://x", 0)))
    }

    @Test fun packageTypoIsCorrected() {
        assertEquals("com.github.damontecres.wholphin", Launch.correctPackage("com.github.damonteceres.wholphin"))
        assertEquals("com.github.damontecres.wholphin", Launch.correctPackage("com.github.damonteceres.wholphin "))
        assertEquals("com.example.tv", Launch.correctPackage("com.example.tv"))
    }

    @Test fun keyMacroParses() {
        val st = dev.krist.shieldremote.data.KeyMacro.parse("HOME, wait500, DOWN, RIGHT x3, OK")!!
        assertEquals(7, st.size)
        assertEquals(dev.krist.shieldremote.data.KeyMacro.Step.Key(3), st[0])
        assertEquals(dev.krist.shieldremote.data.KeyMacro.Step.Wait(500), st[1])
        assertEquals(dev.krist.shieldremote.data.KeyMacro.Step.Key(23), st[6])
        assertEquals(null, dev.krist.shieldremote.data.KeyMacro.parse("HOME, JUMP"))
    }
}
