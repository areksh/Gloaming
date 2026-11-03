package sh.arek.gloaming.ui

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.SunTimes
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * How loudly a row speaks: dimmed where it will not be seen - behind cloud, or
 * a pass in the earth's shadow - strong where the outlook sends you out.
 */
enum class RowTone { Normal, Dim, Strong }

/**
 * How a lane is drawn. Only the moon has one now - the planets moved to the
 * chart above - but a lane drawn from a snapshot made before that still reads.
 */
enum class LaneStyle { Solid, Dashed, Dotted }

/**
 * Which of the outlook's recommendations a row is: the headline's one trip
 * out, or the line under it.
 */
enum class PickTag { Go, Also }

/** What a row is about, so the screen can give it an icon and a tap target. */
@Serializable
sealed interface RowSubject {
    @Serializable
    data object Twilight : RowSubject
    @Serializable
    data object Clear : RowSubject
    @Serializable
    data object Moon : RowSubject
    @Serializable
    data class Pass(val pass: SatellitePass) : RowSubject
    @Serializable
    data class Planet(val planet: PlanetTonight) : RowSubject
    @Serializable
    data class Event(val event: SkyEvent) : RowSubject
    /** A pattern at its highest: a busy row on the timeline, and listed on Sky. */
    @Serializable
    data class Constellation(val seen: ConstellationTonight) : RowSubject
}

/** How a pass is headed, on its timeline row and on its sheet alike. */
internal fun passTitle(pass: SatellitePass): String = "${pass.name}, ${pass.peakElevation.roundToInt()} degrees high"

/** The line under a pass's heading: the two ends of it. */
internal fun passEnds(pass: SatellitePass): String =
    "Rises ${Compass.of(pass.riseAzimuth).label}, sets ${Compass.of(pass.setAzimuth).label}"

/** How a planet's highest moment is headed, on its row and on its sheet alike. */
internal fun planetPeakTitle(planet: PlanetTonight): String =
    "${planet.body.label} highest, ${planet.peakAltitude.roundToInt()} degrees"

/** The line under a planet's heading: which way to face. */
internal fun planetPeakDetail(planet: PlanetTonight): String = "In the ${Compass.of(planet.peakAzimuth).label}"

@Serializable
data class TimelineRow(
    val atMillis: Long,
    /** Local clock time, as read off the device's own zone. */
    val time: String,
    val title: String,
    val detail: String?,
    val tone: RowTone,
    val subject: RowSubject,
    /** Set where the outlook recommends this, which the row says with a pill. */
    val pick: PickTag? = null,
    /**
     * Something to browse rather than to go out for: shown only with the
     * Busy setting on, and counted under the timeline when it is off.
     */
    val busy: Boolean = false,
)

/** A stretch of forecast cloud cover, in percent, drawn as shading down the time axis. */
@Serializable
data class CloudSpan(val fromMillis: Long, val untilMillis: Long, val cover: Int)

/** Something that is up for a stretch of the night: the moon or a bright planet. */
@Serializable
data class Lane(val label: String, val style: LaneStyle, val fromMillis: Long, val untilMillis: Long)

/**
 * Tonight from sunset to sunrise, as data for a time-proportional axis.
 *
 * Everything here is in real milliseconds, never clock readings: on the night
 * the clocks change the axis must be as long as the night really is, and only
 * the labels follow the wall clock.
 */
@Serializable
data class Timeline(
    val startMillis: Long,
    val endMillis: Long,
    val rows: List<TimelineRow>,
    val cloud: List<CloudSpan>,
    val lanes: List<Lane>,
    /** Where now falls on the axis, null once the night is over or before it starts. */
    val nowMillis: Long?,
    /** How far down the sun is through the night, for colouring the sky column. */
    val sun: List<SunSample> = emptyList(),
    /** The moments of the headline's trip out, which a bracket joins. */
    val trip: List<Long> = emptyList(),
    /** The moments the line under the headline names. */
    val alsoAt: List<Long> = emptyList(),
)

/** The sun's altitude, in degrees, at one moment of the night. */
@Serializable
data class SunSample(val atMillis: Long, val altitude: Double)

/** Builds the [Timeline] and does the arithmetic of laying its rows out. Pure and clock-free. */
object NightTimeline {

    private const val HOUR_MILLIS: Long = 60 * 60 * 1000L
    private const val SUN_STEP_MILLIS: Long = 15 * 60 * 1000L

