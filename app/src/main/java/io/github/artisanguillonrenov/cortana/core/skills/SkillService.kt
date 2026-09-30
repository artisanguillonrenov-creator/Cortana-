package io.github.artisanguillonrenov.cortana.core.skills

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.RiskHint
import io.github.artisanguillonrenov.cortana.contracts.SkillBundle
import io.github.artisanguillonrenov.cortana.contracts.SkillCondition
import io.github.artisanguillonrenov.cortana.contracts.SkillDefinition
import io.github.artisanguillonrenov.cortana.contracts.SkillLifecycle
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillRunEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillVersionEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.tools.SchemaValidator
import io.github.artisanguillonrenov.cortana.core.tools.ToolDiscovery
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the device can tell a skill before a step (doc 04 §14). */
interface SkillEnvironment {
    fun foregroundPackage(): String?
    fun installedVersion(packageName: String): String?
}

@Serializable
data class ReplayStep(
    val ordinal: Int,
    val capability: String,
    val arguments: JsonObject,
    val preconditions: List<SkillCondition> = emptyList(),
    val postconditions: List<SkillCondition> = emptyList(),
    val coordinateFallback: Boolean = false,
)

@Serializable
data class ReplayPlan(
    val skillId: String,
    val version: Int,
    val name: String,
    val preconditions: List<SkillCondition>,
    val steps: List<ReplayStep>,
    val postconditions: List<SkillCondition>,
)

data class ValidationReport(val ok: Boolean, val problems: List<String>)

/**
 * Procedural memory (doc 04 §12–15, blueprint §17): the one owner of skills — storage and
 * versions, lifecycle (candidate → tested → active → degraded → revalidated/retired), dry-run
 * validation, replay preparation, deterministic condition checks, statistics, invalidation,
 * import/export. Replays themselves run step by step through the ToolDispatcher (StepRunner).
 */
