package sh.arek.gloaming.ui

/** How many busy rows a quiet timeline leaves off, by kind. */
data class Hidden(val passes: Int, val constellations: Int, val dates: Int) {

    /**
     * "Busy adds 3 passes, 5 constellations and 1 date to the timeline.", or
     * null when it would add nothing. The line under a quiet timeline, so
     * what it leaves off is never left off silently. Not "hidden": each is
     * still listed under "Also up tonight", only not given a row.
     */
    fun sentence(): String? {
        val parts = listOfNotNull(
            count(passes, "pass", "passes"),
            count(constellations, "constellation", "constellations"),
            count(dates, "date", "dates"),
        )
        if (parts.isEmpty()) return null
        val joined = if (parts.size == 1) parts.single() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Busy adds $joined to the timeline."
    }

    private fun count(n: Int, one: String, many: String): String? = when (n) {
        0 -> null
        1 -> "1 $one"
        else -> "$n $many"
    }
}

/**
 * The Busy setting, as a filter on a night already worked out: switching it
 * never works the night out again.
 */
object TimelineShown {

    /** [timeline] with its busy rows left out, unless [busy]. */
    fun of(timeline: Timeline, busy: Boolean): Timeline =
        if (busy) timeline else timeline.copy(rows = timeline.rows.filterNot { it.busy })

    /** What [of] leaves out with Busy off. */
    fun hidden(timeline: Timeline): Hidden {
        val busy = timeline.rows.filter { it.busy }
        return Hidden(
            passes = busy.count { it.subject is RowSubject.Pass },
            constellations = busy.count { it.subject is RowSubject.Constellation },
            dates = busy.count { it.subject is RowSubject.Event },
        )
    }
}
