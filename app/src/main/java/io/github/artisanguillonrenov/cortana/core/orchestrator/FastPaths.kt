package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Deterministic handling of the two most common owner requests, so they work even with a weak
 * model or no network: "Rappelle-moi dans 5 minutes de boire de l'eau" (no model call at all) and
 * "Retiens que je préfère des réponses courtes" (saved immediately, confirmed to the model).
 * Pure functions — unit-tested.
 */
object FastPaths {
    private val explicitMemory = Regex(
        "(?i)^\\s*(?:cortana[ ,]+)?(?:retiens|souviens[- ]toi|m[ée]morise|n'oublie pas|note|garde en m[ée]moire|remember)\\b\\s*(?:bien\\s+)?(?:que|qu'|de|d'|ceci\\s*:|:)?\\s*(.+)$"
    )
    private val explicitAnywhere = Regex("(?i)\\b(retiens|souviens[- ]toi|m[ée]morise|n'oublie pas|garde en m[ée]moire|note que|remember)\\b")

    fun isExplicitMemoryRequest(text: String): Boolean = explicitAnywhere.containsMatchIn(text)

    data class MemoryIntent(val text: String, val type: String)

    fun parseExplicitMemory(text: String): MemoryIntent? {
        val m = explicitMemory.find(text.trim()) ?: return null
        var fact = m.groupValues[1].trim().trimEnd('.', '!', ' ')
        if (fact.length < 3 || fact.endsWith("?")) return null
        fact = fact.replaceFirstChar { it.uppercase() }
        val lower = fact.lowercase()
        val type = when {
            Regex("pr[ée]f[èe]r|pr[ée]f[ée]rence|j'aime|je n'aime|je d[ée]teste|r[ée]ponds|r[ée]ponses|parle[- ]moi|tutoie|vouvoie|appelle[- ]moi|ton|style").containsMatchIn(lower) -> MemoryTypes.PREFERENCE
            Regex("je m'appelle|mon nom|mon pr[ée]nom|j'habite|je vis|mon anniversaire|je suis n[ée]|mon m[ée]tier|je travaille|ma femme|mon mari|mes enfants|mon fils|ma fille|mon adresse").containsMatchIn(lower) -> MemoryTypes.PROFILE
            else -> MemoryTypes.SEMANTIC
        }
        return MemoryIntent(fact, type)
    }

    data class ReminderIntent(val atMs: Long, val message: String)

