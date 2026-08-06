package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.KpReading
import sh.arek.gloaming.data.SkyStatus
import sh.arek.gloaming.data.SolarWind

class PollingPolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun status(kp: Double? = 1.0, bz: Double? = -1.0) = SkyStatus(
        schema = 1,
        generated = "g",
        kp = kp?.let { KpReading(it, "t") },
        solarWind = bz?.let { SolarWind(bz = it, bt = 5.0, speed = 400.0, time = "t") },
    )

    private fun plan(
        darkness: Darkness,
        cloud: CloudCover? = null,
        status: SkyStatus? = status(),
    ) = PollingPolicy.next(darkness, now, cloud, status)

    @Test
    fun `polls quickly when it is dark, clear and the wind has turned south`() {
        val plan = plan(Darkness.Now(endsAt = null), status = status(bz = -8.0))

        assertEquals(PollingPolicy.Reason.ELEVATED, plan.reason)
        assertEquals(PollingPolicy.ELEVATED_MINUTES, plan.delayMinutes)
    }

    @Test
    fun `polls quickly when Kp is elevated`() {
        val plan = plan(Darkness.Now(endsAt = null), status = status(kp = 5.0))

        assertEquals(PollingPolicy.Reason.ELEVATED, plan.reason)
    }

    /**
     * Bz is the leading indicator the whole design rests on. DSCOVR sits an
     * hour upstream at L1, so a southward turn is warning of a substorm that
     * has not started yet - which is why it escalates on its own, without
     * waiting for Kp to catch up.
     */
    @Test
    fun `does not escalate on a northward or trivially southward field`() {
        assertEquals(PollingPolicy.Reason.QUIET, plan(Darkness.Now(null), status = status(bz = 4.0)).reason)
        assertEquals(PollingPolicy.Reason.QUIET, plan(Darkness.Now(null), status = status(bz = -2.0)).reason)
    }

    @Test
    fun `polls at the quiet rate when it is dark and nothing is happening`() {
        val plan = plan(Darkness.Now(endsAt = null))

        assertEquals(PollingPolicy.Reason.QUIET, plan.reason)
        assertEquals(PollingPolicy.QUIET_MINUTES, plan.delayMinutes)
    }

    @Test
    fun `slows down under heavy cloud`() {
        val plan = plan(Darkness.Now(null), cloud = CloudCover(95, 95, 0, 0))

        assertEquals(PollingPolicy.Reason.OVERCAST, plan.reason)
        assertEquals(PollingPolicy.OVERCAST_MINUTES, plan.delayMinutes)
    }

    // Broken cloud is not overcast: the aurora is bright enough to work through
    // gaps, and this rate governs whether the app is watching at all.
    @Test
    fun `does not slow down for broken cloud`() {
        assertEquals(
            PollingPolicy.Reason.QUIET,
            plan(Darkness.Now(null), cloud = CloudCover(70, 70, 0, 0)).reason,
        )
    }

    /**
     * Cloud is only known when a recent poll happened to fetch it, so an
     * unknown sky must fall through to the clear rate. Erring towards polling
     * more often is the right way to be wrong here.
     */
    @Test
    fun `treats unknown cloud as worth watching`() {
        assertEquals(PollingPolicy.Reason.QUIET, plan(Darkness.Now(null), cloud = null).reason)
    }

    /**
     * Following the Sec. 9.3 table: a sky you cannot see through is not worth
     * polling fast, whatever the wind is doing.
     */
    @Test
    fun `stays slow under heavy cloud even during a storm`() {
        val plan = plan(
            Darkness.Now(null),
            cloud = CloudCover(100, 100, 0, 0),
            status = status(kp = 7.0, bz = -15.0),
        )

        assertEquals(PollingPolicy.Reason.OVERCAST, plan.reason)
    }

    /**
     * The single largest saving in the design: while the sun is up there is
     * nothing any amount of polling could discover, and the time it comes down
     * is known days ahead without touching the network.
     */
    @Test
    fun `sleeps until dusk rather than polling through the day`() {
        val duskInSevenHours = now + 7 * 60 * minute

        val plan = plan(Darkness.Later(startsAt = duskInSevenHours, endsAt = null))

        assertEquals(PollingPolicy.Reason.DAYLIGHT, plan.reason)
        assertEquals(7 * 60, plan.delayMinutes)
    }

    @Test
    fun `wakes at dusk rather than a moment before it`() {
        val plan = plan(Darkness.Later(startsAt = now + 90 * minute, endsAt = null))

        assertEquals(90, plan.delayMinutes)
    }

    // A dusk already past, or one seconds away, must still produce a delay the
    // alarm manager can accept rather than zero or a negative.
    @Test
    fun `never schedules a wake in the past`() {
        val plan = plan(Darkness.Later(startsAt = now - 5 * minute, endsAt = null))

        assertTrue("${plan.delayMinutes}", plan.delayMinutes >= 1)
    }

    /**
     * The midnight sun. Nothing gets dark enough to see an aurora for weeks, so
     * the app checks back twice a day to find out when that changes, rather
     * than either polling all summer or sleeping through the end of it.
     */
    @Test
    fun `goes dormant under the midnight sun`() {
        val plan = plan(Darkness.None)

        assertEquals(PollingPolicy.Reason.DORMANT, plan.reason)
        assertEquals(PollingPolicy.DORMANT_MINUTES, plan.delayMinutes)
    }

    @Test
    fun `never sleeps longer than a dormant season would`() {
        val plan = plan(Darkness.Later(startsAt = now + 40L * 24 * 60 * minute, endsAt = null))

        assertEquals(PollingPolicy.DORMANT_MINUTES, plan.delayMinutes)
    }

    // A status document the app could not fetch says nothing about the sun,
    // and a quiet rate is the safe reading of no information.
    @Test
    fun `polls at the quiet rate when there is no status to read`() {
        assertEquals(PollingPolicy.Reason.QUIET, plan(Darkness.Now(null), status = null).reason)
    }

    @Test
    fun `polls at the quiet rate when the status carries no wind reading`() {
        val plan = plan(Darkness.Now(null), status = status(kp = null, bz = null))

        assertEquals(PollingPolicy.Reason.QUIET, plan.reason)
    }

    /**
     * Every rate has to survive the round trip through the alarm the scheduler
     * actually sets, which is inexact and jittered. A zero or negative here
     * would arm an alarm for the past.
     */
    @Test
    fun `every rate is a delay an alarm can be set for`() {
        val plans = listOf(
            plan(Darkness.Now(null), status = status(bz = -9.0)),
            plan(Darkness.Now(null)),
            plan(Darkness.Now(null), cloud = CloudCover(100, 100, 0, 0)),
            plan(Darkness.Later(now + minute, null)),
            plan(Darkness.None),
        )

        assertTrue(plans.all { it.delayMinutes >= 1 })
    }
}