    /** Cover at or above which a stretch of the night is called mostly cloudy. */
    private const val CLOUDY_PERCENT: Int = 50

    /**
     * The verdicts that mean it will not be seen at all, which a row says by
     * being dimmed: behind cloud, or a pass in the earth's shadow. The rest -
     * low, in twilight, already over - are things a row can still be true of.
     */
    private val UNSEEN = setOf(Gate.Cloud, Gate.Sunlit)

    /**
     * The calendar's dates rather than its sights: worth knowing, and shown
     * only on a busy timeline. An eclipse, a shower or a conjunction is
     * something to go out for, and is always shown.
     */
    private val ALMANAC = setOf(
        EventKind.MoonPhase,
        EventKind.MoonQuarter,
        EventKind.Season,
        EventKind.Elongation,
        EventKind.Opposition,
        EventKind.GreatestBrightness,
    )

    /** Below this much of the disc lit, the moon spoils nothing and the night is moonless. */
    private const val MOONLESS_ILLUMINATION: Double = 0.03

    /**
     * Null when there is no sunset or no sunrise to bound the night - the
     * midnight sun, the polar night, or no position at all. A night with
     * no civil darkness but a sunset and sunrise still has a timeline.
     *
     * The rows are the night's moments: twilight, every gap in the cloud, the
     * moon, events, passes, and what the outlook recommends. The planets are
     * not among them - the chart draws when each is up - except one the
     * outlook picks, which gets a row at the moment it was picked for.
     *
     * Every row the night could show is here, the ones to browse flagged
     * [TimelineRow.busy], so the Busy setting is a filter on the screen and
     * never a reason to work the night out again.
     *
     * Passes are the list's best ones only, so every pass row has a verdict
     * from the outlook to match. Passes and events match a verdict on subject
     * and moment.
     */
    fun of(
        list: TonightList,
        report: OutlookReport,
        hourly: List<HourlyCover>,
        settings: UserSettings,
        nowMillis: Long,
    ): Timeline? {
        val latitude = settings.latitude ?: return null
        val longitude = settings.longitude ?: return null
        val darkFrom = list.darkFrom
        val darkUntil = list.darkUntil

        val sun = if (darkFrom != null && darkUntil != null) {
            Twilight.sunTimes(latitude, longitude, darkFrom..darkUntil)
        } else {
            nightByTheSun(latitude, longitude, nowMillis)
        }
        val start = sun.setsAt ?: return null
        val end = sun.risesAt ?: return null
        if (end <= start) return null

        val goOut = report.outlook as? Outlook.GoOut
        val trip = goOut?.picks.orEmpty()
        val also = goOut?.alsoTonight.orEmpty()
        val unseen = report.excluded.filter { it.gate in UNSEEN }

        fun tagOf(match: (Pick) -> Boolean): PickTag? = when {
            trip.any(match) -> PickTag.Go
            also.any(match) -> PickTag.Also
            else -> null
        }

        fun toneOf(pick: PickTag?, dimmed: Boolean) = when {
            pick != null -> RowTone.Strong
            dimmed -> RowTone.Dim
            else -> RowTone.Normal
        }

        val rows = mutableListOf<TimelineRow>()

        fun row(
            at: Long,
            title: String,
            subject: RowSubject,
            detail: String? = null,
            tone: RowTone = RowTone.Normal,
            pick: PickTag? = null,
            busy: Boolean = false,
        ) {
            if (at in start..end) rows += TimelineRow(at, clock(at), title, detail, tone, subject, pick, busy && pick == null)
        }

        row(start, "Sunset", RowSubject.Twilight)
        darkFrom?.let { row(it, "Dark enough for aurora", RowSubject.Twilight) }
        list.darkestFrom?.takeIf { it != darkFrom }?.let { row(it, "Fully dark", RowSubject.Twilight) }

        val announced = list.announcedPass
        for (pass in list.bestPasses) {
            val pick = tagOf { it.kind == PickKind.Pass && it.subject == pass.name && it.atMillis == pass.peaksAt }
            val dimmed = unseen.any { it.subject == pass.name && it.atMillis == pass.peaksAt }
            val wanted = pass.channel == PassChannel.Iss || pass.channel == PassChannel.Trains ||
                (announced != null && pass.catalogue == announced.catalogue && pass.peaksAt == announced.peaksAt)
            row(
                pass.peaksAt,
                passTitle(pass),
                RowSubject.Pass(pass),
                passEnds(pass),
                toneOf(pick, dimmed),
                pick,
                busy = !wanted,
            )
        }

        list.moon?.let { moon ->
            val phase = "${describeMoonPhase(moon.phaseDegrees, moon.illumination)}, " +
                "${(moon.illumination * 100).roundToInt()}% lit"
            moon.risesAt?.let { row(it, "Moonrise", RowSubject.Moon, phase) }
            moon.setsAt?.let { row(it, "Moonset", RowSubject.Moon, phase) }
            moonless(list, moon)?.let { (at, title) -> row(at, title, RowSubject.Moon, "Best for faint things") }
        }

        for (pick in trip + also) {
            val planet = pick.planet ?: continue
            if (pick.kind != PickKind.Planet) continue
            val seen = Planets.where(planet.body, latitude, longitude, pick.atMillis)
            val tag = if (pick in trip) PickTag.Go else PickTag.Also
            row(
                pick.atMillis,
                "${planet.body.label}, ${seen.altitude.roundToInt()} degrees high",
                RowSubject.Planet(planet),
                "In the ${Compass.of(seen.azimuth).label}",
                RowTone.Strong,
                tag,
            )
        }

        for (event in list.events) {
            val pick = tagOf { it.kind == PickKind.Event && it.subject == event.title && it.event?.at == event.at }
            val dimmed = unseen.any { it.subject == event.title && it.atMillis == event.at }
            row(
                event.at,
                event.title,
                RowSubject.Event(event),
                tone = toneOf(pick, dimmed),
                pick = pick,
                busy = event.kind in ALMANAC,
            )
        }

        list.noctilucent?.let { nlc ->
            val pick = tagOf { it.kind == PickKind.Event && it.subject == nlc.title }
            row(
                nlc.from,
                "Look for noctilucent cloud",
                RowSubject.Event(nlc),
                "Low in the ${report.poleward.label} until ${clock(nlc.until)}",
                toneOf(pick, dimmed = false),
                pick,
            )
        }

        // The ones the app leads with, whatever the Sky tab is set to list:
        // every pattern up would bury the night's moments.
        val patterns = list.everyConstellation.ifEmpty { list.constellations }
        for (seen in Tonight.worthListing(patterns, settings.copy(showEveryConstellation = false))) {
            row(
                seen.peakAt,
                "${seen.constellation.name} highest, ${seen.peakAltitude.roundToInt()} degrees",
                RowSubject.Constellation(seen),
                "In the ${Compass.of(seen.peakAzimuth).label}",
                busy = true,
            )
        }

        if (darkFrom != null && darkUntil != null) {
            val moments = (trip + also).map { it.atMillis }
            ClearSpell.all(hourly, settings.cloudTolerance, darkFrom, darkUntil, nowMillis)
                .forEachIndexed { i, spell ->
                    val title = if (i == 0) "Clear until ${clock(spell.until)}" else "Clear again until ${clock(spell.until)}"
                    val holdsPick = moments.any { it in spell.from..spell.until }
                    row(spell.from, title, RowSubject.Clear, tone = if (holdsPick) RowTone.Strong else RowTone.Normal)
                    if (!spell.allNight && spell.until < darkUntil) row(spell.until, "Cloud returns", RowSubject.Clear)
                }
        }

        (list.darkestUntil ?: darkUntil)?.let { row(it, "First light", RowSubject.Twilight) }
        row(end, "Sunrise", RowSubject.Twilight)

        return Timeline(
            startMillis = start,
            endMillis = end,
            rows = rows.sortedBy { it.atMillis },
            cloud = cloudSpans(hourly, start, end),
            lanes = lanes(list, start, end),
            nowMillis = nowMillis.takeIf { it in start..end },
            sun = sunSamples(latitude, longitude, start, end),
            trip = trip.map { it.atMillis },
            alsoAt = also.map { it.atMillis },
        )
    }

