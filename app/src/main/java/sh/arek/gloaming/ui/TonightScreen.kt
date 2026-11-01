package sh.arek.gloaming.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.Answer
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.ui.theme.LocalPalette

/** Tonight's scrolling column, which is what pulling down to check acts on. */
const val TONIGHT_SCROLL_TAG = "tonight-scroll"

/**
 * Tonight as a whole: the night, scrolling, with the watch button pinned
 * under it.
 *
 * Pinned rather than at the end of the scroll, because it is the one thing on
 * the screen somebody acts on in a hurry - when the sky is doing something -
 * and that is the moment nobody should have to scroll past the night to reach
 * it. Pulling the night down runs a check, the same as Check now in the
 * checks sheet.
 *
 * @param onFindIt opens Find aimed at a row's subject; with none, rows offer
 *   no Find it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonightScreen(
    state: MainUiState,
    actions: UiActions = UiActions(),
    scroll: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    onFindIt: ((FindTarget) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(modifier) {
        PullToRefreshBox(
            isRefreshing = state.checking,
            // Not while one is in flight, as Check now is disabled then too.
            onRefresh = { if (!state.checking) actions.onPollNow() },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TONIGHT_SCROLL_TAG)
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TonightContent(state, actions, onFindIt)
            }
        }
        HorizontalDivider(color = palette.line)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            WatchButton(state, actions)
        }
    }
}

/**
 * What the sky is doing tonight, under the line that says how fresh it is.
 *
 * Emitted as loose children rather than wrapped in a column of its own, so the
 * caller owns the scrolling. The watch button is not among them: it is pinned
 * by [TonightScreen] rather than scrolled.
 *
 * The night itself is the page: the timeline from sunset to sunrise, then what
 * else is up and what was left out and why. Each row opens a sheet saying it
 * in full; the open row is remembered by its tag, so a rotation keeps it open.
 */
@Composable
fun TonightContent(
    state: MainUiState,
    actions: UiActions = UiActions(),
    onFindIt: ((FindTarget) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    var showChecks by rememberSaveable { mutableStateOf(false) }
    var openRow by rememberSaveable { mutableStateOf<String?>(null) }

    WorthItCard(state, actions.onVerdict)

    TonightHeader(state, actions, onOpenChecks = { showChecks = true })

    val located = state.settings.hasLocation
    // Never an empty night: the first one on an open takes several seconds.
    if (located && state.tonight == null && state.tonightUpdating) {
        Text(WORKING_OUT_TONIGHT, style = MaterialTheme.typography.bodyLarge, color = palette.muted)
    }
    val timeline = state.timeline?.takeIf { located }?.let { drawn ->
        // The night is laid out every quarter of an hour; the line at now
        // follows the screen's own minute.
        drawn.copy(nowMillis = state.nowMillis.takeIf { it in drawn.startMillis..drawn.endMillis })
    }
    if (timeline != null) {
        NightTimelineView(timeline, palette, onRow = { openRow = timelineRowTag(it) })
        // CC BY 4.0 asks for the credit beside the data it covers, and the
        // hatching is that data.
        if (timeline.cloud.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    append("Weather data by ")
                    withLink(LinkAnnotation.Url("https://open-meteo.com/")) {
                        append("Open-Meteo.com")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }

    state.tonight?.takeIf { located }?.let { tonight ->
        // The polar night and the midnight sun have no sunset to draw from,
        // and how dark it gets is then the first thing worth saying.
        if (timeline == null) DarknessLines(tonight)
        EntryList(
            "Also up tonight",
            TonightLists.alsoUp(tonight, timeline, state.outlook, state.nowMillis),
            ALSO_UP_TAG,
        )
        EntryList(
            "Not worth it tonight",
            TonightLists.notWorth(
                tonight,
                state.outlook,
                everChecked = state.alertState.lastPollAt != null,
                elementsFailure = state.elementsFailure,
                nowMillis = state.nowMillis,
            ),
            NOT_WORTH_TAG,
        )
    }

    timeline?.rows?.firstOrNull { timelineRowTag(it) == openRow }?.let { row ->
        // Find it only where there is somewhere to go: a row about something
        // that can be pointed at, and a caller with a Find to open.
        val target = findTarget(row.subject)
        val findIt: (@Composable () -> Unit)? = if (target != null && onFindIt != null) {
            {
                FindItButton {
                    openRow = null
                    onFindIt(target)
                }
            }
        } else {
            null
        }
        RowSheet(row, state, actions, onDismiss = { openRow = null }, findIt = findIt)
    }
    if (showChecks) ChecksSheet(state, actions, onDismiss = { showChecks = false })
}

/**
 * "Was it worth it?", for an alert that was never answered.
 *
 * The second half of the notification's two buttons, and the one that catches
 * the nights that actually matter: somebody woken at two in the morning swipes
 * the alert away and forms an opinion about it at nine, by which time the
 * buttons are gone. This is where that opinion lands.
 *
 * It carries the third answer the notification deliberately does not.
 * "Did not look" is the honest answer for a night somebody slept through, and
 * without it the alerts nobody answered would be counted as failures by any
 * naive reading of the record - but a third button on a notification is a
 * notification that gets dismissed unread. On a screen somebody is already
 * looking at, it costs nothing.
 *
 * At the top of the screen, above everything about tonight, because it is a
 * question rather than a statement and it is stale in a day. It appears only
 * once the alert is old enough to have an answer, and disappears the moment
 * there is one.
 */
@Composable
private fun WorthItCard(state: MainUiState, onVerdict: (Answer) -> Unit) {
    val pending = state.pendingVerdict ?: return
    if (state.nowMillis - pending.atMillis < WORTH_IT_DELAY_MILLIS) return
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.raised, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Was it worth it?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gloaming alerted you at ${clock(pending.atMillis)} about " +
                "${pending.subject ?: "something in the sky"}. Saying whether it was worth " +
                "going out for is the one thing that lets it work out where your alert " +
                "settings should actually sit - and nothing about this leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (answer in Answer.entries) {
                OutlinedButton(
                    onClick = { onVerdict(answer) },
                    modifier = Modifier.testTag(verdictTag(answer)),
                ) { Text(answer.label) }
            }
        }
    }
}

/**
 * How long an alert has to have been out before it is worth asking about.
 *
 * An hour. Long enough for somebody to have gone out and come back, and short
 * enough that the question still arrives on the night it is about.
 */
private const val WORTH_IT_DELAY_MILLIS: Long = 60 * 60 * 1000L

/** How an answer is reached from a test. */
fun verdictTag(answer: Answer): String = "verdict:${answer.name}"

/**
 * A heading and its entries, or nothing at all: a heading over an empty list
 * reads as something having gone wrong.
 */
@Composable
private fun EntryList(title: String, entries: List<ListEntry>, tag: String) {
    if (entries.isEmpty()) return
    val palette = LocalPalette.current

    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        for (entry in entries) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                // In words and weight as well as the accent, for night vision.
                if (entry.happeningNow) {
                    Text(
                        HAPPENING_NOW,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent,
                    )
                }
                entry.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (entry.alarming) FontWeight.Bold else null,
                        color = if (entry.alarming) palette.alarm else palette.muted,
                    )
                }
                entry.note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
        }
    }
}
