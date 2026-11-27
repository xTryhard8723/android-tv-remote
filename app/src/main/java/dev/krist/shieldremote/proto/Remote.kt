package dev.krist.shieldremote.proto

/**
 * Remote protocol (remotemessage.proto, proto3) messages. Field numbers match androidtvremote2.
 * proto3 semantics: scalar fields equal to their default value are not serialized.
 */
object Remote {
    object Feature {
        const val PING = 1
        const val KEY = 2
        const val IME = 4
        const val VOICE = 8
        const val POWER = 32
        const val VOLUME = 64
        const val APP_LINK = 512
        const val DEFAULT = PING or KEY or IME or POWER or VOLUME or APP_LINK
        const val WITH_VOICE = DEFAULT or VOICE
    }

    object Direction { const val START_LONG = 1; const val END_LONG = 2; const val SHORT = 3 }

    object Key {
        const val HOME = 3; const val BACK = 4
        const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
        const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
        const val ENTER = 66; const val DEL = 67; const val MENU = 82; const val SEARCH = 84
        const val MEDIA_PLAY_PAUSE = 85; const val MEDIA_NEXT = 87; const val MEDIA_PREVIOUS = 88
        const val MEDIA_REWIND = 89; const val MEDIA_FAST_FORWARD = 90
        const val VOLUME_MUTE = 164; const val INFO = 165; const val SETTINGS = 176; const val APP_SWITCH = 187
        const val MEDIA_STOP = 86; const val CHANNEL_UP = 166; const val CHANNEL_DOWN = 167; const val GUIDE = 172
        const val SHIFT_LEFT = 59
        const val CAPTIONS = 175; const val TV_INPUT = 178; const val ASSIST = 219; const val SLEEP = 223; const val WAKEUP = 224
    }

    // --- proto3 helpers ---
    private fun ProtoWriter.i3(field: Int, v: Int) { if (v != 0) intField(field, v) }
    private fun ProtoWriter.s3(field: Int, v: String) { if (v.isNotEmpty()) stringField(field, v) }

    // --- client -> server ---
    fun configure(features: Int, packageName: String = "atvremote", appVersion: String = "1.0.0"): ByteArray =
        ProtoWriter().messageField(1) {
            i3(1, features)
            messageField(2) {
                // model(1)/vendor(2) intentionally unset, like androidtvremote2
                i3(3, 1)          // unknown1
                s3(4, "1")        // unknown2
                s3(5, packageName)
                s3(6, appVersion)
            }
        }.toByteArray()

    fun setActive(features: Int): ByteArray = ProtoWriter().messageField(2) { i3(1, features) }.toByteArray()

    fun pingResponse(val1: Int): ByteArray = ProtoWriter().messageField(9) { i3(1, val1) }.toByteArray()

    fun keyInject(keyCode: Int, direction: Int = Direction.SHORT): ByteArray =
        ProtoWriter().messageField(10) { i3(1, keyCode); i3(2, direction) }.toByteArray()

    fun appLink(link: String): ByteArray = ProtoWriter().messageField(90) { s3(1, link) }.toByteArray()

    /**
     * IME batch edit replacing the focused field's content with [text], cursor at [cursor]
     * (androidtvremote2 send_text uses start = end = len(text) - 1).
     */
    fun imeBatchEdit(imeCounter: Int, fieldCounter: Int, text: String, cursor: Int = text.length - 1): ByteArray =
        ProtoWriter().messageField(21) {
            i3(1, imeCounter)
            i3(2, fieldCounter)
            messageField(3) {
                i3(1, 1) // insert
                messageField(2) { i3(1, cursor); i3(2, cursor); s3(3, text) }
            }
        }.toByteArray()

    fun voiceBegin(sessionId: Int): ByteArray = ProtoWriter().messageField(30) { i3(1, sessionId) }.toByteArray()
    fun voicePayload(sessionId: Int, samples: ByteArray): ByteArray =
        ProtoWriter().messageField(31) { i3(1, sessionId); if (samples.isNotEmpty()) bytesField(2, samples) }.toByteArray()
    fun voiceEnd(sessionId: Int): ByteArray = ProtoWriter().messageField(32) { i3(1, sessionId) }.toByteArray()

