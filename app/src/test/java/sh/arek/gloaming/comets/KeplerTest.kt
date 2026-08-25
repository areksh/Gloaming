package sh.arek.gloaming.comets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orbit solver, checked against JPL Horizons rather than against itself.
 *
 * The eclipse coverage figure in Phase 4 is the reason for that insistence: it
 * agreed with its own fixtures perfectly and disagreed with every published
 * table, because the fixtures shared the code's arithmetic and so could only
 * ever confirm it. The numbers below come from Horizons' own osculating
 * elements and its own answer for where the comet was, so a mistake here shows
 * up as a disagreement rather than as a consistent pair of wrongs.
 *
 * Three orbits, one per branch, because the branches share no arithmetic:
 * Halley is an ordinary ellipse, Hale-Bopp is the near-parabolic case that
 * breaks a naive solver, and 3I/ATLAS is unbound and needs the hyperbolic form.
 */
class KeplerTest {

    /**
     * Horizons quotes true anomaly to eleven figures and the app needs
     * arcminutes, so a millionth of a degree is far tighter than anything the
     * app depends on and still loose enough not to break on a rounding change.
     */
    private val degreeTolerance = 1e-6

    private val auTolerance = 1e-8

    // 1P/Halley, osculating at TT JD 2446531.5 - the 1986 apparition.
    private val halley = Orbit(
        perihelionAu = 0.5871097150372122,
        eccentricity = 0.9672794335097039,
        inclinationDegrees = 162.2423847767351,
        nodeDegrees = 58.86030972914597,
        perihelionArgumentDegrees = 111.8665705152629,
        perihelionJdTt = 2446470.959040325,
    )

    // C/1995 O1 (Hale-Bopp), osculating at TT JD 2450536.5, four days before
    // perihelion. e = 0.995 is where an ellipse stops behaving like one.
    private val haleBopp = Orbit(
        perihelionAu = 0.9141695106942098,
        eccentricity = 0.9951306139219294,
        inclinationDegrees = 89.43017886941958,
        nodeDegrees = 282.470593486876,
        perihelionArgumentDegrees = 130.5872422543277,
        perihelionJdTt = 2450539.633093244,
    )

    // 3I/ATLAS, osculating at TT JD 2461277.5. Interstellar, so e is far above
    // one and the ellipse formulae do not merely lose accuracy, they divide by
    // a negative semi-major axis.
    private val atlas3i = Orbit(
        perihelionAu = 1.35201178145392,
        eccentricity = 6.123869212164036,
        inclinationDegrees = 175.2401369163986,
        nodeDegrees = 323.0244810260886,
        perihelionArgumentDegrees = 128.7971146707141,
        perihelionJdTt = 2460977.949768671,
    )

    private fun degrees(radians: Double) = (Math.toDegrees(radians) + 360.0) % 360.0

    // --- against an independent ephemeris ---

    @Test
    fun `matches Horizons for an ordinary ellipse`() {
        val point = Kepler.solve(halley, 2446531.5)

        assertEquals(1.332019472366668, point.radiusAu, auTolerance)
        assertEquals(97.89650874935323, degrees(point.trueAnomaly), degreeTolerance)
    }

    @Test
    fun `matches Horizons for a near-parabolic comet`() {
        val point = Kepler.solve(haleBopp, 2450536.5)

        assertEquals(0.9158967742294272, point.radiusAu, auTolerance)
        assertEquals(355.016020773412, degrees(point.trueAnomaly), degreeTolerance)
    }

    @Test
    fun `matches Horizons for an unbound interstellar orbit`() {
        val point = Kepler.solve(atlas3i, 2461277.5)

        assertEquals(10.572208221442732, point.radiusAu, 1e-7)
        assertEquals(90.83248381181518, degrees(point.trueAnomaly), degreeTolerance)
    }

    // --- the parabolic branch ---

