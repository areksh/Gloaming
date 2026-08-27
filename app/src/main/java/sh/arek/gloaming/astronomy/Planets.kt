package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.searchRiseSet

/** One planet's night: how high it gets, which way to look, and when. */
data class PlanetTonight(
    val body: SkyBody,
    /** The highest it gets inside tonight's darkness, in degrees. */
    val peakAltitude: Double,
    /** When it is at that altitude. */
    val peakAt: Long,
    /** Degrees clockwise from north at that moment. */
    val peakAzimuth: Double,
    /** Visual magnitude then. Smaller is brighter. */
    val magnitude: Double,
    /** When it rises inside tonight's darkness, if it does. */
    val risesAt: Long?,
    /** When it sets inside tonight's darkness, if it does. */
    val setsAt: Long?,
)

/**
 * The naked-eye planets, for the night rather than for the year.
 *
 * The app has been able to put all six bodies in the sky topocentrically since
 * the conjunctions went in, and the calendar already dates the one night a year
 * each is at opposition. Neither answers the question somebody standing in a
 * garden actually asks, which is what is up now and where to look - and that is
 * the question a planet is best at, because unlike an aurora or a comet the
 * answer is certain months in advance and never disappoints.
 *
 * Topocentric, like [Conjunctions] and for the same reason: an altitude is a
 * fact about a place. Refraction is applied here where [Twilight] deliberately
 * omits it, because these numbers are read by somebody looking at the sky
 * rather than compared against a window boundary computed airless.
 */
object Planets {

    /**
     * The planets anybody can see without equipment.
     *
     * Uranus and Neptune are out for the same reason the rest of the app leaves
     * them out: if it needs a telescope it is out of scope.
     */
    private val WATCHED = listOf(
        SkyBody.Venus,
        SkyBody.Jupiter,
        SkyBody.Mars,
        SkyBody.Saturn,
        SkyBody.Mercury,
    )

    /**
     * How high a planet must get before it is worth a line.
     *
     * Lower than the comet bar, because a planet is a bright point rather than
     * a smudge and the haze that erases a comet at ten degrees leaves Jupiter
     * perfectly obvious. Roughly where a clear horizon runs out.
     */
    const val VISIBLE_DEGREES: Double = 5.0

    /**
     * Mercury's own bar, which is much higher.
     *
     * Mercury is never far from the sun, so it spends most of the year a few
     * degrees up in a twilight sky that is still bright - technically above the
     * horizon, and in practice invisible. At the common bar it would print a
     * line nobody could act on most nights of the year, which is the fastest
     * way to teach somebody that this card is not worth reading.
     */
    const val MERCURY_DEGREES: Double = 10.0

    /** Ten minutes, matching every other sweep of a night in the app. */
    private const val SAMPLE_MILLIS: Long = 10L * 60 * 1000

    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** `searchRiseSet` rejects a non-positive span, and a zero-length night is one. */
    private const val MINIMUM_DAYS: Double = 1e-6

    /**
     * Every planet worth looking for inside [night], highest first.
     *
     * Highest rather than brightest or earliest: somebody reading this wants to
     * know what they will actually be able to find, and forty degrees up beats
     * half a magnitude every time. Empty is an ordinary answer - a night with
     * no planet above the bar happens often enough.
     */
    fun tonight(latitude: Double, longitude: Double, night: LongRange): List<PlanetTonight> {
        if (night.isEmpty()) return emptyList()
        val observer = Observer(latitude, longitude, 0.0)

        return WATCHED
            .mapNotNull { seen(it, observer, night) }
            .filter { it.peakAltitude >= barFor(it.body) }
            .sortedByDescending { it.peakAltitude }
    }

    private fun barFor(body: SkyBody) =
        if (body == SkyBody.Mercury) MERCURY_DEGREES else VISIBLE_DEGREES

    private fun seen(body: SkyBody, observer: Observer, night: LongRange): PlanetTonight? {
        val engineBody = bodyOf(body)

        var peakAt = night.first
        var peakAltitude = Double.NEGATIVE_INFINITY
        var peakAzimuth = 0.0

        // Swept rather than solved. A planet's transit could be found exactly
        // from its hour angle, but the highest it gets *inside a window* is a
        // different question - most nights the transit falls outside the
        // darkness, and then the answer is one end of the window rather than
        // the transit at all.
        var at = night.first
        while (at <= night.last) {
            val time = Time.fromMillisecondsSince1970(at)
            val position = equator(engineBody, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
            val above = horizon(time, observer, position.ra, position.dec, Refraction.Normal)
            if (above.altitude > peakAltitude) {
                peakAltitude = above.altitude
                peakAzimuth = above.azimuth
                peakAt = at
            }
            at += SAMPLE_MILLIS
        }

        if (peakAltitude == Double.NEGATIVE_INFINITY) return null

        val peakTime = Time.fromMillisecondsSince1970(peakAt)
        val days = ((night.last - night.first).toDouble() / DAY_MILLIS).coerceAtLeast(MINIMUM_DAYS)
        val from = Time.fromMillisecondsSince1970(night.first)

        fun crossing(direction: Direction): Long? =
            searchRiseSet(engineBody, observer, direction, from, days)
                ?.toMillisecondsSince1970()
                ?.takeIf { it <= night.last }

        return PlanetTonight(
            body = body,
            peakAltitude = peakAltitude,
            peakAt = peakAt,
            peakAzimuth = peakAzimuth,
            magnitude = illumination(engineBody, peakTime).mag,
            risesAt = crossing(Direction.Rise),
            setsAt = crossing(Direction.Set),
        )
    }

    private fun bodyOf(body: SkyBody) = when (body) {
        SkyBody.Mercury -> Body.Mercury
        SkyBody.Venus -> Body.Venus
        SkyBody.Mars -> Body.Mars
        SkyBody.Jupiter -> Body.Jupiter
        SkyBody.Saturn -> Body.Saturn
        // Neither is in WATCHED, and neither is a planet. The sun is not
        // something to look for and the moon has a card of its own.
        SkyBody.Sun -> Body.Sun
        SkyBody.Moon -> Body.Moon
    }
}
