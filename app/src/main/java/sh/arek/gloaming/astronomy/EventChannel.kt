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

    /**
     * An addition to the design's taxonomy, recorded rather than assumed. These
     * arrive nightly for three months and then not at all for nine, which is a
     * quite different appetite from an eclipse twice a year - and the season is
     * exactly when the aurora channel has nothing to say, so somebody may well
     * want this one and no other.
     */
    Noctilucent("noctilucent"),

    /**
     * The channel Sec. 9.5's taxonomy named from the start and nothing filled
     * until now. Its own rather than folded in with conjunctions, because the
     * appetite is quite different: a good comet is a handful of times a decade,
     * and somebody who wants to be woken for one may well want nothing else.
     */
    Comets("comets"),
    ;

    companion object {
        /** Null for the kinds that are context rather than an occasion. */
        fun of(kind: EventKind): EventChannel? = when (kind) {
            EventKind.SolarEclipse, EventKind.LunarEclipse -> Eclipses
            EventKind.MeteorShower -> MeteorShowers
            EventKind.Conjunction,
            EventKind.Elongation,
            EventKind.Opposition,
            EventKind.GreatestBrightness,
            -> Conjunctions
            EventKind.Noctilucent -> Noctilucent
            EventKind.Comet -> Comets
            EventKind.MoonPhase, EventKind.MoonQuarter, EventKind.Season -> null
        }
    }
}
