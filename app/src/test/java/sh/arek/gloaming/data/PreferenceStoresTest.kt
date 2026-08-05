package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoresTest {

    private fun settingsStore() = PreferencesSettingsStore(FakeSharedPreferences())

    /**
     * The position must come back exactly as typed.
     *
     * This is the regression: saving 55.95 and reopening the app showed 56.0,
     * because the coarse mirror was landing in the same store as the precise
     * value and overwriting it.
     */
    @Test
    fun `returns the position exactly as it was written`() {
        val store = settingsStore()

        store.write(UserSettings(latitude = 55.95, longitude = -3.19))

        val read = store.read()
        assertEquals(55.95, read.latitude!!, 0.0)
        assertEquals(-3.19, read.longitude!!, 0.0)
    }

    @Test
    fun `survives values that do not round to anything tidy`() {
        val store = settingsStore()

        store.write(UserSettings(latitude = -42.8794, longitude = 147.3294))

        assertEquals(-42.8794, store.read().latitude!!, 0.0)
        assertEquals(147.3294, store.read().longitude!!, 0.0)
    }

    @Test
    fun `round-trips every setting`() {
        val store = settingsStore()
        val settings = UserSettings(
            latitude = 69.65,
            longitude = 18.96,
            threshold = 70,
            alertsEnabled = false,
            pollIntervalMinutes = 30,
            alertsBeforeUnlock = true,
        )

        store.write(settings)

        assertEquals(settings, store.read())
    }

    @Test
    fun `reads sensible defaults from an empty store`() {
        val settings = settingsStore().read()

        assertNull(settings.latitude)
        assertNull(settings.longitude)
        assertEquals(UserSettings.DEFAULT_THRESHOLD, settings.threshold)
        assertTrue(settings.alertsEnabled)
        assertEquals(false, settings.alertsBeforeUnlock)
    }

    @Test
    fun `clearing a location removes it rather than storing a zero`() {
        val store = settingsStore()
        store.write(UserSettings(latitude = 55.95, longitude = -3.19))

        store.write(UserSettings(latitude = null, longitude = null))

        assertNull(store.read().latitude)
        assertNull(store.read().longitude)
    }

    @Test
    fun `round-trips alert state`() {
        val store = PreferencesAlertStateStore(FakeSharedPreferences())
        val state = AlertState(
            lastAlertAt = 1_700_000_000_000,
            lastAlertProbability = 62,
            armed = false,
            lastPollAt = 1_700_000_100_000,
            lastProbability = 41,
            lastError = "offline",
        )

        store.write(state)

        assertEquals(state, store.read())
    }

    @Test
    fun `alert state defaults to armed with nothing recorded`() {
        val state = PreferencesAlertStateStore(FakeSharedPreferences()).read()

        assertTrue(state.armed)
        assertNull(state.lastAlertAt)
        assertNull(state.lastPollAt)
        assertNull(state.lastError)
    }

    // The two stores are separate files, and writing the coarse mirror must not
    // touch the precise copy. Conflating them is exactly what went wrong.
    @Test
    fun `the two stores do not share values`() {
        val credential = PreferencesSettingsStore(FakeSharedPreferences())
        val device = PreferencesSettingsStore(FakeSharedPreferences())

        credential.write(UserSettings(latitude = 55.95, longitude = -3.19))
        device.write(UserSettings(latitude = 56.0, longitude = 357.0))

        assertEquals(55.95, credential.read().latitude!!, 0.0)
        assertNotEquals(credential.read().longitude!!, device.read().longitude!!, 0.0)
    }
}

class MirrorSelectionTest {

    private val precise = UserSettings(latitude = 55.95, longitude = -3.19, threshold = 40)

    @Test
    fun `mirrors nothing when before-unlock alerting is off`() {
        assertNull(BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = false)))
    }

    @Test
    fun `mirrors nothing when there is no location to reduce`() {
        assertNull(BootAware.mirrorOf(UserSettings(alertsBeforeUnlock = true)))
    }

    // Only the cell reaches the weaker store. This is the privacy claim the
    // whole option rests on.
    @Test
    fun `mirrors the grid cell rather than the precise position`() {
        val mirror = BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = true))!!

        assertEquals(56.0, mirror.latitude!!, 0.0)
        assertEquals(357.0, mirror.longitude!!, 0.0)
        assertNotEquals(precise.latitude!!, mirror.latitude!!, 0.0)
    }

    @Test
    fun `carries the rest of the settings the poll needs`() {
        val mirror = BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = true))!!

        assertEquals(40, mirror.threshold)
        assertEquals(true, mirror.alertsBeforeUnlock)
    }
}
