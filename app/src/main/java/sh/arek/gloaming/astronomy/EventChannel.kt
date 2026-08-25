package sh.arek.gloaming.astronomy

/**
 * Which notification channel a calendar event belongs to, so any of them can be
 * silenced alone from the system settings.
 *
 * Three where the design's taxonomy names three, with one widening:
 * elongations join conjunctions rather than getting a channel of their own.
 * They are the same appetite - a planet is well placed tonight - and somebody
 * who wants to be told about one wants to be told about the other.
 *
 * Moon phases have no channel at all and never notify. One arrives every 7.4
 * days; what they are for is planning the rest around, not interrupting.
 */
enum class EventChannel(val id: String) {
    Eclipses("eclipses"),
    MeteorShowers("meteor-showers"),
    Conjunctions("conjunctions"),
    ;

    companion object {
        /** Null for the kinds that are context rather than an occasion. */
        fun of(kind: EventKind): EventChannel? = when (kind) {
            EventKind.SolarEclipse, EventKind.LunarEclipse -> Eclipses
            EventKind.MeteorShower -> MeteorShowers
            EventKind.Conjunction, EventKind.Elongation -> Conjunctions
            EventKind.MoonPhase -> null
        }
    }
}
