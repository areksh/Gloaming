package sh.arek.gloaming.ui

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.AlertState
import sh.arek.gloaming.data.FindAim
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.magneticDeclination
import sh.arek.gloaming.domain.CompassAccuracy
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The Find screen, driven by a fake compass emitting chosen matrices.
 *
 * Never the phone's own sensors: what the screen says must follow from the
 * reading it was given, and a real compass would make every assertion depend
 * on which way the phone happens to be lying. Driven by hand-built state, so
 * nothing here touches the app's preference stores.
 *
 * **These need the device unlocked**, for the reason recorded in [ScreensTest].
 */
class FindScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val latitude = 55.95
    private val longitude = -3.19
    private val located = UserSettings(latitude = latitude, longitude = longitude)

    private var offeredAt: Long? = null
    private var checkedAt: Long? = null
    private var offset: Double? = null
    private var nightVisionWhenFinding: Boolean? = null
    private var aim: FindAim? = null

    private val actions = UiActions(
        onCompassOffered = { offeredAt = it },
        onCompassChecked = { checkedAt = it },
        onCompassOffsetChange = { offset = it },
        onNightVisionWhenFindingChange = { nightVisionWhenFinding = it },
        onFindAimChange = { aim = it },
    )

    private val station = SatellitePass(
        catalogue = 25544,
        name = "ISS (ZARYA)",
        channel = PassChannel.Iss,
        risesAt = now + hour,
        riseAzimuth = 225.0,
        peaksAt = now + hour + 300_000L,
        peakElevation = 68.0,
        peakAzimuth = 180.0,
        setsAt = now + hour + 600_000L,
        setAzimuth = 135.0,
        rangeAtPeakKm = 420.0,
        sunlitAtPeak = true,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    private fun night(passes: List<SatellitePass> = emptyList()) = TonightList(
        darkFrom = now,
        darkUntil = now + 8 * hour,
        passes = passes,
        bestPasses = passes,
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
    )

    private fun state(
        settings: UserSettings = located,
        tonight: TonightList? = night(),
        timeline: Timeline? = null,
        nowMillis: Long = now,
    ) = MainUiState(
        settings = settings,
        alertState = AlertState(),
        latitudeInput = "55.95",
        longitudeInput = "-3.19",
        quietFromInput = "23:00",
        quietUntilInput = "07:00",
        batteryExempt = true,
        notificationsAllowed = true,
        nowMillis = nowMillis,
        tonight = tonight,
        timeline = timeline,
    )

    private fun show(
        source: FakeCompassSource,
        initial: FindTarget? = FindTarget.Aurora,
        settings: UserSettings = located,
        tonight: TonightList? = night(),
        at: Long = now,
    ) {
        compose.setContent {
            FindScreen(
                state = state(settings = settings, tonight = tonight, nowMillis = at),
                actions = actions,
                source = source,
                initial = initial,
                clock = { at },
            )
        }
    }

    private fun instruction() = compose.onNodeWithTag(FIND_INSTRUCTION_TAG)

    // --- guiding ---

    /**
     * Aurora is looked for due north, ten degrees up; a phone facing east at
     * the horizon has a long way to turn left and a little to tilt.
     */
    @Test
    fun guidesTowardsTheChosenTarget() {
        val compass = FakeCompassSource()
        show(compass)

        compass.point(azimuth = 90.0, altitude = 0.0)

        instruction().assertTextEquals("Turn left a long way, then tilt up a little.")
        compose.onNodeWithTag(FIND_WHERE_TAG)
            .assertTextEquals("North, 10 degrees up. You are facing east, 0 degrees up.")
    }

    @Test
    fun saysThereWhenLinedUp() {
        val compass = FakeCompassSource()
        show(compass)

        compass.point(azimuth = 0.0, altitude = 10.0)

        instruction().assertTextEquals("There.")
    }

    /**
     * Looked through, the phone points out of its back: held up with the back
     * towards the aurora's spot it is lined up, where aimed by its top edge
     * the same hold would be pointing at the sky overhead.
     */
    @Test
    fun looksThroughThePhoneWhenAimedThatWay() {
        val compass = FakeCompassSource()
        show(compass, settings = located.copy(findAim = FindAim.LookThrough))

        compass.point(azimuth = 0.0, altitude = 10.0, aim = FindAim.LookThrough)

        instruction().assertTextEquals("There.")
    }

    @Test
    fun aimedByTheTopEdgeTheSameHoldIsNotLinedUp() {
        val compass = FakeCompassSource()
        show(compass)

        compass.point(azimuth = 0.0, altitude = 10.0, aim = FindAim.LookThrough)

        // Its top edge is 80 degrees up and leaning south, away from the aurora.
        instruction().assertTextEquals("Turn round, then tilt down.")
    }

    @Test
    fun saysHowToHoldThePhoneForTheWayItIsAimed() {
        val compass = FakeCompassSource()
        show(compass, settings = located.copy(findAim = FindAim.LookThrough))

        compose.onNodeWithText(aimLine(FindAim.LookThrough)).assertIsDisplayed()
        compose.onNodeWithTag(findAimTag(FindAim.LookThrough)).assertIsSelected()
        compose.onNodeWithTag(findAimTag(FindAim.TopEdge)).assertIsNotSelected()
    }

    @Test
    fun theAimChoiceIsReported() {
        show(FakeCompassSource())

        compose.onNodeWithTag(findAimTag(FindAim.LookThrough)).performClick()

        assertEquals(FindAim.LookThrough, aim)
    }

    /** No turning finds a pass that has not risen; the time it comes up is the answer. */
    @Test
    fun aTargetNotYetRisenGivesItsRiseTime() {
        val compass = FakeCompassSource()
        show(compass, initial = FindTarget.Pass(25544, station.risesAt), tonight = night(listOf(station)))

        compass.point(azimuth = 90.0, altitude = 0.0)

        instruction().assertTextEquals("Not up until ${clock(station.risesAt)}.")
        compose.onNodeWithTag(FIND_WHERE_TAG).assertTextEquals(
            "Rises in the south-west at ${clock(station.risesAt)}. You are facing east, 0 degrees up.",
        )
    }

    /**
     * A pass alert's rise comes off the poller's search grid, a second or so
     * from tonight's; it selects tonight's chip rather than adding a
     * "Satellite 25544" chip beside it.
     */
    @Test
    fun aPassFromANotificationSelectsTonightsChipForIt() {
        val handedIn = FindTarget.Pass(25544, station.risesAt + 2_000L)
        show(FakeCompassSource(), initial = handedIn, tonight = night(listOf(station)))

        compose.onNodeWithTag(findChipTag(FindTarget.Pass(25544, station.risesAt))).assertIsSelected()
        compose.onNodeWithTag(findChipTag(handedIn)).assertDoesNotExist()
        compose.onNodeWithText("Satellite 25544", substring = true).assertDoesNotExist()
    }

    /** Every pass in a busy night made the picker long enough to lag; only the first few are offered. */
    @Test
    fun aBusyNightOffersNoMoreThanTheLimitOfPasses() {
        val busy = (0 until 9).map {
            station.copy(
                catalogue = 40000 + it,
                risesAt = now + 3 * minute * (it + 1),
                peaksAt = now + 3 * minute * (it + 1) + 300_000L,
                setsAt = now + 3 * minute * (it + 1) + 600_000L,
            )
        }
        show(FakeCompassSource(), tonight = night(busy))

        val chips = compose.onAllNodes(SemanticsMatcher("a pass chip") {
            SemanticsProperties.TestTag in it.config &&
                it.config[SemanticsProperties.TestTag].startsWith("find-chip:pass:")
        }).fetchSemanticsNodes()

        assertEquals(sh.arek.gloaming.domain.FindTargets.MAX_PASSES, chips.size)
    }

    /** On a cold start from a pass alert tonight is not worked out yet, and the pass has not gone. */
    @Test
    fun aPassBeforeTonightIsWorkedOutIsNotCalledGone() {
        val compass = FakeCompassSource()
        show(compass, initial = FindTarget.Pass(25544, station.risesAt), tonight = null)

        compass.point(azimuth = 90.0, altitude = 0.0)

        instruction().assertTextEquals("Working out tonight...")
    }

    // --- only what can be seen now or very soon ---

    private fun chipTags() = compose.onAllNodes(SemanticsMatcher("a chip") {
        SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith("find-chip:")
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    /** Sun high and the moon up, so the moon is all there is to point at. */
    private fun dayWithMoon(): Long = (0 until 30 * 48).asSequence()
        .map { now + it * hour / 2 }
        .first {
            SkyPositions.body(SkyBody.Moon, latitude, longitude, it).altitude in 3.0..60.0 &&
                Twilight.sunAltitude(latitude, longitude, it) > 10.0
        }

    private fun dayWithoutMoon(): Long = (0 until 30 * 48).asSequence()
        .map { now + it * hour / 2 }
        .first {
            SkyPositions.body(SkyBody.Moon, latitude, longitude, it).altitude < -10.0 &&
                Twilight.sunAltitude(latitude, longitude, it) > 10.0
        }

    /** Well after dark, when the Plough is up. */
    private fun dark(): Long = (0 until 48).asSequence()
        .map { now + it * hour / 2 }
        .first { Twilight.sunAltitude(latitude, longitude, it) < -20.0 }

    @Test
    fun inDaylightOnlyTheMoonIsOffered() {
        show(FakeCompassSource(), initial = null, at = dayWithMoon())

        assertEquals(listOf(findChipTag(FindTarget.Moon)), chipTags())
        compose.onNodeWithTag(findChipTag(FindTarget.Moon)).assertIsSelected()
    }

    @Test
    fun withNothingUpItNamesTheNextThing() {
        show(FakeCompassSource(), initial = null, at = dayWithoutMoon())

        val line = compose.onNodeWithTag(FIND_NOTHING_TAG)
        line.assertIsDisplayed()
        // The search a day ahead runs off the main thread, so the line is on
        // screen first and the next-up part follows.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Next up:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        line.assert(SemanticsMatcher("names what is next") {
            val text = it.config[SemanticsProperties.Text].joinToString("") { part -> part.text }
            text.startsWith("Nothing to point at right now. Next up: ") && ", from " in text
        })
        assertEquals(emptyList<String>(), chipTags())
    }

    /** Not knowing tonight yet is not the same as nothing being up. */
    @Test
    fun whileTonightIsNotKnownItSaysItIsWorkingItOut() {
        show(FakeCompassSource(), initial = null, tonight = null, at = dayWithoutMoon())

        compose.onNodeWithTag(FIND_NOTHING_TAG).assertTextEquals(WORKING_OUT_TONIGHT)
    }

    /** A target nobody tapped is held once shown, so the list changing does not move the guidance. */
    @Test
    fun aDefaultTargetStaysSelectedAfterItHasSet() {
        var at by mutableStateOf(dayWithMoon())
        compose.setContent {
            FindScreen(
                state = state(nowMillis = at),
                actions = actions,
                source = FakeCompassSource(),
                initial = null,
                clock = { at },
            )
        }
        compose.onNodeWithTag(findChipTag(FindTarget.Moon)).assertIsSelected()

        at = dayWithoutMoon()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Not up until", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(findChipTag(FindTarget.Moon)).assertIsSelected()
    }

    /** A chosen target that goes below the horizon stays chosen, and says when it is back. */
    @Test
    fun aChosenTargetThatHasSetStaysSelectedAndSaysWhenItIsBack() {
        val at = dayWithoutMoon()
        show(FakeCompassSource(), initial = FindTarget.Moon, at = at)

        compose.onNodeWithTag(findChipTag(FindTarget.Moon)).assertIsSelected()
        instruction().assert(SemanticsMatcher("says when it is next up") {
            it.config[SemanticsProperties.Text].joinToString("") { part -> part.text }.startsWith("Not up until ")
        })
    }

    @Test
    fun waitsForTheCompassBeforeGuiding() {
        show(FakeCompassSource())

        instruction().assertTextEquals("Waiting for the compass.")
    }

    @Test
    fun theInstructionIsSpokenAsItChanges() {
        show(FakeCompassSource())

        instruction().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    /** The drawing is one node to a screen reader, saying what the words below it say. */
    @Test
    fun theViewSaysWhatItShows() {
        val compass = FakeCompassSource()
        show(compass)

        compass.point(azimuth = 0.0, altitude = 10.0)

        compose.onNodeWithTag(FIND_VIEW_TAG).assert(hasContentDescription("There.", substring = true))
    }

    /**
     * A pattern's view draws its figure and still says it is lined up.
     *
     * The words come from the separation, not from where the ring is drawn,
     * so this proves the figure draws without breaking the view; that the
     * ring sits on the reticle when lined up is [SkyProjection]'s own test,
     * which puts the centre at the origin.
     */
    @Test
    fun drawsAPatternWithoutLosingTheRing() {
        val compass = FakeCompassSource()
        val at = dark()
        val plough = Constellations.WATCHED.first { it.name == "Ursa Major" }
        show(compass, initial = FindTarget.Pattern("Ursa Major"), at = at)

        val centre = SkyPositions.fixed(plough.raHours, plough.dec, latitude, longitude, at)
        compass.point(azimuth = centre.azimuth, altitude = centre.altitude)

        compose.onNodeWithTag(FIND_VIEW_TAG).assert(hasContentDescription("There.", substring = true))
    }

    @Test
    fun offersThePloughAndTheChosenTarget() {
        show(FakeCompassSource(), initial = FindTarget.Aurora, at = dark())

        compose.onNodeWithTag(findChipTag(FindTarget.Pattern("Ursa Major"))).assertIsDisplayed()
        compose.onNodeWithTag(findChipTag(FindTarget.Aurora)).assertIsSelected()
    }

    @Test
    fun pickingAChipAimsAtIt() {
        val compass = FakeCompassSource()
        show(compass, initial = FindTarget.Aurora, at = dark())
        compass.point(azimuth = 0.0, altitude = 10.0)

        compose.onNodeWithTag(findChipTag(FindTarget.Pattern("Ursa Major"))).performClick()

        compose.onNodeWithTag(findChipTag(FindTarget.Pattern("Ursa Major"))).assertIsSelected()
        compose.onNodeWithText("The Plough", substring = true).assertIsDisplayed()
    }

    @Test
    fun saysSoOnAPhoneWithNoCompass() {
        show(FakeCompassSource(present = false))

        compose.onNodeWithText("This phone has no compass, so Find cannot point.").assertIsDisplayed()
        compose.onNodeWithTag(FIND_VIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun asksForALocationFirst() {
        show(FakeCompassSource(), settings = UserSettings())

        compose.onNodeWithText("Set where you are", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(FIND_VIEW_TAG).assertDoesNotExist()
    }

    // --- buzzing and the screen ---

    /** Once on lining up, not on every reading that stays lined up. */
    @Test
    fun buzzesOnceEachTimeItLinesUp() {
        val compass = FakeCompassSource()
        val haptics = CountingHaptics()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                FindScreen(state(), actions, compass, FindTarget.Aurora, clock = { now })
            }
        }

        compass.point(azimuth = 0.0, altitude = 10.0)
        compose.waitForIdle()
        compass.point(azimuth = 1.0, altitude = 11.0)
        compose.waitForIdle()
        compass.point(azimuth = 359.0, altitude = 9.0)
        compose.waitForIdle()
        assertEquals(1, haptics.longPresses)

        compass.point(azimuth = 90.0, altitude = 10.0)
        compose.waitForIdle()
        compass.point(azimuth = 0.0, altitude = 10.0)
        compose.waitForIdle()
        assertEquals(2, haptics.longPresses)
    }

    @Test
    fun keepsTheScreenOnOnlyWhileShown() {
        var shown by mutableStateOf(true)
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            if (shown) FindScreen(state(), actions, FakeCompassSource(), FindTarget.Aurora, clock = { now })
        }

        compose.waitForIdle()
        assertTrue(view!!.keepScreenOn)

        shown = false
        compose.waitForIdle()
        assertFalse(view!!.keepScreenOn)
    }

    /**
     * Night vision on Find dims the window as well as turning it red, and
     * hands back whatever brightness was there before once Find is left.
     * Read from the window, as NightVisionWindowTest does: brightness never
     * reaches the composition.
     */
    @Test
    fun dimsTheScreenWhileFindIsShownInNightVision() {
        var shown by mutableStateOf(true)
        setBrightness(0.5f)
        compose.setContent {
            if (shown) {
                FindScreen(
                    state(settings = located.copy(nightVisionWhenFinding = true)),
                    actions, FakeCompassSource(), FindTarget.Aurora, clock = { now },
                )
            }
        }

        compose.waitForIdle()
        assertEquals(NIGHT_BRIGHTNESS, brightness(), 0f)

        shown = false
        compose.waitForIdle()
        assertEquals(0.5f, brightness(), 0f)
    }

    @Test
    fun leavesTheBrightnessAloneWithoutNightVisionWhenFinding() {
        compose.setContent {
            FindScreen(
                state(settings = located.copy(nightVisionWhenFinding = false)),
                actions, FakeCompassSource(), FindTarget.Aurora, clock = { now },
            )
        }

        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, brightness(), 0f)
    }

    private fun brightness(): Float {
        var brightness = Float.NaN
        compose.runOnUiThread { brightness = compose.activity.window.attributes.screenBrightness }
        return brightness
    }

    /** This test activity's own window, never the phone's setting. */
    private fun setBrightness(value: Float) {
        compose.runOnUiThread {
            val window = compose.activity.window
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
    }

    @Test
    fun stopsListeningToTheCompassWhenTheAppIsNotInFront() {
        val compass = FakeCompassSource()
        show(compass)
        compose.waitUntil(2_000) { compass.listeners == 1 }

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(2_000) { compass.listeners == 0 }

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(2_000) { compass.listeners == 1 }
    }

    // --- the compass check ---

    @Test
    fun offersTheCompassCheckOnALowReading() {
        val compass = FakeCompassSource()
        show(compass, settings = located.copy(compassOfferedAt = null))

        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.Low)

        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertIsDisplayed()
        assertEquals(now, offeredAt)
    }

    @Test
    fun doesNotOfferItTwiceInAWeek() {
        val compass = FakeCompassSource()
        show(compass, settings = located.copy(compassOfferedAt = now - 6 * 24 * hour))

        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.Low)
        compose.waitForIdle()

        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertDoesNotExist()
        assertNull(offeredAt)
    }

    @Test
    fun doesNotOfferItWhenTheCompassIsGood() {
        val compass = FakeCompassSource()
        show(compass)

        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.High)
        compose.waitForIdle()

        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun checkCompassOpensTheSheetAndDoneRecordsIt() {
        val compass = FakeCompassSource()
        show(compass)
        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.Medium)

        compose.onNodeWithTag(CHECK_COMPASS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPASS_DONE_TAG).performScrollTo().performClick()

        assertEquals(now, checkedAt)
        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertDoesNotExist()
    }

    /** Pointed three degrees short of the moon, Line up moves the compass three degrees on. */
    @Test
    fun lineUpCorrectsTheCompassOnTheMoon() {
        val at = moonUp()
        val moon = SkyPositions.body(SkyBody.Moon, latitude, longitude, at)
        val declination = magneticDeclination(latitude, longitude, at)
        val compass = FakeCompassSource()
        show(compass, at = at)

        compass.point(azimuth = moon.azimuth - declination - 3.0, altitude = moon.altitude)
        compose.onNodeWithTag(CHECK_COMPASS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_UP_TAG).performScrollTo().performClick()

        assertNotNull(offset)
        assertEquals(3.0, offset!!, 0.5)
    }

    @Test
    fun lineUpSaysWhyItRefused() {
        val at = moonUp()
        val moon = SkyPositions.body(SkyBody.Moon, latitude, longitude, at)
        val compass = FakeCompassSource()
        show(compass, at = at)

        compass.point(azimuth = moon.azimuth, altitude = 0.0)
        compose.onNodeWithTag(CHECK_COMPASS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_UP_TAG).performScrollTo().performClick()

        compose.onNodeWithText("Point at the moon first.").performScrollTo().assertIsDisplayed()
        assertNull(offset)
    }

    @Test
    fun offersNoLineUpWhileTheMoonIsDown() {
        val at = moonDown()
        val compass = FakeCompassSource()
        show(compass, at = at)
        compass.point(azimuth = 0.0, altitude = 10.0)

        compose.onNodeWithTag(CHECK_COMPASS_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LINE_UP_TAG).assertDoesNotExist()
    }

    // --- getting here, and leaving ---

    @Test
    fun findItOnARowOpensFindAimedAtThatRow() {
        val compass = FakeCompassSource()
        val row = TimelineRow(
            station.peaksAt, clock(station.peaksAt), "ISS (ZARYA), 68 degrees high", null,
            RowTone.Normal, RowSubject.Pass(station), PickTag.Go,
        )
        val timeline = Timeline(
            startMillis = now - hour,
            endMillis = now + 13 * hour,
            rows = listOf(row),
            cloud = emptyList(),
            nowMillis = now,
        )
        compose.setContent {
            GloamingNav(
                state = state(tonight = night(listOf(station)), timeline = timeline),
                actions = actions,
                compass = compass,
            )
        }

        compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().performClick()
        compose.onNodeWithTag(FIND_IT_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(Section.Find.tag).assertIsSelected()
        compose.onNodeWithTag(findChipTag(FindTarget.Pass(25544, station.risesAt))).assertIsSelected()
    }

    @Test
    fun aRowWithNothingToPointAtOffersNoFindIt() {
        val row = TimelineRow(now, clock(now), "Sunset", null, RowTone.Normal, RowSubject.Twilight, PickTag.Go)
        val timeline = Timeline(now - hour, now + 13 * hour, listOf(row), emptyList(), now)
        compose.setContent {
            GloamingNav(state = state(timeline = timeline), actions = actions, compass = FakeCompassSource())
        }

        compose.onNodeWithTag(timelineRowTag(row)).performScrollTo().performClick()

        compose.onNodeWithTag(ROW_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIND_IT_TAG).assertDoesNotExist()
    }

    /**
     * The sensor is registered while Find is on screen and let go as soon as it is not.
     *
     * Find listens only while it has something to point at, and on the real
     * clock that was whatever the sky over Edinburgh held as the test ran: in
     * the evening twilight, nothing, and the test failed. Held at a dark hour
     * the Plough, which never sets there, is always offered.
     */
    @Test
    fun listensToTheCompassOnlyWhileFindIsShown() {
        val compass = FakeCompassSource()
        val dark = now + 14 * hour
        compose.setContent {
            GloamingNav(state = state(nowMillis = dark), actions = actions, compass = compass, findClock = { dark })
        }
        compose.waitForIdle()
        assertEquals(0, compass.listeners)

        compose.onNodeWithTag(Section.Find.tag).performClick()
        compose.waitUntil(2_000) { compass.listeners == 1 }

        compose.onNodeWithTag(Section.Tonight.tag).performClick()
        compose.waitUntil(2_000) { compass.listeners == 0 }
    }

    // --- the Finding things pages ---

    private fun showPage(page: SettingsPage, settings: UserSettings, compass: FakeCompassSource) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SettingsPageContent(page = page, state = state(settings = settings), actions = actions, compass = compass)
            }
        }
    }

    @Test
    fun theCompassPageResetsTheCorrection() {
        val compass = FakeCompassSource()
        showPage(SettingsPage.Compass, located.copy(compassOffsetDegrees = 4.0), compass)
        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.Medium)

        compose.onNodeWithText("Compass accuracy is medium", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(COMPASS_RESET_TAG).performScrollTo().performClick()

        assertEquals(0.0, offset!!, 0.0)
    }

    @Test
    fun theCompassPageOffersNoResetWithNoCorrection() {
        showPage(SettingsPage.Compass, located, FakeCompassSource())

        compose.onNodeWithTag(COMPASS_RESET_TAG).assertDoesNotExist()
    }

    @Test
    fun theCompassPageOpensTheCheck() {
        val compass = FakeCompassSource()
        showPage(SettingsPage.Compass, located, compass)
        compass.point(azimuth = 0.0, altitude = 10.0, accuracy = CompassAccuracy.Low)

        compose.onNodeWithTag(CHECK_COMPASS_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(COMPASS_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun theNightVisionWhenFindingSwitchIsReported() {
        showPage(SettingsPage.NightVisionWhenFinding, located, FakeCompassSource())

        compose.onNodeWithTag(NIGHT_VISION_FINDING_SWITCH_TAG).performClick()

        assertEquals(false, nightVisionWhenFinding)
    }

    /** The first instant from [now], on the half hour, when the moon is comfortably up. */
    private fun moonUp(): Long = (0 until 24 * 60).asSequence()
        .map { now + it * hour / 2 }
        .first { SkyPositions.body(SkyBody.Moon, latitude, longitude, it).altitude in 20.0..60.0 }

    private fun moonDown(): Long = (0 until 24 * 60).asSequence()
        .map { now + it * hour / 2 }
        .first { SkyPositions.body(SkyBody.Moon, latitude, longitude, it).altitude < -10.0 }

    private class CountingHaptics : HapticFeedback {
        var longPresses = 0

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            if (hapticFeedbackType == HapticFeedbackType.LongPress) longPresses++
        }
    }
}
