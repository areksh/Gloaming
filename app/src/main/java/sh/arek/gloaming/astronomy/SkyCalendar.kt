package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.LocalSolarEclipseInfo
import io.github.cosinekitty.astronomy.LunarEclipseInfo
import io.github.cosinekitty.astronomy.MoonQuarterInfo
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.Visibility as ElongationSide
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.localSolarEclipsesAfter
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import io.github.cosinekitty.astronomy.moonQuartersAfter
import io.github.cosinekitty.astronomy.searchMaxElongation
import kotlin.math.roundToInt

/**
 * What the sky will do next, worked out on the device from the vendored
 * ephemeris.
 *
 * Nothing here touches the network and nothing here needs to. Eclipses,
 * elongations and moon phases are known centuries ahead, which is the whole
 * reason Sec. 6 says aurora is the only feature with a live upstream: there is
 * nothing to poll for, only arithmetic to do.
 *
 * **The computation is the easy half.** Every call below is one line of
 * wiring; the work is deciding what is worth telling somebody about. Three of
 * the next six lunar eclipses are penumbral and show nothing at all, and half
 * the solar eclipses in the next decade happen with the sun under the horizon
 * at Edinburgh. So every event is judged against the observer before it leaves
 * here, and the ones that cannot be seen say why rather than being dropped -
 * silence reads as a bug, and "not from where you are" reads as an answer.
 */
object SkyCalendar {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * How finely a night is sampled when asking how high something gets.
     *
     * Ten minutes against an altitude that moves at most a quarter of a degree
     * in that time: far inside the slack in any of the thresholds below, and
     * cheap enough to do for every planet on every night in the horizon.
     */
    private const val SAMPLE_MILLIS = 10L * 60 * 1000

    /** Below this at maximum, and a clear horizon stops being a detail. */
    private const val LOW_DEGREES = 10.0

    /** Less of the disc than this covered, and most people would not notice. */
    private const val SLIGHT = 0.10

    /**
     * How long a polar night is sampled for before giving up.
     *
     * [Twilight.darkness] reports no end at all inside the arctic circle in
     * winter, which is true and unhelpful to a loop. A day's worth covers every
     * question the calendar asks of one night.
     */
    private const val UNBOUNDED_NIGHT_MILLIS = DAY_MILLIS

    /**
     * Everything worth knowing about between [fromMillis] and [horizonDays]
     * later, in time order.
     *
     * A year by default: eclipses are the headline and they are sparse, so a
     * shorter horizon would usually show none at all.
     */
    fun events(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        horizonDays: Int = 365,
    ): List<SkyEvent> {
        val until = fromMillis + horizonDays.toLong() * DAY_MILLIS
        val observer = observer(latitude, longitude)
        val start = Time.fromMillisecondsSince1970(fromMillis)

        return buildList {
            addAll(solarEclipses(observer, start, until))
            addAll(lunarEclipses(observer, start, until))
            addAll(elongations(latitude, longitude, start, until))
            addAll(moonPhases(start, until))
        }
            .filter { it.at in fromMillis..until }
            .sortedBy { it.at }
    }

    // --- solar eclipses ---

    private fun solarEclipses(observer: Observer, start: Time, until: Long): List<SkyEvent> =
        localSolarEclipsesAfter(start, observer)
            .takeWhile { it.peak.time.millis() <= until }
            .map(::solarEclipse)
            .toList()

