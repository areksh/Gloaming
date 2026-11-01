package sh.arek.gloaming.notify

import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import sh.arek.gloaming.aurora.Likelihood
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.SkyStatus

/**
 * That the notification path works on a real device.
 *
 * Channel creation is version-branched and posting can throw when the runtime
 * permission was declined - neither is reachable from a JVM unit test, and both
 * sit directly between the app deciding to alert and the user learning anything.
 */
@RunWith(AndroidJUnit4::class)
class NotifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val status = SkyStatus(
        schema = 1,
        generated = "2026-08-06T00:00:00Z",
        globalActivity = "high",
        maxProbability = 80,
    )

    private val clear = CloudCover(total = 5, low = 0, mid = 0, high = 5)

    private fun ovation(probability: Int) =
        Likelihood(probability, Likelihood.Source.OVATION)

    private fun uk(probability: Int) =
        Likelihood(probability, Likelihood.Source.AURORAWATCH)

    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * Reading a notification back needs it to have actually been posted, which
     * needs the runtime permission a fresh install has never been asked for.
     */
    @Before
    fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        manager.cancelAll()
    }

    @After
    fun clearNotifications() {
        manager.cancelAll()
    }

    /**
     * Posting is asynchronous, so the notification is not necessarily readable
     * back the instant [AndroidAuroraNotifier] returns.
     */
    private fun postedAlert(): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * The whole point of the alert is to get someone outside, and the first
     * thing they do is tap it. Without a content intent the tap does nothing at
     * all: the notification does not even dismiss, because `setAutoCancel` only
     * takes effect when there is somewhere to go.
     */
    @Test
    fun tappingTheAlertOpensTheApp() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear)

        val posted = postedAlert()
        assertNotNull("the alert was never posted", posted)
        assertNotNull(
            "tapping the alert goes nowhere: it has no content intent",
            posted!!.notification.contentIntent,
        )
        assertTrue("tapping the alert should dismiss it", posted.notification.flags and
            android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    /**
     * The two buttons Sec. 15 has been waiting on since the first version.
     *
     * On the notification rather than in the app, because the only moment
     * somebody knows whether an alert was worth it is the moment they come
     * back inside - and no new notification at all: two actions on one that was
     * going out anyway.
     */
    @Test
    fun theAlertAsksWhetherItWasWorthIt() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear, threshold = 30)

        val posted = postedAlert()
        assertNotNull("the alert was never posted", posted)

        val labels = posted!!.notification.actions.orEmpty().map { it.title.toString() }
        assertTrue(labels.toString(), labels.contains("Worth it"))
        assertTrue(labels.toString(), labels.contains("Not worth it"))
    }

    /**
     * Two, not three. Sec. 9.5 argues attention is the scarcest thing the app
     * spends, and a notification with three buttons is one that gets dismissed
     * unread. "Did not look" lives on the Tonight card, where somebody is
     * already looking at a screen.
     */
    @Test
    fun theAlertDoesNotCarryAThirdButton() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear, threshold = 30)

        val posted = postedAlert()
        assertEquals(2, posted!!.notification.actions.orEmpty().size)
    }

    @Test
    fun postingCreatesTheAuroraChannel() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(AndroidAuroraNotifier.CHANNEL_AURORA)
            assertNotNull("aurora channel was not created", channel)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        }
    }

    /**
     * The collapsed text is what someone sees at two in the morning, so it
     * opens with the outlook's own words; the figures that used to be the whole
     * of it move behind the expand.
     */
    @Test
    fun theAlertOpensWithTheOutlookAndKeepsTheFiguresInTheExpandedText() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear, threshold = 30)

        val posted = postedAlert()
        assertNotNull("the alert was never posted", posted)
        val extras = posted!!.notification.extras
        val collapsed = extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        val expanded = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()

        assertTrue(collapsed, collapsed.startsWith("Aurora likely now:"))
        assertTrue(expanded, expanded.startsWith("Aurora likely now:"))
        assertTrue(expanded, expanded.contains("Local probability 72%"))
        assertTrue(expanded, expanded.contains("clear"))
    }

    /**
     * Posting must never throw, whatever the permission state. An alert the
     * user declined is their choice; a crash in a background wake is a bug.
     *
     * Unknown cloud is included because it is the path taken whenever
     * Open-Meteo is unreachable, which is exactly when nobody is watching.
     */
    @Test
    fun postingNeverThrows() {
        val notifier = AndroidAuroraNotifier(context)

        notifier.notifyAurora(ovation(0), status, clear)
        notifier.notifyAurora(ovation(100), status, CloudCover(100, 100, 100, 100))
        notifier.notifyAurora(uk(60), status, null)
        notifier.notifyAurora(
            ovation(55),
            status.copy(aurorawatch = null, kp = null, solarWind = null),
            null,
        )
        // No Kp, no Bz, no cloud and no threshold: every field the verdict
        // record wants is absent, which is the state a first poll after a
        // reboot is in.
        notifier.notifyAurora(
            ovation(55),
            status.copy(aurorawatch = null, kp = null, solarWind = null),
            null,
            threshold = null,
        )
    }
}
