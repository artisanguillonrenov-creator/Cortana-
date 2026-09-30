package io.github.artisanguillonrenov.cortana.core.tools

import io.github.artisanguillonrenov.cortana.core.model.ToolSpec
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolCategory { SERVICE, WEB, FILES, SYSTEM, UI, DEV, DOCUMENTS, COMMS, MEDIA, INTEGRATIONS }

/**
 * Families of the tool chips of the Cortana Workspace design (Outils, Système, Fichiers, Terminal,
 * Git, Navigateur). A family switched off for a conversation is simply not offered to the model;
 * policy, approvals and budgets are unchanged.
 */
object ToolFamilies {
    const val TOOLS = "tools"
    const val SYSTEM = "system"
    const val FILES = "files"
    const val TERMINAL = "terminal"
    const val GIT = "git"
    const val WEB = "web"
    val all = listOf(TOOLS, SYSTEM, FILES, TERMINAL, GIT, WEB)

    fun of(capability: String, category: ToolCategory): String = when {
        capability.startsWith("repo.") -> GIT
        category == ToolCategory.DEV -> TERMINAL
        category == ToolCategory.WEB -> WEB
        category == ToolCategory.FILES -> FILES
        category == ToolCategory.SYSTEM || category == ToolCategory.UI -> SYSTEM
        else -> TOOLS
    }

    /** Families a toolset can offer at all ("conversation" offers none; "assistant" has no developer tools). */
    fun offered(toolset: String): Set<String> = when (toolset) {
        Toolsets.CONVERSATION -> emptySet()
        Toolsets.ASSISTANT -> setOf(TOOLS, SYSTEM, FILES, WEB)
        else -> all.toSet()
    }

    /** The chips shown as active: offered by the conversation's toolset and not switched off. */
    fun active(toolset: String, off: Collection<String>): Set<String> = offered(toolset) - off.toSet()

    /**
     * One chip switched on or off: the conversation keeps its toolset when it offers what is on (else the
     * smallest one that does), and every other offered family is switched off. Nothing on = direct mode.
     * Returns the new toolset and the families switched off.
     */
    fun toggle(toolset: String, off: Collection<String>, family: String, on: Boolean): Pair<String, List<String>> {
        val want = if (on) active(toolset, off) + family else active(toolset, off) - family
        val next = when {
            want.isEmpty() -> Toolsets.CONVERSATION
            toolset != Toolsets.CONVERSATION && want.all { it in offered(toolset) } -> toolset
            want.all { it in offered(Toolsets.ASSISTANT) } -> Toolsets.ASSISTANT
            else -> Toolsets.FULL
        }
        return next to all.filter { it in offered(next) && it !in want }
    }
}

/** Toolsets selectable per session. "conversation" = direct mode, no tools. */
object Toolsets {
    const val CONVERSATION = "conversation"
    const val ASSISTANT = "assistant"
    const val FULL = "full"
    val labels = linkedMapOf(CONVERSATION to "Discussion", ASSISTANT to "Assistant", FULL to "Complet")

    fun categories(toolset: String): Set<ToolCategory> = when (toolset) {
        CONVERSATION -> emptySet()
        ASSISTANT -> setOf(ToolCategory.SERVICE, ToolCategory.WEB, ToolCategory.FILES, ToolCategory.SYSTEM, ToolCategory.DOCUMENTS, ToolCategory.MEDIA)
        else -> ToolCategory.entries.toSet()
    }
}

data class ToolResult(
    val ok: Boolean,
    val text: String,
    /** Non-null when the output is untrusted observed content (web, screen, file…) → taints the task. */
    val untrustedSource: String? = null,
    /** Special control signal for the orchestrator (e.g. ask_user, discover). */
    val control: String? = null,
    /** Structured payload for the runtime (never shown to the model as-is). */
    val data: JsonElement? = null,
) {
    companion object {
        fun ok(text: String, untrustedSource: String? = null) = ToolResult(true, text, untrustedSource)
        fun error(text: String) = ToolResult(false, text)
    }
}

