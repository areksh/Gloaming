package sh.arek.gloaming.data

import kotlinx.serialization.json.Json
import sh.arek.gloaming.crypto.Ed25519Verifier
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.getBytes

/**
 * Fetches, verifies and caches the signed manifest.
 *
 * The security posture in one sentence: **nothing is trusted until its
 * signature verifies against the key compiled into this APK.** A manifest that
 * fails verification is treated as hostile rather than merely stale, because
 * `message` renders into a notification on every install and `endpoints`
 * decides where the app sends its traffic. If the domain ever lapsed or were
 * hijacked, that combination would be a phishing primitive.
 *
 * On any failure the app falls back to its last known-good copy - and re-checks
 * that copy's signature too, rather than trusting a file that could have been
 * corrupted or altered on disk.
 */
class ManifestRepository(
    private val http: HttpFetcher,
    private val store: ManifestStore,
    private val publicKey: ByteArray,
    private val manifestUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the freshest manifest that can be trusted.
     *
     * At most one network fetch a day, which is the manifest's documented
     * budget. Every poller calls this on every wake, so without the gate an
     * elevated night of five-minute polls would turn one request into six
     * hundred; a verified copy held for less than a day is simply the answer.
     * The cost is that a moved endpoint, or the kill switch, takes up to a day
     * to reach a device - which is the latency the design already accepts for
     * a document re-fetched daily.
     *
     * The cached copy is verified on every refresh rather than only when the
     * network fails: freshness is a property of a copy that verifies, and its
     * stamp is what a fetched manifest has to beat. That costs one signature
     * check per poll, which is minutes apart.
     *
     * @throws ManifestUnavailableException when neither the fetched nor the
     *   cached manifest verifies.
     */
    suspend fun refresh(): Manifest {
        val cached = lastKnownGood()
        if (cached != null && heldRecently()) return cached

        val fetched = runCatching { fetchAndVerify() }.getOrNull()

        if (fetched != null && !isRollback(fetched.manifest, cached)) {
            store.write(fetched.cached)
            return fetched.manifest
        }
        return cached ?: cachedOrThrow()
    }

    /**
     * Whether the held copy is recent enough to answer without a request.
     *
     * A stamp in the future means the clock has jumped backwards; treating
     * that as fresh would hold the copy until the clock caught up, so it
     * refetches instead.
     */
    private fun heldRecently(): Boolean {
        val age = store.writtenAt()?.let { now() - it } ?: return false
        return age in 0 until REFRESH_INTERVAL_MILLIS
    }

    /**
     * Whether a verified manifest is older than the one already held.
     *
     * Signatures do not expire, so verification alone leaves a replay open: an
     * attacker who could answer for the domain could serve a correctly signed
     * older copy and move the endpoints back to ones they had since taken over,
     * or restore a `message` that had been withdrawn. Refusing to go backwards
     * is what closes it. Equal is not a rollback - the same document is
     * republished whenever it legitimately changes and never otherwise.
     */
    private fun isRollback(fetched: Manifest, cached: Manifest?): Boolean =
        cached != null && fetched.issuedAt < cached.issuedAt

    /** The cached manifest, if one is present and still verifies. */
    fun lastKnownGood(): Manifest? = runCatching { verifyCached() }.getOrNull()

    private class Verified(val manifest: Manifest, val cached: CachedManifest)

    private suspend fun fetchAndVerify(): Verified {
        val body = http.getBytes(manifestUrl)
        val signature = http.getBytes("$manifestUrl.sig")
        return Verified(parseVerified(body, signature), CachedManifest(body, signature))
    }

    private fun cachedOrThrow(): Manifest =
        runCatching { verifyCached() }.getOrElse { cause ->
            throw ManifestUnavailableException(
                "No manifest could be verified, and no usable cached copy exists",
                cause,
            )
        }

    private fun verifyCached(): Manifest {
        val cached = store.read()
            ?: throw ManifestUnavailableException("No cached manifest")
        return parseVerified(cached.body, cached.signature)
    }

    /**
     * Verifies the signature over the exact bytes received, then parses.
     *
     * Order matters: parsing first and verifying a re-serialised form would
     * check a different document from the one that arrived.
     */
    private fun parseVerified(body: ByteArray, signature: ByteArray): Manifest {
        if (!Ed25519Verifier.verify(publicKey, body, signature)) {
            throw ManifestUnavailableException("Manifest signature did not verify")
        }
        return json.decodeFromString(Manifest.serializer(), body.toString(Charsets.UTF_8))
    }

    companion object {
        /** The documented budget: the manifest costs one request a day. */
        const val REFRESH_INTERVAL_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}
