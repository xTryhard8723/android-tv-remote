package dev.krist.shieldremote.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedDevice(val id: String, val host: String, val name: String, val serviceName: String?)

/** An app shortcut: optional deep link, optional package (launched via intent / market fallbacks). */
data class Shortcut(val name: String, val link: String, val color: Long, val pkg: String = "", val keys: String = "", val icon: String = "") {
    val packageName: String get() = pkg.ifBlank { Launch.packageFromMarket(link) ?: "" }
}

/** Ordered launch attempts for an app shortcut. */
object Launch {
    fun marketUri(pkg: String) = "market://launch?id=$pkg"
    fun packageFromMarket(link: String): String? =
        Regex("^market://launch\\?id=([A-Za-z0-9_.]+)").find(link.trim())?.groupValues?.get(1)

    /**
     * Links the Shield's remote service can open. It resolves an app link as ACTION_VIEW of the URI, so:
     * the app's own deep link first (custom scheme, e.g. wholphin://), then market://launch?id=pkg, which
     * goes through the Play Store (broken by Play Store 52.x since Aug 2026 unless a market:// handler such
     * as AVA Connect is installed). "intent:" URIs are not parsed by the TV and make it drop the session.
     */
    fun candidates(s: Shortcut, preferred: String? = null, rejected: Set<String> = emptySet()): List<String> {
        if (isLinkless(s)) return emptyList() // e.g. SmartTube: the Shield rejects every link and drops the session
        val pkg = s.packageName
        val out = mutableListOf<String>()
        if (s.link.isNotBlank() && packageFromMarket(s.link) == null) out += s.link.trim()
        AppCatalog.byPackage(pkg)?.link?.takeIf { it.isNotBlank() && it !in out }?.let { out += it }
        // No market:// fallback: the Play Store no longer launches apps and the Shield answers the
        // rejected link with an error + disconnect. Apps without a deep link use a key sequence.
        out.retainAll { isValidLink(it) && it !in rejected }
        if (preferred != null && preferred in out) { out.remove(preferred); out.add(0, preferred) }
        return out
    }

    /** Apps the Shield cannot open via app link (verified on the user's Shield: smarttube:// is rejected). */
    val LINKLESS_PACKAGES = setOf("com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta")
    fun isLinkless(s: Shortcut): Boolean =
        s.packageName in LINKLESS_PACKAGES || s.name.lowercase().replace(" ", "").contains("smarttube") ||
            s.link.lowercase().replace(" ", "").startsWith("smarttube")

    /** A link the TV can parse: lowercase scheme, no spaces, not intent:/market: (those make it drop the session). */
    fun isValidLink(l: String): Boolean {
        val t = l.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        if (!Regex("^[a-z][a-z0-9+.\\-]*:.+").matches(t)) return false
        val scheme = t.substringBefore(':')
        return scheme != "intent" && scheme != "market" && scheme != "javascript" && scheme != "file"
    }

    /** Human-readable launch method for the shortcut list / editor. */
    fun describe(s: Shortcut, working: String? = null): String = when {
        s.keys.isNotBlank() -> "Key sequence: ${s.keys}"
        else -> {
            val c = candidates(s, working)
            when {
                isLinkless(s) -> "Can't open by link: record a key sequence"
                c.isEmpty() && s.packageName.isNotBlank() -> "No deep link: tap to record a key sequence"
                c.isEmpty() -> "Not set"
                working != null && working == c.first() -> "Works via ${c.first()}"
                c.first().startsWith("market://") -> "Play Store link (needs AVA Connect on the Shield)"
                else -> "Deep link ${c.first()}"
            }
        }
    }

    /** Fixes near-miss spellings of known package names (e.g. com.github.damonteceres.wholphin). */
    fun correctPackage(pkg: String): String {
        val p = pkg.trim()
        if (p.isBlank() || AppCatalog.byPackage(p) != null) return p
        val best = AppCatalog.apps.minByOrNull { distance(it.pkg.lowercase(), p.lowercase()) } ?: return p
        return if (distance(best.pkg.lowercase(), p.lowercase()) <= 3) best.pkg else p
    }

    fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }
}

/** Key-sequence shortcuts: "HOME, DOWN, RIGHT x3, OK" (names, optional xN repeat, optional wait500). */
object KeyMacro {
    private fun tokens(t: String) = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Appends a recorded key, folding repeats into "RIGHT x3". */
    fun append(seq: String, key: String): String {
        val tk = tokens(seq).toMutableList()
        val last = tk.lastOrNull()
        val m = last?.let { Regex("^([A-Za-z]+)(?:\\s*x(\\d+))?$").find(it) }
        if (m != null && m.groupValues[1].equals(key, true) && !key.startsWith("wait")) {
            tk[tk.size - 1] = "$key x${(m.groupValues[2].toIntOrNull() ?: 1) + 1}"
        } else tk += key
        return tk.joinToString(", ")
    }

