package sh.arek.gloaming.astronomy

import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.localSolarEclipsesAfter
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import kotlin.math.abs
import kotlin.math.roundToInt

/** One moment in an eclipse worth counting down to. */
data class ContactMoment(val label: String, val atMillis: Long)

/**
 * One eclipse, reduced to the moments a person outside is waiting for.
 *
 * [moments] is in time order and never empty. What it contains depends on the
 * eclipse: a partial one has first contact, maximum and last contact, while a
 * total or annular one has the two extra moments that bound the interesting
 * part.
 */
data class EclipseTrack(
    val title: String,
    val body: SkyBody,
    val moments: List<ContactMoment>,
) {
    val startsAt: Long get() = moments.first().atMillis
    val endsAt: Long get() = moments.last().atMillis
}

/**
 * Counting down an eclipse, contact by contact.
 *
 * **Any eclipse, not only a total solar one.** The design offers this as a
 * totality countdown, and totality is the one case it would almost never be
 * used for: from Edinburgh the next six solar eclipses are all partial, with
 * obscurations between 17 and 64 per cent, and there is no totality for
 * decades. Knowing when first contact is - so the filter goes on the telescope
 * and the glasses come out - is the actual need, and it is the same countdown.
 *
 * **Lunar eclipses too, against the design's note that they have nothing to
 * point at.** They do: the moon has an azimuth and an altitude like anything
 * else, and the eclipse of 28 August 2026 peaks with it seven degrees above the
 * horizon at Edinburgh. At that height where to look is the whole question. The
 * real objection in that note is length - lunar eclipses run for hours - and
 * counting to the *next contact* rather than through the whole event answers
 * it, with a cap of its own for the same reason.
 */
object EclipseTracks {

    /** How far a re-derived eclipse may sit from the one asked for. */
    const val SAME_ECLIPSE_TOLERANCE_MILLIS: Long = 60 * 60 * 1000L

    /** How far back to start searching, so an eclipse already under way is found. */
    private const val SEARCH_BACK_DAYS: Double = 2.0

    /** How many eclipses to look at before giving up on a match. */
    private const val CANDIDATES: Int = 4

    /**
     * The eclipse peaking near [peakAtMillis], as seen from here.
     *
     * Re-derived rather than carried through an intent, the same as a pass is:
     * the countdown ought to be the best answer the device can give now, not
     * the one it gave when it put the notification up.
     */
    fun forPeak(
        latitude: Double,
        longitude: Double,
        peakAtMillis: Long,
        toleranceMillis: Long = SAME_ECLIPSE_TOLERANCE_MILLIS,
    ): EclipseTrack? {
        val observer = Observer(latitude, longitude, 0.0)
        val from = Time.fromMillisecondsSince1970(peakAtMillis).addDays(-SEARCH_BACK_DAYS)

        val solar = localSolarEclipsesAfter(from, observer)
            .take(CANDIDATES)
            .firstOrNull { abs(it.peak.time.toMillisecondsSince1970() - peakAtMillis) <= toleranceMillis }
        if (solar != null) {
            return EclipseTrack(
                title = "${word(solar.kind)} eclipse of the sun",
                body = SkyBody.Sun,
                moments = listOfNotNull(
                    ContactMoment("first contact", solar.partialBegin.time.toMillisecondsSince1970()),
                    solar.totalBegin?.let {
                        ContactMoment(inner(solar.kind), it.time.toMillisecondsSince1970())
                    },
                    ContactMoment("maximum", solar.peak.time.toMillisecondsSince1970()),
                    solar.totalEnd?.let {
                        ContactMoment(outer(solar.kind), it.time.toMillisecondsSince1970())
                    },
                    ContactMoment("last contact", solar.partialEnd.time.toMillisecondsSince1970()),
                ).sortedBy { it.atMillis },
            )
        }

        val lunar = lunarEclipsesAfter(from)
            .take(CANDIDATES)
            .filter { it.kind != EclipseKind.Penumbral }
            .firstOrNull { abs(it.peak.toMillisecondsSince1970() - peakAtMillis) <= toleranceMillis }
            ?: return null

        val peak = lunar.peak.toMillisecondsSince1970()
        val umbral = (lunar.sdPartial * 60_000L).toLong()
        val total = (lunar.sdTotal * 60_000L).toLong()

        return EclipseTrack(
            title = "${word(lunar.kind)} eclipse of the moon",
            body = SkyBody.Moon,
            moments = buildList {
                add(ContactMoment("the shadow first touches", peak - umbral))
                if (total > 0) add(ContactMoment("totality begins", peak - total))
                add(ContactMoment("maximum", peak))
                if (total > 0) add(ContactMoment("totality ends", peak + total))
                add(ContactMoment("the shadow leaves", peak + umbral))
            }.sortedBy { it.atMillis },
        )
    }

    /** The moment still ahead, or null once the last one has passed. */
    fun nextMoment(track: EclipseTrack, nowMillis: Long): ContactMoment? =
        track.moments.firstOrNull { it.atMillis > nowMillis }

    /** Whether there is anything left of it to watch. */
    fun isOver(track: EclipseTrack, nowMillis: Long): Boolean = nowMillis > track.endsAt

    /**
     * The one line on the ongoing notification.
     *
     * Written for somebody holding a phone at arm's length outdoors: what is
     * next, how long until it, and which way to look. The safety clause on a
     * solar eclipse is not decoration - this is the one thing the app will ever
     * tell somebody to look at that can blind them.
     */
    fun describe(track: EclipseTrack, where: Sighting?, nowMillis: Long): String {
        val next = nextMoment(track, nowMillis)
            ?: return if (track.body == SkyBody.Sun) "Over. Safe to look away." else "Over."

        val timing = "${next.label} in ${countdown(next.atMillis - nowMillis)}"
        val place = when {
            where == null -> ""
            where.altitude <= 0 -> ". Below the horizon from here"
            else -> ". Look ${Compass.of(where.azimuth).label}, " +
                "${where.altitude.roundToInt()} degrees up"
        }
        val safety = if (track.body == SkyBody.Sun) {
            ". Never without a proper solar filter"
        } else {
            ""
        }
        return "$timing$place$safety."
    }

    /**
     * Minutes and seconds, padded, then hours once there are any.
     *
     * The same padding rule as the pass countdown and for the same reason: the
     * line is rewritten every second, and one that changes width as it counts
     * reads as a glitch rather than a clock.
     */
    fun countdown(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 500L) / 1000L
        if (seconds < 60L) return "${seconds}s"
        val minutes = seconds / 60L
        if (minutes < 60L) {
            return "${minutes}m ${(seconds % 60L).toString().padStart(2, '0')}s"
        }
        return "${minutes / 60L}h ${(minutes % 60L).toString().padStart(2, '0')}m"
    }

    private fun word(kind: EclipseKind) = when (kind) {
        EclipseKind.Total -> "Total"
        EclipseKind.Annular -> "Annular"
        else -> "Partial"
    }

    private fun inner(kind: EclipseKind) =
        if (kind == EclipseKind.Annular) "the ring closes" else "totality begins"

    private fun outer(kind: EclipseKind) =
        if (kind == EclipseKind.Annular) "the ring breaks" else "totality ends"
}
