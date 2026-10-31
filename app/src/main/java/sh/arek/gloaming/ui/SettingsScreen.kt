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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.BuildConfig
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.AlertDecision
import sh.arek.gloaming.domain.PollingPolicy
import sh.arek.gloaming.domain.Recommendation

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
    onShowEveryConstellationChange: (Boolean) -> Unit,
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
    onCalendarSyncChange: (CalendarSync) -> Unit,
    onAutomaticSettingsChange: (Boolean) -> Unit,
    onClearVerdicts: () -> Unit,
    onCometMagnitudeChange: (Int) -> Unit,
    onCometAltitudeChange: (Int) -> Unit,
    onCometStaleChange: (Int) -> Unit,
    onCometMoonChange: (Int) -> Unit,
    onLocationModeChange: (LocationMode) -> Unit,
    onLocationCadenceChange: (LocationCadence) -> Unit,
    onDetectLocation: () -> Unit,
    onSave: () -> Unit,
) {
    LocationCard(
        state,
        onLatitudeChange,
        onLongitudeChange,
        onSave,
        onLocationModeChange,
        onLocationCadenceChange,
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
        onCalendarSyncChange,
    )
    CometCard(
        state,
        onCometMagnitudeChange,
        onCometAltitudeChange,
        onCometStaleChange,
        onCometMoonChange,
    )
    ConstellationCard(state, onShowEveryConstellationChange)
    AutomaticSettingsCard(state, onAutomaticSettingsChange)
    VerdictsCard(state, onClearVerdicts)
    BeforeUnlockCard(state, onBeforeUnlockChange, onPreciseBeforeUnlockChange)
    NightVisionCard(state, onNightVisionChange)
    LogCard(state, onLogRetentionChange, onExportLog, onClearLog)
    CreditsCard(state)
    VersionFooter()
}

/**
 * Which build this is, at the foot of the settings.
 *
 * Outside a card and last of everything, because it is not a setting and
 * nobody comes looking for it until something has gone wrong - at which point
 * it is the first thing worth quoting. There is nowhere else to read it: the
 * app is installed from an APK rather than a store, so no listing carries the
 * version and nothing updates it in the background, and a phone can sit on one
 * build for months without a word about it anywhere.
 *
 * Read from the build rather than written down here, so it cannot drift from
 * the APK it is printed in.
 */
