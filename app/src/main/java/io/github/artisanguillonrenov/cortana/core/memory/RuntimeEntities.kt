package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Database v2 — durable agent runtime (production pack doc 06 §10, roadmap phase 2).

/** Append-only task state transitions (§7.3). Written only by the TaskStateMachine. */
@Entity(tableName = "task_events", indices = [Index("taskId"), Index("at")])
data class TaskEventEntity(
    @PrimaryKey val eventId: String,
    val taskId: String,
    val fromState: String?,
    val toState: String,
    val actor: String,
    val reason: String,
    val at: Long,
    val traceId: String,
    val stepId: String? = null,
    val toolCallId: String? = null,
)

/** Versioned plans; a revision is a new row, never an update of an executed plan (§6.3). */
@Entity(tableName = "plans", indices = [Index("taskId")])
data class PlanEntity(
    @PrimaryKey val planId: String,
    val taskId: String,
    val version: Int,
    val strategy: String,
    val planJson: String,
    val createdAt: Long,
    val active: Boolean,
)

@Entity(tableName = "checkpoints", indices = [Index("taskId"), Index("createdAt")])
data class CheckpointEntity(
    @PrimaryKey val checkpointId: String,
    val taskId: String,
    val planId: String?,
    val planVersion: Int,
    val state: String,
    val checkpointJson: String,
    val reason: String,
    val createdAt: Long,
)

@Entity(tableName = "task_notebooks")
data class TaskNotebookEntity(
    @PrimaryKey val taskId: String,
    val notebookJson: String,
    val updatedAt: Long,
)

/**
 * Idempotency ledger (§66, doc 06 §7): a logical side effect is recorded as `started` BEFORE the
 * executor runs and `succeeded`/`failed` after, so a crash in between is detectable (`started`
 * without outcome = uncertain → reconcile, never blind replay).
 */
