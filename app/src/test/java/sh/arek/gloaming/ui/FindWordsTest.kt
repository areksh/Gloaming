package sh.arek.gloaming.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.astronomy.AltAz
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.domain.CompassAccuracy
import sh.arek.gloaming.domain.CompassCheck
import sh.arek.gloaming.domain.FindTarget
import sh.arek.gloaming.domain.Guidance
import sh.arek.gloaming.domain.Guide
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.TargetFix
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/** The words on the Find screen and which rows of the night it can point at. */
class FindWordsTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private val pass = SatellitePass(
        25544, "ISS (ZARYA)", PassChannel.Iss, now + hour, 225.0, now + hour + 240_000L, 68.0, 180.0,
        now + hour + 480_000L, 135.0, 420.0, true, null, null,
    )

    private val jupiter = PlanetTonight(SkyBody.Jupiter, 46.0, now, 178.0, -2.4, null, null)

    private fun event(kind: EventKind) = SkyEvent(
        kind = kind,
        at = now,
        from = now,
        until = now + hour,
        title = "Something",
        detail = "",
        visibility = Visibility.Good,
    )

    private fun fix(
        position: AltAz? = null,
        risesAt: Long? = null,
        riseAzimuth: Double? = null,
    ) = TargetFix("Saturn", position, risesAt, riseAzimuth, "Pale yellow.")

    private fun night(
        planets: List<PlanetTonight> = emptyList(),
        moon: MoonTonight? = null,
        passes: List<SatellitePass> = emptyList(),
    ) = TonightList(
        darkFrom = now,
        darkUntil = now + 8 * hour,
        passes = passes,
        bestPasses = passes,
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
        moon = moon,
        planets = planets,
    )

    // --- which rows Find can point at ---

    @Test
    fun `points at the planet a planet row is about`() {
        assertEquals(FindTarget.Planet(SkyBody.Jupiter), findTarget(RowSubject.Planet(jupiter)))
    }

    @Test
    fun `points at the moon from a moon row`() {
        assertEquals(FindTarget.Moon, findTarget(RowSubject.Moon))
    }

    @Test
    fun `points at the pass a pass row is about, by satellite and rise`() {
        assertEquals(FindTarget.Pass(25544, now + hour), findTarget(RowSubject.Pass(pass)))
    }

    @Test
    fun `points at the moon from an event that happens to the moon`() {
        for (kind in listOf(EventKind.LunarEclipse, EventKind.Occultation, EventKind.MoonPhase, EventKind.MoonQuarter)) {
            assertEquals(kind.name, FindTarget.Moon, findTarget(RowSubject.Event(event(kind))))
        }
    }

    @Test
    fun `points at the moon from an event itself, as a row does`() {
        for (kind in EventKind.entries) {
            assertEquals(
                kind.name,
                findTarget(RowSubject.Event(event(kind))),
                findTarget(event(kind)),
            )
        }
        assertEquals(FindTarget.Moon, findTarget(event(EventKind.LunarEclipse)))
        assertNull(findTarget(event(EventKind.SolarEclipse)))
    }

    @Test
    fun `offers nothing for a row with nothing to point at`() {
        assertNull(findTarget(RowSubject.Twilight))
        assertNull(findTarget(RowSubject.Clear))
        assertNull(findTarget(RowSubject.Event(event(EventKind.MeteorShower))))
        assertNull(findTarget(RowSubject.Event(event(EventKind.SolarEclipse))))
    }

    // --- where it is ---

    @Test
    fun `says where the target is and where the phone faces`() {
        assertEquals(
            "South-east, 24 degrees up. You are facing south, 10 degrees up.",
            whereLine(fix(position = AltAz(24.0, 135.0)), AltAz(10.0, 180.0)),
        )
    }

    @Test
    fun `rounds both to the whole degree`() {
        assertEquals(
            "North-west, 25 degrees up. You are facing west, 9 degrees up.",
            whereLine(fix(position = AltAz(24.6, 310.0)), AltAz(9.4, 266.0)),
        )
    }

    @Test
    fun `says down when the phone faces below the horizon`() {
        assertEquals(
            "North, 10 degrees up. You are facing north, 12 degrees down.",
            whereLine(fix(position = AltAz(10.0, 0.0)), AltAz(-12.0, 2.0)),
        )
    }

    @Test
    fun `says one degree rather than one degrees`() {
        assertEquals(
            "East, 1 degree up. You are facing east, 1 degree down.",
            whereLine(fix(position = AltAz(1.0, 90.0)), AltAz(-1.0, 90.0)),
        )
    }

    @Test
    fun `says only where the target is before the compass has a reading`() {
        assertEquals("North, 10 degrees up.", whereLine(fix(position = AltAz(10.0, 0.0)), null))
    }

    @Test
    fun `says where and when a target below the horizon comes up`() {
        assertEquals(
            "Rises in the east at ${clock(now + hour)}. You are facing south, 10 degrees up.",
            whereLine(fix(risesAt = now + hour, riseAzimuth = 92.0), AltAz(10.0, 180.0)),
        )
    }

    @Test
    fun `says when a target rises even where it is not known where`() {
        assertEquals("Rises at ${clock(now + hour)}.", whereLine(fix(risesAt = now + hour), null))
    }

    @Test
    fun `says nothing of where a target is that cannot be placed`() {
        assertNull(whereLine(fix(), AltAz(10.0, 180.0)))
    }

    // --- the instruction ---

    @Test
    fun `guides in the words the guide gives`() {
        val guidance = Guidance(turnDegrees = 30.0, tiltDegrees = 20.0, aligned = false)

        assertEquals("Turn right, then tilt up.", findInstruction(fix(position = AltAz(30.0, 30.0)), guidance))
    }

    @Test
    fun `says there when lined up`() {
        val guidance = Guidance(turnDegrees = 1.0, tiltDegrees = 1.0, aligned = true)

        assertEquals("There.", findInstruction(fix(position = AltAz(30.0, 30.0)), guidance))
    }

    @Test
    fun `waits for the compass before guiding`() {
        assertEquals("Waiting for the compass.", findInstruction(fix(position = AltAz(30.0, 30.0)), null))
    }

    @Test
    fun `gives the rise time of a target not yet up, compass or not`() {
        val guidance = Guidance(turnDegrees = 30.0, tiltDegrees = 20.0, aligned = false)

        assertEquals("Not up until ${clock(now + hour)}.", findInstruction(fix(risesAt = now + hour), guidance))
        assertEquals("Not up until ${clock(now + hour)}.", findInstruction(fix(risesAt = now + hour), null))
    }

    @Test
    fun `says so of a target below the horizon with no rise to give`() {
        assertEquals("Cannot be pointed at now.", findInstruction(fix(), null))
    }

    @Test
    fun `says so of a target that has gone`() {
        assertEquals("That one has gone for tonight.", findInstruction(null, null))
    }

    // --- the compass ---

    @Test
    fun `says how far the compass can be trusted at each accuracy`() {
        assertEquals(
            "The compass is unreliable right now, so this could point anywhere.",
            accuracyLine(CompassAccuracy.Unreliable),
        )
        assertEquals(
            "Compass accuracy is low, so this could be 20 degrees out.",
            accuracyLine(CompassAccuracy.Low),
        )
        assertEquals(
            "Compass accuracy is medium, so this could be a few degrees out.",
            accuracyLine(CompassAccuracy.Medium),
        )
        assertEquals("Compass accuracy is high.", accuracyLine(CompassAccuracy.High))
    }

    @Test
    fun `refuses to line up on a moon too low`() {
        assertEquals(
            "The moon is too low to line up on.",
            lineUpRefusal(AltAz(CompassCheck.MIN_MOON_ALTITUDE - 0.1, 120.0)),
        )
    }

    @Test
    fun `refuses to line up on a moon too high`() {
        assertEquals("The moon is too high to line up on.", lineUpRefusal(AltAz(Guide.ZENITH_DEGREES, 120.0)))
    }

    @Test
    fun `otherwise asks to be pointed at the moon`() {
        assertEquals("Point at the moon first.", lineUpRefusal(AltAz(CompassCheck.MIN_MOON_ALTITUDE, 120.0)))
        assertEquals("Point at the moon first.", lineUpRefusal(AltAz(Guide.ZENITH_DEGREES - 0.1, 120.0)))
    }

    @Test
    fun `says how far out the compass was once lined up`() {
        assertEquals("Lined up. The compass was 3 degrees out.", lineUpResult(-3.4))
        assertEquals("Lined up. The compass was 1 degree out.", lineUpResult(0.6))
    }

    @Test
    fun `says the compass was right when lining up changed nothing worth saying`() {
        assertEquals("Lined up. The compass was already right.", lineUpResult(0.4))
    }

    // --- the chips ---

    @Test
    fun `names each kind of target on its chip`() {
        val list = night(planets = listOf(jupiter), passes = listOf(pass))

        assertEquals("Jupiter", chipLabel(FindTarget.Planet(SkyBody.Jupiter), list, now))
        assertEquals("The moon", chipLabel(FindTarget.Moon, list, now))
        assertEquals("The Plough", chipLabel(FindTarget.Pattern("Ursa Major"), list, now))
        assertEquals("Orion", chipLabel(FindTarget.Pattern("Orion"), list, now))
        assertEquals("Aurora", chipLabel(FindTarget.Aurora, list, now))
    }

    @Test
    fun `adds the rise time to the chip of something not yet up`() {
        val rising = jupiter.copy(risesAt = now + 2 * hour)
        val moon = MoonTonight(90.0, 0.5, 0.5, 0.2, risesAt = now + 3 * hour, setsAt = null)
        val list = night(planets = listOf(rising), moon = moon, passes = listOf(pass))

        assertEquals("Jupiter, ${clock(now + 2 * hour)}", chipLabel(FindTarget.Planet(SkyBody.Jupiter), list, now))
        assertEquals("The moon, ${clock(now + 3 * hour)}", chipLabel(FindTarget.Moon, list, now))
        assertEquals("ISS (ZARYA), ${clock(now + hour)}", chipLabel(FindTarget.Pass(25544, now + hour), list, now))
    }

    @Test
    fun `leaves the time off once it has risen`() {
        val rose = jupiter.copy(risesAt = now - hour)
        val list = night(planets = listOf(rose), passes = listOf(pass))

        assertEquals("Jupiter", chipLabel(FindTarget.Planet(SkyBody.Jupiter), list, now))
        assertEquals("ISS (ZARYA)", chipLabel(FindTarget.Pass(25544, now + hour), list, now + hour))
    }

    @Test
    fun `names a pass the night no longer holds by its satellite number`() {
        assertEquals("Satellite 25544", chipLabel(FindTarget.Pass(25544, now + hour), night(), now))
    }
}
