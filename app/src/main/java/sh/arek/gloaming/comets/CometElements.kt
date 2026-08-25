package sh.arek.gloaming.comets

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What somebody actually measured, as opposed to what the elements predict.
 *
 * The reason this field exists at all is that the prediction is unreliable in
 * both directions. On 2026-08-25 the magnitude law put 220P/McNaught at 15.7
 * while thirteen observers had it at 9.3, and put 161P/Hartley-IRAS at 7.1
 * while nine had it at 15.0.
 */
@Serializable
data class ObservedMagnitude(
    val magnitude: Double,
    /** When the most recent report behind this figure was made. */
    @SerialName("atMillis") val atMillis: Long,
    val reports: Int,
    /**
     * How many separate people. Carried so the app can say so, and because it
     * is the number that makes the figure trustworthy rather than the count of
     * reports - one person can file a dozen and be wrong in all of them.
     */
    val observers: Int,
)

/**
 * One comet, exactly as the fetcher publishes it.
 *
 * Two upstreams reduced into one record: the Minor Planet Center's elements,
 * which say where it will be, and COBS's observations, which say how bright it
 * is. The second is nullable and often null - most comets in the file are too
 * faint for anybody to bother with.
 */
@Serializable
data class PublishedComet(
    /** The MPC's identifier, which is also what the star and the alert record key on. */
    val key: String,
    /** The MPC's combined designation and name, for display. */
    val name: String,
    /** Perihelion distance, AU. */
    val q: Double,
    val e: Double,
    /** Inclination to the ecliptic, J2000, degrees. */
    val i: Double,
    /** Longitude of the ascending node, J2000, degrees. */
    val node: Double,
    /** Argument of perihelion, J2000, degrees. */
    val peri: Double,
    /**
     * Perihelion passage as a Julian date in **Terrestrial Time**.
     *
     * Published in the scale the MPC states it in rather than converted to a
     * unix timestamp and back. The two differ by over a minute, and a comet
     * near the Earth moves an arcminute in that - so a round trip through UTC
     * would spend the whole error budget twice for no gain. See [CometFinder].
     */
    @SerialName("perihelion_jd_tt") val perihelionJdTt: Double,
    /** Absolute magnitude, null where the file gives none. */
    @SerialName("magnitude_h") val magnitudeH: Double? = null,
    /** Slope parameter, conventionally 4. */
    val slope: Double? = null,
    val observed: ObservedMagnitude? = null,
) {
    /** The elements in the form the solver takes. */
    val orbit: Orbit
        get() = Orbit(
            perihelionAu = q,
            eccentricity = e,
            inclinationDegrees = i,
            nodeDegrees = node,
            perihelionArgumentDegrees = peri,
            perihelionJdTt = perihelionJdTt,
        )

    /** Whether the measured magnitude is recent enough to be worth believing. */
    fun hasFreshObservation(nowMillis: Long, staleAfterMillis: Long): Boolean {
        val seen = observed ?: return false
        return nowMillis - seen.atMillis <= staleAfterMillis
    }
}

@Serializable
data class CometsDocument(
    val schema: Int = 0,
    /** Unix seconds at which the fetcher built this. */
    val updated: Long = 0,
    val comets: List<PublishedComet> = emptyList(),
)

/**
 * Reads `comets.json`.
 *
 * The same shape as `Elements` does for `sats.json`, and for the same reasons:
 * everything awkward has already been done by the fetcher, so this is a rename
 * and a parse. `ignoreUnknownKeys` because an install that never updates - and
 * F-Droid and Obtainium users reliably do not - must not break the first time a
 * later phase adds a field.
 */
object CometFile {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): CometsDocument = json.decodeFromString(body)
}
