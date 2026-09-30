package io.github.artisanguillonrenov.cortana.design

import io.github.artisanguillonrenov.cortana.contracts.ExpectedOutcome
import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.contracts.PlanStep
import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.ui.components.Approval
import io.github.artisanguillonrenov.cortana.ui.components.LogKind
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.StepState
import io.github.artisanguillonrenov.cortana.ui.cortana.TaskRunProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Design step 4: the task panel is a faithful projection of the runtime's records, never an estimate. */
class TaskRunProjectionTest {
    private val t0 = 1_700_000_000_000L
    private val utc = ZoneOffset.UTC
    private fun task(state: String, ended: Long? = null) = TaskEntity("t", "s", "Créer l'app Notes\nsuite", "dag", state, false, "{}", t0, endedAt = ended, updatedAt = ended ?: t0)
    private fun plan(vararg st: StepStatus) = Plan(planId = "p", taskId = "t", version = 1, objective = "o", strategy = PlanStrategy.DAG, createdAt = t0,
        steps = st.mapIndexed { i, s -> PlanStep("s$i", i, "Étape ${i + 1}", "obj", expectedOutcome = ExpectedOutcome("ok"), status = s) })
    private fun call(cap: String, outcome: String, at: Long, input: String = "{}") = ToolCallEntity("c$at", "t", "s0", cap, input, null, "{}", outcome, null, at)
    private fun ev(to: String, at: Long, from: String? = null, reason: String = "") = TaskEventEntity("e$at", "t", from, to, "o", reason, at, "tr")
    private val label: (String) -> String = { cap -> mapOf("dev.build" to "Build", "files.write" to "Écrire un fichier", "device.install" to "Installer").getValue(cap) }

    @Test fun runningPlanShowsStepProgressAndElapsedTimeNeverAnEstimate() {
        val p = TaskRunProjection.project(task("running"), plan(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED, StepStatus.RUNNING, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING),
            emptyList(), emptyList(), TaskRunProjection.Live("Compilation…", false), approvalPending = false, label = label, now = t0 + 150_000, zone = utc)
        assertEquals(RunStatus.Running, p.run.status)
        assertEquals("3/6", p.progressLabel)
        assertEquals(2f / 6f, p.run.progress, 0.001f)
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Running, StepState.Upcoming, StepState.Upcoming, StepState.Upcoming), p.steps.map { it.state })
        assertEquals("Étape 3/6 · Étape 3", p.stepLabel)
        assertEquals("En cours depuis 2 min", p.remaining)
        assertEquals("En cours…", p.planLabel)
        assertEquals("the live status is the last line", LogKind.Run, p.run.logs.last().kind)
    }

    @Test fun approvalPauseResumeAndEndAreLoggedInOrderWithTheirKinds() {
        val calls = listOf(call("dev.build", "ok", t0 + 1_000), call("files.write", "ok", t0 + 2_000, """{"path":"app/src/Note.kt","content":"x"}"""), call("device.install", "denied", t0 + 5_000))
        val events = listOf(ev("running", t0 + 500), ev("waiting_authorization", t0 + 4_000, "running"), ev("running", t0 + 4_500, "waiting_authorization"),
            ev("paused", t0 + 6_000, "running", "Arrêt demandé · tâche suspendue"), ev("running", t0 + 7_000, "paused"), ev("completed", t0 + 9_000, "running"))
        val p = TaskRunProjection.project(task("completed", ended = t0 + 278_000), plan(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), calls, events, null, false, label, t0 + 300_000, utc)
        assertEquals(RunStatus.Done, p.run.status)
        assertEquals("Terminée en 4 min 38 s", p.remaining)
        assertEquals("2/2", p.progressLabel)
        assertEquals(
            listOf(LogKind.Ok, LogKind.Dir, LogKind.Wait, LogKind.Stop, LogKind.Stop, LogKind.Play, LogKind.Ok),
            p.run.logs.map { it.kind },
        )
        assertEquals("22:13:21", p.run.logs.first().time) // t0 + 1 s, UTC, HH:mm:ss
        assertTrue(p.run.logs.any { it.text == "Installer · refusé" })
        assertTrue(p.run.logs.any { it.text == "Reprise de la tâche" })
        assertEquals(listOf("app/src/Note.kt"), p.files.map { it.name })
        assertFalse("tool inputs are never shown", p.run.logs.any { it.text.contains("content") })
    }

    @Test fun statesOutsideTheDesignAreNeverShownAsDone() {
        assertEquals(RunStatus.Failed, TaskRunProjection.status(io.github.artisanguillonrenov.cortana.contracts.TaskState.FAILED, null, false))
        assertEquals(RunStatus.Stopped, TaskRunProjection.status(io.github.artisanguillonrenov.cortana.contracts.TaskState.HALTED, null, false))
        assertEquals(RunStatus.Paused, TaskRunProjection.status(io.github.artisanguillonrenov.cortana.contracts.TaskState.PAUSED, null, false))
        assertEquals(RunStatus.AwaitingApproval, TaskRunProjection.status(io.github.artisanguillonrenov.cortana.contracts.TaskState.RUNNING, null, approvalPending = true))
        val p = TaskRunProjection.project(task("paused"), plan(StepStatus.SUCCEEDED, StepStatus.RUNNING, StepStatus.PENDING), emptyList(), emptyList(), null, false, label, t0, utc)
        assertEquals("the interrupted step waits (amber), it does not spin", StepState.Waiting, p.steps[1].state)
        assertEquals(Approval.None, p.run.approval)
        assertEquals("En pause", p.remaining)
        val noPlan = TaskRunProjection.project(task("running"), null, emptyList(), emptyList(), TaskRunProjection.Live("Je réfléchis…", false), false, label, t0, utc)
        assertEquals("", noPlan.progressLabel)
        assertEquals("Je réfléchis…", noPlan.stepLabel)
    }
}
