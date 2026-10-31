package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.arek.gloaming.astronomy.Darkness

/**
 * That the app says why it is not checking.
 *
 * The adaptive schedule sleeps through daylight, which at a high latitude in
 * summer is most of a day - twelve hours in the dormant case. A screen that
 * shows only "checked 11 hours ago" makes correct behaviour look like a broken
 * background service, and the first thing anybody does about a broken
 * background service is start turning settings off.
 */
class ScheduleTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** A fixed zone, so the expected wording does not depend on the machine. */
    private val utcMinuteOfDay: (Long) -> Int =
        { ((it / minute) % (24 * 60)).toInt() }

    @Test
    fun `says it is watching when it is dark`() {
        val text = describeSchedule(Darkness.Now(endsAt = null), utcMinuteOfDay)

        assertEquals("Dark now, so Gloaming is watching.", text)
    }

    /**
     * The important one. This is the state the app spends most of a summer day
     * in, and the sentence has to make a long gap read as intended.
     */
    @Test
    fun `names the time it will next look when the sun is up`() {
        val duskAt = 22 * hour + 40 * minute

        val text = describeSchedule(Darkness.Later(startsAt = duskAt, endsAt = null), utcMinuteOfDay)

        assertEquals("Sun is up. Nothing to see until it gets dark, around 22:40.", text)
    }

    /**
     * A separate answer from "later today", because it is a different situation
     * and lasts for weeks rather than hours.
     */
    @Test
    fun `explains a season with no darkness in it at all`() {
        val text = describeSchedule(Darkness.None, utcMinuteOfDay)

        assertEquals(
            "It does not get dark here at this time of year. " +
                "Gloaming checks twice a day until that changes.",
            text,
        )
    }

    // --- the status line at the head of Tonight ---

    /**
     * The age and the schedule in one line, because they are read together: a
     * long gap is only alarming until the second half explains it.
     */
    @Test
    fun `puts the age and the schedule in one line`() {
        val text = describeStatus(
            lastPollAt = 100 * hour,
            now = 100 * hour + 6 * minute,
            darkness = Darkness.Later(startsAt = 20 * hour + 40 * minute, endsAt = null),
            localMinuteOfDay = utcMinuteOfDay,
        )

        assertEquals(
            "Checked 6 minutes ago. Sun is up. Nothing to see until it gets dark, around 20:40.",
            text,
        )
    }

    /** Without a location there is no darkness to describe, and no half-sentence. */
    @Test
    fun `gives only the age when there is no darkness to describe`() {
        assertEquals(
            "Not checked yet.",
            describeStatus(lastPollAt = null, now = hour, darkness = null, localMinuteOfDay = utcMinuteOfDay),
        )
    }
}
