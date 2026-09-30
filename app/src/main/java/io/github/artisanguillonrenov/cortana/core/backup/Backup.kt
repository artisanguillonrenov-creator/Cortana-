package io.github.artisanguillonrenov.cortana.core.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.artisanguillonrenov.cortana.contracts.CONTRACTS_SCHEMA_VERSION
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Access to the secret store by handle (a seam: tests and instance B use an in-memory vault). */
interface SecretAccess {
    fun get(handle: String): String?
    fun put(handle: String, value: String)
    fun has(handle: String): Boolean
    /** Stored handles (values never listed). */
    fun handles(): Set<String> = emptySet()
    fun remove(handle: String) {}
}

class BackupException(message: String) : Exception(message)

/** What this build can read and write; carried by every backup and by the update manifest (phase 30). */
@Serializable
data class CompatibilityManifest(
    val appVersion: String,
    val appVersionCode: Int,
    val dbSchema: Int,
    val contractsVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workerProtocol: String = WorkerProtocol.VERSION,
    val backupFormat: Int = BackupService.FORMAT_VERSION,
    /** Oldest database schema a backup may come from to be restored here. */
    val minRestorableSchema: Int = 2,
)

@Serializable data class BackupEntry(val name: String, val sha256: String, val size: Long, val rows: Int? = null)

@Serializable data class BackupEncryption(val alg: String = "AES-256-GCM", val kdf: String = "PBKDF2-HMAC-SHA256", val iterations: Int, val salt: String, val keyCheck: String)

@Serializable
data class BackupManifest(
    val format: String = BackupService.FORMAT,
    val formatVersion: Int = BackupService.FORMAT_VERSION,
    val createdAt: Long,
    val compatibility: CompatibilityManifest,
    val tables: List<String>,
    val entries: List<BackupEntry>,
    val includesSecrets: Boolean,
    val artifacts: List<String> = emptyList(),
    val encryption: BackupEncryption? = null,
    /** HMAC-SHA256 of the manifest (without this field) under the backup key, when encrypted. */
    val mac: String? = null,
)

data class BackupOptions(val passphrase: String? = null, val includeSecrets: Boolean = false, val artifactIds: List<String> = emptyList())

data class Inspection(val manifest: BackupManifest?, val verified: Boolean, val compatible: Boolean, val problems: List<String>)

enum class RestoreMode { MERGE, REPLACE }

data class TableReport(val table: String, val rows: Int, val inserted: Int, val identical: Int, val conflicts: Int, val replaced: Int, val droppedColumns: Set<String>)

data class RestoreReport(
    val dryRun: Boolean,
    val mode: RestoreMode,
    val tables: List<TableReport>,
    val secretsRestored: Int,
    val missingSecrets: List<String>,
    val artifacts: Int,
    val preRestoreBackup: String?,
) {
    fun summary(): String = buildString {
        append(if (dryRun) "Simulation " else "Restauration ")
        append(if (mode == RestoreMode.REPLACE) "(remplacement) : " else "(fusion) : ")
        append(tables.filter { it.rows > 0 }.joinToString { "${it.table} ${it.inserted}+" + (if (it.conflicts > 0) " ${it.conflicts}≠" else "") + (if (it.replaced > 0) " ${it.replaced}↺" else "") })
        if (secretsRestored > 0) append(" · $secretsRestored secret(s)")
        if (missingSecrets.isNotEmpty()) append(" · ${missingSecrets.size} secret(s) à ressaisir")
        if (artifacts > 0) append(" · $artifacts artefact(s)")
    }
}

/**
 * Backup, restore and portability (doc 06 §12-13, phase 29). A backup is a zip: `manifest.json`
 * (versions, compatibility, per-entry SHA-256, optional encryption parameters and MAC), one JSON
 * lines file per table of owner data, selected artifacts, and — only when explicitly asked, and
 * then always encrypted with a passphrase — the secrets those rows refer to. Rows are exported and
 * imported by column name, so a backup restores into any compatible schema; derived indexes
 * (full text, vectors) are rebuilt, never carried. Restoring first backs the current state up,
 * applies everything in one transaction, merges (local rows win, conflicts reported) or replaces
 * explicitly, and can be simulated.
 */
