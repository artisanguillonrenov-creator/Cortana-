package io.github.artisanguillonrenov.cortana.executors.dev

import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.core.dev.BuildService
import io.github.artisanguillonrenov.cortana.core.dev.DependencyService
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionRefused
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonObject

/** build.run / test.run / lint.run / dependency.inspect (doc 03 §8-11, §18). Builds and tests execute project code (≥ L2). */
class BuildTools(private val workspaces: WorkspaceManager, private val builds: BuildService, private val deps: DependencyService) {
    private val common = arrayOf(
        "workspace" to S.str("Projet"),
        "network" to S.str("Réseau pendant l'exécution (deny par défaut ; allow pour télécharger des dépendances)", listOf("deny", "allow")),
        "backend" to S.str("Moteur : auto (défaut), android-local ou identifiant de worker"),
    )

    fun tools(): List<ToolDefinition> = listOf(
        run("build.run", "Compile/empaquette le projet avec son système de build détecté (Gradle, Maven, npm, Python, Cargo, Go, CMake, Make). Les artefacts produits sont récupérés et vérifiés.",
            S.obj(*common, "target" to S.str("Cible/tâche optionnelle (ex. assembleRelease, build)"), required = listOf("workspace")), "Compiler le projet",
            listOf("compiler", "build", "apk", "empaqueter", "construire")) { a, ctx, net ->
            val w = workspaces.require(a.str("workspace")!!)
            val r = builds.build(w, a.str("target"), net, ctx.taskId, a.str("backend"))
            val text = buildString {
                append(if (r.status == "succeeded") "✅ Build réussi" else "❌ Build ${r.status}").append(" [${r.backend}] en ${r.durationMs} ms\n")
                r.diagnostics.filter { it.severity == "error" }.take(25).forEach { append("• ${it.source}: ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""} ${it.message.take(240)}\n") }
                if (r.artifacts.isNotEmpty()) append("Artefacts vérifiés : ").append(r.artifacts.joinToString { "${it.name} (${it.artifactId.take(8)}, sha256 ${it.sha256.take(12)}…)" }).append('\n')
                if (r.status != "succeeded") append("--- fin du journal ---\n").append(r.logTail.takeLast(3_000))
            }
            ToolResult(r.status == "succeeded", text, workspaceSource(w.workspaceId), data = notebookData(commandsRun = listOf("build ${a.str("target") ?: ""} → ${r.status}".trim())))
        },
        run("test.run", "Lance les tests (tous, ou filtrés par nom), analyse les rapports JUnit, distingue échec de test et échec d'infrastructure, signale les tests instables et compare au passage précédent.",
            S.obj(*common, "filter" to S.str("Filtre de nom de test (optionnel)"), required = listOf("workspace")), "Lancer les tests",
            listOf("tester", "tests", "junit", "verifier", "pytest")) { a, ctx, net ->
            val w = workspaces.require(a.str("workspace")!!)
            val r = builds.test(w, a.str("filter"), net, ctx.taskId, a.str("backend"))
            ToolResult(r.status == "passed", r.render(), workspaceSource(w.workspaceId),
                data = notebookData(testsRun = listOf("${a.str("filter") ?: "suite"} → ${r.status} (${r.passed} ok, ${r.failed} ko${if (r.flaky.isNotEmpty()) ", ${r.flaky.size} instable(s)" else ""})")))
        },
        run("lint.run", "Analyse statique / lint du projet avec l'outil détecté, diagnostics normalisés.",
            S.obj(*common, required = listOf("workspace")), "Analyser le code (lint)", listOf("lint", "analyse", "qualite", "diagnostics")) { a, ctx, net ->
            val w = workspaces.require(a.str("workspace")!!)
            val (r, diags) = builds.lint(w, net, ctx.taskId, a.str("backend"))
            ToolResult(r.ok, "Lint ${r.status} [${r.backend} · ${r.sandbox}] : ${diags.count { it.severity == "error" }} erreur(s), ${diags.count { it.severity == "warning" }} avertissement(s)\n" +
                diags.take(40).joinToString("\n") { "• ${it.severity} ${it.source}: ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""} ${it.message.take(200)}" }, workspaceSource(w.workspaceId))
        },
        ToolDefinition(
            "dependency.inspect", "Inventaire des dépendances (manifestes, verrous, versions multiples, versions non épinglées). Ne modifie rien.",
            S.obj("workspace" to S.str("Projet"), required = listOf("workspace")), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.DEV,
            label = "Inspecter les dépendances", tags = listOf("dependances", "versions", "librairies", "paquets"),
        ) { a, _ ->
            try { val w = workspaces.require(a.str("workspace")!!); ToolResult.ok(deps.inspect(w).render(), workspaceSource(w.workspaceId)) }
            catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur") }
        },
    )

    private fun run(
        cap: String, desc: String, schema: JsonObject, label: String, tags: List<String>,
        exec: suspend (JsonObject, ToolContext, NetworkMode) -> ToolResult,
    ) = ToolDefinition(
        cap, desc, schema, Risk.L2, SideEffect.EXTERNAL, Idempotency.NONE, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = 16_000, timeoutMs = 3_700_000,
        label = label, tags = tags, extraTraits = setOf(Trait.EXECUTES_CODE),
        riskClassifier = { a, _ ->
            val w = workspaces.find(a.str("workspace") ?: "") ?: return@ToolDefinition RiskAssessment(Risk.L2, deny = true, denyReason = "Projet introuvable")
            RiskAssessment(Risk.L2, listOfNotNull("Exécute le code du projet « ${w.name} » (${w.trust})", "réseau autorisé".takeIf { a.str("network") == "allow" }), targetDescription = w.name)
        },
    ) { a, ctx ->
        try { exec(a, ctx, if (a.str("network") == "allow") NetworkMode.ALLOW else NetworkMode.DENY) }
        catch (e: ExecutionRefused) { ToolResult.error(e.message ?: "Exécution refusée") }
        catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
    }
}
