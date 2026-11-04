package sh.arek.gloaming.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.data.FindAim

/**
 * Which way the phone points, from the rotation sensor, held either of the
 * two ways [FindAim] names: aimed by its top edge like a pointer, or looked
 * through like a window, out of its back.
 *
 * Pure arithmetic with no Android types, so it is tested on the JVM.
 */
object Pointing {
    /**
     * How close to straight up or down a window may look before it has no up
     * to turn the drawing to: the vertical, seen end on, is a point.
     */
    private const val NO_ROLL_BELOW = 1e-3

    /**
     * Where the phone points in the sky.
     *
     * [rotation] is the 9-element row-major matrix that
     * `SensorManager.getRotationMatrixFromVector` produces: world X east, Y
     * north, Z up, and device X right, Y top edge, Z out of the screen, so
     * each column is one device axis in world coordinates. Aimed by the top
     * edge, the direction is the second column (+Y); looked through, it is
     * the third reversed (-Z), out of the back, and rolling the phone in its
     * own plane leaves it alone. Its east and north parts give the azimuth
     * and its up part the altitude.
     *
     * The sensor's azimuth is from magnetic north; [declinationDegrees]
     * (east positive) brings it to true north, and [offsetDegrees] is the
     * user's own correction from a compass check. The azimuth is returned in
     * 0..360.
     */
    fun direction(rotation: FloatArray, aim: FindAim, declinationDegrees: Double, offsetDegrees: Double): AltAz {
        val (east, north, up) = when (aim) {
            FindAim.TopEdge -> Triple(rotation[1], rotation[4], rotation[7])
            FindAim.LookThrough -> Triple(-rotation[2], -rotation[5], -rotation[8])
        }
        val magnetic = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        val azimuth = wrap360(magnetic + declinationDegrees + offsetDegrees)
        val altitude = Math.toDegrees(asin(up.toDouble().coerceIn(-1.0, 1.0)))
        return AltAz(altitude, azimuth)
    }

    /**
     * How far to turn the drawing of the sky, in degrees clockwise on the
     * screen, so that looked through, its up is the sky's up however the
     * phone is rolled. Measured from the device's own top edge, before the
     * screen's rotation is allowed for.
     *
     * World up in device coordinates is the matrix's third row; its part in
     * the screen's plane is the way up looks on the screen. Turned clockwise,
     * the phone sees the horizon tilt anticlockwise, and this is negative by
     * as much. Looking straight up or down there is no such way, and it is 0.
     *
     * Aimed by the top edge it is 0 below [TOP_EDGE_TURNS_FROM]: the screen
     * is not a window then, and its up is where the top edge points, not the
     * sky's. Near the zenith that rule breaks down, because the azimuth is
     * read from the top edge's level part, which shrinks to nothing there:
     * a degree of hand movement swings it by ten, and tipping the edge past
     * straight up turns it by half a turn, which would throw the drawing to
     * the other side of the reticle. So from there up the drawing is turned
     * by the phone's own frame - up towards the screen's face, as tipping
     * the top edge back moves it - weighed in until [TOP_EDGE_TURNS_FULLY],
     * where the swing in the azimuth is cancelled exactly.
     */
    fun roll(rotation: FloatArray, aim: FindAim): Double {
        val right = rotation[6].toDouble()
        val top = when (aim) {
            FindAim.TopEdge -> rotation[8].toDouble()
            FindAim.LookThrough -> rotation[7].toDouble()
        }
        if (abs(right) < NO_ROLL_BELOW && abs(top) < NO_ROLL_BELOW) return 0.0
        val turn = Math.toDegrees(atan2(right, top))
        if (aim == FindAim.LookThrough) return turn

        val altitude = Math.toDegrees(asin(rotation[7].toDouble().coerceIn(-1.0, 1.0)))
        val weight = ((altitude - TOP_EDGE_TURNS_FROM) / (TOP_EDGE_TURNS_FULLY - TOP_EDGE_TURNS_FROM))
            .coerceIn(0.0, 1.0)
        return turn * weight
    }

    /** Aimed by the top edge, the drawing starts to turn with the phone at this height. */
    private const val TOP_EDGE_TURNS_FROM = 60.0

    /** And turns with it fully from here up, where the azimuth would otherwise swing. */
    private const val TOP_EDGE_TURNS_FULLY = 80.0
}

internal fun wrap360(degrees: Double): Double {
    val r = degrees % 360.0
    return if (r < 0.0) r + 360.0 else r
}

/**
 * How far to move the phone to put a target in line.
 *
 * @property turnDegrees -180..180, positive is to the right; 0 when either end is near the zenith.
 * @property tiltDegrees target altitude less pointing altitude; positive is up.
 * @property aligned whether the two are within [Guide.ALIGNED_DEGREES] of each other on the sky.
 */
data class Guidance(val turnDegrees: Double, val tiltDegrees: Double, val aligned: Boolean)

object Guide {
    const val ALIGNED_DEGREES = 5.0

