package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.Noctilucent
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.SatellitesDocument
import sh.arek.gloaming.satellites.TrainFinder

/** What there is to see between now and the end of tonight. */
data class TonightList(
    /** When the sky is dark enough to be worth going out, if it will be. */
    val darkFrom: Long?,
    val darkUntil: Long?,
    /** Every pass that clears the bar in sunlight, in time order. */
    val passes: List<SatellitePass>,
    /** The few worth leading with, also in time order. */
    val bestPasses: List<SatellitePass>,
    /** Calendar events whose window falls inside tonight. */
    val events: List<SkyEvent>,
    /**
     * Comets up tonight, worked out for tonight rather than taken from the
     * calendar.
     *
     * The calendar entry for a comet is about the best night of the year, which
     * is the wrong thing to read standing in a garden: what is wanted here is
     * where to look now. Same comet, different question.
     */
    val comets: List<SkyEvent>,
    /** Tonight's noctilucent prompt, in season and at the right latitude. */
    val noctilucent: SkyEvent?,
) {
    val isEmpty: Boolean
        get() = passes.isEmpty() && events.isEmpty() && comets.isEmpty() && noctilucent == null
}

/**
 * Tonight, which is the one thing the calendar cannot hold.
 *
 * A year of eclipses and oppositions fits in a list; a year of satellite passes
 * does not - a clear night here has upwards of forty worth looking at, so a
 * calendar of them would run to tens of thousands of entries and be useless at
 * every scale. Yet standing outside at nine wondering what is crossing is
 * exactly the question the app exists for, and until now it could only be
 * answered by waiting to be interrupted.
 *
 * So the split is by shape rather than by feature: what is dated and sparse
 * goes in the calendar, and what is nightly and dense goes here.
 *
 * Everything is computed from what the device already holds. No network.
 */
object Tonight {

    /**
     * How far ahead to look when it is the middle of the day.
     *
     * Long enough to reach the coming night from any point in the afternoon,
     * and short enough that "tonight" does not quietly become "this week".
     */
    private const val HORIZON_MILLIS: Long = 24 * 60 * 60 * 1000L

    /**
     * The passes worth listing rather than every one above the horizon.
     *
     * A lower bar than an alert uses. Somebody reading a list has chosen to
     * look and can skim past a mediocre pass; the same pass arriving as a
     * notification has interrupted them and has to earn it.
     */
    const val LISTED_BAR_DEGREES: Double = 10.0

    fun of(
        satellites: SatellitesDocument?,
        comets: CometsDocument?,
        calendar: List<SkyEvent>,
        settings: UserSettings,
        nowMillis: Long,
    ): TonightList {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (latitude == null || longitude == null) {
            return TonightList(null, null, emptyList(), emptyList(), emptyList(), emptyList(), null)
        }

        val night = Twilight.nightNearest(latitude, longitude, nowMillis)
        // The window to describe: tonight's darkness where there is some, and
        // otherwise the next day, so a midsummer night at high latitude still
        // lists what crosses rather than going blank.
        val from = maxOf(night?.first ?: nowMillis, nowMillis)
        val until = night?.last ?: (nowMillis + HORIZON_MILLIS)

        val passes = satellites
            ?.let { visiblePasses(it, latitude, longitude, from, until, settings) }
            .orEmpty()

        return TonightList(
            darkFrom = night?.first,
            darkUntil = night?.last,
            passes = passes,
            bestPasses = best(passes),
            // Marginal stays: somebody looking at a list of tonight has already
            // decided to go out, and "the moon is low at maximum" is what they
            // need to plan around rather than a reason to be told nothing.
            // Only what cannot be seen from here at all is dropped.
            // The calendar's own comet entries are dropped here rather than
            // listed twice: they describe the best night of the year, and the
            // list below answers the same comet for tonight.
            events = calendar.filter {
                it.overlaps(from, until) &&
                    it.visibility !is Visibility.NotFromHere &&
                    it.kind != EventKind.Comet
            },
            comets = Comets
                .tonight(comets, latitude, longitude, settings, nowMillis)
                .filter { it.visibility !is Visibility.NotFromHere },
            noctilucent = Noctilucent.event(latitude, longitude, nowMillis),
        )
    }

    /**
     * An event belongs to tonight if any part of it does.
     *
     * A shower peak runs the whole night and an eclipse an hour of it, so
     * comparing the moment alone would drop the shower somebody is standing
     * outside for.
     */
    private fun SkyEvent.overlaps(from: Long, until: Long): Boolean =
        this.from <= until && this.until >= from

    /**
     * The handful to lead with out of a night that can hold hundreds.
     *
     * Listing the first few by time answers "what happens next", which is not
     * the question - at dusk that is whatever rocket body happens to be up, and
     * the pass worth walking outside for may be four hours later. So the
     * station comes first whatever its elevation, because it is the one most
     * people mean, and the rest of the places go to whatever gets highest.
     *
     * Presented in time order once chosen, because that is how somebody plans
     * an evening.
     */
    fun best(passes: List<SatellitePass>, limit: Int = HIGHLIGHTS): List<SatellitePass> {
        val station = passes.filter { it.channel == PassChannel.Iss }
        val rest = passes
            .filterNot { it.channel == PassChannel.Iss }
            .sortedByDescending { it.peakElevation }

        return (station + rest).take(limit).sortedBy { it.risesAt }
    }

    /** How many passes are worth leading with before a list becomes a catalogue. */
    const val HIGHLIGHTS: Int = 6

    private fun visiblePasses(
        document: SatellitesDocument,
        latitude: Double,
        longitude: Double,
        from: Long,
        until: Long,
        settings: UserSettings,
    ): List<SatellitePass> {
        val bar = minOf(settings.minimumPassElevationDegrees.toDouble(), LISTED_BAR_DEGREES)
        val ends = document.trains.flatMap { listOf(it.lead, it.tail) }.toSet()

        val singles = document.satellites
            .filterNot { it.catalogue in ends }
            .flatMap { PassFinder.passes(it, latitude, longitude, from, until, bar) }
        val trains = TrainFinder.passes(document, latitude, longitude, from, until, bar)

        return PassPlanner
            .visible(singles + trains) { Twilight.isDark(latitude, longitude, it) }
            .sortedBy { it.risesAt }
    }
}
