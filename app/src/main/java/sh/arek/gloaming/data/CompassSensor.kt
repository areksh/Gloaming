package sh.arek.gloaming.data

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import sh.arek.gloaming.domain.CompassAccuracy

/**
 * One reading of which way the phone is held, and how far to trust it.
 *
 * @property rotation the 9-element row-major matrix from
 *   `SensorManager.getRotationMatrixFromVector`, which is what
 *   [sh.arek.gloaming.domain.Pointing.direction] reads.
 * @property accuracy what the platform last said of the magnetometer's
 *   calibration, which is what the compass check is about.
 */
class CompassReading(val rotation: FloatArray, val accuracy: CompassAccuracy) {
    override fun equals(other: Any?): Boolean =
        other is CompassReading && rotation.contentEquals(other.rotation) && accuracy == other.accuracy

    override fun hashCode(): Int = 31 * rotation.contentHashCode() + accuracy.hashCode()
}

/**
 * Where compass readings come from: the phone's sensors, or a fake in a test.
 *
 * A seam rather than a sensor call inside the screen, so the Find screen can
 * be driven by chosen matrices and a test never registers a real sensor.
 */
interface CompassSource {
    /** Whether the phone has the sensor Find needs; when false, [readings] never emits. */
    val present: Boolean

    /**
     * Readings for as long as the flow is collected. Collecting registers the
     * sensors and cancelling unregisters them, so the collector decides when
     * the phone spends power on them.
     */
    fun readings(): Flow<CompassReading>
}

/** No compass at all: what a screen gets when it was given none to use. */
object NoCompass : CompassSource {
    override val present: Boolean = false

    override fun readings(): Flow<CompassReading> = emptyFlow()
}

/**
 * The phone's own sensors.
 *
 * Attitude comes from the rotation vector, which the platform fuses from the
 * accelerometer, gyroscope and magnetometer - steadier than reading the
 * magnetometer and accelerometer by hand. Accuracy comes from the
 * magnetometer, because that is the part a figure of eight puts right and the
 * rotation vector does not report its calibration the same way everywhere.
 */
class AndroidCompassSource(context: Context) : CompassSource {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    override val present: Boolean get() = rotationSensor != null

    override fun readings(): Flow<CompassReading> {
        val manager = sensors ?: return emptyFlow()
        val rotationVector = rotationSensor ?: return emptyFlow()
        return callbackFlow {
            var magnetic: CompassAccuracy? = null
            val matrix = FloatArray(9)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_MAGNETIC_FIELD -> magnetic = CompassAccuracy.fromSensorStatus(event.accuracy)
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            // Some devices send a fifth value (the heading
                            // accuracy) that older platforms' conversion
                            // rejects; the first four are the quaternion.
                            val values = if (event.values.size > 4) event.values.copyOf(4) else event.values
                            SensorManager.getRotationMatrixFromVector(matrix, values)
                            val accuracy = magnetic ?: CompassAccuracy.fromSensorStatus(event.accuracy)
                            trySend(CompassReading(matrix.copyOf(), accuracy))
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                        magnetic = CompassAccuracy.fromSensorStatus(accuracy)
                    }
                }
            }
            manager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
            magnetometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
            awaitClose { manager.unregisterListener(listener) }
        }.conflate()
    }
}

/**
 * How far magnetic north is from true north here, in degrees east.
 *
 * From the platform's own model of the Earth's field, so it needs no network.
 * What Find adds to a compass bearing to point at a true one.
 */
fun magneticDeclination(latitude: Double, longitude: Double, atMillis: Long): Double =
    GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, atMillis).declination.toDouble()
