package sh.arek.gloaming.ui

import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.deviceLocalMinuteOfDay
import sh.arek.gloaming.satellites.IssCoverage

/**
 * The sentences the screens are made of, apart from the screens themselves.
 *
 * Pure functions over already-resolved state, which is why they had their own
 * test files long before they had their own file: nothing here needs a device,
 * a clock or a composition to check.
 */

/**
 * Why the app is or is not checking at the moment.
 *
 * The schedule follows the sky, which means it deliberately does nothing for up
 * to twelve hours at a stretch. Without this sentence the screen says only
 * "checked 11 hours ago", and correct behaviour is indistinguishable from a
 * background service the system has killed - which is the reading that gets an
 * app uninstalled.
 *
 * @param localMinuteOfDay injected so the wording can be tested without
 *   depending on the machine's time zone.
 */
fun describeSchedule(
    darkness: Darkness,
    localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
): String = when (darkness) {
    is Darkness.Now -> "Dark now, so Gloaming is watching."

    is Darkness.Later ->
        "Sun is up. Nothing to see until it gets dark, around " +
            "${formatTimeOfDay(localMinuteOfDay(darkness.startsAt))}."

    Darkness.None ->
        "It does not get dark here at this time of year. " +
            "Gloaming checks twice a day until that changes."
}

/**
 * Turns a timestamp into the age the user actually cares about.
 *
 * Kept as plain text rather than a relative-time widget so "never" and "just
 * now" read the same way as everything else on the screen.
 */
fun describeAge(lastPollAt: Long?, now: Long): String {
    if (lastPollAt == null) return "Not checked yet"
    val minutes = ((now - lastPollAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Checked just now"
        minutes < 60 -> "Checked $minutes minute${plural(minutes)} ago"
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            "Checked $hours hour${plural(hours)} ago"
        }
        else -> {
            val days = minutes / (60 * 24)
            "Last checked $days day${plural(days)} ago"
        }
    }
}

internal fun plural(value: Long) = if (value == 1L) "" else "s"

/**
 * Why the station never appears, for a user it cannot reach.
 *
 * Null wherever the station does pass, which is most places anyone lives. The
 * app has no pass list that could show up empty - it speaks only through
 * notifications - so without this sentence a user in Tromso sees exactly what
 * they would see if the feature were broken, and reasonably concludes it is.
 *
 * Quotes the orbit's limit and their own latitude rather than asserting a
 * conclusion, so the claim can be checked against any other source.
 */
fun describeIssCoverage(latitude: Double): String? {
    val here = "The station's orbit reaches ${degrees(IssCoverage.INCLINATION_DEGREES)} " +
        "degrees north and south, and you are at ${degrees(latitude)}, so "
    return when (IssCoverage.of(latitude)) {
        IssCoverage.Covered -> null

        IssCoverage.Marginal -> here + "its passes here are low, brief and occasional."

        IssCoverage.None -> here + "it never comes above your horizon. Other bright " +
            "satellites are unaffected - many of them are in polar orbits."
    }
}

/**
 * One decimal place, without a locale's decimal separator.
 *
 * `String.format` would render "69,7" on a machine set to a decimal-comma
 * locale, in the middle of an English sentence. Sign is dropped because the
 * hemisphere does not change the answer.
 */
internal fun degrees(value: Double): String {
    val tenths = kotlin.math.round(kotlin.math.abs(value) * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** Hours where they are whole, because "360 minutes" is not how anyone thinks. */
internal fun describeInterval(minutes: Int): String = when {
    minutes < 60 -> "at most every $minutes minutes"
    minutes == 60 -> "at most once an hour"
    minutes % 60 == 0 -> "at most every ${minutes / 60} hours"
    else -> "at most every $minutes minutes"
}

internal fun shortInterval(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/** Short enough for a row of five buttons on a narrow screen. */
internal fun shortWatchDuration(option: WatchDuration): String = when (option) {
    WatchDuration.OneHour -> "1h"
    WatchDuration.TwoHours -> "2h"
    WatchDuration.FourHours -> "4h"
    WatchDuration.EightHours -> "8h"
    WatchDuration.UntilLight -> "Dawn"
}

/** Plain words for the retention setting, including having turned it off. */
internal fun describeRetention(days: Int): String = when {
    days <= 0 -> "nothing - the log is off"
    days == 1 -> "1 day"
    else -> "$days days"
}

internal fun describeLogSize(bytes: Long): String = when {
    bytes <= 0L -> "Nothing recorded yet."
    bytes < 1024L -> "$bytes bytes recorded."
    else -> "${bytes / 1024} KB recorded."
}

/** Whole days, for the advance notice the calendar gives. */
internal fun describeDays(days: Int): String = if (days == 1) "1 day" else "$days days"