    /**
     * Where the darkest part of the night has no moon in it, as the moment it
     * starts and what to call it; null where the moon is up throughout.
     *
     * The first stretch only: the moon crosses the horizon at most once each
     * way in a night, so a second would need it to set, rise and set again.
     */
    private fun moonless(list: TonightList, moon: MoonTonight): Pair<Long, String>? {
        val from = list.darkestFrom ?: list.darkFrom ?: return null
        val until = list.darkestUntil ?: list.darkUntil ?: return null
        if (until <= from) return null
        if (moon.illumination < MOONLESS_ILLUMINATION) return from to "Moonless all night"

        val up = upSpans(moon, from, until)
        var cursor = from
        for (span in up.sortedBy { it.first }) {
            if (span.first > cursor) return cursor to downUntil(cursor, span.first, from, until)
            cursor = maxOf(cursor, span.last)
        }
        return if (cursor < until) cursor to downUntil(cursor, until, from, until) else null
    }

    private fun downUntil(down: Long, up: Long, from: Long, until: Long): String = when {
        down <= from && up >= until -> "Moonless all night"
        up >= until -> "Moonless dark until first light"
        else -> "Moonless dark until ${clock(up)}"
    }

    /** When the moon is up between [start] and [end], from its crossings. */
    private fun upSpans(moon: MoonTonight, start: Long, end: Long): List<LongRange> {
        val spans = mutableListOf<LongRange>()
        fun add(from: Long, until: Long) {
            val f = from.coerceIn(start, end)
            val u = until.coerceIn(start, end)
            if (f < u) spans += f..u
        }
        val rises = moon.risesAt
        val sets = moon.setsAt
        when {
            rises != null && sets != null && sets < rises -> {
                add(start, sets)
                add(rises, end)
            }
            rises != null && sets != null -> add(rises, sets)
            rises != null -> add(rises, end)
            sets != null -> add(start, sets)
            moon.upFraction >= 0.5 -> add(start, end)
        }
        return spans
    }

