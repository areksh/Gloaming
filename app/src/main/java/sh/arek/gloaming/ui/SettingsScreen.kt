package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.AlertDecision
import sh.arek.gloaming.domain.PollingPolicy

/**
 * Everything that is a setting rather than a state of the sky.
 *
 * Emitted as loose children for the same reason [TonightContent] is: the
 * caller owns the scrolling.
 */
@Composable
fun SettingsContent(
    state: MainUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onThresholdChange: (Int) -> Unit,
    onAlertsEnabledChange: (Boolean) -> Unit,
    onBeforeUnlockChange: (Boolean) -> Unit,
    onPreciseBeforeUnlockChange: (Boolean) -> Unit,
    onCloudGateChange: (Boolean) -> Unit,
    onCloudToleranceChange: (Int) -> Unit,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    onQuietFromChange: (String) -> Unit,
    onQuietUntilChange: (String) -> Unit,
    onQuietHoursOverrideChange: (Boolean) -> Unit,
    onNightVisionChange: (Boolean) -> Unit,
    onLogRetentionChange: (Int) -> Unit,
    onExportLog: () -> Unit,
    onClearLog: () -> Unit,
    onAuroraIntervalChange: (Int) -> Unit,
    onIssIntervalChange: (Int) -> Unit,
    onBrightIntervalChange: (Int) -> Unit,
    onTrainIntervalChange: (Int) -> Unit,
    onPassAlertsChange: (Boolean) -> Unit,
    onFollowStationChange: (Boolean) -> Unit,
    onPassLeadChange: (Int) -> Unit,
    onMinimumElevationChange: (Int) -> Unit,
    onLiveLeadChange: (Int) -> Unit,
    onEventAlertsChange: (Boolean) -> Unit,
    onEventHeadsUpChange: (Int) -> Unit,
    onEventLeadChange: (Int) -> Unit,
    onEclipseFollowMaxChange: (Int) -> Unit,
    onNotifyEveryEventChange: (Boolean) -> Unit,
    onCometMagnitudeChange: (Int) -> Unit,
    onCometAltitudeChange: (Int) -> Unit,
    onCometStaleChange: (Int) -> Unit,
    onCometMoonChange: (Int) -> Unit,
    onLocationModeChange: (LocationMode) -> Unit,
    onDetectLocation: () -> Unit,
    onSave: () -> Unit,
) {
    LocationCard(
        state,
        onLatitudeChange,
        onLongitudeChange,
        onSave,
        onLocationModeChange,
        onDetectLocation,
    )
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
        onPassLeadChange,
        onMinimumElevationChange,
        onLiveLeadChange,
    )
    CalendarCard(
        state,
        onEventAlertsChange,
        onEventHeadsUpChange,
        onEventLeadChange,
        onEclipseFollowMaxChange,
        onNotifyEveryEventChange,
    )
    CometCard(
        state,
        onCometMagnitudeChange,
        onCometAltitudeChange,
        onCometStaleChange,
        onCometMoonChange,
    )
    BeforeUnlockCard(state, onBeforeUnlockChange, onPreciseBeforeUnlockChange)
    NightVisionCard(state, onNightVisionChange)
    LogCard(state, onLogRetentionChange, onExportLog, onClearLog)
    CreditsCard(state)
}

