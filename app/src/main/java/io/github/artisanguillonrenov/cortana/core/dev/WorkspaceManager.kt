package io.github.artisanguillonrenov.cortana.core.dev

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.contracts.Workspace
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

class WorkspaceException(message: String) : Exception(message)

/**
 * Path confinement for one workspace root (doc 03 §2 "empêcher la sortie du périmètre"): every
 * relative path is canonicalized and must stay under the root; `.git` internals are never
 * writable through file operations; symbolic links pointing outside are refused.
 */
class WorkspaceFs(val root: File) {
    private val canonicalRoot: File = root.canonicalFile

    fun resolve(relative: String, forWrite: Boolean = false): File {
        val clean = relative.trim().removePrefix("./").replace('\\', '/').trimStart('/')
        if (clean.split('/').any { it == ".." }) throw WorkspaceException("Chemin hors du projet refusé : $relative")
        val f = File(canonicalRoot, clean)
        val canonical = f.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            throw WorkspaceException("Chemin hors du projet refusé : $relative")
        }
        if (forWrite && (clean == ".git" || clean.startsWith(".git/"))) throw WorkspaceException("Les fichiers internes de Git ne se modifient pas directement : $relative")
        return f
    }

    fun relative(f: File): String = f.canonicalFile.relativeTo(canonicalRoot).path.replace(File.separatorChar, '/')

    /** Files under the root, skipping `.git` and heavy generated directories unless asked. */
    fun walk(includeGenerated: Boolean = false): Sequence<File> = canonicalRoot.walkTopDown()
        .onEnter { d -> d == canonicalRoot || (d.name != ".git" && (includeGenerated || d.name !in GENERATED_DIRS)) }
        .filter { it.isFile }

    companion object {
        val GENERATED_DIRS = setOf("build", ".gradle", "node_modules", "dist", "target", "out", ".idea", "__pycache__", ".venv", "venv", ".next", ".cortana")
    }
}

/**
 * The one owner of developer workspaces (doc 03 §2): create, import (SAF copy), clone (via
 * GitService), open, trust, per-task mutation lock, cleanup. Workspaces live in the app-private
 * directory `files/workspaces/<id>` so Git, patches and builds work on real files.
 */
class WorkspaceManager(private val context: Context, private val db: CortanaDatabase, private val audit: AuditLog) {
    private val dao get() = db.dev()
    val baseDir: File get() = File(context.filesDir, "workspaces").apply { mkdirs() }

    fun observe(): Flow<List<WorkspaceEntity>> = dao.observeWorkspaces()
    suspend fun list(): List<WorkspaceEntity> = dao.workspaces()
    suspend fun get(id: String): WorkspaceEntity? = dao.workspace(id)

    /** By id, id prefix or exact name (what the model may pass). */
    suspend fun find(ref: String): WorkspaceEntity? =
        dao.workspace(ref) ?: dao.workspaces().firstOrNull { it.workspaceId.startsWith(ref) || it.name.equals(ref, ignoreCase = true) }

    suspend fun require(ref: String): WorkspaceEntity = find(ref) ?: throw WorkspaceException("Projet « $ref » introuvable. Projets : ${dao.workspaces().joinToString { it.name }.ifEmpty { "aucun" }}")

    fun fs(w: WorkspaceEntity): WorkspaceFs = WorkspaceFs(File(w.rootPath))

    suspend fun create(name: String, origin: String = "created", trust: WorkspaceTrust = WorkspaceTrust.TRUSTED_LOCAL): WorkspaceEntity = withContext(Dispatchers.IO) {
        val id = Ids.new()
        val dir = File(baseDir, id).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val w = WorkspaceEntity(id, sanitizeName(name), dir.absolutePath, origin, trust = wire(trust), createdAt = now, lastOpenedAt = now)
        dao.upsertWorkspace(w)
        audit.record("cortana", "workspace.create", w.name, "ok", """{"workspace":"$id","origin":"${origin.substringBefore(':')}"}""")
        w
    }

    /** Copies a SAF tree into a new workspace (imported projects are untrusted by default). */
    suspend fun importTree(treeUri: Uri, name: String? = null, maxBytes: Long = 200L * 1024 * 1024): WorkspaceEntity {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw WorkspaceException("Dossier illisible")
        return importDocument(tree, name, "imported:$treeUri", maxBytes)
    }

