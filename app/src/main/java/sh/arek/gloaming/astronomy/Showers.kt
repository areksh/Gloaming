package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.searchSunLongitude

/**
 * One annual shower, as the IMO publishes it.
 *
 * **The peak is a solar longitude, not a date.** That is how the IMO states it
 * and it is what makes this table survive a change of year: the Earth reaches a
 * given point in its orbit at the same place every year, so 140.0 degrees is
 * the Perseid peak in 2027, 2028 and 2099 alike. A table of dates would need
 * replacing annually, would drift by a day at a time as leap years came and
 * went, and would be wrong in exactly the quiet way nobody notices.
 *
 * That also settles the "remote override" the hosted-file design left open for
 * `showers.json`: a table that does not rot does not need one, which saves an
 * endpoint, a cache path and a signature.
 */
data class Shower(
    val name: String,
    /** Solar longitude of maximum, J2000, in degrees - the IMO's own figure. */
    val solarLongitude: Double,
    /** Radiant right ascension at maximum, in hours. */
    val radiantRightAscension: Double,
    /** Radiant declination at maximum, in degrees. */
    val radiantDeclination: Double,
    /** Zenithal hourly rate: the rate under a perfect sky with the radiant overhead. */
    val zenithHourlyRate: Int,
    val parent: String,
    /** When the shower is active at all, for context rather than for alerting. */
    val activeFrom: String,
    val activeUntil: String,
)

/**
 * One shower's maximum, worked out for a place and a night.
 *
 * Everything here except [peaksAt] depends on where the observer stands, which
 * is the whole reason the app computes it rather than printing a table: a ZHR
 * of 25 means nothing when the radiant cannot get above 18 degrees, and a ZHR
 * of 150 means much less than it sounds under a gibbous moon.
 */
data class ShowerPeak(
    val shower: Shower,
    val peaksAt: Long,
    /** The highest the radiant gets during the darkness nearest the peak. */
    val radiantAltitude: Double,
    val radiantAzimuth: Double,
    /** When it is highest, which is the hour to be outside. */
    val bestAt: Long,
    /** Fraction of the moon's disc lit at maximum, 0 at new and 1 at full. */
    val moonIllumination: Double,
    /** The share of that night's darkness the moon is above the horizon for. */
    val moonUpFraction: Double,
) {
    /**
     * How much of the shower the moon takes away, from nothing to almost all.
     *
     * A full moon that has set by midnight costs a good deal less than a half
     * moon up all night, so brightness alone is the wrong measure and this is
     * the product of the two.
     */
    val moonInterference: Double get() = moonIllumination * moonUpFraction

    /**
     * Roughly what an hour outside might actually produce, rather than the ZHR.
     *
     * A zenithal hourly rate is a laboratory figure: it assumes the radiant
     * directly overhead and a sky dark enough to show sixth-magnitude stars.
     * Printing 150 for the Geminids and delivering a dozen is the same mistake
     * as printing a satellite magnitude nobody can stand behind, and Sec. 6
     * rejects that one explicitly.
     *
     * The radiant-elevation correction is the standard one - rates fall with
     * the sine of the radiant's altitude, because a low radiant hides most of
     * the sky the meteors are in. Sky brightness is not corrected for here
     * because the app does not know where the observer is standing relative to
     * a town, so the text that quotes this says what it assumes.
     */
    val hourlyRateFromHere: Int
        get() = if (radiantAltitude <= 0.0) 0
        else (zenithHourlyRate() * kotlin.math.sin(Math.toRadians(radiantAltitude))).toInt()

    private fun zenithHourlyRate() = shower.zenithHourlyRate.toDouble()
}

