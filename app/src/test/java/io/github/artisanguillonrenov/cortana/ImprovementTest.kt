package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.improvement.Analyzers
import io.github.artisanguillonrenov.cortana.core.improvement.ImprovementChange
import io.github.artisanguillonrenov.cortana.core.improvement.ImprovementSnapshot
import io.github.artisanguillonrenov.cortana.core.improvement.LongPrompts
import io.github.artisanguillonrenov.cortana.core.improvement.RoutingProposals
import io.github.artisanguillonrenov.cortana.core.improvement.SettingChange
import io.github.artisanguillonrenov.cortana.core.improvement.ShortcutChange
import io.github.artisanguillonrenov.cortana.core.improvement.ToolHygiene
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.memory.ImprovementProposalEntity
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.core.memory.UsageEntity
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 27 gate: Cortana proposes improvements without silently modifying itself — analysis
 * changes nothing, only the owner applies a typed, bounded change, and every applied change rolls back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImprovementTest : CortanaTestBase() {
    private val imp get() = c.improvements
    private val now = System.currentTimeMillis()
    private val H = 3_600_000L

    private fun task(objective: String, state: String, at: Long, reason: String? = null, sessionId: String = "s-imp", tainted: Boolean = false, source: String = "chat"): TaskEntity = runBlocking {
        TaskEntity(Ids.new(), sessionId, objective, "interactive", state, tainted, "{}", at, terminationReason = reason, source = source, updatedAt = at).also { c.db.tasks().upsert(it) }
    }

    private fun call(t: TaskEntity, cap: String, args: String, outcome: String = "ok", out: String = "ok", at: Long = t.createdAt + 1) = runBlocking {
        c.db.tasks().insertToolCall(ToolCallEntity(Ids.new(), t.id, "s1", cap, args, null, "{}", outcome, out, at))
    }

    private fun proposals() = runBlocking { imp.all() }
    private fun byKind(kind: String) = proposals().filter { it.kind == kind }
    private fun change(p: ImprovementProposalEntity): ImprovementChange = imp.change(p)!!

    private fun seedUsage() = runBlocking {
        for (d in 1..6) c.db.usage().insert(UsageEntity(Ids.new(), now - d * 24 * H, "p", "m", "agent", 1000, 100, 0.10, false))
        c.db.usage().insert(UsageEntity(Ids.new(), now - 1_000, "p", "m", "agent", 1000, 100, 2.40, false))
    }

    @Test fun gateAnalysisProposesButNeverChangesAnything() {
        repeat(3) { i -> task("Organise ma semaine $i", "failed", now - (i + 1) * H, "budget_exhausted: Limite d'appels d'outils atteinte (30)") }
        repeat(3) { i -> task("Cherche mes notes sur le jardin", "completed", now - (10 + i) * H).also { call(it, "memory.search", """{"query":"jardin"}""") } }
        seedUsage()
        val before = c.settings.current

        val r = runBlocking { imp.analyze() }
        assertTrue(r.created >= 3)
        // Nothing changed: settings, tasks, no model call, only proposals (and one audit line).
        assertEquals(before, c.settings.current)
        assertEquals(6, runBlocking { c.db.tasks().taskCount() })
        assertEquals(0, server.requestCount)
        assertEquals(listOf("improvement.analyze"), runBlocking { c.db.audit().allAscending() }.map { it.action }.filter { it.startsWith("improvement.") })

        val failure = byKind("recurring_failure").single { it.fingerprint == "recurring_failure:task:budget_exhausted" }
        assertEquals(SettingChange("maxToolCallsPerTask", JsonPrimitive(45)), change(failure))
        assertEquals("failures@1" to 1, failure.analyzer to failure.version)
        assertTrue(AppJson.parseToJsonElement(failure.evidenceJson).toString().contains("\"count\":3"))
        val fp = byKind("fast_path").single()
        assertEquals(ShortcutChange("Cherche mes notes sur le jardin", "memory.search", """{"query":"jardin"}"""), change(fp))
        val cost = byKind("cost_anomaly").single()
        assertEquals(SettingChange("dailySpendCapUsd", JsonPrimitive(1.0)), change(cost))

        // Same evidence → same version; new evidence → a new version, never a duplicate.
        runBlocking { imp.analyze() }
        assertEquals(1, byKind("recurring_failure").single { it.fingerprint == failure.fingerprint }.version)
        task("Organise ma semaine 4", "failed", now - 30 * 60_000, "budget_exhausted: Limite d'appels d'outils atteinte (30)")
        runBlocking { imp.analyze() }
        val v2 = byKind("recurring_failure").single { it.fingerprint == failure.fingerprint }
        assertEquals(2 to failure.proposalId, v2.version to v2.proposalId)
        assertEquals(before, c.settings.current)
        // The model sees proposals read-only; there is no capability to apply them.
        assertNotNull(c.registry.byCapability("improvement.list"))
        assertTrue(c.registry.all().none { it.capability.startsWith("improvement.") && it.capability != "improvement.list" })
    }

    @Test fun ownerAppliesAndRollsBackBoundedSettingChanges() {
        repeat(3) { i -> task("Organise ma semaine $i", "failed", now - (i + 1) * H, "budget_exhausted: Limite d'appels d'outils atteinte (30)") }
        seedUsage()
        runBlocking { imp.analyze() }
        val failure = byKind("recurring_failure").single()
        assertEquals("Réglage « appels d'outils par tâche » → 45", runBlocking { imp.apply(failure.proposalId) })
        assertEquals(45, c.settings.current.maxToolCallsPerTask)
        val applied = runBlocking { c.db.improvements().get(failure.proposalId) }!!
        assertEquals("applied" to "owner", applied.status to applied.decidedBy)
        assertTrue(applied.previousJson!!.contains("30"))
        assertTrue(runCatching { runBlocking { imp.apply(failure.proposalId) } }.isFailure) // once
        runBlocking { imp.rollback(failure.proposalId) }
        assertEquals(30, c.settings.current.maxToolCallsPerTask)
        assertEquals("rolled_back", runBlocking { c.db.improvements().get(failure.proposalId) }!!.status)

        // A value the owner changed again after applying is never overwritten by a rollback.
        val cost = byKind("cost_anomaly").single()
        runBlocking { imp.apply(cost.proposalId) }
        assertEquals(1.0, c.settings.current.dailySpendCapUsd)
        runBlocking { c.settings.update { it.copy(dailySpendCapUsd = 5.0) } }
        val refused = runCatching { runBlocking { imp.rollback(cost.proposalId) } }.exceptionOrNull()
        assertTrue(refused?.message.orEmpty().contains("modifié depuis"))
        assertEquals(5.0, c.settings.current.dailySpendCapUsd)

        // Security settings and out-of-range values are outside what an improvement may touch.
        fun inject(change: String): String = runBlocking {
            val p = ImprovementProposalEntity(Ids.new(), "test:${Ids.new()}", "routing", "t", "r", "{}", change, "open", 1, "test@1", now, now)
            c.db.improvements().upsert(p); p.proposalId
        }
        val sec = inject("""{"type":"setting","key":"knownDestinations","value":"evil.example"}""")
        assertTrue(runCatching { runBlocking { imp.apply(sec) } }.exceptionOrNull()!!.message!!.contains("non modifiable"))
        val big = inject("""{"type":"setting","key":"maxToolCallsPerTask","value":5000}""")
        assertTrue(runCatching { runBlocking { imp.apply(big) } }.isFailure)
        assertFalse(c.settings.current.knownDestinations.contains("evil.example"))
        assertEquals(30, c.settings.current.maxToolCallsPerTask)
        assertTrue(runBlocking { c.db.audit().allAscending() }.map { it.action }.containsAll(listOf("improvement.apply", "improvement.rollback")))

    }

    @Test fun approvedShortcutAnswersWithoutTheModelAndRollsBack() {
        val s = session()
        runBlocking { c.memory.save("Le jardin a besoin d'eau le mardi", MemoryTypes.SEMANTIC, MemoryStatus.ACTIVE, "explicit") }
        repeat(3) { i -> task("Cherche mes notes sur le jardin", "completed", now - (10 + i) * H).also { call(it, "memory.search", """{"query":"jardin"}""") } }
        runBlocking { imp.analyze() }
        val fp = byKind("fast_path").single()
        runBlocking { imp.apply(fp.proposalId) }
        assertEquals(1, c.settings.current.ownerShortcuts.size)

        runAndWait(s, "Cherche mes notes sur le jardin !")
        assertEquals(0, server.requestCount) // no model call
        val t = lastTask()
        assertEquals("fast_path" to "completed", t.mode to t.state)
        assertEquals("memory.search", runBlocking { c.db.tasks().toolCalls(t.id) }.single().capability) // through the dispatcher
        assertTrue(messages(s).last().text.contains("besoin d'eau"))

        runBlocking { imp.rollback(fp.proposalId) }
        assertTrue(c.settings.current.ownerShortcuts.isEmpty())
        server.enqueue(text("Voici vos notes."))
        runAndWait(s, "Cherche mes notes sur le jardin")
        assertEquals(1, server.requestCount)

        // A shortcut can never bind an action with an external effect.
        val bad = runBlocking {
            ImprovementProposalEntity(Ids.new(), "fast_path:x", "fast_path", "t", "r", "{}",
                """{"type":"shortcut","phrase":"préviens","capability":"notify.owner","args":"{\"message\":\"x\"}"}""", "open", 1, "test@1", now, now).also { c.db.improvements().upsert(it) }
        }
        assertTrue(runCatching { runBlocking { imp.apply(bad.proposalId) } }.exceptionOrNull()!!.message!!.contains("raccourci"))
    }

    @Test fun failThenSuccessBecomesARegressionCaseThatDetectsRegressions() {
        val a = task("Résume mes notes du jardin", "failed", now - 5 * H, "step_failed: introuvable")
        val b = task("Résume mes notes du jardin", "completed", now - 4 * H)
        call(b, "memory.search", """{"query":"jardin"}""")
        runBlocking { imp.analyze() }
        val ev = byKind("eval_case").single()
        assertTrue(ev.evidenceJson.contains(a.id) && ev.evidenceJson.contains(b.id))
        runBlocking { imp.apply(ev.proposalId) }
        val case = runBlocking { imp.evalCases() }.single()
        assertEquals(listOf("memory.search"), Analyzers.expected(case))

        // Later, the same request completes by another path: flagged as a regression.
        val later = task("Résume mes notes du jardin", "completed", System.currentTimeMillis() + 1_000)
        call(later, "web.search", """{"query":"jardin"}""")
        runBlocking { imp.analyze() }
        val reg = byKind("regression").single()
        assertEquals("regression:regress:case:${case.caseId}", reg.fingerprint)
        assertNull(reg.changeJson) // an observation: the fix is the owner's (or the Software Factory's)

        runBlocking { imp.rollback(ev.proposalId) }
        assertTrue(runBlocking { imp.evalCases() }.isEmpty())
    }

    @Test fun skillProposalsActivateAndRollBack() {
        val id = Ids.new()
        runBlocking { c.db.skills().upsert(SkillEntity(id, "Arroser", 1, "validated", false, "a>b", successCount = 2, createdAt = now, updatedAt = now)) }
        val failing = Ids.new()
        runBlocking { c.db.skills().upsert(SkillEntity(failing, "Photos", 1, "active", true, "c>d", successCount = 1, failureCount = 3, consecutiveFailures = 3, confidence = 0.25, createdAt = now, updatedAt = now)) }
        runBlocking { imp.analyze() }
        val on = byKind("skill").single()
        val off = byKind("skill_confidence").single()
        runBlocking { imp.apply(on.proposalId); imp.apply(off.proposalId) }
        assertTrue(runBlocking { c.skills.get(id) }!!.enabled)
        assertFalse(runBlocking { c.skills.get(failing) }!!.enabled)
        runBlocking { imp.rollback(on.proposalId); imp.rollback(off.proposalId) }
        assertFalse(runBlocking { c.skills.get(id) }!!.enabled)
        assertTrue(runBlocking { c.skills.get(failing) }!!.enabled)
    }

    @Test fun obsoleteAndRejectedFindingsAreNotReproposed() {
        val ts = (0 until 3).map { i -> task("Organise ma semaine $i", "failed", now - (i + 1) * H, "budget_exhausted: Limite d'appels d'outils atteinte (30)") }
        runBlocking { imp.analyze() }
        val p = byKind("recurring_failure").single()
        runBlocking { imp.reject(p.proposalId) }
        runBlocking { imp.analyze() }
        assertEquals("rejected", byKind("recurring_failure").single().status)
        // Evidence gone (tasks older than the window) → an open proposal becomes obsolete.
        runBlocking { c.db.tasks().upsert(ts[0].copy(createdAt = now - 20 * 24 * H)) }
        repeat(3) { i -> task("Photos $i", "failed", now - (i + 1) * H, "plan_blocked: aucune capacité") }
        runBlocking { imp.analyze() }
        val blocked = byKind("recurring_failure").single { it.fingerprint.endsWith("plan_blocked") }
        runBlocking { c.db.tasks().since(0).filter { it.objective.startsWith("Photos") }.forEach { c.db.tasks().upsert(it.copy(createdAt = now - 20 * 24 * H)) } }
        runBlocking { imp.analyze() }
        assertEquals("obsolete", runBlocking { c.db.improvements().get(blocked.proposalId) }!!.status)
    }

    // ---------------------------------------------------------------- pure analyzers

    private fun snap(
        tasks: List<TaskEntity> = emptyList(), calls: List<ToolCallEntity> = emptyList(), usage: List<UsageEntity> = emptyList(),
        tools: List<ToolDefinition> = emptyList(), settings: AppSettings = AppSettings(), sessions: Map<String, String?> = emptyMap(),
        providers: Map<String, String> = emptyMap(), windows: Map<String, Int> = emptyMap(), skills: List<SkillEntity> = emptyList(),
    ) = ImprovementSnapshot(now, tasks, calls.groupBy { it.taskId }, usage, tools, skills, emptyList(), settings, sessions, providers, windows)

    private fun t(state: String, session: String, reason: String? = null) = TaskEntity(Ids.new(), session, "x", "interactive", state, false, "{}", now - H, terminationReason = reason)

    @Test fun routingProposesAProviderThatWorks() {
        val tasks = List(4) { t("failed", "sa", "provider.error: 503") } + List(2) { t("completed", "sa") } + List(3) { t("completed", "sb") }
        val drafts = RoutingProposals.analyze(snap(tasks, settings = AppSettings(defaultProviderId = "A"), sessions = mapOf("sa" to "A", "sb" to "B"), providers = mapOf("A" to "Infermatic", "B" to "Groq")))
        assertEquals(SettingChange("defaultProviderId", JsonPrimitive("B")), drafts.single().change)
        // Without a proven alternative: an observation only.
        val alone = RoutingProposals.analyze(snap(tasks.filter { it.sessionId == "sa" }, settings = AppSettings(defaultProviderId = "A"), sessions = mapOf("sa" to "A"), providers = mapOf("A" to "Infermatic")))
        assertNull(alone.single().change)
    }

    @Test fun longPromptsAndDuplicateToolsAreDetected() {
        val usage = List(12) { UsageEntity(Ids.new(), now - H, "p", "m", "agent", 7000, 50, null, false) }
        val d = LongPrompts.analyze(snap(usage = usage, windows = mapOf("p/m" to 8192))).single()
        assertEquals(SettingChange("maxToolsOffered", JsonPrimitive(16)), d.change)
        assertTrue(LongPrompts.analyze(snap(usage = usage, windows = mapOf("p/m" to 128_000))).isEmpty())

        fun def(cap: String, desc: String) = ToolDefinition(cap, desc, S.obj(), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE) { _, _ -> ToolResult.ok("") }
        val tools = listOf(
            def("files.read", "Lit le contenu complet d'un fichier texte du dossier de travail"),
            def("mcp.disk.read", "Lit le contenu complet d'un fichier texte du dossier de travail"),
            def("web.search", "Recherche sur le web avec le moteur configuré"),
        )
        val dup = ToolHygiene.analyze(snap(tools = tools)).single { it.kind == "duplicate_tools" }
        assertEquals("dup:files.read|mcp.disk.read", dup.key)
        assertNull(dup.change)
    }
}
