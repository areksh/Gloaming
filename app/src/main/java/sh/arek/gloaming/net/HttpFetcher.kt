package sh.arek.gloaming.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The outcome of a conditional GET. */
sealed interface HttpResult {
    /** The server sent a body, along with its ETag if it published one. */
    class Ok(val body: ByteArray, val etag: String?) : HttpResult

    /** The cached copy is still current; nothing was transferred. */
    data object NotModified : HttpResult
}

/**
 * The app's outbound HTTP boundary.
 *
 * Narrow on purpose: every request is a GET of one small public document, so
 * the logic above can be tested without a server or a mock web stack.
 *
 * Conditional GET is part of the interface rather than an optimisation bolted
 * on later. The published objects carry `Cache-Control` and ETags precisely so
 * that a device polling all night mostly transfers nothing, and the read path's
 * whole cost model depends on it.
 */
interface HttpFetcher {
    /**
     * Fetches a URL, optionally only if it has changed.
     *
     * @param etag the ETag of the copy already held, or null to fetch
     *   unconditionally.
     * @throws IOException on any transport failure or unsuccessful status.
     */
    suspend fun get(url: String, etag: String? = null): HttpResult
}

/** Fetches a URL and returns its body, treating a 304 as a protocol error. */
suspend fun HttpFetcher.getBytes(url: String): ByteArray =
    when (val result = get(url)) {
        is HttpResult.Ok -> result.body
        HttpResult.NotModified ->
            throw IOException("$url answered 304 to an unconditional request")
    }

/**
 * OkHttp-backed fetcher.
 *
 * The User-Agent names the app, which AuroraWatch UK's terms require and which
 * is good manners towards every other source.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    override suspend fun get(url: String, etag: String?): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == HTTP_NOT_MODIFIED -> HttpResult.NotModified
                response.isSuccessful -> HttpResult.Ok(response.body.bytes(), response.header("ETag"))
                else -> throw IOException("GET $url returned ${response.code}")
            }
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT: String = "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"
        private const val HTTP_NOT_MODIFIED = 304
    }
}
