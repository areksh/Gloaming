package sh.arek.gloaming.work

import android.content.SharedPreferences

/**
 * What one wake armed, so that the next one can say whether it arrived.
 *
 * @param atMillis wall clock when the alarm was armed.
 * @param askedMinutes what that arming asked for, before jitter.
 * @param complete whether the poll that armed it got as far as its own
 *   reschedule. False is written first and replaced at the end of the poll, so
 *   a poll that is killed leaves its own false behind.
 * @param bootInstantMillis wall clock minus uptime: the same number for every
 *   wake within one boot, and a different one after a reboot or a clock
 *   correction. See [WakeAudit.findings].
 */
data class WakeRecord(
    val atMillis: Long,
    val askedMinutes: Int,
    val complete: Boolean,
    val bootInstantMillis: Long,
)

/**
 * Saying out loud what the journal could previously only imply.
 *
 * Two questions the log could not answer about itself:
 *
 * **Did the last poll finish?** A `goAsync` broadcast is killed at sixty
 * seconds and the kill is not graceful - the coroutine's `finally` never runs -
 * so a killed poll writes whichever stage lines it reached and then simply
 * stops. Read the morning after, that is indistinguishable from a poll that had
 * nothing to say, and on 2026-09-21 it hid a real one: a wake at 03:08:12Z
 * logged its aurora outcome and neither of the two checks after it, and the
 * near-zenith pass it had woken up to announce was never mentioned.
 *
 * **Did this wake arrive when it was asked to?** Inexact alarms land late by
 * design and [sh.arek.gloaming.domain.Delivery] documents the band - 1.25 to
 * 1.99 times the interval, median 1.78. A wake outside that band is a different
 * thing entirely, and on 2026-09-16 one took 167 minutes against a 30 minute
 * request, which nothing recorded.
 *
 * Both are answered from one record rather than by logging every wake: a line
 * per poll would be a third again as much journal for a log measured in days,
 * and neither of these is worth saying when nothing went wrong.
 */
object WakeAudit {

    /**
     * How many times the requested interval a wake may take before it is worth
     * a line.
     *
     * Two, because [sh.arek.gloaming.domain.Delivery] measured the worst
     * ordinary delivery at 1.99 and halves every deadline on the strength of
     * it. Anything past that is not the batching window.
     */
    const val LATE_MULTIPLE = 2

    /**
     * And however many multiples, at least this much absolute lateness.
     *
     * A one minute request delivered in three is twice over and completely
     * unremarkable - the window on a wake that short is seconds, and the
     * approach to a pass alert asks for a run of them. Without a floor those
     * would be most of what this ever said.
     */
    const val LATE_FLOOR_MILLIS = 5 * 60_000L

    /** Tolerance on the boot instant, absorbing NTP without absorbing a reboot. */
    const val SAME_BOOT_MILLIS = 60_000L

    /**
     * What is worth saying about [previous], given that a wake is happening now.
     *
     * Empty when the last wake behaved, which is almost always.
     *
     * Silent across a reboot. Elapsed realtime restarts at zero, so no duration
     * spanning one means anything, and a poll interrupted by a shutdown was not
     * killed in any sense worth reporting. `BootReceiver` already records that
     * the reboot happened, which is the true account of that gap.
     */
    fun findings(previous: WakeRecord?, nowMillis: Long, bootInstantMillis: Long): List<String> {
        if (previous == null) return emptyList()
        if (kotlin.math.abs(bootInstantMillis - previous.bootInstantMillis) > SAME_BOOT_MILLIS) {
            return emptyList()
        }
        val since = nowMillis - previous.atMillis
        if (since < 0) return emptyList()

        val findings = mutableListOf<String>()
        if (!previous.complete) {
            findings += "the previous poll never reached its reschedule - killed at sixty " +
                "seconds, or the process died - ${minutes(since)}m ago"
        }
        val asked = previous.askedMinutes * 60_000L
        if (asked > 0 && since > asked * LATE_MULTIPLE && since - asked > LATE_FLOOR_MILLIS) {
            findings += "this wake asked for ${previous.askedMinutes}m and arrived after " +
                "${minutes(since)}m"
        }
        return findings
    }

    private fun minutes(millis: Long): Long = millis / 60_000L
}

/**
 * Where the record is kept.
 *
 * Device-encrypted, because the wake this exists to explain is the one that
 * happens before anybody has unlocked the phone.
 */
class WakeRecordStore(private val prefs: SharedPreferences) {

    fun read(): WakeRecord? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return null
        return WakeRecord(
            atMillis = at,
            askedMinutes = prefs.getInt(KEY_ASKED, 0),
            complete = prefs.getBoolean(KEY_COMPLETE, true),
            bootInstantMillis = prefs.getLong(KEY_BOOT, 0L),
        )
    }

    /**
     * Written with `commit` rather than `apply`.
     *
     * The whole point of the record is to survive a process that is about to be
     * killed. `apply` returns immediately and writes on a background thread,
     * which is exactly the thread that does not get to finish.
     */
    @Suppress("ApplySharedPref")
    fun write(record: WakeRecord) {
        prefs.edit()
            .putLong(KEY_AT, record.atMillis)
            .putInt(KEY_ASKED, record.askedMinutes)
            .putBoolean(KEY_COMPLETE, record.complete)
            .putLong(KEY_BOOT, record.bootInstantMillis)
            .commit()
    }

    private companion object {
        const val KEY_AT = "wake_armed_at"
        const val KEY_ASKED = "wake_asked_minutes"
        const val KEY_COMPLETE = "wake_complete"
        const val KEY_BOOT = "wake_boot_instant"
    }
}
