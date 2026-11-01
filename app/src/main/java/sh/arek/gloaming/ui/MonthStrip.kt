package sh.arek.gloaming.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Twilight

/**
 * One night of a month: how much the moon spoils it and whether anything
 * dated falls on it.
 *
 * @property moonlight [Twilight.moonInterference] for the night that starts on
 *   this date's evening, 0 (no moon in the dark) to 1 (full moon up all night).
 * @property past the night is over; it is drawn, muted, but never counts as the
 *   darkest run.
 * @property today the night that starts this evening.
 */
data class NightCell(
    val day: Int,
    val moonlight: Double,
    val hasEvent: Boolean,
    val past: Boolean = false,
    val today: Boolean = false,
)

/**
 * A month of [NightCell]s, with the longest run of dark nights already found.
 *
 * @property month 1 to 12.
 * @property name the month's name in the device's language, without the year.
 * @property darkFrom the first day of the longest run of nights below
 *   [MonthStrips.DARK_BELOW], or null when there is none.
 */
data class MonthStrip(
    val year: Int,
    val month: Int,
    val name: String,
    val nights: List<NightCell>,
    val darkFrom: Int?,
    val darkUntil: Int?,
) {
    /**
     * The darkest run said in words, so that it never depends on telling two
     * shades apart: with night vision on there is only one colour to shade with.
     */
    fun darkestWords(): String = when {
        darkFrom == null || darkUntil == null -> "No dark nights this month"
        darkFrom == darkUntil -> "Darkest: $darkFrom $name"
        else -> "Darkest: $darkFrom-$darkUntil $name"
    }

    /** The whole strip for a screen reader: the shading cannot be read aloud. */
    fun description(): String {
        val days = nights.filter { it.hasEvent }.map { it.day }
        val events = when (days.size) {
            0 -> "No events."
            1 -> "Events on ${days[0]} $name."
            else -> "Events on ${days.dropLast(1).joinToString(", ")} and ${days.last()} $name."
        }
        val today = nights.firstOrNull { it.today }?.let { " Today is ${it.day} $name." }.orEmpty()
        return "$name $year. ${darkestWords()}. $events$today"
    }
}

/** Works out the strips for the months ahead. Pure and clock-free. */
object MonthStrips {

    /** Moonlight below which a night counts as dark, for naming the darkest run. */
    const val DARK_BELOW: Double = 0.3

    /** Months shown on Ahead: the one the calendar starts in and the eleven after. */
    const val YEAR_AHEAD: Int = 12

    /**
     * [months] strips, the first for the month [fromMillis] falls in, in the
     * device's time zone. The date of [fromMillis] is today: earlier nights are
     * flagged [NightCell.past] and left out of the darkest run.
     *
     * A night is aimed at midnight at the end of its date, which the nearest
     * night search turns into the dusk-to-dawn stretch around it. A dot goes on
     * any day an event in [events] is dated to.
     *
     * Slow enough (a year measures about 0.13 s on a desktop JVM, more on a
     * phone) that the caller keeps it off the main thread.
     */
    fun of(
        latitude: Double,
        longitude: Double,
        fromMillis: Long,
        months: Int,
        events: List<SkyEvent>,
    ): List<MonthStrip> {
        val eventDays = events.groupBy { dayKey(it.at) }.keys
        val today = dayKey(fromMillis)
        val names = SimpleDateFormat("MMMM", Locale.getDefault())

        val cursor = Calendar.getInstance()
        cursor.timeInMillis = fromMillis
        cursor.set(Calendar.DAY_OF_MONTH, 1)
        cursor.set(Calendar.HOUR_OF_DAY, 12)
        cursor.set(Calendar.MINUTE, 0)
        cursor.set(Calendar.SECOND, 0)
        cursor.set(Calendar.MILLISECOND, 0)

        return List(months) {
            val year = cursor.get(Calendar.YEAR)
            val month = cursor.get(Calendar.MONTH) + 1
            val name = names.format(cursor.time)
            val length = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)

            val nights = (1..length).map { day ->
                cursor.set(Calendar.DAY_OF_MONTH, day)
                val midnight = Calendar.getInstance()
                midnight.timeInMillis = cursor.timeInMillis
                midnight.add(Calendar.HOUR_OF_DAY, 12)
                val key = year * 10_000 + month * 100 + day
                NightCell(
                    day = day,
                    moonlight = Twilight.moonInterference(latitude, longitude, midnight.timeInMillis),
                    hasEvent = key in eventDays,
                    past = key < today,
                    today = key == today,
                )
            }
            val run = darkestRun(nights)

            cursor.set(Calendar.DAY_OF_MONTH, 1)
            cursor.add(Calendar.MONTH, 1)
            MonthStrip(year, month, name, nights, run?.first, run?.last)
        }
    }

    /** The longest stretch of consecutive dark nights; the first wins a tie. */
    private fun darkestRun(nights: List<NightCell>): IntRange? {
        var best: IntRange? = null
        var start = -1
        for ((index, night) in nights.withIndex()) {
            if (night.moonlight < DARK_BELOW && !night.past) {
                if (start < 0) start = index
                val run = nights[start].day..night.day
                if (best == null || run.last - run.first > best.last - best.first) best = run
            } else {
                start = -1
            }
        }
        return best
    }

    private fun dayKey(atMillis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = atMillis
        return calendar.get(Calendar.YEAR) * 10_000 +
            (calendar.get(Calendar.MONTH) + 1) * 100 +
            calendar.get(Calendar.DAY_OF_MONTH)
    }
}
