package sh.arek.gloaming.data

import kotlin.math.abs

/** One position the device offered, and what it is worth. */
data class DeviceFix(
    val latitude: Double,
    val longitude: Double,
    /** Radius of uncertainty in metres. Zero means the provider did not say. */
    val accuracyMetres: Float,
    val atMillis: Long,
)

/**
 * Where the device thinks it is, when asked.
 *
 * An interface so the Android glue is the only thing that needs a device, and
 * so the screens can be driven from a fixed answer in a test.
 */
interface LocationSource {
    /** The best fix available now, or null if there is not a usable one. */
    suspend fun current(): DeviceFix?

    /**
     * Asks the system for a new approximate fix, waiting at most
     * [timeoutMillis], and returns the best of that and what [current] has.
     * Null when neither is usable, including when the ask timed out.
     */
    suspend fun fresh(timeoutMillis: Long): DeviceFix?
}

/**
 * Picking between the fixes `LocationManager` hands back.
 *
 * It answers per provider and they disagree: the network provider replies
 * instantly and coarsely, GPS replies precisely and may be hours stale because
 * the phone has been indoors. Neither "most recent" nor "most accurate" alone
 * is right, so freshness is a gate and accuracy is the ranking within it.
 */
object LocationChoice {

    /**
     * How old a fix may be and still describe where somebody is standing.
     *
     * Half an hour. The failure this prevents is specific and likely for this
     * app: somebody drives out of town to a dark site, opens Gloaming, and is
     * handed a precise fix of the house they left - which would put the aurora
     * gate, the cloud forecast and every pass prediction in the wrong place
     * while looking entirely confident.
     */
    const val TOO_OLD_MILLIS: Long = 30 * 60 * 1000L

    /**
     * How vague a fix may be and still be worth having.
     *
     * Fifty kilometres is already half an aurora grid cell, and past this the
     * typed-in answer the user could give is better than the one the device is
     * offering.
     */
    const val TOO_VAGUE_METRES: Float = 50_000f

    /**
     * How long a background ask for a fresh fix is given before it is dropped.
     *
     * Twenty seconds: a network fix that has not come by then is not coming,
     * and the poll that asked has a budget of sixty seconds to share.
     */
    const val FRESH_TIMEOUT_MILLIS: Long = 20_000L

    /** Used to rank a provider that declined to say how good its fix is. */
    private const val UNKNOWN_ACCURACY_METRES: Float = TOO_VAGUE_METRES

    fun best(fixes: List<DeviceFix>, nowMillis: Long): DeviceFix? = fixes
        .filter { it.isPlausible() && it.isFreshAt(nowMillis) && it.accuracyMetres < TOO_VAGUE_METRES }
        .minWithOrNull(compareBy({ it.rank() }, { -it.atMillis }))

    /**
     * A fix dated ahead of now is a clock correction rather than a better
     * answer, so it is aged rather than trusted.
     */
    private fun DeviceFix.isFreshAt(nowMillis: Long): Boolean =
        abs(nowMillis - atMillis) <= TOO_OLD_MILLIS

    /**
     * Zero accuracy means the provider did not say, not that it is perfect.
     * Ranking it as perfect would have an unlabelled fix beat a good one.
     */
    private fun DeviceFix.rank(): Float =
        if (accuracyMetres <= 0f) UNKNOWN_ACCURACY_METRES else accuracyMetres

    /**
     * Null island is where a broken fix lands. Refusing it costs a real user on
     * that exact spot in the Gulf of Guinea nothing but a typed-in position.
     */
    private fun DeviceFix.isPlausible(): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
