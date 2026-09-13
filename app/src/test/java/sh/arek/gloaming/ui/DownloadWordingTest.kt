package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.net.Transfer
import sh.arek.gloaming.net.TransferState

/**
 * The sentences the downloads card is made of.
 *
 * Written because a fortnight of failed satellite fetches was, from the screen,
 * indistinguishable from a fortnight of quiet sky: the elements went stale, the
 * conditional GETs kept answering 304, and nothing anywhere said so.
 */
class DownloadWordingTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 100 * day

    private fun transfer(
        state: TransferState,
        atMillis: Long = now,
        bytes: Int? = null,
        error: String? = null,
    ) = Transfer("Satellite elements", state, atMillis, bytes, error)

    // --- what one transfer is doing ---

    @Test
    fun `says a request is open`() {
        assertEquals(
            "Downloading...",
            describeTransfer(transfer(TransferState.Fetching), now),
        )
    }

    /** The size is the evidence that something actually arrived. */
    @Test
    fun `says what arrived and how big it was`() {
        assertEquals(
            "Downloaded 52 KB just now",
            describeTransfer(transfer(TransferState.Fresh, bytes = 53354), now),
        )
    }

    @Test
    fun `counts small bodies in bytes rather than rounding them to nothing`() {
        assertEquals(
            "Downloaded 486 bytes just now",
            describeTransfer(transfer(TransferState.Fresh, bytes = 486), now),
        )
    }

    /**
     * A 304 is the common case by a long way and has to read as success, or an
     * app that is working perfectly looks like one that has stopped.
     */
    @Test
    fun `says an unchanged copy was already current`() {
        assertEquals(
            "Already up to date, checked 5 minutes ago",
            describeTransfer(transfer(TransferState.Unchanged, atMillis = now - 5 * minute), now),
        )
    }

    @Test
    fun `reads out why a transfer failed`() {
        val failed = transfer(
            TransferState.Failed,
            atMillis = now - 2 * hour,
            error = "GET https://sky.arek.sh/v1/sats.json returned 503",
        )

        assertEquals(
            "Failed 2 hours ago: GET https://sky.arek.sh/v1/sats.json returned 503",
            describeTransfer(failed, now),
        )
    }

    /** A failure with nothing to say about itself still has to say it failed. */
    @Test
    fun `copes with a failure that carries no reason`() {
        assertEquals("Failed just now", describeTransfer(transfer(TransferState.Failed), now))
    }

    // --- how old the copy on the device is ---

    @Test
    fun `says when nothing has ever been downloaded`() {
        assertEquals("Never downloaded", describeHeldCopy(null))
    }

    @Test
    fun `counts the age of the held copy`() {
        assertEquals("Held copy is 3 hours old", describeHeldCopy(3 * hour))
        assertEquals("Held copy is 14 days old", describeHeldCopy(14 * day))
        assertEquals("Held copy is 1 day old", describeHeldCopy(day))
    }

    @Test
    fun `calls a copy from the last hour current`() {
        assertEquals("Held copy is current", describeHeldCopy(5 * minute))
    }

    // --- when that age is worth complaining about ---

    /**
     * The threshold that matters. SGP4 is a near-epoch model: elements a
     * fortnight old put a pass minutes and degrees away from where it is, and
     * the app was doing exactly that in silence.
     */
    @Test
    fun `calls elements older than three days stale`() {
        assertFalse(isStale(2 * day))
        assertTrue(isStale(4 * day))
        assertTrue(isStale(14 * day))
    }

    /** Nothing held at all is not "stale"; it is a different sentence. */
    @Test
    fun `does not call a missing copy stale`() {
        assertFalse(isStale(null))
    }
}