@Entity(tableName = "idempotency_ledger", indices = [Index("taskId"), Index("status")])
data class IdempotencyEntity(
    @PrimaryKey val key: String,
    val taskId: String,
    val capability: String,
    val argsHash: String,
    /** started | succeeded | failed | reconciled | unknown */
    val status: String,
    val receipt: String? = null,
    val outcomeRef: String? = null,
    val reversible: Boolean = false,
    val undoRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Authorization requests and the owner's decisions (§23, §85). */
@Entity(tableName = "approvals", indices = [Index("taskId"), Index("createdAt")])
data class ApprovalEntity(
    @PrimaryKey val approvalId: String,
    val taskId: String?,
    val capability: String,
    val bindingHash: String,
    val risk: String,
    /** pending | approved | refused | expired | cancelled */
    val status: String,
    val detailsJson: String,
    val createdAt: Long,
    val decidedAt: Long? = null,
)

/** Scoped, expiring, revocable grants (§23.2, doc 06 §6). */
@Entity(tableName = "grants", indices = [Index("capability")])
data class GrantEntity(
    @PrimaryKey val grantId: String,
    val capability: String,
    val scope: String? = null,
    val constraintsJson: String = "{}",
    val taskId: String? = null,
    val sessionId: String? = null,
    val scheduleId: String? = null,
    val validFrom: Long,
    val validUntil: Long? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
    val revoked: Boolean = false,
    val createdBy: String = "owner",
    val createdAt: Long,
)

/** Transactional outbox for deliveries that must survive a crash (§10.2). */
@Entity(tableName = "outbox", indices = [Index(value = ["dedupeKey"], unique = true), Index("status")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val payloadJson: String,
    val dedupeKey: String,
    /** pending | delivered | failed */
    val status: String,
    val attempts: Int = 0,
    val nextAttemptAt: Long,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val lastError: String? = null,
)

/** Artifact Service registry (doc 03 §12, §34.5). Files live on disk; only metadata + hash here. */
@Entity(tableName = "artifacts", indices = [Index("producerTaskId"), Index("createdAt")])
data class ArtifactEntity(
    @PrimaryKey val artifactId: String,
    val type: String,
    val mime: String,
    val name: String,
    val uri: String,
    val sha256: String,
    val sizeBytes: Long,
    val producerTaskId: String? = null,
    val producerCapability: String? = null,
    val sourceIdsJson: String = "[]",
    val metadataJson: String = "{}",
    @ColumnInfo(defaultValue = "keep") val retention: String = "keep",
    val createdAt: Long,
    val deleted: Boolean = false,
)

/** Context Engine v2 compaction (doc 04 §8): rolling summary of the messages that left the window. */
@Entity(tableName = "conversation_summaries")
data class ConversationSummaryEntity(
    @PrimaryKey val sessionId: String,
    /** createdAt of the newest message covered by [summary]. */
    val coveredUntil: Long,
    /** Number of visible (user/assistant) turns, from the start of the session, folded into [summary]. */
    val coveredCount: Int,
    val summary: String,
    /** extractive | model */
    val method: String,
    val updatedAt: Long,
    /** v4: newest message covered, so a summary is reused only on a branch that contains it. */
    val coveredUntilMessageId: String? = null,
)

/** Derived vector index (doc 04 §11): recomputable from `memories`, keyed by embedder fingerprint. */
@Entity(tableName = "memory_vectors", indices = [Index("fingerprint")])
data class MemoryVectorEntity(
    @PrimaryKey val memoryId: String,
    val fingerprint: String,
    val dim: Int,
    val vector: ByteArray,
    val textHash: String,
    val updatedAt: Long,
) {
    override fun equals(other: Any?) = other is MemoryVectorEntity && other.memoryId == memoryId && other.textHash == textHash && other.fingerprint == fingerprint
    override fun hashCode() = memoryId.hashCode()
}

/** Optional knowledge-graph relations between memories (supersedes, same_entity). */
@Entity(tableName = "memory_edges", primaryKeys = ["fromId", "toId", "relation"], indices = [Index("toId")])
data class MemoryEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Double = 1.0,
    val entity: String? = null,
    val createdAt: Long,
)

/** Procedural memory (doc 04 §12–15): one row per skill; the definition of each version is kept. */
@Entity(tableName = "skills", indices = [Index("signature"), Index("lifecycle")])
data class SkillEntity(
    @PrimaryKey val skillId: String,
    val name: String,
    val currentVersion: Int,
    /** candidate | tested | validated | active | degraded | retired */
    val lifecycle: String,
    val enabled: Boolean,
    val signature: String?,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val confidence: Double = 0.0,
    val lastValidatedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val lastFailureReason: String? = null,
    val createdFrom: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "skill_versions", primaryKeys = ["skillId", "version"])
data class SkillVersionEntity(
    val skillId: String,
    val version: Int,
    val definitionJson: String,
    val note: String,
    val createdAt: Long,
)

/** Every replay, validation and invalidation event (stats and audit of the skill). */
@Entity(tableName = "skill_runs", indices = [Index("skillId")])
data class SkillRunEntity(
    @PrimaryKey val runId: String,
    val skillId: String,
    val version: Int,
    val taskId: String?,
    /** replay | validation | invalidation | learning */
    val kind: String,
    /** succeeded | failed | diverged | refused */
    val outcome: String,
    val failedStep: Int? = null,
    val reason: String? = null,
    val at: Long,
)

/** Successful task trajectories kept to detect repeated procedures (candidate learning). */
@Entity(tableName = "skill_trajectories", indices = [Index("signature")])
data class SkillTrajectoryEntity(
    @PrimaryKey val taskId: String,
    val signature: String,
    val objective: String,
    val stepsJson: String,
    val createdAt: Long,
)

/** Developer workspaces (doc 03 §2). Files live under the app-private `workspaces/<id>` directory. */
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey val workspaceId: String,
    val name: String,
    val rootPath: String,
    /** Origin: created | imported:<saf uri> | cloned:<url> */
    val origin: String,
    val backendId: String = "android-local",
    val vcsType: String? = null,
    val currentBranch: String? = null,
    val baseRevision: String? = null,
    val writable: Boolean = true,
    /** untrusted | trusted_local | system_project | read_only */
    val trust: String = "untrusted",
    val detectedStacksJson: String = "[]",
    val buildSystemsJson: String = "[]",
    val profileJson: String? = null,
    val lockTaskId: String? = null,
    val lockUntil: Long? = null,
    val createdAt: Long,
    val lastOpenedAt: Long,
)

/** Applied patches with their backups (rollback) and the exact diff (audit, review). */
@Entity(tableName = "changesets", indices = [Index("workspaceId"), Index("taskId")])
data class ChangeSetEntity(
    @PrimaryKey val changeSetId: String,
    val workspaceId: String,
    val patchId: String,
    val taskId: String?,
    val changeSetJson: String,
    val backupDir: String,
    /** applied | rolled_back */
    val status: String,
    val createdAt: Long,
)

/** Paired workers (doc 03 §16-17): pinned certificate, capabilities, revocation. */
@Entity(tableName = "workers")
data class WorkerEntity(
    @PrimaryKey val workerId: String,
    val name: String,
    val baseUrl: String,
    val certificateSha256: String,
    val workerPublicKey: String,
    val capabilitiesJson: String,
    val pairedAt: Long,
    val lastSeenAt: Long? = null,
    val lastError: String? = null,
    val revoked: Boolean = false,
)

/**
 * Installed plugins (blueprint §21, doc 06 `plugins`). [state]: installing | active | disabled |
 * removing | broken — the two transient states let startup recovery finish or undo an interrupted
 * operation. [keyFingerprint] pins the publisher key: updates must be signed by the same key.
 */
@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val publisher: String,
    val keyFingerprint: String,
    val publicKey: String,
    val activeVersion: String,
    val state: String,
    val manifestJson: String,
    val secretHandlesJson: String = "{}",
    val installedAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
)

