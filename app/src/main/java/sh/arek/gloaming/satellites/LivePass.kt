package sh.arek.gloaming.satellites

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG
import kotlin.math.roundToInt

/** Where a satellite is at one instant, from where the observer is standing. */
data class LiveFix(
    /** Degrees clockwise from north. */
    val azimuth: Double,
    /** Degrees above the horizon; negative below it. */
    val elevation: Double,
    /** Slant range in kilometres. */
    val rangeKm: Double,
    /** Whether the sun is still on it, and so whether there is anything to see. */
    val sunlit: Boolean,
)

/**
 * One satellite, one observer, asked repeatedly.
 *
 * Held as an object rather than a function because setting the propagator up
 * costs real work and following a pass asks it the same question once a second
 * for ten minutes. The elements and the observer cannot change during a pass -
 * a pass is a few minutes long, and re-fetching mid-pass would move the very
 * numbers the user is watching.
 */
class LiveTracker(
    elements: SatelliteElements,
    latitude: Double,
    longitude: Double,
) {
    private val observer = GeoPos(latitude, longitude, 0.0)
    private val orbital = elements.toOrbitalData().getObject()

    fun fixAt(atMillis: Long): LiveFix {
        val position = orbital.getPosition(observer, atMillis)
        return LiveFix(
            azimuth = position.azimuth * RAD2DEG,
            elevation = position.elevation * RAD2DEG,
            rangeKm = position.distance,
            sunlit = !position.eclipsed,
        )
    }
}

/**
 * Following a pass while it happens.
 *
 * The case for this, from the design: a foreground service started ten minutes
 * before a pass **owns its own clock**, so the moment that actually matters -
 * the satellite clearing the horizon - never depends on the system waking
 * anything precisely. The alarm that starts it can be as inexact as Android
 * likes.
 *
 * Nothing here touches the network. Everything a live pass needs is already on
 * the device before it starts, which is what makes it safe to run with the
 * screen off in a field with no signal.
 */
object LivePass {

    /**
     * How long before the rise the tracker starts, when nobody has said
     * otherwise.
     *
     * The number lives in [sh.arek.gloaming.data.UserSettings] now: long enough
     * to get outside and let your eyes adjust, short enough that a foreground
     * service is not running for half an hour, and which of those matters more
     * depends on how far the user has to walk. This is the fallback for callers
     * with no settings to hand.
     */
    const val LEAD_MILLIS: Long = 10 * 60 * 1000L

    /** When to start following [pass]. */
    fun startAt(pass: SatellitePass, leadMillis: Long = LEAD_MILLIS): Long =
        startAt(pass.risesAt, leadMillis)

    /**
     * The same, from the rise alone.
     *
     * The notification action carries the rise and not the whole pass, because
     * a pass is re-derived at the moment of following and there is no sense in
     * shipping a copy of it through a pending intent to be thrown away.
     */
    fun startAt(risesAt: Long, leadMillis: Long = LEAD_MILLIS): Long = risesAt - leadMillis

    /**
     * Whether the tracker should start immediately rather than wait for a wake.
     *
     * The alert goes out thirty minutes ahead and the tracker wants ten, so
     * whoever taps straight away has twenty minutes to wait and whoever reads
     * it late has none. Starting at once in the late case is not merely
     * convenient: from Android 12 a foreground service cannot generally be
     * started from the background, and a notification the user has just pressed
     * is one of the exemptions - so the tap is the best moment there will be.
     */
    fun startsNow(risesAt: Long, now: Long, leadMillis: Long = LEAD_MILLIS): Boolean =
        now >= startAt(risesAt, leadMillis)

    /** Whether there is any of [pass] left to watch at [now]. */
    fun isWorthFollowing(pass: SatellitePass, now: Long): Boolean = now <= pass.setsAt

    /**
     * Whether a request written down before a reboot is still worth putting
     * back afterwards.
     *
     * Judged on the peak because the peak is what identifies a pass everywhere
     * else in the app, and because it is the right line anyway: past the
     * highest point there is a minute or two left of a low, fading object, and
     * a phone that has just finished booting has spent most of that already.
     */
    fun stillPending(peaksAt: Long, now: Long): Boolean = now <= peaksAt

    /**
     * The pass among [passes] that is the one identified by [expectedPeakAt].
     *
     * The tracker starts ten minutes before a pass it was told about half an
     * hour earlier, and re-derives it from whatever elements the device holds
     * by then - which is the point, since those may be newer. So the moment it
     * finds is not the moment the user was told, and the two have to be matched
     * approximately.
     *
     * Null past [tolerance]: a pass does not move by that much, so something
     * further away is a different pass, and following the wrong object is
     * worse than following none.
     */
    fun chosen(
        passes: List<SatellitePass>,
        expectedPeakAt: Long,
        tolerance: Long,
    ): SatellitePass? = passes
        .filter { kotlin.math.abs(it.peaksAt - expectedPeakAt) <= tolerance }
        .minByOrNull { kotlin.math.abs(it.peaksAt - expectedPeakAt) }

    /**
     * The one line on the ongoing notification.
     *
     * Written for somebody holding the phone at arm's length in the dark:
     * which way to look, how far up, and how long they have. The shadow clause
     * matters more than it looks - a satellite that goes out part-way across
     * surprises people who were told to look at a particular time, and being
     * told it has happened is the difference between "it is gone" and "I must
     * be looking in the wrong place".
     */
    fun describe(fix: LiveFix, pass: SatellitePass, now: Long): String {
        if (now > pass.setsAt) return "Gone."

        val body = if (now < pass.risesAt) {
            "Rises ${Compass.of(pass.riseAzimuth).label} in ${countdown(pass.risesAt - now)}"
        } else {
            val timing = if (now < pass.peaksAt) {
                "highest in ${countdown(pass.peaksAt - now)}"
            } else {
                "sets in ${countdown(pass.setsAt - now)}"
            }
            "Look ${Compass.of(fix.azimuth).label}, " +
                "${fix.elevation.roundToInt()} degrees up, $timing"
        }

        val up = now >= pass.risesAt
        return if (up && !fix.sunlit) "$body. In the Earth's shadow." else "$body."
    }

    /**
     * Minutes and seconds, with the seconds padded.
     *
     * Padded because the text is rewritten every second: "2m 5s" and "2m 15s"
     * are different widths, and a line that changes width as it counts reads as
     * a glitch rather than a clock.
     */
    fun countdown(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 500L) / 1000L
        if (seconds < 60L) return "${seconds}s"
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return "${minutes}m ${remainder.toString().padStart(2, '0')}s"
    }
}
