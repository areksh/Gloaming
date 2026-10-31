package sh.arek.gloaming.domain

import sh.arek.gloaming.data.LocationCadence
import sh.arek.gloaming.data.LocationMode
import sh.arek.gloaming.data.UserSettings
import sh.arek.gloaming.data.isDue

/** Why a background location wake did nothing. */
enum class RefreshSkip {
    /** The position is typed in, so there is nothing to detect. */
    NotAutomatic,

    /** The cadence is answered by opening the app or by the button. */
    NotBackground,

    /** Before first unlock the settings cannot be read. */
    Locked,

    /** The background grant has been taken away since the cadence was chosen. */
    NoPermission,

    /** The wake came early; the last refresh is still within its interval. */
    NotDue,
}

/**
 * Whether and when the phone's position is refreshed with no screen open.
 *
 * Pure and clock-free: the receiver supplies the instant, the lock state and
 * the permission, so every decision it can make is a unit test here rather
 * than a night spent waiting for an alarm. ADR-0016 records why there is a
 * background refresh at all.
 */
object LocationRefresh {

    /**
     * The soonest a refresh alarm may be asked for.
     *
     * A position more than an interval old asks for "now", and the receiver
     * re-arms after every wake. Without a floor a refresh that keeps finding no
     * fix would re-arm for the past and wake the phone back to back.
     */
    private const val MINIMUM_DELAY_MILLIS = 15 * 60_000L

    private const val MILLIS_PER_HOUR = 60L * 60 * 1000

    /**
     * Why this wake should not refresh, or null if it should.
     *
     * Checked in this order, cheapest and most permanent first, so the answer
     * names the reason the user would want to know about: a cadence that does
     * not run in the background says so even when the permission is gone too.
     */
    fun skip(
        settings: UserSettings,
        unlocked: Boolean,
        backgroundPermission: Boolean,
        now: Long,
    ): RefreshSkip? = when {
        settings.locationMode != LocationMode.Automatic -> RefreshSkip.NotAutomatic
        settings.locationCadence !is LocationCadence.Every -> RefreshSkip.NotBackground
        !unlocked -> RefreshSkip.Locked
        !backgroundPermission -> RefreshSkip.NoPermission
        !settings.locationCadence.isDue(settings.locationUpdatedAt, now) -> RefreshSkip.NotDue
        else -> null
    }

    /**
     * When the next refresh should be asked for, or null if none should be.
     *
     * One interval after the last refresh, and never sooner than a quarter of
     * an hour from [now]. A position never refreshed is overdue, so it gets the
     * quarter of an hour: counting an interval from [now] instead pushed the
     * first refresh a whole interval out on every wake that found no fix and
     * on every save that re-armed it.
     */
    fun nextAt(settings: UserSettings, now: Long): Long? {
        if (settings.locationMode != LocationMode.Automatic) return null
        val cadence = settings.locationCadence as? LocationCadence.Every ?: return null
        val soonest = now + MINIMUM_DELAY_MILLIS
        val updatedAt = settings.locationUpdatedAt ?: return soonest
        return maxOf(updatedAt + cadence.hours * MILLIS_PER_HOUR, soonest)
    }
}
