package sh.arek.gloaming.astronomy

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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

    /**
     * Which way [point] lies from [centre] on the drawing, as a direction of
     * length one, or null at the centre itself or straight opposite it,
     * where every way is as short as any other.
     *
     * The way the shorter great circle sets off, which within 90 degrees is
     * the way [around] puts the point, since the projection keeps directions
     * out from its centre. It is defined behind the viewer too, where
     * [around] gives nothing.
     */
    fun towards(centre: AltAz, point: AltAz): PlanePoint? {
        val alt0 = Math.toRadians(centre.altitude)
        val alt = Math.toRadians(point.altitude)
        val dAz = Math.toRadians(point.azimuth - centre.azimuth)
        val x = cos(alt) * sin(dAz)
        val y = cos(alt0) * sin(alt) - sin(alt0) * cos(alt) * cos(dAz)
        val length = hypot(x, y)
        if (length < 1e-9) return null
        return PlanePoint(x / length, y / length)
    }

    /**
     * Where the horizon falls on a drawing centred at [centreAltitude]: a
     * circle, since the projection draws every great circle that misses the
     * point opposite the centre as one. Looking up, the sky is inside it;
     * looking down, outside.
     *
     * Null within [LEVEL_DEGREES] of level, where the circle is so large that
     * across a view the horizon is the straight line through the centre - off
     * by under a twentieth of a degree thirty degrees out.
     */
    fun horizon(centreAltitude: Double): PlaneCircle? {
        if (abs(centreAltitude) < LEVEL_DEGREES) return null
        val a = Math.toRadians(centreAltitude)
        // The horizon below the centre is centreAltitude away, and opposite,
        // over the zenith, 180 less it: r = 2 tan(c/2) at each end.
        return PlaneCircle(PlanePoint(0.0, 2 * DEGREES * cos(a) / sin(a)), 2 * DEGREES / abs(sin(a)))
    }

    private const val LEVEL_DEGREES = 0.5
}

/** A circle on a flat drawing of the sky, in the same degrees as [PlanePoint]. */
data class PlaneCircle(val centre: PlanePoint, val radius: Double)
