package sh.arek.gloaming.domain

import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
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
data class Pick(
    val kind: PickKind,
    val subject: String,
    val atMillis: Long,
    val event: SkyEvent? = null,
    val pass: SatellitePass? = null,
    val planet: PlanetTonight? = null,
)

/** The tests a recommendation must pass, in the order they are tried. */
enum class Gate { Visibility, Time, Darkness, Cloud, Moon, Height, Sunlit, Aurora }

/**
 * Something that was looked at and set aside, with the first reason it failed.
 *
 * [note] carries what the wording needs and the gate alone does not say:
 * for [Gate.Visibility] the event's own reason; for [Gate.Cloud] the forecast
 * cover at its hour, for [Gate.Moon] the moon's illumination, for
 * [Gate.Height] the highest it gets and for [Gate.Aurora] the probability, each
 * as a whole number (percent or degrees). Null for the rest, and for a cloud
 * failure where the forecast has no hour for it at all.
 *
 * [atMillis] is the moment it was judged at - a pass's peak, which is what a
 * timeline row matches on - and null for the aurora.
 */
data class Excluded(
    val subject: String,
    val atMillis: Long?,
    val gate: Gate,
    val note: String? = null,
)

/**
 * Tonight in one sentence, before it is put into words.
 *
 * The one rule every case keeps: nothing is recommended unless it will
 * actually be seen. A sentence that sends somebody out into cloud, or out for
 * a shower under a full moon, is worse than no sentence - it is the thing that
 * teaches people to stop believing it.
 */
sealed interface Outlook {
    /** No position, so nothing about tonight can be said at all. */
    data object NoLocation : Outlook

    /** Not enough held to say anything true: no forecast, or stale elements. */
    data class CannotTell(val forecastHeld: Boolean, val elementsStale: Boolean) : Outlook

    /** An aurora above the threshold, dark and clear now. [clearUntil] only with a forecast. */
    data class AuroraNow(val probability: Int, val clearUntil: Long?) : Outlook

    /** It never gets properly dark tonight. */
    data class NoNight(val noctilucent: Boolean) : Outlook

    /**
     * The forecast has no clear spell tonight.
     *
     * [missed] is the best thing that failed on the cloud alone, so the
     * sentence can say what the cloud is costing rather than just that it is
     * there. Null when everything would have failed anyway.
     */
    data class Overcast(val nextClear: ClearSpell?, val missed: Pick?) : Outlook

    /**
     * At least one thing worth going out for, best first, at most
     * [Outlooks.MAX_PICKS]. [clearFrom] and [clearUntil] only with a forecast
     * that says so; [conditional] when there is none to say it.
     */
    data class GoOut(
        val picks: List<Pick>,
        val clearFrom: Long?,
        val clearUntil: Long?,
        val conditional: Boolean,
    ) : Outlook

    /**
     * Dark, clear or not known to be cloudy, and nothing on.
     *
     * [darkFrom] is when it is dark and, with a forecast, clear too - the
     * clear spell's start rather than dusk, so "clear from" is never earlier
     * than the forecast allows. [learn] is a constellation that is up.
     */
    data class Quiet(val darkFrom: Long, val learn: String?, val conditional: Boolean) : Outlook
}

/**
 * The outlook with what was set aside and why.
 *
 * [auroraProbability] is the reading only while it is young enough to act on,
 * so the wording never quotes a figure from hours ago as if it were now.
 */
