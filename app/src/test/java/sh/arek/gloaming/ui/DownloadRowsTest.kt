package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.net.Transfer
import sh.arek.gloaming.net.TransferState

/**
 * The rows of the downloads card.
 *
 * Two sources answering two different questions: what the device is working
 * from, which survives a restart and comes from the caches, and what happened
 * the last time anything tried to fetch, which is this session only. A row can
 * have either, both, or - on a first run before any poll - only the first.
 */
class DownloadRowsTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 100 * day

    private fun rows(
        transfers: List<Transfer> = emptyList(),
        elements: Long? = null,
        comets: Long? = null,
        held: Map<String, Long?> = mapOf(
            "Satellite elements" to elements,
            "Comets" to comets,
        ),
    ) = downloadRows(transfers, held, now)

    private fun row(
        label: String,
        transfers: List<Transfer> = emptyList(),
        elements: Long? = null,
        comets: Long? = null,
    ) = rows(transfers, elements, comets).single { it.label == label }

    private fun transfer(
        label: String,
        state: TransferState,
        atMillis: Long = now,
        bytes: Int? = null,
        error: String? = null,
    ) = Transfer(label, state, atMillis, bytes, error)

    /**
     * The two documents the device holds always have a row, whether or not
     * anything has been fetched this session. That is the whole point: an app
     * opened cold after a fortnight of failures must still say the elements are
     * a fortnight old.
     */
    @Test
    fun `speaks for the held documents before anything has been fetched`() {
        val listed = rows(elements = 14 * day, comets = 2 * hour)

        assertEquals(listOf("Satellite elements", "Comets"), listed.map { it.label })
        assertEquals("Held copy is 14 days old", listed[0].lines.single())
        assertEquals("Held copy is 2 hours old", listed[1].lines.single())
    }

    /**
     * The two held documents have a row even with nothing held at all: on a
     * first run "never downloaded" is exactly what somebody needs to be told.
     */
    @Test
    fun `speaks for the held documents even before a first download`() {
        assertEquals("Never downloaded", row("Satellite elements").lines.single())
        assertFalse(row("Satellite elements").alarming)
    }

    /** Old elements are the failure that hid for a fortnight, so they are marked. */
    @Test
    fun `marks stale elements`() {
        assertTrue(row("Satellite elements", elements = 14 * day).alarming)
        assertFalse(row("Satellite elements", elements = 2 * hour).alarming)
    }

    /**
     * Comets are not held to the elements' three days. A comet's orbit is good
     * for months, so a document a week old is ordinary rather than alarming -
     * marking it would teach the reader to ignore the marking.
     */
    @Test
    fun `does not mark a comet document that is merely a few days old`() {
        assertFalse(row("Comets", comets = 5 * day).alarming)
        assertTrue(row("Comets", comets = 40 * day).alarming)
    }

    /** Both halves of the story on one row, the held copy first. */
    @Test
    fun `puts the last attempt underneath the held copy`() {
        val listed = rows(
            transfers = listOf(transfer("Satellite elements", TransferState.Unchanged)),
            elements = 14 * day,
        )

        assertEquals(
            listOf("Held copy is 14 days old", "Already up to date, checked just now"),
            listed.single { it.label == "Satellite elements" }.lines,
        )
    }

    /** A failure is worth marking however fresh the copy underneath it is. */
    @Test
    fun `marks a row whose last attempt failed`() {
        val listed = rows(
            transfers = listOf(
                transfer("Space weather", TransferState.Failed, error = "returned 503"),
            ),
        )

        assertTrue(listed.single { it.label == "Space weather" }.alarming)
    }

    /** Objects with no age on this device still appear once one is tried. */
    @Test
    fun `adds a row for anything else that has been fetched`() {
        val listed = rows(
            transfers = listOf(transfer("Aurora grid", TransferState.Fresh, bytes = 3543)),
        )

        val grid = listed.single { it.label == "Aurora grid" }
        assertEquals("Downloaded 3 KB just now", grid.lines.single())
    }

    /**
     * Every document the device holds gets its age, not just the two with a
     * threshold. The aurora rows used to carry only what the last fetch did,
     * which read as an oversight beside the two that said both.
     */
    @Test
    fun `gives every held document its age`() {
        val listed = downloadRows(
            transfers = listOf(transfer("Space weather", TransferState.Unchanged)),
            held = mapOf("Space weather" to 20 * minute),
            now = now,
        )

        assertEquals(
            listOf("Held copy is current", "Already up to date, checked just now"),
            listed.single().lines,
        )
    }

    /** But an age alone is never a mark, outside the two that earn one. */
    @Test
    fun `never marks a document that has no threshold`() {
        val listed = downloadRows(
            transfers = emptyList(),
            held = mapOf("Space weather" to 400 * day),
            now = now,
        )

        assertFalse(listed.single().alarming)
    }

    /**
     * A settled order, so the card does not reshuffle as each request lands.
     * The held documents keep their place in it rather than being bolted on.
     */
    @Test
    fun `keeps one settled order across both sources`() {
        val listed = rows(
            transfers = listOf(
                transfer("Cloud forecast", TransferState.Unchanged),
                transfer("Aurora grid", TransferState.Fresh, bytes = 1),
                transfer("Endpoints", TransferState.Unchanged),
            ),
            elements = day,
            comets = day,
        )

        assertEquals(
            listOf("Endpoints", "Aurora grid", "Satellite elements", "Comets", "Cloud forecast"),
            listed.map { it.label },
        )
    }

    // --- whether the report is worth showing at all ---

    /** Quiet on an ordinary evening before anybody has asked for anything. */
    @Test
    fun `stays quiet when nothing has been fetched and nothing is wrong`() {
        assertFalse(worthReporting(rows(elements = hour, comets = hour), checking = false))
    }

    /** It speaks while a check is running, which is what the bar sits beside. */
    @Test
    fun `speaks while a check is running`() {
        assertTrue(worthReporting(emptyList(), checking = true))
    }

    /** And afterwards, because that is when somebody asked to be told. */
    @Test
    fun `speaks once something has actually been fetched`() {
        val listed = rows(
            transfers = listOf(transfer("Satellite elements", TransferState.Unchanged)),
            elements = hour,
        )

        assertTrue(worthReporting(listed, checking = false))
    }

    /**
     * And unasked when something is wrong, which is the case it exists for: a
     * fortnight of stale elements that only speaks if you happen to press a
     * button is a fortnight of silence.
     */
    @Test
    fun `speaks unasked when something is wrong`() {
        assertTrue(worthReporting(rows(elements = 14 * day), checking = false))
    }
}
