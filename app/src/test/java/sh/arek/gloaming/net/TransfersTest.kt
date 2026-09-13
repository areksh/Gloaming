package sh.arek.gloaming.net

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the app is downloading, and what went wrong last time it tried.
 *
 * The gap this closes is a real one: a fortnight of failed satellite fetches
 * looked, from the screen, exactly like a fortnight of quiet sky. Every fetch
 * failure was swallowed into a log line nobody reads.
 */
class TransfersTest {

    private val sats = "https://sky.arek.sh/v1/sats.json"
    private val oval = "https://sky.arek.sh/v1/oval.bin"
    private val weather = "https://api.open-meteo.com/v1/forecast?latitude=53&longitude=-1"

    @Before
    fun forget() = Transfers.forget()

    @Test
    fun `holds nothing before anything has been fetched`() {
        assertEquals(emptyList<Transfer>(), Transfers.snapshot())
    }

    @Test
    fun `names a published object rather than quoting its URL at the user`() {
        Transfers.started(sats, atMillis = 1L)

        assertEquals("Satellite elements", Transfers.snapshot().single().label)
    }

    /** Open-Meteo's URL carries a query, so the name cannot come from the path alone. */
    @Test
    fun `names the forecast despite its query string`() {
        Transfers.started(weather, atMillis = 1L)

        assertEquals("Cloud forecast", Transfers.snapshot().single().label)
    }

    /** Something unrecognised is still worth showing, under whatever it is called. */
    @Test
    fun `falls back to the file name for an endpoint it does not know`() {
        Transfers.started("https://example.test/v1/mystery.json", atMillis = 1L)

        assertEquals("mystery.json", Transfers.snapshot().single().label)
    }

    @Test
    fun `reports a transfer in flight`() {
        Transfers.started(sats, atMillis = 5L)

        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Fetching, transfer.state)
        assertEquals(5L, transfer.atMillis)
        assertNull(transfer.error)
    }

    @Test
    fun `reports a body that arrived, with its size`() {
        Transfers.started(sats, atMillis = 5L)
        Transfers.fresh(sats, bytes = 53354, atMillis = 9L)

        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Fresh, transfer.state)
        assertEquals(53354, transfer.bytes)
        assertEquals(9L, transfer.atMillis)
    }

    /** A 304 is the common case and is not a failure; it is also not new data. */
    @Test
    fun `distinguishes an unchanged copy from a fresh one`() {
        Transfers.started(sats, atMillis = 5L)
        Transfers.unchanged(sats, atMillis = 9L)

        assertEquals(TransferState.Unchanged, Transfers.snapshot().single().state)
    }

    /** The reason, read out of the whole cause chain, is the point of recording it. */
    @Test
    fun `keeps why a transfer failed`() {
        Transfers.started(sats, atMillis = 5L)
        Transfers.failed(sats, IOException("GET $sats returned 503"), atMillis = 9L)

        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Failed, transfer.state)
        assertTrue(transfer.error!!, transfer.error!!.contains("503"))
    }

    /** One row per object: the latest attempt replaces the one before it. */
    @Test
    fun `keeps only the latest attempt at each object`() {
        Transfers.failed(sats, IOException("first"), atMillis = 1L)
        Transfers.started(sats, atMillis = 2L)
        Transfers.fresh(sats, bytes = 10, atMillis = 3L)

        val transfer = Transfers.snapshot().single()
        assertEquals(TransferState.Fresh, transfer.state)
        assertNull(transfer.error)
    }

    /**
     * A fixed order, so the card does not reshuffle itself as each request
     * lands. Published in the order a poll actually walks them.
     */
    @Test
    fun `lists the objects in a settled order`() {
        Transfers.started(weather, atMillis = 1L)
        Transfers.started(sats, atMillis = 2L)
        Transfers.started(oval, atMillis = 3L)

        assertEquals(
            listOf("Aurora grid", "Satellite elements", "Cloud forecast"),
            Transfers.snapshot().map { it.label },
        )
    }

    /** The screen reads this on a tick, so it needs to know when to bother. */
    @Test
    fun `moves its version on every change`() {
        val before = Transfers.version()
        Transfers.started(sats, atMillis = 1L)
        val during = Transfers.version()
        Transfers.fresh(sats, bytes = 1, atMillis = 2L)

        assertTrue(during > before)
        assertTrue(Transfers.version() > during)
    }

    /** What is in flight right now, for the sentence beside the progress bar. */
    @Test
    fun `names what is in flight`() {
        Transfers.started(sats, atMillis = 1L)
        Transfers.started(oval, atMillis = 2L)

        assertEquals(listOf("Aurora grid", "Satellite elements"), Transfers.inFlight())

        Transfers.fresh(oval, bytes = 1, atMillis = 3L)
        assertEquals(listOf("Satellite elements"), Transfers.inFlight())
    }
}
