package sh.arek.gloaming.scenarios

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.ui.ALSO_UP_TAG
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.NOT_WORTH_TAG
import sh.arek.gloaming.ui.ROW_SHEET_TAG
import sh.arek.gloaming.ui.RowSubject
import sh.arek.gloaming.ui.NIGHT_STRIP_TAG
import sh.arek.gloaming.ui.Timeline
import sh.arek.gloaming.ui.TimelineRow
import sh.arek.gloaming.ui.TonightSections
import sh.arek.gloaming.ui.disclosureTag
import sh.arek.gloaming.ui.describeMissingElements
import sh.arek.gloaming.ui.OUTLOOK_HEADLINE_TAG
import sh.arek.gloaming.ui.OutlookWords
import sh.arek.gloaming.ui.TonightScreen
import sh.arek.gloaming.ui.WATCH_BUTTON_TAG
import sh.arek.gloaming.ui.timelineRowTag

/**
 * Every night in the catalogue, drawn on a real device.
 *
 * The JVM suite proves the app has something to say about each of these nights.
 * This proves it can say it: that the timeline and the lists under it render,
 * and carry the things the night was given rather than an empty sky.
 *
 * One test per night, through JUnit's parameterised runner, so a failure names
 * the night rather than "the scenarios test". The catalogue is the parameter
 * list, so adding a night here costs nothing and never gets forgotten.
 *
 * **These need the device unlocked**, like every other Compose test here: an
 * activity launched behind the keyguard resumes and pauses again without ever
 * drawing, and every assertion fails with "No compose hierarchies found in the
 * app", which reads like a broken setup and is not.
 */
