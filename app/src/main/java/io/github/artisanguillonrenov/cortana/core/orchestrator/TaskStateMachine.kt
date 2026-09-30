package io.github.artisanguillonrenov.cortana.core.orchestrator

import androidx.room.withTransaction
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.StructuredError
import io.github.artisanguillonrenov.cortana.contracts.TaskRequest
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.contracts.TaskTransitions
import io.github.artisanguillonrenov.cortana.contracts.TerminationReason
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor

class IllegalTransitionException(val from: TaskState, val to: TaskState) :
    IllegalStateException("Transition interdite ${from.wire} → ${to.wire}")

/**
 * LAW-002 — the only writer of task rows. Every state change is validated against the explicit
 * transition table and written in the same transaction as its append-only `task_events` row.
 */
class TaskStateMachine(private val db: CortanaDatabase) {
    /** Called once a task reached a terminal state (e.g. its root trace span); never blocks the transition. */
    @Volatile var onTerminal: ((TaskEntity) -> Unit)? = null
    /** Identifies this process; a task leased by another (dead) process is interrupted at startup. */
    val processLease: String = "proc-" + Ids.new().take(8)
    private val tasks get() = db.tasks()
    private val runtime get() = db.runtime()

    suspend fun create(request: TaskRequest, sessionId: String, mode: String): TaskEntity {
        val now = System.currentTimeMillis()
        val traceId = "trace-" + Ids.new()
        val t = TaskEntity(
            id = Ids.new(), sessionId = sessionId, objective = request.objective.take(4000), mode = mode,
            state = TaskState.RECEIVED.wire, tainted = false, countersJson = "{}", createdAt = now,
            source = ContractJson.encodeToJsonElement(io.github.artisanguillonrenov.cortana.contracts.TaskSource.serializer(), request.source).toString().trim('"'),
            parentTaskId = request.parentTaskId, traceId = traceId,
            requestJson = Redactor.redact(ContractJson.encodeToString(TaskRequest.serializer(), request)),
            updatedAt = now, leaseOwner = processLease, leaseExpiresAt = now + LEASE_MS,
        )
        db.withTransaction {
            tasks.upsert(t)
            runtime.insertEvent(TaskEventEntity(Ids.new(), t.id, null, TaskState.RECEIVED.wire, "orchestrator", "created (${t.source})", now, traceId))
        }
        return t
    }

    suspend fun get(taskId: String): TaskEntity? = tasks.get(taskId)

    fun stateOf(t: TaskEntity): TaskState = TaskState.fromWireOrNull(t.state) ?: when (t.state) {
        "limit" -> TaskState.FAILED
        else -> TaskState.INTERRUPTED
    }

    /**
     * Validated transition. [mutate] may change non-state fields atomically with the transition.
     * Terminal states set `endedAt`; [termination] is stored separately from the state (§7.2).
     */
    suspend fun transition(
        taskId: String,
        to: TaskState,
        actor: String,
        reason: String,
        termination: TerminationReason? = null,
        error: StructuredError? = null,
        stepId: String? = null,
        toolCallId: String? = null,
        mutate: (TaskEntity) -> TaskEntity = { it },
    ): TaskEntity = db.withTransaction {
        val cur = tasks.get(taskId) ?: throw IllegalStateException("Tâche inconnue $taskId")
        val from = stateOf(cur)
        if (from == to) return@withTransaction mutate(cur).also { tasks.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
        if (!TaskTransitions.isAllowed(from, to)) throw IllegalTransitionException(from, to)
        val now = System.currentTimeMillis()
        val next = mutate(cur).copy(
            state = to.wire,
            updatedAt = now,
            endedAt = if (to.terminal) now else null,
            terminationReason = termination?.let { "${it.code}: ${Redactor.redact(it.message)}" } ?: if (to.terminal) cur.terminationReason else null,
            errorJson = error?.let { ContractJson.encodeToString(StructuredError.serializer(), it) } ?: cur.errorJson,
            leaseOwner = if (to.terminal) null else processLease,
            leaseExpiresAt = if (to.terminal) null else now + LEASE_MS,
        )
        tasks.upsert(next)
        runtime.insertEvent(TaskEventEntity(Ids.new(), taskId, from.wire, to.wire, actor, Redactor.redact(reason).take(500), now, cur.traceId.ifEmpty { taskId }, stepId, toolCallId))
        next
    }.also { t -> if (to.terminal && t.endedAt != null && t.state == to.wire && t.updatedAt == t.endedAt) onTerminal?.let { cb -> runCatching { cb(t) } } }

    /** Non-state field updates (counters, taint, plan pointers) — still only through here. */
    /**
     * A same-state event (no transition): e.g. a specialist started or returned its result. [detail]
     * (a contract as JSON) is kept in the reason, redacted and bounded.
     */
    suspend fun record(taskId: String, actor: String, summary: String, detail: String, stepId: String? = null) {
        val cur = tasks.get(taskId) ?: return
        val st = stateOf(cur).wire
        runtime.insertEvent(TaskEventEntity(Ids.new(), taskId, st, st, actor, Redactor.redact("$summary | $detail").take(4_000), System.currentTimeMillis(), cur.traceId.ifEmpty { taskId }, stepId))
    }

    suspend fun update(taskId: String, mutate: (TaskEntity) -> TaskEntity): TaskEntity = db.withTransaction {
        val cur = tasks.get(taskId) ?: throw IllegalStateException("Tâche inconnue $taskId")
        val now = System.currentTimeMillis()
        val next = mutate(cur).copy(state = cur.state, updatedAt = now, leaseExpiresAt = if (stateOf(cur).terminal) null else now + LEASE_MS)
        tasks.upsert(next)
        next
    }

    /** Tasks that were alive when the process died (not terminal and not leased by this process). */
    suspend fun orphaned(): List<TaskEntity> = tasks.nonTerminal().filter { it.leaseOwner != processLease }

    companion object {
        const val LEASE_MS = 120_000L
    }
}
