package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

/**
 * What the screen shows, driven from state.
 *
 * Written after "Check now" appeared to do nothing: the poll ran, but the
 * screen had read the alert state once and never again, so it kept saying "Not
 * checked yet" however many times the button was pressed. These pin both halves
 * of that - that the status text follows the state it is given, and that the
 * button reports being busy.
 *
 * **These need the device unlocked.** An activity launched behind the keyguard
 * resumes for an instant and pauses again without ever drawing, so the Compose
 * harness finds no semantics tree and every assertion here fails with "No
 * compose hierarchies found in the app" - which reads like a broken test setup
 * and is not.
 */
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val located = UserSettings(latitude = 55.95, longitude = -3.19)

    private var polled = 0

    private fun show(
        settings: UserSettings = located,
        alertState: AlertState = AlertState(),
        checking: Boolean = false,
    ) {
        compose.setContent {
            MainScreen(
                state = MainUiState(
                    settings = settings,
                    alertState = alertState,
                    latitudeInput = "55.95",
                    longitudeInput = "-3.19",
                    quietFromInput = "23:00",
                    quietUntilInput = "07:00",
                    batteryExempt = true,
                    notificationsAllowed = true,
                    nowMillis = now,
                    checking = checking,
                ),
                onLatitudeChange = {},
                onLongitudeChange = {},
                onThresholdChange = {},
                onAlertsEnabledChange = {},
                onBeforeUnlockChange = {},
                onCloudGateChange = {},
                onCloudToleranceChange = {},
                onQuietHoursEnabledChange = {},
                onQuietFromChange = {},
                onQuietUntilChange = {},
                onQuietHoursOverrideChange = {},
                onNightVisionChange = {},
                onSave = {},
                onPollNow = { polled++ },
                onRequestBatteryExemption = {},
                onRequestNotifications = {},
            )
        }
    }

    @Test
    fun showsTheProbabilityAndItsAge() {
        show(alertState = AlertState(lastProbability = 42, lastPollAt = now - 10 * 60_000L))

        compose.onNodeWithText("42% aurora probability here").assertExists()
        compose.onNodeWithText("Checked 10 minutes ago").assertExists()
    }

    /** The state the reported bug was stuck in. */
    @Test
    fun saysSoBeforeAnythingHasBeenChecked() {
        show(alertState = AlertState())

        compose.onNodeWithText("Not checked yet").assertExists()
    }

    // Scrolled to first. The button sits below the fold on a phone, and a
    // click aimed at a node outside the scroll viewport never reaches it -
    // which is how the busy-button test below passed vacuously at first.
    @Test
    fun checkNowAsksForACheck() {
        show()

        compose.onNodeWithText("Check now").performScrollTo().performClick()

        assertEquals(1, polled)
    }

    /**
     * A button that looks idle while a check is running reads as a broken
     * button, and a second press would only queue the same work again.
     */
    @Test
    fun checkNowSaysWhenItIsBusyAndRefusesASecondPress() {
        show(checking = true)

        compose.onNodeWithText("Checking...").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Checking...").performClick()

        assertEquals(0, polled)
    }

    @Test
    fun checkNowIsAvailableWhenIdle() {
        show()

        compose.onNodeWithText("Check now").assertIsEnabled()
    }

    @Test
    fun reportsAFailedCheckRatherThanHidingIt() {
        show(alertState = AlertState(lastPollAt = now - 60_000L, lastError = "offline"))

        compose.onNodeWithText("Last check failed: offline").assertExists()
    }

    @Test
    fun asksForALocationBeforeItHasOne() {
        show(settings = UserSettings())

        compose.onNodeWithText("Set your location to begin").assertExists()
    }

    /**
     * Open-Meteo's CC BY 4.0 terms require attribution next to the data, so it
     * has to be on screen whenever the cloud gate is on.
     */
    @Test
    fun attributesOpenMeteoWhileTheCloudGateIsOn() {
        show(settings = located.copy(cloudGateEnabled = true))

        compose.onNodeWithText("Weather data by Open-Meteo.com").assertExists()
    }
}
