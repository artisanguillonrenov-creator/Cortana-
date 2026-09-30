package io.github.artisanguillonrenov.cortana.util

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

val PrettyJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    explicitNulls = false
}

object TimeFmt {
    private val frLocale = Locale.FRANCE
    private val dateTime = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH:mm", frLocale)
    private val short = DateTimeFormatter.ofPattern("dd/MM HH:mm", frLocale)
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", frLocale)

    fun zone(): ZoneId = ZoneId.systemDefault()
    fun full(epochMs: Long, zone: ZoneId = zone()): String = dateTime.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun short(epochMs: Long, zone: ZoneId = zone()): String = short.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun time(epochMs: Long, zone: ZoneId = zone()): String = timeOnly.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun iso(epochMs: Long, zone: ZoneId = zone()): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone).withNano(0))
}

object Hash {
    fun sha256(text: String): String = sha256Bytes(text.toByteArray(Charsets.UTF_8))

    fun sha256Bytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256File(f: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(64 * 1024); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return hex(md.digest())
    }

    fun hex(d: ByteArray): String {
        val sb = StringBuilder(d.size * 2)
        for (b in d) { val v = b.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f]) }
        return sb.toString()
    }
}

/** Logging that always goes through the redactor so secrets never reach logcat. */
object CLog {
    private const val TAG = "Cortana"
    fun d(msg: String) = Log.d(TAG, Redactor.redact(msg))
    fun i(msg: String) = Log.i(TAG, Redactor.redact(msg))
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, Redactor.redact(msg + (t?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: "")))
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, Redactor.redact(msg + (t?.let { ": ${it.javaClass.simpleName} ${it.message}" } ?: "")))
}

fun String.truncateBytes(maxBytes: Int, suffix: String = "\n…[tronqué]"): String {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    var cut = maxBytes
    // do not split a multi-byte UTF-8 sequence
    while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
    return String(bytes, 0, cut, Charsets.UTF_8) + suffix
}

// ---- JsonObject helpers ----
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }
fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.lowercase().let { c -> if (c == "true") true else if (c == "false") false else null } }
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

/** Canonical JSON string (sorted keys) used for idempotency keys and approval binding. */
fun canonicalJson(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonicalJson(it.value)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { canonicalJson(it) }
    else -> e.toString()
}
