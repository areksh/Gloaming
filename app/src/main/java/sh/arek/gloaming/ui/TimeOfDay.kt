package sh.arek.gloaming.ui

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