    fun dropLast(seq: String): String = tokens(seq).dropLast(1).joinToString(", ")

    private val names = mapOf(
        "HOME" to 3, "BACK" to 4, "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "OK" to 23, "CENTER" to 23, "ENTER" to 66,
        "MENU" to 82, "PLAY" to 85, "PLAYPAUSE" to 85, "SEARCH" to 84, "SETTINGS" to 176, "APPS" to 284, "POWER" to 26,
        "VOLUP" to 24, "VOLDOWN" to 25, "MUTE" to 164,
    )
    sealed class Step { data class Key(val code: Int) : Step(); data class Wait(val ms: Long) : Step() }

    /** Returns null if any token is unknown. */
    fun parse(text: String): List<Step>? {
        val out = mutableListOf<Step>()
        for (raw in text.split(',', ';', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }) {
            val w = Regex("^WAIT\\s*(\\d+)$").find(raw)
            if (w != null) { out += Step.Wait(w.groupValues[1].toLong().coerceIn(50, 5000)); continue }
            val m = Regex("^([A-Z_]+)\\s*(?:[X×*]\\s*(\\d+))?$").find(raw) ?: return null
            val code = names[m.groupValues[1].replace("_", "")] ?: return null
            repeat((m.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 20)) { out += Step.Key(code) }
        }
        return out
    }
}

