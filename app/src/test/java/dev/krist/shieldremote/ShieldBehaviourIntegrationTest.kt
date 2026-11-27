package dev.krist.shieldremote

import dev.krist.shieldremote.data.KeyMacro
import dev.krist.shieldremote.data.Launch
import dev.krist.shieldremote.data.Shortcut
import dev.krist.shieldremote.net.PairingClient
import dev.krist.shieldremote.net.RemoteSession
import dev.krist.shieldremote.net.TlsConnector
import dev.krist.shieldremote.net.clientSslContext
import dev.krist.shieldremote.proto.Remote
import dev.krist.shieldremote.protocol.ImeBridge
import dev.krist.shieldremote.protocol.SelfSignedCert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone's real typing / launch code (ImeBridge, ImeSender, Launch, KeyMacro) over real TLS against
 * mocktv.py in "shieldmode", which mimics the Shield from the user's logs: batch edits are INSERTED at
 * the cursor, each edit is answered with ImeShowRequest(counter+1, full value, start=end=cursor), a
 * reconnect re-reports the focused field via ime_key_inject, and an unknown app link gets remote_error
 * plus a closed session. Runs only when MOCKTV_DIR is set.
 */
class ShieldBehaviourIntegrationTest {
    private val dir = System.getenv("MOCKTV_DIR")
    private fun tv() = File(dir, "field.txt").readText()
    private fun stats(): Map<String, Int> = File(dir, "stats.txt").readText().split(" ").associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
    private val clock = { System.nanoTime() / 1_000_000 }

