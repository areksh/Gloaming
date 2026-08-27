package sh.arek.gloaming.ui

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
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

    @Test
    fun `names the phases everybody uses`() {
        assertEquals("New moon", describeMoonPhase(0.0))
        assertEquals("Waxing crescent", describeMoonPhase(45.0))
        assertEquals("First quarter", describeMoonPhase(90.0))
        assertEquals("Waxing gibbous", describeMoonPhase(135.0))
        assertEquals("Full moon", describeMoonPhase(180.0))
        assertEquals("Waning gibbous", describeMoonPhase(225.0))
        assertEquals("Last quarter", describeMoonPhase(270.0))
        assertEquals("Waning crescent", describeMoonPhase(315.0))
    }

    /**
     * The moon moves about 12 degrees a day, and nobody calls a moon eleven
     * hours off full anything but full.
     */
    @Test
    fun `gives the named phases a window rather than an instant`() {
        assertEquals("Full moon", describeMoonPhase(176.0))
        assertEquals("Full moon", describeMoonPhase(184.0))
        assertEquals("New moon", describeMoonPhase(357.0))
        assertEquals("New moon", describeMoonPhase(3.0))
    }

    @Test
    fun `reads a phase outside the circle as the one inside it`() {
        assertEquals("Full moon", describeMoonPhase(540.0))
        assertEquals("Full moon", describeMoonPhase(-180.0))
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
