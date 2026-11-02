package sh.arek.gloaming.domain

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** What kind of thing a recommendation is. Also the order they are named in. */
enum class PickKind { Aurora, Event, Pass, Planet }

/**
 * One thing worth going out for, and when.
 *
 * Exactly one of [event], [pass] and [planet] is set, matching [kind]; none is
 * set for the aurora, which is a reading rather than an object.
 */
@Serializable
data class Pick(
    val kind: PickKind,
    val subject: String,
    val atMillis: Long,
    val event: SkyEvent? = null,
    val pass: SatellitePass? = null,
    val planet: PlanetTonight? = null,
)

/**
 * The tests a recommendation must pass, in the order they are tried.
 *
 * [Stale] is for passes worked out from orbital elements too old to trust:
 * the time they give may be minutes out, and a pass is over in four.
 */
enum class Gate { Visibility, Stale, Time, Darkness, Cloud, Moon, Height, Sunlit, Aurora }

/**
 * Why the sky is not dark enough, for a [Gate.Darkness] exclusion.
 *
 * The same gate says three different things, and a sentence that says the
 * wrong one is a false one: "it never gets dark enough tonight" about a comet
 * judged at dusk on a December night would be untrue.
 */
enum class DarknessShortfall {
    /** Tonight never gets as dark as this needs. */
    NeverDark,

    /** Judged in twilight on a night that does get dark enough. */
    Twilight,

    /** The aurora, which is judged at now: not dark yet. */
    NotYet,
}

/**
 * Something that was looked at and set aside, with the first reason it failed.
 *
 * [note] carries what the wording needs and the gate alone does not say:
 * for [Gate.Visibility] the event's own reason; for [Gate.Cloud] the forecast
 * cover at its hour, for [Gate.Moon] the moon's illumination, for
 * [Gate.Height] a pass's peak elevation or a planet's altitude when it was
 * judged (floored), and for [Gate.Aurora] the probability, each as a whole
 * number (percent or degrees). Null for the rest, and for a cloud failure
 * where the forecast has no hour for it at all.
 *
 * [atMillis] is what a timeline row matches on: a pass's peak, an event's own
 * [SkyEvent.at] (not the moment it was judged at, which can move with the
 * clock and the forecast), a planet's judged moment, and null for the aurora.
 */
@Serializable
data class Excluded(
    val subject: String,
    val atMillis: Long?,
    val gate: Gate,
    val note: String? = null,
    /** Set on every [Gate.Darkness] exclusion, and only there. */
    val darkness: DarknessShortfall? = null,
    /**
     * Set on every [Gate.Height] exclusion, and only there: whether the figure
     * in [note] is a pass's peak or a planet's height when it was judged, which
     * the sentence has to say differently.
     */
    val kind: PickKind? = null,
)

/**
 * Tonight in one sentence, before it is put into words.
 *
 * The one rule every case keeps: nothing is recommended unless it will
 * actually be seen. A sentence that sends somebody out into cloud, or out for
 * a shower under a full moon, is worse than no sentence - it is the thing that
 * teaches people to stop believing it.
 */
@Serializable
sealed interface Outlook {
    /** No position, so nothing about tonight can be said at all. */
    @Serializable
    data object NoLocation : Outlook

    /** Not enough held to say anything true: no forecast, or stale elements. */
    @Serializable
    data class CannotTell(val forecastHeld: Boolean, val elementsStale: Boolean) : Outlook

    /**
     * An aurora above the threshold, dark and clear now. [clearUntil] only
     * with a forecast; [conditional] when there is none to say it is clear.
     */
    @Serializable
    data class AuroraNow(val probability: Int, val clearUntil: Long?, val conditional: Boolean) :
        Outlook

    /** It never gets properly dark tonight. */
    @Serializable
    data class NoNight(val noctilucent: Boolean) : Outlook

    /**
     * The forecast has no clear spell tonight.
     *
     * [missed] is the best thing that failed on the cloud alone, so the
     * sentence can say what the cloud is costing rather than just that it is
     * there. Null when everything would have failed anyway.
     */
    @Serializable
    data class Overcast(val nextClear: ClearSpell?, val missed: Pick?) : Outlook

