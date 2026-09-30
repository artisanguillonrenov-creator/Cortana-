package io.github.artisanguillonrenov.cortana.core.improvement

import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.EvalCaseEntity
import io.github.artisanguillonrenov.cortana.core.memory.ImprovementProposalEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPath
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathContext
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathMatch
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What applying a proposal does. Every change is typed, bounded and reversible; there is no "run code" change. */
@Serializable
sealed interface ImprovementChange {
    fun describe(): String
}

/** One owner setting among [SettingKeys.TUNABLE] — never a security, permission or secret setting. */
@Serializable @SerialName("setting")
data class SettingChange(val key: String, val value: JsonElement) : ImprovementChange {
    override fun describe() = "Réglage « ${SettingKeys.label(key)} » → ${if (value is JsonNull) "aucun" else (value as? JsonPrimitive)?.content ?: value.toString()}"
}

/** Binds an exact phrase to one low-risk call (still dispatched under the policy). */
@Serializable @SerialName("shortcut")
data class ShortcutChange(val phrase: String, val capability: String, val args: String) : ImprovementChange {
    override fun describe() = "Raccourci « $phrase » → $capability $args"
}

@Serializable @SerialName("eval_case")
data class EvalCaseChange(val objective: String, val expected: List<String>) : ImprovementChange {
    override fun describe() = "Cas de test « ${objective.take(60)} » : ${expected.joinToString(" → ")}"
}

@Serializable @SerialName("skill_state")
data class SkillStateChange(val skillId: String, val enable: Boolean) : ImprovementChange {
    override fun describe() = if (enable) "Activer la procédure" else "Désactiver la procédure"
}

/** Owner-approved shortcut (setting `ownerShortcuts`). */
@Serializable
data class OwnerShortcut(val phrase: String, val capability: String, val args: String, val proposalId: String? = null)

/** The only settings an improvement may change, with their bounds. Anything else is refused. */
object SettingKeys {
    const val MAX_TOOL_CALLS = 100
    const val MAX_MODEL_CALLS = 60
    const val MIN_TOOLS_OFFERED = 12

    val TUNABLE = setOf("maxToolCallsPerTask", "maxModelCallsPerTask", "maxTaskMinutes", "maxToolsOffered", "maxPlanSteps", "maxReplans", "dailySpendCapUsd", "defaultProviderId")

    fun label(key: String) = when (key) {
        "maxToolCallsPerTask" -> "appels d'outils par tâche"; "maxModelCallsPerTask" -> "appels au modèle par tâche"; "maxTaskMinutes" -> "durée maximale d'une tâche"
        "maxToolsOffered" -> "outils proposés par appel"; "maxPlanSteps" -> "étapes de plan"; "maxReplans" -> "replanifications"
        "dailySpendCapUsd" -> "plafond de dépense quotidien"; "defaultProviderId" -> "fournisseur par défaut"; else -> key
    }

    fun read(s: AppSettings, key: String): JsonElement = when (key) {
        "maxToolCallsPerTask" -> JsonPrimitive(s.maxToolCallsPerTask); "maxModelCallsPerTask" -> JsonPrimitive(s.maxModelCallsPerTask)
        "maxTaskMinutes" -> JsonPrimitive(s.maxTaskMinutes); "maxToolsOffered" -> JsonPrimitive(s.maxToolsOffered)
        "maxPlanSteps" -> JsonPrimitive(s.maxPlanSteps); "maxReplans" -> JsonPrimitive(s.maxReplans)
        "dailySpendCapUsd" -> s.dailySpendCapUsd?.let { JsonPrimitive(it) } ?: JsonNull
        "defaultProviderId" -> s.defaultProviderId?.let { JsonPrimitive(it) } ?: JsonNull
        else -> throw IllegalArgumentException("réglage non modifiable par une amélioration : $key")
    }

