package sh.arek.gloaming.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app's outbound HTTP boundary.
 *
 * Narrow on purpose: all traffic is a plain GET of a small public document, and
 * keeping the surface this small means the logic above it can be tested without
 * a server or a mock web stack.
 */
interface HttpFetcher {
    /**
     * Fetches a URL and returns its body.
     *
     * @throws IOException on any transport failure or non-success status.
     */
    suspend fun getBytes(url: String): ByteArray
}

/**
 * OkHttp-backed fetcher.
 *
 * The User-Agent names the app, which AuroraWatch UK's terms require and which
 * is simply good manners towards every other source.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {

    override suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET $url returned ${response.code}")
            }
            response.body.bytes()
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT: String = "Gloaming/0.1 (+https://codeberg.org/arek/Gloaming)"
    }
}