    /**
     * At least one thing worth going out for, best first, at most
     * [Outlooks.MAX_PICKS]. [clearFrom] and [clearUntil] are the run of clear
     * hours around the first pick, only with a forecast that says so;
     * [conditional] when there is none to say it. [clearUnderway] is whether
     * that run has already begun and [clearAllNight] whether it covers all the
     * darkness still to come: "cloudy until" and "clear all night" are
     * different sentences, and the times alone cannot tell them apart.
     * [clearEarlier] is tonight's first clear spell where it comes before
     * that run, so "cloudy until" is never said over hours the forecast has
     * clear.
     */
    @Serializable
    data class GoOut(
        val picks: List<Pick>,
        val clearFrom: Long?,
        val clearUntil: Long?,
        val conditional: Boolean,
        val clearUnderway: Boolean = false,
        val clearAllNight: Boolean = false,
        val clearEarlier: ClearSpell? = null,
    ) : Outlook

    /**
     * Dark, clear or not known to be cloudy, and nothing on.
     *
     * [darkFrom] is when it is dark and, with a forecast, clear too - the
     * clear spell's start rather than dusk, so "clear from" is never earlier
     * than the forecast allows. [learn] is a constellation that is up.
     */
    @Serializable
    data class Quiet(val darkFrom: Long, val learn: String?, val conditional: Boolean) : Outlook
}

/**
 * The outlook with what was set aside and why.
 *
 * [auroraProbability] is the reading only while it is young enough to act on,
 * so the wording never quotes a figure from hours ago as if it were now.
 * [poleward] is the way to the observer's nearer pole, where the aurora and
 * noctilucent clouds are looked for; north where there is no position.
 */
@Serializable
data class OutlookReport(
    val outlook: Outlook,
    val excluded: List<Excluded>,
    val auroraProbability: Int?,
    val threshold: Int,
    val poleward: Compass,
)

/**
 * Everything time-dependent the outlook needs, so the decision itself holds no
 * clock and the tests can stub the sky.
 */
data class OutlookInput(
    val settings: UserSettings,
    val nowMillis: Long,
    val hourly: List<HourlyCover>,
    val auroraProbability: Int?,
    val auroraAgeMillis: Long?,
    val elementsAgeMillis: Long?,
    val sunAltitude: (Long) -> Double,
    val moonAltitude: (Long) -> Double,
    /** How high a planet is at a moment, so it is judged then rather than at its peak. */
    val planetAltitude: (SkyBody, Long) -> Double,
)

/**
 * Decides tonight's outlook from what the device already holds.
 *
 * Every candidate - the aurora, tonight's dated events and comets, the best
 * passes, the planets - is put through each gate that applies to its kind at
 * its own time. What passes them all is recommended; what does not is listed
 * with its first failure. The outcome is then the first of the spec's seven
 * that holds, in order.
 *
 * Pure: no clock, no network, no Android.
 */
object Outlooks {
    /** Illumination at or above which a moon that is up washes out faint things. */
    const val MOON_WASHOUT_ILLUMINATION = 0.4

    /** Degrees a planet must reach to be worth naming. */
    const val PLANET_MIN_ALTITUDE = 10.0

    /**
     * Probability from which an aurora is bright enough to see in civil
     * twilight. Below it, it is a faint thing and needs a fully dark sky.
     */
    const val AURORA_BRIGHT_PROBABILITY = 50

    /** How old a reading may be and still be acted on. */
    const val AURORA_READING_MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L

    /** How old the orbital elements may be before a pass time is not worth trusting. */
    const val ELEMENTS_STALE_MILLIS = 3 * 24 * 60 * 60 * 1000L

    /** How many things one sentence names. */
    const val MAX_PICKS = 2

    /** What the aurora is called in a [Pick] or an [Excluded]. */
    const val AURORA_SUBJECT = "Aurora"

    /**
     * How old a reading may be and still show the app is in touch.
     *
     * Not a figure to act on - that is [AURORA_READING_MAX_AGE_MILLIS] - but
     * enough to tell an offline phone with a forecast-free setting from one
     * that has heard nothing at all and cannot honestly say anything.
     */
    private const val AURORA_KNOWN_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L

