package sh.arek.gloaming.share

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.astronomy.Visibility

/**
 * The calendar file, which somebody else's software has to read.
 *
 * Everything else the app writes is read back by the app. This is the one
 * output whose consumer is a stranger, so it follows RFC 5545 to the letter
 * rather than to the spirit: CRLF endings, folded lines, escaped text. A file
 * that "looks fine" and fails to import is worse than no export at all,
 * because the failure lands in somebody else's calendar app with no
 * explanation.
 */
class IcsTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        kind: EventKind = EventKind.LunarEclipse,
        title: String = "Total lunar eclipse",
        detail: String = "Totality from 02:11 to 03:24.",
        from: String = "2028-12-31T01:00:00Z",
        until: String = "2028-12-31T04:30:00Z",
    ) = SkyEvent(
        kind = kind,
        at = at(from),
        from = at(from),
        until = at(until),
        title = title,
        detail = detail,
        visibility = Visibility.Good,
    )

    private fun lines(text: String) = text.split("\r\n")

    @Test
    fun `wraps the event in a calendar object`() {
        val out = lines(ics(event()))

        assertEquals("BEGIN:VCALENDAR", out.first())
        assertEquals("END:VCALENDAR", out.last { it.isNotEmpty() })
        assertTrue(out.contains("BEGIN:VEVENT"))
        assertTrue(out.contains("END:VEVENT"))
        assertTrue(out.contains("VERSION:2.0"))
    }

    /**
     * RFC 5545 is explicit that lines end CRLF. Plenty of parsers tolerate a
     * bare newline and some do not, and the ones that do not are the ones
     * shipped with desktop mail clients.
     */
    @Test
    fun `ends every line with a carriage return and a newline`() {
        val out = ics(event())

        assertFalse(out.replace("\r\n", "").contains("\n"))
        assertTrue(out.endsWith("\r\n"))
    }

    /** UTC, in the basic form, with the trailing Z that says so. */
    @Test
    fun `writes the times as UTC instants`() {
        val out = lines(ics(event()))

        assertTrue(out.toString(), out.contains("DTSTART:20281231T010000Z"))
        assertTrue(out.toString(), out.contains("DTEND:20281231T043000Z"))
    }

    @Test
    fun `carries the title and the detail`() {
        val out = lines(ics(event()))

        assertTrue(out.toString(), out.contains("SUMMARY:Total lunar eclipse"))
        assertTrue(out.toString(), out.any { it.startsWith("DESCRIPTION:Totality from") })
    }

    /**
     * The same identity the star uses, so re-importing an event the user
     * already has updates it in place rather than making a second copy of the
     * same eclipse.
     */
    @Test
    fun `identifies the event by the key the star uses`() {
        val out = lines(ics(event()))

        val uid = out.single { it.startsWith("UID:") }
        assertTrue(uid, uid.contains("LunarEclipse"))
        assertTrue(uid, uid.endsWith("@gloaming.arek.sh"))
    }

    @Test
    fun `gives two different events two different identities`() {
        val one = ics(event(from = "2028-12-31T01:00:00Z"))
        val other = ics(event(from = "2029-06-26T01:00:00Z"))

        assertTrue(uidOf(one) != uidOf(other))
    }

    /**
     * A comet is keyed on its name rather than its time, because the calendar
     * invents its moment and that moment moves on every recompute. Two exports
     * of the same comet a week apart have to be the same entry.
     */
    @Test
    fun `keys a comet on its name so the entry does not multiply`() {
        val one = ics(event(kind = EventKind.Comet, title = "Comet 12P", from = "2028-12-31T01:00:00Z"))
        val other = ics(event(kind = EventKind.Comet, title = "Comet 12P", from = "2029-01-07T01:00:00Z"))

        assertEquals(uidOf(one), uidOf(other))
    }

    private fun uidOf(text: String) = lines(text).single { it.startsWith("UID:") }

    // --- escaping, which is where a hand-written writer goes wrong ---

    /**
     * Commas, semicolons and backslashes are field separators in a property
     * value. The comet details contain commas today - "Magnitude 9.5, from 39
     * observers" - so this is not a hypothetical.
     */
    @Test
    fun `escapes the characters that separate fields`() {
        val out = ics(event(detail = "Magnitude 9.5, from 39 observers; measured\\predicted."))

        val description = lines(out).single { it.startsWith("DESCRIPTION:") }
        assertTrue(description, description.contains("9.5\\, from"))
        assertTrue(description, description.contains("observers\\; measured"))
        assertTrue(description, description.contains("measured\\\\predicted"))
    }

    @Test
    fun `escapes a newline rather than breaking the property`() {
        val out = ics(event(detail = "First line.\nSecond line."))

        val description = lines(out).single { it.startsWith("DESCRIPTION:") }
        assertTrue(description, description.contains("First line.\\nSecond line."))
    }

    // --- folding ---

    /**
     * RFC 5545 caps a line at 75 octets and folds the rest onto continuation
     * lines beginning with a space. The comet descriptions run well past that
     * already.
     */
    @Test
    fun `folds a long line at seventy-five octets`() {
        val long = "Magnitude 9.5, from 39 observers within the last few days. " +
            "Highest at 46 degrees in the south, best after midnight when the moon has set."

        val out = lines(ics(event(detail = long)))

        assertTrue(out.toString(), out.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(out.any { it.startsWith(" ") })
    }

    /**
     * Unfolding a folded file has to give back exactly what went in: strip the
     * CRLF and the single leading space from each continuation and the original
     * property is there.
     */
    @Test
    fun `unfolds back to the property that was written`() {
        val long = "A description long enough to need folding, several times over, " +
            "so that the continuation handling is exercised rather than merely present."

        val out = ics(event(detail = long))

        val unfolded = out.replace("\r\n ", "")
        assertTrue(unfolded, unfolded.contains("DESCRIPTION:$long".replace(",", "\\,")))
    }

    /**
     * Folding counts octets, not characters. A degree sign is two bytes in
     * UTF-8, so a writer that counts characters produces lines a parser
     * rejects - and every comet description is a candidate for one.
     */
    @Test
    fun `folds on octets rather than characters`() {
        val out = lines(ics(event(detail = "e".repeat(40) + "é".repeat(40))))

        assertTrue(out.toString(), out.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
    }

    // --- the file it is written to ---

    @Test
    fun `names the file after the event`() {
        assertEquals("total-lunar-eclipse.ics", icsFileName(event()))
    }

    @Test
    fun `keeps a file name usable when the title is not`() {
        val name = icsFileName(event(title = "Moon 0.3 deg from Venus / Mars?"))

        assertEquals("moon-0-3-deg-from-venus-mars.ics", name)
    }

    @Test
    fun `falls back to a name rather than producing an empty one`() {
        assertEquals("sky-event.ics", icsFileName(event(title = "***")))
    }
}
