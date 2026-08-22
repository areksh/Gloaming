package sh.arek.gloaming.satellites

import com.rtbishop.look4sat.core.domain.predict.EARTH_RADIUS
import com.rtbishop.look4sat.core.domain.predict.FLAT_FACT
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendored propagator against Vallado's SGP4 verification vectors.
 *
 * This is the release gate for pass prediction. A propagator that is subtly
 * wrong does not announce itself: it produces passes that look entirely
 * plausible and arrive forty seconds late, which is the difference between
 * standing outside at the right moment and missing it.
 *
 * The vectors are the official test data accompanying Vallado, Crawford,
 * Hujsak and Kelso, "Revisiting Spacetrack Report #3" (AIAA 2006-6753), taken
 * from the copy distributed with `python-sgp4` because celestrak.org was
 * unreachable. `SGP4-VER.TLE` holds the elements; `tcppver.out` holds the
 * expected TEME position and velocity at each offset from epoch.
 *
 * Three deliberate limits, each of them a scoping decision rather than a
 * concession:
 *
 * - **Position only.** The propagator exposes a geodetic position, not the raw
 *   state vector, so the velocity columns cannot be checked. Position is what
 *   moves a pass time, so it is the part that matters here.
 * - **Near-Earth only.** The app predicts passes for low-orbit objects from
 *   CelesTrak's `stations` and `visual` groups, every one of which takes the
 *   SGP4 branch. This port descends from the original Spacetrack Report #3
 *   rather than from Vallado's 2006 revision, and the Lyddane fix that revision
 *   introduced lands squarely in SDP4, so gating on the deep-space cases would
 *   be asserting that a 1980 algorithm reproduces a 2006 one. Measured
 *   2026-08-22, the deep-space cases disagree by anything from 9 metres to
 *   135,638 km, and one produces NaN.
 * - **Perigee above 156 km.** Below that the drag model applies its s4
 *   modification, which is another place the two revisions part company. Three
 *   cases sit down there and are left out: COSMOS 2405 at a 129 km perigee, out
 *   by 7.8 km, and two already inside the atmosphere at 80 km and -49 km. An
 *   object with a perigee that low has days to live and appears in no group the
 *   app reads.
 *
 * Six cases remain, and within that scope the agreement is close. Measured on
 * the same date, the four in normal flight came within 20 metres of Vallado -
 * about four of which is this test rounding its instants to the millisecond -
 * and the two decaying ones within 320 metres. At orbital speed 320 metres is
 * 40 milliseconds of pass time.
 */
class Sgp4VerificationTest {

    /**
     * How far the reconstructed position may sit from Vallado's.
     *
     * Set against what the app needs rather than against machine precision. A
     * satellite in low orbit covers about 7.7 km every second, so this is
     * comfortably inside a one-second pass time - the tolerance the manual
     * check against Heavens-Above is made to.
     */
    private val toleranceKm = 0.5

    /**
     * Below this the drag model switches to its s4 modification, where the two
     * SGP4 revisions differ. Nothing the app predicts for orbits this low.
     */
    private val minimumPerigeeKm = 156.0

    private data class Expected(val tsince: Double, val x: Double, val y: Double, val z: Double)

    private data class Case(val data: OrbitalData, val expected: List<Expected>)

    /** Anywhere will do: the satellite's own position does not depend on it. */
    private val observer = GeoPos(0.0, 0.0, 0.0)

    /**
     * Guards the gate below from passing on an empty list.
     *
     * A fixture that failed to load, or a parser that quietly matched nothing,
     * would turn the release gate into a test that asserts nothing at all -
     * and it would go on reporting success for ever.
     */
    @Test
    fun `loads every verification case from the fixtures`() {
        val cases = cases()

        assertEquals(33, cases.size)
        assertTrue(cases.all { it.expected.isNotEmpty() })
        assertEquals(6, cases.count { inScope(it.data) })
    }

    @Test
    fun `matches Vallado's vectors for every near-Earth case in normal flight`() {
        val failures = mutableListOf<String>()
        var worst = 0.0

        for (case in cases().filter { inScope(it.data) }) {
            val error = maximumErrorKm(case)
            worst = maxOf(worst, error)
            if (error > toleranceKm) {
                failures += "${case.data.catnum}: ${"%.3f".format(error)} km"
            }
        }

        assertTrue(
            "worst error ${"%.3f".format(worst)} km, tolerance $toleranceKm km; over it: $failures",
            failures.isEmpty(),
        )
    }

    /** The regime the app actually predicts in: low orbit, not scraping the air. */
    private fun inScope(data: OrbitalData): Boolean =
        !data.isDeepSpace && perigeeKm(data) >= minimumPerigeeKm

    /**
     * Perigee height, by the same approximation the propagator's own
     * `willBeSeen` uses.
     */
    private fun perigeeKm(data: OrbitalData): Double {
        val semiMajorAxis = 331.25 * exp(ln(1440.0 / data.meanmo) * (2.0 / 3.0))
        return semiMajorAxis * (1.0 - data.eccn) - EARTH_RADIUS
    }

