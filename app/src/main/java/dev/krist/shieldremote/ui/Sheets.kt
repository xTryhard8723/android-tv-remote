package dev.krist.shieldremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krist.shieldremote.data.Prefs
import dev.krist.shieldremote.data.SavedDevice
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.proto.Remote

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, inSheet: Boolean = true, content: @Composable () -> Unit) {
    if (!inSheet) { // inline rendering (screenshot tests) with exactly the sheet's colours
        androidx.compose.material3.Surface(color = Palette.Surface, contentColor = Palette.Text) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 16.dp)) { content() }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        contentColor = Palette.Text,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) { content() }
    }
}

private data class XKey(val code: Int, val label: String, val hold: Hold = Hold.SINGLE)

private val MORE_KEYS = listOf(
    XKey(Remote.Key.ASSIST, "Assistant"), XKey(Remote.Key.SEARCH, "Search"), XKey(Remote.Key.TV_INPUT, "Input"), XKey(Remote.Key.SETTINGS, "Settings"),
    XKey(Remote.Key.CHANNEL_UP, "Ch +", Hold.REPEAT), XKey(Remote.Key.CHANNEL_DOWN, "Ch −", Hold.REPEAT), XKey(Remote.Key.GUIDE, "Guide"), XKey(Remote.Key.INFO, "Info"),
    XKey(Remote.Key.MEDIA_PREVIOUS, "Previous"), XKey(Remote.Key.MEDIA_STOP, "Stop"), XKey(Remote.Key.MEDIA_NEXT, "Next"), XKey(Remote.Key.CAPTIONS, "Captions"),
    XKey(Remote.Key.SLEEP, "Sleep"), XKey(Remote.Key.WAKEUP, "Wake"), XKey(Remote.Key.MENU, "Menu", Hold.LONG_PRESS), XKey(Remote.Key.APP_SWITCH, "Recents"),
)

@Composable
fun MoreKeysSheet(sink: KeySink, onDismiss: () -> Unit, inSheet: Boolean = true) = Sheet(onDismiss, inSheet) {
    Text("More keys", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
    Spacer(Modifier.height(14.dp))
    MORE_KEYS.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { k ->
                RemoteButton(k.code, sink, Modifier.weight(1f).height(54.dp), k.hold, RoundedCornerShape(16.dp), container = Palette.SurfaceHi, label = k.label)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Text("Sleep puts the TV to sleep; Power or Wake turns it back on.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DevicesSheet(
    devices: List<SavedDevice>, activeId: String?,
    onSelect: (String) -> Unit, onRename: (String, String) -> Unit, onForget: (String) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit, inSheet: Boolean = true,
) = Sheet(onDismiss, inSheet) {
    var renaming by remember { mutableStateOf<SavedDevice?>(null) }
    var forgetting by remember { mutableStateOf<SavedDevice?>(null) }
    Text("Devices", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
    Spacer(Modifier.height(12.dp))
    devices.forEach { d ->
        val active = d.id == activeId
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (active) Palette.Accent.copy(alpha = 0.10f) else Palette.SurfaceHi)
                .border(1.dp, if (active) Palette.Accent.copy(alpha = 0.5f) else Palette.Stroke, RoundedCornerShape(18.dp))
                .clickable { onSelect(d.id); onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (active) Icons.Rounded.Check else Icons.Rounded.Tv, null, tint = if (active) Palette.Accent else Palette.TextDim)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.host, color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { renaming = d }) { Icon(Icons.Rounded.Edit, "Rename", tint = Palette.TextDim) }
            IconButton(onClick = { forgetting = d }) { Icon(Icons.Rounded.Delete, "Forget", tint = Palette.Danger) }
        }
        Spacer(Modifier.height(10.dp))
    }
    TextButton(onClick = { onDismiss(); onAdd() }) {
        Icon(Icons.Rounded.Add, null, tint = Palette.Accent); Text("  Pair a new device", color = Palette.Accent)
    }
    renaming?.let { d ->
        TextPrompt("Rename device", d.name, onDismiss = { renaming = null }) { onRename(d.id, it); renaming = null }
    }
    forgetting?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${d.name}?") },
            text = { Text("You'll need to pair again with a code on the TV to use it.") },
            confirmButton = { TextButton(onClick = { onForget(d.id); forgetting = null }) { Text("Forget", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
            containerColor = Palette.SurfaceHi, titleContentColor = Palette.Text, textContentColor = Palette.Text,
        )
    }
}

@Composable
fun TextPrompt(title: String, initial: String, label: String = "Name", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(v, { v = it }, singleLine = true, label = { Text(label) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, cursorColor = Palette.Accent))
        },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onDone(v.trim()) }) { Text("Save", color = Palette.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Palette.SurfaceHi, titleContentColor = Palette.Text, textContentColor = Palette.Text,
    )
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            FilterChip(
                selected = i == selected, onClick = { onSelect(i) }, label = { Text(o) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Accent.copy(alpha = 0.22f), selectedLabelColor = Palette.AccentSoft),
            )
        }
    }
}

