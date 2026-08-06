package sh.arek.gloaming.work

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
    fun cancelAlarm() {
        PollAlarm.cancel(context)
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
     * "Check now" registers an alarm, for the moment it asked for.
     *
     * The delay is injected rather than left at its production default of a
     * second, because at that default the alarm fires part-way through this
     * test: `PollReceiver` then makes a real network request, and re-arms the
     * schedule in its `finally` long after [cancelAlarm] has run - so the
     * alarm outlives the test and fails whichever one comes next.
     *
     * Asserting the requested delay is what the alarm manager holds is what
     * keeps that injection honest. Without it the parameter could be quietly
     * ignored, the alarm would still register, and the test would still pass
     * while the stray poll came back.
     */
    @Test
    fun checkNowRegistersAnAlarmForTheMomentItAsksFor() {
        PollAlarm.cancel(context)

        PollAlarm.scheduleNow(context, delayMillis = 10 * 60_000L)

        assertTrue("no alarm was registered", hasRegisteredAlarm())
        val requested = requestedTriggerTime()
        assertNotNull("the pending alarm records no requested trigger time", requested)
        assertTrue(
            "the alarm was requested for $requested, not the ten minutes asked for",
            Regex("""^\+(9|10)m""").containsMatchIn(requested!!),
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

    /** Whether the alarm manager currently holds a pending alarm for this app. */
    private fun hasRegisteredAlarm(): Boolean =
        pendingAlarms().contains(ourAlarmTag)

    /**
     * When the pending alarm asked to be woken, relative to now.
     *
     * Reads `origWhen` rather than `whenElapsed`: the latter is the time left
     * after app standby, Doze and battery saver have each had their say, and
     * says nothing about what was requested. Null when no alarm is pending.
     */
    private fun requestedTriggerTime(): String? {
        val ours = pendingAlarms().substringAfter(ourAlarmTag, missingDelimiterValue = "")
        return Regex("""origWhen=(\+\S+)""").find(ours)?.groupValues?.get(1)
    }

    private val ourAlarmTag: String
        get() = "*walarm*:${context.packageName}/.work.PollReceiver"

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
}
