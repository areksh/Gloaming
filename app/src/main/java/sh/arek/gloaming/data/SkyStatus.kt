package sh.arek.gloaming.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The small, latency-critical document every device polls.
 *
 * Deliberately about half a kilobyte: it is fetched far more often than
 * anything else, and it carries just enough for a device to decide whether it
 * needs the much larger grid at all.
 */
@Serializable
data class SkyStatus(
    val schema: Int,
    /** When the Worker composed this document. */
    val generated: String,
    /**
     * Coarse global activity, used to skip fetching the grid entirely when
     * there is nothing anywhere worth looking at.
     */
    @SerialName("global_activity") val globalActivity: String = ACTIVITY_UNKNOWN,
    /** Peak cell anywhere on the globe, so a device can apply its own cut. */
    @SerialName("max_probability") val maxProbability: Int? = null,
    /** ETag of the current grid; a change is what prompts a re-fetch. */
    @SerialName("oval_etag") val ovalEtag: String? = null,
    /** When SWPC observed the grid, as distinct from when it was republished. */
    @SerialName("oval_observed") val ovalObserved: String? = null,
    @SerialName("oval_forecast") val ovalForecast: String? = null,
    @SerialName("space_updated") val spaceUpdated: String? = null,
    val kp: KpReading? = null,
    @SerialName("solar_wind") val solarWind: SolarWind? = null,
    /** Present only when the UK enhancement layer has data. */
    val aurorawatch: AuroraWatchStatus? = null,
) {
    /** True when the server has no grid at all, so its silence means nothing. */
    val activityUnknown: Boolean get() = globalActivity == ACTIVITY_UNKNOWN

    /**
     * True when nothing anywhere on the globe is active.
     *
     * "Unknown" is deliberately not quiet: it means the server could not say,
     * which must not be mistaken for "there is no aurora".
     */
    val globallyQuiet: Boolean get() = globalActivity == ACTIVITY_NONE

    companion object {
        const val ACTIVITY_UNKNOWN = "unknown"
        const val ACTIVITY_NONE = "none"
    }
}

@Serializable
data class KpReading(val value: Double, val time: String)

@Serializable
data class SolarWind(
    /**
     * North-south component of the interplanetary magnetic field, in nT.
     *
     * The leading indicator the whole design rests on: DSCOVR sits at L1, about
     * an hour upstream, so a southward turn (negative Bz) is 30-60 minutes of
     * warning before a substorm.
     */
    val bz: Double,
    val bt: Double,
    val speed: Double? = null,
    val time: String,
)

@Serializable
data class AuroraWatchStatus(
    /** One of green, yellow, amber, red. Their thresholds, not ours. */
    val status: String,
    val updated: String,
    @SerialName("site_id") val siteId: String? = null,
)
