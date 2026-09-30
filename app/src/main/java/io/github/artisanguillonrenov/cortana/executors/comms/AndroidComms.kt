package io.github.artisanguillonrenov.cortana.executors.comms

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import io.github.artisanguillonrenov.cortana.core.comms.CalendarInfo
import io.github.artisanguillonrenov.cortana.core.comms.CalendarStore
import io.github.artisanguillonrenov.cortana.core.comms.CommsException
import io.github.artisanguillonrenov.cortana.core.comms.Contact
import io.github.artisanguillonrenov.cortana.core.comms.ContactsStore
import io.github.artisanguillonrenov.cortana.core.comms.EventDraft
import io.github.artisanguillonrenov.cortana.core.comms.EventInstance
import io.github.artisanguillonrenov.cortana.core.comms.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** ContactsContract, read and create (no update/delete: the owner's address book stays theirs). */
class AndroidContactsStore(private val context: Context) : ContactsStore {
    private val cr get() = context.contentResolver
    override val available get() = context.granted(Manifest.permission.READ_CONTACTS)

    private fun need(p: String) { if (!context.granted(p)) throw CommsException("Accès aux contacts non autorisé : Santé → Communications → Autoriser.") }

    override suspend fun search(query: String, limit: Int): List<Contact> = withContext(Dispatchers.IO) {
        need(Manifest.permission.READ_CONTACTS)
        val like = "%${query.trim()}%"
        val digits = query.filter { it.isDigit() }
        val ids = LinkedHashSet<String>()
        cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.DISPLAY_NAME} LIKE ? OR (${ContactsContract.Data.MIMETYPE} IN (?, ?) AND ${ContactsContract.Data.DATA1} LIKE ?)" +
                (if (digits.length >= 4) " OR (${ContactsContract.Data.MIMETYPE} = ? AND REPLACE(REPLACE(${ContactsContract.Data.DATA1}, ' ', ''), '.', '') LIKE ?)" else ""),
            arrayOf(like, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, like) +
                (if (digits.length >= 4) arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "%${digits.takeLast(9)}%") else emptyArray()),
            "${ContactsContract.Data.DISPLAY_NAME} ASC")?.use { c -> while (c.moveToNext() && ids.size < limit) ids += c.getString(0) }
        ids.mapNotNull { load(it) }
    }

    override suspend fun get(id: String): Contact? = withContext(Dispatchers.IO) { need(Manifest.permission.READ_CONTACTS); load(id) }

    private fun load(id: String): Contact? {
        var name: String? = null; val phones = mutableListOf<String>(); val emails = mutableListOf<String>(); var org: String? = null
        cr.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id), null)?.use { c ->
            while (c.moveToNext()) {
                name = name ?: c.getString(0)
                val v = c.getString(2) ?: continue
                when (c.getString(1)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones += v
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += v
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> org = v
                }
            }
        }
        return name?.let { Contact(id, it, phones.distinct(), emails.distinct(), org) }
    }

    override suspend fun create(name: String, phone: String?, email: String?): String = withContext(Dispatchers.IO) {
        need(Manifest.permission.WRITE_CONTACTS)
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build(),
        )
        phone?.let { ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, it).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build() }
        email?.let { ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, it).build() }
        val res = cr.applyBatch(ContactsContract.AUTHORITY, ops)
        val raw = res.firstOrNull()?.uri?.let { ContentUris.parseId(it) } ?: throw CommsException("Contact non créé")
        cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.CONTACT_ID), "${ContactsContract.RawContacts._ID} = ?", arrayOf(raw.toString()), null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: raw.toString()
    }
}

/** CalendarContract: instances (recurrences expanded by the provider), events with reminders and attendees. */
class AndroidCalendarStore(private val context: Context) : CalendarStore {
    private val cr get() = context.contentResolver
    override val available get() = context.granted(Manifest.permission.READ_CALENDAR)
    private fun need(p: String) { if (!context.granted(p)) throw CommsException("Accès à l'agenda non autorisé : Santé → Communications → Autoriser.") }

