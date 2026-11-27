package dev.krist.shieldremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.krist.shieldremote.data.Prefs
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.ui.IconPicker
import dev.krist.shieldremote.ui.Palette
import dev.krist.shieldremote.ui.ShieldTheme
import dev.krist.shieldremote.ui.ShortcutIcon
import dev.krist.shieldremote.ui.ShortcutIcons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h891dp-night-xxhdpi")
class ShortcutIconScreenshotTest {
    private val library = listOf(
        Shortcut("Netflix", "netflix://", 0xFFE50914, "com.netflix.ninja"),
        Shortcut("Jellyfin", "", 0xFF00A4DC, "org.jellyfin.androidtv"),
        Shortcut("Wholphin", "wholphin://", 0xFF5B2FE0, "com.github.damontecres.wholphin"),
        Shortcut("Plex", "plex://", 0xFFE5A00D, "com.plexapp.android"),
        Shortcut("YouTube", "https://www.youtube.com", 0xFFFF0033, "com.google.android.youtube.tv"),
        Shortcut("SmartTube", "", 0xFFB71C1C, "org.smarttube.stable"),
        Shortcut("Kodi", "", 0xFF17B2E7, "org.xbmc.kodi"),
        Shortcut("Disney+", "https://www.disneyplus.com", 0xFF113CCF, "com.disney.disneyplus"),
        Shortcut("Prime Video", "https://app.primevideo.com", 0xFF00A8E1, "com.amazon.amazonvideo.livingroom"),
        Shortcut("Spotify", "", 0xFF1DB954, "com.spotify.tv.android"),
        Shortcut("Twitch", "", 0xFF9146FF),
        Shortcut("HBO Max", "", 0xFF002BE7, "com.wbd.stream"),
    )

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun libraryAndPicker() {
        captureRoboImage("build/screenshots/shortcut_icons_preview.png") {
            ShieldTheme {
                Column(
                    Modifier
                        .requiredHeight(820.dp)
                        .background(Palette.Bg)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Shortcuts", color = Palette.Text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        library.forEach { ShortcutIcon(it, 60.dp) }
                    }
                    Text("Default bar", color = Palette.TextDim, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Prefs.DEFAULT_SHORTCUTS.forEach { ShortcutIcon(it, 60.dp) }
                    }
                    IconPicker(library[0], "brand:netflix", {}, {})
                    Text("${ShortcutIcons.brands.size} official logos", color = Palette.TextDim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
