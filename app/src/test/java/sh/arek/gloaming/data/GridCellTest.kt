package sh.arek.gloaming.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.arek.gloaming.aurora.OvalGrid

class GridCellTest {

    @Test
    fun `reduces a position to whole degrees`() {
        assertEquals(GridCell(56, 357), GridCell.of(55.95, -3.19))
    }

    // This is the privacy claim the before-unlock option rests on: what reaches
    // device-protected storage is a cell about 111 km across, not an address.
    @Test
    fun `discards everything finer than a degree`() {
        val home = GridCell.of(55.9533, -3.1883)
        val elsewhereInTheSameCity = GridCell.of(55.9411, -3.2754)
        val severalStreetsAway = GridCell.of(55.9601, -3.1701)

        assertEquals(home, elsewhereInTheSameCity)
        assertEquals(home, severalStreetsAway)
    }

    @Test
    fun `normalises western longitudes onto the grid`() {
        assertEquals(357, GridCell.of(0.0, -3.0).longitude)
        assertEquals(1, GridCell.of(0.0, -359.0).longitude)
        assertEquals(180, GridCell.of(0.0, 180.0).longitude)
    }

    // Rounding can land on 360, which is not a column, so wrapping has to
    // happen after rounding.
    @Test
    fun `wraps a longitude that rounds up onto 360`() {
        assertEquals(0, GridCell.of(0.0, 359.6).longitude)
        assertEquals(0, GridCell.of(0.0, -0.4).longitude)
    }

    @Test
    fun `clamps at the poles`() {
        assertEquals(90, GridCell.of(90.4, 0.0).latitude)
        assertEquals(-90, GridCell.of(-95.0, 0.0).latitude)
    }

    /**
     * The cell must index the published grid identically to the raw position,
     * or a poll before unlock would answer differently from one after it.
     */
    @Test
    fun `indexes the grid the same way the raw position does`() {
        val cells = ByteArray(OvalGrid.BYTES)
        cells[(56 + 90) * OvalGrid.LONGITUDES + 357] = 64
        val grid = OvalGrid.fromPacked(cells)

        val cell = GridCell.of(55.95, -3.19)

        assertEquals(
            grid.probabilityAt(55.95, -3.19),
            grid.probabilityAt(cell.latitude.toDouble(), cell.longitude.toDouble()),
        )
        assertEquals(64, grid.probabilityAt(cell.latitude.toDouble(), cell.longitude.toDouble()))
    }

    @Test
    fun `settings without a location have no cell`() {
        assertNull(UserSettings().cell)
        assertNull(UserSettings(latitude = 55.0).cell)
        assertNull(UserSettings(longitude = -3.0).cell)
    }

    @Test
    fun `settings with a location expose its cell`() {
        assertEquals(
            GridCell(56, 357),
            UserSettings(latitude = 55.95, longitude = -3.19).cell,
        )
    }
}
