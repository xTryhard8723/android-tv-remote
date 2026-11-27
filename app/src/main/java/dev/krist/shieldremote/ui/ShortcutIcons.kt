package dev.krist.shieldremote.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krist.shieldremote.R
import dev.krist.shieldremote.data.Shortcut

/**
 * Shortcut icons. Brand tiles use the official app icons (drawable-nodpi/ic_app_*), generic Material
 * icons, or a picture from the gallery copied into app storage.
 * Stored in Shortcut.icon as "" (auto), "brand:<id>", "mi:<id>" or "file:<absolute path>".
 * Existing "brand:<id>" values keep the same IDs so saved shortcuts pick up the new logos automatically.
 */
object ShortcutIcons {
    data class Brand(
        val id: String,
        val label: String,
        val bg: List<Color>,
        val fg: Color,
        val keys: List<String>,
        val textSize: Int = 22,
        @DrawableRes val drawable: Int = 0,
    )

    val brands = listOf(
        Brand("netflix", "N", listOf(Color(0xFF141414), Color(0xFF000000)), Color(0xFFE50914), listOf("netflix", "com.netflix"), 30, R.drawable.ic_app_netflix),
        Brand("jellyfin", "JF", listOf(Color(0xFFAA5CC3), Color(0xFF00A4DC)), Color.White, listOf("jellyfin"), 22, R.drawable.ic_app_jellyfin),
        Brand("wholphin", "W", listOf(Color(0xFF9B7BFF), Color(0xFF5B2FE0)), Color.White, listOf("wholphin"), 28, R.drawable.ic_app_wholphin),
        Brand("plex", "plex", listOf(Color(0xFF282A2D), Color(0xFF1F2023)), Color(0xFFE5A00D), listOf("plex"), 17, R.drawable.ic_app_plex),
        Brand("youtube", "▶", listOf(Color(0xFFFF3B3B), Color(0xFFFF0033)), Color.White, listOf("youtube", "com.google.android.youtube"), 26, R.drawable.ic_app_youtube),
        Brand("smarttube", "ST", listOf(Color(0xFFEF5350), Color(0xFFB71C1C)), Color.White, listOf("smarttube", "smart tube", "liskovsoft", "teamsmart", "org.smarttube"), 22, R.drawable.ic_app_smarttube),
        Brand("kodi", "K", listOf(Color(0xFF3FC3F0), Color(0xFF17B2E7)), Color.White, listOf("kodi", "org.xbmc"), 28, R.drawable.ic_app_kodi),
        Brand("disney", "D+", listOf(Color(0xFF1A3FD6), Color(0xFF0B1D6B)), Color.White, listOf("disney"), 22, R.drawable.ic_app_disney),
        Brand("prime", "prime", listOf(Color(0xFF1EB6EE), Color(0xFF00A8E1)), Color.White, listOf("prime", "amazon"), 16, R.drawable.ic_app_prime),
        Brand("spotify", "S", listOf(Color(0xFF1ED760), Color(0xFF1DB954)), Color(0xFF111111), listOf("spotify"), 28, R.drawable.ic_app_spotify),
        Brand("twitch", "T", listOf(Color(0xFFA36BFF), Color(0xFF9146FF)), Color.White, listOf("twitch"), 28, R.drawable.ic_app_twitch),
        Brand("hbo", "max", listOf(Color(0xFF2A4BFF), Color(0xFF002BE7)), Color.White, listOf("hbo", "max", "com.wbd"), 18, R.drawable.ic_app_hbo),
    )

    val generic: List<Pair<String, ImageVector>> = listOf(
        "movie" to Icons.Rounded.Movie, "tv" to Icons.Rounded.Tv, "music" to Icons.Rounded.MusicNote,
        "game" to Icons.Rounded.SportsEsports, "sports" to Icons.Rounded.SportsSoccer, "kids" to Icons.Rounded.ChildCare,
        "live" to Icons.Rounded.LiveTv, "settings" to Icons.Rounded.Settings, "star" to Icons.Rounded.Star, "home" to Icons.Rounded.Home,
    )

    /** Auto-match by app name / package; null = plain initial tile. */
    fun auto(s: Shortcut): String? {
        val hay = (s.name + " " + s.packageName).lowercase()
        // check SmartTube before YouTube (its name has "tube")
        return brands.sortedByDescending { it.id == "smarttube" }.firstOrNull { b -> b.keys.any { k -> if (k == "max") Regex("\\bmax\\b").containsMatchIn(hay) else hay.contains(k) } }?.let { "brand:${it.id}" }
    }

    fun resolve(s: Shortcut): String = s.icon.ifBlank { auto(s) ?: "" }
}

@Composable
fun ShortcutIcon(s: Shortcut, size: Dp = 60.dp, iconOverride: String? = null) {
    val spec = iconOverride ?: ShortcutIcons.resolve(s)
    val shape = RoundedCornerShape(size * 0.3f)
    val base = Color(s.color.toInt())
    when {
        spec.startsWith("brand:") -> {
            val b = ShortcutIcons.brands.firstOrNull { it.id == spec.removePrefix("brand:") }
            if (b != null) {
                if (b.drawable != 0) {
                    Image(
                        painterResource(b.drawable),
                        contentDescription = s.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(size).clip(shape),
                    )
                    return
                }
                Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(b.bg)), contentAlignment = Alignment.Center) {
                    Text(b.label, color = b.fg, fontWeight = FontWeight.Black, fontSize = (b.textSize * size.value / 60f).sp, maxLines = 1)
                }
                return
            }
        }
        spec.startsWith("mi:") -> {
            val v = ShortcutIcons.generic.firstOrNull { it.first == spec.removePrefix("mi:") }?.second
            if (v != null) {
                Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(base.copy(alpha = 0.85f), base))), contentAlignment = Alignment.Center) {
                    Icon(v, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
                }
                return
            }
        }
        spec.startsWith("file:") -> {
            val path = spec.removePrefix("file:")
            val bmp = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
            if (bmp != null) {
                Image(bmp, s.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
                return
            }
        }
    }
    Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(base.copy(red = minOf(1f, base.red + 0.15f)), base))), contentAlignment = Alignment.Center) {
        Text(s.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (24 * size.value / 60f).sp)
    }
}

/** Icon library for the shortcut editor. [selected] "" = auto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconPicker(s: Shortcut, selected: String, onPick: (String) -> Unit, onGallery: () -> Unit) {
    Column {
        Text("Icon", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
        FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun cell(spec: String, content: @Composable () -> Unit) {
                val sel = selected == spec
                Box(Modifier.clip(RoundedCornerShape(16.dp)).border(if (sel) 3.dp else 0.dp, if (sel) Palette.Accent else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onPick(spec) }.padding(3.dp)) { content() }
            }
            cell("") {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Palette.Surface), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.AccentSoft, modifier = Modifier.size(18.dp))
                        Text("Auto", color = Palette.TextDim, fontSize = 10.sp)
                    }
                }
            }
            ShortcutIcons.brands.forEach { b -> cell("brand:${b.id}") { ShortcutIcon(s, 44.dp, "brand:${b.id}") } }
            ShortcutIcons.generic.forEach { (id, _) -> cell("mi:$id") { ShortcutIcon(s, 44.dp, "mi:$id") } }
            if (selected.startsWith("file:")) cell(selected) { ShortcutIcon(s, 44.dp, selected) }
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface).border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp)).clickable { onGallery() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.PhotoLibrary, "Choose from gallery", tint = Palette.Text, modifier = Modifier.size(20.dp))
                    Text("Gallery", color = Palette.TextDim, fontSize = 10.sp)
                }
            }
        }
    }
}
