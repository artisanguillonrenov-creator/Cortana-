package io.github.artisanguillonrenov.cortana.core.chat

import io.github.artisanguillonrenov.cortana.core.memory.ConversationRepository
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Live generation state for the Workspace (doc 05, D-20260930-068). The orchestrator reports into it;
 * screens observe it. Every event of a run carries a strictly increasing sequence so a screen that
 * reconnects (recreated, reopened) resumes from what it last saw without duplicates. The text of an
 * answer being generated is also written to its message row, throttled, so STOP, a crash or a closed
 * screen never loses what was already received. The hub never calls a model or a tool.
 */
class ChatStreamHub(
    private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Database snapshot period of a streaming answer. */
    private val snapshotMs: Long = 900,
    /** UI batching period (doc 05 §5.2: never re-render on every token). */
    private val publishMs: Long = 60,
) {
    data class LiveStream(val messageId: String, val runId: String, val sessionId: String, val text: String, val sequence: Long, val lane: Int? = null, val closed: Boolean = false)

    /** A run as a screen sees it: which conversation is generating, since which sequence. */
    data class LiveRun(val runId: String, val sessionId: String, val taskId: String?, val sequence: Long, val status: String)

    private class Stream(val id: String, val run: Run, val parentId: String?, val metaJson: String?, val lane: Int?, val moveLeaf: Boolean) {
        val text = StringBuilder()
        var published = 0
        var snapshotAt = 0L
        var closed = false
        var tick: Job? = null
    }

    private class Run(val runId: String, val sessionId: String, val taskId: String?) {
        var sequence = 0L
        var status = ""
        val streams = LinkedHashMap<String, Stream>()
    }

    private val lock = Any()
    private val runs = HashMap<String, Run>()
    private val writes = Mutex()

    private val _events = MutableSharedFlow<ChatStreamEvent>(replay = 128, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ChatStreamEvent> = _events
    private val _live = MutableStateFlow<Map<String, LiveStream>>(emptyMap())
    /** Open answers by message id: the freshest text, ahead of the throttled database snapshots. */
    val live: StateFlow<Map<String, LiveStream>> = _live
    private val _runs = MutableStateFlow<Map<String, LiveRun>>(emptyMap())
    /** Running generations by conversation. */
    val runsBySession: StateFlow<Map<String, LiveRun>> = _runs

    // ------------------------------------------------------------------ run lifecycle

    fun begin(sessionId: String, taskId: String?): String {
        val run = Run(Ids.new(), sessionId, taskId)
        synchronized(lock) { runs[run.runId] = run }
        emit(run) { seq -> ChatStreamEvent.GenerationStarted(run.runId, seq, sessionId, taskId) }
        publishRun(run)
        return run.runId
    }

    fun status(runId: String, text: String) {
        val run = run(runId) ?: return
        if (run.status == text) return
        run.status = text
        emit(run) { seq -> ChatStreamEvent.Status(runId, seq, run.sessionId, text) }
        publishRun(run)
    }

    fun toolStarted(runId: String, capability: String, label: String) {
        val run = run(runId) ?: return
        emit(run) { seq -> ChatStreamEvent.ToolStarted(runId, seq, run.sessionId, capability, label) }
    }

    fun toolCompleted(runId: String, capability: String, ok: Boolean, summary: String) {
        val run = run(runId) ?: return
        emit(run) { seq -> ChatStreamEvent.ToolCompleted(runId, seq, run.sessionId, capability, ok, summary.take(300)) }
    }

    fun approvalRequired(runId: String, requestId: String, action: String, risk: String) {
        val run = run(runId) ?: return
        emit(run) { seq -> ChatStreamEvent.ApprovalRequired(runId, seq, run.sessionId, requestId, action, risk) }
    }

    /** The run is over ([state] = the task's terminal or waiting state). Open streams must have been closed or aborted. */
    fun end(runId: String, state: String) {
        val run = synchronized(lock) { runs.remove(runId) } ?: return
        emit(run) { seq -> ChatStreamEvent.GenerationCompleted(runId, seq, run.sessionId, state) }
        _runs.update { it - run.sessionId }
    }

    // ------------------------------------------------------------------ answers

    /**
     * A model call starts: returns the id its answer row will have. The writer finalises that row with
     * `addMessage(id = …)` and then calls [close]; meanwhile deltas are snapshotted under the same id.
     */
    fun open(runId: String, parentId: String? = null, metaJson: String? = null, lane: Int? = null, moveLeaf: Boolean = true): String {
        val id = Ids.new()
        val run = run(runId) ?: return id
        synchronized(lock) { run.streams[id] = Stream(id, run, parentId, metaJson, lane, moveLeaf) }
        return id
    }

    fun delta(runId: String, messageId: String, delta: String) {
        if (delta.isEmpty()) return
        val s = stream(runId, messageId) ?: return
        val first: Boolean
        synchronized(lock) {
            if (s.closed) return
            first = s.text.isEmpty()
            s.text.append(delta)
        }
        // The first token creates the row at once (the answer exists from its first word), then snapshots are throttled.
        if (first || clock() - s.snapshotAt >= snapshotMs) snapshot(s)
        synchronized(lock) {
            if (s.tick == null) s.tick = scope.launch { delay(publishMs); synchronized(lock) { s.tick = null }; publish(s) }
        }
    }

    /** The provider restarted the answer (fallback): the text so far is discarded. */
    fun reset(runId: String, messageId: String) {
        val s = stream(runId, messageId) ?: return
        synchronized(lock) { if (s.closed) return; s.text.setLength(0); s.published = 0 }
        publish(s)
    }

    /** The row of [messageId] was finalised by its writer (or never needed): stop snapshotting it. */
    fun close(runId: String, messageId: String) {
        val s = stream(runId, messageId) ?: return
        synchronized(lock) { s.tick?.cancel(); s.tick = null }
        // The last batch is published first: a fast answer may end before the first batching period.
        if (synchronized(lock) { s.published < s.text.length }) publish(s)
        synchronized(lock) { s.closed = true; s.run.streams.remove(messageId) }
        // Kept a moment as "closed" so a screen never shows an older snapshot while the database catches up.
        _live.update { m -> m[messageId]?.let { m + (messageId to it.copy(closed = true)) } ?: m }
        scope.launch { delay(3_000); _live.update { it - messageId } }
    }

    /**
     * The run stops without finalising its open answers (STOP, failure, timeout): what was received is
     * written and the rows are marked [status] (stopped or interrupted). Never re-sends anything.
     */
    suspend fun abort(runId: String, status: String): Int = withContext(NonCancellable) {
        val run = run(runId) ?: return@withContext 0
        val pending = synchronized(lock) { run.streams.values.filter { !it.closed && it.published < it.text.length }.onEach { it.tick?.cancel() } }
        pending.forEach { publish(it) }
        val open = synchronized(lock) { run.streams.values.filter { !it.closed }.onEach { it.closed = true; it.tick?.cancel() }.toList().also { run.streams.clear() } }
        var kept = 0
        writes.withLock {
            for (s in open) {
                val text = synchronized(lock) { s.text.toString() }
                if (text.isBlank()) { conversations.endStream(s.id, status); continue }
                runCatching {
                    conversations.streamSnapshot(run.sessionId, s.id, text, run.runId, run.taskId, s.parentId, s.metaJson, s.moveLeaf)
                    conversations.endStream(s.id, status, text)
                    kept++
                }.onFailure { CLog.w("stream abort write failed", it) }
                _live.update { it - s.id }
            }
        }
        if (open.isNotEmpty()) emit(run) { seq -> ChatStreamEvent.GenerationStopped(runId, seq, run.sessionId, status) }
        kept
    }

    /** Ends one open answer (a comparison lane stopped on its own): what was received is kept, marked [status]. */
    suspend fun abortStream(runId: String, messageId: String, status: String) = withContext(NonCancellable) {
        val s = stream(runId, messageId) ?: return@withContext
        synchronized(lock) { s.tick?.cancel(); s.tick = null }
        if (synchronized(lock) { s.published < s.text.length }) publish(s)
        val text = synchronized(lock) { s.closed = true; s.run.streams.remove(messageId); s.text.toString() }
        writes.withLock {
            runCatching {
                if (text.isNotBlank()) conversations.streamSnapshot(s.run.sessionId, s.id, text, s.run.runId, s.run.taskId, s.parentId, s.metaJson, s.moveLeaf)
                conversations.endStream(s.id, status, text.ifBlank { null })
            }.onFailure { CLog.w("stream end write failed", it) }
        }
        _live.update { it - messageId }
    }

    /** The current text of an open answer (empty when unknown). */
    fun textOf(runId: String, messageId: String): String = stream(runId, messageId)?.let { s -> synchronized(lock) { s.text.toString() } }.orEmpty()

    // ------------------------------------------------------------------ internals

    private fun run(runId: String) = synchronized(lock) { runs[runId] }
    private fun stream(runId: String, messageId: String) = synchronized(lock) { runs[runId]?.streams?.get(messageId) }

    /** Numbering and emission happen together, so events always leave in sequence order. */
    private fun emit(run: Run, make: (Long) -> ChatStreamEvent) {
        synchronized(lock) { _events.tryEmit(make(++run.sequence)) }
    }

    private fun publishRun(run: Run) {
        _runs.update { it + (run.sessionId to LiveRun(run.runId, run.sessionId, run.taskId, run.sequence, run.status)) }
    }

    private fun publish(s: Stream) = synchronized(lock) {
        if (s.closed) return@synchronized
        val full = s.text.toString()
        val delta = if (s.published <= full.length) full.substring(s.published) else full
        s.published = full.length
        val seq = ++s.run.sequence
        _live.update { it + (s.id to LiveStream(s.id, s.run.runId, s.run.sessionId, full, seq, s.lane)) }
        _events.tryEmit(ChatStreamEvent.StreamDelta(s.run.runId, seq, s.run.sessionId, s.id, delta, full, s.lane))
    }

    private fun snapshot(s: Stream) {
        s.snapshotAt = clock()
        scope.launch {
            writes.withLock {
                val text = synchronized(lock) { if (s.closed) null else s.text.toString() } ?: return@withLock
                runCatching { conversations.streamSnapshot(s.run.sessionId, s.id, text, s.run.runId, s.run.taskId, s.parentId, s.metaJson, s.moveLeaf) }
                    .onFailure { CLog.w("stream snapshot failed", it) }
            }
        }
    }
}

/**
 * A screen's position in the event stream of each run (doc 05 §5.3): an event is applied once, in
 * order; a replayed or late event is ignored. Text deltas carry the full text, so a gap is harmless.
 */
class StreamCursor {
    private val last = HashMap<String, Long>()

    /** True when [e] is new for this screen (and records it). */
    fun accept(e: ChatStreamEvent): Boolean {
        val seen = last[e.runId] ?: 0L
        if (e.sequence <= seen) return false
        last[e.runId] = e.sequence
        if (last.size > 64) last.keys.take(last.size - 64).forEach { last.remove(it) }
        return true
    }
}
