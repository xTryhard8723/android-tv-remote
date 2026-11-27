package dev.krist.shieldremote

import dev.krist.shieldremote.protocol.PairingSecret
import dev.krist.shieldremote.protocol.SelfSignedCert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey

class PairingSecretTest {
    @Test fun matchesReferenceImplementation() {
        for (v in GoldenVectors.secrets) {
            val d = PairingSecret.compute(BigInteger(v.clientN, 16), BigInteger.valueOf(v.clientE.toLong()), BigInteger(v.serverN, 16), BigInteger.valueOf(v.serverE.toLong()), v.code)
            assertEquals(v.digest, d.hex())
        }
    }

    @Test fun lowercaseCodeIsAccepted() {
        val v = GoldenVectors.secrets[2] // code "7e5d4c"
        val d = PairingSecret.compute(BigInteger(v.clientN, 16), BigInteger.valueOf(v.clientE.toLong()), BigInteger(v.serverN, 16), BigInteger.valueOf(v.serverE.toLong()), v.code.uppercase())
        assertEquals(v.digest, d.hex())
    }

    @Test fun checkByteValidation() {
        val v = GoldenVectors.secrets[0]
        val d = v.digest.hex()
        val good = "%02X".format(d[0].toInt() and 0xFF) + v.code.substring(2)
        PairingSecret.check(d, good)
        val bad = "%02X".format((d[0].toInt() + 1) and 0xFF) + v.code.substring(2)
        try { PairingSecret.check(d, bad); fail() } catch (_: PairingSecret.InvalidCode) {}
    }

    @Test fun formatValidation() {
        for (c in listOf("", "12345", "1234567", "GHIJKL", "12 345")) {
            try { PairingSecret.validateFormat(c); fail(c) } catch (_: PairingSecret.InvalidCode) {}
        }
        PairingSecret.validateFormat("0aF9b3")
    }

    @Test fun hexBytesMatchesPythonFromhex() {
        assertEquals("010001", PairingSecret.hexBytes("0" + BigInteger.valueOf(65537).toString(16)).hex())
        assertEquals("03", PairingSecret.hexBytes("0" + "3").hex())
        assertEquals("0abc", PairingSecret.hexBytes("ABC").hex())
    }

    @Test fun selfSignedCertificateIsValidAndUsable() {
        val kp = SelfSignedCert.generateKeyPair()
        val cert = SelfSignedCert.create(kp, "ShieldRemote Test")
        cert.checkValidity()
        cert.verify(kp.public)
        assertTrue(cert.subjectX500Principal.name.contains("ShieldRemote Test"))
        val pub = cert.publicKey as RSAPublicKey
        assertEquals(2048, pub.modulus.bitLength())
        assertEquals(BigInteger.valueOf(65537), pub.publicExponent)
        // cert-based overload equals the BigInteger path
        val server = SelfSignedCert.create(SelfSignedCert.generateKeyPair(), "server")
        val sp = server.publicKey as RSAPublicKey
        assertEquals(
            PairingSecret.compute(pub.modulus, pub.publicExponent, sp.modulus, sp.publicExponent, "ABCDEF").hex(),
            PairingSecret.compute(cert, server, "ABCDEF").hex(),
        )
    }
}
