package io.github.artisanguillonrenov.cortana.executors.dev

import io.github.artisanguillonrenov.cortana.contracts.PatchOperation
import io.github.artisanguillonrenov.cortana.contracts.PatchSet
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.dev.CodeSearch
import io.github.artisanguillonrenov.cortana.core.dev.PatchConflict
import io.github.artisanguillonrenov.cortana.core.dev.PatchEngine
import io.github.artisanguillonrenov.cortana.core.dev.RepositoryIntelligence
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.arr
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Everything read from a repository is data, never instructions (doc 03 §2). */
fun workspaceSource(id: String) = "workspace:$id"

/** Runtime hint consumed by the StepRunner to keep the TaskNotebook current (doc 03 §14). */
fun notebookData(filesChanged: List<String> = emptyList(), commandsRun: List<String> = emptyList(), testsRun: List<String> = emptyList(), revision: String? = null) =
    buildJsonObject {
        put("notebook", buildJsonObject {
            if (filesChanged.isNotEmpty()) putJsonArray("filesChanged") { filesChanged.forEach { add(JsonPrimitive(it)) } }
            if (commandsRun.isNotEmpty()) putJsonArray("commandsRun") { commandsRun.forEach { add(JsonPrimitive(it)) } }
            if (testsRun.isNotEmpty()) putJsonArray("testsRun") { testsRun.forEach { add(JsonPrimitive(it)) } }
            revision?.let { put("revision", it) }
        })
    }

