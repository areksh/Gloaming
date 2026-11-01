package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Spherical
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.rotationEqjEqd

/**
 * Where something is in the sky, as seen from one place.
 *
 * @property altitude degrees above the horizon, refracted; negative is below it.
 * @property azimuth degrees clockwise from true north: 0 north, 90 east.
 */
data class AltAz(val altitude: Double, val azimuth: Double)

/**
 * Where a body or a fixed point is at one instant.
 *
 * Topocentric and refracted, the same as [Planets], so a position drawn on a
 * chart agrees with the altitude printed beside it. The instant is a
 * parameter; nothing here reads the clock.
 */
object SkyPositions {

    /** Where [body] is from the given place at [atMillis]. */
    fun body(body: SkyBody, latitude: Double, longitude: Double, atMillis: Long): AltAz =
        Planets.position(Planets.bodyOf(body), Observer(latitude, longitude, 0.0), atMillis)
            .let { AltAz(it.altitude, it.azimuth) }

    /**
     * Where a fixed point is, given in J2000 coordinates.
     *
     * @param raHours right ascension in hours, 0 to 24.
     * @param decDegrees declination in degrees, -90 to 90.
     * @param longitude degrees east of Greenwich.
     */
    fun fixed(
        raHours: Double,
        decDegrees: Double,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): AltAz {
        val time = Time.fromMillisecondsSince1970(atMillis)
        val observer = Observer(latitude, longitude, 0.0)
        // The horizon conversion wants coordinates of date, and catalogue
        // positions are J2000. Precession is about 0.36 degrees by now: more
        // than a chart should be out by.
        val ofDate = rotationEqjEqd(time)
            .rotate(Spherical(decDegrees, raHours * 15.0, 1.0).toVector(time))
            .toEquatorial()
        return horizon(time, observer, ofDate.ra, ofDate.dec, Refraction.Normal)
            .let { AltAz(it.altitude, it.azimuth) }
    }
}
