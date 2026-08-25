package sh.arek.gloaming.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import sh.arek.gloaming.data.WatchDuration

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
    private var auroraInterval: Int? = null
    private var issInterval: Int? = null
    private var followStation: Boolean? = null
    private var watchDuration: WatchDuration? = null
    private var precise: Boolean? = null


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
                onPreciseBeforeUnlockChange = { precise = it },
                onCloudGateChange = {},
                onCloudToleranceChange = {},
                onQuietHoursEnabledChange = {},
                onQuietFromChange = {},
                onQuietUntilChange = {},
                onQuietHoursOverrideChange = {},
                onNightVisionChange = {},
                onAuroraIntervalChange = { auroraInterval = it },
                onIssIntervalChange = { issInterval = it },
                onBrightIntervalChange = {},
                onTrainIntervalChange = {},
                onPassAlertsChange = {},
                onFollowStationChange = { followStation = it },
                onWatchingChange = { watchRequest = it },
                onWatchDurationChange = { watchDuration = it },
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
        // Named specifically: the vendored propagator's credit says GPL-3.0
        // too, so a substring match on the licence alone no longer identifies
        // the app's own statement of it.
        compose.onNodeWithText("Gloaming itself is free software under the GPL-3.0", substring = true)
            .assertExists()
        compose.onNodeWithText("Vendored under the GPL-3.0", substring = true).assertExists()
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

    /**
     * How often the app may interrupt is the user's answer to give, and a
     * setting that cannot be reached is not a setting. Three channels, because
     * an aurora, the space station and a rocket body are three different
     * appetites.
     */
    @Test
    fun offersAnIntervalForEveryChannel() {
        show()

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Aurora: at most every 6 hours", substring = true).assertExists()
        compose.onNodeWithText("Space station: at most every 6 hours", substring = true)
            .assertExists()
        compose.onNodeWithText("Other bright satellites: at most every 6 hours", substring = true)
            .assertExists()
        // Its own row rather than a share of the bright catalogue's: a launch
        // is a train for a few nights and then never again.
        compose.onNodeWithText("Starlink trains: at most every 6 hours", substring = true)
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun reportsTheIntervalTheUserPicked() {
        show()

        compose.onNodeWithText("How often at most").performScrollTo()
        compose.onAllNodesWithText("1h").onFirst().performScrollTo().performClick()

        assertEquals(60, auroraInterval)
    }

    /**
     * A user the station cannot reach is told so.
     *
     * There is no pass list to show empty - the app speaks only through
     * notifications - so without this sentence an unreachable station and a
     * broken app look identical from the outside.
     */
    @Test
    fun explainsWhyTheStationNeverAppearsAtHighLatitude() {
        show(settings = UserSettings(latitude = 69.6, longitude = 18.96))

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("never comes above your horizon", substring = true)
            .assertExists()
    }

    /** And a user it does reach is not told anything of the sort. */
    @Test
    fun saysNothingAboutCoverageWhereTheStationPasses() {
        show(settings = UserSettings(latitude = 55.95, longitude = -3.19))

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("The station's orbit reaches", substring = true)
            .assertDoesNotExist()
    }

    /**
     * Silencing passes hides their intervals rather than leaving two controls
     * that do nothing.
     */
    @Test
    fun hidesThePassIntervalsWhenPassesAreOff() {
        show(settings = UserSettings(passAlertsEnabled = false))

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Space station: at most every 6 hours", substring = true)
            .assertDoesNotExist()
    }

    /**
     * The precision choice only exists once there is a copy to be precise
     * about. Offering it with before-unlock alerting off would be a question
     * about nothing.
     */
    @Test
    fun offersThePrecisionChoiceOnlyOnceTheCopyExists() {
        show(settings = located.copy(alertsBeforeUnlock = false))

        compose.onNodeWithText("Alert before unlock").performScrollTo().assertExists()
        compose.onNodeWithText("Store my exact location").assertDoesNotExist()
    }

    @Test
    fun reportsTurningOnPreciseStorageBeforeUnlock() {
        show(settings = located.copy(alertsBeforeUnlock = true))

        compose.onNodeWithTag(PRECISE_BEFORE_UNLOCK_TAG).performScrollTo().performClick()

        assertEquals(true, precise)
    }

    /**
     * The card must say which of the two it is actually doing. Claiming only a
     * whole degree is stored while storing the typed position would be the
     * worst kind of wrong.
     */
    @Test
    fun saysWhichLocationIsActuallyStored() {
        show(settings = located.copy(alertsBeforeUnlock = true, preciseLocationBeforeUnlock = true))

        compose.onNodeWithText("Alert before unlock").performScrollTo().assertExists()
        compose.onNodeWithText("exactly as you typed it", substring = true).assertExists()
        compose.onNodeWithText("Only the whole degree is stored", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun saysSoWhenOnlyTheRoundedLocationIsStored() {
        show(settings = located.copy(alertsBeforeUnlock = true))

        compose.onNodeWithText("Alert before unlock").performScrollTo().assertExists()
        compose.onNodeWithText("Only the whole degree is stored", substring = true).assertExists()
    }

    /**
     * How long a watch runs was a constant nobody could see, let alone change.
     * The span is the user's call, the same as the interruption intervals; the
     * ceiling above it is not, because an unbounded watch holds a wake lock and
     * polls every two minutes until the battery is flat.
     */
    @Test
    fun offersEveryWatchDuration() {
        show()

        compose.onNodeWithText("Watch now").performScrollTo().assertExists()
        compose.onNodeWithText("Stops on its own after: 4 hours").assertExists()
        for (label in listOf("1h", "2h", "4h", "8h", "Dawn")) {
            compose.onAllNodesWithText(label).onFirst().assertExists()
        }
    }

    @Test
    fun reportsTheWatchDurationTheUserPicked() {
        show()

        compose.onNodeWithText("Watch now").performScrollTo()
        compose.onAllNodesWithText("Dawn").onFirst().performScrollTo().performClick()

        assertEquals(WatchDuration.UntilLight, watchDuration)
    }

    /** The midsummer case is stated rather than discovered at three in the morning. */
    @Test
    fun explainsWhatHappensWhenTheSkyNeverDarkens() {
        show(settings = located.copy(watchDuration = WatchDuration.UntilLight))

        compose.onNodeWithText("Watch now").performScrollTo().assertExists()
        compose.onNodeWithText("it never fully does", substring = true).assertExists()
    }

    /**
     * Following every pass live is offered beside the station and nowhere else.
     *
     * Not a default waiting to be relaxed: the station goes over most nights,
     * whereas the bright catalogue goes over constantly, so the same switch on
     * that channel would be a foreground service most of every clear night.
     */
    @Test
    fun offersAutomaticFollowingForTheStationOnly() {
        show()

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Follow every station pass", substring = true).assertExists()
        compose.onNodeWithText("Follow every bright satellite pass", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun reportsTurningAutomaticFollowingOn() {
        show()

        compose.onNodeWithTag(FOLLOW_STATION_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(true, followStation)
    }

    /** Off, it goes with the intervals: a switch under a silenced channel does nothing. */
    @Test
    fun hidesAutomaticFollowingWhenPassesAreOff() {
        show(settings = UserSettings(passAlertsEnabled = false))

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Follow every station pass", substring = true)
            .assertDoesNotExist()
    }
}
