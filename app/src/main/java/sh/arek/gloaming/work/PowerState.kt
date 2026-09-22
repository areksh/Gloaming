package sh.arek.gloaming.work

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * The two things that decide whether an alarm arrives, in one string.
 *
 * Written only when something has already gone wrong, because that is the
 * moment they cannot be recovered afterwards: both are current state with no
 * history anywhere, so a wake that arrived hours late is unexplainable by the
 * following morning. `tools/bfu-check.sh` reads the same two over adb, which
 * answers the question for a phone on the desk and not for the one that was
 * on a bedside table at three in the morning.
 *
 * The standby bucket is the likelier of the two. Coming off the power allowlist
 * drops an app to FREQUENT or RARE, where the system defers alarms by hours -
 * the app still looks healthy, the alarm still lists as pending, and nothing
 * anywhere says why the night went quiet.
 */
object PowerState {

    /** Never throws and never blocks: this runs on the way to explaining a fault. */
    fun describe(context: Context): String {
        val bucket = runCatching { bucket(context) }.getOrNull() ?: "bucket unknown"
        val exempt = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        val optimisation = when (exempt) {
            true -> "exempt from battery optimisation"
            false -> "NOT exempt from battery optimisation"
            null -> "exemption unknown"
        }
        return "$bucket, $optimisation"
    }

    /**
     * The app's own bucket, which needs no permission. The overload taking a
     * package name is the one that wants PACKAGE_USAGE_STATS.
     */
    private fun bucket(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "bucket unavailable"
        val value = context.getSystemService(UsageStatsManager::class.java)
            ?.appStandbyBucket
            ?: return null
        return "bucket $value (${name(value)})"
    }

    /**
     * Spelled out rather than left as a number, because the number alone says
     * nothing and the distinction that matters is not where a reader expects:
     * EXEMPTED and ACTIVE alarms arrive, everything below FREQUENT is deferred.
     *
     * Literals, not the `UsageStatsManager` constants. Two of the buckets the
     * app can actually be in - EXEMPTED at 5, which is where the battery
     * exemption puts it and therefore the normal state for this app, and NEVER
     * at 50 - are hidden from the public SDK and have no constant to name.
     * Half the table in symbols and half in numbers would read worse than all
     * of it in the numbers the platform prints.
     */
    fun name(bucket: Int): String = when (bucket) {
        5 -> "exempted"
        10 -> "active"
        20 -> "working set"
        30 -> "frequent - alarms are deferred"
        40 -> "rare - alarms are deferred hard"
        45 -> "restricted - alarms barely run"
        50 -> "never - the app has not been opened"
        else -> "unrecognised"
    }
}
