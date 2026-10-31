package sh.arek.gloaming.data

/**
 * How often the phone's position is refreshed once it is detected rather than
 * typed in.
 *
 * Only [Every] is allowed to wake the app for it: the other two are answered
 * by the user opening the app or pressing the button.
 */
sealed interface LocationCadence {

    /** Each time the app is opened. The default. */
    data object OnOpen : LocationCadence

    /** Only when the user asks. */
    data object Manual : LocationCadence

    /** In the background, every [hours] whole hours. */
    data class Every(val hours: Int) : LocationCadence

    companion object {
        const val MIN_HOURS = 1
        const val MAX_HOURS = 48

        val PRESETS: List<LocationCadence> =
            listOf(OnOpen, Every(1), Every(6), Every(24), Manual)

        /** A custom interval typed by the user, or null if it is not 1 to 48 whole hours. */
        fun parseHours(input: String): Int? =
            input.trim().toIntOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }
    }
}

val LocationCadence.runsInBackground: Boolean
    get() = this is LocationCadence.Every

/** Whether the position should be refreshed now, given when it last was. */
fun LocationCadence.isDue(updatedAt: Long?, now: Long): Boolean = when (this) {
    LocationCadence.OnOpen -> true
    LocationCadence.Manual -> false
    is LocationCadence.Every -> updatedAt == null || now - updatedAt >= hours * MILLIS_PER_HOUR
}

private const val MILLIS_PER_HOUR = 60L * 60 * 1000