    /** Minimal phone: one session at a time, the app's ImeBridge, a fake editor. */
    inner class Phone(private val ctx: javax.net.ssl.SSLContext) {
        @Volatile var session: RemoteSession? = null
        @Volatile var phoneText = ""
        @Volatile var panel = false
        @Volatile var sentImeOrKeys = 0
        @Volatile var errors = 0
        @Volatile var beginWaiter: kotlinx.coroutines.CompletableDeferred<Int>? = null
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ime = ImeBridge(clock,
            sendStroke = { k -> sentImeOrKeys++; session!!.send(Remote.keyInject(k.code)) },
            sendBatch = { ic, fc, t, c -> sentImeOrKeys++; session!!.send(Remote.imeBatchEdit(ic, fc, t, c)) },
            trace = { log += it },
            ui = object : ImeBridge.Ui {
                override val panelVisible get() = panel
                override val autoKeyboard get() = true
                override fun open(text: String, label: String, show: Boolean) { phoneText = text; panel = show }
                override fun close() { panel = false }
            })
        private var runner: Thread? = null

        fun connect() {
            val ready = CountDownLatch(1)
            val s = RemoteSession(TlsConnector.connect(ctx, "127.0.0.1", 6466, null), Remote.Feature.DEFAULT, object : RemoteSession.Listener {
                override fun onReady() = ready.countDown()
                override fun onMessage(msg: Remote.Msg) {
                    when (msg) {
                        is Remote.Msg.ImeKeyInject -> ime.onKeyInject(msg)
                        is Remote.Msg.ImeBatchEdit -> ime.onBatchEdit(msg)
                        is Remote.Msg.ImeShowRequest -> ime.onShowRequest(msg)
                        is Remote.Msg.Error -> errors++
                        is Remote.Msg.VoiceBegin -> beginWaiter?.complete(msg.sessionId)
                        else -> {}
                    }
                }
                override fun onDeviceInfo(info: Remote.DeviceInfo) {}
            }, clock)
            ime.sessionStarted() // what RemoteController does for each (re)connect
            session = s
            runner = thread { s.run() }
            assertTrue("session ready", ready.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
        }
        val alive get() = runner?.isAlive == true

        /** Like RemoteController.imeEdit + its 100 ms debounced flushIme. */
        fun type(newText: String) {
            phoneText = newText
            ime.userEdit(newText)
            Thread.sleep(100)
            var guard = 0
            while (ime.flush(false) && guard++ < 50) Thread.sleep(100)
        }
        fun key(code: Int) { if (code == Remote.Key.DPAD_CENTER) ime.userOk(); session!!.send(Remote.keyInject(code)) }

        /** Like RemoteController.launchShortcut: one link at a time, rejected ones remembered. */
        val rejected = mutableSetOf<String>()
        fun launch(sc: Shortcut) {
            ime.suppressAutoOpen()
            if (sc.keys.isNotBlank()) {
                for (st in KeyMacro.parse(sc.keys)!!) when (st) {
                    is KeyMacro.Step.Key -> { session!!.send(Remote.keyInject(st.code)); Thread.sleep(150) }
                    is KeyMacro.Step.Wait -> Thread.sleep(st.ms)
                }
                ime.suppressAutoOpen(); return
            }
            for (uri in Launch.candidates(sc, null, rejected)) {
                val before = errors
                session!!.send(Remote.appLink(uri))
                Thread.sleep(600)
                if (errors > before || !alive) { rejected += uri; connect(); return } // reconnect like the controller
                return
            }
        }
        fun settle(ms: Long = 1200) = Thread.sleep(ms)
    }

    private fun pairedPhone(): Phone {
        val kp = SelfSignedCert.generateKeyPair(); val cert = SelfSignedCert.create(kp, "shield-behaviour")
        val ctx = clientSslContext(kp.private, cert)
        PairingClient({ ctx }, { cert }, "127.0.0.1", 6467).apply { start("shield-behaviour", null); Thread.sleep(200); finish(File(dir, "code.txt").readText().trim()); close() }
        return Phone(ctx)
    }

    private fun focusField(p: Phone, prefill: String) {
        File(dir, "prefill.txt").writeText(prefill)
        p.ime.userOk()          // the user pressed OK on the D-pad into the box
        p.key(Remote.Key.INFO) // mock: TV focuses a text box containing [prefill]
        p.settle(500)
    }

    private fun check(p: Phone, scenario: String) {
        p.settle()
        assertEquals("$scenario: TV value must equal phone text", p.phoneText, tv())
        println("PASS  $scenario  (phone=\"${p.phoneText}\" tv=\"${tv()}\")")
    }

    @Test fun realShieldScenarios() {
        assumeTrue(dir != null)
        File(dir, "shieldmode").writeText("")
        val p = pairedPhone()
        p.connect()

        // 1. type a word letter by letter into an empty box (the bug: "ttetesttest…test123")
        focusField(p, "")
        assertTrue("keyboard pops for a new field", p.panel)
        for (w in listOf("t", "te", "tes", "test", "test1", "test12", "test123")) { p.type(w); Thread.sleep(40) }
        check(p, "1 typing letter by letter")

        // 2. backspace
        p.type("test12"); p.type("test1"); p.type("test")
        check(p, "2 backspace x3")

        // 3. mid-word edits (replace a letter, insert in the middle, delete in the middle)
        p.type("tast"); check(p, "3a mid-word replace")
        p.type("taXst"); check(p, "3b mid-word insert")
        p.type("tast"); check(p, "3c mid-word delete")
        p.type(""); check(p, "3d clear all")

        // 4. keyboard opens on a box that already has text: prefill is never sent back
        val before4 = p.sentImeOrKeys
        p.settle(3100) // out of the typing window, like a fresh field
        focusField(p, "star")
        assertEquals("prefill shown on phone", "star", p.phoneText)
        p.ime.userEdit("star"); p.ime.flush(false) // keyboard re-commits the same text: must send nothing
        p.settle()
        assertEquals("prefill caused no sends", before4, p.sentImeOrKeys)
        p.type("stark"); check(p, "4 open with existing text, then type")

        // 5. shortcut pressed while the text box is open: rejected link -> error + session drop -> reconnect
        p.settle(3100)
        val before5 = p.sentImeOrKeys
        p.panel = false // user closed the phone keyboard, pressed a shortcut
        p.launch(Shortcut("Broken", "foo://", 0, "com.example.foo"))
        p.settle(2500)
        assertEquals("no typing sent after shortcut/reconnect", before5, p.sentImeOrKeys)
        assertFalse("keyboard not auto-popped right after a shortcut/reconnect", p.panel)
        assertEquals("TV text untouched", "stark", tv())
        assertTrue("reconnected", p.alive)
        val errs = stats()["errors"]!!
        p.launch(Shortcut("Broken", "foo://", 0, "com.example.foo")) // rejected link is never sent again
        p.settle(800)
        assertEquals("rejected link not resent", errs, stats()["errors"]!!)
        println("PASS  5 shortcut while text box open (no sends, no popup, link not resent)")
        // 5b. same, but with the phone keyboard still open and the user just typed
        p.panel = true; p.phoneText = tv(); p.ime.userOpened(tv()); p.type("stark!"); p.type("stark")
        val before5b = p.sentImeOrKeys
        p.launch(Shortcut("Broken2", "bar://", 0, "com.example.bar"))
        p.settle(3500)
        assertEquals("no typing sent after shortcut/reconnect (panel open)", before5b, p.sentImeOrKeys)
        assertEquals("stark", tv()); assertEquals("stark", p.phoneText)
        println("PASS  5b shortcut while typing with the phone keyboard open (no copying loop)")

        // 6. reconnect (TV drops the link) while the box is open and the phone keyboard is up
        p.panel = true; p.phoneText = tv(); p.ime.userOpened(tv())
        val before6 = p.sentImeOrKeys
        File(dir, "drop").writeText("")
        Thread.sleep(1500)
        assertFalse(p.alive)
        p.connect(); p.settle(1500)
        assertEquals("reconnect caused no sends", before6, p.sentImeOrKeys)
        assertEquals("stark", tv())
        p.type("stark w"); p.type("stark wa"); p.type("stark war"); p.type("stark wars")
        check(p, "6 reconnect while text box open, then keep typing")

        // 7. no loop: 3 s idle with the panel open -> nothing sent
        val idle = p.sentImeOrKeys
        p.settle(3000)
        assertEquals("idle: no messages", idle, p.sentImeOrKeys)
        println("PASS  7 no message loop while idle")

        // 8. SmartTube: never an app link (with or without space), launched by key sequence
        val links0 = stats()["links"]!!
        for (sc in listOf(
            Shortcut("SmartTube", "smart tube://", 0, "com.teamsmart.videomanager.tv"),
            Shortcut("SmartTube", "smarttube://", 0, ""),
            Shortcut("SmartTube", "", 0, "com.liskovsoft.smarttubetv.beta"),
        )) { assertTrue(Launch.candidates(sc).isEmpty()); p.launch(sc) }
        p.settle(600)
        assertEquals("SmartTube: no app link ever sent", links0, stats()["links"]!!)
        assertTrue(p.alive)
        p.launch(Shortcut("SmartTube", "", 0, "com.teamsmart.videomanager.tv", keys = "HOME, wait300, DOWN, RIGHT x2, OK"))
        p.settle(800)
        assertTrue("session survives", p.alive)
        assertEquals("<closed>", tv())
        assertTrue(File(dir, "mocktv.log").readText().contains("KEYCODE_DPAD_CENTER"))
        println("PASS  8 SmartTube: no link sent, key sequence launches it, session stays up")

        // 9. a valid link (Wholphin) still opens
        val errs9 = stats()["errors"]!!
        p.launch(Shortcut("Wholphin", "wholphin://", 0, "com.github.damontecres.wholphin"))
        p.settle(600)
        assertTrue(p.alive)
        assertEquals(errs9, stats()["errors"]!!)
        println("PASS  9 valid deep link (wholphin://) accepted")

        val st = stats()
        println("mock stats: $st")
        assertEquals("every edit used the TV's current field counter", 0, st["counter_mismatch"])
        File(dir, "shieldmode").delete()
    }

    /** Voice as the app now does it: accept EVERY remote_voice_begin, stream, end; fallback only on no answer. */
    @Test fun voicePaths() {
        assumeTrue(dir != null)
        File(dir, "shieldmode").writeText("")
        File(dir, "noack").delete(); File(dir, "novoice").delete()
        val p = pairedPhone(); p.connect()
        val results = java.util.concurrent.LinkedBlockingQueue<dev.krist.shieldremote.protocol.VoiceStreamer.Result>()
        // like RemoteController.onVoiceBegin -> acceptVoice: speech for 1 s, then silence (endpointer stops)
        fun accept(id: Int) = thread {
            val n = 8 * 1000; val speech = ByteArray(n * 2) { i -> if ((i / 2) % 16 < 8) (if (i % 2 == 0) 0xA0.toByte() else 0x0F) else (if (i % 2 == 0) 0x60.toByte() else 0xF0.toByte()) }
            val audio = speech + ByteArray(8 * 3000 * 2)
            var pos = 0
            results += dev.krist.shieldremote.protocol.VoiceStreamer({ p.session!!.send(it) }, { println("    $it") }).streamSession(id,
                openMic = { { b, o, l -> val k = minOf(l, 1600, audio.size - pos); if (k <= 0) 0 else { System.arraycopy(audio, pos, b, o, k); pos += k; Thread.sleep(2); k } } },
                shouldStop = { false })
        }
        fun pressMic(): Int? {
            val w = kotlinx.coroutines.CompletableDeferred<Int>(); p.beginWaiter = w
            p.session!!.send(Remote.keyInject(Remote.Key.SEARCH))
            return kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1_500) { w.await() } }
        }
        fun mockLog() = File(dir, "mocktv.log").readText()

