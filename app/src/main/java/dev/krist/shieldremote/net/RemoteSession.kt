package dev.krist.shieldremote.net

import android.os.SystemClock
import dev.krist.shieldremote.proto.FrameDecoder
import dev.krist.shieldremote.proto.Framing
import dev.krist.shieldremote.proto.Remote
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * One live TLS session on port 6466. Answers configure / set_active / ping exactly like
 * androidtvremote2, and reports everything else to [listener]. [run] blocks until the
 * session dies (socket error, EOF, watchdog) and always returns the reason.
 */
class RemoteSession(
    private val socket: SSLSocket,
    requestedFeatures: Int,
    private val listener: Listener,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    interface Listener {
        fun onReady()
        fun onMessage(msg: Remote.Msg)
        fun onDeviceInfo(info: Remote.DeviceInfo)
        /** Every parsed inbound message, before handling (diagnostics). */
        fun onRaw(msg: Remote.Msg) {}
    }

    companion object {
        /** Server pings every ~5 s; androidtvremote2 gives up after 16 s of silence. We are a bit stricter. */
        const val WATCHDOG_MS = 12_000L
    }

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "atv-writer").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    @Volatile var activeFeatures = requestedFeatures; private set
    @Volatile var lastRx = clock(); private set
    @Volatile var lastTx = clock(); private set
    @Volatile var ready = false; private set
    @Volatile private var configured = false

    val isOpen get() = !closed.get()

    fun send(payload: ByteArray) {
        if (closed.get()) return
        val framed = Framing.frame(payload)
        runCatching {
            writer.execute {
                try {
                    val out = socket.outputStream
                    out.write(framed); out.flush()
                    lastTx = clock()
                } catch (e: Exception) { close() }
            }
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.closeQuietly()
            writer.shutdown()
            runCatching { writer.awaitTermination(200, TimeUnit.MILLISECONDS) }
        }
    }

    /** Blocks reading frames. Returns a human readable reason when the session ends. */
    fun run(): String {
        val decoder = FrameDecoder()
        val buf = ByteArray(16 * 1024)
        socket.soTimeout = 1000 // wake up regularly to evaluate the watchdog
        var configureFallbackAt = 0L
        try {
            while (!closed.get()) {
                val n = try { socket.inputStream.read(buf) } catch (e: SocketTimeoutException) { 0 }
                val now = clock()
                if (n < 0) return "TV closed the connection"
                if (n == 0) {
                    if (now - maxOf(lastRx, lastTx) > WATCHDOG_MS || now - lastRx > WATCHDOG_MS + 8_000) return "No response from TV (watchdog)"
                    // Some firmwares may never send remote_start: become ready shortly after set_active.
                    if (!ready && configured && configureFallbackAt in 1..now) markReady()
                    continue
                }
                lastRx = now
                for (frame in decoder.feed(buf, n)) {
                    val msg = try { Remote.parse(frame) } catch (e: Exception) { continue }
                    listener.onRaw(msg)
                    when (msg) {
                        is Remote.Msg.PingRequest -> send(Remote.pingResponse(msg.val1)) // reply first, always
                        is Remote.Msg.Configure -> {
                            listener.onDeviceInfo(msg.device)
                            activeFeatures = activeFeatures and msg.features
                            send(Remote.configure(activeFeatures))
                        }
                        is Remote.Msg.SetActive -> {
                            send(Remote.setActive(activeFeatures))
                            configured = true
                            configureFallbackAt = now + 1500
                        }
                        is Remote.Msg.Start -> { markReady(); listener.onMessage(msg) }
                        else -> listener.onMessage(msg)
                    }
                }
            }
            return "Closed"
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        } finally {
            close()
        }
    }

    private fun markReady() {
        if (!ready) { ready = true; listener.onReady() }
    }
}
