package sh.arek.gloaming.share

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/**
 * One sky event as a message somebody can read in a chat window.
 *
 * The plain-text sibling of [ics]. Both exist because they answer different
 * questions: an .ics is for putting the event in a calendar, and this is for
 * telling somebody about it - and a message that arrives as an attachment
 * nobody opens is not telling them about it.
 *
 * In the device's own zone, because a time in UTC is wrong by an hour for half
 * the year here and by more elsewhere - and the whole point of the message is
 * that the recipient knows when to go outside.
 *
 * The visibility caveat is carried where there is one. "It only reaches 4
 * degrees, low in the south" is the difference between a wasted evening and a
 * good one, and sending the headline without it is sending a half-truth.
 */
fun shareText(event: SkyEvent): String = buildString {
    appendLine(event.title)
    appendLine(when {
        sameDay(event.from, event.until) ->
            "${DATE.format(Date(event.from))}, ${TIME.format(Date(event.from))} to " +
                TIME.format(Date(event.until))

        else ->
            "${DATE.format(Date(event.from))} ${TIME.format(Date(event.from))} to " +
                "${DATE.format(Date(event.until))} ${TIME.format(Date(event.until))}"
    })
    appendLine()
    appendLine(event.detail)

    when (val visibility = event.visibility) {
        is Visibility.Marginal -> appendLine(visibility.reason)
        is Visibility.NotFromHere -> appendLine(visibility.reason)
        Visibility.Good -> Unit
    }
}.trimEnd()

private fun sameDay(one: Long, other: Long): Boolean =
    DATE.format(Date(one)) == DATE.format(Date(other))

/**
 * Recreated per call rather than held.
 *
 * `SimpleDateFormat` is not thread-safe, and it caches the zone it was built
 * with - which would leave a message written after a flight timed in the zone
 * the app started in.
 */
private val DATE: SimpleDateFormat
    get() = SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())

private val TIME: SimpleDateFormat
    get() = SimpleDateFormat("HH:mm", Locale.getDefault())
