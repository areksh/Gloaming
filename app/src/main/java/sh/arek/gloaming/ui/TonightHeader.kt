package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.arek.gloaming.ui.theme.LocalPalette

/** The line that says how fresh things are, and opens the checks sheet. */
const val STATUS_LINE_TAG = "status-line"

/** A banner standing in for the status line while something must be allowed. */
const val ALERT_BANNER_TAG = "alert-banner"

/** The opening sentence. */
const val OUTLOOK_HEADLINE_TAG = "outlook-headline"

/**
 * The head of Tonight: how fresh things are, the date, and the outlook.
 *
 * The status line is one quiet line rather than the card it replaced: when
 * the app last looked and why it may not have looked since, with everything
 * operational a tap away in the checks sheet. It only raises its voice when
 * something is wrong. When background checks or notifications are not
 * allowed, a banner goes above it, because then the app cannot do the one
 * thing it is for. The line stays under the banner: a phone that cannot alert
 * is exactly the one whose downloads and last error somebody wants to see.
 *
 * Then the outlook: one sentence, decided by [sh.arek.gloaming.domain.Outlooks]
 * and worded by [OutlookWords], with nothing in its place until it has been
 * worked out rather than a guess.
 */
@Composable
fun TonightHeader(state: MainUiState, actions: UiActions, onOpenChecks: () -> Unit) {
    if (!state.batteryExempt) {
        AlertBanner(
            title = "Gloaming cannot alert you overnight yet",
            body = "Android holds background checks until the phone next wakes, which " +
                "overnight means hours. Allowing them is required, not optional.",
            action = "Allow background checks",
            onAction = actions.onRequestBatteryExemption,
        )
    }
    if (!state.notificationsAllowed) {
        AlertBanner(
            title = "Notifications are off",
            body = "Without notification permission the app can watch the sky but " +
                "cannot tell you about it.",
            action = "Allow notifications",
            onAction = actions.onRequestNotifications,
        )
    }
    StatusLine(state, onOpenChecks)

    OutlookLines(state)
}

/**
 * When the app last looked, why it may not have looked since, and - unasked -
 * anything that is wrong. The whole line is the button.
 */
@Composable
private fun StatusLine(state: MainUiState, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(STATUS_LINE_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                describeStatus(state.alertState.lastPollAt, state.nowMillis, state.darkness),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
            describeTrouble(state.downloads, state.alertState.lastError)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.alarm,
                )
            }
        }
        Chevron()
    }
}

/** The ">" at the end of the status line, drawn rather than typed. */
@Composable
private fun Chevron() {
    val colour = LocalPalette.current.muted
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val w = size.width
        drawLine(colour, Offset(w * 0.375f, w * 0.1875f), Offset(w * 0.6875f, w * 0.5f), stroke, StrokeCap.Round)
        drawLine(colour, Offset(w * 0.6875f, w * 0.5f), Offset(w * 0.375f, w * 0.8125f), stroke, StrokeCap.Round)
    }
}

/**
 * Something that must be allowed before the app can work, in the alarm
 * colour and the full width of the screen. Shape and weight carry it as much
 * as colour does, because night vision has only one colour to give.
 */
@Composable
private fun AlertBanner(title: String, body: String, action: String, onAction: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.alarm, RoundedCornerShape(12.dp))
            .padding(16.dp)
            // Read as one item, title and body together; the button stays its own.
            .semantics(mergeDescendants = true) {}
            .testTag(ALERT_BANNER_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = palette.onAccent,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.onAccent)
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.onAccent,
                contentColor = palette.alarm,
            ),
        ) { Text(action, fontWeight = FontWeight.Bold) }
    }
}

/**
 * The date, the opening sentence and the line under it.
 *
 * Without a location the sentence slot asks for one, which is the only true
 * thing it could say. With one but no outlook yet it is left empty: the
 * outlook is worked out off the main thread and lands a moment later.
 */
@Composable
private fun OutlookLines(state: MainUiState) {
    val palette = LocalPalette.current
    val report = state.outlook
    val headline = when {
        !state.settings.hasLocation -> "Set your location to begin"
        report == null -> null
        else -> OutlookWords.headline(report)
    }
    val follow = report?.takeIf { state.settings.hasLocation }
        ?.let { OutlookWords.follow(it, state.nowMillis) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            describeDate(state.nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
        headline?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = palette.text,
                modifier = Modifier.testTag(OUTLOOK_HEADLINE_TAG),
            )
        }
        follow?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
        }
    }
}
