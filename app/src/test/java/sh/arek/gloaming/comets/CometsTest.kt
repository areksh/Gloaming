package sh.arek.gloaming.comets

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.arek.gloaming.astronomy.EventKind
import sh.arek.gloaming.astronomy.Visibility
import sh.arek.gloaming.data.UserSettings

/**
 * What the calendar and the tonight list make of a comet.
 *
 * The comet throughout is C/1995 O1 (Hale-Bopp) at its 1997 apparition, which
 * is convenient for two reasons: it was genuinely spectacular, so a threshold
 * of any sensible value admits it, and it is far enough north to be up all
 * night from Edinburgh. Where a different geometry is wanted the elements are
 * altered rather than a second real comet introduced, so what each test turns
 * on stays in the test.
 */
class CometsTest {

    private val latitude = 55.95
    private val longitude = -3.19

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private val settings = UserSettings(latitude = latitude, longitude = longitude)

    private fun comet(
        magnitudeH: Double? = -1.0,
        slope: Double? = 4.0,
        observed: ObservedMagnitude? = null,
        perihelionJdTt: Double = 2450539.633093244,
    ) = PublishedComet(
        key = "J95O010",
        name = "C/1995 O1 (Hale-Bopp)",
        q = 0.9141695106942098,
        e = 0.9951306139219294,
        i = 89.43017886941958,
        node = 282.470593486876,
        peri = 130.5872422543277,
        perihelionJdTt = perihelionJdTt,
        magnitudeH = magnitudeH,
        slope = slope,
        observed = observed,
    )

    private fun document(vararg comets: PublishedComet) =
        CometsDocument(schema = 1, updated = 0, comets = comets.toList())

    private fun calendar(
        vararg comets: PublishedComet,
        now: String = "1997-03-01T12:00:00Z",
        user: UserSettings = settings,
    ) = Comets.calendar(document(*comets), latitude, longitude, user, at(now))

    private fun tonight(
        vararg comets: PublishedComet,
        now: String = "1997-03-29T22:00:00Z",
        user: UserSettings = settings,
    ) = Comets.tonight(document(*comets), latitude, longitude, user, at(now))

    // --- the calendar entry ---

    @Test
    fun `gives a bright comet one entry with its name on it`() {
        val events = calendar(comet())

        assertEquals(1, events.size)
        assertEquals(EventKind.Comet, events.first().kind)
        assertTrue(events.first().title, events.first().title.contains("Hale-Bopp"))
    }

    /**
     * The window is how long it stays inside the threshold, not the whole year.
     * That is the part somebody would arrange a trip around, and a comet worth
     * seeing is worth seeing for weeks rather than on one night.
     */
    @Test
    fun `spans the weeks the comet is worth seeing rather than a moment`() {
        val event = calendar(comet()).first()

        val days = (event.until - event.from) / (24 * 60 * 60 * 1000)
        assertTrue("window of $days days", days > 14)
        assertTrue("window of $days days", days < 365)
        assertTrue(event.at in event.from..event.until)
    }

    /**
     * The window's edges deliberately run half a magnitude past the threshold.
     *
     * The threshold is a hard line and the sweep is weekly, so without slack a
     * comet hovering at the line would flicker in and out of the calendar from
     * one recomputation to the next. The pin is on the fading edge: the last
     * week of the window has the comet already fainter than the threshold but
     * inside the slack, which the magnitude law itself is asked to confirm.
     * Found unpinned by a mutation that set the slack to zero and survived the
     * whole suite.
     */
    @Test
    fun `holds the window through weeks a whisker outside the threshold`() {
        // H = 5.5 peaks around magnitude 5.7 on this geometry: just inside the
        // default threshold of 6, with a long slow fade through the slack.
        val hovering = comet(magnitudeH = 5.5)
        val event = calendar(hovering).first()

        val atTheEnd = CometFinder.positionOf(
            hovering.orbit,
            magnitudeH = 5.5,
            slope = 4.0,
            latitude = latitude,
            longitude = longitude,
            atMillis = event.until,
        ).magnitude!!
        val threshold = settings.cometMagnitudeThreshold.toDouble()

        assertTrue(
            "the window ended at $atTheEnd, inside the threshold rather than the slack",
            atTheEnd > threshold,
        )
        assertTrue(
            "the window ended at $atTheEnd, past the slack entirely",
            atTheEnd <= threshold + 0.6,
        )
    }

