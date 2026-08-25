package sh.arek.gloaming.astronomy

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** When to look for noctilucent clouds tonight, and which way. */
data class NlcWindow(
    val fromMillis: Long,
    val untilMillis: Long,
    /** North from the northern hemisphere, south from the southern. */
    val lookTowards: Compass,
)

/**
 * Noctilucent clouds: a prompt, not a prediction.
 *
 * These are ice crystals eighty kilometres up, so high that they stay in
 * sunlight long after the ground is dark - which is the whole of the geometry.
 * They need the sun between about 6 and 16 degrees below the horizon: any
 * higher and the sky outshines them, any lower and the clouds are in shadow
 * too.
 *
 * **There is no live source and the app does not pretend otherwise.** AIM, the
 * satellite that watched them, ended in 2023, and nothing has replaced it. So
 * this says "the conditions are right, go and look north" and says why that is
 * not a forecast. Sec. 6 requires the distinction be stated rather than
 * implied.
 *
 * It fills a real gap. Sec. 5 notes that at 50 to 60 degrees there is no
 * astronomical darkness at all from late May to early August, so the aurora
 * side of the app goes dormant for the season - at exactly the latitudes it
 * covers best, and exactly when these clouds are out.
 *
 * **Not part of the calendar.** Every other kind is a dated occasion; this is a
 * condition that holds most clear nights for three months, and ninety identical
 * entries would bury the things worth scrolling to. It reaches the same
 * notifier by the same route, on a channel of its own.
 */
object Noctilucent {

    /** Above this the twilight is too bright to pick them out. */
    const val UPPER_DEGREES: Double = -6.0

    /** Below this the clouds are in the Earth's shadow like everything else. */
    const val LOWER_DEGREES: Double = -16.0

    /**
     * The latitudes they are seen from.
     *
     * They form over the summer pole and are seen from the band below it,
     * looking towards it. Too far towards the equator and they never rise above
     * the horizon; too far towards the pole and the sun never drops far enough
     * to light them against a dark sky.
     */
    const val NEAREST_THE_EQUATOR: Double = 50.0
    const val NEAREST_THE_POLE: Double = 70.0

    /** Late May to mid August in the north, as days of the year. */
    private const val SEASON_OPENS = 145
    private const val SEASON_CLOSES = 225

    /** Half a year, to put the southern season in the southern summer. */
    private const val HALF_A_YEAR = 183

    /** Whether it is both the right time of year and the right latitude. */
    fun isSeason(latitude: Double, atMillis: Long): Boolean {
        val magnitude = kotlin.math.abs(latitude)
        if (magnitude < NEAREST_THE_EQUATOR || magnitude > NEAREST_THE_POLE) return false

        val day = dayOfYear(atMillis)
        val shifted = if (latitude < 0) ((day + HALF_A_YEAR - 1) % 365) + 1 else day
        return shifted in SEASON_OPENS..SEASON_CLOSES
    }

    /**
     * Tonight's window, or null when there is not one.
     *
     * Built from the two darkness windows [Twilight] already computes: the
     * stretch when the sun is below 6 degrees, minus the stretch when it is
     * below 16. What remains at the start of the night is the evening window,
     * which is when these are most often reported.
     *
     * When the sun never reaches 16 degrees down - which at these latitudes in
     * high summer is most nights - the whole of the dark period qualifies, and
     * that is correct rather than a fallback: it is exactly the condition that
     * makes the season.
     */
    fun window(latitude: Double, longitude: Double, atMillis: Long): NlcWindow? {
        if (!isSeason(latitude, atMillis)) return null

        val twilight = Twilight.darkness(latitude, longitude, atMillis, UPPER_DEGREES)
        val opens = when (twilight) {
            is Darkness.Now -> atMillis
            is Darkness.Later -> twilight.startsAt
            Darkness.None -> return null
        }
        val lightAgain = when (twilight) {
            is Darkness.Now -> twilight.endsAt
            is Darkness.Later -> twilight.endsAt
            Darkness.None -> null
        } ?: return null

        val deep = Twilight.darkness(latitude, longitude, opens, LOWER_DEGREES)
        val closes = when (deep) {
            // Deep twilight has already begun, so there is no evening window
            // left tonight - only the morning one, which is the same geometry
            // running the other way and is not what this prompt is for.
            is Darkness.Now -> return null
            is Darkness.Later -> minOf(deep.startsAt, lightAgain)
            Darkness.None -> lightAgain
        }

        if (closes <= opens) return null
        return NlcWindow(
            fromMillis = opens,
            untilMillis = closes,
            lookTowards = if (latitude >= 0) Compass.North else Compass.South,
        )
    }

    /**
     * Tonight's prompt, in the same shape as everything else that can
     * interrupt.
     *
     * Always [Visibility.Good] when it exists at all: unlike an eclipse, there
     * is no marginal case to describe. Either the geometry holds tonight or
     * there is nothing to say.
     */
    fun event(latitude: Double, longitude: Double, atMillis: Long): SkyEvent? {
        val window = window(latitude, longitude, atMillis) ?: return null
        return SkyEvent(
            kind = EventKind.Noctilucent,
            at = window.fromMillis,
            from = window.fromMillis,
            until = window.untilMillis,
            title = "Good conditions for noctilucent clouds",
            detail = "Look low in the ${window.lookTowards.label} for electric-blue clouds " +
                "that stay lit after everything else has gone dark. The sun is at the right " +
                "depth tonight and you are at the right latitude - but there is no way to " +
                "forecast whether the clouds are actually there, so this is a prompt to look " +
                "rather than a prediction that you will see them.",
            visibility = Visibility.Good,
        )
    }

    /** UTC throughout: a season measured in months does not care about an hour. */
    private fun dayOfYear(atMillis: Long): Int {
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = atMillis
        return calendar.get(Calendar.DAY_OF_YEAR)
    }
}
