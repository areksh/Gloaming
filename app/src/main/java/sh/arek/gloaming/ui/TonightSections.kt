package sh.arek.gloaming.ui

import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList

/**
 * The rows the outlook picked, split at now: those still worth going out for,
 * and those already over, which the screen folds into one line.
 */
data class Worth(val upcoming: List<TimelineRow>, val earlier: List<TimelineRow>)

/**
 * What Tonight shows under the strip, worked out from the night it draws:
 * one short list of what the outlook picked, and two folded lists - everything
 * else up, and what was set aside. Pure and clock-free.
 *
 * The short list is capped by the outlook itself, which picks at most
 * [sh.arek.gloaming.domain.Outlooks.MAX_PICKS] for the trip and
 * [sh.arek.gloaming.domain.Outlooks.MAX_ALSO] beside it. A second cap here
 * would only hide picks.
 */
object TonightSections {

    /**
     * How long after its moment a picked row with no end of its own - a
     * planet at the moment it was picked for - still counts as to come.
     */
    const val EARLIER_GRACE_MILLIS: Long = 10 * 60 * 1000L

    /** Beyond this, what is next is given a time rather than a count of minutes. */
    const val NEXT_UP_AT_MILLIS: Long = 90 * 60 * 1000L

    private const val MINUTE_MILLIS: Long = 60 * 1000L

    /** How many names a folded list's preview gives before counting the rest. */
    private const val PREVIEW_NAMES = 3

    /**
     * The picked rows in time order, split at now; all to come before the
     * night. A row is over once what it is about is: a pass once it has set,
     * an event once its window has closed - an eclipse is still to come for as
     * long as it is going on - and anything else a little after its moment.
     */
    fun worth(timeline: Timeline): Worth {
        val picked = timeline.rows.filter { it.pick != null }.sortedBy { it.atMillis }
        val now = timeline.nowMillis ?: return Worth(picked, emptyList())
        val (earlier, upcoming) = picked.partition { endOf(it) < now }
        return Worth(upcoming, earlier)
    }

    /** When what [row] is about is over. */
    private fun endOf(row: TimelineRow): Long = when (val subject = row.subject) {
        is RowSubject.Pass -> subject.pass.setsAt
        is RowSubject.Event -> maxOf(subject.event.until, row.atMillis)
        else -> row.atMillis + EARLIER_GRACE_MILLIS
    }

    /**
     * Everything up tonight but the picked rows: what the short list does not
     * already say. With only the picked rows counted as drawn, [TonightLists.alsoUp]
     * lists all the rest; with no timeline, all of it.
     */
    fun everything(
        list: TonightList,
        timeline: Timeline?,
        report: OutlookReport?,
        nowMillis: Long,
        tolerance: Int,
    ): List<ListEntry> {
        val shown = timeline?.let { t -> worth(t).let { t.copy(rows = it.upcoming + it.earlier) } }
        return listOfNotNull(timeline?.let { theNight(list, it, tolerance) }) +
            TonightLists.alsoUp(list, shown, report, nowMillis)
    }

    /**
     * The night's own moments in a line - sunset, darkness, the clear spells,
     * the moonless stretch, sunrise - which the strip draws and nothing else
     * puts in words, then how dark it gets and what the cloud is doing, which
     * the twilight and clear rows' sheets used to say. Picked rows are the
     * short list's; the moon's rising and setting are said by "The moon" entry.
     */
    private fun theNight(list: TonightList, timeline: Timeline, tolerance: Int): ListEntry {
        val moments = timeline.rows.filter { row ->
            row.pick == null && when (row.subject) {
                RowSubject.Twilight, RowSubject.Clear -> true
                RowSubject.Moon -> row.title.startsWith(MOONLESS)
                else -> false
            }
        }
        val said = (describeDarkness(list) + describeCloudTonight(list, tolerance)).joinToString(" ")
        if (moments.isEmpty()) return ListEntry(NIGHT_TITLE, said)
        return ListEntry(NIGHT_TITLE, moments.sortedBy { it.atMillis }.joinToString(" · ") { "${it.time} ${it.title}" }, note = said)
    }

    /**
     * A folded list's heading: "Everything up tonight · 6", or the title alone
     * where all it holds is the night itself, rather than "· 0" over it.
     */
    fun heading(title: String, entries: List<ListEntry>): String {
        val n = count(entries)
        return if (n == 0) title else "$title · $n"
    }

    /** The heading of the night's own moments, first under "Everything up tonight". */
    const val NIGHT_TITLE = "The night"

    private const val MOONLESS = "Moonless"

    /**
     * A folded list in a line: "Jupiter, Pleiades, Orion and 3 more". Only
     * titled entries are named, and never the night's own moments; null when
     * none is.
     */
    fun preview(entries: List<ListEntry>): String? {
        val names = entries.mapNotNull { it.title }.filter { it != NIGHT_TITLE }
        if (names.isEmpty()) return null
        if (names.size == 1) return names.single()
        if (names.size <= PREVIEW_NAMES) return names.dropLast(1).joinToString(", ") + " and " + names.last()
        return names.take(PREVIEW_NAMES).joinToString(", ") + " and ${names.size - PREVIEW_NAMES} more"
    }

    /** How many things a folded list holds, for "Everything up tonight · 6": the night itself is not one. */
    fun count(entries: List<ListEntry>): Int = entries.count { it.title != NIGHT_TITLE }

    /** "Earlier: Starlink train G6-77 18:52, Mercury 16:50", most recent first; null with none. */
    fun earlierLine(earlier: List<TimelineRow>): String? {
        if (earlier.isEmpty()) return null
        return "Earlier: " + earlier.sortedByDescending { it.atMillis }.joinToString(", ") { "${shortLabel(it)} ${it.time}" }
    }

    /** The first picked row still to come, once the night is under way. */
    fun nextUp(worth: Worth, nowMillis: Long?): TimelineRow? {
        nowMillis ?: return null
        return worth.upcoming.firstOrNull()
    }

    /** "ISS in 6 min", "ISS now" once it has begun, or "ISS at 21:14" when it is more than 90 minutes off. */
    fun nextUpWords(row: TimelineRow, nowMillis: Long): String {
        val name = shortLabel(row)
        val ahead = row.atMillis - nowMillis
        return when {
            ahead <= 0 -> "$name now"
            ahead <= NEXT_UP_AT_MILLIS -> "$name in ${minutesUp(ahead)} min"
            else -> "$name at ${row.time}"
        }
    }

    /** "Starts in 3 h 44 min" until the trip's first moment; null with no trip or once it has come. */
    fun countdown(timeline: Timeline, nowMillis: Long): String? {
        val first = timeline.trip.minOrNull() ?: return null
        if (first <= nowMillis) return null
        val minutes = minutesUp(first - nowMillis)
        val hours = minutes / 60
        val rest = minutes % 60
        val parts = listOfNotNull(
            "$hours h".takeIf { hours > 0 },
            "$rest min".takeIf { rest > 0 },
        )
        return "Starts in " + parts.joinToString(" ")
    }

    /** Whole minutes, rounded up: "in 1 min" for anything under a minute rather than "in 0". */
    private fun minutesUp(millis: Long): Long = (millis + MINUTE_MILLIS - 1) / MINUTE_MILLIS
}
