package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the patterns are, from where somebody is standing.
 *
 * The expected altitudes here are not taken from the ephemeris and pinned
 * afterwards. A constellation sits at a fixed declination, so the highest its
 * centre ever gets is `90 - latitude + dec` and the lowest is
 * `latitude + dec - 90`, and both are arithmetic anybody can check. That makes
 * this suite an independent check on the wiring rather than a record of what
 * the code happened to print - if the right ascension and declination were
 * swapped, or the hours read as degrees, every number below would move.
 *
 * Edinburgh throughout except where the point is a latitude that cannot see
 * something, matching the rest of the astronomy suite.
 */
class ConstellationsTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun night(iso: String, lat: Double = latitude, lon: Double = longitude) =
        Twilight.nightNearest(lat, lon, at(iso))

    private fun tonight(iso: String, lat: Double = latitude, lon: Double = longitude) =
        Constellations.tonight(lat, lon, night(iso, lat, lon)!!)

    private fun row(name: String) = Constellations.WATCHED.first { it.name == name }

    private fun day(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    private fun events(from: String, days: Int) =
        SkyCalendar.events(latitude, longitude, at(from), days)
            .filter { it.kind == EventKind.Constellation }

    private fun bestNight(name: String, from: String, days: Int) = Constellations
        .bestNights(at(from), at(from) + days * 86_400_000L)
        .first { it.constellation.name == name }
        .atMillis

    // --- how high, and which way ---

    /**
     * Orion's centre sits at declination +5, so from Edinburgh it transits at
     * 90 - 55.95 + 5 = 39 degrees. Mid-January puts that transit near ten in
     * the evening, well inside the darkness, so the sweep should find the
     * transit itself rather than one end of the window.
     */
    @Test
    fun `puts orion at its transit height on a january night`() {
        val orion = tonight("2027-01-15T22:00:00Z").first { it.constellation.name == "Orion" }

        assertEquals(orion.peakAltitude.toString(), 39.0, orion.peakAltitude, 1.0)
        assertEquals(Compass.South, Compass.of(orion.peakAzimuth))
    }

    /**
     * The Plough at declination +50.7 reaches 90 - 55.95 + 50.7 = 84.75, which
     * is within six degrees of straight overhead.
     */
    @Test
    fun `puts the plough almost overhead in spring`() {
        val plough = tonight("2027-03-20T23:00:00Z").first { it.constellation.name == "Ursa Major" }

        assertEquals(plough.peakAltitude.toString(), 84.75, plough.peakAltitude, 1.0)
    }

    /** Highest first, because that is what decides whether a pattern can be found. */
    @Test
    fun `sorts by how high each one gets`() {
        val seen = tonight("2027-01-15T22:00:00Z")

        assertEquals(seen.map { it.peakAltitude }.sortedDescending(), seen.map { it.peakAltitude })
    }

    // --- what cannot be seen from here ---

    /**
     * The Southern Cross sits at declination -60. From Edinburgh its centre
     * would transit at 90 - 55.95 - 60.2 = -26 degrees, which is a quarter of
     * the way to straight down.
     */
    @Test
    fun `never raises the southern cross over scotland`() {
        val seen = tonight("2027-05-15T23:00:00Z")

        assertNull(seen.firstOrNull { it.constellation.name == "Crux" })
    }

    /**
     * And does raise it where it belongs. Ushuaia is at 54.8 south and Crux at
     * declination -60.2, so the two are only 5.4 degrees apart and it passes
     * almost overhead: 90 - 5.4 = 84.6.
     */
    @Test
    fun `raises the southern cross over ushuaia`() {
        val crux = tonight("2027-05-15T03:00:00Z", lat = -54.80, lon = -68.30)
            .firstOrNull { it.constellation.name == "Crux" }

        assertNotNull("expected the Southern Cross from Tierra del Fuego", crux)
        assertEquals(crux!!.peakAltitude.toString(), 84.6, crux.peakAltitude, 1.0)
    }

    /**
     * A pattern whose centre is up but which is mostly still below the hills
     * is not one to send somebody out for, so the bar carries each row's own
     * reach on top of the common ten degrees.
     */
    @Test
    fun `holds a sprawling pattern to a higher bar than a compact one`() {
        assertTrue(row("Virgo").extentDegrees > row("Lyra").extentDegrees)
    }

    // --- the ones that never set ---

    /** Declination +50.7 from 55.95 north never comes closer to the horizon than 16 degrees. */
    @Test
    fun `knows the plough never sets from scotland`() {
        val plough = tonight("2027-01-15T22:00:00Z").first { it.constellation.name == "Ursa Major" }

        assertTrue(plough.isCircumpolar)
    }

    /** Orion does set, and says so, or the card would claim a winter pattern all year. */
    @Test
    fun `knows orion sets from scotland`() {
        val orion = tonight("2027-01-15T22:00:00Z").first { it.constellation.name == "Orion" }

        assertTrue(!orion.isCircumpolar)
    }

    /** Circumpolar is about the observer, not the pattern: the Plough sets in Cairo. */
    @Test
    fun `knows the plough sets from the tropics`() {
        val plough = tonight("2027-01-15T22:00:00Z", lat = 25.69, lon = 32.64)
            .firstOrNull { it.constellation.name == "Ursa Major" }

        assertNotNull(plough)
        assertTrue(!plough!!.isCircumpolar)
    }

    // --- no night at all ---

    /**
     * Svalbard in June has no darkness, which is an answer rather than a
     * failure. Sec. 5 requires the polar cases be handled rather than treated
     * as edge cases.
     */
    @Test
    fun `says nothing when there is no darkness to speak of`() {
        assertNull(night("2027-06-21T00:00:00Z", lat = 78.2, lon = 15.6))
    }

    /** And an empty window is nothing to sweep, rather than something to guess at. */
    @Test
    fun `says nothing for an empty window`() {
        assertEquals(emptyList<ConstellationTonight>(), Constellations.tonight(78.2, 15.6, 1L..0L))
    }

    // --- the one night of the year ---

    /**
     * A pattern is best placed when it stands on the meridian at midnight,
     * which is when the sun sits opposite it. Orion's centre is at right
     * ascension 5.58h, so its opposite point is 17.58h. The sun's right
     * ascension is exactly 18h at the December solstice and is then moving at
     * its fastest, 24 / 365.25 / cos(23.44 deg) = 0.072h a day, so 17.58h
     * falls 0.42 / 0.072 = 5.9 days before 21 December.
     */
    @Test
    fun `dates orion's best night to the middle of december`() {
        val best = bestNight("Orion", "2027-01-01T00:00:00Z", 400)

        assertEquals("2027-12-16", day(best).toString())
    }

    /**
     * Cygnus at 20.62h has its opposite point at 8.62h. The sun is at 6h at
     * the June solstice, so that is 2.62h later; the rate eases from 0.072h a
     * day towards the mean of 0.066h as it goes, which puts it 37 to 40 days
     * on - the end of July.
     */
    @Test
    fun `dates cygnus's best night to the turn of august`() {
        val best = bestNight("Cygnus", "2027-01-01T00:00:00Z", 400)

        assertEquals("2027-07-30", day(best).toString())
    }

    /**
     * The invariant behind both dates, checked directly rather than through a
     * calendar: on its best night the pattern really is highest in the middle
     * of the night, and really is close to due south from the north.
     */
    @Test
    fun `stands highest near the middle of the night on its best night`() {
        for (row in Constellations.WATCHED) {
            val best = Constellations
                .bestNights(at("2027-01-01T00:00:00Z"), at("2028-01-01T00:00:00Z"))
                .first { it.constellation.name == row.name }

            val sighting = Constellations.bestPlacement(row, latitude, longitude, best.atMillis)
                ?: continue
            val night = Twilight.nightNearest(latitude, longitude, best.atMillis)!!
            val middle = (night.first + night.last) / 2
            val hoursOff = kotlin.math.abs(sighting.atMillis - middle) / 3_600_000.0

            // Three hours, which is the slack a summer night with barely any
            // darkness leaves: the window is short and its middle is dragged
            // off true midnight by the season rather than by the pattern.
            assertTrue("${row.name} peaks ${hoursOff}h from the middle", hoursOff < 3.0)
        }
    }

    /** One a year each, or the calendar would carry the same pattern twice. */
    @Test
    fun `dates each constellation once a year`() {
        val best = Constellations
            .bestNights(at("2027-01-01T00:00:00Z"), at("2028-01-01T00:00:00Z"))

        assertEquals(Constellations.WATCHED.size, best.size)
        assertEquals(best.map { it.constellation.name }.toSet().size, best.size)
    }

    // --- as calendar entries ---

    /**
     * The calendar carries the occasion, and says which star to look for.
     *
     * Dated a day after the crossing the placement is solved from, because the
     * entry is timed to the moment it is actually highest and that falls just
     * after local midnight. A meteor shower is dated the same way and for the
     * same reason: the useful answer is the night, and the night has a date on
     * both sides of twelve.
     */
    @Test
    fun `puts orion's best night in the calendar`() {
        val orion = events("2027-01-01T00:00:00Z", 400)
            .first { it.title.contains("Orion") }

        assertEquals("2027-12-17", day(orion.at).toString())
        assertTrue(orion.title, orion.title.contains("at its best"))
        assertTrue(orion.detail, orion.detail.contains("Rigel"))
        assertTrue(orion.detail, orion.detail.contains("belt"))
    }

    /**
     * The window is one night, not one season. That is what keeps a
     * constellation inside every rule the calendar already has: the
     * happening-now marker, the announce-once memory and the time range on the
     * row all assume a window shorter than an opposition's three weeks.
     */
    @Test
    fun `dates the entry to a single night`() {
        for (event in events("2027-01-01T00:00:00Z", 400)) {
            val hours = (event.until - event.from) / 3_600_000.0

            assertTrue("${event.title} runs ${hours}h", hours <= 24.0)
            assertTrue(event.title, event.at in event.from..event.until)
        }
    }

    /** A pattern that never rises here still gets a row, saying so. */
    @Test
    fun `says the southern cross cannot be seen from scotland`() {
        val crux = events("2027-01-01T00:00:00Z", 400).first { it.title.contains("Crux") }

        assertTrue(crux.visibility.toString(), crux.visibility is Visibility.NotFromHere)
    }

    /** It is a marker, not an interruption - the same treatment a season gets. */
    @Test
    fun `never interrupts about a constellation`() {
        assertEquals(null, EventChannel.of(EventKind.Constellation))
    }

    // --- the lookup ---

    /** Betelgeuse, at J2000 right ascension 5h 55m and declination +7 24'. */
    @Test
    fun `names orion as the constellation betelgeuse falls in`() {
        assertEquals("Orion", Constellations.containing(5.9195, 7.4071))
    }

    /** Sirius, at 6h 45m and -16 43'. */
    @Test
    fun `names canis major as the constellation sirius falls in`() {
        assertEquals("Canis Major", Constellations.containing(6.7525, -16.7161))
    }

    /** Polaris, at 2h 32m and +89 16'. */
    @Test
    fun `names ursa minor as the constellation polaris falls in`() {
        assertEquals("Ursa Minor", Constellations.containing(2.5303, 89.2641))
    }

    // --- the table itself ---

    /** Every row is a real pattern with a real anchor, or the card promises nothing. */
    @Test
    fun `gives every constellation a brightest star and a way in`() {
        for (row in Constellations.WATCHED) {
            assertTrue(row.name, row.name.isNotBlank())
            assertTrue(row.name, row.brightestStar.isNotBlank())
            assertTrue(row.name, row.starHop.isNotBlank())
            assertTrue(row.name, row.raHours in 0.0..24.0)
            assertTrue(row.name, row.dec in -90.0..90.0)
            assertTrue(row.name, row.extentDegrees > 0.0)
        }
    }

    /** The table's own coordinates must agree with the library's boundaries. */
    @Test
    fun `puts every row inside the constellation it names`() {
        for (row in Constellations.WATCHED) {
            assertEquals(row.name, row.name, Constellations.containing(row.raHours, row.dec))
        }
    }

    /** Named once each, or the card would list the same pattern twice. */
    @Test
    fun `names each constellation once`() {
        val names = Constellations.WATCHED.map { it.name }

        assertEquals(names.toString(), names.size, names.toSet().size)
    }

    /** Plain ASCII, like everything else. */
    @Test
    fun `writes plain ascii`() {
        for (row in Constellations.WATCHED) {
            assertTrue(row.name, row.name.all { it.code in 32..126 })
            assertTrue(row.starHop, row.starHop.all { it.code in 32..126 })
            assertTrue(row.brightestStar, row.brightestStar.all { it.code in 32..126 })
        }
    }
}
