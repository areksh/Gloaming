package sh.arek.gloaming.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import sh.arek.gloaming.BuildConfig
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.ui.theme.LocalPalette

/**
 * The questions Settings is grouped by, in the order they are asked.
 *
 * Grouped by what somebody is trying to find out rather than by which part of
 * the app a control belongs to, so "why did it not wake me?" and "stop it
 * waking me" each lead to one place.
 */
enum class SettingsGroup(val title: String) {
    WhereYouAre("Where you are"),
    WorthWakingFor("What is worth waking for"),
    LeaveYouAlone("When to leave you alone"),
    IsItWorking("Is it working"),
    FindingThings("Finding things"),
    LearningFromAnswers("Learning from your answers"),
    ScreenAndReference("Screen and reference"),
}

/**
 * One row of the index, and the page it opens.
 *
 * Declared in display order: the index lists the entries as they stand. The
 * version is not one - it is the line at the foot of the index.
 */
enum class SettingsPage(val group: SettingsGroup, val title: String) {
    Location(SettingsGroup.WhereYouAre, "Location"),
    BeforeFirstUnlock(SettingsGroup.WhereYouAre, "Before first unlock"),

    Aurora(SettingsGroup.WorthWakingFor, "Aurora"),
    SatellitePasses(SettingsGroup.WorthWakingFor, "Satellite passes"),
    DatedEvents(SettingsGroup.WorthWakingFor, "Dated events"),
    Comets(SettingsGroup.WorthWakingFor, "Comets"),
    Cloud(SettingsGroup.WorthWakingFor, "Cloud"),
    Constellations(SettingsGroup.WorthWakingFor, "Constellations"),

    QuietHours(SettingsGroup.LeaveYouAlone, "Quiet hours"),

    BackgroundChecks(SettingsGroup.IsItWorking, "Background checks"),
    Notifications(SettingsGroup.IsItWorking, "Notifications"),
    Downloads(SettingsGroup.IsItWorking, "Downloads"),
    Log(SettingsGroup.IsItWorking, "Log"),

    Compass(SettingsGroup.FindingThings, "Compass"),
    NightVisionWhenFinding(SettingsGroup.FindingThings, "Night vision when finding"),

    AutomaticSettings(SettingsGroup.LearningFromAnswers, "Automatic settings"),
    YourAnswers(SettingsGroup.LearningFromAnswers, "Your answers"),

    NightVision(SettingsGroup.ScreenAndReference, "Night vision"),
    Guide(SettingsGroup.ScreenAndReference, "Guide to the words"),
    CalendarSync(SettingsGroup.ScreenAndReference, "Calendar sync"),
    Credits(SettingsGroup.ScreenAndReference, "Credits and licences"),
}

/** How a test reaches a row of the index. */
fun settingsRowTag(page: SettingsPage): String = "settings:${page.name}"

/**
 * The current value of a page's main control, in a few words.
 *
 * Resolved from the same state as the page itself, so the row can never say
 * one thing and the page another.
 */
