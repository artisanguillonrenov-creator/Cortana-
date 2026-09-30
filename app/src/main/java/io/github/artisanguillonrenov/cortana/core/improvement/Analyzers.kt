package io.github.artisanguillonrenov.cortana.core.improvement

import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.memory.EvalCaseEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.core.memory.UsageEntity
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Everything the analyzers read: a bounded, read-only picture of recent use. */
data class ImprovementSnapshot(
    val now: Long,
    val tasks: List<TaskEntity>,
    val callsByTask: Map<String, List<ToolCallEntity>>,
    val usage: List<UsageEntity>,
    val tools: List<ToolDefinition>,
    val skills: List<SkillEntity>,
    val evalCases: List<EvalCaseEntity>,
    val settings: AppSettings,
    /** sessionId → providerId used (session's own, else the default at analysis time). */
    val providerOfSession: Map<String, String?>,
    val providers: Map<String, String>,
    /** "providerId/modelId" → context window in tokens. */
    val contextWindows: Map<String, Int>,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** What an analyzer found; the service turns it into a versioned proposal. */
data class Draft(
    val kind: String,
    /** Subject inside the kind; with it forms the proposal fingerprint. */
    val key: String,
    val title: String,
    val rationale: String,
    val evidence: JsonObject,
    val change: ImprovementChange? = null,
)

interface ImprovementAnalyzer {
    /** id@version, recorded on every proposal it produces (attribution). */
    val id: String
    fun analyze(s: ImprovementSnapshot): List<Draft>
}

object Analyzers {
    const val DAY = 86_400_000L
    /** Capabilities that organize a task rather than do its work. */
    val META = setOf("tools_discover", "ask_user", "skill.run")

    fun all(): List<ImprovementAnalyzer> = listOf(RecurringFailures, FastPathProposals, SkillProposals, RoutingProposals, EvalCaseProposals, Regressions, ToolHygiene, LongPrompts, CostAnomalies)

    fun key(text: String): String = text.lowercase(Locale.FRENCH).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun code(t: TaskEntity): String = t.terminationReason?.substringBefore(':')?.trim()?.ifBlank { null } ?: "inconnu"

    fun failed(t: TaskEntity) = t.state == "failed" || t.state == "timed_out"

    /** A capability the owner may bind to a phrase: reads or reversible, low risk, never the screen. */
    fun shortcutable(d: ToolDefinition): Boolean =
        (d.sideEffect == SideEffect.NONE || d.sideEffect == SideEffect.REVERSIBLE) && d.baseRisk.level <= Risk.L1.level && d.category != ToolCategory.UI && d.capability !in META

    fun okWork(calls: List<ToolCallEntity>) = calls.filter { it.outcome == "ok" && it.capability !in META }

    fun evidence(vararg pairs: Pair<String, Any?>, tasks: List<TaskEntity> = emptyList()) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, v)
                is List<*> -> putJsonArray(k) { v.forEach { add(JsonPrimitive(it.toString())) } }
                else -> put(k, v.toString())
            }
        }
        if (tasks.isNotEmpty()) putJsonArray("tasks") { tasks.take(10).forEach { add(JsonPrimitive(it.id)) } }
    }

    fun median(xs: List<Double>): Double = xs.sorted().let { if (it.isEmpty()) 0.0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    fun isSubsequence(expected: List<String>, actual: List<String>): Boolean {
        var i = 0
        for (a in actual) if (i < expected.size && a == expected[i]) i++
        return i == expected.size
    }

    fun expected(c: EvalCaseEntity): List<String> = runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), c.expectedJson) }.getOrDefault(emptyList())
}

