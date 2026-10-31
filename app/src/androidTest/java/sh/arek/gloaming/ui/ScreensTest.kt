package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.BuildConfig
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.VerdictChannel
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.Calibration
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Recommendations
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
 * and not about which tab it is behind. GloamingNavTest covers the navigation.
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
    private var exemptionsAsked = 0
    private var notificationsAsked = 0
    private var verdictsCleared = 0

    /** The last value the watch button asked for, or null if it was never pressed. */
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
    private var cadence: LocationCadence? = null
    private var detected = 0
    private var everyEvent: Boolean? = null
    private var everyConstellation: Boolean? = null
    private var cometMagnitude: Int? = null
    private var cometMoon: Int? = null
    private var logRetention: Int? = null
    private var threshold: Int? = null
    private var cloudTolerance: Int? = null
    private var calendarSync: CalendarSync? = null
    private var verdict: Answer? = null
    private var automatic: Boolean? = null
    private var exported = 0
    private var cleared = 0


    private fun show(
        settings: UserSettings = located,
        alertState: AlertState = AlertState(),
        batteryExempt: Boolean = true,
        notificationsAllowed: Boolean = true,
        checking: Boolean = false,
        darkness: Darkness? = null,
        powerSaveMode: Boolean = false,
        watching: Boolean = false,
        watchingUntil: Long? = null,
        logSizeBytes: Long = 0L,
        locationNote: String? = null,
        backgroundLocationAllowed: Boolean = false,
        tonight: TonightList? = null,
        outlook: OutlookReport? = null,
        calendarName: String? = null,
        pendingVerdict: Conditions? = null,
        recommendations: Recommendations? = null,
        verdicts: List<Verdict> = emptyList(),
        downloads: List<DownloadRow> = emptyList(),
        downloading: List<String> = emptyList(),
        elementsFailure: String? = null,
    ) {
        compose.setContent {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Follows the retention and the cadence the screen reports, as
                // the real one does, so a test can pick a preset and see what
                // comes next.
                var shown by remember { mutableStateOf(settings) }
                val state = MainUiState(
                    settings = shown,
                    alertState = alertState,
                    latitudeInput = "55.95",
                    longitudeInput = "-3.19",
                    quietFromInput = "23:00",
                    quietUntilInput = "07:00",
                    batteryExempt = batteryExempt,
                    notificationsAllowed = notificationsAllowed,
                    nowMillis = now,
                    checking = checking,
                    darkness = darkness,
                    powerSaveMode = powerSaveMode,
                    watching = watching,
                    watchingUntil = watchingUntil,
                    logSizeBytes = logSizeBytes,
                    locationNote = locationNote,
                    backgroundLocationAllowed = backgroundLocationAllowed,
                    tonight = tonight,
                    outlook = outlook,
                    calendarName = calendarName,
                    pendingVerdict = pendingVerdict,
                    recommendations = recommendations,
                    verdicts = verdicts,
                    downloads = downloads,
                    downloading = downloading,
                    elementsFailure = elementsFailure,
                )

                val tonightActions = UiActions(
                    onVerdict = { verdict = it },
                    onWatchingChange = { watchRequest = it },
                    onWatchDurationChange = { watchDuration = it },
                    onPollNow = { polled++ },
                    onRequestBatteryExemption = { exemptionsAsked++ },
                    onRequestNotifications = { notificationsAsked++ },
                )
                TonightContent(state = state, actions = tonightActions)
                // Pinned under the scroll on the real screen; here it follows
                // the content, because these tests are about what it says.
                WatchButton(state = state, actions = tonightActions)

                SettingsContent(
                    state = state,
                    actions = UiActions(
                        onThresholdChange = { threshold = it },
                        onPreciseBeforeUnlockChange = { precise = it },
                        onCloudToleranceChange = { cloudTolerance = it },
                        onShowEveryConstellationChange = { everyConstellation = it },
                        onLogRetentionChange = {
                            logRetention = it
                            shown = shown.copy(logRetentionDays = it)
                        },
                        onExportLog = { exported++ },
                        onClearLog = { cleared++ },
                        onAuroraIntervalChange = { auroraInterval = it },
                        onIssIntervalChange = { issInterval = it },
                        onFollowStationChange = { followStation = it },
                        onPassLeadChange = { passLead = it },
                        onMinimumElevationChange = { minimumElevation = it },
                        onLiveLeadChange = { liveLead = it },
                        onEventAlertsChange = { eventAlerts = it },
                        onEventHeadsUpChange = { eventHeadsUp = it },
                        onEventLeadChange = { eventLead = it },
                        onNotifyEveryEventChange = { everyEvent = it },
                        onCalendarSyncChange = { calendarSync = it },
                        onAutomaticSettingsChange = { automatic = it },
                        onClearVerdicts = { verdictsCleared++ },
                        onCometMagnitudeChange = { cometMagnitude = it },
                        onCometMoonChange = { cometMoon = it },
                        onLocationModeChange = { locationMode = it },
                        onLocationCadenceChange = {
                            cadence = it
                            shown = shown.copy(locationCadence = it)
                        },
                        onDetectLocation = { detected++ },
                    ),
                )
            }
        }
    }

    @Test
    fun showsTheProbabilityAndItsAge() {
        show(alertState = AlertState(lastProbability = 42, lastPollAt = now - 10 * 60_000L))

        compose.onNodeWithText("Aurora").assertExists()
        compose.onNodeWithText("42% probability here").assertExists()
        compose.onNodeWithTag(STATUS_LINE_TAG)
            .assertTextContains("Checked 10 minutes ago", substring = true)
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

        compose.onNodeWithTag(STATUS_LINE_TAG).assertTextContains("Not checked yet", substring = true)
    }

    /** The operational chrome is a tap away, behind the line that says how fresh things are. */
    @Test
    fun theStatusLineOpensTheChecksSheet() {
        show()
        compose.onNodeWithTag(CHECKS_SHEET_TAG).assertDoesNotExist()

        openChecks()

        compose.onNodeWithTag(CHECKS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHECK_NOW_TAG).assertIsDisplayed()
    }

    // Scrolled to first. The button sits below the fold on a phone, and a
    // click aimed at a node outside the scroll viewport never reaches it -
    // which is how the busy-button test below passed vacuously at first.
    @Test
    fun checkNowAsksForACheck() {
        show()
        openChecks()

        compose.onNodeWithTag(CHECK_NOW_TAG).performScrollTo().performClick()

        assertEquals(1, polled)
    }

    /**
     * A button that looks idle while a check is running reads as a broken
     * button, and a second press would only queue the same work again.
     */
    @Test
    fun checkNowIsBusyWhileChecking() {
        show(checking = true)
        openChecks()

        compose.onNodeWithText("Checking...").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Checking...").performClick()

        assertEquals(0, polled)
    }

    @Test
    fun checkNowIsAvailableWhenIdle() {
        show()
        openChecks()

        compose.onNodeWithText("Check now").performScrollTo().assertIsEnabled()
    }

    /** Pulling Tonight down is the other way to ask, and asks the same thing. */
    @Test
    fun pullingDownAsksForACheck() {
        compose.setContent {
            TonightScreen(
                state = MainUiState(
                    settings = located,
                    alertState = AlertState(),
                    latitudeInput = "55.95",
                    longitudeInput = "-3.19",
                    quietFromInput = "23:00",
                    quietUntilInput = "07:00",
                    batteryExempt = true,
                    notificationsAllowed = true,
                    nowMillis = now,
                ),
                actions = UiActions(onPollNow = { polled++ }),
            )
        }

        compose.onNodeWithTag(TONIGHT_SCROLL_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, polled)
    }

    /**
     * A failed check says so where it is seen, and says what failed where
     * there is room to.
     */
    @Test
    fun reportsAFailedCheckRatherThanHidingIt() {
        show(alertState = AlertState(lastPollAt = now - 60_000L, lastError = "offline"))

        compose.onNodeWithTag(STATUS_LINE_TAG)
            .assertTextContains("The last check failed.", substring = true)
        openChecks()
        compose.onNodeWithText("Last check failed: offline").performScrollTo().assertIsDisplayed()
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

        compose.onNodeWithText("Checked 11 hours ago", substring = true).assertExists()
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
     * as the "check now" button that ran nothing. Idle, it opens the choice of
     * how long, and starting is a second, deliberate tap.
     */
    @Test
    fun watchTonightOffersTheDurationsAndStarts() {
        show(watching = false)

        compose.onNodeWithTag(WATCH_BUTTON_TAG)
            .assertTextContains("Watch tonight")
            .assertTextContains("Checks every 2 minutes for 4 hours")
            .performScrollTo()
            .performClick()

        compose.onNodeWithTag(WATCH_SHEET_TAG).assertIsDisplayed()
        // Inside the sheet: the settings below carry "1h" and "2h" of their own.
        val inSheet = hasAnyAncestor(hasTestTag(WATCH_SHEET_TAG))
        for (label in listOf("1h", "2h", "4h", "8h", "Dawn")) {
            compose.onNode(hasText(label) and inSheet).assertExists()
        }
        assertEquals(null, watchRequest)

        compose.onNode(hasText("Dawn") and inSheet).performClick()
        compose.onNodeWithText("Start watching").performScrollTo().performClick()

        assertEquals(WatchDuration.UntilLight, watchDuration)
        assertEquals(true, watchRequest)
    }

    /** Running, it says when it will stop on its own, and a tap stops it now. */
    @Test
    fun aRunningWatchSaysUntilWhenAndStops() {
        val until = now + 4 * 3_600_000L
        show(watching = true, watchingUntil = until)

        compose.onNodeWithTag(WATCH_BUTTON_TAG)
            .assertTextContains(describeWatching(until))
            .performScrollTo()
            .performClick()

        assertEquals(false, watchRequest)
        compose.onNodeWithTag(WATCH_SHEET_TAG).assertDoesNotExist()
    }

    /**
     * The saver notice is raised only when the saver is actually on. A standing
     * warning about a mode nobody has enabled trains people to ignore the
     * warnings that matter.
     */
    @Test
    fun mentionsBatterySaverOnlyWhileItIsOn() {
        show(powerSaveMode = false)
        openChecks()
        compose.onNodeWithText("Battery saver is on.", substring = true).assertDoesNotExist()
    }

    @Test
    fun warnsAboutBatterySaverWhenItIsOn() {
        show(powerSaveMode = true)
        openChecks()

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
     * Which build the phone is on, at the very bottom.
     *
     * The app arrives as an APK rather than from a store, so nothing else can
     * answer that question: there is no listing to read the version off and no
     * background update to make it moot. A phone can sit on one build for
     * months, and the report that comes back about a missed alert is worth
     * having only if it says which build missed it.
     */
    @Test
    fun showsWhichBuildTheAppIs() {
        show()

        compose.onNodeWithText(
            versionLine(
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.GIT_COMMIT,
                BuildConfig.GIT_MODIFIED,
                BuildConfig.DEBUG,
            ),
        ).performScrollTo().assertIsDisplayed()
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
     * screen has to say so, in place of the status line, and offer the way to
     * grant it.
     */
    @Test
    fun aMissingExemptionReplacesTheStatusLineWithABanner() {
        show(batteryExempt = false)

        compose.onNodeWithTag(STATUS_LINE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(ALERT_BANNER_TAG)
            .assertTextContains("Gloaming cannot alert you overnight yet")
        compose.onNodeWithText(
            "Android holds background checks until the phone next wakes, which overnight " +
                "means hours. Allowing them is required, not optional.",
        ).assertExists()
        compose.onNodeWithText("Allow background checks").performScrollTo().performClick()

        assertEquals(1, exemptionsAsked)
    }

    /** Without notifications the app can watch but not tell, and it says so the same way. */
    @Test
    fun notificationsOffGetsItsOwnBanner() {
        show(notificationsAllowed = false)

        compose.onNodeWithTag(STATUS_LINE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("Gloaming cannot alert you overnight yet").assertDoesNotExist()
        compose.onNodeWithText("Allow notifications").performScrollTo().performClick()

        assertEquals(1, notificationsAsked)
    }

    /** The opening sentence, and the line under it, are the first thing about the sky. */
    @Test
    fun showsTheOutlookHeadline() {
        show(
            outlook = OutlookReport(
                outlook = Outlook.Overcast(nextClear = null, missed = null),
                excluded = emptyList(),
                auroraProbability = 12,
                threshold = 40,
            ),
        )

        compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG)
            .assertTextEquals("Overcast all night. Nothing worth going out for.")
        compose.onNodeWithText("The forecast has no clear spell yet.").assertIsDisplayed()
        compose.onNodeWithText(describeDate(now)).assertIsDisplayed()
    }

    /** No outlook is guessed at before one has been worked out. */
    @Test
    fun saysNothingBeforeTheOutlookIsWorkedOut() {
        show(outlook = null)

        compose.onNodeWithTag(OUTLOOK_HEADLINE_TAG).assertDoesNotExist()
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

    /**
     * By tag, not by tree order. "Off" reached this by position for as long as
     * it was the only such button on the screen, and stopped being right the
     * moment the calendar destinations added another one above it - which is
     * exactly the failure the tagged choice rows exist to prevent.
     */
    @Test
    fun reportsTheRetentionTheUserPicked() {
        show(logSizeBytes = 2048)

        compose.onNodeWithTag(choiceTag("Keep", "Off")).performScrollTo().performClick()

        assertEquals(0, logRetention)
    }

    /** Any number of days up to a year, for somebody the presets do not suit. */
    @Test
    fun savesACustomRetentionOnceItIsAValidNumber() {
        show(logSizeBytes = 2048)

        compose.onNodeWithTag(LOG_RETENTION_CUSTOM_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LOG_RETENTION_FIELD_TAG).performScrollTo().performTextReplacement("30")

        assertEquals(30, logRetention)
    }

    @Test
    fun marksCustomSelectedOnlyWhenTheRetentionIsNotAPreset() {
        show(settings = located.copy(logRetentionDays = 30))
        compose.onNodeWithTag(LOG_RETENTION_CUSTOM_TAG).performScrollTo().assertIsSelected()
    }

    @Test
    fun leavesCustomUnselectedForAPresetRetention() {
        show(settings = located.copy(logRetentionDays = 7))
        compose.onNodeWithTag(LOG_RETENTION_CUSTOM_TAG).performScrollTo().assertIsNotSelected()
    }

    @Test
    fun seedsTheCustomFieldFromTheRetentionInForce() {
        show(settings = located.copy(logRetentionDays = 7))

        compose.onNodeWithTag(choiceTag("Keep", "3d")).performScrollTo().performClick()
        compose.onNodeWithTag(LOG_RETENTION_CUSTOM_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(LOG_RETENTION_FIELD_TAG).performScrollTo()
            .assertTextEquals("Days", "3")
    }

    @Test
    fun saysSoAndSavesNothingForAnOutOfRangeRetention() {
        show(logSizeBytes = 2048)

        compose.onNodeWithTag(LOG_RETENTION_CUSTOM_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LOG_RETENTION_FIELD_TAG).performScrollTo().performTextReplacement("400")

        assertEquals(null, logRetention)
        compose.onNodeWithText("Enter a whole number of days between 1 and 365.")
            .performScrollTo()
            .assertIsDisplayed()
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
        openWatch()

        compose.onNodeWithText("Stops on its own after: 4 hours").assertExists()
        for (label in listOf("1h", "2h", "4h", "8h", "Dawn")) {
            compose.onAllNodesWithText(label).onFirst().assertExists()
        }
    }

    @Test
    fun reportsTheWatchDurationTheUserPicked() {
        show()
        openWatch()

        compose.onAllNodesWithText("Dawn").onFirst().performScrollTo().performClick()

        assertEquals(WatchDuration.UntilLight, watchDuration)
    }

    /** The midsummer case is stated rather than discovered at three in the morning. */
    @Test
    fun explainsWhatHappensWhenTheSkyNeverDarkens() {
        show(settings = located.copy(watchDuration = WatchDuration.UntilLight))
        openWatch()

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

        compose.onNodeWithText("Update now").performScrollTo().assertIsDisplayed()
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
     * only in the store listing: roughly, and never the GPS, whichever cadence
     * is chosen.
     */
    @Test
    fun saysWhatItWillAndWillNotDoWithYourPosition() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("Approximate only", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("never turns the GPS on", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A preset is reported as a request: asking Android for more is the caller's job. */
    @Test
    fun choosingEverySixHoursAsksForThatCadence() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "6").performScrollTo().performClick()

        assertEquals(LocationCadence.Every(6), cadence)
    }

    @Test
    fun marksTheCadenceInForceSelected() {
        show(
            settings = located.copy(
                locationMode = LocationMode.Automatic,
                locationCadence = LocationCadence.Every(24),
            ),
        )

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "24").performScrollTo().assertIsSelected()
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "open").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").performScrollTo().assertIsNotSelected()
    }

    /**
     * Any whole number of hours the presets miss, sent when the typing is
     * finished rather than on every keystroke: "12" passes through "1", and
     * each value sent can bring up Android's own prompt.
     */
    @Test
    fun aCustomIntervalIsSentOnDoneOnceValid() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").performScrollTo().performClick()
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().performTextReplacement("3")

        assertEquals(null, cadence)

        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performImeAction()

        assertEquals(LocationCadence.Every(3), cadence)
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").performScrollTo().assertIsSelected()
    }

    @Test
    fun anInvalidCustomIntervalIsNeverSent() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").performScrollTo().performClick()
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performImeAction()

        assertEquals(null, cadence)
        compose.onNodeWithText("Enter a whole number of hours between 1 and 48.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** The cadence changed without anybody touching it, so the card says why. */
    @Test
    fun saysWhenBackgroundLocationWasTakenAway() {
        show(
            settings = located.copy(
                locationMode = LocationMode.Automatic,
                backgroundLocationRevoked = true,
            ),
        )

        compose.onNodeWithText(
            "Android no longer lets Gloaming read your position in the background, so it " +
                "now updates each time you open the app.",
        ).performScrollTo().assertIsDisplayed()
    }

    private val allTheTimeWarning = "Android will ask you to allow location all the time. " +
        "Choose approximate there too; Gloaming never asks for more."

    /** Warned before Android's own prompt arrives, so the system page is no surprise. */
    @Test
    fun warnsOfTheAllTheTimePromptBeforeItIsAllowed() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText(allTheTimeWarning).performScrollTo().assertIsDisplayed()
    }

    /** Once it has been answered yes there is nothing left for Android to ask. */
    @Test
    fun saysNothingOfThePromptOnceItIsAllowed() {
        show(
            settings = located.copy(locationMode = LocationMode.Automatic),
            backgroundLocationAllowed = true,
        )

        compose.onAllNodesWithText(allTheTimeWarning).assertCountEquals(0)
    }

    @Test
    fun saysWhenThePositionWasLastUpdated() {
        show(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("Not updated yet").performScrollTo().assertIsDisplayed()
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

        compose.onNodeWithText("Only the events you star in the Ahead section will interrupt.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theEveryEventSwitchIsReported() {
        show()

        compose.onNodeWithTag(EVERY_EVENT_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(false, everyEvent)
    }

    /** Trimmed by default, so the switch turns the full list on. */
    @Test
    fun theEveryConstellationSwitchIsReported() {
        show()

        compose.onNodeWithTag(EVERY_CONSTELLATION_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(true, everyConstellation)
    }

    /**
     * Three destinations rather than a switch. "Put it in my calendar" means
     * two different things to two reasonable people - a local calendar that
     * syncs nowhere, or the one they already live out of - and choosing for
     * them would be wrong either way.
     */
    @Test
    fun offersEveryCalendarDestination() {
        show()

        for (option in CalendarSync.entries) {
            compose.onNodeWithTag(calendarSyncTag(option)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun reportsTheCalendarDestinationTheUserPicked() {
        show()

        compose.onNodeWithTag(calendarSyncTag(CalendarSync.OwnCalendar))
            .performScrollTo()
            .performClick()

        assertEquals(CalendarSync.OwnCalendar, calendarSync)
    }

    /**
     * The trade is stated rather than discovered. Writing to a calendar that
     * syncs to a work account sends these entries to that server, which is the
     * user's existing arrangement but still their choice to make knowingly.
     */
    @Test
    fun saysWhereTheEntriesWouldActuallyGo() {
        show(settings = located.copy(calendarSync = CalendarSync.DeviceCalendar))

        compose.onNodeWithText("go to that server", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A phone with four calendars needs to be told which one it found. */
    @Test
    fun namesTheCalendarItIsWritingTo() {
        show(
            settings = located.copy(calendarSync = CalendarSync.OwnCalendar),
            calendarName = "Gloaming sky events",
        )

        compose.onNodeWithText("Gloaming sky events", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Off is the default, and says so rather than saying nothing. */
    @Test
    fun saysNothingIsBeingWrittenWhenSyncIsOff() {
        show()

        compose.onNodeWithText("Nothing is written anywhere", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- calibration ---

    /**
     * The default thresholds have been guesses since the first version, and the
     * documents have said so in writing throughout. This is the switch that
     * lets the app work them out from the nights the user actually had.
     */
    @Test
    fun offersToSetTheThresholdsAutomatically() {
        show()

        compose.onNodeWithTag(AUTOMATIC_SETTINGS_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(true, automatic)
    }

    /**
     * With automatic mode off the app still says what it would have chosen.
     * That is most of the value of calibration at none of its cost: the app
     * makes its case underneath the control and the user decides.
     */
    @Test
    fun saysWhatItWouldHaveChosenEvenWhenItIsNotChoosing() {
        show(recommendations = calibrated())

        // Several rows carry a note, which is the point - so the assertion is
        // that at least one is there, not that exactly one is.
        compose.onAllNodesWithText("Your nights suggest", substring = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * With it on, the choices are taken away rather than greyed out. A row of
     * buttons that cannot be pressed is a row somebody will press and then call
     * broken.
     */
    @Test
    fun takesTheChoicesAwayWhileItIsSettingThemItself() {
        show(
            settings = located.copy(automaticSettings = true),
            recommendations = calibrated(),
        )

        // Several rows carry a note, which is the point - so the assertion is
        // that at least one is there, not that exactly one is.
        compose.onAllNodesWithText("Set for you from", substring = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(choiceTag("Alert above", "70 per cent")).assertDoesNotExist()
    }

    /** The reasoning is stated, not merely the number. */
    @Test
    fun explainsWhyItChoseWhatItChose() {
        show(
            settings = located.copy(automaticSettings = true),
            recommendations = calibrated(),
        )

        // Several rows carry a note, which is the point - so the assertion is
        // that at least one is there, not that exactly one is.
        compose.onAllNodesWithText("worth going out for", substring = true).onFirst()
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Nothing recorded and nothing being managed: no note under every control. */
    @Test
    fun saysNothingUnderTheControlsBeforeAnyVerdicts() {
        show(recommendations = Calibration.of(emptyList()))

        compose.onNodeWithText("Your nights suggest", substring = true).assertDoesNotExist()
    }

    /** A calibration built from real verdicts, so the notes have something to say. */
    private fun calibrated(): Recommendations = Calibration.of(
        List(8) {
            Verdict(
                answeredAtMillis = now + it,
                answer = if (it < 6) Answer.Worth else Answer.NotWorth,
                conditions = Conditions(
                    atMillis = now,
                    channel = VerdictChannel.Aurora,
                    probability = 60,
                    cloudTotal = 20,
                ),
            )
        },
    )

    // --- was it worth it ---

    /**
     * The half of the question the notification cannot ask.
     *
     * Somebody woken at two in the morning swipes the alert away and forms an
     * opinion about it at nine, by which time the buttons are gone. Sec. 15
     * has wanted this record since the first version and nothing was collecting
     * it.
     */
    @Test
    fun asksWhetherAnUnansweredAlertWasWorthIt() {
        show(pendingVerdict = alerted(now - 3 * 3_600_000L))

        compose.onNodeWithText("Was it worth it?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gloaming alerted you at", substring = true).assertExists()
    }

    /**
     * Three answers here where the notification offers two. "Did not look" is
     * the honest answer for a night somebody slept through, and counting those
     * as failures would be the single easiest way to draw the wrong conclusion
     * from the whole record.
     */
    @Test
    fun offersTheAnswerNobodyCanGiveInTheDark() {
        show(pendingVerdict = alerted(now - 3 * 3_600_000L))

        compose.onNodeWithTag(verdictTag(Answer.DidNotLook)).performScrollTo().performClick()

        assertEquals(Answer.DidNotLook, verdict)
    }

    @Test
    fun reportsTheVerdictTheUserGave() {
        show(pendingVerdict = alerted(now - 3 * 3_600_000L))

        compose.onNodeWithTag(verdictTag(Answer.Worth)).performScrollTo().performClick()

        assertEquals(Answer.Worth, verdict)
    }

    /** A question about last night goes above everything about tonight. */
    @Test
    fun theWorthItQuestionStillComesFirst() {
        show(pendingVerdict = alerted(now - 3 * 3_600_000L))

        val question = compose.onNodeWithText("Was it worth it?").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag(STATUS_LINE_TAG).getUnclippedBoundsInRoot()

        assertTrue(question.top < status.top)
    }

    /** An alert from ten minutes ago has no answer yet, so there is no question. */
    @Test
    fun waitsBeforeAskingAboutAnAlertThatHasJustGoneOut() {
        show(pendingVerdict = alerted(now - 10 * 60_000L))

        compose.onNodeWithText("Was it worth it?").assertDoesNotExist()
    }

    @Test
    fun asksNothingWhenEveryAlertHasBeenAnsweredFor() {
        show()

        compose.onNodeWithText("Was it worth it?").assertDoesNotExist()
    }

    private fun alerted(at: Long) = Conditions(
        atMillis = at,
        channel = VerdictChannel.Aurora,
        probability = 45,
        threshold = 30,
        subject = "Aurora",
    )

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

    /**
     * The cloud the app gates every alert on, said out loud for the first time.
     *
     * "80% cloud" is the wrong summary of a night that is overcast until
     * midnight and then clear for three hours, so the gap is the line that
     * matters rather than the number above it.
     */
    @Test
    fun showsTheCloudAndTheGapInIt() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                cloud = CloudCover(total = 80, low = 60, mid = 20, high = 10),
                clearSpell = ClearSpell(
                    from = now + 3 * 3_600_000L,
                    until = now + 5 * 3_600_000L,
                    underway = false,
                    allNight = false,
                ),
            ),
        )

        compose.onNodeWithText("Sky now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("80% cloud overhead.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Clearer from about", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * CC BY 4.0 requires the credit beside the data it covers, and this is the
     * first place in the app that shows a forecast rather than acting on one
     * out of sight.
     */
    @Test
    fun creditsOpenMeteoBesideTheForecast() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                cloud = CloudCover(total = 10, low = 0, mid = 0, high = 10),
            ),
        )

        compose.onNodeWithText("Sky now").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Weather data by Open-Meteo.com").onFirst().assertExists()
    }

    /** Nothing held and nothing forecast is not a card saying "unknown". */
    @Test
    fun leavesOutTheSkyCardWithNoReadingAtAll() {
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

        compose.onNodeWithText("Sky now").assertDoesNotExist()
    }

    /**
     * The moon, which the app has weighed against every comet since comets went
     * in and never once mentioned. What it reports is how much of the night the
     * moon spoils rather than how bright it is - a full moon that has set by
     * midnight costs far less than a half moon up all night.
     */
    @Test
    fun showsTheMoonAndWhatItCostsTheNight() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                moon = MoonTonight(
                    phaseDegrees = 180.0,
                    illumination = 0.98,
                    upFraction = 1.0,
                    interference = 0.98,
                    risesAt = null,
                    setsAt = null,
                ),
            ),
        )

        compose.onNodeWithText("The moon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Full moon, 98% lit.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("washes out about 98%", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Both thresholds. Civil twilight is where an aurora starts to show;
     * astronomical darkness is a different night, and at these latitudes
     * between May and August it does not arrive at all - which is why a summer
     * meteor shower disappoints.
     */
    @Test
    fun showsBothDarknessThresholds() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                darkestFrom = now + 3_600_000L,
                darkestUntil = now + 7 * 3_600_000L,
            ),
        )

        compose.onNodeWithText("Dark from", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fully dark from", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun saysWhenItNeverGetsFullyDark() {
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

        compose.onNodeWithText("It never gets fully dark here", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The one card on the tab whose answer is certain. Cloud is a forecast, an
     * aurora is a probability and a comet is usually a disappointment; a planet
     * is where the arithmetic says it is, and will be there.
     */
    @Test
    fun showsThePlanetsUpTonight() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                planets = listOf(
                    PlanetTonight(
                        body = SkyBody.Jupiter,
                        peakAltitude = 46.4,
                        peakAt = now + 3 * 3_600_000L,
                        peakAzimuth = 178.0,
                        magnitude = -2.46,
                        risesAt = null,
                        setsAt = null,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Planets tonight").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Jupiter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Highest 46 degrees south", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The other card whose answer is certain, and the only one that tries to
     * teach rather than report: the star-hop is there for somebody who cannot
     * yet find the pattern the rest of the line is describing.
     */
    @Test
    fun showsTheConstellationsUpTonight() {
        show(tonight = withConstellations(isAtItsBest = false))

        compose.onNodeWithText("Constellations tonight").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Orion").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Highest 39 degrees south", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("belt", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** The one night of the year says so, because it is why to go out tonight. */
    @Test
    fun marksTheConstellationAtItsBestTonight() {
        show(tonight = withConstellations(isAtItsBest = true))

        compose.onNodeWithText("At its best tonight", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Absent rather than empty, like every card on the tab but the satellites. */
    @Test
    fun leavesOutTheConstellationsCardWhenNoneAreUp() {
        show(tonight = withConstellations(isAtItsBest = false).copy(constellations = emptyList()))

        compose.onNodeWithText("Constellations tonight").assertDoesNotExist()
    }

    /** The ones held back are still said, or the card would simply lose them. */
    @Test
    fun namesTheConstellationsThatNeverSet() {
        show(
            tonight = withConstellations(isAtItsBest = false)
                .copy(constellationsAlwaysUp = listOf("Ursa Major", "Cassiopeia")),
        )

        // Matched on the opening rather than on "every night of the year",
        // which the settings card explaining this control also says - and
        // show() renders both screens into one column.
        compose.onNodeWithText("Also up, as they are", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The card that already taught, now able to explain what it taught in.
     *
     * Cassiopeia's star-hop is "the far side of Polaris from the Plough",
     * which is no use whatever to somebody who knows neither - and that is
     * precisely the reader a star-hop is written for. The words are under the
     * card that used them rather than a tab away, because the question is
     * asked while looking at the sentence that raised it.
     */
    @Test
    fun explainsTheWordsTheConstellationsCardUses() {
        show(tonight = withConstellations(isAtItsBest = false))

        compose.onNodeWithTag(helpTag(HelpTopic.Constellations)).performScrollTo().performClick()

        compose.onNodeWithText("Polaris").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The Plough").performScrollTo().assertIsDisplayed()
    }

    /**
     * Closed until asked for. The card's own answer is what somebody came for,
     * and a glossary in front of it is a glossary between them and tonight.
     */
    @Test
    fun keepsTheExplanationOutOfTheWayUntilItIsWanted() {
        show(tonight = withConstellations(isAtItsBest = false))

        compose.onNodeWithText("Polaris").assertDoesNotExist()
    }

    /** And closes again, or the button is a one-way door. */
    @Test
    fun putsTheExplanationAwayAgain() {
        val explain = compose.onNodeWithTag(helpTag(HelpTopic.Constellations))
        show(tonight = withConstellations(isAtItsBest = false))

        explain.performScrollTo().performClick()
        explain.performScrollTo().performClick()

        compose.onNodeWithText("Polaris").assertDoesNotExist()
    }

    /**
     * Each card answers for its own words and no others. The satellites card
     * has no business explaining a star-hop, and a single shared sheet of
     * every term in the app would be the Guide tab with extra steps.
     */
    @Test
    fun explainsOnlyTheWordsTheCardItselfUses() {
        show(tonight = withConstellations(isAtItsBest = false))

        compose.onNodeWithTag(helpTag(HelpTopic.Satellites)).performScrollTo().performClick()

        compose.onNodeWithText("A pass").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Polaris").assertDoesNotExist()
    }

    private fun withConstellations(isAtItsBest: Boolean) = TonightList(
        darkFrom = now,
        darkUntil = now + 8 * 3_600_000L,
        passes = emptyList(),
        bestPasses = emptyList(),
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
        constellations = listOf(
            ConstellationTonight(
                constellation = Constellations.WATCHED.first { it.name == "Orion" },
                peakAltitude = 39.0,
                peakAt = now + 3 * 3_600_000L,
                peakAzimuth = 180.0,
                isCircumpolar = false,
                isAtItsBest = isAtItsBest,
            ),
        ),
    )

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
     * The caveat travels with a dated event too, not only with a comet.
     *
     * This is the whole reason Tonight keeps marginal events rather than
     * dropping them - "the moon only reaches 8 degrees at maximum, in a sky
     * that is not fully dark" is what somebody plans around - and the card was
     * quietly not carrying it. A partial eclipse promised on the home screen
     * and explained only in the calendar is worse than one not promised at all.
     */
    @Test
    fun saysWhyAnEventTonightIsOnlyMarginal() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = listOf(
                    SkyEvent(
                        kind = EventKind.LunarEclipse,
                        at = now + 3_600_000L,
                        from = now,
                        until = now + 4 * 3_600_000L,
                        title = "Partial eclipse of the moon",
                        detail = "18 per cent of the moon in shadow at maximum.",
                        visibility = Visibility.Marginal(
                            "The moon is only 8 degrees up at maximum, in a sky that is " +
                                "not fully dark.",
                        ),
                    ),
                ),
                comets = emptyList(),
                noctilucent = null,
            ),
        )

        compose.onNodeWithText("Partial eclipse of the moon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("only 8 degrees up at maximum", substring = true)
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

    /**
     * The record behind the recommendations, on the screen that makes them.
     *
     * The app collected these for months and showed only what it concluded from
     * them, which asks the user to take a moved threshold on trust - the exact
     * thing the automatic-settings card was written not to do.
     */
    @Test
    fun showsWhatTheAlertsWereWorth() {
        show(verdicts = listOf(auroraVerdict(Answer.Worth), auroraVerdict(Answer.DidNotLook)))

        compose.onNodeWithText("What your alerts were worth").performScrollTo().assertExists()
        compose.onNodeWithText(
            "Aurora - 2 alerts: 1 worth going out for, 1 you did not look at",
        ).performScrollTo().assertExists()
        compose.onAllNodesWithText("54% from AuroraWatch UK", substring = true)
            .onFirst().performScrollTo().assertExists()
    }

    /** Nothing recorded is a state to explain, not an empty card. */
    @Test
    fun saysHowVerdictsGetThereWhenNoneHaveBeenGiven() {
        show(verdicts = emptyList())

        compose.onNodeWithText("Nothing answered yet.", substring = true)
            .performScrollTo().assertExists()
    }

    /** Somebody's own account of their own nights, theirs to delete. */
    @Test
    fun clearsTheRecordWhenAsked() {
        show(verdicts = listOf(auroraVerdict(Answer.Worth)))

        compose.onNodeWithTag(CLEAR_VERDICTS_TAG).performScrollTo().performClick()

        assertEquals(1, verdictsCleared)
    }

    private fun auroraVerdict(answer: Answer) = Verdict(
        answeredAtMillis = now,
        answer = answer,
        conditions = Conditions(
            atMillis = now - 100_000L,
            channel = VerdictChannel.Aurora,
            probability = 54,
            source = "AURORAWATCH",
            kp = 5.3,
            bz = -8.1,
            cloudTotal = 22,
            threshold = 30,
        ),
    )

    // --- what the app has been downloading ---

    /**
     * The card that would have caught the fortnight. Stale elements have to say
     * so on the screen, because nothing else on it can: from the outside,
     * passes predicted from a two-week-old catalogue look exactly like passes.
     */
    @Test
    fun saysHowOldTheHeldDocumentsAre() {
        show(
            downloads = listOf(
                DownloadRow(
                    "Satellite elements",
                    listOf("Held copy is 14 days old"),
                    alarming = true,
                ),
            ),
        )
        openChecks()

        compose.onNodeWithText("Satellite elements").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Held copy is 14 days old").performScrollTo().assertIsDisplayed()
    }

    /** A failed fetch is shown rather than left in a log file nobody reads. */
    @Test
    fun showsWhyADownloadFailed() {
        show(
            downloads = listOf(
                DownloadRow(
                    "Space weather",
                    listOf("Failed 2 hours ago: GET status.json returned 503"),
                    alarming = true,
                ),
            ),
        )
        openChecks()

        compose.onNodeWithText("Failed 2 hours ago: GET status.json returned 503")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** While a check runs, the card names what is actually in flight. */
    @Test
    fun namesWhatIsDownloadingDuringACheck() {
        show(checking = true, downloading = listOf("Aurora grid", "Satellite elements"))
        openChecks()

        compose.onNodeWithText("Downloading: Aurora grid, Satellite elements")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * And says something even before the first request has been labelled -
     * in its own words. "Checking..." is the button's, directly above it in the
     * checks sheet, and two
     * nodes carrying it made this assertion ambiguous as well as the screen
     * repetitive.
     */
    @Test
    fun saysItIsStartingBeforeAnyRequestIsOpen() {
        show(checking = true)
        openChecks()

        compose.onNodeWithText("Starting...").performScrollTo().assertIsDisplayed()
    }

    /**
     * Quiet on an ordinary evening. It used to be a standing card, which on a
     * working install is six lines saying everything is fine - and the status
     * line raises nothing either.
     */
    @Test
    fun saysNothingAboutDownloadsWhenThereIsNothingToSay() {
        show(
            downloads = listOf(
                DownloadRow("Satellite elements", listOf("Held copy is current"), false),
            ),
        )

        compose.onNodeWithText("Something is wrong", substring = true).assertDoesNotExist()
        openChecks()
        compose.onNodeWithTag(DOWNLOADS_CARD_TAG).assertDoesNotExist()
    }

    /**
     * But unasked when something is wrong. A report that only speaks when
     * pressed would have sat silent through the whole fortnight that prompted
     * it, so the status line names what is wrong without being opened.
     */
    @Test
    fun speaksAboutDownloadsUnaskedWhenSomethingIsWrong() {
        show(
            downloads = listOf(
                DownloadRow("Satellite elements", listOf("Held copy is 14 days old"), true),
            ),
        )

        compose.onNodeWithTag(STATUS_LINE_TAG).assertTextContains(
            "Something is wrong with the satellite elements.",
            substring = true,
        )
    }

    /** The report itself is in the checks sheet, not on the face of Tonight. */
    @Test
    fun theDownloadsReportSitsInTheChecksSheet() {
        show(
            downloads = listOf(
                DownloadRow("Satellite elements", listOf("Held copy is 14 days old"), true),
            ),
        )
        compose.onNodeWithTag(DOWNLOADS_CARD_TAG).assertDoesNotExist()

        openChecks()

        compose.onNodeWithTag(DOWNLOADS_CARD_TAG).assertExists()
    }

    /**
     * "Nothing crossing" and "no catalogue to look in" are the same empty list
     * and must not be the same sentence. A device that has never managed to
     * download the elements was being told the sky was quiet.
     */
    @Test
    fun admitsItDoesNotKnowWhatIsCrossingWithoutTheElements() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                elementsHeld = false,
            ),
        )

        compose.onNodeWithText("Not worked out yet", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Nothing crossing tonight that clears the horizon in sunlight.")
            .assertDoesNotExist()
    }

    /** And with elements held, an empty night is still allowed to be empty. */
    @Test
    fun stillSaysTheSkyIsQuietWhenItHasTheElementsAndNothingCrosses() {
        show(
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                elementsHeld = true,
            ),
        )

        compose.onNodeWithText("Nothing crossing tonight that clears the horizon in sunlight.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A phone that has checked and still has no elements has a fault, and is
     * told so - unlike one on its first run, which has not failed at anything.
     */
    @Test
    fun reportsBrokenElementDownloadsDifferentlyFromAFirstRun() {
        show(
            alertState = AlertState(lastPollAt = now - 5 * 60_000L),
            tonight = TonightList(
                darkFrom = now,
                darkUntil = now + 8 * 3_600_000L,
                passes = emptyList(),
                bestPasses = emptyList(),
                events = emptyList(),
                comets = emptyList(),
                noctilucent = null,
                elementsHeld = false,
            ),
            elementsFailure = "GET sats.json returned 503",
        )

        compose.onNodeWithText("returned 503", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Not worked out yet", substring = true).assertDoesNotExist()
    }

    private fun openChecks() {
        compose.onNodeWithTag(STATUS_LINE_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openWatch() {
        compose.onNodeWithTag(WATCH_BUTTON_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }
}
