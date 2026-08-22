package sh.arek.gloaming.satellites

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG

/**
 * One satellite crossing one observer's sky, from horizon to horizon.
 *
 * Angles in degrees, times in unix milliseconds, range in kilometres. Nothing
 * here says whether the pass is worth watching - that needs the observer's own
 * darkness as well, and belongs to [PassDescription].
 */
data class SatellitePass(
    val catalogue: Int,
    val name: String,
    val risesAt: Long,
    val riseAzimuth: Double,
    val peaksAt: Long,
    val peakElevation: Double,
    val peakAzimuth: Double,
    val setsAt: Long,
    val setAzimuth: Double,
    /** Slant range at the highest point, in kilometres. */
    val rangeAtPeakKm: Double,
    /** Whether the satellite is in sunlight when it is highest. */
    val sunlitAtPeak: Boolean,
    /**
     * When the satellite crosses into the Earth's shadow mid-pass, if it does.
     *
     * This is the moment it visibly goes out, part-way up the sky, and it
     * surprises people who were told to look at a particular time.
     */
    val entersShadowAt: Long?,
) {
    val durationMillis: Long get() = setsAt - risesAt
}

/**
 * Finds passes by searching the propagator.
 *
 * The vendored predict package answers for a single instant - position,
 * elevation, eclipse state - and offers no search of its own, so this is the
 * one piece of new astronomy in the satellite feature. It is pure: given the
 * same elements, position and window it returns the same passes, with no clock
 * and no network anywhere in it.
 *
 * The method is the obvious one, done carefully. Step through the window
 * looking for the elevation changing sign, then bisect each crossing to the
 * second; find the highest point by narrowing the interval around it. Bisection
 * is safe here because elevation is continuous and crosses zero exactly twice
 * per pass, and the peak search is safe because a pass has one maximum.
 *
 * Cost, honestly: a coarse step of half a minute over twelve hours is about
 * 1,400 propagations per satellite, and the whole `visual` group is a few
 * seconds of CPU. That is a background job run once a day, not something on the
 * path of a screen opening. [OrbitalObject.willBeSeen] throws out anything
 * whose orbit cannot reach the observer's latitude before any of it happens.
 */
object PassFinder {

    /**
     * The default bar for a pass being worth mentioning.
     *
     * Below about ten degrees a satellite is behind whatever the observer has
     * on their horizon - houses, trees, a hill - and telling them to go out for
     * it wastes the one thing the app is asking for, which is their attention.
     */
    const val MINIMUM_PEAK_ELEVATION_DEGREES: Double = 10.0

    /**
     * The coarse search step.
     *
     * Half a minute cannot step over a pass: the shortest horizon-to-horizon
     * crossing in low orbit lasts several minutes. Larger steps would be
     * cheaper and would eventually miss one entirely.
     */
    private const val COARSE_STEP_MILLIS = 30_000L

    /** Bisection stops here. Half a second is well inside any useful accuracy. */
    private const val PRECISION_MILLIS = 500L

    /**
     * How far past the end of the window a pass already under way may run.
     *
     * A pass that starts inside the window is reported whole rather than cut
     * off at an arbitrary boundary; nothing in low orbit stays up for half an
     * hour.
     */
    private const val OVERRUN_MILLIS = 30 * 60 * 1000L

    /**
     * Every pass that begins between [fromMillis] and [untilMillis].
     *
     * A pass already under way at [fromMillis] is left out: its rise time is
     * outside the window and reporting a made-up one would be worse than
     * silence.
     */
    fun passes(
        elements: SatelliteElements,
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        untilMillis: Long,
        minimumPeakElevation: Double = MINIMUM_PEAK_ELEVATION_DEGREES,
    ): List<SatellitePass> {
        val observer = GeoPos(latitude, longitude, 0.0)
        val orbital = elements.toOrbitalData().getObject()
        if (!orbital.willBeSeen(observer)) return emptyList()

        val elevation = { at: Long -> orbital.getElevation(observer, at) * RAD2DEG }
        val found = mutableListOf<SatellitePass>()

        var previousTime = fromMillis
        var previous = elevation(fromMillis)
        var time = fromMillis + COARSE_STEP_MILLIS

        while (previousTime < untilMillis) {
            val current = elevation(time)

            if (previous <= 0.0 && current > 0.0) {
                val risesAt = crossing(elevation, previousTime, time)
                val setsAt = descent(elevation, time, risesAt)
                val pass = describe(orbital, observer, elements, risesAt, setsAt)

                if (pass.peakElevation >= minimumPeakElevation) found += pass

                // Resume past the pass rather than walking back through it.
                previousTime = setsAt
                previous = elevation(setsAt)
                time = setsAt + COARSE_STEP_MILLIS
                continue
            }

            previousTime = time
            previous = current
            time += COARSE_STEP_MILLIS
        }
        return found
    }

