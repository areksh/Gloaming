package sh.arek.gloaming.ui

/**
 * The words the rest of the app prints, explained.
 *
 * Pure data with no Android in it, like [Credit] and for the same reason: what
 * is worth pinning is the writing, and a JVM test can read it. The screens are
 * two composables over this file.
 *
 * The app already taught in one place - the star-hop on each constellation,
 * "the far side of Polaris from the Plough" - and that sentence is useless to
 * anybody who does not already know what Polaris and the Plough are, which is
 * exactly the reader it was written for. This is the rest of that thought:
 * every landmark the hops point from, every term the calendar and Tonight
 * put on screen, and the handful of things about actually standing outside in
 * the dark that no amount of arithmetic replaces.
 *
 * Written to be read rather than looked up. An entry says what the thing is,
 * then what it means for somebody deciding whether to put a coat on, and where
 * a number would flatter - a shower's advertised rate, a comet's brightness,
 * an aurora photograph - it says so plainly. Disappointing somebody once is
 * cheaper than being the app that promised them a light show.
 */

/** One word explained, or one thing worth knowing. */
data class GuideEntry(val term: String, val body: String)

/** A run of entries under one heading, in the order they are worth reading. */
data class GuideSection(val title: String, val blurb: String, val entries: List<GuideEntry>)

/**
 * A set of words a part of the app can explain: a kind of row on Tonight's
 * timeline ([helpTopic] says which), or the calendar.
 *
 * Only where there are any: notes under plain English are noise.
 * [Guide.TOPIC_TERMS] says which entries each one offers.
 */
enum class HelpTopic {
    Aurora,
    SkyNow,
    Darkness,
    Moon,
    Planets,
    Constellations,
    Events,
    Comets,
    Satellites,
    Calendar,
}

object Guide {

    // --- finding your way ---

