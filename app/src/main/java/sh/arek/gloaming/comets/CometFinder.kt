package sh.arek.gloaming.comets

import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.Vector
import io.github.cosinekitty.astronomy.helioVector
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.rotationEclEqj
import io.github.cosinekitty.astronomy.rotationEqjEqd
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import sh.arek.gloaming.astronomy.Twilight

/** Where a comet is from here, how far away, and how bright. */
data class CometSighting(
    val atMillis: Long,
    /** Apparent degrees above the horizon, refraction included. */
    val altitude: Double,
    /** Degrees clockwise from north. */
    val azimuth: Double,
    /** J2000 right ascension in degrees, rather than the hours the library uses. */
    val rightAscensionDegrees: Double,
    val declinationDegrees: Double,
    /** How far the comet is from the sun, in AU. */
    val sunDistanceAu: Double,
    /** How far the comet is from the observer, in AU. */
    val earthDistanceAu: Double,
    /** Predicted, and null where the elements carry no absolute magnitude. */
    val magnitude: Double?,
    /** Whether the sun is far enough down here for the comet to be seeable. */
    val isDark: Boolean,
)

/**
 * A comet's place in the sky, worked out from its orbit.
 *
 * The planets get this for free: `astronomy-engine` carries their ephemerides
 * and `SkyCalendar` asks it where Jupiter is. A comet has no ephemeris to carry
 * - there are hundreds, they are discovered continually, and their orbits are
 * revised as they are observed - so the position has to be built from elements,
 * which is what [Kepler] is for. Everything after that is the library's:
 * the Earth's own position, the frame rotations, and the horizon.
 *
 * **Terrestrial Time is the point of care here.** The perihelion passage the
 * MPC publishes is TT and the phone's clock is UTC. They differ by about
 * seventy seconds at present, and a comet moving a degree a day covers an
 * arcminute in that - the whole error budget, spent on a units mistake. The
 * library's `Time` carries both scales, so the conversion is a field access and
 * not arithmetic; it is done in exactly one place, below.
 *
 * Aberration and the observer's displacement from the centre of the Earth are
 * both left out. Together they are worth under half an arcminute, and this
 * exists to answer whether a comet is up and how high, not to point a telescope.
 */
object CometFinder {

    /** The Julian date of J2000.0, from which the library counts days. */
    private const val J2000: Double = 2451545.0

    /** The speed of light in AU per day, for the light-time correction. */
    private const val LIGHT_AU_PER_DAY: Double = 173.1446326846693

    /**
     * The slope assumed when the file gives none.
     *
     * Four is the conventional value and the one the MPC itself fills in. It is
     * far steeper than an asteroid's, which is the physical difference: a comet
     * grows a coma as it warms, so it brightens much faster than the
     * inverse-square law alone.
     */
    private const val USUAL_SLOPE: Double = 4.0

    /**
     * Enough for the light-time correction to stop moving.
     *
     * Light takes about eight minutes to cross an AU, in which a comet moves a
     * few arcseconds; the second pass corrects that, and the third has nothing
     * left to correct.
     */
    private const val LIGHT_STEPS: Int = 3

    /** How finely a night is searched for the comet's best moment. */
    private const val SAMPLE_MILLIS: Long = 10 * 60 * 1000L

    /**
     * Where the comet is, as seen from here at [atMillis] - an ordinary unix
     * instant in UTC, as every clock in the app gives it.
     *
     * The conversion to Terrestrial Time happens once, below, through the
     * library's `Time`, which carries both scales. See the note on this object
     * for why that is the line to look at first if a comet is ever slightly in
     * the wrong place.
     */
    fun positionOf(
        orbit: Orbit,
        magnitudeH: Double?,
        slope: Double?,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): CometSighting {
        val time = Time.fromMillisecondsSince1970(atMillis)
        val jdTt = julianDayTt(time)
        val observer = Observer(latitude, longitude, 0.0)
        val earth = helioVector(Body.Earth, time)

        // Light-time: the comet is seen where it was when the light left it,
        // not where it is now.
        var lightDays = 0.0
        var comet = earth
        var geocentric = earth
        repeat(LIGHT_STEPS) {
            comet = heliocentric(orbit, jdTt - lightDays, time)
            geocentric = comet - earth
            lightDays = geocentric.length() / LIGHT_AU_PER_DAY
        }

        val j2000 = geocentric.toEquatorial()
        val ofDate = rotationEqjEqd(time).rotate(geocentric).toEquatorial()
        val above = horizon(time, observer, ofDate.ra, ofDate.dec, Refraction.Normal)

        val sunDistance = comet.length()
        val earthDistance = geocentric.length()

        return CometSighting(
            atMillis = atMillis,
            altitude = above.altitude,
            azimuth = above.azimuth,
            rightAscensionDegrees = j2000.ra * 15.0,
            declinationDegrees = j2000.dec,
            sunDistanceAu = sunDistance,
            earthDistanceAu = earthDistance,
            magnitude = magnitudeOf(magnitudeH, slope, sunDistance, earthDistance),
            isDark = Twilight.isDark(latitude, longitude, atMillis),
        )
    }

