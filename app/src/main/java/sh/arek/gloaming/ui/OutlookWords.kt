package sh.arek.gloaming.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.Compass
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.domain.ClearSpell
import sh.arek.gloaming.domain.DarknessShortfall
import sh.arek.gloaming.domain.Excluded
import sh.arek.gloaming.domain.Gate
import sh.arek.gloaming.domain.Outlook
import sh.arek.gloaming.domain.OutlookReport
import sh.arek.gloaming.domain.Outlooks
import sh.arek.gloaming.domain.Pick
import sh.arek.gloaming.domain.PickKind
import sh.arek.gloaming.domain.StormMode

/**
 * The outlook as words.
 *
 * Nothing here decides anything: the decision is [Outlooks], and every
 * sentence below says only what that decision already established. Where the
 * data does not say a thing, the sentence leaves it out rather than guess - a
 * sentence that is vague is a nuisance, one that is wrong teaches people to
 * stop reading it.
 */
object OutlookWords {

    private const val HOUR_MILLIS = 60 * 60 * 1000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val MIDNIGHT_MARGIN_MINUTES = 30
    private const val SHARED_TIME_MILLIS = 15 * 60 * 1000L
    private const val HIGH_DEGREES = 50.0
    private const val LOW_DEGREES = 25.0
    private const val SHOWER_SUFFIX = " at their best"
    private const val LIKELY_PERCENT = 50
    private val NUMBERS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    )

    /** The opening sentence, or null where there is no outlook to give. */
    fun headline(report: OutlookReport): String? = when (val outlook = report.outlook) {
        Outlook.NoLocation -> null
        is Outlook.CannotTell -> when {
            !outlook.forecastHeld && outlook.elementsStale ->
                "Gloaming cannot say what tonight holds: it has no recent forecast, " +
                    "and its satellite data is too old to trust."
            !outlook.forecastHeld ->
                "Gloaming cannot say what tonight holds: it has no recent forecast."
            outlook.elementsStale ->
                "Gloaming cannot say when satellites pass: its satellite data is too old to trust."
            else -> "Gloaming cannot say what tonight holds."
        }
        is Outlook.AuroraNow ->
            if (outlook.conditional) {
                "If it is clear, aurora is ${chance(outlook.probability)} now: " +
                    "${outlook.probability}% here. " +
                    "Look ${report.poleward.label}, low down."
            } else {
                auroraAlert(outlook.probability, outlook.clearUntil, report.poleward)
            }
        is Outlook.NoNight ->
            "It does not get properly dark tonight. " +
                "Only the moon and the brightest planets will show."
        is Outlook.Overcast -> "Overcast all night. Nothing worth going out for."
        is Outlook.GoOut -> moonSpoils(report)?.let { spoiled ->
            val lead = clearLead(outlook, "Clear from")
            if (lead == null) spoiled.replaceFirstChar { it.uppercase() } else "$lead, but $spoiled"
        } ?: goOut(outlook)
        is Outlook.Quiet -> quiet(outlook, report)
    }

    /**
     * The line under it, or null where there is nothing more to add.
     *
     * [nowMillis] is needed only to say whether a later clear spell is
     * tomorrow or further off; nothing else here reads a clock.
     */
    fun follow(report: OutlookReport, nowMillis: Long): String? = when (val outlook = report.outlook) {
        Outlook.NoLocation -> null
        is Outlook.CannotTell -> when {
            !outlook.forecastHeld && outlook.elementsStale -> "Check now to fetch both."
            !outlook.forecastHeld -> "Check now to fetch one."
            outlook.elementsStale -> "Check now to fetch fresh data."
            else -> "Check now to refresh."
        }
        is Outlook.AuroraNow ->
            "It can fade within the hour. Watch tonight keeps checking every " +
                "${StormMode.POLL_MINUTES} minutes."
        is Outlook.NoNight ->
            "Noctilucent clouds are possible low in the ${report.poleward.label} tonight."
                .takeIf { outlook.noctilucent }
        is Outlook.Overcast -> overcastFollow(outlook, nowMillis)
        is Outlook.GoOut ->
            if (moonSpoils(report) != null) stillWorthALook(outlook.picks) else auroraLine(report)
        is Outlook.Quiet -> auroraLine(report)
    }

    /** Why a candidate was set aside, for the list of what was not chosen. */
    fun excluded(e: Excluded, threshold: Int, nowMillis: Long): String = when (e.gate) {
        Gate.Visibility -> e.note ?: "It will not be well placed from here."
        Gate.Stale -> "Satellite times are days old, so they cannot be trusted."
        // A time gate also fails for something that starts after the darkness ends.
        Gate.Time ->
            if (e.atMillis != null && e.atMillis < nowMillis) {
                "Already over."
            } else {
                "Not during tonight's darkness."
            }
        Gate.Darkness -> when (e.darkness) {
            DarknessShortfall.NeverDark -> "It never gets dark enough tonight."
            DarknessShortfall.NotYet -> "It is not dark enough yet."
            else -> "Only in twilight, which washes it out."
        }
        Gate.Cloud -> e.note?.let { "Forecast $it% cloud when it crosses." }
            ?: "No forecast for that hour, so it cannot be counted on."
        Gate.Moon -> "The moon is up and ${e.note}% lit, which washes it out."
        Gate.Height ->
            if (e.kind == PickKind.Planet) {
                "Only ${e.note} degrees up by then."
            } else {
                "Only ${e.note} degrees up at best."
            }
        Gate.Sunlit -> "In the earth's shadow, so it will not show."
        // Held back at or above the threshold can only mean the reading is old.
        Gate.Aurora ->
            if ((e.note?.toIntOrNull() ?: 0) >= threshold) {
                "${e.note}%, but the reading is too old to act on."
            } else {
                "${e.note}%, too weak to see from here."
            }
    }

    /**
     * The alert for an aurora over the user's threshold and clear now; the
     * same words as the headline. [towards] is the observer's nearer pole.
     */
    fun auroraAlert(probability: Int, clearUntil: Long?, towards: Compass): String {
        val until = clearUntil?.let { ", while it stays clear until ${clock(it)}" } ?: ""
        return "Aurora ${chance(probability)} now: $probability% here. " +
            "Look ${towards.label}, low down$until."
    }

    /**
     * "likely" at even odds or better, "possible" below. The alert fires at
     * the user's own threshold, which can be as low as 10%, and "likely" at
     * 15% would be the sort of claim that teaches people to stop reading.
     */
    private fun chance(probability: Int): String =
        if (probability >= LIKELY_PERCENT) "likely" else "possible"

    private fun goOut(outlook: Outlook.GoOut): String {
        val first = outlook.picks.first().atMillis
        if (outlook.conditional) return "If it is clear, ${goClause(outlook.picks, at(first))}."

        val from = outlook.clearFrom
        val until = outlook.clearUntil
        if (from == null || until == null) return "${goClause(outlook.picks, at(first)).capital()}."

        return when {
            outlook.clearAllNight ->
                "Clear all night. ${goClause(outlook.picks, at(first)).capital()}."
            outlook.clearUnderway ->
                "Clear until ${clock(until)}. ${goClause(outlook.picks, at(first)).capital()}."
            else -> {
                val start = if (nearMidnight(from)) "at midnight" else "from ${clock(from)}"
                "${laterSpell(outlook.clearEarlier, from, until)} " +
                    "${goClause(outlook.picks, start).capital()}."
            }
        }
    }

    /**
     * The weather up to a clear run that starts later on. "Cloudy until" only
     * where nothing before it is clear: with an earlier spell, that is said
     * first and the run is the second.
     */
    private fun laterSpell(earlier: ClearSpell?, from: Long, until: Long): String {
        earlier ?: return "Cloudy until ${clock(from)}, then ${hours(until - from)}."
        val first = if (earlier.underway) {
            "Clear until ${clock(earlier.until)}"
        } else {
            "Clear from ${clock(earlier.from)} to ${clock(earlier.until)}"
        }
        return "$first, and again from ${clock(from)} to ${clock(until)}."
    }

    /**
     * "go out WHEN for X". Picks more than a quarter of an hour apart each get
     * their own time, because one time for both would send somebody out for
     * the second long before it is there.
     */
    private fun goClause(picks: List<Pick>, firstWhen: String): String {
        val second = picks.getOrNull(1)
        val apart = second != null &&
            Math.abs(second.atMillis - picks.first().atMillis) > SHARED_TIME_MILLIS &&
            // Both inside the half hour round midnight say "at midnight".
            at(second.atMillis) != firstWhen
        if (!apart) return "go out $firstWhen for ${names(picks)}"
        return "go out $firstWhen for ${pickName(picks.first())}, and ${at(second!!.atMillis)} " +
            "for ${pickName(second)}"
    }

    private fun String.capital(): String = replaceFirstChar { it.uppercase() }

    /** How the clear weather runs, for a sentence that goes on to say what spoils it. */
    private fun clearLead(outlook: Outlook.GoOut, startedPhrase: String): String? {
        val from = outlook.clearFrom
        val until = outlook.clearUntil
        if (outlook.conditional || from == null || until == null) return null
        return when {
            outlook.clearAllNight -> "Clear all night"
            outlook.clearUnderway -> "Clear until ${clock(until)}"
            else -> "$startedPhrase ${clock(from)}"
        }
    }

    private fun quiet(outlook: Outlook.Quiet, report: OutlookReport): String {
        val lead = if (outlook.conditional) {
            "Dark from ${clock(outlook.darkFrom)}"
        } else {
            "Clear and dark from ${clock(outlook.darkFrom)}"
        }
        moonSpoils(report)?.let { return "$lead, but $it" }
        val learn = outlook.learn?.let { " A good night to learn $it." } ?: ""
        return "$lead, with nothing special on.$learn"
    }

    /**
     * "the moon will hide most of X: it is N% lit and up.", for the first
     * faint thing the moon set aside. The aurora is left out: it is a reading,
     * and "most of the aurora" is not a thing the moon hides.
     *
     * Not "up until dawn": whether it stays up is not in what the decision
     * carries, only that it was up when the thing was judged.
     */
    private fun moonSpoils(report: OutlookReport): String? {
        val moon = report.excluded.firstOrNull {
            it.gate == Gate.Moon && it.subject != Outlooks.AURORA_SUBJECT
        } ?: return null
        return "the moon will hide most of ${showerName(moon.subject)}: " +
            "it is ${moon.note}% lit and up."
    }

    private fun stillWorthALook(picks: List<Pick>): String? {
        if (picks.isEmpty()) return null
        val names = names(picks)
        if (picks.size > 1) return "${names.capital()} are still worth a look."
        return "${names.capital()} is still worth a look${where(picks.first())}."
    }

    /**
     * Where to look, only when the pick is at the planet's own peak: elsewhere
     * the stored bearing and height belong to a different moment.
     */
    private fun where(pick: Pick): String {
        val planet = pick.planet ?: return ""
        if (planet.peakAt != pick.atMillis) return ""
        val height = when {
            planet.peakAltitude >= HIGH_DEGREES -> "high "
            planet.peakAltitude < LOW_DEGREES -> "low "
            else -> ""
        }
        return ", ${height}in the ${Compass.of(planet.peakAzimuth).label} around " +
            clock(pick.atMillis)
    }

    private fun auroraLine(report: OutlookReport): String? {
        val probability = report.auroraProbability ?: return null
        if (probability >= report.threshold) return null
        val how = if (probability * 4 <= report.threshold * 3) "well under" else "just under"
        return "Aurora $probability%, $how the ${report.threshold}% that would wake you."
    }

    private fun overcastFollow(outlook: Outlook.Overcast, nowMillis: Long): String? {
        val missed = outlook.missed?.let { pick ->
            val event = pick.event
            when {
                event?.kind == EventKind.MeteorShower && pick.atMillis == event.at ->
                    "${showerName(pick.subject).replaceFirstChar { it.uppercase() }} peak tonight."
                else -> "Cloud will hide ${pickName(pick)} tonight."
            }
        }
        val next = outlook.nextClear?.let { spell ->
            "The next clear spell in the forecast is ${day(spell, nowMillis)} from ${clock(spell.from)}."
        } ?: "The forecast has no clear spell yet.".takeIf { missed == null }
        return listOfNotNull(missed, next).joinToString(" ").ifEmpty { null }
    }

    private fun day(spell: ClearSpell, nowMillis: Long): String {
        val days = dayNumber(spell.from) - dayNumber(nowMillis)
        return when {
            days <= 0 -> "tonight"
            days == 1L -> "tomorrow"
            else -> "on " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(spell.from))
        }
    }

    /** Days since the epoch in the device's own zone, so midnight is where the person sees it. */
    private fun dayNumber(millis: Long): Long {
        val zone = Calendar.getInstance().timeZone
        return Math.floorDiv(millis + zone.getOffset(millis), DAY_MILLIS)
    }

    private fun hours(span: Long): String {
        val count = (span / HOUR_MILLIS).toInt().coerceAtLeast(1)
        if (count == 1) return "an hour of clear sky"
        val word = NUMBERS.getOrNull(count - 1) ?: count.toString()
        return "$word clear hours"
    }

    /** "at midnight" within half an hour of it, else "at HH:mm". */
    private fun at(millis: Long): String =
        if (nearMidnight(millis)) "at midnight" else "at ${clock(millis)}"

    private fun nearMidnight(millis: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minute <= MIDNIGHT_MARGIN_MINUTES || minute >= 24 * 60 - MIDNIGHT_MARGIN_MINUTES
    }

    /** Two names joined by "and", or by a comma where a name has its own "and" in it. */
    private fun names(picks: List<Pick>): String {
        val named = picks.map { pickName(it) }
        return named.joinToString(if (named.any { " and " in it }) ", and " else " and ")
    }

    /**
     * What a pick is called in a sentence. Eclipse and occultation titles are
     * descriptions ("Partial eclipse of the sun") and read as the thing after
     * "for"; everything else is already a name.
     */
    private fun pickName(pick: Pick): String {
        val lower = pick.subject.replaceFirstChar { it.lowercase() }
        return when (pick.event?.kind) {
            EventKind.MeteorShower -> showerName(pick.subject)
            EventKind.SolarEclipse, EventKind.LunarEclipse -> "the $lower"
            EventKind.Occultation, EventKind.Conjunction ->
                // "The moon passes Venus" is a sentence; "Venus and Jupiter at their
                // closest" is already a phrase and keeps its planet's capital.
                if (pick.subject.startsWith("The ")) lower.replace(" passes ", " passing ") else pick.subject
            else -> pick.subject
        }
    }

    /** "Geminids at their best" as "the Geminids". Other titles pass through. */
    private fun showerName(subject: String): String =
        if (subject.endsWith(SHOWER_SUFFIX)) "the " + subject.removeSuffix(SHOWER_SUFFIX) else subject
}
