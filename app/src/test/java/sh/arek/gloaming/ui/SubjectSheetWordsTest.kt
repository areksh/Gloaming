package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * What a subject's sheet says: its heading, the same one its timeline row
 * has, and for a pass the whole of it rather than the one line a row has
 * room for.
 */
class SubjectSheetWordsTest {

    private val rise = 1_793_644_084_410L
    private val minute = 60_000L

    private val ariane = SatellitePass(
        catalogue = 28499,
        name = "ARIANE 5 R/B",
        channel = PassChannel.Bright,
        risesAt = rise,
        riseAzimuth = 13.3,
        peaksAt = rise + 6 * minute,
        peakElevation = 75.4,
        peakAzimuth = 287.8,
        setsAt = rise + 13 * minute,
        setAzimuth = 202.0,
        rangeAtPeakKm = 697.0,
        sunlitAtPeak = true,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    @Test
    fun `says when and where a pass rises, peaks and sets`() {
        val lines = describePassDetail(ariane)

        assertTrue(lines.toString(), "Rises north at ${clock(ariane.risesAt)}." in lines)
        assertTrue(lines.toString(), "Highest at ${clock(ariane.peaksAt)}, 75 degrees up in the west." in lines)
        assertTrue(lines.toString(), "Sets south at ${clock(ariane.setsAt)}." in lines)
        assertTrue(lines.toString(), "A bright satellite or rocket body." in lines)
    }

    @Test
    fun `says when a pass goes into the earth's shadow`() {
        val fades = ariane.copy(entersShadowAt = rise + 10 * minute)

        assertTrue("Goes into the earth's shadow at ${clock(rise + 10 * minute)}, and fades out there." in describePassDetail(fades))
    }

    @Test
    fun `says when a pass comes out of the earth's shadow`() {
        val late = ariane.copy(sunlitAtPeak = false, leavesShadowAt = rise + 8 * minute)

        assertTrue("Comes out of the earth's shadow at ${clock(rise + 8 * minute)}." in describePassDetail(late))
    }

    @Test
    fun `says a pass in shadow the whole way will not be seen`() {
        val dark = ariane.copy(sunlitAtPeak = false)

        assertTrue("In the earth's shadow the whole way, so it will not be seen." in describePassDetail(dark))
    }

    @Test
    fun `names the space station`() {
        assertTrue("The space station." in describePassDetail(ariane.copy(channel = PassChannel.Iss, name = "ISS (ZARYA)")))
    }

    @Test
    fun `heads a pass's sheet the way its row is headed`() {
        assertEquals(
            SheetHeading(clock(ariane.peaksAt), "ARIANE 5 R/B, 75 degrees high", "Rises north, sets south"),
            heading(RowSubject.Pass(ariane)),
        )
    }

    @Test
    fun `heads a pattern's sheet with its name and when it is highest`() {
        val orion = ConstellationTonight(
            Constellations.WATCHED.first { it.name == "Orion" }, 30.0, rise, 180.0, false, false,
        )

        assertEquals(SheetHeading(clock(rise), "Orion", null), heading(RowSubject.Constellation(orion)))
    }

    @Test
    fun `has no heading of its own for the twilight and the clear spell`() {
        assertNull(heading(RowSubject.Twilight))
        assertNull(heading(RowSubject.Clear))
    }
}
