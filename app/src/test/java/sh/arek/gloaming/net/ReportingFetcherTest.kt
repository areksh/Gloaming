package sh.arek.gloaming.net

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fetcher that says what it is doing.
 *
 * A decorator rather than a change to [OkHttpFetcher], so that the one place
 * every request passes through gains a record without the transport gaining a
 * reason to know about screens.
 */
class ReportingFetcherTest {

    private val url = "https://sky.arek.sh/v1/sats.json"

    @Before
    fun forget() = Transfers.forget()

    private class Fake(private val answer: () -> HttpResult) : HttpFetcher {
        var etagSeen: String? = null
        var calls = 0
        override suspend fun get(url: String, etag: String?): HttpResult {
            calls++
            etagSeen = etag
            return answer()
        }
    }

    private fun reporting(answer: () -> HttpResult) =
        Fake(answer).let { it to ReportingFetcher(it) { 42L } }

    /** It is a decorator: the answer is the delegate's, untouched. */
    @Test
    fun `hands back exactly what the delegate returned`() = runBlocking {
        val body = HttpResult.Ok("hello".toByteArray(), "\"tag\"")
        val (fake, fetcher) = reporting { body }

        assertSame(body, fetcher.get(url))
        assertEquals(1, fake.calls)
    }

    /** And the conditional request survives the wrapping, which is the cost model. */
    @Test
    fun `passes the etag through`() = runBlocking {
        val (fake, fetcher) = reporting { HttpResult.NotModified }

        fetcher.get(url, "\"held\"")

        assertEquals("\"held\"", fake.etagSeen)
    }

    @Test
    fun `records a body that arrived, with its size`() = runBlocking {
        val (_, fetcher) = reporting { HttpResult.Ok(ByteArray(53354), null) }

        fetcher.get(url)

        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Fresh, transfer.state)
        assertEquals(53354, transfer.bytes)
        assertEquals(42L, transfer.atMillis)
    }

    @Test
    fun `records an unchanged copy`() = runBlocking {
        val (_, fetcher) = reporting { HttpResult.NotModified }

        fetcher.get(url)

        assertEquals(TransferState.Unchanged, Transfers.snapshot().single().state)
    }

    /**
     * The failure is recorded and then rethrown. Swallowing it here would turn
     * every caller's own fallback into dead code.
     */
    @Test
    fun `records a failure and lets it through`() = runBlocking {
        val (_, fetcher) = reporting { throw IOException("GET $url returned 503") }

        val thrown = runCatching { fetcher.get(url) }.exceptionOrNull()

        assertTrue(thrown is IOException)
        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Failed, transfer.state)
        assertTrue(transfer.error!!, transfer.error!!.contains("503"))
    }

    /**
     * A call that is cancelled - which is what the poll's own 45-second
     * `withTimeout` looks like from in here - must not leave the row stuck on
     * "Downloading..." for the rest of the process.
     */
    @Test
    fun `settles the row when the call is cancelled rather than leaving it open`() = runBlocking {
        val neverAnswers = object : HttpFetcher {
            override suspend fun get(url: String, etag: String?): HttpResult {
                delay(10_000)
                return HttpResult.NotModified
            }
        }
        val fetcher = ReportingFetcher(neverAnswers) { 42L }

        runCatching { withTimeout(50) { fetcher.get(url) } }

        assertEquals(emptyList<String>(), Transfers.inFlight())
        assertEquals(TransferState.Failed, Transfers.snapshot().single().state)
    }

    /** While the call is open the record says so, which is what a bar is drawn from. */
    @Test
    fun `marks the object as in flight for the length of the call`() = runBlocking {
        var duringCall: List<String> = emptyList()
        val fetcher = ReportingFetcher(
            Fake {
                duringCall = Transfers.inFlight()
                HttpResult.NotModified
            },
        ) { 42L }

        fetcher.get(url)

        assertEquals(listOf("Satellite elements"), duringCall)
        assertEquals(emptyList<String>(), Transfers.inFlight())
    }
}
