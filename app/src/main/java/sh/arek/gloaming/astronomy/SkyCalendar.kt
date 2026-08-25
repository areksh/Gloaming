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
     * How close an eclipse has to be to a quarter phase to be the same event.
     *
     * Maximum eclipse and exact full or new moon are the same alignment seen
     * two ways and never fall more than an hour or so apart.
     */
    private const val ECLIPSE_IS_A_PHASE_MILLIS = 6L * 60 * 60 * 1000

    /** The moon is half a degree across, and is the only ruler anyone carries. */
    private const val MOON_WIDTH_DEGREES = 0.5

    /**
     * How much wider the seen separation has to be than the true minimum before
     * saying so is worth the words. A whole moon's width is the point at which
     * the difference is something you would notice rather than arithmetic.
     */
    private const val DRIFT_WORTH_SAYING = MOON_WIDTH_DEGREES

    /** Beyond this many moon-widths the comparison stops being a picture. */
    private const val WIDTHS_WORTH_COUNTING = 4

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
            addAll(conjunctions(latitude, longitude, fromMillis, until))
            addAll(showers(latitude, longitude, fromMillis, until))
            addAll(moonPhases(start, until))
        }
            .filter { it.at in fromMillis..until }
            .let(::withoutPhasesThatAreEclipses)
            .sortedBy { it.at }
    }

    /**
     * A solar eclipse happens at new moon and a lunar one at full, always, by
     * definition. Printing both is printing the same fact twice seven minutes
     * apart, and the eclipse is by far the more interesting half of it.
     */
    private fun withoutPhasesThatAreEclipses(events: List<SkyEvent>): List<SkyEvent> {
        val eclipses = events
            .filter { it.kind == EventKind.SolarEclipse || it.kind == EventKind.LunarEclipse }
            .map { it.at }

        return events.filterNot { event ->
            event.kind == EventKind.MoonPhase &&
                eclipses.any { kotlin.math.abs(it - event.at) <= ECLIPSE_IS_A_PHASE_MILLIS }
        }
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

            // Almost always because it sets before the sky is dark rather than
            // because it never rises: an inner planet at greatest elongation is
            // still close to the sun, and from a high latitude in autumn the
            // ecliptic meets the horizon at an angle that takes it straight
            // down with it.
            best.altitude <= 0.0 -> Visibility.NotFromHere(
                "$name never clears the horizon here once the sky is dark, so this " +
                    "apparition is not one for this latitude.",
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

    // --- conjunctions ---

    /**
     * Two bright things close together, reported as they are seen.
     *
     * The list is generous where the notifications will not be: three degrees
     * catches roughly one pairing a fortnight, which is about right for
     * something you scroll past and far too much for something that buzzes.
     * [Conjunctions.STRIKING_DEGREES] is the tighter bar, applied where the
     * interrupting is decided.
     *
     * The event is dated to the moment it is best seen rather than to the
     * moment it is closest, because the calendar's job is to name a night to go
     * out on. When the two differ by enough to matter the detail says so.
     */
    private fun conjunctions(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        until: Long,
    ): List<SkyEvent> = Conjunctions
        .approaches(latitude, longitude, fromMillis, until)
        .mapNotNull { conjunction(it, latitude, longitude) }

    private fun conjunction(
        approach: Approach,
        latitude: Double,
        longitude: Double,
    ): SkyEvent? {
        val seen = Conjunctions.asSeen(approach, latitude, longitude) ?: return null
        val night = Twilight.nightNearest(latitude, longitude, approach.atMillis)

        // Dropped rather than explained, which is the opposite of what eclipses
        // and elongations get. Those are rare, people hear about them from
        // elsewhere, and "not from where you are" is the answer they came for.
        // Nobody hears about a conjunction, so one that cannot be seen is not a
        // disappointment to be headed off - it is clutter.
        if (seen.altitude <= 0.0) return null

        // The pair has to look like a conjunction at the moment it is visible,
        // not merely at the moment it is closest. The moon comes within three
        // degrees of Mars on 7 September 2026 and is six degrees away by the
        // time either of them clears the horizon at Edinburgh, which is the
        // moon being vaguely near Mars rather than an event.
        if (seen.separationDegrees > Conjunctions.NOTABLE_DEGREES) return null

        val visibility = when {
            seen.altitude < LOW_DEGREES -> Visibility.Marginal(
                "They only reach ${seen.altitude.roundToInt()} degrees, low in the " +
                    "${Compass.of(seen.azimuth).label}, so you will need a clear horizon.",
            )

            else -> Visibility.Good
        }

        // The figure quoted is the one somebody standing outside will see. When
        // the true minimum happens in daylight or below the horizon, quoting it
        // would promise a sight that is not on offer here.
        val apart = seen.separationDegrees
        val drifted = seen.separationDegrees - approach.separationDegrees > DRIFT_WORTH_SAYING

        return SkyEvent(
            kind = EventKind.Conjunction,
            at = seen.atMillis,
            from = night?.first ?: approach.atMillis,
            until = night?.last ?: approach.atMillis,
            title = pairing(approach),
            detail = buildString {
                append(apartInWords(apart))
                if (visibility is Visibility.Good) {
                    append(", ${seen.altitude.roundToInt()} degrees up in the ")
                    append(Compass.of(seen.azimuth).label)
                }
                append(".")
                if (drifted) {
                    append(
                        " They come closer still, but not while they are above the horizon here.",
                    )
                }
            },
            visibility = visibility,
        )
    }

    /**
     * The moon leads when it is involved, because that is how it is said aloud:
     * the moon passes Jupiter, not Jupiter passes the moon.
     */
    private fun pairing(approach: Approach): String {
        val moon = approach.first == SkyBody.Moon || approach.second == SkyBody.Moon
        if (moon) {
            val other = if (approach.first == SkyBody.Moon) approach.second else approach.first
            return "The moon passes ${other.label}"
        }
        return "${approach.first.label} and ${approach.second.label} at their closest"
    }

    /**
     * Degrees, and then the same thing in a unit anyone can check by looking up.
     *
     * "0.5 degrees" means nothing at arm's length. A moon's width is half a
     * degree and is the only angular measure most people have ever had to hand.
     */
    private fun apartInWords(degrees: Double): String {
        val figure = String.format(java.util.Locale.UK, "%.1f", degrees)
        val widths = (degrees / MOON_WIDTH_DEGREES).roundToInt()
        // Past about four moon-widths the comparison stops helping: nobody can
        // picture nine of them in a row, and the number on its own is clearer.
        val comparison = when {
            degrees < MOON_WIDTH_DEGREES / 2 -> " - closer together than half a moon's width"
            widths <= 1 -> " - about a moon's width apart"
            widths <= WIDTHS_WORTH_COUNTING -> " - about $widths moon-widths apart"
            else -> " apart"
        }
        return "$figure degrees$comparison"
    }

    // --- meteor showers ---

    /**
     * The peak nights, with the two things most shower forecasts leave out.
     *
     * A ZHR is quoted everywhere and is a laboratory figure; how high the
     * radiant gets and what the moon is doing are what actually decide the
     * night, and both depend on where the observer is. A rate of 150 with the
     * radiant at 15 degrees under a gibbous moon is a rate of about five.
     *
     * Unlike a conjunction, a shower whose radiant never rises is still worth
     * listing with its reason: people hear about the Perseids from the
     * newspapers, and being told they belong to the other hemisphere is the
     * answer they came for.
     */
    private fun showers(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        until: Long,
    ): List<SkyEvent> = Showers
        .peaks(latitude, longitude, fromMillis, until)
        .map { shower(it, latitude, longitude) }

    private fun shower(peak: ShowerPeak, latitude: Double, longitude: Double): SkyEvent {
        val night = Twilight.nightNearest(latitude, longitude, peak.peaksAt)
        val moonPercent = (peak.moonIllumination * 100).roundToInt()

        val visibility = when {
            peak.radiantAltitude <= 0.0 -> Visibility.NotFromHere(
                "The radiant never rises here, so this one belongs to the other hemisphere.",
            )

            night == null -> Visibility.NotFromHere(
                "There is no darkness here at this time of year.",
            )

            peak.radiantAltitude < Showers.LOW_RADIANT_DEGREES -> Visibility.Marginal(
                "The radiant only reaches ${peak.radiantAltitude.roundToInt()} degrees, so " +
                    "most of the shower happens below the horizon.",
            )

            peak.moonInterference > Showers.MOONLIT -> Visibility.Marginal(
                "The moon is $moonPercent per cent lit and up for most of the night, which " +
                    "will wash out all but the brightest.",
            )

            else -> Visibility.Good
        }

        return SkyEvent(
            kind = EventKind.MeteorShower,
            // Dated to the dark hours rather than to the maximum, which often
            // falls in the middle of the day: the useful answer is which night
            // to go out on.
            at = peak.bestAt,
            from = night?.first ?: peak.peaksAt,
            until = night?.last ?: peak.peaksAt,
            title = "${peak.shower.name} at their best",
            detail = buildString {
                if (peak.hourlyRateFromHere > 0) {
                    append("Perhaps ${peak.hourlyRateFromHere} an hour from here at its best, ")
                    append("under a properly dark sky - a town cuts that by more than half. ")
                } else {
                    append("Up to ${peak.shower.zenithHourlyRate} an hour where the radiant ")
                    append("rises. ")
                }
                append("Look ${Compass.of(peak.radiantAzimuth).label}")
                if (peak.radiantAltitude > 0.0) {
                    append(", ${peak.radiantAltitude.roundToInt()} degrees up")
                }
                append(", though they can appear anywhere in the sky. ")
                append("Debris from ${peak.shower.parent}. ")
                append("Active ${peak.shower.activeFrom} to ${peak.shower.activeUntil}.")
            },
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
        val window = Twilight.nightNearest(latitude, longitude, aroundMillis) ?: return null
        val observer = observer(latitude, longitude)
        val target = bodyOf(body)

        var best: Sighting? = null
        var at = window.first
        while (at <= window.last) {
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