    private val FINDING = GuideSection(
        title = "Finding your way round the sky",
        blurb = "Everything this app says about where to look is a direction and a " +
            "height. These are the landmarks the rest of it points from. Which are " +
            "worth learning first depends on where you are: the Plough and Polaris " +
            "in the northern hemisphere, the Southern Cross in the southern.",
        entries = listOf(
            GuideEntry(
                "Degrees above the horizon",
                "How high something is: 0 at the horizon, 90 straight overhead. Your " +
                    "own hand at arm's length is a good enough ruler - a thumb is about " +
                    "2 degrees, a clenched fist about 10, and the span from thumb to " +
                    "little finger about 20. So \"highest 30 degrees\" is three fists " +
                    "stacked up from the horizon: clear of most rooftops, and high " +
                    "enough to be out of the worst of the haze.",
            ),
            GuideEntry(
                "North, north-east, and the rest",
                "Which way to face. The quickest way is Find, which shows a bearing " +
                    "against the way the phone is held, or any compass; they are " +
                    "easily thrown off indoors, by a car, or by anything magnetic in a " +
                    "pocket. Failing that, the stars will do it. In the northern " +
                    "hemisphere, follow the Plough's Pointers to Polaris, which is " +
                    "north near enough. In the southern, extend the Southern Cross's " +
                    "long axis about four and a half times its length and drop " +
                    "straight down to the horizon, which is south near enough. " +
                    "Everything else follows from that.",
            ),
            GuideEntry(
                "The Plough",
                "Seven bright stars making a saucepan: four in the bowl, three in the " +
                    "handle. It is the part of Ursa Major anybody can actually find, it " +
                    "never sets from the middle and high latitudes of the northern " +
                    "hemisphere, and drops out of sight altogether a long way south " +
                    "of the equator. Americans call it the Big Dipper, and older " +
                    "books call it Charles's Wain.",
            ),
            GuideEntry(
                "The Pointers",
                "The two stars at the end of the Plough's bowl, furthest from the " +
                    "handle. Draw a line through them away from the bowl, carry on " +
                    "about five times the gap between them, and you arrive at Polaris. " +
                    "The southern sky has Pointers of its own - two brilliant stars in " +
                    "Centaurus aiming at the Southern Cross - and this app means " +
                    "whichever pair is above your horizon.",
            ),
            GuideEntry(
                "Polaris",
                "The Pole Star: the one star that stays put. Everything else wheels " +
                    "round it through the night while Polaris sits over true north, at " +
                    "a height above the horizon equal to your latitude: 45 degrees up at " +
                    "latitude 45, on the horizon at the equator, and never seen from " +
                    "the southern hemisphere. It is not a particularly bright star, " +
                    "which surprises people; what makes it worth knowing is that it " +
                    "does not move. Find it with the Plough's Pointers.",
            ),
            GuideEntry(
                "Star-hopping",
                "Getting from a pattern you know to one you do not by following a line " +
                    "of bright stars. It is how every direction for a constellation " +
                    "on Tonight is written - follow the curve of the Plough's handle and you " +
                    "reach Arcturus - because a bare position in the sky is nearly " +
                    "impossible to point at and a chain of bright stars is easy. Learn " +
                    "three or four patterns and the rest opens up from them.",
            ),
            GuideEntry(
                "The Summer Triangle",
                "Three brilliant stars - Vega, Deneb and Altair - making a wide " +
                    "triangle in the evening sky from the middle of the year into the " +
                    "autumn of the north, which is the winter and spring of the south. " +
                    "From the north it stands high, with Vega the highest and Altair " +
                    "the lowest; from the south it sits low towards the north and the " +
                    "other way up, Altair on top. Vega is the brightest, Deneb " +
                    "sits in the Milky Way at the head of Cygnus, and Altair has a " +
                    "fainter star either side of it.",
            ),
            GuideEntry(
                "The Pleiades",
                "A tight little knot of stars in Taurus, often taken at first glance " +
                    "for a miniature Plough, and often the thing somebody notices " +
                    "without meaning to. Six are straightforward on a good night and " +
                    "more come out with binoculars. Looking slightly to one side of the " +
                    "group shows more of it than staring straight at it. Also called " +
                    "the Seven Sisters.",
            ),
            GuideEntry(
                "The Square of Pegasus",
                "Four stars in a large, almost empty square, high on autumn evenings " +
                    "in the northern hemisphere (towards the south) and on spring " +
                    "evenings in the southern (towards the north). The emptiness is " +
                    "the useful part: from a properly dark place you can count " +
                    "several stars inside it, from a suburb perhaps one, and from a " +
                    "town centre none - which makes it a decent test of how dark your " +
                    "sky actually is.",
            ),
            GuideEntry(
                "The Milky Way",
                "Our own galaxy seen edge-on from inside it: a faint band of light " +
                    "across the sky, at its thickest and brightest towards Sagittarius, " +
                    "low towards the south on northern summer nights and high up on " +
                    "southern winter ones. It needs a properly dark sky and twenty " +
                    "minutes of dark adaptation, and then it is unmistakable. " +
                    "If you have never seen it, that is a fact about where you have been " +
                    "standing rather than about your eyes.",
            ),
            GuideEntry(
                "The Southern Cross",
                "A small, tight cross of four stars, the landmark of the southern sky " +
                    "and never visible from most of the northern hemisphere. The two " +
                    "bright Pointers of Centaurus stand beside it. Its long axis aims " +
                    "roughly at the south celestial pole, which - unlike the north - " +
                    "has no bright star to mark it.",
            ),
            GuideEntry(
                "Circumpolar",
                "Close enough to the pole never to set. From the middle northern " +
                    "latitudes the Plough, Cassiopeia and their neighbours are up every " +
                    "night of the year at some hour and some angle, and from the " +
                    "middle southern latitudes the Southern Cross and Centaurus do " +
                    "the same. " +
                    "That is why the app names such patterns together on one line " +
                    "rather than giving each a row that would never change. The nearer " +
                    "you are to a pole the more of the sky is circumpolar; from the " +
                    "equator none of it is.",
            ),
        ),
    )

    // --- the vocabulary ---

