package sh.arek.gloaming.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyPositions
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

        val patterns = FindTargets.available(list, auroraKnown = false, nowMillis = now)
            .filterIsInstance<FindTarget.Pattern>()

        assertEquals(
            listOf("Ursa Major", "Orion", "Cygnus", "Lyra"),
            patterns.map { it.name },
        )
        assertEquals("The Plough", fix(patterns.first())!!.label)
    }

    @Test
    fun `offers the Plough even when tonight's list does not name it`() {
        val patterns = FindTargets.available(tonight(), auroraKnown = false, nowMillis = now)
            .filterIsInstance<FindTarget.Pattern>()

        assertEquals(listOf("Ursa Major"), patterns.map { it.name })
    }

    @Test
    fun `offers aurora only when it is known about, and puts it low in the north`() {
        assertFalse(FindTargets.available(tonight(), false, now).contains(FindTarget.Aurora))
        assertTrue(FindTargets.available(tonight(), true, now).contains(FindTarget.Aurora))

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
    fun `offers the moon only when it is up some of the night`() {
        assertTrue(FindTargets.available(tonight(moonUp = true), false, now).contains(FindTarget.Moon))
        assertFalse(FindTargets.available(tonight(moonUp = false), false, now).contains(FindTarget.Moon))
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
        val available = FindTargets.available(tonight(passes = listOf(pass)), false, pass.risesAt - 60_000L)
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

        val during = FindTargets.available(list, false, pass.peaksAt)
        val after = FindTargets.available(list, false, pass.setsAt + 1)

        assertTrue(during.any { it is FindTarget.Pass })
        assertFalse(after.any { it is FindTarget.Pass })
        assertNull(fix(FindTarget.Pass(pass.catalogue, pass.risesAt), list, at = pass.setsAt + 1))
    }

    @Test
    fun `offers the next pass first`() {
        val later = pass.copy(risesAt = pass.risesAt + 90 * 60_000L, peaksAt = pass.peaksAt + 90 * 60_000L,
            setsAt = pass.setsAt + 90 * 60_000L)
        val list = tonight(passes = listOf(later, pass))

        val passes = FindTargets.available(list, false, pass.risesAt - 1).filterIsInstance<FindTarget.Pass>()

        assertEquals(listOf(pass.risesAt, later.risesAt), passes.map { it.risesAt })
    }

    private fun passAt(offsetMinutes: Int, catalogue: Int = pass.catalogue) = pass.copy(
        catalogue = catalogue,
        risesAt = pass.risesAt + offsetMinutes * 60_000L,
        peaksAt = pass.peaksAt + offsetMinutes * 60_000L,
        setsAt = pass.setsAt + offsetMinutes * 60_000L,
    )

    private fun offeredPasses(list: TonightList, at: Long, handedIn: FindTarget.Pass? = null) =
        FindTargets.available(list, false, at, handedIn).filterIsInstance<FindTarget.Pass>()

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
}
