package sh.arek.gloaming.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.NoCompass
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.decode
import sh.arek.gloaming.domain.encode
import sh.arek.gloaming.ui.theme.LocalPalette

/**
 * The sections of the app, each one a place on the bottom bar.
 *
 * [tag] reaches the bar item from a test, because a label alone is ambiguous
 * with a heading of the same name on the screen above it.
 */
enum class Section(val label: String, val tag: String) {
    Tonight("Tonight", "nav-tonight"),
    Find("Find", "nav-find"),
    Ahead("Ahead", "nav-ahead"),
    Settings("Settings", "nav-settings"),
}

/** The way back from a Settings page to the index, reached by tag from a test. */
const val SETTINGS_BACK_TAG = "settings-back"

/** The heading of an open Settings page. */
const val SETTINGS_PAGE_TITLE_TAG = "settings-page-title"

/**
 * The sections over one activity, behind a bottom bar.
 *
 * No navigation library: a handful of sections need no back stack, no deep
 * links and no new dependency, and `rememberSaveable` is enough to survive a
 * rotation.
 *
 * Settings is an index of pages, and the page is the one place a back stack
 * is wanted: it is one nullable page, so there is nothing to trap or strand
 * anybody. System Back on a page returns to the index. Choosing a section on
 * the bar, Settings again included, lands on its index, so a page never
 * outlives a visit to it.
 *
 * Each section owns its own scroll, which is why [TonightContent] and
 * [SettingsPageContent] emit loose children rather than columns of their own.
 * [TonightScreen] brings its own column because it pins the watch button
 * under the scroll, and [FindScreen] because it can be themed apart from the
 * rest. Find it on a Tonight row switches to Find aimed at that row.
 *
 * @param compass what Find and the Compass settings page read; the phone's
 *   sensors in the app, a fake in a test, and none at all by default.
 * @param initialFind a target handed in by Find it on a notification, encoded,
 *   already read by `findTargetFrom` in `MainActivity`.
 *   A target this build can read opens Find aimed at it, wherever the app was;
 *   anything else is ignored and the app opens as it would have. Either way
 *   [onInitialFindShown] is called, so the caller can forget it and the same
 *   notification tapped again aims Find again.
 * @param findClock the time Find works from; a parameter so a test can hold
 *   it still, since what Find offers depends on what is up in the real sky.
 */