data class OutlookReport(
    val outlook: Outlook,
    val excluded: List<Excluded>,
    val auroraProbability: Int?,
    val threshold: Int,
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

    /** The dated events worth going out for. The rest are scenery or planning. */
    private val BRIGHT_EVENTS = setOf(
        EventKind.SolarEclipse,
        EventKind.LunarEclipse,
        EventKind.Conjunction,
        EventKind.Occultation,
    )
    private val FAINT_EVENTS = setOf(EventKind.MeteorShower, EventKind.Comet)

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
        )

        if (settings.latitude == null || settings.longitude == null) {
            return report(Outlook.NoLocation, emptyList())
        }

        // The cloud gate needs both the setting and a forecast to judge by.
        // Missing either, nothing can be called clear and every sentence is
        // conditional - including the clear spell Tonight may have found in a
        // forecast the user has asked not to be judged by.
        val cloudGate = settings.cloudGateEnabled && input.hourly.isNotEmpty()
        val night = Night(list, input, cloudGate, if (cloudGate) list.clearSpell else null)

        val judged = candidates(list, night).map { it to failures(it, night) }
        val picks = judged.filter { it.second.isEmpty() }.map { it.first.pick }
        val excluded = judged.filter { it.second.isNotEmpty() }.map { (candidate, failed) ->
            val gate = failed.first()
            val note = note(candidate, gate, night)
            Excluded(candidate.pick.subject, candidate.excludedAt, gate, note)
        }

        val heardRecently = input.auroraProbability != null &&
            readingAge != null && readingAge < AURORA_KNOWN_MAX_AGE_MILLIS
        val forecastHeld = input.hourly.isNotEmpty()
        val elementsStale = (input.elementsAgeMillis ?: 0L) > ELEMENTS_STALE_MILLIS
        val passesOnly = picks.isNotEmpty() && picks.all { it.kind == PickKind.Pass }
        if ((settings.cloudGateEnabled && !forecastHeld && !heardRecently) ||
            (elementsStale && passesOnly)
        ) {
            return report(Outlook.CannotTell(forecastHeld, elementsStale), excluded)
        }

        // The aurora is judged at now, dark and clear included, so a
        // recommended aurora is one that is dark and clear now.
        val probability = input.auroraProbability
        if (probability != null && picks.any { it.kind == PickKind.Aurora }) {
            val clearUntil = night.spell?.takeIf { it.from <= now }?.until
            return report(Outlook.AuroraNow(probability, clearUntil), excluded)
        }

        val darkFrom = list.darkFrom
            ?: return report(Outlook.NoNight(list.noctilucent != null), excluded)

        if (cloudGate && list.clearSpell == null) {
            // Ruling R3: the best candidate whose only failure is the cloud.
            val missed = judged.firstOrNull { it.second == listOf(Gate.Cloud) }?.first?.pick
            return report(Outlook.Overcast(list.nextClearSpell, missed), excluded)
        }

        if (picks.isNotEmpty()) {
            return report(
                Outlook.GoOut(
                    picks = picks.take(MAX_PICKS),
                    clearFrom = night.spell?.from,
                    clearUntil = night.spell?.until,
                    conditional = !cloudGate,
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

    /** What every gate needs to know about tonight. */
    private class Night(
        val list: TonightList,
        val input: OutlookInput,
        val cloudGate: Boolean,
        /** Tonight's clear spell, only where the cloud gate is judging. */
        val spell: ClearSpell?,
    ) {
        val now: Long get() = input.nowMillis
        val end: Long? get() = list.darkUntil
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
                val at = if (event.kind == EventKind.MeteorShower) {
                    showerTime(event, night)
                } else {
                    event.at
                }
                Candidate(
                    // Judged from now rather than at a peak already behind us,
                    // for an event whose window is still running.
                    pick = Pick(PickKind.Event, event.title, maxOf(at, now), event = event),
                    from = event.from,
                    until = event.until,
                    faint = faint,
                    visibility = event.visibility,
                )
            }

        val passes = ranked(list.bestPasses).map {
            val pick = Pick(PickKind.Pass, it.name, it.peaksAt, pass = it)
            Candidate(pick, it.peaksAt, it.peaksAt, faint = false)
        }

        val planets = list.planets.map {
            val from = it.risesAt ?: list.darkFrom ?: now
            val until = it.setsAt ?: list.darkUntil ?: Long.MAX_VALUE
            val start = maxOf(now, list.darkFrom ?: now)
            val end = list.darkUntil ?: Long.MAX_VALUE
            val at = if (start <= end) it.peakAt.coerceIn(start, end) else it.peakAt
            val pick = Pick(PickKind.Planet, it.body.label, at, planet = it)
            Candidate(pick, from, until, faint = false)
        }

        return listOfNotNull(aurora) + events + passes + planets
    }

    /**
     * When to judge a shower.
     *
     * At its best hour if the clear spell covers it. Otherwise from when it
     * clears, if that is inside the shower's window - moved on to full
     * darkness where the spell starts in twilight, because a shower judged at
     * dusk would fail on darkness for a spell that is still clear an hour
     * later. Without a spell, at its best hour.
     */
    private fun showerTime(event: SkyEvent, night: Night): Long {
        val spell = night.spell ?: return event.at
        if (event.at >= spell.from && event.at < spell.until) return event.at
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

    private fun time(candidate: Candidate, night: Night): Gate? {
        val end = night.end
        val over = candidate.until < night.now
        val tooLate = end != null && candidate.from > end
        return Gate.Time.takeIf { over || tooLate }
    }

    private fun darkness(candidate: Candidate, night: Night): Gate? {
        val dark = if (candidate.faint) {
            val from = night.list.darkestFrom
            val until = night.list.darkestUntil
            from != null && until != null && candidate.at in from..until
        } else {
            night.input.sunAltitude(candidate.at) < Twilight.CIVIL_DEGREES
        }
        return Gate.Darkness.takeUnless { dark }
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
            planet != null -> planet.peakAltitude >= PLANET_MIN_ALTITUDE
            else -> true
        }
        return Gate.Height.takeUnless { high }
    }

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
        Gate.Height -> (candidate.pick.pass?.peakElevation ?: candidate.pick.planet?.peakAltitude)
            ?.roundToInt()?.toString()
        Gate.Aurora -> night.input.auroraProbability?.toString()
        else -> null
    }

    /**
     * A constellation to learn on a quiet night: the one at its best if there
     * is one, else the Plough wherever Ursa Major is up, which from these
     * latitudes is every night.
     */
    private fun toLearn(list: TonightList): String? =
        list.constellations.firstOrNull { it.isAtItsBest }?.constellation?.name
            ?: "the Plough".takeIf {
                list.constellations.any { it.constellation.name == URSA_MAJOR } ||
                    URSA_MAJOR in list.constellationsAlwaysUp
            }

    private const val URSA_MAJOR = "Ursa Major"
}