    private const val HOUR_MILLIS = 60 * 60 * 1000L

    /**
     * How far above -6 degrees the sun may read and still count as dark.
     *
     * Dusk is found by a search that stops a hair short of the line, so at
     * [TonightList.darkFrom] itself the sun reads -5.999997. A planet whose
     * best moment is before dusk is judged exactly there, and without this an
     * evening planet twenty degrees up is set aside as "not dark yet".
     */
    private const val DUSK_TOLERANCE_DEGREES = 0.01

    /** The dated events worth going out for. The rest are scenery or planning. */
    private val BRIGHT_EVENTS = setOf(
        EventKind.SolarEclipse,
        EventKind.LunarEclipse,
        EventKind.Conjunction,
        EventKind.Occultation,
    )
    private val FAINT_EVENTS = setOf(EventKind.MeteorShower, EventKind.Comet)

    /**
     * Events judged at their moment, and over once it has passed.
     *
     * A comet's moment is where it is best placed tonight and a conjunction's
     * is where the pair is seen closest; the window around either is the whole
     * night, and "still under way" at four in the morning would be judging a
     * comet that has set or a pair that has drifted apart. Eclipses,
     * occultations and showers have a real beginning and end and keep it.
     */
    private val MOMENT_EVENTS = setOf(EventKind.Comet, EventKind.Conjunction)

    fun of(list: TonightList, input: OutlookInput): OutlookReport {
        val settings = input.settings
        val now = input.nowMillis
        val readingAge = input.auroraAgeMillis
        val auroraFresh = input.auroraProbability != null &&
            readingAge != null && readingAge < AURORA_READING_MAX_AGE_MILLIS

        fun report(outlook: Outlook, excluded: List<Excluded>) = OutlookReport(
            outlook = outlook,
            excluded = excluded,
            auroraProbability = input.auroraProbability.takeIf { auroraFresh },
            threshold = settings.threshold,
            poleward = settings.latitude?.let(Compass::polewardOf) ?: Compass.North,
        )

        if (settings.latitude == null || settings.longitude == null) {
            return report(Outlook.NoLocation, emptyList())
        }

        // The cloud gate needs both the setting and a forecast that says
        // something about tonight. Missing either, nothing can be called clear
        // and every sentence is conditional - including the clear spell Tonight
        // may have found in a forecast the user has asked not to be judged by.
        // A forecast that ends before dusk, or starts tomorrow, is not one:
        // judged by it, every hour of tonight is missing and the night would
        // read as overcast.
        val forecastHeld = coversTonight(list, input)
        val cloudGate = settings.cloudGateEnabled && forecastHeld
        // Unknown age is treated as old: a pass time is only as good as the
        // elements it came from, and nothing says these are recent.
        val elementsStale = input.elementsAgeMillis
            ?.let { it > ELEMENTS_STALE_MILLIS }
            ?: list.bestPasses.isNotEmpty()
        val spell = if (cloudGate) list.clearSpell else null
        val night = Night(list, input, cloudGate, spell, elementsStale)

        val judged = candidates(list, night).map { it to failures(it, night) }
        val picks = judged.filter { it.second.isEmpty() }.map { it.first.pick }
        val excluded = judged.filter { it.second.isNotEmpty() }.map { (candidate, failed) ->
            val gate = failed.first()
            Excluded(
                subject = candidate.pick.subject,
                atMillis = candidate.excludedAt,
                gate = gate,
                note = note(candidate, gate, night),
                darkness = if (gate == Gate.Darkness) shortfall(candidate, night) else null,
                kind = candidate.pick.kind.takeIf { gate == Gate.Height },
            )
        }

        val heardRecently = input.auroraProbability != null &&
            readingAge != null && readingAge < AURORA_KNOWN_MAX_AGE_MILLIS
        // Stale passes are never picked, so "passes would be the only
        // recommendation" is nothing else picked and a pass held back by its
        // elements alone.
        val passesOnly = picks.isEmpty() &&
            judged.any { it.first.pick.kind == PickKind.Pass && it.second == listOf(Gate.Stale) }
        if ((settings.cloudGateEnabled && !forecastHeld && !heardRecently) ||
            (elementsStale && passesOnly)
        ) {
            return report(Outlook.CannotTell(forecastHeld, elementsStale), excluded)
        }

        // The aurora is judged at now, dark and clear included, so a
        // recommended aurora is one that is dark and clear now.
        val probability = input.auroraProbability
        if (probability != null && picks.any { it.kind == PickKind.Aurora }) {
            val clearUntil = night.runAt(now)?.until
            return report(Outlook.AuroraNow(probability, clearUntil, !cloudGate), excluded)
        }

        val darkFrom = list.darkFrom
            ?: return report(Outlook.NoNight(list.noctilucent != null), excluded)

        if (cloudGate && list.clearSpell == null) {
            // Ruling R3: the best candidate whose only failure is the cloud.
            val missed = judged.firstOrNull { it.second == listOf(Gate.Cloud) }?.first?.pick
            return report(Outlook.Overcast(list.nextClearSpell, missed), excluded)
        }

        if (picks.isNotEmpty()) {
            // The clear hours around the first thing named, not the night's
            // first clear hours: "clear 18:00 to 19:00, go out at 23:00" would
            // be two true halves making a false sentence.
            val run = night.runAt(picks.first().atMillis)
            return report(
                Outlook.GoOut(
                    picks = picks.take(MAX_PICKS),
                    clearFrom = run?.from,
                    clearUntil = run?.until,
                    conditional = !cloudGate,
                    clearUnderway = run?.underway ?: false,
                    clearAllNight = run?.allNight ?: false,
                    clearEarlier = night.spell?.takeIf { run != null && it.from < run.from },
                ),
                excluded,
            )
        }

        return report(
            Outlook.Quiet(
                darkFrom = night.spell?.from ?: darkFrom,
                learn = toLearn(list),
                conditional = !cloudGate,
            ),
            excluded,
        )
    }

