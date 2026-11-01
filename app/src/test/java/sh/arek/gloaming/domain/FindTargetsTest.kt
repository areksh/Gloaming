package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.satellites.LiveTracker
import sh.arek.gloaming.satellites.PassFinder
import sh.arek.gloaming.satellites.SatelliteElements
import sh.arek.gloaming.satellites.SatellitePass
import sh.arek.gloaming.satellites.SatellitesDocument

/**
 * What the Find tab offers, and where each thing is.
 *
 * Edinburgh, one fixed evening, and the ISS from Vallado's element set for the
 * passes. Positions are checked against [SkyPositions] itself, so a target
 * "below the horizon" is one that genuinely is.
 */
class FindTargetsTest {

    @Before
    fun forgetTheLastRise() = FindTargets.forget()

    private val latitude = 55.95
    private val longitude = -3.19
    private val now = Instant.parse("2026-12-14T20:00:00Z").toEpochMilli()

    private val iss = SatelliteElements(
        name = "ISS (ZARYA)",
        catalogue = 25544,
        epoch = 21320.51955234,
        epochMillis = 1637065689322L,
        meanMotion = 15.48582035,
        eccentricity = 0.0004694,
        inclination = 51.6447,
        raan = 309.4881,
        argPerigee = 203.6966,
        meanAnomaly = 299.8876,
        bstar = 0.000031985,
        meanMotionDot = 0.00001288,
    )
    private val elements = SatellitesDocument(schema = 1, updated = "2021-11-16T12:00:00Z", satellites = listOf(iss))

    private val issFrom = 1637065689322L
    private val pass: SatellitePass = PassFinder.passes(
        elements = iss,
        latitude = latitude,
        longitude = longitude,
        fromMillis = issFrom,
        untilMillis = issFrom + 24 * 60 * 60 * 1000L,
    ).first()

    private val planets = listOf(SkyBody.Mercury, SkyBody.Venus, SkyBody.Mars, SkyBody.Jupiter, SkyBody.Saturn)

    private fun altitude(body: SkyBody, at: Long = now) =
        SkyPositions.body(body, latitude, longitude, at).altitude

    private fun planetTonight(body: SkyBody) = PlanetTonight(
        body = body, peakAltitude = 30.0, peakAt = now, peakAzimuth = 180.0,
        magnitude = -2.0, risesAt = null, setsAt = null,
    )

    private fun tonight(
        planets: List<SkyBody> = emptyList(),
        passes: List<SatellitePass> = emptyList(),
        bestPasses: List<SatellitePass> = passes,
        constellations: List<String> = emptyList(),
        moonUp: Boolean = true,
    ) = TonightList(
        darkFrom = null,
        darkUntil = null,
        passes = passes,
        bestPasses = bestPasses,
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
        planets = planets.map(::planetTonight),
        constellations = constellations.map { name ->
            val c = Constellations.WATCHED.first { it.name == name }
            sh.arek.gloaming.astronomy.ConstellationTonight(c, 40.0, now, 180.0, false, false)
        },
        moon = if (moonUp) {
            MoonTonight(90.0, 0.5, 0.5, 0.25, null, null)
        } else {
            MoonTonight(90.0, 0.5, 0.0, 0.0, null, null)
        },
    )

    private fun fix(
        target: FindTarget,
        list: TonightList = tonight(),
        withElements: SatellitesDocument? = elements,
        at: Long = now,
    ) = FindTargets.fix(target, list, withElements, latitude, longitude, at)

    private val dark = Sky(sunAltitude = -20.0, auroraNow = false)

    private fun placed(altitude: Double?, risesAt: Long? = null) =
        TargetFix("x", altitude?.let { AltAz(it, 180.0) }, risesAt, null, "")

    /** Every body and pattern at [altitude]; every pass rising [riseIn] from [at]. */
    private fun everythingAt(altitude: Double?, at: Long = now, riseIn: Long? = null): (FindTarget) -> TargetFix? = {
        if (it is FindTarget.Pass) placed(null, riseIn?.let { lead -> at + lead }) else placed(altitude)
    }

