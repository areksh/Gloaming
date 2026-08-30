package sh.arek.gloaming.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import sh.arek.gloaming.describe

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
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    override suspend fun get(url: String, etag: String?): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED -> HttpResult.NotModified
                    response.isSuccessful ->
                        HttpResult.Ok(bounded(url, response), response.header("ETag"))
                    else -> throw Refusal("GET $url returned ${response.code}")
                }
            }
        } catch (refused: Refusal) {
            // Already says which URL and what came back.
            throw refused
        } catch (cause: IOException) {
            // Everything else is the transport: a name that will not resolve, a
            // refused connection, a handshake, a call that ran out of time.
            // Those arrive naming neither this URL nor, in the worst of them,
            // anything beyond an address - and what catches this records the
            // message as the reason tonight's check failed.
            throw IOException("GET $url failed: ${cause.describe()}", cause)
        }
    }

    /** A response this refused, which already carries its URL and its reason. */
    private class Refusal(message: String) : IOException(message)

    /**
     * The body, refusing to buffer more than a published object could be.
     *
     * `bytes()` reads whatever arrives, and the manifest's signature is checked
     * only once the bytes are already in memory - so a body that never ends
     * exhausts the heap before anything can reject it, on a background poll
     * with a wake lock held. Reading one byte past the cap is enough to tell
     * the difference without buffering the rest.
     */
    private fun bounded(url: String, response: Response): ByteArray {
        val source = response.body.source()
        source.request(MAX_BODY_BYTES + 1)
        val buffered = source.buffer.size
        if (buffered > MAX_BODY_BYTES) {
            throw Refusal("GET $url returned more than $MAX_BODY_BYTES bytes")
        }
        return source.buffer.readByteArray()
    }

    companion object {
        const val DEFAULT_USER_AGENT: String = "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"
        private const val HTTP_NOT_MODIFIED = 304

        /**
         * The largest body worth accepting.
         *
         * Every published object is kilobytes - the packed grid is 65 KB before
         * compression and the rest are smaller - so this is generous by three
         * orders of magnitude. It is a bound on the damage, not a size check.
         */
        const val MAX_BODY_BYTES: Long = 2L * 1024 * 1024

        /**
         * A client with a whole-call deadline, not just per-stage timeouts.
         *
         * OkHttp's connect and read timeouts each restart whenever a byte
         * moves, so a server dribbling data can hold a call open indefinitely.
         * The poll that would hang is a background one holding a wake lock with
         * a timeout of its own, and losing that wake outright is worse than
         * failing and retrying at the next.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** Comfortably inside the receiver's own poll timeout. */
        private const val CALL_TIMEOUT_SECONDS = 20L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}
