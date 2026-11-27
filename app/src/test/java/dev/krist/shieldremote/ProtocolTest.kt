package dev.krist.shieldremote

import dev.krist.shieldremote.GoldenVectors.frames
import dev.krist.shieldremote.proto.FrameDecoder
import dev.krist.shieldremote.proto.Framing
import dev.krist.shieldremote.proto.Polo
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.proto.Remote.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun framed(p: ByteArray) = Framing.frame(p).hex()
    private fun payload(name: String): ByteArray = FrameDecoder().feed(frames.getValue(name).hex()).single()

    @Test fun pairingMessagesMatchReference() {
        assertEquals(frames["pairing_request"], framed(Polo.pairingRequest("Galaxy S25 Ultra")))
        assertEquals(frames["options"], framed(Polo.options()))
        assertEquals(frames["configuration"], framed(Polo.configuration()))
        assertEquals(frames["secret"], framed(Polo.secret(ByteArray(32) { it.toByte() })))
    }

    @Test fun pairingRepliesParse() {
        val ack = Polo.parse(payload("pairing_request_ack"))
        assertEquals(Polo.Kind.PAIRING_REQUEST_ACK, ack.kind)
        assertEquals("SHIELD", ack.serverName)
        assertEquals(2, ack.protocolVersion)
        assertEquals(Polo.STATUS_OK, ack.status)
        assertEquals(Polo.Kind.CONFIGURATION_ACK, Polo.parse(payload("configuration_ack")).kind)
        assertEquals(Polo.Kind.SECRET_ACK, Polo.parse(payload("secret_ack")).kind)
        assertEquals(Polo.Kind.OPTIONS, Polo.parse(payload("options")).kind)
        assertEquals(Polo.STATUS_BAD_SECRET, Polo.parse(payload("bad_secret")).status)
    }

    @Test fun remoteClientMessagesMatchReference() {
        assertEquals(615, Remote.Feature.DEFAULT)
        assertEquals(frames["configure"], framed(Remote.configure(Remote.Feature.DEFAULT)))
        assertEquals(frames["set_active"], framed(Remote.setActive(Remote.Feature.DEFAULT)))
        assertEquals(frames["ping_response"], framed(Remote.pingResponse(12345)))
        assertEquals(frames["ping_response_neg"], framed(Remote.pingResponse(-5)))
        assertEquals(frames["key_short"], framed(Remote.keyInject(Remote.Key.DPAD_CENTER, Remote.Direction.SHORT)))
        assertEquals(frames["key_start_long"], framed(Remote.keyInject(Remote.Key.HOME, Remote.Direction.START_LONG)))
        assertEquals(frames["key_end_long"], framed(Remote.keyInject(Remote.Key.POWER, Remote.Direction.END_LONG)))
        assertEquals(frames["app_link"], framed(Remote.appLink("https://www.youtube.com")))
        assertEquals(frames["app_link_long"], framed(Remote.appLink("x".repeat(300))))
        assertEquals(frames["ime_text"], framed(Remote.imeBatchEdit(3, 9, "hello")))
    }

    @Test fun remoteServerMessagesParse() {
        val cfg = Remote.parse(payload("srv_configure")) as Msg.Configure
        assertEquals(1023, cfg.features)
        assertEquals("NVIDIA", cfg.device.vendor)
        assertEquals("SHIELD Android TV", cfg.device.model)
        assertEquals("5.2.473254133", cfg.device.appVersion)
        assertTrue(Remote.parse(payload("srv_set_active")) is Msg.SetActive)
        assertEquals(Msg.Start(true), Remote.parse(payload("srv_start")))
        assertEquals(Msg.PingRequest(12345, 7), Remote.parse(payload("ping_request")))
        assertEquals(Msg.PingRequest(-5, 0), Remote.parse(payload("ping_request_neg")))
        assertEquals(Msg.Volume(37, 100, true, "SHIELD"), Remote.parse(payload("srv_volume")))
        val inj = Remote.parse(payload("srv_ime_key_inject")) as Msg.ImeKeyInject
        assertEquals("com.netflix.ninja", inj.appPackage); assertNull(inj.field)
        assertEquals("live ime_counter comes from app_info.counter", 5, inj.appCounter)
        val be = Remote.parse(payload("srv_batch_edit")) as Msg.ImeBatchEdit
        assertEquals(42, be.imeCounter); assertEquals(7, be.fieldCounter); assertNull(be.text)
        val mine = Remote.parse(payload("ime_text")) as Msg.ImeBatchEdit
        assertEquals("hello", mine.text); assertEquals(4, mine.start); assertEquals(4, mine.end)
        assertEquals(Msg.Error(true), Remote.parse(payload("srv_error")))
        assertEquals(Msg.KeyInject(23, 3), Remote.parse(payload("key_short")))
    }

    @Test fun pingIsAnsweredWithSameValue() {
        val req = Remote.parse(payload("ping_request")) as Msg.PingRequest
        assertEquals(frames["ping_response"], framed(Remote.pingResponse(req.val1)))
    }

    @Test fun decoderHandlesArbitrarySplits() {
        val stream = listOf("srv_configure", "srv_set_active", "ping_request", "app_link_long", "srv_volume")
            .map { frames.getValue(it).hex() }
        val all = stream.reduce { a, b -> a + b }
        for (chunk in listOf(1, 2, 3, 7, 64, all.size)) {
            val d = FrameDecoder()
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i < all.size) { val n = minOf(chunk, all.size - i); out += d.feed(all.copyOfRange(i, i + n)); i += n }
            assertEquals("chunk=$chunk", stream.size, out.size)
            stream.forEachIndexed { k, f -> assertArrayEquals(FrameDecoder().feed(f).single(), out[k]) }
        }
    }

    @Test fun multiByteLengthPrefix() {
        val p = Remote.appLink("x".repeat(300))
        val f = Framing.frame(p)
        assertEquals(p.size + 2, f.size)
        assertTrue(f[0].toInt() and 0x80 != 0)
        assertArrayEquals(p, FrameDecoder().feed(f).single())
    }

    @Test fun unknownFieldsAreSkipped() {
        // remote_configure with an extra unknown varint field 15 and fixed32 field 16 inside
        val p = dev.krist.shieldremote.proto.ProtoWriter().messageField(1) {
            intField(1, 7); varintField(15, 99)
        }.toByteArray()
        assertEquals(7, (Remote.parse(p) as Msg.Configure).features)
        assertTrue(Remote.parse(byteArrayOf()) is Msg.Empty)
    }
}

