package sh.arek.gloaming.satellites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document the Worker publishes, and its mapping onto the propagator.
 *
 * Thin as it looks, this is where a pass ends up 40 seconds out. Every field is
 * a number in a specific unit and there is nothing in a wrong one to complain
 * about, so the mapping is pinned rather than trusted.
 */
class ElementsTest {

    private val document = """
        {
          "schema": 1,
          "updated": "2026-08-22T12:00:00.000Z",
          "satellites": [
            {
              "name": "ISS (ZARYA)",
              "catalogue": 25544,
              "epoch": 21320.51955234,
              "epoch_millis": 1637065689322,
              "mean_motion": 15.48582035,
              "eccentricity": 0.0004694,
              "inclination": 51.6447,
              "raan": 309.4881,
              "arg_perigee": 203.6966,
              "mean_anomaly": 299.8876,
              "bstar": 0.000031985,
              "mean_motion_dot": 0.00001288,
              "magnitude": -1.8
            }
          ]
        }
    """.trimIndent()

    /**
     * Trains arrived after this document's first release, and an install that
     * is never updated must keep reading a document that has them.
     */
    @Test
    fun `reads the trains beside the elements`() {
        val withTrain = document.replace(
            "\"satellites\": [",
            """
            "trains": [{"launch": "2026-123", "members": 22, "lead": 100234, "tail": 100255}],
            "satellites": [
            """.trimIndent(),
        )

        val parsed = Elements.parse(withTrain)

        assertEquals(1, parsed.trains.size)
        assertEquals("2026-123", parsed.trains[0].launch)
        assertEquals(22, parsed.trains[0].members)
        assertEquals(100234, parsed.trains[0].lead)
        assertEquals(100255, parsed.trains[0].tail)
    }

    @Test
    fun `reads a document published before trains existed`() {
        assertTrue(Elements.parse(document).trains.isEmpty())
    }

    @Test
    fun `reads the published document`() {
        val parsed = Elements.parse(document)

        assertEquals(1, parsed.schema)
        assertEquals("2026-08-22T12:00:00.000Z", parsed.updated)
        assertEquals(1, parsed.satellites.size)
        assertEquals("ISS (ZARYA)", parsed.satellites[0].name)
        assertEquals(-1.8, parsed.satellites[0].magnitude!!, 1e-9)
    }

    /**
     * The whole reason the app is on OMM rather than TLE: the public catalogue
     * passed 100000 in July 2026, and the brightest new objects are up there.
     */
    @Test
    fun `keeps a six-digit catalogue number`() {
        val parsed = Elements.parse(document.replace("25544", "100123"))

        assertEquals(100123, parsed.satellites[0].catalogue)
    }

    /** A field added by a later Worker must not stop an older install reading it. */
    @Test
    fun `ignores fields it does not know`() {
        val parsed = Elements.parse(document.replace("\"schema\": 1,", "\"schema\": 1, \"extra\": 7,"))

        assertEquals(1, parsed.satellites.size)
    }

    @Test
    fun `treats a missing magnitude as unknown rather than zero`() {
        val parsed = Elements.parse(document.replace("\"magnitude\": -1.8", "\"magnitude\": null"))

        assertNull(parsed.satellites[0].magnitude)
    }

    /**
     * Field for field onto the vendored element type. The names differ, the
     * units do not, and a transposition here is invisible at every other layer.
     */
    @Test
    fun `maps every element onto the propagator's own type`() {
        val data = Elements.parse(document).satellites[0].toOrbitalData()

        assertEquals("ISS (ZARYA)", data.name)
        assertEquals(25544, data.catnum)
        assertEquals(21320.51955234, data.epoch, 1e-9)
        assertEquals(15.48582035, data.meanmo, 1e-9)
        assertEquals(0.0004694, data.eccn, 1e-12)
        assertEquals(51.6447, data.incl, 1e-9)
        assertEquals(309.4881, data.raan, 1e-9)
        assertEquals(203.6966, data.argper, 1e-9)
        assertEquals(299.8876, data.meanan, 1e-9)
        assertEquals(0.000031985, data.bstar, 1e-15)
        assertEquals(0.00001288, data.ndot, 1e-15)
    }

    /**
     * The ISS is a near-Earth object and must be propagated by SGP4, not the
     * deep-space branch. The propagator picks for itself from the mean motion,
     * so a mangled element would quietly change the model as well as the orbit.
     */
    @Test
    fun `hands the ISS to the near-Earth model`() {
        val data = Elements.parse(document).satellites[0].toOrbitalData()

        assertTrue(!data.isDeepSpace)
        assertEquals(92.99, data.orbitalPeriod, 0.01)
    }
}
