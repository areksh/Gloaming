package sh.arek.gloaming.data

import kotlinx.serialization.json.Json
import sh.arek.gloaming.aurora.OvalGrid
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.HttpResult

/** Everything the app knows about the sky right now, and how fresh it is. */
class SkySnapshot(
    val status: SkyStatus,
    /** Null when no grid has been fetched, or the cached one is unusable. */
    val grid: OvalGrid?,
    /** Milliseconds since the epoch when the status was last transferred. */
    val statusFetchedAt: Long,
)

/** The cached grid and the ETag it was served with. */
class CachedGrid(val etag: String?, val gzipped: ByteArray)

/** Persistence for whatever survives between wakes. */
interface SkyCache {
    fun readStatus(): Pair<String, String?>?
    fun writeStatus(body: String, etag: String?)
    fun readGrid(): CachedGrid?
    fun writeGrid(cached: CachedGrid)
}

/**
 * Fetches `status.json`, and the aurora grid only when it is actually needed.
 *
 * The two-tier fetch is the point. `oval.bin` is roughly four times the size of
 * `status.json`, for data that is usually all zeros, so the device fetches it
 * only when the grid has genuinely changed or it has none. Everything else is a
 * conditional GET that mostly transfers nothing at all.
 *
 * Failures degrade rather than throw wherever a cached answer exists. A device
 * that wakes at 3 a.m. on a flaky connection should still be able to say what
 * it knew an hour ago, with its age shown, rather than showing nothing.
 */
class SkyRepository(
    private val http: HttpFetcher,
    private val cache: SkyCache,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Refreshes from the endpoints the manifest advertises.
     *
     * @throws SkyUnavailableException when there is no status document to be
     *   had, from the network or the cache.
     */
    suspend fun refresh(manifest: Manifest): SkySnapshot {
        val statusUrl = manifest.endpoints[ENDPOINT_STATUS]
            ?: throw SkyUnavailableException("Manifest advertises no status endpoint")

        val cachedStatus = cache.readStatus()
        val status = fetchStatus(statusUrl, cachedStatus)
        val grid = resolveGrid(manifest, status)

        return SkySnapshot(status, grid, now())
    }

    private suspend fun fetchStatus(url: String, cached: Pair<String, String?>?): SkyStatus {
        val fetched = runCatching {
            when (val result = http.get(url, cached?.second)) {
                HttpResult.NotModified -> null
                is HttpResult.Ok -> {
                    val body = result.body.toString(Charsets.UTF_8)
                    // Parse before caching, so an unparseable body never
                    // replaces a good cached one.
                    val parsed = json.decodeFromString(SkyStatus.serializer(), body)
                    cache.writeStatus(body, result.etag)
                    parsed
                }
            }
        }.getOrNull()

        if (fetched != null) return fetched

        val body = cached?.first ?: throw SkyUnavailableException("No status available")
        return runCatching { json.decodeFromString(SkyStatus.serializer(), body) }
            .getOrElse { throw SkyUnavailableException("Cached status is unreadable", it) }
    }

    /**
     * Returns the grid, fetching it only when the status says it has changed.
     *
     * A grid that fails to inflate is discarded rather than cached, so a
     * corrupted download cannot poison later wakes.
     */
    private suspend fun resolveGrid(manifest: Manifest, status: SkyStatus): OvalGrid? {
        val cached = cache.readGrid()
        val haveCurrent = cached != null && cached.etag != null && cached.etag == status.ovalEtag

        if (!haveCurrent) {
            val url = manifest.endpoints[ENDPOINT_OVAL]
            if (url != null) {
                val fetched = runCatching {
                    when (val result = http.get(url, cached?.etag)) {
                        HttpResult.NotModified -> null
                        is HttpResult.Ok -> {
                            // Inflate before caching: an unusable grid must not
                            // replace a usable one.
                            val grid = OvalGrid.fromGzip(result.body)
                            cache.writeGrid(CachedGrid(result.etag, result.body))
                            grid
                        }
                    }
                }.getOrNull()
                if (fetched != null) return fetched
            }
        }

        return cached?.let { runCatching { OvalGrid.fromGzip(it.gzipped) }.getOrNull() }
    }

    companion object {
        const val ENDPOINT_STATUS = "status"
        const val ENDPOINT_OVAL = "oval"
    }
}

/** Raised when the app cannot say anything about the sky at all. */
class SkyUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