@RunWith(Parameterized::class)
class ScenarioScreensTest(private val scenario: Scenario) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun nights(): List<Array<Any>> = Scenarios.all.map { arrayOf<Any>(it) }
    }

    @get:Rule
    val compose = createComposeRule()

    /**
     * The astronomy is worked out before the composition rather than inside it.
     *
     * A night of it is tenths of a second even on a desktop, and the app itself
     * keeps it out of the composition for exactly that reason - so a test that
     * did it in `setContent` would be measuring something the app never does.
     */
    private val state: MainUiState = Scenarios.uiState(scenario)

    private val tonight: TonightList = requireNotNull(state.tonight)

    /** The whole of Tonight as the app draws it, pinned watch button included. */
    /**
     * The screen, with both folded lists opened: what this suite looks for is
     * whether the night is said at all, and a folded list still says it.
     */
    private fun render() {
        compose.setContent { TonightScreen(state = state) }
        for (tag in listOf(ALSO_UP_TAG, NOT_WORTH_TAG)) {
            if (compose.onAllNodesWithTag(disclosureTag(tag)).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(disclosureTag(tag)).performScrollTo().performClick()
                compose.waitForIdle()
            }
        }
    }

    /**
     * Matched whole rather than as a substring, which is not fussiness: the
     * moon is listed as "The moon" and one of the events in the catalogue is
     * called "The moon passes Mars", so a substring match finds two nodes and
     * fails on a screen that is perfectly correct.
     */
    private fun shown(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun absent(text: String) {
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    /**
     * Somewhere on the screen, as part of whatever says it: a pass's name
     * inside its row's title, an event's title with the time it was left out
     * at. The first of them is enough.
     */
    private fun somewhere(text: String) {
        compose.onAllNodesWithText(text, substring = true).onFirst().performScrollTo().assertIsDisplayed()
    }

    /**
     * What is said for the satellites on a night with no passes to list, which
     * is two quite different sentences.
     *
     * Most nights in the catalogue hold no orbital elements at all, and that is
     * the fixtures being honest rather than thin: SGP4 is a near-epoch model, so
     * an eclipse dated 2029 cannot carry a real element set and is given none.
     * The screen is then right to say it does not know, rather than that the sky
     * is empty - which is the distinction it was taught, and exactly what this
     * suite should hold it to.
     *
     * Derived from the screen's own wording rather than written out again here,
     * so that the assertion cannot drift from the screen while still passing.
     */
    private fun emptySatellitesSentence(): String =
        if (tonight.elementsHeld) {
            "Nothing crossing tonight that clears the horizon in sunlight."
        } else {
            describeMissingElements(
                everChecked = state.alertState.lastPollAt != null,
                failure = state.elementsFailure,
            ).text
        }

    /**
     * Something of the night's own on the screen for everything it declares
     * it shows, wherever the new screen puts it: a row on the timeline, an
     * entry under "Also up tonight", or an entry under "Not worth it tonight"
     * with the reason it was left out.
     *
     * Asserted from the scenario's own declaration rather than from what the
     * screen happens to hold, so a night that claims a comet cannot pass on a
     * screen that has quietly lost it. The calendar-only declarations - an
     * eclipse in daylight, a shower's peak, an event not visible here - are
     * the Ahead screen's and are not looked for here.
     */
    @Test
    fun showsWhatThisNightCallsFor() {
        render()

        if (scenario.settings.latitude == null) {
            compose.onNodeWithTag(NIGHT_STRIP_TAG).assertDoesNotExist()
            compose.onNodeWithTag(ALSO_UP_TAG).assertDoesNotExist()
            compose.onNodeWithTag(NOT_WORTH_TAG).assertDoesNotExist()
            return
        }

        val timeline = state.timeline
        if (timeline != null) {
            compose.onNodeWithTag(NIGHT_STRIP_TAG).performScrollTo().assertIsDisplayed()
            // The picked rows are drawn; the rest are listed under the strip.
            for (row in TonightSections.worth(timeline).upcoming) {
                compose.onNodeWithTag(timelineRowTag(row)).assertExists()
            }
        }

        for (shows in scenario.shows) {
            when (shows) {
                Shows.Events -> somewhere(tonight.events.first().title)
                Shows.Comets -> somewhere(tonight.comets.first().title)
                Shows.Passes -> somewhere(tonight.bestPasses.first().name)
                Shows.Train -> somewhere(tonight.bestPasses.first { it.channel == PassChannel.Trains }.name)
                Shows.Planets -> somewhere(tonight.planets.first().body.label)
                Shows.Moon -> showsTheMoon(timeline)
                Shows.Constellations -> somewhere(tonight.constellations.first().constellation.name)
                Shows.ConstellationAtItsBest -> somewhere("At its best tonight")
                Shows.ConstellationsAlwaysUp -> somewhere("Also up, as they are")
                Shows.Noctilucent -> showsTheNoctilucentPrompt(timeline)
                Shows.ClearSpell, Shows.ClearSpellUnderway -> row(timeline) { it.subject == RowSubject.Clear }
                Shows.Cloud -> if (timeline?.cloud.orEmpty().isNotEmpty()) {
                    somewhere("Weather data by Open-Meteo.com")
                }
                // With no sunset to draw from - the polar night - the
                // darkness is said in words where the timeline would be.
                Shows.AstronomicalNight -> if (timeline != null) {
                    row(timeline) { it.title == "Fully dark" }
                } else {
                    somewhere("Fully dark from")
                }
                Shows.NoAstronomicalNight -> absent("Fully dark")
                Shows.Aurora -> somewhere("${requireNotNull(scenario.alertState.lastProbability)}%")
                Shows.PendingVerdict -> somewhere("Was it worth it?")
                else -> Unit
            }
        }
    }

    /**
     * At least one moment of the night that [matches], on the screen: as a
     * row where the outlook picked it, and in words in the lists where not.
     */
    private fun row(timeline: Timeline?, matches: (TimelineRow) -> Boolean) {
        val row = requireNotNull(timeline?.rows?.firstOrNull(matches)) { "no such row on ${timeline?.rows}" }
        if (row.pick != null) {
            compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().assertIsDisplayed()
        } else {
            somewhere(row.title)
        }
    }

    /**
     * The moon has rows where it rises or sets tonight, and is listed where
     * it is up all night with no row to say so.
     */
    private fun showsTheMoon(timeline: Timeline?) {
        val row = timeline?.rows?.firstOrNull { it.subject == RowSubject.Moon && it.pick != null }
        if (row != null) {
            compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().assertIsDisplayed()
        } else {
            compose.onNode(hasText("The moon") and hasAnyAncestor(hasTestTag(ALSO_UP_TAG)))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    /**
     * The noctilucent prompt has a row of its own on the timeline, worded as
     * something to do, and is listed by its title only where there is no
     * timeline to put it on.
     */
    private fun showsTheNoctilucentPrompt(timeline: Timeline?) {
        val prompt = requireNotNull(tonight.noctilucent)
        val row = timeline?.rows?.firstOrNull { it.subject == RowSubject.Event(prompt) && it.pick != null }
        if (row != null) {
            compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().assertIsDisplayed()
        } else {
            somewhere(prompt.title)
        }
    }

    /**
     * Where an event can only half be seen, the reason is on the home screen and
     * not only on the calendar entry: in the sheet its row opens, or beside it
     * in the lists.
     *
     * This is the assertion the catalogue was worth building for. The caveat was
     * missing from the tonight list for months and nobody could see it, because
     * saying so requires a marginal event to be happening on the night somebody
     * looks - which is a few nights a year.
     */
    @Test
    fun printsTheReasonAnEventIsWorthLessThanItSounds() {
        val event = tonight.events.firstOrNull { it.visibility is Visibility.Marginal } ?: return
        val reason = (event.visibility as Visibility.Marginal).reason

        render()
        val row = state.timeline?.rows?.firstOrNull { it.subject == RowSubject.Event(event) && it.pick != null }
        if (row != null) {
            compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().performClick()
            compose.onNode(hasText(reason) and hasAnyAncestor(hasTestTag(ROW_SHEET_TAG)))
                .performScrollTo()
                .assertIsDisplayed()
        } else {
            somewhere(reason)
        }
    }

    /**
     * An event happening as the screen is read says so, and one that is merely
     * happening later tonight does not.
     *
     * Both halves matter. A mark that appeared on everything would be a label
     * on the whole card rather than a signal about one entry, and an eclipse
     * three hours off wearing it would send somebody outside for nothing.
     */
    @Test
    fun marksWhatIsHappeningAsYouRead() {
        if (scenario.settings.latitude == null) return

        render()

        if (tonight.events.any { it.isUnderWay(scenario.nowMillis) }) {
            somewhere("Happening now")
        } else {
            absent("Happening now")
        }
    }

    /**
     * A night with nothing on it still says so rather than drawing an empty
     * screen. The commonest night of the year, and the one most easily broken
     * by a change to anything above it.
     */
    @Test
    fun saysSomethingOnAnEmptyNight() {
        if (!tonight.isEmpty || scenario.settings.latitude == null) return

        render()
        shown(emptySatellitesSentence())
        // Pinned under the scroll, so there on an empty night without scrolling.
        compose.onNodeWithTag(WATCH_BUTTON_TAG).assertIsDisplayed().assertTextContains("Watch tonight")
    }

    /**
     * The night opens with the sentence the outlook decided, word for word -
     * and with none where there is no outlook to give, rather than a blank
     * heading.
     */
    @Test
    fun opensWithTheOutlookItDecided() {
        render()

        val headline = state.outlook?.let { OutlookWords.headline(it) }
        if (headline == null) {
            if (scenario.settings.latitude == null) {
                shown("Set your location to begin")
            } else {
                compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG).assertDoesNotExist()
            }
            return
        }
        compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(headline)
    }
}
