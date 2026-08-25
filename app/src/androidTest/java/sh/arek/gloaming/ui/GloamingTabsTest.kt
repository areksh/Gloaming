package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

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
}
