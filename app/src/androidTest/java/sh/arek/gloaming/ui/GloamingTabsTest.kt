package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.EventPlanner

/**
 * The tab bar, which is the app's first navigation of any kind.
 *
 * **These need the device unlocked**, for the reason recorded in [ScreensTest]:
 * behind the keyguard nothing is ever drawn and every assertion fails in a way
 * that looks like a broken harness.
 */
class GloamingTabsTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun show(
        settings: UserSettings = located,
        calendar: List<SkyEvent> = listOf(eclipse),
        calendarLoading: Boolean = false,
    ) {
        compose.setContent {
            GloamingTabs(
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
                onLatitudeChange = {},
                onLongitudeChange = {},
                onThresholdChange = {},
                onAlertsEnabledChange = {},
                onBeforeUnlockChange = {},
                onPreciseBeforeUnlockChange = {},
                onCloudGateChange = {},
                onCloudToleranceChange = {},
                onQuietHoursEnabledChange = {},
                onQuietFromChange = {},
                onQuietUntilChange = {},
                onQuietHoursOverrideChange = {},
                onNightVisionChange = {},
                onLogRetentionChange = {},
                onExportLog = {},
                onClearLog = {},
                onAuroraIntervalChange = {},
                onIssIntervalChange = {},
                onBrightIntervalChange = {},
                onTrainIntervalChange = {},
                onPassAlertsChange = {},
                onFollowStationChange = {},
                onPassLeadChange = {},
                onMinimumElevationChange = {},
                onLiveLeadChange = {},
                onEventAlertsChange = {},
                onEventHeadsUpChange = {},
                onEventLeadChange = {},
                onEclipseFollowMaxChange = {},
                onNotifyEveryEventChange = {},
                onToggleStar = { starred += it },
                onToggleKind = { hiddenKind = it },
                onCometMagnitudeChange = {},
                onCometAltitudeChange = {},
                onCometStaleChange = {},
                onCometMoonChange = {},
                onLocationModeChange = {},
                onDetectLocation = {},
                onWatchingChange = {},
                onWatchDurationChange = {},
                onSave = {},
                onPollNow = {},
                onRequestBatteryExemption = {},
                onRequestNotifications = {},
            )
        }
    }

    /** Tonight is what the app is for, so it is what opens. */
    @Test
    fun opensOnTonight() {
        show()

        compose.onNodeWithText("Check now").assertIsDisplayed()
    }

    /** The calendar is a tab away and shows what it was given. */
    @Test
    fun showsTheCalendarOnItsOwnTab() {
        show()

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithText("Partial eclipse of the sun").assertIsDisplayed()
    }

    /** The settings did not vanish when they moved off the front page. */
    @Test
    fun showsTheSettingsOnItsOwnTab() {
        show()

        compose.onNodeWithTag(TAB_SETTINGS).performClick()

        compose.onNodeWithText("Location").performScrollTo().assertIsDisplayed()
    }

    /** Switching away and back does not lose the tab that was chosen. */
    @Test
    fun staysOnTheTabItWasLeftOn() {
        show()

        compose.onNodeWithTag(TAB_CALENDAR).performClick()
        compose.onNodeWithTag(TAB_TONIGHT).performClick()
        compose.onNodeWithTag(TAB_CALENDAR).performClick()

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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithText("The calendar needs a location").assertIsDisplayed()
    }

    /** A quarter of a second of arithmetic should say it is working, not look empty. */
    @Test
    fun saysItIsWorkingWhileTheCalendarIsComputed() {
        show(calendar = emptyList(), calendarLoading = true)

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithText("Working out the year ahead...").assertIsDisplayed()
    }

    // --- starring ---

    /** A star is offered on an event that could act on it. */
    @Test
    fun offersAStarOnAVisibleEvent() {
        show()

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithTag(starTag(eclipse)).performScrollTo().assertIsDisplayed()
    }

    /** Pressing it reports the event rather than acting alone. */
    @Test
    fun reportsAStarredEvent() {
        show()

        compose.onNodeWithTag(TAB_CALENDAR).performClick()
        compose.onNodeWithTag(starTag(eclipse)).performScrollTo().performClick()

        assertEquals(listOf(eclipse), starred)
    }

    /** An already-starred event says so, so the control is not a mystery. */
    @Test
    fun saysWhenAnEventIsAlreadyStarred() {
        show(settings = located.copy(starredEvents = setOf(EventPlanner.starKey(eclipse))))

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithTag(starTag(marginal)).performScrollTo().assertIsDisplayed()
    }

    /**
     * No star on something the ground is in the way of. That is not a
     * judgement about whether it is worth the trouble - a star cannot lift it
     * above the horizon, so the control would do nothing.
     */
    @Test
    fun offersNoStarOnAnEventThatCannotBeSeen() {
        val hidden = eclipse.copy(visibility = Visibility.NotFromHere("below the horizon here"))
        show(calendar = listOf(hidden))

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onAllNodesWithTag(starTag(hidden)).assertCountEquals(0)
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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithTag(kindTag(EventKind.SolarEclipse)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New and full moon (1)").performScrollTo().assertIsDisplayed()
    }

    /** Pressing one reports the kind rather than acting alone. */
    @Test
    fun reportsAFilteredKind() {
        val phase = eclipse.copy(kind = EventKind.MoonPhase, at = eclipse.at + 86_400_000L)
        show(calendar = listOf(eclipse, phase))

        compose.onNodeWithTag(TAB_CALENDAR).performClick()
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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

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

        compose.onNodeWithTag(TAB_CALENDAR).performClick()

        compose.onNodeWithText("Comet 220P/McNaught").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("39 observers", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A comet can be starred, which is what makes "tell me anyway" reachable. */
    @Test
    fun reportsAStarredComet() {
        show(calendar = listOf(comet))

        compose.onNodeWithTag(TAB_CALENDAR).performClick()
        compose.onNodeWithTag(starTag(comet)).performScrollTo().performClick()

        assertEquals(listOf(comet), starred)
    }

    /** Comets get their own chip, so somebody who wants none can hide the lot. */
    @Test
    fun offersACometChipOnTheFilter() {
        show(calendar = listOf(comet, eclipse))

        compose.onNodeWithTag(TAB_CALENDAR).performClick()
        compose.onNodeWithTag(kindTag(EventKind.Comet)).performScrollTo().performClick()

        assertEquals(EventKind.Comet, hiddenKind)
    }
}