    /**
     * Every quarter of an hour from sunset to sunrise, both ends included.
     * Fine enough that the twilight bands blend rather than step at 56 dp or
     * more an hour, and few enough to work out with the rest of the night.
     */
    private fun sunSamples(latitude: Double, longitude: Double, start: Long, end: Long): List<SunSample> {
        val times = generateSequence(start) { it + SUN_STEP_MILLIS }.takeWhile { it < end }.toList() + end
        return times.map { SunSample(it, Twilight.sunAltitude(latitude, longitude, it)) }
    }

    /**
     * The night containing [nowMillis], else the coming one, by sunset and
     * sunrise alone - for a northern midsummer, where civil darkness never
     * comes but the sun still goes down.
     *
     * The sunrise is searched from the sunset, never from now: from now it
     * would skip a sunrise already past and span a day and more.
     */
    private fun nightByTheSun(latitude: Double, longitude: Double, nowMillis: Long): SunTimes {
        fun nightFrom(anchor: Long): SunTimes {
            val set = Twilight.sunTimes(latitude, longitude, anchor..(anchor + 24 * HOUR_MILLIS)).setsAt
                ?: return SunTimes(null, null)
            return Twilight.sunTimes(latitude, longitude, set..(set + 24 * HOUR_MILLIS))
        }

        val current = nightFrom(nowMillis)
        val rise = current.risesAt ?: return current
        return if (rise >= nowMillis) current else nightFrom(rise + 12 * HOUR_MILLIS)
    }

    /**
     * The drawing in one paragraph, for a screen reader: the span of the
     * night, when it is mostly cloudy, what each lane says is up, and now.
     * The rows say the rest themselves.
     */
    fun summary(timeline: Timeline): String {
        val start = timeline.startMillis
        val end = timeline.endMillis

        fun span(from: Long, until: Long): String = when {
            from <= start && until >= end -> "all night"
            from <= start -> "until ${clock(until)}"
            until >= end -> "from ${clock(from)}"
            else -> "from ${clock(from)} to ${clock(until)}"
        }

        val sentences = mutableListOf("Tonight from sunset at ${clock(start)} to sunrise at ${clock(end)}.")

        val cloudy = mutableListOf<LongRange>()
        for (span in timeline.cloud.sortedBy { it.fromMillis }.filter { it.cover >= CLOUDY_PERCENT }) {
            val last = cloudy.lastOrNull()
            if (last != null && last.last >= span.fromMillis) {
                cloudy[cloudy.lastIndex] = last.first..maxOf(last.last, span.untilMillis)
            } else {
                cloudy += span.fromMillis..span.untilMillis
            }
        }
        if (cloudy.isNotEmpty()) {
            sentences += "Mostly cloudy " + cloudy.joinToString(", and ") { span(it.first, it.last) } + "."
        }

        for (lane in timeline.lanes) {
            val name = if (lane.label == "Moon") "The moon" else lane.label
            sentences += "$name is up ${span(lane.fromMillis, lane.untilMillis)}."
        }

        timeline.nowMillis?.let { sentences += "Now ${clock(it)}." }
        return sentences.joinToString(" ")
    }

