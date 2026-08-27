package sh.arek.gloaming.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.TonightContent

/**
 * Every night in the catalogue, drawn on a real device.
 *
 * The JVM suite proves the app has something to say about each of these nights.
 * This proves it can say it: that the cards it puts on the screen render, carry
 * the title that names what they are about, and print the event they were given
 * rather than an empty box with a heading over it.
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

    private fun render() {
        compose.setContent {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TonightContent(
                    state = state,
                    onWatchingChange = {},
                    onWatchDurationChange = {},
                    onPollNow = {},
                    onRequestBatteryExemption = {},
                    onRequestNotifications = {},
                )
            }
        }
    }

    /**
     * Matched whole rather than as a substring, which is not fussiness: the
     * moon's card is titled "The moon" and one of the events in the catalogue
     * is called "The moon passes Mars", so a substring match finds two nodes
     * and fails on a screen that is perfectly correct.
     */
    private fun shown(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun absent(text: String) {
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    /**
     * The cards the night should have, with the content that makes each of them
     * worth having.
     *
     * A title alone would not be worth asserting: the failure this is here to
     * catch is a card that draws its heading and then nothing, which is what a
     * layout mistake in a card body looks like from the outside.
     */
    @Test
    fun showsTheCardsThisNightCallsFor() {
        render()

        if (scenario.settings.latitude == null) {
            absent("Darkness")
            absent("Satellites")
            absent("Events tonight")
            return
        }

        // Two cards are on every located night whatever the sky is doing, and
        // deliberately so: an empty sky is an answer, and a missing card is
        // not - it reads as something having gone wrong.
        shown("Darkness")
        shown("Satellites")

        if (tonight.passes.isEmpty()) {
            shown("Nothing crossing tonight that clears the horizon in sunlight.")
        }

        // The rest are absent rather than empty when they have nothing to say.
        if (tonight.moon != null) shown("The moon") else absent("The moon")

        if (tonight.cloud != null || tonight.clearSpell != null) {
            shown("Sky now")
        } else {
            absent("Sky now")
        }

        if (tonight.events.isNotEmpty() || tonight.noctilucent != null) {
            shown("Events tonight")
            tonight.events.firstOrNull()?.let { shown(it.title) }
            tonight.noctilucent?.let { shown(it.title) }
        } else {
            absent("Events tonight")
        }

        if (tonight.comets.isNotEmpty()) {
            shown("Comets tonight")
            shown(tonight.comets.first().title)
        } else {
            absent("Comets tonight")
        }

        if (tonight.planets.isNotEmpty()) {
            shown("Planets tonight")
            shown(tonight.planets.first().body.label.replaceFirstChar { it.uppercase() })
        } else {
            absent("Planets tonight")
        }
    }

    /**
     * Where an event can only half be seen, the reason is on the home screen and
     * not only on the calendar entry.
     *
     * This is the assertion the catalogue was worth building for. The caveat was
     * missing from the tonight list for months and nobody could see it, because
     * saying so requires a marginal event to be happening on the night somebody
     * looks - which is a few nights a year.
     */
    @Test
    fun printsTheReasonAnEventIsWorthLessThanItSounds() {
        val marginal = tonight.events
            .mapNotNull { (it.visibility as? sh.arek.gloaming.astronomy.Visibility.Marginal)?.reason }
            .firstOrNull()
            ?: return

        render()
        shown(marginal)
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
            shown("Happening now")
        } else {
            absent("Happening now")
        }
    }

    /**
     * A night with nothing on it still says so rather than drawing an empty
     * screen. The commonest night of the year, and the one most easily broken
     * by a change to any of the cards above.
     */
    @Test
    fun saysSomethingOnAnEmptyNight() {
        if (!tonight.isEmpty || scenario.settings.latitude == null) return

        render()
        shown("Nothing crossing tonight that clears the horizon in sunlight.")
        shown("Watch now")
    }
}
