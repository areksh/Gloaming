package sh.arek.gloaming.comets

import io.github.cosinekitty.astronomy.Time
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an orbit into somewhere to look, checked against JPL Horizons.
 *
 * The observer is Edinburgh, as everywhere else in this suite. The comets are
 * C/1995 O1 (Hale-Bopp), with the osculating elements Horizons gives for
 * 1997-03-29, and 1P/Halley at its 1986 apparition. Both sets of expectations
 * are Horizons' own answers for the same instants, requested in plain UT so
 * there is no ambiguity about which clock either side is reading.
 *
 * **Halley is here to catch a time-scale mistake and nothing else.** Perihelion
 * passage is published in Terrestrial Time and the phone's clock is UTC; in
 * 1986 the two differed by 55 seconds. Hale-Bopp drifts about an arcsecond in
 * that and no realistic tolerance would notice, but Halley was crossing the sky
 * at six and a half degrees a day, so the same mistake moves it seventeen
 * arcseconds - which the tolerance below does notice.
 */
class CometFinderTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private val haleBopp = Orbit(
        perihelionAu = 0.9141695106942098,
        eccentricity = 0.9951306139219294,
        inclinationDegrees = 89.43017886941958,
        nodeDegrees = 282.470593486876,
        perihelionArgumentDegrees = 130.5872422543277,
        perihelionJdTt = 2450539.633093244,
    )

    private val halley = Orbit(
        perihelionAu = 0.5871097150372122,
        eccentricity = 0.9672794335097039,
        inclinationDegrees = 162.2423847767351,
        nodeDegrees = 58.86030972914597,
        perihelionArgumentDegrees = 111.8665705152629,
        perihelionJdTt = 2446470.959040325,
    )

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun seen(
        orbit: Orbit,
        iso: String,
        magnitudeH: Double? = null,
        slope: Double? = null,
    ) = CometFinder.positionOf(orbit, magnitudeH, slope, latitude, longitude, at(iso))

    /**
     * Two thousandths of a degree, which is seven arcseconds.
     *
     * These are compared against Horizons' **geocentric** answer, because that
     * is what this computes. It leaves out the observer's displacement from the
     * centre of the Earth, which is not a fixed error but grows as a comet
     * comes closer: seven arcseconds for Hale-Bopp at 1.33 AU and twenty-one
     * for Halley at 0.42. Checking geocentric against topocentric would be
     * measuring that gap rather than the code, and the gap does not matter to
     * an app that asks how high something is.
     *
     * Tight enough to be worth having: reading the perihelion passage as UTC
     * rather than Terrestrial Time moves Halley seventeen arcseconds, so that
     * mistake fails this. Loose enough not to be measuring the difference
     * between two solar system ephemerides - the residual against Horizons is
     * about 1.4 arcseconds, which is the library's Earth against the one JPL
     * integrates.
     */
    private val positionTolerance = 0.002

    /**
     * A tenth of a degree for anything involving the horizon, which covers what
     * this deliberately does not model: annual aberration, worth about twenty
     * arcseconds and included in Horizons' apparent altitude; the observer's
     * displacement from the centre of the Earth, worth up to twenty at these
     * distances; and the difference between two refraction models. None of it
     * matters to an app asking whether a comet clears ten degrees, so the
     * altitude and azimuth are checked against a real observing site while the
     * coordinates above are checked geocentrically.
     */
    private val pointingTolerance = 0.1

    // --- the time scale ---

    /**
     * The conversion the rest of this file depends on, pinned directly.
     *
     * Doing it only through a position turns out to be a weak guard. Reading
     * the clock as UTC rather than Terrestrial Time moves even Halley - the
     * fastest thing here, crossing six degrees of sky a day - by under eight
     * arcseconds, which is inside any tolerance loose enough to survive the
     * difference between two solar system ephemerides. So the offset itself is
     * asserted, where it is a minute rather than a rounding error.
     *
     * Terrestrial Time ran 55.2 seconds ahead of UTC in April 1986, a
     * measured historical value rather than one this code produced. The
     * tolerance allows for the detail of the library's delta-T model without
     * allowing the answer to be zero.
     */
    @Test
    fun `reads the clock as Terrestrial Time and not as UTC`() {
        val millis = at("1986-04-11T22:00:00Z")
        val time = Time.fromMillisecondsSince1970(millis)
        val naiveUtcJulianDay = millis / 86_400_000.0 + 2440587.5

        val offsetSeconds = (CometFinder.julianDayTt(time) - naiveUtcJulianDay) * 86_400.0

        assertEquals(55.2, offsetSeconds, 1.0)
    }

    // --- against an independent ephemeris ---

    @Test
    fun `matches Horizons for a comet high in the sky`() {
        val sighting = seen(haleBopp, "1997-03-29T00:00:00Z")

        assertEquals(19.77649, sighting.rightAscensionDegrees, positionTolerance)
        assertEquals(45.42312, sighting.declinationDegrees, positionTolerance)
        assertEquals(12.375538, sighting.altitude, pointingTolerance)
        assertEquals(348.233531, sighting.azimuth, pointingTolerance)
    }

    @Test
    fun `matches Horizons on both distances`() {
        val sighting = seen(haleBopp, "1997-03-29T00:00:00Z")

        assertEquals(0.915904448125, sighting.sunDistanceAu, 1e-5)
        assertEquals(1.33215298571509, sighting.earthDistanceAu, 1e-5)
    }

    /**
     * The fastest-moving fixture here, and the closest: Halley was 0.42 AU away
     * and crossing six degrees of sky a day. It is below the horizon from
     * Edinburgh that night, which is the honest answer for a comet that far
     * south and worth pinning too.
     */
    @Test
    fun `matches Horizons for a fast close comet`() {
        val sighting = seen(halley, "1986-04-11T22:00:00Z")

        assertEquals(218.71645, sighting.rightAscensionDegrees, positionTolerance)
        assertEquals(-46.54065, sighting.declinationDegrees, positionTolerance)
        assertEquals(1.346040532131, sighting.sunDistanceAu, 1e-5)
        assertEquals(0.41858683514279, sighting.earthDistanceAu, 1e-5)
        assertTrue(sighting.altitude.toString(), sighting.altitude < -15.0)
    }

    /**
     * Propagated 96 days back from the epoch its elements osculate at, which is
     * the case that matters in practice: the fetcher republishes weekly and the
     * app has to stay right in between. Two-body motion drifts from the real
     * thing by well under an arcsecond over three months, even for a comet
     * passing this close to the sun.
     */
    @Test
    fun `stays right when the elements are propagated months`() {
        val sighting = seen(haleBopp, "1996-12-23T00:00:00Z")

        assertEquals(277.46494, sighting.rightAscensionDegrees, positionTolerance)
        assertEquals(2.81744, sighting.declinationDegrees, positionTolerance)
        assertEquals(1.867063865154, sighting.sunDistanceAu, 1e-4)
        assertEquals(2.69085521308189, sighting.earthDistanceAu, 1e-4)
    }

    /**
     * Three degrees up is where the app's answer turns from "go outside" to
     * "not from here", so it is worth pinning. It is also where refraction is
     * largest - it lifts a body about a quarter of a degree here - which is why
     * the altitude reported is the apparent one, matching what `SkyCalendar`
     * already does for the planets.
     */
    @Test
    fun `matches Horizons for a comet scraping the horizon`() {
        val sighting = seen(haleBopp, "1997-04-22T00:00:00Z")

        assertEquals(61.63892, sighting.rightAscensionDegrees, positionTolerance)
        assertEquals(31.86519, sighting.declinationDegrees, positionTolerance)
        assertEquals(2.894135, sighting.altitude, pointingTolerance)
    }

    /**
     * Below the horizon the coordinates still have to be right and the altitude
     * only has to be decisively negative. Refraction models disagree well below
     * the horizon because the question is meaningless there - there is no air
     * path for the light to bend along.
     */
    @Test
    fun `puts a comet below the horizon below the horizon`() {
        val sighting = seen(haleBopp, "1996-12-23T00:00:00Z")

        assertTrue(sighting.altitude.toString(), sighting.altitude < -25.0)
    }

    // --- brightness ---

    /**
     * The standard comet law, `m = H + 5 log10(delta) + 2.5 G log10(r)`, worked
     * by hand rather than taken from the code: at one AU from both the sun and
     * the Earth both logarithms vanish, so the answer is the absolute magnitude
     * itself.
     */
    @Test
    fun `puts a comet at one AU from everything at its absolute magnitude`() {
        assertEquals(6.5, CometFinder.magnitudeOf(6.5, 4.0, 1.0, 1.0)!!, 1e-9)
    }

    @Test
    fun `dims a comet with distance from the sun and from the observer`() {
        val near = CometFinder.magnitudeOf(6.5, 4.0, 1.0, 1.0)!!
        val far = CometFinder.magnitudeOf(6.5, 4.0, 2.0, 2.0)!!

        // 5 log10(2) + 10 log10(2) = 15 log10(2) = 4.515.
        assertEquals(4.515, far - near, 1e-3)
    }

    /**
     * The slope is what makes a comet a comet rather than a rock: it brightens
     * far faster than the inverse-square law as it nears the sun, because it is
     * growing a coma rather than merely reflecting more light.
     */
    @Test
    fun `brightens a comet faster than a rock as it nears the sun`() {
        val comet = CometFinder.magnitudeOf(6.5, 4.0, 0.5, 1.0)!!
        val rock = CometFinder.magnitudeOf(6.5, 1.0, 0.5, 1.0)!!

        assertTrue("$comet should be brighter than $rock", comet < rock)
    }

    @Test
    fun `has no magnitude for a comet with no absolute magnitude`() {
        assertNull(CometFinder.magnitudeOf(null, 4.0, 1.0, 1.0))
    }

    /** A missing slope falls back to the conventional 4 rather than to no answer. */
    @Test
    fun `assumes the usual slope when the file gives none`() {
        assertEquals(
            CometFinder.magnitudeOf(6.5, 4.0, 1.5, 1.5),
            CometFinder.magnitudeOf(6.5, null, 1.5, 1.5),
        )
    }

    @Test
    fun `reports the magnitude on the sighting when it can`() {
        val sighting = seen(haleBopp, "1997-03-29T00:00:00Z", magnitudeH = 6.5, slope = 4.0)

        assertNotNull(sighting.magnitude)
        assertEquals(
            CometFinder.magnitudeOf(6.5, 4.0, sighting.sunDistanceAu, sighting.earthDistanceAu),
            sighting.magnitude,
        )
    }

    // --- the best moment of a night ---

    /**
     * A comet is not an occasion with a time, so the calendar has to pick one:
     * the moment in the coming night when it stands highest. The same question
     * `SkyCalendar.bestInDarkness` already answers for the planets, on the same
     * night boundaries.
     */
    @Test
    fun `finds the highest moment of the nearest night`() {
        val night = CometFinder.bestInDarkness(
            haleBopp,
            magnitudeH = 6.5,
            slope = 4.0,
            latitude = latitude,
            longitude = longitude,
            aroundMillis = at("1997-03-29T00:00:00Z"),
        )

        assertNotNull(night)
        assertTrue(night!!.altitude > 0.0)
        assertTrue(night.isDark)

        // Nothing dark that night may stand higher than the moment chosen.
        for (step in 0..48) {
            val other = CometFinder.positionOf(
                haleBopp, 6.5, 4.0, latitude, longitude,
                night.atMillis - 6 * 3_600_000L + step * 15 * 60_000L,
            )
            if (other.isDark) {
                assertTrue(
                    "${other.altitude} at ${other.atMillis} beats ${night.altitude}",
                    other.altitude <= night.altitude + 1e-6,
                )
            }
        }
    }

    /**
     * At a high enough latitude in midsummer there is no darkness to be had,
     * and that is an answer rather than a failure - the same one `Twilight`
     * gives, and for the same reason.
     */
    @Test
    fun `has no best moment where the sun does not set`() {
        assertNull(
            CometFinder.bestInDarkness(
                haleBopp, 6.5, 4.0, 78.0, 15.0, at("1997-06-21T00:00:00Z"),
            ),
        )
    }
}
