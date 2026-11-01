package sh.arek.gloaming.ui

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import sh.arek.gloaming.data.LocationCadence

const val CADENCE_EXPLAINER_CONTINUE_TAG = "cadence-explainer-continue"
const val CADENCE_EXPLAINER_CANCEL_TAG = "cadence-explainer-cancel"

const val CADENCE_EXPLAINER_TITLE = "Background updates"

/**
 * What the dialog says about Android's page for the background grant.
 *
 * "Use precise location" is a toggle from Android 12 (API 31); before that the
 * page has no such control, so the advice to leave it off is left out.
 */
fun cadenceExplainerText(sdk: Int = Build.VERSION.SDK_INT): String =
    if (sdk >= Build.VERSION_CODES.S) {
        "Android will ask whether Gloaming may use your location all the time. Choose " +
            "\"Allow all the time\" and leave \"Use precise location\" off - Gloaming only " +
            "needs to know roughly where you are."
    } else {
        "Android will ask whether Gloaming may use your location all the time. Choose " +
            "\"Allow all the time\"."
    }

/**
 * Holds an interval pick back until it has been explained.
 *
 * Android's page for the background grant offers precise location as well, and
 * it replaces the app on screen, so a warning drawn on the card is gone before
 * it can be read. An interval picked while the grant is missing is therefore
 * [pending] behind a dialog, and only [continueRequest] hands it on to
 * [request] - where Android is asked. Anything that needs no grant goes
 * straight through.
 */
class CadenceExplainer internal constructor(
    private val allowed: () -> Boolean,
    private val request: (LocationCadence) -> Unit,
    pending: MutableState<Int?>,
) {
    private var pendingHours by pending

    /** The interval waiting on the dialog, if there is one. */
    val pending: LocationCadence.Every? get() = pendingHours?.let { LocationCadence.Every(it) }

    fun pick(cadence: LocationCadence) {
        if (cadence is LocationCadence.Every && !allowed()) {
            pendingHours = cadence.hours
        } else {
            request(cadence)
        }
    }

    fun continueRequest() {
        val waiting = pending ?: return
        pendingHours = null
        request(waiting)
    }

    fun cancel() {
        pendingHours = null
    }
}

/**
 * An explainer for a card that reports picks through [request].
 *
 * Saved across recreation by the hours waiting, the way the request it leads
 * to is, so the dialog survives a rotation.
 */
@Composable
fun rememberCadenceExplainer(
    backgroundAllowed: Boolean,
    request: (LocationCadence) -> Unit,
): CadenceExplainer {
    val allowed by rememberUpdatedState(backgroundAllowed)
    val send by rememberUpdatedState(request)
    val pending = rememberSaveable { mutableStateOf<Int?>(null) }
    return remember { CadenceExplainer({ allowed }, { send(it) }, pending) }
}

/** The dialog for [explainer]; draws nothing while no pick is waiting. */
@Composable
fun CadenceExplainerDialog(explainer: CadenceExplainer) {
    if (explainer.pending == null) return
    AlertDialog(
        onDismissRequest = explainer::cancel,
        title = { Text(CADENCE_EXPLAINER_TITLE) },
        text = { Text(cadenceExplainerText()) },
        confirmButton = {
            TextButton(
                onClick = explainer::continueRequest,
                modifier = Modifier.testTag(CADENCE_EXPLAINER_CONTINUE_TAG),
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(
                onClick = explainer::cancel,
                modifier = Modifier.testTag(CADENCE_EXPLAINER_CANCEL_TAG),
            ) { Text("Cancel") }
        },
    )
}
