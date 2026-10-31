package sh.arek.gloaming.domain

import sh.arek.gloaming.data.HourlyCover

/**
 * A stretch of tonight's darkness the forecast puts under the cloud tolerance.
 *
 * The number the app already had - "80% cloud" - is the wrong summary of a
 * night that is overcast until midnight and then clear for three hours, and it
 * is the second of those that decides whether anybody puts a coat on. This is
 * the same question the cloud gate asks, asked of the hours ahead instead of
 * the hour now.
 *
 * Everything here is arithmetic over a list. No clock, no network, no Android:
 * the hours arrive from the forecast that was fetched anyway.
 */
data class ClearSpell(
    /** When it starts, never earlier than now or the start of the darkness. */
    val from: Long,
    /** When it ends, never later than the end of the darkness. */
    val until: Long,
    /** Whether it has already begun, which changes what there is to say about it. */
    val underway: Boolean,
    /** Whether it covers the whole of tonight's darkness that is still to come. */
    val allNight: Boolean,
) {
    companion object {

        /** An hourly reading describes the hour that begins at its timestamp. */
        private const val HOUR_MILLIS: Long = 60 * 60 * 1000L

        /**
         * The first spell worth waiting for, or null if there is none.
         *
         * The first rather than the longest. Somebody reading this is deciding
         * whether to stay up, and the next gap is what that decision turns on -
         * a longer one at four in the morning is a different question and
         * usually a rhetorical one.
         *
         * @param hourly cover per hour, judged on [HourlyCover.effective], in any
         *   order, gaps allowed.
         * @param tolerance the user's own cloud tolerance, as a percentage.
         * @param darkFrom start of tonight's darkness.
         * @param darkUntil end of it.
         * @param nowMillis so a spell half behind you is reported from here.
         */
        fun first(
            hourly: List<HourlyCover>,
            tolerance: Int,
            darkFrom: Long,
            darkUntil: Long,
            nowMillis: Long,
        ): ClearSpell? {
            val windowFrom = maxOf(darkFrom, nowMillis)
            if (windowFrom >= darkUntil) return null

            // Sorted rather than assumed sorted. These come out of a cache file
            // that may have been written hours ago by a different version, and
            // an out-of-order array would produce a spell running backwards
            // rather than an obvious failure.
            val ordered = hourly.sortedBy { it.atMillis }

            var runFrom: Long? = null
            var runUntil = 0L

            for (entry in ordered) {
                val entryUntil = entry.atMillis + HOUR_MILLIS
                val clear = entry.effective <= tolerance &&
                    entry.atMillis < darkUntil &&
                    entryUntil > windowFrom

                // A missing hour breaks the run rather than joining what is
                // either side of it: calling 20:00 and 22:00 one continuous
                // spell would be inventing 21:00.
                val continues = clear && runFrom != null && entry.atMillis == runUntil

                when {
                    continues -> runUntil = entryUntil

                    // A run that is open and not continued has ended, and the
                    // first one is the one wanted - so stop here rather than
                    // going on to find a better. Ahead of the branch that
                    // starts a run, or the clear hour after a gap would
                    // silently replace the run the gap just ended.
                    runFrom != null -> return spell(runFrom, runUntil, windowFrom, darkUntil)

                    clear -> {
                        runFrom = entry.atMillis
                        runUntil = entryUntil
                    }

                    else -> Unit
                }
            }

            return runFrom?.let { spell(it, runUntil, windowFrom, darkUntil) }
        }

        private fun spell(
            from: Long,
            until: Long,
            windowFrom: Long,
            darkUntil: Long,
        ): ClearSpell {
            val start = maxOf(from, windowFrom)
            val end = minOf(until, darkUntil)
            return ClearSpell(
                from = start,
                until = end,
                underway = start <= windowFrom,
                allNight = start <= windowFrom && end >= darkUntil,
            )
        }
    }
}
