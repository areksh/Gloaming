package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.WatchDuration

/** The tabs, reached by tag because a label alone is ambiguous with a heading. */
const val TAB_TONIGHT = "tab-tonight"
const val TAB_CALENDAR = "tab-calendar"
const val TAB_SETTINGS = "tab-settings"

/**
 * Three tabs over one activity.
 *
 * No navigation library: three tabs need no back stack, no deep links and no
 * new dependency, and `rememberSaveable` is enough to survive a rotation. The
 * app has been a single screen since Phase 1 and this is the smallest thing
 * that stops being one.
 *
 * Each tab owns its own scroll, which is why [TonightContent],
 * [CalendarContent] and [SettingsContent] emit loose children rather than
 * columns of their own.
 */
@Composable
fun GloamingTabs(
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
    onWatchingChange: (Boolean) -> Unit,
    onWatchDurationChange: (WatchDuration) -> Unit,
    onSave: () -> Unit,
    onPollNow: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected) {
            Tab(
                selected = selected == 0,
                onClick = { selected = 0 },
                text = { Text("Tonight") },
                modifier = Modifier.testTag(TAB_TONIGHT),
            )
            Tab(
                selected = selected == 1,
                onClick = { selected = 1 },
                text = { Text("Calendar") },
                modifier = Modifier.testTag(TAB_CALENDAR),
            )
            Tab(
                selected = selected == 2,
                onClick = { selected = 2 },
                text = { Text("Settings") },
                modifier = Modifier.testTag(TAB_SETTINGS),
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selected) {
                0 -> TonightContent(
                    state = state,
                    onWatchingChange = onWatchingChange,
                    onWatchDurationChange = onWatchDurationChange,
                    onPollNow = onPollNow,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onRequestNotifications = onRequestNotifications,
                )

                1 -> CalendarContent(
                    events = state.calendar,
                    hasLocation = state.settings.hasLocation,
                    loading = state.calendarLoading,
                )

                else -> SettingsContent(
                    state = state,
                    onLatitudeChange = onLatitudeChange,
                    onLongitudeChange = onLongitudeChange,
                    onThresholdChange = onThresholdChange,
                    onAlertsEnabledChange = onAlertsEnabledChange,
                    onBeforeUnlockChange = onBeforeUnlockChange,
                    onPreciseBeforeUnlockChange = onPreciseBeforeUnlockChange,
                    onCloudGateChange = onCloudGateChange,
                    onCloudToleranceChange = onCloudToleranceChange,
                    onQuietHoursEnabledChange = onQuietHoursEnabledChange,
                    onQuietFromChange = onQuietFromChange,
                    onQuietUntilChange = onQuietUntilChange,
                    onQuietHoursOverrideChange = onQuietHoursOverrideChange,
                    onNightVisionChange = onNightVisionChange,
                    onLogRetentionChange = onLogRetentionChange,
                    onExportLog = onExportLog,
                    onClearLog = onClearLog,
                    onAuroraIntervalChange = onAuroraIntervalChange,
                    onIssIntervalChange = onIssIntervalChange,
                    onBrightIntervalChange = onBrightIntervalChange,
                    onTrainIntervalChange = onTrainIntervalChange,
                    onPassAlertsChange = onPassAlertsChange,
                    onFollowStationChange = onFollowStationChange,
                    onPassLeadChange = onPassLeadChange,
                    onMinimumElevationChange = onMinimumElevationChange,
                    onLiveLeadChange = onLiveLeadChange,
                    onEventAlertsChange = onEventAlertsChange,
                    onEventHeadsUpChange = onEventHeadsUpChange,
                    onEventLeadChange = onEventLeadChange,
                    onEclipseFollowMaxChange = onEclipseFollowMaxChange,
                    onSave = onSave,
                )
            }
        }
    }
}
