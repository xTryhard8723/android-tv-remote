package dev.krist.shieldremote.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Pairing secret computation, byte-for-byte equivalent to androidtvremote2 pairing.py:
 *   sha256( hex(client_n) | hex("0"+client_e) | hex(server_n) | hex("0"+server_e) | code[2:] )
 * The code is valid when digest[0] == int(code[0:2], 16).
 */
object PairingSecret {
    class InvalidCode(msg: String) : Exception(msg)

    /** Equivalent of Python `bytes.fromhex(f"{n:X}")` (left-padded to an even number of digits). */
    internal fun hexBytes(hex: String): ByteArray {
        val h = if (hex.length % 2 == 1) "0$hex" else hex
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun normalize(code: String): String = code.trim().uppercase()

    fun validateFormat(code: String) {
        val c = normalize(code)
        if (c.length != 6) throw InvalidCode("The code must be exactly 6 characters")
        if (!c.all { it in '0'..'9' || it in 'A'..'F' }) throw InvalidCode("The code only contains 0-9 and A-F")
    }

    fun compute(clientN: BigInteger, clientE: BigInteger, serverN: BigInteger, serverE: BigInteger, code: String): ByteArray {
        val c = normalize(code)
        validateFormat(c)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(hexBytes(clientN.toString(16).uppercase()))
        md.update(hexBytes("0" + clientE.toString(16).uppercase()))
        md.update(hexBytes(serverN.toString(16).uppercase()))
        md.update(hexBytes("0" + serverE.toString(16).uppercase()))
        md.update(hexBytes(c.substring(2)))
        return md.digest()
    }

    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        return compute(c.modulus, c.publicExponent, s.modulus, s.publicExponent, code)
    }

    /** Throws if the digest does not match the code's check byte (i.e. user typed the wrong code). */
    fun check(digest: ByteArray, code: String) {
        val expected = normalize(code).substring(0, 2).toInt(16)
        if ((digest[0].toInt() and 0xFF) != expected) throw InvalidCode("That code doesn't match the one on the TV")
    }
}
