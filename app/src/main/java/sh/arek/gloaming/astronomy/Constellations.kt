package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.constellation
import io.github.cosinekitty.astronomy.horizon

/**
 * One pattern in the sky, as a person would look for it.
 *
 * [raHours] and [dec] are a representative centre rather than a boundary
 * centroid: what is wanted is the point somebody would aim at, which for a
 * long thin constellation is not the middle of its official area.
 *
 * **J2000, and never precessed.** Precession moves these about 50 arcseconds a
 * year, so by 2050 the table is out by roughly a third of a degree. Every bar
 * it is measured against is tens of degrees, and the patterns themselves are
 * tens of degrees across, so correcting it would be arithmetic that changes no
 * answer the app ever gives.
 */
data class Constellation(
    val name: String,
    /** Right ascension in hours, which is the unit the library's `horizon` wants. */
    val raHours: Double,
    /** Declination in degrees. */
    val dec: Double,
    val brightestStar: String,
    val brightestMagnitude: Double,
    /**
     * Roughly how far the pattern reaches from [raHours] and [dec], in degrees.
     *
     * Used to decide when enough of it is above the horizon to be worth
     * naming. A constellation is not a point: Orion's centre can sit ten
     * degrees up with half the hunter still below the hills.
     */
    val extentDegrees: Double,
    /** How to pick it out, for somebody who does not already know. */
    val starHop: String,
)

/** One constellation's night: how high it gets, which way to look, and when. */
data class ConstellationTonight(
    val constellation: Constellation,
    /** The highest its centre gets inside tonight's darkness, in degrees. */
    val peakAltitude: Double,
    /** When it is at that altitude. */
    val peakAt: Long,
    /** Degrees clockwise from north at that moment. */
    val peakAzimuth: Double,
    /**
     * Whether it never sets from here, and so belongs to no season at all.
     *
     * Worth saying rather than leaving out. Half the patterns a northern
     * observer learns first are circumpolar, and "this one is up every night
     * of the year" is the most useful thing that can be said about them.
     */
    val isCircumpolar: Boolean,
)

/**
 * The constellations, for the night rather than for the year.
 *
 * The app has always excluded stars, and the reason recorded in Sec. 8 is
 * about deep-sky objects: astronomy-engine offers exactly eight user-defined
 * star slots, and a catalogue is a different app. Neither argument reaches a
 * constellation. There are twenty-odd worth naming, they need no catalogue
 * and no slots - a fixed right ascension and declination is all a pattern is -
 * and they are the most naked-eye thing in the sky, which is the test Sec. 13
 * says settles the question.
 *
 * What they are not is an occasion. "Orion is up from October to March" is a
 * condition that holds for months, so it goes here, where the noctilucent
 * prompt and the planets already are, and only the one dated fact - the night
 * a pattern is best placed - reaches the calendar.
 *
 * Topocentric and refracted, like [Planets] and for the same reason: these
 * numbers are read by somebody looking at the sky rather than compared against
 * a window boundary computed airless.
 */
object Constellations {

