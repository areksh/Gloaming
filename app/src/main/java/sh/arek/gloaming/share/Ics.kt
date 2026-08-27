package sh.arek.gloaming.share

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.domain.EventPlanner

/**
 * One sky event as a calendar file, for somebody else's software to read.
 *
 * Everything else the app writes is read back by the app. This is the one
 * output whose consumer is a stranger, so it follows RFC 5545 to the letter
 * rather than to the spirit - CRLF endings, folded lines, escaped text. A file
 * that looks fine and fails to import is worse than no export at all, because
 * the failure lands in somebody else's calendar app with no explanation and
 * nothing the user can do about it.
 *
 * Hand-written rather than pulled in as a dependency. The whole of what is
 * needed here is four properties and an escaping rule; a calendaring library
 * would be several thousand lines to produce forty.
 *
 * No network, no Android, no permission. This is a file the user hands to
 * whatever they like.
 */
fun ics(event: SkyEvent): String = buildString {
    // No folding on these: every one is fixed and short.
    append("BEGIN:VCALENDAR\r\n")
    append("VERSION:2.0\r\n")
    append("PRODID:-//Gloaming//Sky calendar//EN\r\n")
    append("CALSCALE:GREGORIAN\r\n")
    append("METHOD:PUBLISH\r\n")
    append("BEGIN:VEVENT\r\n")
    append(fold("UID:${uid(event)}"))
    append(fold("DTSTAMP:${utc(event.at)}"))
    append(fold("DTSTART:${utc(event.from)}"))
    // Omitted where the event has no duration, which RFC 5545 requires: DTEND
    // "MUST be greater than" DTSTART, and a new moon or a greatest elongation
    // happens at an instant. A DATE-TIME start with no end is precisely how the
    // standard says to write one, and writing DTEND equal to DTSTART is how a
    // file gets rejected by the stricter half of the calendar apps that exist.
    if (event.until > event.from) append(fold("DTEND:${utc(event.until)}"))
    append(fold("SUMMARY:${escape(event.title)}"))
    append(fold("DESCRIPTION:${escape(event.detail)}"))
    append("END:VEVENT\r\n")
    append("END:VCALENDAR\r\n")
}

/**
 * A file name somebody can find again in a downloads folder.
 *
 * From the title rather than the key, because the key is for machines and this
 * is the one string the user sees before the file reaches its destination.
 */
fun icsFileName(event: SkyEvent): String {
    val slug = event.title
        .lowercase(Locale.UK)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")

    return if (slug.isEmpty()) "sky-event.ics" else "$slug.ics"
}

/**
 * The same identity the star uses, with a domain suffix as RFC 5545 asks.
 *
 * Deliberately the star key rather than something generated: re-importing an
 * event the user already has updates the entry in place instead of leaving two
 * copies of the same eclipse. It also means a comet is keyed on its name, which
 * is what stops a comet multiplying - the calendar invents a comet's moment and
 * that moment moves on every recompute.
 */
private fun uid(event: SkyEvent): String =
    EventPlanner.starKey(event).map { if (it.isLetterOrDigit()) it else '-' }.joinToString("") +
        "@gloaming.arek.sh"

/**
 * Escaped as a TEXT value.
 *
 * The backslash goes first: escaping it after the others would double the
 * backslashes those others had just introduced. Commas are not hypothetical -
 * every comet description has one today ("Magnitude 9.5, from 39 observers").
 */
private fun escape(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")

/** UTC in the basic form, with the Z that says it is UTC. */
private fun utc(millis: Long): String =
    SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.UK)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

/**
 * One property, folded to 75 octets a line.
 *
 * Octets rather than characters, and it matters: a degree sign is two bytes in
 * UTF-8, so a writer that counts characters emits lines a strict parser
 * rejects. A multi-byte character is never split across the fold, because half
 * a character on each of two lines is not a character.
 *
 * Continuations begin with a single space, which the reader strips - so
 * unfolding gives back exactly what went in.
 */
private fun fold(line: String): String {
    val bytes = line.toByteArray(Charsets.UTF_8)
    if (bytes.size <= LINE_OCTETS) return "$line\r\n"

    val out = StringBuilder()
    var taken = 0
    var budget = LINE_OCTETS

    while (taken < line.length) {
        var length = 0
        var octets = 0
        while (taken + length < line.length) {
            val width = line[taken + length].toString().toByteArray(Charsets.UTF_8).size
            if (octets + width > budget) break
            octets += width
            length++
        }
        // A single character wider than the whole budget cannot happen at 74
        // octets, but taking none would loop for ever if it did.
        if (length == 0) length = 1

        out.append(line, taken, taken + length).append("\r\n")
        taken += length
        // The continuation's leading space is one of its 75 octets.
        budget = LINE_OCTETS - 1
        if (taken < line.length) out.append(' ')
    }
    return out.toString()
}

/** RFC 5545: no line may exceed 75 octets, not counting the CRLF. */
private const val LINE_OCTETS = 75
