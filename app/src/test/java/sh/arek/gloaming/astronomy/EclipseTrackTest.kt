package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eclipse countdown, against eclipses whose contact times are published.
 *
 * Deliberately covers partial and lunar eclipses as well as total ones: from
 * Edinburgh the next six solar eclipses are all partial and there is no
 * totality for decades, so a totality-only countdown would be a feature with no
 * user here.
 */
class EclipseTrackTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun track(peak: String) =
        EclipseTracks.forPeak(latitude, longitude, at(peak))

    // --- finding the eclipse again ---

    /** The partial solar eclipse of 2 August 2027, with its three contacts. */
    @Test
    fun `rebuilds a partial solar eclipse from its peak`() {
        val found = track("2027-08-02T09:00:23Z")

        assertNotNull(found)
        assertEquals("Partial eclipse of the sun", found!!.title)
        assertEquals(SkyBody.Sun, found.body)
        assertEquals(
            listOf("first contact", "maximum", "last contact"),
            found.moments.map { it.label },
        )
        assertEquals(at("2027-08-02T08:11:11Z") / 1000, found.startsAt / 1000)
        assertEquals(at("2027-08-02T09:51:31Z") / 1000, found.endsAt / 1000)
    }

    /** The near-total lunar eclipse of 28 August 2026, which has no totality. */
    @Test
    fun `rebuilds a partial lunar eclipse from its peak`() {
        val found = track("2026-08-28T04:12:49Z")

        assertNotNull(found)
        assertEquals("Partial eclipse of the moon", found!!.title)
        assertEquals(SkyBody.Moon, found.body)
        assertEquals(
            listOf("the shadow first touches", "maximum", "the shadow leaves"),
            found.moments.map { it.label },
        )
    }

    /** A total lunar eclipse gains the two moments that bound totality. */
    @Test
    fun `gives a total lunar eclipse its totality contacts`() {
        val found = track("2028-12-31T16:51:54Z")

        assertNotNull(found)
        assertEquals(
            listOf(
                "the shadow first touches",
                "totality begins",
                "maximum",
                "totality ends",
                "the shadow leaves",
            ),
            found!!.moments.map { it.label },
        )
    }

    /** Contacts are always in order, or the countdown would go backwards. */
    @Test
    fun `keeps the contacts in time order`() {
        for (peak in listOf("2027-08-02T09:00:23Z", "2026-08-28T04:12:49Z", "2028-12-31T16:51:54Z")) {
            val moments = track(peak)!!.moments
            assertEquals(peak, moments.map { it.atMillis }.sorted(), moments.map { it.atMillis })
        }
    }

    /** Asked about a time with no eclipse near it, there is nothing to follow. */
    @Test
    fun `finds nothing when no eclipse is near`() {
        assertNull(track("2027-03-15T12:00:00Z"))
    }

    /** A penumbral eclipse is not followed, because there is nothing to see. */
    @Test
    fun `refuses to follow a penumbral eclipse`() {
        assertNull(track("2027-02-20T23:12:44Z"))
    }

    // --- counting down ---

    /** Before it starts, the next thing is first contact. */
    @Test
    fun `counts down to first contact`() {
        val found = track("2027-08-02T09:00:23Z")!!
        val next = EclipseTracks.nextMoment(found, at("2027-08-02T08:00:00Z"))

        assertEquals("first contact", next!!.label)
    }

    /** Part-way through, it has moved on to the next one. */
    @Test
    fun `moves on to the next contact as each passes`() {
        val found = track("2027-08-02T09:00:23Z")!!
        val next = EclipseTracks.nextMoment(found, at("2027-08-02T08:30:00Z"))

        assertEquals("maximum", next!!.label)
    }

    /** Once the last contact is past there is nothing ahead. */
    @Test
    fun `has nothing left after the last contact`() {
        val found = track("2027-08-02T09:00:23Z")!!

        assertNull(EclipseTracks.nextMoment(found, at("2027-08-02T10:00:00Z")))
        assertTrue(EclipseTracks.isOver(found, at("2027-08-02T10:00:00Z")))
    }

    // --- what it says ---

    /** The line names what is next, when, and which way to look. */
    @Test
    fun `says what is next and where to look`() {
        val found = track("2027-08-02T09:00:23Z")!!
        val now = at("2027-08-02T08:00:00Z")
        val where = SkyCalendar.positionOf(SkyBody.Sun, latitude, longitude, now)

        val line = EclipseTracks.describe(found, where, now)

        assertTrue(line, line.contains("first contact in"))
        assertTrue(line, line.contains("degrees up"))
        assertTrue(line, line.all { it.code in 32..126 })
    }

    /**
     * The one thing the app will ever point somebody at that can blind them, so
     * the warning is on every line rather than only the first.
     */
    @Test
    fun `warns about the sun on every line`() {
        val found = track("2027-08-02T09:00:23Z")!!
        for (minute in listOf(0L, 30L, 70L)) {
            val now = at("2027-08-02T08:00:00Z") + minute * 60_000
            val line = EclipseTracks.describe(found, null, now)
            assertTrue(line, line.contains("solar filter"))
        }
    }

    /** A lunar eclipse carries no such warning, because it needs none. */
    @Test
    fun `does not warn about the moon`() {
        val found = track("2026-08-28T04:12:49Z")!!
        val line = EclipseTracks.describe(found, null, at("2026-08-28T03:00:00Z"))

        assertTrue(line, !line.contains("filter"))
    }

    /** Below the horizon is worth saying rather than pointing at the ground. */
    @Test
    fun `says when it is below the horizon`() {
        val found = track("2026-08-28T04:12:49Z")!!
        val line = EclipseTracks.describe(
            found,
            Sighting(0L, altitude = -12.0, azimuth = 180.0),
            at("2026-08-28T03:00:00Z"),
        )

        assertTrue(line, line.contains("Below the horizon"))
    }

    /** Once over it says so, and for the sun it says the danger has passed. */
    @Test
    fun `says when it is over`() {
        val solar = EclipseTracks.describe(
            track("2027-08-02T09:00:23Z")!!,
            null,
            at("2027-08-02T11:00:00Z"),
        )
        assertEquals("Over. Safe to look away.", solar)

        val lunar = EclipseTracks.describe(
            track("2026-08-28T04:12:49Z")!!,
            null,
            at("2026-08-28T08:00:00Z"),
        )
        assertEquals("Over.", lunar)
    }

    /** The clock is padded so the line does not change width as it counts. */
    @Test
    fun `pads the countdown so it does not jitter`() {
        assertEquals("45s", EclipseTracks.countdown(45_000))
        assertEquals("2m 05s", EclipseTracks.countdown(125_000))
        assertEquals("1h 05m", EclipseTracks.countdown(65 * 60_000L))
        assertEquals("0s", EclipseTracks.countdown(-5_000))
    }
}