fun summary(page: SettingsPage, state: MainUiState): String {
    val settings = state.settings
    return when (page) {
        SettingsPage.Location -> when {
            settings.locationMode == LocationMode.Automatic && summaryAlarming(page, state) ->
                "Approximate, from the phone. Background updates off, revoked by Android"

            settings.locationMode == LocationMode.Automatic ->
                "Approximate, from the phone. ${describeCadenceSummary(settings.locationCadence)}"

            settings.hasLocation ->
                "Typed in: ${coordinates(settings.latitude!!, settings.longitude!!)}"

            else -> "Not set yet"
        }

        SettingsPage.BeforeFirstUnlock -> when {
            !settings.alertsBeforeUnlock -> "Off"
            settings.preciseLocationBeforeUnlock -> "On, with your exact location"
            else -> "On, rounded to the whole degree"
        }

        SettingsPage.Aurora ->
            if (settings.alertsEnabled) "${settings.threshold}% chance or more" else "Alerts off"

        SettingsPage.SatellitePasses ->
            if (settings.passAlertsEnabled) {
                "Ignoring passes below ${settings.minimumPassElevationDegrees} degrees"
            } else {
                "Off"
            }

        SettingsPage.DatedEvents -> when {
            !settings.eventAlertsEnabled -> "Off"
            settings.notifyAboutEveryEvent -> "Everything visible from here"
            else -> "Only the ones you star"
        }

        SettingsPage.Comets -> "Down to ${describeMagnitude(settings.cometMagnitudeThreshold)}"

        SettingsPage.Cloud ->
            if (settings.cloudGateEnabled) {
                "Held back above ${describePercent(settings.cloudTolerance)} cloud"
            } else {
                "Not checked"
            }

        SettingsPage.Constellations ->
            if (settings.showEveryConstellation) {
                "Every one above the horizon"
            } else {
                "The handful worth leading with"
            }

        SettingsPage.QuietHours -> settings.quietHours?.let {
            "${formatTimeOfDay(it.startMinute)} to ${formatTimeOfDay(it.endMinute)}"
        } ?: "Off"

        SettingsPage.BackgroundChecks ->
            if (state.batteryExempt) "Allowed" else "Not allowed. Overnight alerts will arrive hours late"

        SettingsPage.Notifications ->
            if (state.notificationsAllowed) "On" else "Off. Gloaming cannot tell you anything"

        SettingsPage.Downloads ->
            describeTrouble(state.downloads, state.alertState.lastError)
                ?: if (state.downloads.isEmpty()) "Nothing downloaded yet" else "Nothing wrong"

        SettingsPage.Log ->
            if (settings.logRetentionDays <= 0) {
                "Off"
            } else {
                "${describeLogHeld(state.logSizeBytes)}, kept for ${describeDays(settings.logRetentionDays)}"
            }

        SettingsPage.Compass -> {
            val degrees = abs(settings.compassOffsetDegrees).roundToInt()
            when {
                degrees > 0 ->
                    "Corrected by $degrees ${if (degrees == 1) "degree" else "degrees"} on the moon"
                settings.compassCheckedAt != null -> "Checked, no correction"
                else -> "Not checked yet"
            }
        }

        SettingsPage.NightVisionWhenFinding -> if (settings.nightVisionWhenFinding) "On" else "Off"

        SettingsPage.AutomaticSettings -> if (settings.automaticSettings) "On" else "Off"

        SettingsPage.YourAnswers -> when (val count = state.verdicts.size) {
            0 -> "None yet"
            1 -> "1 answer"
            else -> "$count answers"
        }

        SettingsPage.NightVision -> if (settings.nightVision) "On" else "Off"

        SettingsPage.Guide -> "Landmarks, and what the words mean"

        SettingsPage.CalendarSync -> settings.calendarSync.label

        SettingsPage.Credits -> "Data sources and licences"
    }
}

/**
 * Whether a row's summary reports something broken rather than a choice.
 *
 * The index draws these in the alarm colour and in bold, so the difference
 * survives night vision, where every colour is a red.
 */
fun summaryAlarming(page: SettingsPage, state: MainUiState): Boolean = when (page) {
    SettingsPage.Location ->
        state.settings.locationMode == LocationMode.Automatic &&
            state.settings.backgroundLocationRevoked

    SettingsPage.BackgroundChecks -> !state.batteryExempt
    SettingsPage.Notifications -> !state.notificationsAllowed
    SettingsPage.Downloads -> describeTrouble(state.downloads, state.alertState.lastError) != null
    else -> false
}

/** "Empty", "640 bytes" or "212 KB": how much of the log is on disk. */
private fun describeLogHeld(bytes: Long): String = when {
    bytes <= 0L -> "Empty"
    bytes < 1024L -> "$bytes bytes"
    else -> "${bytes / 1024} KB"
}

/**
 * The index: every group, each page a row showing its current value.
 *
 * Emitted as loose children like the rest of Settings, so the caller owns the
 * scrolling. The version closes it, outside any group because it is not a
 * setting and nobody looks for it until something has gone wrong.
 */
@Composable
fun SettingsIndex(
    state: MainUiState,
    onOpen: (SettingsPage) -> Unit,
) {
    for (group in SettingsGroup.entries) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleSmall,
                color = LocalPalette.current.muted,
                modifier = Modifier.semantics { heading() },
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = LocalPalette.current.raised),
            ) {
                val pages = SettingsPage.entries.filter { it.group == group }
                pages.forEachIndexed { index, page ->
                    if (index > 0) HorizontalDivider()
                    SettingsRow(page, state, onOpen)
                }
            }
        }
    }
    VersionFooter()
}

@Composable
private fun SettingsRow(page: SettingsPage, state: MainUiState, onOpen: (SettingsPage) -> Unit) {
    val palette = LocalPalette.current
    val alarming = summaryAlarming(page, state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { onOpen(page) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(settingsRowTag(page)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(page.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            summary(page, state),
            style = MaterialTheme.typography.bodySmall,
            // Colour and weight together: a red summary among muted ones is
            // lost in night vision, where everything is red.
            color = if (alarming) palette.alarm else palette.muted,
            fontWeight = if (alarming) FontWeight.Bold else null,
        )
    }
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
internal fun VersionFooter() {
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
