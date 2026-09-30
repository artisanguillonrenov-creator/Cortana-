package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Chat Workspace (schema v4, D-20260930-068): organisation and UI state around the one conversation
 * store. Messages stay in `messages`; these tables hold projects, drafts, the send queue, pins and the
 * visible compaction checkpoints.
 */

/** A project groups conversations and gives them inherited instructions and a preferred model. */
@Entity(tableName = "projects", indices = [Index("updatedAt")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Instructions every conversation of the project inherits (owner text, sent as a note, never as policy). */
    val instructions: String = "",
    /** "providerId/modelId" or null (follow the global route). */
    val preferredModel: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

/** The unsent text of a conversation: survives closing the app. */
@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(
    @PrimaryKey val sessionId: String,
    val text: String,
    /** Pending attachments (List<AttachmentRef> JSON). */
    val attachmentsJson: String = "[]",
    val updatedAt: Long,
)

/** A message written while Cortana was busy: sent in order once the current task ends. */
@Entity(tableName = "chat_queue", indices = [Index("sessionId"), Index("position")])
data class ChatQueueEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    val attachmentsJson: String = "[]",
    val position: Long,
    val createdAt: Long,
    /** queued | confirm (needs the owner again: old, or written before a restart) | sending */
    val status: String = "queued",
)

/** An element the owner keeps in the model's context while the budget allows. */
@Entity(tableName = "chat_pins", indices = [Index("sessionId")])
data class ChatPinEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** message | artifact | note */
    val targetType: String,
    val targetId: String,
    val label: String,
    /** For notes: the owner's text (for messages and artifacts the text is read from its source). */
    val text: String? = null,
    val createdAt: Long,
)

/** A visible compaction: what was summarised, up to where, by which method or model. */
@Entity(tableName = "context_checkpoints", indices = [Index("sessionId"), Index("createdAt")])
data class ContextCheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val summary: String,
    val coveredUntilMessageId: String?,
    val coveredCount: Int,
    /** extractive | model */
    val method: String,
    val modelRef: String?,
    val createdAt: Long,
)

/** Sidebar preview: the last visible message of a conversation. */
data class SessionPreview(val sessionId: String, val preview: String?)

/** Minimal projection for branch navigation (siblings of the path). */
data class MessageNode(val id: String, val parentId: String?, val role: String, val hidden: Boolean, val createdAt: Long, val metaJson: String?)

@Dao
interface ChatDao {
    // projects
    @Upsert suspend fun upsertProject(p: ProjectEntity)
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY updatedAt DESC") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)

    // drafts
    @Upsert suspend fun upsertDraft(d: ChatDraftEntity)
    @Query("SELECT * FROM chat_drafts WHERE sessionId = :sessionId") suspend fun draft(sessionId: String): ChatDraftEntity?
    @Query("DELETE FROM chat_drafts WHERE sessionId = :sessionId") suspend fun deleteDraft(sessionId: String)

    // queue
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueue(q: ChatQueueEntity)
    @Query("SELECT * FROM chat_queue WHERE sessionId = :sessionId ORDER BY position, createdAt") fun observeQueue(sessionId: String): Flow<List<ChatQueueEntity>>
    @Query("SELECT * FROM chat_queue WHERE sessionId = :sessionId ORDER BY position, createdAt") suspend fun queue(sessionId: String): List<ChatQueueEntity>
    @Query("SELECT * FROM chat_queue ORDER BY position, createdAt") suspend fun allQueued(): List<ChatQueueEntity>
    @Query("SELECT * FROM chat_queue ORDER BY position, createdAt") fun observeAllQueued(): Flow<List<ChatQueueEntity>>
    @Query("SELECT COALESCE(MAX(position), 0) FROM chat_queue WHERE sessionId = :sessionId") suspend fun maxPosition(sessionId: String): Long
    @Query("UPDATE chat_queue SET position = :position WHERE id = :id") suspend fun setPosition(id: String, position: Long)
    @Query("UPDATE chat_queue SET status = :status WHERE id = :id") suspend fun setQueueStatus(id: String, status: String)
    @Query("DELETE FROM chat_queue WHERE id = :id") suspend fun dequeue(id: String)

    // pins
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun pin(p: ChatPinEntity)
    @Query("SELECT * FROM chat_pins WHERE sessionId = :sessionId ORDER BY createdAt") fun observePins(sessionId: String): Flow<List<ChatPinEntity>>
    @Query("SELECT * FROM chat_pins WHERE sessionId = :sessionId ORDER BY createdAt") suspend fun pins(sessionId: String): List<ChatPinEntity>
    @Query("DELETE FROM chat_pins WHERE id = :id") suspend fun unpin(id: String)

    // checkpoints
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addCheckpoint(c: ContextCheckpointEntity)
    @Query("SELECT * FROM context_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC") fun observeCheckpoints(sessionId: String): Flow<List<ContextCheckpointEntity>>
    @Query("SELECT * FROM context_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC") suspend fun checkpoints(sessionId: String): List<ContextCheckpointEntity>
    @Query("DELETE FROM context_checkpoints WHERE sessionId = :sessionId AND id NOT IN (SELECT id FROM context_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trimCheckpoints(sessionId: String, keep: Int)

    // session cleanup
    @Query("DELETE FROM chat_queue WHERE sessionId = :sessionId") suspend fun deleteQueue(sessionId: String)
    @Query("DELETE FROM chat_pins WHERE sessionId = :sessionId") suspend fun deletePins(sessionId: String)
    @Query("DELETE FROM context_checkpoints WHERE sessionId = :sessionId") suspend fun deleteCheckpoints(sessionId: String)
}
