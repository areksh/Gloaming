package sh.arek.gloaming.aurora

import sh.arek.gloaming.data.AuroraWatchStatus

/**
 * One number for how likely an aurora is here, whatever produced it.
 *
 * A single model on purpose: the rest of the app - the gates, the schedule, the
 * notification, the widget - never branches on which source is in play, so
 * losing the UK layer would cost the quality bump and nothing else. The
 * bounding box would simply always answer false and every user would fall
 * through to the global grid.
 */
data class Likelihood(val probability: Int, val source: Source) {

    /** Which reading the answer came from, for the UI to attribute honestly. */
    enum class Source { OVATION, AURORAWATCH }

    companion object {

        /**
         * The UK, generously drawn.
         *
         * Deliberately a little larger than the coastline. Before first unlock
         * the observer's position is rounded to a whole degree, so a box drawn
         * tightly would drop Shetland and Scilly on exactly the polls that
         * matter most. The cost of being generous is a few users in the North
         * Sea getting a UK reading, which is no worse than the grid alone.
         */
        const val UK_SOUTH = 49.0
        const val UK_NORTH = 61.5
        const val UK_WEST = -9.0
        const val UK_EAST = 2.5

        /**
         * AuroraWatch's four levels, on this app's own 0-100 scale.
         *
         * Their thresholds are untouched - their terms require that, and it is
         * their data. This maps the level they publish onto the scale the rest
         * of the app already speaks, which is a judgement about presentation
         * and not an adjustment of their alerting. Like the alert threshold, the
         * numbers want a season of real data to calibrate against.
         *
         * Green is not zero. It means their magnetometers see nothing unusual,
         * not that an aurora is impossible - and the grid is free to say more.
         */
        private val LEVELS = mapOf(
            "green" to 5,
            "yellow" to 30,
            "amber" to 55,
            "red" to 80,
        )

        fun forLevel(level: String): Int? = LEVELS[level.lowercase()]

        fun insideUk(latitude: Double, longitude: Double): Boolean =
            latitude in UK_SOUTH..UK_NORTH && longitude in UK_WEST..UK_EAST

        /**
         * The best available reading for an observer.
         *
         * Inside the UK the two are combined by taking the higher, so the UK
         * layer can only ever raise the answer. That is not a hedge: AuroraWatch
         * alerts come from a single alerting site which switches on data loss
         * and defaults to the Lancaster magnetometer, making them steadily more
         * pessimistic further north - and the north is where the aurora is. A
         * green status silencing a grid that says 65% over Shetland would be
         * the app failing at its one job.
         */
        fun evaluate(
            latitude: Double,
            longitude: Double,
            ovation: Int,
            aurorawatch: AuroraWatchStatus?,
        ): Likelihood {
            if (!insideUk(latitude, longitude)) return Likelihood(ovation, Source.OVATION)

            val uk = aurorawatch?.status?.let(::forLevel) ?: return Likelihood(ovation, Source.OVATION)

            return if (uk > ovation) {
                Likelihood(uk, Source.AURORAWATCH)
            } else {
                Likelihood(ovation, Source.OVATION)
            }
        }
    }
}