/** What an executor gets at dispatch time. Secrets are resolved from handles here, never earlier. */
interface ToolContext {
    val taskId: String
    val sessionId: String
    val tainted: Boolean
    val lastUserText: String
    val incognito: Boolean
    /** Effective risk the policy authorized; executors refuse if the live target became riskier. */
    val approvedRisk: Risk
    fun resolveSecret(handle: String?): String?
    /** Called by UI executors so the orchestrator shows the automation indicator. */
    fun markUiAutomation()
    /** Ledger key of this call for KEYED tools (dedupe key for durable effects), null otherwise. */
    val idempotencyKey: String? get() = null
    /** Session toolset: the owner's boundary for what this task may ever use. */
    val toolset: String
    /** The model reading tool results runs locally (tablet or LAN): sensitive content may be shown to it. */
    val modelLocal: Boolean get() = false
}

/** Context available to risk classifiers during policy evaluation. */
data class PolicyContext(
    val taskId: String,
    val tainted: Boolean,
    val taintSources: List<String>,
    val toolCallsSoFar: Int,
    /** The owner asked for this exact action directly (fast path), not the model. */
    val ownerDirect: Boolean = false,
    val sessionId: String? = null,
    val scheduleId: String? = null,
    val maxToolCalls: Int? = null,
)

/**
 * §8 — one capability = one meaning. The model sees [functionName] (dots are not allowed in
 * OpenAI function names), the registry maps it back to [capability].
 */
class ToolDefinition(
    val capability: String,
    val description: String,
    val inputSchema: JsonObject,
    val baseRisk: Risk,
    val sideEffect: SideEffect,
    val idempotency: Idempotency,
    val dataEgress: DataEgress,
    val category: ToolCategory,
    val maxOutputBytes: Int = 12_000,
    val timeoutMs: Long = 45_000,
    /** Short French description used on the approval screen and activity indicator. */
    val label: String = capability,
    val destinationOf: ((JsonObject) -> String?)? = null,
    val riskClassifier: (suspend (JsonObject, PolicyContext) -> RiskAssessment?)? = null,
    /** Passive owner-direct actions (e.g. creating a reminder) stay available while STOP is active. */
    val allowWhenHalted: Boolean = false,
    /** Searchable tags for dynamic tool discovery. */
    val tags: List<String> = emptyList(),
    /** Extra policy traits (doc 06 §5); base traits are derived from side effect / egress. */
    val extraTraits: Set<Trait> = emptySet(),
    /**
     * After a crash between execution and ledger commit: true = the effect verifiably happened,
     * false = verifiably not, null = unknown (→ ask the owner, never blind replay).
     */
    val reconcile: (suspend (JsonObject, ToolContext) -> Boolean?)? = null,
    /**
     * Destination known only from live state (e.g. the host a browser link or form really goes to);
     * takes precedence over [destinationOf] for the taint / known-destination rule.
     */
    val destinationResolver: (suspend (JsonObject, PolicyContext) -> String?)? = null,
    private val execute: suspend (JsonObject, ToolContext) -> ToolResult,
) {
    val functionName: String = capability.replace('.', '_')
    fun spec(): ToolSpec = ToolSpec(functionName, description, inputSchema)

    val traits: Set<Trait> by lazy {
        buildSet {
            when (sideEffect) {
                SideEffect.NONE -> add(Trait.READ_ONLY)
                SideEffect.REVERSIBLE -> add(Trait.REVERSIBLE)
                SideEffect.EXTERNAL -> add(Trait.EXTERNAL_SIDE_EFFECT)
                SideEffect.IRREVERSIBLE -> add(Trait.DESTRUCTIVE)
            }
            if (dataEgress == DataEgress.EXTERNAL) add(Trait.NETWORK_EGRESS)
            addAll(extraTraits)
        }
    }

    /** LAW-003/004 — only [ToolDispatcher] calls this, with the policy decision that authorized it. */
    internal suspend fun invokeAuthorized(args: JsonObject, ctx: ToolContext, decision: PolicyDecision): ToolResult {
        require(decision.capability == capability) { "decision for ${decision.capability} used for $capability" }
        require(decision.requirement != Requirement.DENY) { "denied call dispatched" }
        return execute(args, ctx)
    }
}