    /**
     * A true parabola, checked against Horizons rather than against the
     * neighbouring branches.
     *
     * Horizons never quotes an eccentricity of exactly one for a real comet, so
     * this uses its facility for user-supplied elements: it is given e = 1 and
     * Hale-Bopp's remaining elements, and asked where that object would be.
     * That makes it a genuinely independent answer for Barker's equation, which
     * continuity against the other branches on its own cannot provide.
     *
     * Three epochs either side of perihelion, because the cubic root Barker's
     * equation takes is the one part of this file whose sign depends on which
     * way the comet is going.
     */
    @Test
    fun `matches Horizons for a parabolic orbit`() {
        val parabola = haleBopp.copy(eccentricity = 1.0)

        assertEquals(1.9214611584195256, Kepler.solve(parabola, 2450436.5).radiusAu, 1e-8)
        assertEquals(0.9159052180920572, Kepler.solve(parabola, 2450536.5).radiusAu, 1e-8)
        assertEquals(1.8417804426020512, Kepler.solve(parabola, 2450636.5).radiusAu, 1e-8)

        assertEquals(-92.77788404662002, Math.toDegrees(Kepler.solve(parabola, 2450436.5).trueAnomaly), degreeTolerance)
        assertEquals(-4.990026868399943, Math.toDegrees(Kepler.solve(parabola, 2450536.5).trueAnomaly), degreeTolerance)
        assertEquals(90.4181516565343, Math.toDegrees(Kepler.solve(parabola, 2450636.5).trueAnomaly), degreeTolerance)
    }

    // --- the branch boundary ---

    /**
     * The three branches are separately derived and meet at e = 1, so they have
     * to agree there.
     *
     * The eccentricities have to sit clearly *outside* the parabolic band or
     * this test is worthless: pick them too close to one and all three calls
     * take the parabolic branch, and the test compares that branch against
     * itself and can never fail. An earlier version of this test did exactly
     * that, and passed happily with the constant in Barker's equation altered.
     * A hundred-thousandth is a hundred times the band, so the ellipse and
     * hyperbola formulae genuinely run.
     *
     * The tolerance is then the real difference between a parabola and its
     * neighbours over this span, which is about four hundred-thousandths of an
     * AU. A wrong constant in Barker's equation moves the answer by of order an
     * AU, so there is no risk of the tolerance hiding one.
     */
    @Test
    fun `agrees across the parabolic boundary`() {
        val nudge = 1e-5

        for (days in listOf(-400.0, -100.0, -10.0, -0.5, 0.5, 10.0, 100.0, 400.0)) {
            val at = haleBopp.perihelionJdTt + days
            val ellipse = Kepler.solve(haleBopp.copy(eccentricity = 1.0 - nudge), at)
            val parabola = Kepler.solve(haleBopp.copy(eccentricity = 1.0), at)
            val hyperbola = Kepler.solve(haleBopp.copy(eccentricity = 1.0 + nudge), at)

            assertEquals("r at $days days", parabola.radiusAu, ellipse.radiusAu, 2e-4)
            assertEquals("r at $days days", parabola.radiusAu, hyperbola.radiusAu, 2e-4)
            assertEquals("v at $days days", parabola.trueAnomaly, ellipse.trueAnomaly, 5e-5)
            assertEquals("v at $days days", parabola.trueAnomaly, hyperbola.trueAnomaly, 5e-5)
        }
    }

    /**
     * The guard on the test above: these three eccentricities must take three
     * different code paths. If the band ever widens past the nudge, the
     * continuity test silently stops testing anything, so the disagreement
     * between the branches is asserted to be real but small.
     */
    @Test
    fun `the boundary test straddles genuinely different branches`() {
        val at = haleBopp.perihelionJdTt + 400.0
        val ellipse = Kepler.solve(haleBopp.copy(eccentricity = 1.0 - 1e-5), at).radiusAu
        val parabola = Kepler.solve(haleBopp.copy(eccentricity = 1.0), at).radiusAu
        val hyperbola = Kepler.solve(haleBopp.copy(eccentricity = 1.0 + 1e-5), at).radiusAu

        assertTrue("ellipse took the parabolic branch", abs(ellipse - parabola) > 1e-9)
        assertTrue("hyperbola took the parabolic branch", abs(hyperbola - parabola) > 1e-9)
        assertTrue("the ellipse should fall short of the parabola", ellipse < parabola)
        assertTrue("the hyperbola should overshoot it", hyperbola > parabola)
    }

