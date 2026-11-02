package sh.arek.gloaming.ui

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import sh.arek.gloaming.data.CompassReading
import sh.arek.gloaming.data.CompassSource
import sh.arek.gloaming.data.FindAim
import sh.arek.gloaming.domain.CompassAccuracy

/**
 * A compass that reads whatever a test tells it to, and says whether anybody
 * is listening - which is how a test shows the screen lets go of the sensor.
 */
class FakeCompassSource(override val present: Boolean = true) : CompassSource {
    private val latest = MutableStateFlow<CompassReading?>(null)

    /** How many collectors are registered right now. */
    val listeners: Int get() = latest.subscriptionCount.value

    override fun readings(): Flow<CompassReading> = latest.filterNotNull()

    /**
     * The phone pointing at [azimuth] (magnetic) and [altitude], both in
     * degrees, held the way [aim] says: its top edge there, or its back.
     */
    fun point(
        azimuth: Double,
        altitude: Double,
        accuracy: CompassAccuracy = CompassAccuracy.High,
        aim: FindAim = FindAim.TopEdge,
    ) {
        val rotation = when (aim) {
            FindAim.TopEdge -> pointingAt(azimuth, altitude)
            FindAim.LookThrough -> lookingAt(azimuth, altitude)
        }
        latest.value = CompassReading(rotation, accuracy)
    }

    companion object {
        /**
         * A rotation matrix with the device's top edge (+Y) at [azimuth] and
         * [altitude], held without roll: right edge level, screen facing the
         * holder. Row-major, world X east, Y north, Z up, as the platform's.
         */
        fun pointingAt(azimuth: Double, altitude: Double): FloatArray {
            val a = Math.toRadians(azimuth)
            val h = Math.toRadians(altitude)
            val y = doubleArrayOf(sin(a) * cos(h), cos(a) * cos(h), sin(h))
            val x = doubleArrayOf(cos(a), -sin(a), 0.0)
            val z = doubleArrayOf(
                x[1] * y[2] - x[2] * y[1],
                x[2] * y[0] - x[0] * y[2],
                x[0] * y[1] - x[1] * y[0],
            )
            return columns(x, y, z)
        }

        /**
         * A rotation matrix with the device's back (-Z) at [azimuth] and
         * [altitude], held without roll: right edge level, screen towards
         * the holder, top edge on the sky's side.
         */
        fun lookingAt(azimuth: Double, altitude: Double): FloatArray {
            val a = Math.toRadians(azimuth)
            val h = Math.toRadians(altitude)
            val z = doubleArrayOf(-sin(a) * cos(h), -cos(a) * cos(h), -sin(h))
            val x = doubleArrayOf(cos(a), -sin(a), 0.0)
            // Y = Z x X, for a right-handed frame.
            val y = doubleArrayOf(
                z[1] * x[2] - z[2] * x[1],
                z[2] * x[0] - z[0] * x[2],
                z[0] * x[1] - z[1] * x[0],
            )
            return columns(x, y, z)
        }

        private fun columns(x: DoubleArray, y: DoubleArray, z: DoubleArray) = floatArrayOf(
            x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
        )
    }
}
