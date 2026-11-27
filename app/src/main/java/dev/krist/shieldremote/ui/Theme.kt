package dev.krist.shieldremote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    // Bg / BgTop / Accent / AccentSoft follow Settings › Appearance (state-backed, so the UI recomposes).
    var Bg by mutableStateOf(Color(0xFF35363A))      // Google TV remote: flat medium-dark grey
    var BgTop by mutableStateOf(Color(0xFF35363A))
    val Surface = Color(0xFF202124)                   // buttons, D-pad ring, sheets
    val SurfaceHi = Color(0xFF2A2B2F)
    val Stroke = Color(0xFF45474B)
    var Accent by mutableStateOf(Color(0xFF81C995)) // Google green
    var AccentSoft by mutableStateOf(Color(0xFFA8DAB5))

    fun apply(accent: Int, amoled: Boolean) {
        val a = dev.krist.shieldremote.data.Settings.ACCENTS.getOrElse(accent) { dev.krist.shieldremote.data.Settings.ACCENTS[0] }
        Accent = Color(a.second); AccentSoft = Color(a.third)
        Bg = if (amoled) Color(0xFF000000) else Color(0xFF35363A)
        BgTop = if (amoled) Color(0xFF000000) else Color(0xFF35363A)
    }
    val Warn = Color(0xFFFFB020)
    val Danger = Color(0xFFF28B82)
    /** Status-dot colors on the SHIELD pill: on / asleep / off-or-disconnected. */
    val StatusOn = Color(0xFF12B347)
    val StatusAsleep = Color(0xFFFFC107)
    val StatusOff = Color(0xFFE53935)
    val Text = Color(0xFFF1F3F4)
    val TextDim = Color(0xFFBDC1C6)
}

@Composable
private fun scheme() = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF0B1200),
    secondary = Palette.AccentSoft,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHi,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHi,
    surfaceContainerHighest = Palette.SurfaceHi,
    outline = Palette.Stroke,
    error = Palette.Danger,
)

private val typography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.3.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
)

/**
 * Always the dark scheme (ignores system light mode and Samsung/Material You dynamic colour), and
 * every screen sits in a Surface so LocalContentColor is the light text colour. Without the Surface,
 * Text with no explicit colour falls back to LocalContentColor's default of black.
 */
@Composable
fun ShieldTheme(textScale: Float = 1f, content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme(), typography = typography) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * textScale)
    ) {
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = Palette.Bg,
            contentColor = Palette.Text,
        ) { content() }
    }
}
