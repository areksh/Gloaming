package sh.arek.gloaming

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.data.AndroidLocationSource
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.CalendarSyncer
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.share.ics
import sh.arek.gloaming.share.icsFileName
import sh.arek.gloaming.share.shareText
import sh.arek.gloaming.ui.GloamingTabs
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.formatTimeOfDay
import sh.arek.gloaming.ui.parseTimeOfDay
import sh.arek.gloaming.ui.theme.GloamingTheme
import sh.arek.gloaming.work.PassAlarm
import sh.arek.gloaming.work.PollAlarm
import sh.arek.gloaming.work.Refresh
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
/**
 * How often tonight's list is worked out again while the screen is open.
 *
 * Fifteen minutes. Passes move on and the darkness window turns over, but
 * recomputing on every minute tick would run the propagator over the whole
 * catalogue sixty times an hour for an answer that changes slowly.
 */
private const val TONIGHT_STALE_MILLIS = 15 * 60_000L

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
        // was ever lost. Rearming replaces whatever alarm was pending rather
        // than adding to it, so opening the app repeatedly costs nothing.
        //
        // Only when alerts are on, though. The settings switch cancels the
        // alarm and the boot receiver already declines to rearm it, so without
        // this the app would put back, on every launch, a wake somebody had
        // switched off - one that can only find alerts disabled and go back to
        // sleep.
        val settings = Graph.settings(this).read()
        if (settings.alertsEnabled) {
            PollAlarm.schedule(this, settings.pollIntervalMinutes)
        }

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

/**
 * Hands one event to whatever the user picks, as a message.
 *
 * Text rather than a file, deliberately. The two buttons answer different
 * questions: this one is for telling somebody about an eclipse, and an
 * attachment nobody opens is not telling them about it.
 */
