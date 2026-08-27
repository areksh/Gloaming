package sh.arek.gloaming.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What somebody said about an alert the app sent them. */
enum class Answer(val label: String) {
    /** Worth going outside for. */
    Worth("Worth it"),

    /** Went out, and it was not. */
    NotWorth("Not worth it"),

    /**
     * Never looked, which is neither of the above and must not be counted as
     * either.
     *
     * Without this the two buttons quietly become one: somebody who slept
     * through the alert has no honest answer, so they press nothing, and the
     * alerts that go unanswered are exactly the ones a naive count would read
     * as failures.
     */
    DidNotLook("Did not look"),
}

/** Which kind of alert is being judged. */
enum class VerdictChannel {
    Aurora,
    Pass,
    Event,
}

/**
 * Everything about the sky at the moment an alert went out.
 *
 * Recorded with the verdict rather than looked up afterwards, and that is the
 * whole design: Kp and Bz are read from a document that is replaced every few
 * minutes and are persisted nowhere at all, so by the time somebody answers
 * "was it worth it?" the conditions that prompted the alert no longer exist
 * anywhere. They have to travel with the question.
 *
 * Which also settles how. `FLAG_IMMUTABLE` is required from Android 12 and
 * forbids the receiver filling anything in, so every field here is baked into
 * the notification's action at the moment it is built.
 */
@Serializable
data class Conditions(
    /** When the alert went out. */
    val atMillis: Long,
    val channel: VerdictChannel,
    /** Local aurora probability, for an aurora alert. */
    val probability: Int? = null,
    /** Which reading said so: the global grid, or AuroraWatch's magnetometers. */
    val source: String? = null,
    val kp: Double? = null,
    val bz: Double? = null,
    val cloudTotal: Int? = null,
    val cloudLow: Int? = null,
    val cloudMid: Int? = null,
    val cloudHigh: Int? = null,
    /** The threshold in force when the alert went out, not the one in force now. */
    val threshold: Int? = null,
    /** How high the pass got, for a pass alert. */
    val peakElevation: Double? = null,
    /** The comet's magnitude, for a comet. */
    val magnitude: Double? = null,
    /** How much of the night the moon spoiled, for anything faint. */
    val moonInterference: Double? = null,
    /** What was being judged, so a log line reads as English. */
    val subject: String? = null,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        /**
         * Never throws. This is decoded from a notification action that may
         * have been built by a previous version of the app and sat in the
         * shade for a week, and a malformed one is a verdict not recorded
         * rather than a crash in a broadcast receiver.
         */
        fun decode(encoded: String?): Conditions? = encoded?.let {
            runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull()
        }

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** One answer, with the sky it was an answer about. */
@Serializable
data class Verdict(
    /** When the answer was given, which is not when the alert went out. */
    val answeredAtMillis: Long,
    val answer: Answer,
    val conditions: Conditions,
)

/**
 * Where the verdicts are kept.
 *
 * An interface with one implementation, matching every other store here, so
 * the arithmetic that reads them can be tested without a filesystem.
 */
interface VerdictLog {
    fun read(): List<Verdict>
    fun append(verdict: Verdict)
    fun clear()
}
