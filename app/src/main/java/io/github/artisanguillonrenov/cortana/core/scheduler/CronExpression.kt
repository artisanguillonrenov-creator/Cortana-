package io.github.artisanguillonrenov.cortana.core.scheduler

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.BitSet

/** Standard 5-field cron (minute hour day-of-month month day-of-week), DST-correct via java.time. */
class CronExpression private constructor(
    private val minutes: BitSet,
    private val hours: BitSet,
    private val doms: BitSet,
    private val months: BitSet,
    private val dows: BitSet,
    private val domStar: Boolean,
    private val dowStar: Boolean,
    val source: String,
) {
    fun next(after: ZonedDateTime): ZonedDateTime? {
        val zone = after.zone
        var t = after.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = after.plusYears(5)
        var guard = 0
        while (t.isBefore(limit) && guard++ < 200_000) {
            if (!months[t.monthValue]) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone); continue
            }
            if (!dayMatches(t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay(zone); continue
            }
            if (!hours[t.hour]) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1); continue
            }
            if (!minutes[t.minute]) {
                t = t.plusMinutes(1); continue
            }
            return t
        }
        return null
    }

    private fun dayMatches(t: ZonedDateTime): Boolean {
        val dom = doms[t.dayOfMonth]
        val dow = dows[t.dayOfWeek.value % 7]
        return when {
            domStar && dowStar -> true
            domStar -> dow
            dowStar -> dom
            else -> dom || dow
        }
    }

    companion object {
        private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val dayNames = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

        fun parse(expr: String): CronExpression {
            val f = expr.trim().split(Regex("\\s+"))
            require(f.size == 5) { "Une expression cron doit avoir 5 champs (minute heure jour mois jour-semaine)" }
            val dow = field(f[4], 0, 7, dayNames, 0)
            if (dow[7]) { dow.set(0); dow.clear(7) }
            return CronExpression(
                field(f[0], 0, 59), field(f[1], 0, 23), field(f[2], 1, 31), field(f[3], 1, 12, monthNames, 1), dow,
                f[2] == "*" || f[2] == "?", f[4] == "*" || f[4] == "?", expr.trim(),
            )
        }

        private fun value(s: String, names: List<String>?, offset: Int): Int {
            s.toIntOrNull()?.let { return it }
            val i = names?.indexOf(s.lowercase().take(3)) ?: -1
            require(i >= 0) { "Valeur cron invalide : $s" }
            return i + offset
        }

        private fun field(spec: String, min: Int, max: Int, names: List<String>? = null, offset: Int = 0): BitSet {
            val bits = BitSet(max + 1)
            for (part in spec.split(',')) {
                val (range, stepStr) = part.split('/').let { it[0] to it.getOrNull(1) }
                val step = stepStr?.toIntOrNull()?.also { require(it > 0) { "Pas cron invalide" } } ?: 1
                val (lo, hi) = when {
                    range == "*" || range == "?" -> min to max
                    range.contains('-') -> range.split('-').let { value(it[0], names, offset) to value(it[1], names, offset) }
                    else -> value(range, names, offset).let { v -> v to (if (stepStr != null) max else v) }
                }
                require(lo in min..max && hi in min..max && lo <= hi) { "Plage cron hors limites : $part" }
                var v = lo
                while (v <= hi) { bits.set(v); v += step }
            }
            return bits
        }
    }
}

/** Timing part of a ScheduleRow (§4 specJson). */
@Serializable
data class ScheduleSpec(
    val at: Long? = null,
    val everyMinutes: Int? = null,
    val startAt: Long? = null,
    val cron: String? = null,
    /** Condition watch (blueprint §39.4): checked every [everyMinutes]; the task runs only when it is met. */
    val condition: ConditionSpec? = null,
) {
    fun describe(zone: ZoneId): String = when {
        cron != null -> "cron « $cron »"
        everyMinutes != null -> "toutes les $everyMinutes min"
        at != null -> "une fois"
        else -> "?"
    }

    fun isRecurring() = cron != null || everyMinutes != null

    /** Next fire strictly after [afterMs], or null when there is none (one-shot already due). */
    fun nextAfter(afterMs: Long, zone: ZoneId): Long? = when {
        cron != null -> CronExpression.parse(cron).next(Instant.ofEpochMilli(afterMs).atZone(zone))?.toInstant()?.toEpochMilli()
        everyMinutes != null -> {
            val period = everyMinutes.coerceAtLeast(1) * 60_000L
            val start = startAt ?: afterMs
            if (start > afterMs) start else start + ((afterMs - start) / period + 1) * period
        }
        at != null -> if (at > afterMs) at else null
        else -> null
    }

    fun toJson(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        fun parse(json: String): ScheduleSpec = AppJson.decodeFromString(serializer(), json)
    }
}

@Serializable
data class ScheduleAction(
    /** notify | task */
    val type: String,
    val message: String? = null,
    val objective: String? = null,
) {
    fun toJson(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        fun parse(json: String): ScheduleAction = AppJson.decodeFromString(serializer(), json)
    }
}

/**
 * What a condition watch checks: one read-only capability call (run by the orchestrator through the
 * dispatcher, never by the scheduler) and a deterministic test on its text result.
 */
@Serializable
data class ConditionSpec(
    val capability: String,
    /** JSON object of arguments. */
    val args: String = "{}",
    /** contains | not_contains | regex | above | below | changed */
    val test: String,
    val value: String? = null,
) {
    init {
        require(test in TESTS) { "test inconnu : $test (${TESTS.joinToString()})" }
        require(test == "changed" || !value.isNullOrBlank()) { "valeur requise pour le test $test" }
        if (test == "regex") runCatching { Regex(value!!) }.getOrElse { throw IllegalArgumentException("expression régulière invalide") }
        if (test == "above" || test == "below") require(value!!.replace(',', '.').toDoubleOrNull() != null) { "nombre attendu pour $test" }
    }

    fun describe() = when (test) {
        "contains" -> "« $value » apparaît"; "not_contains" -> "« $value » n'apparaît plus"; "regex" -> "le motif $value correspond"
        "above" -> "la valeur dépasse $value"; "below" -> "la valeur descend sous $value"; else -> "le résultat change"
    } + " ($capability)"

    /** Met or not, and the fingerprint of [text] (kept to detect a change next time). */
    fun evaluate(text: String, previousFingerprint: String?): Pair<Boolean, String> {
        val fp = java.security.MessageDigest.getInstance("SHA-256").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val number = Regex("-?\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull()
        val v = value?.replace(',', '.')
        val met = when (test) {
            "contains" -> text.contains(value!!, ignoreCase = true)
            "not_contains" -> !text.contains(value!!, ignoreCase = true)
            "regex" -> Regex(value!!).containsMatchIn(text)
            "above" -> number != null && number > v!!.toDouble()
            "below" -> number != null && number < v!!.toDouble()
            else -> previousFingerprint != null && previousFingerprint != fp
        }
        return met to fp
    }

    companion object { val TESTS = listOf("contains", "not_contains", "regex", "above", "below", "changed") }
}

