package dev.krist.shieldremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krist.shieldremote.ImeState
import dev.krist.shieldremote.Voice
import dev.krist.shieldremote.data.Shortcut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.filled.Tv
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import dev.krist.shieldremote.Link
import dev.krist.shieldremote.RemoteState
import dev.krist.shieldremote.TvStatus
import dev.krist.shieldremote.tvStatus
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.data.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

interface RemoteActions : KeySink {
    fun launch(shortcut: dev.krist.shieldremote.data.Shortcut)
    fun imeEdit(old: String, new: String, selectionEnd: Int)
    fun imeEnter()
    fun openKeyboard()
    fun closeKeyboard()
    fun reconnect()
    /** Power key: toggle when connected; when the link is down, reconnect and send WAKEUP. */
    fun power(code: Int = Remote.Key.POWER) = short(code)
    fun pairNew()
    fun setTouchpad(enabled: Boolean)
    fun voicePress()
    fun voiceRelease(heldMs: Long)
    fun openDevices()
    fun openSettings()
    fun openMoreKeys()
    fun editShortcuts()
}

/** Mirrors a row's order for left-handed mode while keeping each child (icons, text) left-to-right. */
@Composable
private fun Mirrored(mirror: Boolean, row: @Composable (@Composable (@Composable () -> Unit) -> Unit) -> Unit) {
    val ltr: @Composable (@Composable () -> Unit) -> Unit = { c -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { c() } }
    CompositionLocalProvider(LocalLayoutDirection provides if (mirror) LayoutDirection.Rtl else LayoutDirection.Ltr) { row(ltr) }
}

/** Scales the controls (not the D-pad, which takes the remaining space) for the button-size setting. */
@Composable
private fun Sized(scale: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) { content() }
}

@Composable
fun RemoteScreen(state: RemoteState, touchpad: Boolean, shortcuts: List<Shortcut>, actions: RemoteActions, layout: Settings = Settings()) {
    val view = LocalView.current
    val scale = when (layout.buttonSize) { 0 -> 0.86f; 2 -> 1.14f; else -> 1f }
    val left = layout.leftHanded
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state, touchpad, actions)
            Spacer(Modifier.height(14.dp))
            if (layout.showExtraKeys) Sized(scale) {
                Mirrored(left) { ltr ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ltr {
                            CircleAction(if (touchpad) Icons.Rounded.Gamepad else Icons.Rounded.TouchApp, if (touchpad) "D-pad mode" else "Touchpad mode") { actions.setTouchpad(!touchpad) }
                            MicButton(state.voice, actions)
                            RemoteButton(Remote.Key.APP_SWITCH, actions, Modifier.size(60.dp), Hold.SINGLE, icon = Icons.Rounded.Apps, contentDescription = "Recent apps")
                            CircleAction(Icons.Rounded.Keyboard, "Keyboard") { actions.openKeyboard() }
                            CircleAction(Icons.Rounded.MoreHoriz, "More keys") { actions.openMoreKeys() }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // D-pad / touchpad gets all remaining height
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val d = minOf(maxWidth * 0.92f, maxHeight * 0.96f)
                // One fixed-size slot for both modes: nothing measures differently while switching, so the pad
                // never jumps; the modes only crossfade (no size / position animation).
                val padH = minOf(maxHeight * 0.96f, maxWidth * 1.05f)
                Crossfade(touchpad, Modifier.width(maxWidth).height(padH), animationSpec = androidx.compose.animation.core.tween(180), label = "mode") { tp ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (tp) TouchPad(actions, Modifier.fillMaxSize())
                        else DPad(actions, d)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Sized(scale) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Mirrored(left) { ltr ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ltr {
                                val m = Modifier.weight(1f).height(68.dp)
                                val shape = RoundedCornerShape(20.dp)
                                RemoteButton(Remote.Key.BACK, actions, m, Hold.LONG_PRESS, shape, icon = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                                RemoteButton(Remote.Key.HOME, actions, m, Hold.LONG_PRESS, shape, icon = Icons.Rounded.Home, contentDescription = "Home")
                                RemoteButton(Remote.Key.MENU, actions, m, Hold.LONG_PRESS, shape, icon = Icons.Rounded.Menu, contentDescription = "Menu")
                            }
                        }
                    }
                    if (layout.showMedia) {
                        Spacer(Modifier.height(12.dp))
                        Mirrored(left) { ltr ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ltr {
                                    val shape = RoundedCornerShape(20.dp)
                                    RemoteButton(Remote.Key.MEDIA_REWIND, actions, Modifier.weight(1f).height(68.dp), Hold.REPEAT, shape, icon = Icons.Rounded.FastRewind, contentDescription = "Rewind")
                                    RemoteButton(
                                        Remote.Key.MEDIA_PLAY_PAUSE, actions, Modifier.weight(1.3f).height(68.dp), Hold.SINGLE, shape,
                                        container = Palette.Accent.copy(alpha = 0.18f), content = Palette.AccentSoft, icon = Icons.Rounded.PlayArrow, iconSize = 32.dp, contentDescription = "Play/Pause",
                                    )
                                    RemoteButton(Remote.Key.MEDIA_FAST_FORWARD, actions, Modifier.weight(1f).height(68.dp), Hold.REPEAT, shape, icon = Icons.Rounded.FastForward, contentDescription = "Fast forward")
                                }
                            }
                        }
                    }
                    if (layout.showVolume) {
                        Spacer(Modifier.height(12.dp))
                        Mirrored(left) { ltr -> Box { ltr { VolumeBar(state, actions) } } }
                    }
                }
            }
            if (layout.showShortcuts) {
                Spacer(Modifier.height(14.dp))
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(shortcuts) { app -> AppChip(app, onLongClick = { actions.editShortcuts() }) { haptic(view); actions.launch(app) } }
                    item { AddChip { actions.editShortcuts() } }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        KeyboardPanel(state.ime, actions, Modifier.align(Alignment.BottomCenter), sendOnEnter = !layout.liveTyping)
        ListeningOverlay(state.voice, actions, Modifier.align(Alignment.BottomCenter))
    }
}

