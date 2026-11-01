package sh.arek.gloaming.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.NoCompass

/** What first-run setup asks about, in the order it asks. */
enum class SetupStep(val page: SettingsPage) {
    Location(SettingsPage.Location),
    Notifications(SettingsPage.Notifications),
    BackgroundChecks(SettingsPage.BackgroundChecks),
}

/**
 * The steps this version of Android has anything to ask for.
 *
 * Notification permission is a runtime grant only from API 33; below that the
 * channels are simply on and the step would be asking for nothing.
 */
fun setupSteps(sdk: Int = Build.VERSION.SDK_INT): List<SetupStep> =
    SetupStep.entries.filter { it != SetupStep.Notifications || sdk >= Build.VERSION_CODES.TIRAMISU }

const val SETUP_SKIP_TAG = "setup-skip"

const val SETUP_NEXT_TAG = "setup-next"

/**
 * Three questions, once, each skippable: where you are, whether Gloaming may
 * speak, and whether it may look while the phone sleeps.
 *
 * Each step is the Settings page for the same question, not a copy of it, so
 * the explanation, the permission flow and the wording cannot drift apart. A
 * skipped step is not lost: the Tonight banners keep reporting whatever is
 * still missing, and the same controls sit in Settings.
 *
 * The step survives rotation. System Back on a later step returns to the one
 * before; on the first it is left alone, so the app closes as it would
 * anywhere else and nobody is trapped.
 *
 * Finishing, or skipping the last step, calls [UiActions.onSetupDone] to record
 * it and then [onFinished] for whatever the caller does next.
 */
@Composable
fun SetupFlow(
    state: MainUiState,
    actions: UiActions,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    steps: List<SetupStep> = setupSteps(),
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val position = index.coerceIn(0, steps.lastIndex)
    val step = steps[position]
    val last = position == steps.lastIndex

    BackHandler(enabled = position > 0) { index = position - 1 }

    fun advance() {
        if (last) {
            actions.onSetupDone()
            onFinished()
        } else {
            index = position + 1
        }
    }

    val done = when (step) {
        SetupStep.Location -> state.settings.hasLocation
        SetupStep.Notifications -> state.notificationsAllowed
        SetupStep.BackgroundChecks -> state.batteryExempt
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Step ${position + 1} of ${steps.size}",
            style = MaterialTheme.typography.labelLarge,
        )
        SettingsPageContent(step.page, state, actions)
        if (done) {
            Button(
                onClick = ::advance,
                modifier = Modifier.fillMaxWidth().testTag(SETUP_NEXT_TAG),
            ) { Text(if (last) "Finish" else "Next") }
        } else {
            OutlinedButton(
                onClick = ::advance,
                modifier = Modifier.fillMaxWidth().testTag(SETUP_SKIP_TAG),
            ) { Text(if (last) "Skip and finish" else "Skip") }
        }
    }
}

/**
 * What the app shows: first-run setup until it has been walked through or
 * skipped, and the navigation after. [initialFind] is passed on to the
 * navigation; see [GloamingNav].
 */
@Composable
fun GloamingRoot(
    state: MainUiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    onSetupFinished: () -> Unit = {},
    compass: CompassSource = NoCompass,
    initialFind: String? = null,
    onInitialFindShown: () -> Unit = {},
) {
    if (state.settings.setupDone) {
        GloamingNav(state, actions, modifier, compass, initialFind, onInitialFindShown)
    } else {
        // Setup comes first: a target handed in before it is done is dropped
        // rather than kept, so it cannot surprise anybody when setup ends.
        LaunchedEffect(initialFind) { if (initialFind != null) onInitialFindShown() }
        SetupFlow(state, actions, onFinished = onSetupFinished, modifier = modifier)
    }
}
