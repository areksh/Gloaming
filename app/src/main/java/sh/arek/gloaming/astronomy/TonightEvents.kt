package sh.arek.gloaming.astronomy

import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.comets.CometsDocument
import sh.arek.gloaming.data.UserSettings

/**
 * The calendar events [sh.arek.gloaming.domain.Tonight] lists, worked out
 * over a short horizon instead of being taken from the year.
 *
 * Tonight keeps only the events whose window overlaps tonight, so it needs
 * nothing from the year beyond what is already open. The year scan costs
 * about ten seconds on a Pixel 8a and Tonight used to wait for it; this takes
 * about two and a half and waits for nothing.
 */
object TonightEvents {
    /**
     * How far ahead to look.
     *
     * Not one day, though tonight is about one, because a window can open long
     * before the moment it is dated: an opposition opens
     * [SkyCalendar.LONGEST_WINDOW_MILLIS] (three weeks) ahead of its date, so
     * on the first night it is worth looking Jupiter is twenty-one days and a
     * few hours from "at its best". The date has to be inside the horizon for
     * the event to be listed at all, and tonight can run to a day ahead of
     * now, so the longest window plus a day is the shortest horizon that never
     * misses one. Shorter fails `TonightEventsTest` on the edge nights the
     * oppositions put there; the shower, elongation and brightest windows
     * are all shorter than the opposition's.
     */
    const val HORIZON_DAYS: Int = 22

    /**
     * What the calendar effect would hand [sh.arek.gloaming.domain.Tonight.of],
     * restricted to [HORIZON_DAYS] ahead of [nowMillis]. Comets are merged in
     * by date as the calendar does; [sh.arek.gloaming.domain.Tonight] drops
     * those entries and lists the comets for tonight itself, so they are here
     * for equivalence rather than because tonight uses them.
     */
    fun of(
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
        comets: CometsDocument?,
        settings: UserSettings,
    ): List<SkyEvent> {
        val sky = SkyCalendar.events(latitude, longitude, nowMillis, HORIZON_DAYS)
        val cometEvents = Comets.calendar(comets, latitude, longitude, settings, nowMillis)
        return (sky + cometEvents).sortedBy { it.at }
    }
}