/**
 * The meteor showers worth telling somebody about, and what each is worth
 * tonight.
 *
 * No API for this exists anywhere, so the table is hand-built - one of the two
 * components the design says have to be written from scratch. Thirteen rows
 * covers every shower a person who is not a specialist has heard of.
 *
 * **The radiant is taken at maximum and does not drift here.** The IMO
 * publishes a daily drift, and it is left out deliberately: the app speaks
 * about the peak night and no other, so the radiant at maximum is exactly the
 * figure that matters, and carrying a drift term would refine a position on
 * nights nothing is ever said about. It also keeps the table to numbers that
 * can be checked against a published source rather than interpolated.
 *
 * Radiant positions are J2000 and used as though they were of date. Precession
 * is about a degree over fifty years, which is far inside the twenty-degree
 * band that decides whether a radiant is usably high.
 */
object Showers {

    /** Below this the radiant is too low for most of the shower to be above the horizon. */
    const val LOW_RADIANT_DEGREES: Double = 20.0

    /** Above this the moon takes enough of the shower to be worth warning about. */
    const val MOONLIT: Double = 0.4

    /** Ten minutes, matching the sampling used for everything else on one night. */
    private const val SAMPLE_MILLIS: Long = 10L * 60 * 1000

    /** A year and a day, so a search from any starting point finds the next one. */
    private const val SEARCH_DAYS: Double = 366.0

    val TABLE: List<Shower> = listOf(
        Shower(
            name = "Quadrantids",
            solarLongitude = 283.15,
            radiantRightAscension = 15.33,
            radiantDeclination = 49.7,
            zenithHourlyRate = 110,
            parent = "asteroid 2003 EH1",
            activeFrom = "28 December",
            activeUntil = "12 January",
        ),
        Shower(
            name = "Lyrids",
            solarLongitude = 32.32,
            radiantRightAscension = 18.07,
            radiantDeclination = 33.3,
            zenithHourlyRate = 18,
            parent = "comet C/1861 G1 Thatcher",
            activeFrom = "14 April",
            activeUntil = "30 April",
        ),
        Shower(
            name = "eta Aquariids",
            solarLongitude = 45.5,
            radiantRightAscension = 22.53,
            radiantDeclination = -1.4,
            zenithHourlyRate = 50,
            parent = "comet 1P/Halley",
            activeFrom = "19 April",
            activeUntil = "28 May",
        ),
        Shower(
            name = "southern delta Aquariids",
            solarLongitude = 125.0,
            radiantRightAscension = 22.70,
            radiantDeclination = -16.3,
            zenithHourlyRate = 25,
            parent = "comet 96P/Machholz",
            activeFrom = "12 July",
            activeUntil = "23 August",
        ),
        Shower(
            name = "alpha Capricornids",
            solarLongitude = 127.0,
            radiantRightAscension = 20.47,
            radiantDeclination = -10.2,
            zenithHourlyRate = 5,
            parent = "comet 169P/NEAT",
            activeFrom = "3 July",
            activeUntil = "15 August",
        ),
        Shower(
            name = "Perseids",
            solarLongitude = 140.0,
            radiantRightAscension = 3.22,
            radiantDeclination = 58.1,
            zenithHourlyRate = 100,
            parent = "comet 109P/Swift-Tuttle",
            activeFrom = "17 July",
            activeUntil = "24 August",
        ),
        Shower(
            name = "Draconids",
            solarLongitude = 195.4,
            radiantRightAscension = 17.47,
            radiantDeclination = 55.7,
            zenithHourlyRate = 10,
            parent = "comet 21P/Giacobini-Zinner",
            activeFrom = "6 October",
            activeUntil = "10 October",
        ),
        Shower(
            name = "Orionids",
            solarLongitude = 208.0,
            radiantRightAscension = 6.35,
            radiantDeclination = 15.6,
            zenithHourlyRate = 20,
            parent = "comet 1P/Halley",
            activeFrom = "2 October",
            activeUntil = "7 November",
        ),
        Shower(
            name = "southern Taurids",
            solarLongitude = 223.0,
            radiantRightAscension = 3.53,
            radiantDeclination = 15.0,
            zenithHourlyRate = 5,
            parent = "comet 2P/Encke",
            activeFrom = "10 September",
            activeUntil = "20 November",
        ),
        Shower(
            name = "northern Taurids",
            solarLongitude = 230.0,
            radiantRightAscension = 3.87,
            radiantDeclination = 22.7,
            zenithHourlyRate = 5,
            parent = "comet 2P/Encke",
            activeFrom = "20 October",
            activeUntil = "10 December",
        ),
        Shower(
            name = "Leonids",
            solarLongitude = 235.27,
            radiantRightAscension = 10.28,
            radiantDeclination = 21.6,
            zenithHourlyRate = 15,
            parent = "comet 55P/Tempel-Tuttle",
            activeFrom = "6 November",
            activeUntil = "30 November",
        ),
        Shower(
            name = "Geminids",
            solarLongitude = 262.2,
            radiantRightAscension = 7.55,
            radiantDeclination = 32.3,
            zenithHourlyRate = 150,
            parent = "asteroid 3200 Phaethon",
            activeFrom = "4 December",
            activeUntil = "20 December",
        ),
        Shower(
            name = "Ursids",
            solarLongitude = 270.7,
            radiantRightAscension = 14.60,
            radiantDeclination = 75.3,
            zenithHourlyRate = 10,
            parent = "comet 8P/Tuttle",
            activeFrom = "17 December",
            activeUntil = "26 December",
        ),
    )

