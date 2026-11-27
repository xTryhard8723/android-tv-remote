package dev.krist.shieldremote

import dev.krist.shieldremote.data.Launch
import dev.krist.shieldremote.data.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPolicyTest {
    @Test fun invalidLinksAreNeverSent() {
        for (bad in listOf("smart tube://", "Wholphin://", "intent:#Intent;end", "market://launch?id=x", "no-scheme", " ", "foo bar://x"))
            assertFalse(bad, Launch.isValidLink(bad))
        for (ok in listOf("wholphin://", "netflix://", "https://www.youtube.com", "twitch://home")) assertTrue(ok, Launch.isValidLink(ok))
        assertEquals(emptyList<String>(), Launch.candidates(Shortcut("X", "smart tube://", 0, "com.example.x")))
    }
    @Test fun smartTubeNeverGetsALink() {
        for (sc in listOf(Shortcut("SmartTube", "smarttube://", 0, ""), Shortcut("Smart Tube", "", 0, "com.teamsmart.videomanager.tv"),
            Shortcut("ST", "x://", 0, "com.liskovsoft.smarttubetv.beta"), Shortcut("ST", "x://", 0, "com.liskovsoft.smarttubetv")))
            assertTrue(Launch.candidates(sc).isEmpty())
    }
    @Test fun rejectedLinksAreSkipped() {
        val sc = Shortcut("Wholphin", "wholphin://", 0, "com.github.damontecres.wholphin")
        assertEquals(listOf("wholphin://"), Launch.candidates(sc))
        assertTrue(Launch.candidates(sc, null, setOf("wholphin://")).isEmpty())
    }
}
