package dev.krist.shieldremote.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Generates a self-signed RSA-2048 X.509 v3 certificate with a tiny DER encoder (no BouncyCastle needed). */
object SelfSignedCert {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return byteArrayOf(tag.toByte()) + len(body.size) + body
    }

    private fun seq(vararg p: ByteArray) = tlv(0x30, *p)
    private fun set(vararg p: ByteArray) = tlv(0x31, *p)
    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun oid(vararg bytes: Int) = tlv(0x06, ByteArray(bytes.size) { bytes[it].toByte() })
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private val NULL = byteArrayOf(0x05, 0x00)

    // 1.2.840.113549.1.1.11 sha256WithRSAEncryption
    private val SHA256_RSA = seq(oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), NULL)
    // 2.5.4.3 commonName
    private val CN = oid(0x55, 0x04, 0x03)

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }
    private fun genTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x18, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    fun create(keyPair: KeyPair, commonName: String, validYears: Int = 20): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600_000)
        val notAfter = Date(now + validYears * 365L * 24 * 3600_000)
        val name = seq(set(seq(CN, utf8(commonName))))
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, int(BigInteger.valueOf(2))), // v3
            int(serial),
            SHA256_RSA,
            name,
            seq(utcTime(notBefore), if (notAfter.year + 1900 >= 2050) genTime(notAfter) else utcTime(notAfter)),
            name,
            keyPair.public.encoded, // SubjectPublicKeyInfo DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keyPair.private); update(tbs); sign() }
        val der = seq(tbs, SHA256_RSA, tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}
