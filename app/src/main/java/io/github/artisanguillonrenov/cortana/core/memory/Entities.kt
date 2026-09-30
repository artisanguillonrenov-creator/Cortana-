package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

// §4 — Core data model. IDs are UUID strings, timestamps epoch-millis UTC.

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val incognito: Boolean = false,
    val providerId: String? = null,
    val modelId: String? = null,
    /** conversation | assistant | full */
    val toolset: String = "full",
    // ---- v4 (Chat Workspace, D-20260930-068) ----
    /** Leaf of the branch shown and continued (null = the newest message: linear conversations). */
    val activeLeafId: String? = null,
    val projectId: String? = null,
    /** chat | agent | research | council | compare | dev | voice (ChatMode wire names). */
    @ColumnInfo(defaultValue = "chat") val mode: String = "chat",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "[]") val tagsJson: String = "[]",
    /** Per-conversation choices (ChatSessionSettings JSON): disabled memories, model lock, compare routes. */
    @ColumnInfo(defaultValue = "{}") val settingsJson: String = "{}",
)

object Roles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

@Entity(tableName = "messages", indices = [Index("sessionId"), Index("createdAt"), Index("parentId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val usageJson: String? = null,
    /** assistant: JSON array of tool calls; tool: {"toolCallId":..,"name":..,"ok":..} */
    val toolCallsJson: String? = null,
    val taskId: String? = null,
    /** Opaque provider reasoning blocks echoed back during tool use (never shown). */
    val reasoningJson: String? = null,
    /** Internal messages (repair prompts) kept for the model but hidden in the UI. */
    val hidden: Boolean = false,
    // ---- v4 (Chat Workspace): a conversation is a tree; the path leaf → root is what the model and the owner see ----
    /** Previous message on this message's branch (null = first message of the conversation). */
    val parentId: String? = null,
    /** complete | streaming | stopped | interrupted | error (MessageStatus). */
    @ColumnInfo(defaultValue = "complete") val status: String = "complete",
    /** Generation run that produced it (stream deduplication and resume). */
    val runId: String? = null,
    /** Structured extras (MessageMeta JSON): attachments, model, compare group, continuation, finish reason. */
    val metaJson: String? = null,
)

object MessageStatus {
    const val COMPLETE = "complete"
    const val STREAMING = "streaming"
    const val STOPPED = "stopped"
    const val INTERRUPTED = "interrupted"
    const val ERROR = "error"
}

@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(val text: String)

object TaskStates {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val HALTED = "halted"
    const val INTERRUPTED = "interrupted"
    const val LIMIT = "limit"
    const val WAITING_USER = "waiting_user"
}

@Entity(tableName = "tasks", indices = [Index("sessionId"), Index("state"), Index("parentTaskId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val objective: String,
    val mode: String,
    val state: String,
    val tainted: Boolean,
    val countersJson: String,
    val createdAt: Long,
    val endedAt: Long? = null,
    val terminationReason: String? = null,
    // ---- v2 (durable runtime) ----
    @ColumnInfo(defaultValue = "chat") val source: String = "chat",
    val parentTaskId: String? = null,
    val planId: String? = null,
    val currentStepId: String? = null,
    @ColumnInfo(defaultValue = "") val traceId: String = "",
    @ColumnInfo(defaultValue = "0") val stepCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val replanCount: Int = 0,
    val errorJson: String? = null,
    val requestJson: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** Process instance that currently owns the task; a foreign/expired lease after restart = interrupted. */
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
)

@Entity(tableName = "steps", indices = [Index("taskId")])
data class StepEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    /** action | goal | ask_user | respond */
    val kind: String,
    val state: String,
    val capability: String? = null,
    val paramsJson: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "tool_calls", indices = [Index("taskId")])
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val stepId: String,
    val capability: String,
    val inputJson: String,
    val idempotencyKey: String? = null,
    val policyDecisionJson: String,
    /** ok | error | denied | refused | skipped_duplicate | cancelled */
    val outcome: String,
    val outputRef: String? = null,
    val createdAt: Long,
)

object MemoryStatus {
    const val ACTIVE = "active"
    const val PENDING = "pending_confirmation"
    const val SUPERSEDED = "superseded"
    const val DELETED = "deleted"
}

@Entity(tableName = "memories", indices = [Index("status"), Index("type")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    /** profile | preference | semantic | episodic */
    val type: String,
    val scope: String = "global",
    val text: String,
    val structuredJson: String? = null,
    /** normal | sensitive */
    val sensitivity: String = "normal",
    val confidence: Double = 1.0,
    val importance: Int = 3,
    val status: String,
    val supersedesId: String? = null,
    val provenanceJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "memories_fts")
data class MemoryFts(val text: String)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** once | interval | cron | reminder */
    val kind: String,
    val specJson: String,
    val timezone: String,
    val actionJson: String,
    val enabled: Boolean,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    /** catch_up_once | skip */
    val missedRunPolicy: String,
    val createdAt: Long,
    val lastOutcome: String? = null,
    /** allow | skip | queue | replace — what a fire does while a previous run is queued or running (blueprint §39.5). */
    @ColumnInfo(defaultValue = "skip") val concurrencyPolicy: String = "skip",
)

@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val occurredAt: Long,
    val actor: String,
    val action: String,
    val targetJson: String?,
    val outcome: String,
    val metaJson: String,
    val prevHash: String,
    val hash: String,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val presetId: String,
    val baseUrl: String,
    val apiKeyHandle: String?,
    val quirksJson: String?,
    val enabled: Boolean,
    val fallbackOrder: Int,
    /** Owner marked this provider usable as an automatic fallback (§5.4). */
    val allowFallback: Boolean,
    val defaultModelId: String?,
    val spendCapUsd: Double? = null,
    val createdAt: Long,
)

@Entity(tableName = "model_caps", primaryKeys = ["providerId", "modelId"])
data class ModelCapEntity(
    val providerId: String,
    val modelId: String,
    val contextWindow: Int?,
    /** 1 yes, 0 no, -1 auto */
    val nativeTools: Int,
    val nativeJson: Int,
    val vision: Int,
    /** bundled | owner | learned */
    val source: String,
)

@Entity(tableName = "usage", indices = [Index("occurredAt")])
data class UsageEntity(
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val providerId: String,
    val modelId: String,
    val role: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val costUsd: Double?,
    val estimated: Boolean,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val valueJson: String,
)
