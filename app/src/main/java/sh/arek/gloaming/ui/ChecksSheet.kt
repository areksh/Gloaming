package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.ui.theme.LocalPalette

/** The checks sheet, opened from the status line. */
const val CHECKS_SHEET_TAG = "checks-sheet"

/** The button that runs a check now, inside the sheet. */
const val CHECK_NOW_TAG = "check-now"

/** The downloads report, reached by tag because its rows are prose, not labels. */
const val DOWNLOADS_CARD_TAG = "downloads-report"

/**
 * Everything operational, out of the middle of the sky.
 *
 * Check now, why the last check failed, what has been downloaded, battery
 * saver and the privacy note all used to sit among the cards about tonight,
 * and read as though they were part of it. They are what somebody reaches for
 * when the status line says something is off, so that is where they live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecksSheet(state: MainUiState, actions: UiActions, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.raised,
        contentColor = palette.text,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag(CHECKS_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Checks", style = MaterialTheme.typography.titleLarge)

            state.alertState.lastError?.let {
                Text(
                    "Last check failed: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.alarm,
                )
            }

            // Disabled while in flight, and says so. A button that looks idle
            // during the second or two a check takes reads as a broken button,
            // and the second press would only queue the same work again.
            Button(
                onClick = actions.onPollNow,
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth().testTag(CHECK_NOW_TAG),
            ) {
                Text(if (state.checking) "Checking..." else "Check now")
            }

            DownloadsReport(state)

            // Only raised when the saver is actually on. A standing warning
            // about a mode nobody has enabled is noise, and noise is what gets
            // the useful warnings ignored.
            if (state.powerSaveMode) {
                Text(
                    "Battery saver is on. Gloaming is allowed to run in the background " +
                        "anyway" +
                        (if (state.batteryExempt) "" else " only once you allow background checks") +
                        ", but battery saver is the first thing to suspect if " +
                        "checks stop landing overnight. Watching tonight is not affected by it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.alarm,
                )
            }

            Text(
                "Nothing this app's own servers run ever learns where you are. The aurora " +
                    "grid is downloaded whole and looked up on this phone, and every alert " +
                    "decision is made here.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

/**
 * What the app has downloaded, how old what it holds is, and what went wrong.
 *
 * This exists because of a fortnight nobody could have noticed. The satellite
 * elements stopped being republished upstream, every conditional GET went on
 * cheerfully answering 304, and the app carried on predicting passes from a
 * two-week-old catalogue. Nothing failed loudly enough to show: the failures
 * that did happen went to `Log.w` and to a journal file, and from the screen a
 * broken pipeline and a quiet sky read identically. "Nothing crossing tonight"
 * is a sentence the app must only ever say when it is true.
 *
 * Under Check now in the checks sheet, and usually absent even there: on an
 * ordinary evening it is six lines saying everything is fine, which is
 * furniture. It speaks while a check runs and after one has been run. The case
 * it was built for - something actually wrong, whether or not anybody asked -
 * is spoken on the status line by [describeTrouble], because a report that
 * only speaks when opened would have sat silent through the entire fortnight.
 */
@Composable
private fun DownloadsReport(state: MainUiState) {
    if (!worthReporting(state.downloads, state.checking)) return
    val palette = LocalPalette.current

    Column(
        modifier = Modifier.testTag(DOWNLOADS_CARD_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Indeterminate, and honestly so: these are half a dozen small
        // conditional GETs, most of which transfer nothing at all. A bar
        // filling to a percentage would be inventing a number - what is worth
        // showing is that something is happening and what it is.
        if (state.checking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                // Not "Checking..." - the button directly above already says
                // that, and a screen that says the same word twice in two
                // lines is telling you nothing with the second one. This line
                // exists to add what the button cannot: which object is open.
                text = state.downloading.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                    ?.let { "Downloading: $it" }
                    ?: "Starting...",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        for (row in state.downloads) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (line in row.lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.alarming) palette.alarm else palette.muted,
                )
            }
        }
    }
}
