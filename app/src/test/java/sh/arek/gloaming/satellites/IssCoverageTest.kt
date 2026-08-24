package sh.arek.gloaming.satellites

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the station can be seen from where the user is standing.
 *
 * The propagator already answers this correctly by refusing to find passes it
 * cannot find, so nothing here changes what the app predicts. It exists so the
 * app can say *why* the station never appears, which silence cannot.
 *
 * The boundary that matters most is Edinburgh. A threshold drawn even slightly
 * too low would tell a user in the band the app is best at that they get no
 * station passes, which is both wrong and worse than the silence it replaces.
 */
class IssCoverageTest {

    @Test
    fun `covers the latitudes under the ground track`() {
        assertEquals(IssCoverage.Covered, IssCoverage.of(0.0))
        assertEquals(IssCoverage.Covered, IssCoverage.of(51.5))
    }

    @Test
    fun `covers Edinburgh, which gets station passes most weeks`() {
        assertEquals(IssCoverage.Covered, IssCoverage.of(55.95))
    }

    @Test
    fun `calls the few degrees past the ground track marginal`() {
        assertEquals(IssCoverage.Marginal, IssCoverage.of(58.0))
    }

    @Test
    fun `gives Tromso nothing`() {
        assertEquals(IssCoverage.None, IssCoverage.of(69.65))
    }

    @Test
    fun `treats the southern hemisphere identically`() {
        assertEquals(IssCoverage.Covered, IssCoverage.of(-54.8))
        assertEquals(IssCoverage.Marginal, IssCoverage.of(-58.0))
        assertEquals(IssCoverage.None, IssCoverage.of(-69.65))
    }

    @Test
    fun `puts the boundaries in the more generous band`() {
        // Both thresholds are approximations of a fuzzy edge, so each belongs
        // to the band that promises less and disappoints less.
        assertEquals(IssCoverage.Marginal, IssCoverage.of(IssCoverage.MARGINAL_ABOVE_DEGREES))
        assertEquals(IssCoverage.Marginal, IssCoverage.of(IssCoverage.NONE_ABOVE_DEGREES))
        assertEquals(IssCoverage.None, IssCoverage.of(IssCoverage.NONE_ABOVE_DEGREES + 0.1))
    }
}