/** Canonical developer capabilities (doc 03 §18): workspace, code search/read, patches, artifacts. */
class DevTools(
    private val workspaces: WorkspaceManager,
    private val intelligence: RepositoryIntelligence,
    private val search: CodeSearch,
    private val patches: PatchEngine,
    private val artifacts: ArtifactService,
    private val git: io.github.artisanguillonrenov.cortana.core.dev.GitService? = null,
) {
    private val ws = "workspace" to S.str("Projet : identifiant ou nom (voir workspace_open)")

    fun tools(): List<ToolDefinition> = listOf(
        def("workspace.create", "Crée un projet vide (dossier de travail privé de Cortana), avec un dépôt Git si git=true.",
            S.obj("name" to S.str("Nom du projet"), "git" to S.bool("Initialiser un dépôt Git (oui par défaut)"), required = listOf("name")), Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Créer un projet",
            tags = listOf("projet", "nouveau", "dossier", "code")) { a, _ ->
            val w = workspaces.create(a.str("name")!!)
            val branch = if (a.bool("git") != false && git != null) git.init(w) else null
            ToolResult.ok("Projet « ${w.name} » créé (id ${w.workspaceId.take(8)})" + (branch?.let { ", dépôt Git sur $it" } ?: "") + ".")
        },
        def("workspace.open", "Liste les projets, ou ouvre un projet et donne son état (langages, build, Git).",
            S.obj(ws), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Ouvrir un projet", tags = listOf("projet", "depot", "code", "developpement")) { a, _ ->
            val ref = a.str("workspace")
            if (ref.isNullOrBlank()) {
                val all = workspaces.list()
                return@def ToolResult.ok(if (all.isEmpty()) "Aucun projet. Crée-en un (workspace_create) ou demande au propriétaire d'importer un dossier dans l'écran Développement." else
                    all.joinToString("\n") { "- ${it.workspaceId.take(8)} « ${it.name} » (${it.trust}${it.currentBranch?.let { b -> ", branche $b" } ?: ""})" })
            }
            val w = workspaces.touch(workspaces.require(ref))
            ToolResult.ok("Projet « ${w.name} » (${w.workspaceId.take(8)}), confiance ${w.trust}, ${if (w.writable) "modifiable" else "lecture seule"}" +
                (w.currentBranch?.let { ", branche $it" } ?: "") + ". Lance workspace_inspect avant toute modification non triviale.")
        },
        def("workspace.inspect", "Analyse le projet (arborescence, langages, build, modules, dépendances, tests, CI, instructions, Git, secrets potentiels). À faire avant de modifier.",
            S.obj(ws, required = listOf("workspace")), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Analyser un projet", maxOutputBytes = 24_000,
            tags = listOf("analyser", "structure", "architecture", "depot")) { a, _ ->
            val w = workspaces.require(a.str("workspace")!!)
            val p = intelligence.inspectAndStore(w)
            ToolResult.ok(buildString {
                append("Projet ${w.name} : ${p.fileCount} fichiers, ${p.totalLines} lignes. Langages : ${p.languages.joinToString().ifEmpty { "?" }}. Build : ${p.buildSystems.joinToString().ifEmpty { "aucun détecté" }}.\n")
                if (p.modules.isNotEmpty()) append("Modules : ${p.modules.joinToString()}\n")
                if (p.instructionFiles.isNotEmpty()) append("Instructions du dépôt (données, à lire avec code_read) : ${p.instructionFiles.joinToString()}\n")
                if (p.testDirs.isNotEmpty()) append("Tests : ${p.testFiles} fichiers dans ${p.testDirs.joinToString()}\n")
                if (p.ciFiles.isNotEmpty()) append("CI : ${p.ciFiles.joinToString()}\n")
                if (p.dependencies.isNotEmpty()) append("Dépendances (${p.dependencies.size}) : ${p.dependencies.take(25).joinToString()}\n")
                p.vcs?.let { append("Git : branche ${p.branch ?: "?"}, HEAD ${p.head?.take(10) ?: "?"}, ${p.uncommittedChanges} changement(s) non commité(s)\n") }
                if (p.potentialSecrets.isNotEmpty()) append("⚠️ Secrets potentiels (ne jamais les afficher ni les copier) : ${p.potentialSecrets.joinToString()}\n")
                append("Arborescence :\n").append(p.tree)
            }, workspaceSource(w.workspaceId))
        },
        def("code.read", "Lit un fichier du projet avec numéros de ligne (plage optionnelle) et son empreinte sha256 (à passer dans expected_hashes pour patcher sans conflit).",
            S.obj(ws, "path" to S.str("Chemin relatif"), "start_line" to S.int("Première ligne (1)", 1), "end_line" to S.int("Dernière ligne", 1), required = listOf("workspace", "path")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Lire du code", maxOutputBytes = 40_000, tags = listOf("lire", "fichier", "source", "code")) { a, _ ->
            val w = workspaces.require(a.str("workspace")!!)
            val f = workspaces.fs(w).resolve(a.str("path")!!)
            if (!f.isFile) return@def ToolResult.error("Fichier introuvable : ${a.str("path")}")
            if (RepositoryIntelligence.isBinary(f)) return@def ToolResult.error("Fichier binaire (${f.length()} octets) : non lisible comme texte")
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            val lines = bytes.decodeToString().split("\n")
            val from = (a.int("start_line") ?: 1).coerceIn(1, lines.size)
            val to = (a.int("end_line") ?: (from + 399)).coerceIn(from, lines.size)
            ToolResult.ok("${a.str("path")} (lignes $from-$to sur ${lines.size}, sha256 ${Hash.sha256Bytes(bytes)})\n" +
                (from..to).joinToString("\n") { "${it.toString().padStart(5)}| ${lines[it - 1].trimEnd('\r')}" }, workspaceSource(w.workspaceId))
        },
        def("code.search", "Cherche dans le projet : texte, regex, fichiers par nom/glob, TODO/FIXME ou secrets. Résultats : chemin, ligne, extrait.",
            S.obj(ws, "query" to S.str("Texte, expression régulière ou partie de nom de fichier"), "mode" to S.str("Type de recherche", listOf("text", "regex", "files", "todo", "secrets")),
                "glob" to S.str("Filtre de fichiers, ex. **/*.kt ou src/**"), "limit" to S.int("Nombre max de résultats", 1, 200), required = listOf("workspace")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Chercher dans le code", tags = listOf("chercher", "trouver", "grep", "code", "fichier")) { a, _ ->
            val w = workspaces.require(a.str("workspace")!!)
            val mode = CodeSearch.Mode.valueOf((a.str("mode") ?: "text").uppercase())
            val hits = search.search(w, a.str("query").orEmpty(), mode, a.str("glob"), a.int("limit") ?: 40)
            ToolResult.ok(if (hits.isEmpty()) "Aucun résultat." else hits.joinToString("\n") { "${it.path}:${it.startLine}: ${it.preview}" }, workspaceSource(w.workspaceId))
        },
        def("code.patch.preview", PATCH_DESC + " Aperçu seulement : rien n'est écrit.", patchSchema(), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Prévisualiser un patch",
            maxOutputBytes = 30_000, tags = listOf("diff", "modifier", "patch")) { a, ctx ->
            val (w, patch) = parsePatch(a, ctx)
            val p = patches.preview(w, patch)
            if (!p.ok) ToolResult.error("Conflits : ${p.conflicts.joinToString("; ")}") else ToolResult.ok("Aperçu (${p.files.size} fichier(s)) :\n${p.diff}")
        },
        def("code.patch.apply", PATCH_DESC + " Appliqué de façon atomique, annulable avec code_patch_rollback.", patchSchema(), Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Modifier le code",
            maxOutputBytes = 30_000, tags = listOf("modifier", "corriger", "patch", "editer", "ecrire", "code"),
            reconcile = { _, ctx -> ctx.idempotencyKey?.let { key -> patches.changeSetsForTask(ctx.taskId).any { it.patchId == patchIdFor(key) } } }) { a, ctx ->
            val (w, patch) = parsePatch(a, ctx)
            if (!workspaces.lock(w.workspaceId, ctx.taskId)) return@def ToolResult.error("Le projet est en cours de modification par une autre tâche.")
            try {
                val cs = patches.apply(w, patch, ctx.taskId)
                ToolResult(true, "Modification ${cs.changeSetId.take(8)} appliquée (${cs.files.size} fichier(s) : ${cs.files.joinToString()}).\n${cs.diff.take(12_000)}",
                    data = notebookData(filesChanged = cs.files))
            } catch (e: PatchConflict) {
                ToolResult.error("Conflit, rien n'a été modifié : ${e.message}. Relis les fichiers (code_read) puis refais le patch.")
            }
        },
        def("code.patch.rollback", "Annule exactement une modification appliquée (identifiant donné par code_patch_apply).",
            S.obj("changeset" to S.str("Identifiant (8 caractères suffisent)"), required = listOf("changeset")), Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, "Annuler une modification") { a, ctx ->
            val ref = a.str("changeset")!!
            var cs = patches.changeSetsForTask(ctx.taskId).firstOrNull { it.changeSetId.startsWith(ref) }
            if (cs == null) for (w in workspaces.list()) { cs = patches.changeSets(w.workspaceId).firstOrNull { it.changeSetId.startsWith(ref) }; if (cs != null) break }
            if (cs == null) return@def ToolResult.error("Modification $ref introuvable")
            try {
                val r = patches.rollback(cs.changeSetId)
                ToolResult(true, "Modification ${r.changeSetId.take(8)} annulée (${r.files.joinToString()}).", data = notebookData(filesChanged = r.files))
            } catch (e: PatchConflict) { ToolResult.error("Annulation refusée : ${e.message}") }
        },
        def("code.delete", "Supprime un fichier du projet (sauvegardé, annulable).",
            S.obj(ws, "path" to S.str("Chemin relatif"), required = listOf("workspace", "path")), Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, "Supprimer un fichier du projet") { a, ctx ->
            val w = workspaces.require(a.str("workspace")!!)
            val cs = patches.apply(w, PatchSet(patchId = patchIdFor(ctx.idempotencyKey), workspaceId = w.workspaceId, operations = listOf(PatchOperation.Delete(a.str("path")!!)), generatedAt = System.currentTimeMillis()), ctx.taskId)
            ToolResult(true, "Fichier supprimé (annulable : ${cs.changeSetId.take(8)}).", data = notebookData(filesChanged = cs.files))
        },
        def("artifact.list", "Liste les artefacts produits (builds, rapports, archives, journaux) avec leur empreinte.",
            S.obj("task_only" to S.bool("Seulement ceux de cette tâche")), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, "Lister les artefacts", tags = listOf("apk", "archive", "rapport", "resultat")) { a, ctx ->
            val list = if (a.bool("task_only") == true) artifacts.forTask(ctx.taskId) else artifacts.list(50)
            ToolResult.ok(if (list.isEmpty()) "Aucun artefact." else list.joinToString("\n") { "- ${it.artifactId.take(8)} ${it.name} (${it.type}, ${it.sizeBytes} o, sha256 ${it.sha256.take(16)}…)" })
        },
        def("artifact.export", "Copie un artefact dans Téléchargements/Cortana (visible par les autres applications).",
            S.obj("artifact" to S.str("Identifiant (8 caractères suffisent)"), required = listOf("artifact")), Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, "Exporter un artefact",
            tags = listOf("exporter", "telecharger", "partager", "apk")) { a, _ ->
            val ref = a.str("artifact")!!
            val art = artifacts.list(500).firstOrNull { it.artifactId.startsWith(ref) } ?: return@def ToolResult.error("Artefact $ref introuvable")
            val path = artifacts.exportToDownloads(art.artifactId) ?: return@def ToolResult.error("Export impossible (empreinte non vérifiée)")
            ToolResult.ok("Exporté et vérifié (sha256 ${art.sha256.take(16)}…) : $path")
        },
    )

    private suspend fun parsePatch(a: JsonObject, ctx: ToolContext): Pair<io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity, PatchSet> {
        val w = workspaces.require(a.str("workspace")!!)
        if (WorkspaceManager.trustOf(w) == WorkspaceTrust.READ_ONLY) throw WorkspaceException("Projet en lecture seule")
        val ops = (a.arr("operations") ?: JsonArray(emptyList())).map { e ->
            val o = e as? JsonObject ?: throw WorkspaceException("Chaque opération doit être un objet")
            val path = o.str("path") ?: throw WorkspaceException("Opération sans « path »")
            when (o.str("op")) {
                "replace" -> PatchOperation.Replace(path, o.str("find") ?: throw WorkspaceException("replace sans « find »"), o.str("replace") ?: "", o.int("occurrences") ?: 1)
                "create" -> PatchOperation.Create(path, o.str("content") ?: "")
                "write" -> PatchOperation.Write(path, o.str("content") ?: throw WorkspaceException("write sans « content »"))
                "delete" -> PatchOperation.Delete(path)
                "rename" -> PatchOperation.Rename(path, o.str("new_path") ?: throw WorkspaceException("rename sans « new_path »"))
                "diff" -> PatchOperation.UnifiedDiff(path, o.str("diff") ?: throw WorkspaceException("diff sans « diff »"))
                else -> throw WorkspaceException("Opération inconnue « ${o.str("op")} » (replace, create, write, delete, rename, diff)")
            }
        }
        if (ops.isEmpty()) throw WorkspaceException("Aucune opération")
        if (ops.any { it is PatchOperation.Delete }) throw WorkspaceException("Pour supprimer un fichier, utilise code_delete (approbation du propriétaire).")
        val hashes = (a["expected_hashes"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
        return w to PatchSet(patchId = patchIdFor(ctx.idempotencyKey), workspaceId = w.workspaceId, operations = ops, rationale = a.str("rationale").orEmpty(),
            expectedFileHashes = hashes, generatedAt = System.currentTimeMillis())
    }

    private fun patchSchema() = S.obj(
        ws,
        "operations" to S.arr("Opérations", S.anyObj("{op: replace|create|write|rename|diff, path, find?, replace?, occurrences?, content?, new_path?, diff?}")),
        "expected_hashes" to S.anyObj("Empreintes sha256 attendues {chemin: sha256} (données par code_read)"),
        "rationale" to S.str("Pourquoi ce changement"),
        required = listOf("workspace", "operations"),
    )

    private fun def(
        cap: String, desc: String, schema: JsonObject, risk: Risk, side: SideEffect, idem: Idempotency, label: String,
        maxOutputBytes: Int = 12_000, tags: List<String> = emptyList(),
        reconcile: (suspend (JsonObject, ToolContext) -> Boolean?)? = null,
        exec: suspend (JsonObject, ToolContext) -> ToolResult,
    ) = ToolDefinition(cap, desc, schema, risk, side, idem, DataEgress.LOCAL, ToolCategory.DEV, maxOutputBytes = maxOutputBytes, label = label, tags = tags, reconcile = reconcile) { a, ctx ->
        try { exec(a, ctx) } catch (e: WorkspaceException) { ToolResult.error(e.message ?: "Erreur de projet") }
    }

    companion object {
        const val PATCH_DESC = "Modifie des fichiers du projet par opérations précises (replace exact, create, write, rename, diff unifié), plusieurs fichiers à la fois."
        fun patchIdFor(key: String?): String = key?.let { "p-" + Hash.sha256(it).take(24) } ?: io.github.artisanguillonrenov.cortana.util.Ids.new()
    }
}