/** Repeated failures of tasks (same termination code) or of one tool (same error). */
object RecurringFailures : ImprovementAnalyzer {
    override val id = "failures@1"
    private const val WINDOW = 14 * Analyzers.DAY
    private const val MIN = 3

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val recent = s.tasks.filter { it.createdAt >= s.now - WINDOW }
        val out = mutableListOf<Draft>()
        recent.filter(Analyzers::failed).groupBy(Analyzers::code).filter { it.value.size >= MIN && it.key != "kill_switch" }.forEach { (code, ts) ->
            val reasons = ts.mapNotNull { it.terminationReason?.substringAfter(':')?.trim() }
            val tools = reasons.count { it.contains("appels d'outils") }
            val models = reasons.count { it.contains("appels au modèle") }
            val change: ImprovementChange? = when {
                code == "budget_exhausted" && tools * 2 >= ts.size && s.settings.maxToolCallsPerTask < SettingKeys.MAX_TOOL_CALLS ->
                    SettingChange("maxToolCallsPerTask", JsonPrimitive((s.settings.maxToolCallsPerTask * 3 / 2).coerceAtMost(SettingKeys.MAX_TOOL_CALLS)))
                code == "budget_exhausted" && models * 2 >= ts.size && s.settings.maxModelCallsPerTask < SettingKeys.MAX_MODEL_CALLS ->
                    SettingChange("maxModelCallsPerTask", JsonPrimitive((s.settings.maxModelCallsPerTask * 3 / 2).coerceAtMost(SettingKeys.MAX_MODEL_CALLS)))
                else -> null
            }
            out += Draft(
                "recurring_failure", "task:$code", "Échecs répétés : $code (${ts.size} en 14 jours)",
                (reasons.distinct().take(3).joinToString(" · ").ifBlank { "Même cause d'échec." }) +
                    if (change == null) " Aucune correction automatique possible : examiner les tâches, ou demander une modification via la fabrique logicielle (revue + approbation)." else "",
                Analyzers.evidence("count" to ts.size, "code" to code, "samples" to reasons.distinct().take(3), tasks = ts), change,
            )
        }
        val errors = recent.flatMap { t -> s.callsByTask[t.id].orEmpty().filter { it.outcome == "error" }.map { t to it } }
        errors.groupBy { (_, c) -> c.capability + "|" + signature(c.outputRef) }.filter { (_, v) -> v.size >= MIN && v.map { it.first.id }.distinct().size >= 2 }.forEach { (k, v) ->
            val cap = k.substringBefore('|')
            out += Draft(
                "recurring_failure", "tool:$k", "L'outil « $cap » échoue souvent (${v.size} fois)",
                "Même erreur : ${v.first().second.outputRef?.take(160) ?: "?"}. À vérifier : configuration, connexion ou service distant.",
                Analyzers.evidence("count" to v.size, "capability" to cap, tasks = v.map { it.first }.distinct()),
            )
        }
        return out
    }

    /** The error without numbers, ids or quoted values: the same failure groups together. */
    fun signature(text: String?): String = (text ?: "").lowercase().replace(Regex("« [^»]*»|\"[^\"]*\"|[0-9a-f]{8,}|\\d+"), "#").take(60)
}

/** The same objective answered repeatedly by one read-only call: bind the phrase to that call, no model needed. */
object FastPathProposals : ImprovementAnalyzer {
    override val id = "fastpath@1"
    private const val MIN = 3

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val defs = s.tools.associateBy { it.capability }
        val known = s.settings.ownerShortcuts.map { Analyzers.key(it.phrase) }.toSet()
        val candidates = s.tasks.filter { it.state == "completed" && !it.tainted && it.mode != "fast_path" && it.parentTaskId == null && it.source in setOf("chat", "voice") }.mapNotNull { t ->
            val calls = s.callsByTask[t.id].orEmpty().filter { it.capability !in Analyzers.META }
            val c = calls.singleOrNull()?.takeIf { it.outcome == "ok" } ?: return@mapNotNull null
            val def = defs[c.capability]?.takeIf(Analyzers::shortcutable) ?: return@mapNotNull null
            val args = runCatching { AppJson.parseToJsonElement(c.inputJson).jsonObject }.getOrNull() ?: return@mapNotNull null
            if (args.toString().contains(io.github.artisanguillonrenov.cortana.util.Redactor.MASK) || args.toString().length > 600) return@mapNotNull null
            Triple(t, def.capability, args)
        }
        return candidates.groupBy { (t, cap, args) -> Triple(Analyzers.key(t.objective), cap, args.toString()) }
            .filter { (k, v) -> v.size >= MIN && k.first.isNotBlank() && k.first.length <= 120 && k.first !in known }
            .map { (k, v) ->
                val latest = v.maxBy { it.first.createdAt }
                Draft(
                    "fast_path", "fp:${k.first}", "Raccourci : « ${latest.first.objective.take(60)} »",
                    "Demandé ${v.size} fois, toujours résolu par un seul appel « ${k.second} » avec les mêmes arguments. Un raccourci répondrait sans appel au modèle (politique et audit inchangés).",
                    Analyzers.evidence("count" to v.size, "capability" to k.second, "args" to k.third, tasks = v.map { it.first }),
                    ShortcutChange(latest.first.objective.trim().take(200), k.second, k.third),
                )
            }
    }
}