        // V1. mic button: SEARCH -> TV voice_begin(katniss) -> accept/echo -> PCM payloads -> end
        val id = pressMic()
        assertEquals(42, id)
        accept(id!!)
        val r = results.poll(10, TimeUnit.SECONDS) as dev.krist.shieldremote.protocol.VoiceStreamer.Result.Done
        Thread.sleep(400)
        assertEquals("silence after speech", r.reason)
        assertTrue(mockLog().contains("remote_voice_begin { session_id: 42 }"))
        assertTrue(mockLog().contains("remote_voice_end { session_id: 42 }"))
        assertTrue(mockLog().contains("voice payload 8192 bytes"))
        println("PASS  V1 mic: SEARCH → voice_begin(katniss) accepted → ${r.chunks} payloads / ${r.bytes} bytes → voice_end (${r.reason})")

        // V1b. 1.0.8 default: buffered upload, exactly the library's payload format
        val idB = pressMic()!!
        val streamer = dev.krist.shieldremote.protocol.VoiceStreamer({ p.session!!.send(it) }, { println("    $it") })
        streamer.begin(idB)
        val speech = ByteArray(2 * 8000 * 2) { i -> if ((i / 2) % 16 < 8) (if (i % 2 == 0) 0xA0.toByte() else 0x0F) else (if (i % 2 == 0) 0x60.toByte() else 0xF0.toByte()) }
        val sentBytes = streamer.sendBuffered(idB, speech) // 32000 + 4096 -> 20480 + 15616
        Thread.sleep(500)
        val lb = mockLog().substringAfterLast("remote_voice_begin { session_id: $idB }")
        assertEquals(listOf(20480, 15616), Regex("voice payload (\\d+) bytes").findAll(lb).map { it.groupValues[1].toInt() }.toList())
        assertTrue(lb.contains("remote_voice_end { session_id: $idB }"))
        assertEquals(36096, sentBytes)
        println("PASS  V1b buffered upload: begin echo → payloads 20480+15616 (library chunking, 4096 B trailing silence) → end")

