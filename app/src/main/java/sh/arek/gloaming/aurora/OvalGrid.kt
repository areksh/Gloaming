package sh.arek.gloaming.aurora

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.math.roundToInt

/**
 * The global aurora probability grid, held whole and indexed on the device.
 *
 * This is a privacy decision before it is a performance one. A
 * `/aurora?lat=&lon=` endpoint would be easier and would log an approximate
 * user location on every poll; shipping the whole grid means the server never
 * learns where anyone is.
 *
 * The layout mirrors what the Worker packs: one byte per cell, row-major with
 * one row per latitude from -90 to +90, each row holding longitudes 0 to 359.
 * Geomagnetic latitude is not geographic latitude - the auroral oval is centred
 * on the geomagnetic pole, which sits well over towards North America - and the
 * grid has that model already baked in, which is why a cell lookup is correct
 * everywhere and a "Kp above X and latitude above Y" rule is not.
 */
class OvalGrid private constructor(private val cells: ByteArray) {

    /**
     * Aurora probability, as a percentage, at the given position.
     *
     * Treat the result as a floor rather than a verdict: OVATION is known to
     * overpredict at low activity, and aurora can be visible up to about
     * 1,000 km equatorward of the oval, low on the horizon.
     */
    fun probabilityAt(latitude: Double, longitude: Double): Int {
        val row = (latitude.roundToInt().coerceIn(-90, 90)) + 90
        // Rounding can land on 360, which is not a column, so wrap afterwards.
        val column = Math.floorMod(longitude.roundToInt(), LONGITUDES)
        return cells[row * LONGITUDES + column].toInt() and 0xFF
    }

    companion object {
        const val LONGITUDES: Int = 360
        const val LATITUDES: Int = 181
        const val BYTES: Int = LONGITUDES * LATITUDES

        /**
         * Wraps an already-unpacked grid.
         *
         * @throws IllegalArgumentException if it does not cover the globe. A
         *   short grid would read as "no aurora anywhere" and silently suppress
         *   every alert, which is the one failure this app must not have.
         */
        fun fromPacked(cells: ByteArray): OvalGrid {
            require(cells.size == BYTES) {
                "Aurora grid must be $BYTES bytes, was ${cells.size}"
            }
            return OvalGrid(cells)
        }

        /**
         * Inflates the published grid.
         *
         * The payload is gzip at the application layer, not an HTTP transfer
         * encoding: declaring `Content-Encoding` let the CDN inflate the object
         * and serve forty times the bytes, so the client inflates it instead.
         *
         * It stops at one byte past the size a grid can be. Capping the
         * download does not bound this - compression is precisely the case
         * where the two sizes are unrelated, and a couple of kilobytes of
         * gzipped zeros expand to gigabytes - and the process that would run
         * out of memory is a background poll at three in the morning.
         */
        fun fromGzip(gzipped: ByteArray): OvalGrid {
            val inflated = try {
                GZIPInputStream(ByteArrayInputStream(gzipped)).use { stream ->
                    val buffer = ByteArray(BYTES + 1)
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = stream.read(buffer, filled, buffer.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                    require(filled <= BYTES) { "Aurora grid stopped inflating past $BYTES bytes" }
                    buffer.copyOf(filled)
                }
            } catch (cause: ZipException) {
                throw IllegalArgumentException("Aurora grid was not valid gzip", cause)
            } catch (cause: java.io.EOFException) {
                throw IllegalArgumentException("Aurora grid was truncated", cause)
            }
            return fromPacked(inflated)
        }
    }
}
