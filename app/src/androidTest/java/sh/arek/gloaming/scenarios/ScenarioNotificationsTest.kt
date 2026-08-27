package sh.arek.gloaming.scenarios

import android.app.NotificationManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import sh.arek.gloaming.astronomy.EventChannel
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.notify.AndroidEventNotifier
import sh.arek.gloaming.notify.AndroidPassNotifier
import sh.arek.gloaming.satellites.PassDescription

/**
 * The notification each night in the catalogue would produce, posted to a real
 * device.
 *
 * The whole point of this app is a notification arriving at two in the morning,
 * and the interesting ones - an eclipse, an occultation, a Starlink train - are
 * exactly the ones nobody can wait around for. A JVM test cannot reach any of
 * this: channel creation is version-branched, posting throws when the runtime
 * permission was declined, and the text is assembled inside the builder.
 *
 * One test per night, so a failure names the night. Nights with nothing to
 * announce return early rather than being filtered out of the parameter list,
 * because "this night produced no notification" is itself worth being able to
 * read off the results.
 */
@RunWith(Parameterized::class)
class ScenarioNotificationsTest(private val scenario: Scenario) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun nights(): List<Array<Any>> = Scenarios.all.map { arrayOf<Any>(it) }

        /** Long enough for a post to come back through the system. */
        private const val PATIENCE_MILLIS = 5_000L
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val tonight = Scenarios.tonight(scenario)

    /** Reading one back needs the permission a fresh install has never been asked for. */
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

    /** Posting is asynchronous, so it is not readable back the instant the call returns. */
    private fun posted(): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + PATIENCE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun StatusBarNotification.title(): String =
        notification.extras.getCharSequence("android.title")?.toString().orEmpty()

    private fun StatusBarNotification.body(): String =
        notification.extras.getCharSequence("android.bigText")?.toString()
            ?: notification.extras.getCharSequence("android.text")?.toString().orEmpty()

    /** The first event tonight that has a channel to speak on. */
    private fun announceable(): SkyEvent? =
        tonight.events.firstOrNull { EventChannel.of(it.kind) != null }

    /**
     * An event happening tonight arrives with its own title and the whole of
     * its detail, not a truncated line.
     *
     * The detail is where the caveat lives - "only 7 degrees up, in a sky that
     * is not fully dark" - so a notification that dropped it would be sending
     * somebody out into the garden on a promise the app knows it cannot keep.
     */
    @Test
    fun announcesTonightsEvent() {
        val event = announceable() ?: return

        AndroidEventNotifier(context).notifyEvent(event, EventPlanner.Phase.Tonight)

        val notification = posted()
        assertNotNull("${scenario.name}: ${event.title} posted nothing", notification)
        assertTrue(
            "${notification!!.title()} does not name ${event.title}",
            notification.title().startsWith(event.title),
        )
        assertTrue(
            "the detail was not carried through in full",
            notification.body().contains(event.detail),
        )
    }

    /**
     * And the advance notice a day or two earlier, which is a different string
     * built from the same event - the phase decides whether the title carries a
     * time tonight or the name of a day.
     */
    @Test
    fun announcesAnEventInAdvance() {
        val event = announceable() ?: return

        AndroidEventNotifier(context).notifyEvent(event, EventPlanner.Phase.HeadsUp)

        val notification = posted()
        assertNotNull("${scenario.name}: ${event.title} posted nothing", notification)
        assertTrue(
            "${notification!!.title()} does not name ${event.title}",
            notification.title().startsWith(event.title),
        )
    }

    /**
     * A comet, which reaches the same notifier by a channel of its own.
     */
    @Test
    fun announcesACometUpTonight() {
        val comet = tonight.comets.firstOrNull() ?: return

        AndroidEventNotifier(context).notifyEvent(comet, EventPlanner.Phase.Tonight)

        val notification = posted()
        assertNotNull("${scenario.name}: ${comet.title} posted nothing", notification)
        assertTrue(
            "${notification!!.title()} does not name ${comet.title}",
            notification.title().startsWith(comet.title),
        )
    }

    /**
     * A pass, described as somebody standing outside needs it: when, which way,
     * how high, and whether it goes out part-way across.
     *
     * The best of the night rather than the first, because that is the one the
     * app would actually interrupt for.
     */
    @Test
    fun announcesTheBestPassOfTheNight() {
        val pass = tonight.bestPasses.firstOrNull() ?: return
        val latitude = scenario.latitude ?: return
        val longitude = scenario.longitude ?: return

        val description = PassDescription.of(
            pass = pass,
            observerIsDark = Twilight.isDark(latitude, longitude, pass.peaksAt),
        )
        AndroidPassNotifier(context).notifyPass(pass, description)

        val notification = posted()
        assertNotNull("${scenario.name}: ${pass.name} posted nothing", notification)
        assertTrue(
            "${notification!!.body()} does not say which way to look",
            notification.body().contains(description.risesTowards.label),
        )
    }
}
