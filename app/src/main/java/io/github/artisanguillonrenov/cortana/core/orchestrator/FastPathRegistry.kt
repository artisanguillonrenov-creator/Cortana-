package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * Context a fast path can use to decide (never to bypass policy). [workspaces]: existing project names.
 * [ownerText]: the text is the owner's own words (chat or voice) in a task no external content influenced.
 */
data class FastPathContext(
    val nowMs: Long, val zone: ZoneId, val incognito: Boolean, val availableCapabilities: Set<String>,
    val workspaces: List<String> = emptyList(), val ownerText: Boolean = true,
)

/** One further dispatcher call of a fixed fast-path sequence. */
data class FastPathCall(val capability: String, val args: JsonObject)

/**
 * A deterministic, versioned shortcut (doc 04 §19): matcher + parameter extraction → one canonical
 * capability call that still goes through the dispatcher and the policy engine.
 */
data class FastPathMatch(
    val pathId: String,
    val capability: String,
    val args: JsonObject,
    val confidence: Double,
    /** Renders the owner-facing reply from the tool result; null = hand over to the model. */
    val render: (ToolResult) -> String?,
    /** When true the model still answers afterwards (e.g. "retiens que" gets a short confirmation). */
    val continueToModel: Boolean = false,
    val noteForModel: ((ToolResult) -> String)? = null,
    /** Passive owner-direct action allowed while STOP is active (reminder, explicit memory). */
    val passive: Boolean = false,
    /** Ignore the session toolset (reminders and explicit memory always worked in 1.2.0). */
    val ignoresToolset: Boolean = false,
    /** Fixed follow-up calls, each through the dispatcher, run only while every previous call succeeded. */
    val then: List<FastPathCall> = emptyList(),
    /** A failed or refused call ends the task with its reason instead of handing over to the model (no loop). */
    val stopOnFailure: Boolean = false,
    /** Only for the owner's own words in an untainted task (never from a notification, webhook, attachment…). */
    val ownerTextOnly: Boolean = false,
)

interface FastPath {
    val id: String
    val version: Int
    fun match(text: String, ctx: FastPathContext): FastPathMatch?
}

class FastPathRegistry(private val paths: List<FastPath> = defaults()) {
    val all: List<FastPath> get() = paths

    fun match(text: String, ctx: FastPathContext, threshold: Double = 0.8): FastPathMatch? =
        paths.mapNotNull { runCatching { it.match(text, ctx) }.getOrNull() }
            .filter { m ->
                m.confidence >= threshold && (!m.ownerTextOnly || ctx.ownerText) &&
                    (m.ignoresToolset || (m.capability in ctx.availableCapabilities && m.then.all { it.capability in ctx.availableCapabilities }))
            }
            .maxByOrNull { it.confidence }

