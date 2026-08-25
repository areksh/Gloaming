package sh.arek.gloaming

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.DisposableEffect
import sh.arek.gloaming.ui.GloamingTabs
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.formatTimeOfDay
import sh.arek.gloaming.ui.parseTimeOfDay
import sh.arek.gloaming.ui.theme.GloamingTheme
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.work.PassAlarm
import sh.arek.gloaming.work.PollAlarm
import sh.arek.gloaming.work.StormService

/**
 * How often the screen re-reads its clock while it is in front.
 *
 * A minute, because that is the finest unit anything displayed uses: the age
 * counts in whole minutes and the darkness sentence names a time to the minute.
 */
private const val TICK_MILLIS = 60_000L

/** What the switch offers when quiet hours are first turned on. */
private const val DEFAULT_QUIET_FROM = 23 * 60
private const val DEFAULT_QUIET_UNTIL = 7 * 60

/** Quiet hours from two typed times, or null if either is not a time. */
private fun quietHoursFrom(from: String, until: String): QuietHours? {
    val start = parseTimeOfDay(from) ?: return null
    val end = parseTimeOfDay(until) ?: return null
    return QuietHours(start, end)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Make sure a schedule exists, in case this is a first run or the chain
        // was ever lost. Enqueuing is idempotent under a unique work name.
        PollAlarm.schedule(this, Graph.settings(this).read().pollIntervalMinutes)

        setContent { GloamingApp() }
    }
}

/**
 * Dims the screen while night vision is on, and hands it back afterwards.
 *
 * Red on black is most of the answer but not all of it: a bright screen of any
 * colour still floods a dark-adapted eye. Set on this window rather than in
 * system settings, so it applies to this app alone and reverts the moment it
 * closes.
 */
@androidx.compose.runtime.Composable
private fun DimScreen(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled) {
        activity.setBrightness(if (enabled) NIGHT_BRIGHTNESS else SYSTEM_BRIGHTNESS)
        onDispose { activity.setBrightness(SYSTEM_BRIGHTNESS) }
    }
}

private fun Activity.setBrightness(value: Float) {
    window.attributes = window.attributes.apply { screenBrightness = value }
}

/** As dim as the platform allows without switching the backlight off entirely. */
private const val NIGHT_BRIGHTNESS = 0.01f

/** The sentinel that hands brightness back to the system. */
private const val SYSTEM_BRIGHTNESS = -1f

/**
 * Hands the log to whatever the user picks to receive it.
 *
 * A copy rather than the file itself. The journal lives in device-protected
 * storage and goes on being written to; sharing a live handle to it would give
 * another app a moving target and a foothold in this one's storage, where a
 * snapshot in the share directory is exactly what was asked for and nothing
 * more.
 */
