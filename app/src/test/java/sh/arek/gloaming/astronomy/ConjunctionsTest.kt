package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Close approaches, judged as they are seen rather than as they are closest.
 *
 * The distinction is the whole feature. Over the next four hundred nights most
 * of the closest approaches from Edinburgh happen with the pair below the
 * horizon - the moon passes within four arcminutes of Venus on 14 September
 * 2026, at ten in the morning. An app that announced that would be technically
 * correct and useless.
 */
class ConjunctionsTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun found(from: String, days: Int, within: Double = 3.0) = Conjunctions.approaches(
        latitude = latitude,
        longitude = longitude,
        fromMillis = at(from),
        untilMillis = at(from) + days * 86_400_000L,
        withinDegrees = within,
    )

    private fun pair(list: List<Approach>, a: SkyBody, b: SkyBody) =
        list.firstOrNull { (it.first == a && it.second == b) || (it.first == b && it.second == a) }

    /**
     * The hours before it happens are the hours it matters.
     *
     * The search samples every six hours and calls a sample a minimum when it
     * is lower than the ones either side of it, so the first sample never had
     * anything before it to compare against and an approach in the opening six
     * hours could not be seen. That is not an obscure corner: the calendar is
     * worked out from the moment the app is opened, so the six hours it could
     * not see were always the next six - and somebody opening the app at nine
     * to ask about a conjunction at eleven was told there was nothing.
     */
    @Test
    fun `finds an approach in the next few hours`() {
        val approach = pair(
            found("2026-11-16T00:00:00Z", 5),
            SkyBody.Mars,
            SkyBody.Jupiter,
        )

        assertNotNull("Mars and Jupiter are closest at 01:50 on 16 November", approach)
        assertEquals(
            Instant.ofEpochMilli(approach!!.atMillis).toString(),
            at("2026-11-16T01:50:43Z") / 1000,
            approach.atMillis / 1000,
        )
    }

    /** And the same at the far end, where the last sample had nothing after it. */
    @Test
    fun `finds an approach in the last few hours`() {
        val approach = pair(
            found("2026-11-11T06:00:00Z", 5),
            SkyBody.Mars,
            SkyBody.Jupiter,
        )

        assertNotNull("the same approach, four hours inside the end", approach)
    }

    /** What falls outside the window still stays outside it. */
    @Test
    fun `leaves out an approach beyond the window`() {
        val approach = pair(
            found("2026-11-01T00:00:00Z", 10),
            SkyBody.Mars,
            SkyBody.Jupiter,
        )

        assertNull(approach)
    }

    /**
     * Mars and Jupiter, 16 November 2026: 1.2 degrees apart with both a good way
     * up in a dark sky. The one unambiguously good planet-planet pairing in the
     * horizon, and the case the search has to get right to the second.
     */
    @Test
    fun `finds mars and jupiter at their closest to the second`() {
        val approach = pair(found("2026-11-01T00:00:00Z", 30), SkyBody.Mars, SkyBody.Jupiter)

        assertNotNull(approach)
        assertEquals(
            Instant.ofEpochMilli(approach!!.atMillis).toString(),
            at("2026-11-16T01:50:43Z") / 1000,
            approach.atMillis / 1000,
        )
        assertEquals(1.193, approach.separationDegrees, 0.005)
    }

    /**
     * The moon within four arcminutes of Venus - close enough to be an
     * occultation somewhere on Earth - with the pair below Edinburgh's horizon
     * at the time. The search must still find it; the verdict is what changes.
     */
    @Test
    fun `finds a near occultation of venus by the moon`() {
        val approach = pair(found("2026-09-01T00:00:00Z", 30), SkyBody.Moon, SkyBody.Venus)

        assertNotNull(approach)
        assertEquals(0.069, approach!!.separationDegrees, 0.005)
    }

    /**
     * The sampling step is six hours, so an unrefined answer would land on a
     * six-hour boundary. Refining is what makes the time worth printing.
     */
    @Test
    fun `refines the moment far below the sampling step`() {
        val approach = pair(found("2026-11-01T00:00:00Z", 30), SkyBody.Mars, SkyBody.Jupiter)!!
        val secondsIntoTheHour = (approach.atMillis / 1000) % 3600

        assertTrue(secondsIntoTheHour.toString(), secondsIntoTheHour != 0L)
    }

    /** Nothing beyond the bar is reported, however interesting it might be. */
    @Test
    fun `reports nothing wider than the bar it was given`() {
        val close = found("2026-08-25T00:00:00Z", 400, within = 1.0)

        assertTrue(close.isNotEmpty())
        assertTrue(close.all { it.separationDegrees <= 1.0 })
    }

    /** A tighter bar can only ever be a subset of a looser one. */
    @Test
    fun `a tighter bar returns a subset of a looser one`() {
        val loose = found("2026-08-25T00:00:00Z", 200, within = 3.0).map { it.atMillis / 1000 }
        val tight = found("2026-08-25T00:00:00Z", 200, within = 1.0).map { it.atMillis / 1000 }

        assertTrue(tight.isNotEmpty())
        assertTrue(loose.containsAll(tight))
    }

    /** A pair is never reported against itself, and never twice per approach. */
    @Test
    fun `reports each approach once`() {
        val all = found("2026-08-25T00:00:00Z", 200)
        val keys = all.map { Triple(it.first, it.second, it.atMillis / 60_000) }

        assertTrue(all.none { it.first == it.second })
        assertEquals(keys.size, keys.toSet().size)
    }

    // --- as it is actually seen ---

    /**
     * The pair that is closest at ten in the morning is not a sight. Whatever
     * the answer is for that night, it cannot be the four-arcminute figure,
     * because nobody at Edinburgh can see it.
     */
    @Test
    fun `reports the separation at the moment the pair is best placed`() {
        val approach = pair(found("2026-09-01T00:00:00Z", 30), SkyBody.Moon, SkyBody.Venus)!!
        val seen = Conjunctions.asSeen(approach, latitude, longitude)

        if (seen != null) {
            assertTrue(
                seen.separationDegrees.toString(),
                seen.separationDegrees > approach.separationDegrees,
            )
        }
    }

    /** Mars and Jupiter are up and well placed, so the seen figure stays close. */
    @Test
    fun `keeps the separation when the pair is up in the dark anyway`() {
        val approach = pair(found("2026-11-01T00:00:00Z", 30), SkyBody.Mars, SkyBody.Jupiter)!!
        val seen = Conjunctions.asSeen(approach, latitude, longitude)

        assertNotNull(seen)
        assertTrue(seen!!.altitude.toString(), seen.altitude > 10.0)
        assertTrue(seen.separationDegrees.toString(), seen.separationDegrees < 2.5)
    }

    /** Inside the arctic circle at midsummer there is no night to see it in. */
    @Test
    fun `sees nothing at midsummer inside the arctic circle`() {
        val approach = Approach(
            atMillis = at("2027-06-21T12:00:00Z"),
            separationDegrees = 0.5,
            first = SkyBody.Venus,
            second = SkyBody.Jupiter,
        )

        assertEquals(null, Conjunctions.asSeen(approach, 78.2, 15.6))
    }
}
