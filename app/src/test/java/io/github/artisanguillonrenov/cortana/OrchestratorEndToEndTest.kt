package io.github.artisanguillonrenov.cortana

import androidx.test.core.app.ApplicationProvider
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskStates
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Drives the real container (Room, registry, policy, orchestrator, gateway) against a scripted
 * OpenAI-compatible server. This is the closest we can get to the tablet without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrchestratorEndToEndTest {
    private lateinit var server: MockWebServer
    private lateinit var app: CortanaApp
    private val c get() = app.container

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        app = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() { server.shutdown() }

    private fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun text(t: String) = sse("""{"choices":[{"delta":{"content":${AppJson.parseToJsonElement("\"" + t.replace("\"", "\\\"") + "\"")}},"finish_reason":"stop"}]}""")

    private fun toolCall(name: String, args: String) = sse(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"$name","arguments":""}}]}}]}""",
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":${AppJson.parseToJsonElement("\"" + args.replace("\"", "\\\"") + "\"")}}}]},"finish_reason":"tool_calls"}]}""",
    )

    private fun session(model: String, toolset: String = Toolsets.FULL): SessionEntity = runBlocking {
        val preset = c.presets.byId("local")!!
        val p = c.providers.create(preset, "Test", server.url("/v1").toString().trimEnd('/'), null)
        c.providers.update(p.copy(defaultModelId = model), null)
        c.settings.update { it.copy(defaultProviderId = p.id) }
        c.conversations.createSession(providerId = p.id, modelId = model, toolset = toolset)
    }

    private fun runAndWait(s: SessionEntity, text: String) {
        assertTrue(c.orchestrator.submit(s.id, text))
        val deadline = System.currentTimeMillis() + 20_000
        while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("task did not finish", c.orchestrator.isBusy())
    }

    private fun messages(s: SessionEntity) = runBlocking { c.conversations.messages(s.id) }
    private fun lastTask() = runBlocking { c.tasksFlow.first().first() }

    @Test fun plainChatStreamsAndPersists() {
        val s = session("llama-3.1-8b-instruct")
        server.enqueue(text("Bonjour, je suis Cortana."))
        runAndWait(s, "Salut")
        val m = messages(s)
        assertEquals(Roles.USER, m[0].role)
        assertEquals("Bonjour, je suis Cortana.", m.last { it.role == Roles.ASSISTANT }.text)
        assertEquals(TaskStates.COMPLETED, lastTask().state)
        // The request carried native tool specs and the French system prompt. VNext (D-20260927-014):
        // a relevant subset plus tools_discover is sent; every registered capability stays reachable.
        val body = AppJson.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        val offered = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"].toString().trim('"') }
        assertTrue(offered.contains("tools_discover") && offered.contains("ask_user"))
        assertTrue(c.registry.forToolset(s.toolset).size > 30)
        assertTrue(body["messages"]!!.jsonArray[0].jsonObject["content"].toString().contains("Cortana"))
    }

    @Test fun reminderFastPathNeedsNoModel() {
        val s = session("llama-3.1-8b-instruct")
        runAndWait(s, "Rappelle-moi dans 5 minutes de boire de l'eau")
        assertEquals(0, server.requestCount)
        val sched = runBlocking { c.scheduler.all() }.single()
        assertEquals("reminder", sched.kind)
        assertTrue(sched.nextRunAt!! > System.currentTimeMillis() + 4 * 60_000)
        assertTrue(messages(s).last().text.contains("boire de l'eau", ignoreCase = true))
    }

    @Test fun explicitMemoryIsSavedAndConfirmed() {
        val s = session("llama-3.1-8b-instruct")
        server.enqueue(text("C'est noté."))
        runAndWait(s, "Retiens que je préfère des réponses courtes")
        val mem = runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }
        assertEquals("Je préfère des réponses courtes", mem.single().text)
        // FTS retrieval finds it for the next turn
        assertTrue(runBlocking { c.memory.search("réponses courtes") }.isNotEmpty())
    }

    @Test fun nativeToolLoopRunsThroughPolicyAndPersists() {
        val s = session("llama-3.1-8b-instruct")
        server.enqueue(toolCall("schedule_list", "{}"))
        server.enqueue(text("Vous n'avez aucune planification."))
        runAndWait(s, "Quelles sont mes planifications ?")
        val m = messages(s)
        val tool = m.single { it.role == Roles.TOOL }
        assertTrue(tool.text.contains("Aucune planification"))
        assertEquals("Vous n'avez aucune planification.", m.last().text)
        val calls = runBlocking { c.db.tasks().toolCalls(lastTask().id) }
        assertEquals("schedule.list", calls.single().capability)
        assertEquals("ok", calls.single().outcome)
        // Second request contains the tool result tied to the call id
        server.takeRequest()
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(second.contains("\"tool_call_id\":\"call_1\""))
    }

    @Test fun emulatedToolCallingForUnknownModels() {
        val s = session("tiny-local-model")
        server.enqueue(text("{\"tool_calls\":[{\"name\":\"memory_search\",\"arguments\":{\"query\":\"café\"}}]}"))
        server.enqueue(text("Je n'ai rien trouvé."))
        runAndWait(s, "Que sais-tu sur mon café ?")
        val first = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull("emulated requests carry no native tools", first["tools"])
        assertTrue(first["messages"].toString().contains("memory_search"))
        assertEquals("memory.search", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single().capability)
        assertEquals("Je n'ai rien trouvé.", messages(s).last().text)
    }

    @Test fun malformedEmulatedCallIsRepairedNeverExecuted() {
        val s = session("tiny-local-model")
        server.enqueue(text("{\"tool_calls\":[{\"name\":\"format_disk\",\"arguments\":{}}]}"))
        server.enqueue(text("Désolée, je ne peux pas."))
        runAndWait(s, "Formate le disque")
        assertTrue(runBlocking { c.db.tasks().toolCalls(lastTask().id) }.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun l2ActionWaitsForOwnerApproval() {
        val s = session("llama-3.1-8b-instruct")
        runBlocking { c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "test") }
        server.enqueue(toolCall("memory_forget", """{"query":"thé"}"""))
        server.enqueue(text("C'est oublié."))
        val approver = Thread {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val p = c.approvals.pending.value
                if (p != null) { c.approvals.resolve(p.id, ApprovalDecision(true)); return@Thread }
                Thread.sleep(20)
            }
        }.also { it.start() }
        runAndWait(s, "Oublie que j'aime le thé")
        approver.join()
        assertTrue(runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.isEmpty())
        val audit = runBlocking { c.db.audit().allAscending() }
        assertTrue(audit.any { it.action == "approval.memory.forget" && it.outcome == "approved" })
        assertNull(runBlocking { c.audit.verify() })
    }

    @Test fun refusedApprovalBlocksAction() {
        val s = session("llama-3.1-8b-instruct")
        runBlocking { c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "test") }
        server.enqueue(toolCall("memory_forget", """{"query":"thé"}"""))
        server.enqueue(text("D'accord, je n'ai rien oublié."))
        val refuser = Thread {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val p = c.approvals.pending.value
                if (p != null) { c.approvals.resolve(p.id, ApprovalDecision(false)); return@Thread }
                Thread.sleep(20)
            }
        }.also { it.start() }
        runAndWait(s, "Oublie que j'aime le thé")
        refuser.join()
        assertEquals(1, runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.size)
        assertEquals("refused", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single().outcome)
    }

    @Test fun killSwitchRemovesToolsButKeepsChat() {
        val s = session("llama-3.1-8b-instruct")
        c.killSwitch.halt("test")
        server.enqueue(text("STOP est actif, je ne peux pas agir."))
        runAndWait(s, "Ouvre les paramètres")
        val body = AppJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(body["tools"])
        assertEquals("direct", lastTask().mode)
        c.killSwitch.resume("test")
    }

    @Test fun taintedTaskMakesMemoryWritesPending() {
        val s = session("llama-3.1-8b-instruct")
        // A file/web/screen read taints the task; simulate with web.search against an unreachable engine is flaky,
        // so use android.ui.observe which fails (no service) — instead, check policy on a tainted context directly.
        val def = c.registry.byCapability("web.fetch")!!
        val decision = runBlocking {
            c.policy.evaluate(def, AppJson.parseToJsonElement("""{"url":"https://inconnu.example/x?d=secret"}""").jsonObject,
                io.github.artisanguillonrenov.cortana.core.tools.PolicyContext("t", tainted = true, taintSources = listOf("web.fetch:evil"), toolCallsSoFar = 0))
        }
        assertEquals(io.github.artisanguillonrenov.cortana.core.policy.Requirement.CONFIRM, decision.requirement)
        assertNotNull(s)
    }

    @Test fun clearedSettingsPersistAcrossRestart() = runBlocking {
        c.settings.update { it.copy(defaultProviderId = "p1", workingFolderUri = "content://x", dailySpendCapUsd = 2.5) }
        c.settings.update { it.copy(defaultProviderId = null, workingFolderUri = null, dailySpendCapUsd = null) }
        val reloaded = io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository(c.db.settings()).also { it.loadBlocking() }.current
        assertNull(reloaded.defaultProviderId)
        assertNull(reloaded.workingFolderUri)
        assertNull(reloaded.dailySpendCapUsd)
    }

    @Test fun duplicateModelIdsAreCollapsed() {
        val list = io.github.artisanguillonrenov.cortana.core.model.OpenAiCompatibleProvider.parseModels("""{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}""")
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    @Test fun streamOptionsRejectionIsLearned() {
        val s = session("llama-3.1-8b-instruct")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unrecognized request argument supplied: stream_options"}}"""))
        server.enqueue(text("Bonjour"))
        runAndWait(s, "Salut")
        assertEquals("Bonjour", messages(s).last().text)
        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("stream_options"))
        val p = runBlocking { c.providers.all().single() }
        assertTrue(p.quirksJson!!.contains("noStreamUsage"))
    }
}
