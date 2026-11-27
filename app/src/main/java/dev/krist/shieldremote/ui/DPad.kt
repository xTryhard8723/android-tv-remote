package dev.krist.shieldremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.krist.shieldremote.proto.Remote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private const val NONE = -1

/** Circular D-pad: four arc segments + centre OK. Arrows auto-repeat when held, OK supports long-press. */
@Composable
fun DPad(sink: KeySink, diameter: Dp, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableIntStateOf(NONE) }
    val glow by animateFloatAsState(if (active != NONE) 1f else 0f, tween(120), label = "glow")
    val innerFrac = 0.42f

    Box(
        modifier
            .size(diameter)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val d = down.position - c
                    val r = hypot(d.x, d.y)
                    val outer = size.width / 2f
                    if (r > outer * 1.02f) return@awaitEachGesture
                    val code = if (r < outer * innerFrac) Remote.Key.DPAD_CENTER else {
                        val ang = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())) // 0 = right, 90 = down
                        when {
                            ang >= -45 && ang < 45 -> Remote.Key.DPAD_RIGHT
                            ang >= 45 && ang < 135 -> Remote.Key.DPAD_DOWN
                            ang >= -135 && ang < -45 -> Remote.Key.DPAD_UP
                            else -> Remote.Key.DPAD_LEFT
                        }
                    }
                    active = code
                    val released = CompletableDeferred<Unit>()
                    scope.launch {
                        runPress(code, if (code == Remote.Key.DPAD_CENTER) Hold.LONG_PRESS else Hold.REPEAT, sink, view) { released.await() }
                    }
                    waitForUpOrCancellation()
                    released.complete(Unit)
                    active = NONE
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2f
            val center = Offset(size.width / 2, size.height / 2)
            // Ring body
            drawCircle(Palette.Surface, radius = outer, center = center) // flat, borderless (Google TV style)
            // Active segment highlight
            val startAngle = when (active) {
                Remote.Key.DPAD_RIGHT -> -45f
                Remote.Key.DPAD_DOWN -> 45f
                Remote.Key.DPAD_LEFT -> 135f
                Remote.Key.DPAD_UP -> 225f
                else -> null
            }
            if (startAngle != null) {
                val ringW = outer * (1 - innerFrac)
                drawArc(
                    Palette.Accent.copy(alpha = 0.22f * glow), startAngle, 90f, useCenter = false,
                    topLeft = Offset(center.x - outer + ringW / 2, center.y - outer + ringW / 2),
                    size = Size((outer - ringW / 2) * 2, (outer - ringW / 2) * 2),
                    style = Stroke(ringW),
                )
            }
            // Separators
            val inner = outer * innerFrac
            for (a in listOf(45.0, 135.0, 225.0, 315.0)) {
                val rad = Math.toRadians(a)
                val s = Offset(center.x + (inner + 6) * Math.cos(rad).toFloat(), center.y + (inner + 6) * Math.sin(rad).toFloat())
                val e = Offset(center.x + (outer - 10) * Math.cos(rad).toFloat(), center.y + (outer - 10) * Math.sin(rad).toFloat())
                if (false) drawLine(Palette.Stroke, s, e, strokeWidth = 2f) // no separators in the flat style
            }
            // Centre OK
            val okPressed = active == Remote.Key.DPAD_CENTER
            drawCircle(if (okPressed) Palette.Accent else Color(0xFF4A4B4F), radius = inner, center = center)
        }
        val arrowPad = diameter * 0.06f
        val tint = { code: Int -> if (active == code) Palette.AccentSoft else Color(0xFFBDC1C6) }
        Icon(Icons.Rounded.KeyboardArrowUp, "Up", tint = tint(Remote.Key.DPAD_UP), modifier = Modifier.align(Alignment.TopCenter).padding(top = arrowPad).size(52.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, "Down", tint = tint(Remote.Key.DPAD_DOWN), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = arrowPad).size(52.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Left", tint = tint(Remote.Key.DPAD_LEFT), modifier = Modifier.align(Alignment.CenterStart).padding(start = arrowPad).size(52.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Right", tint = tint(Remote.Key.DPAD_RIGHT), modifier = Modifier.align(Alignment.CenterEnd).padding(end = arrowPad).size(52.dp))
    }
}

