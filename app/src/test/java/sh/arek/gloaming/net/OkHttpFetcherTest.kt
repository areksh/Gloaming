package sh.arek.gloaming.net

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real HTTP client against a real server.
 *
 * Everything above this layer is tested against fakes written to match what the
 * fetcher was assumed to do. That is circular: it proves the callers agree with
 * my assumptions, not that OkHttp behaves that way. Conditional GET in
 * particular is load-bearing - a device polling all night should mostly
 * transfer nothing - and 304 is a status most HTTP code never sees.
 *
 * MockWebServer runs in-process, so this needs no network.
 */
class OkHttpFetcherTest {

    private lateinit var server: MockWebServer
    private val fetcher = OkHttpFetcher()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun url(path: String = "/status.json") = server.url(path).toString()

    @Test
    fun `returns the body and the etag on a 200`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "\"abc123\"")
                .setBody("""{"schema":1}"""),
        )

        val result = fetcher.get(url()) as HttpResult.Ok

        assertEquals("""{"schema":1}""", String(result.body))
        assertEquals("\"abc123\"", result.etag)
    }

    @Test
    fun `sends the etag as If-None-Match when one is held`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        fetcher.get(url(), etag = "\"abc123\"")

        assertEquals("\"abc123\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `sends no If-None-Match when nothing is held`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))

        fetcher.get(url())

        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    // The whole point of the caching contract: an unchanged document costs one
    // request and no body at all.
    @Test
    fun `reports a 304 as not modified rather than an empty body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        val result = fetcher.get(url(), etag = "\"abc123\"")

        assertEquals(HttpResult.NotModified, result)
    }

    /** AuroraWatch UK's terms require the User-Agent to name the app. */
    @Test
    fun `names the app in the User-Agent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x"))

        fetcher.get(url())

        val agent = server.takeRequest().getHeader("User-Agent")
        assertTrue("User-Agent was '$agent'", agent!!.contains("Gloaming"))
    }

    @Test
    fun `treats a server error as a failure rather than a body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))

        val failure = runCatching { fetcher.get(url()) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("503"))
    }

    @Test
    fun `treats a 404 as a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val failure = runCatching { fetcher.get(url()) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
    }

    /**
     * A 304 to an unconditional request is a protocol violation, and silently
     * returning nothing would look like an empty document to the caller.
     */
    @Test
    fun `getBytes rejects a 304 it never asked for`() = runTest {
        server.enqueue(MockResponse().setResponseCode(304))

        val failure = runCatching { fetcher.getBytes(url()) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertTrue(failure!!.message!!.contains("304"))
    }

    @Test
    fun `getBytes returns the body on success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))

        assertEquals("hello", String(fetcher.getBytes(url())))
    }

    /** Binary bodies must survive byte for byte: the grid is gzip. */
    @Test
    fun `returns binary bodies unaltered`() = runTest {
        val gzipMagic = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(okio.Buffer().write(gzipMagic)),
        )

        val result = fetcher.get(url("/oval.bin")) as HttpResult.Ok

        assertArrayEquals(gzipMagic, result.body)
    }

    @Test
    fun `reports a connection failure rather than hanging`() = runTest {
        val dead = server.url("/gone").toString()
        server.shutdown()

        val failure = runCatching { fetcher.getBytes(dead) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
    }

    /**
     * The signature does not help here.
     *
     * The manifest is verified only after its bytes are in memory, so a body
     * that never ends exhausts the heap before anything gets to reject it - and
     * the poll that would OOM is a background one at three in the morning. The
     * published objects are kilobytes; the cap is generous by three orders of
     * magnitude and still bounds the damage.
     */
    @Test
    fun `refuses a body larger than any published object`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("x".repeat(OkHttpFetcher.MAX_BODY_BYTES.toInt() + 1)),
        )

        val error = runCatching { fetcher.get(url()) }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is IOException)
    }

    @Test
    fun `accepts a body at the limit`() = runTest {
        val body = "x".repeat(OkHttpFetcher.MAX_BODY_BYTES.toInt())
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = fetcher.get(url()) as HttpResult.Ok

        assertEquals(OkHttpFetcher.MAX_BODY_BYTES, result.body.size.toLong())
    }

    /**
     * A refused connection carries none of the context a non-200 does: OkHttp
     * reports it as an exception whose message is an address, and the poller
     * that catches it records that as the reason the check failed. "Last check
     * failed: example.test" reads as though the app is naming a website rather
     * than a fault - so the URL and the kind of failure are put back.
     */
    @Test
    fun `names the URL and the fault when the connection is refused`() = runTest {
        val dead = url()
        server.shutdown() // nothing is listening on that port now

        val error = runCatching { fetcher.get(dead) }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is IOException)
        assertTrue("did not name the URL: ${error?.message}", error!!.message!!.contains(dead))
        assertTrue("did not say what happened: ${error.message}", error.cause != null)
    }

    /**
     * A poll runs inside a receiver holding a wake lock with a timeout of its
     * own, so a stalled connection has to fail rather than hang: without a call
     * timeout, OkHttp's per-stage timeouts can be reset indefinitely by a
     * trickle of bytes.
     */
    @Test
    fun `gives up on a call that never finishes`() {
        assertTrue(
            "no call timeout is set",
            OkHttpFetcher.defaultClient().callTimeoutMillis > 0,
        )
    }
}
