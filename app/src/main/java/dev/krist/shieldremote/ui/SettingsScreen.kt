package dev.krist.shieldremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.krist.shieldremote.data.SavedDevice
import dev.krist.shieldremote.data.Settings
import kotlin.math.roundToInt

/** Everything the settings page can do outside of the settings object itself. */
interface SettingsActions {
    fun update(f: (Settings) -> Settings)
    fun back()
    fun openNotificationSettings()
    fun manageShortcuts()
    fun manageDevices()
    fun pairNew()
    fun diagnostics()
    fun testMicrophone() {}
    fun playLastVoice() {}
    fun exportSettings()
    fun importSettings()
    fun resetSettings()
}

private val NOTIF_LABELS = mapOf(
    "BACK" to "Back", "HOME" to "Home", "UP" to "Up", "DOWN" to "Down", "LEFT" to "Left", "RIGHT" to "Right", "OK" to "OK",
    "PLAY_PAUSE" to "Play/Pause", "VOL_DOWN" to "Vol −", "VOL_UP" to "Vol +", "MUTE" to "Mute", "POWER" to "Power",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(s: Settings, devices: List<SavedDevice>, tvModel: String, version: String, a: SettingsActions) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Section("Notifications") {
                val on = s.notifications
                SwitchItem("App notifications", if (on) "Needed to stay connected in the background" else "Off: no notifications at all; the app disconnects when you leave it", on) { v -> a.update { it.copy(notifications = v) } }
                SwitchItem("Notification remote", "Mini remote in the notification while connected in the background", s.notifRemote, enabled = on) { v -> a.update { it.copy(notifRemote = v) } }
                SwitchItem("Show on lock screen", "Use the notification remote without unlocking", s.notifLockScreen, enabled = on && s.notifRemote) { v -> a.update { it.copy(notifLockScreen = v) } }
                Item("Notification buttons", "Pick up to ${Settings.MAX_NOTIF_BUTTONS} (${s.notifButtons.size} selected)") {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Settings.NOTIF_KEYS.forEach { k ->
                            val on = k in s.notifButtons
                            Chip(NOTIF_LABELS[k] ?: k, on, enabled = s.notifications && s.notifRemote && (on || s.notifButtons.size < Settings.MAX_NOTIF_BUTTONS)) {
                                a.update { st -> st.copy(notifButtons = if (on) st.notifButtons - k else st.notifButtons + k) }
                            }
                        }
                    }
                }
                SwitchItem("Connection alerts", "Notify when the connection is lost or restored", s.connectionAlerts, enabled = on) { v -> a.update { it.copy(connectionAlerts = v) } }
                if (on) NavItem("System notification settings", "Importance, sound, lock-screen privacy", a::openNotificationSettings)
            }
            Section("Connection") {
                val graces = listOf(0 to "Off", 1 to "1 min", 3 to "3 min", 10 to "10 min", 30 to "30 min", -1 to "Always")
                if (s.notifications) Item("Stay connected after leaving the app", if (s.graceMinutes < 0) "Until you tap Disconnect in the notification" else if (s.graceMinutes == 0) "Disconnects as soon as you leave" else "Keeps the link alive in the background") {
                    Chips(graces.map { it.second }, graces.indexOfFirst { it.first == s.graceMinutes }.coerceAtLeast(0)) { i -> a.update { it.copy(graceMinutes = graces[i].first) } }
                }
                SwitchItem("Auto-connect on launch", "Off: tap the status pill to connect", s.autoConnect) { v -> a.update { it.copy(autoConnect = v) } }
                SwitchItem("Reconnect on Wi-Fi change", "Immediately re-open the link when the network changes", s.reconnectOnWifi) { v -> a.update { it.copy(reconnectOnWifi = v) } }
                SwitchItem("Keep screen on", "While the remote is open", s.keepScreenOn) { v -> a.update { it.copy(keepScreenOn = v) } }
                if (devices.size > 1 || s.defaultDeviceId.isNotBlank()) {
                    Item("Default device", "Connected at app launch") {
                        val opts = listOf("" to "Last used") + devices.map { it.id to it.name }
                        Chips(opts.map { it.second }, opts.indexOfFirst { it.first == s.defaultDeviceId }.coerceAtLeast(0)) { i -> a.update { it.copy(defaultDeviceId = opts[i].first) } }
                    }
                }
            }
            Section("Remote layout") {
                Item("Default control mode") { Chips(listOf("D-pad", "Touchpad"), if (s.touchpad) 1 else 0) { i -> a.update { it.copy(touchpad = i == 1) } } }
                SwitchItem("Top row (power, voice, keyboard, more)", null, s.showExtraKeys) { v -> a.update { it.copy(showExtraKeys = v) } }
                SwitchItem("Media buttons", null, s.showMedia) { v -> a.update { it.copy(showMedia = v) } }
                SwitchItem("Volume bar", null, s.showVolume) { v -> a.update { it.copy(showVolume = v) } }
                SwitchItem("App shortcuts", null, s.showShortcuts) { v -> a.update { it.copy(showShortcuts = v) } }
                Item("Button size") { Chips(listOf("Compact", "Normal", "Large"), s.buttonSize) { i -> a.update { it.copy(buttonSize = i) } } }
                SwitchItem("Left-handed layout", "Mirrors the button rows", s.leftHanded) { v -> a.update { it.copy(leftHanded = v) } }
                SliderItem("Touchpad sensitivity", "×" + "%.1f".format(s.touchpadSensitivity), s.touchpadSensitivity, 0.5f..2f, 14) { v -> a.update { it.copy(touchpadSensitivity = (v * 10).roundToInt() / 10f) } }
                SliderItem("Long-press duration", "${s.longPressMs} ms", s.longPressMs.toFloat(), 250f..1000f, 14) { v -> a.update { it.copy(longPressMs = ((v / 50).roundToInt() * 50)) } }
                SliderItem("Key repeat speed", "every ${s.repeatMs} ms", s.repeatMs.toFloat(), 40f..250f, 20) { v -> a.update { it.copy(repeatMs = ((v / 5).roundToInt() * 5)) } }
            }
            Section("Input") {
                SwitchItem("Phone volume buttons control TV", "While the app is open", s.volumeKeys) { v -> a.update { it.copy(volumeKeys = v) } }
                SwitchItem("Auto-open keyboard", "When a text box is focused on the TV", s.autoKeyboard) { v -> a.update { it.copy(autoKeyboard = v) } }
                Item("Typing mode", if (s.liveTyping) "Every keystroke appears on the TV immediately" else "Text is sent when you press Enter") {
                    Chips(listOf("Live", "Send on Enter"), if (s.liveTyping) 0 else 1) { i -> a.update { it.copy(liveTyping = i == 0) } }
                }
                SwitchItem("Key-press fallback", "Type key by key when the TV reports no text field (off by default)", s.keyFallback, enabled = !s.alwaysKeys) { v -> a.update { it.copy(keyFallback = v) } }
                SwitchItem("Always type with key presses", "For apps that don't accept text edits", s.alwaysKeys) { v -> a.update { it.copy(alwaysKeys = v) } }
            }
            Section("Feedback") {
                Item("Haptic strength") { Chips(listOf("Off", "Light", "Medium", "Strong"), s.haptics) { i -> a.update { it.copy(haptics = i) }; Haptics.level = i; Haptics.play(null, Haptics.Kind.PRESS) } }
                SwitchItem("Click sound", "Play a click on every press", s.sound) { v -> a.update { it.copy(sound = v) } }
            }
            Section("Appearance") {
                Item("Accent colour", Settings.ACCENTS.getOrNull(s.accent)?.first) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings.ACCENTS.forEachIndexed { i, (name, c, _) ->
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(Color(c.toInt()))
                                    .border(if (i == s.accent) 3.dp else 0.dp, Palette.Text, CircleShape)
                                    .clickable { a.update { it.copy(accent = i) } },
                                contentAlignment = Alignment.Center,
                            ) { if (i == s.accent) Icon(Icons.Rounded.Check, name, tint = Color(0xFF0B0F14)) }
                        }
                    }
                }
                SwitchItem("Pure black background", "AMOLED: saves power on the S25 Ultra's display", s.amoled) { v -> a.update { it.copy(amoled = v) } }
                SliderItem("Text size", "${(s.textScale * 100).roundToInt()} %", s.textScale, 0.85f..1.3f, 8) { v -> a.update { it.copy(textScale = (v * 20).roundToInt() / 20f) } }
            }
            Section("Voice") {
                Item("Voice search", if (s.voiceStreaming) "Talks to the TV's Google Assistant like the Google TV app; falls back to the phone if the TV doesn't answer within 1.5 s" else "Recognised on the phone, typed into the open TV text box (or searched on YouTube)") {
                    Chips(listOf("Stream to TV", "Phone recogniser"), if (s.voiceStreaming) 0 else 1) { i -> a.update { it.copy(voiceStreaming = i == 0) } }
                }
                if (s.voiceStreaming) Item("Upload", if (s.voiceLive) "Live: audio is sent while you speak" else "Buffered: sent right after you stop speaking (most reliable)") {
                    Chips(listOf("Buffered", "Live"), if (s.voiceLive) 1 else 0) { i -> a.update { it.copy(voiceLive = i == 1) } }
                }
                NavItem("Test microphone", "Records 3 s and plays it back, with the level", a::testMicrophone)
                NavItem("Play last voice search", "Hear exactly what was sent to the TV", a::playLastVoice)
            }
            Section("Shortcuts & devices") {
                NavItem("App shortcuts", "Add, edit, test and reorder", a::manageShortcuts)
                NavItem("Devices", "${devices.size} paired · rename, forget or switch", a::manageDevices)
                NavItem("Pair a new device", null, a::pairNew)
            }
            Section("Advanced") {
                NavItem("Diagnostics", "Protocol log of recent messages", a::diagnostics)
                NavItem("Export settings", "Save settings and shortcuts to a file", a::exportSettings)
                NavItem("Import settings", "Load a previously exported file", a::importSettings)
                NavItem("Reset to defaults", "Settings only; pairings and shortcuts stay", { confirmReset = true }, danger = true)
                Item("About", "Android TV Remote $version" + if (tvModel.isNotBlank()) "\nConnected to $tvModel" else "") {}
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset settings?") },
        text = { Text("All settings return to their defaults. Paired devices and shortcuts are kept.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; a.resetSettings() }) { Text("Reset", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Palette.Text) } },
        containerColor = Palette.SurfaceHi, titleContentColor = Palette.Text, textContentColor = Palette.Text,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), color = Palette.AccentSoft, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Surface).border(1.dp, Palette.Stroke.copy(alpha = 0.6f), RoundedCornerShape(20.dp))) { content() }
}

