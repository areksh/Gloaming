package sh.arek.gloaming.astronomy

import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrapper over the vendored ephemeris, not the ephemeris itself.
 *
 * astronomy-engine is validated upstream against NOVAS and JPL Horizons to
 * within an arcminute; re-testing that here would be re-testing a third party.
 * What is worth testing is everything this project puts around it - that the
 * epoch conversion is right, that latitude and longitude have not been
 * transposed, that the refraction model matches the one the window search uses,
 * and above all that the polar cases produce an answer rather than a null
 * somebody later dereferences.
 *
 * The solar assertions are checked against geometry rather than against the
 * library: at solar noon on a solstice the sun's altitude is
 * `90 - |latitude - declination|`, and the solstice declination is +/-23.44
 * degrees. That is an independent oracle.
 */
class TwilightTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val greenwichLatitude = 51.4779
    private val greenwichLongitude = 0.0

    private val juneSolstice = 1_782_043_200_000L // 2026-06-21T12:00:00Z
    private val decemberSolstice = 1_797_854_400_000L // 2026-12-21T12:00:00Z
    private val marchEquinox = 1_774_008_000_000L // 2026-03-20T12:00:00Z

    private val obliquity = 23.44

    /**
     * The day's highest sun, which is the quantity the geometry actually
     * predicts.
     *
     * Sampling the day rather than reading 12:00 UTC, because solar noon is not
     * noon: the equation of time moves it by up to a quarter of an hour, which
     * at the equator is nearly two degrees of altitude. Asserting against the
     * clock instead of against the sun would encode that error as an
     * expectation.
     */
    private fun highestSun(latitude: Double, longitude: Double, onDay: Long): Double =
        (0..(24 * 60)).maxOf { Twilight.sunAltitude(latitude, longitude, onDay + it * 60_000L) }

    @Test
    fun `puts the midsummer sun where the geometry says it is`() {
        val altitude = highestSun(greenwichLatitude, greenwichLongitude, juneSolstice)

        assertEquals(90.0 - greenwichLatitude + obliquity, altitude, 0.2)
    }

    @Test
    fun `puts the midwinter sun where the geometry says it is`() {
        val altitude = highestSun(greenwichLatitude, greenwichLongitude, decemberSolstice)

        assertEquals(90.0 - greenwichLatitude - obliquity, altitude, 0.2)
    }

    @Test
    fun `puts the equinox sun overhead at the equator`() {
        val altitude = highestSun(latitude = 0.0, longitude = 0.0, onDay = marchEquinox)

        assertEquals(90.0, altitude, 0.2)
    }

    /**
     * Transposing latitude and longitude is the classic way to get plausible
     * nonsense out of an ephemeris. At noon UTC the sun is high over Greenwich
     * and below the horizon at the antimeridian; swapping the arguments would
     * put it somewhere else entirely.
     */
    @Test
    fun `does not transpose latitude and longitude`() {
        val overhead = Twilight.sunAltitude(greenwichLatitude, greenwichLongitude, juneSolstice)
        val antipodal = Twilight.sunAltitude(greenwichLatitude, 180.0, juneSolstice)

        assertTrue("sun should be high over Greenwich at noon, was $overhead", overhead > 55.0)
        assertTrue("sun should be down at the antimeridian, was $antipodal", antipodal < 0.0)
    }

    @Test
    fun `reports darkness now when the sun is already down`() {
        // 01:00 UTC in mid-January at Greenwich: the middle of a long night.
        val night = 1_768_438_800_000L

        val darkness = Twilight.darkness(greenwichLatitude, greenwichLongitude, night)

        assertTrue("$darkness", darkness is Darkness.Now)
        assertNotNull((darkness as Darkness.Now).endsAt)
    }

    @Test
    fun `reports darkness later when the sun is still up`() {
        val darkness = Twilight.darkness(greenwichLatitude, greenwichLongitude, decemberSolstice)

        assertTrue("$darkness", darkness is Darkness.Later)
        val later = darkness as Darkness.Later
        assertTrue("darkness must start after now", later.startsAt > decemberSolstice)
        assertTrue("darkness must end after it starts", later.endsAt!! > later.startsAt)
    }

    /**
     * Shetland's simmer dim, and the reason this returns a type rather than a
     * nullable time. Above about 60 degrees north there is no astronomical
     * darkness at all from May to August - the sun never gets 18 degrees down -
     * and the app says so rather than computing nonsense.
     */
    @Test
    fun `reports no darkness at all through the polar summer`() {
        val darkness = Twilight.darkness(
            latitude = 60.15, // Lerwick
            longitude = -1.15,
            fromMillis = juneSolstice,
            belowDegrees = Twilight.ASTRONOMICAL_DEGREES,
        )

        assertEquals(Darkness.None, darkness)
    }

    // The same place and date, at the shallower threshold the aurora gate uses,
    // does get dark enough - which is why the two thresholds are separate.
    @Test
    fun `still finds civil darkness at Lerwick in midsummer`() {
        val darkness = Twilight.darkness(
            latitude = 60.15,
            longitude = -1.15,
            fromMillis = juneSolstice,
            belowDegrees = Twilight.CIVIL_DEGREES,
        )

        assertTrue("$darkness", darkness is Darkness.Later)
    }

    @Test
    fun `reports no darkness under the midnight sun`() {
        val darkness = Twilight.darkness(
            latitude = 89.0,
            longitude = 0.0,
            fromMillis = juneSolstice,
            belowDegrees = Twilight.CIVIL_DEGREES,
        )

        assertEquals(Darkness.None, darkness)
    }

    /**
     * The other polar case, and the one a nullable end time exists for: in the
     * midwinter dark the sun does not come back up, so the darkness has a start
     * but no end within any horizon worth searching.
     */
    @Test
    fun `reports darkness with no end during the polar night`() {
        val darkness = Twilight.darkness(
            latitude = 89.0,
            longitude = 0.0,
            fromMillis = decemberSolstice,
            belowDegrees = Twilight.CIVIL_DEGREES,
        )

        assertTrue("$darkness", darkness is Darkness.Now)
        assertNull("polar night has no dawn to find", (darkness as Darkness.Now).endsAt)
    }

    // The gate asks this question and nothing else, so it should not have to
    // unpack a window to get an answer.
    @Test
    fun `answers whether it is dark right now`() {
        assertTrue(Twilight.isDark(greenwichLatitude, greenwichLongitude, 1_768_438_800_000L))
        assertTrue(!Twilight.isDark(greenwichLatitude, greenwichLongitude, decemberSolstice))
    }

    /**
     * The window boundaries and the "is it dark now" answer have to come from
     * the same model, or the app can believe it is light at the exact moment
     * its own dusk arrives. Both use geometric altitude, no refraction.
     */
    @Test
    fun `agrees with itself at the moment darkness starts`() {
        val darkness = Twilight.darkness(greenwichLatitude, greenwichLongitude, decemberSolstice)
        val startsAt = (darkness as Darkness.Later).startsAt

        val altitude = Twilight.sunAltitude(greenwichLatitude, greenwichLongitude, startsAt)

        assertEquals(Twilight.CIVIL_DEGREES, altitude, 0.02)
        assertTrue(Twilight.isDark(greenwichLatitude, greenwichLongitude, startsAt + 60_000L))
    }

    @Test
    fun `reports the moon phase and how much of it is lit`() {
        val moon = Twilight.moon(marchEquinox)

        assertTrue("phase $moon", moon.phaseDegrees in 0.0..360.0)
        assertTrue("illumination $moon", moon.illumination in 0.0..1.0)
    }

    /**
     * A synodic month is 29.53 days, so the moon returns to the same phase
     * after one. Checking that pins the epoch conversion: a wrapper that fed
     * seconds where milliseconds were wanted would drift wildly here while
     * still returning a plausible-looking number for a single instant.
     */
    @Test
    fun `advances the moon phase through a synodic month`() {
        val synodicMonth = (29.53059 * 24 * 60 * 60 * 1000).toLong()

        val start = Twilight.moon(marchEquinox)
        val later = Twilight.moon(marchEquinox + synodicMonth)

        val drift = abs(later.phaseDegrees - start.phaseDegrees)
        assertTrue("phase drifted $drift degrees over one synodic month", drift < 10.0)
    }

    @Test
    fun `reports a new moon as dark and a full moon as lit`() {
        val newMoon = Twilight.moon(newMoonNear(marchEquinox))
        val fullMoon = Twilight.moon(fullMoonNear(marchEquinox))

        assertTrue("new moon lit ${newMoon.illumination}", newMoon.illumination < 0.02)
        assertTrue("full moon lit ${fullMoon.illumination}", fullMoon.illumination > 0.98)
    }

    /**
     * A full moon low in the sky washes out far less than one overhead, so the
     * altitude is part of the answer, not just the phase.
     */
    @Test
    fun `reports where the moon is for an observer`() {
        val altitudes = (0..23).map {
            Twilight.moonAltitude(
                greenwichLatitude,
                greenwichLongitude,
                marchEquinox + it * 60 * 60 * 1000L,
            )
        }

        assertTrue("the moon should be up at some point in a day", altitudes.any { it > 0 })
        assertTrue("the moon should be down at some point in a day", altitudes.any { it < 0 })
    }

    /** Steps an hour at a time to the nearest instant with the given phase. */
    private fun phaseNear(from: Long, target: Double): Long {
        val hour = 60 * 60 * 1000L
        return (0..(24 * 40)).map { from + it * hour }
            .minBy { angleBetween(Twilight.moon(it).phaseDegrees, target) }
    }

    private fun newMoonNear(from: Long): Long = phaseNear(from, 0.0)

    private fun fullMoonNear(from: Long): Long = phaseNear(from, 180.0)

    private fun angleBetween(a: Double, b: Double): Double {
        val difference = abs(a - b) % 360.0
        return if (difference > 180.0) 360.0 - difference else difference
    }

    // --- moonlight, shared between showers and comets ---

    /**
     * How much of a night's darkness the moon spoils.
     *
     * Shared by meteor showers and comets, which is why it lives in `Twilight`
     * rather than in either: two implementations of the same number would
     * eventually be two different numbers. It matters most to comets, a comet
     * being a diffuse smudge whose contrast against the sky is what moonlight
     * destroys.
     *
     * The dates are a real full moon and a real new moon over Edinburgh in
     * 1997, so the assertion is about the sky rather than about the arithmetic.
     */
    @Test
    fun `finds a full moon up all night ruinous and a new moon harmless`() {
        val fullMoon = at("1997-03-23T22:00:00Z")
        val newMoon = at("1997-03-08T22:00:00Z")

        assertTrue(Twilight.moon(fullMoon).illumination > 0.99)
        assertTrue(Twilight.moon(newMoon).illumination < 0.02)

        assertTrue(Twilight.moonInterference(55.95, -3.19, fullMoon) > 0.9)
        assertEquals(0.0, Twilight.moonInterference(55.95, -3.19, newMoon), 0.01)
    }

    /**
     * Brightness alone is the wrong measure, which is the whole reason this is
     * a product rather than an illumination. A full moon that has set by
     * midnight costs far less than a half moon up all night, so the fraction
     * has to be able to pull a bright moon's cost down.
     */
    @Test
    fun `never reports more interference than the moon is lit`() {
        var moment = at("1997-02-01T22:00:00Z")
        val end = at("1997-04-15T22:00:00Z")
        while (moment <= end) {
            val interference = Twilight.moonInterference(55.95, -3.19, moment)
            val illumination = Twilight.moon(moment).illumination

            assertTrue("$interference at $moment", interference in 0.0..1.0)
            assertTrue(
                "$interference exceeds $illumination at $moment",
                interference <= illumination + 1e-9,
            )
            moment += 24L * 60 * 60 * 1000
        }
    }

    /** No darkness to spoil is no interference, not a failure. */
    @Test
    fun `reports no interference where the sun does not set`() {
        assertEquals(
            0.0,
            Twilight.moonInterference(78.2, 15.6, at("1997-06-21T12:00:00Z")),
            0.0,
        )
    }
}