    /**
     * The patterns worth naming.
     *
     * Curated rather than derived from a magnitude rule. A rule admits Puppis
     * and Ara - bright stars in a shape nobody could point to - and leaves out
     * Ursa Minor, which every beginner is told to find first. The test is
     * whether somebody could pick it out and would use its name, and that is a
     * judgement rather than a threshold.
     *
     * Southern patterns are here despite the app's northern centre of gravity.
     * They cost nothing from a latitude that cannot see them - the placement
     * verdict says so plainly - and the app already claims to work anywhere.
     */
    val WATCHED = listOf(
        Constellation(
            "Orion", 5.58, 5.0, "Rigel", 0.13, 12.0,
            "Three bright stars in a short straight line make the belt, with " +
                "orange Betelgeuse up-left of it and blue-white Rigel down-right.",
        ),
        Constellation(
            "Ursa Major", 11.0, 50.7, "Alioth", 1.76, 15.0,
            "The seven stars of the Plough. The two at the end of the bowl " +
                "point the way to Polaris.",
        ),
        Constellation(
            "Cassiopeia", 1.0, 62.2, "Schedar", 2.24, 10.0,
            "A flattened W of five stars, on the far side of Polaris from the Plough.",
        ),
        Constellation(
            "Cygnus", 20.62, 44.5, "Deneb", 1.25, 12.0,
            "A long cross laid down the Milky Way, with Deneb at the head and " +
                "the swan flying south.",
        ),
        Constellation(
            "Lyra", 18.85, 36.7, "Vega", 0.03, 6.0,
            "A small parallelogram hanging below brilliant Vega, one corner of " +
                "the Summer Triangle.",
        ),
        Constellation(
            "Aquila", 19.7, 3.4, "Altair", 0.76, 9.0,
            "Altair with a fainter star either side of it, the southern corner " +
                "of the Summer Triangle.",
        ),
        Constellation(
            "Scorpius", 17.0, -31.8, "Antares", 1.06, 13.0,
            "A long curve of stars ending in a hooked sting, with red Antares " +
                "at the heart.",
        ),
        Constellation(
            "Sagittarius", 19.1, -28.5, "Kaus Australis", 1.85, 11.0,
            "A teapot on its side, low in the south, with the Milky Way rising " +
                "from its spout.",
        ),
        Constellation(
            "Taurus", 4.7, 18.0, "Aldebaran", 0.87, 12.0,
            "A V of stars making the bull's face, with orange Aldebaran as one " +
                "eye and the Pleiades above.",
        ),
        Constellation(
            "Gemini", 7.1, 22.6, "Pollux", 1.14, 10.0,
            "Two bright stars side by side, Castor and Pollux, with two lines " +
                "of fainter stars trailing back towards Orion.",
        ),
        Constellation(
            "Canis Major", 6.8, -22.1, "Sirius", -1.46, 9.0,
            "Follow Orion's belt down-left to Sirius, the brightest star in the sky.",
        ),
        Constellation(
            "Canis Minor", 7.65, 6.4, "Procyon", 0.34, 5.0,
            "Procyon with one fainter companion, up-left of Orion and level with Sirius.",
        ),
        Constellation(
            "Auriga", 6.0, 42.0, "Capella", 0.08, 10.0,
            "A rough pentagon with brilliant Capella at its top, almost " +
                "overhead on a winter evening.",
        ),
        Constellation(
            "Leo", 10.7, 13.1, "Regulus", 1.40, 12.0,
            "A backwards question mark makes the head, with Regulus at its " +
                "foot, and a triangle behind it is the tail.",
        ),
        Constellation(
            "Bootes", 14.7, 31.2, "Arcturus", -0.05, 11.0,
            "A kite of stars running up from orange Arcturus. Follow the curve " +
                "of the Plough's handle to find it.",
        ),
        Constellation(
            "Virgo", 13.4, -4.2, "Spica", 0.98, 14.0,
            "Carry the curve of the Plough's handle past Arcturus to Spica, " +
                "and Virgo spreads out around it.",
        ),
        Constellation(
            "Perseus", 3.6, 45.0, "Mirfak", 1.79, 10.0,
            "A crooked line of stars between Cassiopeia's W and the Pleiades.",
        ),
        Constellation(
            "Andromeda", 0.8, 37.4, "Alpheratz", 2.06, 11.0,
            "Two lines of stars running up-left from the corner of the Square " +
                "of Pegasus.",
        ),
        Constellation(
            "Pegasus", 22.7, 19.5, "Markab", 2.39, 12.0,
            "Four stars in a large square, emptier inside than anything around it.",
        ),
        Constellation(
            "Ursa Minor", 15.0, 77.7, "Polaris", 1.98, 10.0,
            "A fainter plough shape hanging from Polaris, which the end of the " +
                "Plough's bowl points to.",
        ),
        Constellation(
            "Crux", 12.45, -60.2, "Acrux", 0.77, 4.0,
            "A small tight cross of four stars, with the two bright Pointers of " +
                "Centaurus beside it.",
        ),
        Constellation(
            "Centaurus", 13.1, -47.3, "Rigil Kentaurus", -0.27, 13.0,
            "Two brilliant stars, the Pointers, aiming at the Southern Cross.",
        ),
    )