    /** Validated write; [providers] are the ids that exist. */
    fun write(s: AppSettings, key: String, v: JsonElement, providers: Set<String>): AppSettings {
        fun int(min: Int, max: Int): Int = (v as? JsonPrimitive)?.intOrNull?.takeIf { it in min..max } ?: throw IllegalArgumentException("valeur hors bornes pour ${label(key)} ($min-$max)")
        return when (key) {
            "maxToolCallsPerTask" -> s.copy(maxToolCallsPerTask = int(5, MAX_TOOL_CALLS))
            "maxModelCallsPerTask" -> s.copy(maxModelCallsPerTask = int(5, MAX_MODEL_CALLS))
            "maxTaskMinutes" -> s.copy(maxTaskMinutes = int(5, 60))
            "maxToolsOffered" -> s.copy(maxToolsOffered = int(MIN_TOOLS_OFFERED, 48))
            "maxPlanSteps" -> s.copy(maxPlanSteps = int(3, 16))
            "maxReplans" -> s.copy(maxReplans = int(0, 5))
            "dailySpendCapUsd" -> s.copy(dailySpendCapUsd = if (v is JsonNull) null else (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 && it <= 1000 } ?: throw IllegalArgumentException("plafond invalide"))
            "defaultProviderId" -> s.copy(defaultProviderId = if (v is JsonNull) null else (v as? JsonPrimitive)?.content?.takeIf { it in providers } ?: throw IllegalArgumentException("fournisseur inconnu"))
            else -> throw IllegalArgumentException("réglage non modifiable par une amélioration : $key")
        }
    }
}

/**
 * Improvement Service (blueprint §18, doc 04 §16, LAW-019): analyzes recent use and records
 * versioned, attributable proposals. It never executes a tool, calls a model or edits code; the
 * only changes it can make are the typed [ImprovementChange]s, and only when the owner applies
 * one — each apply keeps what it replaced so it can be rolled back. Code changes go through the
 * Software Factory (review + approval), never through here.
 */
