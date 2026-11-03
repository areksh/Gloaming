package sh.arek.gloaming.ui

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** The one horizontal strip that draws the whole night. */
class NightStripTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val hour = 3_600_000L
    private val min = 60_000L
    private val sunset = at("2026-12-14T15:39:00Z")
    private val sunrise = at("2026-12-15T08:37:00Z")

    private fun timeline(
        start: Long = sunset,
        end: Long = sunrise,
        now: Long? = null,
        rows: List<TimelineRow> = emptyList(),
        cloud: List<CloudSpan> = emptyList(),
        chart: UpChart? = null,
    ) = Timeline(
        startMillis = start,
        endMillis = end,
        rows = rows,
        cloud = cloud,
        lanes = emptyList(),
        nowMillis = now,
        chart = chart,
    )

    private fun pass(name: String, peak: Long) = SatellitePass(
        25544, name, PassChannel.Iss, peak - 240_000, 315.0, peak, 70.0, 180.0,
        peak + 240_000, 135.0, 450.0, true, null, null,
    )

    private fun passRow(name: String, peak: Long, pick: PickTag?) = TimelineRow(
        peak, clock(peak), passTitle(pass(name, peak)), passEnds(pass(name, peak)), RowTone.Normal,
        RowSubject.Pass(pass(name, peak)), pick,
    )

    @Test
    fun `whole night when not zoomed or not under way`() {
        val t = timeline(now = null)
        assertEquals(StripWindow(sunset, sunrise, zoomed = false), NightStrips.window(t, zoom = true))
        assertEquals(
            StripWindow(sunset, sunrise, zoomed = false),
            NightStrips.window(t.copy(nowMillis = sunset + 3 * hour), zoom = false),
        )
    }

    @Test
    fun `zooms to half an hour behind and two and a half ahead`() {
        val now = sunset + 3 * hour
        assertEquals(
            StripWindow(now - 30 * min, now + 150 * min, zoomed = true),
            NightStrips.window(timeline(now = now), zoom = true),
        )
    }

    @Test
    fun `zoom slides rather than running past either end`() {
        assertEquals(
            StripWindow(sunset, sunset + 3 * hour, zoomed = true),
            NightStrips.window(timeline(now = sunset + 10 * min), zoom = true),
        )
        assertEquals(
            StripWindow(sunrise - 3 * hour, sunrise, zoomed = true),
            NightStrips.window(timeline(now = sunrise - 20 * min), zoom = true),
        )
    }

    @Test
    fun `a night shorter than three hours is drawn whole`() {
        val t = timeline(start = sunset, end = sunset + 2 * hour, now = sunset + hour)
        assertEquals(StripWindow(sunset, sunset + 2 * hour, zoomed = true), NightStrips.window(t, zoom = true))
    }

    @Test
    fun `hour ticks every hour, every third named`() {
        val ticks = NightStrips.hourTicks(at("2026-12-14T15:39:00Z"), at("2026-12-14T21:10:00Z"), ZoneId.of("Europe/London"))
        assertEquals(listOf(16, 17, 18, 19, 20, 21), ticks.map { it.hour })
        assertEquals(listOf(18, 21), ticks.filter { it.named }.map { it.hour })
    }

    @Test
    fun `hour ticks on the night the clocks go back`() {
        val ticks = NightStrips.hourTicks(at("2026-10-24T22:30:00Z"), at("2026-10-25T03:30:00Z"), ZoneId.of("Europe/London"))
        assertEquals(listOf(0, 1, 1, 2, 3), ticks.map { it.hour })
        assertEquals(listOf(hour, hour, hour, hour), ticks.zipWithNext { a, b -> b.atMillis - a.atMillis })
    }

    @Test
    fun `ticks are the picked passes and events, with short labels`() {
        val issAt = sunset + 3 * hour
        val showerAt = sunset + 6 * hour
        val shower = SkyEvent(
            EventKind.MeteorShower, showerAt, showerAt - hour, showerAt + hour,
            "Geminids at their best", "Up to 120 an hour", Visibility.Good,
        )
        val rows = listOf(
            passRow("ISS (ZARYA)", issAt, PickTag.Go),
            passRow("CSS (TIANHE)", issAt + hour, null),
            TimelineRow(showerAt, clock(showerAt), shower.title, null, RowTone.Strong, RowSubject.Event(shower), PickTag.Also),
            TimelineRow(
                issAt + 2 * hour, clock(issAt + 2 * hour), "Saturn, 30 degrees high", "In the south",
                RowTone.Strong, RowSubject.Moon, PickTag.Go,
            ),
        )
        assertEquals(
            listOf(StripTick(issAt, "ISS", PickTag.Go), StripTick(showerAt, "Geminids at their best", PickTag.Also)),
            NightStrips.ticks(timeline(rows = rows)),
        )
    }

    @Test
    fun `short labels`() {
        assertEquals("ISS", shortLabel(passRow("ISS (ZARYA)", sunset, PickTag.Go)))
        assertEquals("Starlink train G6-77", shortLabel(passRow("Starlink train G6-77", sunset, PickTag.Go)))
        val planet = TimelineRow(sunset, clock(sunset), "Saturn, 30 degrees high", null, RowTone.Strong, RowSubject.Moon, PickTag.Go)
        assertEquals("Saturn", shortLabel(planet))
    }

    @Test
    fun `hidden where the cover is over the tolerance`() {
        val cloud = listOf(CloudSpan(sunset, sunset + hour, 30), CloudSpan(sunset + hour, sunset + 2 * hour, 80))
        assertFalse(NightStrips.hidden(cloud, sunset + 10 * min, tolerance = 50))
        assertTrue(NightStrips.hidden(cloud, sunset + hour + 10 * min, tolerance = 50))
        assertFalse(NightStrips.hidden(cloud, sunset + hour + 10 * min, tolerance = 80))
        assertFalse(NightStrips.hidden(cloud, sunset + 5 * hour, tolerance = 50))
    }

    @Test
    fun `summary says the night, the cloud and who is up, with no chart too`() {
        val words = NightStrips.summary(timeline(cloud = listOf(CloudSpan(sunset + 2 * hour, sunrise, 90))))
        assertEquals("Tonight from sunset at 15:39 to sunrise at 08:37. Mostly cloudy from 17:39.", words)
    }

    @Test
    fun `summary says now`() {
        val words = NightStrips.summary(timeline(now = sunset + 3 * hour))
        assertEquals("Tonight from sunset at 15:39 to sunrise at 08:37. Now 18:39.", words)
    }
}
