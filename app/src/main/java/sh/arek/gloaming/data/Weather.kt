package sh.arek.gloaming.data

/**
 * How much of the sky is covered, as percentages.
 *
 * The layers are kept separate because they do not mean the same thing to an
 * observer: high cirrus at 60% still shows a strong aurora, while 60% low
 * stratus does not.
 */
data class CloudCover(
    val total: Int,
    val low: Int,
    val mid: Int,
    val high: Int,
)

/** A reading, with where and when it was taken. */
class CachedCloud(
    val cover: CloudCover,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long,
)

/** Persistence for the last cloud reading. */
interface WeatherCache {
    fun read(): CachedCloud?
    fun write(cached: CachedCloud)
}

/**
 * Where cloud cover comes from.
 *
 * An interface with one shipped implementation, because the alternative to
 * Open-Meteo is a user's own API key at some other provider, and retrofitting
 * that later would mean unpicking the gate as well as the client.
 */
interface WeatherSource {
    /**
     * Cloud cover over an observer, or null when it cannot be established.
     *
     * Null is a real answer, not an error: the cloud gate notifies on an
     * unknown reading rather than suppressing silently, so the caller has to
     * be able to tell "unknown" from "clear".
     *
     * @param baseUrl the endpoint the manifest advertises. Never compiled in -
     *   the direct-from-device call is the one most likely to need moving.
     */
    suspend fun cloudCover(baseUrl: String, latitude: Double, longitude: Double): CloudCover?

    /**
     * The last reading held for this place, without touching the network.
     *
     * For the scheduler, which wants to know whether the sky is worth watching
     * but must not spend a request - and a radio wake - to find out. Null when
     * nothing recent is held, or when the reading was taken somewhere else.
     */
    fun lastKnown(latitude: Double, longitude: Double): CloudCover?
}
