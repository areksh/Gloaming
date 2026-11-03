package sh.arek.gloaming.ui

import kotlinx.serialization.Serializable
import sh.arek.gloaming.astronomy.Planets
import sh.arek.gloaming.astronomy.SkyBody
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.TonightList

/** What a bar on the chart is. */
enum class UpKind { Moon, Planet, Comet }

/**
 * One bar: how high something is through the chart's span.
 *
 * @property samples altitude in degrees every [UpCharts.STEP_MILLIS] from the
 *   chart's start, the last at its end. Below zero is below the horizon.
 * @property body which planet, for its glyph; null for the moon and a comet.
 */
@Serializable
data class UpBody(
    val kind: UpKind,
    val label: String,
    val body: SkyBody?,
    val samples: List<Float>,
    val peakAt: Long,
    val peakAltitude: Double,
)

/** A recommended moment on the chart, and the planet it is about if it is one. */
@Serializable
data class PickMark(val atMillis: Long, val tag: PickTag, val body: SkyBody?)

/**
 * Tonight's moon, planets and comets as bars across the night, with the
 * outlook's picks marked on them.
 *
 * Wider than the night on both sides, so a planet low in the evening
 * twilight - Venus, or Mercury when it shows at all - is drawn rising out of
 * the daylight rather than starting at the chart's edge.
 */
@Serializable
data class UpChart(
    val fromMillis: Long,
    val untilMillis: Long,
    val sunsetMillis: Long,
    val sunriseMillis: Long,
    /** The sun's altitude on the same step as the bars, for the daylight either end. */
    val sun: List<SunSample>,
    val bodies: List<UpBody>,
    val marks: List<PickMark>,
)

/** Works out the [UpChart] and says it in words. Pure, apart from the sky's own positions. */
object UpCharts {

    const val STEP_MILLIS: Long = 15 * 60 * 1000L

    /** How far the chart runs past sunset and sunrise. */
    private const val MARGIN_MILLIS: Long = 60 * 60 * 1000L

    /** A comet has no curve worked out, only a window; drawn at a middling height through it. */
    private const val COMET_ALTITUDE = 30f

    private const val BELOW = -90f

    /**
     * Null where there is nothing to draw: no moon to speak of and no planet
     * or comet up. An empty frame would read as a chart that failed.
     */
    fun of(
        list: TonightList,
        trip: List<Pick>,
        also: List<Pick>,
        latitude: Double,
        longitude: Double,
        sunsetMillis: Long,
        sunriseMillis: Long,
    ): UpChart? {
        if (list.moon == null && list.planets.isEmpty() && list.comets.isEmpty()) return null
        val from = sunsetMillis - MARGIN_MILLIS
        val until = sunriseMillis + MARGIN_MILLIS
        val times = generateSequence(from) { it + STEP_MILLIS }.takeWhile { it < until }.toList() + until

        fun curve(altitude: (Long) -> Double): Pair<List<Float>, Int> {
            val samples = times.map { altitude(it).toFloat() }
            return samples to samples.indices.maxBy { samples[it] }
        }

        val bodies = mutableListOf<UpBody>()
        list.moon?.let {
            val (samples, peak) = curve { t -> Twilight.moonAltitude(latitude, longitude, t) }
            bodies += UpBody(UpKind.Moon, "Moon", null, samples, times[peak], samples[peak].toDouble())
        }
        for (planet in list.planets) {
            val (samples, peak) = curve { t -> Planets.altitude(planet.body, latitude, longitude, t) }
            bodies += UpBody(UpKind.Planet, planet.body.label, planet.body, samples, times[peak], samples[peak].toDouble())
        }
        for (comet in list.comets) {
            val samples = times.map { if (it in comet.from..comet.until) COMET_ALTITUDE else BELOW }
            bodies += UpBody(UpKind.Comet, comet.title, null, samples, comet.at, COMET_ALTITUDE.toDouble())
        }

        return UpChart(
            fromMillis = from,
            untilMillis = until,
            sunsetMillis = sunsetMillis,
            sunriseMillis = sunriseMillis,
            sun = times.map { SunSample(it, Twilight.sunAltitude(latitude, longitude, it)) },
            bodies = bodies,
            marks = trip.map { PickMark(it.atMillis, PickTag.Go, it.planet?.body) } +
                also.map { PickMark(it.atMillis, PickTag.Also, it.planet?.body) },
        )
    }

    /** The moment of sample [index]. */
    fun timeOf(chart: UpChart, index: Int): Long = minOf(chart.fromMillis + index * STEP_MILLIS, chart.untilMillis)

    /**
     * The chart in words, for a screen reader: who is up between sunset and
     * sunrise, and when.
     */
    fun summary(chart: UpChart): String {
        val night = chart.sunsetMillis..chart.sunriseMillis
        val up = mutableListOf<String>()
        val down = mutableListOf<String>()
        for (body in chart.bodies) {
            val moments = body.samples.indices.filter { body.samples[it] > 0f }.map { timeOf(chart, it) }.filter { it in night }
            val name = if (body.kind == UpKind.Moon) "the moon" else body.label
            if (moments.isEmpty()) {
                down += "${name.replaceFirstChar { it.uppercase() }} is down all night."
                continue
            }
            val from = moments.first()
            val until = moments.last()
            val start = chart.sunsetMillis + STEP_MILLIS
            val end = chart.sunriseMillis - STEP_MILLIS
            up += when {
                from <= start && until >= end -> "$name all night"
                from <= start -> "$name until ${clock(until)}"
                until >= end -> "$name from ${clock(from)}"
                else -> "$name from ${clock(from)} to ${clock(until)}"
            }
        }
        val sentences = mutableListOf<String>()
        if (up.isNotEmpty()) sentences += "Up tonight: " + up.joinToString(", ") + "."
        sentences += down
        return sentences.joinToString(" ")
    }
}