    /**
     * A calendar of the year ahead must not date anything behind us.
     *
     * Easy to get wrong and it was: the brightest sample is usually now, and
     * the night *nearest* now is the one that has just ended - which is the
     * right answer for a dated event and the wrong one for a comet. Reading
     * the real output at eight in the morning showed a comet dated 01:07 that
     * same morning.
     */
    @Test
    fun `never dates a comet in the past`() {
        val now = at("1997-03-29T08:00:00Z")
        val events = Comets.calendar(document(comet()), latitude, longitude, settings, now)

        assertTrue(events.isNotEmpty())
        for (event in events) {
            assertTrue("${event.at} is before $now", event.at >= now)
        }
    }

    /**
     * The window has to contain the moment it is a window around. The peak
     * comes from searching one night and the edges from sampling a week at a
     * time, so the two can disagree by hours at either end - which reads as a
     * calendar entry dated outside its own span.
     */
    @Test
    fun `keeps the moment inside the window it prints`() {
        for (start in listOf("1997-01-15T08:00:00Z", "1997-03-29T22:00:00Z", "1996-11-02T13:00:00Z")) {
            val events = Comets.calendar(
                document(comet()), latitude, longitude, settings, at(start),
            )
            for (event in events) {
                assertTrue(
                    "at ${event.at} outside ${event.from}..${event.until} from $start",
                    event.at in event.from..event.until,
                )
            }
        }
    }

    @Test
    fun `leaves out a comet too faint for the threshold`() {
        assertEquals(emptyList<Any>(), calendar(comet(magnitudeH = 19.0)))
    }

    /**
     * Raising the threshold admits what a stricter one refused.
     *
     * An absolute magnitude of 6.5 on this orbit peaks at 6.7 as seen from
     * here, which straddles the two settings - the point being that the
     * threshold is compared against what the comet looks like from the ground,
     * not against the catalogue number.
     */
    @Test
    fun `admits a fainter comet when the threshold is relaxed`() {
        val faint = comet(magnitudeH = 6.5)

        assertEquals(0, calendar(faint, user = settings.copy(cometMagnitudeThreshold = 4)).size)
        assertEquals(1, calendar(faint, user = settings.copy(cometMagnitudeThreshold = 8)).size)
    }

    @Test
    fun `has nothing to say without a document`() {
        assertEquals(
            emptyList<Any>(),
            Comets.calendar(null, latitude, longitude, settings, at("1997-03-01T12:00:00Z")),
        )
    }

    /** A comet with no absolute magnitude and nothing measured cannot be placed at all. */
    @Test
    fun `leaves out a comet it cannot put on the scale`() {
        assertEquals(emptyList<Any>(), calendar(comet(magnitudeH = null)))
    }

    // --- what the verdict turns on ---

    /**
     * A measurement is what the verdict turns on more than anything else. The
     * formula is routinely a magnitude out either way, so a comet nobody has
     * looked at recently is marginal however good the arithmetic says it is.
     */
    @Test
    fun `calls an unmeasured comet marginal and says why`() {
        val event = calendar(comet()).first()

        val visibility = event.visibility
        assertTrue(visibility.toString(), visibility is Visibility.Marginal)
        assertTrue((visibility as Visibility.Marginal).reason.contains("formula"))
    }

    @Test
    fun `calls a measured comet in a dark sky good`() {
        val events = tonight(
            comet(observed = ObservedMagnitude(1.0, at("1997-03-29T00:00:00Z"), 40, 20)),
            now = "1997-03-29T22:00:00Z",
            user = settings.copy(cometMoonTolerance = 100),
        )

        assertEquals(Visibility.Good, events.first().visibility)
    }

