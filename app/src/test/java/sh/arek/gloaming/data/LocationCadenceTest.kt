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
    fun `manual is never due`() {
        assertFalse(LocationCadence.Manual.isDue(null, now))
        assertFalse(LocationCadence.Manual.isDue(now - 1000 * hour, now))
    }

    @Test
    fun `only intervals run in the background`() {
        assertTrue(LocationCadence.Every(1).runsInBackground)
        assertFalse(LocationCadence.OnOpen.runsInBackground)
        assertFalse(LocationCadence.Manual.runsInBackground)
    }

    @Test
    fun `custom hours are whole numbers from one to forty-eight`() {
        assertEquals(1, LocationCadence.parseHours("1"))
        assertEquals(48, LocationCadence.parseHours("48"))
        assertNull(LocationCadence.parseHours("0"))
        assertNull(LocationCadence.parseHours("49"))
        assertNull(LocationCadence.parseHours("1.5"))
        assertNull(LocationCadence.parseHours(""))
    }

    @Test
    fun `the presets run from each open to manual`() {
        assertEquals(
            listOf(
                LocationCadence.OnOpen,
                LocationCadence.Every(1),
                LocationCadence.Every(6),
                LocationCadence.Every(24),
                LocationCadence.Manual,
            ),
            LocationCadence.PRESETS,
        )
    }
}