    private val WORDS = GuideSection(
        title = "The words this app uses",
        blurb = "Astronomy has a habit of giving an ordinary-looking word a very " +
            "specific job. These are the ones that turn up on Tonight and in " +
            "the calendar.",
        entries = listOf(
            GuideEntry(
                "Magnitude",
                "How bright something looks, on a scale that runs backwards: smaller " +
                    "numbers are brighter, and the brightest things are negative. Each " +
                    "step of 1 is about two and a half times, so magnitude 1 is a " +
                    "hundred times brighter than magnitude 6. For scale: the sun is " +
                    "-27, the full moon -13, Venus at its best about -4, Sirius -1.5, " +
                    "the stars of the Plough around 2. Magnitude 6 is roughly the " +
                    "faintest a good eye reaches on a properly dark night; from a town, " +
                    "4 is nearer the truth.",
            ),
            GuideEntry(
                "High cloud and low cloud",
                "Where the cloud is matters as much as how much of it there is. High " +
                    "cirrus, five miles up and thin, still lets a bright aurora or the " +
                    "moon through; low stratus does not. So a forecast of 60 per cent " +
                    "that is all high cloud can still be a usable night, which is why " +
                    "this app says which kind it is rather than only quoting a total.",
            ),
            GuideEntry(
                "Dark, and fully dark",
                "Two different times, and the app gives both. Dark is the end of civil " +
                    "twilight, with the sun 6 degrees below the horizon: bright stars " +
                    "and planets are out and an aurora will show. Fully dark is " +
                    "astronomical darkness, the sun 18 degrees down, which is when faint " +
                    "things become possible at all - the Milky Way, a comet, most " +
                    "meteors. Above about 49 degrees of latitude, north or south, the " +
                    "second never arrives for a few weeks around midsummer, which is " +
                    "why a midsummer meteor shower disappoints there.",
            ),
            GuideEntry(
                "Moonlight",
                "Why the app weighs the moon rather than merely naming its phase. What " +
                    "costs you a night is how bright the moon is multiplied by how much " +
                    "of the night it is up for: a full moon that has set by midnight " +
                    "costs far less than a half moon up until dawn. It washes out the " +
                    "faint things - comets, meteors, the Milky Way - and leaves the " +
                    "bright ones almost untouched, so a moonlit night is still a fine " +
                    "night for planets, satellites or a strong aurora.",
            ),
            GuideEntry(
                "Aurora probability",
                "The model's estimate that the aurora is overhead where you are within " +
                    "the next half hour. It is not how bright it will be, and it is not " +
                    "a promise: the model reads the solar wind arriving at a satellite " +
                    "about an hour upstream of us and works out where the oval should " +
                    "fall. Thirty per cent is worth stepping outside for. It says " +
                    "nothing at all about cloud, which is why the app checks that " +
                    "separately.",
            ),
            GuideEntry(
                "Kp",
                "A number from 0 to 9 for how disturbed the earth's magnetic field is " +
                    "worldwide, and the usual shorthand elsewhere for aurora chances. " +
                    "Roughly: 5 is a minor storm and can bring it to about 60 degrees " +
                    "magnetic latitude, 6 or 7 pushes it down to about 55, and 8 or 9 " +
                    "- a handful of nights a decade - takes it a long way further from " +
                    "the pole. Magnetic latitude is not the ordinary kind: the same Kp " +
                    "reaches lower geographic latitudes in North America than in " +
                    "Europe, and the picture is mirrored in the southern hemisphere. " +
                    "It is a three-hourly global average, so it is a poor guide to " +
                    "what is happening here right now, which is why this app works " +
                    "from the shape of the oval instead.",
            ),
            GuideEntry(
                "Opposition",
                "When a planet is opposite the sun from us, so it rises at sunset, is " +
                    "highest around midnight and sets at dawn. It is also at its closest " +
                    "to us, and so at its biggest and brightest. For Mars, Jupiter and " +
                    "Saturn this is the best night of the year to look at them, and it " +
                    "is what the calendar's opposition entries mark.",
            ),
            GuideEntry(
                "Conjunction",
                "Two things appearing close together in the sky. They are nowhere near " +
                    "each other in space - one may be a hundred times further away - but " +
                    "from here they line up, and a bright planet a finger's width from " +
                    "the moon is a genuinely striking sight that needs nothing but eyes.",
            ),
            GuideEntry(
                "Occultation",
                "One thing passing in front of another: almost always the moon covering " +
                    "a star or a planet. Unlike a conjunction it has a moment - the " +
                    "object vanishes at the moon's edge and reappears an hour or so " +
                    "later on the other side - and the disappearance is abrupt, because " +
                    "the moon has no atmosphere to fade it out through.",
            ),
            GuideEntry(
                "Elongation",
                "How far from the sun something appears. Mercury and Venus never get " +
                    "far, which is why they are only ever seen shortly after sunset or " +
                    "shortly before sunrise; greatest elongation is when they are " +
                    "furthest out and easiest to catch. Mercury reaches about 28 degrees " +
                    "at best and Venus about 47.",
            ),
            GuideEntry(
                "Greatest brightness",
                "Venus's own best night, which is not the same night as its greatest " +
                    "elongation. Venus shows phases like the moon, and the brightest it " +
                    "gets is a compromise between being close to us and showing enough " +
                    "of its lit side - a fat crescent rather than a full disc. It " +
                    "reaches about magnitude -4.7, bright enough to cast a shadow from a " +
                    "dark place, and bright enough to be reported as a UFO most years.",
            ),
            GuideEntry(
                "Perihelion",
                "A comet's closest approach to the sun, and usually when it is " +
                    "brightest - the sun's heat is what drives off the gas and dust that " +
                    "make a coma and a tail in the first place. Closest to the sun is " +
                    "not the same as closest to us, and not necessarily well placed for " +
                    "looking at: a comet can be at its brightest while lost in the " +
                    "twilight.",
            ),
            GuideEntry(
                "Coma and tail",
                "A comet is a dirty iceberg a few miles across, which you never see. " +
                    "What you see is the coma - a fuzzy cloud of gas and dust boiled off " +
                    "it - and the tail streaming away, always pointing away from the sun " +
                    "rather than backwards along its path. The tail is usually far " +
                    "easier in a photograph than to the eye.",
            ),
            GuideEntry(
                "Radiant",
                "The point a meteor shower's meteors appear to come from. They do not " +
                    "really: they are debris hitting the atmosphere on parallel tracks, " +
                    "and perspective makes them seem to fan out from one spot, the way " +
                    "railway lines seem to meet in the distance. It matters twice over - " +
                    "the shower is only worth watching once its radiant is above the " +
                    "horizon, and the higher the radiant, the more of them reach you.",
            ),
            GuideEntry(
                "An hourly rate",
                "What a shower's headline number means, and what it does not. The " +
                    "published rate assumes the radiant directly overhead, a perfectly " +
                    "dark sky and no moon, and none of those is ever quite true. Expect " +
                    "a good fraction of it at best, less as the radiant sits lower, and " +
                    "far less under a bright moon or from a town. A shower advertising " +
                    "100 an hour giving you 20 is a normal night rather than a failure.",
            ),
            GuideEntry(
                "Umbra and penumbra",
                "The two parts of a shadow: in the umbra the light is completely " +
                    "blocked, in the penumbra only partly. A total lunar eclipse is the " +
                    "moon inside the earth's umbra, which turns it a deep copper red; a " +
                    "penumbral one is the moon in the outer shadow only, and is so " +
                    "slight that most people cannot tell it is happening. The app says " +
                    "which is which.",
            ),
            GuideEntry(
                "Totality",
                "The part of an eclipse worth travelling for. In a total solar eclipse " +
                    "the moon covers the sun completely for a few minutes along a narrow " +
                    "track, the sky darkens enough for stars and the corona appears; a " +
                    "few miles outside that track you get a partial eclipse, which is " +
                    "interesting but is not the same thing at all. A 99 per cent partial " +
                    "eclipse is not 99 per cent of a total one.",
            ),
            GuideEntry(
                "Noctilucent cloud",
                "Night-shining cloud: wisps of ice fifty miles up, so high that they " +
                    "catch sunlight long after everything below them is in darkness. " +
                    "Electric blue and rippled like sand on a beach, quite unlike " +
                    "ordinary cloud. Summer only - late May to early August in the " +
                    "northern hemisphere, late November to early February in the " +
                    "southern - and always low towards the pole (north from the " +
                    "northern hemisphere, south from the southern), between about one " +
                    "and two hours after sunset.",
            ),
            GuideEntry(
                "A pass",
                "A satellite crossing your sky. You see it by reflected sunlight, so a " +
                    "pass only counts when the satellite is still in sunshine while you " +
                    "are already in darkness, which is why they cluster in the couple of " +
                    "hours after dusk and before dawn. It looks like a steady star " +
                    "moving purposefully, with none of an aircraft's flashing, and takes " +
                    "a few minutes from horizon to horizon.",
            ),
            GuideEntry(
                "Entering shadow",
                "Why a satellite fades out in the middle of the sky rather than " +
                    "setting: it has crossed into the earth's shadow and stopped being " +
                    "lit. It happens over a few seconds, usually towards the east, and " +
                    "it is worth expecting - otherwise a pass that ends early looks like " +
                    "the prediction being wrong.",
            ),
            GuideEntry(
                "A satellite train",
                "A string of satellites in a line along the same track, a few seconds " +
                    "apart. It happens for a week or two after a batch is launched into " +
                    "one orbit, before they spread out and climb to their working " +
                    "altitude. Genuinely startling if you do not know what it is, and " +
                    "the commonest reason somebody reports a UFO these days.",
            ),
        ),
    )

