package sh.arek.gloaming.work

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the scheduling machinery actually works on a device.
 *
 * All of this is Android surface with no logic to unit test, and all of it has
 * exactly one job: make sure the app wakes up overnight. Nothing here proves
 * the alarm fires at the right moment - only the real overnight run does - but
 * it proves each piece the wake depends on is present and usable.
 */
@RunWith(AndroidJUnit4::class)
class SchedulingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun putTheRealScheduleBack() {
        RealSchedule.restore(context)
    }

    /**
     * The crash that took the app down on every single alarm: the receiver
     * acquires a partial wake lock, and the permission had silently gone.
     * Acquiring one here throws SecurityException if it ever goes again.
     */
    @Test
    fun canAcquireThePartialWakeLockThePollNeeds() {
        val power = context.getSystemService(PowerManager::class.java)
        assertNotNull(power)

        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gloaming:test")
        lock.acquire(2_000L)
        try {
            assertTrue("wake lock did not report as held", lock.isHeld)
        } finally {
            lock.release()
        }
        assertFalse(lock.isHeld)
    }

    /**
     * Scheduling must actually register an alarm, not merely not throw.
     *
     * Asked of the alarm manager itself. A PendingIntent is no evidence either
     * way: cancelling an alarm leaves the PendingIntent object alive, so
     * FLAG_NO_CREATE keeps returning one long after the alarm is gone.
     */
    @Test
    fun schedulingRegistersAnAlarmWithTheSystem() {
        PollAlarm.cancel(context)
        assertFalse("an alarm was already pending", hasRegisteredAlarm())

        PollAlarm.schedule(context, intervalMinutes = 15)

        assertTrue("no alarm was registered", hasRegisteredAlarm())
    }

    @Test
    fun cancellingRemovesTheAlarm() {
        PollAlarm.schedule(context, intervalMinutes = 15)
        assertTrue(hasRegisteredAlarm())

        PollAlarm.cancel(context)

        assertFalse("the alarm outlived its cancellation", hasRegisteredAlarm())
    }

    /**
     * The suite must not leave the phone unwatched.
     *
     * These tests cancel and re-arm the app's real alarm, because there is no
     * seam between the app and AlarmManager and inventing one would be testing
     * a fake. What they must not do is finish with the alarm cancelled: a suite
     * run in the evening would then take the phone off watch for the night,
     * silently, with the app installed, exempt and looking entirely healthy.
     * The same rule the preference stores already follow - save and restore,
     * never clear.
     */
    @Test
    fun putsThePhoneSOwnScheduleBack() {
        PollAlarm.cancel(context)
        assertFalse("an alarm survived being cancelled", hasRegisteredAlarm())

        RealSchedule.restore(context)

        val settings = BootAware.pollSettings(context).read()
        assertEquals(
            "the restored schedule does not match what this phone is set to do",
            settings.alertsEnabled,
            hasRegisteredAlarm(),
        )
    }

    /**
     * The alarm targets a receiver that is actually installed. A rename or a
     * dropped manifest entry would otherwise fail silently: the alarm fires
     * into nothing and the app never wakes again.
     */
    @Test
    fun theAlarmTargetsAnInstalledReceiver() {
        val intent = Intent(context, PollReceiver::class.java)

        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertTrue("PollReceiver is not registered", resolved.isNotEmpty())
    }

    @Test
    fun theBootReceiverListensForTheLockedBootBroadcast() {
        // LOCKED_BOOT_COMPLETED is the only boot broadcast delivered if the
        // phone reboots overnight and nobody unlocks it.
        val intent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            .setPackage(context.packageName)

        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertTrue(
            "nothing handles LOCKED_BOOT_COMPLETED",
            resolved.any { it.activityInfo.name == BootReceiver::class.java.name },
        )
    }

    @Test
    fun theBootReceiverListensForItsOwnPackageBeingReplaced() {
        // An update cancels the app's alarms exactly as a reboot does, and this
        // app is installed from stores that update in the background. Without
        // this the schedule dies at the update and stays dead until somebody
        // opens the app - silently, because nothing about the app looks wrong.
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
            .setPackage(context.packageName)

        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertTrue(
            "nothing handles MY_PACKAGE_REPLACED",
            resolved.any { it.activityInfo.name == BootReceiver::class.java.name },
        )
    }

    /**
     * The background location refresh follows the settings it is armed from.
     *
     * Saved the way the settings screen saves - the store, then the alarm - and
     * through the real stores, so the phone's own settings are read first and
     * written back afterwards rather than cleared. The alarm is put back by
     * [RealSchedule] in the teardown, from those restored settings.
     */
    @Test
    fun armsTheLocationAlarmForAnIntervalAndCancelsItWhenTypedIn() {
        val saved = Graph.settings(context).read()
        try {
            val every6 = saved.copy(
                locationMode = LocationMode.Automatic,
                locationCadence = LocationCadence.Every(6),
            )
            BootAware.saveSettings(context, every6)
            LocationAlarm.arm(context, every6)

            assertTrue(
                "no location alarm for every six hours",
                hasRegisteredAlarm(LOCATION_RECEIVER),
            )

            val typed = every6.copy(locationMode = LocationMode.Manual)
            BootAware.saveSettings(context, typed)
            LocationAlarm.arm(context, typed)

            assertFalse(
                "a location alarm outlived typing the position in",
                hasRegisteredAlarm(LOCATION_RECEIVER),
            )
        } finally {
            BootAware.saveSettings(context, saved)
        }
    }

    @Test
    fun theLocationAlarmTargetsAnInstalledReceiver() {
        val intent = Intent(context, LocationReceiver::class.java)

        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertTrue("LocationReceiver is not registered", resolved.isNotEmpty())
    }

    /** Whether the alarm manager currently holds a pending alarm for [receiver]. */
    private fun hasRegisteredAlarm(receiver: String = POLL_RECEIVER): Boolean =
        pendingAlarms().contains("*walarm*:${context.packageName}/$receiver")

    /**
     * The part of the alarm dump that lists alarms actually scheduled.
     *
     * Only the part before the history sections counts. "Removal history"
     * records each cancelled alarm as a snapshot carrying the very same
     * `*walarm*` tag, so searching the whole dump reads the record of a
     * cancellation as proof the alarm is still scheduled.
     *
     * The heading is matched without its colon, and identically to
     * `tools/bfu-check.sh`, which reads the same dump for the same reason.
     * `substringBefore` returns the whole string when its delimiter is absent,
     * so a heading that does not match exactly quietly reinstates the very
     * history this excludes.
     */
    private fun pendingAlarms(): String =
        shell("dumpsys alarm").substringBefore("App Alarm history")

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private companion object {
        const val POLL_RECEIVER = ".work.PollReceiver"
        const val LOCATION_RECEIVER = ".work.LocationReceiver"
    }
}
