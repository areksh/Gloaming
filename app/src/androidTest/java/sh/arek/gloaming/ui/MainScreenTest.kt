package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.Darkness
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

    /** The last value the watch switch asked for, or null if it was never moved. */
    private var watchRequest: Boolean? = null


    private fun show(
        settings: UserSettings = located,
        alertState: AlertState = AlertState(),
        batteryExempt: Boolean = true,
        checking: Boolean = false,
        darkness: Darkness? = null,
        powerSaveMode: Boolean = false,
        watching: Boolean = false,
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
                    batteryExempt = batteryExempt,
                    notificationsAllowed = true,
                    nowMillis = now,
                    checking = checking,
                    darkness = darkness,
                    powerSaveMode = powerSaveMode,
                    watching = watching,
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
                onWatchingChange = { watchRequest = it },
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

    /**
     * The schedule sleeps through daylight, which is most of a summer day at
     * the latitudes this app is for. Left unexplained, a twelve-hour gap
     * between checks is indistinguishable from a background service the system
     * has killed.
     */
    @Test
    fun explainsALongGapBetweenChecksRatherThanLeavingItLookingBroken() {
        val duskAt = now + 6 * 60 * 60_000L

        show(
            alertState = AlertState(lastPollAt = now - 11 * 60 * 60_000L),
            darkness = Darkness.Later(startsAt = duskAt, endsAt = null),
        )

        compose.onNodeWithText("Checked 11 hours ago").assertExists()
        compose.onNodeWithText(
            "Sun is up. Nothing to see until it gets dark, around",
            substring = true,
        ).assertExists()
    }

    /** No location means no darkness to describe, and no half-sentence about it. */
    @Test
    fun saysNothingAboutTheScheduleWithoutALocation() {
        show(settings = UserSettings(), darkness = null)

        compose.onNodeWithText("Sun is up.", substring = true).assertDoesNotExist()
    }

    /**
     * The one control in the app that is not a setting but an action, so it is
     * worth proving the tap reaches the caller - this is the same shape of bug
     * as the "check now" button that ran nothing.
     */
    @Test
    fun theWatchSwitchAsksForAWatch() {
        show(watching = false)

        compose.onNodeWithTag(WATCH_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(true, watchRequest)
    }

    @Test
    fun theWatchSwitchAsksToStopOneAlreadyRunning() {
        show(watching = true)

        compose.onNodeWithTag(WATCH_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(false, watchRequest)
    }

    /**
     * The saver notice is raised only when the saver is actually on. A standing
     * warning about a mode nobody has enabled trains people to ignore the
     * warnings that matter.
     */
    @Test
    fun mentionsBatterySaverOnlyWhileItIsOn() {
        show(powerSaveMode = false)
        compose.onNodeWithText("Battery saver is on.", substring = true).assertDoesNotExist()
    }

    @Test
    fun warnsAboutBatterySaverWhenItIsOn() {
        show(powerSaveMode = true)

        compose.onNodeWithText("Battery saver is on.", substring = true).assertExists()
    }

    /**
     * The MIT notice has to be reachable on the device, not merely present in
     * the repository, and the credits card is where it lives. A card that
     * silently stopped rendering would be a licence breach no other test would
     * notice.
     */
    @Test
    fun showsTheCreditsAndTheAppsOwnLicence() {
        show()

        compose.onNodeWithText("Credits and licences").performScrollTo().assertExists()
        compose.onNodeWithText("Cloud forecasts, under CC BY 4.0.").assertExists()
        compose.onNodeWithText("GPL-3.0", substring = true).assertExists()
    }

    /**
     * Their credit follows their data. It appears beside an AuroraWatch reading
     * and stays away from a global-grid one, which is what their conditions ask
     * for and the only honest way to present either number.
     */
    @Test
    fun creditsAuroraWatchBesideAReadingOfTheirs() {
        show(alertState = AlertState(lastProbability = 55, lastFromAuroraWatch = true))

        compose.onNodeWithText("This reading came from AuroraWatch UK.").assertExists()
        compose.onNodeWithText(
            "AuroraWatch UK alerts data is provided by Lancaster University",
            substring = true,
        ).performScrollTo().assertExists()
    }

    @Test
    fun leavesAuroraWatchUncreditedForAGlobalGridReading() {
        show(alertState = AlertState(lastProbability = 55, lastFromAuroraWatch = false))

        compose.onAllNodesWithText("AuroraWatch", substring = true).assertCountEquals(0)
    }

    /**
     * The exemption is a requirement rather than a refinement - a Gloaming
     * without it cannot alert anyone overnight, which is the whole app - so the
     * screen has to say so and offer the way to grant it.
     */
    @Test
    fun demandsTheBatteryExemptionRatherThanWorkingAroundIt() {
        show(batteryExempt = false)

        compose.onNodeWithText("Gloaming cannot alert you yet").assertExists()
        compose.onNodeWithText("Allow background checks").assertExists()
    }
}