    /**
     * Something placed beside the axis: a row, or the label at now. [lead] is
     * how far below its top the point is that sits on its moment - the middle
     * of a row's first line, the middle of the label.
     */
    data class Slot(val atMillis: Long, val lead: Int, val height: Int)

    /** The axis that came of placing the slots, and each slot's top, in their order. */
    data class Placed(val axis: NightAxis, val tops: List<Int>)

    /**
     * Places [slots], which must be in time order, and draws the axis through
     * them, so the sky column, the cloud, the lanes and the line at now stay
     * level with the rows that name them.
     *
     * An hour is [hourPx] tall wherever the slots leave room. Where one would
     * overlap the slot above it - closer than [gap] - it is pushed down to
     * clear it, and the axis stretches with it: the time after it is measured
     * from where it was pushed to, never from where it would have been. So an
     * hour is never shorter than [hourPx], only longer where the night is
     * crowded.
     *
     * Before this the rows were pushed and the axis was not. A crowded evening
     * left them hours below their moments by morning, and now, drawn at its
     * moment on the axis, sat among the rows at 23:00 at five past midnight.
     */
    fun place(startMillis: Long, slots: List<Slot>, hourPx: Float, pad: Int, gap: Int): Placed {
        if (slots.isEmpty()) return Placed(NightAxis(longArrayOf(startMillis), floatArrayOf(pad.toFloat()), hourPx), emptyList())
        val perMilli = hourPx / HOUR_MILLIS
        val ys = FloatArray(slots.size)
        val tops = IntArray(slots.size)
        for (i in slots.indices) {
            val slot = slots[i]
            ys[i] = if (i == 0) {
                maxOf(pad + (slot.atMillis - startMillis) * perMilli, slot.lead.toFloat())
            } else {
                val above = slots[i - 1]
                maxOf(
                    ys[i - 1] + (slot.atMillis - above.atMillis) * perMilli,
                    (tops[i - 1] + above.height + gap + slot.lead).toFloat(),
                )
            }
            tops[i] = (ys[i] - slot.lead).roundToInt()
        }
        return Placed(NightAxis(LongArray(slots.size) { slots[it].atMillis }, ys, hourPx), tops.toList())
    }

    private fun cloudSpans(hourly: List<HourlyCover>, start: Long, end: Long): List<CloudSpan> =
        hourly.sortedBy { it.atMillis }.mapNotNull {
            val from = maxOf(it.atMillis, start)
            val until = minOf(it.atMillis + HOUR_MILLIS, end)
            if (from < until) CloudSpan(from, until, it.effective) else null
        }

    /** The moon's lane; the planets are drawn on the chart above instead. */
    private fun lanes(list: TonightList, start: Long, end: Long): List<Lane> {
        val moon = list.moon ?: return emptyList()
        return upSpans(moon, start, end).map { Lane("Moon", LaneStyle.Solid, it.first, it.last) }
    }
}

/**
 * Where each moment of the night falls down the drawing, in pixels: straight
 * between the knots [NightTimeline.place] put at each row, and [hourPx] an
 * hour beyond them. Two knots at one moment are a step, which that moment
 * reads at the top of.
 */
class NightAxis internal constructor(
    private val times: LongArray,
    private val ys: FloatArray,
    private val hourPx: Float,
) {
    fun y(atMillis: Long): Float {
        val perMilli = hourPx / (60 * 60 * 1000f)
        if (atMillis <= times.first()) return ys.first() - (times.first() - atMillis) * perMilli
        if (atMillis > times.last()) return ys.last() + (atMillis - times.last()) * perMilli
        // The first knot at or after the moment; the one before it is the last before.
        var lo = 0
        var hi = times.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < atMillis) lo = mid + 1 else hi = mid
        }
        if (times[lo] == atMillis) return ys[lo]
        val fraction = (atMillis - times[lo - 1]).toFloat() / (times[lo] - times[lo - 1])
        return ys[lo - 1] + (ys[lo] - ys[lo - 1]) * fraction
    }
}
