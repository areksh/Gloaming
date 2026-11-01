package sh.arek.gloaming.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.DeviceFix
import sh.arek.gloaming.data.GridCell
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.LocationSource
import sh.arek.gloaming.data.TonightSnapshot
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.LocationGrantSource
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.work.RealSchedule

/**
 * That Tonight outlives the activity: a rotation neither blanks it nor works
 * it out again, a rotation in the middle of a run neither restarts it nor
 * loses it, and Check now works it out again from scratch. A cold start
 * opens on the night kept from last time.
 *
 * The real activity against the real stores, because what is under test is
 * the activity going away and coming back. The position is typed in for the
 * run - the phone's own, where it has one - so a fresh fix arriving on a
 * recreation cannot move the keys under the counts; the settings, and the
 * kept night in its file, are put back afterwards and never cleared. Check now
 * is a real check, as pressing it is.
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
    private val snapshots = Graph.tonightSnapshots(context)
    private var originalSnapshot: TonightSnapshot? = null

    @Before
    fun typeThePositionIn() {
        originalSnapshot = snapshots.read()
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
        Graph.locationGrantsOverride = null
        Graph.locationSourceOverride = null
        originalSnapshot?.let(snapshots::write) ?: snapshots.clear()
        BootAware.saveSettings(context, original)
        // The activity arms the poll at whatever the settings said, and Check
        // now re-arms it from its plan; both go back to the phone's own.
        RealSchedule.restore(context)
    }

    private fun headline(): SemanticsNodeInteraction = compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG)

    private fun headlineText(): String =
        headline().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    /**
     * Until a night is up and nothing is being worked out.
     *
     * Idle first, and the screen read before the holder: the holder lives for
     * the process, so a night left from an earlier test would otherwise
     * satisfy the wait before this activity's effects had asked for anything.
     */
    private fun awaitTheNight() {
        compose.waitForIdle()
        compose.waitUntil(NIGHT_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(OUTLOOK_HEADLINE_TAG).fetchSemanticsNodes().isNotEmpty() &&
                !results.tonightUpdating &&
                results.outlook != null
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

    /**
     * In automatic mode a rotation used to ask the phone for its position
     * again, as every start did; a fix that wandered moved both keys and threw
     * the night and the year away. Leaving and coming back is an open, and
     * still asks. A fake source stands in for the phone's, answering with the
     * position already stored so nothing else moves.
     */
    @Test
    fun aRotationInAutomaticModeDoesNotAskThePhoneAgain() {
        val stored = Graph.settings(context).read()
        BootAware.saveSettings(
            context,
            stored.copy(locationMode = LocationMode.Automatic, locationCadence = LocationCadence.OnOpen),
        )
        Graph.locationGrantsOverride = object : LocationGrantSource {
            override fun location() = true
            override fun background() = false
        }
        val asked = AtomicInteger()
        Graph.locationSourceOverride = object : LocationSource {
            override suspend fun current(): DeviceFix {
                asked.incrementAndGet()
                return DeviceFix(stored.latitude!!, stored.longitude!!, 0f, System.currentTimeMillis())
            }

            override suspend fun fresh(timeoutMillis: Long): DeviceFix = current()
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(ASK_TIMEOUT_MILLIS) { asked.get() == 1 }

            scenario.recreate()
            compose.waitForIdle()
            // Away and back, which is an open, so the count has something
            // later to be measured against.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(ASK_TIMEOUT_MILLIS) { asked.get() >= 2 }
            compose.waitForIdle()

            assertEquals(2, asked.get())
        }
    }

    /**
     * A cold start: the holder is empty, but the last night worked out was
     * kept. Its sentence and "Updating..." are up together before the fresh
     * run, which needs a calendar first and then a night, has landed anything.
     * The snapshot is made for the cell the typed position falls in, and
     * carries a sentence the real run would not give, so seeing it proves it
     * came from the file.
     */
    @Test
    fun aColdStartOpensOnTheSnapshotMarkedAsUpdating() {
        val here = Graph.settings(context).read()
        val now = System.currentTimeMillis()
        val kept = OutlookReport(
            outlook = Outlook.AuroraNow(probability = 99, clearUntil = null, conditional = true),
            excluded = emptyList(),
            auroraProbability = 99,
            threshold = 30,
            poleward = Compass.North,
        )
        val sentence = OutlookWords.headline(kept)!!
        snapshots.write(
            TonightSnapshot.of(
                cell = GridCell.of(here.latitude!!, here.longitude!!),
                writtenAt = now - 60 * 60_000L,
                list = TonightList(now, now + 6 * 60 * 60_000L, emptyList(), emptyList(), emptyList(), emptyList(), null),
                outlook = kept,
                hourly = emptyList(),
                timeline = null,
            ),
        )
        Graph.resetScreenResultsForTest()
        val cold = Graph.screenResults(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(SEED_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag(OUTLOOK_HEADLINE_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            headline().assertTextEquals(sentence)
            compose.onNodeWithTag(TONIGHT_UPDATING_TAG).assertExists()
            compose.onNodeWithText(TONIGHT_UPDATING).assertExists()
            assertTrue(cold.tonightUpdating)
            assertEquals(0, cold.tonightRuns)
        }
    }

    private companion object {
        /** Opening on the snapshot is immediate; this is room for a slow start. */
        const val SEED_TIMEOUT_MILLIS = 15_000L

        /** A calendar and then a night, each the better part of ten seconds, with room. */
        const val NIGHT_TIMEOUT_MILLIS = 120_000L

        /** A real check runs the pollers, the pass sweep included, before the night. */
        const val CHECK_TIMEOUT_MILLIS = 180_000L

        /** More than a test's count takes, Check now included, so one begun clear of it ends clear. */
        const val BOUNDARY_MARGIN_MILLIS = 150_000L

        /** A position asked of a fake source arrives at once; this is room for a slow start. */
        const val ASK_TIMEOUT_MILLIS = 10_000L

        /** The screen reads its clock once a minute; this is past the tick that notices. */
        const val TICK_MARGIN_MILLIS = 65_000L
    }
}
