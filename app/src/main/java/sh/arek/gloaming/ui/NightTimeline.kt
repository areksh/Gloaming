package sh.arek.gloaming.ui

import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight
import sh.arek.gloaming.data.HourlyCover
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.TonightList
import sh.arek.gloaming.satellites.SatellitePass

/** How loudly a row speaks: dimmed behind cloud, strong where the outlook sends you out. */
enum class RowTone { Normal, Dim, Strong }

/** How a lane is drawn, so the moon and two planets can run side by side without a key. */
enum class LaneStyle { Solid, Dashed, Dotted }

/** What a row is about, so the screen can give it an icon and a tap target. */
sealed interface RowSubject {
    data object Twilight : RowSubject
    data object Clear : RowSubject
    data object Moon : RowSubject
    data class Pass(val pass: SatellitePass) : RowSubject
    data class Planet(val planet: PlanetTonight) : RowSubject
    data class Event(val event: SkyEvent) : RowSubject
}

data class TimelineRow(
    val atMillis: Long,
    /** Local clock time, as read off the device's own zone. */
    val time: String,
    val title: String,
    val detail: String?,
    val tone: RowTone,
    val subject: RowSubject,
)

/** A stretch of forecast cloud cover, in percent, drawn as shading down the time axis. */
data class CloudSpan(val fromMillis: Long, val untilMillis: Long, val cover: Int)

/** Something that is up for a stretch of the night: the moon or a bright planet. */
data class Lane(val label: String, val style: LaneStyle, val fromMillis: Long, val untilMillis: Long)

/**
 * Tonight from sunset to sunrise, as data for a time-proportional axis.
 *
 * Everything here is in real milliseconds, never clock readings: on the night
 * the clocks change the axis must be as long as the night really is, and only
 * the labels follow the wall clock.
 */
data class Timeline(
    val startMillis: Long,
    val endMillis: Long,
    val rows: List<TimelineRow>,
    val cloud: List<CloudSpan>,
    val lanes: List<Lane>,
    /** Where now falls on the axis, null once the night is over or before it starts. */
    val nowMillis: Long?,
)

/** Builds the [Timeline] and does the arithmetic of laying its rows out. Pure and clock-free. */
object NightTimeline {

    private const val HOUR_MILLIS: Long = 60 * 60 * 1000L
    private const val MAX_PLANET_LANES: Int = 2

