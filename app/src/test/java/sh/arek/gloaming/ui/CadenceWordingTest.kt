package sh.arek.gloaming.ui

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.data.LocationCadence

/**
 * How often the position is refreshed, and when it last was, as the location
 * card says them.
 */
class CadenceWordingTest {

    private val hour = 60 * 60 * 1000L

    /** 2027-01-15 08:00 UTC. */
    private val now = 1_800_000_000_000L

    /** Midnight UTC on the same day, so offsets from it read as the time they print. */
    private val midnight = now / (24 * hour) * (24 * hour)

    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    /** Times are printed in the device's own zone and language, so the test fixes both. */
    @Before
    fun fixTheZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreTheZone() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    // --- cadence ---

    @Test
    fun `says opening the app as background updates being off`() {
        assertEquals("Off - when I open Gloaming", describeCadence(LocationCadence.OnOpen))
    }

    @Test
    fun `says one hour in the singular`() {
        assertEquals("Every hour", describeCadence(LocationCadence.Every(1)))
    }

    @Test
    fun `says any other number of hours as a number`() {
        assertEquals("Every 6 hours", describeCadence(LocationCadence.Every(6)))
        assertEquals("Every 3 hours", describeCadence(LocationCadence.Every(3)))
        assertEquals("Every 48 hours", describeCadence(LocationCadence.Every(48)))
    }

    @Test
    fun `says twenty-four hours as once a day`() {
        assertEquals("Once a day", describeCadence(LocationCadence.Every(24)))
    }

    // --- last updated ---

    @Test
    fun `says so when the position has never been updated`() {
        assertEquals("Not updated yet", describeLocationUpdated(null, now))
    }

    @Test
    fun `gives only the time for an update earlier today`() {
        assertEquals(
            "Last updated at 06:30",
            describeLocationUpdated(midnight + 6 * hour + 30 * 60_000L, now),
        )
        assertEquals("Last updated at 00:00", describeLocationUpdated(midnight, now))
    }

    @Test
    fun `gives the date as well for an update before today`() {
        assertEquals(
            "Last updated 14 Jan at 23:59",
            describeLocationUpdated(midnight - 60_000L, now),
        )
        assertEquals(
            "Last updated 3 Jan at 09:15",
            describeLocationUpdated(midnight - 12 * 24 * hour + 9 * hour + 15 * 60_000L, now),
        )
    }

    /** Today is the device's today, not UTC's: the same instant reads differently in Auckland. */
    @Test
    fun `judges today in the device's own zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))

        // 2027-01-15 08:00 UTC is 21:00 there; 10:00 UTC on the 14th is 23:00 on the 14th.
        assertEquals(
            "Last updated 14 Jan at 23:00",
            describeLocationUpdated(midnight - 14 * hour, now),
        )
        // 12:00 UTC on the 14th is 01:00 on the 15th there - today.
        assertEquals("Last updated at 01:00", describeLocationUpdated(midnight - 12 * hour, now))
    }
}
