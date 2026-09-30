package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.ChangeSet
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.PatchOperation
import io.github.artisanguillonrenov.cortana.contracts.PatchSet
import io.github.artisanguillonrenov.cortana.core.memory.ChangeSetEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PatchEngine (doc 03 §6): never rewrites a whole file by default. Every PatchSet is computed in
 * memory first (dry-run/preview with conflict detection and hash preconditions), then applied
 * atomically across files (backups outside the workspace, temp files + rename, post-write hash
 * check, automatic restore on failure) and recorded as an auditable ChangeSet that can be rolled
 * back exactly. Line endings (LF/CRLF) and a UTF-8 BOM are preserved.
 */
class PatchEngine(
    private val db: CortanaDatabase,
    private val workspaces: WorkspaceManager,
    private val audit: AuditLog,
    private val backupRoot: File,
) {
    data class FileChange(val path: String, val before: String?, val after: String?, val renamedFrom: String? = null)
    data class Preview(val ok: Boolean, val conflicts: List<String>, val changes: List<FileChange>, val diff: String) {
        val files get() = changes.map { it.path }
    }

    private val dao get() = db.dev()

    suspend fun preview(w: WorkspaceEntity, patch: PatchSet): Preview = withContext(Dispatchers.IO) { compute(w, patch) }

    suspend fun apply(w: WorkspaceEntity, patch: PatchSet, taskId: String?): ChangeSet = withContext(Dispatchers.IO) {
        if (!w.writable || WorkspaceManager.trustOf(w) == io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust.READ_ONLY) throw WorkspaceException("Projet en lecture seule")
        val p = compute(w, patch)
        if (!p.ok) throw PatchConflict(p.conflicts.joinToString("; "))
        val fs = workspaces.fs(w)
        val csId = Ids.new()
        val backup = File(backupRoot, "${w.workspaceId}/$csId").apply { mkdirs() }
        val touched = p.changes.flatMap { listOfNotNull(it.path, it.renamedFrom) }.distinct()
        val before = touched.associateWith { path -> fs.resolve(path).takeIf { it.isFile }?.let { Hash.sha256Bytes(it.readBytes()) } }
        // 1. back up every file we are about to touch
        for (path in touched) {
            val f = fs.resolve(path)
            if (f.isFile) f.copyTo(File(backup, path), overwrite = true)
        }
        try {
            // 2. write new contents through temp files, then rename (per file atomic)
            for (c in p.changes) {
                c.renamedFrom?.let { from -> val src = fs.resolve(from, forWrite = true); if (src.exists() && !src.delete()) throw WorkspaceException("suppression impossible : $from") }
                val target = fs.resolve(c.path, forWrite = true)
                if (c.after == null) { if (target.exists() && !target.delete()) throw WorkspaceException("suppression impossible : ${c.path}"); continue }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.cortana-tmp")
                tmp.writeBytes(c.after.toByteArray(Charsets.UTF_8))
                if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw WorkspaceException("écriture impossible : ${c.path}") }
            }
            // 3. post-write verification
            for (c in p.changes) {
                val f = fs.resolve(c.path)
                val ok = if (c.after == null) !f.exists() else f.isFile && f.readText(Charsets.UTF_8) == c.after
                if (!ok) throw WorkspaceException("vérification après écriture échouée : ${c.path}")
            }
        } catch (e: Exception) {
            restore(fs, backup, touched, before.filterValues { it != null }.keys, p.changes.filter { it.before == null }.map { it.path })
            throw e
        }
        val after = touched.associateWith { path -> fs.resolve(path).takeIf { it.isFile }?.let { Hash.sha256Bytes(it.readBytes()) } }
        val cs = ChangeSet(
            changeSetId = csId, workspaceId = w.workspaceId, patchId = patch.patchId, taskId = taskId, files = touched,
            beforeHashes = before, afterHashes = after, diff = p.diff, status = "applied", createdAt = System.currentTimeMillis(),
        )
        dao.upsertChangeSet(ChangeSetEntity(csId, w.workspaceId, patch.patchId, taskId, ContractJson.encodeToString(ChangeSet.serializer(), cs), backup.absolutePath, "applied", cs.createdAt))
        audit.record("cortana", "code.patch.apply", w.name, "ok", """{"workspace":"${w.workspaceId}","changeset":"$csId","files":${touched.size}}""")
        cs
    }

    /** Exact rollback; refuses when a file changed since the patch unless [force] (never silently loses work). */
    suspend fun rollback(changeSetId: String, force: Boolean = false): ChangeSet = withContext(Dispatchers.IO) {
        val e = dao.changeSet(changeSetId) ?: throw WorkspaceException("Modification introuvable")
        if (e.status != "applied") throw WorkspaceException("Modification déjà annulée")
        val cs = ContractJson.decodeFromString(ChangeSet.serializer(), e.changeSetJson)
        val w = workspaces.get(cs.workspaceId) ?: throw WorkspaceException("Projet introuvable")
        val fs = workspaces.fs(w)
        val drift = cs.files.filter { path -> fs.resolve(path).takeIf { it.isFile }?.let { Hash.sha256Bytes(it.readBytes()) } != cs.afterHashes[path] }
        if (drift.isNotEmpty() && !force) throw PatchConflict("fichiers modifiés depuis : ${drift.joinToString()}")
        restore(fs, File(e.backupDir), cs.files, cs.beforeHashes.filterValues { it != null }.keys, cs.beforeHashes.filterValues { it == null }.keys.toList())
        val rolled = cs.copy(status = "rolled_back")
        dao.upsertChangeSet(e.copy(status = "rolled_back", changeSetJson = ContractJson.encodeToString(ChangeSet.serializer(), rolled)))
        audit.record("cortana", "code.patch.rollback", w.name, "ok", """{"changeset":"$changeSetId"}""")
        rolled
    }

    suspend fun changeSets(workspaceId: String) = dao.changeSets(workspaceId).map { ContractJson.decodeFromString(ChangeSet.serializer(), it.changeSetJson) }
    /**
     * Net effect of a task on its workspaces: for every file its applied ChangeSets touched, the
     * content before the task's first change (from the backups) and the current content. Files
     * that ended up unchanged are omitted. Keyed by workspace id.
     */
    suspend fun netChanges(taskId: String): Map<String, List<FileChange>> = withContext(Dispatchers.IO) {
        val applied = dao.changeSetsForTask(taskId).filter { it.status == "applied" }.sortedBy { it.createdAt }
        applied.groupBy { it.workspaceId }.mapNotNull { (wsId, sets) ->
            val w = workspaces.get(wsId) ?: return@mapNotNull null
            val fs = workspaces.fs(w)
            val before = LinkedHashMap<String, String?>()
            for (e in sets) {
                val cs = ContractJson.decodeFromString(ChangeSet.serializer(), e.changeSetJson)
                for (path in cs.files) if (path !in before) {
                    before[path] = if (cs.beforeHashes[path] == null) null else File(e.backupDir, path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
                }
            }
            wsId to before.mapNotNull { (path, b) ->
                val after = runCatching { fs.resolve(path) }.getOrNull()?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                if (b == after) null else FileChange(path, b, after)
            }
        }.toMap()
    }

    suspend fun changeSetsForTask(taskId: String) = dao.changeSetsForTask(taskId).map { ContractJson.decodeFromString(ChangeSet.serializer(), it.changeSetJson) }

    private fun restore(fs: WorkspaceFs, backup: File, touched: Collection<String>, existedBefore: Set<String>, createdByPatch: List<String>) {
        for (path in touched) {
            val f = fs.resolve(path)
            if (path in existedBefore) { f.parentFile?.mkdirs(); File(backup, path).copyTo(f, overwrite = true) }
            else if (path in createdByPatch || !File(backup, path).exists()) f.delete()
        }
    }

    // ---------------------------------------------------------------- computation (pure, in memory)

    private fun compute(w: WorkspaceEntity, patch: PatchSet): Preview {
        val fs = workspaces.fs(w)
        val conflicts = mutableListOf<String>()
        if (patch.workspaceId != w.workspaceId) conflicts += "patch prévu pour un autre projet"
        val state = LinkedHashMap<String, String?>()        // path → current content in the simulation
        val original = LinkedHashMap<String, String?>()     // path → content before the patch
        val renames = LinkedHashMap<String, String>()        // newPath → oldPath
        fun load(path: String): String? {
            if (path in state) return state[path]
            val f = try { fs.resolve(path, forWrite = true) } catch (e: WorkspaceException) { conflicts += e.message!!; return null }
            val text = if (f.isFile) {
                if (RepositoryIntelligence.isBinary(f)) { conflicts += "$path est un fichier binaire"; null } else f.readText(Charsets.UTF_8)
            } else null
            original[path] = text; state[path] = text
            return text
        }
        for ((path, expected) in patch.expectedFileHashes) {
            val cur = load(path)
            val actual = cur?.let { Hash.sha256Bytes(it.toByteArray(Charsets.UTF_8)) }
            if (actual != expected) conflicts += "$path a changé depuis sa lecture (hash attendu ${expected.take(12)}, actuel ${actual?.take(12) ?: "absent"})"
        }
        for (op in patch.operations) {
            val cur = load(op.path)
            when (op) {
                is PatchOperation.Replace -> {
                    if (cur == null) { conflicts += "${op.path} introuvable"; continue }
                    val eol = eolOf(cur)
                    val find = toEol(op.find, eol); val repl = toEol(op.replace, eol)
                    if (find.isEmpty()) { conflicts += "${op.path} : texte à remplacer vide"; continue }
                    val count = occurrences(cur, find)
                    if (count != op.expectedOccurrences) { conflicts += "${op.path} : « ${op.find.take(60)} » trouvé $count fois (attendu ${op.expectedOccurrences})"; continue }
                    state[op.path] = cur.replace(find, repl)
                }
                is PatchOperation.Create -> {
                    if (cur != null) { conflicts += "${op.path} existe déjà"; continue }
                    state[op.path] = op.content
                }
                is PatchOperation.Write -> state[op.path] = if (cur != null) keepStyle(cur, op.content) else op.content
                is PatchOperation.Delete -> {
                    if (cur == null) { conflicts += "${op.path} introuvable"; continue }
                    state[op.path] = null
                }
                is PatchOperation.Rename -> {
                    if (cur == null) { conflicts += "${op.path} introuvable"; continue }
                    if (load(op.newPath) != null) { conflicts += "${op.newPath} existe déjà"; continue }
                    state[op.newPath] = cur; state[op.path] = null; renames[op.newPath] = op.path
                }
                is PatchOperation.UnifiedDiff -> {
                    val base = cur ?: if (op.diff.contains("--- /dev/null")) "" else { conflicts += "${op.path} introuvable"; continue }
                    try {
                        val bom = base.startsWith(BOM)
                        val eol = eolOf(base)
                        val applied = Diff.applyUnified(base.removePrefix(BOM).replace("\r\n", "\n"), op.diff)
                        state[op.path] = (if (bom) BOM else "") + toEol(applied, eol)
                    } catch (e: PatchConflict) { conflicts += "${op.path} : ${e.message}" }
                }
            }
        }
        val changes = state.keys.filter { original[it] != state[it] }.map { p -> FileChange(p, original[p], state[p], renames[p]) }
            .filterNot { c -> renames.values.contains(c.path) && c.after == null } // the rename's source is shown with the target
        val diff = changes.joinToString("") { c -> Diff.unified(c.renamedFrom ?: c.path, c.path, c.before ?: c.renamedFrom?.let { original[it] }, c.after) }
        if (changes.isEmpty() && conflicts.isEmpty()) conflicts += "le patch ne change rien"
        return Preview(conflicts.isEmpty(), conflicts, changes, diff)
    }

    companion object {
        private const val BOM = "\uFEFF"
        fun eolOf(s: String): String = if (s.contains("\r\n")) "\r\n" else "\n"
        fun toEol(s: String, eol: String): String = if (eol == "\r\n") s.replace("\r\n", "\n").replace("\n", "\r\n") else s.replace("\r\n", "\n")
        private fun keepStyle(old: String, new: String): String = (if (old.startsWith(BOM) && !new.startsWith(BOM)) BOM else "") + toEol(new, eolOf(old))
        private fun occurrences(s: String, find: String): Int { var n = 0; var i = s.indexOf(find); while (i >= 0) { n++; i = s.indexOf(find, i + find.length) }; return n }
    }
}
