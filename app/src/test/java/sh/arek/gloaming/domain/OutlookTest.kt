package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The opening sentence may only ever send somebody out for something they will
 * see. Every test here builds tonight by hand and stubs the sun and the moon as
 * step functions, so each gate can be failed on its own.
 *
 * The night is a December one at Edinburgh: civil darkness 16:20 to 07:50 UTC,
 * astronomical 17:30 to 06:40, the moon down unless a test raises it.
 */
class OutlookTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val darkFrom = at("2026-12-13T16:20:00Z")
    private val darkUntil = at("2026-12-14T07:50:00Z")
    private val darkestFrom = at("2026-12-13T17:30:00Z")
    private val darkestUntil = at("2026-12-14T06:40:00Z")
    private val evening = at("2026-12-13T17:00:00Z")

    private val settings = UserSettings(
        latitude = 55.95,
        longitude = -3.19,
        threshold = 40,
        cloudTolerance = 50,
        minimumPassElevationDegrees = 30,
    )

    /** The sun as a step: fully down in astronomical darkness, -10 in civil, up otherwise. */
    private val sun: (Long) -> Double = { t ->
        when (t) {
            in darkestFrom..darkestUntil -> -20.0
            in darkFrom..darkUntil -> -10.0
            else -> 10.0
        }
    }

    private val moonDown: (Long) -> Double = { -5.0 }

    /** One entry an hour from [from], reading the percentages in order. */
    private fun hours(from: String, vararg total: Int): List<HourlyCover> =
        total.mapIndexed { index, cover -> HourlyCover(at(from) + index * HOUR, cover) }

    /** Clear all night: 10% every hour from 16:00 to 08:00. */
    private val clearNight = hours("2026-12-13T16:00:00Z", *IntArray(16) { 10 })

    private val allNight = ClearSpell(darkFrom, darkUntil, underway = false, allNight = true)

    private fun moon(illumination: Double) = MoonTonight(
        phaseDegrees = 180.0,
        illumination = illumination,
        upFraction = 1.0,
        interference = illumination,
        risesAt = null,
        setsAt = null,
    )

    private fun tonight(
        darkFrom: Long? = this.darkFrom,
        darkestFrom: Long? = this.darkestFrom,
        darkestUntil: Long? = this.darkestUntil,
        passes: List<SatellitePass> = emptyList(),
        bestPasses: List<SatellitePass> = passes,
        events: List<SkyEvent> = emptyList(),
        comets: List<SkyEvent> = emptyList(),
        noctilucent: SkyEvent? = null,
        moon: MoonTonight? = moon(0.1),
        clearSpell: ClearSpell? = allNight,
        nextClearSpell: ClearSpell? = null,
        planets: List<PlanetTonight> = emptyList(),
        constellations: List<ConstellationTonight> = emptyList(),
        constellationsAlwaysUp: List<String> = emptyList(),
    ) = TonightList(
        darkFrom = darkFrom,
        darkUntil = if (darkFrom == null) null else darkUntil,
        passes = passes,
        bestPasses = bestPasses,
        events = events,
        comets = comets,
        noctilucent = noctilucent,
        darkestFrom = darkestFrom,
        darkestUntil = darkestUntil,
        moon = moon,
        clearSpell = clearSpell,
        planets = planets,
        constellations = constellations,
        constellationsAlwaysUp = constellationsAlwaysUp,
        nextClearSpell = nextClearSpell,
    )

    private fun input(
        settings: UserSettings = this.settings,
        now: Long = evening,
        hourly: List<HourlyCover> = clearNight,
        auroraProbability: Int? = null,
        auroraAgeMillis: Long? = 10 * MINUTE,
        elementsAgeMillis: Long? = HOUR,
        moonAltitude: (Long) -> Double = moonDown,
        planetAltitude: (SkyBody, Long) -> Double = { _, _ -> 30.0 },
        sunAltitude: (Long) -> Double = sun,
    ) = OutlookInput(
        settings = settings,
        nowMillis = now,
        hourly = hourly,
        auroraProbability = auroraProbability,
        auroraAgeMillis = auroraAgeMillis,
        elementsAgeMillis = elementsAgeMillis,
        sunAltitude = sunAltitude,
        moonAltitude = moonAltitude,
        planetAltitude = planetAltitude,
    )

    private fun pass(
        peaksAt: Long,
        name: String = "ISS (ZARYA)",
        elevation: Double = 60.0,
        sunlitAtPeak: Boolean = true,
    ) = SatellitePass(
        catalogue = 25544,
        name = name,
        channel = PassChannel.Iss,
        risesAt = peaksAt - 4 * MINUTE,
        riseAzimuth = 225.0,
        peaksAt = peaksAt,
        peakElevation = elevation,
        peakAzimuth = 180.0,
        setsAt = peaksAt + 4 * MINUTE,
        setAzimuth = 45.0,
        rangeAtPeakKm = 450.0,
        sunlitAtPeak = sunlitAtPeak,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    private fun planet(body: SkyBody, peakAt: Long, altitude: Double = 30.0) = PlanetTonight(
        body = body,
        peakAltitude = altitude,
        peakAt = peakAt,
        peakAzimuth = 180.0,
        magnitude = 0.5,
        risesAt = null,
        setsAt = null,
    )

    private fun shower(
        at: Long = at("2026-12-14T01:00:00Z"),
        visibility: Visibility = Visibility.Good,
    ) = SkyEvent(
        kind = EventKind.MeteorShower,
        at = at,
        from = darkFrom,
        until = darkUntil,
        title = "Geminids at their best",
        detail = "Perhaps 100 an hour.",
        visibility = visibility,
    )

    private fun comet(at: Long = at("2026-12-13T19:00:00Z")) = SkyEvent(
        kind = EventKind.Comet,
        at = at,
        from = darkFrom,
        until = darkUntil,
        title = "Comet C/2026 A1",
        detail = "A smudge in the west.",
        visibility = Visibility.Good,
    )

    private fun occultation(at: Long) = SkyEvent(
        kind = EventKind.Occultation,
        at = at,
        from = at - HOUR,
        until = at + HOUR,
        title = "The moon covers Saturn",
        detail = "Gone for an hour.",
        visibility = Visibility.Good,
    )

    private fun eclipse(kind: EventKind, at: Long) = SkyEvent(
        kind = kind,
        at = at,
        from = at - 40 * MINUTE,
        until = at + 40 * MINUTE,
        title = if (kind == EventKind.SolarEclipse) "Eclipse of the sun" else "Eclipse of the moon",
        detail = "",
        visibility = Visibility.Good,
    )

    private fun conjunction(at: Long) = SkyEvent(
        kind = EventKind.Conjunction,
        at = at,
        from = darkFrom,
        until = darkUntil,
        title = "The moon and Jupiter",
        detail = "2 degrees apart.",
        visibility = Visibility.Good,
    )

    private fun pattern(name: String, isAtItsBest: Boolean = false) = ConstellationTonight(
        constellation = Constellations.WATCHED.first { it.name == name },
        peakAltitude = 50.0,
        peakAt = at("2026-12-14T00:00:00Z"),
        peakAzimuth = 180.0,
        isCircumpolar = false,
        isAtItsBest = isAtItsBest,
    )

    @Test
    fun `no location gives no sentence`() {
        val report = Outlooks.of(tonight(), input(settings = UserSettings()))

        assertEquals(Outlook.NoLocation, report.outlook)
    }

    @Test
    fun `points the aurora and noctilucent clouds towards the nearer pole`() {
        val north = Outlooks.of(tonight(), input())
        val south = Outlooks.of(tonight(), input(settings = settings.copy(latitude = -45.87, longitude = 170.5)))

        assertEquals(Compass.North, north.poleward)
        assertEquals(Compass.South, south.poleward)
    }

    @Test
    fun `go out says whether the clear run has begun and covers the night`() {
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(tonight(planets = listOf(saturn)), input())

        val goOut = report.outlook as Outlook.GoOut
        assertTrue(goOut.clearUnderway)
        assertTrue(goOut.clearAllNight)
    }

    @Test
    fun `recommends a planet only inside the clear spell`() {
        val spell = ClearSpell(
            from = at("2026-12-13T22:00:00Z"),
            until = at("2026-12-14T02:00:00Z"),
            underway = false,
            allNight = false,
        )
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))
        val jupiter = planet(SkyBody.Jupiter, at("2026-12-13T19:00:00Z"))
        val hourly = hours(
            "2026-12-13T16:00:00Z",
            90, 90, 90, 90, 90, 90, 10, 10, 10, 10, 90, 90, 90, 90, 90, 90,
        )

        val report = Outlooks.of(
            tonight(clearSpell = spell, planets = listOf(jupiter, saturn)),
            input(hourly = hourly),
        )

        assertEquals(
            Outlook.GoOut(
                picks = listOf(Pick(PickKind.Planet, "Saturn", saturn.peakAt, planet = saturn)),
                clearFrom = spell.from,
                clearUntil = spell.until,
                conditional = false,
            ),
            report.outlook,
        )
    }

    @Test
    fun `never recommends a pass under forecast cloud`() {
        val iss = pass(at("2026-12-13T19:12:00Z"))
        val hourly = hours("2026-12-13T16:00:00Z", 10, 10, 10, 80, 10, 10, 10, 10)

        val report = Outlooks.of(tonight(passes = listOf(iss)), input(hourly = hourly))

        assertEquals(
            listOf(Excluded("ISS (ZARYA)", iss.peaksAt, Gate.Cloud, "80")),
            report.excluded,
        )
    }

    @Test
    fun `an hour missing from the forecast fails the cloud gate`() {
        val iss = pass(at("2026-12-14T03:12:00Z"))
        val hourly = hours("2026-12-13T16:00:00Z", 10, 10, 10, 10, 10, 10, 10, 10)

        val report = Outlooks.of(tonight(passes = listOf(iss)), input(hourly = hourly))

        assertEquals(
            listOf(Excluded("ISS (ZARYA)", iss.peaksAt, Gate.Cloud, null)),
            report.excluded,
        )
    }

    @Test
    fun `never recommends a pass in the earth's shadow`() {
        val iss = pass(at("2026-12-13T19:12:00Z"), sunlitAtPeak = false)

        val report = Outlooks.of(tonight(passes = listOf(iss)), input())

        assertEquals(Gate.Sunlit, report.excluded.single().gate)
    }

    @Test
    fun `never recommends a pass below the minimum elevation`() {
        val iss = pass(at("2026-12-13T19:12:00Z"), elevation = 14.8)

        val report = Outlooks.of(tonight(passes = listOf(iss)), input())

        assertEquals(
            listOf(Excluded("ISS (ZARYA)", iss.peaksAt, Gate.Height, "14", kind = PickKind.Pass)),
            report.excluded,
        )
    }

    @Test
    fun `never recommends a planet that stays low`() {
        val mercury = planet(SkyBody.Mercury, at("2026-12-13T19:00:00Z"), altitude = 6.0)

        val report = Outlooks.of(
            tonight(planets = listOf(mercury)),
            input(planetAltitude = { _, _ -> 6.0 }),
        )

        assertEquals(Gate.Height, report.excluded.single().gate)
    }

    @Test
    fun `a bright moon spoils a meteor shower`() {
        val geminids = shower()

        val report = Outlooks.of(
            tonight(events = listOf(geminids), moon = moon(0.92)),
            input(moonAltitude = { 30.0 }),
        )

        assertEquals(
            listOf(Excluded(geminids.title, geminids.at, Gate.Moon, "92")),
            report.excluded,
        )
    }

    @Test
    fun `a set moon does not spoil it`() {
        val geminids = shower()

        val report = Outlooks.of(
            tonight(events = listOf(geminids), moon = moon(0.92)),
            input(moonAltitude = { t -> if (t < at("2026-12-14T00:00:00Z")) 30.0 else -5.0 }),
        )

        assertEquals(
            listOf(Pick(PickKind.Event, geminids.title, geminids.at, event = geminids)),
            (report.outlook as Outlook.GoOut).picks,
        )
    }

    @Test
    fun `a shower on a night clear from dusk keeps its best hour`() {
        val geminids = shower(at = at("2026-12-14T01:00:00Z"))

        val report = Outlooks.of(tonight(events = listOf(geminids)), input())

        assertEquals(geminids.at, (report.outlook as Outlook.GoOut).picks.single().atMillis)
    }

    @Test
    fun `a shower whose best hour is cloudy is timed from when it clears`() {
        val geminids = shower(at = at("2026-12-14T03:00:00Z"))
        val spell = ClearSpell(
            from = at("2026-12-13T22:00:00Z"),
            until = at("2026-12-14T01:00:00Z"),
            underway = false,
            allNight = false,
        )
        val hourly = hours(
            "2026-12-13T16:00:00Z",
            90, 90, 90, 90, 90, 90, 10, 10, 10, 90, 90, 90, 90, 90, 90, 90,
        )

        val report = Outlooks.of(
            tonight(events = listOf(geminids), clearSpell = spell),
            input(hourly = hourly),
        )

        assertEquals(spell.from, (report.outlook as Outlook.GoOut).picks.single().atMillis)
    }

    @Test
    fun `a clear spell that starts in twilight times a shower from full darkness`() {
        val geminids = shower(at = at("2026-12-14T03:00:00Z"))
        val spell = ClearSpell(
            from = darkFrom,
            until = at("2026-12-13T20:00:00Z"),
            underway = false,
            allNight = false,
        )
        val hourly = hours("2026-12-13T16:00:00Z", 10, 10, 10, 10, 90, 90, 90, 90, 90, 90, 90, 90)

        val report = Outlooks.of(
            tonight(events = listOf(geminids), clearSpell = spell),
            input(hourly = hourly),
        )

        assertEquals(darkestFrom, (report.outlook as Outlook.GoOut).picks.single().atMillis)
    }

    @Test
    fun `high thin cloud spoils a faint thing but not a bright one`() {
        val thin = (0 until 16).map {
            HourlyCover(at("2026-12-13T16:00:00Z") + it * HOUR, 90, low = 0, mid = 0, high = 90)
        }
        val geminids = shower()
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(events = listOf(geminids), planets = listOf(saturn)),
            input(hourly = thin),
        )

        val picked = (report.outlook as Outlook.GoOut).picks.map { it.kind }
        assertEquals(
            listOf(PickKind.Planet) to listOf(Gate.Cloud),
            picked to report.excluded.map { it.gate },
        )
    }

    @Test
    fun `a faint thing needs a fully dark sky`() {
        val report = Outlooks.of(
            tonight(comets = listOf(comet()), darkestFrom = null, darkestUntil = null),
            input(),
        )

        assertEquals(
            listOf(Excluded("Comet C/2026 A1", comet().at, Gate.Darkness, darkness = NEVER_DARK)),
            report.excluded,
        )
    }

    @Test
    fun `marginal events are never recommended`() {
        val reason = "The radiant only reaches 12 degrees."
        val geminids = shower(visibility = Visibility.Marginal(reason))

        val report = Outlooks.of(tonight(events = listOf(geminids)), input())

        assertEquals(
            listOf(Excluded(geminids.title, geminids.at, Gate.Visibility, reason)),
            report.excluded,
        )
    }

    @Test
    fun `scenery events are neither recommended nor excluded`() {
        val fullMoon = SkyEvent(
            kind = EventKind.MoonPhase,
            at = at("2026-12-13T21:00:00Z"),
            from = at("2026-12-13T21:00:00Z"),
            until = at("2026-12-13T21:00:00Z"),
            title = "Full moon",
            detail = "",
            visibility = Visibility.Good,
        )

        val report = Outlooks.of(tonight(events = listOf(fullMoon)), input())

        assertTrue(report.outlook is Outlook.Quiet && report.excluded.isEmpty())
    }

    @Test
    fun `aurora above the threshold while clear now leads`() {
        val now = at("2026-12-13T20:00:00Z")
        val spell = ClearSpell(now, at("2026-12-14T01:00:00Z"), underway = true, allNight = false)
        val hourly = hours("2026-12-13T16:00:00Z", *IntArray(9) { 10 }, *IntArray(7) { 90 })

        val report = Outlooks.of(
            tonight(clearSpell = spell),
            input(now = now, hourly = hourly, auroraProbability = 65),
        )

        assertEquals(Outlook.AuroraNow(65, spell.until, conditional = false), report.outlook)
    }

    @Test
    fun `an old aurora reading is not acted on`() {
        val now = at("2026-12-13T20:00:00Z")

        val report = Outlooks.of(
            tonight(),
            input(now = now, auroraProbability = 65, auroraAgeMillis = 4 * HOUR),
        )

        assertEquals(
            listOf(Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Aurora, "65")),
            report.excluded,
        )
    }

    @Test
    fun `an old aurora reading is not quoted`() {
        val report = Outlooks.of(
            tonight(),
            input(auroraProbability = 65, auroraAgeMillis = 4 * HOUR),
        )

        assertNull(report.auroraProbability)
    }

    @Test
    fun `a weak aurora is set aside for being weak rather than for the twilight`() {
        val report = Outlooks.of(tonight(), input(auroraProbability = 12))

        assertEquals(
            listOf(Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Aurora, "12")),
            report.excluded,
        )
    }

    @Test
    fun `a strong aurora needs only civil darkness`() {
        val spell = ClearSpell(evening, darkUntil, underway = true, allNight = true)

        val report = Outlooks.of(tonight(clearSpell = spell), input(auroraProbability = 55))

        assertEquals(Outlook.AuroraNow(55, darkUntil, conditional = false), report.outlook)
    }

    @Test
    fun `overcast all night names tomorrow's clear spell`() {
        val tomorrow = ClearSpell(
            from = at("2026-12-14T21:00:00Z"),
            until = at("2026-12-15T02:00:00Z"),
            underway = false,
            allNight = false,
        )
        val geminids = shower()
        val overcast = hours("2026-12-13T16:00:00Z", *IntArray(16) { 95 })

        val report = Outlooks.of(
            tonight(events = listOf(geminids), clearSpell = null, nextClearSpell = tomorrow),
            input(hourly = overcast),
        )

        assertEquals(
            Outlook.Overcast(
                nextClear = tomorrow,
                missed = Pick(PickKind.Event, geminids.title, geminids.at, event = geminids),
            ),
            report.outlook,
        )
    }

    @Test
    fun `overcast misses nothing that would have failed anyway`() {
        val iss = pass(at("2026-12-13T19:12:00Z"), sunlitAtPeak = false)
        val overcast = hours("2026-12-13T16:00:00Z", *IntArray(16) { 95 })

        val report = Outlooks.of(
            tonight(passes = listOf(iss), clearSpell = null),
            input(hourly = overcast),
        )

        assertEquals(Outlook.Overcast(nextClear = null, missed = null), report.outlook)
    }

    @Test
    fun `no night in midsummer`() {
        val report = Outlooks.of(
            tonight(darkFrom = null, darkestFrom = null, darkestUntil = null, clearSpell = null),
            input(),
        )

        assertEquals(Outlook.NoNight(noctilucent = false), report.outlook)
    }

    @Test
    fun `a midsummer night says when noctilucent cloud is possible`() {
        val noctilucent = SkyEvent(
            kind = EventKind.Noctilucent,
            at = at("2026-06-20T23:00:00Z"),
            from = at("2026-06-20T22:00:00Z"),
            until = at("2026-06-21T02:00:00Z"),
            title = "Noctilucent clouds possible",
            detail = "",
            visibility = Visibility.Good,
        )

        val report = Outlooks.of(
            tonight(
                darkFrom = null,
                darkestFrom = null,
                darkestUntil = null,
                clearSpell = null,
                noctilucent = noctilucent,
            ),
            input(),
        )

        assertEquals(Outlook.NoNight(noctilucent = true), report.outlook)
    }

    @Test
    fun `without a forecast nothing is called clear`() {
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(planets = listOf(saturn)),
            input(settings = settings.copy(cloudGateEnabled = false)),
        )

        assertEquals(
            Outlook.GoOut(
                picks = listOf(Pick(PickKind.Planet, "Saturn", saturn.peakAt, planet = saturn)),
                clearFrom = null,
                clearUntil = null,
                conditional = true,
            ),
            report.outlook,
        )
    }

    @Test
    fun `offline with a recent aurora reading still answers, conditionally`() {
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(planets = listOf(saturn), clearSpell = null),
            input(hourly = emptyList(), auroraProbability = 10, auroraAgeMillis = 20 * HOUR),
        )

        assertEquals(
            Outlook.GoOut(
                picks = listOf(Pick(PickKind.Planet, "Saturn", saturn.peakAt, planet = saturn)),
                clearFrom = null,
                clearUntil = null,
                conditional = true,
            ),
            report.outlook,
        )
    }

    @Test
    fun `clear and quiet suggests a constellation to learn`() {
        val report = Outlooks.of(
            tonight(
                constellations = listOf(pattern("Orion")),
                constellationsAlwaysUp = listOf("Ursa Major", "Cassiopeia"),
            ),
            input(),
        )

        assertEquals("the Plough", (report.outlook as Outlook.Quiet).learn)
    }

    /** South of the equator the Plough is down, and the Southern Cross is the signpost. */
    @Test
    fun `clear and quiet in the south suggests the Southern Cross`() {
        val report = Outlooks.of(
            tonight(
                constellations = listOf(pattern("Scorpius")),
                constellationsAlwaysUp = listOf("Crux", "Centaurus"),
            ),
            input(),
        )

        assertEquals("the Southern Cross", (report.outlook as Outlook.Quiet).learn)
    }

    @Test
    fun `a constellation at its best is the one to learn`() {
        val report = Outlooks.of(
            tonight(
                constellations = listOf(pattern("Perseus"), pattern("Orion", isAtItsBest = true)),
                constellationsAlwaysUp = listOf("Ursa Major"),
            ),
            input(),
        )

        assertEquals("Orion", (report.outlook as Outlook.Quiet).learn)
    }

    @Test
    fun `a quiet night is called clear only from when the spell starts`() {
        val spell = ClearSpell(
            from = at("2026-12-13T23:00:00Z"),
            until = darkUntil,
            underway = false,
            allNight = false,
        )

        val report = Outlooks.of(tonight(clearSpell = spell), input())

        assertEquals(
            Outlook.Quiet(darkFrom = spell.from, learn = null, conditional = false),
            report.outlook,
        )
    }

    @Test
    fun `cannot tell without a forecast or a recent reading`() {
        val report = Outlooks.of(
            tonight(clearSpell = null),
            input(hourly = emptyList(), auroraProbability = null, auroraAgeMillis = null),
        )

        assertEquals(
            Outlook.CannotTell(forecastHeld = false, elementsStale = false),
            report.outlook,
        )
    }

    @Test
    fun `stale elements with only passes to offer cannot tell`() {
        val iss = pass(at("2026-12-13T19:12:00Z"))

        val report = Outlooks.of(
            tonight(passes = listOf(iss)),
            input(elementsAgeMillis = 4 * DAY),
        )

        assertEquals(
            Outlook.CannotTell(forecastHeld = true, elementsStale = true),
            report.outlook,
        )
    }

    @Test
    fun `stale elements do not silence a planet`() {
        val iss = pass(at("2026-12-13T19:12:00Z"))
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(passes = listOf(iss), planets = listOf(saturn)),
            input(elementsAgeMillis = 4 * DAY),
        )

        assertFalse(report.outlook is Outlook.CannotTell)
    }

    @Test
    fun `names at most two things, best first`() {
        val jupiter = conjunction(at("2026-12-13T22:00:00Z"))
        val iss = pass(at("2026-12-13T19:12:00Z"))
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T18:00:00Z"))

        val report = Outlooks.of(
            tonight(events = listOf(jupiter), passes = listOf(iss), planets = listOf(saturn)),
            input(),
        )

        assertEquals(
            listOf(PickKind.Event, PickKind.Pass),
            (report.outlook as Outlook.GoOut).picks.map { it.kind },
        )
    }

    @Test
    fun `something already over is not recommended`() {
        val iss = pass(at("2026-12-13T16:50:00Z"))

        val report = Outlooks.of(tonight(passes = listOf(iss)), input())

        assertEquals(
            listOf(Excluded("ISS (ZARYA)", iss.peaksAt, Gate.Time, null)),
            report.excluded,
        )
    }

    @Test
    fun `a conjunction past its moment is over`() {
        val now = at("2026-12-13T17:30:00Z")
        val jupiter = conjunction(at("2026-12-13T16:30:00Z"))

        val report = Outlooks.of(tonight(events = listOf(jupiter)), input(now = now))

        assertEquals(
            listOf(Excluded(jupiter.title, jupiter.at, Gate.Time)),
            report.excluded,
        )
    }

    @Test
    fun `a comet past its best moment is over`() {
        val now = at("2026-12-13T20:00:00Z")
        val comet = comet(at = at("2026-12-13T19:00:00Z"))

        val report = Outlooks.of(tonight(comets = listOf(comet)), input(now = now))

        assertEquals(listOf(Excluded(comet.title, comet.at, Gate.Time)), report.excluded)
    }

    @Test
    fun `an event still under way is judged from now rather than its past peak`() {
        val now = at("2026-12-13T17:30:00Z")
        val jupiter = occultation(at("2026-12-13T16:50:00Z"))
        val hourly = hours("2026-12-13T16:00:00Z", 90, *IntArray(15) { 10 })

        val report = Outlooks.of(
            tonight(events = listOf(jupiter)),
            input(now = now, hourly = hourly),
        )

        assertEquals(
            listOf(Pick(PickKind.Event, jupiter.title, now, event = jupiter)),
            (report.outlook as Outlook.GoOut).picks,
        )
    }

    @Test
    fun `a solar eclipse needs no darkness, a lunar one does`() {
        val morning = at("2026-12-14T08:10:00Z")
        val sunEclipse = eclipse(EventKind.SolarEclipse, morning)
        val moonEclipse = eclipse(EventKind.LunarEclipse, morning)
        val hourly = hours("2026-12-13T16:00:00Z", *IntArray(18) { 10 })

        val report = Outlooks.of(
            tonight(events = listOf(sunEclipse, moonEclipse)),
            input(hourly = hourly),
        )

        val picked = (report.outlook as Outlook.GoOut).picks.map { it.subject }
        assertEquals(
            listOf(sunEclipse.title) to listOf(moonEclipse.title to Gate.Darkness),
            picked to report.excluded.map { it.subject to it.gate },
        )
    }

    @Test
    fun `an aurora in daylight is not dark yet`() {
        val report = Outlooks.of(
            tonight(),
            input(now = at("2026-12-13T15:00:00Z"), auroraProbability = 65),
        )

        assertEquals(
            listOf(
                Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Darkness, darkness = NOT_YET),
            ),
            report.excluded,
        )
    }

    @Test
    fun `a comet judged in twilight on a night that gets dark is set aside for the twilight`() {
        val dusk = comet(at = at("2026-12-13T17:00:00Z"))

        val report = Outlooks.of(
            tonight(comets = listOf(dusk)),
            input(now = at("2026-12-13T16:30:00Z")),
        )

        assertEquals(
            listOf(Excluded(dusk.title, dusk.at, Gate.Darkness, darkness = TWILIGHT)),
            report.excluded,
        )
    }

    @Test
    fun `a pass before dark is set aside for the twilight`() {
        val iss = pass(at("2026-12-13T16:10:00Z"))

        val report = Outlooks.of(
            tonight(passes = listOf(iss)),
            input(now = at("2026-12-13T16:00:00Z")),
        )

        assertEquals(
            listOf(Excluded(iss.name, iss.peaksAt, Gate.Darkness, darkness = TWILIGHT)),
            report.excluded,
        )
    }

    @Test
    fun `a planet past its peak is judged at the height it is then`() {
        val now = at("2026-12-14T00:30:00Z")
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T18:00:00Z"), altitude = 25.0)

        val report = Outlooks.of(
            tonight(planets = listOf(saturn)),
            input(now = now, planetAltitude = { _, t -> if (t >= now) 4.0 else 25.0 }),
        )

        assertEquals(listOf(Excluded("Saturn", now, Gate.Height, "4", kind = PickKind.Planet)), report.excluded)
    }

    /**
     * Dusk is found by a search that stops a hair short of -6 degrees, so a
     * planet judged at the very start of the dark sees the sun at -5.999997.
     * That is dark: an evening planet must not be lost to the rounding.
     */
    @Test
    fun `a planet judged at dusk is dark by the time dusk was found`() {
        val now = at("2026-12-13T15:00:00Z")
        val jupiter = planet(SkyBody.Jupiter, at("2026-12-13T15:30:00Z"))
        val duskSun: (Long) -> Double = { t -> if (t == darkFrom) -5.999997 else sun(t) }

        val report = Outlooks.of(
            tonight(planets = listOf(jupiter)),
            input(now = now, sunAltitude = duskSun),
        )

        assertEquals(emptyList<Excluded>(), report.excluded)
        val goOut = report.outlook as Outlook.GoOut
        assertEquals(listOf("Jupiter" to darkFrom), goOut.picks.map { it.subject to it.atMillis })
    }

    @Test
    fun `an aurora without a forecast is conditional`() {
        val report = Outlooks.of(
            tonight(),
            input(
                settings = settings.copy(cloudGateEnabled = false),
                now = at("2026-12-13T20:00:00Z"),
                auroraProbability = 65,
            ),
        )

        assertEquals(Outlook.AuroraNow(65, null, conditional = true), report.outlook)
    }

    @Test
    fun `stale elements never recommend a pass`() {
        val iss = pass(at("2026-12-13T19:12:00Z"))
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(passes = listOf(iss), planets = listOf(saturn)),
            input(elementsAgeMillis = 4 * DAY),
        )

        assertEquals(listOf(Excluded(iss.name, iss.peaksAt, Gate.Stale)), report.excluded)
    }

    @Test
    fun `elements of unknown age are stale`() {
        val iss = pass(at("2026-12-13T19:12:00Z"))
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(passes = listOf(iss), planets = listOf(saturn)),
            input(elementsAgeMillis = null),
        )

        assertEquals(listOf(Excluded(iss.name, iss.peaksAt, Gate.Stale)), report.excluded)
    }

    /** Clear 18:00-19:00, cloud until 22:00, clear 22:00-02:00, cloud after. */
    private val twoRuns = hours(
        "2026-12-13T16:00:00Z",
        90, 90, 10, 90, 90, 90, 10, 10, 10, 10, 90, 90, 90, 90, 90, 90,
    )
    private val firstRun = ClearSpell(
        from = at("2026-12-13T18:00:00Z"),
        until = at("2026-12-13T19:00:00Z"),
        underway = false,
        allNight = false,
    )

    @Test
    fun `the clear hours named are the ones around the first pick`() {
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(planets = listOf(saturn), clearSpell = firstRun),
            input(hourly = twoRuns),
        )

        val goOut = report.outlook as Outlook.GoOut
        assertEquals(
            at("2026-12-13T22:00:00Z") to at("2026-12-14T02:00:00Z"),
            goOut.clearFrom to goOut.clearUntil,
        )
    }

    @Test
    fun `a shower whose best hour is in a later clear run keeps its best hour`() {
        val geminids = shower(at = at("2026-12-14T01:00:00Z"))

        val report = Outlooks.of(
            tonight(events = listOf(geminids), clearSpell = firstRun),
            input(hourly = twoRuns),
        )

        assertEquals(geminids.at, (report.outlook as Outlook.GoOut).picks.single().atMillis)
    }

    @Test
    fun `a shower set aside is listed at its own time, not the time it was judged`() {
        val geminids = shower(at = at("2026-12-14T03:00:00Z"))
        val spell = ClearSpell(
            from = at("2026-12-13T22:00:00Z"),
            until = at("2026-12-14T01:00:00Z"),
            underway = false,
            allNight = false,
        )
        val hourly = hours(
            "2026-12-13T16:00:00Z",
            90, 90, 90, 90, 90, 90, 10, 10, 10, 90, 90, 90, 90, 90, 90, 90,
        )

        val report = Outlooks.of(
            tonight(events = listOf(geminids), clearSpell = spell, moon = moon(0.92)),
            input(hourly = hourly, moonAltitude = { 30.0 }),
        )

        assertEquals(
            listOf(Excluded(geminids.title, geminids.at, Gate.Moon, "92")),
            report.excluded,
        )
    }

    /** A forecast held, but for tomorrow afternoon: none of it is about tonight. */
    private val tomorrowOnly = hours("2026-12-14T12:00:00Z", 90, 90, 90, 90)

    @Test
    fun `a forecast that misses tonight is no forecast`() {
        val saturn = planet(SkyBody.Saturn, at("2026-12-13T23:00:00Z"))

        val report = Outlooks.of(
            tonight(planets = listOf(saturn), clearSpell = null),
            input(hourly = tomorrowOnly, auroraProbability = 10, auroraAgeMillis = HOUR),
        )

        assertEquals(
            Outlook.GoOut(
                picks = listOf(Pick(PickKind.Planet, "Saturn", saturn.peakAt, planet = saturn)),
                clearFrom = null,
                clearUntil = null,
                conditional = true,
            ),
            report.outlook,
        )
    }

    @Test
    fun `a forecast that misses tonight with no reading cannot tell`() {
        val report = Outlooks.of(
            tonight(clearSpell = null),
            input(hourly = tomorrowOnly),
        )

        assertEquals(
            Outlook.CannotTell(forecastHeld = false, elementsStale = false),
            report.outlook,
        )
    }

    @Test
    fun `a faint aurora needs a fully dark sky`() {
        val report = Outlooks.of(tonight(), input(auroraProbability = 45))

        assertEquals(
            listOf(
                Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Darkness, darkness = NOT_YET),
            ),
            report.excluded,
        )
    }

    @Test
    fun `a bright moon spoils a faint aurora`() {
        val report = Outlooks.of(
            tonight(moon = moon(0.92)),
            input(
                now = at("2026-12-13T20:00:00Z"),
                auroraProbability = 45,
                moonAltitude = { 30.0 },
            ),
        )

        assertEquals(
            listOf(Excluded(Outlooks.AURORA_SUBJECT, null, Gate.Moon, "92")),
            report.excluded,
        )
    }

    @Test
    fun `a faint aurora under a dark moonless sky leads`() {
        val report = Outlooks.of(
            tonight(),
            input(now = at("2026-12-13T20:00:00Z"), auroraProbability = 45),
        )

        assertEquals(Outlook.AuroraNow(45, darkUntil, conditional = false), report.outlook)
    }

    @Test
    fun `candidates come from the best passes, not every pass`() {
        val shadowed = pass(at("2026-12-13T19:12:00Z"), name = "CSS (TIANHE)", sunlitAtPeak = false)
        val unlisted = pass(at("2026-12-13T20:30:00Z"), name = "NOAA 15")

        val report = Outlooks.of(
            tonight(passes = listOf(shadowed, unlisted), bestPasses = listOf(shadowed)),
            input(),
        )

        assertTrue(
            report.outlook is Outlook.Quiet &&
                report.excluded == listOf(Excluded(shadowed.name, shadowed.peaksAt, Gate.Sunlit)),
        )
    }

    @Test
    fun `reports the threshold the reading is measured against`() {
        val report = Outlooks.of(tonight(), input(auroraProbability = 10))

        assertEquals(40 to 10, report.threshold to report.auroraProbability)
    }

    private companion object {
        val NEVER_DARK = DarknessShortfall.NeverDark
        val TWILIGHT = DarknessShortfall.Twilight
        val NOT_YET = DarknessShortfall.NotYet
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