    /**
     * Every shower peaking between the two instants, in time order.
     *
     * The solar longitude is turned into an instant by searching for when the
     * sun reaches it, which is why the table never needs a year in it.
     */
    fun peaks(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        untilMillis: Long,
    ): List<ShowerPeak> = TABLE
        .flatMap { shower -> maxima(shower, fromMillis, untilMillis) }
        .map { (shower, peaksAt) -> peak(shower, peaksAt, latitude, longitude) }
        .sortedBy { it.peaksAt }

    private fun maxima(
        shower: Shower,
        fromMillis: Long,
        untilMillis: Long,
    ): List<Pair<Shower, Long>> = buildList {
        var cursor = Time.fromMillisecondsSince1970(fromMillis)
        while (true) {
            val found = searchSunLongitude(shower.solarLongitude, cursor, SEARCH_DAYS) ?: return@buildList
            val at = found.toMillisecondsSince1970()
            if (at > untilMillis) return@buildList
            add(shower to at)
            // Stepped a day past, because the search returns the longitude at or
            // after its start and would otherwise return the same peak forever.
            cursor = found.addDays(1.0)
        }
    }

    private fun peak(
        shower: Shower,
        peaksAt: Long,
        latitude: Double,
        longitude: Double,
    ): ShowerPeak {
        val observer = Observer(latitude, longitude, 0.0)
        val night = Twilight.nightNearest(latitude, longitude, peaksAt)

        var bestAt = peaksAt
        var highest = Double.NEGATIVE_INFINITY
        var azimuth = 0.0
        var samples = 0

        if (night != null) {
            var at = night.first
            while (at <= night.last) {
                val time = Time.fromMillisecondsSince1970(at)
                val above = horizon(
                    time,
                    observer,
                    shower.radiantRightAscension,
                    shower.radiantDeclination,
                    Refraction.Normal,
                )
                if (above.altitude > highest) {
                    highest = above.altitude
                    azimuth = above.azimuth
                    bestAt = at
                }
                samples++
                at += SAMPLE_MILLIS
            }
        }

        return ShowerPeak(
            shower = shower,
            peaksAt = peaksAt,
            // No darkness at all is the polar summer, and a radiant that cannot
            // be observed is the same answer as one that never rises.
            radiantAltitude = if (samples == 0) 0.0 else highest,
            radiantAzimuth = azimuth,
            bestAt = bestAt,
            moonIllumination = Twilight.moon(peaksAt).illumination,
            // Shared with the comets, which need exactly the same figure - see
            // [Twilight.moonUpFraction].
            moonUpFraction = if (night == null) {
                0.0
            } else {
                Twilight.moonUpFraction(latitude, longitude, night)
            },
        )
    }
}
