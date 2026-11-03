package sh.arek.gloaming.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LinearProgressIndicator
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
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.ui.theme.LocalPalette

/** The line that says how fresh things are, and opens the checks sheet. */
const val STATUS_LINE_TAG = "status-line"

/** A banner standing in for the status line while something must be allowed. */
const val ALERT_BANNER_TAG = "alert-banner"

/** The opening sentence. */
const val OUTLOOK_HEADLINE_TAG = "outlook-headline"

/** The progress bar under the status line while tonight is being worked out. */
const val TONIGHT_UPDATING_TAG = "tonight-updating"

/** What the status line says under it while tonight is being worked out. */
const val TONIGHT_UPDATING = "Updating..."

/**
 * What stands in for the night, here and on Find, while there is none yet to
 * show because it is still being worked out.
 */
const val WORKING_OUT_TONIGHT = "Working out tonight..."

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
 * While tonight is being worked out, a progress bar and "Updating..." go
 * under the status line - the bar alone while there is no night yet, since
 * the body then says "Working out tonight..." itself.
 *
 * Then the outlook: one sentence, decided by [sh.arek.gloaming.domain.Outlooks]
 * and worded by [OutlookWords], with nothing in its place until it has been
 * worked out rather than a guess. Once the night is under way and something
 * picked is still to come, what is next leads instead ("ISS in 6 min") and
 * the sentence follows it.
 */
@Composable
fun TonightHeader(
    state: MainUiState,
    actions: UiActions,
    onOpenChecks: () -> Unit,
    timeline: Timeline? = null,
    onFindIt: ((FindTarget) -> Unit)? = null,
) {
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
    // With no night yet the body says "Working out tonight..." in its place,
    // and the same thing said twice, one line apart, reads as two things.
    if (state.tonightUpdating) UpdatingLine(words = !workingOutTonight(state))

    OutlookLines(state, timeline, onFindIt)
}

/**
 * Whether the body stands "Working out tonight..." in for a night it does not
 * have yet: one is being worked out for a position, and none is held.
 */
fun workingOutTonight(state: MainUiState): Boolean =
    state.settings.hasLocation && state.tonight == null && state.tonightUpdating

/**
 * That tonight is being worked out, which takes several seconds on a phone.
 * Without it a screen that had not changed yet read as one that never would.
 * [words] is false where the body already says so.
 */
@Composable
private fun UpdatingLine(words: Boolean) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag(TONIGHT_UPDATING_TAG))
        if (words) Text(TONIGHT_UPDATING, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
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
 * The date, the opening sentence, the line under it, and where the aurora
 * reading came from when that is AuroraWatch.
 *
 * Without a location the sentence slot asks for one, which is the only true
 * thing it could say. With one but no outlook yet it is left empty: the
 * outlook is worked out off the main thread, and until it lands the line
 * under the status line and the night beneath say so.
 *
 * Before the night, a countdown to the trip goes under the sentence; on an
 * overcast night, a card saying when it next clears. Once the night is under
 * way and a picked row is still to come, that row leads - with Find it where
 * it can be pointed at - and the sentence moves under it, in place of the
 * line that would have followed.
 */
@Composable
private fun OutlookLines(state: MainUiState, timeline: Timeline?, onFindIt: ((FindTarget) -> Unit)?) {
    val palette = LocalPalette.current
    val report = state.outlook
    val headline = when {
        !state.settings.hasLocation -> "Set your location to begin"
        report == null -> null
        else -> OutlookWords.headline(report)
    }
    val follow = report?.takeIf { state.settings.hasLocation }
        ?.let { OutlookWords.follow(it, state.nowMillis) }
    val now = timeline?.nowMillis
    val next = timeline?.let { TonightSections.nextUp(TonightSections.worth(it), now) }
    val countdown = timeline?.takeIf { next == null }?.let { TonightSections.countdown(it, state.nowMillis) }
    val nextClear = (report?.outlook as? Outlook.Overcast)
        ?.takeIf { state.settings.hasLocation }
        ?.let { OutlookWords.nextClear(it, state.nowMillis) }
    val headlineStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            describeDate(state.nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
        if (next != null && now != null) {
            Text(
                TonightSections.nextUpWords(next, now),
                style = headlineStyle.copy(fontWeight = FontWeight.Bold),
                color = palette.text,
                modifier = Modifier.testTag(NEXT_UP_TAG),
            )
            val target = findTarget(next.subject)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (target != null && onFindIt != null) FindItButton(NEXT_UP_FIND_TAG) { onFindIt(target) }
                next.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
            }
            headline?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                    modifier = Modifier.testTag(OUTLOOK_HEADLINE_TAG),
                )
            }
        } else {
            headline?.let {
                Text(
                    it,
                    style = headlineStyle,
                    color = palette.text,
                    modifier = Modifier.testTag(OUTLOOK_HEADLINE_TAG),
                )
            }
            follow?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            countdown?.let { CountdownChip(it) }
            nextClear?.let { NextClearCard(it) }
        }
        // Beside the outlook that reads it. AuroraWatch's conditions tie the
        // credit to their data, and somebody deciding whether to trust 55%
        // should see where it came from without scrolling; the attribution
        // they specify is carried in full under Credits and licences.
        if (state.settings.hasLocation && state.alertState.lastFromAuroraWatch) {
            Text(
                "This reading came from AuroraWatch UK.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

/** How long until the trip out, as a filled pill: weight and fill, not colour alone. */
@Composable
private fun CountdownChip(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = palette.onAccent,
        modifier = Modifier
            .background(palette.accent, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(COUNTDOWN_TAG),
    )
}

/** When an overcast night next clears, as a card of its own rather than a clause in a sentence. */
@Composable
private fun NextClearCard(text: String) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.raised, RoundedCornerShape(16.dp))
            .border(1.dp, palette.accent, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(NEXT_CLEAR_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "NEXT CLEAR SPELL",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = palette.accent,
        )
        Text(text, style = MaterialTheme.typography.titleMedium, color = palette.text)
    }
}