    /**
     * The moonlight rule as the sky actually presents it: the same comet, the
     * same tolerance, two different moons.
     *
     * 1997-03-23 was a full moon over Edinburgh and up essentially all night -
     * an interference of 0.999 - and 1997-03-08 was a new moon, at 0.000. A
     * bright comet has to come out marginal under the first and good under the
     * second, at the default tolerance and with nothing else changed.
     *
     * Testing it this way round rather than by moving the tolerance is the
     * point: it proves the verdict follows the moon, not merely the setting.
     */
    @Test
    fun `calls the same comet marginal under a full moon and good under a new one`() {
        val underFull = tonight(
            comet(observed = ObservedMagnitude(1.0, at("1997-03-23T12:00:00Z"), 40, 20)),
            now = "1997-03-23T22:00:00Z",
        )
        val underNew = tonight(
            comet(observed = ObservedMagnitude(1.0, at("1997-03-08T12:00:00Z"), 40, 20)),
            now = "1997-03-08T22:00:00Z",
        )

        val moonlit = underFull.first().visibility
        assertTrue(moonlit.toString(), moonlit is Visibility.Marginal)
        assertTrue((moonlit as Visibility.Marginal).reason.contains("moon"))

        assertEquals(Visibility.Good, underNew.first().visibility)
    }

    /**
     * The moonlight rule, which matters more for a comet than for anything else
     * the app covers: a comet is a diffuse smudge, so what moonlight destroys is
     * its contrast against the sky rather than its brightness.
     */
    @Test
    fun `calls a comet under a bright moon marginal when the tolerance is strict`() {
        val measured = comet(observed = ObservedMagnitude(1.0, at("1997-03-23T00:00:00Z"), 40, 20))

        // 1997-03-24 was two days before full, with the moon up most of the night.
        val strict = tonight(measured, now = "1997-03-23T22:00:00Z", user = settings.copy(cometMoonTolerance = 25))
        val relaxed = tonight(measured, now = "1997-03-23T22:00:00Z", user = settings.copy(cometMoonTolerance = 100))

        assertTrue(strict.first().visibility.toString(), strict.first().visibility is Visibility.Marginal)
        assertTrue((strict.first().visibility as Visibility.Marginal).reason.contains("moon"))
        assertEquals(Visibility.Good, relaxed.first().visibility)
    }

    /**
     * The altitude rule. A comet low down is lost in the haze in a way a planet
     * is not, which is why the default bar is higher than the satellite one.
     */
    @Test
    fun `calls a low comet marginal when the bar is raised`() {
        val measured = comet(observed = ObservedMagnitude(1.0, at("1997-03-29T00:00:00Z"), 40, 20))
        val high = settings.copy(cometMoonTolerance = 100, minimumCometAltitudeDegrees = 5)
        val strict = settings.copy(cometMoonTolerance = 100, minimumCometAltitudeDegrees = 89)

        assertEquals(Visibility.Good, tonight(measured, user = high).first().visibility)
        assertTrue(tonight(measured, user = strict).first().visibility is Visibility.Marginal)
    }

    // --- correcting the prediction with what people have seen ---

    /**
     * The correction that makes the whole thing hold together.
     *
     * On 2026-08-25 the magnitude law put 220P/McNaught at 15.7 while
     * thirty-nine observers had it at 9.3. Without carrying that difference
     * across, the app would say 9.3 tonight and 15.7 next week - not a forecast
     * so much as a contradiction.
     */
    @Test
    fun `carries a measurement across to the nights either side of it`() {
        val seenAt = at("1997-03-29T00:00:00Z")
        val optimistic = comet(magnitudeH = 12.0, observed = ObservedMagnitude(2.0, seenAt, 30, 15))

        val corrected = Comets.effectiveAbsoluteMagnitude(
            optimistic,
            latitude,
            longitude,
            seenAt,
            UserSettings().cometObservationStaleMillis,
        )!!

        // The comet was measured far brighter than the law said, so the
        // absolute magnitude it implies is far brighter too.
        assertTrue("$corrected", corrected < 12.0)

        val quoted = CometFinder.positionOf(
            optimistic.orbit, corrected, optimistic.slope, latitude, longitude, seenAt,
        ).magnitude!!
        assertEquals(2.0, quoted, 0.01)
    }

