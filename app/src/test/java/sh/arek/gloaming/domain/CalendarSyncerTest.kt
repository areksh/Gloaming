package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.CalendarSync

/** What actually reaches the calendar, and what is recorded about it afterwards. */
class CalendarSyncerTest {

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

    /** Hands out row ids in order, and records everything it was asked to do. */
    private class FakeWriter(
        private val permitted: Boolean = true,
        private val refuses: Boolean = false,
    ) : CalendarWriter {
        val written = mutableListOf<Triple<SkyEvent, CalendarSync, String?>>()
        val removed = mutableListOf<String>()
        private var next = 1

        override fun write(event: SkyEvent, destination: CalendarSync, rowId: String?): String? {
            written += Triple(event, destination, rowId)
            if (refuses) return null
            return rowId ?: (next++).toString()
        }

        override fun remove(rowId: String) {
            removed += rowId
        }

        override fun isPermitted() = permitted

        override fun destinationName(destination: CalendarSync) = "Test calendar"
    }

    @Test
    fun `writes a newly starred event and records the row it got`() {
        val eclipse = event()
        val writer = FakeWriter()

        val record = CalendarSyncer.sync(
            writer = writer,
            destination = CalendarSync.OwnCalendar,
            starred = setOf(key(eclipse)),
            events = listOf(eclipse),
            synced = emptySet(),
        )

        assertEquals(listOf(eclipse), writer.written.map { it.first })
        assertEquals(setOf("${key(eclipse)}|1"), record)
    }

    @Test
    fun `passes the destination the user chose through to the writer`() {
        val eclipse = event()
        val writer = FakeWriter()

        CalendarSyncer.sync(
            writer, CalendarSync.DeviceCalendar, setOf(key(eclipse)), listOf(eclipse), emptySet(),
        )

        assertEquals(CalendarSync.DeviceCalendar, writer.written.single().second)
    }

    @Test
    fun `removes the row when the star comes off, and forgets it`() {
        val eclipse = event()
        val writer = FakeWriter()

        val record = CalendarSyncer.sync(
            writer = writer,
            destination = CalendarSync.OwnCalendar,
            starred = emptySet(),
            events = listOf(eclipse),
            synced = setOf("${key(eclipse)}|42"),
        )

        assertEquals(listOf("42"), writer.removed)
        assertEquals(emptySet<String>(), record)
    }

    /**
     * Switching the destination off takes back everything the app wrote.
     * Leaving the entries would make "off" mean "stop adding more", which is
     * not what anybody reading that switch would expect.
     */
    @Test
    fun `takes everything back when the destination is switched off`() {
        val eclipse = event()
        val writer = FakeWriter()

        val record = CalendarSyncer.sync(
            writer = writer,
            destination = CalendarSync.Off,
            starred = setOf(key(eclipse)),
            events = listOf(eclipse),
            synced = setOf("${key(eclipse)}|42"),
        )

        assertEquals(listOf("42"), writer.removed)
        assertEquals(emptySet<String>(), record)
    }

    /** Nothing is written twice, however many polls go past. */
    @Test
    fun `writes nothing on a second pass with nothing changed`() {
        val eclipse = event()
        val writer = FakeWriter()

        val once = CalendarSyncer.sync(
            writer, CalendarSync.OwnCalendar, setOf(key(eclipse)), listOf(eclipse), emptySet(),
        )
        writer.written.clear()

        val twice = CalendarSyncer.sync(
            writer, CalendarSync.OwnCalendar, setOf(key(eclipse)), listOf(eclipse), once,
        )

        assertTrue(writer.written.toString(), writer.written.isEmpty())
        assertEquals(once, twice)
    }

    /**
     * A comet's dates move every time the calendar is recomputed, and it is
     * keyed on its name so that is the same entry. The row it already has is
     * handed back to the writer, which updates in place - so a reminder the
     * user set on it survives.
     */
    @Test
    fun `updates a comet's existing row rather than adding a second one`() {
        val comet = event(kind = EventKind.Comet, title = "Comet 12P")
        val moved = event(kind = EventKind.Comet, title = "Comet 12P", from = "2029-01-07T01:00:00Z")
        val writer = FakeWriter()

        val record = CalendarSyncer.sync(
            writer = writer,
            destination = CalendarSync.OwnCalendar,
            starred = setOf(key(comet)),
            events = listOf(moved),
            synced = setOf("${key(comet)}|42"),
        )

        assertEquals(listOf(moved), writer.written.map { it.first })
        assertEquals("42", writer.written.single().third)
        assertEquals(emptyList<String>(), writer.removed)
        assertEquals(setOf("${key(comet)}|42"), record)
    }

    /**
     * A permission revoked from the system settings leaves the record alone.
     *
     * The rows are still in the calendar and the app can no longer reach them,
     * so forgetting the ids would mean granting the permission again wrote a
     * second copy of everything and orphaned the first.
     */
    @Test
    fun `keeps its record untouched when the permission has gone`() {
        val eclipse = event()
        val held = setOf("${key(eclipse)}|42")

        val record = CalendarSyncer.sync(
            writer = FakeWriter(permitted = false),
            destination = CalendarSync.OwnCalendar,
            starred = setOf(key(eclipse)),
            events = listOf(eclipse),
            synced = held,
        )

        assertEquals(held, record)
    }

    @Test
    fun `does nothing at all without a writer`() {
        val eclipse = event()
        val held = setOf("${key(eclipse)}|42")

        assertEquals(
            held,
            CalendarSyncer.sync(null, CalendarSync.OwnCalendar, emptySet(), emptyList(), held),
        )
    }

    /**
     * A write the provider refused records nothing, so the next pass tries
     * again rather than believing an entry exists that does not.
     */
    @Test
    fun `records nothing for a write the calendar refused`() {
        val eclipse = event()
        val writer = FakeWriter(refuses = true)

        val record = CalendarSyncer.sync(
            writer, CalendarSync.OwnCalendar, setOf(key(eclipse)), listOf(eclipse), emptySet(),
        )

        assertEquals(emptySet<String>(), record)
    }
}
