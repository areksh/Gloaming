package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.activity.ComponentActivity
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.EventPlanner

/**
 * The bottom bar, which is the app's only navigation.
 *
 * **These need the device unlocked**, for the reason recorded in [ScreensTest]:
 * behind the keyguard nothing is ever drawn and every assertion fails in a way
 * that looks like a broken harness.
 */
class GloamingNavTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val located = UserSettings(latitude = 55.95, longitude = -3.19)

    private val starred = mutableListOf<SkyEvent>()
    private var hiddenKind: EventKind? = null

    private val eclipse = SkyEvent(
        kind = EventKind.SolarEclipse,
        at = 1_800_000_000_000L,
        from = 1_799_996_400_000L,
        until = 1_800_003_600_000L,
        title = "Partial eclipse of the sun",
        detail = "27 per cent of the sun covered at maximum.",
        visibility = Visibility.Good,
    )

    /** What the two ways out of the app were asked to send. */
    private var calendarSync: CalendarSync? = null
    private val shared = mutableListOf<SkyEvent>()
    private val exported = mutableListOf<SkyEvent>()

    private fun show(
        settings: UserSettings = located,
        calendar: List<SkyEvent> = listOf(eclipse),
        calendarLoading: Boolean = false,
    ) {
        compose.setContent {
            GloamingNav(
                state = MainUiState(
                    settings = settings,
                    alertState = AlertState(),
                    latitudeInput = "55.95",
                    longitudeInput = "-3.19",
                    quietFromInput = "23:00",
                    quietUntilInput = "07:00",
                    batteryExempt = true,
                    notificationsAllowed = true,
                    nowMillis = 1_800_000_000_000L,
                    calendar = calendar,
                    calendarLoading = calendarLoading,
                ),
                actions = UiActions(
                    onCalendarSyncChange = { calendarSync = it },
                    onToggleStar = { starred += it },
                    onToggleKind = { hiddenKind = it },
                    onShareEvent = { shared += it },
                    onExportEvent = { exported += it },
                ),
            )
        }
    }

    /**
     * Tonight is what the app is for, so it is what opens.
     *
     * The tab, and then Tonight's own content. The watch button is the one
     * part of it that is a claim about position rather than scroll: it is
     * pinned under the night, so it is on screen without scrolling whatever
     * the cards above it render as. This once asserted "Check now" displayed
     * unscrolled, which was a claim about how tall those cards were, and it
     * began failing on a phone whose app binary had not changed.
     */
    @Test
    fun opensOnTonight() {
        show()

        compose.onNodeWithTag(Section.Tonight.tag).assertIsSelected()
        compose.onNodeWithTag(WATCH_BUTTON_TAG).assertIsDisplayed()
    }

    /** The calendar is a tab away and shows what it was given. */
    @Test
    fun showsTheCalendarAsAhead() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Partial eclipse of the sun").assertIsDisplayed()
    }

    /** The settings did not vanish when they moved off the front page. */
    @Test
    fun showsTheSettingsOnItsOwnSection() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()

        compose.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
    }

    /**
     * The guide is reached from Settings, and is the same every night.
     *
     * It is the one screen here that needs no location, no forecast and no
     * network, which is why it is worth having somewhere to send a reader who
     * has not set any of that up yet.
     */
    @Test
    fun theGuideIsReachedFromSettings() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()

        compose.onNodeWithText("Finding your way round the sky")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Polaris").performScrollTo().assertIsDisplayed()
    }

    /**
     * The guide costs nothing without a location, unlike the calendar. A
     * reader who has not set one up yet is exactly the reader it is for.
     */
    @Test
    fun theGuideWorksWithoutALocation() {
        show(settings = UserSettings(), calendar = emptyList())

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()

        compose.onNodeWithText("The Plough").performScrollTo().assertIsDisplayed()
    }

    /** The guide is a page under Settings, not a section of the bar. */
    @Test
    fun thereIsNoGuideSection() {
        show()

        compose.onAllNodesWithText("Guide").assertCountEquals(0)
        compose.onAllNodesWithTag("nav-guide").assertCountEquals(0)
    }

    /** Leaving the guide returns to Settings, not to somewhere else. */
    @Test
    fun theGuideReturnsToSettings() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()
        compose.onNodeWithTag(GUIDE_BACK_TAG).performClick()

        compose.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
    }

    /** System Back from the guide returns to Settings rather than leaving the app. */
    @Test
    fun systemBackLeavesTheGuideForSettings() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()
        compose.waitForIdle()
        Espresso.pressBack()

        compose.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(GUIDE_BACK_TAG).assertCountEquals(0)
    }

    /** Tapping Settings while the guide is open shows the Settings index again. */
    @Test
    fun tappingSettingsWhileInTheGuideShowsTheIndex() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()
        compose.onNodeWithTag(Section.Settings.tag).performClick()

        compose.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(GUIDE_BACK_TAG).assertCountEquals(0)
    }

    /** Leaving Settings for another section and returning does not reopen the guide. */
    @Test
    fun switchingSectionsResetsTheGuide() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(GUIDE_ROW_TAG).performClick()
        compose.onNodeWithTag(Section.Tonight.tag).performClick()
        compose.onNodeWithTag(Section.Settings.tag).performClick()

        compose.onAllNodesWithTag(GUIDE_BACK_TAG).assertCountEquals(0)
    }

    /**
     * Each section keeps its own scroll position: scrolling Settings down and
     * visiting another section does not send it back to the top, nor does it
     * move the others.
     */
    @Test
    fun eachSectionKeepsItsOwnScrollPosition() {
        show()

        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithText("Credits and licences", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(Section.Tonight.tag).performClick()
        compose.onNodeWithTag(Section.Settings.tag).performClick()

        compose.onNodeWithText("Credits and licences", substring = true).assertIsDisplayed()
    }

    /** Switching away and back does not lose the tab that was chosen. */
    @Test
    fun staysOnTheSectionItWasLeftOn() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(Section.Tonight.tag).performClick()
        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Partial eclipse of the sun").assertIsDisplayed()
    }

    /**
     * Every event in the calendar depends on where the observer stands, so
     * without a location there is nothing honest to show. Saying why beats an
     * empty page, which reads as a broken tab.
     */
    @Test
    fun asksForALocationBeforeShowingACalendar() {
        show(settings = UserSettings(), calendar = emptyList())

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("The calendar needs a location").assertIsDisplayed()
    }

    /** A quarter of a second of arithmetic should say it is working, not look empty. */
    @Test
    fun saysItIsWorkingWhileTheCalendarIsComputed() {
        show(calendar = emptyList(), calendarLoading = true)

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Working out the year ahead...").assertIsDisplayed()
    }

    // --- starring ---

    /** A star is offered on an event that could act on it. */
    @Test
    fun offersAStarOnAVisibleEvent() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithTag(starTag(eclipse)).performScrollTo().assertIsDisplayed()
    }

    /** Pressing it reports the event rather than acting alone. */
    @Test
    fun reportsAStarredEvent() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(starTag(eclipse)).performScrollTo().performClick()

        assertEquals(listOf(eclipse), starred)
    }

    /** An already-starred event says so, so the control is not a mystery. */
    @Test
    fun saysWhenAnEventIsAlreadyStarred() {
        show(settings = located.copy(starredEvents = setOf(EventPlanner.starKey(eclipse))))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Starred").performScrollTo().assertIsDisplayed()
    }

    /**
     * A marginal event can be starred, and that is the point of the control:
     * the star is somebody saying "tell me anyway" about the one thing the app
     * was otherwise going to keep quiet about.
     */
    @Test
    fun offersAStarOnAMarginalEvent() {
        val marginal = eclipse.copy(
            visibility = Visibility.Marginal("The sun is only 4 degrees up at maximum."),
        )
        show(calendar = listOf(marginal))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithTag(starTag(marginal)).performScrollTo().assertIsDisplayed()
    }

    /**
     * A star even on something the ground is in the way of, which reverses what
     * this test used to assert.
     *
     * The old rule was that a star which cannot produce a notification is a
     * control that does nothing, so it was withheld here. That was wrong about
     * what a star is: it is also what calendar sync writes into the phone's
     * calendar, and that path never looked at visibility or at channels. An
     * eclipse that misses this country is exactly the sort of thing somebody
     * puts in a calendar, possibly while looking up flights.
     *
     * It still cannot wake anybody - `EventPlanner.worthAnnouncing` refuses
     * NotFromHere whatever the star says - so what is offered here is a way to
     * record an interest, not a way to be interrupted about the impossible.
     */
    @Test
    fun offersAStarEvenOnAnEventThatCannotBeSeenFromHere() {
        val hidden = eclipse.copy(visibility = Visibility.NotFromHere("below the horizon here"))
        show(calendar = listOf(hidden))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithTag(starTag(hidden)).performScrollTo().assertIsDisplayed()
    }

    // --- filtering ---

    /** A chip per kind present, with its count, since volume is the point. */
    @Test
    fun offersAChipForEachKindPresent() {
        val phase = eclipse.copy(
            kind = EventKind.MoonPhase,
            at = eclipse.at + 86_400_000L,
            title = "Full moon",
        )
        show(calendar = listOf(eclipse, phase))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(KINDS_FILTER_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(kindTag(EventKind.SolarEclipse)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New and full moon (1)").performScrollTo().assertIsDisplayed()
    }

    /** Pressing one reports the kind rather than acting alone. */
    @Test
    fun reportsAFilteredKind() {
        val phase = eclipse.copy(kind = EventKind.MoonPhase, at = eclipse.at + 86_400_000L)
        show(calendar = listOf(eclipse, phase))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(KINDS_FILTER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(kindTag(EventKind.MoonPhase)).performScrollTo().performClick()

        assertEquals(EventKind.MoonPhase, hiddenKind)
    }

    /** A hidden kind is gone from the list, not merely greyed out. */
    @Test
    fun hidesAFilteredKindFromTheList() {
        val phase = eclipse.copy(
            kind = EventKind.MoonPhase,
            at = eclipse.at + 86_400_000L,
            title = "Full moon",
        )
        show(
            settings = located.copy(hiddenEventKinds = setOf("MoonPhase")),
            calendar = listOf(eclipse, phase),
        )

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Partial eclipse of the sun").assertIsDisplayed()
        compose.onAllNodesWithText("Full moon").assertCountEquals(0)
    }

    /** Filtering everything out says so rather than looking broken. */
    @Test
    fun saysSoWhenEverythingIsFilteredOut() {
        show(
            settings = located.copy(hiddenEventKinds = setOf("SolarEclipse", "MoonPhase")),
            calendar = listOf(eclipse, eclipse.copy(kind = EventKind.MoonPhase, at = eclipse.at + 1)),
        )

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Everything is filtered out. Turn a kind back on above.")
            .assertIsDisplayed()
    }

    // --- comets ---

    /**
     * A comet in the calendar, keyed on its name rather than its moment.
     *
     * That distinction is the whole reason this test exists. Every other event
     * here has a moment fixed by orbital mechanics; a comet's is invented by
     * the calendar - the best night from now - and moves whenever the calendar
     * is recomputed, so a star keyed on time would be lost within a day of
     * being set.
     */
    private val comet = SkyEvent(
        kind = EventKind.Comet,
        at = 1_800_000_000_000L,
        from = 1_797_408_000_000L,
        until = 1_802_592_000_000L,
        title = "Comet 220P/McNaught",
        detail = "Magnitude 9.5, from 39 observers within the last few days. " +
            "Highest at 46 degrees in the south.",
        visibility = Visibility.Good,
    )

    @Test
    fun showsACometInTheCalendar() {
        show(calendar = listOf(comet))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()

        compose.onNodeWithText("Comet 220P/McNaught").performScrollTo().assertIsDisplayed()
        // The detail is said in the row's sheet now, not on the row.
        compose.onNodeWithTag(eventRowTag(comet)).performScrollTo().performClick()
        compose.onNodeWithText("39 observers", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A comet can be starred, which is what makes "tell me anyway" reachable. */
    @Test
    fun reportsAStarredComet() {
        show(calendar = listOf(comet))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(starTag(comet)).performScrollTo().performClick()

        assertEquals(listOf(comet), starred)
    }

    /** Comets get their own chip, so somebody who wants none can hide the lot. */
    @Test
    fun offersACometChipOnTheFilter() {
        show(calendar = listOf(comet, eclipse))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(KINDS_FILTER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(kindTag(EventKind.Comet)).performScrollTo().performClick()

        assertEquals(EventKind.Comet, hiddenKind)
    }

    /**
     * The two ways an event leaves the app.
     *
     * Neither needs a permission: sharing is an ACTION_SEND of text, and the
     * .ics goes out through the file provider the log already uses. Here
     * rather than in ScreensTest because Ahead is only reachable through
     * the bottom bar. They live in the row's sheet.
     */
    @Test
    fun offersToShareAnEvent() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(eventRowTag(eclipse)).performScrollTo().performClick()
        compose.onNodeWithTag(shareTag(eclipse)).performScrollTo().performClick()

        assertEquals(listOf(eclipse), shared)
        assertEquals(emptyList<SkyEvent>(), exported)
    }

    @Test
    fun offersToExportAnEventAsACalendarFile() {
        show()

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(eventRowTag(eclipse)).performScrollTo().performClick()
        compose.onNodeWithTag(exportTag(eclipse)).performScrollTo().performClick()

        assertEquals(listOf(eclipse), exported)
        assertEquals(emptyList<SkyEvent>(), shared)
    }

    /**
     * Offered on an event that cannot be seen from here, unlike the star.
     * "The sun will already have set on this one" is a perfectly good thing to
     * send somebody, and an eclipse that misses Edinburgh may well be why
     * somebody is looking up flights.
     */
    @Test
    fun offersToShareAnEventThatCannotBeSeenFromHere() {
        val elsewhere = eclipse.copy(
            visibility = Visibility.NotFromHere("The sun has set here before it starts."),
        )
        show(calendar = listOf(elsewhere))

        compose.onNodeWithTag(Section.Ahead.tag).performClick()
        compose.onNodeWithTag(eventRowTag(elsewhere)).performScrollTo().performClick()
        compose.onNodeWithTag(shareTag(elsewhere)).performScrollTo().performClick()

        assertEquals(listOf(elsewhere), shared)
    }
}
