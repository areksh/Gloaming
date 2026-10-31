package sh.arek.gloaming.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A time of day typed by the user, as minutes since midnight.
 *
 * Typed rather than picked, to match the rest of this screen: someone already
 * entering their coordinates will find "23:00" quicker than a dial.
 *
 * Returns null for anything that is not a time. Deliberately not clamped - a
 * typo quietly becoming 23:59 would set quiet hours nobody asked for, and the
 * user would have no way to tell it had happened.
 */
fun parseTimeOfDay(text: String): Int? {
    val parts = text.trim().split(':', '.')
    if (parts.size != 2) return null

    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (parts[1].length != 2) return null
    if (hours !in 0..23 || minutes !in 0..59) return null

    return hours * 60 + minutes
}

/** Minutes since midnight, back as `HH:MM`. */
fun formatTimeOfDay(minuteOfDay: Int): String {
    val hours = minuteOfDay / 60
    val minutes = minuteOfDay % 60
    return "${pad(hours)}:${pad(minutes)}"
}

private fun pad(value: Int): String = if (value < 10) "0$value" else "$value"

/**
 * The device's own clock and zone. A time printed in UTC would be wrong by an
 * hour for half the year here and by more elsewhere.
 */
internal fun clock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * The date over the outlook, "Saturday 31 October", in the device's own zone.
 *
 * No year and no place: the year is never in doubt on a screen about tonight,
 * and there is no geocoder to name the place with.
 */
fun describeDate(millis: Long): String =
    SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(millis))