    // --- standing outside ---

    private val SEEING = GuideSection(
        title = "Actually seeing it",
        blurb = "None of the arithmetic helps if you step outside for ninety seconds " +
            "and look at your phone. This is the other half of it, and most of " +
            "it costs nothing.",
        entries = listOf(
            GuideEntry(
                "Dark adaptation",
                "Your eyes take about twenty minutes to become properly night-adapted " +
                    "and the better part of an hour to finish the job. One glance at a " +
                    "phone screen undoes most of it. This is the single largest " +
                    "difference between seeing the Milky Way and not seeing it, it costs " +
                    "nothing, and it is the step almost everybody skips.",
            ),
            GuideEntry(
                "Red light",
                "Red light spoils dark adaptation far less than white. A torch with a " +
                    "red filter, or this app's own night-vision mode, lets you read " +
                    "something outside without starting the twenty minutes over. Dim " +
                    "matters as much as red: a bright red light is still a bright light.",
            ),
            GuideEntry(
                "Averted vision",
                "Looking slightly to one side of a faint thing instead of straight at " +
                    "it. The centre of your retina is poor in the dark and the edges are " +
                    "far more sensitive, so a faint comet or a nebula often appears the " +
                    "moment you look a little away from it and vanishes again when you " +
                    "look back. It feels wrong and it works.",
            ),
            GuideEntry(
                "What an aurora actually looks like",
                "Usually not like the photographs. A camera collects light for several " +
                    "seconds and your eye does not, so a modest aurora looks like a pale " +
                    "grey-green arc low towards the pole, easy to mistake for cloud until " +
                    "you notice the stars showing through it. Colour comes with strength " +
                    "- green first, red high up in a real storm - and the movement, when " +
                    "it starts, is the part no photograph conveys.",
            ),
            GuideEntry(
                "What a comet actually looks like",
                "A small fuzzy grey patch, like a thumbprint on the sky, with a tail " +
                    "that is more often a faint brightening on one side than the sweep " +
                    "in the pictures. Binoculars help more than a telescope. The bright " +
                    "naked-eye comets that make the news come along a few times a " +
                    "decade; the rest are worth seeing and need expectations managed, " +
                    "which is why this app prints the brightness beside the name.",
            ),
            GuideEntry(
                "Binoculars",
                "The most useful thing to own, well ahead of a telescope. Any ordinary " +
                    "pair works, and 10x50 is the usual advice - ten times " +
                    "magnification, 50mm lenses. They show the moons of Jupiter, the " +
                    "Pleiades properly, comets, and a great deal more of the Milky Way. " +
                    "A telescope shows more still and takes setting up, which is why it " +
                    "stays indoors on a cold night and binoculars do not.",
            ),
            GuideEntry(
                "Where to stand",
                "Ten minutes' walk away from the nearest streetlight is worth more than " +
                    "any equipment. Put a building or a hedge between yourself and the " +
                    "worst of the light, keep the direction you care about clear of " +
                    "rooftops - anything low down is being looked at through far more " +
                    "atmosphere and haze - and give yourself a horizon. A garden with " +
                    "the lights off beats a hilltop with a car park.",
            ),
            GuideEntry(
                "Patience",
                "Meteor showers especially are a matter of lying back and waiting. The " +
                    "rate is an average over an hour and they arrive in clumps with long " +
                    "empty gaps between, so fifteen minutes is not a sample. Take " +
                    "something to lie on and something warm, look up rather than at any " +
                    "particular spot, and give it an hour.",
            ),
        ),
    )

