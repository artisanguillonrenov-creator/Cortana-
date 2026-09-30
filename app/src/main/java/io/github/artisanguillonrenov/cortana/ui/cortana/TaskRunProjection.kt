package io.github.artisanguillonrenov.cortana.ui.cortana

import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.ui.components.Approval
import io.github.artisanguillonrenov.cortana.ui.components.FileChange
import io.github.artisanguillonrenov.cortana.ui.components.LogKind
import io.github.artisanguillonrenov.cortana.ui.components.LogLine
import io.github.artisanguillonrenov.cortana.ui.components.PlanStep
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.StepState
import io.github.artisanguillonrenov.cortana.ui.components.TaskRunUi
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The task panel of the design, projected from the durable records of the one runtime (spec 11.3):
 * the task, its active plan, its tool calls and state transitions, the live state of the orchestrator
 * and the approval it waits for. Pure: no service is called here, so the mapping is tested as is.
 * Nothing is estimated: the design's "~ 2-3 min restantes" becomes the real elapsed time.
 */
object TaskRunProjection {

    data class Live(val status: String, val waitingApproval: Boolean)

    data class Panel(
        val run: TaskRunUi,
        val steps: List<PlanStep>,
        /** "3/6", or "" without a plan. */
        val progressLabel: String,
        /** Line under the progress bar ("En cours depuis 2 min", "En attente de votre accord", "Terminée en 4 min 38 s"). */
        val remaining: String,
        /** Plan card header: "En cours…", "En attente", "En pause", "Terminé". */
        val planLabel: String,
        val stepLabel: String,
        val files: List<FileChange>,
        val startedAt: Long,
    )

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun status(state: TaskState, live: Live?, approvalPending: Boolean): RunStatus = when (state) {
        TaskState.COMPLETED -> RunStatus.Done
        TaskState.PAUSED -> RunStatus.Paused
        TaskState.FAILED -> RunStatus.Failed
        TaskState.CANCELLED, TaskState.HALTED, TaskState.TIMED_OUT, TaskState.INTERRUPTED -> RunStatus.Stopped
        TaskState.WAITING_AUTHORIZATION -> RunStatus.AwaitingApproval
        else -> if (approvalPending || live?.waitingApproval == true) RunStatus.AwaitingApproval else RunStatus.Running
    }

    fun steps(plan: Plan?, status: RunStatus): List<PlanStep> = plan?.steps.orEmpty().sortedBy { it.ordinal }.map { s ->
        PlanStep(
            s.title,
            when (s.status) {
                StepStatus.SUCCEEDED, StepStatus.SKIPPED -> StepState.Done
                StepStatus.RUNNING -> if (status == RunStatus.Running) StepState.Running else StepState.Waiting
                StepStatus.FAILED, StepStatus.CANCELLED -> StepState.Failed
                StepStatus.PENDING -> StepState.Upcoming
            },
        )
    }

