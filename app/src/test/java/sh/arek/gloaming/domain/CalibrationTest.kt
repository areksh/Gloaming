package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel

/**
 * Turning "was it worth it?" into settings.
 *
 * The rule has to be simple enough to state back to the user in one sentence,
 * because a setting the app changed by itself for reasons it will not explain
 * is one the user can neither trust nor argue with. These pin both halves: the
 * number it arrives at, and that it arrives at the permissive end until there
 * is real evidence to move it.
 */
class CalibrationTest {

    private var clock = 1_800_000_000_000L

    private fun aurora(
        probability: Int,
        answer: Answer,
        cloud: Int = 0,
    ) = Verdict(
        answeredAtMillis = clock++,
        answer = answer,
        conditions = Conditions(
            atMillis = clock,
            channel = VerdictChannel.Aurora,
            probability = probability,
            cloudTotal = cloud,
        ),
    )

    private fun pass(elevation: Double, answer: Answer) = Verdict(
        answeredAtMillis = clock++,
        answer = answer,
        conditions = Conditions(
            atMillis = clock,
            channel = VerdictChannel.Pass,
            peakElevation = elevation,
        ),
    )

    private fun repeat(count: Int, make: () -> Verdict) = List(count) { make() }

    // --- nothing recorded ---

    /**
     * The failure that matters is missing a real aurora; the failure that does
     * not is one look out of a window. So with nothing to go on the app opens
     * every gate it has rather than guessing at a bar.
     */
    @Test
    fun `recommends the widest setting on offer with nothing recorded`() {
        val recommendations = Calibration.of(emptyList())

        assertEquals(UserSettings.THRESHOLD_CHOICES.min(), recommendations.threshold.value)
        assertEquals(
            UserSettings.CLOUD_TOLERANCE_CHOICES.max(),
            recommendations.cloudTolerance.value,
        )
        assertEquals(
            UserSettings.MINIMUM_ELEVATION_CHOICES.min(),
            recommendations.minimumPassElevation.value,
        )
        assertEquals(
            UserSettings.ALERT_INTERVAL_CHOICES.min(),
            recommendations.auroraInterval.value,
        )
    }

    @Test
    fun `says how far off it is from having enough to go on`() {
        val recommendations = Calibration.of(repeat(2) { aurora(50, Answer.Worth) })

        assertTrue(
            recommendations.threshold.reason,
            recommendations.threshold.reason.contains("2 of ${Calibration.MINIMUM_SAMPLE}"),
        )
        assertEquals(2, recommendations.threshold.sample)
    }

    /**
     * The widest setting is the only one with enough nights behind it early on,
     * so the recommendation relaxes before it tightens. That is a property of
     * the rule rather than an accident: a bar should not move on one night.
     */
    @Test
    fun `does not tighten on a single disappointing night`() {
        val verdicts = repeat(5) { aurora(20, Answer.Worth) } + aurora(80, Answer.NotWorth)

        assertEquals(10, Calibration.of(verdicts).threshold.value)
    }

    // --- the aurora threshold ---

    /**
     * Somebody for whom only the strong displays are worth the walk. Alerts in
     * the twenties disappoint; alerts in the fifties do not.
     */
    @Test
    fun `raises the threshold when the low ones are not worth going out for`() {
        val verdicts = repeat(6) { aurora(20, Answer.NotWorth) } +
            repeat(6) { aurora(60, Answer.Worth) }

        // 30, not 50: every alert at 30% or above was worth it here, and the
        // rule takes the widest bar that still pays off rather than the
        // strictest that happens to.
        val threshold = Calibration.of(verdicts).threshold
        assertEquals(30, threshold.value)
        assertTrue(threshold.reason, threshold.reason.contains("worth going out for"))
    }

    /** Somebody with a dark northern horizon, for whom even 20% pays off. */
    @Test
    fun `keeps the threshold low when the low ones are worth it too`() {
        val verdicts = repeat(10) { aurora(15, Answer.Worth) }

        assertEquals(10, Calibration.of(verdicts).threshold.value)
    }

    /**
     * Nothing has paid off at any bar, so the strictest one on offer is the
     * honest answer - and it says that is why, rather than presenting 70% as if
     * it had been derived from a happy band.
     */
    @Test
    fun `goes as strict as it can when nothing has been worth it`() {
        val verdicts = repeat(12) { aurora(90, Answer.NotWorth) }

        val threshold = Calibration.of(verdicts).threshold
        assertEquals(UserSettings.THRESHOLD_CHOICES.max(), threshold.value)
        assertTrue(threshold.reason, threshold.reason.contains("not worth"))
    }

    /**
     * An even split is not evidence. It is evidence the app is right half the
     * time, which is what a coin does - so the bar has to be cleared strictly
     * rather than tied.
     */
    @Test
    fun `does not accept a bar that pays off exactly half the time`() {
        val verdicts = repeat(6) { aurora(15, Answer.Worth) } +
            repeat(6) { aurora(15, Answer.NotWorth) }

        assertEquals(UserSettings.THRESHOLD_CHOICES.max(), Calibration.of(verdicts).threshold.value)
    }

