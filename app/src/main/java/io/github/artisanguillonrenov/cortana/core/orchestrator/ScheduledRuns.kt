package io.github.artisanguillonrenov.cortana.core.orchestrator

import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleDao
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleEntity
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleRunEntity
import io.github.artisanguillonrenov.cortana.core.model.ToolCall
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.KillSwitch
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.scheduler.ConditionSpec
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.secrets.SecretStore
import io.github.artisanguillonrenov.cortana.core.tools.DispatchRequest
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolDispatcher
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive

/**
 * Executes the durable runs the scheduler recorded (blueprint §39.2-39.5). The scheduler decides
 * *when* and writes the run; this runner decides *how*: a condition watch first calls its read-only
 * capability through the [ToolDispatcher] (same policy, no model call) and stops there when the
 * condition is not met; a task run becomes an ordinary SCHEDULE TaskRequest for the [Orchestrator].
 * Runs stay queued in the table while another task holds the orchestrator and survive restarts.
 */
class ScheduledRunner(
    private val dao: ScheduleDao,
    private val orchestrator: Orchestrator,
    private val dispatcher: ToolDispatcher,
    private val registry: ToolRegistry,
    private val secrets: SecretStore,
    private val audit: AuditLog,
    private val killSwitch: KillSwitch,
) {
    private val lock = Mutex()
    @Volatile private var requested = false
    @Volatile private var current: String? = null
    @Volatile private var cancelReason: String? = null

    /**
     * Executes queued runs in order until none is left or the orchestrator is busy. Any caller may
     * ask at any time: a request made while a drain is in progress is picked up by that drain.
     */
    suspend fun drain(): Int {
        requested = true
        if (!lock.tryLock()) return 0
        var n = 0
        try {
            while (requested) {
                requested = false
                for (r in dao.queuedRuns()) {
                    if (!runOne(r)) break
                    n++
                }
            }
        } finally {
            current = null
            lock.unlock()
        }
        return n
    }

    /** Stops [r] (replace policy, schedule deleted). The run ends as cancelled with [reason]. */
    suspend fun cancel(r: ScheduleRunEntity, reason: String) {
        if (current == r.runId) {
            cancelReason = reason
            orchestrator.cancel(reason)
        } else if (r.status == "running") {
            // Not executing in this process (stale row): close it.
            finish(r, "cancelled", reason)
        }
    }

    /** False when the orchestrator is busy: the run stays queued for the next drain. */
    private suspend fun runOne(r: ScheduleRunEntity): Boolean {
        val s = dao.get(r.scheduleId) ?: run { dao.upsertRun(r.copy(status = "cancelled", finishedAt = now(), detail = "planification supprimée")); return true }
        if (killSwitch.isHalted()) { finish(r, "cancelled", "autonomie arrêtée (STOP)"); return true }
        val objective = runCatching { ScheduleAction.parse(s.actionJson) }.getOrNull()?.objective
            ?: run { finish(r, "failed", "action invalide"); return true }
        if (orchestrator.isBusy()) return false
        current = r.runId
        cancelReason = null
        var hints = emptyMap<String, String>()
        var prefix = ""
        if (s.kind == ScheduleKinds.CONDITION) {
            val cond = runCatching { ScheduleSpec.parse(s.specJson).condition }.getOrNull()
                ?: run { finish(r, "failed", "condition absente ou invalide"); return true }
            dao.upsertRun(r.copy(status = "running", startedAt = now(), detail = "vérification : ${cond.describe()}"))
            val check = check(s, r, cond)
            if (check.error != null) { finish(r, "failed", (check.fp?.let { "fp=$it · " } ?: "") + check.error); return true }
            if (!check.met) { finish(r, "condition_not_met", "fp=${check.fp} · ${cond.describe()} : non"); return true }
            prefix = "fp=${check.fp} · condition remplie · "
            hints = mapOf("untrusted_source" to "surveillance:${cond.capability}", "untrusted_content" to check.text.take(8_000))
            audit.record("scheduler", "schedule.watch.met", s.name, "ok", """{"capability":${JsonPrimitive(cond.capability)}}""")
        }
        dao.upsertRun(r.copy(status = "running", startedAt = now(), detail = prefix + "tâche en cours"))
        val task = try {
            orchestrator.runScheduledRun(s, r.runId, objective, hints)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CLog.e("scheduled run failed", e)
            finish(r, "failed", prefix + "erreur interne : ${e.message}"); return true
        }
        if (task == null) {
            // Lost the race against another ingress: back to the queue, untouched.
            dao.upsertRun(r)
            return false
        }
        val state = TaskState.fromWireOrNull(task.state)
        val status = when (state) {
            TaskState.COMPLETED -> "succeeded"
            TaskState.CANCELLED, TaskState.HALTED -> "cancelled"
            TaskState.FAILED, TaskState.TIMED_OUT -> "failed"
            TaskState.WAITING_USER, TaskState.WAITING_AUTHORIZATION, TaskState.PAUSED -> "waiting"
            else -> "interrupted"
        }
        finish(r.copy(taskId = task.id), status, prefix + (cancelReason ?: task.terminationReason ?: state?.wire ?: task.state))
        return true
    }

    private class Check(val met: Boolean, val fp: String?, val text: String, val error: String?)

    private suspend fun check(s: ScheduleEntity, r: ScheduleRunEntity, cond: ConditionSpec): Check {
        val def = registry.byCapability(cond.capability) ?: return Check(false, null, "", "capacité inconnue : ${cond.capability}")
        watchRefusal(def)?.let { return Check(false, null, "", it) }
        val out = try {
            dispatcher.dispatch(DispatchRequest(
                taskId = "watch:${r.runId}", sessionId = "watch:${s.id}", call = ToolCall(Ids.new(), def.functionName, cond.args),
                allowed = setOf(def.capability), callIndex = 1, tainted = false, taintSources = emptyList(), maxToolCalls = 1,
                ctxFactory = { risk -> WatchContext("watch:${r.runId}", "watch:${s.id}", risk, s.name, secrets) }, scheduleId = s.id,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Check(false, null, "", "vérification impossible : ${e.message}")
        }
        if (!out.result.ok) return Check(false, null, "", "vérification impossible : ${out.result.text.take(300)}")
        val previous = dao.lastFingerprintRun(s.id, r.runId)?.detail?.let { FP.find(it)?.groupValues?.get(1) }
        val (met, fp) = cond.evaluate(out.result.text, previous)
        return Check(met, fp, out.result.text, null)
    }

    private suspend fun finish(r: ScheduleRunEntity, status: String, detail: String) {
        // The schedule may have been deleted meanwhile: never resurrect its runs.
        if (dao.get(r.scheduleId) == null) return
        dao.upsertRun(r.copy(status = status, finishedAt = now(), detail = detail.take(1_000), startedAt = r.startedAt ?: dao.run(r.runId)?.startedAt))
        audit.record("scheduler", "schedule.run", r.scheduleId, status, """{"run":"${r.runId}"}""")
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private val FP = Regex("^fp=([0-9a-f]{16})")

        /** Why [def] cannot be watched, or null: only read-only, non-UI capabilities (no approval loop in the background). */
        fun watchRefusal(def: ToolDefinition): String? = when {
            def.sideEffect != SideEffect.NONE -> "« ${def.capability} » a un effet ; une surveillance n'utilise que des lectures"
            def.baseRisk.level > Risk.L1.level -> "« ${def.capability} » est trop sensible pour une surveillance"
            def.category == ToolCategory.UI -> "« ${def.capability} » pilote l'écran ; impossible en arrière-plan"
            def.capability in setOf("ask_user", "tools_discover") -> "« ${def.capability} » n'est pas une lecture"
            else -> null
        }
    }
}

/** Tool context of a condition check: no task, never tainted, nothing on screen. */
private class WatchContext(
    override val taskId: String,
    override val sessionId: String,
    override val approvedRisk: Risk,
    override val lastUserText: String,
    private val secrets: SecretStore,
) : ToolContext {
    override val tainted = false
    override val incognito = false
    override val toolset = Toolsets.FULL
    override fun resolveSecret(handle: String?): String? = null
    override fun markUiAutomation() = Unit
}
