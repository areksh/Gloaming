package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.constellation
import io.github.cosinekitty.astronomy.equator
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
    /**
     * Whether tonight is the night of the year it is best placed.
     *
     * Carried on the card rather than left to the calendar entry, because the
     * screen would otherwise name the same pattern twice on that one night -
     * once for where it is and once for the occasion.
     */
    val isAtItsBest: Boolean,
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

        val best = atTheirBestOn(night)

        return WATCHED
            .map { seen(it, observer, latitude, night, it.name in best) }
            .filter { it.peakAltitude >= barFor(it.constellation) }
            .sortedByDescending { it.peakAltitude }
    }

    /**
     * Which patterns have their best night of the year on [night].
     *
     * Searched over a few days either side rather than over the night itself.
     * The crossing that dates a best night is a fact about the sun's position
     * and falls at whatever hour it falls at, often the middle of the day, so
     * a window of one night's darkness would usually miss it - and the daily
     * sampling [bestNights] uses cannot resolve a span shorter than a day at
     * all.
     *
     * The match is by which night the crossing is nearest, which is the same
     * rule [Twilight.nightNearest] applies when the calendar picks the night
     * to date the entry to. So the card and the calendar agree by
     * construction rather than by coincidence.
     */
    private fun atTheirBestOn(night: LongRange): Set<String> {
        val middle = (night.first + night.last) / 2
        return bestNights(night.first - 2 * DAY_MILLIS, night.last + 2 * DAY_MILLIS)
            .filter { kotlin.math.abs(it.atMillis - middle) <= DAY_MILLIS / 2 }
            .map { it.constellation.name }
            .toSet()
    }

    private fun barFor(constellation: Constellation) =
        VISIBLE_DEGREES + constellation.extentDegrees / 2.0

    private fun seen(
        constellation: Constellation,
        observer: Observer,
        latitude: Double,
        night: LongRange,
        isAtItsBest: Boolean,
    ): ConstellationTonight {
        val best = peak(constellation, observer, night)
        return ConstellationTonight(
            constellation = constellation,
            peakAltitude = best.altitude,
            peakAt = best.atMillis,
            peakAzimuth = best.azimuth,
            isCircumpolar = isCircumpolar(constellation, latitude),
            isAtItsBest = isAtItsBest,
        )
    }

    /**
     * The highest a pattern's centre gets inside [night].
     *
     * Swept rather than solved, matching [Planets]. The transit could be had
     * exactly from the hour angle, but the highest it gets *inside a window*
     * is a different question, and most nights the transit falls outside the
     * darkness entirely.
     */
    private fun peak(
        constellation: Constellation,
        observer: Observer,
        night: LongRange,
    ): Sighting {
        var peakAt = night.first
        var peakAltitude = Double.NEGATIVE_INFINITY
        var peakAzimuth = 0.0

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

        return Sighting(peakAt, peakAltitude, peakAzimuth)
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

    // --- the one night of the year ---

    /**
     * A constellation and the night it is best placed from anywhere.
     *
     * The date is a fact about the solar system rather than about the
     * observer: how high it then gets, and whether it rises at all, is what
     * depends on where somebody is standing.
     */
    data class BestPlacement(val constellation: Constellation, val atMillis: Long)

    /** How often the sun's right ascension is sampled when dating the year. */
    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Hours of right ascension in a full turn, which is the unit the library uses. */
    private const val HOURS_IN_A_TURN = 24.0

    /**
     * When each pattern stands on the meridian at midnight, between
     * [fromMillis] and [until].
     *
     * That is what "at its best" means for something that does not change:
     * highest in the middle of the darkest part of the night, which is a
     * different night for each pattern and the same argument an opposition
     * makes for a planet.
     *
     * **Solved from the sun rather than searched per constellation.** A
     * pattern is opposite the sun when the sun's right ascension is twelve
     * hours from its own, so one daily sweep of the sun's position dates every
     * row in the table at once. Searching each one separately would multiply a
     * year's ephemeris work by the size of the table, and the calendar has a
     * quarter of a second for everything it does.
     */
    fun bestNights(fromMillis: Long, until: Long): List<BestPlacement> {
        if (until <= fromMillis) return emptyList()

        // The sun's right ascension, sampled a day apart across the span. It
        // advances about four minutes of right ascension a day and never
        // reverses, so a day is fine enough to interpolate a crossing from.
        val days = ((until - fromMillis) / DAY_MILLIS).toInt() + 1
        val sunRa = DoubleArray(days) { index ->
            val time = Time.fromMillisecondsSince1970(fromMillis + index * DAY_MILLIS)
            equator(Body.Sun, time, GEOCENTRIC, EquatorEpoch.J2000, Aberration.Corrected).ra
        }

        return WATCHED.flatMap { constellation ->
            crossings(constellation, sunRa, fromMillis)
                .map { BestPlacement(constellation, it) }
        }
    }

    /**
     * Every moment the sun reaches the right ascension opposite
     * [constellation] inside the span.
     *
     * Measured as a signed gap in hours so the wrap at twenty-four is not a
     * special case: the gap falls steadily as the sun catches up, and an
     * answer is where it passes through zero.
     *
     * **All of them, not the first.** The calendar searches back three weeks
     * before the horizon it reports, so a span is a year and a bit and a
     * pattern whose best night falls in that lookback crosses twice. Returning
     * only the first dropped Orion out of the whole of 2027, because its
     * December 2026 night had ended before the horizon began.
     */
    private fun crossings(
        constellation: Constellation,
        sunRa: DoubleArray,
        fromMillis: Long,
    ): List<Long> {
        val opposite = (constellation.raHours + HOURS_IN_A_TURN / 2) % HOURS_IN_A_TURN

        fun gap(index: Int): Double {
            val raw = (opposite - sunRa[index] + HOURS_IN_A_TURN * 1.5) % HOURS_IN_A_TURN
            return raw - HOURS_IN_A_TURN / 2
        }

        val found = mutableListOf<Long>()
        for (index in 1 until sunRa.size) {
            val before = gap(index - 1)
            val after = gap(index)
            // Only the fall through zero. The jump back up is the gap wrapping
            // round, which is the arithmetic turning over rather than a second
            // night.
            if (before >= 0.0 && after < 0.0) {
                val share = before / (before - after)
                found += fromMillis + ((index - 1) * DAY_MILLIS) + (share * DAY_MILLIS).toLong()
            }
        }
        return found
    }

    /**
     * The highest [constellation] gets on the night nearest [aroundMillis],
     * or null where that night has no darkness in it.
     */
    fun bestPlacement(
        constellation: Constellation,
        latitude: Double,
        longitude: Double,
        aroundMillis: Long,
    ): Sighting? {
        val night = Twilight.nightNearest(latitude, longitude, aroundMillis) ?: return null
        return peak(constellation, Observer(latitude, longitude, 0.0), night)
    }

    /** The sun's own right ascension barely moves with the observer. */
    private val GEOCENTRIC = Observer(0.0, 0.0, 0.0)

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
