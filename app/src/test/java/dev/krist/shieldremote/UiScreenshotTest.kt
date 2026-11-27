package dev.krist.shieldremote

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import dev.krist.shieldremote.data.Prefs
import dev.krist.shieldremote.data.SavedDevice
import dev.krist.shieldremote.net.DiscoveredTv
import dev.krist.shieldremote.ui.DevicesSheet
import dev.krist.shieldremote.ui.KeySink
import dev.krist.shieldremote.ui.MoreKeysSheet
import dev.krist.shieldremote.ui.PairingContent
import dev.krist.shieldremote.ui.RemoteActions
import dev.krist.shieldremote.ui.RemoteScreen
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.unit.dp
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.ui.IconPicker
import dev.krist.shieldremote.ui.Palette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import dev.krist.shieldremote.ui.ShieldTheme
import dev.krist.shieldremote.ui.ShortcutsSheet
import org.junit.Test
import dev.krist.shieldremote.data.Settings
import dev.krist.shieldremote.ui.SettingsScreen
import dev.krist.shieldremote.ui.SettingsActions
import dev.krist.shieldremote.ui.DebugSheet
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen at Galaxy S25 Ultra size (≈412×891 dp) to app/build/screenshots, once with the
 * system in dark mode and once in light mode: the app must look identical (always dark, light text).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
abstract class UiShots(private val mode: String) {
    private val noop = object : RemoteActions {
        override fun short(code: Int) {}
        override fun down(code: Int) {}
        override fun up(code: Int) {}
        override fun launch(shortcut: dev.krist.shieldremote.data.Shortcut) {}
        override fun imeEdit(old: String, new: String, selectionEnd: Int) {}
        override fun imeEnter() {}
        override fun openKeyboard() {}
        override fun closeKeyboard() {}
        override fun reconnect() {}
        override fun pairNew() {}
        override fun setTouchpad(enabled: Boolean) {}
        override fun voicePress() {}
        override fun voiceRelease(heldMs: Long) {}
        override fun openDevices() {}
        override fun openSettings() {}
        override fun openMoreKeys() {}
        override fun editShortcuts() {}
    }
    private val sink = object : KeySink { override fun short(code: Int) {}; override fun down(code: Int) {}; override fun up(code: Int) {} }
    private val dev = SavedDevice("1", "192.168.1.50", "SHIELD", "SHIELD")
    private val dev2 = SavedDevice("2", "192.168.1.51", "Bedroom SHIELD", "Bedroom SHIELD")
    private val base = RemoteState(link = Link.CONNECTED, device = dev, devices = listOf(dev, dev2), volume = VolumeInfo(25, 100, false), isOn = true)
    private val found = listOf(DiscoveredTv("SHIELD", "192.168.1.50", 6466), DiscoveredTv("Bedroom SHIELD", "192.168.1.51", 6466), DiscoveredTv("Sony TV", "192.168.1.52", 6466))

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/${name}_$mode.png") { ShieldTheme { content() } }

