package sh.arek.gloaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.SkyBody

/** What Everything mode has in view and under the reticle. */
class EverythingViewTest {

    private val pointing = AltAz(30.0, 180.0)

    private fun at(altitude: Double, azimuth: Double) = TargetFix("x", AltAz(altitude, azimuth), null, null, "")

    private val a = FindTarget.Planet(SkyBody.Jupiter)
    private val b = FindTarget.Planet(SkyBody.Saturn)
    private val c = FindTarget.Moon

    @Test
    fun `names what is in view nearest first`() {
        val items = listOf(a to at(32.0, 180.0), b to at(30.0, 200.0), c to at(30.0, 170.0))

        assertEquals(listOf(a, c, b), EverythingView.inView(pointing, items, 30.0))
    }

    /** 29.9 degrees off on the sky is drawn just past a 30 degree circle: the line must agree with the drawing. */
    @Test
    fun `leaves out what is drawn outside the circle`() {
        val items = listOf(a to at(59.9, 180.0), b to at(58.0, 180.0))

        assertEquals(listOf(b), EverythingView.inView(pointing, items, 30.0))
    }

    @Test
    fun `leaves out what is behind`() {
        assertEquals(emptyList<FindTarget>(), EverythingView.inView(pointing, listOf(a to at(30.0, 0.0)), 30.0))
    }

    @Test
    fun `leaves out a target with no position`() {
        val items = listOf(a to TargetFix("x", null, 1L, 90.0, ""))

        assertEquals(emptyList<FindTarget>(), EverythingView.inView(pointing, items, 30.0))
    }

    @Test
    fun `puts the nearer of two under the reticle`() {
        val items = listOf(a to at(30.0, 183.0), b to at(30.0, 181.5))

        assertEquals(b, EverythingView.underReticle(pointing, items))
    }

    @Test
    fun `puts nothing under the reticle beyond the aligned distance`() {
        assertNull(EverythingView.underReticle(pointing, listOf(a to at(30.0, 186.0))))
    }
}
