package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlookStoreTest {

    private fun store(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        PreferencesOutlookStore(prefs)

    @Test
    fun `holds nothing until one is written`() {
        assertNull(store().read())
    }

    @Test
    fun `returns the outlook exactly as it was written`() {
        val prefs = FakeSharedPreferences()
        val held = HeldOutlook("Clear all night. Look for the ISS at 22:14.", 1_000L, 2_000L, "56,357", 900L)

        store(prefs).write(held)

        assertEquals(held, store(prefs).read())
    }

    @Test
    fun `replaces the outlook before it rather than adding to it`() {
        val store = store()
        store.write(HeldOutlook("first", 1L, 2L, "56,357", 1L))
        val second = HeldOutlook("second", 3L, 4L, "56,357", 3L)

        store.write(second)

        assertEquals(second, store.read())
    }

    @Test
    fun `keeps an outlook written before any check as one with no check`() {
        val prefs = FakeSharedPreferences()
        val held = HeldOutlook("Dark from 18:45, with nothing special on.", 1_000L, 2_000L, "56,357", null)

        store(prefs).write(held)

        assertEquals(held, store(prefs).read())
    }

    /**
     * An outlook left by a build that did not record the check it was
     * written against cannot be told apart from one written against an old
     * check, so it is not held at all rather than quoted.
     */
    @Test
    fun `holds nothing from an outlook stored without the check it was written against`() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("outlook_headline", "Aurora likely now: 62% here. Look north, low down.")
            .putLong("outlook_written_at", 1_000L)
            .putLong("outlook_valid_until", 2_000L)
            .putString("outlook_place", "56,357")
            .apply()

        assertNull(store(prefs).read())
    }

    @Test
    fun `clearing takes the outlook away and leaves the rest of the file alone`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putLong("last_poll_at", 42L).apply()
        store(prefs).write(HeldOutlook("first", 1L, 2L, "56,357", 1L))

        store(prefs).clear()

        assertNull(store(prefs).read())
        assertEquals(42L, prefs.getLong("last_poll_at", 0L))
        assertEquals(setOf("last_poll_at"), prefs.all.keys)
    }

    @Test
    fun `names the place by its grid cell and never the precise position`() {
        val place = HeldOutlook.placeOf(UserSettings(latitude = 55.95, longitude = -3.19))

        assertEquals("56,357", place)
        assertNull(HeldOutlook.placeOf(UserSettings()))
    }
}
