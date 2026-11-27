package dev.krist.shieldremote

import dev.krist.shieldremote.net.PairingClient
import dev.krist.shieldremote.net.RemoteSession
import dev.krist.shieldremote.net.TlsConnector
import dev.krist.shieldremote.net.clientSslContext
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.protocol.PairingSecret
import dev.krist.shieldremote.protocol.SelfSignedCert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end test against a mock TV (mocktv/mocktv.py) built on androidtvremote2's own protobufs and
 * pairing-secret code over real TLS (OpenSSL). Runs only when MOCKTV_DIR is set.
 */
class MockTvIntegrationTest {
    private val dir = System.getenv("MOCKTV_DIR")
    private val host = "127.0.0.1"

    private fun log() = File(dir, "mocktv.log").readText()

    @Test fun pairConnectAndControl() {
        assumeTrue(dir != null)
        val kp = SelfSignedCert.generateKeyPair()
        val cert = SelfSignedCert.create(kp, "JVM integration")
        val ctx = clientSslContext(kp.private, cert)

        // Pairing
        val pc = PairingClient({ ctx }, { cert }, host, 6467)
        pc.start("JVM integration test", null)
        assertEquals("MOCKSHIELD", pc.serverName)
        Thread.sleep(200)
        val code = File(dir, "code.txt").readText().trim()
        val wrong = "%02X".format((code.substring(0, 2).toInt(16) + 1) and 0xFF) + code.substring(2)
        try { pc.finish(wrong); throw AssertionError("wrong code accepted") } catch (_: PairingSecret.InvalidCode) {}
        pc.finish(code.lowercase())
        val t0 = System.currentTimeMillis(); pc.close(); val closeMs = System.currentTimeMillis() - t0
        println("pairing close took $closeMs ms")
        assertTrue("close must not block (took $closeMs ms)", closeMs < 1500)
        assertTrue(log().contains("PAIR SUCCESS"))

        // Remote session
        val ready = CountDownLatch(1)
        val msgs = java.util.concurrent.CopyOnWriteArrayList<Remote.Msg>()
        var device: Remote.DeviceInfo? = null
        val sock = TlsConnector.connect(ctx, host, 6466, null)
        val s = RemoteSession(sock, Remote.Feature.WITH_VOICE, object : RemoteSession.Listener {
            override fun onReady() = ready.countDown()
            override fun onMessage(msg: Remote.Msg) { msgs += msg }
            override fun onDeviceInfo(info: Remote.DeviceInfo) { device = info }
        })
        var reason = ""
        val t = thread { reason = s.run() }
        assertTrue("session ready", ready.await(5, TimeUnit.SECONDS))
        assertEquals("NVIDIA", device?.vendor)
        assertEquals(Remote.Feature.WITH_VOICE, s.activeFeatures)
        s.send(Remote.keyInject(Remote.Key.DPAD_UP))
        s.send(Remote.keyInject(Remote.Key.HOME, Remote.Direction.START_LONG))
        s.send(Remote.keyInject(Remote.Key.HOME, Remote.Direction.END_LONG))
        s.send(Remote.appLink("https://www.youtube.com"))
        s.send(Remote.keyInject(Remote.Key.INFO)) // mock: opens a text field
        Thread.sleep(500)
        assertTrue("ime show", msgs.any { it is Remote.Msg.ImeShowRequest && it.field?.label == "Search movies" })
        s.send(Remote.imeBatchEdit(1, 7, "stark"))
        s.send(Remote.keyInject(Remote.Key.SEARCH)) // mock: voice begin
        Thread.sleep(500)
        val vb = msgs.filterIsInstance<Remote.Msg.VoiceBegin>().single()
        s.send(Remote.voiceBegin(vb.sessionId))
        Remote.voiceChunks(ByteArray(12000)).forEach { s.send(Remote.voicePayload(vb.sessionId, it)) }
        s.send(Remote.voiceEnd(vb.sessionId))
        Thread.sleep(6000) // at least one ping from the mock
        assertTrue(msgs.any { it is Remote.Msg.Volume })
        val l = log()
        for (needle in listOf(
            "remote_configure { code1: 623", "remote_set_active { active: 623", "KEYCODE_DPAD_UP direction: SHORT",
            "KEYCODE_HOME direction: START_LONG", "KEYCODE_HOME direction: END_LONG", "app_link: \"https://www.youtube.com\"",
            "ime_counter: 1 field_counter: 7 edit_info { insert: 1 text_field_status { start: 4 end: 4 value: \"stark\" }",
            "remote_voice_begin { session_id: 42 }", "voice payload 12000 bytes", "remote_voice_end { session_id: 42 }", "pong 1",
        )) assertTrue("mock log missing: $needle", l.contains(needle))

        // Mock drops the link: run() must return promptly so the controller can reconnect.
        File(dir, "drop").writeText("")
        t.join(5000)
        val c0 = System.currentTimeMillis(); s.close(); println("session close took ${System.currentTimeMillis() - c0} ms")
        assertTrue("session ended after drop", !t.isAlive)
        println("session ended: $reason")

        // An unpaired certificate must not get a configure message.
        val kp2 = SelfSignedCert.generateKeyPair()
        val ctx2 = clientSslContext(kp2.private, SelfSignedCert.create(kp2, "stranger"))
        var cfg = false
        val r2 = runCatching {
            val s2 = RemoteSession(TlsConnector.connect(ctx2, host, 6466, null), Remote.Feature.DEFAULT, object : RemoteSession.Listener {
                override fun onReady() {}
                override fun onMessage(msg: Remote.Msg) {}
                override fun onDeviceInfo(info: Remote.DeviceInfo) { cfg = true }
            })
            s2.run()
        }
        println("unpaired: $r2")
        assertTrue(!cfg)
    }

    @Test fun watchdogDetectsSilentTv() {
        assumeTrue(dir != null)
        // Re-use pairing: pair a fresh identity quickly.
        val kp = SelfSignedCert.generateKeyPair()
        val cert = SelfSignedCert.create(kp, "watchdog")
        val ctx = clientSslContext(kp.private, cert)
        PairingClient({ ctx }, { cert }, host, 6467).apply { start("watchdog", null); Thread.sleep(200); finish(File(dir, "code.txt").readText().trim()); close() }
        val silent = File(dir, "silent").apply { writeText("") }
        try {
            val clock = { System.nanoTime() / 1_000_000 }
            val s = RemoteSession(TlsConnector.connect(ctx, host, 6466, null), Remote.Feature.DEFAULT, object : RemoteSession.Listener {
                override fun onReady() {}
                override fun onMessage(msg: Remote.Msg) {}
                override fun onDeviceInfo(info: Remote.DeviceInfo) {}
            }, clock)
            val t0 = clock()
            val reason = s.run()
            val took = clock() - t0
            println("watchdog: $reason after $took ms")
            assertTrue(reason.contains("watchdog"))
            assertTrue(took in 11_000..15_000)
        } finally { silent.delete() }
    }
}