    private val numberWords = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "quinze" to 15, "vingt" to 20, "trente" to 30, "quarante" to 40,
        "quarante-cinq" to 45, "cinquante" to 50, "soixante" to 60,
    )
    private const val NUM = "(\\d+|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|quinze|vingt|trente|quarante(?:-cinq)?|cinquante|soixante)"
    private const val UNIT = "(secondes?|sec|minutes?|mins?|mn|heures?|h|jours?)"
    private const val LEAD = "(?i)^\\s*(?:cortana[ ,]+)?(?:peux-tu\\s+|pourrais-tu\\s+)?(?:me\\s+rappeler|rappelle[- ]moi|rappelle-moi|fais[- ]moi penser)"

    private val inPattern = Regex("$LEAD\\s+(?:dans|d'ici)\\s+$NUM\\s*$UNIT(?:\\s+et\\s+(demie|quart))?\\s*(?:,\\s*)?(?:de\\s+|d'|que\\s+|qu'|pour\\s+)?(.+?)\\s*[.!]?$")
    private val inPatternTail = Regex("$LEAD\\s+(?:de\\s+|d'|que\\s+|qu')?(.+?)\\s+(?:dans|d'ici)\\s+$NUM\\s*$UNIT\\s*[.!]?$")
    private val halfHour = Regex("$LEAD\\s+dans\\s+(une\\s+demi[- ]heure|un\\s+quart\\s+d'heure|trois\\s+quarts\\s+d'heure)\\s*(?:,\\s*)?(?:de\\s+|d'|que\\s+|qu')?(.+?)\\s*[.!]?$")
    private val atPattern = Regex("$LEAD\\s+(demain\\s+)?(?:à|a|vers)\\s+(\\d{1,2})\\s*(?:h|:|heures?)\\s*(\\d{2})?\\s*(demain)?\\s*(?:,\\s*)?(?:de\\s+|d'|que\\s+|qu')?(.+?)\\s*[.!]?$")

    private fun num(s: String): Int? = s.toIntOrNull() ?: numberWords[s.lowercase()]

    private fun unitMs(u: String): Long = when {
        u.startsWith("s") -> 1_000L
        u.startsWith("h") -> 3_600_000L
        u.startsWith("j") -> 86_400_000L
        else -> 60_000L
    }

    private fun cleanMessage(raw: String): String? {
        val m = raw.trim().trim(',', '.', '!', ' ')
        if (m.length < 2) return null
        return m.replaceFirstChar { it.uppercase() }
    }

    fun parseReminder(text: String, nowMs: Long, zone: ZoneId): ReminderIntent? {
        val t = text.trim()
        inPattern.find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return null
            var ms = n * unitMs(m.groupValues[2].lowercase())
            when (m.groupValues[3].lowercase()) {
                "demie" -> ms += unitMs(m.groupValues[2].lowercase()) / 2
                "quart" -> ms += unitMs(m.groupValues[2].lowercase()) / 4
            }
            val msg = cleanMessage(m.groupValues[4]) ?: return null
            if (ms < 5_000) return null
            return ReminderIntent(nowMs + ms, msg)
        }
        halfHour.find(t)?.let { m ->
            val w = m.groupValues[1].lowercase()
            val min = when {
                w.contains("demi") -> 30
                w.startsWith("trois") -> 45
                else -> 15
            }
            val msg = cleanMessage(m.groupValues[2]) ?: return null
            return ReminderIntent(nowMs + min * 60_000L, msg)
        }
        inPatternTail.find(t)?.let { m ->
            val n = num(m.groupValues[2]) ?: return null
            val msg = cleanMessage(m.groupValues[1]) ?: return null
            return ReminderIntent(nowMs + n * unitMs(m.groupValues[3].lowercase()), msg)
        }
        atPattern.find(t)?.let { m ->
            val h = m.groupValues[2].toIntOrNull() ?: return null
            val min = m.groupValues[3].toIntOrNull() ?: 0
            if (h !in 0..23 || min !in 0..59) return null
            val tomorrow = m.groupValues[1].isNotBlank() || m.groupValues[4].isNotBlank()
            val msg = cleanMessage(m.groupValues[5]) ?: return null
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            var target = now.toLocalDate().atTime(h, min).atZone(zone)
            if (tomorrow) target = target.plusDays(1) else if (!target.isAfter(now)) target = target.plusDays(1)
            return ReminderIntent(target.toInstant().toEpochMilli(), msg)
        }
        return null
    }

    /** "HH:mm", "HHhmm", "HHh", "AAAA-MM-JJTHH:mm[:ss]", "AAAA-MM-JJ HH:mm", or ISO with offset. */
    fun parseAt(s: String, zone: ZoneId, nowMs: Long): Long? {
        val v = s.trim()
        runCatching { return OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(v.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(v.replace(' ', 'T'), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")).atZone(zone).toInstant().toEpochMilli() }
        Regex("^(\\d{1,2})\\s*[h:]\\s*(\\d{2})?$", RegexOption.IGNORE_CASE).find(v)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 0..23 || min !in 0..59) return null
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            var target = LocalDate.from(now).atTime(LocalTime.of(h, min)).atZone(zone)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return target.toInstant().toEpochMilli()
        }
        return null
    }

    private val urlRe = Regex("https?://[^\\s)>\"']+")

    fun hostsIn(text: String): List<String> = urlRe.findAll(text).mapNotNull { m ->
        runCatching { java.net.URI(m.value).host }.getOrNull()?.lowercase()
    }.distinct().toList()
}
