package dev.krist.shieldremote.net

import dev.krist.shieldremote.proto.FrameDecoder
import dev.krist.shieldremote.proto.Framing
import dev.krist.shieldremote.proto.Polo
import dev.krist.shieldremote.protocol.PairingSecret
import java.io.Closeable
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket

class PairingException(msg: String) : Exception(msg)

/**
 * Pairing over TLS port 6467 (polo protocol), same flow as androidtvremote2:
 * pairing_request -> ack -> options -> options -> configuration -> configuration_ack (TV shows code)
 * -> secret -> secret_ack. Blocking; call from a background thread.
 */
class PairingClient(
    private val sslContext: () -> javax.net.ssl.SSLContext,
    private val clientCert: () -> X509Certificate,
    private val host: String,
    private val port: Int = 6467,
) : Closeable {
    constructor(certs: CertStore, host: String, port: Int = 6467) : this(certs::sslContext, { certs.identity().second }, host, port)

    private var socket: SSLSocket? = null
    private val decoder = FrameDecoder()
    private val pending = ArrayDeque<ByteArray>()
    var serverName: String? = null; private set

    private fun send(bytes: ByteArray) {
        val out = socket!!.outputStream
        out.write(Framing.frame(bytes)); out.flush()
    }

    private fun receive(): Polo.Message {
        val s = socket!!
        val buf = ByteArray(4096)
        while (pending.isEmpty()) {
            val n = s.inputStream.read(buf)
            if (n < 0) throw IOException("TV closed the pairing connection")
            pending.addAll(decoder.feed(buf, n))
        }
        val msg = Polo.parse(pending.removeFirst())
        if (msg.status != Polo.STATUS_OK) {
            throw PairingException(
                when (msg.status) {
                    Polo.STATUS_BAD_SECRET -> "The TV rejected the code"
                    Polo.STATUS_BAD_CONFIGURATION -> "The TV rejected the pairing configuration"
                    else -> "Pairing error from TV (status ${msg.status})"
                }
            )
        }
        return msg
    }

    private fun expect(kind: Polo.Kind): Polo.Message {
        val m = receive()
        if (m.kind != kind) throw PairingException("Unexpected pairing reply: ${m.kind} (wanted $kind)")
        return m
    }

    /** Step 1: after this returns the TV displays a 6 character code. */
    fun start(clientName: String, socketFactory: SocketFactory?) {
        val s = TlsConnector.connect(sslContext(), host, port, socketFactory)
        s.soTimeout = 10_000 // every pairing step is answered by the TV itself
        socket = s
        send(Polo.pairingRequest(clientName))
        serverName = expect(Polo.Kind.PAIRING_REQUEST_ACK).serverName
        send(Polo.options())
        expect(Polo.Kind.OPTIONS)
        send(Polo.configuration())
        expect(Polo.Kind.CONFIGURATION_ACK)
        s.soTimeout = 0 // waiting for the human to read the code; the TV holds the session open
    }

    /** Step 2: verify the code locally, send the secret and wait for secret_ack. */
    fun finish(code: String) {
        val s = socket ?: throw PairingException("Pairing session is not open")
        val serverCert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw PairingException("TV did not present a certificate")
        val digest = PairingSecret.compute(clientCert(), serverCert, code)
        PairingSecret.check(digest, code)
        s.soTimeout = 10_000
        send(Polo.secret(digest))
        expect(Polo.Kind.SECRET_ACK)
    }

    override fun close() { socket?.closeQuietly(); socket = null }
}