@Composable
fun GloamingNav(
    state: MainUiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    compass: CompassSource = NoCompass,
    initialFind: String? = null,
    onInitialFindShown: () -> Unit = {},
    findClock: () -> Long = { System.currentTimeMillis() },
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // The open Settings page by name: null is the index. A name rather than the
    // enum so a saved state from a build with different pages restores to the
    // index instead of failing.
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    val page = SettingsPage.entries.firstOrNull { it.name == openPage }
    val sections = Section.entries

    // One scroll position per section, remembered out here rather than inside
    // the branch that uses it, so moving away and back returns to where the
    // reader was.
    val tonightScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val aheadScroll = rememberLazyListState()
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    // What Find is aimed at, encoded, so Find it on a row can open it on that
    // row and a target picked on Find is still chosen after a visit elsewhere.
    var findTarget by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(initialFind) {
        if (initialFind != null) {
            FindTarget.decode(initialFind)?.let {
                findTarget = it.encode()
                selected = Section.Find.ordinal
                openPage = null
            }
            onInitialFindShown()
        }
    }

    // System Back leaves a page for the index rather than the app.
    BackHandler(enabled = page != null) { openPage = null }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            val palette = LocalPalette.current
            NavigationBar(containerColor = palette.raised, contentColor = palette.text) {
                sections.forEachIndexed { index, section ->
                    NavigationBarItem(
                        selected = selected == index,
                        // Choosing a section, Settings included, lands on its
                        // index: a page never outlives a visit to it.
                        onClick = {
                            selected = index
                            openPage = null
                        },
                        icon = { SectionIcon(section) },
                        label = { Text(section.label) },
                        modifier = Modifier.testTag(section.tag),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.onAccent,
                            selectedTextColor = palette.accent,
                            indicatorColor = palette.accent,
                            unselectedIconColor = palette.muted,
                            unselectedTextColor = palette.muted,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        val content = Modifier.padding(inner)
        when (sections[selected]) {
            // The calendar brings its own scrolling: it is a lazy list, and a
            // lazy list inside a scrolling column is measured with unbounded
            // height, which defeats the point of it and throws.
            Section.Ahead -> AheadScreen(
                state = state,
                actions = actions,
                starredOnly = starredOnly,
                onStarredOnlyChange = { starredOnly = it },
                modifier = content.fillMaxSize(),
                listState = aheadScroll,
            )

            // Tonight scrolls itself too, with the watch button pinned under
            // the scroll and pulling down to check.
            Section.Tonight -> TonightScreen(
                state = state,
                actions = actions,
                scroll = tonightScroll,
                modifier = content.fillMaxSize(),
                onFindIt = { target ->
                    findTarget = target.encode()
                    selected = Section.Find.ordinal
                },
            )

            // Find scrolls itself, and listens to the compass only while it
            // is the section shown.
            Section.Find -> FindScreen(
                state = state,
                actions = actions,
                source = compass,
                initial = findTarget?.let { FindTarget.decode(it) },
                modifier = content.fillMaxSize(),
                onTargetChange = { findTarget = it.encode() },
                clock = findClock,
            )

            Section.Settings -> if (page == null) {
                ScrollingSection(settingsScroll, content) {
                    SettingsIndex(state = state, onOpen = { openPage = it.name })
                }
            } else {
                // Its own scroll, made here so each page opens at the top.
                ScrollingSection(rememberScrollState(), content) {
                    TextButton(
                        onClick = { openPage = null },
                        modifier = Modifier.testTag(SETTINGS_BACK_TAG),
                    ) { Text("Back to Settings") }
                    Text(
                        page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .semantics { heading() }
                            .testTag(SETTINGS_PAGE_TITLE_TAG),
                    )
                    SettingsPageContent(page = page, state = state, actions = actions, compass = compass)
                }
            }
        }
    }
}

@Composable
private fun SectionIcon(section: Section) {
    androidx.compose.material3.Icon(
        imageVector = when (section) {
            Section.Tonight -> Icons.moon
            Section.Find -> Icons.compass
            Section.Ahead -> Icons.calendar
            Section.Settings -> Icons.sliders
        },
        contentDescription = null,
    )
}

/**
 * The bar icons, drawn here as 24dp strokes so no icon library is needed.
 * Stroked in black; the bar tints them.
 */
private object Icons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    val moon = icon("moon") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f)
        arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f)
        close()
    }

    val compass = icon("compass") {
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, true, 4f, 12f)
        arcTo(8f, 8f, 0f, true, true, 20f, 12f)
        close()
        moveTo(15f, 9f)
        lineTo(13.2f, 13.2f)
        lineTo(9f, 15f)
        lineTo(10.8f, 10.8f)
        close()
    }

    val calendar = icon("calendar") {
        moveTo(5f, 6f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        horizontalLineTo(5f)
        close()
        moveTo(5f, 10f)
        horizontalLineTo(19f)
        moveTo(8f, 3f)
        verticalLineTo(7f)
        moveTo(16f, 3f)
        verticalLineTo(7f)
    }

    val sliders = icon("sliders") {
        moveTo(4f, 7f)
        horizontalLineTo(20f)
        moveTo(4f, 17f)
        horizontalLineTo(20f)
        moveTo(9f, 4f)
        verticalLineTo(10f)
        moveTo(15f, 14f)
        verticalLineTo(20f)
    }
}

/**
 * A section that scrolls as one column, which Settings and the guide do.
 *
 * The padding and spacing live here so the sections look identical to each
 * other - the calendar applies the same figures as its own content padding,
 * and [TonightScreen] to its own column.
 */
@Composable
private fun ScrollingSection(
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