    /**
     * How high the centre must get before the pattern is worth a line.
     *
     * Higher than [Planets]' five degrees, and the difference is the point: a
     * planet is a dot and is perfectly obvious at five degrees, while a
     * constellation ten degrees across at five degrees up is half a
     * constellation. Each row's own [Constellation.extentDegrees] is added on
     * top, so a sprawling pattern has to clear more than a compact one.
     */
    const val VISIBLE_DEGREES: Double = 10.0

    /** Ten minutes, matching every other sweep of a night in the app. */
    private const val SAMPLE_MILLIS: Long = 10L * 60 * 1000

    /**
     * Every constellation worth looking for inside [night], highest first.
     *
     * Highest rather than brightest: the question somebody standing outside is
     * asking is what they can actually find, and a pattern near the zenith
     * beats a brighter one in the trees every time.
     */
    fun tonight(
        latitude: Double,
        longitude: Double,
        night: LongRange,
    ): List<ConstellationTonight> {
        if (night.isEmpty()) return emptyList()
        val observer = Observer(latitude, longitude, 0.0)

        return WATCHED
            .map { seen(it, observer, latitude, night) }
            .filter { it.peakAltitude >= barFor(it.constellation) }
            .sortedByDescending { it.peakAltitude }
    }

    private fun barFor(constellation: Constellation) =
        VISIBLE_DEGREES + constellation.extentDegrees / 2.0

    private fun seen(
        constellation: Constellation,
        observer: Observer,
        latitude: Double,
        night: LongRange,
    ): ConstellationTonight {
        var peakAt = night.first
        var peakAltitude = Double.NEGATIVE_INFINITY
        var peakAzimuth = 0.0

        // Swept rather than solved, matching Planets. The transit could be had
        // exactly from the hour angle, but the highest it gets *inside a
        // window* is a different question, and most nights the transit falls
        // outside the darkness entirely.
        var at = night.first
        while (at <= night.last) {
            val time = Time.fromMillisecondsSince1970(at)
            val above = horizon(
                time,
                observer,
                constellation.raHours,
                constellation.dec,
                Refraction.Normal,
            )
            if (above.altitude > peakAltitude) {
                peakAltitude = above.altitude
                peakAzimuth = above.azimuth
                peakAt = at
            }
            at += SAMPLE_MILLIS
        }

        return ConstellationTonight(
            constellation = constellation,
            peakAltitude = peakAltitude,
            peakAt = peakAt,
            peakAzimuth = peakAzimuth,
            isCircumpolar = isCircumpolar(constellation, latitude),
        )
    }

    /**
     * Whether the pattern never sets from [latitude].
     *
     * The lowest a fixed point gets is `|latitude| + |dec| - 90` on the same
     * side of the equator as the observer, so it stays up when that is above
     * the horizon. The extent is subtracted, because a constellation whose
     * centre skims the horizon is not one that is genuinely up all night.
     */
    private fun isCircumpolar(constellation: Constellation, latitude: Double): Boolean {
        val sameSide = latitude >= 0 == constellation.dec >= 0
        if (!sameSide) return false
        val lowest = kotlin.math.abs(latitude) + kotlin.math.abs(constellation.dec) - 90.0
        return lowest - constellation.extentDegrees / 2.0 > 0.0
    }

    /**
     * The constellation a point in the sky falls in.
     *
     * **J2000, which is not the frame most of this app works in.** The
     * vendored lookup converts to the B1875 boundaries the IAU drew, and it
     * documents J2000 as its input; handing it the of-date coordinates
     * [Planets] and [SkyCalendar] use would be right almost everywhere and
     * quietly wrong near a boundary.
     */
    fun containing(raJ2000Hours: Double, decJ2000: Double): String =
        constellation(raJ2000Hours, decJ2000).name
}
