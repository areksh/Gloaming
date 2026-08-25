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
            cloudTolerance = 55,
            cloudGateEnabled = false,
            quietHours = QuietHours(23 * 60, 7 * 60),
            quietHoursOverride = true,
            nightVision = true,
        )

        store.write(settings)

        assertEquals(settings, store.read())
    }

    // Off is the default, and it has to survive being written: a null that read
    // back as some stored pair would silence the app on its own initiative.
    @Test
    fun `clearing quiet hours removes them rather than storing midnight`() {
        val store = settingsStore()
        store.write(UserSettings(quietHours = QuietHours(23 * 60, 7 * 60)))

        store.write(UserSettings(quietHours = null))

        assertNull(store.read().quietHours)
    }

    @Test
    fun `round-trips quiet hours that begin at midnight`() {
        val store = settingsStore()

        store.write(UserSettings(quietHours = QuietHours(0, 6 * 60)))

        assertEquals(QuietHours(0, 6 * 60), store.read().quietHours)
    }

    @Test
    fun `reads sensible defaults from an empty store`() {
        val settings = settingsStore().read()

        assertNull(settings.latitude)
        assertNull(settings.longitude)
        assertEquals(UserSettings.DEFAULT_THRESHOLD, settings.threshold)
        assertTrue(settings.alertsEnabled)
        assertEquals(false, settings.alertsBeforeUnlock)
        assertEquals(UserSettings.DEFAULT_CLOUD_TOLERANCE, settings.cloudTolerance)
        assertTrue(settings.cloudGateEnabled)
        assertNull(settings.quietHours)
        assertEquals(false, settings.quietHoursOverride)
        assertEquals(false, settings.nightVision)
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
            lastFromAuroraWatch = true,
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
        assertEquals(-3.0, mirror.longitude!!, 0.0)
        assertNotEquals(precise.latitude!!, mirror.latitude!!, 0.0)
    }

    /**
     * A longitude, not a grid column.
     *
     * The mirror used to store the grid's own 0-359 column, so a user west of
     * Greenwich came back at 357 before first unlock. The aurora lookup wraps
     * and did not care, which is why it went unnoticed - but everything else
     * that takes a longitude does: the cloud forecast rejects 357 outright, and
     * a bounding box would place Edinburgh in Kazakhstan.
     */
    @Test
    fun `mirrors a western longitude as negative, not as a grid column`() {
        val mirror = BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = true))!!

        assertTrue("longitude ${mirror.longitude} is outside -180..180", mirror.longitude!! in -180.0..180.0)
    }

    // Rounding still has to land on the same cell it always did, or a poll
    // before unlock would read a different part of the grid from one after it.
    @Test
    fun `mirrors to the same grid cell as the precise position`() {
        val settings = precise.copy(alertsBeforeUnlock = true)

        val mirror = BootAware.mirrorOf(settings)!!

        assertEquals(settings.cell, mirror.cell)
    }

    @Test
    fun `mirrors an eastern longitude unchanged`() {
        val tromso = UserSettings(latitude = 69.65, longitude = 18.96, alertsBeforeUnlock = true)

        val mirror = BootAware.mirrorOf(tromso)!!

        assertEquals(19.0, mirror.longitude!!, 0.0)
        assertEquals(tromso.cell, mirror.cell)
    }

    @Test
    fun `carries the rest of the settings the poll needs`() {
        val mirror = BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = true))!!

        assertEquals(40, mirror.threshold)
        assertEquals(true, mirror.alertsBeforeUnlock)
    }

    /**
     * Rounding costs the aurora nothing and costs passes a great deal.
     *
     * The grid lookup rounds to whole degrees anyway, so an aurora alert before
     * first unlock is exactly as good either way. A pass prediction is not:
     * a degree is 111 km, which moves rise and set by tens of seconds and the
     * peak by whole degrees of elevation - so "look south-west at 22:14" stops
     * being true. Somebody who wants accurate passes before first unlock can
     * say so, and what they are trading is stated where they say it.
     */
    @Test
    fun `mirrors the precise position when the user has asked for that`() {
        val settings = precise.copy(alertsBeforeUnlock = true, preciseLocationBeforeUnlock = true)

        val mirror = BootAware.mirrorOf(settings)!!

        assertEquals(55.95, mirror.latitude!!, 0.0)
        assertEquals(-3.19, mirror.longitude!!, 0.0)
    }

    /** And it is off unless asked for: the default is still the grid cell. */
    @Test
    fun `rounds by default`() {
        val mirror = BootAware.mirrorOf(precise.copy(alertsBeforeUnlock = true))!!

        assertEquals(56.0, mirror.latitude!!, 0.0)
    }

    /** The option means nothing on its own; before-unlock alerting still gates it. */
    @Test
    fun `mirrors nothing precise when before-unlock alerting is off`() {
        val settings = precise.copy(alertsBeforeUnlock = false, preciseLocationBeforeUnlock = true)

        assertNull(BootAware.mirrorOf(settings))
    }
}
