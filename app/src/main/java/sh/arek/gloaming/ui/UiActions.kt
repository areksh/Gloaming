package sh.arek.gloaming.ui

import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.WatchDuration

/**
 * Every callback the screens can raise, in one place.
 *
 * A plain bundle rather than an interface: the screens take it as one
 * parameter instead of threading sixty lambdas, and every member defaults to
 * a no-op so a test sets only the ones it observes. [sh.arek.gloaming.MainActivity]
 * is the one place that builds the real thing.
 */
data class UiActions(
    val onLatitudeChange: (String) -> Unit = {},
    val onLongitudeChange: (String) -> Unit = {},
    val onThresholdChange: (Int) -> Unit = {},
    val onAlertsEnabledChange: (Boolean) -> Unit = {},
    val onBeforeUnlockChange: (Boolean) -> Unit = {},
    val onPreciseBeforeUnlockChange: (Boolean) -> Unit = {},
    val onCloudGateChange: (Boolean) -> Unit = {},
    val onCloudToleranceChange: (Int) -> Unit = {},
    val onQuietHoursEnabledChange: (Boolean) -> Unit = {},
    val onQuietFromChange: (String) -> Unit = {},
    val onQuietUntilChange: (String) -> Unit = {},
    val onQuietHoursOverrideChange: (Boolean) -> Unit = {},
    val onNightVisionChange: (Boolean) -> Unit = {},
    val onShowEveryConstellationChange: (Boolean) -> Unit = {},
    val onLogRetentionChange: (Int) -> Unit = {},
    val onExportLog: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onAuroraIntervalChange: (Int) -> Unit = {},
    val onIssIntervalChange: (Int) -> Unit = {},
    val onBrightIntervalChange: (Int) -> Unit = {},
    val onTrainIntervalChange: (Int) -> Unit = {},
    val onPassAlertsChange: (Boolean) -> Unit = {},
    val onFollowStationChange: (Boolean) -> Unit = {},
    val onPassLeadChange: (Int) -> Unit = {},
    val onMinimumElevationChange: (Int) -> Unit = {},
    val onLiveLeadChange: (Int) -> Unit = {},
    val onEventAlertsChange: (Boolean) -> Unit = {},
    val onEventHeadsUpChange: (Int) -> Unit = {},
    val onEventLeadChange: (Int) -> Unit = {},
    val onEclipseFollowMaxChange: (Int) -> Unit = {},
    val onNotifyEveryEventChange: (Boolean) -> Unit = {},
    val onToggleStar: (SkyEvent) -> Unit = {},
    val onToggleKind: (EventKind) -> Unit = {},
    val onShareEvent: (SkyEvent) -> Unit = {},
    val onExportEvent: (SkyEvent) -> Unit = {},
    val onCalendarSyncChange: (CalendarSync) -> Unit = {},
    val onVerdict: (Answer) -> Unit = {},
    val onAutomaticSettingsChange: (Boolean) -> Unit = {},
    val onClearVerdicts: () -> Unit = {},
    val onCometMagnitudeChange: (Int) -> Unit = {},
    val onCometAltitudeChange: (Int) -> Unit = {},
    val onCometStaleChange: (Int) -> Unit = {},
    val onCometMoonChange: (Int) -> Unit = {},
    val onLocationModeChange: (LocationMode) -> Unit = {},
    val onLocationCadenceChange: (LocationCadence) -> Unit = {},
    val onDetectLocation: () -> Unit = {},
    val onWatchingChange: (Boolean) -> Unit = {},
    val onWatchDurationChange: (WatchDuration) -> Unit = {},
    val onSave: () -> Unit = {},
    val onPollNow: () -> Unit = {},
    val onRequestBatteryExemption: () -> Unit = {},
    val onRequestNotifications: () -> Unit = {},
    val onSetupDone: () -> Unit = {},
    /** The compass check was offered at this instant, so it is not offered again for a week. */
    val onCompassOffered: (Long) -> Unit = {},
    /** The figure of eight was done at this instant. */
    val onCompassChecked: (Long) -> Unit = {},
    /** The compass correction is now this many degrees, from Line up or a reset. */
    val onCompassOffsetChange: (Double) -> Unit = {},
    val onNightVisionWhenFindingChange: (Boolean) -> Unit = {},
)
