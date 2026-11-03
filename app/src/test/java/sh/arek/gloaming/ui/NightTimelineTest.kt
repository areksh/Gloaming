package sh.arek.gloaming.ui

import java.time.Instant
import java.util.TimeZone
import kotlinx.serialization.json.Json
import org.junit.After
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.Compass
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
        OutlookReport(outlook, excluded, null, 40, Compass.North)

    private fun goOut(picks: List<Pick>, also: List<Pick> = emptyList()) =
        Outlook.GoOut(picks, null, null, false, alsoTonight = also)

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

    /**
     * A pass in the earth's shadow cannot be seen at all, which is what a dim
     * row says; drawn like a visible one, it read as a reason to look up.
     */
    @Test
    fun `dims a pass in the earth's shadow as well as one behind cloud`() {
        val shadowed = pass("SL-16 R/B", "2026-12-14T19:00:00Z")
        val report = report(excluded = listOf(Excluded("SL-16 R/B", shadowed.peaksAt, Gate.Sunlit)))

        val row = build(list(passes = listOf(shadowed)), report)!!.rows.single { it.subject is RowSubject.Pass }

        assertEquals(RowTone.Dim, row.tone)
    }

    @Test
    fun `matches an event on its own moment`() {
        val event = SkyEvent(
            EventKind.MeteorShower, at("2026-12-14T23:00:00Z"), at("2026-12-14T22:00:00Z"),
            at("2026-12-15T03:00:00Z"), "Geminids at their best", "", Visibility.Good,
        )
        // Judged at a later moment than the event's own, as a shower is.
        val report = report(excluded = listOf(Excluded(event.title, event.at, Gate.Cloud, "80")))

        val rows = build(list(events = listOf(event)), report)!!.rows

        assertEquals(RowTone.Dim, rows.single { it.subject is RowSubject.Event }.tone)
    }

    /**
     * An event under way says so on its row; the noctilucent prompt is
     * worked out for tonight rather than dated, so its window being open is
     * not something happening.
     */
    @Test
    fun `marks an event under way as happening but never the noctilucent prompt`() {
        val from = at("2026-06-21T22:00:00Z")
        val until = at("2026-06-22T02:00:00Z")
        val eclipse = SkyEvent(EventKind.LunarEclipse, from + 3_600_000L, from, until, "Eclipse", "", Visibility.Good)
        val nlc = SkyEvent(EventKind.Noctilucent, from, from, until, "Noctilucent cloud season", "", Visibility.Good)
        fun row(event: SkyEvent) = TimelineRow(event.at, "", event.title, null, RowTone.Normal, RowSubject.Event(event))
        val during = from + 1_800_000L

        assertTrue(row(eclipse).isHappening(during))
        assertFalse(row(eclipse).isHappening(until + 1))
        assertFalse(row(nlc).isHappening(during))
    }

    /** The chart above draws the planets; the timeline lists only the one it sends you out for. */
    @Test
    fun `has no planet rows unless one is picked`() {
        val venus = planet(SkyBody.Venus, -4.0, "2026-12-14T16:30:00Z", "2026-12-14T20:00:00Z", "2026-12-14T18:00:00Z")

        val rows = build(list(planets = listOf(venus)))!!.rows

        assertTrue(rows.none { it.subject is RowSubject.Planet })
    }

    @Test
    fun `a picked planet gets one row at its moment with a pill`() {
        val saturn = planet(SkyBody.Saturn, 0.8, null, "2026-12-15T01:00:00Z", "2026-12-14T17:30:00Z")
        val moment = at("2026-12-14T19:00:00Z")
        val report = report(goOut(listOf(Pick(PickKind.Planet, "Saturn", moment, planet = saturn))))

        val row = build(list(planets = listOf(saturn)), report)!!.rows.single { it.subject is RowSubject.Planet }

        val seen = sh.arek.gloaming.astronomy.Planets.where(SkyBody.Saturn, 55.9533, -3.1883, moment)
        assertEquals(moment, row.atMillis)
        assertEquals("Saturn, ${seen.altitude.roundToInt()} degrees high", row.title)
        assertEquals("In the ${Compass.of(seen.azimuth).label}", row.detail)
        assertEquals(PickTag.Go, row.pick)
        assertEquals(RowTone.Strong, row.tone)
    }

    @Test
    fun `the headline's picks are Go and the line under it Also`() {
        val trip = pass("ISS", "2026-12-14T17:00:00Z")
        val later = pass("ISS", "2026-12-14T21:00:00Z")
        val report = report(
            goOut(
                listOf(Pick(PickKind.Pass, "ISS", trip.peaksAt, pass = trip)),
                also = listOf(Pick(PickKind.Pass, "ISS", later.peaksAt, pass = later)),
            ),
        )

        val timeline = build(list(passes = listOf(trip, later)), report)!!
        val rows = timeline.rows.filter { it.subject is RowSubject.Pass }

        assertEquals(listOf(PickTag.Go, PickTag.Also), rows.map { it.pick })
        assertEquals(listOf(trip.peaksAt), timeline.trip)
        assertEquals(listOf(later.peaksAt), timeline.alsoAt)
    }

    @Test
    fun `a pick outside the night has no row`() {
        val morning = pass("ISS", "2026-12-15T09:30:00Z")
        val report = report(goOut(emptyList(), also = listOf(Pick(PickKind.Pass, "ISS", morning.peaksAt, pass = morning))))

        val timeline = build(list(passes = listOf(morning)), report)!!

        assertTrue(timeline.rows.none { it.pick != null })
    }

    /** Clear 19:00-23:00 and 02:00-05:00, cloudy either side, from the forecast. */
    private val twoGaps = (12..31).map { h ->
        val t = at("2026-12-14T00:00:00Z") + h * 3_600_000L
        HourlyCover(t, if (h in 19..22 || h in 26..28) 10 else 90)
    }

    @Test
    fun `marks every clear gap with its end`() {
        val rows = build(list(), hourly = twoGaps)!!.rows.filter { it.subject is RowSubject.Clear }

        assertEquals(
            listOf("Clear until 23:00", "Cloud returns", "Clear again until 05:00", "Cloud returns"),
            rows.map { it.title },
        )
        assertEquals(listOf("19:00", "23:00", "02:00", "05:00"), rows.map { it.time })
        assertTrue(rows.all { it.tone == RowTone.Normal })
    }

    @Test
    fun `bolds only the clear spell a pick falls in`() {
        val station = pass("ISS", "2026-12-15T03:00:00Z")
        val report = report(goOut(listOf(Pick(PickKind.Pass, "ISS", station.peaksAt, pass = station))))

        val rows = build(list(passes = listOf(station)), report, hourly = twoGaps)!!
            .rows.filter { it.subject is RowSubject.Clear && it.title.startsWith("Clear") }

        assertEquals(listOf(RowTone.Normal, RowTone.Strong), rows.map { it.tone })
    }

    @Test
    fun `a spell covering the night has no cloud returning`() {
        val clear = (12..33).map { HourlyCover(at("2026-12-14T00:00:00Z") + it * 3_600_000L, 0) }

        val rows = build(list(), hourly = clear, nowMillis = at("2026-12-14T15:00:00Z"))!!.rows

        assertTrue(rows.none { it.title == "Cloud returns" })
        assertEquals(1, rows.count { it.subject is RowSubject.Clear })
    }

    @Test
    fun `moonrise says the phase`() {
        val moon = MoonTonight(250.0, 0.38, 0.4, 0.15, risesAt = at("2026-12-14T23:40:00Z"), setsAt = null)

        val row = build(list(moon = moon))!!.rows.single { it.title == "Moonrise" }

        assertEquals("Waning gibbous, 38% lit", row.detail)
    }

    @Test
    fun `says when the dark is moonless`() {
        val rising = MoonTonight(250.0, 0.38, 0.4, 0.15, risesAt = at("2026-12-14T23:40:00Z"), setsAt = null)
        val setting = MoonTonight(60.0, 0.4, 0.3, 0.1, risesAt = null, setsAt = at("2026-12-14T21:10:00Z"))
        val newMoon = MoonTonight(2.0, 0.01, 0.5, 0.0, risesAt = null, setsAt = null)

        fun moonless(moon: MoonTonight) = build(list(moon = moon))!!.rows.filter { it.title.startsWith("Moonless") }

        // Fully dark at 18:30 until 06:00, in the list above.
        assertEquals(listOf("Moonless dark until 23:40" to "18:30"), moonless(rising).map { it.title to it.time })
        assertEquals(listOf("Moonless dark until first light" to "21:10"), moonless(setting).map { it.title to it.time })
        assertEquals(listOf("Moonless all night" to "18:30"), moonless(newMoon).map { it.title to it.time })
        assertTrue(moonless(MoonTonight(180.0, 1.0, 1.0, 1.0, null, null)).isEmpty())
    }

    @Test
    fun `prompts a look for noctilucent cloud`() {
        val nlc = SkyEvent(
            EventKind.Noctilucent, at("2026-12-14T17:10:00Z"), at("2026-12-14T17:10:00Z"),
            at("2026-12-14T18:10:00Z"), "Good conditions for noctilucent clouds", "", Visibility.Good,
        )

        val row = build(list().copy(noctilucent = nlc))!!.rows.single { it.title == "Look for noctilucent cloud" }

        assertEquals("17:10", row.time)
        assertEquals("Low in the north until 18:10", row.detail)
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

    /** The sky column is coloured by how far down the sun is, so the model carries that. */
    @Test
    fun `samples the sun's height down the night for the sky column`() {
        val timeline = build(list())!!
        val sun = timeline.sun

        assertEquals(timeline.startMillis, sun.first().atMillis)
        assertEquals(timeline.endMillis, sun.last().atMillis)
        assertTrue(sun.zipWithNext().all { (a, b) -> b.atMillis - a.atMillis in 1..15 * 60_000L })
        val midnight = sun.minBy { kotlin.math.abs(it.atMillis - at("2026-12-15T00:00:00Z")) }
        assertEquals(Twilight.sunAltitude(55.9533, -3.1883, midnight.atMillis), midnight.altitude, 1e-9)
        assertTrue("${midnight.altitude}", midnight.altitude < -18.0)
    }

    /** Slots a row each, with the middle of the first line 10 below the top. */
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

    @Test
    fun `carries the up tonight chart for the same night`() {
        val venus = planet(SkyBody.Venus, -4.0, null, "2026-12-14T20:00:00Z", "2026-12-14T17:30:00Z")

        val timeline = build(list(planets = listOf(venus)))!!

        val chart = timeline.chart!!
        assertEquals(timeline.startMillis, chart.sunsetMillis)
        assertEquals(timeline.endMillis, chart.sunriseMillis)
        assertEquals(listOf("Venus"), chart.bodies.map { it.label })
    }

    /**
     * A night saved before the Busy setting and the moon's lane went: its rows
     * carry "busy" and it carries "lanes", and it must still open rather than
     * leave Tonight blank until the night is worked out again.
     */
    @Test
    fun `a snapshot with busy rows and lanes still decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val row = TimelineRow(0, "00:00", "Sunset", null, RowTone.Normal, RowSubject.Twilight)
        val current = json.encodeToString(Timeline(startMillis = 0, endMillis = 1, rows = listOf(row), cloud = emptyList(), nowMillis = null))
        val old = current
            .replace("\"title\":\"Sunset\"", "\"title\":\"Sunset\",\"busy\":true")
            .replaceFirst("{", "{\"lanes\":[{\"label\":\"Moon\",\"style\":\"Solid\",\"fromMillis\":0,\"untilMillis\":1}],")
        assertTrue(old.contains("\"busy\":true") && old.contains("\"lanes\""))
        assertEquals(listOf(row), json.decodeFromString<Timeline>(old).rows)
    }
}