    private fun offered(
        list: TonightList = tonight(),
        at: Long = now,
        sky: Sky = dark,
        handedIn: FindTarget.Pass? = null,
        fixOf: (FindTarget) -> TargetFix? = everythingAt(30.0),
    ) = FindTargets.available(
        list, at, handedIn,
        altitudeOf = { fixOf(it)?.position?.altitude },
        passFixOf = { fixOf(it) },
        sky = sky,
    )

    private fun offeredPasses(
        list: TonightList,
        at: Long,
        handedIn: FindTarget.Pass? = null,
    ) = offered(list, at, handedIn = handedIn, fixOf = everythingAt(30.0, at, riseIn = 60_000L))
        .filterIsInstance<FindTarget.Pass>()

    @Test
    fun `round-trips every kind of target`() {
        val targets = listOf(
            FindTarget.Planet(SkyBody.Saturn),
            FindTarget.Moon,
            FindTarget.Pattern("Ursa Major"),
            FindTarget.Pass(25544, 1792000000000L),
            FindTarget.Aurora,
        )

        targets.forEach { assertEquals(it, FindTarget.decode(it.encode())) }
        assertEquals("planet:Saturn", FindTarget.Planet(SkyBody.Saturn).encode())
        assertEquals("pass:25544:1792000000000", targets[3].encode())
    }

    @Test
    fun `decodes nothing from rubbish`() {
        listOf("planet:Pluto", "planet:Sun", "planet:Moon", "", "  ", "pass:x:y", "pass:0:5", "pass:-1:5", "pass:25544",
            "pass:1:2:3", "pattern:", "pattern:Atlantis", "comet", ":", "planet:").forEach {
            assertNull("'$it' should not decode", FindTarget.decode(it))
        }
    }

    @Test
    fun `offers the Plough first among patterns`() {
        val list = tonight(constellations = listOf("Orion", "Ursa Major", "Cygnus", "Lyra", "Cassiopeia"))

        val patterns = offered(list).filterIsInstance<FindTarget.Pattern>()

        assertEquals(
            listOf("Ursa Major", "Orion", "Cygnus", "Lyra"),
            patterns.map { it.name },
        )
        assertEquals("The Plough", fix(patterns.first())!!.label)
    }

    @Test
    fun `offers the Plough even when tonight's list does not name it`() {
        val patterns = offered().filterIsInstance<FindTarget.Pattern>()

        assertEquals(listOf("Ursa Major"), patterns.map { it.name })
    }

    @Test
    fun `places aurora low in the north`() {
        val aurora = fix(FindTarget.Aurora)!!
        val spot = aurora.position!!
        assertEquals(0.0, spot.azimuth, 0.0)
        assertEquals(10.0, spot.altitude, 0.0)
        assertEquals("North, low down", aurora.recognise.substringBefore('.'))
        assertTrue(aurora.recognise, aurora.recognise.contains("northern horizon"))
    }

    @Test
    fun `puts aurora low in the south for an observer below the equator`() {
        val aurora = FindTargets.fix(FindTarget.Aurora, tonight(), elements, -45.87, 170.5, now)!!

        assertEquals(180.0, aurora.position!!.azimuth, 0.0)
        assertEquals(10.0, aurora.position.altitude, 0.0)
        assertEquals("South, low down", aurora.recognise.substringBefore('.'))
        assertTrue(aurora.recognise, aurora.recognise.contains("southern horizon"))
    }

    @Test
    fun `a body below the horizon has no position but says when it rises`() {
        val below = planets.first { altitude(it) < -5.0 }

        val result = fix(FindTarget.Planet(below))!!

        assertNull(result.position)
        val rises = result.risesAt!!
        assertTrue(rises > now)
        assertEquals(0.0, altitude(below, rises), 0.5)
        assertEquals(
            SkyPositions.body(below, latitude, longitude, rises).azimuth,
            result.riseAzimuth!!,
            0.5,
        )
    }

    @Test
    fun `a body above the horizon has a position and no rise time`() {
        val above = planets.first { altitude(it) > 5.0 }

        val result = fix(FindTarget.Planet(above))!!

        assertEquals(altitude(above), result.position!!.altitude, 1e-9)
        assertNull(result.risesAt)
        assertNull(result.riseAzimuth)
    }

