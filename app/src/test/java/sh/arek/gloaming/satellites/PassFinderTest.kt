package sh.arek.gloaming.satellites

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding when a satellite is above the horizon, and how high it gets.
 *
 * The propagator answers for one instant only, so the search is this project's
 * own code and this is where its bugs would live. It is checked against a
 * second-by-second sweep of the same propagator: the sweep is far too slow to
 * ship, but it is obviously correct, which makes it the right thing to be
 * measured against. Whether the propagator itself is right is a separate
 * question, settled by the Vallado vectors.
 *
 * Every case is a fixed element set at a fixed instant, so nothing here depends
 * on a clock, a network or the date the suite is run.
 */
class PassFinderTest {

    /** The ISS, from Vallado's own element set, and a night over Edinburgh. */
    private val iss = SatelliteElements(
        name = "ISS (ZARYA)",
        catalogue = 25544,
        epoch = 21320.51955234,
        epochMillis = 1637065689322L,
        meanMotion = 15.48582035,
        eccentricity = 0.0004694,
        inclination = 51.6447,
        raan = 309.4881,
        argPerigee = 203.6966,
        meanAnomaly = 299.8876,
        bstar = 0.000031985,
        meanMotionDot = 0.00001288,
    )

    private val latitude = 55.9533
    private val longitude = -3.1883
    private val from = 1637065689322L
    private val until = from + 24 * 60 * 60 * 1000L

    private fun elevationAt(millis: Long): Double =
        iss.toOrbitalData().getObject()
            .getElevation(GeoPos(latitude, longitude, 0.0), millis) * RAD2DEG

    /** Horizon crossings and peak elevations, at one-second resolution. */
    private fun sweep(): List<Triple<Long, Long, Double>> {
        val orbital = iss.toOrbitalData().getObject()
        val observer = GeoPos(latitude, longitude, 0.0)
        val found = mutableListOf<Triple<Long, Long, Double>>()

        var start: Long? = null
        var peak = -90.0
        var previous = orbital.getElevation(observer, from) * RAD2DEG

        var t = from + 1000L
        while (t <= until + 30 * 60 * 1000L) {
            val elevation = orbital.getElevation(observer, t) * RAD2DEG
            if (previous <= 0.0 && elevation > 0.0 && t <= until) {
                start = t
                peak = elevation
            } else if (start != null) {
                if (previous > 0.0 && elevation <= 0.0) {
                    found += Triple(start, t, peak)
                    start = null
                } else {
                    peak = maxOf(peak, elevation)
                }
            }
            previous = elevation
            t += 1000L
        }
        return found
    }

    @Test
    fun `finds the same passes as a second-by-second sweep`() {
        val expected = sweep().filter { it.third >= PassFinder.MINIMUM_PEAK_ELEVATION_DEGREES }

        val passes = PassFinder.passes(iss, latitude, longitude, from, until)

        assertEquals(expected.size, passes.size)
        expected.zip(passes).forEach { (swept, found) ->
            // The sweep only resolves to the second it lands on, so a second of
            // disagreement at the horizon is the sweep's error, not the search's.
            assertTrue(
                "rise ${found.risesAt} against swept ${swept.first}",
                abs(found.risesAt - swept.first) <= 1000L,
            )
            assertTrue(
                "set ${found.setsAt} against swept ${swept.second}",
                abs(found.setsAt - swept.second) <= 1000L,
            )
            // Elevation is flat near the top, so the peak is compared by height
            // rather than by the instant it was reached.
            assertEquals(swept.third, found.peakElevation, 0.01)
        }
    }

    @Test
    fun `puts the horizon crossings on the horizon`() {
        val passes = PassFinder.passes(iss, latitude, longitude, from, until)

        assertTrue(passes.isNotEmpty())
        passes.forEach {
            assertEquals(0.0, elevationAt(it.risesAt), 0.05)
            assertEquals(0.0, elevationAt(it.setsAt), 0.05)
            assertEquals(it.peakElevation, elevationAt(it.peaksAt), 0.01)
        }
    }

    @Test
    fun `runs in order without overlapping`() {
        val passes = PassFinder.passes(iss, latitude, longitude, from, until)

        passes.zipWithNext { earlier, later ->
            assertTrue(earlier.risesAt < earlier.peaksAt)
            assertTrue(earlier.peaksAt < earlier.setsAt)
            assertTrue(earlier.setsAt < later.risesAt)
        }
    }

    /**
     * A low pass is a pass behind the houses. The bar is the caller's, because
     * what counts as clear sky is the user's own horizon.
     */
    @Test
    fun `honours the minimum elevation asked for`() {
        val all = PassFinder.passes(iss, latitude, longitude, from, until, minimumPeakElevation = 0.0)
        val high = PassFinder.passes(iss, latitude, longitude, from, until, minimumPeakElevation = 60.0)

        assertTrue(high.size < all.size)
        assertTrue(high.all { it.peakElevation >= 60.0 })
    }