    /**
     * One solar eclipse, judged on the altitudes the library already returns.
     *
     * `EclipseEvent` carries the sun's altitude at each contact, refraction
     * included, so no extra work is needed to know whether the ground is in the
     * way - and the answer is often that it partly is. The eclipse of 26
     * January 2028 begins with the sun 5.6 degrees up at Edinburgh and reaches
     * maximum after it has set. Neither "good" nor "not visible" describes
     * that; being told the sun sets part-way through is what lets somebody
     * decide.
     */
    private fun solarEclipse(eclipse: LocalSolarEclipseInfo): SkyEvent {
        val covered = (eclipse.obscuration * 100).roundToInt()
        val begins = eclipse.partialBegin
        val ends = eclipse.partialEnd
        val peak = eclipse.peak

        val visibility = when {
            maxOf(begins.altitude, peak.altitude, ends.altitude) <= 0.0 ->
                Visibility.NotFromHere(
                    "The sun is below the horizon here throughout, so none of it is visible.",
                )

            eclipse.obscuration < SLIGHT ->
                Visibility.Marginal(
                    "Only $covered per cent of the sun is covered, which is hard to notice " +
                        "without looking for it.",
                )

            peak.altitude <= 0.0 && begins.altitude > 0.0 ->
                Visibility.Marginal("The sun sets before maximum, so only the first part shows.")

            peak.altitude <= 0.0 ->
                Visibility.Marginal("The sun rises after maximum, so only the last part shows.")

            peak.altitude < LOW_DEGREES ->
                Visibility.Marginal(
                    "The sun is only ${peak.altitude.roundToInt()} degrees up at maximum, so " +
                        "you will need a clear horizon.",
                )

            else -> Visibility.Good
        }

        return SkyEvent(
            kind = EventKind.SolarEclipse,
            at = peak.time.millis(),
            from = begins.time.millis(),
            until = ends.time.millis(),
            title = "${solarWord(eclipse.kind)} eclipse of the sun",
            detail = "$covered per cent of the sun covered at maximum. " +
                "Never look at the sun without a proper solar filter.",
            visibility = visibility,
        )
    }

    private fun solarWord(kind: EclipseKind) = when (kind) {
        EclipseKind.Total -> "Total"
        EclipseKind.Annular -> "Annular"
        else -> "Partial"
    }

    // --- lunar eclipses ---

    /**
     * Umbral eclipses only.
     *
     * A penumbral eclipse is not a dim eclipse, it is an invisible one: the
     * moon passes through the Earth's outer shadow and no part of it enters the
     * umbra, so the disc looks the same to the eye. Three of the next six are
     * penumbral, and listing them would teach somebody to stop reading.
     */
    private fun lunarEclipses(observer: Observer, start: Time, until: Long): List<SkyEvent> =
        lunarEclipsesAfter(start)
            .takeWhile { it.peak.millis() <= until }
            .filter { it.kind != EclipseKind.Penumbral }
            .map { lunarEclipse(observer, it) }
            .toList()

    private fun lunarEclipse(observer: Observer, eclipse: LunarEclipseInfo): SkyEvent {
        val covered = (eclipse.obscuration * 100).roundToInt()
        val half = eclipse.sdPartial / (60.0 * 24.0)
        val begins = eclipse.peak.addDays(-half)
        val ends = eclipse.peak.addDays(half)

        val atPeak = altitudeOf(Body.Moon, observer, eclipse.peak)
        val atBegin = altitudeOf(Body.Moon, observer, begins)
        val atEnd = altitudeOf(Body.Moon, observer, ends)

        val visibility = when {
            maxOf(atBegin, atPeak, atEnd) <= 0.0 ->
                Visibility.NotFromHere(
                    "The moon is below the horizon here throughout, so none of it is visible.",
                )

            eclipse.obscuration < SLIGHT ->
                Visibility.Marginal(
                    "Only $covered per cent of the moon enters the deep shadow, which is a " +
                        "slight darkening rather than a sight.",
                )

            atPeak <= 0.0 && atBegin > 0.0 ->
                Visibility.Marginal("The moon sets before maximum, so only the first part shows.")

            atPeak <= 0.0 ->
                Visibility.Marginal("The moon rises after maximum, so only the last part shows.")

            atPeak < LOW_DEGREES ->
                Visibility.Marginal(
                    "The moon is only ${atPeak.roundToInt()} degrees up at maximum" +
                        twilightClause(observer, eclipse.peak) + ".",
                )

            else -> Visibility.Good
        }

        return SkyEvent(
            kind = EventKind.LunarEclipse,
            at = eclipse.peak.millis(),
            from = begins.millis(),
            until = ends.millis(),
            title = "${lunarWord(eclipse.kind)} eclipse of the moon",
            detail = "$covered per cent of the moon in the Earth's deep shadow at maximum. " +
                "Safe to watch with the naked eye, unlike the sun.",
            visibility = visibility,
        )
    }

