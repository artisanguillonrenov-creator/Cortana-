package io.github.artisanguillonrenov.cortana.executors.dev

import io.github.artisanguillonrenov.cortana.contracts.Diagnostic
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.dev.BuildService
import io.github.artisanguillonrenov.cortana.core.dev.CodeIntelligence
import io.github.artisanguillonrenov.cortana.core.dev.PatchConflict
import io.github.artisanguillonrenov.cortana.core.dev.PatchEngine
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionRefused
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.arr
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Repository intelligence tools (doc 03 §5, §18): symbols, references, test impact, static
 * diagnostics and a symbol-aware rename whose before/after diagnostics are part of the result.
 */
class CodeTools(
    private val workspaces: WorkspaceManager,
    private val intel: CodeIntelligence,
    private val patches: PatchEngine,
    private val builds: BuildService,
    private val review: io.github.artisanguillonrenov.cortana.core.dev.ReviewService? = null,
) {
    private val ws = "workspace" to S.str("Projet")

    fun tools(): List<ToolDefinition> = listOf(
        read("code.symbols", "Liste/cherche les symboles du projet (classes, fonctions, propriétés, types) avec fichier et ligne. Avec un nom exact : définition et documentation.",
            S.obj(ws, "query" to S.str("Nom ou partie de nom"), "file" to S.str("Limiter à un fichier"), "kind" to S.str("Type : class, function, property, method, type, variable"),
                "limit" to S.int("Nombre max", 1, 300), required = listOf("workspace")), "Explorer les symboles", listOf("symbole", "definition", "classe", "fonction", "structure")) { w, a ->
            val q = a.str("query")
            val list = intel.symbols(w, q, a.str("file"), a.str("kind"), a.int("limit") ?: 60)
            val hover = q?.let { intel.hover(w, it) }
            if (list.isEmpty()) "Aucun symbole." else buildString {
                hover?.let { append("Définition :\n").append(it).append("\n\n") }
                list.forEach { append("${it.file}:${it.line} ${it.kind} ${it.container?.let { c -> "$c." } ?: ""}${it.name} — ${it.signature}\n") }
            }
        },
        read("code.references", "Trouve toutes les utilisations d'un symbole dans le code (hors chaînes et commentaires), définitions en premier.",
            S.obj(ws, "symbol" to S.str("Nom exact"), "limit" to S.int("Nombre max", 1, 500), required = listOf("workspace", "symbol")), "Trouver les références",
            listOf("references", "utilisations", "appels", "usages")) { w, a ->
            val refs = intel.references(w, a.str("symbol")!!, a.int("limit") ?: 150)
            if (refs.isEmpty()) "Aucune référence." else "${refs.size} occurrence(s) :\n" +
                refs.joinToString("\n") { "${it.file}:${it.line}:${it.column}${if (it.isDefinition) " [définition]" else ""} ${it.preview}" }
        },
        read("code.impact", "Impact d'un changement : fichiers qui dépendent des fichiers donnés et tests à relancer.",
            S.obj(ws, "files" to S.arr("Fichiers modifiés (chemins relatifs)", S.str("Chemin")), required = listOf("workspace", "files")), "Analyser l'impact",
            listOf("impact", "dependances", "tests", "regression")) { w, a ->
            val files = a.arr("files")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val graph = intel.dependencyGraph(w)
            val dependents = graph.filterValues { deps -> deps.any { it in files } }.keys.sorted()
            val tests = intel.impactedTests(w, files)
            buildString {
                append("Dépendent directement de ${files.joinToString()} : ${dependents.joinToString().ifEmpty { "aucun fichier" }}\n")
                append("Tests impactés : ${tests.joinToString().ifEmpty { "aucun test trouvé (lancer toute la suite)" }}")
            }
        },
        read("code.diagnostics", "Diagnostics statiques sans rien exécuter : imports relatifs cassés, définitions en double. Pour compiler ou tester, utilise build_run / test_run / lint_run.",
            S.obj(ws, required = listOf("workspace")), "Diagnostiquer le code", listOf("diagnostics", "erreurs", "imports", "analyse")) { w, _ ->
            val d = intel.staticDiagnostics(w)
            if (d.isEmpty()) "Aucun problème statique détecté." else render(d)
        },
        ToolDefinition(
            "review.changes", "Relit les modifications (diff, périmètre, modifications accidentelles, secrets, tests sur le code actuel, architecture, migrations) et rend un verdict. " +
                "basis=task : changements de cette tâche (défaut) ; basis=git : modifications non commitées du dépôt.",
            S.obj("workspace" to S.str("Projet (obligatoire pour basis=git)"), "basis" to S.str("Base de comparaison", listOf("task", "git")),
                "scope" to S.str("Périmètre attendu (glob, ex. src/**) : tout fichier hors périmètre est signalé")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = 20_000,
            label = "Relire les modifications", tags = listOf("revue", "relire", "verifier", "diff", "review"),
        ) { a, ctx ->
            val svc = review ?: return@ToolDefinition ToolResult.error("Revue indisponible")
            try {
                val results = if (a.str("basis") == "git") listOf(svc.reviewUncommitted(workspaces.require(a.str("workspace") ?: throw WorkspaceException("workspace requis")), a.str("scope"), ctx.taskId))
                else svc.reviewTask(ctx.taskId, a.str("scope")).filter { r -> a.str("workspace")?.let { ref -> workspaces.find(ref)?.workspaceId == r.workspaceId } ?: true }
                if (results.isEmpty()) ToolResult.ok("Aucune modification de code par cette tâche.")
                else ToolResult(results.none { it.verdict == "blocked" }, results.joinToString("\n") { io.github.artisanguillonrenov.cortana.core.dev.ReviewService.render(it) }, workspaceSource(results.first().workspaceId))
            } catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
            catch (e: io.github.artisanguillonrenov.cortana.core.dev.GitRefused) { ToolResult.error(e.message ?: "Git") }
        },
        ToolDefinition(
            "code.rename", "Renomme un symbole partout où il est utilisé dans le code (pas dans les chaînes ni les commentaires). apply=false : aperçu seulement. " +
                "Avec verify=tests|build, les tests ou le build sont lancés avant ET après ; en cas de régression la modification est annulée automatiquement.",
            S.obj(ws, "symbol" to S.str("Nom actuel"), "new_name" to S.str("Nouveau nom"), "scope" to S.str("Limiter à des fichiers (glob, ex. src/**)"),
                "apply" to S.bool("Appliquer (sinon aperçu)"), "verify" to S.str("Vérification avant/après", listOf("none", "tests", "build")),
                "rollback_on_regression" to S.bool("Annuler si la vérification régresse (oui par défaut)"), required = listOf("workspace", "symbol", "new_name")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = 30_000, timeoutMs = 3_700_000,
            label = "Renommer un symbole", tags = listOf("renommer", "refactoriser", "refactoring", "symbole"),
            riskClassifier = { a, _ ->
                val w = workspaces.find(a.str("workspace") ?: "") ?: return@ToolDefinition RiskAssessment(Risk.L1, deny = true, denyReason = "Projet introuvable")
                val v = a.str("verify") ?: "none"
                if (a.bool("apply") == true && v != "none") RiskAssessment(Risk.L2, listOf("Exécute le code du projet « ${w.name} » (${w.trust}) avant et après le renommage"), targetDescription = w.name)
                else null
            },
            reconcile = { _, ctx -> ctx.idempotencyKey?.let { key -> patches.changeSetsForTask(ctx.taskId).any { it.patchId == DevTools.patchIdFor(key) } } },
        ) { a, ctx ->
            try { rename(a, ctx) }
            catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
            catch (e: ExecutionRefused) { ToolResult.error(e.message ?: "Exécution refusée") }
        },
    )

    private suspend fun rename(a: JsonObject, ctx: ToolContext): ToolResult {
        val w = workspaces.require(a.str("workspace")!!)
        val name = a.str("symbol")!!; val newName = a.str("new_name")!!
        val plan = intel.renamePlan(w, name, newName, a.str("scope"), DevTools.patchIdFor(ctx.idempotencyKey))
        val preview = patches.preview(w, plan.patch)
        if (!preview.ok) return ToolResult.error("Conflits : ${preview.conflicts.joinToString("; ")}")
        val untouched = untouchedMentions(w, name)
        if (a.bool("apply") != true) {
            return ToolResult.ok("Aperçu : $name → $newName, ${plan.occurrences} occurrence(s) dans ${plan.files.size} fichier(s) (${plan.files.joinToString()}).\n" +
                (if (untouched.isNotEmpty()) "Non modifiés (chaînes/commentaires, à vérifier) : ${untouched.joinToString()}\n" else "") + plan.diff, workspaceSource(w.workspaceId))
        }
        if (WorkspaceManager.trustOf(w) == WorkspaceTrust.READ_ONLY) return ToolResult.error("Projet en lecture seule")
        if (!workspaces.lock(w.workspaceId, ctx.taskId)) return ToolResult.error("Le projet est en cours de modification par une autre tâche.")
        val verify = a.str("verify") ?: "none"
        val staticBefore = intel.staticDiagnostics(w)
        val before = if (verify == "none") null else check(w, verify, ctx)
        val cs = try { patches.apply(w, plan.patch, ctx.taskId) } catch (e: PatchConflict) { return ToolResult.error("Conflit, rien n'a été modifié : ${e.message}") }
        val staticAfter = intel.staticDiagnostics(w)
        val after = if (verify == "none") null else check(w, verify, ctx)
        val newStatic = staticAfter.filter { d -> d.severity == "error" && staticBefore.none { it.message == d.message && it.file == d.file } }
        val regressed = newStatic.isNotEmpty() || (before != null && after != null && (before.ok && !after.ok || after.failing.any { it !in before.failing }))
        val rolledBack = regressed && a.bool("rollback_on_regression") != false && runCatching { patches.rollback(cs.changeSetId) }.isSuccess
        val text = buildString {
            append(if (rolledBack) "↩️ Renommage annulé (régression détectée)" else if (regressed) "⚠️ Renommage appliqué avec régression" else "✅ Renommage appliqué")
            append(" : $name → $newName, ${plan.occurrences} occurrence(s) dans ${cs.files.size} fichier(s) (${cs.files.joinToString()}), modification ${cs.changeSetId.take(8)}.\n")
            append("Diagnostics statiques : ${staticBefore.count { it.severity == "error" }} erreur(s) avant, ${staticAfter.count { it.severity == "error" }} après.\n")
            if (newStatic.isNotEmpty()) append("Nouveaux : \n").append(render(newStatic)).append('\n')
            if (before != null && after != null) append("Vérification ($verify) : avant ${before.summary} · après ${after.summary}\n")
            after?.failing?.filter { before?.failing?.contains(it) != true }?.takeIf { it.isNotEmpty() }?.let { append("Nouveaux échecs : ${it.joinToString()}\n") }
            if (untouched.isNotEmpty()) append("Mentions non modifiées (chaînes/commentaires, à vérifier) : ${untouched.joinToString()}\n")
            if (!rolledBack) append(cs.diff.take(12_000))
        }
        return ToolResult(!regressed, text, workspaceSource(w.workspaceId),
            data = notebookData(filesChanged = if (rolledBack) emptyList() else cs.files,
                testsRun = listOfNotNull(before?.let { "avant renommage → ${it.summary}" }, after?.let { "après renommage → ${it.summary}" })))
    }

    private data class Check(val ok: Boolean, val summary: String, val failing: Set<String>)

    private suspend fun check(w: WorkspaceEntity, verify: String, ctx: ToolContext): Check = when (verify) {
        "tests" -> builds.test(w, null, NetworkMode.DENY, ctx.taskId, retryFlaky = false).let { r ->
            Check(r.status == "passed", "${r.status} (${r.passed} ok, ${r.failed} ko)", r.failedTests.map { t -> listOfNotNull(t.suite, t.name).joinToString(".") }.toSet() +
                listOfNotNull(r.infraFailure?.let { "infra" }))
        }
        "build" -> builds.build(w, null, NetworkMode.DENY, ctx.taskId).let { r ->
            Check(r.status == "succeeded", "${r.status} (${r.diagnostics.count { it.severity == "error" }} erreur(s))",
                r.diagnostics.filter { it.severity == "error" }.map { "${it.file}:${it.message}" }.toSet())
        }
        else -> throw WorkspaceException("verify : none, tests ou build")
    }

    /** Mentions of the old name left in strings/comments: reported to the model, never rewritten. */
    private suspend fun untouchedMentions(w: WorkspaceEntity, name: String): List<String> {
        val re = Regex("(?<![\\w$])" + Regex.escape(name) + "(?![\\w$])")
        val fs = workspaces.fs(w)
        return intel.index(w).keys.flatMap { rel ->
            val lines = runCatching { fs.resolve(rel).readLines() }.getOrDefault(emptyList())
            lines.mapIndexedNotNull { i, raw ->
                val lang = io.github.artisanguillonrenov.cortana.core.dev.LexicalProvider.language(rel) ?: return@mapIndexedNotNull null
                val code = io.github.artisanguillonrenov.cortana.core.dev.LexicalProvider.stripCommentsAndStrings(raw, lang)
                if (re.containsMatchIn(raw) && re.findAll(raw).count() > re.findAll(code).count()) "$rel:${i + 1}" else null
            }
        }.take(20)
    }

    private fun render(d: List<Diagnostic>) = d.take(40).joinToString("\n") { "• ${it.severity} ${it.source}: ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""} ${it.message.take(200)}" }

    private fun read(cap: String, desc: String, schema: JsonObject, label: String, tags: List<String>, exec: suspend (WorkspaceEntity, JsonObject) -> String) = ToolDefinition(
        cap, desc, schema, Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = 20_000, label = label, tags = tags,
    ) { a, _ ->
        try { val w = workspaces.require(a.str("workspace")!!); ToolResult.ok(exec(w, a), workspaceSource(w.workspaceId)) }
        catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
    }
}
