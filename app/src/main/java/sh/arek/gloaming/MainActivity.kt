package sh.arek.gloaming

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.SkyCalendar
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.TonightEvents
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.notify.FindIt
import sh.arek.gloaming.comets.Comets
import sh.arek.gloaming.data.AndroidLocationSource
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.data.Conditions
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.QuietHours
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.Verdict
import sh.arek.gloaming.data.isDue
import sh.arek.gloaming.domain.CadenceAnswer
import sh.arek.gloaming.domain.CadencePick
import sh.arek.gloaming.domain.CadenceRequest
import sh.arek.gloaming.domain.StalePending
import sh.arek.gloaming.domain.CalendarSyncer
import sh.arek.gloaming.domain.Calibration
import sh.arek.gloaming.domain.EventPlanner
import sh.arek.gloaming.domain.LocationGrants
import sh.arek.gloaming.domain.LocationRefresh
import sh.arek.gloaming.domain.OutlookInput
import sh.arek.gloaming.domain.Outlooks
import sh.arek.gloaming.domain.Recommendations
import sh.arek.gloaming.domain.SkyPoller
import sh.arek.gloaming.domain.Tonight
import sh.arek.gloaming.domain.encode
import sh.arek.gloaming.domain.findTargetFrom
import sh.arek.gloaming.net.TransferState
import sh.arek.gloaming.net.Transfers
import sh.arek.gloaming.share.ics
import sh.arek.gloaming.share.icsFileName
import sh.arek.gloaming.share.shareText
import sh.arek.gloaming.ui.CalendarKey
import sh.arek.gloaming.ui.DownloadRow
import sh.arek.gloaming.ui.DimScreen
import sh.arek.gloaming.ui.GloamingRoot
import sh.arek.gloaming.ui.downloadRows
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.MonthStrips
import sh.arek.gloaming.ui.NightTimeline
import sh.arek.gloaming.ui.OutlookWords
import sh.arek.gloaming.ui.TONIGHT_STALE_MILLIS
import sh.arek.gloaming.ui.TonightKey
import sh.arek.gloaming.ui.UiActions
import sh.arek.gloaming.ui.WorkedNight
import sh.arek.gloaming.ui.formatTimeOfDay
import sh.arek.gloaming.ui.localDay
import sh.arek.gloaming.ui.parseTimeOfDay
import sh.arek.gloaming.widget.AuroraWidget
import sh.arek.gloaming.widget.HeldUpdate
import sh.arek.gloaming.widget.WidgetContent
import sh.arek.gloaming.ui.theme.GloamingTheme
import sh.arek.gloaming.work.LocationAlarm
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

/**
 * How often the downloads card re-reads what the fetcher has been doing.
 *
 * Half a second: fast enough that a check reads as progress rather than as a
 * frozen card, and cheap enough not to matter - the tick reads one atomic
 * integer and stops unless it has moved.
 */
private const val DOWNLOADS_TICK_MILLIS = 500L

/** The elements, as the transfer record labels them. */
private const val ELEMENTS_LABEL = "Satellite elements"

/**
 * Set as the activity stops to be rebuilt for a configuration change, and
 * taken by the first start of the one that replaces it, which is not an open.
 *
 * In memory rather than in saved state, because it is exactly as long-lived
 * as the thing it marks: a rebuild for a configuration happens within the
 * process, and a process started afresh is always a real open. Saved state is
 * also written before `onStop` below Android 9, too early to know.
 */
private var recreatedForConfiguration = false

/** Quiet hours from two typed times, or null if either is not a time. */
private fun quietHoursFrom(from: String, until: String): QuietHours? {
    val start = parseTimeOfDay(from) ?: return null
    val end = parseTimeOfDay(until) ?: return null
    return QuietHours(start, end)
}

class MainActivity : ComponentActivity() {

    /**
     * The Find target a notification's "Find it" handed in, encoded, until the
     * screen has used it. Snapshot state so a second tap while the app is open
     * reaches the composition; cleared once shown so the same target can be
     * aimed at again.
     */
    private var pendingFind by mutableStateOf<String?>(null)

    /**
     * `singleTop` so tapping "Find it" while the app is already in front lands
     * here rather than stacking a second copy.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeFindTarget(intent)
    }

    /**
     * Moves the target out of [intent] and into [pendingFind], as
     * [findTargetFrom] reads it. Removed from the intent so a recreation,
     * which hands the same intent back, does not aim Find a second time.
     */
    private fun takeFindTarget(intent: Intent) {
        val extra = intent.getStringExtra(FindIt.EXTRA_FIND_TARGET) ?: return
        intent.removeExtra(FindIt.EXTRA_FIND_TARGET)
        pendingFind = findTargetFrom(
            extra,
            setupDone = Graph.settings(this).read().setupDone,
            launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
        )?.encode()
    }

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

        // Only on a fresh start: after a rotation or process death the intent
        // is the original one, and its target has already been used.
        if (savedInstanceState == null) takeFindTarget(intent)

        setContent { GloamingApp(initialFind = pendingFind, onInitialFindShown = { pendingFind = null }) }
    }
}

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

