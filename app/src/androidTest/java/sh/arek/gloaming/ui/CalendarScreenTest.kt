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
import androidx.compose.ui.test.onNodeWithText
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
}
