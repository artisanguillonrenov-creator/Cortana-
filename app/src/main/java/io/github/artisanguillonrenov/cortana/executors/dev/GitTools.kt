package io.github.artisanguillonrenov.cortana.executors.dev

import io.github.artisanguillonrenov.cortana.core.dev.GitRefused
import io.github.artisanguillonrenov.cortana.core.dev.GitService
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.tools.PolicyContext
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import io.github.artisanguillonrenov.cortana.util.arr
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.net.URI

/** Git capabilities (doc 03 §7, §18). Reads are L0; local mutations L1; network/protected operations are raised by the policy. */
class GitTools(private val git: GitService, private val workspaces: WorkspaceManager, private val settings: SettingsRepository) {
    private val ws = "workspace" to S.str("Projet : identifiant ou nom")

    fun tools(): List<ToolDefinition> = listOf(
        def("repo.init", "Initialise un dépôt Git dans le projet.", S.obj(ws, "branch" to S.str("Branche initiale (main)"), required = listOf("workspace")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, "Initialiser Git") { a, _ ->
            val w = ws(a); ToolResult.ok("Dépôt Git initialisé (branche ${git.init(w, a.str("branch") ?: "main")}).")
        },
        def("repo.clone", "Clone un dépôt Git https dans un nouveau projet (non fiable par défaut).",
            S.obj("url" to S.str("URL https du dépôt"), "name" to S.str("Nom du projet"), "branch" to S.str("Branche à récupérer"), required = listOf("url")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Cloner un dépôt", egress = DataEgress.EXTERNAL,
            destinationOf = { a -> a.str("url")?.let { runCatching { URI(it).host }.getOrNull() } },
            classifier = { a, _ -> urlGuard(a.str("url")) }, tags = listOf("github", "gitlab", "telecharger", "depot")) { a, _ ->
            val w = git.clone(a.str("url")!!, a.str("name"), a.str("branch"))
            ToolResult.ok("Dépôt cloné dans le projet « ${w.name} » (${w.workspaceId.take(8)}), branche ${w.currentBranch}. Lance workspace_inspect avant de modifier.")
        },
        def("repo.status", "État Git : branche, avance/retard, fichiers modifiés, indexés, non suivis, conflits.", S.obj(ws, required = listOf("workspace")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "État Git") { a, _ -> ToolResult.ok(git.status(ws(a)).render(), workspaceSource(ws(a).workspaceId)) },
        def("repo.diff", "Diff Git : copie de travail vs HEAD (défaut), index (staged=true) ou entre deux révisions (from/to).",
            S.obj(ws, "staged" to S.bool("Diff de l'index"), "path" to S.str("Limiter à un chemin"), "from" to S.str("Révision de départ"), "to" to S.str("Révision d'arrivée"), required = listOf("workspace")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Diff Git", maxOutputBytes = 60_000) { a, _ ->
            val w = ws(a)
            ToolResult.ok(git.diff(w, a.bool("staged") == true, a.str("path"), a.str("from"), a.str("to")).ifBlank { "Aucune différence." }, workspaceSource(w.workspaceId))
        },
        def("repo.log", "Historique des commits.", S.obj(ws, "max" to S.int("Nombre (20)", 1, 200), "path" to S.str("Limiter à un chemin"), required = listOf("workspace")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Historique Git") { a, _ ->
            val w = ws(a)
            val log = git.log(w, a.int("max") ?: 20, a.str("path"))
            ToolResult.ok(if (log.isEmpty()) "Aucun commit." else log.joinToString("\n") { "${it.id.take(10)} ${TimeFmt.short(it.time)} ${it.author} — ${it.message.lineSequence().first().take(120)}" }, workspaceSource(w.workspaceId))
        },
        def("repo.show", "Détail d'un commit (message et diff).", S.obj(ws, "rev" to S.str("Commit, branche ou tag"), required = listOf("workspace", "rev")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Afficher un commit", maxOutputBytes = 60_000) { a, _ -> ToolResult.ok(git.show(ws(a), a.str("rev")!!), workspaceSource(ws(a).workspaceId)) },
        def("repo.branches", "Branches locales et distantes, tags, dépôts distants et worktrees liés.", S.obj(ws, required = listOf("workspace")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Branches Git") { a, _ ->
            val w = ws(a)
            val (local, remote) = git.branches(w)
            ToolResult.ok("Branche courante : ${git.status(w).branch}\nLocales : ${local.joinToString()}\nDistantes : ${remote.joinToString().ifEmpty { "aucune" }}\n" +
                "Tags : ${git.tags(w).joinToString().ifEmpty { "aucun" }}\nDistants : ${git.remotes(w).entries.joinToString { "${it.key} → ${it.value}" }.ifEmpty { "aucun" }}\n" +
                "Worktrees : ${git.worktrees(w).joinToString { "${it.name} (${it.workspaceId.take(8)})" }.ifEmpty { "aucun" }}", workspaceSource(w.workspaceId))
        },
        def("repo.blame", "Auteur et commit de chaque ligne d'un fichier.", S.obj(ws, "path" to S.str("Chemin"), required = listOf("workspace", "path")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Blame Git", maxOutputBytes = 40_000) { a, _ -> ToolResult.ok(git.blame(ws(a), a.str("path")!!), workspaceSource(ws(a).workspaceId)) },
        def("repo.branch.create", "Crée une branche (et s'y place si checkout=true). Préférer une branche dédiée pour tout changement.",
            S.obj(ws, "name" to S.str("Nom, ex. cortana/corrige-add"), "checkout" to S.bool("S'y placer"), "start" to S.str("Point de départ (HEAD)"), required = listOf("workspace", "name")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, "Créer une branche") { a, _ ->
            ToolResult.ok("Branche ${git.createBranch(ws(a), a.str("name")!!, a.bool("checkout") != false, a.str("start"))} créée.")
        },
        def("repo.checkout", "Change de branche (refusé si des modifications locales seraient écrasées).", S.obj(ws, "branch" to S.str("Branche"), required = listOf("workspace", "branch")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, "Changer de branche") { a, _ -> git.switch(ws(a), a.str("branch")!!); ToolResult.ok("Sur la branche ${a.str("branch")}.") },
        def("repo.worktree.create", "Crée une copie de travail isolée du projet sur une nouvelle branche (nouveau projet lié) pour travailler sans toucher la branche principale.",
            S.obj(ws, "branch" to S.str("Nouvelle branche, ex. cortana/tache"), required = listOf("workspace", "branch")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Créer un worktree") { a, _ ->
            val child = git.createWorktree(ws(a), a.str("branch")!!)
            ToolResult.ok("Worktree « ${child.name} » (${child.workspaceId.take(8)}) sur la branche ${child.currentBranch}. Travaille dans ce projet ; repo_push l'y renverra localement.")
        },
        def("repo.commit", "Indexe (tout ou `paths`) et crée un commit local. Jamais d'amend.",
            S.obj(ws, "message" to S.str("Message de commit"), "paths" to S.arr("Chemins à inclure (défaut : tout)", S.str("chemin")), required = listOf("workspace", "message")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Commit local",
            reconcile = { a, _ -> runCatching { git.log(workspaces.require(a.str("workspace")!!), 1).firstOrNull()?.message == a.str("message")!!.trim() }.getOrNull() }) { a, _ ->
            val w = ws(a)
            val paths = a.arr("paths")?.mapNotNull { (it as? JsonPrimitive)?.content }
            val id = git.commit(w, a.str("message")!!, paths)
            ToolResult(true, "Commit ${id.take(10)} sur ${git.status(w).branch}.", data = notebookData(revision = id))
        },
        def("repo.merge", "Fusionne une branche dans la branche courante (localement). Vers une branche protégée : confirmation par empreinte du propriétaire.",
            S.obj(ws, "branch" to S.str("Branche à fusionner"), required = listOf("workspace", "branch")),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, "Fusionner une branche",
            classifier = { a, _ ->
                val w = workspaces.find(a.str("workspace") ?: "") ?: return@def null
                val target = runCatching { git.status(w).branch }.getOrNull()
                if (target != null && target in protectedSet()) RiskAssessment(Risk.L3, listOf("Fusion dans la branche protégée « $target »"), targetDescription = "${a.str("branch")} → $target") else null
            }) { a, ctx ->
            ToolResult.ok("Fusion : ${git.merge(ws(a), a.str("branch")!!, ownerInstructed = ctx.approvedRisk == Risk.L3)}")
        },
        def("repo.revert", "Crée un commit qui annule un commit précédent.", S.obj(ws, "commit" to S.str("Commit à annuler"), required = listOf("workspace", "commit")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Revert") { a, _ -> ToolResult.ok("Commit d'annulation ${git.revert(ws(a), a.str("commit")!!).take(10)}.") },
        def("repo.cherry_pick", "Applique un commit d'une autre branche sur la branche courante.", S.obj(ws, "commit" to S.str("Commit"), required = listOf("workspace", "commit")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Cherry-pick") { a, _ -> ToolResult.ok("Commit ${git.cherryPick(ws(a), a.str("commit")!!).take(10)} appliqué.") },
        def("repo.fetch", "Récupère les nouveautés du dépôt distant (sans rien modifier localement).", S.obj(ws, "remote" to S.str("Distant (origin)"), required = listOf("workspace")),
            Risk.L1, SideEffect.NONE, Idempotency.INTRINSIC, "Fetch", egress = DataEgress.EXTERNAL,
            destinationOf = { a -> "git:" + (a.str("remote") ?: "origin") }) { a, _ -> ToolResult.ok(git.fetch(ws(a), a.str("remote") ?: "origin")) },
        def("repo.pull", "Met à jour la branche depuis le distant avec une stratégie explicite (ff-only par défaut).",
            S.obj(ws, "strategy" to S.str("Stratégie", listOf("ff-only", "merge", "rebase")), "remote" to S.str("Distant (origin)"), required = listOf("workspace")),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, "Pull", egress = DataEgress.EXTERNAL) { a, _ ->
            ToolResult.ok(git.pull(ws(a), a.str("strategy") ?: "ff-only", a.str("remote") ?: "origin"))
        },
        def("repo.push", "Pousse une branche (jamais de force). Vers un dépôt distant : autorisation par empreinte du propriétaire ; vers le projet parent d'un worktree : local.",
            S.obj(ws, "remote" to S.str("Distant (origin)"), "branch" to S.str("Branche (courante)"), required = listOf("workspace")),
            Risk.L1, SideEffect.EXTERNAL, Idempotency.KEYED, "Pousser une branche", egress = DataEgress.EXTERNAL,
            destinationOf = { a -> "git:" + (a.str("remote") ?: "origin") },
            classifier = { a, _ -> pushRisk(a) },
            reconcile = { a, _ -> runCatching { git.pushed(workspaces.require(a.str("workspace")!!), a.str("remote") ?: "origin", a.str("branch")) }.getOrNull() },
            extraTraits = setOf(Trait.USER_VISIBLE_TO_THIRD_PARTY)) { a, _ ->
            ToolResult.ok(git.push(ws(a), a.str("remote") ?: "origin", a.str("branch")))
        },
    )

    /** Local pushes (worktree → parent) stay L1; anything that leaves the tablet is L3 with the exact plan shown. */
    private suspend fun pushRisk(a: JsonObject): RiskAssessment? {
        val w = workspaces.find(a.str("workspace") ?: "") ?: return RiskAssessment(Risk.L2, deny = true, denyReason = "Projet introuvable")
        val plan = runCatching { git.pushPlan(w, a.str("remote") ?: "origin", a.str("branch")) }.getOrElse { return RiskAssessment(Risk.L2, deny = true, denyReason = it.message) }
        val target = "${plan.url} · branche ${plan.branch} · ${plan.commits} commit(s)"
        if (plan.local) return RiskAssessment(if (plan.protectedTarget) Risk.L3 else Risk.L1, if (plan.protectedTarget) listOf("Branche protégée ${plan.branch}") else emptyList(), targetDescription = target)
        return RiskAssessment(Risk.L3, listOfNotNull("Publication vers un dépôt distant", "Branche protégée ${plan.branch}".takeIf { plan.protectedTarget }), targetDescription = target)
    }

    private fun urlGuard(url: String?): RiskAssessment? {
        val u = runCatching { URI(url ?: "") }.getOrNull()
        if (u == null || u.scheme != "https" || u.host.isNullOrBlank()) return RiskAssessment(Risk.L1, deny = true, denyReason = "URL https requise")
        if (!settings.current.allowPrivateNetworkFetch) {
            val private = runCatching { InetAddress.getAllByName(u.host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress } }.getOrDefault(false)
            if (private) return RiskAssessment(Risk.L2, deny = true, denyReason = "Adresse du réseau local refusée (autorisez le réseau local dans Réglages)")
        }
        return null
    }

    private fun protectedSet(): Set<String> = settings.current.protectedBranches.toSet()

    private suspend fun ws(a: JsonObject) = workspaces.require(a.str("workspace") ?: throw WorkspaceException("Projet requis"))

    private fun def(
        cap: String, desc: String, schema: JsonObject, risk: Risk, side: SideEffect, idem: Idempotency, label: String,
        maxOutputBytes: Int = 16_000, egress: DataEgress = DataEgress.LOCAL, destinationOf: ((JsonObject) -> String?)? = null,
        classifier: (suspend (JsonObject, PolicyContext) -> RiskAssessment?)? = null, tags: List<String> = emptyList(),
        reconcile: (suspend (JsonObject, ToolContext) -> Boolean?)? = null, extraTraits: Set<Trait> = emptySet(),
        exec: suspend (JsonObject, ToolContext) -> ToolResult,
    ) = ToolDefinition(
        cap, desc, schema, risk, side, idem, egress, ToolCategory.DEV, maxOutputBytes = maxOutputBytes, label = label, destinationOf = destinationOf,
        riskClassifier = classifier, tags = tags + listOf("git"), reconcile = reconcile, extraTraits = extraTraits,
    ) { a, ctx ->
        try { exec(a, ctx) } catch (e: GitRefused) { ToolResult.error(e.message ?: "Refusé") } catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
        catch (e: org.eclipse.jgit.api.errors.GitAPIException) { ToolResult.error("Git : ${GitService.sanitize(e)}") }
    }
}
