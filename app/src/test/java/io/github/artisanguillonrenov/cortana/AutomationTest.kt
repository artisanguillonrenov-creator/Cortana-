package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleEntity
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleRunEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.scheduler.ConditionSpec
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Phase 27 automation (blueprint §39): durable scheduled runs, concurrency policies, catch-up,
 * restart recovery and condition watches — the scheduler records, the orchestrator side executes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomationTest : CortanaTestBase() {
    private val dao get() = c.db.schedules()
    private val zone = ZoneId.of("Europe/Paris")

    private fun task(name: String, concurrency: String = "skip", missed: String? = null): ScheduleEntity = runBlocking {
        c.scheduler.create(name, ScheduleKinds.INTERVAL, ScheduleSpec(everyMinutes = 60, startAt = System.currentTimeMillis() + 3_600_000),
            ScheduleAction("task", objective = "Fais le point sur $name"), zone, concurrency, missed)
    }

    private fun watch(test: String, value: String?): ScheduleEntity = runBlocking {
        c.scheduler.create("Colis", ScheduleKinds.CONDITION,
            ScheduleSpec(everyMinutes = 15, startAt = System.currentTimeMillis() + 900_000, condition = ConditionSpec("memory.search", """{"query":"colis"}""", test, value)),
            ScheduleAction("task", objective = "Préviens-moi que le colis est arrivé"), zone)
    }

    private fun runs(s: ScheduleEntity) = runBlocking { dao.runs(s.id, 50) }.sortedBy { it.queuedAt }

    /** Waits until no run of [s] is queued or running and the orchestrator is idle. */
    private fun settle(s: ScheduleEntity): List<ScheduleRunEntity> {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (runs(s).none { it.status == "queued" || it.status == "running" } && !c.orchestrator.isBusy()) return runs(s)
            Thread.sleep(40)
        }
        throw AssertionError("runs did not settle: ${runs(s)}")
    }

    private fun remember(text: String) = runBlocking { c.memory.save(text, MemoryTypes.SEMANTIC, MemoryStatus.ACTIVE, "explicit") }

    private fun ctx() = object : ToolContext {
        override val taskId = "t-auto"; override val sessionId = "s"; override val tainted = false; override val lastUserText = ""; override val incognito = false
        override val approvedRisk = Risk.L2; override val toolset = "full"
        override fun resolveSecret(handle: String?): String? = null; override fun markUiAutomation() {}
    }

    private fun tool(cap: String, args: String) = runBlocking {
        c.registry.byCapability(cap)!!.invokeAuthorized(AppJson.parseToJsonElement(args) as JsonObject, ctx(), PolicyDecision(cap, Risk.L2, Risk.L2, Requirement.ALLOW, emptyList(), false))
    }

    /** Records runs without executing them (the drain trigger is detached for policy checks). */
    private fun <T> recordOnly(block: () -> T): T {
        val saved = c.scheduler.onRunQueued
        c.scheduler.onRunQueued = null
        try { return block() } finally { c.scheduler.onRunQueued = saved }
    }

    @Test fun concurrencyPoliciesDecideAgainstOpenRuns() {
        recordOnly {
            runBlocking {
                // skip (default, 1.2.0 behaviour): never a second run while one is open.
                val skip = task("skip")
                assertEquals("mise en file", c.scheduler.enqueueRun(skip, 1L, late = false, manual = false))
                assertTrue(c.scheduler.enqueueRun(skip, 2L, late = false, manual = false).startsWith("ignorée"))
                assertEquals(listOf("queued", "skipped"), runs(skip).map { it.status })

                // queue: one may wait behind the running one, not two.
                val queue = task("queue", "queue")
                c.scheduler.enqueueRun(queue, 1L, false, false)
                dao.upsertRun(runs(queue).single().copy(status = "running", startedAt = 1L))
                assertEquals("mise en file", c.scheduler.enqueueRun(queue, 2L, false, false))
                assertTrue(c.scheduler.enqueueRun(queue, 3L, false, false).startsWith("ignorée"))
                assertEquals(listOf("running", "queued", "skipped"), runs(queue).map { it.status })

                // allow: every due run is recorded.
                val allow = task("allow", "allow")
                repeat(3) { c.scheduler.enqueueRun(allow, it.toLong(), false, false) }
                assertEquals(3, runs(allow).count { it.status == "queued" })

                // replace: waiting runs are cancelled and the running one is stopped; the newest waits.
                val replace = task("replace", "replace")
                c.scheduler.enqueueRun(replace, 1L, false, false)
                dao.upsertRun(runs(replace).single().copy(status = "running", startedAt = 1L))
                c.scheduler.enqueueRun(replace, 2L, false, false)
                c.scheduler.enqueueRun(replace, 3L, false, false)
                val st = runs(replace)
                assertEquals(listOf("cancelled", "cancelled", "queued"), st.map { it.status })
                assertEquals(3L, st.last().dueAt)

                // Unknown policies are refused at creation.
                assertTrue(runCatching { task("x", "parallel") }.exceptionOrNull() is IllegalArgumentException)
                // Disabling a schedule cancels what still waits.
                c.scheduler.setEnabled(allow.id, false)
                assertTrue(runs(allow).all { it.status == "cancelled" })
            }
        }
    }

    @Test fun queuedRunWaitsForTheBusyOrchestratorThenExecutes() {
        val s = session()
        remember("Code du portail : 1234")
        // The chat task stops on an L2 approval: the orchestrator is busy meanwhile.
        server.enqueue(toolCall("memory_forget", """{"query":"portail"}"""))
        server.enqueue(text("Je n'ai rien oublié."))
        server.enqueue(text("Point du jour : rien à signaler."))
        val sched = task("journal")
        check(c.orchestrator.submit(s.id, "Oublie le code du portail"))
        val deadline = System.currentTimeMillis() + 15_000
        while (c.approvals.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val pending = c.approvals.pending.value!!
        runBlocking { c.scheduler.runNow(sched.id) }
        Thread.sleep(300)
        assertEquals("queued", runs(sched).single().status) // durable, not dropped and not blocking the scheduler
        c.approvals.resolve(pending.id, ApprovalDecision(false))
        val done = settle(sched).single()
        assertEquals("succeeded", done.status)
        val t = runBlocking { c.db.tasks().get(done.taskId!!) }!!
        assertEquals("schedule", t.source)
        assertTrue(messages(runBlocking { c.conversations.session(t.sessionId) }!!).last().text.contains("rien à signaler"))
        assertTrue(runBlocking { c.db.audit().allAscending() }.any { it.action == "schedule.run.queued" })
    }

    @Test fun restartMarksRunningRunsInterruptedAndResumesTheQueue() {
        session()
        server.enqueue(text("Relevé fait."))
        val sched = task("relevé")
        runBlocking {
            recordOnly {
                runBlocking {
                    // Rows left by a process that died: one mid-execution, one still waiting.
                    val t0 = System.currentTimeMillis() - 600_000
                    dao.upsertRun(ScheduleRunEntity(Ids.new(), sched.id, t0, t0, "running", startedAt = t0))
                    dao.upsertRun(ScheduleRunEntity(Ids.new(), sched.id, t0 + 1, t0 + 1, "queued"))
                    // Beyond the 60-day retention, finished runs are purged at the same time.
                    dao.upsertRun(ScheduleRunEntity(Ids.new(), sched.id, 1L, 1L, "succeeded", finishedAt = 2L))
                }
            }
            assertEquals(1, c.scheduler.recoverRuns())
        }
        val st = settle(sched)
        assertEquals(listOf("interrupted", "succeeded"), st.map { it.status })
        assertTrue(st.first().detail!!.contains("processus arrêté"))
    }

    @Test fun concurrentRearmsCatchAMissedRunUpOnce() {
        // Boot, startup maintenance and restore can re-arm at the same moment: the missed run is caught up once.
        recordOnly {
            runBlocking {
                val s = task("double", missed = "catch_up_once")
                dao.upsert(s.copy(nextRunAt = System.currentTimeMillis() - 3 * 3_600_000L))
                kotlinx.coroutines.coroutineScope {
                    repeat(4) { launch(kotlinx.coroutines.Dispatchers.Default) { c.scheduler.rearmAll(catchUp = true) } }
                }
                assertEquals(1, runs(s).size)
            }
        }
    }

    @Test fun missedRunsFollowTheSchedulePolicy() {
        recordOnly {
            runBlocking {
                val past = System.currentTimeMillis() - 3 * 3_600_000L
                val catchUp = task("rattrapage", missed = "catch_up_once")
                val skipped = task("ignorée", missed = "skip")
                dao.upsert(catchUp.copy(nextRunAt = past))
                dao.upsert(skipped.copy(nextRunAt = past))
                c.scheduler.rearmAll(catchUp = true)
                val r = runs(catchUp).single()
                assertTrue(r.late && r.status == "queued" && r.dueAt == past)
                assertTrue(dao.get(catchUp.id)!!.lastOutcome!!.startsWith("rattrapage"))
                assertTrue(dao.get(catchUp.id)!!.nextRunAt!! > System.currentTimeMillis())
                assertTrue(runs(skipped).isEmpty())
                assertEquals("manqué (ignoré)", dao.get(skipped.id)!!.lastOutcome)
            }
        }
    }

    @Test fun conditionWatchChecksWithoutModelAndRunsATaintedTaskOnlyWhenMet() {
        session()
        val w = watch("contains", "livré")
        runBlocking { c.scheduler.runNow(w.id) }
        val first = settle(w).single()
        assertEquals("condition_not_met", first.status)
        assertEquals(0, server.requestCount) // no model call, no task
        assertNull(first.taskId)
        assertTrue(runBlocking { c.db.tasks().taskCount() } == 0)
        // The check itself went through the dispatcher (recorded like any tool call).
        val call = runBlocking { c.db.tasks().toolCalls("watch:${first.runId}") }.single()
        assertEquals("memory.search" to "ok", call.capability to call.outcome)

        remember("Le colis est livré chez le voisin")
        server.enqueue(text("Votre colis est arrivé chez le voisin."))
        runBlocking { c.scheduler.runNow(w.id) }
        val met = settle(w).last()
        assertEquals("succeeded", met.status)
        assertTrue(met.detail!!.contains("condition remplie"))
        val t = runBlocking { c.db.tasks().get(met.taskId!!) }!!
        assertTrue(t.tainted) // what the watch read is data, never an instruction
        val body = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(body.contains("surveillance:memory.search") && body.contains("livré chez le voisin"))
    }

    @Test fun changedWatchNeedsABaselineThenFiresOnChange() {
        session()
        remember("Colis : en préparation")
        val w = watch("changed", null)
        runBlocking { c.scheduler.runNow(w.id) }
        settle(w)
        runBlocking { c.scheduler.runNow(w.id) }
        assertEquals(listOf("condition_not_met", "condition_not_met"), settle(w).map { it.status }) // baseline, then unchanged
        remember("Colis : expédié")
        server.enqueue(text("Le suivi du colis a changé : expédié."))
        runBlocking { c.scheduler.runNow(w.id) }
        assertEquals("succeeded", settle(w).last().status)
        assertEquals(1, server.requestCount)
    }

    @Test fun conditionTestsAreDeterministic() {
        fun met(test: String, value: String?, text: String, prev: String? = null) = ConditionSpec("memory.search", "{}", test, value).evaluate(text, prev).first
        assertTrue(met("contains", "LIVRÉ", "colis livré"))
        assertTrue(met("not_contains", "rupture", "en stock"))
        assertTrue(met("regex", "prix: \\d+", "prix: 42"))
        assertTrue(met("below", "50", "Prix actuel : 42,90 €"))
        assertFalse(met("above", "50", "Prix actuel : 42,90 €"))
        assertFalse(met("above", "50", "pas de nombre"))
        val fp = ConditionSpec("memory.search", "{}", "changed", null).evaluate("a", null)
        assertFalse(fp.first)
        assertFalse(met("changed", null, "a", fp.second))
        assertTrue(met("changed", null, "b", fp.second))
        assertTrue(runCatching { ConditionSpec("x", "{}", "contains", null) }.isFailure)
        assertTrue(runCatching { ConditionSpec("x", "{}", "regex", "(") }.isFailure)
        assertTrue(runCatching { ConditionSpec("x", "{}", "above", "beaucoup") }.isFailure)
    }

    @Test fun scheduleToolsCreateWatchesOnlyOnReadOnlyCapabilities() {
        val refused = tool("schedule.create", """{"kind":"condition_watch","objective":"x","every_minutes":15,"watch":{"capability":"notify.owner","args":{"message":"x"},"test":"changed"}}""")
        assertFalse(refused.ok)
        assertTrue(refused.text.contains("effet"))
        assertFalse(tool("schedule.create", """{"kind":"condition_watch","objective":"x","every_minutes":15,"watch":{"capability":"inconnue","test":"changed"}}""").ok)
        val ok = tool("schedule.create", """{"kind":"condition_watch","name":"Stock","objective":"Préviens-moi","every_minutes":30,"concurrency":"queue","watch":{"capability":"memory.search","args":{"query":"stock"},"test":"contains","value":"disponible"}}""")
        assertTrue(ok.text, ok.ok)
        val s = runBlocking { c.scheduler.all() }.single()
        assertEquals(ScheduleKinds.CONDITION to "queue", s.kind to s.concurrencyPolicy)
        assertTrue(tool("schedule.list", "{}").text.contains("« disponible » apparaît"))
        assertTrue(tool("schedule.runs", """{"id":"${s.id.take(8)}"}""").text.startsWith("Aucune exécution"))
        assertFalse(tool("schedule.create", """{"kind":"interval","objective":"x","every_minutes":30,"concurrency":"parallel"}""").ok)
    }

    @Test fun stopCancelsQueuedRunsAndDeleteRemovesThem() {
        session()
        val a = task("a")
        recordOnly { runBlocking { c.scheduler.enqueueRun(a, 1L, false, false) } }
        c.killSwitch.halt("test")
        try {
            runBlocking { c.scheduledRuns.drain() }
            assertEquals("cancelled", runs(a).single().status)
            assertEquals(0, server.requestCount)
        } finally { c.killSwitch.resume("test") }
        runBlocking { c.scheduler.delete(a.id) }
        assertTrue(runs(a).isEmpty())
    }
}
