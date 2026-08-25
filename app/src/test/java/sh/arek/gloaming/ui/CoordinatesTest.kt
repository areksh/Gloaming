package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Printing a position back to the user.
 *
 * Written after the location card showed "53.0, 1.3" for a fix at 53.009,
 * -1.287 - the Midlands rendered as the North Sea - because it reused the
 * helper that drops the sign on purpose.
 */
class CoordinatesTest {

    /** The case that was wrong: a western longitude must not read as eastern. */
    @Test
    fun `names the western hemisphere rather than dropping the sign`() {
        assertEquals("53.009 N, 1.287 W", coordinates(53.009009, -1.287449))
    }

    @Test
    fun `names all four quadrants`() {
        assertEquals("55.950 N, 3.190 W", coordinates(55.95, -3.19))
        assertEquals("41.290 S, 174.780 E", coordinates(-41.29, 174.78))
        assertEquals("69.650 N, 18.960 E", coordinates(69.65, 18.96))
        assertEquals("33.870 S, 151.210 E", coordinates(-33.87, 151.21))
    }

    /** Greenwich and the equator are positive, not a special case. */
    @Test
    fun `treats zero as north and east`() {
        assertEquals("0.000 N, 0.000 E", coordinates(0.0, 0.0))
    }

    /** Three places is about a hundred metres, and it does not drift. */
    @Test
    fun `keeps three decimal places`() {
        assertEquals("1.000 N, 2.000 E", coordinates(1.0, 2.0))
        assertEquals("12.346 N, 1.235 W", coordinates(12.34567, -1.23456))
    }

    /** Plain ASCII, like everything else the app writes. */
    @Test
    fun `writes plain ascii`() {
        val text = coordinates(-41.29, 174.78)
        assertEquals(true, text.all { it.code in 32..126 })
    }
}
