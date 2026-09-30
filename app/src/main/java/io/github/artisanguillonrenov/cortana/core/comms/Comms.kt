package io.github.artisanguillonrenov.cortana.core.comms

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CommsException(message: String) : Exception(message)

// ---------------------------------------------------------------- contacts

data class Contact(val id: String, val name: String, val phones: List<String>, val emails: List<String>, val organization: String? = null, val note: String? = null)

/** Owner's address book (Android: ContactsContract). */
interface ContactsStore {
    val available: Boolean
    suspend fun search(query: String, limit: Int = 20): List<Contact>
    suspend fun get(id: String): Contact?
    suspend fun create(name: String, phone: String?, email: String?): String
    /** Contact owning [number], if any (used to tell known recipients from unknown ones). */
    suspend fun byPhone(number: String): Contact? = search(number, 50).firstOrNull { c -> c.phones.any { PhoneNumbers.same(it, number) } }
}

object PhoneNumbers {
    /** Digits with a leading + kept; French national numbers normalised to +33. */
    fun normalize(n: String): String {
        val t = n.trim()
        val digits = t.filter { it.isDigit() }
        return when {
            t.startsWith("+") -> "+$digits"
            t.startsWith("00") -> "+" + digits.drop(2)
            digits.length == 10 && digits.startsWith("0") -> "+33" + digits.drop(1)
            else -> digits
        }
    }

    fun same(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        return x == y || (x.length >= 9 && y.length >= 9 && x.takeLast(9) == y.takeLast(9))
    }

    fun valid(n: String) = normalize(n).filter { it.isDigit() }.length in 3..15 && Regex("""^[+0-9 ().-]+$""").matches(n.trim())

    /** Short codes (premium SMS/services) and emergency numbers are never reached automatically. */
    fun isShortCode(n: String) = normalize(n).filter { it.isDigit() }.length <= 6
    val EMERGENCY = setOf("112", "15", "17", "18", "114", "115", "119", "196", "191", "911", "999")
    fun isEmergency(n: String) = normalize(n).removePrefix("+") in EMERGENCY

    fun masked(n: String): String { val d = normalize(n); return if (d.length <= 4) d else d.dropLast(4).replace(Regex("\\d"), "•") + d.takeLast(4) }
}

// ---------------------------------------------------------------- phone & SMS

/** What the hardware allows (a Wi-Fi tablet has neither calls nor SMS). */
interface Telephony {
    val canCall: Boolean
    val canSms: Boolean
    /** Opens the dialer pre-filled; the owner presses call. */
    fun prepareCall(number: String)
    /** Places the call directly (CALL_PHONE). */
    fun call(number: String)
    /** Opens the messaging app pre-filled; the owner presses send. */
    fun composeSms(number: String, body: String)
    /** Sends through the system SMS service (SEND_SMS); returns the number of parts. */
    suspend fun sendSms(number: String, body: String): Int
}

// ---------------------------------------------------------------- calendar

data class CalendarInfo(val id: String, val name: String, val account: String, val writable: Boolean, val primary: Boolean, val timeZone: String?)

/** One occurrence (recurring events are expanded by the platform). */
data class EventInstance(
    val eventId: String, val calendarId: String, val title: String, val start: Long, val end: Long, val allDay: Boolean,
    val location: String? = null, val description: String? = null, val rrule: String? = null, val timeZone: String? = null, val busy: Boolean = true,
)

data class EventDraft(
    val calendarId: String, val title: String, val start: Long, val end: Long, val timeZone: String, val allDay: Boolean = false,
    val location: String? = null, val description: String? = null, val rrule: String? = null,
    val reminderMinutes: List<Int> = emptyList(), val attendees: List<String> = emptyList(),
)

/** Owner's calendars (Android: CalendarContract). */
interface CalendarStore {
    val available: Boolean
    suspend fun calendars(): List<CalendarInfo>
    suspend fun instances(from: Long, to: Long, query: String? = null): List<EventInstance>
    suspend fun event(eventId: String): EventDraft?
    suspend fun create(d: EventDraft): String
    suspend fun update(eventId: String, d: EventDraft)
    suspend fun delete(eventId: String)
}

object CalendarRules {
    private val parts = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST")

    /** Validates an RFC 5545 RRULE the way Android's provider expects it; returns problems. */
    fun validateRrule(rule: String): List<String> {
        val p = rule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate { kv -> kv.substringBefore('=').uppercase() to kv.substringAfter('=', "") }
        val out = mutableListOf<String>()
        if (p["FREQ"] !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) out += "FREQ doit être DAILY, WEEKLY, MONTHLY ou YEARLY"
        (p.keys - parts).forEach { out += "élément inconnu : $it" }
        if (p.containsKey("COUNT") && p.containsKey("UNTIL")) out += "COUNT et UNTIL sont exclusifs"
        p["COUNT"]?.let { if (it.toIntOrNull()?.let { n -> n in 1..1000 } != true) out += "COUNT invalide" }
        p["INTERVAL"]?.let { if (it.toIntOrNull()?.let { n -> n in 1..366 } != true) out += "INTERVAL invalide" }
        p["BYDAY"]?.let { if (!Regex("""^([+-]?\d{0,2}(MO|TU|WE|TH|FR|SA|SU))(,[+-]?\d{0,2}(MO|TU|WE|TH|FR|SA|SU))*$""").matches(it)) out += "BYDAY invalide" }
        p["UNTIL"]?.let { if (!Regex("""^\d{8}(T\d{6}Z?)?$""").matches(it)) out += "UNTIL invalide (AAAAMMJJ ou AAAAMMJJTHHMMSSZ)" }
        return out
    }

    /**
     * Parses "2026-10-02T14:30" (local time in [zone]), "2026-10-02T14:30:00+02:00" or a date for all-day
     * events — DST-correct through java.time.
     */
    fun parse(text: String, zone: ZoneId): Long {
        val t = text.trim()
        runCatching { return ZonedDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }
        throw CommsException("Date invalide : « $text » (attendu AAAA-MM-JJTHH:MM)")
    }

    fun format(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", java.util.Locale.FRANCE).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Busy, timed occurrences overlapping [start, end) — the event itself excluded. */
    fun conflicts(start: Long, end: Long, existing: List<EventInstance>, except: String? = null) =
        existing.filter { it.busy && !it.allDay && it.eventId != except && it.start < end && start < it.end }
}

// ---------------------------------------------------------------- notifications

/** One notification the owner allowed Cortana to see. */
data class NotificationItem(
    val key: String, val packageName: String, val appLabel: String, val title: String?, val text: String?,
    val postedAt: Long, val canReply: Boolean, val conversation: String? = null,
)