/**
 * The theme is applied here rather than around this, because night vision is a
 * setting and the settings are read here.
 */
@androidx.compose.runtime.Composable
private fun GloamingApp(initialFind: String?, onInitialFindShown: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { Graph.settings(context) }
    val stateStore = remember { Graph.alertState(context) }
    val compass = remember { Graph.compass(context) }

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

    /**
     * Every save from this screen, so the background location alarm follows
     * the mode and the cadence as saved.
     *
     * Re-armed only when one of those two changed. Arming replaces the pending
     * alarm, and for an overdue position it asks for a quarter of an hour from
     * now - so re-arming on every save let somebody adjusting other settings
     * keep pushing that refresh further off.
     *
     * The screen's copy is only as fresh as the last time it was brought
     * forward, and the background refresh may have stored a newer fix since;
     * saving any other setting from that copy would write the older position
     * back over it. So a newer stored fix wins, and the screen takes it too.
     */
    fun saveSettings(user: UserSettings) {
        val previous = settingsStore.read()
        val saving = LocationRefresh.keepNewerFix(user, previous)
        BootAware.saveSettings(context, saving)
        if (saving != user) {
            settings = saving
            latitudeInput = saving.latitude?.toString().orEmpty()
            longitudeInput = saving.longitude?.toString().orEmpty()
        }
        if (saving.locationMode != previous.locationMode ||
            saving.locationCadence != previous.locationCadence
        ) {
            LocationAlarm.arm(context, saving)
        }
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
    // The year ahead and tonight, held for the process rather than this
    // composition: each takes several seconds on a phone, and held here they
    // went with every rotation and were worked out again from nothing.
    val results = remember { Graph.screenResults(context) }
    var detectingLocation by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Saved across recreation, like the request it reports on: Android may
    // rebuild the activity while the user is on its settings page, and a note
    // held only in memory goes with it.
    var locationNote by rememberSaveable { mutableStateOf<String?>(null) }
    var backgroundLocationOk by remember {
        mutableStateOf(AndroidLocationSource.hasBackgroundPermission(context))
    }

    /**
     * Brings the screen's copy of the settings into line with the grants
     * Android holds now, saving it when that changed anything.
     *
     * Either grant can be withdrawn in Android's settings while the app is
     * closed, and only the background receiver ever noticed. The note, when
     * there is one, is for a change the Location page has no other line for.
     */
    fun reconcileGrants() {
        val grants = Graph.locationGrants(context)
        val reconciled = LocationGrants.reconcile(
            settings,
            locationGranted = grants.location(),
            backgroundGranted = grants.background(),
        )
        if (reconciled.settings !== settings) {
            settings = reconciled.settings
            saveSettings(settings)
        }
        reconciled.note?.let { locationNote = it }
    }

    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryOk by remember { mutableStateOf(batteryExempt()) }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed()) }
    var saverOn by remember { mutableStateOf(powerSaveMode()) }
    var watching by remember { mutableStateOf(StormService.running) }
    var watchingUntil by remember { mutableStateOf(StormService.endsAt) }
    var darkness by remember { mutableStateOf(darknessHere()) }
    var logSize by remember { mutableStateOf(Graph.journal(context).sizeBytes()) }
    var recommendations by remember { mutableStateOf<Recommendations?>(null) }
    var verdicts by remember { mutableStateOf<List<Verdict>>(emptyList()) }
    // Bumped when the record is cleared, which is the one change to it that
    // does not go through a pending verdict and so would otherwise leave the
    // card and the recommendations showing what was just deleted.
    var verdictsNonce by remember { mutableIntStateOf(0) }
    var calendarName by remember { mutableStateOf<String?>(null) }
    var calendarNote by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableStateOf(emptyList<DownloadRow>()) }
    var downloading by remember { mutableStateOf(emptyList<String>()) }
    var elementsFailure by remember { mutableStateOf<String?>(null) }

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
                backgroundLocationOk = AndroidLocationSource.hasBackgroundPermission(context)
                saverOn = powerSaveMode()
                watching = StormService.running
                watchingUntil = StormService.endsAt
                darkness = darknessHere()
                logSize = Graph.journal(context).sizeBytes()
                shownAt = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The first composition as well as every return: the observer above only
    // fires once the lifecycle reaches resumed, after a first frame drawn from
    // settings the grants may already have outgrown.
    LaunchedEffect(Unit) { reconcileGrants() }

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

    // What the app has been downloading, on a tick of its own.
    //
    // Polled rather than pushed. There is not a flow anywhere in this app and
    // one introduced here would be the only one; a version counter read while
    // the screen is in front is enough for a card that changes a handful of
    // times a minute, and it costs an atomic read. The rows are rebuilt when
    // that counter moves or when the minute does - the second because the age
    // of a held copy goes on climbing whether or not anything is fetched.
    //
    // The ages themselves are a handful of `stat` calls, kept off the main
    // thread out of habit rather than necessity.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastVersion = -1
            var lastMinute = -1L
            while (true) {
                val version = Transfers.version()
                val minute = System.currentTimeMillis() / TICK_MILLIS
                if (version != lastVersion || minute != lastMinute) {
                    lastVersion = version
                    lastMinute = minute
                    // Every document the device keeps a copy of, by the name
                    // the transfer record knows it under, so the two halves of
                    // a row meet. Anything not listed here has no copy on the
                    // device to age - it only ever appears once fetched.
                    val held = withContext(Dispatchers.IO) {
                        runCatching {
                            val sky = Graph.sky(context)
                            mapOf(
                                "Endpoints" to Graph.manifests(context).cachedAgeMillis(),
                                "Space weather" to sky.statusAgeMillis(),
                                "Aurora grid" to sky.gridAgeMillis(),
                                "Satellite elements" to
                                    Graph.satellites(context).cachedAgeMillis(),
                                "Comets" to Graph.comets(context).cachedAgeMillis(),
                                "Cloud forecast" to Graph.weather(context).heldAgeMillis(),
                            )
                        }.getOrDefault(emptyMap())
                    }
                    downloads = downloadRows(
                        transfers = Transfers.snapshot(),
                        held = held,
                        now = System.currentTimeMillis(),
                    )
                }
                downloading = Transfers.inFlight()
                elementsFailure = Transfers.snapshot()
                    .firstOrNull { it.label == ELEMENTS_LABEL && it.state == TransferState.Failed }
                    ?.error
                delay(DOWNLOADS_TICK_MILLIS)
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOk = notificationsAllowed() }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    /**
     * Asks the phone for its approximate position, a fresh one if the last is stale,
     * and stores it, or says why not.
     *
     * Off the main thread because it touches the system's location store, and
     * silent about the position itself: the log records that a fix was taken
     * and never where it was, which is the same rule everything else here
     * follows.
     *
     * [fromOpen] is the detection opening the app does by itself, which leaves
     * the note alone either way. Nobody asked for it, so it has nothing to
     * report - and it runs again on coming back from Android's settings page,
     * where clearing the note would wipe the answer that page just gave.
     */
    fun detectLocation(fromOpen: Boolean = false) {
        scope.launch {
            detectingLocation = true
            if (!fromOpen) locationNote = null
            val fix = withContext(Dispatchers.IO) {
                runCatching { Graph.locationSource(context).current() }
                    .onFailure { Log.w("Gloaming", "could not read a position", it) }
                    .getOrNull()
            }
            detectingLocation = false

            if (fix == null) {
                if (fromOpen) return@launch
                // The source has already asked the phone for a fresh fix, so
                // there is nothing more to suggest than trying again or typing it in.
                locationNote = "The phone could not find its position just now. Try " +
                    "Update now again in a moment, or type it in."
                return@launch
            }

            // Stamped here as well as by the background refresh, so the next
            // one is counted from this fix rather than treated as overdue.
            settings = settings.copy(
                latitude = fix.latitude,
                longitude = fix.longitude,
                locationUpdatedAt = System.currentTimeMillis(),
            )
            latitudeInput = fix.latitude.toString()
            longitudeInput = fix.longitude.toString()
            saveSettings(settings)
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
            // The store this composition already holds, not a fresh one. Two
            // stores over the same file are two caches that diverge, and a row
            // id written through one was invisible to the other.
            val store = stateStore
            withContext(Dispatchers.IO) {
                val held = store.read()
                val record = CalendarSyncer.sync(
                    writer = writer,
                    destination = user.calendarSync,
                    starred = user.starredEvents,
                    events = results.calendar,
                    synced = held.syncedEvents,
                )
                if (record != held.syncedEvents) {
                    // Through the state the screen holds, so the next sync in
                    // this composition sees what this one wrote.
                    alertState = store.read().copy(syncedEvents = record)
                    store.write(alertState)
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
            saveSettings(settings)
            calendarNote = "Calendar permission was declined, so nothing is being written. " +
                "Share and Export on each event still work without it."
        }
    }

    /**
     * Straight back to typing it in, rather than leaving a switch on that
     * cannot do anything. Sec. 7 requires the app be fully functional with
     * this denied, and a dead control is not that.
     */
    fun coarseRefused() {
        settings = settings.copy(locationMode = LocationMode.Manual)
        saveSettings(settings)
        locationNote = "Location permission was declined, so Gloaming will keep using " +
            "the position you type in."
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            settings = settings.copy(locationMode = LocationMode.Automatic)
            saveSettings(settings)
            detectLocation()
        } else {
            coarseRefused()
        }
    }

    // Opening the app is a refresh for every cadence that counts it as one:
    // each time for the default, and for an interval only once it is due, so
    // a phone opened often does not ask the system more than it was told to.
    // Read from the store rather than the screen's copy, which is brought
    // forward only on resume and so after this.
    //
    // A rotation is not an open. Asked again there, an approximate fix
    // wanders, and a moved position works the year and the night out again.
    val activity = LocalActivity.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations == true) {
                recreatedForConfiguration = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (recreatedForConfiguration) {
                recreatedForConfiguration = false
                return@repeatOnLifecycle
            }
            val stored = settingsStore.read()
            if (stored.locationMode == LocationMode.Automatic &&
                Graph.locationGrants(context).location() &&
                stored.locationCadence.isDue(stored.locationUpdatedAt, System.currentTimeMillis())
            ) {
                detectLocation(fromOpen = true)
            }
        }
    }

    // The interval a background grant is being asked for, while Android has
    // the question. Saved across recreation by its hours - only an interval is
    // ever asked for - because from API 30 the question is a trip to Android's
    // settings page, and the activity may be rebuilt before it comes back.
    var pendingHours by rememberSaveable { mutableStateOf<Int?>(null) }

    // Whether a request was launched from this screen and has not come back.
    // Not saved: after the process is restarted or the activity rebuilt, a
    // pendingHours that is still set has lost its answer. See
    // CadenceRequest.stalePending.
    var requestLaunched by remember { mutableStateOf(false) }

    fun backgroundMissing(): List<String> = AndroidLocationSource.backgroundRequest(
        sdk = Build.VERSION.SDK_INT,
        coarseGranted = AndroidLocationSource.hasPermission(context),
        backgroundGranted = AndroidLocationSource.hasBackgroundPermission(context),
    )

    /**
     * Stores the cadence somebody picked.
     *
     * Picking one is the answer to a withdrawn grant as well, so the note
     * about that goes with it.
     */
    fun chooseCadence(cadence: LocationCadence) {
        settings = settings.copy(locationCadence = cadence, backgroundLocationRevoked = false)
        saveSettings(settings)
        locationNote = null
    }

    /** Android has answered its last question about the background grant. */
    fun settleBackground() {
        requestLaunched = false
        val hours = pendingHours ?: return
        pendingHours = null
        backgroundLocationOk = AndroidLocationSource.hasBackgroundPermission(context)
        when (CadenceRequest.answer(backgroundMissing())) {
            CadenceAnswer.Granted -> chooseCadence(LocationCadence.Every(hours))
            CadenceAnswer.BackgroundRefused -> {
                // Back to the cadence that needs nothing more, rather than an
                // interval that would wake only to find it may not ask.
                chooseCadence(LocationCadence.OnOpen)
                locationNote = "Background location was not allowed, so Gloaming will " +
                    "update each time you open it."
            }
            CadenceAnswer.CoarseRefused -> coarseRefused()
        }
    }

    val backgroundPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { settleBackground() }

    // From Android 11 the background grant cannot be asked for alongside the
    // coarse one, so coarse goes first and background follows on its own.
    val coarseBeforeBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val next = backgroundMissing()
        if (pendingHours != null && next == listOf(AndroidLocationSource.BACKGROUND_PERMISSION)) {
            backgroundPermission.launch(next.toTypedArray())
        } else {
            settleBackground()
        }
    }

    // After Android has handed over any permission result, which it does
    // before resuming: a request still pending then lost its answer, and
    // would otherwise lock out every later pick. Before the reconciliation,
    // which reads the stored cadence this may change.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val resolution = CadenceRequest.stalePending(
                    pendingHours,
                    requestInFlight = requestLaunched,
                    backgroundGranted = Graph.locationGrants(context).background(),
                )
                if (resolution !is StalePending.None) pendingHours = null
                if (resolution is StalePending.Apply) chooseCadence(resolution.cadence)
                reconcileGrants()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Runs a check the user asked for, here and now.
     *
     * Deliberately not through `AlarmManager`. `setAndAllowWhileIdle` is
     * inexact and the system attaches a batching window - about three quarters
     * of whatever interval it is given, so the shortest wake this could ask for
     * still carries a minute or two, and a long one carries far more - so a
     * "check now" routed through the scheduler could sit unfired while the user
     * stares at an unchanged screen. A button somebody is watching gets run
     * directly.
     *
     * The window was recorded here as a flat "just under two minutes", which is
     * what it comes to at the short end this would have used. It does not stay
     * there: measured on a Pixel 8a it was 24 minutes on a 32 minute request.
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
            // Tonight and the calendar from scratch, even where nothing they
            // are keyed on moved: the calendar does not go stale on its own,
            // but events fall out of the far end of the horizon, and somebody
            // who pressed refresh meant all of it. In the same frame as the
            // new reading, so the two make one new key and one run.
            results.recalculate()
            checking = false
        }
    }

    // Keyed on the location, the comet settings and the day: the calendar is
    // deterministic and does not go stale within a day, so recomputing it on
    // any other change would be work done for no different answer. The day is
    // there because "today" is fixed when it is worked out; see CalendarKey.
    val calendarKey = CalendarKey.of(settings, day = localDay(shownAt), recalculation = results.recalculation)
    LaunchedEffect(calendarKey) {
        val user = settings
        val latitude = user.latitude
        val longitude = user.longitude
        val now = System.currentTimeMillis()
        // The holder outlives this activity, and so may a run; it is not to
        // keep the activity alive with it.
        val app = context.applicationContext
        results.ensureCalendar(
            calendarKey,
            events = {
                if (latitude == null || longitude == null) {
                    emptyList()
                } else {
                    runCatching {
                        // Comets come from a document rather than from the
                        // ephemeris, so they are worked out separately and
                        // merged in by date. A device that has never fetched
                        // one simply has none, which is also what most years
                        // look like.
                        val sky = SkyCalendar.events(latitude, longitude, now)
                        val comets = Comets.calendar(
                            Graph.comets(app).cached(),
                            latitude,
                            longitude,
                            user,
                            now,
                        )
                        (sky + comets).sortedBy { it.at }
                    }.getOrElse {
                        Log.w("Gloaming", "could not work out the calendar", it)
                        emptyList()
                    }
                }
            },
            // A night's moonlight is a sweep of the sky, and a year of them
            // takes as long again as the events.
            months = { events ->
                if (latitude == null || longitude == null) {
                    emptyList()
                } else {
                    runCatching {
                        MonthStrips.of(latitude, longitude, now, MonthStrips.YEAR_AHEAD, events)
                    }.getOrElse {
                        Log.w("Gloaming", "could not shade the months", it)
                        emptyList()
                    }
                }
            },
        )
    }

    // Recomputed whenever a verdict is given or taken away, or the switch is
    // moved, and once on open. Reading the file is off the main thread; the
    // arithmetic over it is trivial, and it is the same rule the pollers apply
    // so the screen and the alerts cannot disagree about what the settings are.
    // The record itself is kept as well as what it adds up to, because the
    // settings screen now shows the evidence beside the recommendation.
    LaunchedEffect(alertState.pendingVerdict, settings.automaticSettings, verdictsNonce) {
        val recorded = withContext(Dispatchers.IO) {
            runCatching { Graph.verdicts(context).read() }
                .onFailure { Log.w("Gloaming", "could not read the verdicts", it) }
                .getOrDefault(emptyList())
        }
        verdicts = recorded
        recommendations = runCatching { Calibration.of(recorded) }
            .onFailure { Log.w("Gloaming", "could not work the recommendations out", it) }
            .getOrNull()
    }

    // Whenever the year ahead is recomputed, so a comet whose dates have moved
    // takes its calendar entry with it, and so the name of the calendar being
    // written to is there to be shown. Cheap and skipped entirely when the
    // feature is off, which is the default.
    LaunchedEffect(results.calendar, settings.calendarSync) {
        // Not while the year is still being worked out. The planner no longer
        // deletes on an empty list, but syncing against one is work with
        // nothing to do and one fewer way for the two to disagree.
        if (settings.calendarSync != CalendarSync.Off && !results.calendarLoading) {
            syncCalendar(settings)
        }
    }

    /**
     * The forecast, fetched rather than merely read.
     *
     * The one thing on this tab that touches the network, and it has to. The
     * poller only pays for a cloud reading once a probability has already
     * cleared the alert threshold, which on a quiet night never happens - so
     * nothing was ever held, and the card showing the gap in the cloud never
     * appeared at all. That was the whole of the clear-spell feature not
     * working in practice, and it took a real phone on a real evening to see.
     *
     * Not a new cost. `OpenMeteoWeather` serves anything under an hour old from
     * the cache and will not refresh faster than Open-Meteo's terms allow, so
     * opening this tab twenty times an evening is one request. The position is
     * rounded to about a kilometre and carries no identifier, exactly as from
     * the poller.
     *
     * Skipped entirely with the cloud gate off: turning that off is also
     * turning off the one request that leaves the device, and making it anyway
     * from a screen would be a betrayal of that switch.
     *
     * Its own effect, and deliberately. Folded into the one below it held the
     * entire tab blank while the network was slow - every card, not just the
     * one waiting on it.
     */
    LaunchedEffect(settings.latitude, settings.longitude, settings.cloudGateEnabled) {
        val latitude = settings.latitude ?: return@LaunchedEffect
        val longitude = settings.longitude ?: return@LaunchedEffect
        if (!settings.cloudGateEnabled) return@LaunchedEffect

        val changed = withContext(Dispatchers.IO) {
            runCatching {
                val weather = Graph.weather(context)
                val held = weather.lastKnown(latitude, longitude) to weather.forecast(latitude, longitude)
                val endpoint = Graph.manifests(context).refresh()
                    .endpoints[SkyPoller.ENDPOINT_WEATHER] ?: return@runCatching false
                weather.cloudCover(endpoint, latitude, longitude)
                (weather.lastKnown(latitude, longitude) to weather.forecast(latitude, longitude)) != held
            }.onFailure { Log.w("Gloaming", "could not read the forecast", it) }.getOrDefault(false)
        }
        // Tonight is worked out again once a forecast has landed, which is
        // the only reason this effect and the one below are separate rather
        // than sequential. Only when it changed what is held: this runs on
        // every open and every rotation, and a reading under an hour old is
        // served from the cache, so bumping regardless worked the night out a
        // second time on every one of them for the same answer.
        if (changed) results.forecastChanged()
    }

    // Worked out from the elements already on the device, so it costs no
    // network, and from its own events over a short horizon
    // (TonightEvents), so it does not wait for the year. Worked out again when
    // a forecast changes what is held, when a check lands, and on the clock
    // tick, because "tonight" moves as the evening does.
    //
    // The outlook is decided here too, from the same documents and the same
    // held forecast, so the sentence at the top cannot disagree with the night
    // beneath it.
    val tonightKey = TonightKey.of(
        settings,
        forecastVersion = results.forecastVersion,
        lastPollAt = alertState.lastPollAt,
        staleSlot = shownAt / TONIGHT_STALE_MILLIS,
        recalculation = results.recalculation,
    )
    LaunchedEffect(tonightKey) {
        val user = settings
        // One snapshot of the alert state for the whole run. Read again later,
        // a check landing mid-run would stamp a sentence decided from the old
        // reading with the new check's time, and the widget would quote it
        // against the reading it contradicts.
        val state = alertState
        val app = context.applicationContext
        results.ensureTonight(tonightKey) work@{
            val latitude = user.latitude ?: return@work null
            val longitude = user.longitude ?: return@work null
            runCatching {
                // Both read the cache and stop. The fetch that fills that cache
                // is its own effect above, so a slow network cannot hold the
                // whole tab blank waiting for a cloud reading.
                val weather = Graph.weather(app)
                val satellites = Graph.satellites(app)
                val elements = satellites.cached()
                val forecast = weather.forecast(latitude, longitude)
                val now = System.currentTimeMillis()
                val comets = Graph.comets(app).cached()
                val list = Tonight.of(
                    satellites = elements,
                    comets = comets,
                    calendar = TonightEvents.of(latitude, longitude, now, comets, user),
                    settings = user,
                    nowMillis = now,
                    cloud = weather.lastKnown(latitude, longitude),
                    hourly = forecast,
                )
                val probability = state.lastProbability
                val report = runCatching {
                    Outlooks.of(
                        list,
                        OutlookInput(
                            settings = user,
                            nowMillis = now,
                            hourly = forecast,
                            auroraProbability = probability,
                            auroraAgeMillis = state.lastPollAt
                                ?.takeIf { probability != null }
                                ?.let { now - it },
                            // How long ago the elements were published, which
                            // is what SGP4's accuracy runs on. The cache's own
                            // age stands in only where the publish time cannot
                            // be read; with no document there is no age at all.
                            elementsAgeMillis = elements?.let { document ->
                                document.updatedMillis()?.let { now - it }
                                    ?: satellites.cachedAgeMillis()
                            },
                            sunAltitude = { Twilight.sunAltitude(latitude, longitude, it) },
                            moonAltitude = { Twilight.moonAltitude(latitude, longitude, it) },
                            planetAltitude = { body, t ->
                                Planets.altitude(body, latitude, longitude, t)
                            },
                        ),
                    )
                }.onFailure { Log.w("Gloaming", "could not work out the outlook", it) }.getOrNull()
                // Laid out from the same list and outlook, so a row's weight
                // is the outlook's own verdict on it.
                val night = report?.let {
                    runCatching { NightTimeline.of(list, it, forecast, user, now) }
                        .onFailure { e -> Log.w("Gloaming", "could not lay out the night", e) }
                        .getOrNull()
                }
                // The sweep above cannot be cancelled, so a run replaced while
                // it was in it gets this far anyway. Its night is dropped
                // unseen, and its outlook must not reach the widget either.
                if (!currentCoroutineContext().isActive) return@work null
                // Left for the widget, which cannot work it out itself, against
                // the check whose reading it was decided from (WidgetContent.update).
                // With no outlook (no location) nothing is written, and the widget
                // asks for a location before it reads the store anyway.
                val store = Graph.outlookStore(app)
                when (
                    val update = WidgetContent.update(
                        before = store.read(),
                        outlook = report?.outlook,
                        sentence = report?.let(OutlookWords::headline),
                        nightEndMillis = night?.endMillis,
                        settings = user,
                        state = state,
                        now = now,
                    )
                ) {
                    HeldUpdate.Keep -> Unit
                    HeldUpdate.Clear -> {
                        store.clear()
                        AuroraWidget.refresh(app)
                    }
                    is HeldUpdate.Write -> {
                        store.write(update.held)
                        AuroraWidget.refresh(app)
                    }
                }
                WorkedNight(list, elements, report, forecast, night)
            }.onFailure { Log.w("Gloaming", "could not work out tonight", it) }.getOrNull()
        }
    }

    GloamingTheme(nightVision = settings.nightVision) {
        DimScreen(settings.nightVision)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            GloamingRoot(
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
                    downloads = downloads,
                    downloading = downloading,
                    elementsFailure = elementsFailure,
                    darkness = darkness,
                    powerSaveMode = saverOn,
                    watching = watching,
                    watchingUntil = watchingUntil,
                    logSizeBytes = logSize,
                    calendar = results.calendar,
                    months = results.months,
                    calendarLoading = results.calendarLoading,
                    tonight = results.tonight,
                    elements = results.elements,
                    outlook = results.outlook,
                    hourly = results.hourly,
                    timeline = results.timeline,
                    tonightUpdating = results.tonightUpdating,
                    detectingLocation = detectingLocation,
                    locationNote = locationNote,
                    backgroundLocationAllowed = backgroundLocationOk,
                    calendarName = calendarName,
                    calendarNote = calendarNote,
                    pendingVerdict = Conditions.decode(alertState.pendingVerdict),
                    recommendations = recommendations,
                    verdicts = verdicts,
                ),
                actions = UiActions(
                    onLatitudeChange = { latitudeInput = it },
                    onLongitudeChange = { longitudeInput = it },
                    onThresholdChange = {
                        settings = settings.copy(threshold = it)
                        saveSettings(settings)
                    },
                    onAlertsEnabledChange = {
                        settings = settings.copy(alertsEnabled = it)
                        saveSettings(settings)
                        if (it) PollAlarm.schedule(context, settings.pollIntervalMinutes) else PollAlarm.cancel(context)
                    },
                    onBeforeUnlockChange = {
                        settings = settings.copy(alertsBeforeUnlock = it)
                        saveSettings(settings)
                    },
                    // Coarsening again clears the cloud reading taken at the finer
                    // position; BootAware.saveSettings owns that.
                    onPreciseBeforeUnlockChange = {
                        settings = settings.copy(preciseLocationBeforeUnlock = it)
                        saveSettings(settings)
                    },
                    onCloudGateChange = {
                        settings = settings.copy(cloudGateEnabled = it)
                        saveSettings(settings)
                    },
                    onCloudToleranceChange = {
                        settings = settings.copy(cloudTolerance = it)
                        saveSettings(settings)
                    },
                    onQuietHoursEnabledChange = { enabled ->
                        settings = settings.copy(
                            quietHours = if (enabled) quietHoursFrom(quietFromInput, quietUntilInput) else null,
                        )
                        saveSettings(settings)
                    },
                    // Applied as typed, and only when both fields parse. A half-typed
                    // "2" must not become quiet hours starting at two in the morning.
                    onQuietFromChange = { typed ->
                        quietFromInput = typed
                        quietHoursFrom(typed, quietUntilInput)?.let {
                            settings = settings.copy(quietHours = it)
                            saveSettings(settings)
                        }
                    },
                    onQuietUntilChange = { typed ->
                        quietUntilInput = typed
                        quietHoursFrom(quietFromInput, typed)?.let {
                            settings = settings.copy(quietHours = it)
                            saveSettings(settings)
                        }
                    },
                    onQuietHoursOverrideChange = {
                        settings = settings.copy(quietHoursOverride = it)
                        saveSettings(settings)
                    },
                    onNightVisionChange = {
                        settings = settings.copy(nightVision = it)
                        saveSettings(settings)
                    },
                    onShowEveryConstellationChange = {
                        settings = settings.copy(showEveryConstellation = it)
                        saveSettings(settings)
                    },
                    onLogRetentionChange = {
                        settings = settings.copy(logRetentionDays = it)
                        saveSettings(settings)
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
                        saveSettings(settings)
                    },
                    onIssIntervalChange = {
                        settings = settings.copy(issPassAlertIntervalMinutes = it)
                        saveSettings(settings)
                    },
                    onBrightIntervalChange = {
                        settings = settings.copy(brightPassAlertIntervalMinutes = it)
                        saveSettings(settings)
                    },
                    onTrainIntervalChange = {
                        settings = settings.copy(trainPassAlertIntervalMinutes = it)
                        saveSettings(settings)
                    },
                    onPassAlertsChange = {
                        settings = settings.copy(passAlertsEnabled = it)
                        saveSettings(settings)
                    },
                    // Turning it off drops whatever pass is queued. There is one
                    // slot and it carries no record of how it was filled, so this
                    // also drops a pass followed by hand - which is the right way
                    // round to be wrong: somebody who has just said they do not
                    // want this should not have a tracker start anyway.
                    onFollowStationChange = {
                        settings = settings.copy(followStationPassesAutomatically = it)
                        saveSettings(settings)
                        if (!it) PassAlarm.cancel(context)
                    },
                    // Optimistic, then corrected. The service reports itself
                    // running from its own thread a moment later, and a switch that
                    // did not move until then would read as ignoring the tap.
                    onWatchingChange = { wanted ->
                        if (wanted) StormService.watchNow(context) else StormService.stop(context)
                        watching = wanted
                        // The service fixes its own end once it has started; until
                        // then the same sum from the same inputs, so the button
                        // says when straight away.
                        watchingUntil = if (wanted) {
                            val now = System.currentTimeMillis()
                            StormService.endsAt ?: (
                                now + settings.watchDuration.capMillis(
                                    now,
                                    (darkness as? Darkness.Now)?.endsAt,
                                )
                            )
                        } else {
                            null
                        }
                    },
                    onPassLeadChange = {
                        settings = settings.copy(passAlertLeadMinutes = it)
                        saveSettings(settings)
                    },
                    onMinimumElevationChange = {
                        settings = settings.copy(minimumPassElevationDegrees = it)
                        saveSettings(settings)
                    },
                    onLiveLeadChange = {
                        settings = settings.copy(liveFollowLeadMinutes = it)
                        saveSettings(settings)
                    },
                    onEventAlertsChange = {
                        settings = settings.copy(eventAlertsEnabled = it)
                        saveSettings(settings)
                    },
                    onEventHeadsUpChange = {
                        settings = settings.copy(eventHeadsUpDays = it)
                        saveSettings(settings)
                    },
                    onEventLeadChange = {
                        settings = settings.copy(eventAlertLeadMinutes = it)
                        saveSettings(settings)
                    },
                    onEclipseFollowMaxChange = {
                        settings = settings.copy(eclipseFollowMaxMinutes = it)
                        saveSettings(settings)
                    },
                    onNotifyEveryEventChange = {
                        settings = settings.copy(notifyAboutEveryEvent = it)
                        saveSettings(settings)
                    },
                    // The four comet controls change which comets belong in the
                    // calendar and tonight, and how they are described;
                    // CalendarKey and TonightKey each hold all four, so saving
                    // them is enough to work both out again.
                    onCometMagnitudeChange = {
                        settings = settings.copy(cometMagnitudeThreshold = it)
                        saveSettings(settings)
                    },
                    onCometAltitudeChange = {
                        settings = settings.copy(minimumCometAltitudeDegrees = it)
                        saveSettings(settings)
                    },
                    onCometStaleChange = {
                        settings = settings.copy(cometObservationStaleDays = it)
                        saveSettings(settings)
                    },
                    onCometMoonChange = {
                        settings = settings.copy(cometMoonTolerance = it)
                        saveSettings(settings)
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
                        saveSettings(settings)

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
                        saveSettings(settings)
                    },
                    onAutomaticSettingsChange = {
                        settings = settings.copy(automaticSettings = it)
                        saveSettings(settings)
                    },
                    onClearVerdicts = {
                        Graph.verdicts(context).clear()
                        verdictsNonce++
                    },
                    onVerdict = { answer ->
                        val conditions = Conditions.decode(alertState.pendingVerdict)
                        if (conditions != null) {
                            val store = Graph.alertState(context)
                            Graph.verdicts(context)
                                .append(Verdict(System.currentTimeMillis(), answer, conditions))
                            // Cleared here as well as in the receiver, so the card
                            // and the notification cannot both collect an answer
                            // about the same night.
                            alertState = store.read().copy(pendingVerdict = null)
                            store.write(alertState)
                            scope.launch {
                                snackbar.showSnackbar(
                                    "Noted. Gloaming uses this to work out where your alert " +
                                        "settings should sit, and it stays on this phone.",
                                )
                            }
                        }
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
                            saveSettings(settings)
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
                            saveSettings(settings)
                            calendarPermission.launch(missing.toTypedArray())
                        } else {
                            // Written before the sync runs, so that switching to
                            // Off is what the syncer reads and takes back.
                            settings = settings.copy(calendarSync = destination)
                            saveSettings(settings)
                            calendarNote = null
                            if (destination == CalendarSync.Off) calendarName = null
                            syncCalendar(settings)
                        }
                    },
                    onLocationCadenceChange = { cadence ->
                        // Asked at the moment an interval is picked, after the card
                        // has explained Android's page in a dialog.
                        val pick = CadenceRequest.onPick(
                            cadence,
                            inFlight = pendingHours != null,
                            missing = backgroundMissing(),
                        )
                        when (pick) {
                            CadencePick.Ignore -> Unit
                            CadencePick.Store -> chooseCadence(cadence)
                            is CadencePick.AskCoarseFirst -> {
                                pendingHours = (cadence as LocationCadence.Every).hours
                                requestLaunched = true
                                coarseBeforeBackground.launch(pick.permissions.toTypedArray())
                            }
                            is CadencePick.Ask -> {
                                pendingHours = (cadence as LocationCadence.Every).hours
                                requestLaunched = true
                                backgroundPermission.launch(pick.permissions.toTypedArray())
                            }
                        }
                    },
                    onDetectLocation = { detectLocation() },
                    onWatchDurationChange = {
                        settings = settings.copy(watchDuration = it)
                        saveSettings(settings)
                    },
                    onSave = {
                        val latitude = latitudeInput.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
                        val longitude = longitudeInput.trim().toDoubleOrNull()?.takeIf { it in -360.0..360.0 }
                        settings = settings.copy(latitude = latitude, longitude = longitude)
                        saveSettings(settings)
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
                    onSetupDone = {
                        settings = settings.copy(setupDone = true)
                        saveSettings(settings)
                    },
                    onCompassOffered = {
                        settings = settings.copy(compassOfferedAt = it)
                        saveSettings(settings)
                    },
                    onCompassChecked = {
                        settings = settings.copy(compassCheckedAt = it)
                        saveSettings(settings)
                    },
                    onCompassOffsetChange = {
                        settings = settings.copy(compassOffsetDegrees = it)
                        saveSettings(settings)
                    },
                    onNightVisionWhenFindingChange = {
                        settings = settings.copy(nightVisionWhenFinding = it)
                        saveSettings(settings)
                    },
                    onFindAimChange = {
                        settings = settings.copy(findAim = it)
                        saveSettings(settings)
                    },
                ),
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                // A first look at the sky once the questions are answered, so
                // Tonight is not empty the first time it is seen.
                onSetupFinished = { checkNow() },
                compass = compass,
                initialFind = initialFind,
                onInitialFindShown = onInitialFindShown,
            )
        }
    }
}
