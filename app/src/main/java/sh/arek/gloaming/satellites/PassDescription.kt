package sh.arek.gloaming.satellites

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

/** How far up the sky a pass gets, in the terms someone outside would use. */
enum class Height(val label: String) {
    /** Near enough overhead to have to look up rather than out. */
    High("high"),
    Middling("midway up"),
    /** Down among the roofs and trees. */
    Low("low"),
}

/**
 * What the pass is worth, without ever claiming a magnitude.
 *
 * The two that are not about quality say why there is nothing to see, because
 * that is the useful thing to know about a pass that is happening anyway.
 */
enum class Prominence {
    /** High, close and in sunlight: as good as this object gets. */
    Striking,
    Good,
    /** Low, distant, or lit for only part of its crossing. */
    Modest,
    /** In the Earth's shadow throughout, so it reflects nothing. */
    InShadow,
    /** The sky where the observer stands is not dark enough. */
    TooLight,
}

/**
 * A pass in words rather than numbers.
 *
 * The app prints no magnitudes, deliberately. The open datasets are stale,
 * incomplete, or in two incompatible conventions that produce answers a
 * magnitude and a half apart, and an app that says "magnitude 2.5" and delivers
 * 4.5 half the time is worse than one that never claimed a number. CelesTrak's
 * `visual` group has already answered "is this worth telling someone about";
 * what is left is ranking within it, which these buckets do adequately.
 *
 * Everything used here is exact geometry: how high the pass gets, how close it
 * comes, and whether the sun is still on the satellite.
 *
 * **The phase angle is deliberately not used**, though the sourcing document
 * lists it. The propagator publishes a geodetic position rather than the state
 * vector it holds, so getting the sun-satellite-observer angle would mean
 * either inverting a conversion inside vendored code that is replaced wholesale
 * on upgrade, or carrying the satellite into astronomy-engine's frame. That is
 * a real failure mode added in order to separate "bright" from "very bright",
 * in a feature whose whole premise is not making that sort of claim.
 */
data class PassDescription(
    val prominence: Prominence,
    val height: Height,
    val risesTowards: Compass,
    val peaksTowards: Compass,
    val setsTowards: Compass,
    /** True when the satellite goes out part-way across the sky. */
    val goesIntoShadow: Boolean,
    /** True when it is dark on the way up and lights up before it sets. */
    val comesOutOfShadow: Boolean,
) {

    /** One line, plain and honest about how much of a claim it is making. */
    val headline: String
        get() {
            val opening = when (prominence) {
                Prominence.Striking -> "Bright, ${height.label} in the ${peaksTowards.label}."
                Prominence.Good -> "A good pass, ${height.label} in the ${peaksTowards.label}."
                Prominence.Modest -> "Low and brief, probably faint."
                Prominence.InShadow ->
                    "It stays in the Earth's shadow throughout, so there will be nothing to see."
                Prominence.TooLight -> "The sky will still be too light to see it."
            }
            return when {
                goesIntoShadow && prominence.isVisible ->
                    "$opening It goes into the Earth's shadow part-way across."
                comesOutOfShadow && prominence.isVisible ->
                    "$opening It is in shadow at first and lights up part-way across."
                else -> opening
            }
        }

    companion object {

        /** At or above this, a pass is worth looking straight up for. */
        const val HIGH_DEGREES: Double = 50.0

        /** Below this it is behind whatever the observer has on their horizon. */
        const val LOW_DEGREES: Double = 25.0

        /**
         * Range at the highest point, beyond which even an overhead pass is
         * small and slow.
         *
         * Separates a low-orbit object at a few hundred kilometres from
         * something in a much higher orbit that happens to be overhead.
         */
        const val CLOSE_KM: Double = 700.0

        /**
         * @param observerIsDark whether the sun is far enough below the horizon
         *   where the observer stands, from
         *   [sh.arek.gloaming.astronomy.Twilight]. Passed in rather than
         *   computed here so this stays free of clocks and ephemerides.
         */
        fun of(pass: SatellitePass, observerIsDark: Boolean): PassDescription {
            val everSunlit = pass.sunlitAtPeak ||
                pass.entersShadowAt != null ||
                pass.leavesShadowAt != null

            val height = when {
                pass.peakElevation >= HIGH_DEGREES -> Height.High
                pass.peakElevation >= LOW_DEGREES -> Height.Middling
                else -> Height.Low
            }

            val prominence = when {
                !observerIsDark -> Prominence.TooLight
                !everSunlit -> Prominence.InShadow
                !pass.sunlitAtPeak -> Prominence.Modest
                height == Height.High && pass.rangeAtPeakKm <= CLOSE_KM -> Prominence.Striking
                height != Height.Low -> Prominence.Good
                else -> Prominence.Modest
            }

            return PassDescription(
                prominence = prominence,
                height = height,
                risesTowards = Compass.of(pass.riseAzimuth),
                peaksTowards = Compass.of(pass.peakAzimuth),
                setsTowards = Compass.of(pass.setAzimuth),
                goesIntoShadow = pass.entersShadowAt != null,
                comesOutOfShadow = pass.leavesShadowAt != null,
            )
        }
    }
}

/** Whether there is anything for the observer to see at all. */
val Prominence.isVisible: Boolean
    get() = this == Prominence.Striking || this == Prominence.Good || this == Prominence.Modest
