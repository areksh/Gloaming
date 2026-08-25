package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The night an instant belongs to.
 *
 * Every dated event in the calendar is judged on the darkness nearest it rather
 * than at the instant itself, so getting "nearest" right is what stops a
 * greatest elongation at ten in the morning being called invisible.
 */
class TwilightNightTest {

    private val edinburgh = 55.95 to -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun nightAt(iso: String, latitude: Double, longitude: Double) =
        Twilight.nightNearest(latitude, longitude, at(iso))

    /** A midday instant belongs to the evening that follows it, not the one behind. */
    @Test
    fun `puts a midday event on the night that follows it`() {
        val night = nightAt("2026-10-12T12:00:00Z", edinburgh.first, edinburgh.second)

        assertNotNull(night)
        assertTrue(
            Instant.ofEpochMilli(night!!.first).toString(),
            night.first > at("2026-10-12T12:00:00Z"),
        )
        assertTrue(night.first < at("2026-10-13T00:00:00Z"))
    }

    /** An instant in the small hours belongs to the night it is already inside. */
    @Test
    fun `keeps an event in the small hours on the night around it`() {
        val moment = at("2026-10-12T02:00:00Z")
        val night = nightAt("2026-10-12T02:00:00Z", edinburgh.first, edinburgh.second)

        assertNotNull(night)
        assertTrue(moment in night!!)
    }

    /** The window is a real stretch of darkness, not a point. */
    @Test
    fun `returns a window that lasts hours`() {
        val night = nightAt("2026-12-21T12:00:00Z", edinburgh.first, edinburgh.second)!!
        val hours = (night.last - night.first) / 3_600_000.0

        assertTrue(hours.toString(), hours > 8.0)
    }

    /** Inside the arctic circle at midsummer there is no night to belong to. */
    @Test
    fun `finds no night at midsummer inside the arctic circle`() {
        assertNull(nightAt("2027-06-21T12:00:00Z", 78.2, 15.6))
    }

    /**
     * The polar night has no dawn within the horizon searched, which is true and
     * useless to a loop: the window is bounded so a caller sampling it
     * terminates.
     */
    @Test
    fun `bounds the polar night rather than returning an open window`() {
        val night = nightAt("2027-12-21T12:00:00Z", 78.2, 15.6)

        assertNotNull(night)
        assertTrue(night!!.last > night.first)
        assertTrue((night.last - night.first) <= 25L * 3_600_000)
    }
}
