package sh.arek.gloaming

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import sh.arek.gloaming.ui.MainScreen
import sh.arek.gloaming.ui.MainUiState
import sh.arek.gloaming.ui.theme.GloamingTheme
import sh.arek.gloaming.data.BootAware
import sh.arek.gloaming.work.PollAlarm

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Make sure a schedule exists, in case this is a first run or the chain
        // was ever lost. Enqueuing is idempotent under a unique work name.
        PollAlarm.schedule(this, Graph.settings(this).read().pollIntervalMinutes)

        setContent {
            GloamingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    GloamingApp(Modifier.padding(padding))
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GloamingApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsStore = remember { Graph.settings(context) }
    val stateStore = remember { Graph.alertState(context) }

    var settings by remember { mutableStateOf(settingsStore.read()) }
    var alertState by remember { mutableStateOf(stateStore.read()) }
    var latitudeInput by remember { mutableStateOf(settings.latitude?.toString().orEmpty()) }
    var longitudeInput by remember { mutableStateOf(settings.longitude?.toString().orEmpty()) }

    fun batteryExempt(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Captured when the screen is shown, so the displayed age is the age as of
    // the last time the user actually looked.
    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryOk by remember { mutableStateOf(batteryExempt()) }
    var notificationsOk by remember { mutableStateOf(notificationsAllowed()) }

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
                shownAt = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsOk = notificationsAllowed() }

    MainScreen(
        state = MainUiState(
            settings = settings,
            alertState = alertState,
            latitudeInput = latitudeInput,
            longitudeInput = longitudeInput,
            batteryExempt = batteryOk,
            notificationsAllowed = notificationsOk,
            nowMillis = shownAt,
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
        onSave = {
            val latitude = latitudeInput.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
            val longitude = longitudeInput.trim().toDoubleOrNull()?.takeIf { it in -360.0..360.0 }
            settings = settings.copy(latitude = latitude, longitude = longitude)
            BootAware.saveSettings(context, settings)
            latitudeInput = latitude?.toString().orEmpty()
            longitudeInput = longitude?.toString().orEmpty()
            PollAlarm.scheduleNow(context)
        },
        onPollNow = { PollAlarm.scheduleNow(context) },
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
        modifier = modifier,
    )
}
