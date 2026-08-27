package sh.arek.gloaming.astronomy

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moon passing in front of a planet, rather than merely near it.
 *
 * The conjunction machinery has found these all along and reported every one as
 * "passes close to", which for an occultation is a considerable understatement:
 * a planet goes out at the moon's edge in a second or two, with no fading, and
 * comes back an hour later somewhere else on the limb. It is one of the very
 * few naked-eye events that looks like something happening.
 *
 * **Asserted on the geometry rather than on the label.** The calendar's own
 * verdict is what is being tested, so taking its word for whether an occultation
 * occurred would be no test at all. What is checked is that at the moment the
 * calendar names, the two discs really do overlap - and that a minute either
 * side of the times it gives, they do not.
 */
class OccultationTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val sydney = -33.87 to 151.21
    private val edinburgh = 55.95 to -3.19

    private fun events(place: Pair<Double, Double>, from: String, days: Int) =
        SkyCalendar.events(place.first, place.second, at(from), days)

    private fun separation(other: SkyBody, place: Pair<Double, Double>, atMillis: Long) =
        Conjunctions.at(SkyBody.Moon, other, place.first, place.second, atMillis).separationDegrees

    private fun discs(other: SkyBody, place: Pair<Double, Double>, atMillis: Long) =
        AngularSize.semiDiameterDegrees(SkyBody.Moon, place.first, place.second, atMillis) +
            AngularSize.semiDiameterDegrees(other, place.first, place.second, atMillis)

    /**
     * The moon passes in front of Jupiter over Sydney on the morning of
     * 19 March 2027, with the pair 30 degrees up in the north-east.
     */
    @Test
    fun `finds an occultation of jupiter and calls it one`() {
        val found = events(sydney, "2027-03-01T00:00:00Z", 40)
            .filter { it.kind == EventKind.Occultation }

        val occultation = found.singleOrNull()
        assertNotNull(found.toString(), occultation)
        assertEquals("The moon passes in front of Jupiter", occultation!!.title)
        assertTrue(
            Instant.ofEpochMilli(occultation.at).toString(),
            occultation.at in at("2027-03-19T00:00:00Z")..at("2027-03-20T00:00:00Z"),
        )
    }

    /** The discs really do overlap at the moment the calendar names. */
    @Test
    fun `puts the discs on top of each other at the moment it names`() {
        val occultation = events(sydney, "2027-03-01T00:00:00Z", 40)
            .single { it.kind == EventKind.Occultation }

        val apart = separation(SkyBody.Jupiter, sydney, occultation.at)
        val touching = discs(SkyBody.Jupiter, sydney, occultation.at)

        assertTrue("$apart degrees apart, discs touch at $touching", apart < touching)
    }

    /**
     * And do not, a minute outside the times it gives. This is the assertion
     * that makes the two times worth printing rather than merely present.
     */
    @Test
    fun `has the discs apart a minute either side of the times it gives`() {
        val occultation = events(sydney, "2027-03-01T00:00:00Z", 40)
            .single { it.kind == EventKind.Occultation }

        for (moment in listOf(occultation.from - 60_000L, occultation.until + 60_000L)) {
            val apart = separation(SkyBody.Jupiter, sydney, moment)
            val touching = discs(SkyBody.Jupiter, sydney, moment)
            assertTrue(
                "at ${Instant.ofEpochMilli(moment)}: $apart apart, discs touch at $touching",
                apart > touching,
            )
        }
    }

    @Test
    fun `brackets the closest moment with the two crossings`() {
        val occultation = events(sydney, "2027-03-01T00:00:00Z", 40)
            .single { it.kind == EventKind.Occultation }

        assertTrue(occultation.from < occultation.at)
        assertTrue(occultation.until > occultation.at)
        // Under two hours: the moon crosses its own diameter in about one.
        assertTrue(
            "${occultation.until - occultation.from} ms",
            occultation.until - occultation.from < 2 * 60 * 60_000L,
        )
    }

    /** Both times are named, because the reappearance is half the event. */
    @Test
    fun `names both the disappearance and the reappearance`() {
        val occultation = events(sydney, "2027-03-01T00:00:00Z", 40)
            .single { it.kind == EventKind.Occultation }

        assertTrue(occultation.detail, occultation.detail.contains("disappears behind the moon"))
        assertTrue(occultation.detail, occultation.detail.contains("reappears"))
    }

    /**
     * The near miss that is not one, and by far the commoner case.
     * `ConjunctionsTest` pins the moon within 0.07 degrees of Venus on
     * 14 September 2026 - close enough to be an occultation somewhere on Earth,
     * and from Edinburgh the pair is below the horizon at the time.
     */
    @Test
    fun `does not call a near miss an occultation`() {
        val found = events(edinburgh, "2026-09-01T00:00:00Z", 30)

        assertTrue(
            found.filter { it.kind == EventKind.Occultation }.toString(),
            found.none { it.kind == EventKind.Occultation },
        )
    }

    /**
     * It replaces the conjunction rather than joining it. Two entries about one
     * pairing, one of them describing it as merely close, is worse than either
     * alone.
     */
    @Test
    fun `replaces the conjunction rather than adding to it`() {
        val onTheNight = events(sydney, "2027-03-01T00:00:00Z", 40)
            .filter { it.at in at("2027-03-19T00:00:00Z")..at("2027-03-20T00:00:00Z") }
            .filter { it.kind == EventKind.Occultation || it.kind == EventKind.Conjunction }

        assertEquals(onTheNight.toString(), 1, onTheNight.size)
        assertEquals(EventKind.Occultation, onTheNight.single().kind)
    }

    /**
     * On the channel the conjunction would have used, so somebody who wants to
     * hear about the moon passing Jupiter hears about it passing in front of
     * Jupiter without opting into anything new.
     */
    @Test
    fun `speaks on the conjunctions channel`() {
        assertEquals(
            EventChannel.of(EventKind.Conjunction),
            EventChannel.of(EventKind.Occultation),
        )
    }

    /**
     * Strictly local, which is the whole reason occultation predictions are
     * published per site: the moon has up to a degree of parallax, about two of
     * its own diameters, so two observers far enough apart genuinely disagree
     * about whether one happened at all.
     */
    @Test
    fun `answers for where the observer stands`() {
        val here = events(edinburgh, "2027-03-01T00:00:00Z", 40)
            .count { it.kind == EventKind.Occultation }
        val elsewhere = events(sydney, "2027-03-01T00:00:00Z", 40)
            .count { it.kind == EventKind.Occultation }

        assertFalse("both places cannot see the same occultations", here == elsewhere)
    }
}
