package sh.arek.gloaming.ui

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** What Tonight's short list and its two folded lists hold, and what they say. */
class TonightSectionsTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val min = 60_000L
    private val sunset = at("2026-11-03T16:35:00Z")
    private val sunrise = at("2026-11-04T07:20:00Z")

    private fun pass(name: String, peak: Long) = SatellitePass(
        25544, name, PassChannel.Iss, peak - 240_000, 270.0, peak, 70.0, 180.0,
        peak + 240_000, 90.0, 450.0, true, null, null,
    )

    private fun passRow(name: String, time: String, pick: PickTag?): TimelineRow {
        val t = at(time)
        val p = pass(name, t)
        return TimelineRow(t, clock(t), passTitle(p), passEnds(p), RowTone.Normal, RowSubject.Pass(p), pick)
    }

    private fun planetRow(label: String, time: String, pick: PickTag?): TimelineRow {
        val t = at(time)
        return TimelineRow(t, clock(t), "$label, 28 degrees high", "In the south", RowTone.Strong, RowSubject.Moon, pick)
    }

    private fun timeline(rows: List<TimelineRow>, now: Long? = null, trip: List<Long> = emptyList()) = Timeline(
        startMillis = sunset,
        endMillis = sunrise,
        rows = rows,
        cloud = emptyList(),
        nowMillis = now,
        trip = trip,
    )

    private val starlink = passRow("Starlink train G6-77", "2026-11-03T18:52:00Z", PickTag.Also)
    private val iss = passRow("ISS (ZARYA)", "2026-11-03T19:24:00Z", PickTag.Go)
    private val saturn = planetRow("Saturn", "2026-11-03T19:30:00Z", PickTag.Go)
    private val sunsetRow = TimelineRow(sunset, clock(sunset), "Sunset", null, RowTone.Normal, RowSubject.Twilight)
    private val css = passRow("CSS (TIANHE)", "2026-11-03T20:00:00Z", null)

    private val night = listOf(sunsetRow, starlink, iss, saturn, css)

    @Test
    fun `worth is the picked rows in time order, the long past ones apart`() {
        val w = TonightSections.worth(timeline(night.shuffled(), now = at("2026-11-03T19:20:00Z")))
        assertEquals(listOf("19:24", "19:30"), w.upcoming.map { it.time })
        assertEquals(listOf("18:52"), w.earlier.map { it.time })
    }

    @Test
    fun `before the night every picked row is upcoming`() {
        val w = TonightSections.worth(timeline(night, now = null))
        assertEquals(listOf("18:52", "19:24", "19:30"), w.upcoming.map { it.time })
        assertEquals(emptyList<TimelineRow>(), w.earlier)
    }

    @Test
    fun `a pass is to come until it sets, and over once it has`() {
        // The fixture's passes set four minutes after their peak.
        val during = TonightSections.worth(timeline(night, now = iss.atMillis + 3 * min))
        assertEquals(listOf("19:24", "19:30"), during.upcoming.map { it.time })
        val after = TonightSections.worth(timeline(night, now = iss.atMillis + 5 * min))
        assertEquals(listOf("19:30"), after.upcoming.map { it.time })
        assertEquals(listOf("18:52", "19:24"), after.earlier.map { it.time })
    }

    private val eclipse = SkyEvent(
        EventKind.LunarEclipse, at("2026-11-03T20:00:00Z"), at("2026-11-03T19:30:00Z"), at("2026-11-03T21:30:00Z"),
        "Total lunar eclipse", "The moon in the earth's shadow", Visibility.Good,
    )
    private val eclipseRow = TimelineRow(
        eclipse.at, clock(eclipse.at), eclipse.title, null, RowTone.Strong, RowSubject.Event(eclipse), PickTag.Go,
    )

    @Test
    fun `an event is to come until it is over, not ten minutes after its moment`() {
        val now = eclipse.at + 30 * min
        val w = TonightSections.worth(timeline(listOf(eclipseRow), now = now))
        assertEquals(listOf(eclipseRow), w.upcoming)
        assertEquals("Total lunar eclipse now", TonightSections.nextUpWords(eclipseRow, now))
        val over = TonightSections.worth(timeline(listOf(eclipseRow), now = eclipse.until + min))
        assertEquals(listOf(eclipseRow), over.earlier)
    }

    @Test
    fun `an aurora-only outlook leaves the list empty`() {
        val w = TonightSections.worth(timeline(listOf(sunsetRow, css)))
        assertEquals(Worth(emptyList(), emptyList()), w)
    }

    private val jupiter = PlanetTonight(SkyBody.Jupiter, 45.0, at("2026-11-04T04:10:00Z"), 180.0, -2.5, null, null)

    private fun list() = TonightList(
        darkFrom = at("2026-11-03T18:35:00Z"), darkUntil = at("2026-11-04T05:25:00Z"),
        passes = listOf(iss, css).map { (it.subject as RowSubject.Pass).pass },
        bestPasses = listOf(iss, css).map { (it.subject as RowSubject.Pass).pass },
        events = emptyList(), comets = emptyList(), noctilucent = null, moon = null, planets = listOf(jupiter),
    )

    private val report = OutlookReport(Outlook.NoLocation, emptyList(), null, 50, Compass.North)

    @Test
    fun `everything holds every row the list leaves out`() {
        val titles = TonightSections.everything(list(), timeline(night), report, sunset, tolerance = 50).map { it.title }
        assertTrue("ISS (ZARYA)" !in titles)
        assertTrue("CSS (TIANHE)" in titles)
        assertTrue("Jupiter" in titles)
    }

    @Test
    fun `everything opens with the night's own moments, which the strip draws but no list names`() {
        val clear = TimelineRow(at("2026-11-03T18:30:00Z"), "18:30", "Clear until 23:00", null, RowTone.Strong, RowSubject.Clear)
        val fully = TimelineRow(at("2026-11-03T18:35:00Z"), "18:35", "Fully dark", null, RowTone.Normal, RowSubject.Twilight)
        val moonless = TimelineRow(at("2026-11-03T18:36:00Z"), "18:36", "Moonless dark until 21:50", "Best for faint things", RowTone.Normal, RowSubject.Moon)
        val moonrise = TimelineRow(at("2026-11-03T21:50:00Z"), "21:50", "Moonrise", "Waning gibbous", RowTone.Normal, RowSubject.Moon)
        val first = TonightSections.everything(
            list(), timeline(listOf(sunsetRow, clear, fully, moonless, iss, moonrise)), report, sunset, tolerance = 50,
        ).first()
        assertEquals("The night", first.title)
        assertEquals("16:35 Sunset · 18:30 Clear until 23:00 · 18:35 Fully dark · 18:36 Moonless dark until 21:50", first.detail)
    }

    @Test
    fun `the night says how dark it gets and what the cloud is doing`() {
        val first = TonightSections.everything(list(), timeline(listOf(sunsetRow)), report, sunset, tolerance = 50).first()
        assertEquals(TonightSections.NIGHT_TITLE, first.title)
        assertTrue(first.note!!, first.note!!.contains("Dark from 18:35 until 05:25."))
    }

    @Test
    fun `a midsummer night says it never gets fully dark`() {
        val bright = list().copy(darkestFrom = null, darkestUntil = null)
        val first = TonightSections.everything(bright, timeline(emptyList()), report, sunset, tolerance = 50).first()
        assertEquals(TonightSections.NIGHT_TITLE, first.title)
        assertTrue(first.detail!!, first.detail!!.contains("It never gets fully dark here"))
    }

    @Test
    fun `a folded list holding only the night is headed without a count`() {
        assertEquals("Everything up tonight", TonightSections.heading("Everything up tonight", entries(TonightSections.NIGHT_TITLE)))
        assertEquals("Everything up tonight · 2", TonightSections.heading("Everything up tonight", entries("A", "B")))
    }

    @Test
    fun `everything with no timeline lists all of it`() {
        assertEquals(
            TonightLists.alsoUp(list(), null, report, sunset),
            TonightSections.everything(list(), null, report, sunset, tolerance = 50),
        )
    }

    private fun entries(vararg titles: String?) = titles.map { ListEntry(it, "detail") }

    @Test
    fun `preview names three and counts the rest`() {
        assertEquals(
            "Jupiter, Pleiades, Orion and 3 more",
            TonightSections.preview(entries("Jupiter", "Pleiades", "Orion", "A", "B", "C")),
        )
        assertEquals("Jupiter, Pleiades and Orion", TonightSections.preview(entries("Jupiter", "Pleiades", "Orion")))
        assertEquals("Jupiter and Orion", TonightSections.preview(entries("Jupiter", "Orion")))
        assertEquals("Jupiter", TonightSections.preview(entries("Jupiter")))
        assertNull(TonightSections.preview(emptyList()))
    }

    @Test
    fun `the night's own moments are neither named nor counted as something up`() {
        val listed = entries(TonightSections.NIGHT_TITLE, "Jupiter", "Orion")
        assertEquals("Jupiter and Orion", TonightSections.preview(listed))
        assertEquals(2, TonightSections.count(listed))
        assertEquals(3, TonightSections.count(entries("Jupiter", null, "Orion")))
    }

    @Test
    fun `preview skips an untitled entry`() {
        assertEquals("Jupiter and Orion", TonightSections.preview(entries("Jupiter", null, "Orion")))
        assertNull(TonightSections.preview(entries(null)))
    }

    @Test
    fun `earlier line, most recent first`() {
        val mercury = planetRow("Mercury", "2026-11-03T16:50:00Z", PickTag.Also)
        assertEquals(
            "Earlier: Starlink train G6-77 18:52, Mercury 16:50",
            TonightSections.earlierLine(listOf(mercury, starlink)),
        )
        assertNull(TonightSections.earlierLine(emptyList()))
    }

    @Test
    fun `next up in minutes, now, or at a time`() {
        val at1918 = at("2026-11-03T19:18:00Z")
        assertEquals("ISS in 6 min", TonightSections.nextUpWords(iss, at1918))
        assertEquals("ISS in 6 min", TonightSections.nextUpWords(iss, at1918 + 30_000))
        assertEquals("ISS now", TonightSections.nextUpWords(iss, at("2026-11-03T19:26:00Z")))
        val late = passRow("ISS (ZARYA)", "2026-11-03T21:14:00Z", PickTag.Go)
        assertEquals("ISS at 21:14", TonightSections.nextUpWords(late, at1918))
        assertEquals("ISS in 90 min", TonightSections.nextUpWords(late, late.atMillis - 90 * min))
    }

    @Test
    fun `next up is the first upcoming row, null once none is left or before the night`() {
        val now = at("2026-11-03T19:20:00Z")
        val t = timeline(night, now = now)
        assertEquals(iss, TonightSections.nextUp(TonightSections.worth(t), now))
        assertNull(TonightSections.nextUp(TonightSections.worth(t), null))
        val late = at("2026-11-03T21:00:00Z")
        assertNull(TonightSections.nextUp(TonightSections.worth(timeline(night, now = late)), late))
    }

    @Test
    fun `countdown to the trip`() {
        val trip = timeline(night, trip = listOf(iss.atMillis, saturn.atMillis))
        assertEquals("Starts in 3 h 44 min", TonightSections.countdown(trip, at("2026-11-03T15:40:00Z")))
        assertEquals("Starts in 2 h", TonightSections.countdown(trip, at("2026-11-03T17:24:00Z")))
        assertEquals("Starts in 25 min", TonightSections.countdown(trip, at("2026-11-03T18:59:00Z")))
        assertEquals("Starts in 1 min", TonightSections.countdown(trip, at("2026-11-03T19:23:30Z")))
        assertNull(TonightSections.countdown(trip, iss.atMillis))
        assertNull(TonightSections.countdown(timeline(night), at("2026-11-03T15:40:00Z")))
    }
}
