package io.github.artisanguillonrenov.cortana

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.artisanguillonrenov.cortana.core.backup.BackupException
import io.github.artisanguillonrenov.cortana.core.backup.BackupManifest
import io.github.artisanguillonrenov.cortana.core.backup.BackupOptions
import io.github.artisanguillonrenov.cortana.core.backup.BackupService
import io.github.artisanguillonrenov.cortana.core.backup.DatabaseDoctor
import io.github.artisanguillonrenov.cortana.core.backup.RestoreMode
import io.github.artisanguillonrenov.cortana.core.backup.SecretAccess
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.EvalCaseEntity
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.Migrations
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillVersionEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Phase 29 gate: a backup of instance A restores on a compatible instance B; doctor checks and repairs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest : CortanaTestBase() {
    private class Vault(val map: MutableMap<String, String> = mutableMapOf()) : SecretAccess {
        override fun get(handle: String) = map[handle]
        override fun put(handle: String, value: String) { map[handle] = value }
        override fun has(handle: String) = map.containsKey(handle)
    }

    private lateinit var dir: File
    private val vaultA = Vault()
    private val passphrase = "tartine-bergamote-42!"
    private val providerKey = "sk-prov-A1B2C3D4E5"
    private val connToken = "tok-conn-Z9Y8X7"
    private val searchKey = "brave-key-QWERTY"
    private lateinit var dbB: CortanaDatabase
    private val now = System.currentTimeMillis()

    @Before fun setUpDirs() {
        dir = File(app.cacheDir, "backup-test-${Ids.new()}").apply { mkdirs() }
        dbB = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CortanaDatabase::class.java, "instance-b-${Ids.new()}.db")
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
    }

    @After fun tearDownDirs() { dbB.close(); dir.deleteRecursively() }

    private fun serviceA() = BackupService(c.db, vaultA, c.artifacts.root, File(dir, "a-backups"), c.compatibility, c.audit)
    private fun serviceB(vault: Vault, db: CortanaDatabase = dbB) = BackupService(db, vault, File(dir, "b-artifacts"), File(dir, "b-backups"), c.compatibility, AuditLog(db.audit()))

    private fun handle() = "secret:" + Ids.new()

    /** Realistic owner data on instance A. Returns the running task id and the artifact id. */
    private fun populateA(): Pair<String, String> = runBlocking {
        val s = c.conversations.createSession(title = "Recettes")
        c.conversations.addMessage(s.id, Roles.USER, "Une recette de crêpes à la châtaigne ?")
        c.conversations.addMessage(s.id, Roles.ASSISTANT, "Voici la pâte : farine de châtaigne, œufs, lait.")
        c.memory.save("Préfère le thé à la bergamote", MemoryTypes.PREFERENCE, MemoryStatus.ACTIVE, "explicit")
        val skill = Ids.new()
        c.db.skills().upsert(SkillEntity(skill, "Arroser", 1, "active", true, "a>b", createdAt = now, updatedAt = now))
        c.db.skills().upsertVersion(SkillVersionEntity(skill, 1, """{"name":"Arroser"}""", "v1", now))
        c.scheduler.create("Boire", ScheduleKinds.REMINDER, ScheduleSpec(at = now + 3_600_000), ScheduleAction("notify", message = "Boire de l'eau"))
        val hp = handle(); vaultA.put(hp, providerKey)
        c.db.providers().upsert(ProviderEntity(Ids.new(), "Infermatic", "infermatic", "https://api.totalgpt.ai/v1", hp, null, true, 0, allowFallback = true, defaultModelId = "m", createdAt = now))
        val hc = handle(); vaultA.put(hc, connToken)
        c.db.connections().upsert(ConnectionEntity(Ids.new(), "telegram", "maison", "token", "{}", """{"bot_token":"$hc"}""", state = "active", createdAt = now, updatedAt = now))
        val hs = handle(); vaultA.put(hs, searchKey)
        c.settings.update { it.copy(maxToolCallsPerTask = 42, searchKeyHandle = hs, searchProvider = "brave") }
        c.db.tasks().upsert(TaskEntity(Ids.new(), s.id, "Résumer", "interactive", "completed", false, "{}", now - 5_000, endedAt = now - 4_000, updatedAt = now - 4_000))
        val running = TaskEntity(Ids.new(), s.id, "En cours", "interactive", "running", false, "{}", now - 1_000, updatedAt = now)
        c.db.tasks().upsert(running)
        c.db.improvements().upsertCase(EvalCaseEntity(Ids.new(), "résumer", "Résumer", """["memory.search"]""", null, now))
        // A finished council (schema v3) travels with its task: structured rows only.
        c.db.council().insertRun(io.github.artisanguillonrenov.cortana.core.memory.CouncilRunEntity("cr1", "t-council", "council_4", "balanced", "completed", now - 3_000,
            completedAt = now - 2_000, configSnapshotJson = "{}", totalTokens = 1_234, summaryJson = """{"consensus":"fort"}"""))
        c.db.council().insertDecision(io.github.artisanguillonrenov.cortana.core.memory.CouncilDecisionEntity("cr1-decision", "cr1", null, "hybrid", "abc123", "{}", "[]", "{}"))
        val art = c.artifacts.registerText("Contrat signé — version finale", "document", "contrat.txt")
        c.memoryIndexer.sync() // derived edges written before the snapshot
        running.id to art.artifactId
    }

    private fun dumpRows(db: CortanaDatabase, table: String): Set<String> = db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cur ->
        val out = mutableSetOf<String>()
        while (cur.moveToNext()) out += (0 until cur.columnCount).joinToString("|") { i -> cur.getColumnName(i) + "=" + (if (cur.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) Hash.sha256Bytes(cur.getBlob(i)) else cur.getString(i)) }
        out
    }

    private fun zipEntries(f: File): Map<String, ByteArray> = ZipInputStream(f.inputStream()).use { z ->
        generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
    }

    private fun rewrite(src: File, dst: File, edit: (MutableMap<String, ByteArray>) -> Unit): File {
        val m = zipEntries(src).toMutableMap(); edit(m)
        ZipOutputStream(dst.outputStream()).use { z -> m.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return dst
    }

    @Test fun gateBackupOfInstanceARestoresOnInstanceB() {
        val (runningTask, artifactId) = populateA()
        val file = File(dir, "a${BackupService.EXT}")
        val manifest = runBlocking { serviceA().create(file, BackupOptions(passphrase, includeSecrets = true, artifactIds = listOf(artifactId))) }
        assertTrue(manifest.includesSecrets && manifest.encryption != null && manifest.mac != null)
        assertEquals(CortanaDatabase.VERSION, manifest.compatibility.dbSchema)
        assertEquals(listOf(artifactId), manifest.artifacts)
        // Encrypted: no plaintext secret or owner text anywhere in the file.
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        listOf(providerKey, connToken, searchKey, "bergamote", "châtaigne").forEach { assertFalse(it, raw.contains(it)) }

        val vaultB = Vault()
        val b = serviceB(vaultB)
        assertFalse(runBlocking { b.inspect(file) }.verified) // passphrase needed
        assertTrue(runBlocking { b.inspect(file, "mauvaise-phrase-123") }.problems.single().contains("incorrecte"))
        val insp = runBlocking { b.inspect(file, passphrase) }
        assertTrue(insp.problems.toString(), insp.verified && insp.compatible)

        val dry = runBlocking { b.restore(file, passphrase, RestoreMode.MERGE, dryRun = true) }
        assertEquals(3, dry.secretsRestored)
        assertTrue(dry.missingSecrets.isEmpty())
        assertEquals(0, dbB.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); it.getInt(0) }) // simulation wrote nothing

        val rep = runBlocking { b.restore(file, passphrase, RestoreMode.REPLACE, dryRun = false) }
        assertNotNull(rep.preRestoreBackup)
        assertTrue(File(rep.preRestoreBackup!!).isFile)
        // Every owner table matches A, row for row (tasks: the running one is closed; artifacts: new location).
        for (t in BackupService.INCLUDED - setOf("tasks", "artifacts")) assertEquals(t, dumpRows(c.db, t), dumpRows(dbB, t))
        assertEquals(dumpRows(c.db, "tasks").size, dumpRows(dbB, "tasks").size)
        val restoredRunning = runBlocking { dbB.tasks().get(runningTask) }!!
        assertEquals("failed", restoredRunning.state)
        assertTrue(restoredRunning.terminationReason!!.startsWith("restored"))
        // Derived indexes rebuilt: full-text search works on B, accents included.
        assertEquals(2, runBlocking { dbB.messages().search("châtaigne") }.size)
        assertEquals("Préfère le thé à la bergamote", runBlocking { dbB.memories().search("bergamote") }.single().text)
        // Settings, secrets, artifacts.
        val settingsB = SettingsRepository(dbB.settings()).also { runBlocking { it.reload() } }
        assertEquals(42, settingsB.current.maxToolCallsPerTask)
        assertEquals(searchKey, vaultB.get(settingsB.current.searchKeyHandle!!))
        assertEquals(setOf(providerKey, connToken, searchKey), vaultB.map.values.toSet())
        val artB = runBlocking { dbB.dev().artifact(artifactId) }!!
        val fB = File(artB.uri.removePrefix("file://"))
        assertTrue(fB.path.startsWith(File(dir, "b-artifacts").path))
        assertEquals(artB.sha256, Hash.sha256File(fB))
        assertEquals("Contrat signé — version finale", fB.readText())
        // The doctor of B finds it healthy (no secret missing, no orphan).
        val doc = DatabaseDoctor(dbB, { null }, dir, File(dir, "b-artifacts"), vaultB, AuditLog(dbB.audit()), hooks())
        val checks = runBlocking { doc.run() }.associateBy { it.id }
        assertTrue(checks.toString(), listOf("integrity", "schema", "fts", "artifacts", "secrets", "audit", "skills").all { checks.getValue(it).ok })
    }

    @Test fun secretsNeverLeaveUnencryptedAndWeakPassphrasesAreRefused() {
        populateA()
        val a = serviceA()
        val refused = runCatching { runBlocking { a.create(File(dir, "x${BackupService.EXT}"), BackupOptions(includeSecrets = true)) } }.exceptionOrNull()
        assertTrue(refused is BackupException && refused.message!!.contains("phrase de passe"))
        assertTrue(runCatching { runBlocking { a.create(File(dir, "y${BackupService.EXT}"), BackupOptions("court", includeSecrets = true)) } }.isFailure)
        val plain = File(dir, "plain${BackupService.EXT}")
        val m = runBlocking { a.create(plain) }
        assertFalse(m.includesSecrets); assertNull(m.encryption)
        val entries = zipEntries(plain)
        assertFalse(entries.containsKey("secrets.json"))
        val all = entries.values.joinToString("") { String(it) }
        listOf(providerKey, connToken, searchKey).forEach { assertFalse(all.contains(it)) }
        assertTrue(all.contains("bergamote")) // owner data is there, only secrets are withheld
        // Restored elsewhere without its secrets: reported, never invented.
        val rep = runBlocking { serviceB(Vault()).restore(plain, null, RestoreMode.REPLACE, dryRun = false) }
        assertEquals(3, rep.missingSecrets.size)
        val doc = DatabaseDoctor(dbB, { null }, dir, File(dir, "b-artifacts"), Vault(), AuditLog(dbB.audit()), hooks())
        assertEquals("warn", runBlocking { doc.run() }.single { it.id == "secrets" }.status)
    }

    @Test fun corruptedTamperedForeignOrNewerBackupsAreRefused() {
        populateA()
        val a = serviceA()
        val plain = File(dir, "p${BackupService.EXT}").also { runBlocking { a.create(it) } }
        val enc = File(dir, "e${BackupService.EXT}").also { runBlocking { a.create(it, BackupOptions(passphrase)) } }
        val b = serviceB(Vault())
        fun refused(f: File, pass: String?, expect: String) {
            val i = runBlocking { b.inspect(f, pass) }
            assertFalse(i.verified)
            assertTrue("${i.problems}", i.problems.joinToString().contains(expect))
            assertTrue(runCatching { runBlocking { b.restore(f, pass, RestoreMode.REPLACE, dryRun = false) } }.exceptionOrNull() is BackupException)
        }
        refused(rewrite(plain, File(dir, "t1${BackupService.EXT}")) { m -> m["data/memories.jsonl"] = m.getValue("data/memories.jsonl").also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } }, null, "empreinte invalide")
        refused(rewrite(plain, File(dir, "t2${BackupService.EXT}")) { m -> m["../evil.sh"] = "rm -rf".toByteArray() }, null, "entrée inattendue")
        refused(rewrite(plain, File(dir, "t3${BackupService.EXT}")) { m -> m["data/extra.jsonl"] = "{}".toByteArray() }, null, "non déclarée")
        refused(rewrite(enc, File(dir, "t4${BackupService.EXT}")) { m ->
            val man = AppJson.decodeFromString(BackupManifest.serializer(), String(m.getValue("manifest.json")))
            m["manifest.json"] = AppJson.encodeToString(BackupManifest.serializer(), man.copy(tables = man.tables - "memories")).toByteArray()
        }, passphrase, "manifeste modifié")
        refused(File(dir, "n${BackupService.EXT}").apply { writeText("pas un zip") }, null, "manifeste")
        // A backup from a newer schema is verified but not compatible.
        val newer = rewrite(plain, File(dir, "t5${BackupService.EXT}")) { m ->
            val man = AppJson.decodeFromString(BackupManifest.serializer(), String(m.getValue("manifest.json")))
            m["manifest.json"] = AppJson.encodeToString(BackupManifest.serializer(), man.copy(compatibility = man.compatibility.copy(dbSchema = io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase.VERSION + 1))).toByteArray()
        }
        val i = runBlocking { b.inspect(newer) }
        assertTrue(i.verified && !i.compatible && i.problems.single().contains("plus récent"))
        assertTrue(runCatching { runBlocking { b.restore(newer, null, RestoreMode.MERGE, dryRun = true) } }.isFailure)
        // Nothing was written to B by any refused restore.
        assertEquals(0, dbB.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memories").use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun mergeKeepsLocalRowsAndReportsConflicts() {
        populateA()
        val file = File(dir, "m${BackupService.EXT}").also { runBlocking { serviceA().create(it) } }
        val mem = runBlocking { c.memory.search("bergamote") }.single()
        runBlocking {
            dbB.memories().upsert(mem.copy(text = "Préfère le café"))
        }
        val b = serviceB(Vault())
        val dry = runBlocking { b.restore(file, null, RestoreMode.MERGE, dryRun = true) }
        assertEquals(1, dry.tables.single { it.table == "memories" }.conflicts)
        val rep = runBlocking { b.restore(file, null, RestoreMode.MERGE, dryRun = false) }
        assertEquals(1, rep.tables.single { it.table == "memories" }.conflicts)
        assertEquals("Préfère le café", runBlocking { dbB.memories().get(mem.id) }!!.text) // local wins in a merge
        assertTrue(rep.tables.single { it.table == "messages" }.inserted == 2)
        val again = runBlocking { b.restore(file, null, RestoreMode.MERGE, dryRun = true) }
        assertEquals(2, again.tables.single { it.table == "messages" }.identical)
        // Replace is explicit and takes the backup's version.
        runBlocking { b.restore(file, null, RestoreMode.REPLACE, dryRun = false) }
        assertEquals("Préfère le thé à la bergamote", runBlocking { dbB.memories().get(mem.id) }!!.text)
    }

    @Test fun everyTableIsClassifiedForBackup() {
        val tables = c.db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cur -> generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.toSet() }
            .filter { !it.startsWith("sqlite_") && it != "room_master_table" && it != "android_metadata" && !Regex(".*_fts_.*").matches(it) }.toSet()
        val classified = BackupService.INCLUDED.toSet() + BackupService.EXCLUDED.keys
        assertEquals(tables, classified)
        assertTrue((BackupService.INCLUDED.toSet() intersect BackupService.EXCLUDED.keys).isEmpty())
    }

    private fun hooks(busy: Boolean = false, onRecover: () -> Unit = {}) = object : DatabaseDoctor.Hooks {
        override fun busy() = busy
        override suspend fun recoverTasks() = onRecover()
        override suspend fun reindexMemories() = 0
        override suspend fun rearmSchedules() {}
        override suspend fun embedderFingerprint(): String? = null
    }

    @Test fun doctorFindsAndRepairsReversibly() = runBlocking {
        populateA()
        val vault = Vault(vaultA.map.toMutableMap())
        var recovered = false
        val doc = DatabaseDoctor(c.db, { app.getDatabasePath(CortanaDatabase.NAME) }, app.filesDir, c.artifacts.root, vault, c.audit, hooks { recovered = true })
        val sdb = c.db.openHelper.writableDatabase
        // Break things the doctor must see.
        sdb.execSQL("DELETE FROM messages_fts WHERE rowid = (SELECT MAX(rowid) FROM messages)") // index entry gone, row still there
        val orphan = File(c.artifacts.root, Ids.new()).apply { mkdirs(); File(this, "perdu.txt").writeText("x") }
        val lost = c.artifacts.registerText("disparu", "document", "disparu.txt").also { File(it.uri.removePrefix("file://")).delete() }
        val ws = Ids.new()
        c.db.dev().upsertWorkspace(WorkspaceEntity(workspaceId = ws, name = "projet", rootPath = "/nulle/part", origin = "local", lockTaskId = "tâche-disparue", lockUntil = now + 3_600_000, createdAt = now, lastOpenedAt = now))
        val badSkill = Ids.new()
        c.db.skills().upsert(SkillEntity(badSkill, "Cassée", 3, "active", true, "x>y", createdAt = now, updatedAt = now))
        vault.map.remove(vault.map.entries.first { it.value == providerKey }.key)
        c.audit.record("owner", "test", null, "ok")
        sdb.execSQL("UPDATE audit SET outcome = 'falsifié' WHERE seq = (SELECT MIN(seq) FROM audit)")

        val found = doc.run().associateBy { it.id }
        assertEquals("warn", found.getValue("fts").status)
        assertEquals("warn", found.getValue("artifacts").status)
        assertEquals("warn", found.getValue("leases").status)
        assertEquals("warn", found.getValue("skills").status)
        assertEquals("warn", found.getValue("secrets").status)
        assertEquals("error", found.getValue("audit").status)
        assertEquals("warn", found.getValue("tasks").status) // the running task of A has no live owner
        // SQLite's own check sees the damaged full-text index: repairable by a rebuild, not a restore.
        assertEquals("warn", found.getValue("integrity").status)
        assertTrue(found.getValue("integrity").detail.contains("FTS4"))
        assertTrue(found.getValue("schema").ok)

        listOf("fts", "artifacts", "leases", "skills", "tasks").forEach { doc.repair(it) }
        val after = doc.run().associateBy { it.id }
        listOf("integrity", "fts", "artifacts", "leases", "skills").forEach { assertTrue("$it ${after[it]}", after.getValue(it).ok) }
        assertTrue(recovered)
        // Reversible: the orphan folder is in quarantine, not deleted; the lost artifact is only marked.
        assertFalse(orphan.exists())
        assertTrue(File(app.filesDir, "quarantine/artifacts").walkTopDown().any { it.name == "perdu.txt" })
        assertTrue(runBlocking { c.db.dev().artifact(lost.artifactId) }!!.deleted)
        assertFalse(runBlocking { c.db.skills().get(badSkill) }!!.enabled)
        assertNull(runBlocking { c.db.dev().workspace(ws) }!!.lockTaskId)
        // Evidence is never "repaired".
        assertEquals("error", after.getValue("audit").status)
        assertTrue(runCatching { doc.repair("audit") }.isFailure)
        assertTrue(c.db.audit().allAscending().count { it.action == "doctor.repair" } >= 5)
    }
}