    /** Follows a pass already begun at [after] down to the horizon. */
    private fun descent(elevation: (Long) -> Double, after: Long, risesAt: Long): Long {
        var previousTime = after
        var time = after + COARSE_STEP_MILLIS
        val limit = risesAt + OVERRUN_MILLIS

        while (time <= limit) {
            if (elevation(time) <= 0.0) return crossing(elevation, previousTime, time)
            previousTime = time
            time += COARSE_STEP_MILLIS
        }
        // Nothing in low orbit stays up this long. Rather than loop for ever,
        // treat the horizon as reached: the caller's elevation bar will throw
        // out anything nonsensical that results.
        return limit
    }

    /**
     * Bisects a bracketed horizon crossing.
     *
     * The interval is known to contain exactly one, from the coarse step's sign
     * change, so bisection cannot converge on the wrong root.
     */
    private fun crossing(elevation: (Long) -> Double, before: Long, after: Long): Long {
        var low = before
        var high = after
        val rising = elevation(before) <= 0.0

        while (high - low > PRECISION_MILLIS) {
            val middle = low + (high - low) / 2
            val above = elevation(middle) > 0.0
            if (above == rising) high = middle else low = middle
        }
        return low + (high - low) / 2
    }

    /**
     * The highest point of a pass, by narrowing the interval around it.
     *
     * A pass has a single maximum, so comparing two interior points says which
     * third of the interval the peak cannot be in. Thirty rounds take a
     * ten-minute pass down to well under a second.
     */
    private fun highest(elevation: (Long) -> Double, risesAt: Long, setsAt: Long): Long {
        var low = risesAt
        var high = setsAt

        while (high - low > PRECISION_MILLIS) {
            val third = (high - low) / 3
            val first = low + third
            val second = high - third
            if (elevation(first) < elevation(second)) low = first else high = second
        }
        return low + (high - low) / 2
    }

    /** Fills in the details of a pass whose ends are already known. */
    private fun describe(
        orbital: OrbitalObject,
        observer: GeoPos,
        elements: SatelliteElements,
        risesAt: Long,
        setsAt: Long,
    ): SatellitePass {
        val elevation = { at: Long -> orbital.getElevation(observer, at) * RAD2DEG }
        val peaksAt = highest(elevation, risesAt, setsAt)

        val rise = orbital.getPosition(observer, risesAt)
        val peak = orbital.getPosition(observer, peaksAt)
        val set = orbital.getPosition(observer, setsAt)

        return SatellitePass(
            catalogue = elements.catalogue,
            name = elements.name,
            risesAt = risesAt,
            riseAzimuth = rise.azimuth * RAD2DEG,
            peaksAt = peaksAt,
            peakElevation = peak.elevation * RAD2DEG,
            peakAzimuth = peak.azimuth * RAD2DEG,
            setsAt = setsAt,
            setAzimuth = set.azimuth * RAD2DEG,
            rangeAtPeakKm = peak.distance,
            sunlitAtPeak = !peak.eclipsed,
            entersShadowAt = shadowEntry(orbital, observer, risesAt, setsAt),
        )
    }

    /**
     * When the satellite goes into the Earth's shadow during the pass.
     *
     * Only an entry, never an exit: a satellite that rises already dark and
     * lights up later is a morning pass, and nobody is standing outside waiting
     * for one. Eclipse state changes at most once across a few minutes of low
     * orbit, so the same bisection works on it.
     */
    private fun shadowEntry(
        orbital: OrbitalObject,
        observer: GeoPos,
        risesAt: Long,
        setsAt: Long,
    ): Long? {
        val eclipsed = { at: Long -> orbital.getPosition(observer, at).eclipsed }
        if (eclipsed(risesAt) || !eclipsed(setsAt)) return null

        var low = risesAt
        var high = setsAt
        while (high - low > PRECISION_MILLIS) {
            val middle = low + (high - low) / 2
            if (eclipsed(middle)) high = middle else low = middle
        }
        return low + (high - low) / 2
    }
}
