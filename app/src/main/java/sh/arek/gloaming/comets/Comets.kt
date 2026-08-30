package sh.arek.gloaming.comets

import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.UserSettings

/** What one comet is worth on one night, from where the observer stands. */
data class CometNight(
    val comet: PublishedComet,
    /** The best moment of the nearest night. */
    val best: CometSighting,
    /** The magnitude the app will quote, or null if none can be. */
    val magnitude: Double?,
    /** Whether that figure rests on somebody's measurement rather than a formula. */
    val measured: Boolean,
    /** How much of the night's darkness the moon spoils, 0 to 1. */
    val moonInterference: Double,
)

/**
 * Comets, as the calendar and the tonight list see them.
 *
 * A comet fits neither shape the app already had. An eclipse is dated and
 * sparse, so it goes in the calendar; a satellite pass is nightly and dense, so
 * it goes on tonight's list. A comet arrives, hangs about for weeks moving a
 * little each night, brightens and fades. So it uses both surfaces for
 * different things: one calendar entry for the window it is worth planning
 * around, and a tonight entry whenever it is actually up.
 *
 * Nothing new was needed for either. Both already take a [SkyEvent] carrying a
 * [Visibility] verdict, so starring, filtering and alerting all work without
 * new machinery - which is the same argument ADR-0009 makes for not building an
 * event engine.
 */
object Comets {

    /** A year, matching the calendar's own horizon. */
    const val HORIZON_DAYS: Int = 365

    /**
     * How finely the year is swept looking for a comet's best weeks.
     *
     * A comet's brightness changes over weeks rather than hours, so a week is
     * fine enough to find the peak and coarse enough that thirty comets over a
     * year stays cheap. The night it picks is then evaluated properly.
     */
    private const val SAMPLE_DAYS: Int = 7

    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * How much of a comet's window either side of the peak is worth listing,
     * expressed as a magnitude allowance.
     *
     * The window runs while the comet is within the user's threshold, and the
     * threshold is a hard edge - so without a little slack a comet that hovers
     * at exactly the threshold would flicker in and out of the calendar week by
     * week. Half a magnitude is under the error in the prediction anyway.
     */
    private const val EDGE_SLACK: Double = 0.5

    /**
     * How far forward to look when the best night turns out to be behind us.
     *
     * [Twilight.nightNearest] picks the night whose middle is closest, which is
     * right for a dated event - an eclipse at midday belongs to the evening
     * after it. A comet is not dated, so at eight in the morning the nearest
     * night is the one that has just ended, and the calendar would offer a
     * moment in the past. Eighteen hours from now lands squarely in the coming
     * night from any time of day.
     */
    private const val LOOK_FORWARD_MILLIS: Long = 18 * 60 * 60 * 1000L

    /**
     * The absolute magnitude to predict with, corrected by what people have
     * actually seen.
     *
     * The published absolute magnitude feeds a two-hundred-year-old law that
     * assumes a comet brightens smoothly as it nears the sun, and comets do not
     * oblige. Where there is a recent measurement, the difference between it
     * and what the law predicted for that same night is carried across to every
     * other night - which is what an observer means by refitting the light
     * curve, done with one point instead of a hundred.
     *
     * It matters enormously in practice. On 2026-08-25 the law put
     * 220P/McNaught at magnitude 15.7 and thirty-nine observers had it at 9.3.
     * Without the correction the app would say 9.3 tonight and 15.7 next week,
     * which is not a forecast so much as a contradiction.
     *
     * Still a prediction, and still says so. A single point cannot tell a
     * genuine outburst from an unusually generous estimate, so the text this
     * feeds never claims more than that a measurement was taken into account.
     */
    fun effectiveAbsoluteMagnitude(
        comet: PublishedComet,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
        staleAfterMillis: Long,
    ): Double? {
        val published = comet.magnitudeH
        val observed = comet.observed
        if (observed == null || !comet.hasFreshObservation(nowMillis, staleAfterMillis)) {
            return published
        }
        if (published == null) {
            // Nothing to correct, but a measurement on its own is still worth
            // more than silence: treat it as the absolute magnitude that would
            // have produced it, so the comet at least has a place on the scale.
            return backFromObservation(comet, latitude, longitude, observed)
        }

        val whenSeen = CometFinder.positionOf(
            comet.orbit,
            published,
            comet.slope,
            latitude,
            longitude,
            observed.atMillis,
        )
        val predicted = whenSeen.magnitude ?: return published
        return published + (observed.magnitude - predicted)
    }

    /** The absolute magnitude implied by one measurement, for a comet with none. */
    private fun backFromObservation(
        comet: PublishedComet,
        latitude: Double,
        longitude: Double,
        observed: ObservedMagnitude,
    ): Double? {
        val seen = CometFinder.positionOf(
            comet.orbit,
            0.0,
            comet.slope,
            latitude,
            longitude,
            observed.atMillis,
        )
        // Computed with H = 0, so what comes back is the whole of the distance
        // terms and the difference is the absolute magnitude itself.
        val distanceTerms = seen.magnitude ?: return null
        return observed.magnitude - distanceTerms
    }

