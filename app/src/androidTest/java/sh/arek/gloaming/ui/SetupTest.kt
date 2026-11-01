package sh.arek.gloaming.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("Ask the phone").assertIsDisplayed()
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
}
