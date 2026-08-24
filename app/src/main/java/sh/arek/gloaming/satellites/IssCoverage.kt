package sh.arek.gloaming.satellites

import kotlin.math.abs

/**
 * Whether the space station reaches the sky above a given latitude.
 *
 * The prediction does not need this: [PassFinder] asks the propagator whether
 * an orbit can reach the observer at all, and a station pass over Tromso is
 * simply never found. What is missing without this is the explanation. A user
 * who is never told about the ISS cannot tell an app that has decided there is
 * nothing to see from an app that is broken, and the second reading is the one
 * people reach for.
 *
 * The station's orbit is inclined 51.6 degrees, so its ground track never goes
 * beyond that. Visibility runs a few degrees further, because a satellite 400 km
 * up is above the horizon well before it is overhead - which is why the two
 * thresholds here are wider than the inclination and why the band between them
 * is called marginal rather than covered.
 */
enum class IssCoverage {
    /** Passes happen here, and regularly. */
    Covered,

    /** Past the ground track: low, brief passes, and not every week. */
    Marginal,

    /** The station never comes above the horizon. */
    None,

    ;

    companion object {

        /** The station's orbital inclination, degrees. Quoted to the user. */
        const val INCLINATION_DEGREES: Double = 51.6

        /** Past the ground track, where passes get low and occasional. */
        const val MARGINAL_ABOVE_DEGREES: Double = 57.0

        /** Beyond the reach of a 400 km orbit inclined at 51.6 degrees. */
        const val NONE_ABOVE_DEGREES: Double = 60.0

        /**
         * How well [latitude] is served, north or south.
         *
         * Both thresholds approximate a fuzzy edge, so each boundary value
         * falls in the more generous band: a marginal pass that does not
         * materialise disappoints less than being told there is nothing when
         * there is.
         */
        fun of(latitude: Double): IssCoverage = when {
            abs(latitude) > NONE_ABOVE_DEGREES -> None
            abs(latitude) >= MARGINAL_ABOVE_DEGREES -> Marginal
            else -> Covered
        }
    }
}