    /** Everything known about one comet on the night nearest [aroundMillis]. */
    fun nightFor(
        comet: PublishedComet,
        latitude: Double,
        longitude: Double,
        settings: UserSettings,
        aroundMillis: Long,
    ): CometNight? {
        val absolute = effectiveAbsoluteMagnitude(
            comet,
            latitude,
            longitude,
            aroundMillis,
            settings.cometObservationStaleMillis,
        )
        val best = CometFinder.bestInDarkness(
            comet.orbit,
            absolute,
            comet.slope,
            latitude,
            longitude,
            aroundMillis,
        ) ?: return null

        return CometNight(
            comet = comet,
            best = best,
            magnitude = best.magnitude,
            measured = comet.hasFreshObservation(
                aroundMillis,
                settings.cometObservationStaleMillis,
            ),
            moonInterference = Twilight.moonInterference(latitude, longitude, aroundMillis),
        )
    }

    /**
     * One entry per comet, for the window it is worth planning around.
     *
     * A comet has no moment the way an eclipse does, so the calendar has to
     * invent one: the night in the year ahead when it stands highest in a dark
     * sky while inside the user's brightness threshold. The window either side
     * is how long it stays within that threshold, which is the part somebody
     * would arrange a trip around.
     */
    fun calendar(
        document: CometsDocument?,
        latitude: Double,
        longitude: Double,
        settings: UserSettings,
        nowMillis: Long,
    ): List<SkyEvent> =
        byComet(document, latitude, longitude, settings, nowMillis).values.sortedBy { it.at }

    /**
     * The same entries, kept against the comet that produced each.
     *
     * The poller needs this rather than the plain list: to say a comet has
     * faded it has to know which comet an announcement was about, and a
     * [SkyEvent] deliberately carries no identity beyond its kind and its
     * moment. Keyed on the name rather than the MPC designation because the
     * name is what a fade notice has to print, and a comet that has dropped out
     * of the document altogether leaves nothing else to print it from.
     */
    fun byComet(
        document: CometsDocument?,
        latitude: Double,
        longitude: Double,
        settings: UserSettings,
        nowMillis: Long,
    ): Map<String, SkyEvent> {
        val comets = document?.comets ?: return emptyMap()
        val threshold = settings.cometMagnitudeThreshold.toDouble()

        return comets.mapNotNull { comet ->
            val absolute = effectiveAbsoluteMagnitude(
                comet,
                latitude,
                longitude,
                nowMillis,
                settings.cometObservationStaleMillis,
            ) ?: return@mapNotNull null

            val window = worthwhileWindow(comet, absolute, latitude, longitude, nowMillis, threshold)
                ?: return@mapNotNull null

            val night = comingNight(comet, latitude, longitude, settings, window.brightestAt, nowMillis)
                ?: return@mapNotNull null

            // The window has to contain the moment it is a window around. The
            // peak is found by searching a night and the edges by sampling a
            // week at a time, so the two can disagree by a few hours at either
            // end - which reads as a calendar entry dated outside its own span.
            comet.name to event(
                night,
                settings,
                from = minOf(window.from, night.best.atMillis),
                until = maxOf(window.until, night.best.atMillis),
            )
        }.toMap()
    }

    /**
     * The comets up tonight, for the list somebody reads standing outside.
     *
     * Evaluated for tonight rather than reusing the calendar entry, because the
     * calendar entry is about the best night of the year and what is wanted
     * here is where to look now.
     */
    fun tonight(
        document: CometsDocument?,
        latitude: Double,
        longitude: Double,
        settings: UserSettings,
        nowMillis: Long,
    ): List<SkyEvent> {
        val comets = document?.comets ?: return emptyList()
        val threshold = settings.cometMagnitudeThreshold.toDouble()

        return comets
            .mapNotNull { nightFor(it, latitude, longitude, settings, nowMillis) }
            .filter { night ->
                val magnitude = night.magnitude ?: return@filter false
                magnitude <= threshold && night.best.altitude > 0.0
            }
            .map { night ->
                event(
                    night,
                    settings,
                    from = night.best.atMillis - DAY_MILLIS / 2,
                    until = night.best.atMillis + DAY_MILLIS / 2,
                )
            }
            .sortedBy { it.at }
    }

    /**
     * The best night at or after [nowMillis], never one already over.
     *
     * The calendar is about the year ahead, so a moment in the past is not an
     * answer to anything - and it is easy to produce, because the brightest
     * sample is often now and the night nearest now is often the one that has
     * just ended.
     */
    private fun comingNight(
        comet: PublishedComet,
        latitude: Double,
        longitude: Double,
        settings: UserSettings,
        aroundMillis: Long,
        nowMillis: Long,
    ): CometNight? {
        val found = nightFor(comet, latitude, longitude, settings, aroundMillis)
        if (found != null && found.best.atMillis >= nowMillis) return found
        return nightFor(comet, latitude, longitude, settings, nowMillis + LOOK_FORWARD_MILLIS)
    }