    /** Whether the forecast has at least one hour inside what is left of tonight's darkness. */
    private fun coversTonight(list: TonightList, input: OutlookInput): Boolean {
        val darkFrom = list.darkFrom
        val darkUntil = list.darkUntil
        if (darkFrom == null || darkUntil == null) return input.hourly.isNotEmpty()
        val from = maxOf(darkFrom, input.nowMillis)
        return input.hourly.any { it.atMillis < darkUntil && it.atMillis + HOUR_MILLIS > from }
    }

    /** What every gate needs to know about tonight. */
    private class Night(
        val list: TonightList,
        val input: OutlookInput,
        val cloudGate: Boolean,
        /** Tonight's first clear spell, only where the cloud gate is judging. */
        val spell: ClearSpell?,
        /** Whether the orbital elements are too old, or of unknown age, to trust a pass time. */
        val elementsStale: Boolean,
    ) {
        val now: Long get() = input.nowMillis
        val end: Long? get() = list.darkUntil

        /**
         * The unbroken run of clear hours containing [t], inside what is left
         * of tonight's darkness, or null where [t] is not in one or the cloud
         * gate is not judging.
         *
         * Clear as [ClearSpell] means it - on the effective cover - and joined
         * only where one hour starts exactly as the last ends, because a
         * missing hour is not a clear one.
         */
        fun runAt(t: Long): ClearSpell? {
            val darkFrom = list.darkFrom
            val darkUntil = list.darkUntil
            if (!cloudGate || darkFrom == null || darkUntil == null) return null
            val windowFrom = maxOf(darkFrom, now)
            if (t < windowFrom || t >= darkUntil) return null

            val tolerance = input.settings.cloudTolerance
            val clear = input.hourly
                .filter { it.effective <= tolerance }
                .map { it.atMillis }
                .toSet()
            val hour = clear.firstOrNull { t >= it && t < it + HOUR_MILLIS } ?: return null

            var runFrom = hour
            while (runFrom - HOUR_MILLIS in clear && runFrom > windowFrom) runFrom -= HOUR_MILLIS
            var runUntil = hour + HOUR_MILLIS
            while (runUntil in clear && runUntil < darkUntil) runUntil += HOUR_MILLIS

            val from = maxOf(runFrom, windowFrom)
            val until = minOf(runUntil, darkUntil)
            return ClearSpell(
                from = from,
                until = until,
                underway = from <= windowFrom,
                allNight = from <= windowFrom && until >= darkUntil,
            )
        }
    }

