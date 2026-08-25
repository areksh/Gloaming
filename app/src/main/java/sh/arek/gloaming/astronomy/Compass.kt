package sh.arek.gloaming.astronomy

import kotlin.math.roundToInt

/** One of the eight points, which is as precise as a direction spoken aloud gets. */
enum class Compass(val label: String) {
    North("north"),
    NorthEast("north-east"),
    East("east"),
    SouthEast("south-east"),
    South("south"),
    SouthWest("south-west"),
    West("west"),
    NorthWest("north-west"),
    ;

    companion object {
        /** Nearest point to a bearing in degrees, wrapping at north. */
        fun of(azimuth: Double): Compass {
            val normalised = ((azimuth % 360.0) + 360.0) % 360.0
            return entries[(normalised / 45.0).roundToInt() % entries.size]
        }
    }
}
