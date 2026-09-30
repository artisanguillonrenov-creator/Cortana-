package io.github.artisanguillonrenov.cortana.core.orchestrator

/**
 * A domain service that extends the one orchestrator without owning a loop (doc 03 §15: the
 * Software Factory "uses the same Planner, tools, Policy Engine and Orchestrator").
 */
interface TaskExtension {
    /** Extra operating guidance for the planner and the agent (data for the prompt), or null. */
    suspend fun guidance(objective: String, coding: Boolean): String? = null

    /**
     * Called before a task resumes (after a crash, or with the owner's [ownerReply] to a question);
     * may add notes or require the owner's confirmation first.
     */
    suspend fun onResume(taskId: String, afterCrash: Boolean, ownerReply: String?): ResumeCheck? = null

    /** Called once the task reached a terminal state: release per-task resources (e.g. browser session). */
    suspend fun onTaskEnd(taskId: String) {}
}

/** [blockReason] non-null: do not continue automatically, tell the owner why and wait. */
data class ResumeCheck(val notes: List<String>, val blockReason: String? = null)