/** Learned procedures ready to be used, and active ones that keep failing. */
object SkillProposals : ImprovementAnalyzer {
    override val id = "skills@1"

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val out = mutableListOf<Draft>()
        s.skills.filter { it.lifecycle == "validated" && !it.enabled }.forEach { k ->
            out += Draft(
                "skill", "skill:${k.skillId}", "Activer la procédure « ${k.name} »",
                "Procédure apprise et validée (${k.signature ?: "?"}). Activée, elle peut être rejouée pour les demandes semblables, toujours sous la politique.",
                Analyzers.evidence("signature" to k.signature, "successes" to k.successCount), SkillStateChange(k.skillId, enable = true),
            )
        }
        s.skills.filter { it.enabled && (it.consecutiveFailures >= 2 || (it.successCount + it.failureCount >= 4 && it.confidence < 0.5)) }.forEach { k ->
            out += Draft(
                "skill_confidence", "skill-conf:${k.skillId}", "Désactiver la procédure « ${k.name} »",
                "Confiance ${"%.0f".format(k.confidence * 100)} %, ${k.consecutiveFailures} échec(s) consécutif(s)" + (k.lastFailureReason?.let { " — dernier : ${it.take(120)}" } ?: "") + ".",
                Analyzers.evidence("confidence" to k.confidence, "failures" to k.failureCount, "consecutive" to k.consecutiveFailures), SkillStateChange(k.skillId, enable = false),
            )
        }
        return out
    }
}

/** A provider that keeps failing while another one works: propose it as the default route. */
object RoutingProposals : ImprovementAnalyzer {
    override val id = "routing@1"
    private const val WINDOW = 7 * Analyzers.DAY

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val recent = s.tasks.filter { it.createdAt >= s.now - WINDOW }
        fun providerFailure(t: TaskEntity) = Analyzers.failed(t) && Analyzers.code(t).startsWith("provider.") && Analyzers.code(t) != "provider.none"
        val byProvider = recent.groupBy { s.providerOfSession[it.sessionId] }.filterKeys { it != null && it in s.providers }
        val stats = byProvider.mapValues { (_, ts) -> ts.count(::providerFailure) to ts.count { it.state == "completed" } }
        val default = s.settings.defaultProviderId ?: return emptyList()
        val (fails, oks) = stats[default] ?: return emptyList()
        if (fails < 3 || fails.toDouble() / (fails + oks) < 0.3) return emptyList()
        val better = stats.filter { (p, st) -> p != default && st.second >= 3 && st.first <= 1 }.maxByOrNull { it.value.second }?.key ?: return listOf(
            Draft("routing", "route:$default", "Le fournisseur « ${s.providers[default]} » échoue souvent",
                "$fails échec(s) du fournisseur sur ${fails + oks} tâches en 7 jours, et aucun autre fournisseur n'a fait ses preuves. Vérifier la clé, le quota ou le modèle.",
                Analyzers.evidence("failures" to fails, "completed" to oks, "provider" to default, tasks = byProvider[default].orEmpty().filter(::providerFailure))),
        )
        return listOf(
            Draft("routing", "route:$default", "Fournisseur par défaut : « ${s.providers[better]} » au lieu de « ${s.providers[default]} »",
                "« ${s.providers[default]} » : $fails échec(s) du fournisseur sur ${fails + oks} tâches en 7 jours ; « ${s.providers[better]} » : ${stats[better]!!.second} tâche(s) réussie(s) sans échec répété.",
                Analyzers.evidence("failures" to fails, "completed" to oks, "from" to default, "to" to better, tasks = byProvider[default].orEmpty().filter(::providerFailure)),
                SettingChange("defaultProviderId", JsonPrimitive(better))),
        )
    }
}

