package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * What the two content screens show, driven from state.
 *
 * Written after "Check now" appeared to do nothing: the poll ran, but the
 * screen had read the alert state once and never again, so it kept saying "Not
 * checked yet" however many times the button was pressed. These pin both halves
 * of that - that the status text follows the state it is given, and that the
 * button reports being busy.
 *
 * Tonight and Settings are rendered together in one column here rather than
 * through the tab bar, because these assertions are about what each card says
 * and not about which tab it is behind. GloamingTabsTest covers the tabs.
 *
 * **These need the device unlocked.** An activity launched behind the keyguard
 * resumes for an instant and pauses again without ever drawing, so the Compose
 * harness finds no semantics tree and every assertion here fails with "No
 * compose hierarchies found in the app" - which reads like a broken test setup
 * and is not.
 */
class ScreensTest {

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
    private var passLead: Int? = null
    private var minimumElevation: Int? = null
    private var liveLead: Int? = null
    private var eventAlerts: Boolean? = null
    private var eventHeadsUp: Int? = null
    private var eventLead: Int? = null
    private var locationMode: LocationMode? = null
    private var detected = 0
    private var everyEvent: Boolean? = null
    private var cometMagnitude: Int? = null
    private var cometMoon: Int? = null
    private var logRetention: Int? = null
    private var threshold: Int? = null
    private var cloudTolerance: Int? = null
    private var exported = 0
    private var cleared = 0


