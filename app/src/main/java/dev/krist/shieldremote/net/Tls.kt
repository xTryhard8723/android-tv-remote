package dev.krist.shieldremote.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import dev.krist.shieldremote.protocol.SelfSignedCert
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Persists the client identity (RSA key + self-signed cert) used for both pairing and remote TLS. */
class CertStore(private val context: Context) {
    private val file get() = File(context.filesDir, "client_identity.p12")
    private val pw = "shieldremote".toCharArray() // file lives in app-private storage
    @Volatile private var cached: Pair<PrivateKey, X509Certificate>? = null

    @Synchronized
    fun identity(): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            runCatching {
                file.inputStream().use { ks.load(it, pw) }
                val key = ks.getKey("client", pw) as PrivateKey
                val cert = ks.getCertificate("client") as X509Certificate
                return (key to cert).also { cached = it }
            }
        }
        val kp = SelfSignedCert.generateKeyPair()
        val cert = SelfSignedCert.create(kp, "Android TV Remote ${Build.MODEL}")
        ks.load(null, null)
        ks.setKeyEntry("client", kp.private, pw, arrayOf(cert))
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { ks.store(it, pw) }
        tmp.renameTo(file)
        return (kp.private to cert).also { cached = it }
    }

    @Synchronized
    fun reset() { cached = null; file.delete() }

    fun sslContext(): SSLContext = identity().let { (key, cert) -> clientSslContext(key, cert) }
}

/** TLS context presenting our client certificate and accepting the TV's self-signed one. */
fun clientSslContext(key: PrivateKey, cert: X509Certificate): SSLContext {
    val pw = "x".toCharArray()
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("client", key, pw, arrayOf(cert)) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(TrustAll), null) }
}

/** The TV presents a self-signed certificate; identity is established by pairing, not by a CA. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Close without waiting for the peer's TLS close_notify (which would block for the read timeout). */
fun SSLSocket.closeQuietly() {
    runCatching { soTimeout = 1 }
    runCatching { close() }
}

class HandshakeFailed(cause: Throwable) : Exception("TLS handshake failed: ${cause.message}", cause)

object TlsConnector {
    /** Opens a TCP connection (optionally bound to a specific network, e.g. Wi-Fi) and completes TLS. */
    fun connect(ctx: SSLContext, host: String, port: Int, socketFactory: SocketFactory?, connectTimeoutMs: Int = 3500): SSLSocket {
        val raw: Socket = (socketFactory ?: SocketFactory.getDefault()).createSocket()
        try {
            raw.tcpNoDelay = true
            raw.keepAlive = true
            raw.setSoLinger(true, 0) // abortive close: never linger on a dead link
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            raw.soTimeout = 6000
        } catch (e: Exception) { runCatching { raw.close() }; throw e }
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            ssl.closeQuietly()
            throw HandshakeFailed(e)
        }
        return ssl
    }
}