    /**
     * An orbit that never reaches the observer's latitude produces nothing, and
     * must do so without propagating a day of it first.
     */
    @Test
    fun `says nothing about a satellite that cannot rise there`() {
        val equatorial = iss.copy(catalogue = 99999, inclination = 5.0)

        assertTrue(PassFinder.passes(equatorial, latitude, longitude, from, until).isEmpty())
    }

    /**
     * A satellite that simply sits in the sky has no pass to announce.
     *
     * Not something the `visual` or `stations` groups contain, but the search
     * has to answer sensibly rather than inventing a horizon crossing half an
     * hour after the one it found - which is what a naive loop does, and it
     * would read as a real pass.
     */
    @Test
    fun `reports no pass for a satellite that never sets`() {
        val geostationary = iss.copy(
            name = "PARKED",
            catalogue = 99998,
            meanMotion = 1.0027,
            eccentricity = 0.0002,
            inclination = 0.05,
            raan = 0.0,
            argPerigee = 0.0,
            meanAnomaly = 0.0,
        )

        val passes = PassFinder.passes(
            geostationary, 0.0, longitude, from, until, minimumPeakElevation = 0.0,
        )

        assertTrue(passes.map { it.durationMillis }.toString(), passes.isEmpty())
    }

    /**
     * Entering the Earth's shadow is the moment a satellite visibly goes out,
     * mid-sky, which is worth telling someone standing outside.
     *
     * A different night, chosen because it has one: on the night the other
     * cases use, every pass over Edinburgh is already in shadow when it rises.
     * That is the ordinary state of affairs a few hours after sunset, and it is
     * why the entry is worth calling out when it does happen.
     */
    @Test
    fun `finds the moment a pass enters the shadow`() {
        val evening = 1637582400000L // 2021-11-22T12:00:00Z
        val passes = PassFinder.passes(
            iss,
            latitude,
            longitude,
            evening,
            evening + 24 * 60 * 60 * 1000L,
            minimumPeakElevation = 0.0,
        )

        val entering = passes.firstOrNull { it.entersShadowAt != null }
        assertNotNull("no pass in this window entered the shadow", entering)

        val at = entering!!.entersShadowAt!!
        assertTrue(at > entering.risesAt && at < entering.setsAt)
        assertTrue("sunlit before", !eclipsedAt(at - 2000L))
        assertTrue("eclipsed after", eclipsedAt(at + 2000L))
        assertNull("entering and leaving at once", entering.leavesShadowAt)
    }

    /**
     * The mirror case, and the reason it is worth tracking rather than
     * discarding: a satellite that rises in shadow and lights up part-way
     * across is a pre-dawn pass, which is visible for its second half. Reading
     * only the eclipse state at the highest point would call that one dark.
     *
     * Wellington rather than Edinburgh, because the pass cluster over Edinburgh
     * falls in the evening for the whole fortnight after this element set, and
     * a shadow exit only happens before dawn.
     */
    @Test
    fun `finds the moment a pass leaves the shadow`() {
        val morning = 1637712000000L // 2021-11-24T00:00:00Z
        val wellingtonLatitude = -41.29
        val wellingtonLongitude = 174.78
        val passes = PassFinder.passes(
            iss,
            wellingtonLatitude,
            wellingtonLongitude,
            morning,
            morning + 24 * 60 * 60 * 1000L,
            minimumPeakElevation = 0.0,
        )

        val leaving = passes.firstOrNull { it.leavesShadowAt != null }
        assertNotNull("no pass in this window left the shadow", leaving)

        val at = leaving!!.leavesShadowAt!!
        assertTrue(at > leaving.risesAt && at < leaving.setsAt)
        assertTrue("eclipsed before", eclipsedAt(at - 2000L, wellingtonLatitude, wellingtonLongitude))
        assertTrue("sunlit after", !eclipsedAt(at + 2000L, wellingtonLatitude, wellingtonLongitude))
        assertNull("leaving and entering at once", leaving.entersShadowAt)
    }

    /** A pass that is in shadow throughout has no entry to report. */
    @Test
    fun `reports no shadow entry for a pass that is dark throughout`() {
        val passes = PassFinder.passes(iss, latitude, longitude, from, until, minimumPeakElevation = 0.0)

        passes.filter {
            it.entersShadowAt == null && it.leavesShadowAt == null && !it.sunlitAtPeak
        }.forEach {
            assertTrue(eclipsedAt(it.risesAt) || eclipsedAt(it.peaksAt))
        }
    }

    private fun eclipsedAt(
        millis: Long,
        atLatitude: Double = latitude,
        atLongitude: Double = longitude,
    ): Boolean = iss.toOrbitalData().getObject()
        .getPosition(GeoPos(atLatitude, atLongitude, 0.0), millis).eclipsed
}
