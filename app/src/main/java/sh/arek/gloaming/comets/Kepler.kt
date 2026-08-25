package sh.arek.gloaming.comets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The six numbers that fix a comet's path, as the Minor Planet Center gives
 * them.
 *
 * Comets are described by perihelion distance and eccentricity rather than by
 * the semi-major axis a planet gets, because a semi-major axis is infinite for
 * a parabola and negative for the interstellar visitors - so the convention
 * every planetary orbit uses cannot express the objects this file exists for.
 *
 * [perihelionJdTt] is Terrestrial Time, not UTC. That distinction is worth the
 * field name: the two differ by about a minute at present, and a comet near the
 * Earth can move an arcminute in that, which is the whole error budget.
 */
data class Orbit(
    /** Closest approach to the sun, in AU. */
    val perihelionAu: Double,
    /** 0 for a circle, below 1 for an ellipse, 1 for a parabola, above for a hyperbola. */
    val eccentricity: Double,
    val inclinationDegrees: Double,
    /** Longitude of the ascending node, J2000 ecliptic. */
    val nodeDegrees: Double,
    /** Argument of perihelion, J2000 ecliptic. */
    val perihelionArgumentDegrees: Double,
    /** Julian date of perihelion passage, Terrestrial Time. */
    val perihelionJdTt: Double,
)

/** How far out the comet is, and how far round from perihelion it has come. */
data class OrbitPoint(
    val radiusAu: Double,
    /** Radians from perihelion, negative on the way in. */
    val trueAnomaly: Double,
)

/**
 * Where a comet is on its orbit at a given moment.
 *
 * Two-body motion only: the comet is treated as falling around the sun alone,
 * with no pull from Jupiter. That is exactly what the MPC's elements are for -
 * they osculate, meaning they describe the two-body orbit tangent to the real
 * path at the moment they were computed - and it is why they are reissued
 * weekly rather than once. Propagating them a few weeks either side is accurate
 * to well under the arcminute this app needs; propagating them a year is not,
 * which is the argument for the fetcher refreshing them rather than the app
 * shipping a table.
 *
 * Three branches, because a conic section is three different shapes and the
 * closed forms for one are singular for another. The ellipse divides by
 * `1 - e`, which is zero for a parabola; the parabola has no mean motion at
 * all; the hyperbola needs sinh where the ellipse needs sin. Comets occur in
 * all three: of the 957 orbits in the MPC file, some 680 are ordinary ellipses,
 * 120 are unbound, and 120 sit in the last thousandth below one where an
 * ellipse stops behaving like an ellipse.
 *
 * Everything here is pure arithmetic on doubles. It is checked against JPL
 * Horizons rather than against its own fixtures - see `KeplerTest`.
 */
object Kepler {

    /**
     * The Gaussian gravitational constant, in AU to the three halves per day.
     *
     * Defined rather than measured: it fixes the length of the astronomical
     * unit, so it carries no uncertainty and is not revised.
     */
    const val GAUSS: Double = 0.01720209895

    /**
     * How close to one an eccentricity has to be before it is treated as a
     * parabola.
     *
     * Not merely a guard against dividing by zero at exactly one: inside this
     * band the parabola is the *more accurate* of the two. The ellipse computes
     * `r = a(1 - e cos E)` where `a = q / (1 - e)`, so as e approaches one, a
     * grows without bound while the bracket shrinks to nothing - and a double
     * holding sixteen digits loses eight of them to that cancellation at
     * `e = 1 - 1e-8`, costing about a tenth of a milli-AU. Barker's equation
     * has no such subtraction and is exact arithmetic throughout.
     *
     * The two error curves cross near `5e-7`: the ellipse's cancellation grows
     * as the band narrows, the parabola's approximation grows as it widens.
     * A millionth is the round number just above that crossing, where both
     * stay near a hundredth of a milli-AU - about an arcsecond at one AU, and
     * so comfortably inside the arcminute this app works to.
     */
    private const val PARABOLIC_BAND: Double = 1e-6

    /** Where the iterations stop; a hundred thousandth of a degree of anomaly. */
    private const val CONVERGED: Double = 1e-13

    /**
     * Enough for the worst case, which is a barely-hyperbolic orbit far from
     * perihelion. Ordinary orbits converge in four or five.
     */
    private const val MAX_STEPS: Int = 100

    /**
     * How far one Newton step may move the answer.
     *
     * Newton's method on the hyperbolic equation can throw the first step into
     * the thousands when the starting guess is poor, and `sinh` of that
     * overflows to infinity - from which no later step recovers. Capping the
     * step costs a few extra iterations and cannot diverge.
     */
    private const val MAX_STEP: Double = 1.0

    /** Where the comet is at [jdTt], given in Terrestrial Time. */
    fun solve(orbit: Orbit, jdTt: Double): OrbitPoint {
        val days = jdTt - orbit.perihelionJdTt
        val q = orbit.perihelionAu
        val e = orbit.eccentricity

        if (abs(e - 1.0) < PARABOLIC_BAND) return parabolic(q, days)
        return if (e < 1.0) elliptical(q, e, days) else hyperbolic(q, e, days)
    }

