package sh.arek.gloaming.astronomy

import java.time.Instant
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stick figures, checked against things that do not depend on the table.
 *
 * The figures were typed in by hand, so the checks here are the ones a typo
 * fails: a star outside the constellation it is drawn in (the library's own
 * IAU boundaries say so), a star too far from the pattern's centre to belong
 * to it (hours entered as degrees, a dropped minus sign), and a line to a
 * star that is not there. Which stars make the shape is judgement, recorded
 * in ADR 0017, and no test can check that.
 */
class ConstellationFiguresTest {

    private fun figure(name: String) =
        ConstellationFigures.of(Constellations.WATCHED.first { it.name == name })!!

    /** Degrees between two points given in right ascension hours and declination degrees. */
    private fun apart(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val d1 = Math.toRadians(dec1)
        val d2 = Math.toRadians(dec2)
        val dRa = Math.toRadians((ra1 - ra2) * 15.0)
        val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    // --- the table against the patterns ---

    @Test
    fun `gives every watched pattern a figure`() {
        for (row in Constellations.WATCHED) {
            assertNotNull(row.name, ConstellationFigures.of(row))
        }
        assertEquals(
            Constellations.WATCHED.map { it.name }.toSet(),
            ConstellationFigures.FIGURES.keys,
        )
    }

    /** The library's IAU boundaries are an independent check on every position typed in. */
    @Test
    fun `puts every star inside the constellation it is drawn in`() {
        for ((name, figure) in ConstellationFigures.FIGURES) {
            for (star in figure.stars) {
                assertEquals(
                    "$name ${star.id}",
                    star.borrowedFrom ?: name,
                    Constellations.containing(star.raHours, star.dec),
                )
            }
        }
    }

    /** Two figures share a star with a neighbour, and only two. */
    @Test
    fun `borrows only Elnath and Alpheratz`() {
        val borrowed = ConstellationFigures.FIGURES.flatMap { (name, figure) ->
            figure.stars.mapNotNull { star -> star.borrowedFrom?.let { name to it } }
        }.toSet()

        assertEquals(setOf("Auriga" to "Taurus", "Pegasus" to "Andromeda"), borrowed)
    }

    /** Hours read as degrees, or a lost minus sign, puts a star far outside its pattern. */
    @Test
    fun `keeps every star within twice the pattern's extent of its centre`() {
        for (row in Constellations.WATCHED) {
            for (star in figure(row.name).stars) {
                val distance = apart(row.raHours, row.dec, star.raHours, star.dec)
                assertTrue(
                    "${row.name} ${star.id} is $distance degrees out",
                    distance <= 2 * row.extentDegrees,
                )
            }
        }
    }

    @Test
    fun `draws only stars it has and draws every star it has`() {
        for ((name, figure) in ConstellationFigures.FIGURES) {
            val ids = figure.stars.map { it.id }
            assertEquals("$name ids", ids.size, ids.toSet().size)
            for (stroke in figure.strokes) {
                assertTrue("$name stroke $stroke", stroke.size >= 2)
                for (id in stroke) assertTrue("$name stroke names $id", id in ids)
            }
            val drawn = figure.strokes.flatten().toSet()
            for (id in ids) assertTrue("$name never draws $id", id in drawn)
        }
    }

    @Test
    fun `names the brightest star where the table says it is`() {
        for (row in Constellations.WATCHED) {
            val named = figure(row.name).stars.filter { it.name == row.brightestStar }
            assertEquals("${row.name} ${row.brightestStar}", 1, named.size)
            assertEquals(row.brightestStar, row.brightestMagnitude, named.single().magnitude, 0.15)
        }
    }

    @Test
    fun `keeps positions and magnitudes in range`() {
        for ((name, figure) in ConstellationFigures.FIGURES) {
            for (star in figure.stars) {
                assertTrue("$name ${star.id} ra", star.raHours >= 0.0 && star.raHours < 24.0)
                assertTrue("$name ${star.id} dec", star.dec in -90.0..90.0)
                assertTrue("$name ${star.id} magnitude", star.magnitude in -1.5..5.0)
            }
        }
    }

    /** Plain ASCII, like everything else. */
    @Test
    fun `writes plain ascii`() {
        for ((name, figure) in ConstellationFigures.FIGURES) {
            for (star in figure.stars) {
                assertTrue("$name ${star.id}", star.id.all { it.code in 32..126 })
                star.name?.let { assertTrue("$name $it", it.all { c -> c.code in 32..126 }) }
            }
        }
    }

    @Test
    fun `turns strokes into segments`() {
        assertEquals(listOf(0 to 1), figure("Canis Minor").segments())
    }

    // --- placed on the sky ---

    /** Orion at its highest on a January night, flattened around its centre. */
    private fun orionAtItsHighest(latitude: Double, longitude: Double, iso: String): Map<String, PlanePoint> {
        val orion = Constellations.WATCHED.first { it.name == "Orion" }
        val night = Twilight.nightNearest(latitude, longitude, Instant.parse(iso).toEpochMilli())!!
        val at = Constellations.tonight(latitude, longitude, night)
            .first { it.constellation.name == "Orion" }.peakAt
        val centre = SkyPositions.fixed(orion.raHours, orion.dec, latitude, longitude, at)
        val placed = figure("Orion").placedAt(latitude, longitude, at)
        return placed.stars.zip(placed.positions)
            .associate { (star, position) -> star.name.orEmpty() to SkyProjection.around(centre, position)!! }
    }

    /** From the north, facing south: Betelgeuse up and to the left, Rigel down and to the right. */
    @Test
    fun `stands orion upright from scotland`() {
        val stars = orionAtItsHighest(55.95, -3.19, "2027-01-15T22:00:00Z")

        assertTrue(stars.getValue("Betelgeuse").y > stars.getValue("Rigel").y)
        assertTrue(stars.getValue("Betelgeuse").x < stars.getValue("Rigel").x)
    }

    /** From the south, facing north, the hunter stands on his head. */
    @Test
    fun `turns orion over from sydney`() {
        val stars = orionAtItsHighest(-33.87, 151.21, "2027-01-15T11:00:00Z")

        assertTrue(stars.getValue("Betelgeuse").y < stars.getValue("Rigel").y)
        assertTrue(stars.getValue("Betelgeuse").x > stars.getValue("Rigel").x)
    }

    @Test
    fun `places every star where SkyPositions puts it`() {
        val at = Instant.parse("2027-01-15T22:00:00Z").toEpochMilli()
        val placed = figure("Orion").placedAt(55.95, -3.19, at)

        assertEquals(placed.stars.size, placed.positions.size)
        for ((star, position) in placed.stars.zip(placed.positions)) {
            assertEquals(star.id, SkyPositions.fixed(star.raHours, star.dec, 55.95, -3.19, at), position)
        }
        assertEquals(figure("Orion").segments(), placed.segments)
    }
}
