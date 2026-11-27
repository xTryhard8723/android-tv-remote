package dev.krist.shieldremote.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a held button behaves. */
enum class Hold {
    /** Tap = SHORT; hold past threshold = START_LONG ... END_LONG (e.g. OK, Home, Power). */
    LONG_PRESS,
    /** Tap = SHORT; hold = repeated SHORT presses (D-pad arrows, volume, seek). */
    REPEAT,
    /** Always a single SHORT on press. */
    SINGLE,
}

/** Callbacks a press gesture produces; [RemoteActions] maps them to key messages. */
interface KeySink {
    fun short(code: Int)
    fun down(code: Int)
    fun up(code: Int)
}

fun haptic(view: View?, long: Boolean = false) = Haptics.play(view, if (long) Haptics.Kind.LONG else Haptics.Kind.PRESS)
fun hapticTick(view: View?) = Haptics.play(view, Haptics.Kind.TICK)

/** Press timings; updated live from Settings › Remote layout. */
@Volatile var LONG_PRESS_MS = 450L
@Volatile var REPEAT_START_MS = 380L
@Volatile var REPEAT_EVERY_MS = 95L
/** Touchpad swipe sensitivity multiplier (higher = shorter swipe per step). */
@Volatile var TOUCHPAD_SENSITIVITY = 1f

/**
 * Runs the tap / long-press / auto-repeat state machine for one press. The SHORT for a tap is
 * sent on release for LONG_PRESS (so a hold can become START_LONG instead) and on touch-down for
 * the others, which makes them feel instantaneous.
 */
suspend fun runPress(code: Int, hold: Hold, sink: KeySink, view: View, awaitRelease: suspend () -> Unit) = coroutineScope {
    haptic(view)
    when (hold) {
        Hold.SINGLE -> { sink.short(code); awaitRelease() }
        Hold.REPEAT -> {
            sink.short(code)
            val job = launch {
                delay(REPEAT_START_MS)
                while (true) { sink.short(code); hapticTick(view); delay(REPEAT_EVERY_MS) }
            }
            awaitRelease(); job.cancel()
        }
        Hold.LONG_PRESS -> {
            var longStarted = false
            val job = launch { delay(LONG_PRESS_MS); longStarted = true; haptic(view, true); sink.down(code) }
            awaitRelease(); job.cancel()
            if (longStarted) sink.up(code) else sink.short(code)
        }
    }
}

fun Modifier.remoteKey(
    code: Int, hold: Hold, sink: KeySink, view: View, scope: kotlinx.coroutines.CoroutineScope, onPressed: (Boolean) -> Unit,
): Modifier = pointerInput(code, hold) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        onPressed(true)
        val released = kotlinx.coroutines.CompletableDeferred<Unit>()
        scope.launch {
            runPress(code, hold, sink, view) { released.await() }
        }
        waitForUpOrCancellation()
        released.complete(Unit)
        onPressed(false)
    }
}
