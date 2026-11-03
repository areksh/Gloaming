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
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.TonightList

/** The horizontal chart of what is up tonight: the moon, the planets and any comet. */
class UpChartTest {

    private lateinit var zone: TimeZone

    @Before
    fun fixZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val lat = 55.9533
    private val lon = -3.1883
    private val sunset = at("2026-12-14T15:39:00Z")
    private val sunrise = at("2026-12-15T08:37:00Z")
    private val hour = 3_600_000L

    private val saturn = PlanetTonight(SkyBody.Saturn, 20.0, at("2026-12-14T17:00:00Z"), 180.0, 1.0, null, null)
    private val jupiter = PlanetTonight(SkyBody.Jupiter, 50.0, at("2026-12-15T02:00:00Z"), 180.0, -2.5, null, null)
    private val moon = MoonTonight(250.0, 0.38, 0.4, 0.15, at("2026-12-14T23:40:00Z"), null)

    private fun list(
        planets: List<PlanetTonight> = listOf(saturn, jupiter),
        moon: MoonTonight? = this.moon,
        comets: List<SkyEvent> = emptyList(),
    ) = TonightList(
        darkFrom = at("2026-12-14T16:20:00Z"), darkUntil = at("2026-12-15T07:55:00Z"),
        passes = emptyList(), bestPasses = emptyList(), events = emptyList(), comets = comets,
        noctilucent = null, moon = moon, planets = planets,
    )

    private fun chart(list: TonightList, trip: List<Pick> = emptyList(), also: List<Pick> = emptyList()) =
        UpCharts.of(list, trip, also, lat, lon, sunset, sunrise)

    @Test
    fun `charts the moon and every planet from an hour before sunset to an hour after sunrise`() {
        val chart = chart(list())!!

        assertEquals(sunset - hour, chart.fromMillis)
        assertEquals(sunrise + hour, chart.untilMillis)
        assertEquals(sunset, chart.sunsetMillis)
        assertEquals(sunrise, chart.sunriseMillis)
        assertEquals(listOf("Moon", "Saturn", "Jupiter"), chart.bodies.map { it.label })
        assertEquals(listOf(UpKind.Moon, UpKind.Planet, UpKind.Planet), chart.bodies.map { it.kind })
    }

    @Test
    fun `samples altitudes on the step`() {
        val chart = chart(list())!!
        val saturnUp = chart.bodies.single { it.body == SkyBody.Saturn }
        val moonUp = chart.bodies.single { it.kind == UpKind.Moon }

        val expectedSize = ((chart.untilMillis - chart.fromMillis + UpCharts.STEP_MILLIS - 1) / UpCharts.STEP_MILLIS + 1).toInt()
        assertEquals(expectedSize, saturnUp.samples.size)
        assertEquals(expectedSize, chart.sun.size)
        val t = chart.fromMillis + 8 * UpCharts.STEP_MILLIS
        assertEquals(Planets.altitude(SkyBody.Saturn, lat, lon, t), saturnUp.samples[8].toDouble(), 0.01)
        assertEquals(Twilight.moonAltitude(lat, lon, t), moonUp.samples[8].toDouble(), 0.01)
        assertEquals(Twilight.sunAltitude(lat, lon, t), chart.sun[8].altitude, 1e-9)
        assertEquals(chart.untilMillis, chart.sun.last().atMillis)
    }

    @Test
    fun `charts a comet over the window it is worth a look`() {
        val comet = SkyEvent(
            EventKind.Comet, at("2026-12-15T02:00:00Z"), at("2026-12-14T19:00:00Z"),
            at("2026-12-15T05:00:00Z"), "Comet 220P/McNaught", "", Visibility.Good,
        )

        val body = chart(list(comets = listOf(comet)))!!.bodies.single { it.kind == UpKind.Comet }

        assertEquals("Comet 220P/McNaught", body.label)
        assertEquals(comet.at, body.peakAt)
        val before = ((comet.from - (sunset - hour)) / UpCharts.STEP_MILLIS).toInt() - 1
        assertTrue(body.samples[before] < 0f)
        assertTrue(body.samples[before + 2] > 0f)
    }

    @Test
    fun `carries the picks as marks with the planet they are about`() {
        val trip = listOf(Pick(PickKind.Planet, "Saturn", at("2026-12-14T18:00:00Z"), planet = saturn))
        val also = listOf(Pick(PickKind.Event, "Geminids", at("2026-12-15T02:00:00Z")))

        val marks = chart(list(), trip, also)!!.marks

        assertEquals(
            listOf(
                PickMark(at("2026-12-14T18:00:00Z"), PickTag.Go, SkyBody.Saturn),
                PickMark(at("2026-12-15T02:00:00Z"), PickTag.Also, null),
            ),
            marks,
        )
    }

    @Test
    fun `has no chart with nothing to chart`() {
        assertNull(chart(list(planets = emptyList(), moon = null)))
    }

    @Test
    fun `summarises who is up when`() {
        val start = sunset
        val chart = UpChart(
            fromMillis = start - hour,
            untilMillis = start + 3 * hour,
            sunsetMillis = start,
            sunriseMillis = start + 2 * hour,
            sun = emptyList(),
            bodies = listOf(
                // Up from the start until an hour in, sampled every quarter hour.
                UpBody(UpKind.Planet, "Saturn", SkyBody.Saturn, List(17) { if (it <= 8) 10f else -5f }, start, 10.0),
                UpBody(UpKind.Moon, "Moon", null, List(17) { -10f }, start, -10.0),
            ),
            marks = emptyList(),
        )

        assertEquals("Up tonight: Saturn until ${clock(start + hour)}. The moon is down all night.", UpCharts.summary(chart))
    }
}
