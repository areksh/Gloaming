package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The noctilucent cloud prompt.
 *
 * This is geometry and a season, not a prediction: there has been no live
 * source since AIM ended in 2023, and the tests are written to pin what the app
 * claims rather than to imply it knows more than it does.
 */
class NoctilucentTest {

    private val edinburgh = 55.95 to -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun window(iso: String, latitude: Double = edinburgh.first, longitude: Double = edinburgh.second) =
        Noctilucent.window(latitude, longitude, at(iso))

    // --- the season ---

    /** High summer at Edinburgh is the middle of the season. */
    @Test
    fun `is in season at midsummer in the north`() {
        assertTrue(Noctilucent.isSeason(55.95, at("2027-06-21T22:00:00Z")))
    }

    /** By late September the mesosphere has warmed and the clouds are gone. */
    @Test
    fun `is out of season in the northern autumn`() {
        assertFalse(Noctilucent.isSeason(55.95, at("2027-09-21T22:00:00Z")))
        assertFalse(Noctilucent.isSeason(55.95, at("2027-01-21T22:00:00Z")))
    }

    /** The southern season is the other half of the year, not the same months. */
    @Test
    fun `shifts the season by half a year in the south`() {
        assertTrue(Noctilucent.isSeason(-55.0, at("2027-12-21T22:00:00Z")))
        assertFalse(Noctilucent.isSeason(-55.0, at("2027-06-21T22:00:00Z")))
    }

    /** Too far south and the clouds are never overhead, whatever the month. */
    @Test
    fun `is never in season in the tropics`() {
        assertFalse(Noctilucent.isSeason(20.0, at("2027-06-21T22:00:00Z")))
        assertFalse(Noctilucent.isSeason(20.0, at("2027-12-21T22:00:00Z")))
    }

    /** Nor at the pole, where the sun never gets low enough to light them from below. */
    @Test
    fun `is never in season at the pole`() {
        assertFalse(Noctilucent.isSeason(85.0, at("2027-06-21T22:00:00Z")))
    }

    // --- the window ---

    /** Midsummer at Edinburgh has a real window, in the evening, looking north. */
    @Test
    fun `finds an evening window at midsummer`() {
        val found = window("2027-06-21T18:00:00Z")

        assertNotNull(found)
        assertTrue(found!!.untilMillis > found.fromMillis)
        assertTrue(found.lookTowards == Compass.North)
    }

    /** In the southern hemisphere you look the other way. */
    @Test
    fun `looks south from the southern hemisphere`() {
        val found = Noctilucent.window(-55.0, -68.0, at("2027-12-21T18:00:00Z"))

        assertNotNull(found)
        assertTrue(found!!.lookTowards == Compass.South)
    }

    /** Out of season there is nothing to prompt, however dark it gets. */
    @Test
    fun `finds no window out of season`() {
        assertNull(window("2027-11-21T18:00:00Z"))
    }

    /** The window is deep twilight, not the whole night. */
    @Test
    fun `keeps the window inside deep twilight`() {
        val found = window("2027-06-21T18:00:00Z")!!
        val hours = (found.untilMillis - found.fromMillis) / 3_600_000.0

        assertTrue(hours.toString(), hours in 0.25..8.0)
    }

    // --- the prompt itself ---

    /** The prompt says what it is: a season and a geometry, not a forecast. */
    @Test
    fun `says plainly that it is not a prediction`() {
        val event = Noctilucent.event(edinburgh.first, edinburgh.second, at("2027-06-21T18:00:00Z"))

        assertNotNull(event)
        assertTrue(event!!.detail, event.detail.contains("no way to forecast"))
        assertTrue(event.kind == EventKind.Noctilucent)
        assertTrue(event.detail, event.detail.all { it.code in 32..126 })
        assertTrue(event.title, event.title.all { it.code in 32..126 })
    }

    /** Out of season there is no prompt at all. */
    @Test
    fun `offers no prompt out of season`() {
        assertNull(Noctilucent.event(edinburgh.first, edinburgh.second, at("2027-11-21T18:00:00Z")))
    }

    /** The prompt is always worth acting on, or it would not be made. */
    @Test
    fun `only prompts when it is worth going outside`() {
        val event = Noctilucent.event(edinburgh.first, edinburgh.second, at("2027-06-21T18:00:00Z"))!!

        assertTrue(event.visibility == Visibility.Good)
    }
}
