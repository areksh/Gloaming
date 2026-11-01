package sh.arek.gloaming.work

import android.content.Context
import sh.arek.gloaming.Graph
import sh.arek.gloaming.data.BootAware

/**
 * Puts the phone's own poll schedule back after a test has moved it.
 *
 * Instrumented tests run against the real AlarmManager, because there is no
 * seam between the app and the system and inventing one would mean testing a
 * fake instead of the thing that has to work. What they must not do is finish
 * with the alarm cancelled. A suite run in the evening would take the phone off
 * watch for the night without saying so: the app is still installed, still
 * exempt, still not stopped, and `./go status` is the only thing that would
 * ever mention it.
 *
 * That is the same rule the preference stores already follow here - save and
 * restore, never clear - applied to the one piece of device state that decides
 * whether the app works at all.
 *
 * Deliberately the same lines [BootReceiver] runs after an update, so a
 * phone that has just finished a suite is in the state a phone that has just
 * taken an update is in, rather than in a third state of the harness's own
 * invention.
 *
 * Not a test itself, and not in `sh.arek.gloaming.harness`: that package is
 * excluded from every run, and this has to be on the classpath of the tests
 * that call it.
 */
object RealSchedule {

    /**
     * Arms the alarm at the interval this phone is actually set to, or leaves
     * it unarmed if alerting is switched off here. The background location
     * refresh likewise, from the settings as they stand.
     */
    fun restore(context: Context) {
        val settings = BootAware.pollSettings(context).read()
        if (settings.alertsEnabled) {
            PollAlarm.schedule(context, settings.pollIntervalMinutes)
        } else {
            PollAlarm.cancel(context)
        }
        // The suite runs unlocked, which is when BootReceiver arms this too.
        LocationAlarm.arm(context, Graph.settings(context).read())
    }
}
