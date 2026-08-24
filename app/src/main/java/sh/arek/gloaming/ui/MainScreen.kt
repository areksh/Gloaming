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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.AlertDecision
import sh.arek.gloaming.domain.PollingPolicy
import sh.arek.gloaming.domain.StormMode
import sh.arek.gloaming.domain.deviceLocalMinuteOfDay
import sh.arek.gloaming.satellites.IssCoverage

/**
 * The watch switch, which is an action rather than a setting and so is the one
 * control worth reaching by tag: every other switch is identifiable by the
 * setting it sits beside.
 */
const val WATCH_SWITCH_TAG = "watch-switch"

/**
 * The live-following switch, reached by tag for the same reason: it sits beside
 * a sentence rather than a value, so there is no "Space station: 6 hours" style
 * label to find it by.
 */
const val FOLLOW_STATION_SWITCH_TAG = "follow-station-switch"

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
    /** True while a check the user asked for is in flight. */
    val checking: Boolean = false,
    /**
     * How dark it is where the observer is, or null when no location is set.
     * Drives the sentence explaining why the app may not have checked recently.
     */
    val darkness: Darkness? = null,
    /** Whether the system is in battery saver right now. */
    val powerSaveMode: Boolean = false,
    /** Whether the foreground watch is running. */
    val watching: Boolean = false,
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
    onAuroraIntervalChange: (Int) -> Unit,
    onIssIntervalChange: (Int) -> Unit,
    onBrightIntervalChange: (Int) -> Unit,
    onTrainIntervalChange: (Int) -> Unit,
    onPassAlertsChange: (Boolean) -> Unit,
    onFollowStationChange: (Boolean) -> Unit,
    onWatchingChange: (Boolean) -> Unit,
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
        InterruptionCard(
            state,
            onAuroraIntervalChange,
            onIssIntervalChange,
            onBrightIntervalChange,
            onTrainIntervalChange,
            onPassAlertsChange,
            onFollowStationChange,
        )
        WatchNowCard(state, onWatchingChange)
        BeforeUnlockCard(state, onBeforeUnlockChange)
        NightVisionCard(state, onNightVisionChange)

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

        CreditsCard(state)
    }
}

/**
 * How often the app may interrupt, per channel.
 *
 * The user's call, not the app's - the same reasoning as quiet hours. Three
 * separate answers because they are three different appetites: an aurora is
 * rare and worth waking for, the station goes over most nights, and the rest go
 * over constantly. A clear night over Edinburgh has upwards of forty passes
 * worth looking at, and an app that mentioned each of them would be muted
 * within the week.
 *
 * The interval is also how the best pass gets picked: it is the window the
 * planner chooses from, so a longer setting does not merely mean fewer alerts,
 * it means better ones.
 */
@Composable
private fun InterruptionCard(
    state: MainUiState,
    onAuroraIntervalChange: (Int) -> Unit,
    onIssIntervalChange: (Int) -> Unit,
    onBrightIntervalChange: (Int) -> Unit,
    onTrainIntervalChange: (Int) -> Unit,
    onPassAlertsChange: (Boolean) -> Unit,
    onFollowStationChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How often at most", style = MaterialTheme.typography.titleMedium)
            Text(
                "Gloaming waits at least this long before interrupting you again on " +
                    "each channel, and tells you about the best thing in that window " +
                    "rather than the first.",
                style = MaterialTheme.typography.bodySmall,
            )

            IntervalRow("Aurora", state.settings.auroraAlertIntervalMinutes, onAuroraIntervalChange)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Satellite passes", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.passAlertsEnabled,
                    onCheckedChange = onPassAlertsChange,
                )
            }
            if (state.settings.passAlertsEnabled) {
                IntervalRow(
                    "Space station",
                    state.settings.issPassAlertIntervalMinutes,
                    onIssIntervalChange,
                )
                // Only where the orbit does not reach, which is nowhere most
                // people live. The control stays whatever the answer is: the
                // point is to explain the silence, not to remove a setting.
                val coverage = state.settings.latitude?.let(::describeIssCoverage)
                if (coverage != null) {
                    Text(coverage, style = MaterialTheme.typography.bodySmall)
                }

                // Beside the station and nowhere else. Not a default waiting to
                // be relaxed: the station goes over most nights and the bright
                // catalogue goes over constantly, so the same switch there would
                // be a live tracker running most of every clear night.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Follow every station pass live",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.settings.followStationPassesAutomatically,
                        onCheckedChange = onFollowStationChange,
                        modifier = Modifier.testTag(FOLLOW_STATION_SWITCH_TAG),
                    )
                }
                Text(
                    "Ten minutes before it rises, Gloaming counts down and says which " +
                        "way to look, until the pass ends. It needs no signal. Otherwise " +
                        "each alert offers to follow that one pass.",
                    style = MaterialTheme.typography.bodySmall,
                )

                IntervalRow(
                    "Other bright satellites",
                    state.settings.brightPassAlertIntervalMinutes,
                    onBrightIntervalChange,
                )
                // Its own answer rather than a share of the bright catalogue's.
                // A launch is a train for a few nights and then never again,
                // and a limit built for objects that come over every night
                // would routinely spend itself on one of those instead.
                IntervalRow(
                    "Starlink trains",
                    state.settings.trainPassAlertIntervalMinutes,
                    onTrainIntervalChange,
                )
                Text(
                    "A string of Starlinks in a line, visible for a few nights after a " +
                        "launch and then not again as they spread out.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun IntervalRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${describeInterval(minutes)}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in UserSettings.ALERT_INTERVAL_CHOICES) {
                OutlinedButton(onClick = { onChange(option) }) { Text(shortInterval(option)) }
            }
        }
    }
}

