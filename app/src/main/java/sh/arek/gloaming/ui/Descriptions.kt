package sh.arek.gloaming.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.ConstellationTonight
import sh.arek.gloaming.astronomy.Darkness
import sh.arek.gloaming.astronomy.PlanetTonight
import sh.arek.gloaming.data.CloudCover
import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.WatchDuration
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.MoonTonight
import sh.arek.gloaming.domain.StormMode
import sh.arek.gloaming.domain.deviceLocalMinuteOfDay
import sh.arek.gloaming.net.Transfer
import sh.arek.gloaming.net.TransferState
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

/**
 * The one line at the head of Tonight: how long ago the app looked, and why it
 * may not have looked since.
 *
 * One line rather than two because they are read together - a long gap is
 * only alarming until the second half explains it. Without a location there
 * is no darkness to describe, and the line stops at the age.
 */
fun describeStatus(
    lastPollAt: Long?,
    now: Long,
    darkness: Darkness?,
    localMinuteOfDay: (Long) -> Int = ::deviceLocalMinuteOfDay,
): String {
    val age = describeAge(lastPollAt, now) + "."
    return if (darkness == null) age else "$age ${describeSchedule(darkness, localMinuteOfDay)}"
}

/** What the watch button promises before it is pressed: how often, and for how long. */
fun describeWatchPlan(duration: WatchDuration): String {
    val span = if (duration == WatchDuration.UntilLight) "until it gets light" else "for ${duration.label}"
    return "Checks every ${StormMode.POLL_MINUTES} minutes $span"
}

/**
 * What the watch button says while a watch runs: when it will stop on its own,
 * which is what tapping it would bring forward. Null is a watch whose end the
 * service has not given, which still says it is running.
 */
fun describeWatching(untilMillis: Long?): String =
    untilMillis?.let { "Watching until ${clock(it)}" } ?: "Watching"

/**
 * Whether a watch is running as far as the screen can tell.
 *
 * Past its own end it has stopped, whatever the screen last heard: the
 * service ends it at the cap and nothing tells a screen left open, which
 * would otherwise go on saying "Watching until" a time already gone. One
 * with no known end is taken at its word.
 */
fun watchRunning(watching: Boolean, untilMillis: Long?, nowMillis: Long): Boolean =
    watching && (untilMillis == null || nowMillis < untilMillis)

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
/**
 * A position with its hemispheres named.
 *
 * Not [degrees], which drops the sign on purpose because its callers supply the
 * direction in words alongside it. Printing a bare coordinate pair through that
 * turned 53.009, -1.287 - the Midlands - into "53.0, 1.3", which is the North
 * Sea. Letters rather than a minus sign because a lone "-" in front of a number
 * is easy to miss and impossible to misread as "W".
 *
 * Three places is about a hundred metres, which is far finer than anything the
 * app does with it and coarse enough not to imply a precision the fix does not
 * have.
 */
internal fun coordinates(latitude: Double, longitude: Double): String {
    val northSouth = if (latitude >= 0) "N" else "S"
    val eastWest = if (longitude >= 0) "E" else "W"
    return "%.3f %s, %.3f %s".format(
        java.util.Locale.UK,
        kotlin.math.abs(latitude),
        northSouth,
        kotlin.math.abs(longitude),
        eastWest,
    )
}

/**
 * A magnitude in degrees, without a sign.
 *
 * The sign is deliberately dropped: every caller says which way separately -
 * "69.6 degrees north", "10 degrees up". Use [coordinates] for a position.
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

/**
 * A span of time before something, as a lead rather than a rate.
 *
 * Distinct from [describeInterval], which says "at most once an hour" - correct
 * for a rate limit and nonsense for a notice, which is not a thing that happens
 * repeatedly.
 */
internal fun describeLead(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes before"
    minutes == 60 -> "an hour before"
    minutes % 60 == 0 -> "${minutes / 60} hours before"
    else -> "$minutes minutes before"
}

/** A cap on how long something may run, as a duration. */
internal fun describeDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "an hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "$minutes minutes"
}

/** A magnitude, where smaller means brighter and the scale surprises people. */
internal fun describeMagnitude(magnitude: Int): String = "magnitude $magnitude"

/** Degrees above the horizon, for the controls that set a bar. */
internal fun describeDegrees(degrees: Int): String = "$degrees degrees"

/** A share, written the way the cloud tolerance already writes one. */
internal fun describePercent(percent: Int): String = "$percent per cent"