        // V2. the Shield opens a session on its own (the 18:29:22 log): accepted, not killed
        val w = kotlinx.coroutines.CompletableDeferred<Int>(); p.beginWaiter = w
        File(dir, "voicebegin").writeText("")
        val id2 = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(3_000) { w.await() } }
        assertEquals(7, id2)
        accept(id2!!)
        val r2 = results.poll(10, TimeUnit.SECONDS)
        assertTrue("$r2", r2 is dev.krist.shieldremote.protocol.VoiceStreamer.Result.Done)
        Thread.sleep(400)
        val l2 = mockLog()
        assertTrue(l2.contains("remote_voice_begin { session_id: 7 }")); assertTrue(l2.contains("remote_voice_end { session_id: 7 }"))
        assertTrue("payloads streamed for session 7", l2.substringAfter("unprompted voice_begin 7").contains("voice payload 8192 bytes"))
        println("PASS  V2 unprompted voice_begin(7, katniss) accepted and streamed (no immediate end)")

        // V3. TV never answers (noack): fallback after 1.5 s, NO second SEARCH; no box open -> YouTube search link
        File(dir, "noack.silent").writeText("")
        File(dir, "noack").delete()
        // mock without voice: SEARCH does nothing
        File(dir, "novoice").delete()
        val t0 = clock()
        val searchesBefore = Regex("KEYCODE_SEARCH").findAll(mockLog()).count()
        File(dir, "mute_search").writeText("")
        val id3 = pressMic()
        val took = clock() - t0
        File(dir, "mute_search").delete()
        assertEquals(null, id3); assertTrue("fallback after ~1.5 s ($took ms)", took in 1400..2200)
        val q = "star wars"
        val uri = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8")
        assertFalse(p.ime.fieldOpen)
        p.session!!.send(Remote.appLink(uri)) // RemoteController.searchText with no TV text box
        Thread.sleep(600)
        assertTrue(p.alive)
        assertTrue(mockLog().contains("app_link: \"$uri\""))
        assertEquals("exactly one SEARCH (the mic press), none from the fallback", searchesBefore + 1, Regex("KEYCODE_SEARCH").findAll(mockLog()).count())
        println("PASS  V3 no voice_begin → phone recogniser after ${took} ms, no extra SEARCH, YouTube search link sent")

        // V4. fallback with a TV text box open: typed with delta logic
        focusField(p, "")
        p.panel = true; p.phoneText = tv(); p.ime.userOpened(tv())
        p.type(q)
        check(p, "V4 phone-recogniser text typed into the open TV text box")
        File(dir, "noack.silent").delete(); File(dir, "shieldmode").delete()
    }

    /** Power button keys reach the TV (what RemoteController.power sends: POWER, or WAKEUP when the link was down). */
    @Test fun powerKeys() {
        assumeTrue(dir != null)
        val p = pairedPhone(); p.connect()
        p.session!!.send(Remote.keyInject(Remote.Key.POWER))
        p.session!!.send(Remote.keyInject(Remote.Key.SLEEP))
        p.session!!.send(Remote.keyInject(Remote.Key.WAKEUP))
        Thread.sleep(600)
        val l = File(dir, "mocktv.log").readText()
        for (k in listOf("KEYCODE_POWER direction: SHORT", "KEYCODE_SLEEP direction: SHORT", "KEYCODE_WAKEUP direction: SHORT")) assertTrue("mock log missing $k", l.contains(k))
        // link down -> reconnect, then WAKEUP is delivered on the new session
        File(dir, "drop").writeText(""); Thread.sleep(1500); assertFalse(p.alive)
        p.connect(); p.session!!.send(Remote.keyInject(Remote.Key.WAKEUP)); Thread.sleep(500)
        assertTrue(File(dir, "mocktv.log").readText().substringAfterLast("REMOTE connect").contains("KEYCODE_WAKEUP direction: SHORT"))
        println("PASS  P1 power: POWER / SLEEP / WAKEUP sent; WAKEUP after reconnect")
    }
}
