package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Two bodies at their closest, as seen from where the observer is standing. */
data class Approach(
    val atMillis: Long,
    val separationDegrees: Double,
    val first: SkyBody,
    val second: SkyBody,
)

/** How a pair looked at the best moment of the night nearest their approach. */
data class PairSighting(
    val atMillis: Long,
    val separationDegrees: Double,
    /** The lower of the two altitudes: both have to be up for there to be a sight. */
    val altitude: Double,
    val azimuth: Double,
)

/**
 * When two bright things in the sky come close together.
 *
 * **Topocentric throughout, which for the moon is not a detail.** The moon has
 * up to a degree of parallax, so a geocentric separation can be a whole moon's
 * width away from what somebody actually sees, and most close approaches
 * involve the moon. A conjunction is a fact about a place, not about the Earth.
 *
 * **The moment of closest approach is usually the wrong moment to report.**
 * Over four hundred nights from Edinburgh the great majority of approaches
 * peak with the pair below the horizon: the moon comes within four arcminutes
 * of Venus on 14 September 2026, at ten in the morning. So the search finds the
 * approach and [asSeen] answers the separate question of what the night either
 * side of it offers, which is the number worth printing.
 */
object Conjunctions {

    /** Wide enough to be worth a line in the calendar. */
    const val NOTABLE_DEGREES: Double = 3.0

    /**
     * Tight enough to be worth interrupting somebody for.
     *
     * A degree is about two moon-widths, and pairs that close arrive roughly
     * every six weeks - often enough to be a channel worth having and rare
     * enough not to be the channel that gets muted. Anything looser is a line
     * in a list, not a notification.
     */
    const val STRIKING_DEGREES: Double = 1.0

    /**
     * The bodies worth pairing off.
     *
     * Everything past Saturn needs a telescope, and the app is about what can
     * be seen from a garden with no equipment at all.
     */
    val WATCHED: List<SkyBody> = listOf(
        SkyBody.Moon,
        SkyBody.Mercury,
        SkyBody.Venus,
        SkyBody.Mars,
        SkyBody.Jupiter,
        SkyBody.Saturn,
    )

    /**
     * Six hours, which is coarse for the moon and still safe.
     *
     * The moon moves about three degrees between samples, so a half-degree
     * approach shows up as a clear dip rather than being stepped over - a
     * minimum only needs to be bracketed, not resolved, because [refine] does
     * the resolving. Finer sampling would multiply the ephemeris calls across
     * fifteen pairs and a year for no better answer.
     */
    private const val STEP_MILLIS: Long = 6L * 60 * 60 * 1000

    /** Ten minutes, matching the sampling everything else uses on one night. */
    private const val SAMPLE_MILLIS: Long = 10L * 60 * 1000

    /**
     * Enough halvings to land inside a second.
     *
     * A twelve-hour bracket narrowed by a third each time is under a
     * hundredth of a second after forty passes, which is far past the
     * precision the ephemeris itself claims.
     */
    private const val REFINEMENTS: Int = 40

    /**
     * Every approach inside [withinDegrees] between the two instants.
     *
     * Sampled coarsely, then each local minimum refined. The bodies are
     * positioned once per sample and the pairs read off those positions, rather
     * than positioning each pair separately - fifteen pairs from six bodies, so
     * the difference is five-fold.
     */
    fun approaches(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        untilMillis: Long,
        withinDegrees: Double = NOTABLE_DEGREES,
    ): List<Approach> {
        if (untilMillis <= fromMillis) return emptyList()

        val observer = Observer(latitude, longitude, 0.0)
        // Sampled one step either side of the window that was asked for.
        // A minimum is recognised by being lower than the samples on both
        // sides of it, so without the padding the first and last steps have
        // nothing to be compared against and an approach inside them is
        // invisible. That mattered: the calendar is worked out from the moment
        // the app is opened, so the blind six hours were always the next six.
        val origin = fromMillis - STEP_MILLIS
        val steps = (((untilMillis + STEP_MILLIS) - origin) / STEP_MILLIS).toInt()

        val samples = Array(steps + 1) { step ->
            directionsAt(observer, origin + step * STEP_MILLIS)
        }

        return buildList {
            for (i in WATCHED.indices) {
                for (j in i + 1 until WATCHED.size) {
                    for (step in 1 until steps) {
                        val before = angle(samples[step - 1][i], samples[step - 1][j])
                        val here = angle(samples[step][i], samples[step][j])
                        val after = angle(samples[step + 1][i], samples[step + 1][j])
                        if (here >= before || here >= after) continue

                        val at = refine(
                            observer,
                            WATCHED[i],
                            WATCHED[j],
                            origin + (step - 1) * STEP_MILLIS,
                            origin + (step + 1) * STEP_MILLIS,
                        )
                        // The padding is there to see into the window, not to
                        // widen it, so what it finds outside is dropped.
                        if (at !in fromMillis..untilMillis) continue

                        val apart = separation(WATCHED[i], WATCHED[j], observer, at)
                        if (apart <= withinDegrees) {
                            add(Approach(at, apart, WATCHED[i], WATCHED[j]))
                        }
                    }
                }
            }
        }.sortedBy { it.atMillis }
    }

