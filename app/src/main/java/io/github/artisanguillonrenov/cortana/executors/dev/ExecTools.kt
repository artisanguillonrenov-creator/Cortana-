package io.github.artisanguillonrenov.cortana.executors.dev

import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionRefused
import io.github.artisanguillonrenov.cortana.core.exec.ProcessSpec
import io.github.artisanguillonrenov.cortana.core.exec.SandboxManager
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str

/** `exec.run` (doc 03 §16): a command in a project, on the backend the SandboxManager allows. Always "executes code" (≥ L2). */
class ExecTools(
    private val workspaces: WorkspaceManager,
    private val sandbox: SandboxManager,
    private val artifacts: ArtifactService,
    private val status: (String) -> Unit = {},
) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "exec.run",
            "Exécute une commande shell dans un projet (délai, arrêt, journaux). Projet non fiable : uniquement dans un bac à sable isolé (worker). Préférer build_run/test_run quand ils existent.",
            S.obj(
                "workspace" to S.str("Projet"), "command" to S.str("Commande shell"), "cwd" to S.str("Dossier relatif (.)"),
                "timeout_s" to S.int("Délai en secondes (120)", 1, 3600), "network" to S.str("Réseau", listOf("deny", "allow")),
                "backend" to S.str("Moteur : auto (défaut), android-local ou identifiant de worker"),
                "artifacts" to S.arr("Fichiers produits à récupérer (globs, ex. build/outputs/**/*.apk)", S.str("glob")), required = listOf("workspace", "command"),
            ),
            Risk.L2, SideEffect.EXTERNAL, Idempotency.NONE, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = 16_000, timeoutMs = 3_700_000,
            label = "Exécuter une commande", tags = listOf("shell", "commande", "script", "terminal", "executer"),
            extraTraits = setOf(Trait.EXECUTES_CODE),
            riskClassifier = { a, _ ->
                val w = workspaces.find(a.str("workspace") ?: "") ?: return@ToolDefinition RiskAssessment(Risk.L2, deny = true, denyReason = "Projet introuvable")
                val git = GitCommandGuard.assess(a.str("command").orEmpty())
                if (git is GitCommandGuard.Verdict.Deny) return@ToolDefinition RiskAssessment(Risk.L3, deny = true, denyReason = git.reason)
                val choice = runCatching { sandbox.choose(w, spec(a), a.str("backend")) }.getOrElse { return@ToolDefinition RiskAssessment(Risk.L2, deny = true, denyReason = it.message) }
                val destructive = git as? GitCommandGuard.Verdict.Destructive
                RiskAssessment(if (destructive != null) Risk.L3 else Risk.L2,
                    listOfNotNull("Exécution sur ${choice.backend.label} (${choice.spec.sandbox.wire}, réseau ${choice.spec.network.name.lowercase()})", choice.note, destructive?.reason),
                    targetDescription = "${w.name} : ${a.str("command")!!.take(200)}")
            },
        ) { a, ctx ->
            try {
                val w = workspaces.require(a.str("workspace")!!)
                val choice = sandbox.choose(w, spec(a), a.str("backend"))
                val r = choice.backend.run(w, choice.spec.copy(taskId = ctx.taskId)) { line -> status(line.take(120)) }
                val full = r.render(Int.MAX_VALUE)
                val log = if (full.length > 12_000) artifacts.registerText(full, "log", "exec-${System.currentTimeMillis()}.log", ctx.taskId, "exec.run") else null
                ToolResult(
                    ok = r.ok,
                    text = (if (!r.ok) "ÉCHEC — " else "") + r.render() + (choice.note?.let { "\n$it" } ?: "") + (log?.let { "\nJournal complet : artefact ${it.artifactId.take(8)}" } ?: "") +
                        (if (r.artifactIds.isNotEmpty()) "\nArtefacts vérifiés (sha256) : " + r.artifactIds.joinToString { it.take(8) } else ""),
                    untrustedSource = workspaceSource(w.workspaceId),
                    data = notebookData(commandsRun = listOf("${a.str("command")!!.take(160)} → ${r.status}${r.exitCode?.let { " ($it)" } ?: ""}")),
                )
            } catch (e: ExecutionRefused) { ToolResult.error(e.message ?: "Exécution refusée") } catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
        },
    )

    private fun spec(a: kotlinx.serialization.json.JsonObject) = ProcessSpec(
        command = a.str("command")!!, cwd = a.str("cwd") ?: ".", timeoutMs = (a.int("timeout_s") ?: 120) * 1000L,
        network = if (a.str("network") == "allow") NetworkMode.ALLOW else NetworkMode.DENY,
        artifactGlobs = (a["artifacts"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList(),
    )
}

/**
 * Git through the shell must not bypass the Git policy (doc 03 E2E-CODE-006): pushes and history
 * rewrites of remotes are refused (repo_push applies the branch protection), destructive local
 * commands (reset --hard, clean -f, branch -D, discarding changes, rebase…) need the owner's
 * biometric confirmation.
 */
object GitCommandGuard {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Deny(val reason: String) : Verdict
        data class Destructive(val reason: String) : Verdict
    }

    private val git = Regex("""(?:^|[;&|(`$\s])git\s+(?:-[Cc]\s+\S+\s+|--\S+\s+)*([a-z-]+)((?:\s+[^;&|]*)?)""")

    fun assess(command: String): Verdict {
        var result: Verdict = Verdict.Ok
        for (m in git.findAll(command)) {
            val sub = m.groupValues[1]; val args = m.groupValues[2]
            when {
                sub == "push" -> return Verdict.Deny("« git push » par le shell est refusé : utilise repo_push (jamais forcé, branches protégées)")
                sub == "filter-branch" || sub == "filter-repo" || (sub == "update-ref" && Regex("""(^|\s)-d\b""").containsMatchIn(args)) ->
                    return Verdict.Deny("Réécriture de l'historique refusée : « git $sub »")
                sub == "reset" && Regex("""--hard|--merge|--keep""").containsMatchIn(args) -> result = Verdict.Destructive("Commande Git destructrice : git reset ${args.trim().take(60)}")
                sub == "clean" && Regex("""(^|\s)-\w*f""").containsMatchIn(args) -> result = Verdict.Destructive("Commande Git destructrice : git clean (supprime des fichiers non suivis)")
                sub == "branch" && Regex("""(^|\s)(-D|--delete\s+--force|-d\s+-f)\b""").containsMatchIn(args) -> result = Verdict.Destructive("Commande Git destructrice : suppression forcée de branche")
                (sub == "checkout" || sub == "restore") && Regex("""(^|\s)(--\s|\.|--staged|--worktree|-f\b)""").containsMatchIn(args) -> result = Verdict.Destructive("Commande Git destructrice : abandon de modifications locales")
                sub == "rebase" || sub == "reflog" && args.contains("expire") || sub == "gc" && args.contains("prune") || sub == "stash" && Regex("""\b(drop|clear)\b""").containsMatchIn(args) ->
                    result = Verdict.Destructive("Commande Git destructrice : git $sub")
            }
        }
        return result
    }
}
