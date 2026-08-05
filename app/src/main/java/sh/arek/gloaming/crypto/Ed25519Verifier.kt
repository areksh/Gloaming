package sh.arek.gloaming.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification, following the reference algorithm in
 * RFC 8032 section 5.1.7.
 *
 * The app needs this because it verifies the signed manifest, and Android's own
 * Ed25519 support only arrives around API 33 while the app targets API 24. A
 * general-purpose crypto library would add megabytes to an APK aimed at F-Droid
 * for the sake of one primitive.
 *
 * Rolling one's own cryptography is normally a poor idea, so the reasoning for
 * doing it here is worth stating:
 *
 * - **Verification only.** No private key is ever handled on the device. Every
 *   input is public: a public key compiled into the APK, a manifest served over
 *   HTTPS, and its signature. There is no secret to leak through a side channel,
 *   so the constant-time discipline that makes signing implementations hard does
 *   not apply.
 * - **The risk is correctness, and correctness is testable.** The
 *   implementation is checked against the RFC 8032 section 7.1 vectors and
 *   against a real signature produced by the project's own Worker.
 * - **Clarity over speed.** Arithmetic uses [BigInteger] rather than packed
 *   limbs. A verification costs tens of milliseconds and happens about once a
 *   day, so the readable version is the right trade.
 *
 * A failed verification must be treated as a hostile manifest, not a transient
 * error: the app falls back to its last known-good copy rather than trusting
 * the response.
 */
object Ed25519Verifier {

    /** Field modulus, 2^255 - 19. */
    private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

    /** Order of the base point group. */
    private val L =
        BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

    /** Curve constant d = -121665 / 121666. */
    private val D = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)

    /** A square root of -1, used to pick the right root when decompressing. */
    private val SQRT_M1 = BigInteger.TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    private val TWO = BigInteger.TWO

    /**
     * A point in extended homogeneous coordinates, where the affine point is
     * (X/Z, Y/Z). Keeping Z lets the whole scalar multiplication avoid modular
     * inversion, which is by far the most expensive operation here.
     */
    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    private val BASE: Point by lazy {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0) ?: error("Ed25519 base point is not on the curve")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /**
     * Verifies a detached Ed25519 signature.
     *
     * @param publicKey the 32-byte compressed public key.
     * @param message the exact bytes that were signed.
     * @param signature the 64-byte detached signature.
     * @return true only if the signature is valid for this key and message.
     *   Malformed input returns false rather than throwing, so a caller cannot
     *   accidentally treat a parse failure as a pass.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false

        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false

        val s = littleEndian(signature.copyOfRange(32, 64))
        // S must already be reduced. Without this, L can be added to a valid S
        // to produce a second signature over the same document, so a signature
        // would no longer identify one exact set of bytes.
        if (s >= L) return false

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(rBytes)
        digest.update(publicKey)
        digest.update(message)
        val k = littleEndian(digest.digest()).mod(L)

        // Valid when [s]B == R + [k]A.
        return equal(scalarMultiply(s, BASE), add(r, scalarMultiply(k, a)))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun add(p1: Point, p2: Point): Point {
        val a = (p1.y - p1.x).multiply(p2.y - p2.x).mod(P)
        val b = (p1.y + p1.x).multiply(p2.y + p2.x).mod(P)
        val c = TWO.multiply(p1.t).multiply(p2.t).multiply(D).mod(P)
        val d = TWO.multiply(p1.z).multiply(p2.z).mod(P)
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Point(
            e.multiply(f).mod(P),
            g.multiply(h).mod(P),
            f.multiply(g).mod(P),
            e.multiply(h).mod(P),
        )
    }

    /**
     * Double-and-add. The addition formula above is unified on this curve, so
     * it doubles correctly too and no separate doubling case is needed.
     */
    private fun scalarMultiply(scalar: BigInteger, point: Point): Point {
        var result = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var addend = point
        var remaining = scalar

        while (remaining > BigInteger.ZERO) {
            if (remaining.testBit(0)) {
                result = add(result, addend)
            }
            addend = add(addend, addend)
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    /** Compares projective points without inverting: x1/z1 == x2/z2. */
    private fun equal(p1: Point, p2: Point): Boolean =
        p1.x.multiply(p2.z).subtract(p2.x.multiply(p1.z)).mod(P).signum() == 0 &&
            p1.y.multiply(p2.z).subtract(p2.y.multiply(p1.z)).mod(P).signum() == 0

    /**
     * Recovers the x coordinate for a given y and sign bit, or null when no
     * such point exists - which is how a value that is not on the curve is
     * rejected.
     */
    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null

        val y2 = y.multiply(y).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (denominator.signum() == 0) return null

        val x2 = (y2 - BigInteger.ONE).multiply(denominator.modInverse(P)).mod(P)
        if (x2.signum() == 0) {
            // x == 0 has only one valid sign; the other encoding is invalid.
            return if (sign == 1) null else BigInteger.ZERO
        }

        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P)
        }
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null

        if (x.testBit(0) != (sign == 1)) {
            x = P - x
        }
        return x
    }

    /** Decodes a 32-byte compressed point, or null if it encodes no point. */
    private fun decompress(bytes: ByteArray): Point? {
        val sign = (bytes[31].toInt() shr 7) and 1
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()

        val y = littleEndian(yBytes)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }
}
