package sh.arek.gloaming.satellites

import sh.arek.gloaming.astronomy.Compass
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a pass second by second while it happens.
 *
 * The prediction and the live track must not disagree. They come from the same
 * propagator but by different routes - the finder searches for the peak, the
 * tracker asks for one instant - and a user standing outside has both in front
 * of them at once: a notification that said "high in the south at 22:14" and a
 * countdown ticking beside it. Two answers that differ by a few degrees would
 * be visible, and would discredit both.
 *
 * Fixed elements at a fixed instant, so no clock and no network.
 */
class LivePassTest {

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

    private val pass: SatellitePass = PassFinder.passes(
        elements = iss,
        latitude = latitude,
        longitude = longitude,
        fromMillis = from,
        untilMillis = from + 24 * 60 * 60 * 1000L,
    ).first()

    private fun tracker() = LiveTracker(iss, latitude, longitude)

    private val tolerance = 10 * 60_000L

    @Test
    fun `agrees with the prediction at the highest point`() {
        val fix = tracker().fixAt(pass.peaksAt)

        assertEquals(pass.peakElevation, fix.elevation, 0.01)
        assertEquals(pass.peakAzimuth, fix.azimuth, 0.01)
        assertEquals(pass.rangeAtPeakKm, fix.rangeKm, 0.5)
    }

    @Test
    fun `puts the satellite on the horizon when the pass begins`() {
        assertEquals(0.0, tracker().fixAt(pass.risesAt).elevation, 0.05)
        assertEquals(0.0, tracker().fixAt(pass.setsAt).elevation, 0.05)
    }

    @Test
    fun `climbs all the way to the peak and falls away after it`() {
        val tracker = tracker()
        val step = (pass.peaksAt - pass.risesAt) / 8

        var previous = tracker.fixAt(pass.risesAt).elevation
        for (i in 1..8) {
            val next = tracker.fixAt(pass.risesAt + i * step).elevation
            assertTrue("elevation should rise into the peak", next > previous)
            previous = next
        }
        assertTrue(tracker.fixAt(pass.setsAt - step).elevation < pass.peakElevation)
    }

    @Test
    fun `reports sunlight the same way the prediction did`() {
        assertEquals(pass.sunlitAtPeak, tracker().fixAt(pass.peaksAt).sunlit)
    }

    @Test
    fun `counts down to the rise before the pass begins`() {
        val text = LivePass.describe(
            fix = tracker().fixAt(pass.risesAt - 252_000L),
            pass = pass,
            now = pass.risesAt - 252_000L,
        )

        assertTrue(text, text.startsWith("Rises ${Compass.of(pass.riseAzimuth).label} in 4m 12s"))
    }

    @Test
    fun `says which way to look and how high once it is up`() {
        val at = pass.risesAt + (pass.peaksAt - pass.risesAt) / 2
        val fix = tracker().fixAt(at)

        val text = LivePass.describe(fix, pass, at)

        assertTrue(text, text.contains(Compass.of(fix.azimuth).label))
        assertTrue(text, text.contains("${fix.elevation.roundToInt()} degrees up"))
        assertTrue(text, text.contains("highest in"))
    }

    @Test
    fun `counts down to the set once it is past its highest`() {
        val at = pass.peaksAt + (pass.setsAt - pass.peaksAt) / 2

        val text = LivePass.describe(tracker().fixAt(at), pass, at)

        assertTrue(text, text.contains("sets in"))
    }

    @Test
    fun `says when the satellite has gone out rather than describing a dark sky`() {
        val at = pass.peaksAt
        val fix = tracker().fixAt(at).copy(sunlit = false)

        val text = LivePass.describe(fix, pass, at)

        assertTrue(text, text.contains("shadow"))
    }

    @Test
    fun `says it is over once the satellite has set`() {
        val at = pass.setsAt + 1000L

        assertEquals("Gone.", LivePass.describe(tracker().fixAt(at), pass, at))
    }

