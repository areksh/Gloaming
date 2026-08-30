package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/**
 * The year ahead, and which part of it is happening now.
 *
 * The calendar keeps an entry while any part of it is still ahead rather than
 * only while its moment is, so the list now holds things already under way
 * alongside things weeks off. Telling those apart is the whole of what this
 * covers - the rest of the row is pinned by the JVM tests behind it.
 *
 * **Needs the device unlocked**, like every other Compose test here.
 */
class CalendarScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    /** An eclipse whose contacts are either side of now. */
    private val underWay = SkyEvent(
        kind = EventKind.LunarEclipse,
        at = now,
        from = now - hour,
        until = now + hour,
        title = "Total eclipse of the moon",
        detail = "100 per cent of the moon's disc in the Earth's deep shadow.",
        visibility = Visibility.Good,
    )

    /** The same shape of event, three hours off. */
    private val later = SkyEvent(
        kind = EventKind.Occultation,
        at = now + 4 * hour,
        from = now + 3 * hour,
        until = now + 5 * hour,
        title = "The moon passes in front of Venus",
        detail = "Venus disappears behind the moon's edge.",
        visibility = Visibility.Good,
    )

    /**
     * Three weeks either side of its date, and inside that window now - the
     * case the mark must not fire on, or half the calendar would wear it.
     */
    private val season = SkyEvent(
        kind = EventKind.Opposition,
        at = now + 2 * 24 * hour,
        from = now - 19 * 24 * hour,
        until = now + 23 * 24 * hour,
        title = "Jupiter at its best for the year",
        detail = "Opposite the sun, so it is up all night.",
        visibility = Visibility.Good,
    )

    /** A constellation's one night, which is one night and not one season. */
    private val pattern = SkyEvent(
        kind = EventKind.Constellation,
        at = now + 6 * hour,
        from = now + 2 * hour,
        until = now + 12 * hour,
        title = "Orion at its best",
        detail = "Highest in the middle of the night, which is the best it is placed all year.",
        visibility = Visibility.Good,
    )

    private fun show(vararg events: SkyEvent) {
        compose.setContent {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CalendarContent(
                    events = events.toList(),
                    hasLocation = true,
                    loading = false,
                    nowMillis = now,
                )
            }
        }
    }

    /**
     * The calendar is the app's densest run of jargon - occultation,
     * elongation, opposition, greatest brightness - and the one screen with no
     * card to hang an Explain button off. Its words go above the filter, where
     * somebody deciding what to hide is already looking.
     */
    @Test
    fun explainsTheWordsTheCalendarPutsOnScreen() {
        show(underWay, later)

        compose.onNodeWithTag(helpTag(HelpTopic.Calendar)).performScrollTo().performClick()

        compose.onNodeWithText("Occultation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Opposition").performScrollTo().assertIsDisplayed()
    }

    /** Closed to begin with, so the first month is still the first thing read. */
    @Test
    fun keepsTheCalendarsWordsOutOfTheWayUntilTheyAreWanted() {
        show(underWay, later)

        compose.onNodeWithText("Occultation").assertDoesNotExist()
    }

    @Test
    fun marksTheEntryThatIsHappeningNow() {
        show(underWay, later)

        compose.onNodeWithText("Happening now").performScrollTo().assertIsDisplayed()
    }

    /** Once, on the one entry - not on every row in the list. */
    @Test
    fun marksOnlyTheEntryThatIsHappeningNow() {
        show(underWay, later, season)

        compose.onAllNodesWithText("Happening now").assertCountEquals(1)
    }

    /** And not at all on a list where nothing has started. */
    @Test
    fun leavesAListOfThingsToComeUnmarked() {
        show(later, season)

        compose.onNodeWithText("Happening now").assertDoesNotExist()
    }

    /**
     * The chip is how a whole kind is hidden, and hiding a kind silences it as
     * well as removing it from the list - so a kind with no chip is a kind
     * nobody can turn off.
     */
    @Test
    fun offersAChipForTheConstellations() {
        show(underWay, pattern)

        compose.onNodeWithTag(kindTag(EventKind.Constellation))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