@Composable
private fun LocationCard(
    state: MainUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onSave: () -> Unit,
    onLocationModeChange: (LocationMode) -> Unit,
    onDetectLocation: () -> Unit,
) {
    val automatic = state.settings.locationMode == LocationMode.Automatic

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything the app works out depends on where you are. Type it in, or let " +
                    "the phone say - whichever suits, and you can change your mind here at " +
                    "any time.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Let the phone tell me", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = automatic,
                    onCheckedChange = {
                        onLocationModeChange(
                            if (it) LocationMode.Automatic else LocationMode.Manual,
                        )
                    },
                    modifier = Modifier.testTag(LOCATION_MODE_SWITCH_TAG),
                )
            }

            if (automatic) {
                Text(
                    "Only while the app is open, and only roughly - to about a kilometre, " +
                        "which is all any of the forecasts use. Gloaming never asks for your " +
                        "position in the background and never turns the GPS on; it reads the " +
                        "last position the phone already had.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onDetectLocation,
                    enabled = !state.detectingLocation,
                    modifier = Modifier.fillMaxWidth().testTag(DETECT_LOCATION_TAG),
                ) {
                    Text(if (state.detectingLocation) "Looking..." else "Use my position now")
                }
                state.locationNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (state.settings.hasLocation) {
                        "Using ${coordinates(state.settings.latitude!!, state.settings.longitude!!)}."
                    } else {
                        "No position yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "North and east are positive. Typing it in is also how you ask about " +
                        "somewhere you are not.",
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
            ChoiceRow(
                label = "Alert above",
                value = "${state.settings.threshold}% local probability",
                choices = UserSettings.THRESHOLD_CHOICES,
                render = ::describePercent,
                onChange = onThresholdChange,
            )
            Text(
                "Higher latitudes want a higher bar.",
                style = MaterialTheme.typography.bodySmall,
            )
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
                ChoiceRow(
                    label = "Hold alerts back above",
                    value = "${state.settings.cloudTolerance}% cloud",
                    choices = UserSettings.CLOUD_TOLERANCE_CHOICES,
                    render = ::describePercent,
                    onChange = onCloudToleranceChange,
                )
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
    onPassLeadChange: (Int) -> Unit,
    onMinimumElevationChange: (Int) -> Unit,
    onLiveLeadChange: (Int) -> Unit,
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

                HorizontalDivider()

                // How much warning, and what is worth warning about. Both were
                // constants; neither is a fact about the sky.
                ChoiceRow(
                    label = "Tell me",
                    value = "${state.settings.passAlertLeadMinutes} minutes before it is highest",
                    choices = UserSettings.PASS_LEAD_CHOICES,
                    render = { "${it}m" },
                    onChange = onPassLeadChange,
                )
                Text(
                    "Also the window the best pass is picked from, so a longer warning " +
                        "means a better pass as well as an earlier one.",
                    style = MaterialTheme.typography.bodySmall,
                )

                ChoiceRow(
                    label = "Ignore passes below",
                    value = "${state.settings.minimumPassElevationDegrees} degrees up",
                    choices = UserSettings.MINIMUM_ELEVATION_CHOICES,
                    render = { "$it deg" },
                    onChange = onMinimumElevationChange,
                )
                Text(
                    "Roughly where roofs and trees stop. Raise it in a city, lower it " +
                        "if you have a clear horizon.",
                    style = MaterialTheme.typography.bodySmall,
                )

                ChoiceRow(
                    label = "Live tracker starts",
                    value = "${state.settings.liveFollowLeadMinutes} minutes before it rises",
                    choices = UserSettings.LIVE_LEAD_CHOICES,
                    render = { "${it}m" },
                    onChange = onLiveLeadChange,
                )
            }
        }
    }
}

/**
 * A labelled value with its choices under it.
 *
 * The choices **wrap**. A plain Row clips whatever does not fit and gives no
 * sign of having done so: the button is still in the semantics tree, still
 * reported as present, and simply cannot be pressed. That is how "30 deg" went
 * missing off the right edge - found by a test that could see it and not press
 * it, which is exactly the shape of bug a user would report as "the last option
 * does nothing".
 *
 * It also means a large system font size costs a row of height rather than the
 * last choice.
 */
@OptIn(ExperimentalLayoutApi::class)
/** The location controls, which sit beside sentences rather than values. */
const val LOCATION_MODE_SWITCH_TAG = "location-mode-switch"
const val EVERY_EVENT_SWITCH_TAG = "every-event-switch"
const val DETECT_LOCATION_TAG = "detect-location"

/** How a chip in a [ChoiceRow] is reached from a test. */
fun choiceTag(label: String, rendered: String): String = "choice:$label:$rendered"

@Composable
private fun ChoiceRow(
    label: String,
    value: String,
    choices: List<Int>,
    render: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in choices) {
                // Tagged because these cannot be identified by the setting they
                // sit beside, unlike every other control here: "1h" appears in
                // seven different rows, so picking one by its text alone means
                // picking by position in the tree - which is exactly what broke
                // when the watch card moved to its own tab.
                OutlinedButton(
                    onClick = { onChange(option) },
                    modifier = Modifier.testTag(choiceTag(label, render(option))),
                ) { Text(render(option)) }
            }
        }
    }
}