// ---- tiny JSON-Schema DSL ----
object S {
    fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }

    fun str(desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
    }

    fun int(desc: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer"); put("description", desc)
        min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }

    fun num(desc: String): JsonObject = buildJsonObject { put("type", "number"); put("description", desc) }
    fun bool(desc: String): JsonObject = buildJsonObject { put("type", "boolean"); put("description", desc) }
    fun arr(desc: String, items: JsonObject): JsonObject = buildJsonObject { put("type", "array"); put("description", desc); put("items", items) }
    fun anyObj(desc: String): JsonObject = buildJsonObject { put("type", "object"); put("description", desc) }
}

/** Minimal JSON-Schema validation (type or type list/required/enum/const/min/max/additionalProperties). */
object SchemaValidator {
    fun validate(schema: JsonObject, value: JsonElement, path: String = "arguments"): List<String> {
        val errors = mutableListOf<String>()
        (schema["const"] as? JsonPrimitive)?.let { c -> if ((value as? JsonPrimitive)?.content != c.content) return listOf("$path doit valoir ${c.content}") }
        // A list of types (["string","number"], ["integer","null"]): the value must match one of them.
        val types = (schema["type"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        if (types != null) {
            if (value is kotlinx.serialization.json.JsonNull) return if ("null" in types) emptyList() else listOf("$path ne peut pas être nul")
            val attempts = types.filter { it != "null" }.map { t -> validate(JsonObject(schema + ("type" to JsonPrimitive(t))), value, path) }
            return if (attempts.isEmpty() || attempts.any { it.isEmpty() }) emptyList() else attempts.minBy { it.size }
        }
        val type = (schema["type"] as? JsonPrimitive)?.content
        // An enum without a type (council_engine_config.schema.json): any primitive among the values.
        if (type == null) (schema["enum"] as? JsonArray)?.let { en ->
            if (value !is JsonPrimitive || en.none { (it as JsonPrimitive).content == value.content }) errors += "$path doit valoir l'un de ${en.joinToString { (it as JsonPrimitive).content }}"
        }
        when (type) {
            "object" -> {
                if (value !is JsonObject) return listOf("$path doit être un objet")
                val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (schema["required"] as? JsonArray)?.forEach { r ->
                    val k = (r as JsonPrimitive).content
                    if (value[k] == null || value[k] is kotlinx.serialization.json.JsonNull) errors += "$path.$k est requis"
                }
                val additional = (schema["additionalProperties"] as? JsonPrimitive)?.content != "false"
                for ((k, v) in value) {
                    val ps = props[k] as? JsonObject
                    if (ps == null) {
                        if (!additional) errors += "$path.$k n'est pas un paramètre connu"
                        continue
                    }
                    if (v is kotlinx.serialization.json.JsonNull) continue
                    errors += validate(ps, v, "$path.$k")
                }
            }
            "string" -> {
                if (value !is JsonPrimitive || !value.isString) return listOf("$path doit être une chaîne")
                (schema["enum"] as? JsonArray)?.let { en ->
                    if (en.none { (it as JsonPrimitive).content == value.content }) errors += "$path doit valoir l'un de ${en.joinToString { (it as JsonPrimitive).content }}"
                }
            }
            "integer", "number" -> {
                val n = (value as? JsonPrimitive)?.takeIf { !it.isString || it.content.toDoubleOrNull() != null }?.content?.toDoubleOrNull()
                    ?: return listOf("$path doit être un nombre")
                if (type == "integer" && n % 1.0 != 0.0) errors += "$path doit être un entier"
                (schema["minimum"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { if (n < it) errors += "$path doit être ≥ $it" }
                (schema["maximum"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { if (n > it) errors += "$path doit être ≤ $it" }
            }
            "boolean" -> {
                val p = value as? JsonPrimitive
                if (p == null || (p.content != "true" && p.content != "false")) errors += "$path doit être un booléen"
            }
            "array" -> {
                if (value !is JsonArray) return listOf("$path doit être un tableau")
                val items = schema["items"] as? JsonObject
                if (items != null) value.forEachIndexed { i, e -> errors += validate(items, e, "$path[$i]") }
            }
        }
        return errors
    }
}