    /**
     * What that pair is worth on the night nearest the approach.
     *
     * The best moment is the one where the lower of the two altitudes is
     * highest: a pair is only a sight when both halves of it are above the
     * horizon, and the fainter one clearing the roofs is what decides.
     *
     * Null when there is no darkness to see it in, which is the polar summer
     * and not a failure.
     */
    fun asSeen(approach: Approach, latitude: Double, longitude: Double): PairSighting? {
        val night = Twilight.nightNearest(latitude, longitude, approach.atMillis) ?: return null
        val observer = Observer(latitude, longitude, 0.0)

        var best: PairSighting? = null
        var at = night.first
        while (at <= night.last) {
            val time = Time.fromMillisecondsSince1970(at)
            val one = seen(approach.first, observer, time)
            val other = seen(approach.second, observer, time)
            val lower = minOf(one.altitude, other.altitude)

            if (best == null || lower > best.altitude) {
                best = PairSighting(
                    atMillis = at,
                    separationDegrees = angle(direction(one), direction(other)),
                    altitude = lower,
                    azimuth = one.azimuth,
                )
            }
            at += SAMPLE_MILLIS
        }
        return best
    }

    /** How far apart the two look from there, right then. */
    fun separation(
        first: SkyBody,
        second: SkyBody,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): Double = separation(first, second, Observer(latitude, longitude, 0.0), atMillis)

    // --- the search ---

    private fun separation(
        first: SkyBody,
        second: SkyBody,
        observer: Observer,
        atMillis: Long,
    ): Double {
        val time = Time.fromMillisecondsSince1970(atMillis)
        return angle(direction(seen(first, observer, time)), direction(seen(second, observer, time)))
    }

    /**
     * Ternary search on the separation, which is smooth and has one minimum
     * inside a bracket the sampling has already isolated.
     *
     * A derivative-based root find would be quicker and needs a derivative the
     * ephemeris does not offer. This costs a few dozen positions per approach,
     * against tens of thousands for the sweep that found it.
     */
    private fun refine(
        observer: Observer,
        first: SkyBody,
        second: SkyBody,
        fromMillis: Long,
        untilMillis: Long,
    ): Long {
        var low = fromMillis
        var high = untilMillis
        repeat(REFINEMENTS) {
            val third = (high - low) / 3
            if (third == 0L) return@repeat
            val left = low + third
            val right = high - third
            if (separation(first, second, observer, left) <
                separation(first, second, observer, right)
            ) {
                high = right
            } else {
                low = left
            }
        }
        return (low + high) / 2
    }

    /**
     * How a pair looks from here at one particular instant.
     *
     * [asSeen] answers a different question - the best moment of the night -
     * which is the right one for a conjunction and the wrong one for an
     * occultation. An occultation happens at the minimum or not at all, and
     * whether it can be seen from here depends on the pair being up *then*,
     * not on how high they get four hours later.
     */
    fun at(
        first: SkyBody,
        second: SkyBody,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): PairSighting {
        val observer = Observer(latitude, longitude, 0.0)
        val time = Time.fromMillisecondsSince1970(atMillis)
        val one = seen(first, observer, time)
        val other = seen(second, observer, time)
        return PairSighting(
            atMillis = atMillis,
            separationDegrees = angle(direction(one), direction(other)),
            altitude = minOf(one.altitude, other.altitude),
            azimuth = one.azimuth,
        )
    }

    // --- geometry ---

    private fun directionsAt(observer: Observer, atMillis: Long): Array<DoubleArray> {
        val time = Time.fromMillisecondsSince1970(atMillis)
        return Array(WATCHED.size) { direction(seen(WATCHED[it], observer, time)) }
    }

    private fun seen(body: SkyBody, observer: Observer, time: Time): Seen {
        val position = equator(bodyOf(body), time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        val above = horizon(time, observer, position.ra, position.dec, Refraction.Normal)
        return Seen(position.ra, position.dec, above.altitude, above.azimuth)
    }

    private class Seen(
        val rightAscension: Double,
        val declination: Double,
        val altitude: Double,
        val azimuth: Double,
    )

    /** A unit vector, so a separation is one dot product rather than trigonometry. */
    private fun direction(seen: Seen): DoubleArray {
        val ra = Math.toRadians(seen.rightAscension * 15.0)
        val dec = Math.toRadians(seen.declination)
        return doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    private fun angle(one: DoubleArray, other: DoubleArray): Double {
        val dot = one[0] * other[0] + one[1] * other[1] + one[2] * other[2]
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun bodyOf(body: SkyBody) = when (body) {
        // Never reached: the sun is not in WATCHED, because two things being
        // close to it is not a conjunction anybody can look at.
        SkyBody.Sun -> Body.Sun
        SkyBody.Mercury -> Body.Mercury
        SkyBody.Venus -> Body.Venus
        SkyBody.Mars -> Body.Mars
        SkyBody.Jupiter -> Body.Jupiter
        SkyBody.Saturn -> Body.Saturn
        SkyBody.Moon -> Body.Moon
    }
}
