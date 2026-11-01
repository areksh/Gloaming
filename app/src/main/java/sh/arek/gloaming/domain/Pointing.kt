package sh.arek.gloaming.domain

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import sh.arek.gloaming.astronomy.AltAz

/**
 * Which way the phone's top edge points, from the rotation sensor.
 *
 * Pure arithmetic with no Android types, so it is tested on the JVM.
 */
object Pointing {
    /**
     * The direction of the device's +Y axis (its top edge) in the sky.
     *
     * [rotation] is the 9-element row-major matrix that
     * `SensorManager.getRotationMatrixFromVector` produces: world X east, Y
     * north, Z up, and device X right, Y top edge, Z out of the screen. The
     * top edge in world coordinates is the matrix's second column, so its
     * east and north parts give the azimuth and its up part the altitude.
     *
     * The sensor's azimuth is from magnetic north; [declinationDegrees]
     * (east positive) brings it to true north, and [offsetDegrees] is the
     * user's own correction from a compass check. The azimuth is returned in
     * 0..360.
     */
    fun direction(rotation: FloatArray, declinationDegrees: Double, offsetDegrees: Double): AltAz {
        val magnetic = Math.toDegrees(atan2(rotation[1].toDouble(), rotation[4].toDouble()))
        val azimuth = wrap360(magnetic + declinationDegrees + offsetDegrees)
        val altitude = Math.toDegrees(asin(rotation[7].toDouble().coerceIn(-1.0, 1.0)))
        return AltAz(altitude, azimuth)
    }
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
     * The separation is the true great-circle angle, not the sum of the two
     * differences: near the pole a large azimuth gap is a small angle.
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

    private fun separation(a: AltAz, b: AltAz): Double {
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
        if (parts.isEmpty()) return ""
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
