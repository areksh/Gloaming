package sh.arek.gloaming.satellites

import kotlin.math.abs

/**
 * A launch still bunched into a train, as one pass.
 *
 * The document publishes only the two ends of each train, which is everything a
 * description needs: a train begins when the front of it clears the horizon and
 * is not over until the back of it has gone. Joining them here means everything
 * downstream - the rate limiting, the choosing, the notification - handles a
 * train as it handles any other pass, with no special case anywhere.
 *
 * Pure, like the rest of this package: same document, same position, same
 * window, same answer, with no clock and no network.
 */
object TrainFinder {

    /**
     * How far apart the two ends may peak and still be the same crossing.
     *
     * A train spans minutes of orbit at most - that is what makes it a train
     * rather than a plane of a constellation - while one pass of an object and
     * the next are ninety minutes apart. So this is wide enough for any train
     * and nowhere near wide enough to pair the front of one crossing with the
     * back of the following one.
     */
    private const val SAME_CROSSING_MILLIS: Long = 15 * 60 * 1000L

    /**
     * The trains in [document] that cross the sky at [latitude], [longitude].
     *
     * [minimumPeakElevation] is passed straight through, so the live tracker
     * can drop the bar the way it does for a single satellite: whoever is
     * following a train is already outside looking for it, and re-applying the
     * "worth mentioning" test at that point could only leave them there with
     * nothing.
     */
    fun passes(
        document: SatellitesDocument,
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        untilMillis: Long,
        minimumPeakElevation: Double = PassFinder.MINIMUM_PEAK_ELEVATION_DEGREES,
    ): List<SatellitePass> {
        if (document.trains.isEmpty()) return emptyList()
        val elements = document.satellites.associateBy { it.catalogue }

        return document.trains.flatMap { train ->
            val lead = elements[train.lead]
            val tail = elements[train.tail]
            // Both or neither. The sentence a train gets is "rising then, the
            // last clear by then", and half of it would be worse than silence.
            if (lead == null || tail == null) {
                emptyList()
            } else {
                join(
                    train,
                    lead,
                    tail,
                    latitude,
                    longitude,
                    fromMillis,
                    untilMillis,
                    minimumPeakElevation,
                )
            }
        }
    }

    private fun join(
        train: StarlinkTrain,
        lead: SatelliteElements,
        tail: SatelliteElements,
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        untilMillis: Long,
        minimumPeakElevation: Double,
    ): List<SatellitePass> {
        val find = { elements: SatelliteElements ->
            PassFinder.passes(
                elements = elements,
                latitude = latitude,
                longitude = longitude,
                fromMillis = fromMillis,
                // Past the end of the window, so a train whose front rises
                // inside it is not cut in half by where the search happened to
                // stop.
                untilMillis = untilMillis + SAME_CROSSING_MILLIS,
                minimumPeakElevation = minimumPeakElevation,
                channel = PassChannel.Trains,
            )
        }

        val tails = find(tail)
        return find(lead).mapNotNull { front ->
            val back = tails
                .filter { abs(it.peaksAt - front.peaksAt) <= SAME_CROSSING_MILLIS }
                .minByOrNull { abs(it.peaksAt - front.peaksAt) }
                ?: return@mapNotNull null

            // The front's geometry throughout, because every member crosses the
            // same piece of sky a few seconds apart: how high the train gets is
            // how high the front of it gets. Only the ending is the back's.
            front.copy(
                name = "Starlink train ${train.launch}",
                setsAt = back.setsAt,
                setAzimuth = back.setAzimuth,
                members = train.members,
            )
        }
    }
}
