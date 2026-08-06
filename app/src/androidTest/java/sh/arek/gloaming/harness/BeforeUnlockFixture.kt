package sh.arek.gloaming.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.PreferencesAlertStateStore
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.devicePreferences

/**
 * Puts the device into the state the before-unlock harness needs, and checks it
 * got there.
 *
 * `tools/bfu-check.sh` reboots the phone and asks whether alerting survives the
 * locked state. That question only means something if before-unlock alerting was
 * actually switched on first, and an empty device-encrypted mirror is a silent
 * failure: the alarm fires, finds nothing to act on, and sleeps again.
 *
 * Written through `BootAware.saveSettings`, the same path the settings screen
 * uses, rather than by editing the preference file. Hand-writing the file would
 * make the harness pass while the code that has to produce that file at runtime
 * went untested - which is the exact bug that made the mirror land in the wrong
 * store to begin with.
 *
 * Excluded from the ordinary instrumented run: it deliberately leaves state
 * behind, which no test should. `build.gradle.kts` filters this package out, and
 * the harness invokes it by name.
 */
@RunWith(AndroidJUnit4::class)
class BeforeUnlockFixture {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun seedBeforeUnlockAlerting() {
        val settings = UserSettings(
            latitude = arguments.getString("latitude")?.toDouble() ?: DEFAULT_LATITUDE,
            longitude = arguments.getString("longitude")?.toDouble() ?: DEFAULT_LONGITUDE,
            threshold = arguments.getString("threshold")?.toInt() ?: UserSettings.DEFAULT_THRESHOLD,
            alertsEnabled = true,
            alertsBeforeUnlock = true,
            pollIntervalMinutes = arguments.getString("interval")?.toInt() ?: DEFAULT_INTERVAL,
        )

        BootAware.saveSettings(context, settings)

        // Alert state is reset so a run starts armed with no rate limit behind
        // it. Otherwise a previous run's alert silently suppresses this one and
        // the harness reports a failure the app did not have.
        PreferencesAlertStateStore(devicePreferences(context)).write(AlertState(armed = true))

        // `write` uses `apply`, which reaches disk on a background thread. The
        // harness reboots the device seconds later, so force the map out
        // synchronously rather than race it. An empty commit flushes everything
        // already applied.
        assertTrue("could not flush the mirror to disk", devicePreferences(context).edit().commit())

        // Assert against what a locked wake will actually read, not against what
        // was just written in memory.
        assertTrue(
            "before-unlock alerting did not survive the write",
            BootAware.beforeUnlockEnabled(context),
        )
        val forPolling = BootAware.pollSettings(context).read()
        assertNotNull("the poll has no location to work from", forPolling.cell)
        assertEquals(true, forPolling.alertsEnabled)
    }

    private companion object {
        // Edinburgh, and far enough north to be a plausible aurora location.
        const val DEFAULT_LATITUDE = 55.95
        const val DEFAULT_LONGITUDE = -3.19
        const val DEFAULT_INTERVAL = 15
    }
}
