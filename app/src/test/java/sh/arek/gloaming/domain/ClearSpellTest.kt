package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.HourlyCover

/**
 * The gap in the cloud, which is the question a marginal night actually turns
 * on.
 *
 * "80% cloud" over a whole night is the wrong summary of a night that is
 * overcast until midnight and then clear for three hours, and it is the second
 * of those that decides whether anybody goes outside.
 */
class ClearSpellTest {

    private val hour = 60 * 60 * 1000L

    /** Midnight UTC on an arbitrary day, so the arithmetic below reads as hours. */
    private val midnight = 1_800_000_000_000L / hour * hour

    /** One entry per hour from [midnight], reading the percentages in order. */
    private fun hours(vararg total: Int): List<HourlyCover> =
        total.mapIndexed { index, cover -> HourlyCover(midnight + index * hour, cover) }

    private fun at(hourOfDay: Int) = midnight + hourOfDay * hour

    @Test
    fun `finds the first run of hours under the tolerance`() {
        //          00   01   02   03  04  05
        val cloud = hours(100, 100, 100, 10, 20, 90)

        val spell = ClearSpell.first(
            hourly = cloud,
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(6),
            nowMillis = at(0),
        )

        assertEquals(at(3), spell!!.from)
        assertEquals(at(5), spell.until)
    }

    /** A single clear hour at one in the morning is exactly what this is for. */
    @Test
    fun `reports a spell one hour long`() {
        val spell = ClearSpell.first(
            hourly = hours(100, 20, 100),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(3),
            nowMillis = at(0),
        )

        assertEquals(at(1), spell!!.from)
        assertEquals(at(2), spell.until)
    }

    @Test
    fun `reports nothing when every hour is over the tolerance`() {
        val spell = ClearSpell.first(
            hourly = hours(90, 95, 100),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(3),
            nowMillis = at(0),
        )

        assertNull(spell)
    }

    @Test
    fun `reports nothing when there is no forecast at all`() {
        val spell = ClearSpell.first(
            hourly = emptyList(),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(3),
            nowMillis = at(0),
        )

        assertNull(spell)
    }

    /**
     * Only the hours of darkness count. A clear afternoon is not a reason to
     * stay up, and reporting one would be worse than reporting nothing.
     */
    @Test
    fun `ignores clear hours outside the darkness`() {
        val cloud = hours(10, 10, 100, 100)

        val spell = ClearSpell.first(
            hourly = cloud,
            tolerance = 40,
            darkFrom = at(2),
            darkUntil = at(4),
            nowMillis = at(0),
        )

        assertNull(spell)
    }

    /** A spell that started before the darkness did begins when the dark does. */
    @Test
    fun `clips a spell to the start of the darkness`() {
        val spell = ClearSpell.first(
            hourly = hours(10, 10, 10, 100),
            tolerance = 40,
            darkFrom = at(1),
            darkUntil = at(4),
            nowMillis = at(0),
        )

        assertEquals(at(1), spell!!.from)
        assertEquals(at(3), spell.until)
    }

    @Test
    fun `clips a spell to the end of the darkness`() {
        val spell = ClearSpell.first(
            hourly = hours(100, 10, 10, 10),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(3),
            nowMillis = at(0),
        )

        assertEquals(at(1), spell!!.from)
        assertEquals(at(3), spell.until)
    }

    /**
     * Nothing already behind you. An hour into a clear spell, the answer is
     * "it is clear now until 02:00", not "it was clear from 23:00".
     */
    @Test
    fun `starts a spell already underway at now rather than in the past`() {
        val spell = ClearSpell.first(
            hourly = hours(10, 10, 10, 100),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(4),
            nowMillis = at(1) + 30 * 60_000L,
        )

        assertEquals(at(1) + 30 * 60_000L, spell!!.from)
        assertEquals(at(3), spell.until)
        assertTrue(spell.underway)
    }

    @Test
    fun `skips a spell that is entirely behind now`() {
        val spell = ClearSpell.first(
            hourly = hours(10, 100, 100, 10, 100),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(5),
            nowMillis = at(2),
        )

        assertEquals(at(3), spell!!.from)
        assertFalse(spell.underway)
    }

    /**
     * A missing hour breaks the run rather than joining what is either side of
     * it. Open-Meteo pads the ends of an array with nulls where a model has
     * produced nothing, and those hours are dropped before they get here -
     * calling 20:00 and 22:00 a continuous spell would be inventing 21:00.
     */
    @Test
    fun `does not join two runs across a missing hour`() {
        val cloud = listOf(
            HourlyCover(at(0), 10),
            HourlyCover(at(2), 10),
            HourlyCover(at(3), 10),
        )

        val spell = ClearSpell.first(
            hourly = cloud,
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(5),
            nowMillis = at(0),
        )

        assertEquals(at(0), spell!!.from)
        assertEquals(at(1), spell.until)
    }

    /** The whole night under the tolerance is a spell, and says so. */
    @Test
    fun `covers the whole night when every dark hour is clear`() {
        val spell = ClearSpell.first(
            hourly = hours(5, 5, 5, 5),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(4),
            nowMillis = at(0),
        )

        assertEquals(at(0), spell!!.from)
        assertEquals(at(4), spell.until)
        assertTrue(spell.allNight)
    }

    @Test
    fun `does not call a partial spell the whole night`() {
        val spell = ClearSpell.first(
            hourly = hours(100, 5, 5, 100),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(4),
            nowMillis = at(0),
        )

        assertFalse(spell!!.allNight)
    }

    /**
     * The hours are read out of a forecast that may be older than the darkness
     * it is being asked about, and an unordered array would silently produce a
     * spell running backwards.
     */
    @Test
    fun `reads the hours in time order whatever order they arrive in`() {
        val cloud = listOf(
            HourlyCover(at(2), 10),
            HourlyCover(at(0), 100),
            HourlyCover(at(1), 10),
        )

        val spell = ClearSpell.first(
            hourly = cloud,
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(3),
            nowMillis = at(0),
        )

        assertEquals(at(1), spell!!.from)
        assertEquals(at(3), spell.until)
    }

    /** A window with no darkness in it has no spell to report. */
    @Test
    fun `reports nothing when the darkness has already ended`() {
        val spell = ClearSpell.first(
            hourly = hours(5, 5, 5),
            tolerance = 40,
            darkFrom = at(0),
            darkUntil = at(2),
            nowMillis = at(3),
        )

        assertNull(spell)
    }
}