    @Test
    fun `pads the seconds so the countdown does not jitter`() {
        // "2m 5s" and "2m 15s" are different widths, and a notification that
        // changes width every ten seconds reads as broken.
        assertEquals("2m 05s", LivePass.countdown(125_000L))
        assertEquals("2m 15s", LivePass.countdown(135_000L))
        assertEquals("45s", LivePass.countdown(45_000L))
        assertEquals("0s", LivePass.countdown(-1L))
    }

    /**
     * The service starts before the rise, not before the peak: the rise is the
     * moment to be looking, and it is what the countdown counts to.
     */
    @Test
    fun `starts ten minutes before the satellite comes up`() {
        assertEquals(pass.risesAt - 10 * 60_000L, LivePass.startAt(pass))
    }

    /**
     * The tracker re-derives the pass when it starts, from whatever elements
     * the device holds by then, so the moment it finds is not the moment the
     * user was told about. It has to recognise the drifted pass as the same one
     * and reject a different pass entirely.
     */
    @Test
    fun `picks the pass nearest the one the user was told about`() {
        val first = pass
        val later = pass.copy(
            risesAt = pass.risesAt + 90 * 60_000L,
            peaksAt = pass.peaksAt + 90 * 60_000L,
            setsAt = pass.setsAt + 90 * 60_000L,
        )

        val chosen = LivePass.chosen(listOf(later, first), first.peaksAt + 180_000L, tolerance)

        assertEquals(first.peaksAt, chosen?.peaksAt)
    }

    @Test
    fun `refuses a pass that has moved further than a pass can move`() {
        val chosen = LivePass.chosen(listOf(pass), pass.peaksAt + 40 * 60_000L, tolerance)

        assertEquals(null, chosen)
    }

    @Test
    fun `has nothing to follow when the elements yield no pass`() {
        assertEquals(null, LivePass.chosen(emptyList(), pass.peaksAt, tolerance))
    }

    @Test
    fun `will not follow a pass that is already over`() {
        assertTrue(LivePass.isWorthFollowing(pass, pass.risesAt - 60_000L))
        assertTrue(LivePass.isWorthFollowing(pass, pass.peaksAt))
        assertTrue(!LivePass.isWorthFollowing(pass, pass.setsAt + 1))
    }

    /**
     * The notification carries the rise but not the whole pass, so the start
     * time has to be derivable from that one number alone.
     */
    @Test
    fun `works the start out from the rise alone`() {
        assertEquals(LivePass.startAt(pass), LivePass.startAt(pass.risesAt))
    }

    /**
     * A phone that reboots between the alert and the pass loses its alarms, and
     * this is the worst request in the app to drop silently: the user has
     * already decided to go outside. So the request is written down, and what
     * is written down has to be judged still current when it is read back.
     */
    @Test
    fun `puts a follow back only while the pass is still to come`() {
        assertTrue(LivePass.stillPending(pass.peaksAt, pass.risesAt))
        assertTrue(LivePass.stillPending(pass.peaksAt, pass.peaksAt))
        assertTrue(!LivePass.stillPending(pass.peaksAt, pass.peaksAt + 1))
    }

    /**
     * The alert goes out thirty minutes ahead and the tracker starts ten, so
     * somebody who acts on the notification at once has twenty minutes to wait
     * and somebody who reads it late has none. Tapping late must still start
     * the tracker, and that tap is also the best moment to start one: Android
     * only lets a foreground service begin from the background under an
     * exemption, and a notification the user has just pressed is one.
     */
    @Test
    fun `starts at once only once the lead has run out`() {
        val start = LivePass.startAt(pass.risesAt)

        assertTrue(!LivePass.startsNow(pass.risesAt, start - 1))
        assertTrue(LivePass.startsNow(pass.risesAt, start))
        assertTrue(LivePass.startsNow(pass.risesAt, pass.risesAt + 60_000L))
    }
}