/** A request that failed and later succeeded becomes a regression case (what it must use to succeed). */
object EvalCaseProposals : ImprovementAnalyzer {
    override val id = "evals@1"

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val have = s.evalCases.map { it.objectiveKey }.toSet()
        return s.tasks.filter { !it.tainted && it.parentTaskId == null }.groupBy { Analyzers.key(it.objective) }.filter { (k, _) -> k.isNotBlank() && k !in have }.mapNotNull { (k, ts) ->
            val sorted = ts.sortedBy { it.createdAt }
            val firstFail = sorted.indexOfFirst(Analyzers::failed).takeIf { it >= 0 } ?: return@mapNotNull null
            val success = sorted.drop(firstFail + 1).lastOrNull { it.state == "completed" } ?: return@mapNotNull null
            val expected = Analyzers.okWork(s.callsByTask[success.id].orEmpty()).map { it.capability }.distinct().take(8)
            if (expected.isEmpty()) return@mapNotNull null
            Draft(
                "eval_case", "eval:$k", "Nouveau cas de test : « ${success.objective.take(60)} »",
                "A échoué (${Analyzers.code(sorted[firstFail])}) puis réussi avec ${expected.joinToString(" → ")}. Gardé comme cas de non-régression : un échec futur sur la même demande sera signalé.",
                Analyzers.evidence("failed" to sorted[firstFail].id, "succeeded" to success.id, "expected" to expected),
                EvalCaseChange(success.objective.take(300), expected),
            )
        }
    }
}

/** Regression cases that used to pass and now fail; tools whose success rate dropped. */
object Regressions : ImprovementAnalyzer {
    override val id = "regressions@1"

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val out = mutableListOf<Draft>()
        val byKey = s.tasks.groupBy { Analyzers.key(it.objective) }
        s.evalCases.filter { it.enabled }.forEach { c ->
            val exp = Analyzers.expected(c)
            val runs = byKey[c.objectiveKey].orEmpty().filter { it.state in setOf("completed", "failed", "timed_out") && it.createdAt > c.createdAt }.sortedBy { it.createdAt }
            val verdicts = runs.map { t -> t to (t.state == "completed" && Analyzers.isSubsequence(exp, Analyzers.okWork(s.callsByTask[t.id].orEmpty()).map { it.capability })) }
            val last = verdicts.lastOrNull() ?: return@forEach
            if (!last.second) out += Draft(
                "regression", "regress:case:${c.caseId}", "Régression : « ${c.objective.take(60)} »",
                "Cette demande (cas de test du ${java.text.DateFormat.getDateInstance().format(java.util.Date(c.createdAt))}) n'a plus abouti comme prévu (${exp.joinToString(" → ")}) : ${if (last.first.state == "completed") "chemin différent" else Analyzers.code(last.first)}.",
                Analyzers.evidence("case" to c.caseId, "expected" to exp, "passedBefore" to verdicts.dropLast(1).count { it.second }, tasks = listOf(last.first)),
            )
        }
        val calls = s.callsByTask.values.flatten().filter { it.capability !in Analyzers.META && it.outcome in setOf("ok", "error") }
        val week = calls.filter { it.createdAt >= s.now - 7 * Analyzers.DAY }.groupBy { it.capability }
        val before = calls.filter { it.createdAt in (s.now - 14 * Analyzers.DAY) until (s.now - 7 * Analyzers.DAY) }.groupBy { it.capability }
        week.forEach { (cap, now) ->
            val prev = before[cap] ?: return@forEach
            if (now.size < 5 || prev.size < 5) return@forEach
            val r1 = now.count { it.outcome == "ok" }.toDouble() / now.size
            val r0 = prev.count { it.outcome == "ok" }.toDouble() / prev.size
            if (r0 - r1 >= 0.3) out += Draft(
                "regression", "regress:tool:$cap", "« $cap » réussit moins souvent",
                "Taux de réussite ${"%.0f".format(r0 * 100)} % la semaine précédente, ${"%.0f".format(r1 * 100)} % cette semaine (${now.size} appels).",
                Analyzers.evidence("before" to r0, "now" to r1, "calls" to now.size),
            )
        }
        return out
    }
}

/** Near-duplicate tool descriptions and tools nobody used for a month. */
object ToolHygiene : ImprovementAnalyzer {
    override val id = "tools@1"

