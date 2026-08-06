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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.AlertDecision
import sh.arek.gloaming.domain.PollingPolicy

/** Everything the screen needs, already resolved. */
data class MainUiState(
    val settings: UserSettings,
    val alertState: AlertState,
    val latitudeInput: String,
    val longitudeInput: String,
    val quietFromInput: String,
    val quietUntilInput: String,
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
    onCloudGateChange: (Boolean) -> Unit,
    onCloudToleranceChange: (Int) -> Unit,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    onQuietFromChange: (String) -> Unit,
    onQuietUntilChange: (String) -> Unit,
    onQuietHoursOverrideChange: (Boolean) -> Unit,
    onNightVisionChange: (Boolean) -> Unit,
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
        SkyCard(state, onCloudGateChange, onCloudToleranceChange)
        QuietHoursCard(
            state,
            onQuietHoursEnabledChange,
            onQuietFromChange,
            onQuietUntilChange,
            onQuietHoursOverrideChange,
        )
        BeforeUnlockCard(state, onBeforeUnlockChange)
        NightVisionCard(state, onNightVisionChange)

        Button(onClick = onPollNow, modifier = Modifier.fillMaxWidth()) {
            Text("Check now")
        }

        Text(
            "Nothing this app's own servers run ever learns where you are. The aurora " +
                "grid is downloaded whole and looked up on this phone, and every alert " +
                "decision is made here.",
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
                "How often Gloaming checks follows the sky: not at all while the sun " +
                    "is up, every ${PollingPolicy.QUIET_MINUTES} minutes on a quiet " +
                    "dark night, and every ${PollingPolicy.ELEVATED_MINUTES} when the " +
                    "solar wind turns southward - which is about half an hour of " +
                    "warning before anything starts.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The two gates that decide whether a reading is worth acting on.
 *
 * Darkness is stated but not offered: it costs nothing, needs no network, and
 * an alert in daylight is never useful. Cloud is optional, because it is the
 * one thing the app asks a third party - and the trade is stated here rather
 * than buried, so switching it off is an informed choice and not a hidden one.
 */
@Composable
private fun SkyCard(
    state: MainUiState,
    onCloudGateChange: (Boolean) -> Unit,
    onCloudToleranceChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sky conditions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Alerts are never sent while the sun is up. That is worked out on this " +
                    "phone and cannot be switched off.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Check cloud cover", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.settings.cloudGateEnabled,
                    onCheckedChange = onCloudGateChange,
                )
            }

            if (state.settings.cloudGateEnabled) {
                Text(
                    "Hold alerts back above ${state.settings.cloudTolerance}% cloud.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in listOf(40, 60, 80, 100)) {
                        OutlinedButton(onClick = { onCloudToleranceChange(option) }) {
                            Text("$option%")
                        }
                    }
                }
                Text(
                    "This is the one thing that leaves your phone: a location rounded to " +
                        "about a kilometre, at most once an hour, with no account or " +
                        "identifier attached. If the forecast cannot be reached you are " +
                        "alerted anyway and told the cloud is unknown.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // CC BY 4.0 requires attribution next to the data it covers.
                Text(
                    buildAnnotatedString {
                        append("Weather data by ")
                        withLink(LinkAnnotation.Url("https://open-meteo.com/")) {
                            append("Open-Meteo.com")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Quiet hours, off by default.
 *
 * The exceptional-event override is a separate switch rather than folded in,
 * because "do not wake me" and "unless it is remarkable" are different
 * decisions and only the user can make either.
 */
@Composable
private fun QuietHoursCard(
    state: MainUiState,
    onEnabledChange: (Boolean) -> Unit,
    onFromChange: (String) -> Unit,
    onUntilChange: (String) -> Unit,
    onOverrideChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Quiet hours", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.quietHours != null,
                    onCheckedChange = onEnabledChange,
                )
            }

            if (state.settings.quietHours == null) {
                Text(
                    "Off. The aurora happens in the small hours, so Gloaming will wake " +
                        "you unless you tell it not to.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.quietFromInput,
                    onValueChange = onFromChange,
                    label = { Text("From") },
                    singleLine = true,
                    isError = parseTimeOfDay(state.quietFromInput) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.quietUntilInput,
                    onValueChange = onUntilChange,
                    label = { Text("Until") },
                    singleLine = true,
                    isError = parseTimeOfDay(state.quietUntilInput) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text("24-hour, like 23:00.", style = MaterialTheme.typography.bodySmall)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Wake me for something exceptional",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.settings.quietHoursOverride,
                    onCheckedChange = onOverrideChange,
                )
            }
            Text(
                "Lets through anything above " +
                    "${AlertDecision.EXCEPTIONAL_PROBABILITY}% local probability - the " +
                    "sort of display people remember for years.",
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
 * Night vision, which is the whole reason to open this app outside.
 *
 * Not tied to the system's dark theme: dark mode is grey-on-black and still
 * emits across the spectrum, which costs the dark adaptation it looks like it
 * is protecting.
 */
@Composable
private fun NightVisionCard(state: MainUiState, onNightVisionChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Night vision", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.nightVision,
                    onCheckedChange = onNightVisionChange,
                )
            }
            Text(
                "Red on black, and the screen dimmed. Your eyes take twenty minutes to " +
                    "adapt to the dark and one glance at a white screen to lose it. Deep " +
                    "red is the one colour that barely registers, which is why " +
                    "observatories and ships' bridges use it.",
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