    /**
     * Something that might be recommended.
     *
     * [from] and [until] are its window for the time gate; for a point in time
     * they are the same moment. [faint] is whether it needs a fully dark,
     * moonless, wholly clear sky rather than merely a dark one.
     */
    private class Candidate(
        val pick: Pick,
        val from: Long,
        val until: Long,
        val faint: Boolean,
        val visibility: Visibility? = null,
        val excludedAt: Long? = pick.atMillis,
    ) {
        val at: Long get() = pick.atMillis
    }

    private fun candidates(list: TonightList, night: Night): List<Candidate> {
        val now = night.now
        val aurora = night.input.auroraProbability?.let {
            Candidate(
                pick = Pick(PickKind.Aurora, AURORA_SUBJECT, now),
                from = now,
                until = now,
                faint = it < AURORA_BRIGHT_PROBABILITY,
                excludedAt = null,
            )
        }

        val events = (list.events + list.comets)
            .filter { it.kind in BRIGHT_EVENTS || it.kind in FAINT_EVENTS }
            .map { event ->
                val faint = event.kind in FAINT_EVENTS
                if (event.kind in MOMENT_EVENTS) {
                    Candidate(
                        pick = Pick(PickKind.Event, event.title, event.at, event = event),
                        from = event.at,
                        until = event.at,
                        faint = faint,
                        visibility = event.visibility,
                    )
                } else {
                    val at = if (event.kind == EventKind.MeteorShower) {
                        showerTime(event, night)
                    } else {
                        event.at
                    }
                    Candidate(
                        // Judged from now rather than at a peak already behind
                        // us, for an event whose window is still running.
                        pick = Pick(PickKind.Event, event.title, maxOf(at, now), event = event),
                        from = event.from,
                        until = event.until,
                        faint = faint,
                        visibility = event.visibility,
                        // Listed at the event's own moment rather than the one
                        // it was judged at, which moves with the clock and the
                        // forecast: a timeline row matches on this.
                        excludedAt = event.at,
                    )
                }
            }

        val passes = ranked(list.bestPasses).map {
            val pick = Pick(PickKind.Pass, it.name, it.peaksAt, pass = it)
            Candidate(pick, it.peaksAt, it.peaksAt, faint = false)
        }

        val planets = list.planets.map { planet ->
            val start = maxOf(now, list.darkFrom ?: now)
            val end = list.darkUntil ?: Long.MAX_VALUE
            val best = if (start <= end) planet.peakAt.coerceIn(start, end) else planet.peakAt
            planetCandidate(planet, best, list, now).let { atBest ->
                if (cloud(atBest, night) == null) atBest else clearMoment(planet, atBest, start, end, list, night)
            }
        }

        return listOfNotNull(aurora) + events + passes + planets
    }

    private fun planetCandidate(planet: PlanetTonight, at: Long, list: TonightList, now: Long): Candidate {
        val from = planet.risesAt ?: list.darkFrom ?: now
        val until = planet.setsAt ?: list.darkUntil ?: Long.MAX_VALUE
        val pick = Pick(PickKind.Planet, planet.body.label, at, planet = planet)
        return Candidate(pick, from, until, faint = false)
    }

    /**
     * A planet whose best moment is under cloud, judged instead at the best
     * moment the forecast leaves clear.
     *
     * A planet is up for hours, not at one moment, and cloud over its highest
     * hour alone used to set it aside for the whole night. Each hour of what
     * is left of the darkness is tried as it starts - or from now, for the
     * hour under way - since a forecast hour says nothing finer, and the
     * moment the planet is highest of those that pass every gate, cloud
     * included, is the one judged. Where none does, the planet keeps [atBest]
     * and the cloud there is the reason given, so the exclusion names its
     * best moment rather than some low one.
     */
    private fun clearMoment(
        planet: PlanetTonight,
        atBest: Candidate,
        start: Long,
        end: Long,
        list: TonightList,
        night: Night,
    ): Candidate =
        night.input.hourly
            .filter { it.atMillis + HOUR_MILLIS > start && it.atMillis < end }
            .sortedBy { it.atMillis }
            .map { planetCandidate(planet, maxOf(it.atMillis, start), list, night.now) }
            .filter { failures(it, night).isEmpty() }
            .maxByOrNull { night.input.planetAltitude(planet.body, it.at) }
            ?: atBest

