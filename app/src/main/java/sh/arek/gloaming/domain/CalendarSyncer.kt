package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.CalendarSync

/**
 * Brings the phone's calendar into line with what is starred.
 *
 * The join between [CalendarSyncPlan], which decides, and [CalendarWriter],
 * which acts. Small enough to read in one go on purpose - it is the only place
 * a delete is issued, and the only ids it can issue one for are ids it was
 * handed by its own record.
 */
object CalendarSyncer {

    /**
     * @param synced the record as stored: "star key|row id" per entry.
     * @return the record as it now stands, to be written back.
     */
    fun sync(
        writer: CalendarWriter?,
        destination: CalendarSync,
        starred: Set<String>,
        events: List<SkyEvent>,
        synced: Set<String>,
    ): Set<String> {
        val held = CalendarSyncPlan.read(synced)

        // Nothing to do and nothing to undo. Notably this is also the answer
        // when the permission has been revoked from the system settings: the
        // rows stay where they are, because the app cannot reach them, and the
        // record stays with them so that granting it again finds them rather
        // than writing a second copy of everything.
        if (writer == null || !writer.isPermitted()) return synced

        // Switching the destination off takes back everything the app put
        // there. Leaving the entries would make "off" mean "stop adding more",
        // which is not what anybody reading that switch would expect.
        val plan = if (destination == CalendarSync.Off) {
            CalendarSyncPlan.clearing(held)
        } else {
            CalendarSyncPlan.of(starred, events, held)
        }

        plan.remove.forEach { writer.remove(it) }

        val written = plan.write.mapNotNull { event ->
            val key = EventPlanner.starKey(event)
            writer.write(event, destination, plan.keep[key])?.let { key to it }
        }

        return CalendarSyncPlan.flatten(plan.keep + written)
    }
}