/** Visible while the Shield's Assistant is listening through the phone mic (or waiting for it). */
@Composable
private fun ListeningOverlay(voice: Voice, actions: RemoteActions, modifier: Modifier) {
    AnimatedVisibility(voice != Voice.IDLE, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                .clip(RoundedCornerShape(28.dp)).background(Palette.SurfaceHi)
                .border(1.dp, Palette.Accent.copy(alpha = 0.6f), RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pulse = if (voice == Voice.LISTENING) pulseScale() else 1f
            Box(Modifier.size(44.dp).scale(pulse).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, null, tint = Color(0xFF0B1200))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(if (voice == Voice.LISTENING) "Listening…" else "Starting voice…", color = Palette.Text, style = MaterialTheme.typography.titleMedium)
                Text(if (voice == Voice.LISTENING) "Speak now. Stops when you pause." else "Asking the TV's Assistant", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Danger.copy(alpha = 0.2f)).clickable { actions.voicePress() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("Stop", color = Palette.Danger, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Top-bar power: tap = POWER (Shield sleep/wake, TV via CEC), hold = Power / Sleep / Wake menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PowerTopButton(actions: RemoteActions) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF3A1A1C))
                .border(1.5.dp, Palette.Danger.copy(alpha = 0.55f), CircleShape)
                .combinedClickable(onClick = { haptic(view); actions.power() }, onLongClick = { haptic(view, true); menu = true }),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PowerSettingsNew, "Power (hold for Sleep / Wake)", tint = Palette.Danger, modifier = Modifier.size(28.dp)) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Power (toggle)") }, onClick = { menu = false; actions.power(Remote.Key.POWER) })
            DropdownMenuItem(text = { Text("Sleep") }, onClick = { menu = false; actions.power(Remote.Key.SLEEP) })
            DropdownMenuItem(text = { Text("Wake") }, onClick = { menu = false; actions.power(Remote.Key.WAKEUP) })
        }
    }
}

