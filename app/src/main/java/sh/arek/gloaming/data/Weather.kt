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

/**
 * Cloud cover at one hour of the forecast.
 *
 * The layers ride along with the total because a clear spell for a planet or a
 * pass is not judged on the total: high thin cloud dims a bright target far
 * less than low cloud does, so 70% cirrus over an otherwise clear sky is still
 * a night worth going out for and 70% stratus is not. [effective] is that
 * judgement, and what a spell is measured against.
 *
 * The layers are null where the forecast did not give them, and for a cache
 * file written before they were asked for. [total] is then all there is, and
 * [effective] falls back to it rather than reading an absent layer as clear.
 *
 * [atMillis] is the start of the hour, in UTC.
 */
data class HourlyCover(
    val atMillis: Long,
    val total: Int,
    val low: Int? = null,
    val mid: Int? = null,
    val high: Int? = null,
) {
    /**
     * Cover as it matters to a bright target: the worse of low and mid cloud,
     * or half the high cloud, which is thin enough to see through.
     */
    val effective: Int
        get() = if (low != null && mid != null && high != null) {
            maxOf(low, mid, high / 2)
        } else {
            total
        }
}

/** A reading, with where and when it was taken. */
class CachedCloud(
    val cover: CloudCover,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long,
    /**
     * The hours ahead, where the response carried them.
     *
     * Empty rather than null when there are none, and empty is what an older
     * cache file deserialises to - a reading that predates the forecast being
     * asked for is still a perfectly good reading.
     */
    val hourly: List<HourlyCover> = emptyList(),
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
     * How old the held reading is, or null where none is held.
     *
     * Defaulted, so a fake in a poller test need not answer it: nothing that
     * decides anything reads this. It exists for the downloads card, which
     * reports how old each thing on the device is, and a forecast with no age
     * beside it was the one row that looked like an oversight.
     */
    fun heldAgeMillis(): Long? = null

    /**
     * The last reading held for this place, without touching the network.
     *
     * For the scheduler, which wants to know whether the sky is worth watching
     * but must not spend a request - and a radio wake - to find out. Null when
     * nothing recent is held, or when the reading was taken somewhere else.
     */
    fun lastKnown(latitude: Double, longitude: Double): CloudCover?

    /**
     * The hours ahead for this place, from the same cached document.
     *
     * Never a fetch. The forecast arrives alongside the reading that is fetched
     * once an hour, so anything that wants it - the screen, in practice - reads
     * what is already held rather than spending the one request Open-Meteo's
     * terms allow.
     *
     * Empty when nothing useful is held: no reading, a stale one, or one taken
     * somewhere else. The caller says nothing rather than guessing.
     */
    fun forecast(latitude: Double, longitude: Double): List<HourlyCover>
}