    private fun elliptical(q: Double, e: Double, days: Double): OrbitPoint {
        val a = q / (1.0 - e)
        val meanAnomaly = GAUSS / a.pow(1.5) * days
        val anomaly = eccentricAnomaly(meanAnomaly, e)
        return OrbitPoint(
            radiusAu = a * (1.0 - e * cos(anomaly)),
            trueAnomaly = 2.0 * atan2(
                sqrt(1.0 + e) * sin(anomaly / 2.0),
                sqrt(1.0 - e) * cos(anomaly / 2.0),
            ),
        )
    }

    private fun hyperbolic(q: Double, e: Double, days: Double): OrbitPoint {
        val a = q / (e - 1.0)
        val meanAnomaly = GAUSS / a.pow(1.5) * days
        val anomaly = hyperbolicAnomaly(meanAnomaly, e)
        return OrbitPoint(
            radiusAu = a * (e * cosh(anomaly) - 1.0),
            trueAnomaly = 2.0 * atan2(
                sqrt(e + 1.0) * sinh(anomaly / 2.0),
                sqrt(e - 1.0) * cosh(anomaly / 2.0),
            ),
        )
    }

    /**
     * Barker's equation, which is the parabolic case and needs no iteration at
     * all.
     *
     * A parabola has no mean motion and no period, so there is no Kepler's
     * equation to solve. What replaces it is a cubic in the tangent of half the
     * true anomaly, and a cubic with a positive linear coefficient has exactly
     * one real root - which Cardano's formula gives outright. This is why
     * comets were computed by hand for two centuries before anyone could
     * iterate: the parabolic case is closed-form.
     */
    private fun parabolic(q: Double, days: Double): OrbitPoint {
        // 3k/sqrt(2), the constant that turns days from perihelion into the
        // right-hand side of the cubic.
        val w = 3.0 * GAUSS * days / (sqrt(2.0) * q.pow(1.5))
        val half = w / 2.0
        val y = cbrt(half + sqrt(half * half + 1.0))
        val s = y - 1.0 / y
        return OrbitPoint(radiusAu = q * (1.0 + s * s), trueAnomaly = 2.0 * atan(s))
    }

    /**
     * Kepler's equation, `M = E - e sin E`, solved for E.
     *
     * Newton's method, but the starting guess is what makes it work. Taking
     * `E = M` is the usual advice and it is fine for a planet; for a comet at
     * e = 0.999 near perihelion it is hopeless, because there the equation is
     * dominated by its cubic term - `M ~ (1 - e)E + E^3/6` - so the true answer
     * is near the cube root of `6M` while M itself is a thousand times smaller.
     * Newton from there crawls, and with a fixed iteration count it simply
     * returns a wrong answer.
     *
     * The fix is to start from the root of that cubic, which Cardano gives in
     * closed form - the same formula the parabolic branch above uses, and for
     * the same reason. From there the iteration converges in a handful of
     * steps at every eccentricity the MPC file contains.
     */
    fun eccentricAnomaly(meanAnomaly: Double, eccentricity: Double): Double {
        val m = wrap(meanAnomaly)
        var anomaly = cubicGuess(m, eccentricity)

        repeat(MAX_STEPS) {
            val slope = 1.0 - eccentricity * cos(anomaly)
            if (slope == 0.0) return anomaly
            val step = (anomaly - eccentricity * sin(anomaly) - m) / slope
            anomaly -= step
            if (abs(step) < CONVERGED) return anomaly
        }
        return anomaly
    }

    /** The hyperbolic twin, `M = e sinh H - H`, solved for H. */
    fun hyperbolicAnomaly(meanAnomaly: Double, eccentricity: Double): Double {
        var anomaly = asinh(meanAnomaly / eccentricity)

        repeat(MAX_STEPS) {
            val slope = eccentricity * cosh(anomaly) - 1.0
            if (slope == 0.0) return anomaly
            var step = (eccentricity * sinh(anomaly) - anomaly - meanAnomaly) / slope
            if (abs(step) > MAX_STEP) step = MAX_STEP * sign(step)
            anomaly -= step
            if (abs(step) < CONVERGED) return anomaly
        }
        return anomaly
    }

    /**
     * The real root of `e E^3 / 6 + (1 - e) E = M`, the cubic that Kepler's
     * equation becomes for small E.
     *
     * Falls back to M itself where the cubic has nothing to say - a circular
     * orbit, where the equation is already solved, and the far side of an orbit
     * where E is nowhere near small and the ordinary guess is the better one.
     */
    private fun cubicGuess(meanAnomaly: Double, eccentricity: Double): Double {
        val cubic = eccentricity / 6.0
        if (cubic <= 0.0) return meanAnomaly

        val linear = (1.0 - eccentricity) / cubic
        val constant = -meanAnomaly / cubic
        val discriminant = (constant / 2.0).pow(2) + (linear / 3.0).pow(3)
        if (discriminant < 0.0) return meanAnomaly

        val root = sqrt(discriminant)
        val guess = cbrt(-constant / 2.0 + root) + cbrt(-constant / 2.0 - root)
        return if (guess.isFinite() && abs(guess) <= PI) guess else meanAnomaly
    }

    /** Mean anomaly folded into a single revolution, so Newton starts near home. */
    private fun wrap(radians: Double): Double {
        val turned = (radians + PI) % (2.0 * PI)
        return (if (turned < 0.0) turned + 2.0 * PI else turned) - PI
    }
}