/** Every version ever installed (doc 06 `plugin_versions`): package hash for audit and rollback. */
@Entity(tableName = "plugin_versions", primaryKeys = ["pluginId", "version"])
data class PluginVersionEntity(
    val pluginId: String,
    val version: String,
    val packageSha256: String,
    val installedAt: Long,
    val removedAt: Long? = null,
)

/**
 * External accounts and endpoints (doc 06 "connectors / connector_accounts", blueprint §38): one
 * row per connection, owned by the ConnectionManager. Secrets are handles into the secret store,
 * never values; [state] is its lifecycle, [health] the last probe.
 */
@Entity(tableName = "connections", indices = [Index(value = ["name"], unique = true)])
data class ConnectionEntity(
    @PrimaryKey val connectionId: String,
    val kind: String,
    val name: String,
    val authScheme: String,
    val configJson: String = "{}",
    val secretHandlesJson: String = "{}",
    val scopesJson: String = "[]",
    /** active | disabled | pending_auth | revoked | error */
    val state: String,
    /** ok | degraded | down | unknown */
    val health: String = "unknown",
    val lastHealthAt: Long? = null,
    val lastError: String? = null,
    val failures: Int = 0,
    val pluginId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * What happened on a connection (doc 06 "connection_health_events"): health probes, auth,
 * refresh, revocation, inbound events (with their untrusted payload until processed) and
 * outbound deliveries. The id doubles as a dedupe key for inbound events.
 */
@Entity(tableName = "connection_events", indices = [Index("connectionId"), Index("at")])
data class ConnectionEventEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val at: Long,
    /** health | auth | refresh | revoke | inbound | outbound | config */
    val type: String,
    /** ok | error | rejected | rate_limited | queued | processed */
    val outcome: String,
    val detail: String,
    val taskId: String? = null,
    val payload: String? = null,
)

/**
 * One execution of a scheduled task (blueprint §39 "schedule_runs"): recorded when the schedule
 * fires, before anything runs, so a run survives a busy orchestrator or a restart. Condition
 * watches record their check result here (a met condition only then becomes a task).
 */
@Entity(tableName = "schedule_runs", indices = [Index("scheduleId"), Index("status")])
data class ScheduleRunEntity(
    @PrimaryKey val runId: String,
    val scheduleId: String,
    val dueAt: Long,
    val queuedAt: Long,
    /** queued | running | succeeded | failed | cancelled | skipped | condition_not_met | waiting | interrupted */
    val status: String,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val taskId: String? = null,
    val detail: String? = null,
    val late: Boolean = false,
)


/**
 * One improvement proposal (blueprint §18, doc 04 §16): versioned (a re-analysis with new evidence
 * bumps [version]), attributable ([analyzer]), reversible ([previousJson] keeps what applying
 * replaced) and testable. Nothing here changes Cortana until the owner applies it.
 */
@Entity(tableName = "improvement_proposals", indices = [Index(value = ["fingerprint"], unique = true), Index("status")])
data class ImprovementProposalEntity(
    @PrimaryKey val proposalId: String,
    /** Stable identity of what is proposed (kind + subject): the same finding is updated, never duplicated. */
    val fingerprint: String,
    /** recurring_failure | skill | skill_confidence | fast_path | routing | eval_case | regression | duplicate_tools | unused_tools | long_prompt | cost_anomaly */
    val kind: String,
    val title: String,
    val rationale: String,
    val evidenceJson: String,
    /** Typed change applied only on the owner's decision; null = observation to act on by hand. */
    val changeJson: String?,
    /** open | applied | rejected | rolled_back | obsolete */
    val status: String,
    val version: Int,
    /** analyzer id@version that produced the current version */
    val analyzer: String,
    val createdAt: Long,
    val updatedAt: Long,
    val decidedAt: Long? = null,
    val decidedBy: String? = null,
    /** What applying replaced, restored by a rollback. */
    val previousJson: String? = null,
)

/** A regression case kept from real use (doc 04 §16 « proposer nouveau test »): objective + capabilities it must use. */
@Entity(tableName = "eval_cases", indices = [Index(value = ["objectiveKey"], unique = true)])
data class EvalCaseEntity(
    @PrimaryKey val caseId: String,
    val objectiveKey: String,
    val objective: String,
    /** JSON array of capabilities expected, in order (a subsequence of the task's calls). */
    val expectedJson: String,
    val sourceProposalId: String?,
    val createdAt: Long,
    val enabled: Boolean = true,
)

/** A finished trace span (phase 28, doc 06 §15): timings, outcome and redacted, content-free attributes. */
@Entity(tableName = "spans", indices = [Index("taskId"), Index("startMs"), Index("exported")])
data class SpanEntity(
    @PrimaryKey val spanId: String,
    val traceId: String,
    val parentSpanId: String?,
    val name: String,
    val taskId: String?,
    val startMs: Long,
    val endMs: Long,
    val status: String,
    val errorType: String?,
    val attributesJson: String,
    /** Sent to the owner's OTLP collector (only when export is enabled). */
    val exported: Boolean = false,
)
