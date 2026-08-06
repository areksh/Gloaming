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

    private val file = File(directory, "weather.json")
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
            )
        }.getOrNull()
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
    )
}