@Composable
private fun VersionFooter() {
    Text(
        versionLine(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_COMMIT,
            BuildConfig.GIT_MODIFIED,
            BuildConfig.DEBUG,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LocationCard(
    state: MainUiState,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onSave: () -> Unit,
    onLocationModeChange: (LocationMode) -> Unit,
    onLocationCadenceChange: (LocationCadence) -> Unit,
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
                Text("Ask the phone", style = MaterialTheme.typography.bodyMedium)
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
                // True of every cadence below, the background ones included:
                // the background grant extends the coarse one in time, never in
                // precision (ADR-0016).
                Text(
                    "Approximate only: to within a few kilometres, which is all the sky " +
                        "needs. Gloaming never turns the GPS on and never logs where you are.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (state.settings.hasLocation) {
                        "Using ${coordinates(state.settings.latitude!!, state.settings.longitude!!)}."
                    } else {
                        "No position yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    describeLocationUpdated(state.settings.locationUpdatedAt, state.nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onDetectLocation,
                    enabled = !state.detectingLocation,
                    modifier = Modifier.fillMaxWidth().testTag(DETECT_LOCATION_TAG),
                ) {
                    Text(if (state.detectingLocation) "Looking..." else "Update now")
                }
                state.locationNote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                CadenceChoice(state, onLocationCadenceChange)
            } else {
                Text(
                    "Nothing is asked of the phone at all. North and east are positive. " +
                        "Typing it in is also how you ask about somewhere you are not.",
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

/**
 * How often a detected position is refreshed: the presets, then any whole
 * number of hours.
 *
 * Each choice is reported rather than acted on. A background cadence needs a
 * grant Android asks for in its own words, and the caller owns that exchange
 * and decides what is stored if the answer is no - so what is marked here is
 * always the cadence in force, not the last one tapped.
 */
@Composable
private fun CadenceChoice(
    state: MainUiState,
    onLocationCadenceChange: (LocationCadence) -> Unit,
) {
    val cadence = state.settings.locationCadence
    val storedCustom = cadence is LocationCadence.Every && cadence !in LocationCadence.PRESETS
    var customOpen by remember { mutableStateOf(storedCustom) }
    var customInput by remember {
        mutableStateOf((cadence as? LocationCadence.Every)?.hours?.toString().orEmpty())
    }
    val customHours = LocationCadence.parseHours(customInput)
    // Custom stays marked while somebody is typing into it, even through a
    // number that happens to be a preset. It lets go once the cadence in force
    // is no longer an interval at all - a refused grant puts it back to opening
    // the app, and the field would otherwise go on claiming a cadence that is
    // not running.
    val customSelected = storedCustom ||
        (customOpen && (cadence is LocationCadence.Every || customHours == null))

    Text("How often", style = MaterialTheme.typography.bodyMedium)
    for (option in LocationCadence.PRESETS) {
        CadenceRow(
            label = describeCadence(option),
            detail = cadenceDetail(option),
            selected = !customSelected && cadence == option,
            tag = CADENCE_TAG_PREFIX + cadenceTagSuffix(option),
            onClick = {
                customOpen = false
                onLocationCadenceChange(option)
            },
        )
    }
    CadenceRow(
        label = "Custom",
        detail = null,
        selected = customSelected,
        tag = CADENCE_TAG_PREFIX + "custom",
        onClick = {
            customInput = (cadence as? LocationCadence.Every)?.hours?.toString().orEmpty()
            customOpen = true
        },
    )
    if (customSelected) {
        OutlinedTextField(
            value = customInput,
            onValueChange = {
                customInput = it
                LocationCadence.parseHours(it)?.let { hours ->
                    onLocationCadenceChange(LocationCadence.Every(hours))
                }
            },
            label = { Text("Hours") },
            singleLine = true,
            isError = customHours == null,
            supportingText = if (customHours == null) {
                {
                    Text(
                        "Enter a whole number of hours between ${LocationCadence.MIN_HOURS} " +
                            "and ${LocationCadence.MAX_HOURS}.",
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(CADENCE_HOURS_FIELD_TAG),
        )
    }

    if (state.settings.backgroundLocationRevoked) {
        Text(
            "Android no longer lets Gloaming read your position in the background, so it " +
                "now updates each time you open the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    // Said before any interval is picked, because picking one is what brings
    // up Android's page, and that page offers precise location too.
    if (!state.backgroundLocationAllowed) {
        Text(
            "Android will ask you to allow location all the time. Choose approximate " +
                "there too; Gloaming never asks for more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CadenceRow(
    label: String,
    detail: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Why somebody would pick a cadence, where the label alone does not say. */
private fun cadenceDetail(cadence: LocationCadence): String? = when (cadence) {
    LocationCadence.OnOpen -> "Enough if you mostly watch from home."
    LocationCadence.Every(6) -> "Keeps alerts right when you travel without opening the app."
    LocationCadence.Every(24) -> "Keeps up with you on trips, using less battery."
    else -> null
}

private fun cadenceTagSuffix(cadence: LocationCadence): String = when (cadence) {
    LocationCadence.OnOpen -> "open"
    LocationCadence.Manual -> "manual"
    is LocationCadence.Every -> cadence.hours.toString()
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
                note = calibrationNote(state, state.recommendations?.threshold, ::describePercent),
                enabled = !state.settings.automaticSettings || state.recommendations == null,
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
                    note = calibrationNote(
                        state,
                        state.recommendations?.cloudTolerance,
                        ::describePercent,
                    ),
                    enabled = !state.settings.automaticSettings || state.recommendations == null,
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

            IntervalRow(
                "Aurora",
                state.settings.auroraAlertIntervalMinutes,
                onAuroraIntervalChange,
                state,
                state.recommendations?.auroraInterval,
            )

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
                    state,
                    state.recommendations?.passInterval,
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
                    state,
                    // The same figure as the station's. The verdict record
                    // carries the satellite's name, not its channel, so three
                    // separate recommendations would be two inventions.
                    state.recommendations?.passInterval,
                )
                // Its own answer rather than a share of the bright catalogue's.
                // A launch is a train for a few nights and then never again,
                // and a limit built for objects that come over every night
                // would routinely spend itself on one of those instead.
                IntervalRow(
                    "Starlink trains",
                    state.settings.trainPassAlertIntervalMinutes,
                    onTrainIntervalChange,
                    state,
                    state.recommendations?.passInterval,
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
                    note = calibrationNote(
                        state,
                        state.recommendations?.minimumPassElevation,
                    ) { "$it degrees" },
                    enabled = !state.settings.automaticSettings || state.recommendations == null,
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
const val LOG_RETENTION_CUSTOM_TAG = "log-retention-custom"
const val LOG_RETENTION_FIELD_TAG = "log-retention-field"
const val EVERY_EVENT_SWITCH_TAG = "every-event-switch"
const val DETECT_LOCATION_TAG = "detect-location"
/** A cadence row: the prefix, then "open", "manual", "custom" or its hours. */
const val CADENCE_TAG_PREFIX = "cadence:"
const val CADENCE_HOURS_FIELD_TAG = "cadence-hours-field"

/** How a chip in a [ChoiceRow] is reached from a test. */
fun choiceTag(label: String, rendered: String): String = "choice:$label:$rendered"

@Composable
private fun ChoiceRow(
    label: String,
    value: String,
    choices: List<Int>,
    render: (Int) -> String,
    onChange: (Int) -> Unit,
    /** What the app has worked out about this setting, where it has. */
    note: String? = null,
    /**
     * False when the app is setting this itself.
     *
     * The choices are removed rather than greyed out. A row of buttons that
     * cannot be pressed is a row of buttons somebody will press, and then
     * conclude the app is broken; the note above says who is deciding and the
     * switch that hands it back is one card away.
     */
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!enabled) return@Column
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
private fun IntervalRow(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
    state: MainUiState? = null,
    recommendation: Recommendation? = null,
) {
    ChoiceRow(
        label = label,
        value = describeInterval(minutes),
        choices = UserSettings.ALERT_INTERVAL_CHOICES,
        render = ::shortInterval,
        onChange = onChange,
        note = calibrationNote(state, recommendation, ::describeInterval),
        enabled = state?.settings?.automaticSettings != true || recommendation == null,
    )
}

/**
 * What the app has worked out about a setting, said in the user's own terms.
 *
 * Three states rather than two, and the third is the point. With automatic mode
 * *off* and something recorded, the app still says what it would have chosen -
 * which is most of the value of calibration at none of its cost: the app makes
 * its case and the user decides. Silent when there is nothing recorded and
 * nothing is being managed, because "not enough nights yet" repeated under
 * seven controls is noise.
 */
private fun calibrationNote(
    state: MainUiState?,
    recommendation: Recommendation?,
    render: (Int) -> String,
): String? {
    if (state == null || recommendation == null) return null

    return when {
        state.settings.automaticSettings ->
            "Set for you from ${nights(recommendation.sample)}. ${recommendation.reason}"

        recommendation.sample > 0 ->
            "Your nights suggest ${render(recommendation.value)}. ${recommendation.reason}"

        else -> null
    }
}

private fun nights(sample: Int) = if (sample == 1) "1 verdict" else "$sample verdicts"

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

/** How a test reaches the constellation switch. */
const val EVERY_CONSTELLATION_SWITCH_TAG = "every-constellation-switch"

/**
 * How much of the sky the constellations card names.
 *
 * A setting rather than a decision made for everybody, because the two answers
 * suit different people. Trimmed, the card leads with what has changed since
 * last month; in full it is every pattern above the horizon, which is what
 * somebody working through them all actually wants.
 */
@Composable
private fun ConstellationCard(state: MainUiState, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Every constellation", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.showEveryConstellation,
                    onCheckedChange = onChange,
                    modifier = Modifier.testTag(EVERY_CONSTELLATION_SWITCH_TAG),
                )
            }
            Text(
                if (state.settings.showEveryConstellation) {
                    "Every pattern above the horizon tonight, highest first - about " +
                        "fifteen of them on a clear winter night."
                } else {
                    "The handful worth leading with, and the ones that never set named " +
                        "in a line at the end. Ranked by height alone the patterns that " +
                        "are up every night of the year fill the card, and what has " +
                        "changed since last month never reaches it."
                },
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
    val days = state.settings.logRetentionDays
    var customOpen by remember { mutableStateOf(days !in UserSettings.LOG_RETENTION_CHOICES) }
    var customInput by remember { mutableStateOf(if (days > 0) days.toString() else "") }
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
                onChange = {
                    customOpen = false
                    onLogRetentionChange(it)
                },
            )

            // A preset the person typed is still a custom entry while they are
            // editing it, so this is a flag and not "is the value a preset".
            val customSelected = days !in UserSettings.LOG_RETENTION_CHOICES
            val onCustom = {
                customInput = days.takeIf { it > 0 }?.toString().orEmpty()
                customOpen = true
            }
            val customModifier = Modifier
                .testTag(LOG_RETENTION_CUSTOM_TAG)
                .semantics { selected = customSelected }
            if (customSelected) {
                Button(onClick = onCustom, modifier = customModifier) { Text("Custom") }
            } else {
                OutlinedButton(onClick = onCustom, modifier = customModifier) { Text("Custom") }
            }
            if (customOpen) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it
                        UserSettings.parseLogRetention(it)?.let(onLogRetentionChange)
                    },
                    label = { Text("Days") },
                    singleLine = true,
                    isError = UserSettings.parseLogRetention(customInput) == null,
                    supportingText = if (UserSettings.parseLogRetention(customInput) == null) {
                        {
                            Text(
                                "Enter a whole number of days between 1 and " +
                                    "${UserSettings.MAX_LOG_RETENTION_DAYS}.",
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(LOG_RETENTION_FIELD_TAG),
                )
            }

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

/**
 * Whether starred events are written into the phone's own calendar.
 *
 * Three answers rather than a switch, because "put it in my calendar" means two
 * different things to two reasonable people - and choosing for them would be
 * wrong either way. What each one costs is stated here rather than left to be
 * discovered, since one of them sends these entries to whatever server the
 * user's calendar already syncs to.
 *
 * The permission is asked for at the moment somebody picks a destination, never
 * on first run. It is the most invasive thing this app could ask for, and the
 * Share and Export buttons on every event need none of it.
 */
@Composable
private fun CalendarSyncControl(state: MainUiState, onChange: (CalendarSync) -> Unit) {
    Text("In your calendar", style = MaterialTheme.typography.titleSmall)
    Text(
        "Events you star can be written into a calendar on this phone. Nothing is " +
            "read back, and only the entries Gloaming wrote are ever changed or " +
            "removed - unstarring one takes it out again.",
        style = MaterialTheme.typography.bodySmall,
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in CalendarSync.entries) {
            OutlinedButton(
                onClick = { onChange(option) },
                modifier = Modifier.testTag(calendarSyncTag(option)),
            ) { Text(option.label) }
        }
    }

    Text(
        when (state.settings.calendarSync) {
            CalendarSync.Off -> "Off. Nothing is written anywhere, and the app holds no " +
                "calendar permission."

            CalendarSync.OwnCalendar -> "Writing to a calendar Gloaming makes on this phone. " +
                "It has no account behind it, so nothing syncs it anywhere - it simply " +
                "appears alongside your others."

            CalendarSync.DeviceCalendar -> "Writing to your default calendar. If that is a " +
                "Google or work account, these entries go to that server like everything " +
                "else in it - which is your existing arrangement rather than something " +
                "Gloaming introduces, but worth knowing."
        },
        style = MaterialTheme.typography.bodySmall,
    )

    // Named where it could be found, because "writing to your default calendar"
    // on a phone with four of them is not an answer. Absent is also an answer:
    // a phone with no writable calendar at all is a real configuration, and
    // silently writing nowhere would look exactly like a broken feature.
    state.calendarName?.let {
        Text("Entries go into \"$it\".", style = MaterialTheme.typography.bodySmall)
    }

    state.calendarNote?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** How a destination is reached from a test. */
fun calendarSyncTag(option: CalendarSync): String = "calendar-sync:${option.name}"

/**
 * What the alerts have been worth, in the user's own answers.
 *
 * The buttons on every alert have been collecting these for as long as they
 * have existed, and the app has only ever shown their consequences: a threshold
 * that moved, and a sentence saying why. That is the wrong way round. A
 * recommendation whose evidence cannot be seen is one that can only be taken on
 * trust, which is exactly what the card above it was written not to ask for -
 * and the record is somebody's own account of their own nights, which they
 * should not need a laptop and `run-as` to read.
 *
 * Sits directly under the automatic-settings switch because that is what it is
 * evidence for.
 */
@Composable
private fun VerdictsCard(state: MainUiState, onClear: () -> Unit) {
    val tallies = tallyVerdicts(state.verdicts)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What your alerts were worth", style = MaterialTheme.typography.titleMedium)

            if (state.verdicts.isEmpty()) {
                Text(
                    "Nothing answered yet. Every alert carries two buttons, and pressing " +
                        "one records what the sky was doing at the time - which is what " +
                        "Gloaming works your thresholds out from.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            for (tally in tallies) {
                Text(
                    "${name(tally.channel)} - ${describe(tally)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider()

            for (verdict in newestFirst(state.verdicts, limit = VERDICTS_SHOWN)) {
                Column {
                    Text(
                        "${verdict.answer.label} - ${answeredOn(verdict.answeredAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        describe(verdict.conditions),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (state.verdicts.size > VERDICTS_SHOWN) {
                Text(
                    "The $VERDICTS_SHOWN most recent of ${state.verdicts.size}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(onClick = onClear, modifier = Modifier.testTag(CLEAR_VERDICTS_TAG)) {
                Text("Clear")
            }
            Text(
                "Clearing takes the thresholds back to their widest, because there is " +
                    "then nothing to narrow them with. Nothing here records where you " +
                    "were, and none of it leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Enough to see the shape of a run of alerts without the card becoming the
 * screen. The whole record is still there, and `./go verdicts` prints it.
 */
private const val VERDICTS_SHOWN = 8

/**
 * `SimpleDateFormat` rather than `java.time`: minSdk 24 without core library
 * desugaring, the same constraint the calendar screen works under.
 */
private val ANSWERED_ON = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

private fun answeredOn(millis: Long): String = ANSWERED_ON.format(Date(millis))

/**
 * Whether the app sets its own alert thresholds from the verdicts.
 *
 * The default thresholds have been guesses since the first version, and the
 * documents have said so in writing throughout: they need a season of real
 * nights, and a number that suits somebody in Kent is wrong for somebody in
 * Shetland. This is the switch that lets the app work them out from the nights
 * the user actually had.
 *
 * Off by default, so no existing install changes behaviour without being asked.
 * And composed over the stored settings rather than written into them, which is
 * what makes the switch safe to try: turning it off restores exactly what was
 * there, because nothing was ever overwritten.
 */
@Composable
private fun AutomaticSettingsCard(state: MainUiState, onChange: (Boolean) -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Set my thresholds for me", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.automaticSettings,
                    onCheckedChange = onChange,
                    modifier = Modifier.testTag(AUTOMATIC_SETTINGS_SWITCH_TAG),
                )
            }
            Text(
                "Every alert asks whether it was worth going out for. With this on, " +
                    "Gloaming uses those answers to set the alert threshold, the cloud " +
                    "tolerance, the pass horizon and how often each channel may " +
                    "interrupt - and says underneath each one what it decided and why.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (state.settings.automaticSettings) {
                    "Your own choices are kept exactly as they were. Switch this off and " +
                        "they come straight back - nothing here overwrites them."
                } else {
                    "Off. Gloaming still says what it would have chosen underneath each " +
                        "setting, so you can take its word or leave it."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Until there is enough to go on it sets everything as wide as it goes. " +
                    "Missing a real aurora is the failure that matters; one look out of " +
                    "the window is not. Nothing about any of this leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The automatic-settings switch, which sits beside a sentence rather than a value. */
const val AUTOMATIC_SETTINGS_SWITCH_TAG = "automatic-settings-switch"

@Composable
private fun CalendarCard(
    state: MainUiState,
    onEventAlertsChange: (Boolean) -> Unit,
    onEventHeadsUpChange: (Int) -> Unit,
    onEventLeadChange: (Int) -> Unit,
    onEclipseFollowMaxChange: (Int) -> Unit,
    onNotifyEveryEventChange: (Boolean) -> Unit,
    onCalendarSyncChange: (CalendarSync) -> Unit,
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

            HorizontalDivider()
            CalendarSyncControl(state, onCalendarSyncChange)
        }
    }
}