private fun shareLog(context: Context) {
    val exports = File(context.cacheDir, "exports").apply { mkdirs() }
    val copy = File(exports, "gloaming-log.txt")
    copy.writeText(Graph.journal(context).read())

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", copy)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Gloaming log")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(Intent.createChooser(send, "Send Gloaming's log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The theme is applied here rather than around this, because night vision is a
 * setting and the settings are read here.
 */
@androidx.compose.runtime.Composable
private fun GloamingApp() {
    val context = LocalContext.current
    val settingsStore = remember { Graph.settings(context) }
    val stateStore = remember { Graph.alertState(context) }

    var settings by remember { mutableStateOf(settingsStore.read()) }
    var alertState by remember { mutableStateOf(stateStore.read()) }
    var latitudeInput by remember { mutableStateOf(settings.latitude?.toString().orEmpty()) }
    var longitudeInput by remember { mutableStateOf(settings.longitude?.toString().orEmpty()) }
    var quietFromInput by remember {
        mutableStateOf(formatTimeOfDay(settings.quietHours?.startMinute ?: DEFAULT_QUIET_FROM))
    }
    var quietUntilInput by remember {
        mutableStateOf(formatTimeOfDay(settings.quietHours?.endMinute ?: DEFAULT_QUIET_UNTIL))
    }

    fun batteryExempt(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun powerSaveMode(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false

    /**
     * How dark it is here, so the screen can explain a gap between checks.
     *
     * Needs no network and costs a fraction of a millisecond, so it is
     * recomputed on the tick below rather than held from when the screen
     * opened. A held answer goes stale at exactly the moment it matters -
     * dusk, which is the transition the sentence exists to describe.
     */
    fun darknessHere(): Darkness? {
        val latitude = settings.latitude ?: return null
        val longitude = settings.longitude ?: return null
        return runCatching {
            Twilight.darkness(latitude, longitude, System.currentTimeMillis())
        }.getOrNull()
    }

    // The clock the screen reads. Advanced on the tick below rather than only
    // when the screen is opened: an age frozen at "checked 3 minutes ago" while
    // somebody watches it is a small lie, and the same freeze is what would
    // leave "nothing to see until it gets dark" up after dark.
    // The year ahead, worked out once per location rather than per composition.
    // It costs about a quarter of a second, which is nothing on a background
    // thread and far too much on the main one.
    var calendar by remember { mutableStateOf(emptyList<SkyEvent>()) }
    var calendarLoading by remember { mutableStateOf(false) }

    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryOk by remember { mutableStateOf(batteryExempt()) }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed()) }
    var saverOn by remember { mutableStateOf(powerSaveMode()) }
    var watching by remember { mutableStateOf(StormService.running) }
    var darkness by remember { mutableStateOf(darknessHere()) }
    var logSize by remember { mutableStateOf(Graph.journal(context).sizeBytes()) }

    // Permission and exemption state changes outside the app, so re-read it
    // whenever the user comes back rather than trusting a stale snapshot.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = settingsStore.read()
                alertState = stateStore.read()
                batteryOk = batteryExempt()
                notificationsOk = notificationsAllowed()
                saverOn = powerSaveMode()
                watching = StormService.running
                darkness = darknessHere()
                logSize = Graph.journal(context).sizeBytes()
                shownAt = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only while the screen is actually in front. Both of the things this
    // refreshes are read off a clock, and neither is worth a wake when nobody
    // is looking at them.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(TICK_MILLIS)
                shownAt = System.currentTimeMillis()
                darkness = darknessHere()
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOk = notificationsAllowed() }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    /**
     * Runs a check the user asked for, here and now.
     *
     * Deliberately not through `AlarmManager`. `setAndAllowWhileIdle` is
     * inexact and the system attaches a batching window - just under two
     * minutes, measured on this hardware - so a "check now" routed through the
     * scheduler could sit unfired while the user stares at an unchanged screen.
     * A button somebody is watching gets run directly.
     *
     * The next scheduled wake is then re-armed from the plan the poll returned,
     * which is what the receiver does after every other poll. That matters most
     * after a location change: the pending alarm was worked out for the old
     * position, and dusk somewhere else is at a different time.
     */
    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Graph.poller(context).poll() } }
                .onSuccess { PollAlarm.schedule(context, it.next.delayMinutes) }
                .onFailure { Log.w("Gloaming", "check now failed", it) }
            alertState = stateStore.read()
            shownAt = System.currentTimeMillis()
            checking = false
        }
    }

    // Keyed on the location and nothing else: the calendar is deterministic and
    // does not go stale, so recomputing it on any other change would be work
    // done for no different answer.
    LaunchedEffect(settings.latitude, settings.longitude) {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (latitude == null || longitude == null) {
            calendar = emptyList()
            return@LaunchedEffect
        }
        calendarLoading = true
        calendar = withContext(Dispatchers.Default) {
            runCatching {
                SkyCalendar.events(latitude, longitude, System.currentTimeMillis())
            }.getOrElse {
                Log.w("Gloaming", "could not work out the calendar", it)
                emptyList()
            }
        }
        calendarLoading = false
    }

    GloamingTheme(nightVision = settings.nightVision) {
        DimScreen(settings.nightVision)
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            GloamingTabs(
                state = MainUiState(
                    settings = settings,
                    alertState = alertState,
                    latitudeInput = latitudeInput,
                    longitudeInput = longitudeInput,
                    quietFromInput = quietFromInput,
                    quietUntilInput = quietUntilInput,
                    batteryExempt = batteryOk,
                    notificationsAllowed = notificationsOk,
                    nowMillis = shownAt,
                    checking = checking,
                    darkness = darkness,
                    powerSaveMode = saverOn,
                    watching = watching,
                    logSizeBytes = logSize,
                    calendar = calendar,
                    calendarLoading = calendarLoading,
                ),
                onLatitudeChange = { latitudeInput = it },
                onLongitudeChange = { longitudeInput = it },
                onThresholdChange = {
                    settings = settings.copy(threshold = it)
                    BootAware.saveSettings(context, settings)
                },
                onAlertsEnabledChange = {
                    settings = settings.copy(alertsEnabled = it)
                    BootAware.saveSettings(context, settings)
                    if (it) PollAlarm.schedule(context, settings.pollIntervalMinutes) else PollAlarm.cancel(context)
                },
                onBeforeUnlockChange = {
                    settings = settings.copy(alertsBeforeUnlock = it)
                    BootAware.saveSettings(context, settings)
                },
                // Coarsening again clears the cloud reading taken at the finer
                // position; BootAware.saveSettings owns that.
                onPreciseBeforeUnlockChange = {
                    settings = settings.copy(preciseLocationBeforeUnlock = it)
                    BootAware.saveSettings(context, settings)
                },
                onCloudGateChange = {
                    settings = settings.copy(cloudGateEnabled = it)
                    BootAware.saveSettings(context, settings)
                },
                onCloudToleranceChange = {
                    settings = settings.copy(cloudTolerance = it)
                    BootAware.saveSettings(context, settings)
                },
                onQuietHoursEnabledChange = { enabled ->
                    settings = settings.copy(
                        quietHours = if (enabled) quietHoursFrom(quietFromInput, quietUntilInput) else null,
                    )
                    BootAware.saveSettings(context, settings)
                },
                // Applied as typed, and only when both fields parse. A half-typed
                // "2" must not become quiet hours starting at two in the morning.
                onQuietFromChange = { typed ->
                    quietFromInput = typed
                    quietHoursFrom(typed, quietUntilInput)?.let {
                        settings = settings.copy(quietHours = it)
                        BootAware.saveSettings(context, settings)
                    }
                },
                onQuietUntilChange = { typed ->
                    quietUntilInput = typed
                    quietHoursFrom(quietFromInput, typed)?.let {
                        settings = settings.copy(quietHours = it)
                        BootAware.saveSettings(context, settings)
                    }
                },
                onQuietHoursOverrideChange = {
                    settings = settings.copy(quietHoursOverride = it)
                    BootAware.saveSettings(context, settings)
                },
                onNightVisionChange = {
                    settings = settings.copy(nightVision = it)
                    BootAware.saveSettings(context, settings)
                },
                onLogRetentionChange = {
                    settings = settings.copy(logRetentionDays = it)
                    BootAware.saveSettings(context, settings)
                    // Turning it off means off now, not once the next poll gets
                    // round to pruning. Somebody switching it off is asking for
                    // the log to stop existing, not to stop growing.
                    if (!settings.keepsLog) Graph.journal(context).clear()
                    logSize = Graph.journal(context).sizeBytes()
                },
                onExportLog = {
                    runCatching { shareLog(context) }
                        .onFailure { Log.w("Gloaming", "could not export the log", it) }
                },
                onClearLog = {
                    Graph.journal(context).clear()
                    logSize = 0L
                },
                onAuroraIntervalChange = {
                    settings = settings.copy(auroraAlertIntervalMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onIssIntervalChange = {
                    settings = settings.copy(issPassAlertIntervalMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onBrightIntervalChange = {
                    settings = settings.copy(brightPassAlertIntervalMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onTrainIntervalChange = {
                    settings = settings.copy(trainPassAlertIntervalMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onPassAlertsChange = {
                    settings = settings.copy(passAlertsEnabled = it)
                    BootAware.saveSettings(context, settings)
                },
                // Turning it off drops whatever pass is queued. There is one
                // slot and it carries no record of how it was filled, so this
                // also drops a pass followed by hand - which is the right way
                // round to be wrong: somebody who has just said they do not
                // want this should not have a tracker start anyway.
                onFollowStationChange = {
                    settings = settings.copy(followStationPassesAutomatically = it)
                    BootAware.saveSettings(context, settings)
                    if (!it) PassAlarm.cancel(context)
                },
                // Optimistic, then corrected. The service reports itself
                // running from its own thread a moment later, and a switch that
                // did not move until then would read as ignoring the tap.
                onWatchingChange = { wanted ->
                    if (wanted) StormService.watchNow(context) else StormService.stop(context)
                    watching = wanted
                },
                onPassLeadChange = {
                    settings = settings.copy(passAlertLeadMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onMinimumElevationChange = {
                    settings = settings.copy(minimumPassElevationDegrees = it)
                    BootAware.saveSettings(context, settings)
                },
                onLiveLeadChange = {
                    settings = settings.copy(liveFollowLeadMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onEventAlertsChange = {
                    settings = settings.copy(eventAlertsEnabled = it)
                    BootAware.saveSettings(context, settings)
                },
                onEventHeadsUpChange = {
                    settings = settings.copy(eventHeadsUpDays = it)
                    BootAware.saveSettings(context, settings)
                },
                onEventLeadChange = {
                    settings = settings.copy(eventAlertLeadMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onWatchDurationChange = {
                    settings = settings.copy(watchDuration = it)
                    BootAware.saveSettings(context, settings)
                },
                onSave = {
                    val latitude = latitudeInput.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                    val longitude = longitudeInput.trim().toDoubleOrNull()?.takeIf { it in -360.0..360.0 }
                    settings = settings.copy(latitude = latitude, longitude = longitude)
                    BootAware.saveSettings(context, settings)
                    latitudeInput = latitude?.toString().orEmpty()
                    longitudeInput = longitude?.toString().orEmpty()
                    // A new position changes the whole schedule, not just the
                    // reading: dusk somewhere else is at a different time.
                    checkNow()
                },
                onPollNow = { checkNow() },
                onRequestBatteryExemption = {
                    // Sends the user to the system list rather than using the
                    // direct-request intent, which Play forbids and which several OEM
                    // builds ignore. Off-Play either is permitted; the settings screen
                    // is the one that behaves consistently.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }.onFailure {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}
