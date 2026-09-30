package io.github.artisanguillonrenov.cortana

import androidx.test.core.app.ApplicationProvider
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before

/** Shared harness: the real container against a scripted OpenAI-compatible server. */
private const val WATCHDOG_MS = 120_000L

abstract class CortanaTestBase {
    protected lateinit var server: MockWebServer
    protected lateinit var app: CortanaApp
    protected val c get() = app.container

    @Before fun baseSetUp() {
        server = MockWebServer(); server.start()
        app = ApplicationProvider.getApplicationContext()
    }

    @After fun baseTearDown() {
        watchdog?.interrupt()
        server.shutdown()
    }

    /**
     * One full run (29/09/2026) once stayed blocked in a test until the 50-minute limit, without leaving any
     * trace. A test still running after [WATCHDOG_MS] (the slowest takes ~16 s) now prints every thread's
     * stack to stderr, then again every period: a hang always leaves evidence instead of a bare timeout.
     */
    private var watchdog: Thread? = null

    @Before fun armWatchdog() {
        val owner = javaClass.simpleName
        watchdog = Thread({
            try {
                var waited = 0L
                while (true) {
                    Thread.sleep(WATCHDOG_MS); waited += WATCHDOG_MS
                    val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, st) ->
                        "\"${t.name}\" ${t.state}\n" + st.joinToString("\n") { "    at $it" }
                    }
                    System.err.println("WATCHDOG: $owner still running after ${waited / 1000} s\n$dump")
                }
            } catch (_: InterruptedException) {
            }
        }, "test-watchdog").apply { isDaemon = true; start() }
    }

    protected fun q(s: String) = JsonPrimitive(s).toString()

    protected fun sse(vararg chunks: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    protected fun text(t: String) = sse("""{"choices":[{"delta":{"content":${q(t)}},"finish_reason":"stop"}]}""")

    protected fun toolCall(name: String, args: String, id: String = "call_1") = sse(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","type":"function","function":{"name":"$name","arguments":""}}]}}]}""",
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":${q(args)}}}]},"finish_reason":"tool_calls"}]}""",
    )

    protected fun session(model: String = "llama-3.1-8b-instruct", toolset: String = Toolsets.FULL): SessionEntity = runBlocking {
        val preset = c.presets.byId("local")!!
        val p = c.providers.create(preset, "Test", server.url("/v1").toString().trimEnd('/'), null)
        c.providers.update(p.copy(defaultModelId = model), null)
        c.settings.update { it.copy(defaultProviderId = p.id) }
        c.conversations.createSession(providerId = p.id, modelId = model, toolset = toolset)
    }

    protected fun runAndWait(s: SessionEntity, text: String, approve: Boolean? = null) {
        val approver = approve?.let { ok ->
            Thread {
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    val p = c.approvals.pending.value
                    if (p != null) { c.approvals.resolve(p.id, io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision(ok)); return@Thread }
                    Thread.sleep(20)
                }
            }.also { it.start() }
        }
        check(c.orchestrator.submit(s.id, text)) { "orchestrator busy" }
        waitIdle()
        approver?.join()
    }

    protected fun waitIdle() {
        val deadline = System.currentTimeMillis() + 25_000
        while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(40)
        assertFalse("task did not finish", c.orchestrator.isBusy())
    }

    protected fun messages(s: SessionEntity) = runBlocking { c.conversations.messages(s.id) }
    protected fun lastTask(): TaskEntity = runBlocking { c.tasksFlow.first().first() }
    protected fun events(taskId: String) = runBlocking { c.db.runtime().events(taskId) }
    protected fun requestBodies(n: Int): List<String> = (1..n).map { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8() }
    protected fun planJson(steps: String) = """{"rationale":"test","steps":$steps}"""
    @Suppress("unused") protected fun json(s: String) = AppJson.parseToJsonElement(s)
}
