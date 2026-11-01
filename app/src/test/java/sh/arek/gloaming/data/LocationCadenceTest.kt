package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCadenceTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `an interval is due once its hours have passed`() {
        val every6 = LocationCadence.Every(6)

        assertFalse(every6.isDue(now - 6 * hour + 60_000, now))
        assertTrue(every6.isDue(now - 6 * hour, now))
    }

    @Test
    fun `never updated is always due`() {
        assertTrue(LocationCadence.Every(24).isDue(null, now))
        assertTrue(LocationCadence.OnOpen.isDue(null, now))
    }

    @Test
    fun `opening the app is always due`() {
        assertTrue(LocationCadence.OnOpen.isDue(now, now))
    }

    @Test
    fun `only intervals run in the background`() {
        assertTrue(LocationCadence.Every(1).runsInBackground)
        assertFalse(LocationCadence.OnOpen.runsInBackground)
    }

    @Test
    fun `custom hours are whole numbers from two to forty-eight`() {
        assertNull(parseCustomHours("1"))
        assertEquals(2, parseCustomHours("2"))
        assertEquals(48, parseCustomHours("48"))
        assertNull(parseCustomHours("49"))
        assertNull(parseCustomHours("0"))
        assertNull(parseCustomHours("1.5"))
        assertNull(parseCustomHours(""))
        assertEquals(3, parseCustomHours(" 3 "))
    }

    @Test
    fun `the presets are off and every hour`() {
        assertEquals(
            listOf(LocationCadence.OnOpen, LocationCadence.Every(1)),
            LocationCadence.PRESETS,
        )
    }
}
