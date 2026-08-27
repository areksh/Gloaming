package sh.arek.gloaming.astronomy

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shower table, checked against dates anyone can look up.
 *
 * The peaks are stored as solar longitude rather than as dates, which is what
 * makes the table survive a change of year - so the interesting tests are the
 * ones that ask for the same shower in several different years, including a
 * leap year, and expect the same few days of August each time.
 */
class ShowersTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun peaks(from: String, days: Int) = Showers.peaks(
        latitude = latitude,
        longitude = longitude,
        fromMillis = at(from),
        untilMillis = at(from) + days * 86_400_000L,
    )

    private fun named(from: String, days: Int, name: String) =
        peaks(from, days).firstOrNull { it.shower.name.contains(name) }

    private fun dayOf(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    // --- the table itself ---

    /** Every row has to be usable, and a zero ZHR or an empty name is not. */
    @Test
    fun `every row is complete and plausible`() {
        assertTrue(Showers.TABLE.size >= 12)
        for (shower in Showers.TABLE) {
            assertTrue(shower.name, shower.name.isNotBlank())
            assertTrue(shower.name, shower.parent.isNotBlank())
            assertTrue(shower.name, shower.solarLongitude in 0.0..360.0)
            assertTrue(shower.name, shower.radiantRightAscension in 0.0..24.0)
            assertTrue(shower.name, shower.radiantDeclination in -90.0..90.0)
            assertTrue(shower.name, shower.zenithHourlyRate > 0)
        }
    }

    /** Two showers sharing a solar longitude would be a copy-paste slip. */
    @Test
    fun `no two showers claim the same peak`() {
        val longitudes = Showers.TABLE.map { it.solarLongitude }
        assertEquals(longitudes.size, longitudes.toSet().size)
    }

    /** British English, no decorative characters. */
    @Test
    fun `writes plain ascii`() {
        for (shower in Showers.TABLE) {
            assertTrue(shower.name, shower.name.all { it.code in 32..126 })
            assertTrue(shower.parent, shower.parent.all { it.code in 32..126 })
        }
    }

    // --- solar longitude resolves to the right dates ---

    /** The Perseids peak on 12 or 13 August. They have done for a very long time. */
    @Test
    fun `puts the perseids in the middle of august`() {
        val peak = named("2027-06-01T00:00:00Z", 120, "Perseids")

        assertNotNull(peak)
        val day = dayOf(peak!!.peaksAt)
        assertEquals(8, day.monthValue)
        assertTrue(day.toString(), day.dayOfMonth in 12..13)
    }

    /** The Geminids peak on 13 or 14 December, and are the best of the year. */
    @Test
    fun `puts the geminids in the middle of december`() {
        val peak = named("2026-11-01T00:00:00Z", 60, "Geminids")

        assertNotNull(peak)
        val day = dayOf(peak!!.peaksAt)
        assertEquals(12, day.monthValue)
        assertTrue(day.toString(), day.dayOfMonth in 13..14)
    }

    /** The Quadrantids open the year, on 3 or 4 January. */
    @Test
    fun `puts the quadrantids at the start of january`() {
        val peak = named("2026-12-01T00:00:00Z", 60, "Quadrantids")

        assertNotNull(peak)
        val day = dayOf(peak!!.peaksAt)
        assertEquals(1, day.monthValue)
        assertTrue(day.toString(), day.dayOfMonth in 3..4)
    }

    /**
     * The point of storing solar longitude: the same shower lands on the same
     * few days every year without the table being touched, leap years included.
     * A table of dates would need replacing annually and would drift a day at a
     * time until somebody noticed.
     */
    @Test
    fun `puts the perseids in the same days of august across four years`() {
        for (year in listOf(2027, 2028, 2029, 2030)) {
            val peak = named("$year-06-01T00:00:00Z", 120, "Perseids")
            assertNotNull("no Perseids in $year", peak)
            val day = dayOf(peak!!.peaksAt)
            assertEquals("$year", 8, day.monthValue)
            assertTrue("$year: $day", day.dayOfMonth in 11..13)
        }
    }

    /** 2028 is a leap year, and February must not shift August. */
    @Test
    fun `is unbothered by a leap year`() {
        val leap = dayOf(named("2028-06-01T00:00:00Z", 120, "Perseids")!!.peaksAt)
        val ordinary = dayOf(named("2029-06-01T00:00:00Z", 120, "Perseids")!!.peaksAt)

        assertTrue("$leap vs $ordinary", kotlin.math.abs(leap.dayOfYear - ordinary.dayOfYear) <= 2)
    }

    /** A year asked for gets a year's worth, not one shower or thirty. */
    @Test
    fun `finds every shower once in a year`() {
        val year = peaks("2027-01-01T00:00:00Z", 365)

        assertEquals(Showers.TABLE.size, year.map { it.shower.name }.toSet().size)
        assertEquals(year.map { it.peaksAt }.sorted(), year.map { it.peaksAt })
    }

    // --- the radiant, where the observer stands ---

    /**
     * The Quadrantid radiant sits at declination +50, which never sets from
     * Edinburgh. Whatever else is wrong with a January night, the radiant being
     * up is not.
     */
    @Test
    fun `finds the quadrantid radiant high from edinburgh`() {
        val peak = named("2026-12-01T00:00:00Z", 60, "Quadrantids")!!

        assertTrue(peak.radiantAltitude.toString(), peak.radiantAltitude > 40.0)
    }

    /**
     * The southern delta Aquariids radiate from declination -16, which from
     * Edinburgh cannot get more than about 18 degrees up however long you wait.
     * Most of the shower happens below the horizon and the app has to say so.
     */
    @Test
    fun `finds the southern delta aquariid radiant low from edinburgh`() {
        val peak = named("2027-06-01T00:00:00Z", 90, "delta Aquariids")!!

        assertTrue(peak.radiantAltitude.toString(), peak.radiantAltitude in 0.0..20.0)
    }

    /** Far enough north and that radiant never comes up at all. */
    @Test
    fun `finds no southern radiant at all from svalbard`() {
        val peak = Showers.peaks(
            latitude = 78.2,
            longitude = 15.6,
            fromMillis = at("2027-06-01T00:00:00Z"),
            untilMillis = at("2027-06-01T00:00:00Z") + 90 * 86_400_000L,
        ).firstOrNull { it.shower.name.contains("delta Aquariids") }

        assertNotNull(peak)
        assertTrue(peak!!.radiantAltitude.toString(), peak.radiantAltitude <= 0.0)
    }

    // --- the moon, which decides whether it is worth going out ---

    /**
     * The moon is the thing most shower forecasts leave out, and it is what
     * decides the night: a ZHR of 150 under a full moon is a ZHR of 20 to the
     * eye. The Geminids of 2026 fall four days after new moon, so it is one of
     * the good years.
     */
    @Test
    fun `finds the geminids of 2026 unspoilt by moonlight`() {
        val peak = named("2026-11-01T00:00:00Z", 60, "Geminids")!!

        assertTrue(peak.moonIllumination.toString(), peak.moonIllumination < 0.4)
    }

    // --- what an hour outside is actually worth ---

    /**
     * A ZHR is a laboratory figure assuming the radiant overhead. The Geminids
     * from Edinburgh get to about 65 degrees, which is good but not overhead,
     * so the honest figure is below the headline 150 and well above nothing.
     */
    @Test
    fun `discounts the headline rate by how high the radiant gets`() {
        val peak = named("2026-11-01T00:00:00Z", 60, "Geminids")!!

        assertTrue(peak.hourlyRateFromHere.toString(), peak.hourlyRateFromHere < 150)
        assertTrue(peak.hourlyRateFromHere.toString(), peak.hourlyRateFromHere > 60)
    }

    /**
     * The southern delta Aquariids barely clear 18 degrees from Edinburgh, so
     * their ZHR of 25 is worth a small handful an hour and the app must not
     * imply otherwise.
     */
    @Test
    fun `discounts a low radiant heavily`() {
        val peak = named("2027-06-01T00:00:00Z", 90, "delta Aquariids")!!

        assertTrue(peak.hourlyRateFromHere.toString(), peak.hourlyRateFromHere <= 10)
    }

    /** A radiant that never rises is worth nothing an hour, not a fraction of one. */
    @Test
    fun `offers nothing an hour when the radiant never rises`() {
        val peak = Showers.peaks(
            latitude = 78.2,
            longitude = 15.6,
            fromMillis = at("2027-06-01T00:00:00Z"),
            untilMillis = at("2027-06-01T00:00:00Z") + 90 * 86_400_000L,
        ).first { it.shower.name.contains("delta Aquariids") }

        assertEquals(0, peak.hourlyRateFromHere)
    }

    // --- the night itself ---

    /**
     * The night of a shower is the one night the app most has to get right, and
     * it was the one night it got wrong.
     *
     * The search for the moment the sun reaches a given longitude was handed a
     * window of 366 days. The function it searches is a sawtooth with a year's
     * period, and the root finder inside it fits a parabola through three
     * samples - so over a whole period the fit is meaningless. Asked on
     * 13 December for a peak on the 14th it returned nothing at all, and asked
     * on the 14th it returned the following December. Either way the shower
     * vanished from the calendar for the two days either side of its peak,
     * which is exactly when somebody opens the app to ask about it.
     */
    @Test
    fun `finds a shower peaking within a day`() {
        val peak = named("2026-12-13T00:00:00Z", 5, "Geminids")

        assertNotNull("the Geminids peak on 14 December 2026", peak)
        assertEquals("2026-12-14", dayOf(peak!!.peaksAt).toString())
    }

    /** And on the day itself, hours before it happens. */
    @Test
    fun `finds a shower peaking later the same night`() {
        val peak = named("2026-12-14T00:30:00Z", 5, "Geminids")

        assertNotNull("the Geminids peak at 04:52 UTC on 14 December 2026", peak)
        assertEquals("2026-12-14", dayOf(peak!!.peaksAt).toString())
    }

    /**
     * It also has to keep working across a year boundary rather than merely at
     * short range: the calendar asks for a year at a time, and a shower found
     * only in the first months of it would be a different bug of the same shape.
     */
    @Test
    fun `finds every yearly peak across a long horizon`() {
        val geminids = peaks("2026-06-01T00:00:00Z", 3 * 365)
            .filter { it.shower.name.contains("Geminids") }
            .map { dayOf(it.peaksAt).year }

        assertEquals(listOf(2026, 2027, 2028), geminids)
    }

    /** Illumination and the share of the night it is up are both fractions. */
    @Test
    fun `keeps the moon figures inside their range`() {
        for (peak in peaks("2027-01-01T00:00:00Z", 365)) {
            assertTrue(peak.shower.name, peak.moonIllumination in 0.0..1.0)
            assertTrue(peak.shower.name, peak.moonUpFraction in 0.0..1.0)
        }
    }
}