private fun shareEvent(context: Context, event: SkyEvent) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, event.title)
        .putExtra(Intent.EXTRA_TEXT, shareText(event))

    context.startActivity(
        Intent.createChooser(send, "Share this event").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * The same event as a calendar file, for putting in a calendar.
 *
 * Through the same export directory and the same provider the log already
 * uses, so this needs no manifest change and - importantly - no calendar
 * permission at all. The user chooses where it goes; the app never reads
 * anything back.
 */
private fun exportEvent(context: Context, event: SkyEvent) {
    val exports = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(exports, icsFileName(event))
    file.writeText(ics(event))

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/calendar")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, event.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    context.startActivity(
        Intent.createChooser(send, "Export as a calendar file")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Both, because the provider needs a read to find where to write. */
private val CALENDAR_PERMISSIONS = listOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
    var tonight by remember { mutableStateOf<TonightList?>(null) }
    var detectingLocation by remember { mutableStateOf(false) }
    // Bumped to ask for the calendar again when the user refreshes by hand.
    var calendarNonce by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var locationNote by remember { mutableStateOf<String?>(null) }

    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryOk by remember { mutableStateOf(batteryExempt()) }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed()) }
    var saverOn by remember { mutableStateOf(powerSaveMode()) }
    var watching by remember { mutableStateOf(StormService.running) }
    var darkness by remember { mutableStateOf(darknessHere()) }
    var logSize by remember { mutableStateOf(Graph.journal(context).sizeBytes()) }
    var calendarName by remember { mutableStateOf<String?>(null) }
    var calendarNote by remember { mutableStateOf<String?>(null) }

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
     * Reads the phone's last known position and stores it, or says why not.
     *
     * Off the main thread because it touches the system's location store, and
     * silent about the position itself: the log records that a fix was taken
     * and never where it was, which is the same rule everything else here
     * follows.
     */
    fun detectLocation() {
        scope.launch {
            detectingLocation = true
            locationNote = null
            val fix = withContext(Dispatchers.IO) {
                runCatching { AndroidLocationSource(context).current() }
                    .onFailure { Log.w("Gloaming", "could not read a position", it) }
                    .getOrNull()
            }
            detectingLocation = false

            if (fix == null) {
                // Deliberately not "turn on your GPS". The app only ever reads
                // positions the phone already had, so the honest advice is to
                // use something that does ask for one, or to type it in.
                locationNote = "The phone has no recent position to hand. Open a map app " +
                    "briefly, or switch this off and type it in."
                return@launch
            }

            settings = settings.copy(latitude = fix.latitude, longitude = fix.longitude)
            latitudeInput = fix.latitude.toString()
            longitudeInput = fix.longitude.toString()
            BootAware.saveSettings(context, settings)
            locationNote = null
        }
    }

    /**
     * Brings the phone's calendar into line with the stars, here and now.
     *
     * Off the main thread: the calendar provider is another process, and a
     * user who has starred a dozen things is a dozen round trips through
     * Binder. The poller does the same thing on its own wake, so this is about
     * the entry appearing while somebody is still looking at the screen rather
     * than about correctness.
     */
    fun syncCalendar(user: UserSettings) {
        scope.launch {
            val writer = Graph.calendarWriter(context)
            val store = Graph.alertState(context)
            withContext(Dispatchers.IO) {
                val held = store.read()
                val record = CalendarSyncer.sync(
                    writer = writer,
                    destination = user.calendarSync,
                    starred = user.starredEvents,
                    events = calendar,
                    synced = held.syncedEvents,
                )
                if (record != held.syncedEvents) {
                    store.write(store.read().copy(syncedEvents = record))
                }
                calendarName = writer.destinationName(user.calendarSync)
            }
        }
    }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            calendarNote = null
            syncCalendar(settings)
        } else {
            // Straight back to off rather than leaving a destination selected
            // that cannot do anything. A control that silently does nothing is
            // worse than one that says why.
            settings = settings.copy(calendarSync = CalendarSync.Off)
            BootAware.saveSettings(context, settings)
            calendarNote = "Calendar permission was declined, so nothing is being written. " +
                "Share and Export on each event still work without it."
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            settings = settings.copy(locationMode = LocationMode.Automatic)
            BootAware.saveSettings(context, settings)
            detectLocation()
        } else {
            // Straight back to typing it in, rather than leaving a switch on
            // that cannot do anything. Sec. 7 requires the app be fully
            // functional with this denied, and a dead control is not that.
            settings = settings.copy(locationMode = LocationMode.Manual)
            BootAware.saveSettings(context, settings)
            locationNote = "Location permission was declined, so Gloaming will keep using " +
                "the position you type in."
        }
    }

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
            runCatching {
                withContext(Dispatchers.IO) { Refresh.everything(context, "CheckNow") }
            }
                .onSuccess {
                    // The soonest of the three, not the aurora interval alone.
                    // Taking only aurora's could push the next wake out past a
                    // pass alert already due, and deliver it after the pass.
                    PollAlarm.schedule(context, it.nextWakeMinutes(settings.pollIntervalMinutes))
                }
                .onFailure { Log.w("Gloaming", "check now failed", it) }
            alertState = stateStore.read()
            shownAt = System.currentTimeMillis()
            // The calendar is deterministic, so it does not go stale on its
            // own - but events do fall out of the far end of the horizon, and
            // somebody who pressed refresh meant all of it.
            calendarNonce++
            checking = false
        }
    }

    // Keyed on the location and nothing else: the calendar is deterministic and
    // does not go stale, so recomputing it on any other change would be work
    // done for no different answer.
    LaunchedEffect(settings.latitude, settings.longitude, calendarNonce) {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (latitude == null || longitude == null) {
            calendar = emptyList()
            return@LaunchedEffect
        }
        calendarLoading = true
        calendar = withContext(Dispatchers.Default) {
            runCatching {
                val now = System.currentTimeMillis()
                // Comets come from a document rather than from the ephemeris,
                // so they are worked out separately and merged in by date. A
                // device that has never fetched one simply has none, which is
                // also what most years look like.
                val sky = SkyCalendar.events(latitude, longitude, now)
                val comets = Comets.calendar(
                    Graph.comets(context).cached(),
                    latitude,
                    longitude,
                    settings,
                    now,
                )
                (sky + comets).sortedBy { it.at }
            }.getOrElse {
                Log.w("Gloaming", "could not work out the calendar", it)
                emptyList()
            }
        }
        calendarLoading = false
    }

    // Whenever the year ahead is recomputed, so a comet whose dates have moved
    // takes its calendar entry with it, and so the name of the calendar being
    // written to is there to be shown. Cheap and skipped entirely when the
    // feature is off, which is the default.
    LaunchedEffect(calendar, settings.calendarSync) {
        if (settings.calendarSync != CalendarSync.Off) syncCalendar(settings)
    }

    // Worked out from the calendar and the elements already on the device, so
    // it costs no network. Recomputed when the calendar is, and on the clock
    // tick, because "tonight" moves as the evening does.
    LaunchedEffect(calendar, settings.latitude, settings.longitude, shownAt / TONIGHT_STALE_MILLIS) {
        val latitude = settings.latitude
        val longitude = settings.longitude
        if (latitude == null || longitude == null) {
            tonight = null
            return@LaunchedEffect
        }
        tonight = withContext(Dispatchers.Default) {
            runCatching {
                // Both of these read the cache and stop. The screen must never
                // spend the one request an hour Open-Meteo's terms allow: the
                // poller already fetches it, and a tab that refetched on every
                // open would take that budget away from the alerts.
                val weather = Graph.weather(context)
                Tonight.of(
                    satellites = Graph.satellites(context).cached(),
                    comets = Graph.comets(context).cached(),
                    calendar = calendar,
                    settings = settings,
                    nowMillis = System.currentTimeMillis(),
                    cloud = weather.lastKnown(latitude, longitude),
                    hourly = weather.forecast(latitude, longitude),
                )
            }.onFailure { Log.w("Gloaming", "could not work out tonight", it) }.getOrNull()
        }
    }

    GloamingTheme(nightVision = settings.nightVision) {
        DimScreen(settings.nightVision)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
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
                    tonight = tonight,
                    detectingLocation = detectingLocation,
                    locationNote = locationNote,
                    calendarName = calendarName,
                    calendarNote = calendarNote,
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
                onEclipseFollowMaxChange = {
                    settings = settings.copy(eclipseFollowMaxMinutes = it)
                    BootAware.saveSettings(context, settings)
                },
                onNotifyEveryEventChange = {
                    settings = settings.copy(notifyAboutEveryEvent = it)
                    BootAware.saveSettings(context, settings)
                },
                // The four comet controls bump the calendar nonce as well as
                // saving, because all four change which comets belong in the
                // list and how they are described - unlike every other setting
                // here, which only changes when the app speaks.
                onCometMagnitudeChange = {
                    settings = settings.copy(cometMagnitudeThreshold = it)
                    BootAware.saveSettings(context, settings)
                    calendarNonce++
                },
                onCometAltitudeChange = {
                    settings = settings.copy(minimumCometAltitudeDegrees = it)
                    BootAware.saveSettings(context, settings)
                    calendarNonce++
                },
                onCometStaleChange = {
                    settings = settings.copy(cometObservationStaleDays = it)
                    BootAware.saveSettings(context, settings)
                    calendarNonce++
                },
                onCometMoonChange = {
                    settings = settings.copy(cometMoonTolerance = it)
                    BootAware.saveSettings(context, settings)
                    calendarNonce++
                },
                onToggleStar = { event ->
                    val key = EventPlanner.starKey(event)
                    val starring = key !in settings.starredEvents
                    val kept = if (starring) {
                        settings.starredEvents + key
                    } else {
                        settings.starredEvents - key
                    }
                    settings = settings.copy(
                        // Pruned as it is written, so a star on something long
                        // past does not sit in the preferences file forever.
                        starredEvents = EventPlanner.pruneStars(kept, System.currentTimeMillis()),
                    )
                    BootAware.saveSettings(context, settings)

                    // Said only when the star cannot change anything, and only
                    // when putting one on: somebody removing a star already
                    // knows they are curating, and being told twice is worse
                    // than not being told.
                    // Both ways round: a star puts the entry in and taking one
                    // off takes it out again, which is the only reading of the
                    // control that does not leave rubbish in somebody's diary.
                    if (settings.calendarSync != CalendarSync.Off) syncCalendar(settings)

                    if (starring && settings.notifyAboutEveryEvent) {
                        scope.launch {
                            snackbar.showSnackbar(
                                "Starred. You are being told about everything anyway - turn " +
                                    "that off under Settings to hear only your starred events.",
                            )
                        }
                    }
                },
                onToggleKind = { kind ->
                    val hidden = settings.hiddenEventKinds
                    settings = settings.copy(
                        hiddenEventKinds = if (kind.name in hidden) {
                            hidden - kind.name
                        } else {
                            hidden + kind.name
                        },
                    )
                    BootAware.saveSettings(context, settings)
                },
                onShareEvent = { shareEvent(context, it) },
                onExportEvent = { exportEvent(context, it) },
                onLocationModeChange = { mode ->
                    if (mode == LocationMode.Automatic &&
                        !AndroidLocationSource.hasPermission(context)
                    ) {
                        // Asked at the moment it is wanted rather than on first
                        // run, so the request arrives with its reason visible
                        // on screen behind it.
                        locationPermission.launch(AndroidLocationSource.PERMISSION)
                    } else {
                        settings = settings.copy(locationMode = mode)
                        BootAware.saveSettings(context, settings)
                        locationNote = null
                        if (mode == LocationMode.Automatic) detectLocation()
                    }
                },
                onCalendarSyncChange = { destination ->
                    val missing = CALENDAR_PERMISSIONS.filterNot { granted(context, it) }
                    if (destination != CalendarSync.Off && missing.isNotEmpty()) {
                        // Asked at the moment somebody picks a destination,
                        // never on first run. This is the most invasive thing
                        // the app could ask for, and the request arrives with
                        // its reason visible on the screen behind it.
                        settings = settings.copy(calendarSync = destination)
                        BootAware.saveSettings(context, settings)
                        calendarPermission.launch(missing.toTypedArray())
                    } else {
                        // Written before the sync runs, so that switching to
                        // Off is what the syncer reads and takes back.
                        settings = settings.copy(calendarSync = destination)
                        BootAware.saveSettings(context, settings)
                        calendarNote = null
                        if (destination == CalendarSync.Off) calendarName = null
                        syncCalendar(settings)
                    }
                },
                onDetectLocation = { detectLocation() },
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
