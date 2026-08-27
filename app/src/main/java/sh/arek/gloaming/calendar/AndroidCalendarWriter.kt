package sh.arek.gloaming.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone
import sh.arek.gloaming.astronomy.SkyEvent
import sh.arek.gloaming.data.CalendarSync
import sh.arek.gloaming.domain.CalendarWriter

/**
 * Starred events, written into the phone's calendar provider.
 *
 * Two destinations, because "put it in my calendar" means two different things
 * to two reasonable people - see [CalendarSync]. The local one is the app's
 * own: registered with `ACCOUNT_TYPE_LOCAL` and no account, so no sync adapter
 * ever picks it up and nothing leaves the phone. The other is whatever the
 * user's primary calendar is, wherever that already syncs to.
 *
 * **Nothing here throws.** A revoked permission, a deleted calendar and a
 * provider that has gone away are all ordinary states, and this runs on a
 * background wake that also has an eclipse to announce. Every failure returns
 * null or does nothing, and the caller's record simply does not gain a row.
 */
class AndroidCalendarWriter(context: Context) : CalendarWriter {

    private val context = context.applicationContext

    override fun isPermitted(): Boolean =
        has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR)

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun write(event: SkyEvent, destination: CalendarSync, rowId: String?): String? {
        if (destination == CalendarSync.Off || !isPermitted()) return null

        return guarded("write") {
            val calendarId = calendarId(destination) ?: return@guarded null
            val values = values(event, calendarId)

            // Updated in place where the row is still there. A delete and a
            // re-insert would lose any reminder the user had set on it, and
            // this path runs every time a comet's dates move - which is every
            // recompute.
            if (rowId != null && update(rowId, values)) return@guarded rowId

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.let { ContentUris.parseId(it).toString() }
        }
    }

    private fun update(rowId: String, values: ContentValues): Boolean {
        val id = rowId.toLongOrNull() ?: return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        return context.contentResolver.update(uri, values, null, null) > 0
    }

    override fun remove(rowId: String) {
        if (!isPermitted()) return
        guarded("remove") {
            val id = rowId.toLongOrNull() ?: return@guarded null
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            context.contentResolver.delete(uri, null, null)
        }
    }

    override fun discardOwnCalendar() {
        if (!isPermitted()) return
        guarded("discard") {
            val id = ownCalendarId() ?: return@guarded null
            // As a sync adapter, with the account repeated in the URI. A
            // calendar deleted any other way is marked rather than removed, and
            // for a local calendar there is no sync adapter that would ever
            // come along to finish the job.
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)
                .buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
                .build()
            context.contentResolver.delete(uri, null, null)
        }
    }

    override fun destinationName(destination: CalendarSync): String? {
        if (destination == CalendarSync.Off || !isPermitted()) return null
        return guarded("name") {
            // Read-only, unlike [calendarId]. Asking what a calendar is called
            // must not create one - the settings screen asks on every open, and
            // a query with a side effect meant the app made a calendar for
            // somebody who had only glanced at the switch, and made it again
            // the instant after deleting it.
            val id = when (destination) {
                CalendarSync.Off -> null
                CalendarSync.OwnCalendar -> ownCalendarId()
                CalendarSync.DeviceCalendar -> primaryCalendarId()
            } ?: return@guarded null
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
    }

    private fun values(event: SkyEvent, calendarId: Long) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, event.title)
        put(CalendarContract.Events.DESCRIPTION, description(event))
        put(CalendarContract.Events.DTSTART, event.from)
        put(CalendarContract.Events.DTEND, event.until)
        // UTC rather than the device's zone. These are instants - an eclipse
        // happens when it happens - and a zone stored here would move the entry
        // if the phone crossed a border before the event.
        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        // Free rather than busy: an eclipse is not a meeting, and marking three
        // hours of somebody's evening as unavailable is not what they asked for.
        put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
    }

    /** The caveat travels with the entry, for the same reason it travels with a share. */
    private fun description(event: SkyEvent): String = listOfNotNull(
        event.detail,
        (event.visibility as? sh.arek.gloaming.astronomy.Visibility.Marginal)?.reason,
        (event.visibility as? sh.arek.gloaming.astronomy.Visibility.NotFromHere)?.reason,
    ).joinToString("\n")

    private fun calendarId(destination: CalendarSync): Long? = when (destination) {
        CalendarSync.Off -> null
        CalendarSync.OwnCalendar -> ownCalendarId() ?: createOwnCalendar()
        CalendarSync.DeviceCalendar -> primaryCalendarId()
    }

    /**
     * The app's own calendar, found by the account it registered under.
     *
     * `ACCOUNT_TYPE_LOCAL` with no real account is the platform's own way of
     * saying "this calendar belongs to this device and syncs nowhere", and it
     * is what a calendar app shows in its list alongside the others.
     */
    private fun ownCalendarId(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
        arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /**
     * Creating a calendar requires writing as a sync adapter.
     *
     * The three account parameters have to be repeated in the URI as well as in
     * the values; the provider rejects the insert otherwise, and it does so
     * with an exception rather than a null.
     */
    private fun createOwnCalendar(): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOUR)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }

        return context.contentResolver.insert(uri, values)?.let { ContentUris.parseId(it) }
    }

    /**
     * The user's primary calendar, or failing that the first writable one.
     *
     * `IS_PRIMARY` is not set by every provider, so the fallback is a real path
     * rather than a defensive one - and an access level of contributor or
     * better is the provider's own way of saying a calendar accepts writes.
     */
    private fun primaryCalendarId(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        ),
        "${CalendarContract.Calendars.VISIBLE} = 1",
        null,
        null,
    )?.use { cursor ->
        var fallback: Long? = null
        while (cursor.moveToNext()) {
            val writable = cursor.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            if (!writable) continue
            if (cursor.getInt(1) == 1) return@use cursor.getLong(0)
            if (fallback == null) fallback = cursor.getLong(0)
        }
        fallback
    }

    /**
     * Every provider call, with its failures turned into null.
     *
     * A revoked permission arrives as a SecurityException, a removed calendar
     * as an IllegalArgumentException, and a provider that has died as a
     * RuntimeException - all on a background wake with nobody to see a crash.
     */
    private fun <T> guarded(what: String, block: () -> T?): T? = runCatching(block)
        .onFailure { Log.w("Gloaming", "calendar $what failed", it) }
        .getOrNull()

    companion object {
        /**
         * The account the app's own calendar is registered under.
         *
         * Not an email address and not a real account: `ACCOUNT_TYPE_LOCAL`
         * means the provider will not offer it to any sync adapter, so this
         * string is only ever a label and a way to find the calendar again.
         */
        const val ACCOUNT_NAME = "Gloaming"

        /** What a calendar app shows in its list. */
        const val CALENDAR_NAME = "Gloaming sky events"

        /** A blue-grey, for a calendar full of things that happen after dark. */
        private const val CALENDAR_COLOUR = 0xFF3F5B7A.toInt()
    }
}