    /** Azimuth means nothing this close to straight up, so only tilt is asked. */
    const val ZENITH_DEGREES = 85.0

    /**
     * The separation is the true great-circle angle ([separation]), not the
     * sum of the two differences: near the pole a large azimuth gap is a
     * small angle.
     */
    fun of(pointing: AltAz, target: AltAz): Guidance {
        val nearZenith = pointing.altitude >= ZENITH_DEGREES || target.altitude >= ZENITH_DEGREES
        var turn = 0.0
        if (!nearZenith) {
            turn = wrap360(target.azimuth - pointing.azimuth)
            if (turn > 180.0) turn -= 360.0
        }
        return Guidance(
            turnDegrees = turn,
            tiltDegrees = target.altitude - pointing.altitude,
            aligned = separation(pointing, target) <= ALIGNED_DEGREES,
        )
    }

    /** The angle between two directions on the sky, in degrees. */
    fun separation(a: AltAz, b: AltAz): Double {
        val a1 = Math.toRadians(a.altitude)
        val a2 = Math.toRadians(b.altitude)
        val dAz = Math.toRadians(a.azimuth - b.azimuth)
        val c = sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos(dAz)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }
}

/** The one line shown while the viewer is lining the phone up. */
object GuideWords {
    private const val LITTLE_TURN = 15.0
    private const val LONG_TURN = 60.0
    private const val ROUND_TURN = 135.0
    private const val NO_TILT = 5.0
    private const val LITTLE_TILT = 15.0

    fun instruction(g: Guidance): String {
        if (g.aligned) return "There."
        val parts = listOfNotNull(turnWords(g.turnDegrees), tiltWords(g.tiltDegrees))
        // Near the zenith no turn is asked, so a few degrees across the top
        // of the sky can leave neither band with anything to say.
        if (parts.isEmpty()) return "Nearly there."
        val line = parts.joinToString(", then ")
        return line.replaceFirstChar { it.uppercaseChar() } + "."
    }

    private fun turnWords(turn: Double): String? {
        val size = abs(turn)
        if (size == 0.0) return null
        if (size >= ROUND_TURN) return "turn round"
        val side = if (turn > 0.0) "right" else "left"
        return when {
            size < LITTLE_TURN -> "turn $side a little"
            size < LONG_TURN -> "turn $side"
            else -> "turn $side a long way"
        }
    }

    private fun tiltWords(tilt: Double): String? {
        val size = abs(tilt)
        if (size < NO_TILT) return null
        val way = if (tilt > 0.0) "up" else "down"
        return if (size < LITTLE_TILT) "tilt $way a little" else "tilt $way"
    }
}

/**
 * When to buzz on lining up: once on coming within [Guide.ALIGNED_DEGREES],
 * and not again until the phone has moved past [REARM_DEGREES].
 *
 * The gap between the two is what keeps a hand that wavers around the edge of
 * lined up from buzzing on every crossing.
 */
object AlignBuzz {
    /** How far off the target the phone must go before lining up buzzes again. */
    const val REARM_DEGREES = 8.0

    /**
     * @property armed whether coming within the aligned distance will buzz.
     * @property fire whether to buzz now.
     */
    data class Step(val armed: Boolean, val fire: Boolean)

    /**
     * Where the buzz among many targets stands: the targets it has buzzed for
     * that would not buzz again yet, because the phone has not been more than
     * [REARM_DEGREES] off them since.
     */
    data class Among(val disarmed: Set<FindTarget>)

    /** Nothing buzzed for yet. */
    val AMONG_START = Among(emptySet())

    /**
     * The buzz for Everything mode, where any of several targets can come
     * under the reticle: the next state, and whether to buzz now.
     *
     * [under] is the target within the aligned distance, if any;
     * [separationOf] how far the phone points from a target, or null for one
     * no longer up. Each target is armed on its own, as [next] arms its one:
     * coming onto a target buzzes unless it has been buzzed for and not left
     * by [REARM_DEGREES] since, so a reticle wavering between two close
     * targets buzzes once for each, not on every crossing, and moving on to
     * another target buzzes at once. A target that has gone re-arms.
     */
    fun nextAmong(state: Among, under: FindTarget?, separationOf: (FindTarget) -> Double?): Pair<Among, Boolean> {
        val disarmed = state.disarmed.filterTo(mutableSetOf()) { target ->
            separationOf(target)?.let { it <= REARM_DEGREES } == true
        }
        if (under != null && under !in disarmed) return Among(disarmed + under) to true
        return Among(disarmed) to false
    }

    /**
     * The next step from [armed] and the angle between where the phone points
     * and the target, or null where there is none (no reading, or nothing to
     * point at), which changes nothing.
     */
    fun next(armed: Boolean, separationDegrees: Double?): Step = when {
        separationDegrees == null -> Step(armed, fire = false)
        armed && separationDegrees <= Guide.ALIGNED_DEGREES -> Step(armed = false, fire = true)
        !armed && separationDegrees > REARM_DEGREES -> Step(armed = true, fire = false)
        else -> Step(armed, fire = false)
    }
}
