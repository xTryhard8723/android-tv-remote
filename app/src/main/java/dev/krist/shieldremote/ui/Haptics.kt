package dev.krist.shieldremote.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** Haptic feedback with a user-selectable strength (0 off, 1 light, 2 medium, 3 strong). */
object Haptics {
    @Volatile var level = 2
    /** Click sound on every press (Settings › Feedback); plays even if system touch sounds are off. */
    @Volatile var sound = false
    private var vibrator: Vibrator? = null
    private var audio: android.media.AudioManager? = null

    fun init(context: Context, level: Int, sound: Boolean = false) {
        this.level = level; this.sound = sound
        audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }

    enum class Kind { TICK, PRESS, LONG }

    fun play(view: View?, kind: Kind) {
        if (sound && kind != Kind.TICK) audio?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f)
        val lvl = level
        if (lvl <= 0) return
        val v = vibrator
        if (Build.VERSION.SDK_INT >= 29 && v != null && v.hasVibrator()) {
            val effect = when (kind) {
                Kind.TICK -> VibrationEffect.EFFECT_TICK
                Kind.PRESS -> when (lvl) { 1 -> VibrationEffect.EFFECT_TICK; 2 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK }
                Kind.LONG -> if (lvl == 1) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK
            }
            v.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            view?.performHapticFeedback(
                when (kind) { Kind.TICK -> HapticFeedbackConstants.CLOCK_TICK; Kind.PRESS -> HapticFeedbackConstants.VIRTUAL_KEY; Kind.LONG -> HapticFeedbackConstants.LONG_PRESS }
            )
        }
    }
}
