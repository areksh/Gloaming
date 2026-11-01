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
        val held = HeldOutlook("Clear all night. Look for the ISS at 22:14.", 1_000L, 2_000L)

        store(prefs).write(held)

        assertEquals(held, store(prefs).read())
    }

    @Test
    fun `replaces the outlook before it rather than adding to it`() {
        val store = store()
        store.write(HeldOutlook("first", 1L, 2L))
        val second = HeldOutlook("second", 3L, 4L)

        store.write(second)

        assertEquals(second, store.read())
    }
}