class SkillService(
    private val db: CortanaDatabase,
    private val registry: ToolRegistry,
    private val env: SkillEnvironment,
    private val audit: AuditLog,
    private val discovery: ToolDiscovery,
) {
    private val dao get() = db.skills()

    fun observeAll(): Flow<List<SkillEntity>> = dao.observeAll()
    suspend fun get(id: String): SkillEntity? = dao.get(id)
    suspend fun bySignature(signature: String): SkillEntity? = dao.bySignature(signature)
    suspend fun runs(id: String) = dao.runs(id)
    suspend fun versions(id: String) = dao.versions(id)

    suspend fun definition(id: String, version: Int? = null): SkillDefinition? {
        val e = dao.get(id) ?: return null
        val v = dao.version(id, version ?: e.currentVersion) ?: return null
        return decode(v.definitionJson)?.copy(
            lifecycle = lifecycleOf(e), enabled = e.enabled, successCount = e.successCount, failureCount = e.failureCount,
            confidence = e.confidence, lastValidatedAt = e.lastValidatedAt, lastFailureReason = e.lastFailureReason,
        )
    }

    /** Stores a new skill (version 1) as a disabled candidate. */
    suspend fun create(def: SkillDefinition, note: String): SkillEntity {
        val now = System.currentTimeMillis()
        val id = def.skillId.ifBlank { Ids.new() }
        val clean = sanitize(def.copy(skillId = id, version = 1, createdAt = now, updatedAt = now))
        dao.upsertVersion(SkillVersionEntity(id, 1, encode(clean), note, now))
        val e = SkillEntity(id, clean.name, 1, wire(SkillLifecycle.CANDIDATE), false, clean.signature, createdFrom = clean.createdFrom, createdAt = now, updatedAt = now)
        dao.upsert(e)
        dao.insertRun(SkillRunEntity(Ids.new(), id, 1, null, "learning", "succeeded", reason = note, at = now))
        audit.record("cortana", "skill.create", clean.name, "ok", """{"skill":"$id"}""")
        return e
    }

    /** A new version replaces the current one and must be validated and activated again. */
    suspend fun addVersion(id: String, def: SkillDefinition, note: String): SkillEntity? {
        val e = dao.get(id) ?: return null
        val now = System.currentTimeMillis()
        val version = e.currentVersion + 1
        dao.upsertVersion(SkillVersionEntity(id, version, encode(sanitize(def.copy(skillId = id, version = version, updatedAt = now))), note, now))
        val upd = e.copy(currentVersion = version, lifecycle = wire(SkillLifecycle.CANDIDATE), enabled = false, consecutiveFailures = 0, updatedAt = now)
        dao.upsert(upd)
        audit.record("owner", "skill.version", e.name, "ok", """{"skill":"$id","version":$version}""")
        return upd
    }

    /**
     * Dry-run validation ("simulation", doc 04 §13-8): capabilities exist, arguments validate against
     * the tool schemas with sample parameters, not coordinate-only, environment matches. No effect.
     */
    suspend fun validate(id: String): ValidationReport {
        val def = definition(id) ?: return ValidationReport(false, listOf("skill introuvable"))
        val problems = mutableListOf<String>()
        if (def.steps.isEmpty()) problems += "aucune étape"
        if (def.steps.isNotEmpty() && def.steps.all { it.coordinateFallback }) problems += "uniquement des coordonnées : jamais validable (blueprint §17.3)"
        for (st in def.steps) {
            val tool = registry.byCapability(st.capability)
            if (tool == null) { problems += "étape ${st.ordinal} : capacité ${st.capability} inconnue"; continue }
            val unknown = placeholders(st.arguments) - def.parameters.keys
            if (unknown.isNotEmpty()) { problems += "étape ${st.ordinal} : paramètre non déclaré ${unknown.joinToString()}"; continue }
            // Type-aware sample values: a placeholder stands for a whole value of the type the tool expects.
            val props = tool.inputSchema["properties"] as? JsonObject
            val sample = st.arguments.mapValues { (k, v) ->
                if (!v.contains("{{")) v else when (((props?.get(k) as? JsonObject)?.get("type") as? JsonPrimitive)?.content) {
                    "integer", "number" -> "1"; "boolean" -> "true"; "object" -> "{}"; "array" -> "[]"
                    else -> placeholder.replace(v, "exemple")
                }
            }
            val args = substitute(sample, emptyMap()) ?: continue
            val errors = SchemaValidator.validate(tool.inputSchema, coerce(args, tool.inputSchema))
            if (errors.isNotEmpty()) problems += "étape ${st.ordinal} : ${errors.joinToString("; ")}"
        }
        val risk = computedRisk(def)
        if (risk.ordinal > def.riskLevel.ordinal) problems += "risque des outils plus élevé qu'à l'apprentissage (${def.riskLevel.name} → ${risk.name})"
        envProblem(def)?.let { problems += it }
        val now = System.currentTimeMillis()
        val e = dao.get(id)!!
        val ok = problems.isEmpty()
        val next = when {
            ok && lifecycleOf(e) in setOf(SkillLifecycle.CANDIDATE, SkillLifecycle.DEGRADED) -> SkillLifecycle.TESTED
            ok -> lifecycleOf(e)
            lifecycleOf(e) == SkillLifecycle.ACTIVE -> SkillLifecycle.DEGRADED
            else -> lifecycleOf(e)
        }
        dao.upsert(e.copy(lifecycle = wire(next), enabled = e.enabled && next == SkillLifecycle.ACTIVE, lastValidatedAt = if (ok) now else e.lastValidatedAt,
            lastFailureReason = problems.firstOrNull() ?: e.lastFailureReason, updatedAt = now))
        dao.insertRun(SkillRunEntity(Ids.new(), id, e.currentVersion, null, "validation", if (ok) "succeeded" else "failed", reason = problems.joinToString("; ").ifEmpty { null }, at = now))
        return ValidationReport(ok, problems)
    }

    /** Owner decision (doc 04 §13-9): only a validated skill can be activated. */
    suspend fun activate(id: String): Boolean {
        val e = dao.get(id) ?: return false
        if (lifecycleOf(e) !in setOf(SkillLifecycle.TESTED, SkillLifecycle.VALIDATED, SkillLifecycle.ACTIVE)) return false
        dao.upsert(e.copy(lifecycle = wire(SkillLifecycle.ACTIVE), enabled = true, updatedAt = System.currentTimeMillis()))
        audit.record("owner", "skill.activate", e.name, "ok", """{"skill":"$id","version":${e.currentVersion}}""")
        return true
    }

    suspend fun disable(id: String) = update(id, "skill.disable") { it.copy(enabled = false) }
    suspend fun retire(id: String) = update(id, "skill.retire") { it.copy(enabled = false, lifecycle = wire(SkillLifecycle.RETIRED)) }

    /** Skills contributed by one source (e.g. "plugin:<id>"). */
    suspend fun byOrigin(origin: String): List<SkillEntity> = dao.all().filter { it.createdFrom == origin }

    /** Installs a packaged skill as a disabled candidate from [origin]; the owner validates and activates it. */
    suspend fun installBundle(json: String, origin: String): SkillEntity {
        val bundle = ContractJson.decodeFromString(SkillBundle.serializer(), json)
        require(bundle.format == "cortana.skill" && bundle.bundleVersion == 1) { "paquet de compétence non pris en charge" }
        val def = bundle.skill
        require(def.steps.isNotEmpty()) { "procédure vide" }
        return create(def.copy(skillId = Ids.new(), createdFrom = origin, riskLevel = maxOf(def.riskLevel, computedRisk(def))), "installé par $origin")
    }

    fun bundleCapabilities(json: String): List<String> =
        ContractJson.decodeFromString(SkillBundle.serializer(), json).skill.steps.map { it.capability }.distinct()

    suspend fun delete(id: String) {
        val e = dao.get(id) ?: return
        dao.deleteRuns(id); dao.deleteVersions(id); dao.delete(id)
        audit.record("owner", "skill.delete", e.name, "ok")
    }

    /** Degrades a skill (app updated, selector absent, capability or policy change, repeated failures). */
    suspend fun invalidate(id: String, reason: String, taskId: String? = null) {
        val e = dao.get(id) ?: return
        val now = System.currentTimeMillis()
        dao.upsert(e.copy(lifecycle = wire(SkillLifecycle.DEGRADED), enabled = false, lastFailureReason = reason, updatedAt = now))
        dao.insertRun(SkillRunEntity(Ids.new(), id, e.currentVersion, taskId, "invalidation", "failed", reason = reason, at = now))
        audit.record("system", "skill.invalidate", e.name, "ok", """{"skill":"$id"}""")
    }

    /** Active skills relevant to [objective], limited to capabilities of the session. */
    suspend fun suggest(objective: String, available: Set<String>, limit: Int = 3): List<SkillDefinition> {
        val q = ToolDiscovery.tokens(objective)
        if (q.isEmpty()) return emptyList()
        return dao.all().filter { it.enabled && lifecycleOf(it) == SkillLifecycle.ACTIVE }
            .mapNotNull { definition(it.skillId) }
            .filter { d -> d.steps.all { it.capability in available } }
            .map { d ->
                val doc = ToolDiscovery.tokens(d.name + " " + d.description + " " + d.triggerHints.joinToString(" "))
                d to q.count { t -> doc.any { it == t || ToolDiscovery.stem(it) == ToolDiscovery.stem(t) } }
            }
            .filter { it.second >= 2 || (q.size == 1 && it.second == 1) }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    suspend fun activeDefinitions(available: Set<String>): List<SkillDefinition> =
        dao.all().filter { it.enabled && lifecycleOf(it) == SkillLifecycle.ACTIVE }.mapNotNull { definition(it.skillId) }.filter { d -> d.steps.all { it.capability in available } }

    /** Resolves a skill by id or exact name and builds the concrete replay (parameters substituted). */
    suspend fun prepare(ref: String, params: Map<String, String>, available: Set<String>): Result<ReplayPlan> {
        val e = dao.get(ref) ?: dao.all().firstOrNull { it.name.equals(ref, ignoreCase = true) }
            ?: return Result.failure(SkillException("Procédure « $ref » introuvable"))
        if (!e.enabled || lifecycleOf(e) != SkillLifecycle.ACTIVE) return Result.failure(SkillException("Procédure « ${e.name} » non active (${e.lifecycle})"))
        val def = definition(e.skillId)!!
        envProblem(def)?.let { reason ->
            invalidate(e.skillId, reason)
            return Result.failure(SkillException("Procédure « ${e.name} » désactivée : $reason. Fais la tâche pas à pas."))
        }
        val missing = def.parameters.keys - params.keys
        if (missing.isNotEmpty()) return Result.failure(SkillException("Paramètres manquants : ${missing.joinToString()} (${def.parameters.entries.joinToString { "${it.key} = ${it.value}" }})"))
        val unavailable = def.steps.map { it.capability }.filter { it !in available }
        if (unavailable.isNotEmpty()) return Result.failure(SkillException("Capacités indisponibles dans cette session : ${unavailable.distinct().joinToString()}"))
        val steps = def.steps.map { st ->
            val tool = registry.byCapability(st.capability)
            val args = substitute(st.arguments, params)!!
            ReplayStep(st.ordinal, st.capability, if (tool != null) coerce(args, tool.inputSchema) else args, st.preconditions, st.postconditions, st.coordinateFallback)
        }
        return Result.success(ReplayPlan(e.skillId, e.currentVersion, e.name, def.preconditions, steps, def.postconditions))
    }

    /**
     * Deterministic conditions. Returns null when satisfied, the divergence otherwise, or
     * [NEEDS_SCREEN] for screen conditions that the runner must check through the dispatcher.
     */
    fun check(c: SkillCondition, available: Set<String>): String? = when (c.type) {
        "capability_available" -> if (c.target in available) null else "capacité ${c.target} indisponible"
        "package_foreground" -> if (env.foregroundPackage() == c.target) null else "application attendue ${c.target}, trouvée ${env.foregroundPackage() ?: "aucune"}"
        "app_version" -> c.target?.let { pkg -> env.installedVersion(pkg).let { v -> if (v == c.expected) null else "version de $pkg : ${v ?: "absente"} (attendue ${c.expected})" } }
        "ui_contains", "ui_absent" -> NEEDS_SCREEN
        "tool_succeeded" -> null // checked by the runner on the step result
        else -> "condition inconnue ${c.type}"
    }

    /** Statistics + automatic degradation after [MAX_CONSECUTIVE_FAILURES] (doc 04 §15). */
    suspend fun recordReplay(id: String, version: Int, taskId: String?, success: Boolean, failedStep: Int?, reason: String?) {
        val e = dao.get(id) ?: return
        val now = System.currentTimeMillis()
        val s = e.successCount + if (success) 1 else 0
        val f = e.failureCount + if (success) 0 else 1
        val consecutive = if (success) 0 else e.consecutiveFailures + 1
        var upd = e.copy(successCount = s, failureCount = f, consecutiveFailures = consecutive, confidence = (s + 1.0) / (s + f + 2.0),
            lastUsedAt = now, lastFailureReason = if (success) e.lastFailureReason else reason, updatedAt = now)
        if (success && lifecycleOf(e) == SkillLifecycle.ACTIVE) upd = upd.copy(lastValidatedAt = now)
        dao.upsert(upd)
        dao.insertRun(SkillRunEntity(Ids.new(), id, version, taskId, "replay", if (success) "succeeded" else "diverged", failedStep, reason?.let(Redactor::redact), now))
        if (consecutive >= MAX_CONSECUTIVE_FAILURES) invalidate(id, "$consecutive échecs consécutifs (dernier : $reason)", taskId)
    }

    // ---------------------------------------------------------------- import / export (blueprint §17.4)

    suspend fun export(id: String): String? {
        val def = definition(id) ?: return null
        val portable = def.copy(successCount = 0, failureCount = 0, confidence = 0.0, enabled = false, lifecycle = SkillLifecycle.CANDIDATE, lastFailureReason = null)
        return ContractJson.encodeToString(SkillBundle.serializer(), SkillBundle(skill = sanitize(portable), exportedAt = System.currentTimeMillis()))
    }

    /** Imported skills always start as disabled candidates and must be validated and activated by the owner. */
    suspend fun import(json: String): Result<SkillEntity> = runCatching {
        val bundle = ContractJson.decodeFromString(SkillBundle.serializer(), json)
        require(bundle.format == "cortana.skill") { "format inconnu ${bundle.format}" }
        require(bundle.bundleVersion == 1) { "version de paquet ${bundle.bundleVersion} non prise en charge" }
        val def = bundle.skill
        require(def.steps.isNotEmpty()) { "procédure vide" }
        val unknown = def.steps.map { it.capability }.filter { registry.byCapability(it) == null }
        require(unknown.isEmpty()) { "capacités inconnues : ${unknown.joinToString()}" }
        create(def.copy(skillId = Ids.new(), createdFrom = "import", riskLevel = maxOf(def.riskLevel, computedRisk(def))), "importé (v${def.version} d'origine)")
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun update(id: String, action: String, t: (SkillEntity) -> SkillEntity) {
        val e = dao.get(id) ?: return
        dao.upsert(t(e).copy(updatedAt = System.currentTimeMillis()))
        audit.record("owner", action, e.name, "ok")
    }

    private fun envProblem(def: SkillDefinition): String? {
        val pkg = def.appPackage ?: return null
        val installed = env.installedVersion(pkg) ?: return "application $pkg absente"
        return if (def.appVersion != null && installed != def.appVersion) "application $pkg mise à jour (${def.appVersion} → $installed)" else null
    }

    fun computedRisk(def: SkillDefinition): RiskHint = def.steps.mapNotNull { registry.byCapability(it.capability)?.baseRisk }
        .maxByOrNull { it.level }?.let { r -> RiskHint.entries.first { it.name == r.name } } ?: RiskHint.L0

    /** Secrets never enter a skill: redacted or masked values become mandatory parameters. */
    private fun sanitize(def: SkillDefinition): SkillDefinition = def.copy(
        steps = def.steps.map { st -> st.copy(arguments = st.arguments.mapValues { (_, v) -> Redactor.redact(v) }) },
    )

    private fun encode(d: SkillDefinition) = ContractJson.encodeToString(SkillDefinition.serializer(), d)
    private fun decode(s: String) = runCatching { ContractJson.decodeFromString(SkillDefinition.serializer(), s) }.getOrNull()

    companion object {
        const val NEEDS_SCREEN = "__screen__"
        const val MAX_CONSECUTIVE_FAILURES = 3
        // Every brace escaped: Android's ICU regex engine rejects a bare "}" that the JVM accepts
        // (2.0.0-rc1 crashed at startup on this line — D-20260928-066).
        private val placeholder = Regex("\\{\\{([a-zA-Z0-9_]+)\\}\\}")

        fun wire(l: SkillLifecycle): String = l.name.lowercase()
        fun lifecycleOf(e: SkillEntity): SkillLifecycle = SkillLifecycle.entries.firstOrNull { it.name.equals(e.lifecycle, ignoreCase = true) } ?: SkillLifecycle.CANDIDATE

        fun placeholders(args: Map<String, String>): Set<String> = args.values.flatMap { v -> placeholder.findAll(v).map { it.groupValues[1] } }.toSet()

        /** "{{param}}" substitution; null if a placeholder has no value. */
        fun substitute(args: Map<String, String>, params: Map<String, String>): JsonObject? {
            val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            for ((k, v) in args) {
                var missing = false
                val value = placeholder.replace(v) { m -> params[m.groupValues[1]] ?: run { missing = true; m.value } }
                if (missing) return null
                out[k] = JsonPrimitive(value)
            }
            return JsonObject(out)
        }

        /** Stored arguments are strings; restore numbers and booleans where the tool schema expects them. */
        fun coerce(args: JsonObject, schema: JsonObject): JsonObject {
            val props = schema["properties"] as? JsonObject ?: return args
            return JsonObject(args.mapValues { (k, v) ->
                val type = ((props[k] as? JsonObject)?.get("type") as? JsonPrimitive)?.content
                val raw = (v as? JsonPrimitive)?.content ?: return@mapValues v
                when (type) {
                    "integer" -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: v
                    "number" -> raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: v
                    "boolean" -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: v
                    "object", "array" -> runCatching { AppJson.parseToJsonElement(raw) }.getOrDefault(v)
                    else -> v
                }
            })
        }

        fun riskOf(r: Risk): RiskHint = RiskHint.entries.first { it.name == r.name }
    }
}

class SkillException(message: String) : Exception(message)

