package sh.arek.gloaming.data

import java.net.URLEncoder
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import sh.arek.gloaming.net.HttpFetcher
import sh.arek.gloaming.net.getBytes

/**
 * Cloud cover from Open-Meteo, fetched by the device itself.
 *
 * This is the one call that is deliberately not proxied. Open-Meteo's rate
 * limits are per-IP, so routing every install through the project's Worker
 * would concentrate all of them onto a handful of egress addresses and trip the
 * per-minute limit for everyone at once. Direct calls spread across client IPs.
 *
 * It is also the one call that carries the observer's position off the device,
 * which is why the position is rounded first and why the reading is cached for
 * an hour: both cut how often, and how precisely, a third party is told where
 * the user is.
 */
class OpenMeteoWeather(
    private val http: HttpFetcher,
    private val cache: WeatherCache,
    private val now: () -> Long = System::currentTimeMillis,
) : WeatherSource {

    private val json = Json { ignoreUnknownKeys = true }

    override fun lastKnown(latitude: Double, longitude: Double): CloudCover? =
        fresh(latitude, longitude)?.cover

    /**
     * The held reading's age, whatever position it was taken at.
     *
     * Deliberately not position-filtered, unlike [lastKnown]. This answers "how
     * old is the file on this device", which is what the downloads card asks of
     * every object; whether the reading is close enough to be *used* is a
     * different question that `fresh` already answers for the callers that care.
     */
    override fun heldAgeMillis(): Long? = cache.read()?.let { now() - it.fetchedAt }

    override fun forecast(latitude: Double, longitude: Double): List<HourlyCover> =
        fresh(latitude, longitude)?.hourly.orEmpty()

    /** The held reading for this place, if it is recent enough to mean anything. */
    private fun fresh(latitude: Double, longitude: Double): CachedCloud? =
        held(round(latitude) to round(longitude))?.takeIf { now() - it.fetchedAt < STALE_LIMIT_MS }

    override suspend fun cloudCover(
        baseUrl: String,
        latitude: Double,
        longitude: Double,
    ): CloudCover? {
        val place = round(latitude) to round(longitude)
        val cached = held(place)
        val age = cached?.let { now() - it.fetchedAt }

        if (age != null && age < REFRESH_INTERVAL_MS) return cached.cover

        val fetched = runCatching {
            val body = http.getBytes(url(baseUrl, place.first, place.second))
            parse(String(body, Charsets.UTF_8))
        }.getOrNull()

        if (fetched != null) {
            cache.write(
                CachedCloud(fetched.cover, place.first, place.second, now(), fetched.hourly),
            )
            return fetched.cover
        }

        // Better a reading past its refresh window than none: losing the gate
        // to a momentary connection failure lets through exactly the alert the
        // gate exists to hold back. Past the stale limit it is the other way
        // round, and an unknown reading - which notifies - is the honest answer.
        return cached?.takeIf { age != null && age < STALE_LIMIT_MS }?.cover
    }

    /** The cached reading, but only if it was taken at the same rounded place. */
    private fun held(place: Pair<Double, Double>): CachedCloud? =
        cache.read()?.takeIf { round(it.latitude) to round(it.longitude) == place }

    /**
     * The same request the app already made, with the hours ahead added.
     *
     * Not a second call. The hourly block rides the reading that is fetched
     * once an hour anyway - a few hundred more bytes on a request that was
     * going out regardless, on the same endpoint under the same licence, with
     * no new identifier and nothing extra revealed: the position was already
     * being sent.
     *
     * `timeformat=unixtime` so the hours arrive as epoch seconds. The default
     * is a local ISO string whose offset lives in a sibling field, which is a
     * timezone bug waiting to happen for no gain at all.
     */
    private fun url(baseUrl: String, latitude: Double, longitude: Double): String =
        "$baseUrl?latitude=$latitude&longitude=$longitude" +
            "&current=${encode(CURRENT_FIELDS)}" +
            "&hourly=${encode(HOURLY_FIELDS)}" +
            "&forecast_days=$FORECAST_DAYS&timeformat=unixtime"

    /**
     * Every field the app asked for, or nothing.
     *
     * A response missing a layer is not the reading that was requested, and
     * reading an absent layer as zero would report a clear sky - the one wrong
     * answer that silently suppresses a real alert.
     */
    private fun parse(body: String): Reading? {
        val response = json.decodeFromString(Response.serializer(), body)
        val current = response.current ?: return null
        return Reading(
            cover = CloudCover(
                total = current.total ?: return null,
                low = current.low ?: return null,
                mid = current.mid ?: return null,
                high = current.high ?: return null,
            ),
            hourly = hours(response.hourly),
        )
    }

    /**
     * The hours ahead, leniently, unlike [parse] above.
     *
     * Only the total is required of an hour. A layer that is absent or null
     * stays null, and [HourlyCover.effective] falls back to the total.
     *
     * A missing or ragged hourly block costs a sentence on a screen; a missing
     * layer in the current reading costs the gate that decides whether to wake
     * somebody. So the strictness that is right for one is wrong for the other,
     * and an absent forecast must never throw away a good reading.
     *
     * Open-Meteo pads the ends of an hourly array with nulls where a model has
     * not produced a value; those hours are dropped rather than guessed at.
     */
    private fun hours(hourly: Hourly?): List<HourlyCover> {
        val times = hourly?.time ?: return emptyList()
        val cover = hourly.total ?: return emptyList()
        return times.zip(cover).mapIndexedNotNull { index, (at, total) ->
            if (at == null || total == null) {
                null
            } else {
                HourlyCover(
                    at * 1000L,
                    total,
                    hourly.low?.getOrNull(index),
                    hourly.mid?.getOrNull(index),
                    hourly.high?.getOrNull(index),
                )
            }
        }
    }

    /** A parsed response: the reading the gate needs, and the hours the screen wants. */
    private class Reading(val cover: CloudCover, val hourly: List<HourlyCover>)

    @Serializable
    private class Response(val current: Current? = null, val hourly: Hourly? = null)

    @Serializable
    private class Hourly(
        val time: List<Long?>? = null,
        @SerialName("cloud_cover") val total: List<Int?>? = null,
        @SerialName("cloud_cover_low") val low: List<Int?>? = null,
        @SerialName("cloud_cover_mid") val mid: List<Int?>? = null,
        @SerialName("cloud_cover_high") val high: List<Int?>? = null,
    )

    @Serializable
    private class Current(
        @SerialName("cloud_cover") val total: Int? = null,
        @SerialName("cloud_cover_low") val low: Int? = null,
        @SerialName("cloud_cover_mid") val mid: Int? = null,
        @SerialName("cloud_cover_high") val high: Int? = null,
    )

    companion object {
        private const val CURRENT_FIELDS =
            "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high"

        /** The total and the layers, across the night. See [HourlyCover] for why. */
        private const val HOURLY_FIELDS = CURRENT_FIELDS

        /**
         * Two days of hours, which is what "tonight" needs from any point in
         * it.
         *
         * The array starts at midnight of the current day wherever it is asked
         * from, so one day would run out at 23:00 and lose the whole second
         * half of a night that starts at ten in the evening.
         */
        private const val FORECAST_DAYS = 2

        /** Open-Meteo's terms cap refreshes at one an hour. */
        const val REFRESH_INTERVAL_MS: Long = 60 * 60 * 1000L

        /** Past this a reading says nothing useful about the sky overhead. */
        const val STALE_LIMIT_MS: Long = 3 * 60 * 60 * 1000L

        /**
         * Places kept in the position sent to Open-Meteo.
         *
         * Two is about 1.1 km, the resolution of the finest model Open-Meteo
         * runs, so the forecast loses nothing - and the request carries a
         * neighbourhood rather than an address. It also makes the cache work at
         * all: a position from `LocationManager` moves in the sixth decimal
         * place between readings, and an unrounded key would miss every time.
         */
        const val PLACES = 2

        private val SCALE = generateSequence(1.0) { it * 10 }.elementAt(PLACES)

        /** Locale-independent: `String.format` would emit a decimal comma. */
        fun round(degrees: Double): Double = (degrees * SCALE).roundToLong() / SCALE

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
