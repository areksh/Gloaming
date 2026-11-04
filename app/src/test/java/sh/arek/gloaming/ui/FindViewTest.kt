package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.domain.TargetFix

/** Which way Find's edge arrow points. Up the screen is negative y. */
class FindViewTest {

    private val perDegree = 5f
    private val inView = 136f

    private fun up(position: AltAz) = TargetFix("Vega", position, null, null, "")

    @Test
    fun `shows no arrow while the target is in view`() {
        assertNull(edgeArrowDirection(AltAz(30.0, 180.0), up(AltAz(35.0, 185.0)), perDegree, inView))
    }

    /**
     * Looking nearly straight up, a star low on the far side is reached by
     * tipping further back, which is up the screen - not down, which is what
     * the height difference alone would say.
     */
    @Test
    fun `points over the zenith to a star on the far side`() {
        val (dx, dy) = edgeArrowDirection(AltAz(88.0, 0.0), up(AltAz(40.0, 180.0)), perDegree, inView)!!

        assertTrue("dy $dy", dy < 0f)
        assertEquals(0f, dx / dy, 1e-6f)
    }

    /** Off to one side but in front, the arrow points where the ring will come in. */
    @Test
    fun `points the way the ring will come in`() {
        val (dx, dy) = edgeArrowDirection(AltAz(20.0, 0.0), up(AltAz(20.0, 60.0)), perDegree, inView)!!

        assertTrue("dx $dx", dx > 0f)
        assertTrue("dy $dy", dy < 0f)
    }

    /** Low and behind, the way is to turn round, not to tip over the top. */
    @Test
    fun `turns round for a low star behind`() {
        val (dx, dy) = edgeArrowDirection(AltAz(10.0, 0.0), up(AltAz(10.0, 170.0)), perDegree, inView)!!

        assertTrue("dx $dx dy $dy", dx > 0f && kotlin.math.abs(dx) > kotlin.math.abs(dy))
    }

    @Test
    fun `points down to the rise point when already facing it`() {
        val fix = TargetFix("Vega", null, 1L, 40.0, "")

        assertEquals(0f to 1f, edgeArrowDirection(AltAz(0.0, 40.0), fix, perDegree, inView))
    }
}
