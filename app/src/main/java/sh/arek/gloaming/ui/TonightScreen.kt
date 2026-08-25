package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.StormMode

/**
 * What the sky is doing now, and the two switches that act on it.
 *
 * Emitted as loose children rather than wrapped in a column of its own, so the
 * caller owns the scrolling. That is what lets the same content sit inside a
 * tab later without a scroll nested inside a scroll.
 */
@Composable
fun TonightContent(
    state: MainUiState,
    onWatchingChange: (Boolean) -> Unit,
    onWatchDurationChange: (WatchDuration) -> Unit,
    onPollNow: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    StatusCard(state)

    if (!state.batteryExempt) {
        WarningCard(
            title = "Gloaming cannot alert you yet",
            body = "Android holds background checks until the phone next wakes, which " +
                "overnight means hours - exactly when the aurora happens and the " +
                "phone is on a bedside table. This exemption is required, not a " +
                "refinement: without it Gloaming cannot do the one thing it is for, " +
                "and no other setting here works around it.",
            action = "Allow background checks",
            onAction = onRequestBatteryExemption,
        )
    }

    if (!state.notificationsAllowed) {
        WarningCard(
            title = "Notifications are off",
            body = "Without notification permission the app can watch the sky but " +
                "cannot tell you about it.",
            action = "Allow notifications",
            onAction = onRequestNotifications,
        )
    }

    WatchNowCard(state, onWatchingChange, onWatchDurationChange)

    // Disabled while in flight, and says so. A button that looks idle
    // during the second or two a check takes reads as a broken button, and
    // the second press would only queue the same work again.
    Button(
        onClick = onPollNow,
        enabled = !state.checking,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.checking) "Checking..." else "Check now")
    }

    Text(
        "Nothing this app's own servers run ever learns where you are. The aurora " +
            "grid is downloaded whole and looked up on this phone, and every alert " +
            "decision is made here.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun StatusCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val probability = state.alertState.lastProbability
            Text(
                text = when {
                    !state.settings.hasLocation -> "Set your location to begin"
                    probability == null -> "No reading yet"
                    else -> "$probability% aurora probability here"
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            // Staleness is shown prominently rather than buried: a reading from
            // three days ago is worse than showing nothing, and a device that
            // has been quiet is not the same as one that has not looked.
            Text(
                text = describeAge(state.alertState.lastPollAt, state.nowMillis),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Immediately under the age, because the two are read together: a
            // long gap is only alarming until the next line explains it.
            state.darkness?.let {
                Text(
                    text = describeSchedule(it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Beside the number, not at the foot of the screen. AuroraWatch's
            // conditions tie the credit to their data, and a reader deciding
            // whether to trust 55% should be able to see where it came from
            // without scrolling. The attribution they specify is carried in
            // full by the credits card below.
            if (state.alertState.lastFromAuroraWatch) {
                Text(
                    "This reading came from AuroraWatch UK.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.alertState.lastError?.let {
                Text(
                    "Last check failed: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

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
 * somebody actually cares about and not on the other three hundred.
 */
@Composable
private fun WatchNowCard(
    state: MainUiState,
    onWatchingChange: (Boolean) -> Unit,
    onWatchDurationChange: (WatchDuration) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Watch now", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.watching,
                    onCheckedChange = onWatchingChange,
                    modifier = Modifier.testTag(WATCH_SWITCH_TAG),
                )
            }
            Text(
                "Checks every ${StormMode.POLL_MINUTES} minutes and keeps a notification " +
                    "up while it does. Nothing Android does to background apps can delay " +
                    "it, so this is the one to use on a night that matters - or if you " +
                    "have missed alerts you expected.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Offered whether or not a watch is running: changing it mid-watch
            // is the moment somebody outside is most likely to want it, and the
            // running watch keeps the length it started with rather than
            // changing underneath them.
            Text(
                "Stops on its own after: ${state.settings.watchDuration.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in WatchDuration.entries) {
                    OutlinedButton(onClick = { onWatchDurationChange(option) }) {
                        Text(shortWatchDuration(option))
                    }
                }
            }
            if (state.settings.watchDuration == WatchDuration.UntilLight) {
                Text(
                    "Until the sky starts to get light where you are. In midsummer up " +
                        "here it never fully does, and then this falls back to " +
                        "${WatchDuration.MAXIMUM_MILLIS / (60 * 60_000L)} hours - which " +
                        "is also the longest any watch will run, so a forgotten one " +
                        "cannot flatten the battery.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Only raised when the saver is actually on. A standing warning
            // about a mode nobody has enabled is noise, and noise is what gets
            // the useful warnings above ignored.
            if (state.powerSaveMode) {
                Text(
                    "Battery saver is on. Gloaming is allowed to run in the background " +
                        "anyway" +
                        (if (state.batteryExempt) "" else " only once you allow it above") +
                        ", but battery saver is the first thing to suspect if " +
                        "checks stop landing overnight. Watching now is not affected by it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