    private fun show(
        settings: UserSettings = located,
        alertState: AlertState = AlertState(),
        batteryExempt: Boolean = true,
        checking: Boolean = false,
        darkness: Darkness? = null,
        powerSaveMode: Boolean = false,
        watching: Boolean = false,
        logSizeBytes: Long = 0L,
        locationNote: String? = null,
        tonight: TonightList? = null,
    ) {
        compose.setContent {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val state = MainUiState(
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
                    logSizeBytes = logSizeBytes,
                    locationNote = locationNote,
                    tonight = tonight,
                )

                TonightContent(
                    state = state,
                    onWatchingChange = { watchRequest = it },
                    onWatchDurationChange = { watchDuration = it },
                    onPollNow = { polled++ },
                    onRequestBatteryExemption = {},
                    onRequestNotifications = {},
                )

                SettingsContent(
                    state = state,
                    onLatitudeChange = {},
                    onLongitudeChange = {},
                    onThresholdChange = { threshold = it },
                    onAlertsEnabledChange = {},
                    onBeforeUnlockChange = {},
                    onPreciseBeforeUnlockChange = { precise = it },
                    onCloudGateChange = {},
                    onCloudToleranceChange = { cloudTolerance = it },
                    onQuietHoursEnabledChange = {},
                    onQuietFromChange = {},
                    onQuietUntilChange = {},
                    onQuietHoursOverrideChange = {},
                    onNightVisionChange = {},
                    onLogRetentionChange = { logRetention = it },
                    onExportLog = { exported++ },
                    onClearLog = { cleared++ },
                    onAuroraIntervalChange = { auroraInterval = it },
                    onIssIntervalChange = { issInterval = it },
                    onBrightIntervalChange = {},
                    onTrainIntervalChange = {},
                    onPassAlertsChange = {},
                    onFollowStationChange = { followStation = it },
                    onPassLeadChange = { passLead = it },
                    onMinimumElevationChange = { minimumElevation = it },
                    onLiveLeadChange = { liveLead = it },
                    onEventAlertsChange = { eventAlerts = it },
                    onEventHeadsUpChange = { eventHeadsUp = it },
                    onEventLeadChange = { eventLead = it },
                    onEclipseFollowMaxChange = {},
                    onNotifyEveryEventChange = { everyEvent = it },
                    onCometMagnitudeChange = { cometMagnitude = it },
                    onCometAltitudeChange = {},
                    onCometStaleChange = {},
                    onCometMoonChange = { cometMoon = it },
                    onLocationModeChange = { locationMode = it },
                    onDetectLocation = { detected++ },
                    onSave = {},
                )
            }
        }
    }

    @Test
    fun showsTheProbabilityAndItsAge() {
        show(alertState = AlertState(lastProbability = 42, lastPollAt = now - 10 * 60_000L))

        compose.onNodeWithText("Aurora").assertExists()
        compose.onNodeWithText("42% probability here").assertExists()
        compose.onNodeWithText("Checked 10 minutes ago").assertExists()
    }

    /**
     * Every part of Tonight says what it is about.
     *
     * The reported complaint was that the tab was unreadable: an oversized
     * unlabelled percentage over a card called "Tonight" that was mostly
     * satellite passes. These are the titles, and the titles are the fix.
     */
    @Test
    fun titlesEveryPartOfTonight() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        for (title in listOf("Aurora", "Darkness", "Satellites")) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
    }

    /** A card with nothing to say is absent rather than empty. */
    @Test
    fun leavesOutTheCardsWithNothingToSay() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("Events tonight").assertDoesNotExist()
        compose.onNodeWithText("Comets tonight").assertDoesNotExist()
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

    /**
     * The widest choice in each row, which is the one that used to go missing.
     *
     * Both of these rows were a plain Row of buttons, so the last one was laid
     * out past the right edge of the screen and could not be pressed - while
     * still being present in the semantics tree, which is why nothing caught
     * it. A press that lands is the assertion; that the row now wraps is not
     * something the semantics tree can be asked about.
     */
    @Test
    fun offersTheHighestAuroraThreshold() {
        show()

        compose.onNodeWithTag(choiceTag("Alert above", "70 per cent"))
            .performScrollTo()
            .performClick()

        assertEquals(70, threshold)
    }

    @Test
    fun offersTheMostPermissiveCloudTolerance() {
        show()

        compose.onNodeWithTag(choiceTag("Hold alerts back above", "100 per cent"))
            .performScrollTo()
            .performClick()

        assertEquals(100, cloudTolerance)
    }

    /**
     * By tag, not by position. "1h" appears in seven rows, so picking the first
     * one meant picking by tree order - and tree order moved when the watch
     * card went to its own tab, which had this quietly clicking the watch
     * duration instead of the aurora interval.
     */
    @Test
    fun reportsTheIntervalTheUserPicked() {
        show()

        compose.onNodeWithTag(choiceTag("Aurora", "1h")).performScrollTo().performClick()

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
     * The log the app keeps for itself, because logcat is the system's ring
     * buffer and has rolled past the interesting part by morning.
     */
    @Test
    fun offersTheLogAndItsRetention() {
        show(logSizeBytes = 2048)

        compose.onNodeWithText("Gloaming's own log").performScrollTo().assertExists()
        compose.onNodeWithText("Keep: 7 days", substring = true).assertExists()
        compose.onNodeWithText("2 KB recorded.").assertExists()
    }

    @Test
    fun reportsTheRetentionTheUserPicked() {
        show(logSizeBytes = 2048)

        compose.onNodeWithText("Gloaming's own log").performScrollTo()
        compose.onAllNodesWithText("Off").onFirst().performScrollTo().performClick()

        assertEquals(0, logRetention)
    }

    @Test
    fun exportsAndClearsOnRequest() {
        show(logSizeBytes = 2048)

        compose.onNodeWithTag(EXPORT_LOG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(CLEAR_LOG_TAG).performScrollTo().performClick()

        assertEquals(1, exported)
        assertEquals(1, cleared)
    }

    /** Nothing to export or clear when there is nothing recorded. */
    @Test
    fun offersNothingToDoWithAnEmptyLog() {
        show(logSizeBytes = 0)

        compose.onNodeWithText("Gloaming's own log").performScrollTo().assertExists()
        compose.onNodeWithText("Nothing recorded yet.").assertExists()
        compose.onNodeWithTag(EXPORT_LOG_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(CLEAR_LOG_TAG).assertIsNotEnabled()
    }

    /** Turning it off says so, rather than showing "0 days". */
    @Test
    fun saysWhenTheLogIsOffRatherThanZeroDays() {
        show(settings = located.copy(logRetentionDays = 0))

        compose.onNodeWithText("Gloaming's own log").performScrollTo().assertExists()
        compose.onNodeWithText("Keep: nothing - the log is off", substring = true).assertExists()
    }

    /**
     * The timings that used to be constants nobody could reach. How much
     * warning you want, what is too low to bother mentioning, and how early the
     * tracker starts are all facts about the person and their horizon rather
     * than about the sky.
     */
    @Test
    fun offersThePassTimingsAsSettings() {
        show()

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Tell me: 30 minutes before it is highest", substring = true)
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText("Ignore passes below: 10 degrees up", substring = true)
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText("Live tracker starts: 10 minutes before it rises", substring = true)
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun reportsTheHorizonTheUserPicked() {
        show()

        compose.onNodeWithText("Ignore passes below", substring = true).performScrollTo()
        compose.onAllNodesWithText("30 deg").onFirst().performScrollTo().performClick()

        assertEquals(30, minimumElevation)
    }

    /** The pass timings go with the intervals when passes are silenced. */
    @Test
    fun hidesThePassTimingsWhenPassesAreOff() {
        show(settings = UserSettings(passAlertsEnabled = false))

        compose.onNodeWithText("How often at most").performScrollTo().assertExists()
        compose.onNodeWithText("Tell me:", substring = true).assertDoesNotExist()
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

    /** The calendar's own switch, and the leads it hides until it is on. */
    @Test
    fun offersTheCalendarAndItsTwoLeads() {
        show()

        compose.onNodeWithText("Sky calendar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notice ahead: 2 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notice on the day: an hour before")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Stop following after: 3 hours")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Switched off, the leads have nothing to configure and go away. */
    @Test
    fun hidesTheCalendarLeadsWhenTheCalendarIsOff() {
        show(settings = located.copy(eventAlertsEnabled = false))

        compose.onNodeWithText("Sky calendar").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Notice ahead: 2 days").assertCountEquals(0)
    }

    /** Turning it off is passed straight out. */
    @Test
    fun theCalendarSwitchIsReported() {
        show()

        compose.onNodeWithTag(EVENT_ALERTS_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(false, eventAlerts)
    }

    /**
     * A notice is not a rate. "at most once an hour" is the right sentence for
     * a rate limit and nonsense for a lead, which is a thing that happens once.
     */
    @Test
    fun describesALeadAsALeadRatherThanARate() {
        show()

        compose.onAllNodesWithText("Notice on the day: at most once an hour").assertCountEquals(0)
    }

    /** Picking a lead reports it, and does not report anything else. */
    @Test
    fun reportsTheCalendarLeadTheUserPicked() {
        show()

        compose.onNodeWithTag(choiceTag("Notice on the day", "2h")).performScrollTo().performClick()

        assertEquals(120, eventLead)
        assertEquals(null, auroraInterval)
    }

    // --- location ---

    /** Typing it in is the default, and the fields are there to type into. */
    @Test
    fun offersTypedInLocationByDefault() {
        show()

        compose.onNodeWithText("Latitude").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Longitude").performScrollTo().assertIsDisplayed()
    }

    /** Asking the phone is offered, and reports the choice rather than acting alone. */
    @Test
    fun offersToLetThePhoneSayWhereYouAre() {
        show()

        compose.onNodeWithTag(LOCATION_MODE_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(LocationMode.Automatic, locationMode)
    }

    /**
     * Switched to automatic, the text fields go: leaving them would offer two
     * answers to one question with no rule for which wins.
     */
    @Test
    fun hidesTheTypedFieldsWhileThePhoneIsAsked() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("Use my position now").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Latitude").assertCountEquals(0)
    }

    /** The button asks for a position rather than doing it on every recomposition. */
    @Test
    fun asksForAPositionWhenTold() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(DETECT_LOCATION_TAG).performScrollTo().performClick()

        assertEquals(1, detected)
    }

    /**
     * The promise is specific and has to be on the screen where it is made, not
     * only in the store listing: roughly, and only while the app is open.
     */
    @Test
    fun saysWhatItWillAndWillNotDoWithYourPosition() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("never asks for your position in the background", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A refusal is reported rather than left as a switch that does nothing. */
    @Test
    fun reportsWhyItHasNoPosition() {
        show(
            settings = located.copy(locationMode = LocationMode.Automatic),
            locationNote = "Location permission was declined, so Gloaming will keep using " +
                "the position you type in.",
        )

        compose.onNodeWithText("Location permission was declined", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** On by default: the calendar is useful before anybody has curated it. */
    @Test
    fun tellsYouAboutEverythingByDefault() {
        show()

        compose.onNodeWithText("Tell me about all of them").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Everything visible from where you are will interrupt", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Turning it off says what starring now does, rather than leaving it implied. */
    @Test
    fun explainsWhatStarringDoesOnceItMatters() {
        show(settings = located.copy(notifyAboutEveryEvent = false))

        compose.onNodeWithText("Only the events you star in the Calendar tab will interrupt.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theEveryEventSwitchIsReported() {
        show()

        compose.onNodeWithTag(EVERY_EVENT_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(false, everyEvent)
    }

    // --- tonight ---

    /** Passes were only ever reachable as a notification. Now they are readable. */
    @Test
    fun listsWhatIsCrossingTonight() {
        val station = SatellitePass(
            catalogue = 25544,
            name = "ISS (ZARYA)",
            channel = PassChannel.Iss,
            risesAt = now + 3_600_000L,
            riseAzimuth = 225.0,
            peaksAt = now + 3_900_000L,
            peakElevation = 68.0,
            peakAzimuth = 180.0,
            setsAt = now + 4_200_000L,
            setAzimuth = 135.0,
            rangeAtPeakKm = 420.0,
            sunlitAtPeak = true,
            entersShadowAt = null,
            leavesShadowAt = null,
        )
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = listOf(station),
                bestPasses = listOf(station),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("Satellites").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ISS (ZARYA)", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("68 degrees south", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** An empty sky is an answer, not a blank card. */
    @Test
    fun saysWhenNothingIsCrossingTonight() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("Nothing crossing tonight", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Midsummer at high latitude has no darkness, and the card says that. */
    @Test
    fun saysWhenItDoesNotGetDark() {
        show(
            tonight = TonightList(
                darkFrom = null,
                darkUntil = null,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("It does not get properly dark here at this time of year.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- comets ---

    /**
     * The four comet controls report what was picked, and picking one does not
     * quietly move another. The testTag carries the label, which is what makes
     * that provable - picking by tree order is how an earlier version of this
     * suite ended up clicking the watch duration instead of the aurora
     * interval when a card moved to another tab.
     */
    @Test
    fun reportsTheCometBrightnessTheUserPicked() {
        show()

        compose.onNodeWithTag(choiceTag("Faintest worth telling me", "magnitude 8"))
            .performScrollTo()
            .performClick()

        assertEquals(8, cometMagnitude)
        assertEquals(null, cometMoon)
        assertEquals(null, eventLead)
    }

    /**
     * The moonlight control, which matters more for a comet than for anything
     * else the app covers: a comet is a smudge rather than a point, so a bright
     * moon washes it out where it would barely dim a planet.
     */
    @Test
    fun reportsTheCometMoonToleranceTheUserPicked() {
        show()

        compose.onNodeWithTag(choiceTag("Moonlight I will put up with", "25 per cent"))
            .performScrollTo()
            .performClick()

        assertEquals(25, cometMoon)
        assertEquals(null, cometMagnitude)
    }

    /** A comet up tonight is listed with where to look. */
    @Test
    fun listsACometThatIsUpTonight() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = listOf(
                    SkyEvent(
                        kind = EventKind.Comet,
                        at = now + 3_600_000L,
                        from = now,
                        until = now + 8 * 3_600_000L,
                        title = "Comet 220P/McNaught",
                        detail = "Magnitude 9.5, from 39 observers within the last few days. " +
                            "Highest at 46 degrees in the south.",
                        visibility = Visibility.Good,
                    ),
                ),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("Comet 220P/McNaught").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("46 degrees in the south", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The reason is shown where there is one. "Washed out by the moon" is the
     * difference between a wasted walk and a good one, so it belongs on the
     * card rather than only in the verdict.
     */
    @Test
    fun saysWhyACometIsOnlyMarginal() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = listOf(
                    SkyEvent(
                        kind = EventKind.Comet,
                        at = now + 3_600_000L,
                        from = now,
                        until = now + 8 * 3_600_000L,
                        title = "Comet 10P/Tempel",
                        detail = "Magnitude 8.3, predicted rather than measured.",
                        visibility = Visibility.Marginal(
                            "It only reaches 4 degrees, low in the south.",
                        ),
                    ),
                ),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("It only reaches 4 degrees", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