    @Test
    fun `places a pattern at its catalogued centre`() {
        val centre = Constellations.WATCHED.first { it.name == "Orion" }

        val result = fix(FindTarget.Pattern("Orion"))!!

        assertEquals(
            SkyPositions.fixed(centre.raHours, centre.dec, latitude, longitude, now),
            result.position,
        )
        assertTrue(result.position!!.altitude > 0.0)
        assertEquals(centre.starHop, result.recognise)
    }

    @Test
    fun `gives nothing for a pattern it has never heard of`() {
        assertNull(fix(FindTarget.Pattern("Atlantis")))
    }

    @Test
    fun `a pass not yet risen gives its rise time and bearing`() {
        val before = pass.risesAt - 5 * 60_000L

        val result = fix(FindTarget.Pass(pass.catalogue, pass.risesAt), tonight(passes = listOf(pass)), at = before)!!

        assertNull(result.position)
        assertEquals(pass.risesAt, result.risesAt)
        assertEquals(pass.riseAzimuth, result.riseAzimuth!!, 0.0)
        assertEquals("ISS (ZARYA)", result.label)
    }

    @Test
    fun `a pass under way is placed by propagating its elements`() {
        val during = pass.peaksAt

        val result = fix(FindTarget.Pass(pass.catalogue, pass.risesAt), tonight(passes = listOf(pass)), at = during)!!

        val expected = LiveTracker(iss, latitude, longitude).fixAt(during)
        assertEquals(expected.elevation, result.position!!.altitude, 1e-9)
        assertEquals(expected.azimuth, result.position.azimuth, 1e-9)
        assertNull(result.risesAt)
    }

    /**
     * What Tonight hands Find once the pass has risen: PassFinder leaves out a
     * pass already under way at the start of its window, so the list rebuilt
     * after rise no longer holds it. Find must still place it from the
     * elements rather than call it gone while it is overhead.
     */
    @Test
    fun `a pass under way is placed even once tonight's list has dropped it`() {
        val during = pass.peaksAt

        val result = fix(FindTarget.Pass(pass.catalogue, pass.risesAt), tonight(), at = during)!!

        val expected = LiveTracker(iss, latitude, longitude).fixAt(during)
        assertEquals(expected.elevation, result.position!!.altitude, 1e-9)
        assertEquals(expected.azimuth, result.position.azimuth, 1e-9)
        assertEquals("ISS (ZARYA)", result.label)
    }

    @Test
    fun `a pass the list does not hold is found by its rise, a few seconds either way`() {
        // A notification's rise comes off the poller's own search grid, which
        // lands a second or so from Tonight's.
        val asked = FindTarget.Pass(pass.catalogue, pass.risesAt + 3_000L)

        val result = fix(asked, tonight(), at = pass.risesAt - 5 * 60_000L)!!

        assertNull(result.position)
        assertEquals(pass.risesAt.toDouble(), result.risesAt!!.toDouble(), 1_000.0)
        assertEquals(pass.riseAzimuth, result.riseAzimuth!!, 0.5)
    }