@Composable
private fun TopCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.Surface).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Palette.Text, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun Header(state: RemoteState, touchpad: Boolean, actions: RemoteActions) {
    var menu by remember { mutableStateOf(false) }
    val dark = Color(0xFF0D2416)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.align(Alignment.CenterEnd)) { PowerTopButton(actions) }
            // Centred Google TV-style device pill; the dot on the TV icon shows the connection state.
            Row(Modifier.widthIn(max = 230.dp).clip(RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).background(Palette.Accent)
                        .clickable { if (state.link == Link.CONNECTED) actions.openDevices() else actions.reconnect() }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(Icons.Filled.Tv, null, tint = dark, modifier = Modifier.size(22.dp))
                        StatusDot(state, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-5).dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(state.device?.name ?: "Android TV", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = dark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(2.dp))
                Box(Modifier.background(Palette.Accent).clickable { actions.openDevices() }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Icon(Icons.Rounded.ExpandMore, "Switch device", tint = dark, modifier = Modifier.size(22.dp))
                }
            }
            Box(Modifier.align(Alignment.CenterStart)) {
                TopCircle(Icons.Rounded.MoreVert, "More") { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reconnect now") }, onClick = { menu = false; actions.reconnect() })
                    DropdownMenuItem(text = { Text("Devices…") }, onClick = { menu = false; actions.openDevices() })
                    DropdownMenuItem(text = { Text("Pair another device") }, onClick = { menu = false; actions.pairNew() })
                    DropdownMenuItem(text = { Text("Edit app shortcuts") }, onClick = { menu = false; actions.editShortcuts() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; actions.openSettings() })
                }
            }
        }
        // Slim banner only while not connected.
        AnimatedVisibility(state.link != Link.CONNECTED) {
            val (text, color) = when (state.link) {
                Link.CONNECTING -> "Connecting…" to Palette.Warn
                Link.RECONNECTING -> (if (state.queued > 0) "Reconnecting… (${state.queued} queued)" else "Reconnecting…") to Palette.Warn
                Link.NO_WIFI -> "No Wi-Fi · waiting for network" to Palette.Danger
                Link.NEEDS_PAIRING -> "Pairing needed · tap to pair again" to Palette.Danger
                Link.NO_DEVICE -> "No device" to Palette.Danger
                else -> (if (state.detail.isNotBlank()) "Disconnected · ${state.detail}" else "Disconnected · tap to retry") to Palette.Danger
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface)
                    .clickable { if (state.link == Link.NEEDS_PAIRING) actions.pairNew() else actions.reconnect() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state)
                Spacer(Modifier.width(10.dp))
                Text(text, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusDot(state: RemoteState, modifier: Modifier = Modifier) {
    val status = tvStatus(state.link, state.isOn)
    val color = when (status) {
        TvStatus.ON -> Palette.StatusOn
        TvStatus.ASLEEP -> Palette.StatusAsleep
        TvStatus.OFF -> Palette.StatusOff
    }
    val label = when (status) {
        TvStatus.ON -> "TV on"
        TvStatus.ASLEEP -> "TV asleep"
        TvStatus.OFF -> "TV off"
    }
    // Pulse only while the link is still coming up; the color stays red (off/disconnected).
    val a = if (state.link == Link.CONNECTING || state.link == Link.RECONNECTING) pulseAlpha() else 1f
    // 16 dp (was 10) with a white hairline + a crisp dark ring so green/yellow/red all pop on the mint pill and on the dark banner.
    Box(
        modifier
            .size(16.dp)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(Color.White)
            .padding(1.25.dp)
            .clip(CircleShape)
            .background(Color(0xFF0D2416))
            .padding(2.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = a)),
    )
}

@Composable
private fun VolumeBar(state: RemoteState, actions: RemoteActions) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(shape).background(Palette.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteButton(Remote.Key.VOLUME_DOWN, actions, Modifier.weight(1f).height(68.dp), Hold.REPEAT, RoundedCornerShape(0.dp), container = Color.Transparent, icon = Icons.Rounded.Remove, contentDescription = "Volume down")
        val muted = state.muted
        RemoteButton(
            Remote.Key.VOLUME_MUTE, actions, Modifier.weight(1.4f).height(68.dp), Hold.SINGLE, RoundedCornerShape(0.dp),
            container = if (muted) Palette.Danger.copy(alpha = 0.18f) else Color.Transparent, content = if (muted) Palette.Danger else Palette.Text,
            icon = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            label = null, contentDescription = if (muted) "Unmute" else "Mute",
        )
        RemoteButton(Remote.Key.VOLUME_UP, actions, Modifier.weight(1f).height(68.dp), Hold.REPEAT, RoundedCornerShape(0.dp), container = Color.Transparent, icon = Icons.Rounded.Add, contentDescription = "Volume up")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppChip(app: Shortcut, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Color(app.color.toInt())
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(Modifier.clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) { ShortcutIcon(app, 60.dp) }
        Text(app.name, color = Palette.TextDim, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AddChip(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Palette.Stroke, RoundedCornerShape(18.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Edit, "Edit shortcuts", tint = Palette.TextDim) }
        Text("Edit", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun CircleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Palette.Text, container: Color = Palette.Surface, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier.size(60.dp).clip(CircleShape).background(container).border(1.dp, Palette.Stroke.copy(alpha = 0.7f), CircleShape)
            .clickable { haptic(view); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint) }
}

/** Tap = start/stop voice search; hold = push-to-talk. */
@Composable
private fun MicButton(voice: Voice, actions: RemoteActions) {
    val view = LocalView.current
    val active = voice != Voice.IDLE
    val pulse = if (voice == Voice.LISTENING) pulseScale() else 1f
    Box(
        Modifier.size(60.dp).scale(pulse).clip(CircleShape)
            .background(if (active) Palette.Accent else Palette.Accent.copy(alpha = 0.16f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val t0 = System.currentTimeMillis()
                    haptic(view)
                    actions.voicePress()
                    waitForUpOrCancellation()
                    actions.voiceRelease(System.currentTimeMillis() - t0)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Mic, "Voice search", tint = if (active) Color(0xFF0B1200) else Palette.AccentSoft) }
}

/**
 * Live keyboard. Opens automatically when the TV focuses a text box (remote_ime_show_request),
 * prefilled with the TV field's content; every edit is mirrored to the TV immediately.
 */
@Composable
private fun KeyboardPanel(ime: ImeState, actions: RemoteActions, modifier: Modifier, sendOnEnter: Boolean = false) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(ime.text, TextRange(ime.text.length))) }
    // TV pushed new content (field focused / changed on TV): resync the editor.
    // TV focused a (new) field: adopt its text once. Never while typing (tvVersion only bumps on open),
    // so the phone keyboard's composing text can't be replayed into a feedback loop.
    LaunchedEffect(ime.tvVersion) {
        if (ime.text != value.text) value = TextFieldValue(ime.text, TextRange(ime.text.length))
    }
    LaunchedEffect(ime.visible) { if (!ime.visible) keyboard?.hide() }
    AnimatedVisibility(ime.visible, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        // Runs once the editor is actually composed, so the focus request can't race the animation.
        // Runs when the panel appears and again whenever the TV opens a new field.
        LaunchedEffect(ime.tvVersion) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
            keyboard?.show()
            kotlinx.coroutines.delay(250) // some keyboards ignore the first show() during the slide-in
            keyboard?.show()
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Palette.SurfaceHi)
                .border(1.dp, Palette.Stroke, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding().imePadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (ime.fromTv) "Typing on TV" + (ime.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else "Send text to TV") + if (sendOnEnter) " · sends on Enter" else "",
                    color = Palette.TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { actions.closeKeyboard() }) { Icon(Icons.Rounded.Close, "Close keyboard", tint = Palette.TextDim) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { nv ->
                        val old = value.text
                        value = nv
                        actions.imeEdit(old, nv.text, nv.selection.end)
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text(ime.label.ifBlank { "Type here — it appears on the TV live" }, color = Palette.TextDim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onAny = { actions.imeEnter() }),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, cursorColor = Palette.Accent),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Accent)
                        .clickable { actions.imeEnter() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter", tint = Color(0xFF0B1200)) }
            }
        }
    }
}