    /** Current step: the running one, else the first not done, else the last. */
    fun currentStep(steps: List<PlanStep>): Int =
        steps.indexOfFirst { it.state == StepState.Running || it.state == StepState.Waiting }.takeIf { it >= 0 }
            ?: steps.indexOfFirst { it.state != StepState.Done }.takeIf { it >= 0 }
            ?: (steps.size - 1).coerceAtLeast(0)

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    private fun elapsed(ms: Long): String {
        val m = ms / 60_000
        return if (m < 1) "moins d’une minute" else if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    /**
     * Log lines of the console: tool calls by outcome, and the transitions that matter to the owner
     * (approval, stop, resume, end). Tool inputs are never shown: only the tool's label.
     */
    fun logs(toolCalls: List<ToolCallEntity>, events: List<TaskEventEntity>, label: (String) -> String, zone: ZoneId, live: Live?, now: Long): List<LogLine> {
        fun t(at: Long) = clock.format(Instant.ofEpochMilli(at).atZone(zone))
        val out = mutableListOf<Pair<Long, LogLine>>()
        toolCalls.forEach { c ->
            val name = label(c.capability)
            val line = when (c.outcome) {
                "ok" -> LogLine(t(c.createdAt), if (isFileCapability(c.capability)) LogKind.Dir else LogKind.Ok, name)
                "skipped_duplicate" -> LogLine(t(c.createdAt), LogKind.Run, "$name · déjà fait")
                "denied", "refused" -> LogLine(t(c.createdAt), LogKind.Stop, "$name · refusé")
                "cancelled" -> LogLine(t(c.createdAt), LogKind.Stop, "$name · annulé")
                else -> LogLine(t(c.createdAt), LogKind.Stop, "$name · échec")
            }
            out += c.createdAt to line
        }
        var previous: String? = null
        events.forEach { e ->
            val line = when (e.toState) {
                TaskState.PLANNING.wire -> LogLine(t(e.at), LogKind.Run, "Planification")
                TaskState.WAITING_AUTHORIZATION.wire -> LogLine(t(e.at), LogKind.Wait, "Approbation requise")
                TaskState.VERIFYING.wire -> LogLine(t(e.at), LogKind.Run, "Vérification")
                TaskState.REPLANNING.wire -> LogLine(t(e.at), LogKind.Run, "Nouveau plan")
                TaskState.PAUSED.wire -> LogLine(t(e.at), LogKind.Stop, e.reason.ifBlank { "Arrêt demandé · tâche suspendue" })
                TaskState.RUNNING.wire -> if (previous == TaskState.PAUSED.wire) LogLine(t(e.at), LogKind.Play, "Reprise de la tâche") else null
                TaskState.COMPLETED.wire -> LogLine(t(e.at), LogKind.Ok, "Tâche terminée")
                TaskState.FAILED.wire -> LogLine(t(e.at), LogKind.Stop, "Échec · ${e.reason}".take(120))
                TaskState.CANCELLED.wire, TaskState.HALTED.wire, TaskState.TIMED_OUT.wire -> LogLine(t(e.at), LogKind.Stop, e.reason.ifBlank { "Tâche arrêtée" }.take(120))
                else -> null
            }
            if (line != null) out += e.at to line
            previous = e.toState
        }
        val sorted = out.sortedBy { it.first }.map { it.second }
        return if (live != null && live.status.isNotBlank()) sorted + LogLine(t(now), LogKind.Run, live.status.take(120)) else sorted
    }

    private fun isFileCapability(capability: String) = capability.startsWith("files.") || capability.startsWith("fs.") || capability.startsWith("storage.") || capability.startsWith("workspace.write")

    /** Files written by the task (from the tool calls' "path"), most recent last; no invented diff counts. */
    fun files(toolCalls: List<ToolCallEntity>): List<FileChange> = toolCalls
        .filter { it.outcome == "ok" && (isFileCapability(it.capability) || it.capability.startsWith("dev.")) }
        .mapNotNull { c ->
            runCatching { (AppJson.parseToJsonElement(c.inputJson) as? JsonObject)?.let { o -> (o["path"] ?: o["file"])?.jsonPrimitive?.contentOrNull } }.getOrNull()
        }
        .distinct().takeLast(40)
        .map { FileChange(it, 0, 0) }

    fun project(
        task: TaskEntity,
        plan: Plan?,
        toolCalls: List<ToolCallEntity>,
        events: List<TaskEventEntity>,
        live: Live?,
        approvalPending: Boolean,
        label: (String) -> String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Panel {
        val state = TaskState.fromWireOrNull(task.state) ?: TaskState.RUNNING
        val status = status(state, live, approvalPending)
        val steps = steps(plan, status)
        val current = currentStep(steps)
        val done = steps.count { it.state == StepState.Done }
        val progress = if (steps.isEmpty()) (if (status == RunStatus.Done) 1f else 0f) else done.toFloat() / steps.size
        val started = task.createdAt
        val ended = task.endedAt ?: task.updatedAt
        val remaining = when (status) {
            RunStatus.Done -> "Terminée en ${duration(ended - started)}"
            RunStatus.AwaitingApproval -> "En attente de votre accord"
            RunStatus.Paused -> "En pause"
            RunStatus.Failed -> "Échec après ${duration(ended - started)}"
            RunStatus.Stopped -> "Arrêtée après ${duration(ended - started)}"
            RunStatus.Running -> "En cours depuis ${elapsed(now - started)}"
        }
        val planLabel = when (status) {
            RunStatus.Running -> "En cours…"; RunStatus.AwaitingApproval -> "En attente"; RunStatus.Paused -> "En pause"
            RunStatus.Done -> "Terminé"; RunStatus.Failed -> "Échec"; RunStatus.Stopped -> "Arrêté"
        }
        val stepLabel = when {
            steps.isEmpty() -> live?.status?.takeIf { it.isNotBlank() } ?: task.objective.lineSequence().first().take(80)
            status == RunStatus.Done -> "Étape ${steps.size}/${steps.size} · Tâche terminée"
            else -> "Étape ${current + 1}/${steps.size} · ${steps[current].title}"
        }
        val run = TaskRunUi(
            status = status, step = current, stepCount = steps.size, progress = progress,
            logs = logs(toolCalls, events, label, zone, live?.takeIf { status == RunStatus.Running }, now),
            approval = if (status == RunStatus.AwaitingApproval) Approval.Pending else Approval.None,
            liveMessage = live?.status.orEmpty(), eta = if (status == RunStatus.Done) duration(ended - started) else elapsed(now - started),
        )
        return Panel(run, steps, if (steps.isEmpty()) "" else if (status == RunStatus.Done) "${steps.size}/${steps.size}" else "${current + 1}/${steps.size}",
            remaining, planLabel, stepLabel, files(toolCalls), started)
    }
}