@Composable
private fun IntervalRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    ChoiceRow(
        label = label,
        value = describeInterval(minutes),
        choices = UserSettings.ALERT_INTERVAL_CHOICES,
        render = ::shortInterval,
        onChange = onChange,
    )
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
private fun BeforeUnlockCard(
    state: MainUiState,
    onBeforeUnlockChange: (Boolean) -> Unit,
    onPreciseBeforeUnlockChange: (Boolean) -> Unit,
) {
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
                if (state.settings.preciseLocationBeforeUnlock) {
                    "The cost: the location you typed, exactly as you typed it, and your " +
                        "alert settings are copied to storage the system can read before " +
                        "you unlock. Turning this off deletes that copy."
                } else {
                    "The cost: your rounded location " +
                        (state.settings.cell?.let { "(${it.latitude}, ${it.longitude}) " } ?: "") +
                        "and alert settings are copied to storage the system can read " +
                        "before you unlock. Only the whole degree is stored - about " +
                        "111 km - never the position you typed. Turning this off deletes " +
                        "that copy."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            // Only once the copy exists at all. Offering a choice about how
            // precise something is, when there is no something, is noise.
            if (state.settings.alertsBeforeUnlock) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Store my exact location",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.settings.preciseLocationBeforeUnlock,
                        onCheckedChange = onPreciseBeforeUnlockChange,
                        modifier = Modifier.testTag(PRECISE_BEFORE_UNLOCK_TAG),
                    )
                }
                Text(
                    "Only worth it for satellite passes. The aurora grid is read at " +
                        "whole degrees anyway, so rounding costs an aurora alert nothing " +
                        "- but 111 km moves a pass by tens of seconds and several " +
                        "degrees of elevation, so \"look south-west at 22:14\" stops " +
                        "being quite true. What you are trading is that anyone who can " +
                        "read this phone before it is unlocked reads where you are, " +
                        "rather than roughly which part of the country.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
 * The app's own log: how much to keep, and what to do with it.
 *
 * Logcat is the system's ring buffer and it has usually rolled past the
 * interesting part by the time anyone asks what happened overnight - which is
 * the only window this app works in. So it keeps its own, and the user owns it:
 * how long to keep, whether to keep it at all, and the two things anyone
 * actually wants to do with a log, which are read it elsewhere and get rid of
 * it.
 */
@Composable
private fun LogCard(
    state: MainUiState,
    onLogRetentionChange: (Int) -> Unit,
    onExportLog: () -> Unit,
    onClearLog: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gloaming's own log", style = MaterialTheme.typography.titleMedium)
            Text(
                "What the app did and why, kept on this phone. Android's own log is a " +
                    "buffer the system recycles, and overnight is exactly when it has " +
                    "already rolled past whatever you wanted to see.",
                style = MaterialTheme.typography.bodySmall,
            )

            ChoiceRow(
                label = "Keep",
                value = describeRetention(state.settings.logRetentionDays),
                choices = UserSettings.LOG_RETENTION_CHOICES,
                render = { if (it == 0) "Off" else "${it}d" },
                onChange = onLogRetentionChange,
            )

            Text(
                "It records decisions - what was checked, what was announced, when the " +
                    "next wake is - and never where you are.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportLog,
                    enabled = state.logSizeBytes > 0,
                    modifier = Modifier.testTag(EXPORT_LOG_TAG),
                ) {
                    Text("Export")
                }
                OutlinedButton(
                    onClick = onClearLog,
                    enabled = state.logSizeBytes > 0,
                    modifier = Modifier.testTag(CLEAR_LOG_TAG),
                ) {
                    Text("Clear")
                }
            }
            Text(describeLogSize(state.logSizeBytes), style = MaterialTheme.typography.bodySmall)
        }
    }
}

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



/** The switch and the two leads the sky calendar interrupts on. */
const val EVENT_ALERTS_SWITCH_TAG = "event-alerts-switch"

/**
 * How the calendar is allowed to interrupt.
 *
 * One switch and two leads, rather than an interval per channel as the pass
 * card carries. Passes needed intervals because a clear night has upwards of
 * forty worth looking at; the whole calendar produces about forty events a
 * year, and only the ones that can actually be seen from here ever speak. A
 * rate limit built for things that come over nightly would have nothing to do,
 * and Android's own per-channel switch already covers "keep eclipses, silence
 * conjunctions".
 */
/**
 * The four numbers that decide what a person hears about comets.
 *
 * Its own card rather than four more rows on the calendar's, because comets ask
 * a different question. Everything else in the calendar either happens or does
 * not - an eclipse is at a time, a planet is at an elongation - whereas a comet
 * is a judgement about whether a faint smudge is worth going outside for, and
 * the answer depends entirely on the person and the sky they have.
 */
