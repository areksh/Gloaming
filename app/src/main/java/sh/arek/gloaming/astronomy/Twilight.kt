package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.searchAltitude

/** How much of the moon is lit, and how far round its cycle it has come. */
data class Moon(
    /** 0 at new moon, 90 at first quarter, 180 at full, 270 at last quarter. */
    val phaseDegrees: Double,
    /** Fraction of the disc lit, 0 at new and 1 at full. */
    val illumination: Double,
)

/**
 * Whether it is dark enough to see anything, and when that changes.
 *
 * A sealed type rather than a nullable time, because "no darkness" and "no
 * dawn" are both real answers at high latitude and a single null cannot
 * distinguish them - nor stop a caller treating either as an error.
 */
sealed interface Darkness {

    /**
     * Dark now, until [endsAt] - or with no end at all, which is the polar
     * night: the sun does not come back above the threshold within the horizon
     * searched.
     */
    data class Now(val endsAt: Long?) : Darkness

    /** Light now; dark from [startsAt], and until [endsAt] if that is known. */
    data class Later(val startsAt: Long, val endsAt: Long?) : Darkness

    /**
     * Light now and light throughout the horizon searched: the midnight sun,
     * and Shetland's simmer dim at the astronomical threshold.
     */
    data object None : Darkness
}

/**
 * Darkness and moonlight, computed on the device from a vendored ephemeris.
 *
 * This is the cheap gate. It needs no network, works in a tunnel and in the
 * polar night, and rules out most of the day before anything is fetched - which
 * is why it is non-optional and why the adaptive schedule is built on it.
 *
 * Everything here is a thin wrapper over `astronomy-engine` (see
 * `app/src/main/vendor/README.md`). It exists so the rest of the app never
 * touches the library's types, and so the polar cases are answered once, here,
 * rather than by every caller.
 */
object Twilight {

    /**
     * Civil twilight. Aurora becomes visible somewhere around here, and this is
     * the threshold the alert gate uses.
     */
    const val CIVIL_DEGREES: Double = -6.0

    /**
     * Astronomical darkness - a genuinely dark sky. Above about 60 degrees of
     * latitude it does not arrive at all between May and August.
     */
    const val ASTRONOMICAL_DEGREES: Double = -18.0

    /**
     * How far ahead a window is searched for.
     *
     * A day and a half covers a night from any point in the preceding day, and
     * stops the polar cases searching a year to conclude what one day already
     * settles.
     */
    private const val SEARCH_DAYS: Double = 1.5

    /** Degrees above the horizon, negative below. */
    fun sunAltitude(latitude: Double, longitude: Double, atMillis: Long): Double =
        altitudeOf(Body.Sun, observer(latitude, longitude), Time.fromMillisecondsSince1970(atMillis))

    fun moonAltitude(latitude: Double, longitude: Double, atMillis: Long): Double =
        altitudeOf(Body.Moon, observer(latitude, longitude), Time.fromMillisecondsSince1970(atMillis))

    fun moon(atMillis: Long): Moon {
        val time = Time.fromMillisecondsSince1970(atMillis)
        return Moon(
            phaseDegrees = moonPhase(time),
            illumination = illumination(Body.Moon, time).phaseFraction,
        )
    }

    /** Whether the sun is below [CIVIL_DEGREES] where the observer stands. */
    fun isDark(latitude: Double, longitude: Double, atMillis: Long): Boolean =
        sunAltitude(latitude, longitude, atMillis) < CIVIL_DEGREES

    /**
     * The next stretch of darkness, or the one already underway.
     *
     * @param belowDegrees how far the sun must be below the horizon to count.
     */
    fun darkness(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        belowDegrees: Double = CIVIL_DEGREES,
    ): Darkness {
        val observer = observer(latitude, longitude)
        val from = Time.fromMillisecondsSince1970(fromMillis)

        if (altitudeOf(Body.Sun, observer, from) < belowDegrees) {
            return Darkness.Now(endsAt = cross(observer, Direction.Rise, from, belowDegrees))
        }

        val startsAt = cross(observer, Direction.Set, from, belowDegrees) ?: return Darkness.None
        return Darkness.Later(
            startsAt = startsAt,
            // Searched from the start of the window rather than from now: the
            // dawn that ends it can be a day away, past the horizon a search
            // from here would reach.
            endsAt = cross(
                observer,
                Direction.Rise,
                Time.fromMillisecondsSince1970(startsAt),
                belowDegrees,
            ),
        )
    }

    /** When the sun next crosses [degrees] going the given way, if it does. */
    private fun cross(observer: Observer, direction: Direction, from: Time, degrees: Double): Long? =
        searchAltitude(Body.Sun, observer, direction, from, SEARCH_DAYS, degrees)
            ?.toMillisecondsSince1970()

    /**
     * Geometric altitude, uncorrected for refraction.
     *
     * Deliberately matching `searchAltitude`, which is airless: an apparent
     * altitude here would disagree with the window boundaries computed there,
     * and the app would believe it was light at the exact moment its own dusk
     * arrived.
     */
    private fun altitudeOf(body: Body, observer: Observer, time: Time): Double {
        val position = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        return horizon(time, observer, position.ra, position.dec, Refraction.None).altitude
    }

    /** Sea level throughout: a few hundred metres moves the horizon by a fraction
     * of a degree, well inside the slack in a -6 degree gate, and the app has no
     * elevation to offer anyway. */
    private fun observer(latitude: Double, longitude: Double) =
        Observer(latitude, longitude, 0.0)
}
