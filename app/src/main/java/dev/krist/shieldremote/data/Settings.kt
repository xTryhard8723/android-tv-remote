package dev.krist.shieldremote.data

import org.json.JSONArray
import org.json.JSONObject

/** Every user-adjustable setting. Persisted as one JSON object (also the export format). */
data class Settings(
    // Notifications
    val notifications: Boolean = true,   // master switch: off = the app posts nothing at all
    val notifRemote: Boolean = true,
    val notifLockScreen: Boolean = true,
    val notifButtons: List<String> = listOf("VOL_DOWN", "PLAY_PAUSE", "VOL_UP", "MUTE", "POWER"),
    val connectionAlerts: Boolean = false,
    // Connection
    val graceMinutes: Int = 3,            // 0 = disconnect on leave, -1 = always while the notification is shown
    val autoConnect: Boolean = true,
    val reconnectOnWifi: Boolean = true,
    val keepScreenOn: Boolean = false,
    val defaultDeviceId: String = "",     // "" = last used
    // Remote layout
    val touchpad: Boolean = false,
    val showMedia: Boolean = true,
    val showVolume: Boolean = true,
    val showExtraKeys: Boolean = true,
    val showShortcuts: Boolean = true,
    val buttonSize: Int = 1,              // 0 compact, 1 normal, 2 large
    val leftHanded: Boolean = false,
    val touchpadSensitivity: Float = 1f,  // 0.5 … 2.0
    val longPressMs: Int = 450,
    val repeatMs: Int = 95,
    // Input
    val volumeKeys: Boolean = true,
    val autoKeyboard: Boolean = true,
    val liveTyping: Boolean = true,       // false = send on Enter
    val keyFallback: Boolean = false,     // type with key events when the TV never reports IME state
    val alwaysKeys: Boolean = false,      // always type with key events
    // Feedback
    val haptics: Int = 2,
    val sound: Boolean = false,
    // Appearance
    val accent: Int = 0,
    val amoled: Boolean = false,
    val textScale: Float = 1f,
    // Voice
    val voiceStreaming: Boolean = true,
    val voiceLive: Boolean = false,      // false = buffered upload after you stop speaking (reliable)   // false = always use the phone's speech recognizer
) {
    /** 0 auto, 1 text edits only, 2 key events only (RemoteController's typing transport). */
    val keyboardMode: Int get() = if (alwaysKeys) 2 else if (keyFallback) 0 else 1

    fun toJson(): JSONObject = JSONObject().apply {
        put("notifications", notifications); put("notifRemote", notifRemote); put("notifLockScreen", notifLockScreen); put("notifButtons", JSONArray(notifButtons)); put("connectionAlerts", connectionAlerts)
        put("graceMinutes", graceMinutes); put("autoConnect", autoConnect); put("reconnectOnWifi", reconnectOnWifi); put("keepScreenOn", keepScreenOn); put("defaultDeviceId", defaultDeviceId)
        put("touchpad", touchpad); put("showMedia", showMedia); put("showVolume", showVolume); put("showExtraKeys", showExtraKeys); put("showShortcuts", showShortcuts)
        put("buttonSize", buttonSize); put("leftHanded", leftHanded); put("touchpadSensitivity", touchpadSensitivity.toDouble()); put("longPressMs", longPressMs); put("repeatMs", repeatMs)
        put("volumeKeys", volumeKeys); put("autoKeyboard", autoKeyboard); put("liveTyping", liveTyping); put("keyFallback2", keyFallback); put("alwaysKeys", alwaysKeys)
        put("haptics", haptics); put("sound", sound); put("accent", accent); put("amoled", amoled); put("textScale", textScale.toDouble()); put("voiceStreaming3", voiceStreaming); put("voiceLive", voiceLive)
    }

    companion object {
        val NOTIF_KEYS = listOf("BACK", "HOME", "UP", "DOWN", "LEFT", "RIGHT", "OK", "PLAY_PAUSE", "VOL_DOWN", "VOL_UP", "MUTE", "POWER")
        const val MAX_NOTIF_BUTTONS = 5

        fun fromJson(o: JSONObject, d: Settings = Settings()): Settings {
            fun b(k: String, v: Boolean) = o.optBoolean(k, v)
            fun i(k: String, v: Int) = o.optInt(k, v)
            fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat()
            val btns = o.optJSONArray("notifButtons")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in NOTIF_KEYS }.take(MAX_NOTIF_BUTTONS) } ?: d.notifButtons
            return Settings(
                b("notifications", d.notifications), b("notifRemote", d.notifRemote), b("notifLockScreen", d.notifLockScreen), btns, b("connectionAlerts", d.connectionAlerts),
                i("graceMinutes", d.graceMinutes).coerceIn(-1, 60), b("autoConnect", d.autoConnect), b("reconnectOnWifi", d.reconnectOnWifi), b("keepScreenOn", d.keepScreenOn), o.optString("defaultDeviceId", d.defaultDeviceId),
                b("touchpad", d.touchpad), b("showMedia", d.showMedia), b("showVolume", d.showVolume), b("showExtraKeys", d.showExtraKeys), b("showShortcuts", d.showShortcuts),
                i("buttonSize", d.buttonSize).coerceIn(0, 2), b("leftHanded", d.leftHanded), f("touchpadSensitivity", d.touchpadSensitivity).coerceIn(0.5f, 2f),
                i("longPressMs", d.longPressMs).coerceIn(250, 1000), i("repeatMs", d.repeatMs).coerceIn(40, 250),
                b("volumeKeys", d.volumeKeys), b("autoKeyboard", d.autoKeyboard), b("liveTyping", d.liveTyping), b("keyFallback2", d.keyFallback), b("alwaysKeys", d.alwaysKeys),
                i("haptics", d.haptics).coerceIn(0, 3), b("sound", d.sound), i("accent", d.accent).coerceIn(0, ACCENTS.size - 1), b("amoled", d.amoled), f("textScale", d.textScale).coerceIn(0.85f, 1.3f),
                b("voiceStreaming3", d.voiceStreaming),
                voiceLive = b("voiceLive", d.voiceLive),
            )
        }

        /** Accent choices: name, colour, softer variant. Google green (default) first. */
        val ACCENTS = listOf(
            Triple("Google green", 0xFF81C995, 0xFFA8DAB5),
            Triple("Lime", 0xFF76B900, 0xFF9BE22D),
            Triple("Ocean", 0xFF8AB4F8, 0xFFAECBFA),
            Triple("Violet", 0xFFA285FF, 0xFFC4B0FF),
            Triple("Ember", 0xFFFF7A1A, 0xFFFFAA66),
            Triple("Rose", 0xFFFF8FAB, 0xFFFFB3C6),
            Triple("Teal", 0xFF14B8A6, 0xFF5EEAD4),
        )
    }
}
