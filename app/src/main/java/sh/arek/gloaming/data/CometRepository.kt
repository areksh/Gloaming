package sh.arek.gloaming.data

import sh.arek.gloaming.comets.CometFile
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

/** Persistence for the published comets, and when they were last transferred. */
interface CometCache {
    fun read(): Pair<String, String?>?
    fun write(body: String, etag: String?)
    /** When the cached body was last written, or null if there is none. */
    fun writtenAt(): Long?
}

/**
 * Fetches `comets.json`.
 *
 * Deliberately the same shape as [SatelliteRepository] - conditional GET, parse
 * before caching so an unreadable body never replaces a good one, fall back to
 * the cache rather than throw - because it is the same problem. The difference
 * is how long the answer stays good: a satellite's elements are stale in hours
 * and a comet's are usable for months, so a failed fetch here costs almost
 * nothing.
 *
 * What does go stale is the measured magnitude underneath, which is why the
 * document carries the time of each observation rather than relying on the age
 * of the file, and why the app says which kind of number it is showing.
 */
class CometRepository(
    private val http: HttpFetcher,
    private val cache: CometCache,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The comets, freshly fetched where possible and cached otherwise. */
    suspend fun refresh(manifest: Manifest): CometsDocument? {
        val url = manifest.endpoints[ENDPOINT_COMETS] ?: return cached()
        val previous = cache.read()

        val fetched = runCatching {
            when (val result = http.get(url, previous?.second)) {
                HttpResult.NotModified -> null
                is HttpResult.Ok -> {
                    val body = result.body.toString(Charsets.UTF_8)
                    val parsed = CometFile.parse(body)
                    cache.write(body, result.etag)
                    parsed
                }
            }
        }.getOrNull()

        return fetched ?: cached()
    }

    fun cached(): CometsDocument? =
        cache.read()?.first?.let { runCatching { CometFile.parse(it) }.getOrNull() }

    /** How old the held document is, or null when there is none. */
    fun cachedAgeMillis(): Long? = cache.writtenAt()?.let { now() - it }

    companion object {
        /**
         * The manifest key. Absent from every manifest published before Phase 5,
         * which is why [refresh] falls back to the cache rather than failing -
         * an install that has not re-fetched the manifest yet should keep
         * whatever it has rather than lose its comets.
         */
        const val ENDPOINT_COMETS = "comets"
    }
}
