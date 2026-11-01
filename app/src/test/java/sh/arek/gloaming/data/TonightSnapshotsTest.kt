package sh.arek.gloaming.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.arek.gloaming.scenarios.Scenarios
import sh.arek.gloaming.ui.NightTimeline

class TonightSnapshotsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun store() = FileTonightSnapshots(temporaryFolder.root)

    private fun file() = File(temporaryFolder.root, "tonight-snapshot.json")

    /** A real night, worked out by the shipped code, with passes, an outlook and a timeline. */
    private fun snapshot(): TonightSnapshot {
        val scenario = Scenarios.byName("a-busy-night-of-passes")
        val list = Scenarios.tonight(scenario)
        val report = Scenarios.outlook(scenario)
        val timeline = NightTimeline.of(list, report, scenario.hourly, scenario.settings, scenario.nowMillis)
        return TonightSnapshot.of(
            cell = GridCell.of(scenario.latitude!!, scenario.longitude!!),
            writtenAt = scenario.nowMillis,
            list = list,
            outlook = report,
            hourly = scenario.hourly,
            timeline = timeline,
        )
    }

    @Test
    fun `a night written reads back equal`() {
        val written = snapshot()
        assertTrue(written.list.passes.isNotEmpty())
        assertNotNull(written.timeline)

        store().write(written)

        assertEquals(written, store().read())
    }

    @Test
    fun `nothing written reads as null`() {
        assertNull(store().read())
    }

    @Test
    fun `a file that will not decode reads as null and is deleted`() {
        file().writeText("{ not json")

        assertNull(store().read())
        assertFalse(file().exists())
    }

    @Test
    fun `another version reads as null and is deleted`() {
        val other = snapshot().copy(version = SNAPSHOT_VERSION + 1)
        store().write(other)

        assertNull(store().read())
        assertFalse(file().exists())
    }

    @Test
    fun `clear removes the file`() {
        store().write(snapshot())

        store().clear()

        assertFalse(file().exists())
    }

    @Test
    fun `a second write replaces the first and leaves no temporary file`() {
        val first = snapshot()
        store().write(first)
        store().write(first.copy(writtenAt = first.writtenAt + 1))

        assertEquals(first.writtenAt + 1, store().read()!!.writtenAt)
        assertEquals(listOf("tonight-snapshot.json"), temporaryFolder.root.list()!!.toList())
    }

    @Test
    fun `the night ends where the timeline ends`() {
        val written = snapshot()
        assertEquals(written.timeline!!.endMillis, written.nightEndMillis)
    }

    @Test
    fun `with no timeline the night ends when the darkness does`() {
        val written = snapshot()
        val bare = TonightSnapshot.of(
            cell = written.cell,
            writtenAt = written.writtenAt,
            list = written.list,
            outlook = null,
            hourly = emptyList(),
            timeline = null,
        )
        assertEquals(written.list.darkUntil, bare.nightEndMillis)
    }

    @Test
    fun `with neither it ends twelve hours after it was written`() {
        val bare = TonightSnapshot.of(
            cell = GridCell(56, 357),
            writtenAt = 1_000L,
            list = snapshot().list.copy(darkUntil = null),
            outlook = null,
            hourly = emptyList(),
            timeline = null,
        )
        assertEquals(1_000L + 12 * hour, bare.nightEndMillis)
    }

    @Test
    fun `is usable in the same cell a minute before the night ends`() {
        val written = snapshot()
        assertTrue(written.usable(written.cell, written.nightEndMillis - minute))
    }

    @Test
    fun `is not usable at or after the end of the night`() {
        val written = snapshot()
        assertFalse(written.usable(written.cell, written.nightEndMillis))
        assertFalse(written.usable(written.cell, written.nightEndMillis + minute))
    }

    @Test
    fun `is not usable in another cell`() {
        val written = snapshot()
        val elsewhere = GridCell(written.cell.latitude + 1, written.cell.longitude)
        assertFalse(written.usable(elsewhere, written.nightEndMillis - minute))
    }

    @Test
    fun `a night written at two in the morning is not usable at three the next afternoon`() {
        val written = snapshot()
        val twoAm = written.nightEndMillis - 6 * hour
        val late = written.copy(writtenAt = twoAm)
        // 15:00 the next day is 13 hours after 02:00 and well past the night's end.
        assertFalse(late.usable(late.cell, twoAm + 37 * hour))
    }
}