    companion object {
        private val articles = Regex("(?i)^(l'|le |la |les |l’)")
        private val compound = Regex("(?i)\\b(et|puis|ensuite|avant|après|si)\\b")
        /** "lance les tests", "lance le build"… are development requests, never an app to open. */
        private val devTarget = Regex("(?i)^(tests?|build|compilation|projet\\b.*|dépôt\\b.*)$")

        private fun json(args: Map<String, String>): JsonObject = buildJsonObject { args.forEach { (k, v) -> put(k, v) } }

        fun defaults(): List<FastPath> = listOf(
            object : FastPath {
                override val id = "reminder"
                override val version = 2
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val r = FastPaths.parseReminder(text, ctx.nowMs, ctx.zone) ?: return null
                    val seconds = ((r.atMs - ctx.nowMs) / 1000).coerceAtLeast(1)
                    return FastPathMatch(
                        id, "schedule.create",
                        buildJsonObject { put("kind", "reminder"); put("message", r.message); put("name", r.message); put("in_seconds", seconds) },
                        confidence = 0.95, passive = true, ignoresToolset = true,
                        render = { res -> if (res.ok) "C'est noté ✓ Je vous rappellerai « ${r.message} » ${TimeFmt.full(r.atMs)}." else null },
                    )
                }
            },
            object : FastPath {
                override val id = "memory.explicit"
                override val version = 2
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val mi = FastPaths.parseExplicitMemory(text) ?: return null
                    return FastPathMatch(
                        id, "memory.save",
                        buildJsonObject { put("text", mi.text); put("type", mi.type); put("explicit", true); put("importance", if (mi.type == MemoryTypes.PREFERENCE) 4 else 3) },
                        confidence = 0.95, passive = true, ignoresToolset = true, continueToModel = true,
                        render = { null },
                        noteForModel = { res ->
                            if (res.ok) "Cortana a déjà enregistré ce souvenir (${mi.type}) : « ${mi.text} ». Confirme brièvement, n'appelle pas memory_save pour ce fait."
                            else "Le propriétaire a demandé de retenir « ${mi.text} » mais rien n'a été enregistré (${res.text}). Dis-le lui."
                        },
                    )
                }
            },
            object : FastPath {
                override val id = "app.open"
                override val version = 1
                private val re = Regex("(?i)^\\s*(?:cortana[ ,]+)?(?:ouvre|lance|démarre|open)\\s+(?:l'application\\s+|l'appli\\s+|l’application\\s+)?(.{2,40}?)\\s*[.!]?$")
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val m = re.find(text) ?: return null
                    val name = m.groupValues[1].replace(articles, "").trim()
                    if (compound.containsMatchIn(name) || name.split(Regex("\\s+")).size > 4 || devTarget.matches(name)) return null
                    return FastPathMatch(id, "android.app.open", buildJsonObject { put("name", name) }, 0.85,
                        render = { res -> if (res.ok) "✓ ${res.text.substringBefore(". Observe")}" else null })
                }
            },
            object : FastPath {
                // "Trouve-moi une photo de la tour Eiffel" shows real photos found on the web, inline,
                // whatever the model would have chosen (never a generated image, never a file to open).
                override val id = "web.images"
                override val version = 1
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val s = io.github.artisanguillonrenov.cortana.core.chat.ImageIntent.search(text) ?: return null
                    val what = if (s.mode == "videos") "vidéos" else "images"
                    return FastPathMatch(id, "web.search", buildJsonObject { put("query", s.query); put("mode", s.mode); put("count", 8) }, 0.9,
                        render = { res ->
                            val engine = res.untrustedSource?.substringAfter("web.search:", "")?.takeIf { it.isNotBlank() && it != "web.search" }
                            when {
                                !res.ok -> null
                                res.text.startsWith("Aucun résultat") -> "Je n'ai trouvé aucune de ces $what pour « ${s.query} »${engine?.let { " ($it)" } ?: ""}. " +
                                    "Essayez d'autres mots, ou un autre moteur dans Réglages → Recherche web."
                                else -> "Voici des $what de « ${s.query} » trouvées sur le web${engine?.let { " ($it)" } ?: ""}. Touchez-en une pour l'agrandir ou ouvrir sa source."
                            }
                        },
                        stopOnFailure = true, ownerTextOnly = true)
                }
            },
            object : FastPath {
                override val id = "volume.set"
                override val version = 1
                private val re = Regex("(?i)^\\s*(?:mets|règle|regle|passe)\\s+le\\s+volume\\s*(?:de\\s+la\\s+|du\\s+|des\\s+)?(média|musique|sonnerie|alarme|notifications?)?\\s*(?:à|a)\\s*(\\d{1,3})\\s*(?:%|pour ?cent)?\\s*[.!]?$")
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val m = re.find(text) ?: return null
                    val pct = m.groupValues[2].toInt().coerceIn(0, 100)
                    val stream = when (m.groupValues[1].lowercase()) { "sonnerie" -> "ring"; "alarme" -> "alarm"; "notification", "notifications" -> "notification"; else -> "media" }
                    return FastPathMatch(id, "android.system.volume.set", buildJsonObject { put("stream", stream); put("percent", pct) }, 0.9,
                        render = { res -> if (res.ok) "✓ ${res.text}" else null })
                }
            },
            object : FastPath {
                override val id = "brightness.set"
                override val version = 1
                private val re = Regex("(?i)^\\s*(?:mets|règle|regle|passe)\\s+la\\s+luminosité\\s+(?:au\\s+(maximum|max|minimum|min)|(?:à|a)\\s*(\\d{1,3})\\s*%?)\\s*[.!]?$")
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val m = re.find(text) ?: return null
                    val pct = when (m.groupValues[1].lowercase()) { "maximum", "max" -> 100; "minimum", "min" -> 1; else -> m.groupValues[2].toIntOrNull() ?: return null }
                    return FastPathMatch(id, "android.system.brightness.set", buildJsonObject { put("percent", pct.coerceIn(1, 100)) }, 0.9,
                        render = { res -> if (res.ok) "✓ ${res.text}" else null })
                }
            },
            object : FastPath {
                override val id = "timer.create"
                override val version = 1
                private val re = Regex("(?i)^\\s*(?:lance\\s+|mets\\s+|démarre\\s+)?(?:un\\s+)?minuteur\\s+(?:de\\s+)?(\\d{1,4})\\s*(secondes?|s|minutes?|min|mn|heures?|h)\\s*[.!]?$")
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val m = re.find(text) ?: return null
                    val n = m.groupValues[1].toInt()
                    val unit = m.groupValues[2].lowercase()
                    val seconds = when { unit.startsWith("h") -> n * 3600; unit.startsWith("s") -> n; else -> n * 60 }
                    if (seconds !in 1..86400) return null
                    return FastPathMatch(id, "android.timer.create", buildJsonObject { put("seconds", seconds) }, 0.9,
                        render = { res -> if (res.ok) "✓ ${res.text}" else null })
                }
            },
            object : FastPath {
                // Explicit development commands (DevCommands): same dispatcher, policy, approvals, ledger and audit.
                override val id = "dev.command"
                override val version = 1
                override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
                    val cmd = DevCommands.parse(text, ctx.workspaces) ?: return null
                    val first = cmd.calls.first()
                    return FastPathMatch(
                        cmd.id, first.capability, json(first.args), confidence = 0.92,
                        then = cmd.calls.drop(1).map { FastPathCall(it.capability, json(it.args)) },
                        stopOnFailure = true, ownerTextOnly = true,
                        render = { res -> if (res.ok) "✓ ${res.text}" else null },
                    )
                }
            },
        )
    }
}
