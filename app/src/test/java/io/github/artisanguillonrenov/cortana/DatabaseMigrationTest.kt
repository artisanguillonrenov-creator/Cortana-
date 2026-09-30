package io.github.artisanguillonrenov.cortana

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.HashingEmbedder
import io.github.artisanguillonrenov.cortana.core.memory.MemoryIndexer
import io.github.artisanguillonrenov.cortana.core.memory.MemoryRepository
import io.github.artisanguillonrenov.cortana.core.memory.Migrations
import io.github.artisanguillonrenov.cortana.core.memory.PreMigrationBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Data-migration gate (doc 08 §13): a realistic v1 database is migrated and every row checked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CortanaDatabase::class.java)

    private fun seedV1() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO sessions VALUES ('s1','Recettes',1,2,0,'p1','llama-3.1','full')")
            execSQL("INSERT INTO messages (id,sessionId,role,text,createdAt,usageJson,toolCallsJson,taskId,reasoningJson,hidden) VALUES ('m1','s1','user','Bonjour, une recette de crêpes ?',1,NULL,NULL,NULL,NULL,0)")
            execSQL("INSERT INTO messages (id,sessionId,role,text,createdAt,usageJson,toolCallsJson,taskId,reasoningJson,hidden) VALUES ('m2','s1','assistant','Voici la pâte à crêpes.',2,NULL,NULL,'t1',NULL,0)")
            execSQL("INSERT INTO memories VALUES ('mem1','preference','global','Je préfère des réponses courtes',NULL,'normal',1.0,3,'active',NULL,'{\"source\":\"explicit\"}',1,1)")
            execSQL("INSERT INTO memories VALUES ('mem2','semantic','global','Habite à Lyon',NULL,'normal',1.0,3,'superseded',NULL,'{}',1,1)")
            execSQL("INSERT INTO memories VALUES ('mem3','semantic','global','Habite à Paris depuis 2024',NULL,'normal',1.0,3,'active','mem2','{\"source\":\"owner_edit\"}',2,2)")
            execSQL("INSERT INTO providers VALUES ('p1','Infermatic','infermatic','https://api.totalgpt.ai/v1','secret:abc',NULL,1,0,0,'llama-3.1',NULL,1)")
            execSQL("INSERT INTO schedules VALUES ('sc1','Boire','reminder','{\"at\":99}','Europe/Paris','{\"type\":\"notify\",\"message\":\"Boire\"}',1,99,NULL,'catch_up_once',1,NULL)")
            execSQL("INSERT INTO audit (seq,id,occurredAt,actor,action,targetJson,outcome,metaJson,prevHash,hash) VALUES (1,'a1',1,'owner','provider.create','Infermatic','ok','{}','genesis','h1')")
            execSQL("INSERT INTO settings VALUES ('defaultProviderId','\"p1\"')")
            execSQL("INSERT INTO settings VALUES ('grants','[\"memory.forget\"]')")
            execSQL("INSERT INTO tasks VALUES ('t1','s1','crêpes','interactive','completed',0,'{}',1,2,NULL)")
            execSQL("INSERT INTO tasks VALUES ('t2','s1','long','interactive','limit',0,'{}',3,4,'Limite d''appels')")
            execSQL("INSERT INTO tasks VALUES ('t3','s1','question','interactive','waiting_user',0,'{}',5,6,'Question posée')")
            execSQL("INSERT INTO tasks VALUES ('t4','s1','crash','interactive','running',1,'{}',7,NULL,NULL)")
            execSQL("INSERT INTO tool_calls VALUES ('tc1','t1','st1','notify.owner','{}','key123','{}','ok','Notification envoyée',1)")
            close()
        }
    }

    @Test fun v1ToV2PreservesEveryRowAndIndexes() {
        seedV1()
        val db = helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2)
        fun count(sql: String): Int = db.query(sql).use { it.moveToFirst(); it.getInt(0) }
        fun str(sql: String): String? = db.query(sql).use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals(1, count("SELECT COUNT(*) FROM sessions"))
        assertEquals(2, count("SELECT COUNT(*) FROM messages"))
        assertEquals(3, count("SELECT COUNT(*) FROM memories"))
        assertEquals(1, count("SELECT COUNT(*) FROM memory_edges WHERE fromId='mem3' AND toId='mem2' AND relation='supersedes'"))
        assertEquals(0, count("SELECT COUNT(*) FROM memory_vectors")) // derived: built by MemoryIndexer, not by SQL
        assertEquals(1, count("SELECT COUNT(*) FROM providers"))
        assertEquals(1, count("SELECT COUNT(*) FROM schedules"))
        assertEquals("skip", str("SELECT concurrencyPolicy FROM schedules WHERE id='sc1'")) // 1.2.0 behaviour: never two runs at once
        assertEquals(0, count("SELECT COUNT(*) FROM schedule_runs"))
        assertEquals(0, count("SELECT COUNT(*) FROM improvement_proposals") + count("SELECT COUNT(*) FROM eval_cases") + count("SELECT COUNT(*) FROM spans"))
        assertEquals(1, count("SELECT COUNT(*) FROM audit"))
        assertEquals(2, count("SELECT COUNT(*) FROM settings"))
        assertEquals(4, count("SELECT COUNT(*) FROM tasks"))
        // v1 vocabulary mapped, termination reason kept separate from state
        assertEquals("failed", str("SELECT state FROM tasks WHERE id='t2'"))
        assertTrue(str("SELECT terminationReason FROM tasks WHERE id='t2'")!!.startsWith("budget_exhausted"))
        assertEquals("completed", str("SELECT state FROM tasks WHERE id='t3'"))
        assertEquals("interrupted", str("SELECT state FROM tasks WHERE id='t4'"))
        assertEquals("t1", str("SELECT traceId FROM tasks WHERE id='t1'"))
        assertEquals(4, count("SELECT COUNT(*) FROM task_events"))
        assertEquals(1, count("SELECT COUNT(*) FROM idempotency_ledger WHERE status='succeeded' AND capability='notify.owner'"))
        // FTS still consistent after migration
        assertEquals(2, count("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'crêpes'"))
        assertEquals(1, count("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'pâte'"))
        assertEquals(1, count("SELECT COUNT(*) FROM memories_fts WHERE memories_fts MATCH 'courtes'"))
        db.close()

        // Room opens the migrated file and its DAOs work (new FTS rows are indexed by the triggers).
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CortanaDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            assertEquals("Bonjour, une recette de crêpes ?", room.messages().search("recette*").single().text)
            assertEquals(4, room.tasks().taskCount())
            // Backfill of the derived vector index for the active memories, then hybrid retrieval.
            val indexer = MemoryIndexer(room, this) { HashingEmbedder() }
            assertEquals(2, indexer.sync())
            assertEquals(2, room.memories().vectors("local-hash-v1-384").size)
            val repo = MemoryRepository(room).also { it.indexer = indexer }
            assertEquals("mem3", repo.retrieve("où j'habite à Paris").first().memory.id)
        }
        room.close()
    }

    @Test fun preMigrationBackupIsWrittenForOlderSchema() {
        seedV1()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backup = PreMigrationBackup.runIfNeeded(ctx, name, 2)
        assertNotNull(backup)
        assertTrue(backup!!.length() > 0)
        // a database already at the target version needs no backup
        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).close()
        assertEquals(null, PreMigrationBackup.runIfNeeded(ctx, name, 2))
    }

    private fun counts(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, Int> =
        listOf("sessions", "messages", "memories", "memory_edges", "providers", "schedules", "audit", "settings", "tasks", "task_events", "tool_calls", "idempotency_ledger")
            .associateWith { t -> db.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) } }

    /** v2 → v3 (council tables) is additive: every v2 row stays, the nine council tables exist and are empty. */
    @Test fun v2ToV3IsAdditiveAndTheCouncilStoreWorks() {
        seedV1()
        val before = helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).let { db -> counts(db).also { db.close() } }
        val db = helper.runMigrationsAndValidate(name, 3, true, Migrations.MIGRATION_2_3)
        assertEquals(before, counts(db))
        listOf("council_runs", "council_agent_slots", "council_rounds", "council_contributions", "council_claims", "council_evidence_refs",
            "council_concerns", "council_votes", "council_decisions").forEach { t -> assertEquals(t, 0, db.query("SELECT COUNT(*) FROM $t").use { it.moveToFirst(); it.getInt(0) }) }
        assertEquals(1, db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_council_rounds_runId_roundIndex'").use { it.moveToFirst(); it.getInt(0) })
        db.close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CortanaDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(4, room.tasks().taskCount())
            val store = io.github.artisanguillonrenov.cortana.core.council.CouncilStore(room.council())
            room.council().insertRun(io.github.artisanguillonrenov.cortana.core.memory.CouncilRunEntity("r1", "t1", "council_4", "balanced", "round_initial", 1, configSnapshotJson = "{}"))
            // Restart boundary: a council alive when the process died is closed, never resumed.
            assertEquals(1, store.recoverInterrupted())
            val r = room.council().run("r1")!!
            assertEquals("failed", r.status)
            assertTrue(r.terminationReason!!.contains("interrompu"))
            assertEquals(0, store.recoverInterrupted())
        }
        room.close()
    }

    /** A 1.2.0 database goes straight to v3 (two chained migrations), rows intact. */
    @Test fun v1ToV3InOneUpgrade() {
        seedV1()
        val db = helper.runMigrationsAndValidate(name, 3, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)
        val c = counts(db)
        assertEquals(1, c["sessions"]); assertEquals(2, c["messages"]); assertEquals(3, c["memories"]); assertEquals(4, c["tasks"])
        assertEquals("interrupted", db.query("SELECT state FROM tasks WHERE id='t4'").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }

    /**
     * v3 → v4 (Chat Workspace, D-20260930-068): every conversation becomes one branch in the order the
     * owner saw it (ties broken by insertion order), each session points at its newest message, and the
     * new tables exist and are empty. Nothing is deleted.
     */
    @Test fun v3ToV4ChainsEachConversationInItsHistoricalOrder() {
        helper.createDatabase(name, 3).apply {
            fun session(id: String) = execSQL("INSERT INTO sessions (id,title,createdAt,updatedAt,incognito,providerId,modelId,toolset) VALUES ('$id','$id',1,9,0,NULL,NULL,'full')")
            fun msg(id: String, s: String, role: String, at: Long, text: String = "texte $id") =
                execSQL("INSERT INTO messages (id,sessionId,role,text,createdAt,usageJson,toolCallsJson,taskId,reasoningJson,hidden) VALUES ('$id','$s','$role','$text',$at,NULL,NULL,NULL,NULL,0)")
            session("s1"); session("s2"); session("s3")
            msg("a1", "s1", "user", 1, "Bonjour Cortana"); msg("a2", "s1", "assistant", 2); msg("a3", "s1", "user", 2); msg("b1", "s2", "user", 3)
            msg("a4", "s1", "assistant", 5); msg("b2", "s2", "assistant", 4)
            execSQL("INSERT INTO conversation_summaries (sessionId,coveredUntil,coveredCount,summary,method,updatedAt) VALUES ('s1',2,2,'résumé','extractive',3)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, Migrations.MIGRATION_3_4)
        fun str(sql: String): String? = db.query(sql).use { if (it.moveToFirst()) it.getString(0) else null }
        fun count(sql: String): Int = db.query(sql).use { it.moveToFirst(); it.getInt(0) }
        val parents = db.query("SELECT id, parentId FROM messages ORDER BY id").use { cur -> buildMap { while (cur.moveToNext()) put(cur.getString(0), cur.getString(1)) } }
        assertEquals(mapOf("a1" to null, "a2" to "a1", "a3" to "a2", "a4" to "a3", "b1" to null, "b2" to "b1"), parents)
        assertEquals("a4", str("SELECT activeLeafId FROM sessions WHERE id='s1'"))
        assertEquals("b2", str("SELECT activeLeafId FROM sessions WHERE id='s2'"))
        assertEquals(null, str("SELECT activeLeafId FROM sessions WHERE id='s3'"))
        assertEquals(6, count("SELECT COUNT(*) FROM messages WHERE status='complete' AND runId IS NULL AND metaJson IS NULL"))
        assertEquals(3, count("SELECT COUNT(*) FROM sessions WHERE mode='chat' AND pinned=0 AND archived=0 AND tagsJson='[]' AND settingsJson='{}' AND projectId IS NULL"))
        assertEquals("résumé", str("SELECT summary FROM conversation_summaries WHERE sessionId='s1' AND coveredUntilMessageId IS NULL"))
        listOf("projects", "chat_drafts", "chat_queue", "chat_pins", "context_checkpoints").forEach { t -> assertEquals(t, 0, count("SELECT COUNT(*) FROM $t")) }
        assertEquals(1, count("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'cortana'"))
        db.close()

        // Room opens it; the path is the old order; a new message continues the branch.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CortanaDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        runBlocking {
            val repo = io.github.artisanguillonrenov.cortana.core.memory.ConversationRepository(room)
            assertEquals(listOf("a1", "a2", "a3", "a4"), repo.messages("s1").map { it.id })
            assertEquals(listOf("b1", "b2"), repo.messages("s2").map { it.id })
            assertEquals(emptyList<String>(), repo.messages("s3").map { it.id })
            assertEquals("a4", repo.addMessage("s1", "user", "suite").parentId)
            assertEquals("Bonjour Cortana", room.messages().search("cortana*").single().text)
        }
        room.close()
    }

    /** A 1.2.0 database goes straight to v4 (three chained migrations): rows intact, conversation linked. */
    @Test fun v1ToV4InOneUpgrade() {
        seedV1()
        val db = helper.runMigrationsAndValidate(name, 4, true, Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4)
        val c = counts(db)
        assertEquals(1, c["sessions"]); assertEquals(2, c["messages"]); assertEquals(3, c["memories"]); assertEquals(4, c["tasks"])
        assertEquals("m1", db.query("SELECT parentId FROM messages WHERE id='m2'").use { it.moveToFirst(); it.getString(0) })
        assertEquals("m2", db.query("SELECT activeLeafId FROM sessions WHERE id='s1'").use { it.moveToFirst(); it.getString(0) })
        db.close()
    }
}
