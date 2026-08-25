package sh.arek.gloaming.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.WatchDuration

/**
 * Tonight and the settings in one scrolling column, as the app has always
 * shown them.
 *
 * The two halves are now [TonightContent] and [SettingsContent] and this owns
 * the scroll around both, so the split changes nothing on screen. It is the
 * seam the tab bar goes into next: a tab wants one scroll per tab, and a
 * scroll nested inside a scroll does not work.
 */
@Composable
fun MainScreen(
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
    onWatchingChange: (Boolean) -> Unit,
    onWatchDurationChange: (WatchDuration) -> Unit,
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

        TonightContent(
            state = state,
            onWatchingChange = onWatchingChange,
            onWatchDurationChange = onWatchDurationChange,
            onPollNow = onPollNow,
            onRequestBatteryExemption = onRequestBatteryExemption,
            onRequestNotifications = onRequestNotifications,
        )

        SettingsContent(
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
            onSave = onSave,
        )
    }
}
