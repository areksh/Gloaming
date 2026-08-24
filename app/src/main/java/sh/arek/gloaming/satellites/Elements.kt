package sh.arek.gloaming.satellites

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One satellite's mean elements, exactly as the Worker publishes them.
 *
 * CelesTrak's usage policy forbids per-device fetching, so no install ever
 * talks to them: the Worker fetches once every two hours and fans the result
 * out through R2. It also does the normalising, which is why nothing here has
 * to parse a CSV or an ISO-8601 timestamp - [epoch] arrives already packed into
 * the form the propagator wants.
 */
@Serializable
data class SatelliteElements(
    val name: String,
    /** NORAD catalogue number. Six digits for anything catalogued since 2026-07. */
    val catalogue: Int,
    /** Packed `YYDDD.dddddd`: 2-digit year, day of year from 1, fraction of day. */
    val epoch: Double,
    /** The same instant in unix milliseconds, for ageing the elements. */
    @SerialName("epoch_millis") val epochMillis: Long,
    /** Revolutions per day. */
    @SerialName("mean_motion") val meanMotion: Double,
    val eccentricity: Double,
    /** Degrees. */
    val inclination: Double,
    /** Right ascension of the ascending node, degrees. */
    val raan: Double,
    /** Argument of pericentre, degrees. */
    @SerialName("arg_perigee") val argPerigee: Double,
    /** Degrees. */
    @SerialName("mean_anomaly") val meanAnomaly: Double,
    /** Drag term, inverse Earth radii. */
    val bstar: Double,
    /** First derivative of mean motion, revolutions per day squared. */
    @SerialName("mean_motion_dot") val meanMotionDot: Double = 0.0,
    /**
     * Standard magnitude where one can be stood behind, otherwise null.
     *
     * Almost always null, and deliberately so: the app describes passes by
     * their geometry and never prints a number it guessed.
     */
    val magnitude: Double? = null,
) {

    /**
     * The vendored propagator's own element type.
     *
     * A rename and nothing more - every unit already matches, which is the
     * point of normalising Worker-side. See `app/src/main/vendor/README.md`.
     */
    fun toOrbitalData(): OrbitalData = OrbitalData(
        name = name,
        epoch = epoch,
        meanmo = meanMotion,
        eccn = eccentricity,
        incl = inclination,
        raan = raan,
        argper = argPerigee,
        meanan = meanAnomaly,
        catnum = catalogue,
        bstar = bstar,
        ndot = meanMotionDot,
    )
}

/**
 * A Starlink launch still bunched into a visible train.
 *
 * Detected upstream rather than here, and for one reason: the Starlink group
 * runs to thousands of objects, and a device that had to work out for itself
 * which of them were still a train would have to be sent all of them. Only the
 * two ends are published, which is all a description needs - when the front of
 * it rises and when the back of it is clear.
 *
 * [lead] and [tail] are catalogue numbers whose elements are published in the
 * same document.
 */
@Serializable
data class StarlinkTrain(
    /** The launch, without the piece letter: `2026-123`. */
    val launch: String,
    /** How many objects of that launch are still in the train. */
    val members: Int,
    /** The satellite in front, which crosses the horizon first. */
    val lead: Int,
    /** The one at the back, which is last to go. */
    val tail: Int,
)

/** `sats.json`: the elements the Worker last fetched, and when. */
@Serializable
data class SatellitesDocument(
    val schema: Int,
    /** When the Worker published these elements, ISO-8601. */
    val updated: String,
    val satellites: List<SatelliteElements> = emptyList(),
    /**
     * The launches still bunched into a train, empty most of the time.
     *
     * Defaulted, like [satellites], so an install that never updates goes on
     * reading a document that has grown a field it knows nothing about.
     */
    val trains: List<StarlinkTrain> = emptyList(),
)

/** Reads `sats.json`. */
object Elements {

    // Unknown keys are ignored so a later Worker can add a field without
    // breaking installs that will never be updated again.
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): SatellitesDocument = json.decodeFromString(body)
}
