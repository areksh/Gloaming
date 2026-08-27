package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.CalendarSync

/**
 * What has to change in the phone's calendar to match what is starred.
 *
 * Pure arithmetic over three sets, deliberately: the part that touches the
 * calendar provider is a handful of statements, and the part that decides what
 * to touch is where every mistake anyone would actually care about lives -
 * writing a second copy of an eclipse, leaving an entry behind after the star
 * came off, or deleting a row the app did not create.
 */
class CalendarSyncPlanTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        kind: EventKind = EventKind.LunarEclipse,
        title: String = "Total lunar eclipse",
        from: String = "2028-12-31T01:00:00Z",
    ) = SkyEvent(
        kind = kind,
        at = at(from),
        from = at(from),
        until = at(from) + 3_600_000L,
        title = title,
        detail = "Totality from 02:11 to 03:24.",
        visibility = Visibility.Good,
    )

    private fun key(event: SkyEvent) = EventPlanner.starKey(event)

    @Test
    fun `writes a starred event that is not in the calendar yet`() {
        val eclipse = event()

        val plan = CalendarSyncPlan.of(
            starred = setOf(key(eclipse)),
            events = listOf(eclipse),
            synced = emptyMap(),
        )

        assertEquals(listOf(eclipse), plan.write)
        assertEquals(emptyList<String>(), plan.remove)
    }

    /** Already there and unchanged: nothing to do, and no second copy. */
    @Test
    fun `leaves an event that is already in the calendar alone`() {
        val eclipse = event()

        val plan = CalendarSyncPlan.of(
            starred = setOf(key(eclipse)),
            events = listOf(eclipse),
            synced = mapOf(key(eclipse) to "42"),
        )

        assertEquals(emptyList<SkyEvent>(), plan.write)
        assertEquals(emptyList<String>(), plan.remove)
        assertEquals(mapOf(key(eclipse) to "42"), plan.keep)
    }

    @Test
    fun `removes the entry when the star comes off`() {
        val eclipse = event()

        val plan = CalendarSyncPlan.of(
            starred = emptySet(),
            events = listOf(eclipse),
            synced = mapOf(key(eclipse) to "42"),
        )

        assertEquals(listOf("42"), plan.remove)
        assertEquals(emptyList<SkyEvent>(), plan.write)
        assertEquals(emptyMap<String, String>(), plan.keep)
    }

    /**
     * A star on something no longer in the year ahead - an eclipse that has
     * happened - takes its calendar entry with it. The entry is Gloaming's, and
     * leaving orphans behind in somebody's calendar is exactly the behaviour
     * that gets calendar permission revoked.
     */
    @Test
    fun `removes the entry for an event that has dropped out of the calendar`() {
        val gone = event()

        val plan = CalendarSyncPlan.of(
            starred = setOf(key(gone)),
            events = emptyList(),
            synced = mapOf(key(gone) to "42"),
        )

        assertEquals(listOf("42"), plan.remove)
    }

    /**
     * A comet is keyed on its name because the calendar invents its moment and
     * that moment moves on every recompute. So the same comet a week later is
     * the same entry, moved - not a second entry.
     */
    @Test
    fun `moves a comet's entry rather than adding a second one`() {
        val comet = event(kind = EventKind.Comet, title = "Comet 12P")
        val later = event(kind = EventKind.Comet, title = "Comet 12P", from = "2029-01-07T01:00:00Z")

        val plan = CalendarSyncPlan.of(
            starred = setOf(key(comet)),
            events = listOf(later),
            synced = mapOf(key(comet) to "42"),
        )

        assertEquals(listOf(later), plan.write)
        assertEquals(emptyList<String>(), plan.remove)
        // The row is kept: it is updated in place rather than deleted and
        // recreated, so a reminder the user set on it survives.
        assertEquals(mapOf(key(comet) to "42"), plan.keep)
    }

    @Test
    fun `writes nothing at all when nothing is starred`() {
        val plan = CalendarSyncPlan.of(emptySet(), listOf(event()), emptyMap())

        assertTrue(plan.write.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    /**
     * Turning the feature off takes back everything the app put there. It is
     * the only honest reading of the switch: leaving entries behind would make
     * "off" mean "stop adding more", which is not what anybody would expect.
     */
    @Test
    fun `takes everything back when the destination is off`() {
        val eclipse = event()

        val plan = CalendarSyncPlan.clearing(mapOf(key(eclipse) to "42", "other" to "7"))

        assertEquals(setOf("42", "7"), plan.remove.toSet())
        assertTrue(plan.write.isEmpty())
        assertTrue(plan.keep.isEmpty())
    }

    // --- how the map survives a trip through a string set ---

    @Test
    fun `round-trips through the flat form the store keeps`() {
        val map = mapOf("LunarEclipse|1861855200000" to "42", "Comet|Comet 12P" to "7")

        assertEquals(map, CalendarSyncPlan.read(CalendarSyncPlan.flatten(map)))
    }

    /**
     * A comet's key holds its name, which can contain anything - including the
     * separator. Splitting at the first bar would make "Comet|12P|A" into a row
     * id of "12P|A", and then the app would try to delete a row that does not
     * exist while orphaning the one that does.
     */
    @Test
    fun `splits a key that contains the separator at the right place`() {
        val map = mapOf("Comet|C/2028 A1 (Some | Name)" to "42")

        assertEquals(map, CalendarSyncPlan.read(CalendarSyncPlan.flatten(map)))
    }

    @Test
    fun `ignores a malformed entry rather than failing`() {
        assertEquals(emptyMap<String, String>(), CalendarSyncPlan.read(setOf("no-separator")))
    }
}