    suspend fun importDocument(tree: DocumentFile, name: String?, origin: String, maxBytes: Long = 200L * 1024 * 1024): WorkspaceEntity = withContext(Dispatchers.IO) {
        val w = create(name ?: tree.name ?: "projet", origin = origin, trust = WorkspaceTrust.UNTRUSTED)
        var copied = 0L
        fun copy(dir: DocumentFile, into: File) {
            for (child in dir.listFiles()) {
                val childName = child.name ?: continue
                if (childName in WorkspaceFs.GENERATED_DIRS && child.isDirectory) continue
                val target = File(into, childName)
                if (child.isDirectory) { target.mkdirs(); copy(child, target) }
                else {
                    copied += child.length()
                    if (copied > maxBytes) throw WorkspaceException("Projet trop volumineux (> ${maxBytes / 1024 / 1024} Mo)")
                    context.contentResolver.openInputStream(child.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        try { copy(tree, File(w.rootPath)) } catch (e: Exception) { delete(w.workspaceId); throw e }
        w
    }

    suspend fun touch(w: WorkspaceEntity, update: (WorkspaceEntity) -> WorkspaceEntity = { it }): WorkspaceEntity {
        val upd = update(w).copy(lastOpenedAt = System.currentTimeMillis())
        dao.upsertWorkspace(upd)
        return upd
    }

    suspend fun setTrust(id: String, trust: WorkspaceTrust) {
        val w = dao.workspace(id) ?: return
        dao.upsertWorkspace(w.copy(trust = wire(trust), writable = trust != WorkspaceTrust.READ_ONLY))
        audit.record("owner", "workspace.trust", w.name, "ok", """{"workspace":"$id","trust":"${wire(trust)}"}""")
    }

    /**
     * Mutation lock per task (doc 03 §2); expires so a crashed task never blocks a project forever,
     * and is taken over at once when the holding task has ended (completed, failed, cancelled, gone).
     */
    suspend fun lock(id: String, taskId: String, ttlMs: Long = 30 * 60_000L): Boolean {
        val now = System.currentTimeMillis()
        if (dao.tryLock(id, taskId, now + ttlMs, now) > 0) return true
        val holder = dao.workspace(id)?.lockTaskId ?: return dao.tryLock(id, taskId, now + ttlMs, now) > 0
        val ended = db.tasks().get(holder)?.let { t -> TaskState.entries.firstOrNull { it.wire == t.state }?.terminal ?: false } ?: true
        return ended && dao.unlock(id, holder) >= 0 && dao.tryLock(id, taskId, now + ttlMs, now) > 0
    }

    suspend fun unlock(id: String, taskId: String) { dao.unlock(id, taskId) }

    /**
     * Content revision of a workspace (sources only, generated directories excluded): changes
     * whenever any tracked file changes, whoever changed it. Used to tell whether test results,
     * reviews and checkpoints still describe the current code.
     */
    suspend fun revision(w: WorkspaceEntity): String = withContext(Dispatchers.IO) {
        val fs = fs(w)
        val lines = fs.walk().map { f -> fs.relative(f) + "\u0000" + io.github.artisanguillonrenov.cortana.util.Hash.sha256Bytes(f.readBytes()) }.sorted().joinToString("\n")
        "r-" + io.github.artisanguillonrenov.cortana.util.Hash.sha256(lines).take(16)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val w = dao.workspace(id) ?: return@withContext
        File(w.rootPath).takeIf { it.canonicalPath.startsWith(baseDir.canonicalPath) }?.deleteRecursively()
        dao.deleteChangeSets(id)
        dao.deleteWorkspace(id)
        audit.record("owner", "workspace.delete", w.name, "ok")
    }

    fun toContract(w: WorkspaceEntity) = Workspace(
        workspaceId = w.workspaceId, name = w.name, root = "app:" + w.rootPath, backendId = w.backendId, vcsType = w.vcsType,
        currentBranch = w.currentBranch, baseRevision = w.baseRevision, writable = w.writable, trust = trustOf(w),
        detectedStacks = list(w.detectedStacksJson), buildSystems = list(w.buildSystemsJson), createdAt = w.createdAt, lastOpenedAt = w.lastOpenedAt,
    )

    private fun list(json: String) = runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
    private fun sanitizeName(n: String) = n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").take(80).ifBlank { "projet" }

    companion object {
        fun wire(t: WorkspaceTrust) = t.name.lowercase()
        fun trustOf(w: WorkspaceEntity) = WorkspaceTrust.entries.firstOrNull { it.name.equals(w.trust, ignoreCase = true) } ?: WorkspaceTrust.UNTRUSTED
    }
}
