package sh.arek.gloaming.work

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That storm mode can actually start on a device.
 *
 * Everything about this service fails at runtime rather than at compile time,
 * and only during an aurora large enough to stand outside for - which is not a
 * condition anybody can reproduce on demand, and is the worst possible moment
 * to discover a problem. Background foreground-service starts are restricted
 * from Android 12, `startForeground` needs a type argument that varies by API
 * level, and `specialUse` is rejected outright without its manifest property.
 *
 * So it is started here for real, and stopped again immediately.
 */
@RunWith(AndroidJUnit4::class)
class StormServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The notification permission is a precondition of what is being tested,
     * not part of it. A fresh install has never been asked for it, and without
     * it a foreground service still runs but its notification is never posted.
     */
    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun stopService() {
        context.stopService(Intent(context, StormService::class.java))
        waitUntil { !StormService.running }
    }

    /**
     * Polls for a condition rather than sleeping on a guess: the service starts
     * on its own thread and there is no callback to wait on.
     */
    private fun waitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun startsInTheForegroundAndSaysItIsRunning() {
        StormService.apply(context, wanted = true)

        assertTrue("storm mode never started", waitUntil { StormService.running })
    }

    /**
     * The persistent notification is the whole justification for a foreground
     * service, and a `specialUse` service that posts none is killed by the
     * system within seconds.
     */
    @Test
    fun postsItsOngoingNotification() {
        StormService.apply(context, wanted = true)
        waitUntil { StormService.running }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            assertTrue(
                "no ongoing notification was posted",
                waitUntil { manager.activeNotifications.any { it.isOngoing } },
            )
        }
    }

    /** Its own channel, so a live tracker can be silenced without losing alerts. */
    @Test
    fun usesItsOwnLowImportanceChannel() {
        StormService.apply(context, wanted = true)
        waitUntil { StormService.running }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(StormService.CHANNEL_STORM)
            assertNotNull("the live channel was not created", channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
    }

    /**
     * Stopping has to actually stop. This holds a wake lock until `onDestroy`,
     * so a service that lingered would keep the CPU awake - the worst thing
     * this app could do to a phone.
     */
    @Test
    fun stopsWhenAsked() {
        StormService.apply(context, wanted = true)
        waitUntil { StormService.running }

        StormService.apply(context, wanted = false)

        assertTrue("storm mode never stopped", waitUntil { !StormService.running })
    }

    // Asking for what is already true must not stack services or notifications.
    @Test
    fun startingTwiceIsHarmless() {
        StormService.apply(context, wanted = true)
        waitUntil { StormService.running }

        StormService.apply(context, wanted = true)

        assertTrue(StormService.running)
    }

    @Test
    fun stoppingWhenItWasNeverRunningIsHarmless() {
        StormService.apply(context, wanted = false)

        assertTrue(!StormService.running)
    }
}
