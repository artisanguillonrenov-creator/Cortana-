package io.github.artisanguillonrenov.cortana.core.dev

import android.content.Context
import android.net.Uri
import io.github.artisanguillonrenov.cortana.contracts.Artifact
import io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Artifact Service (doc 03 §12): every produced file (APK, ZIP, report, log, patch, export…) is
 * stored once under `files/artifacts/<id>/`, hashed (SHA-256), typed and attributed to its task;
 * exports copy it out through the Storage Access Framework and re-verify the hash.
 */
class ArtifactService(private val context: Context, private val db: CortanaDatabase, private val audit: AuditLog) {
    private val dao get() = db.dev()
    val root: File get() = File(context.filesDir, "artifacts").apply { mkdirs() }

    fun observe(): Flow<List<ArtifactEntity>> = dao.observeArtifacts()
    suspend fun list(limit: Int = 200) = dao.artifacts(limit)
    suspend fun forTask(taskId: String) = dao.artifactsForTask(taskId)
    suspend fun get(id: String) = dao.artifact(id)
    fun file(a: ArtifactEntity): File = File(a.uri.removePrefix("file://"))

    /** Copies [source] into the store (or moves it when [move]); returns the registered artifact. */
    suspend fun register(
        source: File, type: String, name: String = source.name, taskId: String? = null, capability: String? = null,
        metadata: Map<String, String> = emptyMap(), sources: List<String> = emptyList(), move: Boolean = false,
    ): ArtifactEntity = withContext(Dispatchers.IO) {
        val id = Ids.new()
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, safeName(name))
        if (move) { if (!source.renameTo(target)) { source.copyTo(target, overwrite = true); source.delete() } } else source.copyTo(target, overwrite = true)
        val a = ArtifactEntity(
            artifactId = id, type = type, mime = mimeOf(target.name), name = target.name, uri = "file://" + target.absolutePath,
            sha256 = Hash.sha256File(target), sizeBytes = target.length(), producerTaskId = taskId, producerCapability = capability,
            sourceIdsJson = AppJson.encodeToString(ListSerializer(String.serializer()), sources),
            metadataJson = AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), metadata), createdAt = System.currentTimeMillis(),
        )
        dao.upsertArtifact(a)
        a
    }

    suspend fun registerText(text: String, type: String, name: String, taskId: String? = null, capability: String? = null, metadata: Map<String, String> = emptyMap()): ArtifactEntity {
        val tmp = File(context.cacheDir, "artifact-${Ids.new()}").apply { writeText(text) }
        return register(tmp, type, name, taskId, capability, metadata, move = true)
    }

    /** Integrity check: the stored file still has its recorded hash. */
    suspend fun verify(id: String): Boolean = withContext(Dispatchers.IO) {
        val a = dao.artifact(id) ?: return@withContext false
        val f = file(a)
        f.isFile && Hash.sha256File(f) == a.sha256
    }

    /** Owner export through SAF (the owner picked [target]); the copy is verified byte for byte. */
    suspend fun exportTo(id: String, target: Uri): Boolean = withContext(Dispatchers.IO) {
        val a = dao.artifact(id) ?: return@withContext false
        if (!verify(id)) return@withContext false
        context.contentResolver.openOutputStream(target)?.use { out -> file(a).inputStream().use { it.copyTo(out) } } ?: return@withContext false
        val copied = context.contentResolver.openInputStream(target)?.use { Hash.sha256Bytes(it.readBytes()) }
        audit.record("owner", "artifact.export", a.name, if (copied == a.sha256) "ok" else "error", """{"artifact":"$id"}""")
        copied == a.sha256
    }

    /** Copies to Download/Cortana through MediaStore (no storage permission on API 29+), then re-verifies the hash. */
    suspend fun exportToDownloads(id: String): String? = withContext(Dispatchers.IO) {
        val a = dao.artifact(id) ?: return@withContext null
        if (!verify(id)) return@withContext null
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, a.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, a.mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Cortana")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> file(a).inputStream().use { it.copyTo(out) } }
            context.contentResolver.openInputStream(uri)?.use { Hash.sha256Bytes(it.readBytes()) } == a.sha256
        }.getOrDefault(false)
        audit.record("cortana", "artifact.export", a.name, if (ok) "ok" else "error", """{"artifact":"$id"}""")
        if (ok) "Téléchargements/Cortana/${a.name}" else { context.contentResolver.delete(uri, null, null); null }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val a = dao.artifact(id) ?: return@withContext
        file(a).parentFile?.takeIf { it.canonicalPath.startsWith(root.canonicalPath) }?.deleteRecursively()
        dao.upsertArtifact(a.copy(deleted = true))
    }

    fun toContract(a: ArtifactEntity) = Artifact(
        artifactId = a.artifactId, type = a.type, mime = a.mime, uri = a.uri, sha256 = a.sha256, sizeBytes = a.sizeBytes,
        producerTaskId = a.producerTaskId, createdAt = a.createdAt, name = a.name,
    )

    companion object {
        fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "artefact" }
        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "apk" -> "application/vnd.android.package-archive"; "aab" -> "application/octet-stream"; "zip" -> "application/zip"
            "json" -> "application/json"; "txt", "log" -> "text/plain"; "md" -> "text/markdown"; "html" -> "text/html"; "csv" -> "text/csv"
            "pdf" -> "application/pdf"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "diff", "patch" -> "text/x-diff"; "xml" -> "application/xml"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "tsv" -> "text/tab-separated-values"; "gz", "tgz" -> "application/gzip"; "tar" -> "application/x-tar"
            "webp" -> "image/webp"; "gif" -> "image/gif"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}
