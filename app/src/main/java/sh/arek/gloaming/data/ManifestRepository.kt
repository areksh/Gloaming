package sh.arek.gloaming.data

import kotlinx.serialization.json.Json
import sh.arek.gloaming.crypto.Ed25519Verifier
import sh.arek.gloaming.net.HttpFetcher

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
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the freshest manifest that can be trusted.
     *
     * @throws ManifestUnavailableException when neither the fetched nor the
     *   cached manifest verifies.
     */
    suspend fun refresh(): Manifest {
        val fetched = runCatching { fetchAndVerify() }.getOrNull()
        if (fetched != null) {
            store.write(fetched.cached)
            return fetched.manifest
        }
        return cachedOrThrow()
    }

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
}
