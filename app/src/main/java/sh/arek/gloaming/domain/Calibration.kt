package sh.arek.gloaming.domain

import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel

/**
 * One setting, as the record suggests it should be.
 *
 * The reason is not decoration. A setting the app changed by itself, for
 * reasons it will not explain, is a setting the user cannot trust or argue
 * with - so every recommendation carries the sentence the settings screen says
 * back, and the number of nights it rests on.
 */
data class Recommendation(
    val value: Int,
    /** How many usable verdicts stand behind it. Zero is the honest starting point. */
    val sample: Int,
    val reason: String,
)

/** Every setting the record can speak to. */
data class Recommendations(
    val threshold: Recommendation,
    val cloudTolerance: Recommendation,
    val minimumPassElevation: Recommendation,
    val auroraInterval: Recommendation,
    val passInterval: Recommendation,
) {
    /**
     * The settings as automatic mode would have them.
     *
     * Composed over the stored settings rather than written into them, which is
     * the whole point: turning automatic mode off restores exactly what the
     * user had, because it was never overwritten.
     */
    fun applyTo(settings: UserSettings): UserSettings = settings.copy(
        threshold = threshold.value,
        cloudTolerance = cloudTolerance.value,
        minimumPassElevationDegrees = minimumPassElevation.value,
        auroraAlertIntervalMinutes = auroraInterval.value,
        // One figure for all three pass channels. The verdict record does not
        // say which channel a pass alert came from - the notification carries
        // the satellite's name, not its channel - so claiming three separate
        // recommendations would be inventing two of them.
        issPassAlertIntervalMinutes = passInterval.value,
        brightPassAlertIntervalMinutes = passInterval.value,
        trainPassAlertIntervalMinutes = passInterval.value,
    )
}

/**
 * Turns "was it worth it?" into settings.
 *
 * `docs/sourcing and constraints.md` Sec. 15 asks for exactly this and no more:
 * real values for the alert thresholds, validated against a season rather than
 * guessed. Everything here is arithmetic over a list of verdicts - no clock, no
 * storage, no Android - which is what lets the rule be argued with rather than
 * merely trusted.
 *
 * **Deliberately simple, and explainable.** The settings screen has to state
 * the reasoning back to the user in one sentence, so a rule that cannot be
 * written in one sentence is the wrong rule however well it fits. What this
 * does is find the most permissive setting at which most alerts still turned
 * out to be worth going out for.
 *
 * **Permissive until there is evidence.** With nothing recorded it recommends
 * the widest setting each control offers, and tightens only as verdicts arrive.
 * The failure that matters is missing a real aurora; the failure that does not
 * is one look out of a window.
 */
object Calibration {

    /**
     * How the recorded value relates to the setting.
     *
     * [AtLeast]: the alert fired because the recorded value cleared the
     * setting, so a lower setting is more permissive - a probability threshold,
     * an elevation bar.
     *
     * [AtMost]: the alert fired because the recorded value stayed under the
     * setting, so a higher setting is more permissive - a cloud tolerance.
     */
    enum class Direction { AtLeast, AtMost }

    /**
     * How many verdicts a candidate needs before it may be chosen.
     *
     * Five. Low enough that a fortnight of clear weather says something, high
     * enough that one disappointing night does not move a threshold by twenty
     * points. Below it the permissive answer stands - which is also why the
     * early recommendations relax rather than tighten: only the widest setting
     * has enough nights behind it to begin with.
     */
    const val MINIMUM_SAMPLE: Int = 5

    /**
     * More than half of them worth it, and strictly more.
     *
     * An even split is not evidence that a setting is worth having - it is
     * evidence that the app is right half the time, which is what a coin does.
     */
    private const val WORTH_SHARE = 0.5

    fun of(verdicts: List<Verdict>): Recommendations = Recommendations(
        threshold = band(
            verdicts = verdicts.forChannel(VerdictChannel.Aurora),
            choices = UserSettings.THRESHOLD_CHOICES,
            direction = Direction.AtLeast,
            noun = "alerts",
            unit = "%",
            value = { it.probability?.toDouble() },
        ),
        cloudTolerance = band(
            verdicts = verdicts.forChannel(VerdictChannel.Aurora),
            choices = UserSettings.CLOUD_TOLERANCE_CHOICES,
            direction = Direction.AtMost,
            noun = "alerts",
            unit = "% cloud",
            value = { it.cloudTotal?.toDouble() },
        ),
        minimumPassElevation = band(
            verdicts = verdicts.forChannel(VerdictChannel.Pass),
            choices = UserSettings.MINIMUM_ELEVATION_CHOICES,
            direction = Direction.AtLeast,
            noun = "passes",
            unit = " degrees",
            value = { it.peakElevation },
        ),
        auroraInterval = interval(verdicts.forChannel(VerdictChannel.Aurora), "aurora alerts"),
        passInterval = interval(verdicts.forChannel(VerdictChannel.Pass), "pass alerts"),
    )

