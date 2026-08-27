package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.SkyEvent

/**
 * What has to change in the phone's calendar to match what is starred.
 *
 * Pure arithmetic over three sets, and separate from the code that touches the
 * calendar provider on purpose: the provider part is a handful of statements,
 * and this is where every mistake anybody would actually notice lives - a
 * second copy of the same eclipse, an entry left behind after the star came
 * off, or a delete aimed at a row the app never created.
 *
 * The last of those is why the app keeps its own record of what it wrote
 * rather than searching the calendar for things that look like its own. It
 * deletes by row id, and only ids it put in that record itself, so there is no
 * path by which a heuristic gone wrong reaches somebody's dentist appointment.
 */
object CalendarSyncPlan {

    /**
     * @property write events to insert or update.
     * @property remove calendar row ids to delete.
     * @property keep the record entries that survive unchanged.
     */
    data class Plan(
        val write: List<SkyEvent>,
        val remove: List<String>,
        val keep: Map<String, String>,
    )

    /**
     * @param starred star keys, as [EventPlanner.starKey] makes them.
     * @param events the calendar as it currently stands.
     * @param synced star key to calendar row id, for what the app has written.
     */
    fun of(
        starred: Set<String>,
        events: List<SkyEvent>,
        synced: Map<String, String>,
    ): Plan {
        val wanted = events.associateBy { EventPlanner.starKey(it) }.filterKeys { it in starred }

        // Written when it is new, and written again when it is already there:
        // an update in place rather than a delete and a re-insert, so a
        // reminder the user set on the row survives a comet's dates moving.
        val write = wanted.values.toList()

        // Everything the app wrote whose star has gone, or whose event has
        // dropped out of the year ahead - an eclipse that has happened takes
        // its entry with it. Orphans left in somebody's calendar are exactly
        // what gets calendar permission revoked.
        val orphaned = synced.filterKeys { it !in wanted.keys }

        return Plan(
            write = write.filterNot { EventPlanner.starKey(it) in synced },
            remove = orphaned.values.toList(),
            keep = synced - orphaned.keys,
        ).let { plan ->
            // Events already in the record still need writing when their times
            // may have moved, which is only ever a comet - everything else has
            // a moment fixed by orbital mechanics and is keyed on it.
            val moved = wanted.values.filter {
                EventPlanner.starKey(it) in plan.keep && movesOnRecompute(it)
            }
            plan.copy(write = plan.write + moved)
        }
    }

    /** Everything the app has written, on its way out. */
    fun clearing(synced: Map<String, String>): Plan =
        Plan(write = emptyList(), remove = synced.values.toList(), keep = emptyMap())

    /**
     * Whether this event's own times can change under a stable key.
     *
     * Only a comet. Every other event here is keyed on the instant it happens,
     * so a changed time is a different key and a different entry; a comet is
     * keyed on its name because the calendar invents its moment, and that
     * moment moves every time the calendar is recomputed.
     */
    private fun movesOnRecompute(event: SkyEvent): Boolean =
        event.kind == sh.arek.gloaming.astronomy.EventKind.Comet

    // --- the flat form the preference store keeps ---

    /** Star key and row id joined, matching how [EventPlanner.key] joins its parts. */
    fun flatten(synced: Map<String, String>): Set<String> =
        synced.map { "${it.key}$SEPARATOR${it.value}" }.toSet()

    /**
     * Split at the **last** separator, not the first.
     *
     * A comet's key carries its name, and a name can contain anything - a
     * designation like "C/2028 A1 (Some | Name)" included. Splitting at the
     * first bar would turn the row id into part of the name, and the app would
     * then try to delete a row that does not exist while orphaning the one that
     * does.
     */
    fun read(flat: Set<String>): Map<String, String> = flat.mapNotNull { entry ->
        val cut = entry.lastIndexOf(SEPARATOR)
        if (cut <= 0 || cut == entry.length - 1) null
        else entry.substring(0, cut) to entry.substring(cut + 1)
    }.toMap()

    private const val SEPARATOR = '|'
}
