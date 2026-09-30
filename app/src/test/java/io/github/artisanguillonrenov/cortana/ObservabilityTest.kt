package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.memory.SpanEntity
import io.github.artisanguillonrenov.cortana.core.observability.ObservabilityService
import io.github.artisanguillonrenov.cortana.core.observability.Otlp
import io.github.artisanguillonrenov.cortana.core.observability.Tracer
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Phase 28 gate: a task shows its model, tool and verifier spans, linked in one tree, without any
 * secret — in the local store, the viewer, the metrics and the optional OTLP export.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservabilityTest : CortanaTestBase() {
    private val secret = "sk-live-CORTANA-7f3a9c2e1b"

    private fun runDagTask(): String {
        Redactor.register(secret)
        val s = session()
        server.enqueue(text(planJson("""[
            {"id":"s1","title":"Lister","objective":"Lister les planifications","capabilities":["schedule.list"],"checks":[{"type":"tool_succeeded","target":"schedule.list"}]},
            {"id":"s2","title":"Chercher","objective":"Chercher en mémoire","capabilities":["memory.search"],"depends_on":["s1"]}]""")))
        server.enqueue(toolCall("schedule_list", "{}"))
        server.enqueue(text("Aucune planification trouvée."))
        server.enqueue(toolCall("memory_search", """{"query":"$secret"}""", id = "call_2"))
        server.enqueue(text("Rien en mémoire."))
        server.enqueue(text("""{"status":"passed","confidence":0.8,"reason":"recherche effectuée"}"""))
        server.enqueue(text("Compte rendu final."))
        runAndWait(s, "Liste mes planifications, puis cherche la clé $secret en mémoire, ensuite fais-moi un résumé.")
        val t = lastTask()
        assertEquals("completed", t.state)
        return t.id
    }

    private fun allSpanRows(): List<SpanEntity> = runBlocking { c.spanStore.flush(); c.db.spans().since(0) }

    @Test fun gateTaskShowsModelToolAndVerifierSpansWithoutSecrets() {
        val taskId = runDagTask()
        val tree = runBlocking { c.observability.trace(taskId) }
        val root = tree.single()
        assertEquals("task", root.span.name)
        assertEquals("completed", root.span.attributes["cortana.task.state"])
        val nodes = root.flatten()
        val names = nodes.map { it.span.name }
        assertTrue(names.toString(), names.containsAll(listOf("task.plan", "task.step", "model.chat", "gen_ai.chat", "tool.execute", "task.verify", "task.synthesize")))
        // One tree: every span of the task hangs (transitively) under the root, in one trace.
        assertEquals(1, nodes.map { it.span.traceId }.distinct().size)
        assertTrue(nodes.drop(1).all { it.depth >= 1 })
        // Nesting follows the work: the tool runs inside its step, the model call inside the agent turn.
        val step1 = nodes.first { it.span.name == "task.step" && it.span.attributes["step"] == "s1" }
        assertTrue(step1.flatten().any { it.span.name == "tool.execute" && it.span.attributes["tool"] == "schedule.list" })
        val turn = step1.flatten().first { it.span.name == "model.chat" }
        assertEquals("gen_ai.chat", turn.children.single().span.name)
        val chat = nodes.first { it.span.name == "gen_ai.chat" }
        assertEquals("llama-3.1-8b-instruct", chat.span.attributes["gen_ai.request.model"])
        assertEquals("chat", chat.span.attributes["gen_ai.operation.name"])
        assertTrue(nodes.first { it.span.name == "task.plan" }.flatten().any { it.span.name == "gen_ai.chat" && it.span.attributes["cortana.role"] == "planner" })
        assertTrue(nodes.first { it.span.name == "task.verify" && it.span.attributes["step"] == "s2" }.flatten().any { it.span.name == "gen_ai.chat" })

        // No secret anywhere: stored rows, viewer, tool output. Attributes are content-free.
        val rows = allSpanRows()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.none { it.attributesJson.contains(secret) || it.name.contains(secret) || (it.errorType ?: "").contains(secret) })
        assertTrue(rows.none { r -> AppJson.parseToJsonElement(r.attributesJson).jsonObject.keys.any(Tracer::isContentKey) })
        val trace = runBlocking { c.registry.byCapability("observability.trace")!!.invokeAuthorized(kotlinx.serialization.json.buildJsonObject { put("task_id", kotlinx.serialization.json.JsonPrimitive(taskId.take(8))) }, ctx(), decision("observability.trace")) }
        assertTrue(trace.ok && trace.text.contains("tool.execute") && !trace.text.contains(secret))

        // Metrics from the same data.
        val m = runBlocking { c.observability.metrics() }
        assertEquals(1, m.tasksByState["completed"])
        assertEquals(2, m.tools.values.sumOf { it.count })
        assertEquals(7, m.models.sumOf { it.stat.count })
        assertEquals(2, m.verification.count)
        assertTrue(m.text().contains("schedule.list"))
    }

    @Test fun spanAttributesAreRedactedAndContentKeysDropped() = runBlocking {
        Redactor.register(secret)
        c.tracer.span("custom", "t-redact", mapOf("cortana.note" to "clé=$secret", "gen_ai.prompt" to "le texte du propriétaire", "tool.arguments" to "{}", "gen_ai.usage.input_tokens" to "12")) {}
        val r = allSpanRows().single { it.name == "custom" }
        assertFalse(r.attributesJson.contains(secret))
        assertTrue(r.attributesJson.contains(Redactor.MASK))
        val keys = AppJson.parseToJsonElement(r.attributesJson).jsonObject.keys
        assertEquals(setOf("cortana.note", "gen_ai.usage.input_tokens"), keys)
        assertEquals(Tracer.rootSpanId("t-redact"), r.parentSpanId)
    }

    @Test fun otlpExportSendsValidJsonOnceWithoutSecretsAndRetriesOnFailure() {
        val taskId = runDagTask()
        val collector = MockWebServer().apply { start() }
        try {
            val h = "secret:otlp-test"
            c.observability.headerOf = { if (it == h) "X-Api-Key: collector-key-1" else null }
            runBlocking { c.settings.update { it.copy(otlpEnabled = true, otlpEndpoint = collector.url("/").toString(), otlpHeaderHandle = h) } }
            collector.enqueue(MockResponse().setResponseCode(503))
            assertEquals(0, runBlocking { c.observability.export() })
            assertTrue(c.observability.lastExport!!.contains("503"))
            collector.takeRequest(5, TimeUnit.SECONDS)
            collector.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val sent = runBlocking { c.observability.export() }
            assertEquals(allSpanRows().size, sent)
            val req = collector.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/traces", req.path)
            assertEquals("collector-key-1", req.getHeader("X-Api-Key"))
            val body = req.body.readUtf8()
            assertFalse(body.contains(secret))
            val rs = AppJson.parseToJsonElement(body).jsonObject["resourceSpans"]!!.jsonArray.single().jsonObject
            val res = rs["resource"]!!.jsonObject["attributes"]!!.jsonArray.associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content }
            assertEquals("cortana", res["service.name"])
            val spans = rs["scopeSpans"]!!.jsonArray.single().jsonObject["spans"]!!.jsonArray.map { it.jsonObject }
            assertTrue(spans.all { it["traceId"]!!.jsonPrimitive.content.matches(Regex("[0-9a-f]{32}")) && it["spanId"]!!.jsonPrimitive.content.matches(Regex("[0-9a-f]{16}")) })
            val ids = spans.map { it["spanId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(spans.filter { it["parentSpanId"] != null }.all { it["parentSpanId"]!!.jsonPrimitive.content in ids }) // the tree survives the id encoding
            assertTrue(spans.all { it["startTimeUnixNano"]!!.jsonPrimitive.content.toLong() <= it["endTimeUnixNano"]!!.jsonPrimitive.content.toLong() })
            assertTrue(spans.any { s -> s["attributes"]!!.jsonArray.any { it.jsonObject["key"]!!.jsonPrimitive.content == "cortana.task.id" && it.jsonObject["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content == taskId } })
            // Exported once: nothing left to send.
            assertEquals(0, runBlocking { c.observability.export() })
            assertEquals(2, collector.requestCount)
        } finally { collector.shutdown() }
    }

    @Test fun exportIsOffByDefaultAndRefusesCleartextToTheInternet() {
        runDagTask()
        assertEquals(0, runBlocking { c.observability.export() }) // disabled by default: nothing leaves the tablet
        assertTrue(runCatching { ObservabilityService.checkEndpoint("http://collector.example.com:4318") }.isFailure)
        assertTrue(runCatching { ObservabilityService.checkEndpoint("https://user:pw@collector.example.com") }.isFailure)
        ObservabilityService.checkEndpoint("https://collector.example.com:4318")
        ObservabilityService.checkEndpoint("http://192.168.1.20:4318")
        ObservabilityService.checkEndpoint("http://localhost:4318")
        assertEquals(32, Otlp.hexId("trace-" + java.util.UUID.randomUUID(), 32).length)
    }

    @Test fun retentionKeepsTheConfiguredWindowAndSize() = runBlocking {
        val old = System.currentTimeMillis() - 10 * 86_400_000L
        c.db.spans().insertAll((1..5).map { SpanEntity("old$it", "t", null, "x", null, old, old + 1, "ok", null, "{}") })
        c.db.spans().insertAll((1..5).map { SpanEntity("new$it", "t", null, "x", null, System.currentTimeMillis() + it, System.currentTimeMillis() + it, "ok", null, "{}") })
        assertEquals(5, c.observability.retention(days = 7))
        assertEquals(2, c.observability.retention(days = 7, maxRows = 3))
        assertEquals(listOf("new3", "new4", "new5"), c.db.spans().since(0).map { it.spanId })
    }

    private fun ctx() = object : io.github.artisanguillonrenov.cortana.core.tools.ToolContext {
        override val taskId = "t-obs"; override val sessionId = "s"; override val tainted = false; override val lastUserText = ""; override val incognito = false
        override val approvedRisk = io.github.artisanguillonrenov.cortana.core.policy.Risk.L0; override val toolset = "full"
        override fun resolveSecret(handle: String?): String? = null; override fun markUiAutomation() {}
    }

    private fun decision(cap: String) = io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision(
        cap, io.github.artisanguillonrenov.cortana.core.policy.Risk.L0, io.github.artisanguillonrenov.cortana.core.policy.Risk.L0,
        io.github.artisanguillonrenov.cortana.core.policy.Requirement.ALLOW, emptyList(), false,
    )
}
