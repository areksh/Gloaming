package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.Graph
import sh.arek.gloaming.MainActivity
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.LocationGrantSource
import sh.arek.gloaming.domain.LocationGrants
import sh.arek.gloaming.work.LocationAlarm

/**
 * The three Settings pages that are about whether the app can do its job,
 * which have no control of their own to set: they report, and where Android
 * holds the grant they ask for it.
 *
 * Rendered one at a time here because their sentences and tags repeat what
 * Tonight and its checks sheet say, which would make [ScreensTest]'s stacked
 * column ambiguous. **These need the device unlocked**, for the reason recorded
 * there.
 */
class SettingsPagesTest {

    @get:Rule
    val compose = createComposeRule()

    private var exemptionsAsked = 0
    private var notificationsAsked = 0

    private fun show(
        page: SettingsPage,
        batteryExempt: Boolean = true,
        notificationsAllowed: Boolean = true,
        downloads: List<DownloadRow> = emptyList(),
    ) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SettingsPageContent(
                    page = page,
                    state = MainUiState(
                        settings = UserSettings(latitude = 55.95, longitude = -3.19),
                        alertState = AlertState(),
                        latitudeInput = "55.95",
                        longitudeInput = "-3.19",
                        quietFromInput = "23:00",
                        quietUntilInput = "07:00",
                        batteryExempt = batteryExempt,
                        notificationsAllowed = notificationsAllowed,
                        nowMillis = 1_800_000_000_000L,
                        downloads = downloads,
                    ),
                    actions = UiActions(
                        onRequestBatteryExemption = { exemptionsAsked++ },
                        onRequestNotifications = { notificationsAsked++ },
                    ),
                )
            }
        }
    }

    @Test
    fun backgroundChecksAskForTheExemptionWhenItIsMissing() {
        show(SettingsPage.BackgroundChecks, batteryExempt = false)

        compose.onNodeWithText("Not allowed.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).performScrollTo().performClick()

        assertEquals(1, exemptionsAsked)
    }

    @Test
    fun backgroundChecksOfferNothingToPressOnceAllowed() {
        show(SettingsPage.BackgroundChecks, batteryExempt = true)

        compose.onNodeWithText("Allowed.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).assertDoesNotExist()
    }

    @Test
    fun notificationsAskForPermissionWhenItIsMissing() {
        show(SettingsPage.Notifications, notificationsAllowed = false)

        compose.onNodeWithTag(ALLOW_NOTIFICATIONS_TAG).performScrollTo().performClick()

        assertEquals(1, notificationsAsked)
    }

    @Test
    fun notificationsOfferNothingToPressOnceAllowed() {
        show(SettingsPage.Notifications, notificationsAllowed = true)

        compose.onNodeWithText("On.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_NOTIFICATIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun downloadsShowEveryDocumentEvenWhenNoneIsWrong() {
        show(
            SettingsPage.Downloads,
            downloads = listOf(
                DownloadRow("Satellite elements", listOf("Held copy is current"), false),
            ),
        )

        compose.onNodeWithTag(DOWNLOADS_PAGE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Satellite elements").assertIsDisplayed()
        compose.onNodeWithText("Held copy is current").assertIsDisplayed()
    }

    @Test
    fun downloadsSayWhenThereAreNone() {
        show(SettingsPage.Downloads)

        compose.onNodeWithText("Nothing downloaded yet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(DOWNLOADS_PAGE_TAG).assertDoesNotExist()
    }

    // --- the location controls ---

    private var shown by mutableStateOf(
        MainUiState(
            settings = UserSettings(
                latitude = 55.95,
                longitude = -3.19,
                locationMode = LocationMode.Automatic,
            ),
            alertState = AlertState(),
            latitudeInput = "55.95",
            longitudeInput = "-3.19",
            quietFromInput = "23:00",
            quietUntilInput = "07:00",
            batteryExempt = true,
            notificationsAllowed = true,
            nowMillis = 1_800_000_000_000L,
        ),
    )

    /** The page and the index row for it, following the cadence the page reports. */
    private fun showLocation(backgroundLocationAllowed: Boolean) {
        shown = shown.copy(backgroundLocationAllowed = backgroundLocationAllowed)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SettingsPageContent(
                    page = SettingsPage.Location,
                    state = shown,
                    actions = UiActions(
                        onLocationCadenceChange = {
                            shown = shown.copy(
                                settings = shown.settings.copy(locationCadence = it),
                            )
                        },
                    ),
                )
                SettingsIndex(shown, onOpen = {})
            }
        }
    }

    @Test
    fun theLocationCardHasASwitchAndThreeBackgroundUpdateRows() {
        showLocation(backgroundLocationAllowed = true)

        compose.onNodeWithText("Use this phone's location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LOCATION_MODE_SWITCH_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Background updates").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off - when I open Gloaming").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Every hour").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Every ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(" hours").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun typingThreeHoursAndPressingDoneReadsBackInTheIndexSummary() {
        showLocation(backgroundLocationAllowed = true)

        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().performTextInput("3")
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performImeAction()

        compose.onNodeWithTag(settingsRowTag(SettingsPage.Location)).performScrollTo()
            .assertTextContains("Approximate, from the phone. Background updates every 3 hours", substring = true)
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").assertIsSelected()
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).assertTextContains("3")
    }

    @Test
    fun pickingEveryHourWithoutTheBackgroundGrantExplainsFirstAndCancelLeavesItOff() {
        showLocation(backgroundLocationAllowed = false)

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "1").performScrollTo().performClick()

        compose.onNodeWithText(cadenceExplainerText()).assertIsDisplayed()
        compose.onNodeWithTag(CADENCE_EXPLAINER_CONTINUE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CADENCE_EXPLAINER_CANCEL_TAG).performClick()

        compose.onNodeWithText(cadenceExplainerText()).assertDoesNotExist()
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "open").performScrollTo().assertIsSelected()
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "1").assertIsNotSelected()
    }

    @Test
    fun cancellingATypedIntervalClearsTheFieldAndLeavesItOff() {
        showLocation(backgroundLocationAllowed = false)

        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().performTextInput("3")
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performImeAction()

        compose.onNodeWithText(cadenceExplainerText()).assertIsDisplayed()
        compose.onNodeWithTag(CADENCE_EXPLAINER_CANCEL_TAG).performClick()

        compose.onNodeWithText(cadenceExplainerText()).assertDoesNotExist()
        compose.onNodeWithTag(CADENCE_HOURS_FIELD_TAG).performScrollTo().assertTextEquals("")
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "open").assertIsSelected()
        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "custom").assertIsNotSelected()
    }

    @Test
    fun continuingFromTheExplainerReportsTheCadencePicked() {
        showLocation(backgroundLocationAllowed = false)

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "1").performScrollTo().performClick()
        compose.onNodeWithTag(CADENCE_EXPLAINER_CONTINUE_TAG).performClick()

        compose.onNodeWithTag(CADENCE_TAG_PREFIX + "1").performScrollTo().assertIsSelected()
    }

    // --- coming back to the real activity after Android's settings changed ---

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var storedBefore: UserSettings? = null

    /**
     * Stores [claimed] over the phone's own settings, keeping its position so
     * nothing of the owner's is lost, and remembers what was there. Read and
     * written the way the app does; never cleared.
     *
     * Setup is marked done: on a fresh install it is not, the activity opens
     * on first-run setup, and there is no Settings tab to reach the page by.
     */
    private fun claim(mode: LocationMode, cadence: LocationCadence) {
        val original = Graph.settings(targetContext).read()
        storedBefore = original
        BootAware.saveSettings(
            targetContext,
            original.copy(
                latitude = original.latitude ?: 55.95,
                longitude = original.longitude ?: -3.19,
                locationMode = mode,
                locationCadence = cadence,
                backgroundLocationRevoked = false,
                setupDone = true,
            ),
        )
    }

    /**
     * Grants the screen believes it has, without granting or revoking anything
     * real: revoking kills the process, and the phone's own grants are the
     * owner's.
     */
    private fun grants(location: Boolean, background: Boolean) {
        Graph.locationGrantsOverride = object : LocationGrantSource {
            override fun location() = location
            override fun background() = background
        }
    }

    @After
    fun putTheStoredSettingsBack() {
        Graph.locationGrantsOverride = null
        val original = storedBefore ?: return
        BootAware.saveSettings(targetContext, original)
        // The activity under test armed or cancelled the refresh for what it
        // was shown, so it is set for the owner's own settings again.
        LocationAlarm.arm(targetContext, original)
    }

    /** Away and back, which is all that reaches the activity's resume. */
    private fun leaveAndReturn(scenario: ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun openLocationPage() {
        compose.onNodeWithTag(Section.Settings.tag).performClick()
        compose.onNodeWithTag(settingsRowTag(SettingsPage.Location)).performScrollTo().performClick()
    }

    @Test
    fun returningWithoutTheLocationGrantTurnsAskingTheDeviceOffAndSaysSo() {
        grants(location = false, background = false)
        claim(LocationMode.Automatic, LocationCadence.Every(3))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveAndReturn(scenario)
            openLocationPage()

            compose.onNodeWithText(LocationGrants.LOCATION_REMOVED_NOTE).assertIsDisplayed()
            assertEquals(
                LocationMode.Manual,
                Graph.settings(targetContext).read().locationMode,
            )
        }
    }

    @Test
    fun theNoteAboutAWithdrawnGrantSurvivesTheActivityBeingRebuilt() {
        grants(location = false, background = false)
        claim(LocationMode.Automatic, LocationCadence.OnOpen)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveAndReturn(scenario)
            openLocationPage()
            compose.onNodeWithText(LocationGrants.LOCATION_REMOVED_NOTE).assertIsDisplayed()

            // The stored settings are already typed in now, so only a note
            // that was kept can say it again.
            scenario.recreate()

            compose.onNodeWithText(LocationGrants.LOCATION_REMOVED_NOTE).assertIsDisplayed()
        }
    }

    @Test
    fun returningWithoutTheBackgroundGrantPutsAnIntervalBackToEachOpenAndShowsTheRedLine() {
        grants(location = true, background = false)
        claim(LocationMode.Automatic, LocationCadence.Every(3))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            leaveAndReturn(scenario)
            openLocationPage()

            compose.onNodeWithText("Android no longer lets Gloaming read", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
            val stored = Graph.settings(targetContext).read()
            assertEquals(LocationCadence.OnOpen, stored.locationCadence)
            assertTrue(stored.backgroundLocationRevoked)
        }
    }
}
