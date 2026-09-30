package io.github.artisanguillonrenov.cortana.ui.components

import androidx.compose.runtime.Immutable

/**
 * State of the active task as the screen shows it (design "Gestion de l'état", spec 11.3). The design
 * draws four; [Failed] and [Stopped] exist so that a task that did not succeed is never shown "Terminée".
 */
enum class RunStatus { Running, AwaitingApproval, Paused, Done, Failed, Stopped }

/** The approval of the running step: none, waiting for the owner, granted once, refused. */
enum class Approval { None, Pending, Granted, Refused }

/** Kind of a live log line: an icon and two colors each (README "Couleurs des logs"). */
enum class LogKind { Run, Ok, Dir, Stop, Play, Wait }

@Immutable
data class LogLine(val time: String, val kind: LogKind, val text: String)

/** A step of the execution plan: done, running (spinner), waiting or paused (amber ring), upcoming, failed. */
enum class StepState { Done, Running, Waiting, Upcoming, Failed }

@Immutable
data class PlanStep(val title: String, val state: StepState)

@Immutable
data class TaskRunUi(
    val status: RunStatus,
    /** 0-based index of the current step. */
    val step: Int,
    val stepCount: Int,
    /** 0..1 */
    val progress: Float,
    val logs: List<LogLine>,
    val approval: Approval,
    val liveMessage: String,
    val eta: String,
)