@Composable
private fun itemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent, headlineColor = Palette.Text, supportingColor = Palette.TextDim,
    disabledHeadlineColor = Palette.TextDim,
)

@Composable
private fun SwitchItem(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = ListItem(
    headlineContent = { Text(title, color = if (enabled) Palette.Text else Palette.TextDim) },
    supportingContent = sub?.let { { Text(it, color = Palette.TextDim) } },
    trailingContent = {
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF0B0F14), checkedTrackColor = Palette.Accent,
            uncheckedThumbColor = Palette.TextDim, uncheckedTrackColor = Palette.SurfaceHi, uncheckedBorderColor = Palette.Stroke,
        ))
    },
    colors = itemColors(),
    modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
)

@Composable
private fun Item(title: String, sub: String? = null, below: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
        if (sub != null) Text(sub, color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        below()
    }
}

@Composable
private fun NavItem(title: String, sub: String?, onClick: () -> Unit, danger: Boolean = false) = ListItem(
    headlineContent = { Text(title, color = if (danger) Palette.Danger else Palette.Text) },
    supportingContent = sub?.let { { Text(it, color = Palette.TextDim) } },
    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextDim) },
    colors = itemColors(),
    modifier = Modifier.clickable(onClick = onClick),
)

@Composable
private fun SliderItem(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.Text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, color = Palette.AccentSoft, style = MaterialTheme.typography.labelLarge)
        }
        Slider(v, onChange, valueRange = range, steps = steps, colors = SliderDefaults.colors(
            thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.SurfaceHi,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        ))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o -> Chip(o, i == selected) { onSelect(i) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(
        containerColor = Palette.SurfaceHi, labelColor = Palette.Text,
        selectedContainerColor = Palette.Accent.copy(alpha = 0.24f), selectedLabelColor = Palette.AccentSoft,
        disabledContainerColor = Palette.SurfaceHi.copy(alpha = 0.5f), disabledLabelColor = Palette.TextDim.copy(alpha = 0.7f),
    ),
    border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = selected, borderColor = Palette.Stroke, selectedBorderColor = Palette.Accent.copy(alpha = 0.6f)),
)
