package sh.arek.gloaming.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sqrt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The phone's own compass, read through the seam Find uses.
 *
 * The one place a real sensor is registered in a test: the Find screen's
 * tests drive a fake, so this is what shows the platform glue produces a
 * rotation matrix [sh.arek.gloaming.domain.Pointing] can read. Skipped on a
 * phone with no rotation sensor, where Find says so instead.
 */
class CompassSensorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsARotationMatrixFromThePhone() = runBlocking {
        val compass = AndroidCompassSource(context)
        assumeTrue("no rotation vector sensor on this phone", compass.present)

        val reading = withTimeout(5_000) { compass.readings().first() }

        assertEquals(9, reading.rotation.size)
        // The top edge in world coordinates is the matrix's second column,
        // and a rotation keeps it a unit vector.
        val r = reading.rotation
        assertEquals(1.0, sqrt((r[1] * r[1] + r[4] * r[4] + r[7] * r[7]).toDouble()), 0.01)
    }
}