class ImprovementService(
    private val db: CortanaDatabase,
    private val registry: ToolRegistry,
    private val settings: SettingsRepository,
    private val skills: SkillService,
    private val audit: AuditLog,
    private val analyzers: List<ImprovementAnalyzer> = Analyzers.all(),
) {
    data class Report(val created: Int, val updated: Int, val obsolete: Int)

    private val dao get() = db.improvements()
    private val lock = Mutex()
    @Volatile private var lastRun = 0L

    fun observe(): Flow<List<ImprovementProposalEntity>> = dao.observe()
    suspend fun all(): List<ImprovementProposalEntity> = dao.all()
    suspend fun open(): List<ImprovementProposalEntity> = dao.withStatus("open")
    suspend fun evalCases(): List<EvalCaseEntity> = dao.cases()
    fun change(p: ImprovementProposalEntity): ImprovementChange? = p.changeJson?.let { runCatching { AppJson.decodeFromString(ImprovementChange.serializer(), it) }.getOrNull() }

    /** After a task: re-analyzes at most every [minIntervalMs]. */
    suspend fun afterTask(minIntervalMs: Long = 30 * 60_000L) {
        if (System.currentTimeMillis() - lastRun < minIntervalMs) return
        runCatching { analyze() }.onFailure { CLog.w("improvement analysis failed", it) }
    }

    suspend fun analyze(now: Long = System.currentTimeMillis()): Report = lock.withLock {
        lastRun = now
        val snap = snapshot(now)
        val seen = mutableSetOf<String>()
        var created = 0; var updated = 0
        for (a in analyzers) {
            val drafts = runCatching { a.analyze(snap) }.getOrElse { CLog.w("analyzer ${a.id} failed", it); continue }
            for (d in drafts) {
                val fp = "${d.kind}:${d.key}".take(400)
                if (!seen.add(fp)) continue
                val changeJson = d.change?.let { AppJson.encodeToString(ImprovementChange.serializer(), it) }
                val evidence = d.evidence.toString()
                val prev = dao.byFingerprint(fp)
                when {
                    prev == null -> {
                        dao.upsert(ImprovementProposalEntity(Ids.new(), fp, d.kind, d.title, d.rationale, evidence, changeJson, "open", 1, a.id, now, now))
                        created++
                    }
                    prev.status == "open" && (prev.evidenceJson != evidence || prev.changeJson != changeJson || prev.title != d.title) -> {
                        dao.upsert(prev.copy(title = d.title, rationale = d.rationale, evidenceJson = evidence, changeJson = changeJson, version = prev.version + 1, analyzer = a.id, updatedAt = now))
                        updated++
                    }
                    prev.status == "obsolete" -> {
                        dao.upsert(prev.copy(title = d.title, rationale = d.rationale, evidenceJson = evidence, changeJson = changeJson, status = "open", version = prev.version + 1, analyzer = a.id, updatedAt = now))
                        updated++
                    }
                    // applied / rejected / rolled back: the owner decided; the same finding is not re-proposed.
                }
            }
        }
        var obsolete = 0
        dao.withStatus("open").filter { it.fingerprint !in seen }.forEach { dao.upsert(it.copy(status = "obsolete", updatedAt = now)); obsolete++ }
        dao.purge(now - 30 * Analyzers.DAY)
        if (created + updated > 0) audit.record("improvement", "improvement.analyze", null, "ok", """{"created":$created,"updated":$updated,"obsolete":$obsolete}""")
        Report(created, updated, obsolete)
    }

    private suspend fun snapshot(now: Long): ImprovementSnapshot {
        val since = now - 30 * Analyzers.DAY
        val tasks = db.tasks().since(since)
        val calls = db.tasks().toolCallsSince(since).groupBy { it.taskId }
        val s = settings.current
        val all = db.providers().all()
        val providers = all.associate { it.id to it.displayName }
        val sessions = tasks.map { it.sessionId }.distinct().associateWith { sid -> db.sessions().get(sid)?.providerId ?: s.defaultProviderId }
        val windows = all.flatMap { db.providers().caps(it.id) }.mapNotNull { c -> c.contextWindow?.let { "${c.providerId}/${c.modelId}" to it } }.toMap()
        return ImprovementSnapshot(now, tasks, calls, db.usage().since(since), registry.all(), db.skills().all(), dao.cases(), s, sessions, providers, windows)
    }

    /** The owner applies [id]: the change is validated again, what it replaces is kept for rollback. */
    suspend fun apply(id: String, by: String = "owner"): String = lock.withLock {
        val p = dao.get(id) ?: throw IllegalArgumentException("proposition introuvable")
        require(p.status == "open") { "proposition déjà ${p.status}" }
        val change = change(p) ?: throw IllegalArgumentException("observation seulement : à traiter à la main")
        val previous: JsonElement = when (change) {
            is SettingChange -> {
                require(change.key in SettingKeys.TUNABLE) { "réglage non modifiable par une amélioration : ${change.key}" }
                val before = SettingKeys.read(settings.current, change.key)
                val ids = db.providers().all().map { it.id }.toSet()
                settings.update { SettingKeys.write(it, change.key, change.value, ids) }
                buildJsonObject { put("value", before) }
            }
            is ShortcutChange -> {
                val def = registry.byCapability(change.capability) ?: throw IllegalArgumentException("capacité inconnue : ${change.capability}")
                require(Analyzers.shortcutable(def)) { "« ${def.capability} » ne peut pas devenir un raccourci" }
                require(runCatching { AppJson.parseToJsonElement(change.args).jsonObject }.isSuccess) { "arguments invalides" }
                val k = Analyzers.key(change.phrase)
                require(settings.current.ownerShortcuts.none { Analyzers.key(it.phrase) == k }) { "un raccourci existe déjà pour cette phrase" }
                settings.update { it.copy(ownerShortcuts = it.ownerShortcuts + OwnerShortcut(change.phrase, change.capability, change.args, p.proposalId)) }
                JsonNull
            }
            is EvalCaseChange -> {
                val c = EvalCaseEntity(Ids.new(), Analyzers.key(change.objective), change.objective,
                    AppJson.encodeToString(ListSerializer(String.serializer()), change.expected), p.proposalId, System.currentTimeMillis())
                require(dao.cases().none { it.objectiveKey == c.objectiveKey }) { "un cas existe déjà pour cette demande" }
                dao.upsertCase(c)
                buildJsonObject { put("caseId", c.caseId) }
            }
            is SkillStateChange -> {
                val k = skills.get(change.skillId) ?: throw IllegalArgumentException("procédure introuvable")
                if (change.enable) require(skills.activate(k.skillId)) { "la procédure n'a pas pu être activée (validation)" } else skills.disable(k.skillId)
                buildJsonObject { put("enabled", k.enabled) }
            }
        }
        dao.upsert(p.copy(status = "applied", decidedAt = System.currentTimeMillis(), decidedBy = by, previousJson = previous.toString(), updatedAt = System.currentTimeMillis()))
        audit.record(by, "improvement.apply", p.title, "ok", """{"proposal":"${p.proposalId}","version":${p.version},"kind":"${p.kind}"}""")
        change.describe()
    }

    /** Restores what [id] replaced; refused when the value was changed again since. */
    suspend fun rollback(id: String, by: String = "owner"): String = lock.withLock {
        val p = dao.get(id) ?: throw IllegalArgumentException("proposition introuvable")
        require(p.status == "applied") { "seule une proposition appliquée peut être annulée" }
        val change = change(p) ?: throw IllegalArgumentException("aucun changement enregistré")
        val prev = p.previousJson?.let { AppJson.parseToJsonElement(it) }
        when (change) {
            is SettingChange -> {
                val now = SettingKeys.read(settings.current, change.key)
                require(now == change.value) { "« ${SettingKeys.label(change.key)} » a été modifié depuis ; annulation refusée pour ne pas écraser votre choix" }
                val ids = db.providers().all().map { it.id }.toSet() + listOfNotNull((prev?.jsonObject?.get("value") as? JsonPrimitive)?.content)
                settings.update { SettingKeys.write(it, change.key, prev!!.jsonObject["value"] ?: JsonNull, ids) }
            }
            is ShortcutChange -> settings.update { s -> s.copy(ownerShortcuts = s.ownerShortcuts.filter { it.proposalId != p.proposalId }) }
            is EvalCaseChange -> (prev?.jsonObject?.get("caseId") as? JsonPrimitive)?.content?.let { dao.deleteCase(it) }
            is SkillStateChange -> {
                val was = (prev?.jsonObject?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: !change.enable
                if (was) skills.activate(change.skillId) else skills.disable(change.skillId)
            }
        }
        dao.upsert(p.copy(status = "rolled_back", decidedAt = System.currentTimeMillis(), decidedBy = by, updatedAt = System.currentTimeMillis()))
        audit.record(by, "improvement.rollback", p.title, "ok", """{"proposal":"${p.proposalId}","version":${p.version}}""")
        "annulé : ${change.describe()}"
    }

    suspend fun reject(id: String, by: String = "owner") = lock.withLock {
        val p = dao.get(id) ?: throw IllegalArgumentException("proposition introuvable")
        require(p.status == "open") { "proposition déjà ${p.status}" }
        dao.upsert(p.copy(status = "rejected", decidedAt = System.currentTimeMillis(), decidedBy = by, updatedAt = System.currentTimeMillis()))
        audit.record(by, "improvement.reject", p.title, "ok", """{"proposal":"${p.proposalId}"}""")
    }

    suspend fun deleteCase(caseId: String) {
        dao.deleteCase(caseId)
        audit.record("owner", "improvement.eval_case.delete", caseId, "ok")
    }
}

/** Fast path over the owner-approved shortcuts: exact phrase only, one call, same dispatcher and policy. */
class OwnerShortcutPath(private val shortcuts: () -> List<OwnerShortcut>) : FastPath {
    override val id = "owner.shortcut"
    override val version = 1

    override fun match(text: String, ctx: FastPathContext): FastPathMatch? {
        val k = Analyzers.key(text)
        if (k.isBlank()) return null
        val sc = shortcuts().firstOrNull { Analyzers.key(it.phrase) == k } ?: return null
        val args = runCatching { AppJson.parseToJsonElement(sc.args).jsonObject }.getOrNull() ?: return null
        return FastPathMatch(id, sc.capability, JsonObject(args), confidence = 0.99, render = { r -> if (r.ok) r.text.take(4_000) else null })
    }
}
