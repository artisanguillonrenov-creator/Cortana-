package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.core.checkpoint.CheckpointService
import io.github.artisanguillonrenov.cortana.core.checkpoint.PlanStore
import io.github.artisanguillonrenov.cortana.core.memory.ApprovalEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import kotlinx.coroutines.flow.Flow

/** Read-only task views for the UI (LAW-009: the UI never touches the database). */
class TaskQueries(private val db: CortanaDatabase, private val plans: PlanStore, private val checkpoints: CheckpointService) {
    fun recent(limit: Int = 100): Flow<List<TaskEntity>> = db.tasks().observeRecent(limit)
    fun toolCalls(taskId: String): Flow<List<ToolCallEntity>> = db.tasks().observeToolCalls(taskId)
    fun events(taskId: String): Flow<List<TaskEventEntity>> = db.runtime().observeEvents(taskId)
    fun activePlan(taskId: String): Flow<Plan?> = plans.observeActive(taskId)
    fun approvals(taskId: String): Flow<List<ApprovalEntity>> = db.runtime().observeApprovalsForTask(taskId)
    suspend fun notebook(taskId: String) = checkpoints.notebookOrNull(taskId)
    suspend fun planVersions(taskId: String): List<Plan> = plans.versions(taskId)
}