    /**
     * When to judge a shower.
     *
     * At its best hour if that hour is in a clear run. Otherwise from when it
     * clears, if that is inside the shower's window - moved on to full
     * darkness where the spell starts in twilight, because a shower judged at
     * dusk would fail on darkness for a spell that is still clear an hour
     * later. Without a spell, at its best hour.
     */
    private fun showerTime(event: SkyEvent, night: Night): Long {
        val spell = night.spell ?: return event.at
        if (night.runAt(event.at) != null) return event.at
        val dark = night.list.darkestFrom?.let { maxOf(spell.from, it) } ?: spell.from
        val from = if (dark < spell.until) dark else spell.from
        return if (from in event.from..event.until) from else event.at
    }

    /**
     * The station first, then by height: the order [Tonight.best] chooses in,
     * kept here rather than its time order, because the best is named first.
     */
    private fun ranked(passes: List<SatellitePass>): List<SatellitePass> =
        passes.sortedWith(
            compareBy<SatellitePass> { it.channel != PassChannel.Iss }
                .thenByDescending { it.peakElevation },
        )

    /** Every gate this candidate fails, the defining one first. */
    private fun failures(candidate: Candidate, night: Night): List<Gate> {
        val failed = listOfNotNull(
            visibility(candidate),
            stale(candidate, night),
            time(candidate, night),
            darkness(candidate, night),
            cloud(candidate, night),
            moon(candidate, night),
            height(candidate, night),
            sunlit(candidate),
            aurora(candidate, night),
        )
        // An aurora reading is about now. Too weak, or too old, is the thing to
        // say about it, ahead of a twilight that would only matter if it were
        // strong.
        return if (candidate.pick.kind == PickKind.Aurora) {
            failed.sortedBy { it != Gate.Aurora }
        } else {
            failed
        }
    }

    private fun visibility(candidate: Candidate): Gate? {
        val visibility = candidate.visibility ?: return null
        return Gate.Visibility.takeUnless { visibility is Visibility.Good }
    }

    private fun stale(candidate: Candidate, night: Night): Gate? =
        Gate.Stale.takeIf { candidate.pick.pass != null && night.elementsStale }

    private fun time(candidate: Candidate, night: Night): Gate? {
        val end = night.end
        val over = candidate.until < night.now
        val tooLate = end != null && candidate.from > end
        return Gate.Time.takeIf { over || tooLate }
    }

    private fun darkness(candidate: Candidate, night: Night): Gate? {
        // Seen by daylight by definition; its visibility already says whether
        // the sun is up from here.
        if (candidate.pick.event?.kind == EventKind.SolarEclipse) return null
        val dark = if (candidate.faint) {
            val from = night.list.darkestFrom
            val until = night.list.darkestUntil
            from != null && until != null && candidate.at in from..until
        } else {
            night.input.sunAltitude(candidate.at) <= Twilight.CIVIL_DEGREES + DUSK_TOLERANCE_DEGREES
        }
        return Gate.Darkness.takeUnless { dark }
    }

    private fun shortfall(candidate: Candidate, night: Night): DarknessShortfall = when {
        candidate.pick.kind == PickKind.Aurora -> DarknessShortfall.NotYet
        candidate.faint && (night.list.darkestFrom == null || night.list.darkestUntil == null) ->
            DarknessShortfall.NeverDark
        !candidate.faint && night.list.darkFrom == null -> DarknessShortfall.NeverDark
        else -> DarknessShortfall.Twilight
    }

