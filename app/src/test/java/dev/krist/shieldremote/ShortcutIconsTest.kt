package dev.krist.shieldremote

import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.ui.ShortcutIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved shortcut icon IDs must keep resolving, including auto-match for the default apps. */
class ShortcutIconsTest {
    @Test fun brandIdsAreStable() {
        val ids = ShortcutIcons.brands.map { it.id }
        assertEquals(
            listOf("netflix", "jellyfin", "wholphin", "plex", "youtube", "smarttube", "kodi", "disney", "prime", "spotify", "twitch", "hbo"),
            ids,
        )
        assertTrue("every brand must ship an official drawable", ShortcutIcons.brands.all { it.drawable != 0 })
    }

    @Test fun autoMatchesKnownAppsAndPrefersSmartTube() {
        fun auto(name: String, pkg: String = "") = ShortcutIcons.auto(Shortcut(name, "", 0xFF000000, pkg))
        assertEquals("brand:netflix", auto("Netflix", "com.netflix.ninja"))
        assertEquals("brand:jellyfin", auto("Jellyfin", "org.jellyfin.androidtv"))
        assertEquals("brand:wholphin", auto("Wholphin", "com.github.damontecres.wholphin"))
        assertEquals("brand:plex", auto("Plex", "com.plexapp.android"))
        assertEquals("brand:youtube", auto("YouTube", "com.google.android.youtube.tv"))
        assertEquals("brand:smarttube", auto("SmartTube", "org.smarttube.stable"))
        assertEquals("brand:smarttube", auto("Smart Tube Next"))
        assertEquals("brand:kodi", auto("Kodi", "org.xbmc.kodi"))
        assertEquals("brand:disney", auto("Disney+", "com.disney.disneyplus"))
        assertEquals("brand:prime", auto("Prime Video", "com.amazon.amazonvideo.livingroom"))
        assertEquals("brand:spotify", auto("Spotify", "com.spotify.tv.android"))
        assertEquals("brand:twitch", auto("Twitch"))
        assertEquals("brand:hbo", auto("Max"))
        assertEquals("brand:hbo", auto("HBO Max", "com.wbd.stream"))
        // SmartTube must win over YouTube when both names could match
        assertEquals("brand:smarttube", auto("SmartTube (YouTube client)"))
    }

    @Test fun savedBrandSpecsStillResolve() {
        ShortcutIcons.brands.forEach { b ->
            val s = Shortcut(b.id, "", 0xFF000000, icon = "brand:${b.id}")
            assertEquals("brand:${b.id}", ShortcutIcons.resolve(s))
        }
        val blank = Shortcut("Netflix", "", 0xFFE50914, "com.netflix.ninja")
        assertEquals("brand:netflix", ShortcutIcons.resolve(blank))
        val custom = Shortcut("Kids", "", 0xFF00AA00, icon = "mi:kids")
        assertEquals("mi:kids", ShortcutIcons.resolve(custom))
        assertNotEquals("brand:youtube", ShortcutIcons.auto(Shortcut("VLC", "", 0)))
    }
}