    /** androidtvremote2: chunks are at most 20 KiB and padded to at least 8 KiB (Shield rejects smaller ones). */
    const val VOICE_CHUNK_SIZE = 20 * 1024
    const val VOICE_CHUNK_MIN_SIZE = 8 * 1024
    fun voiceChunks(pcm: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < pcm.size) {
            val piece = pcm.copyOfRange(i, minOf(pcm.size, i + VOICE_CHUNK_SIZE))
            out += if (piece.size < VOICE_CHUNK_MIN_SIZE) piece.copyOf(VOICE_CHUNK_MIN_SIZE) else piece
            i += VOICE_CHUNK_SIZE
        }
        return out
    }

    // --- server -> client ---
    data class DeviceInfo(val model: String = "", val vendor: String = "", val packageName: String = "", val appVersion: String = "")
    data class TextField(val counter: Int = 0, val value: String = "", val start: Int = 0, val end: Int = 0, val label: String = "")

    sealed class Msg {
        data class Configure(val features: Int, val device: DeviceInfo) : Msg()
        data class SetActive(val active: Int) : Msg()
        data class Error(val value: Boolean) : Msg()
        data class PingRequest(val val1: Int, val val2: Int) : Msg()
        data class PingResponse(val val1: Int) : Msg()
        data class KeyInject(val keyCode: Int, val direction: Int) : Msg()
        data class ImeKeyInject(val appPackage: String, val field: TextField?, val appCounter: Int = 0) : Msg()
        data class ImeBatchEdit(val imeCounter: Int, val fieldCounter: Int, val text: String?, val start: Int, val end: Int) : Msg()
        data class ImeShowRequest(val field: TextField?) : Msg()
        data class Start(val started: Boolean) : Msg()
        data class Volume(val level: Int, val max: Int, val muted: Boolean, val playerModel: String) : Msg()
        data class AppLink(val link: String) : Msg()
        data class VoiceBegin(val sessionId: Int, val packageName: String) : Msg()
        data class VoiceEnd(val sessionId: Int) : Msg()
        data class Other(val field: Int) : Msg()
        object Empty : Msg()
    }

    private inline fun fields(bytes: ByteArray, f: (ProtoReader) -> Unit) {
        val r = ProtoReader(bytes); while (r.next()) f(r)
    }

    private fun textField(bytes: ByteArray): TextField {
        var c = 0; var v = ""; var s = 0; var e = 0; var l = ""
        fields(bytes) { r -> when (r.field) { 1 -> c = r.readInt(); 2 -> v = r.readString(); 3 -> s = r.readInt(); 4 -> e = r.readInt(); 6 -> l = r.readString(); else -> r.skip() } }
        return TextField(c, v, s, e, l)
    }

    fun parse(bytes: ByteArray): Msg {
        var result: Msg = Msg.Empty
        fields(bytes) { r ->
            if (r.wireType != 2) { r.skip(); return@fields }
            val body = r.readBytes()
            result = when (r.field) {
                1 -> { var code = 0; var dev = DeviceInfo()
                    fields(body) { s -> when (s.field) {
                        1 -> code = s.readInt()
                        2 -> { var m = ""; var v = ""; var p = ""; var a = ""
                            fields(s.readBytes()) { d -> when (d.field) { 1 -> m = d.readString(); 2 -> v = d.readString(); 5 -> p = d.readString(); 6 -> a = d.readString(); else -> d.skip() } }
                            dev = DeviceInfo(m, v, p, a) }
                        else -> s.skip() } }
                    Msg.Configure(code, dev) }
                2 -> { var a = 0; fields(body) { s -> if (s.field == 1) a = s.readInt() else s.skip() }; Msg.SetActive(a) }
                3 -> { var v = false; fields(body) { s -> if (s.field == 1) v = s.readBool() else s.skip() }; Msg.Error(v) }
                8 -> { var a = 0; var b = 0; fields(body) { s -> when (s.field) { 1 -> a = s.readInt(); 2 -> b = s.readInt(); else -> s.skip() } }; Msg.PingRequest(a, b) }
                9 -> { var a = 0; fields(body) { s -> if (s.field == 1) a = s.readInt() else s.skip() }; Msg.PingResponse(a) }
                10 -> { var k = 0; var d = 0; fields(body) { s -> when (s.field) { 1 -> k = s.readInt(); 2 -> d = s.readInt(); else -> s.skip() } }; Msg.KeyInject(k, d) }
                20 -> { var pkg = ""; var tf: TextField? = null; var counter = 0
                    fields(body) { s -> when (s.field) {
                        1 -> fields(s.readBytes()) { a -> when (a.field) { 1 -> counter = a.readInt(); 12 -> pkg = a.readString(); else -> a.skip() } }
                        2 -> tf = textField(s.readBytes())
                        else -> s.skip() } }
                    Msg.ImeKeyInject(pkg, tf, counter) }
                21 -> { var ic = 0; var fc = 0; var text: String? = null; var st = 0; var en = 0
                    fields(body) { s -> when (s.field) {
                        1 -> ic = s.readInt(); 2 -> fc = s.readInt()
                        3 -> fields(s.readBytes()) { e -> if (e.field == 2) fields(e.readBytes()) { o -> when (o.field) { 1 -> st = o.readInt(); 2 -> en = o.readInt(); 3 -> text = o.readString(); else -> o.skip() } } else e.skip() }
                        else -> s.skip() } }
                    Msg.ImeBatchEdit(ic, fc, text, st, en) }
                22 -> { var tf: TextField? = null; fields(body) { s -> if (s.field == 2) tf = textField(s.readBytes()) else s.skip() }; Msg.ImeShowRequest(tf) }
                40 -> { var st = false; fields(body) { s -> if (s.field == 1) st = s.readBool() else s.skip() }; Msg.Start(st) }
                50 -> { var lvl = 0; var max = 0; var mute = false; var pm = ""
                    fields(body) { s -> when (s.field) { 3 -> pm = s.readString(); 6 -> max = s.readInt(); 7 -> lvl = s.readInt(); 8 -> mute = s.readBool(); else -> s.skip() } }
                    Msg.Volume(lvl, max, mute, pm) }
                30 -> { var id = 0; var pkg = ""; fields(body) { s -> when (s.field) { 1 -> id = s.readInt(); 2 -> pkg = s.readString(); else -> s.skip() } }; Msg.VoiceBegin(id, pkg) }
                32 -> { var id = 0; fields(body) { s -> if (s.field == 1) id = s.readInt() else s.skip() }; Msg.VoiceEnd(id) }
                90 -> { var l = ""; fields(body) { s -> if (s.field == 1) l = s.readString() else s.skip() }; Msg.AppLink(l) }
                else -> Msg.Other(r.field)
            }
        }
        return result
    }
}
