package sh.arek.gloaming.domain

import kotlinx.serialization.Serializable
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.Noctilucent
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.SatellitesDocument
import sh.arek.gloaming.satellites.TrainFinder

/**
 * The moon tonight: how much of it there is, and how much of the night it has.
 *
 * The app has computed most of this since the comets went in - [Twilight]
 * weighs moonlight against every comet and every shower - and never once said
 * so on a screen. Yet it is the single biggest thing about a clear night after
 * the cloud: a full moon up all night is the difference between a good night
 * and a wasted drive, and unlike the cloud it is known months ahead.
 */
@Serializable
data class MoonTonight(
    /** 0 at new, 90 at first quarter, 180 at full, 270 at last quarter. */
    val phaseDegrees: Double,
    /** Fraction of the disc lit, 0 at new and 1 at full. */
    val illumination: Double,
    /** Share of tonight's darkness the moon spends above the horizon, 0 to 1. */
    val upFraction: Double,
    /**
     * How much of the night's darkness the moon actually spoils, 0 to 1.
     *
     * Illumination times [upFraction], because brightness alone is the wrong
     * measure: a full moon that has set by midnight costs far less than a half
     * moon up all night. The same figure the comet gate already uses.
     */
    val interference: Double,
    /** When it rises inside tonight's darkness, if it does. */
    val risesAt: Long?,
    /** When it sets inside tonight's darkness, if it does. */
    val setsAt: Long?,
)

/** What there is to see between now and the end of tonight. */
/**
 * The pass an alert last spoke about: the object, and roughly when it was
 * highest. Roughly, because the peak moves as it is re-derived - see
 * [AlertState.lastPassPeakAt].
 */
data class AnnouncedPass(val catalogue: Int, val peakAt: Long) {
    companion object {
        fun from(state: AlertState): AnnouncedPass? {
            val catalogue = state.lastPassCatalogue ?: return null
            val peakAt = state.lastPassPeakAt ?: return null
            return AnnouncedPass(catalogue, peakAt)
        }
    }
}

