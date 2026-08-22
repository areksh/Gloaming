package sh.arek.gloaming.data

import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult
import sh.arek.gloaming.satellites.Elements
import sh.arek.gloaming.satellites.SatellitesDocument

/** Persistence for the published elements, and when they were last transferred. */
interface SatelliteCache {
    fun read(): Pair<String, String?>?
    fun write(body: String, etag: String?)
    /** When the cached body was last written, or null if there is none. */
    fun writtenAt(): Long?
}

/**
 * Fetches `sats.json`, the orbital elements every pass prediction rests on.
 *
 * Conditional, and rarely transferring anything: the Worker republishes every
 * two hours and most wakes will get a 304. Elements stay usable for days, so a
 * failed fetch falls back to the cache rather than throwing - a pass predicted
 * from six-hour-old elements is off by seconds, and a pass not predicted at all
 * is off by everything.
 */
class SatelliteRepository(
    private val http: HttpFetcher,
    private val cache: SatelliteCache,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The elements, freshly fetched where possible and cached otherwise. */
    suspend fun refresh(manifest: Manifest): SatellitesDocument? {
        val url = manifest.endpoints[ENDPOINT_SATS] ?: return cached()
        val previous = cache.read()

        val fetched = runCatching {
            when (val result = http.get(url, previous?.second)) {
                HttpResult.NotModified -> null
                is HttpResult.Ok -> {
                    val body = result.body.toString(Charsets.UTF_8)
                    // Parsed before it is cached, so an unreadable body never
                    // replaces a good one.
                    val parsed = Elements.parse(body)
                    cache.write(body, result.etag)
                    parsed
                }
            }
        }.getOrNull()

        return fetched ?: cached()
    }

    fun cached(): SatellitesDocument? =
        cache.read()?.first?.let { runCatching { Elements.parse(it) }.getOrNull() }

    /** How old the held elements are, or null when there are none. */
    fun cachedAgeMillis(): Long? = cache.writtenAt()?.let { now() - it }

    companion object {
        const val ENDPOINT_SATS = "sats"
    }
}
