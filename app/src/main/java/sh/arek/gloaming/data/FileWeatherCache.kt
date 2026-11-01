package sh.arek.gloaming.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps the last cloud reading in the app's private storage.
 *
 * One small JSON file rather than a row in a database: it is read on a
 * background wake before anything heavier is initialised, and there is only
 * ever one reading worth keeping.
 *
 * Note that this file records where the user was, at the rounded position the
 * request carried. It lives in device-protected storage for the same reason the
 * grid cell does - a poll before first unlock needs it - and carries no more
 * precision than the request already sent.
 */
class FileWeatherCache(directory: File) : WeatherCache {

    private val file = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        directory.mkdirs()
    }

    override fun read(): CachedCloud? {
        if (!file.isFile) return null
        return runCatching {
            val stored = json.decodeFromString(Stored.serializer(), file.readText())
            CachedCloud(
                cover = CloudCover(stored.total, stored.low, stored.mid, stored.high),
                latitude = stored.latitude,
                longitude = stored.longitude,
                fetchedAt = stored.fetchedAt,
                hourly = stored.hourAt.zip(stored.hourTotal).mapIndexed { index, (at, total) ->
                    HourlyCover(
                        at,
                        total,
                        stored.hourLow.getOrNull(index),
                        stored.hourMid.getOrNull(index),
                        stored.hourHigh.getOrNull(index),
                    )
                },
            )
        }.getOrNull()
    }

    /** Removes the reading, used when it should no longer be where it is. */
    fun clear() {
        runCatching { file.delete() }
    }

    override fun write(cached: CachedCloud) {
        runCatching {
            file.writeText(
                json.encodeToString(
                    Stored.serializer(),
                    Stored(
                        total = cached.cover.total,
                        low = cached.cover.low,
                        mid = cached.cover.mid,
                        high = cached.cover.high,
                        latitude = cached.latitude,
                        longitude = cached.longitude,
                        fetchedAt = cached.fetchedAt,
                        hourAt = cached.hourly.map { it.atMillis },
                        hourTotal = cached.hourly.map { it.total },
                        hourLow = cached.hourly.map { it.low },
                        hourMid = cached.hourly.map { it.mid },
                        hourHigh = cached.hourly.map { it.high },
                    ),
                ),
            )
        }
    }

    @Serializable
    private class Stored(
        val total: Int,
        val low: Int,
        val mid: Int,
        val high: Int,
        val latitude: Double,
        val longitude: Double,
        val fetchedAt: Long,
        /**
         * The hours ahead, as two parallel arrays.
         *
         * Flat, like the rest of this class, and the shape Open-Meteo serves
         * them in. Both default to empty, which is what makes a `weather.json`
         * written before the forecast was asked for parse rather than being
         * thrown away - and a discarded cache file is an extra request against
         * a per-hour limit. The layers are parallel to them and nullable, and
         * default to empty for the same reason: a file from before they were
         * asked for reads as hours with no layers.
         */
        val hourAt: List<Long> = emptyList(),
        val hourTotal: List<Int> = emptyList(),
        val hourLow: List<Int?> = emptyList(),
        val hourMid: List<Int?> = emptyList(),
        val hourHigh: List<Int?> = emptyList(),
    )

    companion object {
        /** Named here so whoever has to delete it does not spell the path again. */
        const val FILE_NAME = "weather.json"
    }
}