    /** The guide, in the order it is worth reading. */
    val SECTIONS: List<GuideSection> = listOf(FINDING, WORDS, SEEING)

    /** Every entry, flattened, for looking one up. */
    val TERMS: List<GuideEntry> = SECTIONS.flatMap { it.entries }

    /**
     * What each topic offers to explain, in the order it offers it.
     *
     * Named by string rather than by reference so an entry lives in exactly
     * one place - its section - and a topic borrows it. The cost is that a
     * typo here is a term that silently does not appear, which is what
     * `GuideTest` exists to catch.
     */
    internal val TOPIC_TERMS: Map<HelpTopic, List<String>> = mapOf(
        HelpTopic.Aurora to listOf(
            "Aurora probability",
            "What an aurora actually looks like",
            "Kp",
            "Dark, and fully dark",
        ),
        HelpTopic.SkyNow to listOf(
            "High cloud and low cloud",
            "Where to stand",
        ),
        HelpTopic.Darkness to listOf(
            "Dark, and fully dark",
            "Dark adaptation",
            "The Milky Way",
        ),
        HelpTopic.Moon to listOf(
            "Moonlight",
            "Dark, and fully dark",
        ),
        HelpTopic.Planets to listOf(
            "Magnitude",
            "Degrees above the horizon",
            "North, north-east, and the rest",
            "Opposition",
            "Elongation",
        ),
        HelpTopic.Constellations to listOf(
            "Polaris",
            "The Plough",
            "The Pointers",
            "Star-hopping",
            "Circumpolar",
            "Degrees above the horizon",
            "North, north-east, and the rest",
        ),
        HelpTopic.Events to listOf(
            "Radiant",
            "An hourly rate",
            "Umbra and penumbra",
            "Totality",
            "Conjunction",
            "Occultation",
            "Noctilucent cloud",
            "Patience",
        ),
        HelpTopic.Comets to listOf(
            "Magnitude",
            "Coma and tail",
            "Perihelion",
            "What a comet actually looks like",
            "Binoculars",
            "Moonlight",
        ),
        HelpTopic.Satellites to listOf(
            "A pass",
            "Entering shadow",
            "A satellite train",
            "Magnitude",
            "Degrees above the horizon",
            "North, north-east, and the rest",
        ),
        HelpTopic.Calendar to listOf(
            "Opposition",
            "Conjunction",
            "Occultation",
            "Elongation",
            "Greatest brightness",
            "Radiant",
            "An hourly rate",
            "Umbra and penumbra",
            "Totality",
            "Perihelion",
            "Noctilucent cloud",
        ),
    )

    /**
     * What one topic explains, in the order it wants them.
     *
     * Silently drops a term it cannot find rather than throwing. A help button
     * is not worth crashing a screen over, and the test catches the typo that
     * would cause it long before a phone does.
     */
    fun forTopic(topic: HelpTopic): List<GuideEntry> =
        TOPIC_TERMS[topic].orEmpty().mapNotNull { name -> TERMS.firstOrNull { it.term == name } }
}
