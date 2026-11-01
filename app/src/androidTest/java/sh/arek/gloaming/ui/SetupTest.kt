package sh.arek.gloaming.ui

import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.encode
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings

/**
 * First-run setup, driven directly with a state of its own.
 *
 * Nothing here touches the app's preference stores, and no system permission
 * dialog is raised: the allow buttons are asserted to fire their actions
 * rather than pressed through to Android.
 *
 * **These need the device unlocked**, for the reason recorded in [ScreensTest].
 */
class SetupTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(stateOf())
    private var finished = 0
    private var recorded = 0
    private var asked = mutableListOf<String>()
    private var cadences = mutableListOf<LocationCadence>()

    private fun stateOf(
        settings: UserSettings = UserSettings(),
        notificationsAllowed: Boolean = false,
        batteryExempt: Boolean = false,
        latitudeInput: String = "",
        longitudeInput: String = "",
    ) = MainUiState(
        settings = settings,
        alertState = AlertState(),
        latitudeInput = latitudeInput,
        longitudeInput = longitudeInput,
        quietFromInput = "23:00",
        quietUntilInput = "07:00",
        batteryExempt = batteryExempt,
        notificationsAllowed = notificationsAllowed,
        nowMillis = 1_800_000_000_000L,
    )

    private val located = UserSettings(latitude = 55.95, longitude = -3.19)

    private fun show(steps: List<SetupStep> = SetupStep.entries) {
        compose.setContent {
            SetupFlow(
                state = state,
                actions = UiActions(
                    onLatitudeChange = { state = state.copy(latitudeInput = it) },
                    onLongitudeChange = { state = state.copy(longitudeInput = it) },
                    onSave = {
                        state = state.copy(
                            settings = state.settings.copy(
                                latitude = state.latitudeInput.toDouble(),
                                longitude = state.longitudeInput.toDouble(),
                            ),
                        )
                    },
                    onRequestNotifications = { asked += "notifications" },
                    onRequestBatteryExemption = { asked += "battery" },
                    onSetupDone = { recorded++ },
                    onLocationCadenceChange = { cadences += it },
                ),
                onFinished = { finished++ },
                steps = steps,
            )
        }
    }

    @Test
    fun walksTheThreeStepsInOrder() {
        state = stateOf(settings = located, notificationsAllowed = true, batteryExempt = true)
        show()

        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        compose.onNodeWithText("Use this phone's location").assertIsDisplayed()
        compose.onNodeWithTag(SETUP_NEXT_TAG).performClick()

        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("Each kind of alert has its own switch in Android's notification", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag(SETUP_NEXT_TAG).performClick()

        compose.onNodeWithText("Step 3 of 3").assertIsDisplayed()
        assertEquals(0, finished)
        compose.onNodeWithTag(SETUP_NEXT_TAG).performClick()

        assertEquals(1, recorded)
        assertEquals(1, finished)
    }

    @Test
    fun theLocationStepShowsTheBackgroundUpdatesOnceThePhoneIsAsked() {
        state = stateOf(settings = located.copy(locationMode = LocationMode.Automatic))
        show()

        compose.onNodeWithText("Use this phone's location").assertIsDisplayed()
        compose.onNodeWithText("Background updates").assertIsDisplayed()
    }

    /**
     * Reached by changing the state the way the grant callback does: the offer
     * follows the switch coming on, which is all that callback changes.
     */
    @Test
    fun offersBackgroundUpdatesWhenThePhonesLocationIsSwitchedOn() {
        state = stateOf(settings = located)
        show()
        compose.onNodeWithText("Keep your location up to date?").assertDoesNotExist()

        state = state.copy(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("Keep your location up to date?").assertIsDisplayed()
        compose.onNodeWithText(
            "Gloaming can refresh your position in the background, so alerts stay right " +
                "when you travel without opening the app.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(OFFER_ACCEPT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OFFER_DECLINE_TAG).assertIsDisplayed()
    }

    @Test
    fun decliningTheOfferLeavesBackgroundUpdatesOff() {
        state = stateOf(settings = located)
        show()
        state = state.copy(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(OFFER_DECLINE_TAG).performClick()

        compose.onNodeWithText("Keep your location up to date?").assertDoesNotExist()
        assertEquals(emptyList<LocationCadence>(), cadences)
    }

    @Test
    fun offersBackgroundUpdatesOnlyOncePerSetup() {
        state = stateOf(settings = located)
        show()
        state = state.copy(settings = located.copy(locationMode = LocationMode.Automatic))
        compose.onNodeWithTag(OFFER_DECLINE_TAG).performClick()

        state = state.copy(settings = located)
        state = state.copy(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithText("Keep your location up to date?").assertDoesNotExist()
    }

    @Test
    fun acceptingTheOfferExplainsBeforeAskingForEveryHour() {
        state = stateOf(settings = located)
        show()
        state = state.copy(settings = located.copy(locationMode = LocationMode.Automatic))

        compose.onNodeWithTag(OFFER_ACCEPT_TAG).performClick()

        compose.onNodeWithText("Allow all the time", substring = true).assertIsDisplayed()
        assertEquals(emptyList<LocationCadence>(), cadences)

        compose.onNodeWithTag(CADENCE_EXPLAINER_CONTINUE_TAG).performClick()

        assertEquals(listOf<LocationCadence>(LocationCadence.Every(1)), cadences)
    }

    @Test
    fun offersNothingWhenThePhoneWasAlreadyBeingAsked() {
        state = stateOf(settings = located.copy(locationMode = LocationMode.Automatic))
        show()

        compose.onNodeWithText("Keep your location up to date?").assertDoesNotExist()
    }

    @Test
    fun skippingStillFinishes() {
        show()

        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()

        assertEquals(1, recorded)
        assertEquals(1, finished)
    }

    @Test
    fun typingAPositionCompletesTheLocationStep() {
        show()
        compose.onNodeWithTag(SETUP_SKIP_TAG).assertIsDisplayed()

        compose.onNodeWithText("Latitude").performTextInput("55.95")
        compose.onNodeWithText("Longitude").performTextInput("-3.19")
        compose.onNodeWithText("Save").performClick()

        compose.onNodeWithTag(SETUP_NEXT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETUP_NEXT_TAG).performClick()
        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
    }

    @Test
    fun theAllowButtonsRaiseTheirRequests() {
        show()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()

        compose.onNodeWithTag(ALLOW_NOTIFICATIONS_TAG).performClick()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).performClick()

        assertEquals(listOf("notifications", "battery"), asked)
    }

    @Test
    fun backReturnsToThePreviousStep() {
        show()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()

        Espresso.pressBack()

        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        assertEquals(0, recorded)
    }

    @Test
    fun omitsTheNotificationsStepWhereThereIsNothingToAsk() {
        show(steps = setupSteps(sdk = 32))

        compose.onNodeWithText("Step 1 of 2").assertIsDisplayed()
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        compose.onNodeWithTag(ALLOW_BACKGROUND_CHECKS_TAG).assertIsDisplayed()
    }

    @Test
    fun theRootShowsNavigationOnceSetUp() {
        compose.setContent {
            GloamingRoot(state = state.copy(settings = located.copy(setupDone = true)), actions = UiActions())
        }

        compose.onNodeWithTag(Section.Tonight.tag).assertIsDisplayed()
    }

    @Test
    fun theRootShowsSetupUntilItIsDone() {
        compose.setContent {
            GloamingRoot(state = state.copy(settings = located.copy(setupDone = false)), actions = UiActions())
        }

        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        compose.onNodeWithTag(Section.Tonight.tag).assertDoesNotExist()
    }

    /** A notification tapped before setup is done does not skip it, and is not kept. */
    @Test
    fun theRootIgnoresATargetUntilSetupIsDone() {
        var shown = 0
        compose.setContent {
            GloamingRoot(
                state = state.copy(settings = located.copy(setupDone = false)),
                actions = UiActions(),
                initialFind = FindTarget.Aurora.encode(),
                onInitialFindShown = { shown++ },
            )
        }

        compose.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        compose.onNodeWithTag(Section.Tonight.tag).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, shown) }
    }

    @Test
    fun theStepSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SetupFlow(state = state, actions = UiActions(), onFinished = {})
        }
        compose.onNodeWithTag(SETUP_SKIP_TAG).performClick()
        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Step 2 of 3").assertIsDisplayed()
    }
}
