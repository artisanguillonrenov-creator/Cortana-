package io.github.artisanguillonrenov.cortana.core.checkpoint

import androidx.room.withTransaction
import io.github.artisanguillonrenov.cortana.contracts.Checkpoint
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.contracts.TaskNotebook
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.CheckpointEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.PlanEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskNotebookEntity
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Versioned plan persistence: a revision inserts a new version and deactivates the previous one. */
class PlanStore(private val db: CortanaDatabase) {
    private val dao get() = db.runtime()

    suspend fun save(plan: Plan) {
        db.withTransaction {
            val existing = dao.activePlan(plan.taskId)
            if (existing != null && existing.planId != plan.planId) dao.deactivatePlans(plan.taskId)
            dao.upsertPlan(PlanEntity(plan.planId, plan.taskId, plan.version, plan.strategy.name.lowercase(), encode(plan), plan.createdAt, active = true))
        }
    }

    suspend fun active(taskId: String): Plan? = dao.activePlan(taskId)?.let { decode(it.planJson) }
    fun observeActive(taskId: String): Flow<Plan?> = dao.observeActivePlan(taskId).map { e -> e?.let { decode(it.planJson) } }
    suspend fun versions(taskId: String): List<Plan> = dao.plans(taskId).mapNotNull { decode(it.planJson) }

    private fun encode(p: Plan) = Redactor.redact(ContractJson.encodeToString(Plan.serializer(), p))
    private fun decode(s: String): Plan? = runCatching { ContractJson.decodeFromString(Plan.serializer(), s) }
        .onFailure { CLog.w("plan decode failed", it) }.getOrNull()
}

/**
 * Checkpoints (§67, doc 04 §7) and the TaskNotebook (doc 03 §14). A checkpoint captures what is
 * needed to resume without repeating completed side effects: plan version, completed/pending
 * steps, notebook snapshot, idempotency keys and counters.
 */
class CheckpointService(private val db: CortanaDatabase) {
    private val dao get() = db.runtime()
    private val mutex = Mutex()

    suspend fun save(
        taskId: String,
        plan: Plan?,
        state: TaskState,
        reason: String,
        counters: Map<String, Int> = emptyMap(),
        fingerprints: Map<String, String> = emptyMap(),
        workspaceRevisions: Map<String, String> = emptyMap(),
    ): Checkpoint = mutex.withLock {
        val now = System.currentTimeMillis()
        val ledger = dao.ledgerForTask(taskId).filter { it.status == "succeeded" || it.status == "reconciled" }.map { it.key }
        val nb = notebookOrNull(taskId)
        val cp = Checkpoint(
            checkpointId = Ids.new(), taskId = taskId, planId = plan?.planId, planVersion = plan?.version ?: 0, state = state,
            completedSteps = plan?.steps?.filter { it.status == StepStatus.SUCCEEDED || it.status == StepStatus.SKIPPED }?.map { it.stepId } ?: emptyList(),
            pendingSteps = plan?.steps?.filter { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING || it.status == StepStatus.FAILED }?.map { it.stepId } ?: emptyList(),
            currentStepId = plan?.steps?.firstOrNull { it.status == StepStatus.RUNNING }?.stepId,
            notebook = nb, workspaceRevisions = workspaceRevisions, idempotencyKeys = ledger,
            externalFingerprints = fingerprints, counters = counters, reason = reason, createdAt = now,
        )
        dao.insertCheckpoint(
            CheckpointEntity(cp.checkpointId, taskId, cp.planId, cp.planVersion, state.wire, Redactor.redact(ContractJson.encodeToString(Checkpoint.serializer(), cp)), reason, now)
        )
        if (nb != null) upsertNotebook(nb.copy(lastCheckpointId = cp.checkpointId))
        cp
    }

    /** Latest checkpoint; a corrupted row yields null (→ the orchestrator asks the owner, doc 08 §3.5). */
    suspend fun latest(taskId: String): Checkpoint? = dao.lastCheckpoint(taskId)?.let { e ->
        runCatching { ContractJson.decodeFromString(Checkpoint.serializer(), e.checkpointJson) }.onFailure { CLog.w("corrupted checkpoint for $taskId", it) }.getOrNull()
    }

    suspend fun hasCheckpoint(taskId: String): Boolean = dao.checkpointCount(taskId) > 0

    suspend fun notebookOrNull(taskId: String): TaskNotebook? = dao.notebook(taskId)?.let {
        runCatching { ContractJson.decodeFromString(TaskNotebook.serializer(), it.notebookJson) }.getOrNull()
    }

    suspend fun notebook(taskId: String, objective: String): TaskNotebook = notebookOrNull(taskId) ?: TaskNotebook(taskId = taskId, objective = objective)

    suspend fun updateNotebook(taskId: String, objective: String, transform: (TaskNotebook) -> TaskNotebook): TaskNotebook {
        val nb = transform(notebook(taskId, objective)).copy(updatedAt = System.currentTimeMillis())
        upsertNotebook(nb)
        return nb
    }

    private suspend fun upsertNotebook(nb: TaskNotebook) {
        dao.upsertNotebook(TaskNotebookEntity(nb.taskId, Redactor.redact(ContractJson.encodeToString(TaskNotebook.serializer(), nb)), System.currentTimeMillis()))
    }
}