    private data class Window(val from: Long, val until: Long, val brightestAt: Long)

    /**
     * When the comet is inside the threshold, and when it is at its best.
     *
     * Swept a week at a time from now to the horizon. Only the distances matter
     * here, so this does not care where the observer is standing - it is asking
     * whether the comet is bright enough to be worth a calendar entry at all,
     * not whether it is up.
     */
    private fun worthwhileWindow(
        comet: PublishedComet,
        absoluteMagnitude: Double,
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
        threshold: Double,
    ): Window? {
        var from: Long? = null
        var until: Long? = null
        var brightestAt = nowMillis
        var brightest = Double.MAX_VALUE

        var day = 0
        while (day <= HORIZON_DAYS) {
            val at = nowMillis + day * DAY_MILLIS
            val magnitude = CometFinder.positionOf(
                comet.orbit,
                absoluteMagnitude,
                comet.slope,
                latitude,
                longitude,
                at,
            ).magnitude

            if (magnitude != null) {
                if (magnitude < brightest) {
                    brightest = magnitude
                    brightestAt = at
                }
                if (magnitude <= threshold + EDGE_SLACK) {
                    if (from == null) from = at
                    until = at
                }
            }
            day += SAMPLE_DAYS
        }

        if (brightest > threshold) return null
        val start = from ?: return null
        return Window(start, until ?: start, brightestAt)
    }

    /**
     * The verdict and the words.
     *
     * The reasons are kept apart because they call for different answers: a
     * comet washed out by moonlight will be worth seeing in a fortnight, and
     * one that never clears the roofs will not be worth seeing at all.
     */
    private fun event(
        night: CometNight,
        settings: UserSettings,
        from: Long,
        until: Long,
    ): SkyEvent {
        val name = night.comet.name
        val bar = settings.minimumCometAltitudeDegrees.toDouble()
        val altitude = night.best.altitude
        val towards = Compass.of(night.best.azimuth).label

        val visibility = when {
            altitude <= 0.0 -> Visibility.NotFromHere(
                "$name never clears the horizon here while the sky is dark.",
            )

            altitude < bar -> Visibility.Marginal(
                "It only reaches ${altitude.roundToInt()} degrees, low in the $towards, " +
                    "and a comet low down is lost in the haze in a way a planet is not.",
            )

            night.moonInterference > settings.cometMoonFraction -> Visibility.Marginal(
                "The moon is up for much of the night and ${moonWords(night.moonInterference)}. " +
                    "A comet is a smudge rather than a point, so moonlight washes it out " +
                    "far more than it does a star.",
            )

            !night.measured -> Visibility.Marginal(
                "Nobody has reported this one recently, so the brightness is the formula's " +
                    "guess and comets routinely miss it by a magnitude either way.",
            )

            else -> Visibility.Good
        }

        return SkyEvent(
            kind = EventKind.Comet,
            at = night.best.atMillis,
            from = from,
            until = until,
            title = "Comet $name",
            detail = detail(night),
            visibility = visibility,
        )
    }

    private fun moonWords(interference: Double): String = when {
        interference >= 0.75 -> "close to full"
        interference >= 0.4 -> "well lit"
        else -> "bright enough to matter"
    }

    private fun detail(night: CometNight): String = buildString {
        val magnitude = night.magnitude
        if (magnitude != null) {
            append("Magnitude ${format(magnitude)}")
            val observed = night.comet.observed
            if (night.measured && observed != null) {
                append(", from ${observed.observers} ")
                append(if (observed.observers == 1) "observer" else "observers")
                // The span is worth printing: seven observers over five days
                // and two over a fortnight deserve different amounts of trust,
                // and the app is in no position to weigh that for somebody.
                if (observed.windowDays > 0) {
                    append(" over ${observed.windowDays} days")
                } else {
                    append(" within the last few days")
                }
            } else {
                append(", predicted rather than measured")
            }
            append(". ")
        }
        append("Highest at ${night.best.altitude.roundToInt()} degrees ")
        append("in the ${Compass.of(night.best.azimuth).label}")
        // The strongest case in the app for naming a pattern. A comet is a
        // faint smudge at a position nobody has memorised, so "in Pegasus" is
        // worth more than the altitude and the bearing put together. Free
        // here: the sighting already carries J2000 coordinates, which is the
        // frame the lookup wants.
        append(", in ${Constellations.containing(
            night.best.rightAscensionDegrees / 15.0,
            night.best.declinationDegrees,
        )}.")
    }

    private fun format(value: Double): String =
        String.format(java.util.Locale.UK, "%.1f", value)
}
