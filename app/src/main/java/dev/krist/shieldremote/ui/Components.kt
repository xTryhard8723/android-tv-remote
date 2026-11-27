package dev.krist.shieldremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.krist.shieldremote.Link

/** A remote button: haptics, press animation, tap/long-press/repeat semantics. */
@Composable
fun RemoteButton(
    code: Int,
    sink: KeySink,
    modifier: Modifier = Modifier,
    hold: Hold = Hold.LONG_PRESS,
    shape: Shape = CircleShape,
    container: Color = Palette.Surface,
    content: Color = Palette.Text,
    icon: ImageVector? = null,
    label: String? = null,
    iconSize: Dp = 30.dp,
    contentDescription: String? = label,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "s")
    val bg by animateColorAsState(if (pressed) container.lighten(0.12f) else container, tween(90), label = "bg")
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .remoteKey(code, hold, sink, view, scope) { pressed = it },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription, tint = content, modifier = Modifier.size(iconSize))
            if (icon != null && label != null) Spacer(Modifier.width(8.dp))
            if (label != null) Text(label, color = content, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

@Composable
fun StatusPill(link: Link, queued: Int, modifier: Modifier = Modifier) {
    val (color, text) = when (link) {
        Link.CONNECTED -> Palette.Accent to "Connected"
        Link.CONNECTING -> Palette.Warn to "Connecting"
        Link.RECONNECTING -> Palette.Warn to if (queued > 0) "Reconnecting · $queued queued" else "Reconnecting"
        Link.NO_WIFI -> Palette.Danger to "No Wi-Fi"
        Link.NEEDS_PAIRING -> Palette.Danger to "Pairing needed"
        Link.PAUSED -> Palette.TextDim to "Idle"
        Link.NO_DEVICE -> Palette.TextDim to "No device"
    }
    val pulsing = link == Link.CONNECTING || link == Link.RECONNECTING
    val a = if (pulsing) pulseAlpha() else 1f
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = a)))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Infinite animations only exist while needed (keeps the UI idle, and battery happy, otherwise). */
@Composable
fun pulseAlpha(): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a")
    return a
}

@Composable
fun pulseScale(): Float {
    val t = rememberInfiniteTransition(label = "mic")
    val p by t.animateFloat(1f, 1.12f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
    return p
}