    private fun lunarWord(kind: EclipseKind) =
        if (kind == EclipseKind.Total) "Total" else "Partial"

    /**
     * Added to a "low at maximum" reason when the sky is not properly dark
     * either, because the two together are what decide whether it is worth
     * setting an alarm for. The near-total eclipse of 28 August 2026 is both:
     * the moon 7 degrees up, and the sun already climbing towards dawn.
     */
    private fun twilightClause(observer: Observer, time: Time): String {
        val sun = altitudeOf(Body.Sun, observer, time)
        return if (sun > Twilight.ASTRONOMICAL_DEGREES) ", in a sky that is not fully dark" else ""
    }

    // --- greatest elongations ---

    /**
     * The nights to try for Mercury and Venus.
     *
     * Greatest elongation is the whole of what makes Mercury findable: it never
     * strays far from the sun, and this is the fortnight either side of its
     * furthest. The instant itself is usually in daylight, so the verdict comes
     * from the twilight nearest it rather than from the instant - asking how
     * high the planet is at 10 in the morning would answer a question nobody
     * has.
     */
    private fun elongations(
        latitude: Double,
        longitude: Double,
        start: Time,
        until: Long,
    ): List<SkyEvent> = listOf(Body.Mercury, Body.Venus).flatMap { body ->
        buildList {
            var found = searchMaxElongation(body, start)
            while (found.time.millis() <= until) {
                add(elongation(latitude, longitude, body, found.time, found.elongation, found.visibility))
                // Stepped past the one just found, because the search returns
                // the next elongation at or after its start time and would
                // otherwise hand back the same one forever.
                found = searchMaxElongation(body, found.time.addDays(10.0))
            }
        }
    }

    private fun elongation(
        latitude: Double,
        longitude: Double,
        body: Body,
        time: Time,
        degrees: Double,
        side: ElongationSide,
    ): SkyEvent {
        val name = if (body == Body.Mercury) "Mercury" else "Venus"
        val sky = if (side == ElongationSide.Morning) "morning" else "evening"
        val skyBody = if (body == Body.Mercury) SkyBody.Mercury else SkyBody.Venus
        val best = bestInDarkness(skyBody, latitude, longitude, time.millis())

        val visibility = when {
            best == null -> Visibility.NotFromHere(
                "There is no darkness here at this time of year.",
            )

            best.altitude <= 0.0 -> Visibility.NotFromHere(
                "$name stays below the horizon here through the whole of the night.",
            )

            best.altitude < LOW_DEGREES -> Visibility.Marginal(
                "$name only reaches ${best.altitude.roundToInt()} degrees, low in the " +
                    "${Compass.of(best.azimuth).label}, so you will need a clear horizon.",
            )

            else -> Visibility.Good
        }

        return SkyEvent(
            kind = EventKind.Elongation,
            at = time.millis(),
            from = time.addDays(-3.0).millis(),
            until = time.addDays(3.0).millis(),
            title = "$name at its furthest from the sun",
            detail = "${degrees.roundToInt()} degrees away, in the $sky sky. " +
                "The best fortnight of this apparition to look.",
            visibility = visibility,
        )
    }

    // --- moon phases ---

    /**
     * The phases, which never interrupt anybody.
     *
     * They are in the calendar because everything else is planned around them -
     * a new moon is the darkest sky of the month and a full one washes out
     * anything faint - and out of the notifications because a phase arrives
     * every 7.4 days and an app that says so is an app that gets muted.
     */
    private fun moonPhases(start: Time, until: Long): List<SkyEvent> =
        moonQuartersAfter(start)
            .takeWhile { it.time.millis() <= until }
            .map(::moonPhase)
            .toList()

    private fun moonPhase(quarter: MoonQuarterInfo): SkyEvent {
        val at = quarter.time.millis()
        val (title, detail) = when (quarter.quarter) {
            0 -> "New moon" to
                "No moon in the sky tonight: the darkest skies of the month, and the best " +
                    "fortnight for anything faint."
            1 -> "First quarter" to "Half lit, setting around midnight."
            2 -> "Full moon" to
                "Up all night and bright enough to wash out meteors and anything faint."
            else -> "Last quarter" to "Half lit, rising around midnight."
        }
        return SkyEvent(
            kind = EventKind.MoonPhase,
            at = at,
            from = at,
            until = at,
            title = title,
            detail = detail,
            visibility = Visibility.Good,
        )
    }

