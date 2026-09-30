package io.github.artisanguillonrenov.cortana.core.memory

import androidx.room.withTransaction
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The one conversation store. Since schema v4 a conversation is a tree of messages: each message
 * points to its parent and the session to the leaf of its active branch. What the model and the
 * owner see is the path leaf → root; editing, regenerating and branching add siblings and never
 * delete the other branches (Chat Workspace, D-20260930-068).
 */
class ConversationRepository(private val db: CortanaDatabase) {
    private val sessions = db.sessions()
    private val messages = db.messages()

    fun observeSessions(): Flow<List<SessionEntity>> = sessions.observeAll()
    /** Not archived, pinned first (Workspace sidebar). */
    fun observeActiveSessions(): Flow<List<SessionEntity>> = sessions.observeActive()
    fun observeArchivedSessions(): Flow<List<SessionEntity>> = sessions.observeArchived()
    fun observePreviews(): Flow<Map<String, String>> = sessions.observePreviews().map { l -> l.mapNotNull { p -> p.preview?.let { p.sessionId to it } }.toMap() }
    fun observeSession(id: String): Flow<SessionEntity?> = sessions.observe(id)
    /** Every message of the session, all branches (history views, exports of the whole tree). */
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = messages.observeSession(sessionId)

    /** The active branch, oldest first, at most [limit] messages from the leaf (older ones load on demand). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePath(sessionId: String, limit: Int = PATH_WINDOW): Flow<List<MessageEntity>> =
        sessions.observe(sessionId).map { it?.activeLeafId }.distinctUntilChanged().flatMapLatest { leaf ->
            if (leaf == null) messages.observeSession(sessionId) else messages.observePath(leaf, limit)
        }

    suspend fun session(id: String): SessionEntity? = sessions.get(id)
    suspend fun allSessions(): List<SessionEntity> = sessions.all()
    suspend fun latestSession(): SessionEntity? = sessions.latest()
    /** The active branch of [sessionId], oldest first (what the model sees). */
    suspend fun messages(sessionId: String): List<MessageEntity> = path(sessionId)
    /** Every message of every branch, in time order. */
    suspend fun allMessages(sessionId: String): List<MessageEntity> = messages.forSession(sessionId)
    suspend fun message(id: String): MessageEntity? = messages.get(id)

    suspend fun path(sessionId: String, limit: Int = 100_000): List<MessageEntity> {
        val leaf = leafOf(sessionId) ?: return emptyList()
        return messages.path(leaf, limit)
    }

    /** The current leaf: the session pointer, else the newest message (and legacy rows are linked first). */
    suspend fun leafOf(sessionId: String): String? {
        val pointer = sessions.get(sessionId)?.activeLeafId
        if (pointer != null && messages.get(pointer)?.sessionId == sessionId) return pointer
        // Only a conversation that never had a pointer can be legacy (a v4 conversation always has one,
        // even when it legitimately holds several roots after the first message was edited).
        if (pointer == null) healUnlinked(sessionId)
        return sessions.get(sessionId)?.activeLeafId?.takeIf { messages.get(it)?.sessionId == sessionId } ?: messages.newestId(sessionId)
    }

    /**
     * A conversation whose messages are all unlinked (restored from an older backup, imported) becomes
     * one branch in time order — the same order the owner saw before the tree existed.
     */
    suspend fun healUnlinked(sessionId: String) {
        val count = messages.count(sessionId)
        if (count < 2 || messages.rootCount(sessionId) < count) return
        db.withTransaction {
            val ids = messages.unlinked(sessionId)
            ids.zipWithNext().forEach { (parent, child) -> messages.setParent(child, parent) }
            sessions.setLeafOnly(sessionId, ids.last())
        }
    }

    /** Points the session at [leafId] (a message of that session): switching branch or variant. */
    suspend fun setLeaf(sessionId: String, leafId: String?) {
        if (leafId != null) require(messages.get(leafId)?.sessionId == sessionId) { "message d'une autre conversation" }
        sessions.setLeaf(sessionId, leafId, System.currentTimeMillis())
    }

    /** The newest leaf below [messageId] (following the most recent child at each level). */
    suspend fun newestLeafUnder(messageId: String): String = messages.newestLeafUnder(messageId) ?: messageId