    private fun maximumErrorKm(case: Case): Double {
        val orbital = case.data.getObject()
        var worst = 0.0

        for (expected in case.expected) {
            val time = timeAt(case.data.epoch, expected.tsince)
            val position = orbital.getPosition(observer, time)

            // The propagator hands back a geodetic position rather than the
            // state vector it holds, so the vector is reconstructed by
            // inverting the same conversion: theta is the ECI longitude, and
            // altitude and latitude give the radius. Exact to the millimetre,
            // and it keeps the test on the public API.
            val e2 = FLAT_FACT * (2.0 - FLAT_FACT)
            val latitude = position.latitude
            if (abs(cos(latitude)) < 1e-9) continue
            val c = 1.0 / sqrt(1.0 - e2 * sin(latitude).pow(2))
            val radius = (EARTH_RADIUS * c + position.altitude) * cos(latitude)
            val x = radius * cos(position.theta)
            val y = radius * sin(position.theta)
            val z = radius * tan(latitude) - EARTH_RADIUS * c * e2 * sin(latitude)

            val error = sqrt(
                (x - expected.x).pow(2) + (y - expected.y).pow(2) + (z - expected.z).pow(2),
            )
            worst = maxOf(worst, error)
        }
        return worst
    }

    /**
     * The absolute time at which the propagator sees a given offset from epoch.
     *
     * Inverts the propagator's own arithmetic - daynum from 31 Dec 1979, then
     * Julian - so that the offsets in the vectors land exactly where Vallado
     * put them. Rounding to the millisecond puts a floor of about four metres
     * on any agreement, which is far inside the tolerance.
     */
    private fun timeAt(epoch: Double, tsinceMinutes: Double): Long {
        val julianEpoch = julianDateOfEpoch(epoch)
        val julian = julianEpoch + tsinceMinutes / 1440.0
        return (315446400000L + ((julian - 2444238.5) * 86400000.0)).toLong()
    }

    private fun julianDateOfEpoch(epoch: Double): Double {
        var year = floor(epoch * 1e-3)
        val day = (epoch * 1e-3 - year) * 1000.0
        year = if (year < 57) year + 2000 else year + 1900
        val aYear = year - 1
        val a = floor(aYear / 100).toLong()
        val b = 2 - a + a / 4
        return floor(365.25 * aYear).toLong() + (30.6001 * 14).toLong() + 1720994.5 + b + day
    }

    /** Reads the two fixtures and pairs them up by their order in the files. */
    private fun cases(): List<Case> {
        val elements = readElements()
        val expected = readExpected()
        assertTrue(
            "fixtures disagree: ${elements.size} element sets, ${expected.size} result blocks",
            elements.size == expected.size,
        )
        return elements.zip(expected) { data, block ->
            assertTrue("out of step at ${data.catnum}", data.catnum == block.first)
            Case(data, block.second)
        }
    }

    /**
     * A TLE reader for the fixture, and only for the fixture.
     *
     * The app itself never parses TLE - it cannot, since the format has no room
     * for a 6-digit catalogue number - but Vallado's vectors are distributed in
     * it, so the test needs one. Test code, deliberately not production code.
     */
    private fun readElements(): List<OrbitalData> {
        val lines = resource("vallado/SGP4-VER.TLE").lines().filter { it.isNotBlank() }
        val elements = mutableListOf<OrbitalData>()

        for (i in lines.indices) {
            val first = lines[i]
            if (!first.startsWith("1 ")) continue
            val second = lines.getOrNull(i + 1) ?: continue
            if (!second.startsWith("2 ")) continue

            // Columns are fixed width, as the format defines them. The
            // exponential fields carry an implied leading decimal point.
            val mantissa = first.substring(53, 59).trim().toDouble() / 1e5
            val exponent = first.substring(59, 61).trim().toDouble()
            elements += OrbitalData(
                name = "sat ${first.substring(2, 7).trim()}",
                epoch = first.substring(18, 32).trim().toDouble(),
                meanmo = second.substring(52, 63).trim().toDouble(),
                eccn = "0.${second.substring(26, 33).trim()}".toDouble(),
                incl = second.substring(8, 16).trim().toDouble(),
                raan = second.substring(17, 25).trim().toDouble(),
                argper = second.substring(34, 42).trim().toDouble(),
                meanan = second.substring(43, 51).trim().toDouble(),
                catnum = first.substring(2, 7).trim().toInt(),
                bstar = mantissa * 10.0.pow(exponent),
                ndot = first.substring(33, 43).trim().toDouble(),
            )
        }
        return elements
    }

    /** Each block is a catalogue number followed by one row per offset. */
    private fun readExpected(): List<Pair<Int, List<Expected>>> {
        val blocks = mutableListOf<Pair<Int, MutableList<Expected>>>()

        for (line in resource("vallado/tcppver.out").lines()) {
            val fields = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.isEmpty()) continue

            if (fields.size == 2 && fields[1] == "xx") {
                blocks += fields[0].toInt() to mutableListOf()
                continue
            }
            if (fields.size < 7) continue

            blocks.lastOrNull()?.second?.add(
                Expected(
                    tsince = fields[0].toDouble(),
                    x = fields[1].toDouble(),
                    y = fields[2].toDouble(),
                    z = fields[3].toDouble(),
                ),
            )
        }
        return blocks.map { it.first to it.second.toList() }
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader().readText()
}
