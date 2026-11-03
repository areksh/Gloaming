package sh.arek.gloaming.ui

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The stretch of the night the strip draws: all of it, or the hours round now.
 *
 * [zoomed] only once the night is under way and the reader has not asked for
 * the whole of it.
 */
data class StripWindow(val fromMillis: Long, val untilMillis: Long, val zoomed: Boolean)

/** A whole hour on the strip's axis, and whether it is labelled. */
data class HourTick(val atMillis: Long, val hour: Int, val named: Boolean)

/** A picked pass or event, marked across the sky band at its moment. */
data class StripTick(val atMillis: Long, val label: String, val tag: PickTag)

/**
 * The night as one horizontal strip, from sunset to sunrise: the sky, the
 * cloud, a bar for each body, ticks for what was picked and a bracket over the
 * trip out. Pure and clock-free; the drawing is [NightStripView].
 *
 * Everything is in real milliseconds and only the labels read the wall clock,
 * so the night the clocks change is as long on the axis as it really is.
 */
object NightStrips {

    /** How far behind now the zoomed strip starts. */
    const val ZOOM_BEHIND_MILLIS: Long = 30 * 60 * 1000L

    /** How far ahead of now the zoomed strip runs. */
    const val ZOOM_AHEAD_MILLIS: Long = 150 * 60 * 1000L

    /** Cover at or above which a stretch of the night is called mostly cloudy. */
    private const val CLOUDY_PERCENT: Int = 50

    /**
     * The whole night, or - with [zoom] and the night under way - three hours
     * from half an hour before now. The three hours slide rather than run
     * past sunset or sunrise, and a night shorter than that is drawn whole.
     */
    fun window(timeline: Timeline, zoom: Boolean): StripWindow {
        val start = timeline.startMillis
        val end = timeline.endMillis
        val now = timeline.nowMillis
        if (!zoom || now == null) return StripWindow(start, end, zoomed = false)
        val span = minOf(ZOOM_BEHIND_MILLIS + ZOOM_AHEAD_MILLIS, end - start)
        val from = (now - ZOOM_BEHIND_MILLIS).coerceIn(start, end - span)
        return StripWindow(from, from + span, zoomed = true)
    }

    /**
     * Every whole hour after [fromMillis] up to [untilMillis], read off the
     * clock in [zone], every third named. Truncated in the zone, so a zone half
     * an hour off UTC has its ticks on its own hours, and stepped in real
     * hours, so the night the clocks go back has the hour of one o'clock twice.
     */
    fun hourTicks(fromMillis: Long, untilMillis: Long, zone: ZoneId): List<HourTick> {
        val ticks = mutableListOf<HourTick>()
        var at = Instant.ofEpochMilli(fromMillis).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
        while (at.toInstant().toEpochMilli() <= untilMillis) {
            ticks += HourTick(at.toInstant().toEpochMilli(), at.hour, named = at.hour % 3 == 0)
            at = at.plusHours(1)
        }
        return ticks
    }

    /** The picked passes and events, in time order. A picked planet has its bar instead. */
    fun ticks(timeline: Timeline): List<StripTick> =
        timeline.rows
            .filter { it.pick != null && (it.subject is RowSubject.Pass || it.subject is RowSubject.Event) }
            .sortedBy { it.atMillis }
            .map { StripTick(it.atMillis, shortLabel(it), it.pick!!) }

    /**
     * Whether the forecast puts more cloud than [tolerance] over [atMillis],
     * so something up then is drawn as hidden. The comparison
     * [sh.arek.gloaming.domain.ClearSpell] makes: at the tolerance is clear.
     * No forecast for the moment is not a reason to hide anything.
     */
    fun hidden(cloud: List<CloudSpan>, atMillis: Long, tolerance: Int): Boolean {
        val span = cloud.firstOrNull { atMillis >= it.fromMillis && atMillis < it.untilMillis } ?: return false
        return span.cover > tolerance
    }

    /**
     * The strip in words, for a screen reader: the span of the night, when it
     * is mostly cloudy, who is up and when, and now.
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
        for (cloud in timeline.cloud.sortedBy { it.fromMillis }.filter { it.cover >= CLOUDY_PERCENT }) {
            val last = cloudy.lastOrNull()
            if (last != null && last.last >= cloud.fromMillis) {
                cloudy[cloudy.lastIndex] = last.first..maxOf(last.last, cloud.untilMillis)
            } else {
                cloudy += cloud.fromMillis..cloud.untilMillis
            }
        }
        if (cloudy.isNotEmpty()) {
            sentences += "Mostly cloudy " + cloudy.joinToString(", and ") { span(it.first, it.last) } + "."
        }

        timeline.chart?.let { chart -> UpCharts.summary(chart).takeIf { it.isNotEmpty() }?.let { sentences += it } }
        timeline.nowMillis?.let { sentences += "Now ${clock(it)}." }
        return sentences.joinToString(" ")
    }
}

/**
 * What a row is called where there is room for a word or two: a satellite's
 * name without its catalogue suffix ("ISS" for "ISS (ZARYA)"), anything else
 * its title up to the first comma ("Saturn" for "Saturn, 30 degrees high").
 */
internal fun shortLabel(row: TimelineRow): String = when (val subject = row.subject) {
    is RowSubject.Pass -> subject.pass.name.substringBefore(" (")
    else -> row.title.substringBefore(",")
}
