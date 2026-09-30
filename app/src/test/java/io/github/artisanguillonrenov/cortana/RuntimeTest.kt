package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.ExpectedOutcome
import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.contracts.PlanStep
import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.contracts.TaskRequest
import io.github.artisanguillonrenov.cortana.contracts.TaskSource
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.IdempotencyEntity
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.orchestrator.IllegalTransitionException
import io.github.artisanguillonrenov.cortana.core.outbox.Outbox
import io.github.artisanguillonrenov.cortana.core.outbox.OutboxRetryException
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VNext phases 3–5 gates: state machine, planner/verifier/recovery, checkpoints & resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimeTest : CortanaTestBase() {

    @Test fun stateMachineRejectsIllegalTransitionsAndAuditsEveryChange() = runBlocking {
        val s = session()
        val sm = c.stateMachine
        val t = sm.create(TaskRequest(requestId = "r", source = TaskSource.CHAT, objective = "x", createdAt = 1), s.id, "interactive")
        try { sm.transition(t.id, TaskState.VERIFYING, "test", "saut interdit"); fail() } catch (e: IllegalTransitionException) { }
        sm.transition(t.id, TaskState.PLANNING, "test", "ok")
        sm.transition(t.id, TaskState.COMPLETED, "test", "fin")
        try { sm.transition(t.id, TaskState.RUNNING, "test", "terminal figé"); fail() } catch (e: IllegalTransitionException) { }
        val ev = c.db.runtime().events(t.id)
        assertEquals(listOf("received", "planning", "completed"), ev.map { it.toState })
        assertEquals(TaskState.COMPLETED.wire, sm.get(t.id)!!.state)
        assertNotNull(sm.get(t.id)!!.endedAt)
    }

    @Test fun dagPlanRunsStepsVerifiesThenSynthesizes() {
        val s = session()
        server.enqueue(text(planJson("""[
            {"id":"s1","title":"Lister","objective":"Lister les planifications","capabilities":["schedule.list"],"checks":[{"type":"tool_succeeded","target":"schedule.list"}]},
            {"id":"s2","title":"Chercher","objective":"Chercher en mémoire","capabilities":["memory.search"],"depends_on":["s1"]}]""")))
        server.enqueue(toolCall("schedule_list", "{}"))
        server.enqueue(text("Aucune planification trouvée."))
        server.enqueue(toolCall("memory_search", """{"query":"café"}""", id = "call_2"))
        server.enqueue(text("Rien en mémoire."))
        server.enqueue(text("""{"status":"passed","confidence":0.8,"reason":"recherche effectuée"}"""))
        server.enqueue(text("Compte rendu final : rien de planifié, rien en mémoire."))
        runAndWait(s, "Liste mes planifications, puis cherche ce que tu sais de mon café, ensuite fais-moi un résumé.")
        val t = lastTask()
        assertEquals("completed", t.state)
        assertEquals(7, server.requestCount)
        val plan = runBlocking { c.planStore.active(t.id)!! }
        assertEquals(PlanStrategy.DAG, plan.strategy)
        assertTrue(plan.steps.all { it.status == StepStatus.SUCCEEDED })
        assertEquals("Compte rendu final : rien de planifié, rien en mémoire.", messages(s).last { it.role == Roles.ASSISTANT && !it.hidden }.text)
        val states = events(t.id).map { it.toState }
        assertTrue(states.containsAll(listOf("classified", "planning", "running", "verifying", "completed")))
        assertTrue(runBlocking { c.db.runtime().checkpointCount(t.id) } >= 3)
    }

    @Test fun falseSuccessIsCaughtRetriedThenReplanned() {
        val s = session()
        server.enqueue(text(planJson("""[{"id":"s1","title":"Trouver le code","objective":"Trouver le mot code","checks":[{"type":"text_contains","expected":"ZEBRA"}]}]""")))
        server.enqueue(text("Terminé."))            // claims success, check fails → retry
        server.enqueue(text("Terminé, vraiment."))  // same failure → replan
        server.enqueue(text(planJson("""[{"id":"s2","title":"Nouvelle approche","objective":"Retrouver le mot code autrement","checks":[{"type":"text_contains","expected":"ZEBRA"}]}]""")))
        server.enqueue(text("Le mot code est ZEBRA."))
        server.enqueue(text("J'ai trouvé : ZEBRA."))
        runAndWait(s, "Trouve le mot code, puis vérifie-le, ensuite dis-le moi.")
        val t = lastTask()
        assertEquals("completed", t.state)
        assertEquals(1, t.replanCount)
        val plan = runBlocking { c.planStore.active(t.id)!! }
        assertEquals(2, plan.version)
        assertTrue(events(t.id).any { it.toState == "replanning" })
        assertTrue(events(t.id).count { it.toState == "recovering" } >= 2)
    }

    private fun seedInterruptedDag(ledgerStatus: String?, capability: String = "notify.owner"): String = runBlocking {
        val s = session()
        val sm = c.stateMachine
        val t = sm.create(TaskRequest(requestId = "r", source = TaskSource.CHAT, objective = "Deux étapes", createdAt = 1), s.id, "dag")
        val plan = Plan(
            planId = "p1", taskId = t.id, version = 1, objective = "Deux étapes", strategy = PlanStrategy.DAG, createdAt = 1,
            steps = listOf(
                PlanStep("s1", 1, "Prévenir", "notifier", listOf(capability), expectedOutcome = ExpectedOutcome("ok"), status = StepStatus.SUCCEEDED, resultSummary = "Notifié"),
                PlanStep("s2", 2, "Conclure", "conclure", dependencies = listOf("s1"), expectedOutcome = ExpectedOutcome("ok"), status = StepStatus.RUNNING),
            ),
        )
        c.planStore.save(plan)
        sm.transition(t.id, TaskState.PLANNING, "test", "plan")
        sm.transition(t.id, TaskState.RUNNING, "test", "run")
        c.checkpoints.save(t.id, plan, TaskState.RUNNING, "step_done:s1")
        if (ledgerStatus != null) {
            c.db.runtime().upsertLedger(IdempotencyEntity("${t.id}:k1", t.id, capability, "h", ledgerStatus, createdAt = 1, updatedAt = 1))
            c.db.tasks().insertToolCall(io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity("tc", t.id, "s1", capability, """{"message":"x","text":"x"}""", "${t.id}:k1", "{}", "ok", "ok", 1))
        }
        // The process that owned the task died.
        sm.update(t.id) { it.copy(leaseOwner = "proc-dead") }
        t.id
    }

    @Test fun crashResumeContinuesFromCheckpointWithoutRepeatingCompletedSteps() {
        val taskId = seedInterruptedDag("succeeded")
        server.enqueue(text("Étape 2 terminée."))
        server.enqueue(text("""{"status":"passed","confidence":0.9,"reason":"ok"}"""))
        server.enqueue(text("Tout est fait."))
        val report = runBlocking { c.orchestrator.recoverOnStartup() }
        assertTrue(report.single().contains("reprise automatique"))
        waitIdle()
        val t = runBlocking { c.stateMachine.get(taskId)!! }
        assertEquals("completed", t.state)
        assertEquals(3, server.requestCount) // s1 was NOT re-run: only s2, its verification and the synthesis
        val states = events(taskId).map { it.toState }
        assertTrue(states.containsAll(listOf("interrupted", "recovering", "running", "completed")))
        assertTrue(runBlocking { c.db.tasks().toolCalls(taskId) }.none { it.capability == "notify.owner" && it.id != "tc" })
    }

    @Test fun uncertainSideEffectAfterCrashWaitsForTheOwner() {
        // android.share cannot be verified after the fact (no reconcile hook) → the owner decides.
        val taskId = seedInterruptedDag("started", capability = "android.share")
        val report = runBlocking { c.orchestrator.recoverOnStartup() }
        assertTrue(report.single().contains("effet incertain"))
        assertEquals("waiting_user", runBlocking { c.stateMachine.get(taskId)!!.state })
        assertEquals(0, server.requestCount)
        val t = runBlocking { c.stateMachine.get(taskId)!! }
        assertTrue(messages(runBlocking { c.conversations.session(t.sessionId)!! }).last().text.contains("Je ne sais pas si cette action a eu lieu"))
    }

    @Test fun durableEffectRecordedBeforeCrashIsReconciledNotRepeated() {
        val taskId = seedInterruptedDag("started")
        runBlocking { c.outbox.enqueue(Outbox.KIND_NOTIFY_OWNER, buildJsonObject { put("message", "x") }, "$taskId:k1") }
        server.enqueue(text("Étape 2 terminée."))
        server.enqueue(text("""{"status":"passed","confidence":0.9,"reason":"ok"}"""))
        server.enqueue(text("Tout est fait."))
        val report = runBlocking { c.orchestrator.recoverOnStartup() }
        assertTrue(report.single().contains("reprise automatique"))
        waitIdle()
        assertEquals("reconciled", runBlocking { c.db.runtime().ledger("$taskId:k1")!!.status })
        assertEquals("completed", runBlocking { c.stateMachine.get(taskId)!!.state })
    }

    @Test fun outboxDeduplicatesRetriesWithBackoffThenGivesUp() = runBlocking {
        val delivered = mutableListOf<String>()
        var failNext = 2
        c.outbox.register("test.kind") { p ->
            if (failNext-- > 0) throw OutboxRetryException("pas encore")
            delivered += p.toString()
        }
        c.outbox.enqueue("test.kind", buildJsonObject { put("n", 1) }, "k-1")
        c.outbox.enqueue("test.kind", buildJsonObject { put("n", 1) }, "k-1") // same key → same effect
        val t0 = System.currentTimeMillis()
        assertEquals(0, c.outbox.drain(t0))
        assertEquals(0, c.outbox.drain(t0 + 1))            // backoff not elapsed
        assertEquals(0, c.outbox.drain(t0 + 31_000))       // 2nd failure
        assertEquals(1, c.outbox.drain(t0 + 10 * 60_000))  // delivered
        assertEquals(0, c.outbox.drain(t0 + 20 * 60_000))  // never twice
        assertEquals(1, delivered.size)
        assertEquals("delivered", c.outbox.status("k-1"))
        c.outbox.register("test.broken") { throw IllegalStateException("définitif") }
        c.outbox.enqueue("test.broken", buildJsonObject { }, "k-2")
        c.outbox.drain(t0 + 30 * 60_000)
        assertEquals("failed", c.outbox.status("k-2"))
    }

    @Test fun legacyTaskWithoutCheckpointFailsSafely() = runBlocking {
        val s = session()
        val t = c.stateMachine.create(TaskRequest(requestId = "r", source = TaskSource.CHAT, objective = "vieux", createdAt = 1), s.id, "interactive")
        c.stateMachine.transition(t.id, TaskState.PLANNING, "test", "x")
        c.stateMachine.update(t.id) { it.copy(leaseOwner = "proc-dead") }
        c.orchestrator.recoverOnStartup()
        val after = c.stateMachine.get(t.id)!!
        assertEquals("failed", after.state)
        assertTrue(after.terminationReason!!.startsWith("interrupted_no_checkpoint"))
    }

    @Test fun askUserWaitsAndTheAnswerResumesTheSameTask() {
        val s = session()
        server.enqueue(toolCall("ask_user", """{"question":"Pour quelle ville ?"}"""))
        runAndWait(s, "Quel temps fait-il ?")
        val t1 = lastTask()
        assertEquals("waiting_user", t1.state)
        assertEquals("Pour quelle ville ?", messages(s).last { it.role == Roles.ASSISTANT }.text)
        server.enqueue(text("Il fait beau à Lyon."))
        runAndWait(s, "Lyon")
        val t2 = lastTask()
        assertEquals(t1.id, t2.id)
        assertEquals("completed", t2.state)
        assertEquals("Il fait beau à Lyon.", messages(s).last().text)
    }

    @Test fun scopedGrantSkipsApprovalUntilRevoked() {
        val s = session()
        runBlocking {
            c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "t")
            c.memory.save("Aime le café", "preference", MemoryStatus.ACTIVE, "t")
            c.memory.save("Aime le chocolat", "preference", MemoryStatus.ACTIVE, "t")
        }
        // 1st: approve AND remember → grant created
        server.enqueue(toolCall("memory_forget", """{"query":"thé"}""")); server.enqueue(text("Oublié."))
        val approver = Thread {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                c.approvals.pending.value?.let { c.approvals.resolve(it.id, ApprovalDecision(true, rememberGrant = true)); return@Thread }
                Thread.sleep(20)
            }
        }.also { it.start() }
        runAndWait(s, "Oublie le thé"); approver.join()
        // 2nd: covered by the grant, no approval shown
        server.enqueue(toolCall("memory_forget", """{"query":"café"}""")); server.enqueue(text("Oublié aussi."))
        runAndWait(s, "Oublie le café")
        assertTrue("grant must bypass the approval prompt", runBlocking { c.db.runtime().approvalsForTask(lastTask().id) }.isEmpty())
        assertEquals("ok", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single().outcome)
        val grant = runBlocking { c.db.runtime().grantsFor("memory.forget") }.single()
        assertEquals(1, grant.uses)
        // Revoke → approval required again (refused here)
        runBlocking { c.grants.revoke(grant.grantId) }
        server.enqueue(toolCall("memory_forget", """{"query":"chocolat"}""")); server.enqueue(text("D'accord."))
        runAndWait(s, "Oublie le chocolat", approve = false)
        assertEquals("refused", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single().outcome)
        assertEquals(listOf("Aime le chocolat"), runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.map { it.text })
    }

    @Test fun volumeFastPathNeedsNoModelButStillGoesThroughPolicy() {
        val s = session()
        runAndWait(s, "Mets le volume à 30%")
        assertEquals(0, server.requestCount)
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("android.system.volume.set", call.capability)
        assertEquals("ok", call.outcome)
        assertTrue(call.policyDecisionJson.contains("\"requirement\":\"ALLOW\""))
        assertTrue(messages(s).last().text.startsWith("✓ Volume media réglé à 30 %"))
        assertEquals("completed", lastTask().state)
    }

    @Test fun underStopRemindersStillWorkButActionFastPathsDoNot() {
        val s = session()
        c.killSwitch.halt("test")
        runAndWait(s, "Rappelle-moi dans 10 minutes de sortir le chien")
        assertEquals(0, server.requestCount)
        assertEquals(1, runBlocking { c.scheduler.all() }.size)
        server.enqueue(text("STOP est actif."))
        runAndWait(s, "Mets le volume à 30%")
        assertEquals(1, server.requestCount)
        assertTrue(runBlocking { c.db.tasks().toolCalls(lastTask().id) }.isEmpty())
        c.killSwitch.resume("test")
    }

    @Test fun repeatedNonIdempotentCallInOneTaskIsSkipped() {
        val s = session()
        server.enqueue(sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"notify_owner","arguments":"{\"message\":\"coucou\"}"}},{"index":1,"id":"b","type":"function","function":{"name":"notify_owner","arguments":"{\"message\":\"coucou\"}"}}]},"finish_reason":"tool_calls"}]}""",
        ))
        server.enqueue(text("Notifié."))
        runAndWait(s, "Envoie-moi une notification coucou")
        assertEquals(listOf("ok", "skipped_duplicate"), runBlocking { c.db.tasks().toolCalls(lastTask().id) }.map { it.outcome })
        val ledger = runBlocking { c.db.runtime().ledgerForTask(lastTask().id) }.single()
        assertTrue("one durable notification", runBlocking { c.outbox.isRecorded(ledger.key) })
    }
}