    // --- properties every orbit has ---

    @Test
    fun `puts every comet at its perihelion distance at perihelion`() {
        for (orbit in listOf(halley, haleBopp, atlas3i, haleBopp.copy(eccentricity = 1.0))) {
            val point = Kepler.solve(orbit, orbit.perihelionJdTt)

            assertEquals(orbit.perihelionAu, point.radiusAu, 1e-12)
            assertEquals(0.0, point.trueAnomaly, 1e-9)
        }
    }

    /**
     * An orbit is a conic section and a conic is symmetric about its axis, so
     * the comet is the same distance out the same number of days either side of
     * perihelion and exactly as far round in the opposite direction.
     */
    @Test
    fun `is symmetric about perihelion`() {
        for (orbit in listOf(halley, haleBopp, atlas3i, haleBopp.copy(eccentricity = 1.0))) {
            for (days in listOf(1.0, 30.0, 365.0)) {
                val before = Kepler.solve(orbit, orbit.perihelionJdTt - days)
                val after = Kepler.solve(orbit, orbit.perihelionJdTt + days)

                assertEquals(before.radiusAu, after.radiusAu, 1e-9)
                assertEquals(-before.trueAnomaly, after.trueAnomaly, 1e-9)
            }
        }
    }

    /**
     * Whatever route the solver took, the answer has to satisfy the equation it
     * was solving. Swept across the whole range of eccentricity the MPC file
     * actually contains, including the awkward band either side of one.
     */
    @Test
    fun `returns an eccentric anomaly that solves Kepler's equation`() {
        for (e in listOf(0.0, 0.2, 0.5, 0.9, 0.99, 0.999, 0.9999, 0.999999)) {
            for (m in listOf(-3.0, -0.5, -1e-4, 0.0, 1e-8, 1e-4, 0.5, 1.0, 3.0, 6.28)) {
                val eccentric = Kepler.eccentricAnomaly(m, e)
                val wrapped = ((m + PI) % (2 * PI) + 2 * PI) % (2 * PI) - PI

                assertEquals(
                    "e=$e M=$m",
                    wrapped,
                    eccentric - e * sin(eccentric),
                    1e-10,
                )
            }
        }
    }

    @Test
    fun `returns a hyperbolic anomaly that solves the hyperbolic equation`() {
        for (e in listOf(1.001, 1.1, 2.0, 6.2)) {
            for (m in listOf(-50.0, -1.0, -1e-6, 0.0, 1e-6, 1.0, 50.0, 500.0)) {
                val h = Kepler.hyperbolicAnomaly(m, e)

                assertEquals("e=$e M=$m", m, e * kotlin.math.sinh(h) - h, 1e-8 * maxOf(1.0, abs(m)))
            }
        }
    }

    /**
     * Every comet in the MPC file has to produce a finite answer. A solver that
     * returns NaN for one row would take the whole list down with it, and the
     * file contains orbits from every era of observation including some with
     * eccentricities no real comet has.
     */
    @Test
    fun `stays finite across every eccentricity the file contains`() {
        for (e in listOf(0.0, 0.1, 0.9, 0.99, 1.0, 1.0001, 1.5, 3.4, 6.2)) {
            for (days in listOf(-2000.0, -1.0, 0.0, 1.0, 2000.0)) {
                val point = Kepler.solve(
                    haleBopp.copy(eccentricity = e),
                    haleBopp.perihelionJdTt + days,
                )

                assertTrue("e=$e days=$days -> ${point.radiusAu}", point.radiusAu.isFinite())
                assertTrue("e=$e days=$days", point.radiusAu >= 0.0)
                assertTrue("e=$e days=$days", point.trueAnomaly.isFinite())
                assertTrue("e=$e days=$days", abs(cos(point.trueAnomaly)) <= 1.0)
            }
        }
    }
}
