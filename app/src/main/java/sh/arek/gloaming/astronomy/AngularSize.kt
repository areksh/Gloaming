package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import kotlin.math.asin

/**
 * How big something looks from where the observer is standing.
 *
 * Needed for one question the app could not previously ask: whether the moon
 * passing close to a planet actually passes *in front of* it. The conjunction
 * machinery already finds the separation - `ConjunctionsTest` pins Venus at
 * 0.069 degrees from the moon - and reports every such pairing as "passes
 * close to", which for an occultation is a considerable understatement. A
 * planet vanishing behind the moon's edge and reappearing an hour later is one
 * of the few naked-eye events that looks like something happening rather than
 * something being.
 *
 * **Topocentric, and for the moon that is not a detail.** The moon has up to a
 * degree of parallax, which is about two of its own diameters: a geocentric
 * distance would put its edge in the wrong place by more than the whole
 * question turns on, and an occultation is a fact about a place rather than
 * about the Earth. Two observers a few hundred miles apart genuinely see
 * different answers, which is why occultation predictions are published per
 * site.
 *
 * The radii are here rather than taken from the vendored engine because the
 * engine's own constants are private to it (see `app/src/main/vendor/README.md`
 * on not editing vendored source), and `IlluminationInfo` carries no geocentric
 * distance to work back from.
 */
object AngularSize {

    /** Metres, from the IAU 2015 nominal values and the IAU mean radii. */
    private val RADIUS_METRES = mapOf(
        SkyBody.Moon to 1_737_400.0,
        SkyBody.Sun to 695_700_000.0,
        SkyBody.Mercury to 2_439_700.0,
        SkyBody.Venus to 6_051_800.0,
        SkyBody.Mars to 3_389_500.0,
        // Equatorial rather than mean for the two that are visibly flattened.
        // What an occultation cares about is the disc's extent across the sky,
        // and it is the equator that reaches furthest.
        SkyBody.Jupiter to 71_492_000.0,
        SkyBody.Saturn to 60_268_000.0,
    )

    /** The engine works in astronomical units; everything else here is metres. */
    private const val AU_METRES = 149_597_870_700.0

    /**
     * Half the apparent width of [body], in degrees, as seen from the observer.
     *
     * The semi-diameter rather than the diameter, because that is the number an
     * occultation test compares a separation against: two centres are one disc
     * apart when the separation equals the sum of the two semi-diameters.
     */
    fun semiDiameterDegrees(
        body: SkyBody,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): Double {
        val radius = RADIUS_METRES[body] ?: return 0.0
        val time = Time.fromMillisecondsSince1970(atMillis)
        val observer = Observer(latitude, longitude, 0.0)
        val distance = equator(
            engineBody(body),
            time,
            observer,
            EquatorEpoch.OfDate,
            Aberration.Corrected,
        ).dist * AU_METRES

        if (distance <= radius) return 0.0
        return Math.toDegrees(asin(radius / distance))
    }

    /**
     * Whether the moon's disc covers [body] at this moment, from here.
     *
     * The planet's own semi-diameter counts: Jupiter is about 20 arcseconds
     * across, so "the centres are one lunar radius apart" and "Jupiter is
     * entirely hidden" are not the same instant. What is tested is first
     * contact - the moment the discs touch - which is when there is something
     * to see.
     */
    fun moonCovers(
        body: SkyBody,
        separationDegrees: Double,
        latitude: Double,
        longitude: Double,
        atMillis: Long,
    ): Boolean {
        if (body == SkyBody.Moon) return false
        val moon = semiDiameterDegrees(SkyBody.Moon, latitude, longitude, atMillis)
        val other = semiDiameterDegrees(body, latitude, longitude, atMillis)
        return separationDegrees <= moon + other
    }

    private fun engineBody(body: SkyBody) = when (body) {
        SkyBody.Sun -> Body.Sun
        SkyBody.Moon -> Body.Moon
        SkyBody.Mercury -> Body.Mercury
        SkyBody.Venus -> Body.Venus
        SkyBody.Mars -> Body.Mars
        SkyBody.Jupiter -> Body.Jupiter
        SkyBody.Saturn -> Body.Saturn
    }
}