/**
 * What the sky is doing, in words.
 *
 * Here rather than in the notifier that used to own it, because the Tonight
 * tab now says the same thing and two implementations of "clear" would
 * eventually be two different definitions of it - the notification saying the
 * sky is clear while the screen behind it says 20% cloud.
 *
 * An unreachable forecast says so rather than being left out. Omitting it would
 * read as a clear sky, and the whole reason the gate notifies on an unknown
 * reading is that the user should decide whether to go and look.
 *
 * Where the cloud is matters as much as how much: high cirrus at 60% still
 * shows a strong aurora, low stratus at 60% does not.
 */
fun describeCloud(cloud: CloudCover?): String {
    if (cloud == null) return "cloud cover unknown"
    val blocking = cloud.low + cloud.mid
    return when {
        cloud.total <= CLEAR_PERCENT -> "clear"
        cloud.high >= cloud.total && blocking <= THIN_PERCENT ->
            "${cloud.total}% cloud, high and thin"
        else -> "${cloud.total}% cloud"
    }
}

/** At or below this the sky is called clear rather than given a number. */
private const val CLEAR_PERCENT = 15

/** How much low and mid cloud may be present before "high and thin" is a lie. */
private const val THIN_PERCENT = 25

/**
 * The gap in the cloud, as something to plan around.
 *
 * Null when there is no forecast to speak from, which is a different thing from
 * a forecast with no gap in it - the caller says nothing in the first case and
 * "no clear spell tonight" in the second.
 */
internal fun describeClearSpell(spell: ClearSpell?, tolerance: Int): String = when {
    spell == null -> "No gap in the cloud forecast tonight, at $tolerance% or under."

    spell.allNight -> "Under $tolerance% cloud for the rest of the night."

    spell.underway -> "Clear now, until about ${clock(spell.until)}."

    else -> "Clearer from about ${clock(spell.from)} until ${clock(spell.until)}."
}

/**
 * The moon's phase in the words everybody uses for it.
 *
 * The named phases get a window of five degrees either side rather than an
 * instant, because the moon moves about 12 degrees a day and nobody calls a
 * moon eleven hours off full anything but full.
 *
 * Full and new are decided by the illumination printed beside the name rather
 * than by the angle, so the two cannot contradict each other. They very nearly
 * did: a moon a few hours short of full is 173 degrees round its cycle and
 * 99.7% lit, which the angle calls waxing gibbous and the rounded percentage
 * calls 100% - and "Waxing gibbous, 100% lit" is a sentence that argues with
 * itself. Seen on a real phone on the night of a lunar eclipse, which is the
 * one night somebody is certain to be reading this card.
 *
 * @param illumination fraction of the disc lit, 0 to 1.
 */
internal fun describeMoonPhase(phaseDegrees: Double, illumination: Double): String {
    val percent = kotlin.math.round(illumination * 100).toInt()
    if (percent >= 100) return "Full moon"
    if (percent <= 0) return "New moon"

    val phase = ((phaseDegrees % 360) + 360) % 360
    return when {
        phase < NAMED_PHASE_SLACK || phase > 360 - NAMED_PHASE_SLACK -> "New moon"
        phase < 90 - NAMED_PHASE_SLACK -> "Waxing crescent"
        phase <= 90 + NAMED_PHASE_SLACK -> "First quarter"
        phase < 180 - NAMED_PHASE_SLACK -> "Waxing gibbous"
        phase <= 180 + NAMED_PHASE_SLACK -> "Full moon"
        phase < 270 - NAMED_PHASE_SLACK -> "Waning gibbous"
        phase <= 270 + NAMED_PHASE_SLACK -> "Last quarter"
        else -> "Waning crescent"
    }
}

/** About ten hours either side, at the moon's own rate of about 12 degrees a day. */
private const val NAMED_PHASE_SLACK = 5.0

/**
 * How much of tonight the moon spoils, which is not the same as how bright it
 * is.
 *
 * A full moon that has set by midnight costs far less than a half moon up all
 * night, and it is that product the comet gate has always used - so it is that
 * product the screen reports rather than the illumination on its own.
 */
internal fun describeMoonlight(moon: MoonTonight): String {
    val percent = kotlin.math.round(moon.interference * 100).toInt()
    return when {
        moon.upFraction <= 0.0 -> "Below the horizon all night, so it costs you nothing."
        percent <= 5 -> "Barely there: it spoils about $percent% of tonight's darkness."
        percent >= 70 -> "It washes out about $percent% of tonight's darkness."
        else -> "It spoils about $percent% of tonight's darkness."
    }
}