    /**
     * The Julian date, in Terrestrial Time, of a moment on an ordinary clock.
     *
     * The single line this whole file's care about time scales comes down to,
     * pulled out so it can be tested for what it is rather than only through a
     * position. `Time` carries both scales already, so this is a field access
     * and an offset rather than arithmetic that could be got wrong - but which
     * field is read is precisely the mistake worth guarding, and reading `ut`
     * here would be wrong by a minute in a way nothing else would report.
     */
    internal fun julianDayTt(time: Time): Double = time.tt + J2000

    /**
     * The comet's heliocentric position, in the equatorial J2000 frame the
     * library works in.
     *
     * The orbit is solved in its own plane and then swung into the ecliptic by
     * the three angles the elements carry - argument of perihelion, node and
     * inclination - which is where those numbers earn their keep. The result is
     * ecliptic J2000, matching what the MPC publishes, and one rotation from
     * the frame everything else here uses.
     *
     * The vector is stamped with the observation time rather than the earlier
     * moment the light left, because the library refuses to subtract two
     * vectors stamped differently and the pair being differenced is deliberately
     * from two different instants - that is what a light-time correction is.
     */
    private fun heliocentric(orbit: Orbit, jdTt: Double, stamp: Time): Vector {
        val point = Kepler.solve(orbit, jdTt)
        val fromNode = Math.toRadians(orbit.perihelionArgumentDegrees) + point.trueAnomaly
        val node = Math.toRadians(orbit.nodeDegrees)
        val inclination = Math.toRadians(orbit.inclinationDegrees)
        val radius = point.radiusAu

        val ecliptic = Vector(
            radius * (cos(node) * cos(fromNode) - sin(node) * sin(fromNode) * cos(inclination)),
            radius * (sin(node) * cos(fromNode) + cos(node) * sin(fromNode) * cos(inclination)),
            radius * (sin(fromNode) * sin(inclination)),
            stamp,
        )
        return rotationEclEqj().rotate(ecliptic)
    }

    /**
     * The standard comet magnitude law,
     * `m = H + 5 log10(delta) + 2.5 G log10(r)`.
     *
     * The first term is distance from the observer and behaves like any other
     * light source. The second is the interesting one: it says a comet
     * brightens with a power of its distance from the sun rather than with the
     * inverse square, because sunlight is not merely reflected off it - it is
     * boiling material off it, and the coma that results is what is actually
     * being seen.
     *
     * The result is a prediction and routinely wrong by a magnitude in either
     * direction, which is why the app prefers a measured magnitude wherever
     * COBS has one and says which kind of number it is showing.
     */
    fun magnitudeOf(
        magnitudeH: Double?,
        slope: Double?,
        sunDistanceAu: Double,
        earthDistanceAu: Double,
    ): Double? {
        if (magnitudeH == null) return null
        if (sunDistanceAu <= 0.0 || earthDistanceAu <= 0.0) return null
        return magnitudeH +
            5.0 * log10(earthDistanceAu) +
            2.5 * (slope ?: USUAL_SLOPE) * log10(sunDistanceAu)
    }

    /**
     * The moment in the nearest night when the comet stands highest.
     *
     * A comet is not an occasion with a time the way an eclipse is - it is
     * simply there for weeks - so the calendar has to choose a moment to speak
     * about, and the highest point of a dark night is the one worth going
     * outside for. Deliberately the same question, and the same night
     * boundaries, that `SkyCalendar.bestInDarkness` already answers for planets.
     *
     * Null where there is no darkness to be had, which at high latitude in
     * summer is an answer rather than a failure.
     */
    fun bestInDarkness(
        orbit: Orbit,
        magnitudeH: Double?,
        slope: Double?,
        latitude: Double,
        longitude: Double,
        aroundMillis: Long,
    ): CometSighting? {
        val window = Twilight.nightNearest(latitude, longitude, aroundMillis) ?: return null

        var best: CometSighting? = null
        var at = window.first
        while (at <= window.last) {
            val seen = positionOf(orbit, magnitudeH, slope, latitude, longitude, at)
            if (best == null || seen.altitude > best.altitude) best = seen
            at += SAMPLE_MILLIS
        }
        return best
    }
}