@Composable
private fun CometCard(
    state: MainUiState,
    onCometMagnitudeChange: (Int) -> Unit,
    onCometAltitudeChange: (Int) -> Unit,
    onCometStaleChange: (Int) -> Unit,
    onCometMoonChange: (Int) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Comets", style = MaterialTheme.typography.titleMedium)
            Text(
                "Orbits come from the Minor Planet Center and brightnesses from what " +
                    "observers have actually reported, because the formula for how bright " +
                    "a comet should be is routinely a magnitude out in either direction.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                label = "Faintest worth telling me",
                value = describeMagnitude(state.settings.cometMagnitudeThreshold),
                choices = UserSettings.COMET_MAGNITUDE_CHOICES,
                render = ::describeMagnitude,
                onChange = onCometMagnitudeChange,
            )
            Text(
                "Magnitude 6 is about the limit of an unaided eye from a properly dark " +
                    "site. Setting it fainter brings in comets you would need binoculars " +
                    "for, which in most years is the only kind there is.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                label = "Must get this high",
                value = describeDegrees(state.settings.minimumCometAltitudeDegrees),
                choices = UserSettings.COMET_ALTITUDE_CHOICES,
                render = ::describeDegrees,
                onChange = onCometAltitudeChange,
            )
            ChoiceRow(
                label = "Moonlight I will put up with",
                value = describePercent(state.settings.cometMoonTolerance),
                choices = UserSettings.COMET_MOON_CHOICES,
                render = ::describePercent,
                onChange = onCometMoonChange,
            )
            Text(
                "Moonlight matters more for a comet than for anything else here. A comet " +
                    "is a smudge spread across a patch of sky rather than a point, so a " +
                    "bright moon washes it out where it would barely dim a planet.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                label = "Trust a report for",
                value = describeDays(state.settings.cometObservationStaleDays),
                choices = UserSettings.COMET_STALE_CHOICES,
                render = ::describeDays,
                onChange = onCometStaleChange,
            )
            Text(
                "After that the app goes back to the predicted brightness and says so. " +
                    "Comets change fast enough that a month-old report of one in outburst " +
                    "says nothing about tonight.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CalendarCard(
    state: MainUiState,
    onEventAlertsChange: (Boolean) -> Unit,
    onEventHeadsUpChange: (Int) -> Unit,
    onEventLeadChange: (Int) -> Unit,
    onEclipseFollowMaxChange: (Int) -> Unit,
    onNotifyEveryEventChange: (Boolean) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sky calendar", style = MaterialTheme.typography.titleMedium)
            Text(
                "Eclipses, meteor showers and close pairings of planets. Only the ones " +
                    "actually visible from where you are will interrupt; the rest are in " +
                    "the Calendar tab with the reason.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tell me about these", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.eventAlertsEnabled,
                    onCheckedChange = onEventAlertsChange,
                    modifier = Modifier.testTag(EVENT_ALERTS_SWITCH_TAG),
                )
            }

            if (state.settings.eventAlertsEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tell me about all of them", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.settings.notifyAboutEveryEvent,
                        onCheckedChange = onNotifyEveryEventChange,
                        modifier = Modifier.testTag(EVERY_EVENT_SWITCH_TAG),
                    )
                }
                Text(
                    if (state.settings.notifyAboutEveryEvent) {
                        "Everything visible from where you are will interrupt. Turn this off " +
                            "and only the events you have starred in the Calendar tab will."
                    } else {
                        "Only the events you star in the Calendar tab will interrupt."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Eclipses and shower peaks get two notices: one a few days ahead so " +
                        "you can plan around it, and one on the day. Everything else gets " +
                        "the one on the day.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceRow(
                    label = "Notice ahead",
                    value = describeDays(state.settings.eventHeadsUpDays),
                    choices = UserSettings.EVENT_HEADS_UP_CHOICES,
                    render = ::describeDays,
                    onChange = onEventHeadsUpChange,
                )
                ChoiceRow(
                    label = "Notice on the day",
                    value = describeLead(state.settings.eventAlertLeadMinutes),
                    choices = UserSettings.EVENT_LEAD_CHOICES,
                    render = ::shortInterval,
                    onChange = onEventLeadChange,
                )
                Text(
                    "An eclipse notification carries a Follow it action, which counts down " +
                        "to each contact in turn and says where to look. It needs no signal " +
                        "and stops on its own.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceRow(
                    label = "Stop following after",
                    value = describeDuration(state.settings.eclipseFollowMaxMinutes),
                    choices = UserSettings.ECLIPSE_FOLLOW_MAX_CHOICES,
                    render = ::shortInterval,
                    onChange = onEclipseFollowMaxChange,
                )
            }
        }
    }
}