private val SWATCHES = listOf(0xFFE50914, 0xFFFF0033, 0xFFE5A00D, 0xFF113CCF, 0xFF00A8E1, 0xFF1DB954, 0xFF9146FF, 0xFF76B900, 0xFFFF6D00, 0xFF607D8B)

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
    focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Stroke,
    focusedLabelColor = Palette.AccentSoft, unfocusedLabelColor = Palette.TextDim,
    focusedPlaceholderColor = Palette.TextDim, unfocusedPlaceholderColor = Palette.TextDim,
    cursorColor = Palette.Accent,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShortcutsSheet(
    shortcuts: List<Shortcut>, onChange: (List<Shortcut>) -> Unit, onDismiss: () -> Unit, inSheet: Boolean = true,
    onTest: (Shortcut) -> Unit = {}, startEditing: Int? = null, inlineEditor: Boolean = false,
    onRecordKey: (Int) -> Unit = {}, methodOf: (Shortcut) -> String = { dev.krist.shieldremote.data.Launch.describe(it) },
    onPickImage: ((String) -> Unit) -> Unit = {},
) = Sheet(onDismiss, inSheet) {
    var editing by remember { mutableStateOf(startEditing) } // index, or -1 for new
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("App shortcuts", style = MaterialTheme.typography.titleMedium, color = Palette.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(Prefs.DEFAULT_SHORTCUTS) }) { Icon(Icons.Rounded.RestartAlt, "Reset to defaults", tint = Palette.TextDim) }
        }
        Text("Tap a shortcut to edit or test it. Apps are opened by package name (works for sideloaded apps too), or by a deep link if you add one.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        shortcuts.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHi).clickable { editing = i }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(s.color.toInt())))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.name, style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                    Text(methodOf(s), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (i > 0) onChange(shortcuts.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = Palette.TextDim) }
                IconButton(onClick = { if (i < shortcuts.size - 1) onChange(shortcuts.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < shortcuts.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = Palette.TextDim) }
                IconButton(onClick = { onChange(shortcuts.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Delete, "Remove", tint = Palette.Danger) }
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { editing = -1 }) { Icon(Icons.Rounded.Add, null, tint = Palette.Accent); Text("  Add shortcut", color = Palette.Accent) }
    }
    editing?.let { idx ->
        val cur = shortcuts.getOrNull(idx)
        var name by remember(idx) { mutableStateOf(cur?.name ?: "") }
        var pkg by remember(idx) { mutableStateOf(cur?.packageName ?: "") }
        var link by remember(idx) { mutableStateOf(cur?.link?.takeIf { dev.krist.shieldremote.data.Launch.packageFromMarket(it) == null } ?: "") }
        var color by remember(idx) { mutableStateOf(cur?.color ?: SWATCHES[shortcuts.size % SWATCHES.size]) }
        var keys by remember(idx) { mutableStateOf(cur?.keys ?: "") }
        var icon by remember(idx) { mutableStateOf(cur?.icon ?: "") }
        val keysOk = keys.isBlank() || dev.krist.shieldremote.data.KeyMacro.parse(keys) != null
        val linkOk = link.isBlank() || dev.krist.shieldremote.data.Launch.isValidLink(link)
        val linkless = dev.krist.shieldremote.data.Launch.isLinkless(Shortcut(name, link, 0, pkg, keys))
        val needsRecording = keys.isBlank() && (linkless || (link.isBlank() && pkg.isNotBlank() && dev.krist.shieldremote.data.AppCatalog.byPackage(pkg)?.link.isNullOrBlank()))
        fun build(): Shortcut? {
            if (!keysOk || !linkOk) return null
            val p = pkg.trim(); var l = link.trim()
            // tolerate a package typed into the link field
            val pk = dev.krist.shieldremote.data.Launch.correctPackage(if (p.isBlank() && l.isNotBlank() && !l.contains(":")) l.also { l = "" } else p)
            if (pk.isBlank() && l.isBlank() && keys.isBlank()) return null
            val sc = Shortcut(name.trim().ifBlank { pk.substringAfterLast('.').ifBlank { "Keys" } }, l, color, pk, keys.trim(), icon)
            return if (dev.krist.shieldremote.data.Launch.isLinkless(sc)) sc.copy(link = "") else sc
        }
        val title: @Composable () -> Unit = { Text(if (cur == null) "Add shortcut" else "Edit shortcut", color = Palette.Text, style = MaterialTheme.typography.headlineSmall) }
        val body: @Composable () -> Unit = {
                Column(if (inlineEditor) Modifier else Modifier.verticalScroll(rememberScrollState())) {
                    if (needsRecording) {
                        Text("${name.ifBlank { "This app" }} can't be opened by link. Record it: tap HOME, then the arrows to reach it on the TV, then OK, then Save.",
                            color = Palette.Bg, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.AccentSoft).padding(10.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    if (cur == null) {
                        Text("Pick a common app", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                        androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dev.krist.shieldremote.data.AppCatalog.apps.forEach { app ->
                                val sel = pkg == app.pkg
                                Text(app.name, color = if (sel) Palette.Bg else Palette.Text, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) Palette.AccentSoft else Palette.Surface)
                                        .border(1.dp, Palette.Stroke, RoundedCornerShape(12.dp))
                                        .clickable { name = app.name; pkg = app.pkg; link = app.link; color = app.color; keys = "" }.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("…or enter any app", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, placeholder = { Text("Wholphin") }, colors = fieldColors())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(pkg, { pkg = it.trim() }, singleLine = true, label = { Text("Package name") }, placeholder = { Text("com.github.damontecres.wholphin") }, colors = fieldColors())
                    Spacer(Modifier.height(8.dp))
                    if (!linkless) OutlinedTextField(link, { link = it }, singleLine = true, label = { Text("Deep link (optional)") }, placeholder = { Text("netflix://") }, colors = fieldColors(),
                        isError = !linkOk, supportingText = if (linkOk) null else ({ Text("Not a valid link (no spaces, e.g. wholphin://). It won't be sent.", color = Palette.Danger) }))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(keys, { keys = it }, label = { Text("Key sequence (optional)") }, placeholder = { Text("HOME, wait500, DOWN, RIGHT x3, OK") },
                        isError = !keysOk, colors = fieldColors(),
                        supportingText = { Text(if (keysOk) "If set, the shortcut presses these keys instead of opening a link. Keys: HOME BACK UP DOWN LEFT RIGHT OK MENU PLAY SEARCH SETTINGS APPS, xN repeats, waitMS pauses." else "Unknown key name", color = if (keysOk) Palette.TextDim else Palette.Danger) })
                    Text("Record: each button below presses it on the TV and adds it to the sequence", color = Palette.Text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("HOME" to 3, "BACK" to 4, "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "APPS" to 284, "wait500" to 0, "⌫" to -1).forEach { (label, code) ->
                            Text(label, color = Palette.Text, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface).border(1.dp, Palette.Stroke, RoundedCornerShape(12.dp))
                                    .clickable {
                                        keys = when {
                                            code == -1 -> dev.krist.shieldremote.data.KeyMacro.dropLast(keys)
                                            else -> { if (code > 0) onRecordKey(code); dev.krist.shieldremote.data.KeyMacro.append(keys, label) }
                                        }
                                    }.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                    Text("Find a package name in the TV's Settings › Apps, or in the app's Play Store URL (id=…). Apps open via their deep link; package-only launches go through the Play Store, which no longer works for most apps unless AVA Connect is installed on the TV.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                    IconPicker(Shortcut(name.ifBlank { "App" }, link, color, pkg, keys, icon), icon, onPick = { icon = it }, onGallery = { onPickImage { path -> icon = "file:$path" } })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SWATCHES.take(10).forEach { c ->
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c.toInt()))
                                .border(if (c == color) 2.dp else 0.dp, Color.White, CircleShape).clickable { color = c })
                        }
                    }
                }
        }
        val confirm: @Composable () -> Unit = {
                Row {
                    TextButton(onClick = { build()?.let(onTest) }) { Text("Test", color = Palette.AccentSoft) }
                    TextButton(onClick = {
                        build()?.let { s ->
                            onChange(if (cur == null) shortcuts + s else shortcuts.mapIndexed { j, o -> if (j == idx) s else o })
                            editing = null
                        }
                    }) { Text("Save", color = Palette.Accent) }
                }
        }
        val dismiss: @Composable () -> Unit = { TextButton(onClick = { editing = null }) { Text("Cancel", color = Palette.Text) } }
        if (inlineEditor) { // screenshot tests: same content and colours as the dialog, drawn in place
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Surface(color = Palette.SurfaceHi, contentColor = Palette.Text, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(24.dp)) {
                    title(); Spacer(Modifier.height(16.dp)); body()
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) { dismiss(); confirm() }
                }
            }
        } else AlertDialog(
            onDismissRequest = { editing = null },
            title = title, text = body, confirmButton = confirm, dismissButton = dismiss,
            containerColor = Palette.SurfaceHi, titleContentColor = Palette.Text, textContentColor = Palette.Text,
        )
    }
}

@Composable
fun DebugSheet(lines: List<String>, onDismiss: () -> Unit, inSheet: Boolean = true) = Sheet(onDismiss, inSheet) {
    Text("Protocol log", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
    Text("Last messages exchanged with the TV (newest first). Screenshot this if something misbehaves.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().height(520.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(10.dp).verticalScroll(rememberScrollState())) {
        if (lines.isEmpty()) Text("Nothing yet.", color = Palette.TextDim)
        lines.asReversed().forEach { l ->
            Text(l, color = if (l.contains("ime", true) || l.contains("keys")) Palette.AccentSoft else Palette.Text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