    override suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        need(Manifest.permission.READ_CALENDAR)
        val out = mutableListOf<CalendarInfo>()
        cr.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_TIME_ZONE),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null)?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, c.getInt(4) == 1, c.getString(5))
        }
        out
    }

    override suspend fun instances(from: Long, to: Long, query: String?): List<EventInstance> = withContext(Dispatchers.IO) {
        need(Manifest.permission.READ_CALENDAR)
        val b = (if (query.isNullOrBlank()) CalendarContract.Instances.CONTENT_URI else CalendarContract.Instances.CONTENT_SEARCH_URI).buildUpon()
        ContentUris.appendId(b, from); ContentUris.appendId(b, to)
        if (!query.isNullOrBlank()) b.appendPath(query)
        val out = mutableListOf<EventInstance>()
        cr.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.RRULE, CalendarContract.Instances.EVENT_TIMEZONE, CalendarContract.Instances.AVAILABILITY), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += EventInstance(c.getString(0), c.getString(1), c.getString(2) ?: "(sans titre)", c.getLong(3), c.getLong(4), c.getInt(5) == 1,
                c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getInt(10) != CalendarContract.Events.AVAILABILITY_FREE)
        }
        out
    }

    override suspend fun event(eventId: String): EventDraft? = withContext(Dispatchers.IO) {
        need(Manifest.permission.READ_CALENDAR)
        val base = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId.toLong()), arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION, CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.RRULE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else {
                val start = c.getLong(2)
                val end = if (!c.isNull(3)) c.getLong(3) else start + durationMs(c.getString(4))
                EventDraft(c.getString(0), c.getString(1) ?: "", start, end, c.getString(5) ?: "UTC", c.getInt(6) == 1, c.getString(7), c.getString(8), c.getString(9))
            }
        } ?: return@withContext null
        val reminders = mutableListOf<Int>()
        cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId), null)
            ?.use { c -> while (c.moveToNext()) reminders += c.getInt(0) }
        val attendees = mutableListOf<String>()
        cr.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL), "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(eventId), null)
            ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { attendees += it } }
        base.copy(reminderMinutes = reminders, attendees = attendees)
    }

    private fun values(d: EventDraft) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, d.calendarId.toLong())
        put(CalendarContract.Events.TITLE, d.title)
        put(CalendarContract.Events.DTSTART, d.start)
        // Recurring events need DURATION instead of DTEND; all-day events are stored in UTC.
        if (d.rrule != null) { put(CalendarContract.Events.RRULE, d.rrule.removePrefix("RRULE:")); put(CalendarContract.Events.DURATION, if (d.allDay) "P${((d.end - d.start) / 86_400_000).coerceAtLeast(1)}D" else "PT${((d.end - d.start) / 60_000).coerceAtLeast(1)}M"); putNull(CalendarContract.Events.DTEND) }
        else { put(CalendarContract.Events.DTEND, d.end); putNull(CalendarContract.Events.RRULE); putNull(CalendarContract.Events.DURATION) }
        put(CalendarContract.Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else d.timeZone)
        put(CalendarContract.Events.ALL_DAY, if (d.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_LOCATION, d.location)
        put(CalendarContract.Events.DESCRIPTION, d.description)
        put(CalendarContract.Events.HAS_ALARM, if (d.reminderMinutes.isNotEmpty()) 1 else 0)
    }

    private fun children(eventId: Long, d: EventDraft) {
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        d.reminderMinutes.forEach { m -> cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply { put(CalendarContract.Reminders.EVENT_ID, eventId); put(CalendarContract.Reminders.MINUTES, m); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT) }) }
        d.attendees.forEach { e -> cr.insert(CalendarContract.Attendees.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Attendees.EVENT_ID, eventId); put(CalendarContract.Attendees.ATTENDEE_EMAIL, e)
            put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE); put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
        }) }
    }

    override suspend fun create(d: EventDraft): String = withContext(Dispatchers.IO) {
        need(Manifest.permission.WRITE_CALENDAR)
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values(d)) ?: throw CommsException("Événement non créé")
        val id = ContentUris.parseId(uri)
        children(id, d)
        id.toString()
    }

    override suspend fun update(eventId: String, d: EventDraft): Unit = withContext(Dispatchers.IO) {
        need(Manifest.permission.WRITE_CALENDAR)
        if (cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId.toLong()), values(d), null, null) == 0) throw CommsException("Événement introuvable")
        children(eventId.toLong(), d.copy(attendees = emptyList())) // attendees are not re-invited on update
    }

    override suspend fun delete(eventId: String): Unit = withContext(Dispatchers.IO) {
        need(Manifest.permission.WRITE_CALENDAR)
        if (cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId.toLong()), null, null) == 0) throw CommsException("Événement introuvable")
    }

    private fun durationMs(d: String?): Long {
        val m = Regex("""P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(d ?: return 3_600_000) ?: return 3_600_000
        val (w, dd, h, mi, s) = m.destructured
        return ((w.toLongOrNull() ?: 0) * 7 * 86_400 + (dd.toLongOrNull() ?: 0) * 86_400 + (h.toLongOrNull() ?: 0) * 3_600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
    }
}

/** Calls and SMS, only where the hardware has them (a Wi-Fi tablet has neither). */
class AndroidTelephony(private val context: Context) : Telephony {
    private val pm get() = context.packageManager
    override val canCall: Boolean get() = if (Build.VERSION.SDK_INT >= 33) pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING) else pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    override val canSms: Boolean get() = if (Build.VERSION.SDK_INT >= 33) pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) else pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun prepareCall(number: String) = context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun call(number: String) {
        if (!context.granted(Manifest.permission.CALL_PHONE)) throw CommsException("Autorisation d'appeler non accordée : Santé → Communications.")
        context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun composeSms(number: String, body: String) = context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override suspend fun sendSms(number: String, body: String): Int = withContext(Dispatchers.IO) {
        if (!context.granted(Manifest.permission.SEND_SMS)) throw CommsException("Autorisation d'envoyer des SMS non accordée : Santé → Communications.")
        val sms = context.getSystemService(SmsManager::class.java) ?: throw CommsException("Service SMS indisponible")
        val parts = sms.divideMessage(body)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
        parts.size
    }
}

/** ClipboardManager: sensitive copies are flagged (Android 13+) and cleared after a delay. */
class AndroidClipboard(private val context: android.content.Context, private val scope: kotlinx.coroutines.CoroutineScope) : io.github.artisanguillonrenov.cortana.executors.comms.ClipboardAccess {
    private val cm get() = context.getSystemService(android.content.ClipboardManager::class.java)

    override fun read(): io.github.artisanguillonrenov.cortana.executors.comms.ClipboardAccess.Clip? {
        val clip = cm?.primaryClip ?: return null
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        val sensitive = Build.VERSION.SDK_INT >= 33 && clip.description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        return io.github.artisanguillonrenov.cortana.executors.comms.ClipboardAccess.Clip(text, sensitive)
    }

    override fun write(text: String, sensitive: Boolean, clearAfterSec: Int?) {
        val clip = android.content.ClipData.newPlainText("Cortana", text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm?.setPrimaryClip(clip)
        if (clearAfterSec != null) scope.launch {
            kotlinx.coroutines.delay(clearAfterSec * 1000L)
            // Only our own copy is cleared, never something the owner copied since.
            if (runCatching { cm?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull() == text) runCatching { cm?.clearPrimaryClip() }
        }
    }
}