    /**
     * "Did not look" is dropped rather than counted either way. Reading it as a
     * failure would tighten every threshold in the app on the strength of the
     * user having been asleep.
     */
    @Test
    fun `ignores the nights nobody looked`() {
        val slept = repeat(20) { aurora(15, Answer.DidNotLook) }
        val looked = repeat(6) { aurora(15, Answer.Worth) }

        assertEquals(10, Calibration.of(slept + looked).threshold.value)
        assertEquals(6, Calibration.of(slept + looked).threshold.sample)
    }

    // --- the cloud tolerance, which runs the other way ---

    /**
     * A higher cloud tolerance is the permissive end, so the rule walks down
     * from 100 rather than up from 40 - and the answer is the most cloud this
     * person will still put up with.
     */
    @Test
    fun `lowers the cloud tolerance when the cloudy nights disappoint`() {
        val verdicts = repeat(6) { aurora(60, Answer.NotWorth, cloud = 90) } +
            repeat(6) { aurora(60, Answer.Worth, cloud = 30) }

        // 80, not 40: nothing between 30% and 80% cloud has contradicted it,
        // so claiming 40 would be inventing evidence.
        assertEquals(80, Calibration.of(verdicts).cloudTolerance.value)
    }

    @Test
    fun `keeps the cloud tolerance wide when broken cloud still pays off`() {
        val verdicts = repeat(8) { aurora(60, Answer.Worth, cloud = 95) }

        assertEquals(100, Calibration.of(verdicts).cloudTolerance.value)
    }

    // --- the pass elevation bar ---

    /**
     * Somebody in a city with buildings all round. The low passes are never
     * there, the high ones are - and that is a fact about their horizon nobody
     * could have asked them in advance.
     */
    @Test
    fun `raises the pass bar when the low passes cannot be seen`() {
        val verdicts = repeat(8) { pass(6.0, Answer.NotWorth) } +
            repeat(8) { pass(40.0, Answer.Worth) }

        val bar = Calibration.of(verdicts).minimumPassElevation
        assertTrue(bar.toString(), bar.value >= 10)
    }

    @Test
    fun `keeps the pass bar low for a clear horizon`() {
        val verdicts = repeat(8) { pass(7.0, Answer.Worth) }

        assertEquals(5, Calibration.of(verdicts).minimumPassElevation.value)
    }

    /** Aurora verdicts say nothing about a pass bar, and are not counted towards one. */
    @Test
    fun `keeps the channels apart`() {
        val verdicts = repeat(20) { aurora(90, Answer.NotWorth) }

        assertEquals(
            UserSettings.MINIMUM_ELEVATION_CHOICES.min(),
            Calibration.of(verdicts).minimumPassElevation.value,
        )
    }

    // --- how often a channel may interrupt ---

    /**
     * A different question from a threshold and answered differently. Nothing
     * in the record says an alert would have been better an hour later; what it
     * says is how often this channel earned the interruption at all.
     */
    @Test
    fun `lets a channel that keeps earning it interrupt freely`() {
        val verdicts = repeat(8) { aurora(60, Answer.Worth) }

        assertEquals(
            UserSettings.ALERT_INTERVAL_CHOICES.min(),
            Calibration.of(verdicts).auroraInterval.value,
        )
    }

    @Test
    fun `holds back a channel that mostly disappoints`() {
        val verdicts = repeat(8) { aurora(60, Answer.NotWorth) }

        assertEquals(
            UserSettings.ALERT_INTERVAL_CHOICES.max(),
            Calibration.of(verdicts).auroraInterval.value,
        )
    }

    @Test
    fun `counts the pass channel's interval separately from the aurora one`() {
        val verdicts = repeat(8) { aurora(60, Answer.NotWorth) } +
            repeat(8) { pass(50.0, Answer.Worth) }

        val recommendations = Calibration.of(verdicts)
        assertEquals(UserSettings.ALERT_INTERVAL_CHOICES.max(), recommendations.auroraInterval.value)
        assertEquals(UserSettings.ALERT_INTERVAL_CHOICES.min(), recommendations.passInterval.value)
    }

    // --- applying them ---

    /**
     * Composed over the stored settings rather than written into them, which is
     * the whole point of the layer: turning automatic mode off restores exactly
     * what the user had, because it was never overwritten.
     */
    @Test
    fun `applies every calibrated setting and leaves the rest alone`() {
        val stored = UserSettings(
            latitude = 55.95,
            longitude = -3.19,
            threshold = 30,
            cloudTolerance = 80,
            cometMagnitudeThreshold = 4,
            nightVision = true,
        )
        val verdicts = repeat(6) { aurora(20, Answer.NotWorth) } +
            repeat(6) { aurora(60, Answer.Worth) }

        val applied = Calibration.of(verdicts).applyTo(stored)

        assertEquals(30, applied.threshold)
        // Untouched: nothing in the record speaks to a comet's brightness, and
        // making one up would be worse than leaving the user's own choice.
        assertEquals(4, applied.cometMagnitudeThreshold)
        assertTrue(applied.nightVision)
        assertEquals(55.95, applied.latitude!!, 0.0)
    }

    /** All three pass channels take the one figure, because the record has one. */
    @Test
    fun `gives the three pass channels the same interval`() {
        val verdicts = repeat(8) { pass(50.0, Answer.Worth) }

        val applied = Calibration.of(verdicts).applyTo(UserSettings())

        assertEquals(applied.issPassAlertIntervalMinutes, applied.brightPassAlertIntervalMinutes)
        assertEquals(applied.issPassAlertIntervalMinutes, applied.trainPassAlertIntervalMinutes)
    }
}
