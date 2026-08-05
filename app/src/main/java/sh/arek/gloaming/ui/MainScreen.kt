package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings

/** Everything the screen needs, already resolved. */
data class MainUiState(
    val settings: UserSettings,
    val alertState: AlertState,
    val latitudeInput: String,
    val longitudeInput: String,
    val batteryExempt: Boolean,
    val notificationsAllowed: Boolean,
    val nowMillis: Long,
)

@Composable
fun MainScreen(
    state: MainUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onAlertsEnabledChange: (Boolean) -> Unit,
    onBeforeUnlockChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onPollNow: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Gloaming", style = MaterialTheme.typography.headlineMedium)

        StatusCard(state)

        if (!state.batteryExempt) {
            WarningCard(
                title = "Alerts will be delayed",
                body = "Android's battery saving holds background checks until the phone " +
                    "wakes, which overnight can mean hours - exactly when the aurora " +
                    "happens and the phone is on a bedside table. Gloaming needs an " +
                    "exemption to check on its own schedule.",
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

        LocationCard(state, onLatitudeChange, onLongitudeChange, onSave)
        ThresholdCard(state, onThresholdChange, onAlertsEnabledChange)
        BeforeUnlockCard(state, onBeforeUnlockChange)

        Button(onClick = onPollNow, modifier = Modifier.fillMaxWidth()) {
            Text("Check now")
        }

        Text(
            "Your location never leaves this device. The aurora grid is downloaded whole " +
                "and looked up here, so nothing is ever asked about where you are.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
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

@Composable
private fun LocationCard(
    state: MainUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Text(
                "Typed in, not requested. North and east are positive.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.latitudeInput,
                onValueChange = onLatitudeChange,
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.longitudeInput,
                onValueChange = onLongitudeChange,
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

@Composable
private fun ThresholdCard(
    state: MainUiState,
    onThresholdChange: (Int) -> Unit,
    onAlertsEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Alerts", style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.settings.alertsEnabled, onCheckedChange = onAlertsEnabledChange)
            }
            Text(
                "Alert above ${state.settings.threshold}% local probability. " +
                    "Higher latitudes want a higher bar.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in listOf(10, 20, 30, 50, 70)) {
                    OutlinedButton(onClick = { onThresholdChange(option) }) { Text("$option%") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Checks run about every ${state.settings.pollIntervalMinutes} minutes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The before-unlock option, with the trade stated rather than buried.
 *
 * A phone set to reboot on a timer sits at the lock screen afterwards, and an
 * app whose data is behind the user's credential cannot run until someone picks
 * it up. Enabling this keeps alerts working across that gap, at the cost of
 * putting the grid cell and alert settings into storage the system can read
 * before unlock.
 */
@Composable
private fun BeforeUnlockCard(state: MainUiState, onBeforeUnlockChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Alert before unlock", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.alertsBeforeUnlock,
                    onCheckedChange = onBeforeUnlockChange,
                )
            }
            Text(
                "If your phone reboots on a timer, it sits locked until you pick it " +
                    "up, and Gloaming cannot read its own settings until then. Turn " +
                    "this on to keep alerting through that gap.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The cost: your rounded location " +
                    (state.settings.cell?.let { "(${it.latitude}, ${it.longitude}) " } ?: "") +
                    "and alert settings are copied to storage the system can read " +
                    "before you unlock. Only the whole degree is stored - about " +
                    "111 km - never the position you typed. Turning this off deletes " +
                    "that copy.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Turns a timestamp into the age the user actually cares about.
 *
 * Kept as plain text rather than a relative-time widget so "never" and "just
 * now" read the same way as everything else on the screen.
 */
fun describeAge(lastPollAt: Long?, now: Long): String {
    if (lastPollAt == null) return "Not checked yet"
    val minutes = ((now - lastPollAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Checked just now"
        minutes < 60 -> "Checked $minutes minute${plural(minutes)} ago"
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            "Checked $hours hour${plural(hours)} ago"
        }
        else -> {
            val days = minutes / (60 * 24)
            "Last checked $days day${plural(days)} ago"
        }
    }
}

private fun plural(value: Long) = if (value == 1L) "" else "s"