    /**
     * Only the verdicts that are evidence.
     *
     * "Did not look" is dropped rather than counted either way. It is the
     * honest answer for a night somebody slept through, and reading it as a
     * failure would tighten every threshold in the app on the strength of the
     * user having been asleep - which is the single easiest way to draw the
     * wrong conclusion from this record.
     */
    private fun List<Verdict>.forChannel(channel: VerdictChannel) =
        filter { it.conditions.channel == channel && it.answer != Answer.DidNotLook }

    /**
     * The most permissive setting at which most alerts were still worth it.
     *
     * Walked from the permissive end, so the answer is the widest one that
     * clears the bar rather than the strictest that happens to. A candidate
     * with too few nights behind it is skipped, not failed - it has said
     * nothing yet.
     */
    private fun band(
        verdicts: List<Verdict>,
        choices: List<Int>,
        direction: Direction,
        noun: String,
        unit: String,
        value: (Conditions) -> Double?,
    ): Recommendation {
        val usable = verdicts.filter { value(it.conditions) != null }
        val ordered = when (direction) {
            Direction.AtLeast -> choices.sorted()
            Direction.AtMost -> choices.sortedDescending()
        }
        val widest = ordered.first()

        if (usable.size < MINIMUM_SAMPLE) {
            return Recommendation(
                value = widest,
                sample = usable.size,
                reason = "Not enough nights to go on yet, so this is set as wide as it goes. " +
                    "${usable.size} of ${MINIMUM_SAMPLE} recorded.",
            )
        }

        for (candidate in ordered) {
            val band = usable.filter { passes(value(it.conditions)!!, candidate, direction) }
            if (band.size < MINIMUM_SAMPLE) continue

            val worth = band.count { it.answer == Answer.Worth }
            if (worth.toDouble() / band.size > WORTH_SHARE) {
                return Recommendation(
                    value = candidate,
                    sample = band.size,
                    reason = "Of ${band.size} $noun at $candidate$unit or " +
                        "${permissiveWord(direction)}, $worth were worth going out for.",
                )
            }
        }

        // Nothing cleared the bar: every band was mostly disappointing. The
        // strictest setting on offer is the honest answer, and saying why
        // matters - somebody looking at a bar of 70% deserves to know it came
        // from their own nights rather than from an arbitrary default.
        val strictest = ordered.last()
        return Recommendation(
            value = strictest,
            sample = usable.size,
            reason = "Most $noun so far were not worth going out for, so this is set as " +
                "strict as it goes. ${usable.size} recorded.",
        )
    }

    private fun passes(recorded: Double, candidate: Int, direction: Direction) =
        when (direction) {
            Direction.AtLeast -> recorded >= candidate
            Direction.AtMost -> recorded <= candidate
        }

    private fun permissiveWord(direction: Direction) =
        if (direction == Direction.AtLeast) "above" else "under"

    /**
     * How often a channel may interrupt, from how often it was worth it.
     *
     * A different question from the bands above, and answered differently.
     * There is nothing in the record that says "this alert would have been
     * better an hour later" - what there is, is how often this channel earned
     * the interruption at all, and that is exactly what an interval is for.
     *
     * The shortest gap is the permissive end, because a shorter gap means more
     * alerts.
     */
    private fun interval(verdicts: List<Verdict>, noun: String): Recommendation {
        val choices = UserSettings.ALERT_INTERVAL_CHOICES.sorted()

        if (verdicts.size < MINIMUM_SAMPLE) {
            return Recommendation(
                value = choices.first(),
                sample = verdicts.size,
                reason = "Not enough nights to go on yet, so $noun may come as often as " +
                    "they like. ${verdicts.size} of ${MINIMUM_SAMPLE} recorded.",
            )
        }

        val worth = verdicts.count { it.answer == Answer.Worth }
        val share = worth.toDouble() / verdicts.size
        val index = when {
            share >= 0.75 -> 0
            share >= 0.5 -> 1
            share >= 0.25 -> 2
            else -> choices.lastIndex
        }.coerceAtMost(choices.lastIndex)

        return Recommendation(
            value = choices[index],
            sample = verdicts.size,
            reason = "$worth of ${verdicts.size} $noun were worth going out for.",
        )
    }
}
