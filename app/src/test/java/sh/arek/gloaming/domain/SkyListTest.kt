package sh.arek.gloaming.domain

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Constellations
import sh.arek.gloaming.satellites.PassChannel
import sh.arek.gloaming.satellites.SatellitePass

/**
 * The full list behind the Sky tab: every pass of the night by the hour it
 * rises in, and every pattern, the ones that never set apart.
 */
class SkyListTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val utc = ZoneOffset.UTC
    private val every = PassChannel.entries.toSet()

    private fun pass(
        rises: String,
        minutes: Long = 10,
        channel: PassChannel = PassChannel.Bright,
        catalogue: Int = rises.hashCode(),
    ) = SatellitePass(
        catalogue = catalogue,
        name = "SL-16 R/B",
        channel = channel,
        risesAt = at(rises),
        riseAzimuth = 200.0,
        peaksAt = at(rises) + minutes * 30_000L,
        peakElevation = 40.0,
        peakAzimuth = 180.0,
        setsAt = at(rises) + minutes * 60_000L,
        setAzimuth = 100.0,
        rangeAtPeakKm = 900.0,
        sunlitAtPeak = true,
        entersShadowAt = null,
        leavesShadowAt = null,
    )

    private fun pattern(name: String, peakAt: String, circumpolar: Boolean = false) = ConstellationTonight(
        constellation = Constellations.WATCHED.first { it.name == name },
        peakAltitude = 50.0,
        peakAt = at(peakAt),
        peakAzimuth = 180.0,
        isCircumpolar = circumpolar,
        isAtItsBest = false,
    )

    private fun night(
        passes: List<SatellitePass> = emptyList(),
        every: List<ConstellationTonight> = emptyList(),
        shortlist: List<ConstellationTonight> = emptyList(),
    ) = TonightList(
        darkFrom = at("2026-11-02T17:16:00Z"),
        darkUntil = at("2026-11-03T06:30:00Z"),
        passes = passes,
        bestPasses = emptyList(),
        events = emptyList(),
        comets = emptyList(),
        noctilucent = null,
        constellations = shortlist,
        everyConstellation = every,
    )

    private val now = at("2026-11-02T18:00:00Z")

    /** Each row says when the pass is highest, so that is the hour it sits under. */
    @Test
    fun `groups a pass under the hour it is highest in, not the hour it rises in`() {
        val late = pass("2026-11-02T18:56:00Z") // highest 19:01

        val sky = SkyList.of(night(passes = listOf(late)), now, every, utc)

        assertEquals(listOf(at("2026-11-02T19:00:00Z")), sky.passHours.map { it.first })
    }

    @Test
    fun `groups the passes still to come by the hour they peak in`() {
        val list = night(
            passes = listOf(
                pass("2026-11-02T18:24:00Z"),
                pass("2026-11-02T18:41:00Z"),
                pass("2026-11-02T19:05:00Z"),
            ),
        )

        val sky = SkyList.of(list, now, every, utc)

        assertEquals(listOf(at("2026-11-02T18:00:00Z"), at("2026-11-02T19:00:00Z")), sky.passHours.map { it.first })
        assertEquals(listOf(2, 1), sky.passHours.map { it.second.size })
    }

    @Test
    fun `folds the passes already set under earlier`() {
        val gone = pass("2026-11-02T17:40:00Z")
        val underWay = pass("2026-11-02T17:58:00Z", minutes = 7)

        val sky = SkyList.of(night(passes = listOf(gone, underWay)), now, every, utc)

        assertEquals(listOf(gone), sky.earlierPasses)
        assertEquals(listOf(at("2026-11-02T18:00:00Z") to listOf(underWay)), sky.passHours)
    }

    @Test
    fun `keeps only the chosen channels`() {
        val list = night(
            passes = listOf(
                pass("2026-11-02T18:24:00Z", channel = PassChannel.Bright),
                pass("2026-11-02T18:30:00Z", channel = PassChannel.Iss),
            ),
        )

        val sky = SkyList.of(list, now, setOf(PassChannel.Iss), utc)

        assertTrue(sky.passHours.flatMap { it.second }.all { it.channel == PassChannel.Iss })
        assertEquals(1, sky.passHours.flatMap { it.second }.size)
    }

    @Test
    fun `says when the filters hide every pass`() {
        val some = SkyList.of(night(passes = listOf(pass("2026-11-02T18:24:00Z"))), now, emptySet(), utc)
        val none = SkyList.of(night(), now, emptySet(), utc)

        assertTrue(some.filteredOut)
        assertFalse(none.filteredOut)
    }

    @Test
    fun `lists every constellation, the ones that never set apart`() {
        val orion = pattern("Orion", "2026-11-03T03:00:00Z")
        val taurus = pattern("Taurus", "2026-11-03T01:00:00Z")
        val plough = pattern("Ursa Major", "2026-11-03T05:00:00Z", circumpolar = true)
        val cassiopeia = pattern("Cassiopeia", "2026-11-02T21:00:00Z", circumpolar = true)

        val sky = SkyList.of(night(every = listOf(orion, plough, taurus, cassiopeia)), now, every, utc)

        assertEquals(listOf(taurus, orion), sky.seasonal)
        assertEquals(listOf(cassiopeia, plough), sky.alwaysUp)
    }

    /** A snapshot written before the full list was kept has only the shortlist. */
    @Test
    fun `falls back to the shortlist for a snapshot without every constellation`() {
        val orion = pattern("Orion", "2026-11-03T03:00:00Z")

        val sky = SkyList.of(night(shortlist = listOf(orion)), now, every, utc)

        assertEquals(listOf(orion), sky.seasonal + sky.alwaysUp)
    }
}