    private fun words(d: ToolDefinition) = d.description.lowercase(Locale.FRENCH).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val out = mutableListOf<Draft>()
        val tools = s.tools.sortedBy { it.capability }
        val w = tools.associate { it.capability to words(it) }
        for (i in tools.indices) for (j in i + 1 until tools.size) {
            val a = w[tools[i].capability]!!; val b = w[tools[j].capability]!!
            if (a.size < 4 || b.size < 4) continue
            val jac = (a intersect b).size.toDouble() / (a union b).size
            if (jac >= 0.8) out += Draft(
                "duplicate_tools", "dup:${tools[i].capability}|${tools[j].capability}", "Outils en double : « ${tools[i].capability} » et « ${tools[j].capability} »",
                "Descriptions presque identiques (${"%.0f".format(jac * 100)} %) : le modèle peut hésiter entre les deux. Retirer ou préciser l'un d'eux (serveur MCP, plugin, ou modification via la fabrique logicielle).",
                Analyzers.evidence("similarity" to jac),
            )
        }
        val monthTasks = s.tasks.filter { it.createdAt >= s.now - 30 * Analyzers.DAY }
        if (monthTasks.size >= 30) {
            val used = s.callsByTask.values.flatten().filter { it.createdAt >= s.now - 30 * Analyzers.DAY }.map { it.capability }.toSet()
            val unused = tools.map { it.capability }.filter { it !in used && it !in Analyzers.META }
            if (unused.isNotEmpty()) out += Draft(
                "unused_tools", "unused", "${unused.size} outil(s) jamais utilisé(s) depuis 30 jours",
                "Sur ${monthTasks.size} tâches : ${unused.take(12).joinToString()}${if (unused.size > 12) "…" else ""}. Les outils de serveurs MCP ou de plugins inutiles peuvent être retirés ; ceux de Cortana restent disponibles par la découverte.",
                Analyzers.evidence("count" to unused.size, "tools" to unused.take(40)),
            )
        }
        return out
    }
}

/** Agent prompts close to the model's context window: offer fewer tools per call. */
object LongPrompts : ImprovementAnalyzer {
    override val id = "prompts@1"

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        val calls = s.usage.filter { it.role == "agent" && it.occurredAt >= s.now - 7 * Analyzers.DAY && it.inputTokens != null }
        if (calls.size < 10) return emptyList()
        val med = Analyzers.median(calls.map { it.inputTokens!!.toDouble() })
        val window = calls.groupingBy { "${it.providerId}/${it.modelId}" }.eachCount().maxBy { it.value }.key.let { s.contextWindows[it] } ?: 32_000
        if (med < window * 0.6 && med < 24_000) return emptyList()
        val change = if (s.settings.maxToolsOffered > SettingKeys.MIN_TOOLS_OFFERED) SettingChange("maxToolsOffered", JsonPrimitive((s.settings.maxToolsOffered - 8).coerceAtLeast(SettingKeys.MIN_TOOLS_OFFERED))) else null
        return listOf(Draft(
            "long_prompt", "prompt:agent", "Requêtes trop longues pour le modèle",
            "Taille médiane ${med.toInt()} jetons pour une fenêtre de $window (${calls.size} appels en 7 jours) : réponses plus lentes, plus chères, et contexte tronqué." +
                if (change != null) " Proposé : offrir moins d'outils par appel (la découverte reste disponible)." else " Choisir un modèle à plus grande fenêtre.",
            Analyzers.evidence("median" to med.toInt(), "window" to window, "calls" to calls.size), change,
        ))
    }
}

/** A day far above the usual spending. */
object CostAnomalies : ImprovementAnalyzer {
    override val id = "cost@1"

    override fun analyze(s: ImprovementSnapshot): List<Draft> {
        fun day(t: Long) = Instant.ofEpochMilli(t).atZone(s.zone).toLocalDate()
        val today = day(s.now)
        val byDay = s.usage.filter { it.costUsd != null && it.occurredAt >= s.now - 15 * Analyzers.DAY }.groupBy { day(it.occurredAt) }.mapValues { (_, u) -> u.sumOf { it.costUsd!! } }
        val spent = byDay[today] ?: return emptyList()
        val baseline = byDay.filterKeys { it != today }.values.toList()
        if (baseline.size < 5) return emptyList()
        val med = Analyzers.median(baseline)
        if (spent < 0.5 || spent < 3 * med) return emptyList()
        val change = if (s.settings.dailySpendCapUsd == null) SettingChange("dailySpendCapUsd", JsonPrimitive(Math.round(maxOf(1.0, med * 2) * 100) / 100.0)) else null
        return listOf(Draft(
            "cost_anomaly", "cost:$today", "Dépense inhabituelle aujourd'hui : ${"%.2f".format(spent)} \$",
            "Médiane des jours précédents : ${"%.2f".format(med)} \$." + if (change != null) " Aucun plafond n'est réglé : un plafond quotidien arrêterait proprement les tâches au-delà." else " Le plafond quotidien reste celui que vous avez choisi.",
            Analyzers.evidence("today" to spent, "median" to med, "days" to baseline.size), change,
        ))
    }
}