    // --- the shared darkness question ---

    /**
     * How high [body] gets during the darkness nearest [aroundMillis], and
     * where to look for it then.
     *
     * Elongations, conjunctions and shower peaks all need this same answer, and
     * all three have the same trap: the instant that defines the event is
     * usually the wrong moment to evaluate it. A greatest elongation at 10 in
     * the morning is still a fact about that evening.
     *
     * Null when there is no darkness to be had, which is a real answer rather
     * than a failure - the midnight sun and Shetland's simmer dim both produce
     * it, and Sec. 5 says the app must handle them rather than treat them as
     * edge cases.
     */
    fun bestInDarkness(
        body: SkyBody,
        latitude: Double,
        longitude: Double,
        aroundMillis: Long,
    ): Sighting? {
        val window = nightNearest(latitude, longitude, aroundMillis) ?: return null
        val observer = observer(latitude, longitude)
        val target = bodyOf(body)

        var best: Sighting? = null
        var at = window.first
        while (at <= window.second) {
            val time = Time.fromMillisecondsSince1970(at)
            val position = equator(target, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
            val seen = horizon(time, observer, position.ra, position.dec, Refraction.Normal)
            if (best == null || seen.altitude > best.altitude) {
                best = Sighting(at, seen.altitude, seen.azimuth)
            }
            at += SAMPLE_MILLIS
        }
        return best
    }

    /**
     * The stretch of darkness whose middle is closest to [aroundMillis].
     *
     * Three candidates from a day before, because an event at midday belongs to
     * the evening after it and one at four in the morning belongs to the night
     * it is already in, and picking by nearest midpoint gets both right without
     * either being a special case.
     */
    private fun nightNearest(
        latitude: Double,
        longitude: Double,
        aroundMillis: Long,
    ): Pair<Long, Long>? {
        var cursor = aroundMillis - DAY_MILLIS
        val windows = mutableListOf<Pair<Long, Long>>()

        repeat(3) {
            when (val darkness = Twilight.darkness(latitude, longitude, cursor)) {
                is Darkness.Now -> {
                    val ends = darkness.endsAt ?: (cursor + UNBOUNDED_NIGHT_MILLIS)
                    windows += cursor to ends
                    cursor = ends + SAMPLE_MILLIS
                }

                is Darkness.Later -> {
                    val ends = darkness.endsAt ?: (darkness.startsAt + UNBOUNDED_NIGHT_MILLIS)
                    windows += darkness.startsAt to ends
                    cursor = ends + SAMPLE_MILLIS
                }

                Darkness.None -> return@repeat
            }
        }

        return windows.minByOrNull { (from, until) ->
            kotlin.math.abs((from + until) / 2 - aroundMillis)
        }
    }

    // --- shared plumbing ---

    /**
     * Apparent altitude, refraction included - the opposite of [Twilight]'s
     * choice, and deliberately.
     *
     * Twilight matches the airless search that defines its own window
     * boundaries. Here the question is whether a person can see the thing,
     * and near the horizon refraction lifts a body by about half a degree,
     * which is most of a moon's width.
     */
    private fun altitudeOf(body: Body, observer: Observer, time: Time): Double {
        val position = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        return horizon(time, observer, position.ra, position.dec, Refraction.Normal).altitude
    }

    /** Sea level throughout, on the same reasoning as [Twilight]. */
    private fun observer(latitude: Double, longitude: Double) = Observer(latitude, longitude, 0.0)

    private fun bodyOf(body: SkyBody) = when (body) {
        SkyBody.Mercury -> Body.Mercury
        SkyBody.Venus -> Body.Venus
        SkyBody.Mars -> Body.Mars
        SkyBody.Jupiter -> Body.Jupiter
        SkyBody.Saturn -> Body.Saturn
        SkyBody.Moon -> Body.Moon
    }

    private fun Time.millis(): Long = toMillisecondsSince1970()
}
