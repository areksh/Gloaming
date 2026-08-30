package sh.arek.gloaming.ui

import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel

/**
 * The record of "was it worth it?" answers, as the settings screen says it back.
 *
 * The app has been collecting these since the buttons were added and has only
 * ever shown their consequences - a threshold moved, a sentence saying why.
 * That is the wrong way round for a record of somebody's own nights: a
 * recommendation you cannot see the evidence for is one you can only take on
 * trust, which is precisely what the automatic settings were written not to ask
 * for.
 *
 * Pure functions over a list, like everything else in `Descriptions.kt`, so the
 * wording can be argued with on the JVM.
 */

/** One channel's answers, counted. */
data class VerdictTally(
    val channel: VerdictChannel,
    val worth: Int,
    val notWorth: Int,
    val didNotLook: Int,
) {
    val total: Int get() = worth + notWorth + didNotLook
}

/**
 * The record, counted by channel, in the order the channels are declared.
 *
 * A channel nothing has been answered about is left out rather than shown as
 * three zeroes: an empty row says "you have been asked about this and had
 * nothing to say", which is not what an absence means.
 */
fun tallyVerdicts(verdicts: List<Verdict>): List<VerdictTally> =
    VerdictChannel.entries.mapNotNull { channel ->
        val mine = verdicts.filter { it.conditions.channel == channel }
        if (mine.isEmpty()) {
            null
        } else {
            VerdictTally(
                channel = channel,
                worth = mine.count { it.answer == Answer.Worth },
                notWorth = mine.count { it.answer == Answer.NotWorth },
                didNotLook = mine.count { it.answer == Answer.DidNotLook },
            )
        }
    }

/**
 * How a channel has gone, in one line.
 *
 * "Did not look" is reported as its own outcome rather than folded into the
 * failures, for the reason [Answer.DidNotLook] exists at all: somebody who
 * slept through an alert has not judged it, and counting them against the app
 * would make every well-timed overnight alert look like a mistake.
 */
fun describe(tally: VerdictTally): String {
    val alerts = if (tally.total == 1) "1 alert" else "${tally.total} alerts"
    val worth = if (tally.worth == 0) {
        "none worth going out for"
    } else {
        "${tally.worth} worth going out for"
    }
    val parts = buildList {
        add(worth)
        if (tally.notWorth > 0) add("${tally.notWorth} not")
        if (tally.didNotLook > 0) add("${tally.didNotLook} you did not look at")
    }
    return "$alerts: ${parts.joinToString(", ")}"
}

/**
 * What the sky was doing when an alert went out, in one line.
 *
 * Says only what it was given. A pass alert records no Kp and an event records
 * neither, and a sentence that filled the gaps with zeroes or dashes would be
 * inventing readings for nights that had none.
 */
fun describe(conditions: Conditions): String {
    val parts = buildList {
        conditions.probability?.let { probability ->
            add("$probability%" + (conditions.source?.let { " from ${sourceName(it)}" } ?: ""))
        }
        conditions.kp?.let { add("Kp ${trim(it)}") }
        conditions.bz?.let { add("Bz ${trim(it)}") }
        conditions.peakElevation?.let { add("${Math.round(it)} degrees up") }
        conditions.magnitude?.let { add("magnitude ${trim(it)}") }
        conditions.cloudTotal?.let { add("$it% cloud") }
        conditions.threshold?.let { add("threshold $it") }
    }
    val subject = conditions.subject
    return when {
        subject != null && parts.isEmpty() -> subject
        subject != null -> "$subject, ${parts.joinToString(", ")}"
        parts.isEmpty() -> "Nothing was recorded about it"
        else -> parts.joinToString(", ")
    }
}

/**
 * The most recent answers, newest first.
 *
 * The file is appended to and capped from the old end, so the newest are last
 * in it and first here. Worth its own function only because that is exactly the
 * sort of thing that ends up reversed on a screen nobody rereads.
 */
fun newestFirst(verdicts: List<Verdict>, limit: Int = Int.MAX_VALUE): List<Verdict> =
    verdicts.sortedByDescending { it.answeredAtMillis }.take(limit)

/**
 * The channel's name as the rest of the settings screen writes it.
 *
 * Kept here rather than spelled out in the card, so that a record headed "Pass"
 * beside a control headed "Satellite passes" is a test failure rather than
 * something a reader has to work out is the same feature.
 */
fun name(channel: VerdictChannel): String = when (channel) {
    VerdictChannel.Aurora -> "Aurora"
    VerdictChannel.Pass -> "Satellite passes"
    VerdictChannel.Event -> "Sky calendar"
}

/** Their name for the reading, not the enum's. */
private fun sourceName(source: String): String = when (source) {
    "AURORAWATCH" -> "AuroraWatch UK"
    "OVATION" -> "the global grid"
    else -> source
}

/** 5.30 reads as an instrument's precision it does not have; 5.3 does not. */
private fun trim(value: Double): String =
    if (value == Math.rint(value)) "${value.toInt()}" else "$value"