/**
 * Touchpad: swipe = D-pad steps (long swipes produce several steps), tap = OK, hold = long OK.
 */
@Composable
fun TouchPad(sink: KeySink, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val stepPx = with(density) { 56.dp.toPx() } / TOUCHPAD_SENSITIVITY.coerceIn(0.5f, 2f)
    val slop = with(density) { 12.dp.toPx() }
    var flash by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (flash) 1f else 0f, tween(140), label = "tp")
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Accent.copy(alpha = 0.6f * glow), RoundedCornerShape(32.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    flash = true
                    val start = System.currentTimeMillis()
                    var acc = Offset.Zero
                    var moved = false
                    var longSent = false
                    var steps = 0
                    var twoFinger = false
                    val longJob = scope.launch {
                        kotlinx.coroutines.delay(LONG_PRESS_MS)
                        if (!moved) { longSent = true; haptic(view, true); sink.down(Remote.Key.DPAD_CENTER) }
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (ev.changes.count { it.pressed } > 1) { twoFinger = true; longJob.cancel() }
                        acc += ch.positionChange()
                        ch.consume()
                        if (twoFinger) continue
                        if (!moved && hypot(acc.x, acc.y) > slop && !longSent) { moved = true; longJob.cancel() }
                        if (moved) {
                            while (abs(acc.x) >= stepPx || abs(acc.y) >= stepPx) {
                                val code = if (abs(acc.x) > abs(acc.y)) {
                                    if (acc.x > 0) Remote.Key.DPAD_RIGHT else Remote.Key.DPAD_LEFT
                                } else if (acc.y > 0) Remote.Key.DPAD_DOWN else Remote.Key.DPAD_UP
                                sink.short(code); steps++
                                hapticTick(view)
                                acc = when (code) {
                                    Remote.Key.DPAD_RIGHT -> acc.copy(x = acc.x - stepPx, y = 0f)
                                    Remote.Key.DPAD_LEFT -> acc.copy(x = acc.x + stepPx, y = 0f)
                                    Remote.Key.DPAD_DOWN -> acc.copy(y = acc.y - stepPx, x = 0f)
                                    else -> acc.copy(y = acc.y + stepPx, x = 0f)
                                }
                            }
                        }
                    }
                    longJob.cancel()
                    if (twoFinger) { if (longSent) sink.up(Remote.Key.DPAD_CENTER); haptic(view); sink.short(Remote.Key.BACK) }
                    else if (longSent) sink.up(Remote.Key.DPAD_CENTER)
                    else if (!moved) {
                        haptic(view); sink.short(Remote.Key.DPAD_CENTER)
                    } else if (steps == 0 && System.currentTimeMillis() - start < 400) {
                        // A quick flick shorter than one step still counts as one step.
                        val code = if (abs(acc.x) > abs(acc.y)) {
                            if (acc.x > 0) Remote.Key.DPAD_RIGHT else Remote.Key.DPAD_LEFT
                        } else if (acc.y > 0) Remote.Key.DPAD_DOWN else Remote.Key.DPAD_UP
                        sink.short(code); hapticTick(view)
                    }
                    flash = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.TouchApp, null, tint = Palette.TextDim.copy(alpha = 0.5f), modifier = Modifier.size(44.dp))
            Text("Swipe to move · Tap = OK · Hold = long OK · Two-finger tap = Back", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Palette.TextDim.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp))
        }
    }
}

@Suppress("unused")
private fun Modifier.circle() = this.clip(CircleShape)
