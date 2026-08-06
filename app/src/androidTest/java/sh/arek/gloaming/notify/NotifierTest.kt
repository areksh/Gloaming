package sh.arek.gloaming.notify

import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun postingCreatesTheAuroraChannel() {
        AndroidAuroraNotifier(context).notifyAurora(ovation(72), status, clear)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(AndroidAuroraNotifier.CHANNEL_AURORA)
            assertNotNull("aurora channel was not created", channel)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        }
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
    }
}
