package sh.arek.gloaming.astronomy

import kotlin.math.cos
import kotlin.math.sin

/**
 * A point on a flat drawing of the sky, in degrees from the drawing's centre.
 *
 * @property x to the right: clockwise in azimuth, the way somebody facing the
 *   centre would turn to the right.
 * @property y up: towards the zenith.
 */
data class PlanePoint(val x: Double, val y: Double)

/**
 * Flattens a patch of sky around one direction, for drawing.
 *
 * **Stereographic, because it keeps shapes.** A figure drawn with it has the
 * same angles as the one in the sky, at any height, which is what lets
 * somebody match the drawing to the stars. It is scaled so that one unit is
 * one degree near the centre, so a caller can multiply by its own pixels per
 * degree.
 *
 * **In the horizon frame, not the equatorial one.** Up on the drawing is up
 * in the sky from where the observer stands, so a figure comes out turned
 * the way it really stands - Orion upright from Scotland and upside down from
 * Sydney - rather than north-up, as on a printed chart.
 */
object SkyProjection {

    private const val DEGREES = 180.0 / Math.PI

    /**
     * Where [point] falls on a drawing centred on [centre], or null when it
     * is more than 90 degrees away - behind the viewer, where the projection
     * would spread it towards infinity.
     */
    fun around(centre: AltAz, point: AltAz): PlanePoint? {
        val alt0 = Math.toRadians(centre.altitude)
        val alt = Math.toRadians(point.altitude)
        val dAz = Math.toRadians(point.azimuth - centre.azimuth)

        val cosC = sin(alt0) * sin(alt) + cos(alt0) * cos(alt) * cos(dAz)
        if (cosC < 0.0) return null
        val k = DEGREES * 2.0 / (1.0 + cosC)
        return PlanePoint(
            x = k * cos(alt) * sin(dAz),
            y = k * (cos(alt0) * sin(alt) - sin(alt0) * cos(alt) * cos(dAz)),
        )
    }
}
