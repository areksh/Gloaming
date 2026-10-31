package sh.arek.gloaming.ui

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.Excluded
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** The model behind the vertical timeline: what is listed, in what order, with what weight. */
class NightTimelineTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val edinburgh = UserSettings(latitude = 55.9533, longitude = -3.1883)

    // A December night in Edinburgh: sun down about 15:39, up about 08:37.

    private fun pass(name: String, peak: String, elevation: Double = 68.0): SatellitePass {
        val t = at(peak)
        return SatellitePass(
            25544, name, PassChannel.Iss, t - 240_000, 315.0, t, elevation, 180.0,
            t + 240_000, 135.0, 450.0, true, null, null,
        )
    }

    private fun planet(body: SkyBody, magnitude: Double, rises: String?, sets: String?, peak: String) =
        PlanetTonight(
            body, 40.0, at(peak), 180.0, magnitude, rises?.let(::at), sets?.let(::at),
        )

    private fun list(
        passes: List<SatellitePass> = emptyList(),
        events: List<SkyEvent> = emptyList(),
        planets: List<PlanetTonight> = emptyList(),
        moon: MoonTonight? = null,
        clearSpell: ClearSpell? = null,
        darkFrom: Long? = at("2026-12-14T16:20:00Z"),
        darkestFrom: Long? = at("2026-12-14T18:30:00Z"),
    ) = TonightList(
        darkFrom = darkFrom,
        darkUntil = darkFrom?.let { at("2026-12-15T07:55:00Z") },
        passes = passes,
        bestPasses = passes,
        events = events,
        comets = emptyList(),
        noctilucent = null,
        darkestFrom = darkestFrom,
        darkestUntil = at("2026-12-15T06:00:00Z"),
        moon = moon,
        clearSpell = clearSpell,
        planets = planets,
    )

    private fun report(outlook: Outlook = Outlook.NoLocation, excluded: List<Excluded> = emptyList()) =
        OutlookReport(outlook, excluded, null, 40)

    private fun build(
        list: TonightList,
        report: OutlookReport = report(),
        hourly: List<HourlyCover> = emptyList(),
        settings: UserSettings = edinburgh,
        nowMillis: Long = at("2026-12-14T18:00:00Z"),
    ) = NightTimeline.of(list, report, hourly, settings, nowMillis)

    @Test
    fun `spans sunset to sunrise`() {
        val timeline = build(list())!!

        val sun = Twilight.sunTimes(55.9533, -3.1883, at("2026-12-14T16:20:00Z")..at("2026-12-15T07:55:00Z"))
        assertEquals(sun.setsAt, timeline.startMillis)
        assertEquals(sun.risesAt, timeline.endMillis)
        assertEquals("Sunset", timeline.rows.first().title)
        assertEquals("Sunrise", timeline.rows.last().title)
        assertEquals(at("2026-12-14T18:00:00Z"), timeline.nowMillis)
    }

    @Test
    fun `has no now marker when now is outside the night`() {
        assertNull(build(list(), nowMillis = at("2026-12-14T10:00:00Z"))!!.nowMillis)
    }

    @Test
    fun `has no timeline in the midnight sun`() {
        val svalbard = UserSettings(latitude = 78.2, longitude = 15.6)

        assertNull(build(list(darkFrom = null), settings = svalbard, nowMillis = at("2026-06-21T12:00:00Z")))
    }

    /** Torshavn at midsummer: the sun sets and rises but never gets six degrees down. */
    @Test
    fun `spans sunset to sunrise where civil darkness never comes`() {
        val faroes = UserSettings(latitude = 62.0, longitude = -6.77)
        val bright = list(darkFrom = null, darkestFrom = null).copy(darkestUntil = null)

        val timeline = build(bright, settings = faroes, nowMillis = at("2026-06-21T12:00:00Z"))!!

        assertEquals("Sunset", timeline.rows.first().title)
        assertEquals("Sunrise", timeline.rows.last().title)
        assertTrue(timeline.rows.none { it.title.startsWith("Dark enough") || it.title == "Fully dark" })
        val hours = (timeline.endMillis - timeline.startMillis) / 3_600_000.0
        assertTrue("night $hours h", hours in 3.0..8.0)
    }

    private val faroes = UserSettings(latitude = 62.0, longitude = -6.77)

    /** 01:00 local: the night is under way, and now sits inside it. */
    @Test
    fun `without civil darkness keeps the night now is in`() {
        val now = at("2026-06-22T00:00:00Z")
        val bright = list(darkFrom = null, darkestFrom = null).copy(darkestUntil = null)

        val timeline = build(bright, settings = faroes, nowMillis = now)!!

        assertTrue(timeline.startMillis <= now && now <= timeline.endMillis)
        assertEquals(now, timeline.nowMillis)
        val hours = (timeline.endMillis - timeline.startMillis) / 3_600_000.0
        assertTrue("night $hours h", hours in 3.0..8.0)
    }

    /** 05:00 local, after sunrise: last night is over, so the coming one. */
    @Test
    fun `without civil darkness looks to the coming night after sunrise`() {
        val now = at("2026-06-22T04:00:00Z")
        val bright = list(darkFrom = null, darkestFrom = null).copy(darkestUntil = null)

        val timeline = build(bright, settings = faroes, nowMillis = now)!!

        assertTrue("starts ${timeline.startMillis}", timeline.startMillis > now)
        assertNull(timeline.nowMillis)
        val hours = (timeline.endMillis - timeline.startMillis) / 3_600_000.0
        assertTrue("night $hours h", hours in 3.0..8.0)
    }

    @Test
    fun `has no timeline without a position`() {
        assertNull(build(list(), settings = UserSettings()))
    }

    @Test
    fun `orders rows by time`() {
        val timeline = build(
            list(
                passes = listOf(pass("ISS", "2026-12-14T17:00:00Z"), pass("Tiangong", "2026-12-14T17:00:00Z", 30.0)),
                planets = listOf(planet(SkyBody.Venus, -4.0, null, "2026-12-14T20:00:00Z", "2026-12-14T17:30:00Z")),
            ),
        )!!

        val times = timeline.rows.map { it.atMillis }
        assertEquals(times.sorted(), times)
        assertEquals(
            listOf("Sunset", "Dark enough for aurora", "ISS, 68 degrees high", "Tiangong, 30 degrees high"),
            timeline.rows.map { it.title }.take(4),
        )
        assertTrue(timeline.rows.any { it.title == "Fully dark" })
        assertTrue(timeline.rows.any { it.title == "First light" })
    }

    @Test
    fun `describes a pass by its rise and set`() {
        val row = build(list(passes = listOf(pass("ISS", "2026-12-14T17:00:00Z"))))!!
            .rows.single { it.subject is RowSubject.Pass }

        assertEquals("17:00", row.time)
        assertEquals("Rises north-west, sets south-east", row.detail)
        assertEquals(RowTone.Normal, row.tone)
    }

    @Test
    fun `dims a pass behind cloud and strengthens a recommended one`() {
        val hidden = pass("Tiangong", "2026-12-14T17:00:00Z")
        val chosen = pass("ISS", "2026-12-14T19:00:00Z")
        val other = pass("ISS", "2026-12-14T21:00:00Z")
        val report = report(
            Outlook.GoOut(
                listOf(Pick(PickKind.Pass, "ISS", chosen.peaksAt, pass = chosen)), null, null, false,
            ),
            excluded = listOf(
                Excluded("Tiangong", hidden.peaksAt, Gate.Cloud, "90"),
                // Same subject, another time: must not dim the 21:00 ISS pass.
                Excluded("ISS", other.peaksAt + 3_600_000, Gate.Cloud, "90"),
                // Right subject and time, but not cloud.
                Excluded("ISS", other.peaksAt, Gate.Height, "10"),
            ),
        )

        val rows = build(list(passes = listOf(hidden, chosen, other)), report)!!
            .rows.filter { it.subject is RowSubject.Pass }

        assertEquals(listOf(RowTone.Dim, RowTone.Strong, RowTone.Normal), rows.map { it.tone })
    }

    @Test
    fun `matches an event on its own moment and a planet on its name alone`() {
        val event = SkyEvent(
            EventKind.MeteorShower, at("2026-12-14T23:00:00Z"), at("2026-12-14T22:00:00Z"),
            at("2026-12-15T03:00:00Z"), "Geminids at their best", "", Visibility.Good,
        )
        val venus = planet(SkyBody.Venus, -4.0, null, "2026-12-14T20:00:00Z", "2026-12-14T17:30:00Z")
        val report = report(
            excluded = listOf(
                // Judged at a later moment than the event's own, as a shower is.
                Excluded(event.title, event.at, Gate.Cloud, "80"),
                // Judged at a time other than the planet's peak.
                Excluded("Venus", at("2026-12-14T18:10:00Z"), Gate.Cloud, "80"),
            ),
        )

        val rows = build(list(events = listOf(event), planets = listOf(venus)), report)!!.rows

        assertEquals(RowTone.Dim, rows.single { it.subject is RowSubject.Event }.tone)
        assertTrue(rows.filter { it.subject is RowSubject.Planet }.all { it.tone == RowTone.Dim })
    }

    @Test
    fun `lists a planet's rise, highest and set`() {
        val venus = planet(SkyBody.Venus, -4.0, "2026-12-14T16:30:00Z", "2026-12-14T20:00:00Z", "2026-12-14T18:00:00Z")

        val rows = build(list(planets = listOf(venus)))!!.rows.filter { it.subject is RowSubject.Planet }

        assertEquals(listOf("Venus rises", "Venus highest, 40 degrees", "Venus sets"), rows.map { it.title })
        assertEquals(listOf("16:30", "18:00", "20:00"), rows.map { it.time })
    }

    @Test
    fun `marks the clear spell with its end`() {
        val spell = ClearSpell(at("2026-12-14T19:00:00Z"), at("2026-12-14T23:00:00Z"), underway = false, allNight = false)

        val rows = build(list(clearSpell = spell))!!.rows

        val start = rows.single { it.subject is RowSubject.Clear && it.atMillis == spell.from }
        assertEquals("Clear until 23:00", start.title)
        assertEquals(RowTone.Strong, start.tone)
        val end = rows.single { it.title == "Cloud returns" }
        assertEquals(spell.until, end.atMillis)
    }

    @Test
    fun `a spell covering the night has no cloud returning`() {
        val spell = ClearSpell(at("2026-12-14T19:00:00Z"), at("2026-12-15T07:55:00Z"), underway = false, allNight = true)

        assertTrue(build(list(clearSpell = spell))!!.rows.none { it.title == "Cloud returns" })
    }

    @Test
    fun `gives the moon a solid lane from moonrise`() {
        val moon = MoonTonight(180.0, 1.0, 0.5, 0.5, risesAt = at("2026-12-14T22:00:00Z"), setsAt = null)

        val timeline = build(list(moon = moon))!!

        val lane = timeline.lanes.single { it.label == "Moon" }
        assertEquals(LaneStyle.Solid, lane.style)
        assertEquals(moon.risesAt, lane.fromMillis)
        assertEquals(timeline.endMillis, lane.untilMillis)
        assertTrue(timeline.rows.any { it.subject is RowSubject.Moon && it.title == "Moonrise" })
    }

    @Test
    fun `lanes at most two planets, the brightest`() {
        val planets = listOf(
            planet(SkyBody.Saturn, 1.0, null, null, "2026-12-14T18:00:00Z"),
            planet(SkyBody.Venus, -4.0, null, null, "2026-12-14T18:00:00Z"),
            planet(SkyBody.Jupiter, -2.5, "2026-12-14T21:00:00Z", null, "2026-12-15T02:00:00Z"),
        )

        val lanes = build(list(planets = planets))!!.lanes

        assertEquals(listOf("Venus", "Jupiter"), lanes.map { it.label })
        assertTrue(lanes.all { it.style != LaneStyle.Solid })
        assertEquals(at("2026-12-14T21:00:00Z"), lanes[1].fromMillis)
    }

    @Test
    fun `reads the hourly forecast into cloud spans within the night`() {
        val hourly = listOf(
            HourlyCover(at("2026-12-14T15:00:00Z"), 100),
            HourlyCover(at("2026-12-14T16:00:00Z"), 20, low = 10, mid = 10, high = 40),
            HourlyCover(at("2026-12-15T10:00:00Z"), 100),
        )

        val timeline = build(list(), hourly = hourly)!!

        assertEquals(2, timeline.cloud.size)
        assertEquals(timeline.startMillis, timeline.cloud[0].fromMillis)
        assertEquals(at("2026-12-14T16:00:00Z"), timeline.cloud[0].untilMillis)
        assertEquals(20, timeline.cloud[1].cover)
        assertTrue(timeline.cloud.all { it.fromMillis >= timeline.startMillis && it.untilMillis <= timeline.endMillis })
    }

    @Test
    fun `layout keeps a row at its time when there is room`() {
        assertEquals(listOf(0, 100, 300), NightTimeline.layout(listOf(0, 100, 300), listOf(50, 50, 50), 8))
    }

    @Test
    fun `layout pushes a colliding row down by exactly the overlap`() {
        // The second wants 40 but the first ends at 50 and a gap of 8 follows.
        assertEquals(listOf(0, 58, 200), NightTimeline.layout(listOf(0, 40, 200), listOf(50, 50, 50), 8))
    }

    @Test
    fun `layout never moves a row up`() {
        val tops = NightTimeline.layout(listOf(0, 10, 20, 500), listOf(30, 30, 30, 30), 4)

        assertEquals(listOf(0, 34, 68, 500), tops)
        listOf(0, 10, 20, 500).forEachIndexed { i, ideal -> assertTrue(tops[i] >= ideal) }
    }

    /**
     * The night of the autumn change: Edinburgh, clocks go back at 01:00 UTC on
     * the 25th, so the night is an hour longer than the clock says and 01:30
     * happens twice.
     */
    @Test
    fun `a night across the clock change stays proportional and labels local time`() {
        val tonight = Tonight.of(
            satellites = null, comets = null, calendar = emptyList(), settings = edinburgh,
            nowMillis = at("2026-10-24T18:00:00Z"), cloud = null, hourly = emptyList(),
        )
        val first = pass("ISS", "2026-10-25T00:30:00Z")
        val second = pass("Tiangong", "2026-10-25T01:30:00Z")
        val list = tonight.copy(passes = listOf(first, second), bestPasses = listOf(first, second))

        val timeline = NightTimeline.of(list, report(), emptyList(), edinburgh, at("2026-10-24T18:00:00Z"))!!

        val real = timeline.endMillis - timeline.startMillis
        assertTrue("real duration $real", real in (14 * 3_600_000L)..(14 * 3_600_000L + 30 * 60_000L))
        // By the wall clock the same night reads an hour shorter, 13h14; the axis must not follow that.
        val passes = timeline.rows.filter { it.subject is RowSubject.Pass }
        assertEquals(listOf("01:30", "01:30"), passes.map { it.time })
        assertEquals(listOf(first.peaksAt, second.peaksAt), passes.map { it.atMillis })
    }
}
