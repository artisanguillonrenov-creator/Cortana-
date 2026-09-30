package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.SpecialistResult
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.worker.WorkerServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** VNext phase 23: specialists run by the one orchestrator — limited tools, isolated context, parallel read-only steps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpecialistsTest : CortanaTestBase() {
    private lateinit var worker: WorkerServer
    private lateinit var workerDir: File

    @Before fun startWorker() {
        workerDir = Files.createTempDirectory("cortana-worker").toFile()
        worker = WorkerServer(workerDir, overridePort = 0).start()
    }

    @After fun stopWorker() { worker.stop(); workerDir.deleteRecursively() }

    private fun jsProject(): WorkspaceEntity = runBlocking {
        val dir = SampleProjects.jsCalc(File(app.cacheDir, "fixture-spec"))
        val w = c.workspaces.importDocument(DocumentFile.fromFile(dir), "calc-js", "imported:test")
        c.repoIntelligence.inspectAndStore(w)
        c.workspaces.get(w.workspaceId)!!
    }

    private fun runWithOwner(s: SessionEntity, text: String, timeoutMs: Long = 180_000) {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val approver = Thread {
            while (running.get()) {
                c.approvals.pending.value?.let { p -> c.approvals.resolve(p.id, ApprovalDecision(true)) }
                Thread.sleep(20)
            }
        }.also { it.start() }
        try {
            check(c.orchestrator.submit(s.id, text))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertFalse("task did not finish", c.orchestrator.isBusy())
        } finally { running.set(false); approver.join() }
    }

    // ---------------------------------------------------------------- gate

    @Test fun codingTaskUsesAnalystImplementerAndReviewerUnderTheOneOrchestrator() {
        assumeTrue("user namespaces unavailable", worker.sandbox.isolatedAvailable)
        runBlocking { c.workers.pair(worker.pairingString("127.0.0.1")) }
        val w = jsProject()
        runBlocking { c.git.init(w); c.git.commit(w, "État initial"); c.memory.save("Le code du portail est 4521", "fact", MemoryStatus.ACTIVE, "explicit") }
        val s = session()
        runBlocking { c.conversations.addMessage(s.id, "user", "Ma voisine s'appelle Josiane.") }
        server.enqueue(text(planJson("""[
            {"id":"s1","title":"Analyser","objective":"Localiser le bug de add","capabilities":["workspace.inspect","code.search"],"specialist":"code_analyst","checks":[{"type":"tool_succeeded","target":"code.search"}]},
            {"id":"s2","title":"Corriger","objective":"Branche dédiée, patch minimal, tests","capabilities":["repo.branch.create","code.patch.apply","test.run"],"depends_on":["s1"],"specialist":"implementer","checks":[{"type":"tool_succeeded","target":"code.patch.apply"}]},
            {"id":"s3","title":"Relire","objective":"Revue du diff et des tests","capabilities":["review.changes"],"depends_on":["s2"],"specialist":"reviewer","checks":[{"type":"tool_succeeded","target":"review.changes"}]}]""")))
        // Analyst: reads, and its attempt to write is refused (not in its role).
        server.enqueue(toolCall("workspace_inspect", """{"workspace":"calc-js"}""", id = "a1"))
        server.enqueue(toolCall("code_patch_apply", """{"workspace":"calc-js","operations":[{"op":"replace","path":"src/calc.js","find":"add: (a, b) => a - b","replace":"add: (a, b) => a * b"}]}""", id = "a2"))
        server.enqueue(toolCall("code_search", """{"workspace":"calc-js","query":"add:"}""", id = "a3"))
        server.enqueue(text("Bug : add soustrait (src/calc.js:2)."))
        // Implementer.
        server.enqueue(toolCall("repo_branch_create", """{"workspace":"calc-js","name":"cortana/fix-add","checkout":true}""", id = "b1"))
        server.enqueue(toolCall("code_patch_apply", """{"workspace":"calc-js","operations":[{"op":"replace","path":"src/calc.js","find":"add: (a, b) => a - b","replace":"add: (a, b) => a + b"}]}""", id = "b2"))
        server.enqueue(toolCall("test_run", """{"workspace":"calc-js"}""", id = "b3"))
        server.enqueue(text("Corrigé, suite verte."))
        // Reviewer.
        server.enqueue(toolCall("review_changes", """{"workspace":"calc-js"}""", id = "c1"))
        server.enqueue(text("Revue : approuvé."))
        server.enqueue(text("Compte rendu : add corrigé et relu, rien poussé."))
        runWithOwner(s, "Dans le projet calc-js, corrige le bug de la fonction add, lance les tests puis fais relire le diff.")

        val t = lastTask()
        assertEquals(t.terminationReason, "completed", t.state)
        val plan = runBlocking { c.planStore.active(t.id)!! }
        assertEquals(listOf("code_analyst", "implementer", "reviewer"), plan.steps.map { it.specialist })
        assertTrue(plan.steps.all { it.status == StepStatus.SUCCEEDED })
        val cs = runBlocking { c.db.tasks().toolCalls(t.id) }
        // The analyst's write attempt never reached a tool: refused as outside its role (and no discovery tool to escape it).
        assertEquals(listOf("workspace.inspect", "code.search", "repo.branch.create", "code.patch.apply", "test.run", "review.changes"), cs.map { it.capability })
        assertTrue(cs.all { it.outcome == "ok" })
        assertTrue(messages(s).any { it.text.contains("Outil inconnu ou indisponible pour cette étape : code_patch_apply") })
        assertTrue(File(w.rootPath, "src/calc.js").readText().contains("add: (a, b) => a + b"))

        // One orchestrator: one task, no child task; specialists only add same-state records.
        assertEquals(1, runBlocking { c.tasksFlow.first() }.size)
        val ev = events(t.id)
        val spec = ev.filter { it.actor.startsWith("specialist:") }
        assertEquals(6, spec.size)
        assertTrue(spec.all { it.fromState == it.toState })
        assertTrue(ev.filter { it.fromState != it.toState }.none { it.actor.startsWith("specialist") })
        val results = spec.filter { it.reason.contains("résultat") }.map { AppJson.decodeFromString(SpecialistResult.serializer(), it.reason.substringAfter(" | ")) }
        assertEquals(listOf("code_analyst", "implementer", "reviewer"), results.map { it.profileId })
        assertTrue(results.all { it.success })

        // Isolated context: the analyst saw its role and its step, not the memory nor the conversation.
        val bodies = (1..server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS)?.body?.readUtf8() }
        val analyst = bodies.filter { it.contains("analyste de code de Cortana") }
        assertTrue(analyst.isNotEmpty())
        assertTrue(analyst.none { it.contains("4521") || it.contains("Josiane") || it.contains("fais relire") })
        assertTrue("the analyst was offered no writing tool", analyst.first().let { !it.contains("\"code_patch_apply\"") && it.contains("\"code_search\"") })
        val reviewer = bodies.first { it.contains("relecteur de Cortana") }
        assertTrue("the reviewer sees what it depends on", reviewer.contains("Corrigé, suite verte."))
    }

    // ---------------------------------------------------------------- parallelism and cancellation

    private fun scripted(onSpecialist: () -> Unit, plan: String): CopyOnWriteArrayList<String> {
        val bodies = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = request.body.readUtf8(); bodies += b
                return when {
                    b.contains("planificateur de Cortana") -> text(planJson(plan))
                    b.contains("analyste de code de Cortana") || b.contains("relecteur sécurité de Cortana") -> { onSpecialist(); text("Analyse faite.") }
                    else -> text("Synthèse : les deux analyses sont faites.")
                }
            }
        }
        return bodies
    }

    private val parallelPlan = """[
        {"id":"s1","title":"Structure","objective":"Décrire la structure du projet","specialist":"code_analyst","parallel":true},
        {"id":"s2","title":"Sécurité","objective":"Chercher des secrets","specialist":"security_reviewer","parallel":true},
        {"id":"s3","title":"Synthèse","objective":"Rassembler les deux analyses","depends_on":["s1","s2"]}]"""

    @Test fun independentReadOnlyStepsRunInParallelAndMergeInTheOrchestrator() {
        jsProject()
        runBlocking { c.settings.update { it.copy(modelVerification = false) } }
        val latch = CountDownLatch(2)
        val together = CopyOnWriteArrayList<Boolean>()
        scripted({ latch.countDown(); together += latch.await(10, TimeUnit.SECONDS) }, parallelPlan)
        val s = session()
        runWithOwner(s, "Analyse le projet calc-js : décris la structure du code puis fais une revue de sécurité du dépôt.")
        val t = lastTask()
        assertEquals(t.terminationReason, "completed", t.state)
        assertEquals("both specialists were in flight at the same time", listOf(true, true), together.toList())
        assertTrue(messages(s).any { it.text.startsWith("⇉ 2 étapes indépendantes en parallèle") })
        val plan = runBlocking { c.planStore.active(t.id)!! }
        assertTrue(plan.steps.all { it.status == StepStatus.SUCCEEDED })
        assertEquals(4, events(t.id).count { it.actor.startsWith("specialist:") })
    }

    @Test fun cancellingTheTaskCancelsParallelSpecialists() {
        jsProject()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        scripted({ entered.countDown(); release.await(20, TimeUnit.SECONDS) }, parallelPlan)
        val s = session()
        check(c.orchestrator.submit(s.id, "Analyse le projet calc-js : décris la structure du code puis fais une revue de sécurité du dépôt."))
        assertTrue("both specialists started", entered.await(15, TimeUnit.SECONDS))
        val t0 = System.currentTimeMillis()
        c.orchestrator.cancel()
        while (c.orchestrator.isBusy() && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(30)
        release.countDown()
        assertFalse(c.orchestrator.isBusy())
        assertTrue("cancelled promptly", System.currentTimeMillis() - t0 < 5_000)
        assertEquals("cancelled", lastTask().state)
    }
}
