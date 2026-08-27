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

    /**
     * The moon passing in front of a planet, rather than merely near it.
     *
     * Kept apart from [Conjunction] rather than marked within it, because they
     * are not the same event at two magnitudes. A conjunction is two dots close
     * together for a night; an occultation is a planet visibly going out at the
     * moon's edge and coming back an hour later, and it is one of the very few
     * naked-eye events that looks like something *happening*. It is also far
     * rarer, and rarer things want their own filter chip.
     *
     * Strictly local. The moon has up to a degree of parallax - about two of
     * its own diameters - so two observers a few hundred miles apart genuinely
     * disagree about whether one is happening, which is why occultation
     * predictions are published per site.
     */
    Occultation,

    Elongation,

    /**
     * A planet opposite the sun: up all night, closest for the year, and the
     * night to look at it if you look once.
     */
    Opposition,

    /** Venus at its brightest, which is bright enough to cast a shadow. */
    GreatestBrightness,

    /**
     * New and full moon: the two that decide what a night is worth. New is the
     * darkest sky of the month, full washes out everything faint.
     */
    MoonPhase,

    /**
     * First and last quarter, kept apart from [MoonPhase] rather than marked
     * within it.
     *
     * They matter far less to an observer and there are just as many of them,
     * so together the four filled over half the calendar. Making them their own
     * kind means the ordinary filter can hide them - which it does by default -
     * with no special case anywhere for "a moon phase below some threshold".
     */
    MoonQuarter,

    /**
     * An equinox or a solstice. Not a sight - listed because it marks the
     * observing year turning, and at these latitudes the return or loss of
     * darkness. Never interrupts, like a moon phase.
     */
    Season,

    /**
     * A comet, which is neither dated nor nightly but something in between: it
     * arrives, hangs about for weeks moving a little each night, brightens and
     * fades. It appears in the calendar once, for the window worth planning
     * around, and on the tonight list whenever it is actually up.
     */
    Comet,

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
    Sun("the sun"),
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
