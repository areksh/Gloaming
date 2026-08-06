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
        held(round(latitude) to round(longitude))?.takeIf { now() - it.fetchedAt < STALE_LIMIT_MS }
            ?.cover

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
            cache.write(CachedCloud(fetched, place.first, place.second, now()))
            return fetched
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

    private fun url(baseUrl: String, latitude: Double, longitude: Double): String =
        "$baseUrl?latitude=$latitude&longitude=$longitude&current=${encode(CURRENT_FIELDS)}"

    /**
     * Every field the app asked for, or nothing.
     *
     * A response missing a layer is not the reading that was requested, and
     * reading an absent layer as zero would report a clear sky - the one wrong
     * answer that silently suppresses a real alert.
     */
    private fun parse(body: String): CloudCover? {
        val current = json.decodeFromString(Response.serializer(), body).current ?: return null
        return CloudCover(
            total = current.total ?: return null,
            low = current.low ?: return null,
            mid = current.mid ?: return null,
            high = current.high ?: return null,
        )
    }

    @Serializable
    private class Response(val current: Current? = null)

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
