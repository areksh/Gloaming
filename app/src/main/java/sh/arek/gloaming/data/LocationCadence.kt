package sh.arek.gloaming.data

/**
 * How often the phone's position is refreshed once it is detected rather than
 * typed in.
 *
 * Only [Every] is allowed to wake the app for it: [OnOpen] is answered by the
 * user opening the app. The Update now button works under either.
 */
sealed interface LocationCadence {

    /** Each time the app is opened, and nothing in the background. The default. */
    data object OnOpen : LocationCadence

    /** In the background, every [hours] whole hours. */
    data class Every(val hours: Int) : LocationCadence

    companion object {
        const val MIN_HOURS = 1
        const val MAX_HOURS = 48

        /** The rows with a label of their own; any other interval is typed in. */
        val PRESETS: List<LocationCadence> = listOf(OnOpen, Every(1))
    }
}

val LocationCadence.runsInBackground: Boolean
    get() = this is LocationCadence.Every

/** Whether the position should be refreshed now, given when it last was. */
fun LocationCadence.isDue(updatedAt: Long?, now: Long): Boolean = when (this) {
    LocationCadence.OnOpen -> true
    is LocationCadence.Every -> updatedAt == null || now - updatedAt >= hours * MILLIS_PER_HOUR
}

private const val MILLIS_PER_HOUR = 60L * 60 * 1000

/** The shortest interval typed in: one hour has a row of its own. */
const val CUSTOM_MIN_HOURS = 2

/** A custom interval typed by the user, or null if it is not 2 to 48 whole hours. */
fun parseCustomHours(input: String): Int? =
    input.trim().toIntOrNull()?.takeIf { it in CUSTOM_MIN_HOURS..LocationCadence.MAX_HOURS }
