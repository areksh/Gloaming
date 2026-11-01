package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.StormMode
import sh.arek.gloaming.ui.theme.LocalPalette

/** The watch button at the foot of Tonight. */
const val WATCH_BUTTON_TAG = "watch-button"

/** The sheet the idle watch button opens, with the choice of how long. */
const val WATCH_SHEET_TAG = "watch-sheet"

/**
 * The escape hatch from the scheduler, for when the schedule is not trusted.
 *
 * Everything else in the app is scheduled through `AlarmManager`, which the
 * system is free to defer - Doze, app standby, battery saver, and on several
 * OEM builds a process killer of the vendor's own that no exemption reaches. A
 * foreground service is the one thing Android will not defer, because it runs
 * its own clock, and its persistent notification is the price of that.
 *
 * So it is a button rather than a default: the cost is a wake lock and a
 * notification that will not go away, which is worth paying on the night
 * somebody actually cares about and not on the other three hundred. Idle, it
 * opens a sheet with the choice of how long, so starting one is a deliberate
 * second tap; running, it says when it will stop and stops on a tap.
 */
@Composable
fun WatchButton(state: MainUiState, actions: UiActions) {
    val palette = LocalPalette.current
    var choosing by rememberSaveable { mutableStateOf(false) }
    val running = watchRunning(state.watching, state.watchingUntil, state.nowMillis)

    Button(
        onClick = {
            if (running) actions.onWatchingChange(false) else choosing = true
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(WATCH_BUTTON_TAG),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.onAccent,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (running) {
                Text(describeWatching(state.watchingUntil), fontWeight = FontWeight.Bold)
                Text("Tap to stop", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Watch tonight", fontWeight = FontWeight.Bold)
                Text(
                    describeWatchPlan(state.settings.watchDuration),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    // Closed as soon as a watch is running: the button now stops it, and a
    // sheet offering to start one would be offering the wrong thing.
    if (choosing && !running) {
        WatchSheet(
            state = state,
            onDurationChange = actions.onWatchDurationChange,
            onStart = {
                choosing = false
                actions.onWatchingChange(true)
            },
            onDismiss = { choosing = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchSheet(
    state: MainUiState,
    onDurationChange: (WatchDuration) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                .testTag(WATCH_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Watch tonight", style = MaterialTheme.typography.titleLarge)
            Text(
                "Checks every ${StormMode.POLL_MINUTES} minutes and keeps a notification " +
                    "up while it does. Nothing Android does to background apps can delay " +
                    "it, so this is the one to use on a night that matters - or if you " +
                    "have missed alerts you expected.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Stops on its own after: ${state.settings.watchDuration.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            // FlowRow, not Row. Five buttons across a narrow screen leave the
            // last one no width to lay its label out in, and Compose does not
            // clip it - it wraps the text instead, so "Dawn" came out one
            // letter per line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (option in WatchDuration.entries) {
                    if (option == state.settings.watchDuration) {
                        Button(onClick = { onDurationChange(option) }) {
                            Text(shortWatchDuration(option))
                        }
                    } else {
                        OutlinedButton(onClick = { onDurationChange(option) }) {
                            Text(shortWatchDuration(option))
                        }
                    }
                }
            }
            if (state.settings.watchDuration == WatchDuration.UntilLight) {
                Text(
                    "Until the sky starts to get light where you are. Where it never " +
                        "fully does - around midsummer, far from the equator - this " +
                        "falls back to " +
                        "${WatchDuration.MAXIMUM_MILLIS / (60 * 60_000L)} hours - which " +
                        "is also the longest any watch will run, so a forgotten one " +
                        "cannot flatten the battery.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start watching")
            }
        }
    }
}