/** Common Android TV apps for the shortcut picker. */
object AppCatalog {
    data class App(val name: String, val pkg: String, val link: String = "", val color: Long)
    val apps = listOf(
        App("Wholphin", "com.github.damontecres.wholphin", "wholphin://", 0xFF7B4DFF),
        App("Jellyfin", "org.jellyfin.androidtv", color = 0xFF00A4DC),
        App("Kodi", "org.xbmc.kodi", color = 0xFF17B2E7),
        App("SmartTube", "com.teamsmart.videomanager.tv", color = 0xFFE53935),
        App("Stremio", "com.stremio.one", color = 0xFF8A5AAB),
        App("VLC", "org.videolan.vlc", color = 0xFFFF8800),
        App("Twitch", "tv.twitch.android.app", "twitch://home", 0xFF9146FF),
        App("HBO Max", "com.wbd.stream", color = 0xFF002BE7),
        App("Netflix", "com.netflix.ninja", "netflix://", 0xFFE50914),
        App("YouTube", "com.google.android.youtube.tv", "https://www.youtube.com", 0xFFFF0033),
        App("Plex", "com.plexapp.android", "plex://", 0xFFE5A00D),
        App("Disney+", "com.disney.disneyplus", "https://www.disneyplus.com", 0xFF113CCF),
        App("Prime Video", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com", 0xFF00A8E1),
        App("Spotify", "com.spotify.tv.android", color = 0xFF1DB954),
        App("GeForce NOW", "com.nvidia.geforcenow", color = 0xFF76B900),
        App("Moonlight", "com.limelight", color = 0xFF3B82F6),
        App("Steam Link", "com.valvesoftware.steamlink", color = 0xFF1B2838),
        App("Apple TV", "com.apple.atve.androidtv.appletv", color = 0xFF555555),
    )
    fun byPackage(pkg: String): App? = apps.firstOrNull { it.pkg == pkg }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("shield_remote", Context.MODE_PRIVATE)

    var devices: List<SavedDevice>
        get() = runCatching {
            val a = JSONArray(sp.getString("devices", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SavedDevice(o.getString("id"), o.getString("host"), o.getString("name"), o.optString("service").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("id", it.id).put("host", it.host).put("name", it.name).put("service", it.serviceName ?: "")) }
            sp.edit().putString("devices", a.toString()).apply()
        }

    var activeId: String?
        get() = sp.getString("active", null)
        set(v) = sp.edit().putString("active", v).apply()

    val activeDevice: SavedDevice? get() = devices.let { list -> list.firstOrNull { it.id == activeId } ?: list.firstOrNull() }

    fun upsert(host: String, name: String, serviceName: String?): SavedDevice {
        val list = devices.toMutableList()
        val idx = list.indexOfFirst { (serviceName != null && it.serviceName == serviceName) || it.host == host }
        val dev = if (idx >= 0) list[idx].copy(host = host, serviceName = serviceName ?: list[idx].serviceName)
        else SavedDevice(UUID.randomUUID().toString(), host, name, serviceName)
        if (idx >= 0) list[idx] = dev else list += dev
        devices = list
        activeId = dev.id
        return dev
    }

    fun update(dev: SavedDevice) { devices = devices.map { if (it.id == dev.id) dev else it } }
    fun remove(id: String) { devices = devices.filterNot { it.id == id }; if (activeId == id) activeId = devices.firstOrNull()?.id }

    var shortcuts: List<Shortcut>
        get() {
            val raw = sp.getString("shortcuts", null) ?: return DEFAULT_SHORTCUTS
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    val link = o.optString("link")
                    val pkg = Launch.correctPackage(o.optString("pkg").ifBlank { Launch.packageFromMarket(link) ?: "" })
                    // migrate 1.0.0 package shortcuts ("market://launch?id=pkg") to package + no link
                    val raw = Shortcut(o.getString("name"), if (Launch.packageFromMarket(link) != null) "" else link, o.getLong("color"), pkg, o.optString("keys"), o.optString("icon"))
                    // 1.0.5: drop links the Shield can't take (e.g. "smart tube://") and links for link-less apps
                    if (raw.link.isNotBlank() && (!Launch.isValidLink(raw.link) || Launch.isLinkless(raw))) raw.copy(link = "") else raw
                } }
            }.getOrDefault(DEFAULT_SHORTCUTS)
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("link", it.link).put("color", it.color).put("pkg", it.pkg).put("keys", it.keys).put("icon", it.icon)) }
            sp.edit().putString("shortcuts", a.toString()).apply()
        }

    /** All user settings; migrates the 1.0.0 individual keys on first read. */
    var settings: Settings
        get() {
            sp.getString("settings", null)?.let { raw -> runCatching { return Settings.fromJson(JSONObject(raw)) } }
            return Settings(
                touchpad = sp.getBoolean("touchpad", false),
                haptics = sp.getInt("haptics", 2),
                graceMinutes = sp.getInt("grace_min", 3),
            )
        }
        set(v) = sp.edit().putString("settings", v.toJson().toString()).apply()

    /** Settings + shortcuts as a JSON document (export / import). */
    fun exportJson(): String = JSONObject()
        .put("app", "ShieldRemote").put("format", 1)
        .put("settings", settings.toJson())
        .put("shortcuts", JSONArray().also { a -> shortcuts.forEach { a.put(JSONObject().put("name", it.name).put("link", it.link).put("color", it.color).put("pkg", it.pkg).put("keys", it.keys).put("icon", it.icon)) } })
        .toString(2)

    /** Returns the imported settings, or null if the document isn't a ShieldRemote export. */
    fun importJson(text: String): Settings? = runCatching {
        val o = JSONObject(text)
        val st = Settings.fromJson(o.getJSONObject("settings"))
        o.optJSONArray("shortcuts")?.let { a ->
            shortcuts = (0 until a.length()).map { i -> a.getJSONObject(i).let { Shortcut(it.getString("name"), it.optString("link"), it.getLong("color"), Launch.correctPackage(it.optString("pkg")), it.optString("keys"), it.optString("icon")) } }
        }
        settings = st
        st
    }.getOrNull()

    fun launchMethod(pkg: String): String? = sp.getString("launch_$pkg", null)
    /** Links the TV answered with an error: never sent automatically again. */
    val rejectedLinks: Set<String> get() = sp.getStringSet("rejected_links", emptySet()) ?: emptySet()
    fun addRejectedLink(uri: String) = sp.edit().putStringSet("rejected_links", rejectedLinks + uri).apply()
    fun setLaunchMethod(pkg: String, uri: String) = sp.edit().putString("launch_$pkg", uri).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()

    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            Shortcut("Netflix", "netflix://", 0xFFE50914, "com.netflix.ninja"),
            Shortcut("YouTube", "https://www.youtube.com", 0xFFFF0033, "com.google.android.youtube.tv"),
            Shortcut("Plex", "plex://", 0xFFE5A00D, "com.plexapp.android"),
            Shortcut("Disney+", "https://www.disneyplus.com", 0xFF113CCF, "com.disney.disneyplus"),
            Shortcut("Prime Video", "https://app.primevideo.com", 0xFF00A8E1, "com.amazon.amazonvideo.livingroom"),
            Shortcut("Spotify", "", 0xFF1DB954, "com.spotify.tv.android"),
        )

        /** Accepts a deep link (scheme://...) or a bare package name. */
        fun normalizeLink(input: String): String {
            val t = input.trim()
            return if (t.contains("://") || t.startsWith("market:")) t else "market://launch?id=$t"
        }
    }
}