/**
 * When the moon comes and goes, where it does either inside the night.
 *
 * Null when it does neither, which is the commonest case by some way: most
 * nights the moon is either up throughout or down throughout, and inventing a
 * sentence for that would be inventing an event.
 */
internal fun describeMoonTimes(moon: MoonTonight): String? {
    val rises = moon.risesAt?.let { "rises ${clock(it)}" }
    val sets = moon.setsAt?.let { "sets ${clock(it)}" }
    val parts = listOfNotNull(rises, sets)
    if (parts.isEmpty()) return null
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

/**
 * The patterns held back for never setting, named in one line.
 *
 * A row each would be seven of them, identical every night of the year, above
 * the handful that have actually changed since last month. Named like this
 * they still reach somebody who wants the signpost - the Plough points at
 * Polaris and Polaris points at everything else - without being the card.
 */
internal fun describeAlwaysUp(names: List<String>): String =
    "Also up, as they are every night of the year: ${names.joinToString(", ")}."

/**
 * One constellation in one line: how high, which way, when, and how to find it.
 *
 * The positional half matches [describePlanet] exactly, so the two cards read
 * the same way. What is added is the star-hop, which is the part somebody who
 * cannot already find the pattern needs, and it is the same sentence every
 * night because the pattern does not change.
 *
 * No magnitude. A constellation does not have one, and the brightest star's
 * belongs in the calendar entry where there is room to say whose it is.
 */
internal fun describeConstellation(seen: ConstellationTonight): String {
    val where = "Highest ${seen.peakAltitude.roundToInt()} degrees " +
        "${Compass.of(seen.peakAzimuth).label} at ${clock(seen.peakAt)}."

    // Said before the star-hop rather than after it, because it is the reason
    // to go out tonight rather than another night, and the star-hop is the
    // same sentence every time.
    val best = if (seen.isAtItsBest) "At its best tonight, highest all year." else null
    val always = if (seen.isCircumpolar) "Never sets from here." else null

    return listOfNotNull(best, where, always, seen.constellation.starHop).joinToString(" ")
}

/**
 * One planet in one line: how high, which way, when, and how bright.
 *
 * The same shape as [describePassLine], because they answer the same question
 * about different things and reading two different shapes on one screen is
 * work the reader should not have to do. The rise or set is appended only when
 * it falls inside the night - most planets are up throughout or down
 * throughout, and inventing a time for that would be inventing an event.
 */
internal fun describePlanet(planet: PlanetTonight): String {
    val pattern = planet.constellation?.let { ", in $it" } ?: ""
    val where = "Highest ${planet.peakAltitude.roundToInt()} degrees " +
        "${Compass.of(planet.peakAzimuth).label} at ${clock(planet.peakAt)}, " +
        "magnitude ${"%.1f".format(java.util.Locale.UK, planet.magnitude)}" +
        "$pattern."

    val comes = planet.risesAt?.let { "Rises ${clock(it)}." }
    val goes = planet.setsAt?.let { "Sets ${clock(it)}." }
    return listOfNotNull(where, comes, goes).joinToString(" ")
}

// --- what the app has been downloading ---

/**
 * How long ago something happened, as a bare phrase.
 *
 * [describeAge] answers the same arithmetic wrapped in the aurora card's own
 * words ("Checked 5 minutes ago"), which do not fit a row about a download. So
 * the phrase alone, to be built into whichever sentence needs it.
 */
internal fun describeElapsed(atMillis: Long, now: Long): String {
    val minutes = ((now - atMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute${plural(minutes)} ago"
        minutes < 60 * 24 -> (minutes / 60).let { "$it hour${plural(it)} ago" }
        else -> (minutes / (60 * 24)).let { "$it day${plural(it)} ago" }
    }
}

/** A body's size in the largest unit that does not round it away. */
internal fun describeSize(bytes: Int): String =
    if (bytes < 1024) "$bytes bytes" else "${bytes / 1024} KB"

/**
 * What one published object's last transfer did, in a sentence.
 *
 * The unchanged case is the one worth getting right: a 304 is what nearly every
 * request answers, by design, and if it reads as anything other than success
 * then an app working exactly as intended looks like one that has stopped.
 */
fun describeTransfer(transfer: Transfer, now: Long): String {
    val ago = describeElapsed(transfer.atMillis, now)
    return when (transfer.state) {
        TransferState.Fetching -> "Downloading..."
        TransferState.Fresh ->
            "Downloaded ${transfer.bytes?.let { describeSize(it) } ?: "a body"} $ago"
        TransferState.Unchanged -> "Already up to date, checked $ago"
        TransferState.Failed ->
            "Failed $ago" + (transfer.error?.let { ": $it" } ?: "")
    }
}

/**
 * How old the copy on the device is.
 *
 * The half of the story that survives a restart. [Transfer] is in memory and
 * says what happened this session; this says what the device is actually
 * working from, which after a fortnight of failed fetches is the only one of
 * the two with anything to report.
 */
fun describeHeldCopy(ageMillis: Long?): String {
    if (ageMillis == null) return "Never downloaded"
    val minutes = (ageMillis / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 60 -> "Held copy is current"
        minutes < 60 * 24 -> (minutes / 60).let { "Held copy is $it hour${plural(it)} old" }
        else -> (minutes / (60 * 24)).let { "Held copy is $it day${plural(it)} old" }
    }
}

/**
 * Whether a held copy is old enough to be worth complaining about.
 *
 * Three days, and the number comes from the propagator rather than from taste.
 * SGP4 is a near-epoch model: elements a few days from their epoch are good to
 * seconds, and a fortnight out they put a pass minutes and degrees from where
 * it really is. The app spent two weeks predicting from a catalogue that old
 * without a word, which is the whole reason this line exists.
 *
 * False for a copy that was never downloaded - that is a different sentence,
 * and saying "stale" about nothing at all reads as a fault rather than as a
 * first run.
 */
fun isStale(ageMillis: Long?, afterMillis: Long = STALE_AFTER_MILLIS): Boolean =
    ageMillis != null && ageMillis > afterMillis

const val STALE_AFTER_MILLIS: Long = 3L * 24 * 60 * 60 * 1000

/** What the satellites card says when it has no elements, and how loudly. */
data class SatellitesNote(val text: String, val alarming: Boolean)

/**
 * Why there are no passes to list, which is three situations and not one.
 *
 * They all arrive as the same empty list and only two of them are faults. A
 * phone that has simply not run its first check yet is not broken, and telling
 * it "could not download, press Check now" reports a failure that has not
 * happened - which on a fresh install is the first thing the app ever says. An
 * app that cries wolf then is one nobody believes the second time.
 *
 * Note what is *not* among the three: a horizon. `PassPlanner.HORIZON_MILLIS`
 * is twelve hours, but that governs how far ahead the alerting poller looks
 * before deciding to interrupt somebody. This card searches tonight's whole
 * darkness window from the elements themselves, so there is no hour at which it
 * legitimately cannot see tonight yet. With elements in hand, an empty list
 * really does mean an empty sky.
 *
 * @param everChecked whether any poll has ever completed on this device.
 * @param failure why the last attempt at the elements failed, where that is
 *   known. It is held in memory for one process, so a background poll that
 *   failed overnight leaves nothing behind to quote.
 */
fun describeMissingElements(everChecked: Boolean, failure: String?): SatellitesNote = when {
    failure != null -> SatellitesNote(
        "Gloaming could not download the orbital elements, so it cannot say what is " +
            "crossing tonight: $failure",
        alarming = true,
    )

    !everChecked -> SatellitesNote(
        "Not worked out yet. Gloaming downloads the orbital elements on its first " +
            "check, and will say what is crossing once it has them.",
        alarming = false,
    )

    else -> SatellitesNote(
        "Gloaming has checked but still has no orbital elements, so it cannot say " +
            "what is crossing tonight. Press Check now to try again.",
        alarming = true,
    )
}

/** How often the phone's position is refreshed, as the location card offers it. */
fun describeCadence(cadence: LocationCadence): String = when (cadence) {
    LocationCadence.OnOpen -> "Each time you open Gloaming"
    LocationCadence.Manual -> "Only when you tap Update now"
    is LocationCadence.Every -> when (cadence.hours) {
        1 -> "Every hour"
        24 -> "Once a day"
        else -> "Every ${cadence.hours} hours"
    }
}

/**
 * When the detected position was last refreshed.
 *
 * The time alone for today, and the date as well otherwise, judged in the
 * phone's own zone: a refresh from the background is the one thing on the card
 * that changes while nobody is looking, so it says when.
 */
fun describeLocationUpdated(updatedAt: Long?, now: Long): String {
    if (updatedAt == null) return "Not updated yet"
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAt))
    if (sameLocalDay(updatedAt, now)) return "Last updated at $time"
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(updatedAt))
    return "Last updated $date at $time"
}

private fun sameLocalDay(a: Long, b: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = a }
    val second = Calendar.getInstance().apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}
