package sh.arek.gloaming.ui

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.MoonTonight

/**
 * The sentences the Tonight tab is made of, apart from the tab itself.
 *
 * Three of these describe numbers the app has computed for a long time and
 * never said out loud: the cloud it gates every alert on, the moonlight it
 * weighs every comet against, and the gap in the forecast that decides a
 * marginal night. The fourth is the cloud wording the notification already
 * used, which now has two callers and so has to mean one thing.
 */
class TonightWordingTest {

    private val hour = 60 * 60 * 1000L

    /** A real midnight UTC, so [at] reads as the hour it prints. */
    private val midnight = 1_800_000_000_000L / (24 * hour) * (24 * hour)

    private val zone = TimeZone.getDefault()

    /** Times are printed in the device's own zone, so the test fixes one. */
    @Before
    fun fixTheZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTheZone() {
        TimeZone.setDefault(zone)
    }

    private fun at(hourOfDay: Int) = midnight + hourOfDay * hour

    // --- cloud ---

    @Test
    fun `calls a nearly empty sky clear rather than quoting a number`() {
        assertEquals("clear", describeCloud(CloudCover(total = 10, low = 0, mid = 0, high = 10)))
    }

    /**
     * Where the cloud is matters as much as how much of it there is: high
     * cirrus at 60% still shows a strong aurora, low stratus at 60% does not.
     */
    @Test
    fun `says when the cloud is high and thin`() {
        val cloud = CloudCover(total = 60, low = 10, mid = 5, high = 60)

        assertEquals("60% cloud, high and thin", describeCloud(cloud))
    }

    @Test
    fun `quotes the total when the cloud is in the way`() {
        val cloud = CloudCover(total = 60, low = 55, mid = 20, high = 10)

        assertEquals("60% cloud", describeCloud(cloud))
    }

    /**
     * An unreachable forecast says so rather than being left out. Omitting it
     * would read as a clear sky, which is the one wrong answer that silently
     * suppresses a real alert.
     */
    @Test
    fun `says when there is no reading at all`() {
        assertEquals("cloud cover unknown", describeCloud(null))
    }

    // --- the gap in the cloud ---

    @Test
    fun `names when a gap in the cloud opens`() {
        val spell = ClearSpell(from = at(1), until = at(3), underway = false, allNight = false)

        assertEquals("Clearer from about 01:00 until 03:00.", describeClearSpell(spell, 40))
    }

    /** A spell already underway is about how long it lasts, not when it began. */
    @Test
    fun `says how long a gap already open lasts`() {
        val spell = ClearSpell(from = at(1), until = at(3), underway = true, allNight = false)

        assertEquals("Clear now, until about 03:00.", describeClearSpell(spell, 40))
    }

    @Test
    fun `says when the whole night is under the tolerance`() {
        val spell = ClearSpell(from = at(0), until = at(8), underway = true, allNight = true)

        assertEquals("Under 40% cloud for the rest of the night.", describeClearSpell(spell, 40))
    }

    @Test
    fun `says when the forecast has no gap in it`() {
        assertEquals(
            "No gap in the cloud forecast tonight, at 40% or under.",
            describeClearSpell(null, 40),
        )
    }

    // --- the moon ---

    /** The illumination each phase carries, so the name and the number agree. */
    private fun lit(phaseDegrees: Double) =
        (1 - kotlin.math.cos(Math.toRadians(phaseDegrees))) / 2

    private fun phase(degrees: Double) = describeMoonPhase(degrees, lit(degrees))

    @Test
    fun `names the phases everybody uses`() {
        assertEquals("New moon", phase(0.0))
        assertEquals("Waxing crescent", phase(45.0))
        assertEquals("First quarter", phase(90.0))
        assertEquals("Waxing gibbous", phase(135.0))
        assertEquals("Full moon", phase(180.0))
        assertEquals("Waning gibbous", phase(225.0))
        assertEquals("Last quarter", phase(270.0))
        assertEquals("Waning crescent", phase(315.0))
    }