    private fun cloud(candidate: Candidate, night: Night): Gate? {
        if (!night.cloudGate) return null
        val hour = hourAt(candidate.at, night) ?: return Gate.Cloud
        val tolerance = night.input.settings.cloudTolerance
        val clear = hour.effective <= tolerance && (!candidate.faint || hour.total <= tolerance)
        return Gate.Cloud.takeUnless { clear }
    }

    private fun moon(candidate: Candidate, night: Night): Gate? {
        if (!candidate.faint) return null
        val down = night.input.moonAltitude(candidate.at) < 0.0
        val dim = illumination(night) < MOON_WASHOUT_ILLUMINATION
        return Gate.Moon.takeUnless { down || dim }
    }

    private fun height(candidate: Candidate, night: Night): Gate? {
        val pass = candidate.pick.pass
        val planet = candidate.pick.planet
        val high = when {
            pass != null -> pass.peakElevation >= night.input.settings.minimumPassElevationDegrees
            planet != null -> planetAltitude(candidate, planet, night) >= PLANET_MIN_ALTITUDE
            else -> true
        }
        return Gate.Height.takeUnless { high }
    }

    /** Where a planet is when it is judged, not where it was at its peak. */
    private fun planetAltitude(candidate: Candidate, planet: PlanetTonight, night: Night): Double =
        night.input.planetAltitude(planet.body, candidate.at)

    private fun sunlit(candidate: Candidate): Gate? =
        Gate.Sunlit.takeIf { candidate.pick.pass?.sunlitAtPeak == false }

    private fun aurora(candidate: Candidate, night: Night): Gate? {
        if (candidate.pick.kind != PickKind.Aurora) return null
        val probability = night.input.auroraProbability ?: return Gate.Aurora
        val age = night.input.auroraAgeMillis
        val acted = probability >= night.input.settings.threshold &&
            age != null && age < AURORA_READING_MAX_AGE_MILLIS
        return Gate.Aurora.takeUnless { acted }
    }

    /** The forecast hour a moment falls in. A missing hour is not a clear one. */
    private fun hourAt(t: Long, night: Night): HourlyCover? =
        night.input.hourly.firstOrNull { t >= it.atMillis && t < it.atMillis + HOUR_MILLIS }

    /** Unknown counts as full: the moon is only ever let off for being down or slight. */
    private fun illumination(night: Night): Double = night.list.moon?.illumination ?: 1.0

    private fun note(candidate: Candidate, gate: Gate, night: Night): String? = when (gate) {
        Gate.Visibility -> when (val visibility = candidate.visibility) {
            is Visibility.Marginal -> visibility.reason
            is Visibility.NotFromHere -> visibility.reason
            else -> null
        }
        Gate.Cloud -> hourAt(candidate.at, night)?.let {
            // The figure that failed: the total for a faint thing under thin
            // cloud, otherwise the cover as it matters to a bright one.
            val tolerance = night.input.settings.cloudTolerance
            if (candidate.faint && it.effective <= tolerance) it.total else it.effective
        }?.toString()
        Gate.Moon -> (illumination(night) * 100).roundToInt().toString()
        // Floored, so "only 14 degrees up" is never more than the truth.
        Gate.Height -> (
            candidate.pick.pass?.peakElevation
                ?: candidate.pick.planet?.let { planetAltitude(candidate, it, night) }
            )?.let { floor(it).toInt() }?.toString()
        Gate.Aurora -> night.input.auroraProbability?.toString()
        else -> null
    }

    /**
     * A constellation to learn on a quiet night: the one at its best if there
     * is one, else a signpost that is up - the Plough, which from the middle
     * northern latitudes is every night, or the Southern Cross, which is the
     * same from the middle southern ones.
     */
    private fun toLearn(list: TonightList): String? {
        fun up(name: String) =
            list.constellations.any { it.constellation.name == name } || name in list.constellationsAlwaysUp
        return list.constellations.firstOrNull { it.isAtItsBest }?.constellation?.name
            ?: "the Plough".takeIf { up(URSA_MAJOR) }
            ?: "the Southern Cross".takeIf { up(CRUX) }
    }

    private const val URSA_MAJOR = "Ursa Major"
    private const val CRUX = "Crux"
}
