package dev.krist.shieldremote.protocol

import dev.krist.shieldremote.proto.Remote

/**
 * TV-side voice search (androidtvremote2 start_voice / send_voice_chunk / end_voice, and what the Google TV
 * app does): the TV (Google Assistant, "katniss") opens a session with remote_voice_begin(session_id),
 * whether we pressed SEARCH or the Shield started it. We ACCEPT every one: echo remote_voice_begin(id),
 * stream 16-bit mono 8 kHz PCM as remote_voice_payload (<= 20 KiB, padded to >= 8 KiB), then
 * remote_voice_end(id). Android-free so it runs against the mock TV; every step is traced.
 */
class VoiceStreamer(
    private val send: (ByteArray) -> Unit,
    private val trace: (String) -> Unit,
) {
    companion object

    /** Echo the TV's session id (androidtvremote2 start_voice). */
    fun begin(id: Int) { trace("voice: → remote_voice_begin($id) (accepting the TV's session)"); send(dev.krist.shieldremote.proto.Remote.voiceBegin(id)) }

    /** Send a captured utterance in one go, then end the session. */
    fun sendBuffered(id: Int, pcm: ByteArray): Int {
        val parts = VoiceStreamer.bufferedPayloads(pcm)
        parts.forEach { send(dev.krist.shieldremote.proto.Remote.voicePayload(id, it)) }
        val total = parts.sumOf { it.size }
        trace("voice: → ${parts.size} payloads (${parts.joinToString("+") { "${it.size}" }} = $total bytes, ${pcm.size} of speech audio)")
        send(dev.krist.shieldremote.proto.Remote.voiceEnd(id))
        trace("voice: → remote_voice_end($id)")
        return total
    }

    sealed class Result {
        data class Done(val sessionId: Int, val bytes: Int, val chunks: Int, val reason: String) : Result()
        data class Failed(val sessionId: Int, val error: String) : Result()
    }

    /**
     * @param openMic starts the microphone; returns a reader (buf, offset, len) -> bytes read or <0; throws if it can't
     * @param endpointer decides when the user is done (silence / timeout), fed with every block read
     */
    fun streamSession(
        id: Int,
        openMic: () -> ((ByteArray, Int, Int) -> Int),
        shouldStop: () -> Boolean,
        endpointer: Endpointer = Endpointer(),
        closeMic: () -> Unit = {},
    ): Result {
        trace("voice: → remote_voice_begin($id) (accepting the TV's session)")
        send(Remote.voiceBegin(id))
        var bytes = 0; var chunks = 0
        var reason = "stopped"
        try {
            val read = try { openMic() } catch (e: Exception) {
                trace("voice: ✗ microphone failed to start: ${e.message ?: e.javaClass.simpleName}")
                return Result.Failed(id, "Microphone unavailable: ${e.message ?: e.javaClass.simpleName}")
            }
            trace("voice: mic started OK (8 kHz mono PCM16)")
            val buf = ByteArray(Remote.VOICE_CHUNK_MIN_SIZE) // 0.5 s per payload
            var filled = 0
            while (true) {
                if (shouldStop()) { reason = "user stopped"; break }
                val n = read(buf, filled, buf.size - filled)
                if (n < 0) { trace("voice: ✗ mic read error $n"); reason = "mic error $n"; break }
                endpointer.feed(buf, filled, n)?.let { reason = it }
                filled += n
                if (filled >= buf.size) {
                    send(Remote.voicePayload(id, buf.copyOf())); bytes += buf.size; chunks++; filled = 0
                    if (chunks == 1 || chunks % 4 == 0) trace("voice: → payload #$chunks ($bytes bytes so far)")
                }
                if (endpointer.done) break
            }
            if (filled > 0) Remote.voiceChunks(buf.copyOf(filled)).forEach { send(Remote.voicePayload(id, it)); bytes += it.size; chunks++ }
            return Result.Done(id, bytes, chunks, reason)
        } catch (e: Exception) {
            trace("voice: ✗ ${e.message ?: e.javaClass.simpleName}")
            return Result.Failed(id, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { closeMic() }
            send(Remote.voiceEnd(id))
            trace("voice: → remote_voice_end($id) after $chunks payloads / $bytes bytes ($reason)")
        }
    }
}

/**
 * Buffered upload (what works reliably with the Google Assistant on Android TV; see unfoldedcircle
 * integration-androidtv voice_command.py "Option C ... works best", and its note that live 8 KiB chunks made
 * Google mis-hear): the whole utterance + 4096 bytes of trailing silence, split by the library rule
 * (<= 20 KiB pieces, each padded to >= 8 KiB), then remote_voice_end.
 */
fun VoiceStreamer.Companion.bufferedPayloads(pcm: ByteArray): List<ByteArray> = dev.krist.shieldremote.proto.Remote.voiceChunks(pcm + ByteArray(4096))

/**
 * Simple energy endpointer on 8 kHz PCM16 (time comes from the sample count, so it is deterministic):
 * done after [silenceMs] of quiet following speech, after [noSpeechMs] without any speech, or at [maxMs].
 */
class Endpointer(
    private val threshold: Int = 500,
    private val silenceMs: Int = 1_200,
    private val noSpeechMs: Int = 8_000,
    private val maxMs: Int = 15_000,
    private val minMs: Int = 2_000,
) {
    private var totalSamples = 0L
    private var quietSamples = 0L
    var speechSeen = false; private set
    var done = false; private set

    /** Returns a reason once done, else null. */
    fun feed(buf: ByteArray, off: Int, len: Int): String? {
        var i = off
        val end = off + len - 1
        var sumSq = 0.0; var n = 0
        while (i < end) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sumSq += s.toDouble() * s; n++; i += 2
        }
        if (n == 0) return null
        totalSamples += n
        val rms = kotlin.math.sqrt(sumSq / n)
        if (rms >= threshold) { speechSeen = true; quietSamples = 0 } else quietSamples += n
        val ms = { s: Long -> (s * 1000 / 8000).toInt() }
        val reason = when {
            speechSeen && ms(quietSamples) >= silenceMs && ms(totalSamples) >= minMs -> "silence after speech"
            !speechSeen && ms(totalSamples) >= noSpeechMs -> "no speech for ${noSpeechMs / 1000} s"
            ms(totalSamples) >= maxMs -> "max ${maxMs / 1000} s"
            else -> null
        }
        if (reason != null) done = true
        return reason
    }
}
