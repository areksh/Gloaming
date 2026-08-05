package sh.arek.gloaming.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 8032 section 7.1 vectors, plus the project's own live manifest.
 *
 * Every vector here was checked against OpenSSL and Node before being written
 * down, rather than copied from memory.
 */
class Ed25519VerifierTest {

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // RFC 8032, 7.1, TEST 1 - empty message.
    private val test1Key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val test1Message = ByteArray(0)
    private val test1Signature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    // RFC 8032, 7.1, TEST 2 - one-byte message.
    private val test2Key = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val test2Message = hex("72")
    private val test2Signature = hex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    // RFC 8032, 7.1, TEST 3 - two-byte message.
    private val test3Key = hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    private val test3Message = hex("af82")
    private val test3Signature = hex(
        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
            "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
    )

    @Test
    fun `accepts RFC 8032 test 1, an empty message`() {
        assertTrue(Ed25519Verifier.verify(test1Key, test1Message, test1Signature))
    }

    @Test
    fun `accepts RFC 8032 test 2`() {
        assertTrue(Ed25519Verifier.verify(test2Key, test2Message, test2Signature))
    }

    @Test
    fun `accepts RFC 8032 test 3`() {
        assertTrue(Ed25519Verifier.verify(test3Key, test3Message, test3Signature))
    }

    @Test
    fun `rejects a signature with a flipped bit`() {
        val tampered = test3Signature.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()

        assertFalse(Ed25519Verifier.verify(test3Key, test3Message, tampered))
    }

    @Test
    fun `rejects a message with a flipped bit`() {
        val tampered = test3Message.copyOf()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()

        assertFalse(Ed25519Verifier.verify(test3Key, tampered, test3Signature))
    }

    @Test
    fun `rejects a signature made by a different key`() {
        assertFalse(Ed25519Verifier.verify(test2Key, test3Message, test3Signature))
    }

    @Test
    fun `rejects a signature of the wrong length`() {
        assertFalse(Ed25519Verifier.verify(test3Key, test3Message, test3Signature.copyOf(63)))
        assertFalse(Ed25519Verifier.verify(test3Key, test3Message, test3Signature + 0))
    }

    @Test
    fun `rejects a public key of the wrong length`() {
        assertFalse(Ed25519Verifier.verify(test3Key.copyOf(31), test3Message, test3Signature))
    }

    /**
     * S must be reduced modulo the group order. Without this check a valid
     * signature can be trivially altered into another that still verifies,
     * which breaks the assumption that a signature identifies one exact
     * document.
     */
    @Test
    fun `rejects a signature whose scalar is not reduced`() {
        // Add the group order L to S, which leaves S congruent but out of range.
        val order = java.math.BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
        val s = java.math.BigInteger(1, test3Signature.copyOfRange(32, 64).reversedArray())
        val unreduced = (s + order).toByteArray().let { bytes ->
            // to 32-byte little-endian
            val be = ByteArray(32)
            val src = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
            System.arraycopy(src, 0, be, 32 - src.size, src.size)
            be.reversedArray()
        }
        val malleable = test3Signature.copyOfRange(0, 32) + unreduced

        assertFalse(Ed25519Verifier.verify(test3Key, test3Message, malleable))
    }

    @Test
    fun `rejects a public key that is not a curve point`() {
        val notOnCurve = ByteArray(32) { 0xFF.toByte() }

        assertFalse(Ed25519Verifier.verify(notOnCurve, test3Message, test3Signature))
    }

    /**
     * The signature the deployed Worker actually produces, verified against the
     * public key that ships in the APK. If this fails, the app rejects every
     * real manifest no matter how well the RFC vectors pass.
     */
    @Test
    fun `accepts the live manifest signed by the project key`() {
        val publicKey = hex("225e9e2dbb74f7daedc7ff9d9f5bda8714068a2ad7ecf27a312838fc6bc6d93d")
        val signature = hex(
            "ca47a38885037b3646291bfd9f6e6d14e89c1381ed966e07f42a6ec581aa6388" +
                "1343eed0721ef7c67e0987ff32ca90f0f7a6da3de03daf0554739e73d8cf6f0f",
        )
        val manifest = (
            """{"schema":1,"min_supported_version":1,"message":null,""" +
                """"endpoints":{"status":"https://sky.arek.sh/v1/status.json",""" +
                """"oval":"https://sky.arek.sh/v1/oval.bin",""" +
                """"weather":"https://api.open-meteo.com/v1/forecast"}}"""
            ).toByteArray(Charsets.UTF_8)

        assertTrue(Ed25519Verifier.verify(publicKey, manifest, signature))
    }

    @Test
    fun `rejects the live manifest if a single character changes`() {
        val publicKey = hex("225e9e2dbb74f7daedc7ff9d9f5bda8714068a2ad7ecf27a312838fc6bc6d93d")
        val signature = hex(
            "ca47a38885037b3646291bfd9f6e6d14e89c1381ed966e07f42a6ec581aa6388" +
                "1343eed0721ef7c67e0987ff32ca90f0f7a6da3de03daf0554739e73d8cf6f0f",
        )
        val tampered = (
            """{"schema":1,"min_supported_version":1,"message":"pwned",""" +
                """"endpoints":{"status":"https://evil.example/status.json",""" +
                """"oval":"https://sky.arek.sh/v1/oval.bin",""" +
                """"weather":"https://api.open-meteo.com/v1/forecast"}}"""
            ).toByteArray(Charsets.UTF_8)

        assertFalse(Ed25519Verifier.verify(publicKey, tampered, signature))
    }
}
