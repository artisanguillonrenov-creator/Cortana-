package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** Context a fast path can use to decide (never to bypass policy). */
data class FastPathContext(val nowMs: Long, val zone: ZoneId, val incognito: Boolean, val availableCapabilities: Set<String>)

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
            .filter { it.confidence >= threshold && (it.ignoresToolset || it.capability in ctx.availableCapabilities) }
            .maxByOrNull { it.confidence }

    companion object {
        private val articles = Regex("(?i)^(l'|le |la |les |l’)")
        private val compound = Regex("(?i)\\b(et|puis|ensuite|avant|après|si)\\b")

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
                    if (compound.containsMatchIn(name) || name.split(Regex("\\s+")).size > 4) return null
                    return FastPathMatch(id, "android.app.open", buildJsonObject { put("name", name) }, 0.85,
                        render = { res -> if (res.ok) "✓ ${res.text.substringBefore(". Observe")}" else null })
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
        )
    }
}
