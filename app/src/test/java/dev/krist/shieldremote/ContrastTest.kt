package dev.krist.shieldremote

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast of the palette's text/background pairs (AA = 4.5:1 for normal text). */
class ContrastTest {
    private fun lum(argb: Long): Double {
        fun ch(v: Long): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }
    private fun ratio(a: Long, b: Long): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
    private fun blend(fg: Long, bg: Long, alpha: Double): Long {
        fun c(s: Int) = ((((fg shr s) and 0xFF) * alpha + ((bg shr s) and 0xFF) * (1 - alpha)).toLong() and 0xFF) shl s
        return c(16) or c(8) or c(0)
    }

    private val bg = 0xFF35363A; private val bgTop = 0xFF35363A; private val surface = 0xFF202124; private val surfaceHi = 0xFF2A2B2F
    private val text = 0xFFE8EAED; private val dim = 0xFFBDC1C6; private val accent = 0xFF81C995; private val accentSoft = 0xFFA8DAB5
    private val danger = 0xFFF28B82; private val warn = 0xFFFFB020; private val onPrimary = 0xFF0B1200

    @Test fun accentChoicesMeetAA() {
        for ((name, a, soft) in dev.krist.shieldremote.data.Settings.ACCENTS) {
            for ((what, r) in listOf("accent/surfaceHi" to ratio(a, surfaceHi), "soft/surface" to ratio(soft, surface), "dark-on-accent" to ratio(0xFF0B0F14, a))) {
                assertTrue("$name $what only %.2f:1".format(r), r >= 4.5)
            }
        }
    }

    @Test fun textPairsMeetAA() {
        val pairs = mapOf(
            "text/bg" to (text to bg), "text/bgTop" to (text to bgTop), "text/surface" to (text to surface), "text/surfaceHi" to (text to surfaceHi),
            "dim/bg" to (dim to bg), "dim/surface" to (dim to surface), "dim/surfaceHi" to (dim to surfaceHi),
            "accent/bg" to (accent to bg), "accentSoft/surface" to (accentSoft to surface), "danger/bg" to (danger to bg),
            "warn/bg" to (warn to bg), "onPrimary/accent" to (onPrimary to accent), "pillText/accent" to (0xFF0D2416 to accent), "danger/surface" to (danger to surface),
            "placeholder/bg" to (blend(dim, bg, 0.8) to bg),
        )
        for ((name, p) in pairs) {
            val r = ratio(p.first, p.second)
            println("contrast %-20s %.2f:1".format(name, r))
            assertTrue("$name only %.2f:1".format(r), r >= 4.5)
        }
    }
}