    /**
     * Owner's explicit "Supprimer" (doc 03 §3.4): the message and everything below it, on every branch,
     * after a confirmation in the UI. Never used by an edit (an edit adds a version). Returns how many rows went.
     */
    suspend fun deleteFrom(sessionId: String, messageId: String): Int = db.withTransaction {
        val m = messages.get(messageId)?.takeIf { it.sessionId == sessionId } ?: return@withTransaction 0
        val ids = messages.subtree(messageId)
        ids.chunked(500).forEach { messages.deleteAll(it) }
        val leaf = sessions.get(sessionId)?.activeLeafId
        if (leaf == null || leaf in ids) {
            // The parent's newest remaining branch; for a first message, another version of it (never null
            // while messages remain, so healUnlinked never chains the surviving versions together).
            val anchor = m.parentId ?: messages.roots(sessionId).lastOrNull()?.id
            sessions.setLeaf(sessionId, anchor?.let { messages.newestLeafUnder(it) ?: it }, System.currentTimeMillis())
        }
        db.chat().pins(sessionId).filter { it.targetType == "message" && it.targetId in ids }.forEach { db.chat().unpin(it.id) }
        ids.size
    }

    suspend fun childrenOf(parentIds: List<String>): List<MessageNode> = if (parentIds.isEmpty()) emptyList() else parentIds.chunked(500).flatMap { messages.childrenOf(it) }
    suspend fun roots(sessionId: String): List<MessageNode> = messages.roots(sessionId)

    /** Children of every message of [path] plus the roots, keyed by parent id ("" for roots): what variant navigation needs. */
    suspend fun branchMap(sessionId: String, path: List<MessageEntity>): Map<String, List<MessageNode>> =
        (roots(sessionId) + childrenOf(path.map { it.id })).groupBy { it.parentId ?: "" }

    suspend fun createSession(
        title: String = "Nouvelle discussion",
        incognito: Boolean = false,
        providerId: String? = null,
        modelId: String? = null,
        toolset: String = "full",
    ): SessionEntity {
        val now = System.currentTimeMillis()
        val s = SessionEntity(Ids.new(), title, now, now, incognito, providerId, modelId, toolset)
        sessions.upsert(s)
        return s
    }

    suspend fun updateSession(s: SessionEntity) = sessions.upsert(s.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteSession(id: String) {
        db.withTransaction {
            messages.deleteSummary(id)
            messages.deleteSession(id)
            db.chat().deleteDraft(id); db.chat().deleteQueue(id); db.chat().deletePins(id); db.chat().deleteCheckpoints(id)
            sessions.delete(id)
        }
    }

    /**
     * Every persisted message passes through the redactor (§9.5). It is appended to the active branch
     * (parent = current leaf, or [parentId] when given) and becomes the new leaf, atomically. Passing the
     * [id] of a streaming snapshot finalises that row in place (same position in the tree).
     */
    suspend fun addMessage(
        sessionId: String,
        role: String,
        text: String,
        taskId: String? = null,
        toolCallsJson: String? = null,
        usageJson: String? = null,
        reasoningJson: String? = null,
        hidden: Boolean = false,
        id: String? = null,
        parentId: String? = null,
        status: String = MessageStatus.COMPLETE,
        runId: String? = null,
        metaJson: String? = null,
        moveLeaf: Boolean = true,
        /** A new root (editing the first message): no parent even though the conversation has messages. */
        root: Boolean = false,
    ): MessageEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = id?.let { messages.get(it) }
        val m = if (existing != null) existing.copy(
            role = role, text = Redactor.redact(text), usageJson = usageJson ?: existing.usageJson, toolCallsJson = toolCallsJson?.let(Redactor::redact) ?: existing.toolCallsJson,
            taskId = taskId ?: existing.taskId, reasoningJson = reasoningJson ?: existing.reasoningJson, hidden = hidden, status = status,
            runId = runId ?: existing.runId, metaJson = metaJson ?: existing.metaJson,
        ) else MessageEntity(
            id = id ?: Ids.new(), sessionId = sessionId, role = role, text = Redactor.redact(text), createdAt = now,
            usageJson = usageJson, toolCallsJson = toolCallsJson?.let(Redactor::redact), taskId = taskId,
            reasoningJson = reasoningJson, hidden = hidden,
            parentId = if (root) null else parentId ?: leafOf(sessionId), status = status, runId = runId, metaJson = metaJson,
        )
        if (existing != null) messages.upsert(m) else messages.insert(m)
        if (existing == null && moveLeaf) sessions.setLeaf(sessionId, m.id, now) else sessions.touch(sessionId, now)
        m
    }

