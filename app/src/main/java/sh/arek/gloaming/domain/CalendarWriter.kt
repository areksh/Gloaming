package sh.arek.gloaming.domain

import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.CalendarSync

/**
 * Where a starred event goes when the user has asked for it in their calendar.
 *
 * An interface for the same reason [AuroraNotifier] is one: everything that
 * decides *what* to write is ordinary arithmetic that should be testable
 * without a device, and the calendar provider is a boundary rather than a
 * detail of the decision.
 *
 * Every method returns rather than throws. This runs on a background wake with
 * nobody watching, and a revoked permission or a calendar the user deleted is
 * an ordinary Tuesday - not a reason to take down a poll that also has an
 * eclipse to announce.
 */
interface CalendarWriter {

    /**
     * Inserts [event], or updates the row already holding it.
     *
     * @param rowId the row this event was written to last time, if there is
     *   one. Passing it updates in place, which is what stops a comet's dates
     *   moving from producing a second entry - and what preserves a reminder
     *   the user set on the row.
     * @return the row id written, or null if nothing could be written.
     */
    fun write(event: SkyEvent, destination: CalendarSync, rowId: String? = null): String?

    /**
     * Deletes a row this app wrote.
     *
     * Only ever called with an id the app recorded itself. There is deliberately
     * no "find Gloaming's entries" here: a heuristic that goes wrong deletes
     * somebody's dentist appointment.
     */
    fun remove(rowId: String)

    /** Whether the app currently holds what it needs to do any of this. */
    fun isPermitted(): Boolean

    /**
     * The name of the calendar entries are going into, for the settings screen.
     *
     * Null when there is none to name, which is an answer worth showing: a
     * phone with no writable calendar at all is a real configuration, and
     * silently writing nowhere would be indistinguishable from the feature
     * being broken.
     */
    fun destinationName(destination: CalendarSync): String?
}