/** Hours where they are whole, because "360 minutes" is not how anyone thinks. */
private fun describeInterval(minutes: Int): String = when {
    minutes < 60 -> "at most every $minutes minutes"
    minutes == 60 -> "at most once an hour"
    minutes % 60 == 0 -> "at most every ${minutes / 60} hours"
    else -> "at most every $minutes minutes"
}

private fun shortInterval(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/**
 * Who the data came from, and under what terms the app may show it.
 *
 * These are conditions of use rather than politeness. AuroraWatch UK require
 * attribution beside their data, Open-Meteo publish under CC BY 4.0, and the
 * vendored astronomy code is MIT - which requires its notice to travel with
 * every copy of the software, so it has to be readable on the phone and not
 * merely present in the repository.
 */
@Composable
private fun CreditsCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Credits and licences", style = MaterialTheme.typography.titleMedium)

            for (credit in credits(state.alertState.lastFromAuroraWatch)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(credit.name, style = MaterialTheme.typography.bodyLarge)
                    Text(credit.body, style = MaterialTheme.typography.bodySmall)
                    if (credit.links.isNotEmpty()) {
                        Text(
                            buildAnnotatedString {
                                credit.links.forEachIndexed { index, link ->
                                    if (index > 0) append("  -  ")
                                    withLink(LinkAnnotation.Url(link.url)) { append(link.label) }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    credit.licence?.let { LicenceText(it) }
                }
            }

            Text(
                buildAnnotatedString {
                    append("Gloaming itself is free software under the GPL-3.0, with no ")
                    append("advertising and nothing to buy. The source is at ")
                    withLink(LinkAnnotation.Url(SOURCE_URL)) { append(SOURCE_URL) }
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Where the GPL says the corresponding source can be had. */
private const val SOURCE_URL = "https://codeberg.org/arek/Gloaming"

/**
 * A licence behind a tap.
 *
 * Twenty lines of legal boilerplate inlined into a settings screen is read by
 * nobody and pushes everything useful off the bottom. Behind one tap it is
 * still shipped and still reachable, which is what MIT actually asks for.
 */
@Composable
private fun LicenceText(licence: String) {
    var shown by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { shown = !shown }) {
        Text(if (shown) "Hide licence" else "Show licence")
    }
    if (shown) {
        Text(licence, style = MaterialTheme.typography.bodySmall)
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
private fun WatchNowCard(state: MainUiState, onWatchingChange: (Boolean) -> Unit) {
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
                    "have missed alerts you expected. It stops on its own after four " +
                    "hours.",
                style = MaterialTheme.typography.bodySmall,
            )

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
 * Why the app is or is not checking at the moment.
 *
 * The schedule follows the sky, which means it deliberately does nothing for up
 * to twelve hours at a stretch. Without this sentence the screen says only
 * "checked 11 hours ago", and correct behaviour is indistinguishable from a
 * background service the system has killed - which is the reading that gets an
 * app uninstalled.
 *
 * @param localMinuteOfDay injected so the wording can be tested without
 *   depending on the machine's time zone.
 */
fun describeSchedule(
    darkness: Darkness,
    localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
): String = when (darkness) {
    is Darkness.Now -> "Dark now, so Gloaming is watching."

    is Darkness.Later ->
        "Sun is up. Nothing to see until it gets dark, around " +
            "${formatTimeOfDay(localMinuteOfDay(darkness.startsAt))}."

    Darkness.None ->
        "It does not get dark here at this time of year. " +
            "Gloaming checks twice a day until that changes."
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

/**
 * Why the station never appears, for a user it cannot reach.
 *
 * Null wherever the station does pass, which is most places anyone lives. The
 * app has no pass list that could show up empty - it speaks only through
 * notifications - so without this sentence a user in Tromso sees exactly what
 * they would see if the feature were broken, and reasonably concludes it is.
 *
 * Quotes the orbit's limit and their own latitude rather than asserting a
 * conclusion, so the claim can be checked against any other source.
 */
fun describeIssCoverage(latitude: Double): String? {
    val here = "The station's orbit reaches ${degrees(IssCoverage.INCLINATION_DEGREES)} " +
        "degrees north and south, and you are at ${degrees(latitude)}, so "
    return when (IssCoverage.of(latitude)) {
        IssCoverage.Covered -> null

        IssCoverage.Marginal -> here + "its passes here are low, brief and occasional."

        IssCoverage.None -> here + "it never comes above your horizon. Other bright " +
            "satellites are unaffected - many of them are in polar orbits."
    }
}

/**
 * One decimal place, without a locale's decimal separator.
 *
 * `String.format` would render "69,7" on a machine set to a decimal-comma
 * locale, in the middle of an English sentence. Sign is dropped because the
 * hemisphere does not change the answer.
 */
private fun degrees(value: Double): String {
    val tenths = kotlin.math.round(kotlin.math.abs(value) * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}