    /**
     * The moon moves about 12 degrees a day, and nobody calls a moon eleven
     * hours off full anything but full.
     */
    @Test
    fun `gives the named phases a window rather than an instant`() {
        assertEquals("Full moon", phase(176.0))
        assertEquals("Full moon", phase(184.0))
        assertEquals("New moon", phase(357.0))
        assertEquals("New moon", phase(3.0))
    }

    @Test
    fun `reads a phase outside the circle as the one inside it`() {
        assertEquals("Full moon", describeMoonPhase(540.0, 1.0))
        assertEquals("Full moon", describeMoonPhase(-180.0, 1.0))
    }

    /**
     * The name and the number beside it can never contradict each other.
     *
     * "Waxing gibbous, 100% lit" is a sentence that argues with itself, and it
     * was on a real phone on the night of a lunar eclipse - which is the one
     * night somebody is certain to be reading this card. A moon a few hours
     * short of full is 173 degrees round its cycle and 99.7% lit: the angle
     * says gibbous and the rounded percentage says full.
     */
    @Test
    fun `never calls a fully lit moon anything but full`() {
        assertEquals("Full moon", describeMoonPhase(173.0, 0.997))
        assertEquals("Full moon", describeMoonPhase(187.0, 0.997))
    }

    @Test
    fun `never calls an unlit moon anything but new`() {
        assertEquals("New moon", describeMoonPhase(8.0, 0.004))
    }

    /** And still names the ordinary in-between phases from the angle. */
    @Test
    fun `keeps naming the phases either side from the angle`() {
        assertEquals("Waxing gibbous", describeMoonPhase(150.0, 0.93))
        assertEquals("Waning gibbous", describeMoonPhase(210.0, 0.93))
    }

    /**
     * Brightness alone is the wrong measure. A full moon that has set by
     * midnight costs far less than a half moon up all night, and it is that
     * product the comet gate has always used.
     */
    @Test
    fun `reports what the moon spoils rather than how bright it is`() {
        val moon = moon(illumination = 1.0, upFraction = 0.3)

        assertEquals("It spoils about 30% of tonight's darkness.", describeMoonlight(moon))
    }

    @Test
    fun `says when the moon costs the night nothing at all`() {
        val moon = moon(illumination = 1.0, upFraction = 0.0)

        assertEquals(
            "Below the horizon all night, so it costs you nothing.",
            describeMoonlight(moon),
        )
    }

    @Test
    fun `says when the moon washes the night out`() {
        val moon = moon(illumination = 0.98, upFraction = 1.0)

        assertEquals("It washes out about 98% of tonight's darkness.", describeMoonlight(moon))
    }

    @Test
    fun `says when the moon is barely there`() {
        val moon = moon(illumination = 0.04, upFraction = 1.0)

        assertEquals("Barely there: it spoils about 4% of tonight's darkness.", describeMoonlight(moon))
    }

    @Test
    fun `names when the moon comes and goes`() {
        val moon = moon(illumination = 0.5, upFraction = 0.5, risesAt = at(1), setsAt = at(5))

        assertEquals("Rises 01:00, sets 05:00.", describeMoonTimes(moon))
    }

    @Test
    fun `names only the crossing that happens`() {
        val moon = moon(illumination = 0.5, upFraction = 0.5, setsAt = at(2))

        assertEquals("Sets 02:00.", describeMoonTimes(moon))
    }

    /**
     * Most nights the moon is up throughout or down throughout, and inventing
     * a sentence for that would be inventing an event.
     */
    @Test
    fun `says nothing when the moon neither rises nor sets`() {
        assertNull(describeMoonTimes(moon(illumination = 0.5, upFraction = 1.0)))
    }

    // --- the planets ---

