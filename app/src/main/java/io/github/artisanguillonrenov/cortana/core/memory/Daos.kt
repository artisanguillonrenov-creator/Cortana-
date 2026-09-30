package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Upsert suspend fun upsert(s: SessionEntity)
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id = :id") fun observe(id: String): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(): SessionEntity?
    @Query("UPDATE sessions SET updatedAt = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE sessions SET activeLeafId = :leafId, updatedAt = :at WHERE id = :id") suspend fun setLeaf(id: String, leafId: String?, at: Long)
    @Query("UPDATE sessions SET activeLeafId = :leafId WHERE id = :id") suspend fun setLeafOnly(id: String, leafId: String?)
    @Query("SELECT * FROM sessions WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC") fun observeActive(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE archived = 1 ORDER BY updatedAt DESC") fun observeArchived(): Flow<List<SessionEntity>>
    /** Last visible line of each conversation (sidebar preview). */
    @Query(
        "SELECT s.id AS sessionId, (SELECT m.text FROM messages m WHERE m.sessionId = s.id AND m.hidden = 0 AND m.role IN ('user','assistant') " +
            "ORDER BY m.createdAt DESC, m.rowid DESC LIMIT 1) AS preview FROM sessions s"
    )
    fun observePreviews(): Flow<List<SessionPreview>>
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC") suspend fun forProject(projectId: String): List<SessionEntity>
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC") suspend fun all(): List<SessionEntity>
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(m: MessageEntity)
    /** History of the design: visible messages, branch ends and attached files per conversation (read only). */
    @Query("SELECT sessionId, COUNT(*) AS n FROM messages WHERE hidden = 0 AND role IN ('user', 'assistant') GROUP BY sessionId") suspend fun visibleCounts(): List<SessionCount>
    @Query("SELECT m.sessionId AS sessionId, COUNT(*) AS n FROM messages m WHERE m.hidden = 0 AND NOT EXISTS (SELECT 1 FROM messages k WHERE k.parentId = m.id) GROUP BY m.sessionId") suspend fun leafCounts(): List<SessionCount>
    @Query("SELECT DISTINCT sessionId FROM messages WHERE metaJson LIKE '%\"attachments\":[{%'") suspend fun sessionsWithAttachments(): List<String>
    /** Updates in place (same rowid): used for the periodic snapshots of a streaming answer. */
    @Upsert suspend fun upsert(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun get(id: String): MessageEntity?

    // ---- v4 tree (Chat Workspace): the active branch is the path from a leaf up to the root ----
    @Query(
        "WITH RECURSIVE path(id, depth) AS (SELECT id, 0 FROM messages WHERE id = :leafId " +
            "UNION ALL SELECT m.parentId, path.depth + 1 FROM messages m JOIN path ON m.id = path.id WHERE m.parentId IS NOT NULL AND path.depth < :limit) " +
            "SELECT messages.* FROM messages JOIN path ON messages.id = path.id ORDER BY path.depth DESC"
    )
    suspend fun path(leafId: String, limit: Int = 100_000): List<MessageEntity>
    @Query(
        "WITH RECURSIVE path(id, depth) AS (SELECT id, 0 FROM messages WHERE id = :leafId " +
            "UNION ALL SELECT m.parentId, path.depth + 1 FROM messages m JOIN path ON m.id = path.id WHERE m.parentId IS NOT NULL AND path.depth < :limit) " +
            "SELECT messages.* FROM messages JOIN path ON messages.id = path.id ORDER BY path.depth DESC"
    )
    fun observePath(leafId: String, limit: Int): Flow<List<MessageEntity>>
    /** Following the newest child at each level from [startId] down to a leaf. */
    @Query(
        "WITH RECURSIVE down(id, depth) AS (SELECT :startId, 0 UNION ALL " +
            "SELECT (SELECT c.id FROM messages c WHERE c.parentId = down.id ORDER BY c.createdAt DESC, c.rowid DESC LIMIT 1), down.depth + 1 FROM down " +
            "WHERE down.depth < 100000 AND EXISTS (SELECT 1 FROM messages c WHERE c.parentId = down.id)) " +
            "SELECT id FROM down ORDER BY depth DESC LIMIT 1"
    )
    suspend fun newestLeafUnder(startId: String): String?
    @Query("SELECT id, parentId, role, hidden, createdAt, metaJson FROM messages WHERE parentId IN (:parentIds) ORDER BY createdAt ASC, rowid ASC")
    suspend fun childrenOf(parentIds: List<String>): List<MessageNode>
    @Query("SELECT id, parentId, role, hidden, createdAt, metaJson FROM messages WHERE sessionId = :sessionId AND parentId IS NULL ORDER BY createdAt ASC, rowid ASC")
    suspend fun roots(sessionId: String): List<MessageNode>
    @Query("SELECT id FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, rowid DESC LIMIT 1") suspend fun newestId(sessionId: String): String?
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND parentId IS NULL") suspend fun rootCount(sessionId: String): Int
    @Query("SELECT id FROM messages WHERE sessionId = :sessionId AND parentId IS NULL ORDER BY createdAt ASC, rowid ASC") suspend fun unlinked(sessionId: String): List<String>
    @Query("UPDATE messages SET parentId = :parentId WHERE id = :id") suspend fun setParent(id: String, parentId: String?)
    @Query("SELECT * FROM messages WHERE status = 'streaming'") suspend fun streaming(): List<MessageEntity>
    @Query("UPDATE messages SET status = :status WHERE id = :id") suspend fun setStatus(id: String, status: String)
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId") suspend fun count(sessionId: String): Int
    /** [rootId] and every message below it, on every branch. */
    @Query(
        "WITH RECURSIVE sub(id, depth) AS (SELECT :rootId, 0 UNION ALL SELECT m.id, sub.depth + 1 FROM messages m JOIN sub ON m.parentId = sub.id WHERE sub.depth < 100000) " +
            "SELECT id FROM sub"
    )
    suspend fun subtree(rootId: String): List<String>
    @Query("DELETE FROM messages WHERE id IN (:ids)") suspend fun deleteAll(ids: List<String>)
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    fun observeSession(sessionId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, rowid ASC")
    suspend fun forSession(sessionId: String): List<MessageEntity>
    @Query(
        "SELECT messages.* FROM messages JOIN messages_fts ON messages.rowid = messages_fts.rowid " +
            "WHERE messages_fts MATCH :query AND messages.role IN ('user','assistant') ORDER BY messages.createdAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 100): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' AND role IN ('user','assistant') ORDER BY createdAt DESC LIMIT :limit")
    suspend fun searchLike(q: String, limit: Int = 100): List<MessageEntity>
    @Query("DELETE FROM messages WHERE sessionId = :sessionId") suspend fun deleteSession(sessionId: String)
    @Query("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')") suspend fun rebuildFts()
    @Query("SELECT * FROM conversation_summaries WHERE sessionId = :sessionId") suspend fun summary(sessionId: String): ConversationSummaryEntity?
    @Upsert suspend fun upsertSummary(s: ConversationSummaryEntity)
    @Query("DELETE FROM conversation_summaries WHERE sessionId = :sessionId") suspend fun deleteSummary(sessionId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT DISTINCT sessionId FROM tasks WHERE planId IS NOT NULL") suspend fun plannedSessions(): List<String>
    @Upsert suspend fun upsert(t: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): TaskEntity?
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT :limit") fun observeRecent(limit: Int = 100): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE state = 'running'") suspend fun running(): List<TaskEntity>
    @Query("SELECT COUNT(*) FROM tasks") suspend fun taskCount(): Int
    @Query("SELECT * FROM tasks WHERE state NOT IN ('completed','failed','cancelled','timed_out','halted')") suspend fun nonTerminal(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") fun observe(id: String): Flow<TaskEntity?>
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY createdAt ASC") suspend fun children(parentId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1") suspend fun latestForSession(sessionId: String): TaskEntity?
    @Query("SELECT * FROM steps WHERE taskId = :taskId ORDER BY createdAt ASC") suspend fun steps(taskId: String): List<StepEntity>
    @Upsert suspend fun upsertStep(s: StepEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertToolCall(c: ToolCallEntity)
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY createdAt ASC") suspend fun toolCalls(taskId: String): List<ToolCallEntity>
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId ORDER BY createdAt ASC") fun observeToolCalls(taskId: String): Flow<List<ToolCallEntity>>
    @Query("SELECT * FROM tool_calls WHERE taskId = :taskId AND idempotencyKey = :key AND outcome = 'ok' LIMIT 1")
    suspend fun findSucceeded(taskId: String, key: String): ToolCallEntity?
    @Query("SELECT * FROM tasks WHERE createdAt >= :since ORDER BY createdAt DESC LIMIT :limit") suspend fun since(since: Long, limit: Int = 2000): List<TaskEntity>
    @Query("SELECT capability, MAX(createdAt) AS lastAt, COUNT(*) AS uses FROM tool_calls GROUP BY capability") suspend fun capabilityUses(): List<CapabilityUse>
    @Query("SELECT * FROM tool_calls WHERE createdAt >= :since ORDER BY createdAt ASC LIMIT :limit") suspend fun toolCallsSince(since: Long, limit: Int = 20000): List<ToolCallEntity>
}

@Dao
interface MemoryDao {
    @Upsert suspend fun upsert(m: MemoryEntity)
    @Query("SELECT * FROM memories WHERE id = :id") suspend fun get(id: String): MemoryEntity?
    @Query("SELECT * FROM memories WHERE status = :status ORDER BY updatedAt DESC") fun observeByStatus(status: String): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE status = 'active' ORDER BY importance DESC, updatedAt DESC LIMIT :limit") suspend fun topActive(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE status = 'active' AND type IN ('profile','preference') ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun profileAndPreferences(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE status IN ('active','pending_confirmation') AND lower(text) = lower(:text) LIMIT 1") suspend fun findSameText(text: String): MemoryEntity?
    @Query(
        "SELECT memories.* FROM memories JOIN memories_fts ON memories.rowid = memories_fts.rowid " +
            "WHERE memories_fts MATCH :query AND memories.status = 'active' ORDER BY memories.importance DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 20): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE status = 'active' AND text LIKE '%' || :q || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun searchLike(q: String, limit: Int = 20): List<MemoryEntity>
    @Query("SELECT COUNT(*) FROM memories WHERE status = 'pending_confirmation'") fun observePendingCount(): Flow<Int>
    @Query("SELECT * FROM memories WHERE status = 'active'") suspend fun allActive(): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE status != 'deleted' ORDER BY createdAt ASC") suspend fun allForExport(): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE status = 'active' AND id != :exceptId AND text LIKE '%' || :token || '%' LIMIT :limit")
    suspend fun activeContaining(token: String, exceptId: String, limit: Int): List<MemoryEntity>
    @Query(
        "SELECT id FROM memories WHERE (type = 'episodic' AND updatedAt < :episodicBefore) OR (status = 'pending_confirmation' AND updatedAt < :pendingBefore) " +
            "OR (status = 'deleted' AND updatedAt < :deletedBefore) OR (status = 'superseded' AND updatedAt < :supersededBefore)"
    )
    suspend fun expired(episodicBefore: Long, pendingBefore: Long, deletedBefore: Long, supersededBefore: Long): List<String>
    @Query("DELETE FROM memories WHERE id IN (:ids)") suspend fun hardDelete(ids: List<String>)
    @Query("DELETE FROM memories") suspend fun hardDeleteAll()
    // derived index
    @Upsert suspend fun upsertVector(v: MemoryVectorEntity)
    @Query("SELECT * FROM memory_vectors WHERE fingerprint = :fingerprint") suspend fun vectors(fingerprint: String): List<MemoryVectorEntity>
    @Query("SELECT memoryId FROM memory_vectors WHERE fingerprint = :fingerprint") suspend fun indexedIds(fingerprint: String): List<String>
    @Query("DELETE FROM memory_vectors WHERE memoryId IN (:ids)") suspend fun deleteVectors(ids: List<String>)
    @Query("DELETE FROM memory_vectors WHERE fingerprint != :fingerprint") suspend fun deleteOtherVectors(fingerprint: String)
    @Query("DELETE FROM memory_vectors") suspend fun deleteAllVectors()
    // graph
    @Upsert suspend fun upsertEdge(e: MemoryEdgeEntity)
    @Query("SELECT * FROM memory_edges WHERE fromId IN (:ids) OR toId IN (:ids)") suspend fun edgesOf(ids: List<String>): List<MemoryEdgeEntity>
    @Query("DELETE FROM memory_edges WHERE fromId IN (:ids) OR toId IN (:ids)") suspend fun deleteEdges(ids: List<String>)
    @Query("DELETE FROM memory_edges") suspend fun deleteAllEdges()
    @Query("INSERT INTO memories_fts(memories_fts) VALUES('rebuild')") suspend fun rebuildFts()
}

@Dao
interface ScheduleDao {
    @Upsert suspend fun upsert(s: ScheduleEntity)
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun get(id: String): ScheduleEntity?
    @Query("SELECT * FROM schedules ORDER BY createdAt DESC") fun observeAll(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM schedules ORDER BY createdAt DESC") suspend fun all(): List<ScheduleEntity>
    @Query("SELECT * FROM schedules WHERE enabled = 1") suspend fun enabled(): List<ScheduleEntity>
    @Query("DELETE FROM schedules WHERE id = :id") suspend fun delete(id: String)
    @Upsert suspend fun upsertRun(r: ScheduleRunEntity)
    @Query("SELECT * FROM schedule_runs WHERE runId = :id") suspend fun run(id: String): ScheduleRunEntity?
    @Query("SELECT * FROM schedule_runs WHERE scheduleId = :id ORDER BY queuedAt DESC LIMIT :limit") suspend fun runs(id: String, limit: Int = 20): List<ScheduleRunEntity>
    @Query("SELECT * FROM schedule_runs WHERE status = 'queued' ORDER BY queuedAt, runId") suspend fun queuedRuns(): List<ScheduleRunEntity>
    @Query("SELECT * FROM schedule_runs WHERE scheduleId = :id AND status IN ('queued','running')") suspend fun openRuns(id: String): List<ScheduleRunEntity>
    @Query("SELECT * FROM schedule_runs WHERE status = 'running'") suspend fun runningRuns(): List<ScheduleRunEntity>
    @Query("DELETE FROM schedule_runs WHERE queuedAt < :before AND status NOT IN ('queued','running')") suspend fun purgeRuns(before: Long): Int
    @Query("DELETE FROM schedule_runs WHERE scheduleId = :id") suspend fun deleteRuns(id: String)
    /** Last condition-watch result fingerprint of [id] ("fp=…" detail prefix), excluding the run being checked. */
    @Query("SELECT * FROM schedule_runs WHERE scheduleId = :id AND runId != :exclude AND detail LIKE 'fp=%' ORDER BY queuedAt DESC LIMIT 1") suspend fun lastFingerprintRun(id: String, exclude: String): ScheduleRunEntity?
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(a: AuditEntity)
    @Query("SELECT * FROM audit ORDER BY seq DESC LIMIT 1") suspend fun last(): AuditEntity?
    @Query("SELECT * FROM audit ORDER BY seq DESC LIMIT :limit") fun observeRecent(limit: Int = 300): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit ORDER BY seq ASC") suspend fun allAscending(): List<AuditEntity>
}

@Dao
interface ProviderDao {
    @Upsert suspend fun upsert(p: ProviderEntity)
    @Query("SELECT * FROM providers ORDER BY fallbackOrder ASC, createdAt ASC") fun observeAll(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM providers ORDER BY fallbackOrder ASC, createdAt ASC") suspend fun all(): List<ProviderEntity>
    @Query("SELECT * FROM providers WHERE id = :id") suspend fun get(id: String): ProviderEntity?
    @Query("DELETE FROM providers WHERE id = :id") suspend fun delete(id: String)
    @Upsert suspend fun upsertCap(c: ModelCapEntity)
    @Query("SELECT * FROM model_caps WHERE providerId = :providerId AND modelId = :modelId") suspend fun cap(providerId: String, modelId: String): ModelCapEntity?
    @Query("SELECT * FROM model_caps WHERE providerId = :providerId") suspend fun caps(providerId: String): List<ModelCapEntity>
}

@Dao
interface UsageDao {
    @Insert suspend fun insert(u: UsageEntity)
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE occurredAt >= :since") suspend fun costSince(since: Long): Double
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage WHERE occurredAt >= :since AND providerId = :providerId") suspend fun costSinceForProvider(since: Long, providerId: String): Double
    @Query("SELECT COALESCE(SUM(inputTokens), 0) FROM usage WHERE occurredAt >= :since") suspend fun inputTokensSince(since: Long): Long
    @Query("SELECT COALESCE(SUM(outputTokens), 0) FROM usage WHERE occurredAt >= :since") suspend fun outputTokensSince(since: Long): Long
    @Query("SELECT * FROM usage ORDER BY occurredAt DESC LIMIT :limit") fun observeRecent(limit: Int = 200): Flow<List<UsageEntity>>
    @Query("SELECT * FROM usage WHERE occurredAt >= :since ORDER BY occurredAt ASC LIMIT :limit") suspend fun since(since: Long, limit: Int = 20000): List<UsageEntity>
}

@Dao
interface SettingDao {
    @Upsert suspend fun upsert(s: SettingEntity)
    @Query("SELECT * FROM settings") suspend fun all(): List<SettingEntity>
}

/** Durable runtime tables (v2). Task rows themselves are only written by the TaskStateMachine. */
@Dao
interface RuntimeDao {
    // task events (append-only)
    @Insert suspend fun insertEvent(e: TaskEventEntity)
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY at ASC, rowid ASC") suspend fun events(taskId: String): List<TaskEventEntity>
    @Query("SELECT * FROM task_events WHERE taskId = :taskId ORDER BY at ASC, rowid ASC") fun observeEvents(taskId: String): Flow<List<TaskEventEntity>>

    // plans
    @Upsert suspend fun upsertPlan(p: PlanEntity)
    @Query("UPDATE plans SET active = 0 WHERE taskId = :taskId") suspend fun deactivatePlans(taskId: String)
    @Query("SELECT * FROM plans WHERE taskId = :taskId AND active = 1 ORDER BY version DESC LIMIT 1") suspend fun activePlan(taskId: String): PlanEntity?
    @Query("SELECT * FROM plans WHERE taskId = :taskId AND active = 1 ORDER BY version DESC LIMIT 1") fun observeActivePlan(taskId: String): Flow<PlanEntity?>
    @Query("SELECT * FROM plans WHERE taskId = :taskId ORDER BY version ASC") suspend fun plans(taskId: String): List<PlanEntity>

    // checkpoints & notebook
    @Insert suspend fun insertCheckpoint(c: CheckpointEntity)
    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId ORDER BY createdAt DESC, rowid DESC LIMIT 1") suspend fun lastCheckpoint(taskId: String): CheckpointEntity?
    @Query("SELECT COUNT(*) FROM checkpoints WHERE taskId = :taskId") suspend fun checkpointCount(taskId: String): Int
    @Upsert suspend fun upsertNotebook(n: TaskNotebookEntity)
    @Query("SELECT * FROM task_notebooks WHERE taskId = :taskId") suspend fun notebook(taskId: String): TaskNotebookEntity?

    // idempotency ledger
    @Query("SELECT * FROM idempotency_ledger WHERE `key` = :key") suspend fun ledger(key: String): IdempotencyEntity?
    @Upsert suspend fun upsertLedger(e: IdempotencyEntity)
    @Query("SELECT * FROM idempotency_ledger WHERE taskId = :taskId ORDER BY createdAt ASC") suspend fun ledgerForTask(taskId: String): List<IdempotencyEntity>
    @Query("SELECT * FROM idempotency_ledger WHERE status = 'started'") suspend fun uncertainEffects(): List<IdempotencyEntity>

    // approvals
    @Upsert suspend fun upsertApproval(a: ApprovalEntity)
    @Query("SELECT * FROM approvals ORDER BY createdAt DESC LIMIT :limit") fun observeApprovals(limit: Int = 200): Flow<List<ApprovalEntity>>
    @Query("SELECT * FROM approvals WHERE taskId = :taskId ORDER BY createdAt ASC") suspend fun approvalsForTask(taskId: String): List<ApprovalEntity>
    @Query("SELECT * FROM approvals WHERE taskId = :taskId ORDER BY createdAt ASC") fun observeApprovalsForTask(taskId: String): Flow<List<ApprovalEntity>>

    // grants
    @Upsert suspend fun upsertGrant(g: GrantEntity)
    @Query("SELECT * FROM grants ORDER BY createdAt DESC") fun observeGrants(): Flow<List<GrantEntity>>
    @Query("SELECT * FROM grants WHERE capability = :capability AND revoked = 0") suspend fun grantsFor(capability: String): List<GrantEntity>
    @Query("SELECT * FROM grants WHERE grantId = :id") suspend fun grant(id: String): GrantEntity?
    @Query("SELECT COUNT(*) FROM grants") suspend fun grantCount(): Int

    // outbox
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueueOutbox(o: OutboxEntity): Long
    @Query("SELECT * FROM outbox WHERE status = 'pending' AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT 50") suspend fun dueOutbox(now: Long): List<OutboxEntity>
    @Upsert suspend fun upsertOutbox(o: OutboxEntity)
    @Query("SELECT * FROM outbox WHERE dedupeKey = :key") suspend fun outboxByKey(key: String): OutboxEntity?

}

@Dao
interface SkillDao {
    @Upsert suspend fun upsert(s: SkillEntity)
    @Query("SELECT * FROM skills WHERE skillId = :id") suspend fun get(id: String): SkillEntity?
    @Query("SELECT * FROM skills ORDER BY lifecycle = 'active' DESC, updatedAt DESC") fun observeAll(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills") suspend fun all(): List<SkillEntity>
    @Query("SELECT * FROM skills WHERE signature = :signature LIMIT 1") suspend fun bySignature(signature: String): SkillEntity?
    @Query("DELETE FROM skills WHERE skillId = :id") suspend fun delete(id: String)
    @Upsert suspend fun upsertVersion(v: SkillVersionEntity)
    @Query("SELECT * FROM skill_versions WHERE skillId = :id AND version = :version") suspend fun version(id: String, version: Int): SkillVersionEntity?
    @Query("SELECT * FROM skill_versions WHERE skillId = :id ORDER BY version DESC") suspend fun versions(id: String): List<SkillVersionEntity>
    @Query("DELETE FROM skill_versions WHERE skillId = :id") suspend fun deleteVersions(id: String)
    @Insert suspend fun insertRun(r: SkillRunEntity)
    @Query("SELECT * FROM skill_runs WHERE skillId = :id ORDER BY at DESC LIMIT :limit") suspend fun runs(id: String, limit: Int = 50): List<SkillRunEntity>
    @Query("DELETE FROM skill_runs WHERE skillId = :id") suspend fun deleteRuns(id: String)
    @Upsert suspend fun upsertTrajectory(t: SkillTrajectoryEntity)
    @Query("SELECT * FROM skill_trajectories WHERE signature = :signature ORDER BY createdAt DESC LIMIT :limit") suspend fun trajectories(signature: String, limit: Int = 5): List<SkillTrajectoryEntity>
    @Query("DELETE FROM skill_trajectories WHERE createdAt < :before") suspend fun purgeTrajectories(before: Long)
}

@Dao
interface DevDao {
    @Upsert suspend fun upsertWorkspace(w: WorkspaceEntity)
    @Query("SELECT * FROM workspaces WHERE workspaceId = :id") suspend fun workspace(id: String): WorkspaceEntity?
    @Query("SELECT * FROM workspaces ORDER BY lastOpenedAt DESC") suspend fun workspaces(): List<WorkspaceEntity>
    @Query("SELECT * FROM workspaces ORDER BY lastOpenedAt DESC") fun observeWorkspaces(): Flow<List<WorkspaceEntity>>
    @Query("DELETE FROM workspaces WHERE workspaceId = :id") suspend fun deleteWorkspace(id: String)
    @Query("UPDATE workspaces SET lockTaskId = :taskId, lockUntil = :until WHERE workspaceId = :id AND (lockTaskId IS NULL OR lockTaskId = :taskId OR lockUntil < :now)")
    suspend fun tryLock(id: String, taskId: String, until: Long, now: Long): Int
    @Query("UPDATE workspaces SET lockTaskId = NULL, lockUntil = NULL WHERE workspaceId = :id AND lockTaskId = :taskId") suspend fun unlock(id: String, taskId: String): Int
    @Upsert suspend fun upsertChangeSet(c: ChangeSetEntity)
    @Query("SELECT * FROM changesets WHERE changeSetId = :id") suspend fun changeSet(id: String): ChangeSetEntity?
    @Query("SELECT * FROM changesets WHERE workspaceId = :ws ORDER BY createdAt DESC LIMIT :limit") suspend fun changeSets(ws: String, limit: Int = 50): List<ChangeSetEntity>
    @Query("SELECT * FROM changesets WHERE workspaceId = :ws ORDER BY createdAt DESC") fun observeChangeSets(ws: String): Flow<List<ChangeSetEntity>>
    @Query("SELECT * FROM changesets WHERE taskId = :taskId ORDER BY createdAt ASC") suspend fun changeSetsForTask(taskId: String): List<ChangeSetEntity>
    @Query("DELETE FROM changesets WHERE workspaceId = :ws") suspend fun deleteChangeSets(ws: String)
    // artifacts (Artifact Service)
    @Upsert suspend fun upsertArtifact(a: ArtifactEntity)
    @Query("SELECT * FROM artifacts WHERE artifactId = :id") suspend fun artifact(id: String): ArtifactEntity?
    @Query("SELECT * FROM artifacts WHERE deleted = 0 ORDER BY createdAt DESC LIMIT :limit") suspend fun artifacts(limit: Int = 200): List<ArtifactEntity>
    @Query("SELECT * FROM artifacts WHERE deleted = 0 ORDER BY createdAt DESC") fun observeArtifacts(): Flow<List<ArtifactEntity>>
    @Query("SELECT * FROM artifacts WHERE producerTaskId = :taskId AND deleted = 0") suspend fun artifactsForTask(taskId: String): List<ArtifactEntity>
    /** Artifacts of a conversation: produced by its tasks, or attached / saved from it ([marker] matches its id in the metadata). */
    @Query(
        "SELECT * FROM artifacts WHERE deleted = 0 AND (producerTaskId IN (SELECT id FROM tasks WHERE sessionId = :sessionId) " +
            "OR metadataJson LIKE :marker) ORDER BY createdAt DESC LIMIT 300"
    )
    fun observeArtifactsForSession(sessionId: String, marker: String): Flow<List<ArtifactEntity>>
}

@Dao
interface WorkerDao {
    @Upsert suspend fun upsert(w: WorkerEntity)
    @Query("SELECT * FROM workers WHERE workerId = :id") suspend fun get(id: String): WorkerEntity?
    @Query("SELECT * FROM workers ORDER BY pairedAt ASC") suspend fun all(): List<WorkerEntity>
    @Query("SELECT * FROM workers ORDER BY pairedAt ASC") fun observe(): Flow<List<WorkerEntity>>
    @Query("DELETE FROM workers WHERE workerId = :id") suspend fun delete(id: String)
}

@Dao
interface PluginDao {
    @Upsert suspend fun upsert(p: PluginEntity)
    @Upsert suspend fun upsertVersion(v: PluginVersionEntity)
    @Query("SELECT * FROM plugins WHERE pluginId = :id") suspend fun get(id: String): PluginEntity?
    @Query("SELECT * FROM plugins ORDER BY name") suspend fun all(): List<PluginEntity>
    @Query("SELECT * FROM plugins ORDER BY name") fun observe(): Flow<List<PluginEntity>>
    @Query("SELECT * FROM plugin_versions WHERE pluginId = :id ORDER BY installedAt") suspend fun versions(id: String): List<PluginVersionEntity>
    @Query("DELETE FROM plugins WHERE pluginId = :id") suspend fun delete(id: String)
}

@Dao
interface ConnectionDao {
    @Upsert suspend fun upsert(c: ConnectionEntity)
    @Query("SELECT * FROM connections WHERE connectionId = :id") suspend fun get(id: String): ConnectionEntity?
    @Query("SELECT * FROM connections WHERE name = :name") suspend fun byName(name: String): ConnectionEntity?
    @Query("SELECT * FROM connections ORDER BY name") suspend fun all(): List<ConnectionEntity>
    @Query("SELECT * FROM connections ORDER BY name") fun observe(): Flow<List<ConnectionEntity>>
    @Query("DELETE FROM connections WHERE connectionId = :id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(e: ConnectionEventEntity): Long
    @Upsert suspend fun upsertEvent(e: ConnectionEventEntity)
    @Query("SELECT * FROM connection_events WHERE id = :id") suspend fun event(id: String): ConnectionEventEntity?
    @Query("SELECT * FROM connection_events WHERE connectionId = :id ORDER BY at DESC LIMIT :limit") suspend fun events(id: String, limit: Int = 50): List<ConnectionEventEntity>
    @Query("SELECT * FROM connection_events WHERE outcome = 'queued' ORDER BY at, id") suspend fun queued(): List<ConnectionEventEntity>
    @Query("DELETE FROM connection_events WHERE at < :before AND outcome != 'queued'") suspend fun purgeEvents(before: Long): Int
    @Query("DELETE FROM connection_events WHERE connectionId = :id") suspend fun deleteEvents(id: String)
}

@Dao
interface ImprovementDao {
    @Upsert suspend fun upsert(p: ImprovementProposalEntity)
    @Query("SELECT * FROM improvement_proposals WHERE proposalId = :id") suspend fun get(id: String): ImprovementProposalEntity?
    @Query("SELECT * FROM improvement_proposals WHERE fingerprint = :fp") suspend fun byFingerprint(fp: String): ImprovementProposalEntity?
    @Query("SELECT * FROM improvement_proposals ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'applied' THEN 1 ELSE 2 END, updatedAt DESC") fun observe(): Flow<List<ImprovementProposalEntity>>
    @Query("SELECT * FROM improvement_proposals ORDER BY updatedAt DESC") suspend fun all(): List<ImprovementProposalEntity>
    @Query("SELECT * FROM improvement_proposals WHERE status = :status ORDER BY updatedAt DESC") suspend fun withStatus(status: String): List<ImprovementProposalEntity>
    @Query("DELETE FROM improvement_proposals WHERE status IN ('rejected','obsolete','rolled_back') AND updatedAt < :before") suspend fun purge(before: Long): Int
    @Upsert suspend fun upsertCase(c: EvalCaseEntity)
    @Query("SELECT * FROM eval_cases ORDER BY createdAt") suspend fun cases(): List<EvalCaseEntity>
    @Query("SELECT * FROM eval_cases WHERE caseId = :id") suspend fun evalCase(id: String): EvalCaseEntity?
    @Query("DELETE FROM eval_cases WHERE caseId = :id") suspend fun deleteCase(id: String)
}

@Dao
interface SpanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(s: List<SpanEntity>)
    @Query("SELECT * FROM spans WHERE taskId = :taskId ORDER BY startMs, spanId") suspend fun forTask(taskId: String): List<SpanEntity>
    @Query("SELECT * FROM spans WHERE startMs >= :since ORDER BY startMs LIMIT :limit") suspend fun since(since: Long, limit: Int = 50000): List<SpanEntity>
    @Query("SELECT * FROM spans WHERE exported = 0 ORDER BY startMs LIMIT :limit") suspend fun unexported(limit: Int): List<SpanEntity>
    @Query("UPDATE spans SET exported = 1 WHERE spanId IN (:ids)") suspend fun markExported(ids: List<String>)
    @Query("DELETE FROM spans WHERE startMs < :before") suspend fun purge(before: Long): Int
    @Query("SELECT COUNT(*) FROM spans") suspend fun count(): Int
    @Query("DELETE FROM spans WHERE spanId IN (SELECT spanId FROM spans ORDER BY startMs ASC LIMIT :n)") suspend fun deleteOldest(n: Int): Int
}

/** Last use of a capability (from the tool call records). */
data class CapabilityUse(val capability: String, val lastAt: Long, val uses: Int)

/** A count per conversation (History of the design). */
data class SessionCount(val sessionId: String, val n: Int)
