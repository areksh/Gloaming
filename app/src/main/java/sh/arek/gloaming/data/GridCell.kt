package sh.arek.gloaming.data

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * An observer position reduced to the aurora grid's own resolution.
 *
 * The grid lookup rounds to whole degrees anyway, so this throws away nothing
 * the alerting needs. It matters because this is the only location data that
 * goes into device-protected storage for before-first-unlock alerting, and a
 * whole degree is roughly 111 km of latitude - a region, not an address.
 */
@Serializable
data class GridCell(val latitude: Int, val longitude: Int) {

    companion object {
        /**
         * Reduces a precise position to its grid cell.
         *
         * Longitude is normalised into 0-359 to match the published grid, and
         * rounding happens before wrapping: 359.6 rounds to 360, which is not a
         * column.
         */
        fun of(latitude: Double, longitude: Double): GridCell = GridCell(
            latitude = latitude.roundToInt().coerceIn(-90, 90),
            longitude = Math.floorMod(longitude.roundToInt(), 360),
        )
    }
}