    /**
     * Null when there is no sunset or no sunrise to bound the night - the
     * midnight sun, the polar night, or no position at all. A night with
     * no civil darkness but a sunset and sunrise still has a timeline.
     *
     * Passes are the list's best ones only, so every pass row has a verdict
     * from the outlook to match. Passes and events match a verdict on subject
     * and moment; planets on subject alone, because the moment a planet is
     * judged at moves with the clock.
     */
    fun of(
        list: TonightList,
        report: OutlookReport,
        hourly: List<HourlyCover>,
        settings: UserSettings,
        nowMillis: Long,
    ): Timeline? {
        val latitude = settings.latitude ?: return null
        val longitude = settings.longitude ?: return null
        val darkFrom = list.darkFrom
        val darkUntil = list.darkUntil

        // Without civil darkness (a northern midsummer) the sun still sets and
        // rises, so the night is anchored on now: the coming sunset and the
        // sunrise after it.
        val night = if (darkFrom != null && darkUntil != null) {
            darkFrom..darkUntil
        } else {
            nowMillis..(nowMillis + 24 * HOUR_MILLIS)
        }
        val sun = Twilight.sunTimes(latitude, longitude, night)
        val start = sun.setsAt ?: return null
        val end = sun.risesAt ?: return null
        if (end <= start) return null

        val picks = (report.outlook as? Outlook.GoOut)?.picks.orEmpty()
        val cloudy = report.excluded.filter { it.gate == Gate.Cloud }

        fun toneOf(picked: Boolean, dimmed: Boolean) = when {
            picked -> RowTone.Strong
            dimmed -> RowTone.Dim
            else -> RowTone.Normal
        }

        val rows = mutableListOf<TimelineRow>()

        fun row(at: Long, title: String, subject: RowSubject, detail: String? = null, tone: RowTone = RowTone.Normal) {
            if (at in start..end) rows += TimelineRow(at, clock(at), title, detail, tone, subject)
        }

        row(start, "Sunset", RowSubject.Twilight)
        darkFrom?.let { row(it, "Dark enough for aurora", RowSubject.Twilight) }
        list.darkestFrom?.takeIf { it != darkFrom }?.let { row(it, "Fully dark", RowSubject.Twilight) }

        for (pass in list.bestPasses) {
            val picked = picks.any { it.kind == PickKind.Pass && it.subject == pass.name && it.atMillis == pass.peaksAt }
            val dimmed = cloudy.any { it.subject == pass.name && it.atMillis == pass.peaksAt }
            row(
                pass.peaksAt,
                "${pass.name}, ${pass.peakElevation.roundToInt()} degrees high",
                RowSubject.Pass(pass),
                "Rises ${Compass.of(pass.riseAzimuth).label}, sets ${Compass.of(pass.setAzimuth).label}",
                toneOf(picked, dimmed),
            )
        }

        list.moon?.let { moon ->
            moon.risesAt?.let { row(it, "Moonrise", RowSubject.Moon) }
            moon.setsAt?.let { row(it, "Moonset", RowSubject.Moon) }
        }

        for (planet in list.planets) {
            val name = planet.body.label
            val picked = picks.any { it.kind == PickKind.Planet && it.subject == name }
            val dimmed = cloudy.any { it.subject == name }
            val tone = toneOf(picked, dimmed)
            val subject = RowSubject.Planet(planet)
            planet.risesAt?.let { row(it, "$name rises", subject, tone = tone) }
            row(
                planet.peakAt,
                "$name highest, ${planet.peakAltitude.roundToInt()} degrees",
                subject,
                "In the ${Compass.of(planet.peakAzimuth).label}",
                tone,
            )
            planet.setsAt?.let { row(it, "$name sets", subject, tone = tone) }
        }

        for (event in list.events) {
            val picked = picks.any { it.kind == PickKind.Event && it.subject == event.title && it.event?.at == event.at }
            val dimmed = cloudy.any { it.subject == event.title && it.atMillis == event.at }
            row(event.at, event.title, RowSubject.Event(event), tone = toneOf(picked, dimmed))
        }

        list.clearSpell?.let { spell ->
            row(spell.from, "Clear until ${clock(spell.until)}", RowSubject.Clear, tone = RowTone.Strong)
            if (!spell.allNight && spell.until < end) row(spell.until, "Cloud returns", RowSubject.Clear)
        }

        (list.darkestUntil ?: darkUntil)?.let { row(it, "First light", RowSubject.Twilight) }
        row(end, "Sunrise", RowSubject.Twilight)

        return Timeline(
            startMillis = start,
            endMillis = end,
            rows = rows.sortedBy { it.atMillis },
            cloud = cloudSpans(hourly, start, end),
            lanes = lanes(list, start, end),
            nowMillis = nowMillis.takeIf { it in start..end },
        )
    }

    /**
     * Top offsets that keep rows apart while holding each as near its time as it can.
     *
     * [idealTops] must be in row order. A row is only ever pushed down, to
     * exactly [gap] below the one above it, never up past its own time.
     */
    fun layout(idealTops: List<Int>, heights: List<Int>, gap: Int): List<Int> {
        val tops = IntArray(idealTops.size)
        for (i in idealTops.indices) {
            tops[i] = if (i == 0) idealTops[0] else maxOf(idealTops[i], tops[i - 1] + heights[i - 1] + gap)
        }
        return tops.toList()
    }

    private fun cloudSpans(hourly: List<HourlyCover>, start: Long, end: Long): List<CloudSpan> =
        hourly.sortedBy { it.atMillis }.mapNotNull {
            val from = maxOf(it.atMillis, start)
            val until = minOf(it.atMillis + HOUR_MILLIS, end)
            if (from < until) CloudSpan(from, until, it.effective) else null
        }

    private fun lanes(list: TonightList, start: Long, end: Long): List<Lane> {
        val lanes = mutableListOf<Lane>()

        list.moon?.let { moon ->
            fun add(from: Long, until: Long) {
                val f = from.coerceIn(start, end)
                val u = until.coerceIn(start, end)
                if (f < u) lanes += Lane("Moon", LaneStyle.Solid, f, u)
            }
            val rises = moon.risesAt
            val sets = moon.setsAt
            when {
                rises != null && sets != null && sets < rises -> {
                    add(start, sets)
                    add(rises, end)
                }
                rises != null && sets != null -> add(rises, sets)
                rises != null -> add(rises, end)
                sets != null -> add(start, sets)
                moon.upFraction >= 0.5 -> add(start, end)
            }
        }

        list.planets.sortedBy { it.magnitude }.take(MAX_PLANET_LANES).forEachIndexed { index, planet ->
            val from = (planet.risesAt ?: start).coerceIn(start, end)
            val until = (planet.setsAt ?: end).coerceIn(start, end)
            if (from < until) {
                lanes += Lane(
                    planet.body.label,
                    if (index == 0) LaneStyle.Dashed else LaneStyle.Dotted,
                    from,
                    until,
                )
            }
        }
        return lanes
    }
}
