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

    /** How long after its moment a picked row still counts as to come: a pass lasts minutes. */
    const val EARLIER_GRACE_MILLIS: Long = 10 * 60 * 1000L

    /** Beyond this, what is next is given a time rather than a count of minutes. */
    const val NEXT_UP_AT_MILLIS: Long = 90 * 60 * 1000L

    private const val MINUTE_MILLIS: Long = 60 * 1000L

    /** How many names a folded list's preview gives before counting the rest. */
    private const val PREVIEW_NAMES = 3

    /** The picked rows in time order, split at now; all to come before the night. */
    fun worth(timeline: Timeline): Worth {
        val picked = timeline.rows.filter { it.pick != null }.sortedBy { it.atMillis }
        val now = timeline.nowMillis ?: return Worth(picked, emptyList())
        val (earlier, upcoming) = picked.partition { it.atMillis < now - EARLIER_GRACE_MILLIS }
        return Worth(upcoming, earlier)
    }

    /**
     * Everything up tonight but the picked rows: what the short list does not
     * already say. With only the picked rows counted as drawn, [TonightLists.alsoUp]
     * lists all the rest; with no timeline, all of it.
     */
    fun everything(list: TonightList, timeline: Timeline?, report: OutlookReport?, nowMillis: Long): List<ListEntry> {
        val shown = timeline?.let { t -> worth(t).let { t.copy(rows = it.upcoming + it.earlier) } }
        return TonightLists.alsoUp(list, shown, report, nowMillis)
    }

    /**
     * A folded list in a line: "Jupiter, Pleiades, Orion and 3 more". Only
     * titled entries are named; null when none is.
     */
    fun preview(entries: List<ListEntry>): String? {
        val names = entries.mapNotNull { it.title }
        if (names.isEmpty()) return null
        if (names.size == 1) return names.single()
        if (names.size <= PREVIEW_NAMES) return names.dropLast(1).joinToString(", ") + " and " + names.last()
        return names.take(PREVIEW_NAMES).joinToString(", ") + " and ${names.size - PREVIEW_NAMES} more"
    }

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

    /** "ISS in 6 min", "ISS now", or "ISS at 21:14" when it is more than 90 minutes off. */
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