@Serializable
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
    /**
     * When the sun is 18 degrees down rather than 6, if it gets there.
     *
     * A different night from the civil one the app has always shown, and at UK
     * latitudes between May and August the difference between a usable night
     * and a bright one. [Twilight] has taken the threshold as a parameter since
     * it was written; nothing had ever asked it for this one.
     */
    val darkestFrom: Long? = null,
    val darkestUntil: Long? = null,
    /** The moon, where there is a night to weigh it against. */
    val moon: MoonTonight? = null,
    /** The last cloud reading held for here, never a fetch. */
    val cloud: CloudCover? = null,
    /** The first gap in the cloud inside tonight's darkness, if the forecast has one. */
    val clearSpell: ClearSpell? = null,
    /** The naked-eye planets above the bar tonight, highest first. */
    val planets: List<PlanetTonight> = emptyList(),
    /**
     * The constellations to lead with tonight, or all of them where the
     * setting asks for that.
     *
     * The season half of something whose one dated night is in the calendar.
     * A pattern is up for months at a time, which is exactly the shape that
     * belongs here rather than on a list of occasions.
     */
    val constellations: List<ConstellationTonight> = emptyList(),
    /**
     * The ones held back for never setting, named rather than listed.
     *
     * Empty when the setting asks for everything, because then nothing is
     * held back and a closing line about it would be a line about nothing.
     */
    val constellationsAlwaysUp: List<String> = emptyList(),
    /**
     * Whether the device actually holds the orbital elements.
     *
     * Without this, "no elements" and "no passes" are the same empty list, and
     * the screen says "nothing crossing tonight" to a device that has never
     * managed to download a catalogue. That is not a quiet sky, it is an app
     * that does not know, and the two must not read alike - the whole point of
     * the card is that somebody believes it when it says the sky is empty.
     */
    val elementsHeld: Boolean = true,
    /**
     * The first clear spell of the night after tonight.
     *
     * Looked for only when tonight has none, and only where the forecast
     * reaches that night; otherwise null, which is "not known" rather than
     * "cloudy".
     */
    val nextClearSpell: ClearSpell? = null,
) {
    /**
     * Whether there is nothing happening tonight.
     *
     * **Constellations and planets are deliberately not counted, and neither
     * is the moon.** They are scenery rather than occasions: on any clear
     * night from anywhere there are patterns up and usually a planet, so
     * counting them would make this permanently false and it would stop
     * meaning anything. What it asks is whether something is *happening* -
     * a pass, a dated event, a comet, the noctilucent prompt - and "the sky
     * is quiet tonight" stays a true and useful answer with Orion in it.
     */
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

    private const val HOUR_MILLIS: Long = 60 * 60 * 1000L

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

    /**
     * @param cloud the last reading held for here, never fetched. Null is a
     *   real answer and is said as one - the screen must not spend the one
     *   request an hour Open-Meteo's terms allow.
     * @param hourly the hours ahead from that same held reading.
     */
    fun of(
        satellites: SatellitesDocument?,
        comets: CometsDocument?,
        calendar: List<SkyEvent>,
        settings: UserSettings,
        nowMillis: Long,
        cloud: CloudCover? = null,
        hourly: List<HourlyCover> = emptyList(),
        announced: AnnouncedPass? = null,
    ): TonightList {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (latitude == null || longitude == null) {
            return TonightList(
                null, null, emptyList(), emptyList(), emptyList(), emptyList(), null,
                elementsHeld = satellites != null,
            )
        }

        // The night ahead rather than the nearest one. The nearest night to a
        // morning is the one that has just ended, and every part of this list
        // asks whether something falls inside the window: with a window behind
        // the observer, `from` clamps forward to now, `until` stays behind it,
        // and the passes, the events and the comets all answer no. That was
        // eight hours a day of a tab that said the sky was empty.
        val night = Twilight.nightAhead(latitude, longitude, nowMillis)
        // The window to describe: tonight's darkness where there is some, and
        // otherwise the next day, so a midsummer night at high latitude still
        // lists what crosses rather than going blank.
        val from = maxOf(night?.first ?: nowMillis, nowMillis)
        val until = night?.last ?: (nowMillis + HORIZON_MILLIS)

        // The darker window, searched separately rather than derived from the
        // civil one: at these latitudes it routinely does not exist at all
        // between May and August, and that absence is the answer rather than a
        // failure to find one.
        val darkest = Twilight.nightAhead(
            latitude,
            longitude,
            nowMillis,
            Twilight.ASTRONOMICAL_DEGREES,
        )

        val passes = satellites
            ?.let { visiblePasses(it, latitude, longitude, from, until, settings) }
            .orEmpty()

        val everyConstellation = night
            ?.let { Constellations.tonight(latitude, longitude, it) }
            .orEmpty()

        val clearSpell = night?.let {
            ClearSpell.first(
                hourly = hourly,
                tolerance = settings.cloudTolerance,
                darkFrom = it.first,
                darkUntil = it.last,
                nowMillis = nowMillis,
            )
        }
        val nextClearSpell = if (clearSpell == null && night != null) {
            nextNightSpell(latitude, longitude, night, hourly, settings.cloudTolerance)
        } else {
            null
        }

        return TonightList(
            darkFrom = night?.first,
            darkUntil = night?.last,
            passes = passes,
            bestPasses = best(passes, announced = announced, nowMillis = nowMillis),
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
                    it.kind != EventKind.Comet &&
                    // Dropped here for the comets' reason: the card below names
                    // the pattern and marks the night it is at its best, so
                    // listing the calendar entry too would put Orion on the
                    // screen twice on the one night of the year it matters.
                    it.kind != EventKind.Constellation
            },
            comets = Comets
                .tonight(comets, latitude, longitude, settings, nowMillis)
                .filter { it.visibility !is Visibility.NotFromHere },
            noctilucent = Noctilucent.event(latitude, longitude, nowMillis),
            darkestFrom = darkest?.first,
            darkestUntil = darkest?.last,
            moon = night?.let { moonTonight(latitude, longitude, it, nowMillis) },
            cloud = cloud,
            planets = night?.let { Planets.tonight(latitude, longitude, it) }.orEmpty(),
            constellations = worthListing(everyConstellation, settings),
            constellationsAlwaysUp = if (settings.showEveryConstellation) {
                emptyList()
            } else {
                everyConstellation.filter { it.isCircumpolar }.map { it.constellation.name }
            },
            elementsHeld = satellites != null,
            clearSpell = clearSpell,
            nextClearSpell = nextClearSpell,
        )
    }

    /** The first clear spell of the night after [night], if the forecast reaches it. */
    private fun nextNightSpell(
        latitude: Double,
        longitude: Double,
        night: LongRange,
        hourly: List<HourlyCover>,
        tolerance: Int,
    ): ClearSpell? {
        val next = Twilight.nightAhead(latitude, longitude, night.last + HOUR_MILLIS) ?: return null
        return ClearSpell.first(
            hourly = hourly,
            tolerance = tolerance,
            darkFrom = next.first,
            darkUntil = next.last,
            nowMillis = next.first,
        )
    }

    private fun moonTonight(
        latitude: Double,
        longitude: Double,
        night: LongRange,
        nowMillis: Long,
    ): MoonTonight {
        val moon = Twilight.moon(nowMillis)
        val up = Twilight.moonUpFraction(latitude, longitude, night)
        val times = Twilight.moonTimes(latitude, longitude, night)
        return MoonTonight(
            phaseDegrees = moon.phaseDegrees,
            illumination = moon.illumination,
            upFraction = up,
            interference = moon.illumination * up,
            risesAt = times.risesAt,
            setsAt = times.setsAt,
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
    fun best(
        passes: List<SatellitePass>,
        limit: Int = HIGHLIGHTS,
        announced: AnnouncedPass? = null,
        nowMillis: Long = 0L,
    ): List<SatellitePass> {
        val station = passes.filter { it.channel == PassChannel.Iss }
        val rest = passes
            .filterNot { it.channel == PassChannel.Iss }
            .sortedByDescending { it.peakElevation }
        val picked = (station + rest).take(limit)

        // The pass an alert has just spoken about, on top of the limit. The
        // alert picks the soonest pass and this list the highest of the night,
        // and on a night of three hundred passes the two almost never meet - so
        // without this, the one pass the app chose to interrupt somebody about
        // was the one pass no screen showed them.
        val told = announced?.let { told ->
            passes
                .filter { it.catalogue == told.catalogue && it.setsAt > nowMillis }
                .filter { kotlin.math.abs(it.peaksAt - told.peakAt) <= PassPlanner.SAME_PASS_TOLERANCE_MILLIS }
                .minByOrNull { kotlin.math.abs(it.peaksAt - told.peakAt) }
        }
        val all = if (told == null || told in picked) picked else picked + told
        return all.sortedBy { it.risesAt }
    }

    /** How many passes are worth leading with before a list becomes a catalogue. */
    const val HIGHLIGHTS: Int = 6

    /**
     * The patterns worth leading with, out of everything above the horizon.
     *
     * The passes' problem in a different disguise, and it takes the passes'
     * answer. Ranking by height puts the patterns that never set at the top on
     * every night of the year - from Scotland they transit at sixty to
     * eighty-five degrees while Orion never clears forty - so height alone
     * gives seventeen rows whose first seven are the same in January as in
     * December, which tells somebody nothing about tonight.
     *
     * So the ones that never set are held back and named in a line instead,
     * and the places go to the patterns that have actually changed since last
     * month. The one at its best comes first whatever its height, on exactly
     * the grounds the station gets its place among the passes: it is the one
     * worth going out for tonight rather than any other night.
     */
    fun worthListing(
        constellations: List<ConstellationTonight>,
        settings: UserSettings,
        limit: Int = CONSTELLATION_HIGHLIGHTS,
    ): List<ConstellationTonight> {
        if (settings.showEveryConstellation) return constellations

        val seasonal = constellations.filterNot { it.isCircumpolar }
        val best = seasonal.filter { it.isAtItsBest }
        val rest = seasonal.filterNot { it.isAtItsBest }

        return (best + rest).take(limit)
    }

    /**
     * How many constellations to lead with.
     *
     * One fewer than the passes get, because each carries a sentence on how to
     * find it and six of those is a paragraph rather than a card.
     */
    const val CONSTELLATION_HIGHLIGHTS: Int = 5

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