    @Test
    fun `a pass the list does not hold is still gone once it has set`() {
        assertNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt), tonight(), at = pass.setsAt + 1))
    }

    @Test
    fun `a pass the list does not hold, with no elements either, is not known`() {
        assertNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt), tonight(), withElements = null, at = pass.peaksAt))
    }

    @Test
    fun `following a pass the list does not hold searches its elements once`() {
        val target = FindTarget.Pass(pass.catalogue, pass.risesAt)
        fix(target, tonight(), at = pass.peaksAt)
        val searched = FindTargets.passSearches

        val later = fix(target, tonight(), at = pass.peaksAt + 1_000L)!!

        assertEquals(searched, FindTargets.passSearches)
        assertNotNull(later.position)
    }

    @Test
    fun `a notification's pass is matched to tonight's own entry for it`() {
        val available = offered(tonight(passes = listOf(pass)), pass.risesAt - 60_000L, fixOf = everythingAt(30.0, pass.risesAt - 60_000L, riseIn = 60_000L))
        val tonights = FindTarget.Pass(pass.catalogue, pass.risesAt)

        assertEquals(tonights, FindTargets.matching(FindTarget.Pass(pass.catalogue, pass.risesAt + 2_000L), available))
        assertEquals(tonights, FindTargets.matching(FindTarget.Pass(pass.catalogue, pass.risesAt - 9 * 60_000L), available))
        assertNull(FindTargets.matching(FindTarget.Pass(pass.catalogue, pass.risesAt + 11 * 60_000L), available))
        assertNull(FindTargets.matching(FindTarget.Pass(pass.catalogue + 1, pass.risesAt), available))
        assertEquals(FindTarget.Moon, FindTargets.matching(FindTarget.Moon, available))
        assertNull(FindTargets.matching(FindTarget.Aurora, available))
    }

    @Test
    fun `a pass with no elements has no position and says why`() {
        val result = fix(
            FindTarget.Pass(pass.catalogue, pass.risesAt),
            tonight(passes = listOf(pass)),
            withElements = null,
            at = pass.peaksAt,
        )!!

        assertNull(result.position)
        assertTrue(result.recognise.contains("elements"))
    }

    @Test
    fun `drops a pass once it has set`() {
        val list = tonight(passes = listOf(pass))
        val underWay: (FindTarget) -> TargetFix? = { placed(20.0) }

        val during = offered(list, pass.peaksAt, fixOf = underWay)
        val after = offered(list, pass.setsAt + 1, fixOf = underWay)

        assertTrue(during.any { it is FindTarget.Pass })
        assertFalse(after.any { it is FindTarget.Pass })
        assertNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt), list, at = pass.setsAt + 1))
    }

    @Test
    fun `offers the next pass first`() {
        val later = pass.copy(risesAt = pass.risesAt + 90 * 60_000L, peaksAt = pass.peaksAt + 90 * 60_000L,
            setsAt = pass.setsAt + 90 * 60_000L)
        val list = tonight(passes = listOf(later, pass))

        val passes = offeredPasses(list, pass.risesAt - 1)

        assertEquals(listOf(pass.risesAt, later.risesAt), passes.map { it.risesAt })
    }

    private fun passAt(offsetMinutes: Int, catalogue: Int = pass.catalogue) = pass.copy(
        catalogue = catalogue,
        risesAt = pass.risesAt + offsetMinutes * 60_000L,
        peaksAt = pass.peaksAt + offsetMinutes * 60_000L,
        setsAt = pass.setsAt + offsetMinutes * 60_000L,
    )

    @Test
    fun `offers only the best passes and not every pass`() {
        val everything = (0 until 9).map { passAt(it * 90, catalogue = 1000 + it) }
        val best = listOf(everything[1], everything[4], everything[7])

        val offered = offeredPasses(tonight(passes = everything, bestPasses = best), pass.risesAt - 1)

        assertEquals(best.map { it.catalogue }, offered.map { it.catalogue })
    }

    @Test
    fun `offers the soonest six of the best passes`() {
        val best = (0 until 8).map { passAt(it * 90, catalogue = 1000 + it) }

        val offered = offeredPasses(tonight(passes = best, bestPasses = best), pass.risesAt - 1)

        assertEquals(FindTargets.MAX_PASSES, offered.size)
        assertEquals(best.take(6).map { it.risesAt }, offered.map { it.risesAt })
    }

    @Test
    fun `counts only best passes still to come toward the six`() {
        val best = (0 until 8).map { passAt(it * 90, catalogue = 1000 + it) }
        val afterTwoHaveSet = best[1].setsAt + 1

        val offered = offeredPasses(tonight(passes = best, bestPasses = best), afterTwoHaveSet)

        assertEquals(best.drop(2).take(6).map { it.catalogue }, offered.map { it.catalogue })
    }

    @Test
    fun `does not offer a best pass that has set`() {
        val offered = offeredPasses(tonight(passes = listOf(pass), bestPasses = listOf(pass)), pass.setsAt + 1)

        assertTrue(offered.isEmpty())
    }

    @Test
    fun `offers a handed-in pass outside the best ones, in rise order`() {
        val best = listOf(passAt(0, 1001), passAt(180, 1002))
        val handed = FindTarget.Pass(2000, best[0].risesAt + 90 * 60_000L)

        val offered = offeredPasses(tonight(passes = best, bestPasses = best), pass.risesAt - 1, handed)

        assertEquals(listOf(1001, 2000, 1002), offered.map { it.catalogue })
    }

    @Test
    fun `does not duplicate a handed-in pass that is one of the best`() {
        val best = listOf(passAt(0, 1001), passAt(180, 1002))
        val handed = FindTarget.Pass(1001, best[0].risesAt + 2_000L)

        val offered = offeredPasses(tonight(passes = best, bestPasses = best), pass.risesAt - 1, handed)

        assertEquals(listOf(1001, 1002), offered.map { it.catalogue })
        assertEquals(best[0].risesAt, offered[0].risesAt)
    }

    @Test
    fun `a handed-in pass beyond the six is still offered`() {
        val best = (0 until 8).map { passAt(it * 90, catalogue = 1000 + it) }
        val handed = FindTarget.Pass(1007, best[7].risesAt)

        val offered = offeredPasses(tonight(passes = best, bestPasses = best), pass.risesAt - 1, handed)

        assertEquals(7, offered.size)
        assertTrue(offered.any { it.catalogue == 1007 })
    }

    @Test
    fun `places the moon where the engine does`() {
        val result = fix(FindTarget.Moon)!!
        val expected = SkyPositions.body(SkyBody.Moon, latitude, longitude, now)

        assertEquals("The moon", result.label)
        if (expected.altitude >= 0.0) {
            assertEquals(expected, result.position)
        } else {
            assertNull(result.position)
            assertNotNull(result.risesAt)
        }
    }

    @Test
    fun `a pattern below the horizon gives its rise time and bearing`() {
        val below = Constellations.WATCHED.first {
            SkyPositions.fixed(it.raHours, it.dec, latitude, longitude, now).altitude < -5.0 &&
                SkyPositions.fixed(it.raHours, it.dec, latitude, longitude, now + 20 * 3_600_000L).altitude > 0.0
        }

        val result = fix(FindTarget.Pattern(below.name))!!

        assertNull(result.position)
        val rises = result.risesAt!!
        assertEquals(0.0, SkyPositions.fixed(below.raHours, below.dec, latitude, longitude, rises).altitude, 0.5)
        assertEquals(
            SkyPositions.fixed(below.raHours, below.dec, latitude, longitude, rises).azimuth,
            result.riseAzimuth!!,
            0.5,
        )
    }

    @Test
    fun `the rise is the first one, with the body below the horizon until then`() {
        val below = planets.first { altitude(it) < -5.0 }
        val rises = fix(FindTarget.Planet(below))!!.risesAt!!

        for (i in 0..9) {
            val sample = now + (rises - now) * i / 10
            assertTrue("below at sample $i", altitude(below, sample) < 0.0)
        }
        assertTrue(altitude(below, rises + 60_000L) > -0.1)
    }

    @Test
    fun `a pass of another satellite or eleven minutes out is not the one asked for`() {
        val list = tonight(passes = listOf(pass))

        assertNull(fix(FindTarget.Pass(pass.catalogue + 1, pass.risesAt), list, at = pass.peaksAt))
        assertNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt + 11 * 60_000L), list, at = pass.peaksAt))
        assertNotNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt + 9 * 60_000L), list, at = pass.peaksAt))
    }

    @Test
    fun `asking again for the same target does not search again`() {
        val below = planets.first { altitude(it) < -5.0 }
        val first = fix(FindTarget.Planet(below))!!
        val searched = FindTargets.scans

        val second = fix(FindTarget.Planet(below), at = now + 60_000L)!!

        assertEquals(searched, FindTargets.scans)
        assertEquals(first.risesAt, second.risesAt)
        assertEquals(first.riseAzimuth, second.riseAzimuth)
    }

    @Test
    fun `a different target or place searches afresh`() {
        val belows = planets.filter { altitude(it) < -5.0 }
        fix(FindTarget.Planet(belows[0]))
        val searched = FindTargets.scans

        fix(FindTarget.Planet(belows[1]))
        assertEquals(searched + 1, FindTargets.scans)

        FindTargets.fix(FindTarget.Planet(belows[1]), tonight(), elements, latitude + 1.0, longitude, now)
        assertEquals(searched + 2, FindTargets.scans)
    }

    @Test
    fun `remembers that nothing rises, but only for ten minutes`() {
        val never = Constellations.WATCHED.first { it.dec < -50.0 }
        val target = FindTarget.Pattern(never.name)
        assertNull(fix(target)!!.risesAt)
        val searched = FindTargets.scans

        fix(target, at = now + 9 * 60_000L)
        assertEquals(searched, FindTargets.scans)

        fix(target, at = now + 11 * 60_000L)
        assertEquals(searched + 1, FindTargets.scans)
    }

    @Test
    fun `reads a target handed in by a notification`() {
        assertEquals(FindTarget.Moon, findTargetFrom(FindTarget.Moon.encode(), setupDone = true, launchedFromHistory = false))
    }

    @Test
    fun `reads nothing from garbage or from no extra`() {
        assertNull(findTargetFrom("nonsense:42", setupDone = true, launchedFromHistory = false))
        assertNull(findTargetFrom(null, setupDone = true, launchedFromHistory = false))
    }

    @Test
    fun `drops a valid target while setup is not done`() {
        assertNull(findTargetFrom(FindTarget.Aurora.encode(), setupDone = false, launchedFromHistory = false))
    }

    /**
     * On Android 11 and below, Recents relaunches a task with the intent that
     * created it. A task begun by Find it would re-aim Find at that old target
     * on every return from Recents.
     */
    @Test
    fun `drops a valid target handed back by a launch from Recents`() {
        assertNull(findTargetFrom(FindTarget.Aurora.encode(), setupDone = true, launchedFromHistory = true))
    }

    // --- what is offered now ---

    private val noon = Sky(sunAltitude = 40.0, auroraNow = false)

    @Test
    fun `offers the moon at two degrees at noon`() {
        val list = offered(sky = noon, fixOf = everythingAt(2.0))

        assertEquals(listOf(FindTarget.Moon), list)
    }

    @Test
    fun `offers the moon whatever the sun is doing but not below the horizon`() {
        assertTrue(offered(sky = noon, fixOf = everythingAt(2.0)).contains(FindTarget.Moon))
        assertFalse(offered(fixOf = everythingAt(null)).contains(FindTarget.Moon))
        assertFalse(offered(fixOf = everythingAt(0.0)).contains(FindTarget.Moon))
    }

    @Test
    fun `offers a planet at twelve degrees only once the sun is below minus six`() {
        val saturn = FindTarget.Planet(SkyBody.Saturn)

        assertFalse(offered(sky = Sky(-3.0, false), fixOf = everythingAt(12.0)).contains(saturn))
        assertTrue(offered(sky = Sky(-7.0, false), fixOf = everythingAt(12.0)).contains(saturn))
    }

    @Test
    fun `does not offer a planet at eight degrees in full dark`() {
        assertFalse(offered(fixOf = everythingAt(8.0)).any { it is FindTarget.Planet })
        assertTrue(offered(fixOf = everythingAt(10.0)).any { it is FindTarget.Planet })
    }

    @Test
    fun `offers no planet that is below the horizon however dark`() {
        assertTrue(offered(fixOf = everythingAt(null)).none { it is FindTarget.Planet })
    }

    @Test
    fun `offers a pattern at thirty degrees only once the sun is below minus twelve`() {
        val plough = FindTarget.Pattern("Ursa Major")

        assertFalse(offered(sky = Sky(-9.0, false)).contains(plough))
        assertTrue(offered(sky = Sky(-13.0, false)).contains(plough))
    }

    @Test
    fun `offers no pattern below the horizon`() {
        assertTrue(offered(fixOf = everythingAt(null)).none { it is FindTarget.Pattern })
    }

    @Test
    fun `offers only the patterns that are up, the Plough first`() {
        val list = tonight(constellations = listOf("Orion", "Ursa Major", "Cygnus", "Lyra", "Cassiopeia"))
        val up = setOf(FindTarget.Pattern("Cygnus"), FindTarget.Pattern("Lyra"), FindTarget.Pattern("Ursa Major"))

        val patterns = offered(list, fixOf = { if (it in up) placed(30.0) else placed(null) })
            .filterIsInstance<FindTarget.Pattern>()

        assertEquals(listOf("Ursa Major", "Cygnus", "Lyra"), patterns.map { it.name })
    }

    @Test
    fun `does not offer a pass rising at four in the morning at eight in the evening`() {
        val at = now
        val list = tonight(passes = listOf(pass))
        val fixOf: (FindTarget) -> TargetFix? = { placed(null, risesAt = at + 8 * 3_600_000L) }

        assertTrue(offered(list, at, fixOf = fixOf).none { it is FindTarget.Pass })
    }

    @Test
    fun `offers a pass rising within half an hour and not one rising a minute later`() {
        val list = tonight(passes = listOf(pass))
        val at = pass.risesAt - 25 * 60_000L
        val later = pass.risesAt - 29 * 60_000L

        assertEquals(1, offered(list, at, fixOf = { placed(null, pass.risesAt) }).count { it is FindTarget.Pass })
        assertEquals(1, offered(list, pass.risesAt - 30 * 60_000L, fixOf = { placed(null, pass.risesAt) })
            .count { it is FindTarget.Pass })
        assertEquals(0, offered(list, pass.risesAt - 31 * 60_000L, fixOf = { placed(null, pass.risesAt) })
            .count { it is FindTarget.Pass })
        assertEquals(1, offered(list, later, fixOf = { placed(null, pass.risesAt) }).count { it is FindTarget.Pass })
    }

    @Test
    fun `offers a pass that is under way`() {
        val list = tonight(passes = listOf(pass))

        val result = offered(list, pass.peaksAt, fixOf = { if (it is FindTarget.Pass) placed(40.0) else null })

        assertTrue(result.any { it is FindTarget.Pass })
    }

    @Test
    fun `does not offer a pass the elements cannot place or time`() {
        val list = tonight(passes = listOf(pass))

        val result = offered(list, pass.risesAt - 60_000L, fixOf = { null })

        assertTrue(result.none { it is FindTarget.Pass })
    }

    @Test
    fun `offers a handed-in pass rising in three hours`() {
        val handed = FindTarget.Pass(2000, pass.risesAt)
        val at = pass.risesAt - 3 * 3_600_000L

        val result = offered(tonight(), at, handedIn = handed, fixOf = everythingAt(30.0, at, riseIn = 3 * 3_600_000L))

        assertTrue(result.contains(handed))
    }

    @Test
    fun `does not offer aurora when the reading is below the threshold`() {
        assertFalse(offered(sky = Sky(-20.0, auroraNow = false)).contains(FindTarget.Aurora))
    }

    @Test
    fun `offers aurora in the dark with a reading at the threshold`() {
        assertTrue(offered(sky = Sky(-13.0, auroraNow = true)).contains(FindTarget.Aurora))
    }

    @Test
    fun `does not offer aurora at minus ten even with a reading`() {
        assertFalse(offered(sky = Sky(-10.0, auroraNow = true)).contains(FindTarget.Aurora))
    }

    @Test
    fun `lists planets, the moon, patterns, passes then aurora`() {
        val list = tonight(passes = listOf(pass), constellations = listOf("Orion"))
        val at = pass.risesAt - 60_000L

        val result = offered(list, at, sky = Sky(-20.0, true), fixOf = everythingAt(30.0, at, riseIn = 60_000L))

        val kinds = result.map { it::class }
        assertEquals(
            listOf(
                FindTarget.Planet::class, FindTarget.Planet::class, FindTarget.Planet::class,
                FindTarget.Planet::class, FindTarget.Planet::class, FindTarget.Moon::class,
                FindTarget.Pattern::class, FindTarget.Pattern::class, FindTarget.Pass::class,
                FindTarget.Aurora::class,
            ),
            kinds,
        )
    }

    @Test
    fun `deciding what to offer runs no rise search`() {
        // Dusk: planets and the moon include some below the horizon.
        val sky = FindTargets.skyAt(latitude, longitude, now, null, null, threshold = 40)
        FindTargets.forget()

        FindTargets.availableAt(tonight(constellations = listOf("Orion")), elements, latitude, longitude, now, null, sky)

        assertEquals(0, FindTargets.scans)
    }

    // --- the sky ---

    private val reading = now - 60 * 60_000L

    @Test
    fun `aurora is on when a current reading reaches the threshold`() {
        val sky = FindTargets.skyAt(latitude, longitude, now, probability = 40, polledAt = reading, threshold = 40)

        assertTrue(sky.auroraNow)
    }

    @Test
    fun `aurora is off for a reading below the threshold, an old one, or none`() {
        fun on(probability: Int?, polledAt: Long?) =
            FindTargets.skyAt(latitude, longitude, now, probability, polledAt, threshold = 40).auroraNow

        assertFalse(on(39, reading))
        assertFalse(on(90, now - Outlooks.AURORA_READING_MAX_AGE_MILLIS))
        assertFalse(on(null, reading))
        assertFalse(on(90, null))
    }

    @Test
    fun `the sky carries the sun's altitude here and now`() {
        val sky = FindTargets.skyAt(latitude, longitude, now, null, null, threshold = 40)

        assertEquals(Twilight.sunAltitude(latitude, longitude, now), sky.sunAltitude, 0.0)
        assertTrue(sky.sunAltitude < -20.0)
    }

    // --- what is next ---

    private val minute = 60_000L

    /** Saturn clears ten degrees at +9h, Jupiter at +4h; the sun is below minus six from +3h. */
    private fun altitudeAt(at0: Long): (FindTarget, Long) -> Double = { target, t ->
        val h = (t - at0) / 3_600_000.0
        when (target) {
            FindTarget.Planet(SkyBody.Jupiter) -> if (h >= 4.0) 25.0 else -5.0
            FindTarget.Planet(SkyBody.Saturn) -> if (h >= 9.0) 25.0 else -5.0
            else -> -5.0
        }
    }

    private fun sunAt(at0: Long): (Long) -> Double = { t -> if ((t - at0) / 3_600_000.0 >= 3.0) -30.0 else 30.0 }

    @Test
    fun `names the first planet that will qualify and when`() {
        val at0 = now

        val next = FindTargets.nextUp(tonight(), at0, altitudeAt(at0), sunAt(at0))!!

        assertEquals(FindTarget.Planet(SkyBody.Jupiter), next.first)
        assertEquals((at0 + 4 * 3_600_000L).toDouble(), next.second.toDouble(), 5.0 * minute)
        assertTrue(next.second >= at0 + 4 * 3_600_000L)
    }

    @Test
    fun `waits for the sun as well as the height`() {
        val at0 = now
        val high: (FindTarget, Long) -> Double = { target, _ -> if (target == FindTarget.Moon) -5.0 else 25.0 }

        val next = FindTargets.nextUp(tonight(), at0, high, sunAt(at0))!!

        assertEquals((at0 + 3 * 3_600_000L).toDouble(), next.second.toDouble(), 5.0 * minute)
    }

    @Test
    fun `names a pass half an hour before it rises when that is soonest`() {
        val at0 = pass.risesAt - 5 * 3_600_000L
        val list = tonight(passes = listOf(pass))
        val nothing: (FindTarget, Long) -> Double = { _, _ -> -5.0 }

        val next = FindTargets.nextUp(list, at0, nothing, { -30.0 })!!

        assertEquals(FindTarget.Pass(pass.catalogue, pass.risesAt), next.first)
        assertEquals(pass.risesAt - FindTargets.PASS_LEAD_MILLIS, next.second)
    }

    @Test
    fun `the soonest of a body and a pass wins`() {
        val at0 = pass.risesAt - 6 * 3_600_000L
        val list = tonight(passes = listOf(pass))

        val next = FindTargets.nextUp(list, at0, altitudeAt(at0), sunAt(at0))!!

        assertEquals(FindTarget.Planet(SkyBody.Jupiter), next.first)
    }

    @Test
    fun `says nothing when nothing comes up within a day`() {
        val nothing: (FindTarget, Long) -> Double = { _, _ -> -5.0 }

        assertNull(FindTargets.nextUp(tonight(moonUp = false), now, nothing, { -30.0 }))
    }

    @Test
    fun `does not look further than a day ahead`() {
        val late: (FindTarget, Long) -> Double = { _, t -> if (t >= now + 25 * 3_600_000L) 25.0 else -5.0 }

        assertNull(FindTargets.nextUp(tonight(), now, late, { -30.0 }))
    }
}