    @Test fun pairingList() = shot("pairing_list") {
        PairingContent(0, found, null, null, null, "", {}, false, {}, {}, {}, {}, {})
    }
    @Test fun pairingListWithIpAndError() = shot("pairing_error") {
        PairingContent(0, found, null, null, "Couldn't reach 192.168.1.99: connect timed out", "192.168.1.99", {}, false, {}, {}, {}, {}, {})
    }
    @Test fun pairingCode() = shot("pairing_code") {
        PairingContent(2, emptyList(), "SHIELD", "192.168.1.50", "That code doesn't match the one on the TV", "", {}, true, {}, {}, {}, {}, {})
    }
    @Test fun remoteDpad() = shot("remote_dpad") { RemoteScreen(base, false, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun remoteDisconnected() = shot("remote_disconnected") { RemoteScreen(base.copy(link = Link.PAUSED, isOn = null), false, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun remoteTvOn() = shot("remote_tv_on") { RemoteScreen(base.copy(isOn = true), false, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun remoteTvAsleep() = shot("remote_tv_asleep") { RemoteScreen(base.copy(isOn = false), false, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun remoteTvOff() = shot("remote_tv_off") { RemoteScreen(base.copy(link = Link.PAUSED, isOn = null), false, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun iconPicker() = shot("icon_picker") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(Palette.SurfaceHi).padding(16.dp)) {
            IconPicker(Shortcut("Netflix", "netflix://", 0xFFE50914, "com.netflix.ninja"), "brand:netflix", {}, {})
        }
    }
    @Test fun remoteTouchpadConnected() = shot("remote_touchpad_connected") { RemoteScreen(base, true, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun remoteTouchpad() = shot("remote_touchpad") { RemoteScreen(base.copy(link = Link.NEEDS_PAIRING), true, Prefs.DEFAULT_SHORTCUTS, noop) }
    @Test fun keyboardOpen() = shot("remote_keyboard") {
        RemoteScreen(base.copy(ime = ImeState(true, "star", "Search movies", 1, true)), false, Prefs.DEFAULT_SHORTCUTS, noop)
    }
    @Test fun moreKeys() = shot("sheet_more_keys") { MoreKeysSheet(sink, {}, inSheet = false) }
    @Test fun devices() = shot("sheet_devices") { DevicesSheet(listOf(dev, dev2), "1", {}, { _, _ -> }, {}, {}, {}, inSheet = false) }
    private val settingsActions = object : SettingsActions {
        override fun update(f: (Settings) -> Settings) {}
        override fun back() {}; override fun openNotificationSettings() {}; override fun manageShortcuts() {}; override fun manageDevices() {}
        override fun pairNew() {}; override fun diagnostics() {}; override fun exportSettings() {}; override fun importSettings() {}; override fun resetSettings() {}
    }
    @Test fun settings() = shot("settings_top") {
        SettingsScreen(Settings(), listOf(dev, dev2), "NVIDIA SHIELD Android TV", "1.0.1 (2)", settingsActions)
    }
    @Test fun settingsFull() = captureRoboImage("build/screenshots/settings_full_$mode.png") {
        // Whole page in one tall image (the real screen scrolls).
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredHeight(3600.dp)) {
            ShieldTheme { SettingsScreen(Settings(amoled = false), listOf(dev, dev2), "NVIDIA SHIELD Android TV", "1.0.1 (2)", settingsActions) }
        }
    }
    @Test fun addShortcutDialog() = captureRoboImage("build/screenshots/dialog_add_shortcut_$mode.png") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredHeight(1500.dp)) {
            ShieldTheme { ShortcutsSheet(Prefs.DEFAULT_SHORTCUTS.take(2), {}, {}, inSheet = false, startEditing = -1, inlineEditor = true) }
        }
    }
    @Test fun remoteCustomLayout() = shot("remote_lefthanded_large") {
        RemoteScreen(base, false, Prefs.DEFAULT_SHORTCUTS, noop, Settings(leftHanded = true, buttonSize = 2, showMedia = false))
    }
    @Test fun shortcuts() = shot("sheet_shortcuts") { ShortcutsSheet(Prefs.DEFAULT_SHORTCUTS, {}, {}, inSheet = false) }
    @Test fun debugLog() = shot("sheet_debug") {
        DebugSheet(listOf(
            "16:20:01.120  connected to 192.168.1.50, requesting features 615",
            "16:20:01.180  ← Configure(features=615, device=DeviceInfo(model=SHIELD Android TV, vendor=NVIDIA))",
            "16:20:04.002  ← ImeKeyInject(appPackage=com.github.damontecres.wholphin, field=TextField(counter=12, value=, start=0, end=0, label=Search), appCounter=37)",
            "16:20:05.310  → ime_batch_edit ime=37 field=12 \"st\"",
            "16:20:05.390  ← ImeBatchEdit(imeCounter=1, fieldCounter=1, text=st, start=1, end=1)",
        ), {}, inSheet = false)
    }
}

@Config(qualifiers = "w412dp-h891dp-night-xxhdpi")
class UiScreenshotsSystemDark : UiShots("sysdark")

@Config(qualifiers = "w412dp-h891dp-notnight-xxhdpi")
class UiScreenshotsSystemLight : UiShots("syslight")