    @Test
    fun `says how high a planet gets and which way to look`() {
        val jupiter = planet(altitude = 46.4, azimuth = 178.0, magnitude = -2.46, peakAt = at(1))

        assertEquals(
            "Highest 46 degrees south at 01:00, magnitude -2.5.",
            describePlanet(jupiter),
        )
    }

    /** The pattern it is sitting in, which the card above says how to find. */
    @Test
    fun `names the constellation a planet is sitting in`() {
        val jupiter = planet(
            altitude = 46.4,
            azimuth = 178.0,
            magnitude = -2.46,
            peakAt = at(1),
            constellation = "Taurus",
        )

        assertEquals(
            "Highest 46 degrees south at 01:00, magnitude -2.5, in Taurus.",
            describePlanet(jupiter),
        )
    }

    /** The same shape as a satellite pass, because it answers the same question. */
    @Test
    fun `adds a planet's crossings where they fall inside the night`() {
        val venus = planet(
            altitude = 12.1,
            azimuth = 150.0,
            magnitude = -4.49,
            peakAt = at(6),
            risesAt = at(5),
        )

        assertEquals(
            "Highest 12 degrees south-east at 06:00, magnitude -4.5. Rises 05:00.",
            describePlanet(venus),
        )
    }

    // --- the constellations ---

    @Test
    fun `says how high a constellation gets and how to pick it out`() {
        assertEquals(
            "Highest 39 degrees south at 23:00. Three bright stars in a short " +
                "straight line make the belt, with orange Betelgeuse up-left of it " +
                "and blue-white Rigel down-right.",
            describeConstellation(pattern("Orion", altitude = 39.0, peakAt = at(23))),
        )
    }

    /** The reason to go out tonight rather than another night comes first. */
    @Test
    fun `leads with the night a constellation is at its best`() {
        val line = describeConstellation(
            pattern("Orion", altitude = 39.0, peakAt = at(23), isAtItsBest = true),
        )

        assertTrue(line, line.startsWith("At its best tonight, highest all year."))
    }

    /** A pattern that never sets belongs to no season, and says so. */
    @Test
    fun `says when a constellation never sets`() {
        val line = describeConstellation(
            pattern("Ursa Major", altitude = 84.0, peakAt = at(1), isCircumpolar = true),
        )

        assertTrue(line, line.contains("Never sets from here."))
    }

    /** No magnitude: a constellation does not have one. */
    @Test
    fun `never quotes a magnitude for a constellation`() {
        val line = describeConstellation(pattern("Orion", altitude = 39.0, peakAt = at(23)))

        assertTrue(line, !line.contains("magnitude"))
    }

    private fun pattern(
        name: String,
        altitude: Double,
        peakAt: Long,
        isCircumpolar: Boolean = false,
        isAtItsBest: Boolean = false,
    ) = ConstellationTonight(
        constellation = Constellations.WATCHED.first { it.name == name },
        peakAltitude = altitude,
        peakAt = peakAt,
        peakAzimuth = 180.0,
        isCircumpolar = isCircumpolar,
        isAtItsBest = isAtItsBest,
    )

    private fun planet(
        altitude: Double,
        azimuth: Double,
        magnitude: Double,
        peakAt: Long,
        risesAt: Long? = null,
        setsAt: Long? = null,
        constellation: String? = null,
    ) = PlanetTonight(
        body = SkyBody.Jupiter,
        peakAltitude = altitude,
        peakAt = peakAt,
        peakAzimuth = azimuth,
        magnitude = magnitude,
        risesAt = risesAt,
        setsAt = setsAt,
        constellation = constellation,
    )

    private fun moon(
        illumination: Double,
        upFraction: Double,
        risesAt: Long? = null,
        setsAt: Long? = null,
    ) = MoonTonight(
        phaseDegrees = 180.0,
        illumination = illumination,
        upFraction = upFraction,
        interference = illumination * upFraction,
        risesAt = risesAt,
        setsAt = setsAt,
    )
}