class BackupService(
    private val db: CortanaDatabase,
    private val secrets: SecretAccess,
    private val artifactsRoot: File,
    private val backupsDir: File,
    private val compatibility: CompatibilityManifest,
    private val audit: AuditLog?,
) {
    /** Set by the container: reload settings, re-arm schedules, rebuild indexes, fix connections. */
    var afterRestore: (suspend (RestoreReport) -> Unit)? = null
    private val lock = Mutex()

    fun list(): List<File> = backupsDir.listFiles { f -> f.name.endsWith(EXT) }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun newFile(prefix: String = "cortana"): File =
        File(backupsDir.apply { mkdirs() }, "$prefix-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}$EXT")

    /** Copies [file] to Téléchargements/Cortana (verified), for the owner to keep it off the tablet. */
    suspend fun exportToDownloads(context: android.content.Context, file: File): String? = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Cortana")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val expected = Hash.sha256File(file)
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            context.contentResolver.openInputStream(uri)?.use { Hash.sha256Bytes(it.readBytes()) } == expected
        }.getOrDefault(false)
        audit?.record("owner", "backup.export", file.name, if (ok) "ok" else "error")
        if (ok) "Téléchargements/Cortana/${file.name}" else { context.contentResolver.delete(uri, null, null); null }
    }

    /** Copies a backup chosen by the owner (document picker) next to the local ones, bounded in size. */
    suspend fun importFrom(context: android.content.Context, uri: android.net.Uri): File = withContext(Dispatchers.IO) {
        val target = newFile("importee")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > MAX_TOTAL) { target.delete(); throw BackupException("fichier trop volumineux") }; out.write(buf, 0, n) }
            }
        } ?: throw BackupException("fichier illisible")
        target
    }

    // ------------------------------------------------------------------ create

    suspend fun create(target: File, opts: BackupOptions = BackupOptions()): BackupManifest = lock.withLock { createUnlocked(target, opts) }

    private suspend fun createUnlocked(target: File, opts: BackupOptions): BackupManifest = withContext(Dispatchers.IO) {
        if (opts.includeSecrets && opts.passphrase == null) throw BackupException("les secrets ne sont exportés que dans une sauvegarde chiffrée par une phrase de passe")
        opts.passphrase?.let { checkPassphrase(it) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = opts.passphrase?.let { derive(it, salt, ITERATIONS) }
        val entries = mutableListOf<BackupEntry>()
        val dumps = LinkedHashMap<String, List<JsonObject>>()
        db.runInTransaction {
            val sdb = db.openHelper.writableDatabase
            for (t in INCLUDED) dumps[t] = dump(sdb, t).let { rows -> if (t == "artifacts") rows.filter { it.str("artifactId") in opts.artifactIds } else rows }
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray, rows: Int? = null) {
                val stored = key?.let { seal(it, name, bytes) } ?: bytes
                zip.putNextEntry(ZipEntry(name)); zip.write(stored); zip.closeEntry()
                entries += BackupEntry(name, Hash.sha256Bytes(stored), stored.size.toLong(), rows)
            }
            dumps.forEach { (t, rows) -> put("data/$t.jsonl", rows.joinToString("\n") { it.toString() }.toByteArray(), rows.size) }
            val artifacts = dumps["artifacts"].orEmpty()
            for (a in artifacts) {
                val f = File(a.str("uri")?.removePrefix("file://") ?: continue)
                if (f.isFile) put("artifacts/${a.str("artifactId")}/${f.name}", f.readBytes())
            }
            if (opts.includeSecrets) {
                val handles = HANDLE.findAll(dumps.values.flatten().joinToString("\n") { it.toString() }).map { it.value }.toSortedSet()
                val values = handles.mapNotNull { h -> secrets.get(h)?.let { h to it } }.toMap()
                put("secrets.json", AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), values).toByteArray())
            }
            val unsigned = BackupManifest(
                createdAt = System.currentTimeMillis(), compatibility = compatibility, tables = INCLUDED, entries = entries.toList(),
                includesSecrets = opts.includeSecrets, artifacts = artifacts.mapNotNull { it.str("artifactId") },
                encryption = key?.let { BackupEncryption(iterations = ITERATIONS, salt = b64(salt), keyCheck = b64(seal(it, "key-check", KEY_CHECK))) },
            )
            val manifest = key?.let { unsigned.copy(mac = mac(it, unsigned)) } ?: unsigned
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(AppJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray()); zip.closeEntry()
            manifest
        }.also {
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            audit?.record("owner", "backup.create", target.name, "ok", """{"tables":${it.tables.size},"secrets":${it.includesSecrets},"encrypted":${it.encryption != null},"artifacts":${it.artifacts.size}}""")
        }
    }

    // ------------------------------------------------------------------ inspect

    private class Opened(val manifest: BackupManifest, val entries: Map<String, ByteArray>, val key: SecretKeySpec?)

    suspend fun inspect(file: File, passphrase: String? = null): Inspection = withContext(Dispatchers.IO) {
        runCatching { open(file, passphrase) }.fold(
            { o -> val c = incompatibility(o.manifest); Inspection(o.manifest, true, c == null, listOfNotNull(c)) },
            { e ->
                val m = runCatching { readZip(file)[MANIFEST]?.let { AppJson.decodeFromString(BackupManifest.serializer(), String(it)) } }.getOrNull()
                Inspection(m, false, m != null && incompatibility(m) == null, listOf(e.message ?: e.javaClass.simpleName))
            },
        )
    }

    private fun readZip(file: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (!NAME.matches(e.name)) throw BackupException("entrée inattendue dans la sauvegarde : ${e.name.take(80)}")
                if (out.containsKey(e.name)) throw BackupException("entrée en double : ${e.name}")
                if (out.size >= MAX_ENTRIES) throw BackupException("trop d'entrées")
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(chunk); if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL) throw BackupException("sauvegarde trop volumineuse (> ${MAX_TOTAL / 1_000_000} Mo décompressés)")
                    buf.write(chunk, 0, n)
                }
                out[e.name] = buf.toByteArray()
            }
        }
        return out
    }

    private fun open(file: File, passphrase: String?): Opened {
        val raw = readZip(file)
        val manifest = raw[MANIFEST]?.let { runCatching { AppJson.decodeFromString(BackupManifest.serializer(), String(it)) }.getOrNull() }
            ?: throw BackupException("manifeste absent ou illisible : ce n'est pas une sauvegarde Cortana")
        if (manifest.format != FORMAT) throw BackupException("format inconnu : ${manifest.format}")
        if (manifest.formatVersion > FORMAT_VERSION) throw BackupException("sauvegarde d'un format plus récent (${manifest.formatVersion}) : mettez Cortana à jour")
        val key = manifest.encryption?.let { enc ->
            val p = passphrase ?: throw BackupException("sauvegarde chiffrée : phrase de passe requise")
            if (enc.alg != "AES-256-GCM" || enc.kdf != "PBKDF2-HMAC-SHA256" || enc.iterations !in 100_000..5_000_000) throw BackupException("paramètres de chiffrement non pris en charge")
            val k = derive(p, unb64(enc.salt), enc.iterations)
            val check = runCatching { open(k, "key-check", unb64(enc.keyCheck)) }.getOrNull()
            if (check == null || !check.contentEquals(KEY_CHECK)) throw BackupException("phrase de passe incorrecte")
            if (manifest.mac == null || !java.security.MessageDigest.isEqual(manifest.mac.toByteArray(), mac(k, manifest.copy(mac = null)).toByteArray())) throw BackupException("manifeste modifié (authentification invalide)")
            k
        }
        val listed = manifest.entries.associateBy { it.name }
        (raw.keys - MANIFEST - listed.keys).firstOrNull()?.let { throw BackupException("entrée non déclarée : $it") }
        val plain = LinkedHashMap<String, ByteArray>()
        for (e in manifest.entries) {
            val bytes = raw[e.name] ?: throw BackupException("entrée manquante : ${e.name}")
            if (Hash.sha256Bytes(bytes) != e.sha256) throw BackupException("empreinte invalide : ${e.name} (sauvegarde corrompue ou modifiée)")
            plain[e.name] = if (key != null) runCatching { open(key, e.name, bytes) }.getOrElse { throw BackupException("déchiffrement impossible : ${e.name}") } else bytes
        }
        if (manifest.includesSecrets && key == null) throw BackupException("sauvegarde invalide : secrets non chiffrés")
        return Opened(manifest, plain, key)
    }

    private fun incompatibility(m: BackupManifest): String? = when {
        m.compatibility.dbSchema > compatibility.dbSchema -> "sauvegarde d'un schéma plus récent (${m.compatibility.dbSchema} > ${compatibility.dbSchema}) : mettez Cortana à jour"
        m.compatibility.dbSchema < compatibility.minRestorableSchema -> "schéma trop ancien (${m.compatibility.dbSchema})"
        m.compatibility.backupFormat > FORMAT_VERSION -> "format de sauvegarde plus récent"
        else -> null
    }

    // ------------------------------------------------------------------ restore

    /** Simulates ([dryRun]) or applies a restore. Throws [BackupException] when the file is not verified or not compatible. */
    suspend fun restore(file: File, passphrase: String?, mode: RestoreMode, dryRun: Boolean): RestoreReport = lock.withLock {
        val o = withContext(Dispatchers.IO) { open(file, passphrase) }
        incompatibility(o.manifest)?.let { throw BackupException(it) }
        val rows = o.manifest.tables.filter { it in INCLUDED }.associateWith { t ->
            String(o.entries["data/$t.jsonl"] ?: ByteArray(0)).lineSequence().filter { it.isNotBlank() }.map { AppJson.parseToJsonElement(it).jsonObject }.toList()
        }
        val secretValues = o.entries["secrets.json"]?.let { AppJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), String(it)) }.orEmpty()
            .filter { (h, _) -> HANDLE.matches(h) }
        val pre = if (dryRun) null else newFile("avant-restauration").also { createUnlocked(it, BackupOptions()) }.absolutePath
        // Artifact files first (content-addressed check), rows point at their new location.
        val artifactPaths = HashMap<String, String>()
        if (!dryRun) withContext(Dispatchers.IO) {
            for (a in rows["artifacts"].orEmpty()) {
                val id = a.str("artifactId") ?: continue
                if (!ID.matches(id)) continue
                val entry = o.entries.keys.firstOrNull { it.startsWith("artifacts/$id/") } ?: continue
                val bytes = o.entries.getValue(entry)
                if (Hash.sha256Bytes(bytes) != a.str("sha256")) throw BackupException("artefact $id : contenu différent de son empreinte")
                val f = File(File(artifactsRoot, id).apply { mkdirs() }, entry.substringAfterLast('/'))
                f.writeBytes(bytes)
                artifactPaths[id] = "file://" + f.absolutePath
            }
        }
        val reports = withContext(Dispatchers.IO) {
            val out = mutableListOf<TableReport>()
            db.runInTransaction {
                val sdb = db.openHelper.writableDatabase
                for (t in INCLUDED) {
                    val data = rows[t] ?: continue
                    out += apply(sdb, t, data.map { r -> if (t == "artifacts") artifactPaths[r.str("artifactId")]?.let { r.with("uri", JsonPrimitive(it)) } ?: r else r }, mode, dryRun)
                }
                if (!dryRun) {
                    // A task that was running when the backup was taken cannot resume here.
                    val ids = rows["tasks"].orEmpty().filter { it.str("state") !in TERMINAL }.mapNotNull { it.str("id") }
                    ids.forEach { id ->
                        sdb.execSQL("UPDATE tasks SET state = 'failed', terminationReason = ?, endedAt = ?, leaseOwner = NULL, leaseExpiresAt = NULL WHERE id = ? AND state NOT IN ('completed','failed','cancelled','timed_out','halted')",
                            arrayOf<Any?>("restored: tâche en cours lors de la sauvegarde", System.currentTimeMillis(), id))
                    }
                    if (rows.containsKey("messages")) sdb.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
                    if (rows.containsKey("memories")) sdb.execSQL("INSERT INTO memories_fts(memories_fts) VALUES('rebuild')")
                }
            }
            out
        }
        var restoredSecrets = 0
        if (!dryRun) secretValues.forEach { (h, v) -> if (!secrets.has(h) || mode == RestoreMode.REPLACE) { secrets.put(h, v); restoredSecrets++ } }
        val referenced = HANDLE.findAll(rows.values.flatten().joinToString("\n") { it.toString() }).map { it.value }.toSortedSet()
        val missing = referenced.filter { h -> !secrets.has(h) && (dryRun.not() || h !in secretValues) }
        val report = RestoreReport(dryRun, mode, reports, if (dryRun) secretValues.size else restoredSecrets, missing, if (dryRun) rows["artifacts"].orEmpty().size else artifactPaths.size, pre)
        if (!dryRun) {
            audit?.record("owner", "backup.restore", file.name, "ok", """{"mode":"${mode.name.lowercase()}","tables":${reports.size},"secrets":$restoredSecrets,"missingSecrets":${missing.size}}""")
            afterRestore?.invoke(report)
        }
        report
    }

    private fun apply(sdb: SupportSQLiteDatabase, table: String, data: List<JsonObject>, mode: RestoreMode, dryRun: Boolean): TableReport {
        val info = columns(sdb, table)
        val cols = info.map { it.first }.toSet()
        val pk = info.filter { it.second }.map { it.first }.ifEmpty { throw BackupException("table sans clé : $table") }
        val dropped = data.flatMap { it.keys }.toSet() - cols
        val existing = count(sdb, table)
        var inserted = 0; var identical = 0; var conflicts = 0; var replaced = 0
        val wholesale = mode == RestoreMode.REPLACE && table != "artifacts"
        if (wholesale) { replaced = existing; if (!dryRun) sdb.execSQL("DELETE FROM `$table`") }
        for (r in data) {
            val row = JsonObject(r.filterKeys { it in cols })
            if (!wholesale) {
                val cur = find(sdb, table, pk, row)
                if (cur != null) {
                    if (JsonObject(cur.filterKeys { it in row.keys }) == row) { identical++; continue }
                    if (mode == RestoreMode.MERGE) { conflicts++; continue }
                    replaced++
                    if (!dryRun) insert(sdb, table, row, replace = true)
                    continue
                }
            }
            inserted++
            if (!dryRun) insert(sdb, table, row, replace = false)
        }
        return TableReport(table, data.size, inserted, identical, conflicts, replaced, dropped)
    }

    // ------------------------------------------------------------------ SQL helpers

    private fun dump(sdb: SupportSQLiteDatabase, table: String): List<JsonObject> = sdb.query("SELECT * FROM `$table`").use { c ->
        val out = ArrayList<JsonObject>(c.count)
        while (c.moveToNext()) out += JsonObject((0 until c.columnCount).associate { i -> c.getColumnName(i) to value(c, i) })
        out
    }

    private fun value(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> buildJsonObject { put("\$b64", JsonPrimitive(b64(c.getBlob(i)))) }
        else -> JsonPrimitive(c.getString(i))
    }

    private fun bind(v: JsonElement): Any? = when {
        v is JsonNull -> null
        v is JsonObject -> v["\$b64"]?.let { unb64((it as JsonPrimitive).content) } ?: v.toString()
        v is JsonPrimitive && v.isString -> v.content
        v is JsonPrimitive -> v.longOrNull ?: v.doubleOrNull ?: v.booleanOrNull?.let { if (it) 1L else 0L } ?: v.contentOrNull
        else -> v.toString()
    }

    private fun columns(sdb: SupportSQLiteDatabase, table: String): List<Pair<String, Boolean>> = sdb.query("PRAGMA table_info(`$table`)").use { c ->
        val out = mutableListOf<Pair<String, Boolean>>()
        while (c.moveToNext()) out += c.getString(c.getColumnIndexOrThrow("name")) to (c.getInt(c.getColumnIndexOrThrow("pk")) > 0)
        out
    }

    private fun count(sdb: SupportSQLiteDatabase, table: String): Int = sdb.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun find(sdb: SupportSQLiteDatabase, table: String, pk: List<String>, row: JsonObject): Map<String, JsonElement>? {
        val where = pk.joinToString(" AND ") { "`$it` = ?" }
        return sdb.query("SELECT * FROM `$table` WHERE $where", pk.map { bind(row[it] ?: JsonNull) }.toTypedArray()).use { c ->
            if (!c.moveToFirst()) null else (0 until c.columnCount).associate { i -> c.getColumnName(i) to value(c, i) }
        }
    }

    private fun insert(sdb: SupportSQLiteDatabase, table: String, row: JsonObject, replace: Boolean) {
        val keys = row.keys.toList()
        sdb.execSQL("INSERT ${if (replace) "OR REPLACE " else ""}INTO `$table` (${keys.joinToString { "`$it`" }}) VALUES (${keys.joinToString { "?" }})", keys.map { bind(row.getValue(it)) }.toTypedArray())
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.with(k: String, v: JsonElement) = JsonObject(this + (k to v))

    // ------------------------------------------------------------------ crypto

    private fun checkPassphrase(p: String) {
        if (p.length < 12) throw BackupException("phrase de passe trop courte (12 caractères au moins)")
        if (p.toSet().size < 5) throw BackupException("phrase de passe trop simple")
    }

    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }

    /** nonce(12) ‖ AES-GCM(plain) with the entry name as associated data (entries cannot be swapped). */
    private fun seal(key: SecretKeySpec, name: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(name.toByteArray()) }
        return nonce + c.doFinal(plain)
    }

    private fun open(key: SecretKeySpec, name: String, sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)); updateAAD(name.toByteArray()) }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    private fun mac(key: SecretKeySpec, m: BackupManifest): String =
        Hash.hex(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encoded, "HmacSHA256")) }.doFinal(AppJson.encodeToString(BackupManifest.serializer(), m.copy(mac = null)).toByteArray()))

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val FORMAT = "cortana-backup"
        const val FORMAT_VERSION = 1
        const val EXT = ".cortana-backup"
        const val MANIFEST = "manifest.json"
        private const val ITERATIONS = 210_000
        private const val MAX_ENTRIES = 20_000
        private const val MAX_TOTAL = 1_500_000_000L
        private val KEY_CHECK = "cortana-backup-key-check".toByteArray()
        private val TERMINAL = setOf("completed", "failed", "cancelled", "timed_out", "halted")
        private val ID = Regex("[0-9a-fA-F-]{8,64}")
        val HANDLE = Regex("secret:[0-9a-fA-F-]{36}")
        private val NAME = Regex("manifest\\.json|secrets\\.json|data/[a-z_0-9]+\\.jsonl|artifacts/[0-9a-fA-F-]{8,64}/[^/\\\\]{1,120}")

        /** Owner data carried by a backup, in insertion order. */
        val INCLUDED = listOf(
            "settings", "providers", "model_caps", "grants", "connections",
            "projects", "sessions", "messages", "conversation_summaries", "chat_drafts", "chat_pins", "context_checkpoints", "memories", "memory_edges",
            "skills", "skill_versions", "skill_runs", "skill_trajectories",
            "schedules", "tasks", "task_events", "steps", "tool_calls", "usage",
            "artifacts", "improvement_proposals", "eval_cases",
            // v3: the council's structured summaries go with their tasks (never prompts or reasoning).
            "council_runs", "council_agent_slots", "council_rounds", "council_contributions", "council_claims",
            "council_evidence_refs", "council_concerns", "council_votes", "council_decisions",
        )

        /** Everything else, and why it stays on this instance (a test keeps this classification complete). */
        val EXCLUDED = mapOf(
            "messages_fts" to "index plein texte dérivé, reconstruit", "memories_fts" to "index plein texte dérivé, reconstruit",
            "memory_vectors" to "index sémantique dérivé, reconstruit par l'indexeur",
            "audit" to "chaîne d'audit propre à l'instance (preuve locale, non fusionnable)",
            "outbox" to "effets en attente : jamais rejoués sur une autre instance",
            "idempotency_ledger" to "registre anti-répétition propre à l'instance",
            "approvals" to "demandes d'approbation transitoires",
            "plans" to "état de reprise des tâches, non transférable", "checkpoints" to "état de reprise des tâches, non transférable",
            "task_notebooks" to "état de reprise des tâches, non transférable",
            "workspaces" to "projets sur disque de l'instance (copie par Git)", "changesets" to "modifications de projets de l'instance",
            "workers" to "appairage lié aux clés de l'appareil", "plugins" to "paquets vérifiés sur disque : réinstaller",
            "plugin_versions" to "paquets vérifiés sur disque : réinstaller",
            "connection_events" to "journal technique et événements entrants en attente",
            "schedule_runs" to "exécutions propres à l'instance", "spans" to "télémétrie locale",
            "chat_queue" to "messages en file d'envoi : jamais envoyés automatiquement sur une autre instance",
        )
    }
}