class VoiceAndImeTest {
    private fun framed(p: ByteArray) = Framing.frame(p).hex()
    private fun payload(name: String): ByteArray = FrameDecoder().feed(frames.getValue(name).hex()).single()

    @Test fun voiceMessagesMatchReference() {
        assertEquals(623, Remote.Feature.WITH_VOICE)
        assertEquals(frames["configure_voice"], framed(Remote.configure(Remote.Feature.WITH_VOICE)))
        assertEquals(frames["voice_begin"], framed(Remote.voiceBegin(17)))
        assertEquals(frames["voice_end"], framed(Remote.voiceEnd(17)))
        assertEquals(frames["voice_payload"], framed(Remote.voicePayload(17, Remote.voiceChunks(ByteArray(100)).single())))
        assertEquals(Msg.VoiceBegin(17, "com.google.android.katniss"), Remote.parse(payload("srv_voice_begin")))
    }

    @Test fun voiceChunkingMatchesReference() {
        val c = Remote.voiceChunks(ByteArray(20 * 1024 + 10))
        assertEquals(2, c.size)
        assertEquals(20 * 1024, c[0].size)
        assertEquals(8 * 1024, c[1].size)
        assertEquals(listOf(20 * 1024, 10 * 1024), Remote.voiceChunks(ByteArray(30 * 1024)).map { it.size })
    }

    @Test fun imeShowAndHideParse() {
        val show = Remote.parse(payload("srv_ime_show")) as Msg.ImeShowRequest
        assertEquals(Remote.TextField(9, "star", 3, 3, "Search"), show.field)
        assertEquals(Msg.ImeShowRequest(null), Remote.parse(payload("srv_ime_hide")))
        assertEquals(Msg.ImeBatchEdit(2, 9, "abc", 2, 2), Remote.parse(payload("srv_batch_edit_text")))
    }
}
