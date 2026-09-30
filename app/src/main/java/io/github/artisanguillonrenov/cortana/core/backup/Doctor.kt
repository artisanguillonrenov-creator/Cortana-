package io.github.artisanguillonrenov.cortana.core.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One doctor finding; [repair] names the reversible repair available, if any. */
data class DoctorCheck(val id: String, val label: String, val status: String, val detail: String, val repair: String? = null) {
    val ok get() = status == "ok"
}

/**
 * Database Doctor (doc 06 §14, phase 29): integrity, WAL, schema, full-text and vector indexes,
 * artifacts, skills, stuck tasks, workspace leases, scheduler, secret handles, audit chain, disk.
 * Checks only read. Repairs run one at a time on the owner's request, are audited, and are
 * reversible where it matters (orphan files are moved to a quarantine folder, never deleted).
 */
class DatabaseDoctor(
    private val db: CortanaDatabase,
    private val dbFile: () -> File?,
    private val filesDir: File,
    private val artifactsRoot: File,
    private val secrets: SecretAccess,
    private val audit: AuditLog,
    private val hooks: Hooks,
) {
    /** What the doctor needs from the rest of Cortana, without depending on it. */
    interface Hooks {
        fun busy(): Boolean
        suspend fun recoverTasks()
        suspend fun reindexMemories(): Int
        suspend fun rearmSchedules()
        suspend fun embedderFingerprint(): String?
    }

    private val sdb: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    suspend fun run(now: Long = System.currentTimeMillis()): List<DoctorCheck> = withContext(Dispatchers.IO) {
        listOf(::integrity, ::wal, ::schema, ::fts, ::vectors, ::artifacts, ::skills, ::tasks, ::leases, ::scheduler, ::secretHandles, ::auditChain, ::disk)
            .map { check -> runCatching { check(now) }.getOrElse { DoctorCheck(check.name, check.name, "error", "vérification impossible : ${it.message}") } }
    }

    // ------------------------------------------------------------------ checks

    private suspend fun integrity(now: Long): DoctorCheck {
        val r = sdb.query("PRAGMA quick_check").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        return when {
            r == listOf("ok") -> DoctorCheck("integrity", "Intégrité de la base", "ok", "quick_check : ok")
            // Only a derived full-text index is damaged: rebuilt from the tables, nothing is lost.
            r.all { FTS_ONLY.containsMatchIn(it) } -> DoctorCheck("integrity", "Intégrité de la base", "warn", "quick_check : ${r.take(3).joinToString("; ")}", "Reconstruire les index plein texte")
            else -> DoctorCheck("integrity", "Intégrité de la base", "error", "quick_check : ${r.take(3).joinToString("; ")} — restaurer la dernière sauvegarde (Réglages → Sauvegarde)")
        }
    }

    private suspend fun wal(now: Long): DoctorCheck {
        val (busy, log, done) = sdb.query("PRAGMA wal_checkpoint(PASSIVE)").use { c -> c.moveToFirst(); Triple(c.getInt(0), c.getInt(1), c.getInt(2)) }
        val pending = (log - done).coerceAtLeast(0)
        return if (pending > 2_000 || busy != 0) DoctorCheck("wal", "Journal WAL", "warn", "$pending page(s) non reportées", "Reporter le journal (checkpoint)")
        else DoctorCheck("wal", "Journal WAL", "ok", if (log < 0) "mode sans WAL" else "$log page(s), reportées")
    }

    private suspend fun schema(now: Long): DoctorCheck {
        val v = sdb.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }
        return if (v == CortanaDatabase.VERSION) DoctorCheck("schema", "Version du schéma", "ok", "v$v")
        else DoctorCheck("schema", "Version du schéma", "error", "v$v au lieu de v${CortanaDatabase.VERSION}")
    }

    /** Recent rows must be found through their full-text index. */
    private suspend fun fts(now: Long): DoctorCheck {
        val missing = probe("messages", "messages_fts") + probe("memories", "memories_fts")
        return if (missing == 0) DoctorCheck("fts", "Index plein texte", "ok", "recherches de contrôle retrouvées")
        else DoctorCheck("fts", "Index plein texte", "warn", "$missing ligne(s) récente(s) introuvable(s) par la recherche", "Reconstruire les index plein texte")
    }

    private fun probe(table: String, fts: String): Int {
        val rows = sdb.query("SELECT rowid, text FROM `$table` ORDER BY rowid DESC LIMIT 20").use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toList() }
        return rows.count { (rowid, text) ->
            val word = Regex("[\\p{L}\\p{N}]{5,}").find(text ?: "")?.value ?: return@count false
            sdb.query("SELECT COUNT(*) FROM `$fts` WHERE `$fts` MATCH ? AND rowid = ?", arrayOf<Any?>("\"$word\"", rowid)).use { it.moveToFirst(); it.getInt(0) } == 0
        }
    }

    private suspend fun vectors(now: Long): DoctorCheck {
        val fp = hooks.embedderFingerprint()
        val active = sdb.query("SELECT COUNT(*) FROM memories WHERE status = 'active'").use { it.moveToFirst(); it.getInt(0) }
        val indexed = if (fp == null) 0 else sdb.query("SELECT COUNT(*) FROM memory_vectors v JOIN memories m ON m.id = v.memoryId WHERE m.status = 'active' AND v.fingerprint = ?", arrayOf<Any?>(fp)).use { it.moveToFirst(); it.getInt(0) }
        val stale = active - indexed
        return if (stale <= 0) DoctorCheck("vectors", "Index sémantique de la mémoire", "ok", "$indexed/$active souvenir(s) indexé(s)")
        else DoctorCheck("vectors", "Index sémantique de la mémoire", "warn", "$stale souvenir(s) actif(s) sans vecteur à jour", "Réindexer la mémoire")
    }

    private data class Orphans(val missingFiles: List<String>, val orphanDirs: List<File>)

    private fun orphans(): Orphans {
        val rows = sdb.query("SELECT artifactId, uri FROM artifacts WHERE deleted = 0").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toList() }
        val missing = rows.filter { (_, uri) -> !File(uri.removePrefix("file://")).isFile }.map { it.first }
        val known = sdb.query("SELECT artifactId FROM artifacts").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
        val dirs = artifactsRoot.listFiles()?.filter { it.isDirectory && it.name !in known }.orEmpty()
        return Orphans(missing, dirs)
    }

    private suspend fun artifacts(now: Long): DoctorCheck {
        val o = orphans()
        return if (o.missingFiles.isEmpty() && o.orphanDirs.isEmpty()) DoctorCheck("artifacts", "Artefacts", "ok", "fichiers et lignes cohérents")
        else DoctorCheck("artifacts", "Artefacts", "warn", "${o.missingFiles.size} artefact(s) sans fichier, ${o.orphanDirs.size} dossier(s) sans artefact",
            "Marquer les artefacts perdus et mettre les dossiers orphelins en quarantaine")
    }

    private fun invalidSkills(): List<String> = sdb.query(
        "SELECT s.skillId FROM skills s LEFT JOIN skill_versions v ON v.skillId = s.skillId AND v.version = s.currentVersion WHERE s.enabled = 1 AND (v.skillId IS NULL OR v.definitionJson NOT LIKE '{%')",
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private suspend fun skills(now: Long): DoctorCheck {
        val bad = invalidSkills()
        return if (bad.isEmpty()) DoctorCheck("skills", "Procédures", "ok", "définitions présentes")
        else DoctorCheck("skills", "Procédures", "warn", "${bad.size} procédure(s) active(s) sans définition lisible", "Désactiver ces procédures")
    }

    private suspend fun tasks(now: Long): DoctorCheck {
        val stuck = sdb.query("SELECT COUNT(*) FROM tasks WHERE state NOT IN ('completed','failed','cancelled','timed_out','halted','waiting_user','waiting_authorization','paused') AND (leaseExpiresAt IS NULL OR leaseExpiresAt < ?)", arrayOf<Any?>(now))
            .use { it.moveToFirst(); it.getInt(0) }
        return if (stuck == 0 || hooks.busy()) DoctorCheck("tasks", "Tâches bloquées", "ok", if (stuck == 0) "aucune" else "une tâche est en cours")
        else DoctorCheck("tasks", "Tâches bloquées", "warn", "$stuck tâche(s) non terminée(s) sans propriétaire actif", "Reprendre ou clore (récupération au démarrage)")
    }

    private fun staleLeases(now: Long): List<String> = sdb.query(
        "SELECT w.workspaceId FROM workspaces w LEFT JOIN tasks t ON t.id = w.lockTaskId WHERE w.lockTaskId IS NOT NULL AND ((w.lockUntil IS NOT NULL AND w.lockUntil < ?) OR t.id IS NULL OR t.state IN ('completed','failed','cancelled','timed_out','halted'))",
        arrayOf<Any?>(now),
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private suspend fun leases(now: Long): DoctorCheck {
        val stale = staleLeases(now)
        return if (stale.isEmpty()) DoctorCheck("leases", "Verrous de projets", "ok", "aucun verrou périmé")
        else DoctorCheck("leases", "Verrous de projets", "warn", "${stale.size} projet(s) verrouillé(s) par une tâche finie ou disparue", "Libérer ces verrous")
    }

    private suspend fun scheduler(now: Long): DoctorCheck {
        val missed = sdb.query("SELECT COUNT(*) FROM schedules WHERE enabled = 1 AND (nextRunAt IS NULL OR nextRunAt < ?)", arrayOf<Any?>(now - 3_600_000)).use { it.moveToFirst(); it.getInt(0) }
        val running = if (hooks.busy()) 0 else sdb.query("SELECT COUNT(*) FROM schedule_runs WHERE status = 'running'").use { it.moveToFirst(); it.getInt(0) }
        return if (missed + running == 0) DoctorCheck("scheduler", "Planificateur", "ok", "alarmes à jour")
        else DoctorCheck("scheduler", "Planificateur", "warn", "$missed planification(s) en retard, $running exécution(s) « en cours » sans tâche", "Réarmer et rattraper")
    }

    /** Handles referenced by providers, connections and settings that the vault no longer has. */
    fun missingHandles(): List<String> {
        val text = buildString {
            sdb.query("SELECT apiKeyHandle FROM providers WHERE apiKeyHandle IS NOT NULL").use { c -> while (c.moveToNext()) append(c.getString(0)).append('\n') }
            sdb.query("SELECT secretHandlesJson FROM connections WHERE state != 'revoked'").use { c -> while (c.moveToNext()) append(c.getString(0)).append('\n') }
            sdb.query("SELECT valueJson FROM settings").use { c -> while (c.moveToNext()) append(c.getString(0)).append('\n') }
        }
        return BackupService.HANDLE.findAll(text).map { it.value }.distinct().filter { !secrets.has(it) }.toList()
    }

    private suspend fun secretHandles(now: Long): DoctorCheck {
        val missing = missingHandles()
        return if (missing.isEmpty()) DoctorCheck("secrets", "Secrets référencés", "ok", "tous présents dans le coffre")
        else DoctorCheck("secrets", "Secrets référencés", "warn", "${missing.size} secret(s) absent(s) du coffre (clé de fournisseur, connexion ou réglage à ressaisir)")
    }

    private suspend fun auditChain(now: Long): DoctorCheck {
        val broken = audit.verify()
        return if (broken == null) DoctorCheck("audit", "Chaîne d'audit", "ok", "intègre")
        else DoctorCheck("audit", "Chaîne d'audit", "error", "rompue à l'entrée n° $broken : le journal a été modifié hors de Cortana (conservé tel quel comme preuve)")
    }

    private suspend fun disk(now: Long): DoctorCheck {
        fun size(f: File?): Long = f?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val main = dbFile()
        val dbBytes = listOfNotNull(main, main?.let { File(it.path + "-wal") }, main?.let { File(it.path + "-shm") }).sumOf { if (it.isFile) it.length() else 0L }
        val art = size(artifactsRoot)
        val ws = size(File(filesDir, "workspaces"))
        val free = filesDir.usableSpace
        val mb = { b: Long -> "${b / 1_000_000} Mo" }
        val detail = "base ${mb(dbBytes)}, artefacts ${mb(art)}, projets ${mb(ws)}, libre ${mb(free)}"
        return if (free in 1 until 500_000_000L) DoctorCheck("disk", "Espace disque", "warn", "$detail — moins de 500 Mo libres") else DoctorCheck("disk", "Espace disque", "ok", detail)
    }

    private companion object {
        val FTS_ONLY = Regex("(?i)fts[345]? table|inverted index")
    }

    // ------------------------------------------------------------------ repairs

    /** Runs the repair of check [id] (owner action, audited). Returns what was done. */
    suspend fun repair(id: String, now: Long = System.currentTimeMillis()): String = withContext(Dispatchers.IO) {
        val done = when (id) {
            "wal" -> sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> c.moveToFirst(); "journal reporté (${c.getInt(2)} page(s))" }
            "fts", "integrity" -> {
                if (id == "integrity") {
                    val r = sdb.query("PRAGMA quick_check").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
                    if (r != listOf("ok") && !r.all { FTS_ONLY.containsMatchIn(it) }) throw IllegalStateException("dommage hors des index plein texte : restaurer une sauvegarde")
                }
                sdb.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
                sdb.execSQL("INSERT INTO memories_fts(memories_fts) VALUES('rebuild')")
                "index plein texte reconstruits"
            }
            "vectors" -> "${hooks.reindexMemories()} souvenir(s) indexé(s)"
            "artifacts" -> {
                val o = orphans()
                o.missingFiles.forEach { a -> sdb.execSQL("UPDATE artifacts SET deleted = 1 WHERE artifactId = ?", arrayOf<Any?>(a)) }
                val q = File(filesDir, "quarantine/artifacts/$now").apply { if (o.orphanDirs.isNotEmpty()) mkdirs() }
                o.orphanDirs.forEach { d -> if (!d.renameTo(File(q, d.name))) { d.copyRecursively(File(q, d.name), overwrite = true); d.deleteRecursively() } }
                "${o.missingFiles.size} artefact(s) marqué(s) perdu(s), ${o.orphanDirs.size} dossier(s) mis en quarantaine dans ${q.relativeTo(filesDir)}"
            }
            "skills" -> invalidSkills().also { ids -> ids.forEach { sdb.execSQL("UPDATE skills SET enabled = 0 WHERE skillId = ?", arrayOf<Any?>(it)) } }.let { "${it.size} procédure(s) désactivée(s)" }
            "tasks" -> { if (hooks.busy()) throw IllegalStateException("une tâche est en cours"); hooks.recoverTasks(); "récupération des tâches lancée" }
            "leases" -> staleLeases(now).also { ids -> ids.forEach { sdb.execSQL("UPDATE workspaces SET lockTaskId = NULL, lockUntil = NULL WHERE workspaceId = ?", arrayOf<Any?>(it)) } }.let { "${it.size} verrou(s) libéré(s)" }
            "scheduler" -> { hooks.rearmSchedules(); "alarmes réarmées et exécutions orphelines closes" }
            else -> throw IllegalArgumentException("aucune réparation pour « $id »")
        }
        audit.record("owner", "doctor.repair", id, "ok", """{"result":${kotlinx.serialization.json.JsonPrimitive(done)}}""")
        done
    }
}