    /**
     * A snapshot of an answer being generated (crash-safe streaming): created once under the current
     * leaf, then its text is updated in place; finalised by [addMessage] with the same id.
     */
    suspend fun streamSnapshot(sessionId: String, id: String, text: String, runId: String?, taskId: String?, parentId: String? = null, metaJson: String? = null, moveLeaf: Boolean = true) {
        db.withTransaction {
            val existing = messages.get(id)
            if (existing == null) {
                val now = System.currentTimeMillis()
                messages.insert(MessageEntity(id, sessionId, Roles.ASSISTANT, Redactor.redact(text), now, taskId = taskId,
                    parentId = parentId ?: leafOf(sessionId), status = MessageStatus.STREAMING, runId = runId, metaJson = metaJson))
                if (moveLeaf) sessions.setLeaf(sessionId, id, now)
            } else if (existing.status == MessageStatus.STREAMING) {
                messages.upsert(existing.copy(text = Redactor.redact(text)))
            }
        }
    }

    /** Ends a streaming row without a final answer: [status] is stopped (owner) or interrupted (failure, restart). */
    suspend fun endStream(id: String, status: String, text: String? = null) {
        val existing = messages.get(id) ?: return
        if (existing.status != MessageStatus.STREAMING) return
        messages.upsert(existing.copy(status = status, text = text?.let(Redactor::redact) ?: existing.text))
    }

    /** Startup: answers that were streaming when the process died are kept, marked interrupted. */
    suspend fun interruptDanglingStreams(): Int {
        val open = messages.streaming()
        open.forEach { messages.setStatus(it.id, MessageStatus.INTERRUPTED) }
        return open.size
    }

    suspend fun search(query: String): List<MessageEntity> {
        val fts = FtsQuery.build(query) ?: return emptyList()
        return runCatching { messages.search(fts) }.getOrElse { messages.searchLike(query.trim()) }
            .ifEmpty { messages.searchLike(query.trim()) }
    }

    suspend fun summary(sessionId: String): ConversationSummaryEntity? = messages.summary(sessionId)

    // ---- Workspace: projects (schema v4)
    suspend fun project(id: String): ProjectEntity? = db.chat().project(id)
    fun observeProjects(): Flow<List<ProjectEntity>> = db.chat().observeProjects()
    suspend fun saveProject(p: ProjectEntity) = db.chat().upsertProject(p.copy(name = p.name.take(80), instructions = Redactor.redact(p.instructions.take(8_000)), updatedAt = System.currentTimeMillis()))
    suspend fun deleteProject(id: String) = db.withTransaction {
        sessions.forProject(id).forEach { sessions.upsert(it.copy(projectId = null)) }
        db.chat().deleteProject(id)
    }

    // ---- Workspace: pins and compaction checkpoints (schema v4)
    suspend fun pins(sessionId: String): List<ChatPinEntity> = db.chat().pins(sessionId)
    fun observePins(sessionId: String): Flow<List<ChatPinEntity>> = db.chat().observePins(sessionId)
    suspend fun pin(sessionId: String, targetType: String, targetId: String, label: String, text: String? = null): ChatPinEntity {
        val p = ChatPinEntity(Ids.new(), sessionId, targetType, targetId, label.take(120), text?.let(Redactor::redact), System.currentTimeMillis())
        db.chat().pin(p)
        return p
    }
    suspend fun unpin(id: String) = db.chat().unpin(id)
    fun observeCheckpoints(sessionId: String): Flow<List<ContextCheckpointEntity>> = db.chat().observeCheckpoints(sessionId)
    suspend fun addCheckpoint(sessionId: String, summary: String, coveredUntil: String?, count: Int, method: String, modelRef: String? = null) {
        db.withTransaction {
            db.chat().addCheckpoint(ContextCheckpointEntity(Ids.new(), sessionId, Redactor.redact(summary), coveredUntil, count, method, modelRef, System.currentTimeMillis()))
            db.chat().trimCheckpoints(sessionId, CHECKPOINTS_KEPT)
        }
    }
    suspend fun saveSummary(s: ConversationSummaryEntity) = messages.upsertSummary(s.copy(summary = Redactor.redact(s.summary)))

    suspend fun rebuildFts() {
        messages.rebuildFts()
    }
}

/** Messages loaded for the visible branch at once; older ones load when the owner scrolls up. */
const val PATH_WINDOW = 400

/** Compaction checkpoints kept per conversation (older ones are superseded by the newer summaries). */
const val CHECKPOINTS_KEPT = 20

/** Turns free owner text into a safe FTS4 MATCH expression (prefix match on each token). */
object FtsQuery {
    fun build(raw: String): String? {
        val tokens = raw.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .take(8)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
