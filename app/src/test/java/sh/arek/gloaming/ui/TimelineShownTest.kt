package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** What the Busy setting shows, and the line counting what it hides. */
class TimelineShownTest {

    private val hour = 3_600_000L
    private val start = 1_800_000_000_000L

    private fun pass(n: Int) = SatellitePass(
        20000 + n, "R/B $n", PassChannel.Bright, start + n * hour, 200.0, start + n * hour + 1, 40.0, 180.0,
        start + n * hour + 2, 100.0, 500.0, true, null, null,
    )

    private val orion = ConstellationTonight(
        Constellations.WATCHED.first { it.name == "Orion" }, 38.0, start + 5 * hour, 180.0,
        isCircumpolar = false, isAtItsBest = false,
    )

    private val quarter = SkyEvent(
        EventKind.MoonQuarter, start + 6 * hour, start + 6 * hour, start + 6 * hour, "First quarter", "", Visibility.Good,
    )

    private fun row(at: Long, title: String, subject: RowSubject, busy: Boolean) =
        TimelineRow(at, clock(at), title, null, RowTone.Normal, subject, busy = busy)

    private val sunset = row(start, "Sunset", RowSubject.Twilight, busy = false)
    private val busyPasses = (1..3).map { row(start + it * hour, "R/B $it", RowSubject.Pass(pass(it)), busy = true) }
    private val pattern = row(start + 5 * hour, "Orion highest", RowSubject.Constellation(orion), busy = true)
    private val date = row(start + 6 * hour, "First quarter", RowSubject.Event(quarter), busy = true)

    private fun timeline(vararg rows: TimelineRow) =
        Timeline(start, start + 12 * hour, rows.toList(), emptyList(), emptyList(), null)

    @Test
    fun `busy off keeps everything not busy`() {
        val night = timeline(sunset, *busyPasses.toTypedArray(), pattern, date)

        assertEquals(listOf(sunset), TimelineShown.of(night, busy = false).rows)
    }

    @Test
    fun `busy on keeps everything`() {
        val night = timeline(sunset, *busyPasses.toTypedArray(), pattern, date)

        assertEquals(night, TimelineShown.of(night, busy = true))
    }

    @Test
    fun `counts the hidden by kind with plurals`() {
        val night = timeline(sunset, *busyPasses.toTypedArray(), pattern, date)

        val hidden = TimelineShown.hidden(night)

        assertEquals(Hidden(passes = 3, constellations = 1, dates = 1), hidden)
        assertEquals("Busy adds 3 passes, 1 constellation and 1 date to the timeline.", hidden.sentence())
        assertEquals("Busy adds 1 pass to the timeline.", Hidden(1, 0, 0).sentence())
        assertEquals("Busy adds 2 constellations and 4 dates to the timeline.", Hidden(0, 2, 4).sentence())
    }

    @Test
    fun `nothing hidden says nothing`() {
        assertNull(TimelineShown.hidden(timeline(sunset)).sentence())
    }
}
