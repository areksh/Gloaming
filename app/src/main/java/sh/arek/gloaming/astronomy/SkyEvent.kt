package sh.arek.gloaming.astronomy

/**
 * What the sky is doing, in the categories a person would sort it into.
 *
 * Deliberately not one category per notification channel: elongations and
 * conjunctions share a channel because they are the same appetite, and moon
 * phases have none at all. The kind says what the event *is*; where it goes is
 * decided separately.
 */
enum class EventKind {
    SolarEclipse,
    LunarEclipse,
    MeteorShower,
    Conjunction,
    Elongation,
    MoonPhase,

    /**
     * Not a dated occasion like the rest, but a condition that holds most clear
     * nights for three months. Kept out of the calendar for that reason and
     * produced only for tonight - see [Noctilucent].
     */
    Noctilucent,
}

/**
 * Whether it is worth going outside for, from where the observer is standing.
 *
 * The reasons are the point. An event the ground is in the way of still belongs
 * in the calendar, because "it happens tonight but not for you" is useful and
 * an empty list is not - the same argument [IssCoverage] makes about the
 * station's orbit. What the reasons must never do is reach a notification:
 * only [Good] interrupts.
 *
 * There is no fourth case for "nothing to see anywhere". Events like that -
 * a penumbral lunar eclipse, where the moon misses the deep shadow entirely -
 * are not produced at all, because they are not events.
 */
sealed interface Visibility {

    /** Up, dark enough, and worth the walk. */
    data object Good : Visibility

    /** Visible, but with something against it: low, brief, slight, or in twilight. */
    data class Marginal(val reason: String) : Visibility

    /** Below the horizon here, whatever it is doing elsewhere. */
    data class NotFromHere(val reason: String) : Visibility
}

/**
 * The bodies the calendar has anything to say about.
 *
 * A short list rather than the library's full one, because everything beyond
 * Saturn needs a telescope and the app's whole premise is naked-eye observing
 * from a garden.
 */
enum class SkyBody(val label: String) {
    Mercury("Mercury"),
    Venus("Venus"),
    Mars("Mars"),
    Jupiter("Jupiter"),
    Saturn("Saturn"),
    Moon("the moon"),
}

/** Where something was, and how high, at the moment it was best placed. */
data class Sighting(
    val atMillis: Long,
    /** Degrees above the horizon. */
    val altitude: Double,
    /** Degrees clockwise from north. */
    val azimuth: Double,
)

/**
 * One dated thing in the sky, already judged against where the observer stands.
 *
 * [at] is the moment the event peaks and is what the calendar sorts by. [from]
 * and [until] bound the part worth watching, which for an eclipse is its first
 * and last contact and for a shower is a whole night - they are not the same
 * span and nothing should assume they are.
 */
data class SkyEvent(
    val kind: EventKind,
    val at: Long,
    val from: Long,
    val until: Long,
    val title: String,
    val detail: String,
    val visibility: Visibility,
)
