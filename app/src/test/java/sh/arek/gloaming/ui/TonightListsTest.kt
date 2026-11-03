package sh.arek.gloaming.ui

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.Excluded
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** What goes under the timeline: what else is up, and what was left out and why. */
class TonightListsTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private val hour = 3_600_000L

    // 2027-01-15 18:00 UTC.
    private val now = 1_800_000_000_000L - 14 * hour

    private val station = SatellitePass(
        25544, "ISS (ZARYA)", PassChannel.Iss, now + hour, 225.0, now + hour + 180_000L, 68.0,
        180.0, now + hour + 360_000L, 135.0, 420.0, true, null, null,
    )

    private val jupiter = PlanetTonight(SkyBody.Jupiter, 46.4, now + 3 * hour, 178.0, -2.46, null, null)

    private val comet = SkyEvent(
        EventKind.Comet, now + hour, now, now + 8 * hour, "Comet 220P/McNaught",
        "Magnitude 9.5. Highest at 46 degrees in the south.", Visibility.Good,
    )

    private val conjunction = SkyEvent(
        EventKind.Conjunction, now + 2 * hour, now, now + 6 * hour, "The moon passes Mars",
        "Two degrees apart.", Visibility.Good,
    )

    private val orion = ConstellationTonight(
        constellation = Constellations.WATCHED.first { it.name == "Orion" },
        peakAltitude = 39.0,
        peakAt = now + 3 * hour,
        peakAzimuth = 180.0,
        isCircumpolar = false,
        isAtItsBest = false,
    )

    private fun list(
        passes: List<SatellitePass> = emptyList(),
        events: List<SkyEvent> = emptyList(),
        comets: List<SkyEvent> = emptyList(),
        planets: List<PlanetTonight> = emptyList(),
        constellations: List<ConstellationTonight> = emptyList(),
        alwaysUp: List<String> = emptyList(),
        moon: MoonTonight? = null,
        elementsHeld: Boolean = true,
    ) = TonightList(
        darkFrom = now,
        darkUntil = now + 8 * hour,
        passes = passes,
        bestPasses = passes,
        events = events,
        comets = comets,
        noctilucent = null,
        planets = planets,
        constellations = constellations,
        constellationsAlwaysUp = alwaysUp,
        moon = moon,
        elementsHeld = elementsHeld,
    )

    private fun report(vararg excluded: Excluded) =
        OutlookReport(Outlook.Quiet(now, null, false), excluded.toList(), 12, 40, Compass.North)

    private fun timeline(vararg rows: TimelineRow) =
        Timeline(now - hour, now + 14 * hour, rows.toList(), emptyList(), emptyList(), now)

    private fun row(at: Long, title: String, subject: RowSubject) =
        TimelineRow(at, clock(at), title, null, RowTone.Normal, subject)

    @Test
    fun `lists what is up and not on the timeline`() {
        val tonight = list(events = listOf(conjunction), comets = listOf(comet), constellations = listOf(orion))
        val drawn = timeline(row(conjunction.at, conjunction.title, RowSubject.Event(conjunction)))

        val titles = TonightLists.alsoUp(tonight, drawn, report(), now).map { it.title }

        assertEquals(listOf("Comet 220P/McNaught", "Orion"), titles)
    }

    /** A pattern with a row on the timeline as drawn is not listed again. */
    @Test
    fun `leaves out a constellation the timeline has a row for`() {
        val drawn = timeline(row(orion.peakAt, "Orion highest, 39 degrees", RowSubject.Constellation(orion)))

        val titles = TonightLists.alsoUp(list(constellations = listOf(orion)), drawn, report(), now).map { it.title }

        assertTrue(titles.none { it == "Orion" })
    }

    @Test
    fun `says where a comet is and how to find a constellation`() {
        val entries = TonightLists.alsoUp(
            list(comets = listOf(comet), constellations = listOf(orion)), timeline(), report(), now,
        )

        assertEquals(comet.detail, entries[0].detail)
        assertEquals(describeConstellation(orion), entries[1].detail)
    }

    @Test
    fun `carries an event's caveat and whether it is happening now`() {
        val marginal = conjunction.copy(visibility = Visibility.Marginal("Only 8 degrees up."))

        val entry = TonightLists.alsoUp(list(events = listOf(marginal)), null, report(), now + 2 * hour).single()

        assertEquals("Only 8 degrees up.", entry.note)
        assertTrue(entry.happeningNow)
        assertFalse(TonightLists.alsoUp(list(events = listOf(marginal)), null, report(), now - hour).single().happeningNow)
    }

    /**
     * The noctilucent prompt is worked out for tonight rather than dated, so
     * it is a suggestion to look, never something happening as you read.
     */
    /** The prompt has a row on the timeline wherever there is one, so it is not said twice. */
    @Test
    fun `leaves out the noctilucent prompt the timeline has a row for`() {
        val noctilucent = SkyEvent(
            EventKind.Noctilucent, now, now, now + 3 * hour, "Noctilucent cloud season", "", Visibility.Good,
        )
        val drawn = timeline(row(now, "Look for noctilucent cloud", RowSubject.Event(noctilucent)))

        assertTrue(TonightLists.alsoUp(list().copy(noctilucent = noctilucent), drawn, report(), now).isEmpty())
    }

    @Test
    fun `never marks the noctilucent prompt as happening now`() {
        val noctilucent = SkyEvent(
            EventKind.Noctilucent, now, now - hour, now + 3 * hour, "Noctilucent cloud season",
            "Look low in the north after sunset.", Visibility.Good,
        )

        val entry = TonightLists.alsoUp(list().copy(noctilucent = noctilucent), null, report(), now).single()

        assertEquals("Noctilucent cloud season", entry.title)
        assertFalse(entry.happeningNow)
    }

    @Test
    fun `leaves out what was set aside, which is listed with its reason instead`() {
        val tonight = list(comets = listOf(comet), constellations = listOf(orion))
        val setAside = Excluded(comet.title, comet.at, Gate.Moon, note = "70")

        val titles = TonightLists.alsoUp(tonight, timeline(), report(setAside), now).map { it.title }

        assertEquals(listOf("Orion"), titles)
    }

    @Test
    fun `lists everything up when there is no timeline to draw it on`() {
        val tonight = list(passes = listOf(station), events = listOf(conjunction), planets = listOf(jupiter))

        val entries = TonightLists.alsoUp(tonight, null, report(), now)

        assertEquals(listOf("The moon passes Mars", "Jupiter", "ISS (ZARYA)"), entries.map { it.title })
        assertEquals(describePlanet(jupiter), entries[1].detail)
        assertEquals(describePassLine(station), entries[2].detail)
    }

    @Test
    fun `leaves out a planet and a pass the timeline already draws`() {
        val tonight = list(passes = listOf(station), planets = listOf(jupiter))
        val drawn = timeline(
            row(station.peaksAt, "ISS (ZARYA), 68 degrees high", RowSubject.Pass(station)),
            row(jupiter.peakAt, "Jupiter highest, 46 degrees", RowSubject.Planet(jupiter)),
        )

        assertTrue(TonightLists.alsoUp(tonight, drawn, report(), now).isEmpty())
    }

    @Test
    fun `lists the moon when it is up all night and has no row`() {
        val full = MoonTonight(180.0, 0.98, 1.0, 0.98, risesAt = null, setsAt = null)

        val entry = TonightLists.alsoUp(list(moon = full), timeline(), report(), now).single()

        assertEquals("The moon", entry.title)
        assertEquals("Full moon, 98% lit. It washes out about 98% of tonight's darkness.", entry.detail)
    }

    @Test
    fun `leaves the moon to its rows when it rises or sets tonight, and out when it is down`() {
        val rising = MoonTonight(90.0, 0.5, 0.5, 0.25, risesAt = now + 2 * hour, setsAt = null)
        val down = MoonTonight(90.0, 0.5, 0.0, 0.0, risesAt = null, setsAt = null)
        val drawn = timeline(row(now + 2 * hour, "Moonrise", RowSubject.Moon))

        assertTrue(TonightLists.alsoUp(list(moon = rising), drawn, report(), now).isEmpty())
        assertTrue(TonightLists.alsoUp(list(moon = down), timeline(), report(), now).isEmpty())
    }

    @Test
    fun `names the constellations that never set in one line`() {
        val entry = TonightLists.alsoUp(list(alwaysUp = listOf("Ursa Major", "Cassiopeia")), null, report(), now).single()

        assertEquals(null, entry.title)
        assertEquals(describeAlwaysUp(listOf("Ursa Major", "Cassiopeia")), entry.detail)
    }

    @Test
    fun `says why each thing was left out`() {
        val cloudy = Excluded(station.name, station.peaksAt, Gate.Cloud, note = "80")
        val weak = Excluded("Aurora", null, Gate.Aurora, note = "12")

        val entries = TonightLists.notWorth(list(passes = listOf(station)), report(cloudy, weak), true, null, now)

        assertEquals(listOf("ISS (ZARYA) at 19:03", "Aurora"), entries.map { it.title })
        assertEquals("Forecast 80% cloud when it crosses.", entries[0].detail)
        assertEquals("12%, too weak to see from here.", entries[1].detail)
    }

    /** A planet is judged at a moment that moves with the clock, so it is given none. */
    @Test
    fun `gives a planet left out no time`() {
        val low = Excluded("Jupiter", now + 5 * 60_000L, Gate.Height, note = "8", kind = PickKind.Planet)

        val tonight = list(passes = listOf(station), planets = listOf(jupiter))

        val entry = TonightLists.notWorth(tonight, report(low), true, null, now).single()

        assertEquals("Jupiter", entry.title)
        assertEquals("Only 8 degrees up by then.", entry.detail)
    }

    @Test
    fun `says the elements are missing rather than that the sky is empty`() {
        val entry = TonightLists.notWorth(list(elementsHeld = false), null, false, null, now).single()

        assertEquals("Satellites", entry.title)
        assertEquals(describeMissingElements(everChecked = false, failure = null).text, entry.detail)
        assertFalse(entry.alarming)
    }

    @Test
    fun `marks a broken elements download as something wrong`() {
        val entry = TonightLists.notWorth(list(elementsHeld = false), null, true, "returned 503", now).single()

        assertTrue(entry.alarming)
        assertTrue(entry.detail!!.endsWith("returned 503"))
    }

    @Test
    fun `says nothing crosses when it has the elements and nothing does`() {
        val entry = TonightLists.notWorth(list(), null, true, null, now).single()

        assertEquals("Nothing crossing tonight that clears the horizon in sunlight.", entry.detail)
    }

    @Test
    fun `says nothing about satellites while passes are listed`() {
        assertTrue(TonightLists.notWorth(list(passes = listOf(station)), null, true, null, now).isEmpty())
    }
}