    /** Past the staleness window the measurement stops counting and the formula returns. */
    @Test
    fun `stops believing a measurement once it is stale`() {
        val seenAt = at("1997-01-01T00:00:00Z")
        val optimistic = comet(magnitudeH = 12.0, observed = ObservedMagnitude(2.0, seenAt, 30, 15))
        val week = UserSettings().cometObservationStaleMillis

        assertEquals(
            12.0,
            Comets.effectiveAbsoluteMagnitude(
                optimistic, latitude, longitude, seenAt + 60L * 24 * 3_600_000, week,
            )!!,
            1e-9,
        )
    }

    @Test
    fun `keeps the published magnitude when nobody has measured anything`() {
        assertEquals(
            -1.0,
            Comets.effectiveAbsoluteMagnitude(
                comet(), latitude, longitude, at("1997-03-29T00:00:00Z"), Long.MAX_VALUE,
            )!!,
            1e-9,
        )
    }

    /** A measurement alone still puts a comet on the scale when the file gives no H. */
    @Test
    fun `places a comet with no absolute magnitude from a measurement alone`() {
        val seenAt = at("1997-03-29T00:00:00Z")
        val measured = comet(magnitudeH = null, observed = ObservedMagnitude(3.0, seenAt, 30, 15))

        val corrected = Comets.effectiveAbsoluteMagnitude(
            measured, latitude, longitude, seenAt, UserSettings().cometObservationStaleMillis,
        )
        assertNotNull(corrected)

        val quoted = CometFinder.positionOf(
            measured.orbit, corrected, measured.slope, latitude, longitude, seenAt,
        ).magnitude!!
        assertEquals(3.0, quoted, 0.01)
    }

    // --- tonight ---

    @Test
    fun `lists a comet that is up tonight`() {
        val events = tonight(comet())

        assertEquals(1, events.size)
        assertTrue(events.first().detail, events.first().detail.contains("degrees"))
    }

    /**
     * Tonight answers where to look now, so its moment is tonight's rather than
     * the best night of the year the calendar entry names.
     */
    @Test
    fun `answers tonight rather than the best night of the year`() {
        val entry = calendar(comet(), now = "1997-01-15T12:00:00Z").first()
        val listed = tonight(comet(), now = "1997-01-15T22:00:00Z").first()

        assertTrue(
            "calendar ${entry.at} should not be tonight ${listed.at}",
            entry.at != listed.at,
        )
    }

    /** No darkness means nothing to list, which at high latitude in June is the answer. */
    @Test
    fun `lists nothing where the sun does not set`() {
        assertEquals(
            emptyList<Any>(),
            Comets.tonight(
                document(comet()),
                78.2,
                15.6,
                UserSettings(latitude = 78.2, longitude = 15.6),
                at("1997-06-21T12:00:00Z"),
            ),
        )
    }

    /** ASCII throughout, like every other string the app shows. */
    @Test
    fun `writes plain ascii`() {
        val events = calendar(comet()) + tonight(comet())

        assertTrue(events.isNotEmpty())
        for (event in events) {
            for (text in listOf(event.title, event.detail, reason(event.visibility))) {
                assertTrue(text, text.all { it.code in 32..126 })
            }
        }
    }

    private fun reason(visibility: Visibility) = when (visibility) {
        is Visibility.Marginal -> visibility.reason
        is Visibility.NotFromHere -> visibility.reason
        Visibility.Good -> ""
    }
}
