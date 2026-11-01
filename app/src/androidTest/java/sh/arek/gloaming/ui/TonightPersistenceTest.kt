package sh.arek.gloaming.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.work.RealSchedule

/**
 * That Tonight outlives the activity: a rotation neither blanks it nor works
 * it out again, a rotation in the middle of a run neither restarts it nor
 * loses it, and Check now works it out again from scratch.
 *
 * The real activity against the real stores, because what is under test is
 * the activity going away and coming back. The position is typed in for the
 * run - the phone's own, where it has one - so a fresh fix arriving on a
 * recreation cannot move the keys under the counts; the settings are put back
 * afterwards and never cleared. Check now is a real check, as pressing it is.
 *
 * Slow by nature: a night takes the better part of ten seconds to work out on
 * the phone, after a calendar that takes as long. **These need the device
 * unlocked**, for the reason recorded in [ScreensTest].
 */
class TonightPersistenceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val results = Graph.screenResults(context)
    private lateinit var original: UserSettings

    @Before
    fun typeThePositionIn() {
        original = Graph.settings(context).read()
        BootAware.saveSettings(
            context,
            original.copy(
                latitude = original.latitude ?: 55.95,
                longitude = original.longitude ?: -3.19,
                locationMode = LocationMode.Manual,
                setupDone = true,
            ),
        )
    }

    @After
    fun putTheSettingsBack() {
        BootAware.saveSettings(context, original)
        // The activity arms the poll at whatever the settings said, and Check
        // now re-arms it from its plan; both go back to the phone's own.
        RealSchedule.restore(context)
    }

    private fun headline(): SemanticsNodeInteraction = compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG)

    private fun headlineText(): String =
        headline().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    /** Until a night is up and nothing is being worked out. */
    private fun awaitTheNight() {
        compose.waitUntil(NIGHT_TIMEOUT_MILLIS) {
            !results.tonightUpdating &&
                results.outlook != null &&
                compose.onAllNodesWithTag(OUTLOOK_HEADLINE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Tonight is worked out again every [TONIGHT_STALE_MILLIS] by design, so a
     * count taken across that boundary would see one run more than the test
     * caused. When one is near, waits until the screen's minute tick has
     * crossed it and the run that starts has finished.
     */
    private fun clearOfTheStaleBoundary() {
        val now = System.currentTimeMillis()
        val untilBoundary = TONIGHT_STALE_MILLIS - now % TONIGHT_STALE_MILLIS
        if (untilBoundary > BOUNDARY_MARGIN_MILLIS) return
        val ticked = now + untilBoundary + TICK_MARGIN_MILLIS
        compose.waitUntil(untilBoundary + TICK_MARGIN_MILLIS + 5_000L) {
            System.currentTimeMillis() > ticked
        }
        awaitTheNight()
    }

    @Test
    fun aRotationKeepsTheNightAndDoesNotWorkItOutAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTheNight()
            clearOfTheStaleBoundary()
            val sentence = headlineText()
            val runs = results.tonightRuns

            scenario.recreate()

            headline().assertTextEquals(sentence)
            assertEquals(runs, results.tonightRuns)
        }
    }

    @Test
    fun aRotationInTheMiddleOfARunEndsWithExactlyOneRun() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitTheNight()
            clearOfTheStaleBoundary()
            val runs = results.tonightRuns

            scenario.onActivity { results.recalculate() }
            // In the work itself, past the wait for the calendar.
            compose.waitUntil(NIGHT_TIMEOUT_MILLIS) { results.tonightRuns == runs + 1 }
            assertTrue(results.tonightUpdating)
            scenario.recreate()
            awaitTheNight()

            assertEquals(runs + 1, results.tonightRuns)
        }
    }

    @Test
    fun checkNowSaysItIsUpdatingAndWorksTheNightOutAgain() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheNight()
            clearOfTheStaleBoundary()
            val runs = results.tonightRuns

            compose.onNodeWithTag(STATUS_LINE_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(CHECK_NOW_TAG).performClick()
            compose.waitUntil(CHECK_TIMEOUT_MILLIS) {
                compose.onAllNodesWithText(TONIGHT_UPDATING).fetchSemanticsNodes().isNotEmpty()
            }
            awaitTheNight()

            assertEquals(runs + 1, results.tonightRuns)
        }
    }

    private companion object {
        /** A calendar and then a night, each the better part of ten seconds, with room. */
        const val NIGHT_TIMEOUT_MILLIS = 120_000L

        /** A real check runs the pollers, the pass sweep included, before the night. */
        const val CHECK_TIMEOUT_MILLIS = 180_000L

        /** More than a test's count takes, Check now included, so one begun clear of it ends clear. */
        const val BOUNDARY_MARGIN_MILLIS = 150_000L

        /** The screen reads its clock once a minute; this is past the tick that notices. */
        const val TICK_MARGIN_MILLIS = 65_000L
    }
}
