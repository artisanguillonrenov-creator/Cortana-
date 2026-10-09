package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.model.ChatMessage
import io.github.artisanguillonrenov.cortana.core.model.ModelRoute
import io.github.artisanguillonrenov.cortana.core.model.RunPodAddress
import io.github.artisanguillonrenov.cortana.core.model.RunPodClient
import io.github.artisanguillonrenov.cortana.core.model.RunPodResolver
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathContext
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathRegistry
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.executors.runpod.RunPodTools
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** RunPod (rc12): a migrated pod is found again by its name, a stopped pod is started on the owner's request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunPodTest : CortanaTestBase() {
    private lateinit var rp: MockWebServer
    private var pods = """[]"""
    private var resume: () -> MockResponse = { MockResponse().setBody("""{"data":{"podResume":{"id":"stopped1","name":"elyndor-5090","desiredStatus":"RUNNING","costPerHr":0.69}}}""") }
    private val bodies = CopyOnWriteArrayList<String>()
    private var key: String? = "rpa_test"

    @Before fun startRunPod() {
        rp = MockWebServer()
        rp.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = request.body.readUtf8(); bodies += b
                assertEquals("Bearer rpa_test", request.getHeader("Authorization"))
                return if (b.contains("podResume")) resume() else MockResponse().setBody("""{"data":{"myself":{"pods":$pods}}}""")
            }
        }
        rp.start()
    }

    @After fun stopRunPod() = rp.shutdown()

    private fun client() = RunPodClient(OkHttpClient(), { key }, rp.url("/graphql").toString())
    private fun resolver() = RunPodResolver(c.providers, c.settings, client(), c.audit)
    private fun pod(id: String, name: String, status: String) = """{"id":"$id","name":"$name","desiredStatus":"$status","costPerHr":0.69,"machine":{"gpuDisplayName":"RTX 5090"}}"""
    private fun provider(url: String) = runBlocking { c.providers.create(c.presets.byId("custom")!!, "RunPod · elyndor-5090", url, null) }

    @Test fun addressesAreParsedAndMigratedNamesRecognised() {
        assertEquals(RunPodAddress.Parts("36w1us6m7ogo2b", 8000, "/v1"), RunPodAddress.parse("https://36w1us6m7ogo2b-8000.proxy.runpod.net/v1"))
        assertNull(RunPodAddress.parse("https://api.openai.com/v1"))
        assertEquals("elyndor-5090", RunPodAddress.baseName("elyndor-5090-migration-migration"))
    }

    @Test fun aMigratedPodGetsItsNewAddressAndOnlyThat() = runBlocking {
        val p = provider("https://oldpod1-8000.proxy.runpod.net/v1")
        val manual = c.providers.create(c.presets.byId("custom")!!, "Mon serveur", "https://llm.example.com/v1", null)
        c.settings.update { it.copy(runpodPodNames = mapOf(p.id to "elyndor-5090")) }
        pods = "[${pod("newpod9", "elyndor-5090-migration", "RUNNING")},${pod("other", "autre", "RUNNING")}]"
        val r = resolver().refresh()
        assertEquals("https://newpod9-8000.proxy.runpod.net/v1", c.providers.get(p.id)!!.baseUrl)
        assertEquals(listOf("elyndor-5090-migration"), r.changes.map { it.podName })
        assertEquals("an address that is not a RunPod one is never touched", "https://llm.example.com/v1", c.providers.get(manual.id)!!.baseUrl)
        assertTrue(c.db.audit().allAscending().any { it.action == "provider.address_updated" && it.metaJson.contains("newpod9") })
        // The pod exists now: a second check changes nothing.
        assertTrue(resolver().refresh().changes.isEmpty())
    }

    @Test fun anExistingPodIsKeptAndAmbiguityIsNeverGuessed() = runBlocking {
        val p = provider("https://keep1-8000.proxy.runpod.net/v1")
        pods = "[${pod("keep1", "elyndor-5090", "EXITED")},${pod("x2", "elyndor-5090", "RUNNING")}]"
        val r = resolver().refresh()
        assertEquals("https://keep1-8000.proxy.runpod.net/v1", c.providers.get(p.id)!!.baseUrl)
        assertEquals(listOf("keep1"), r.stopped.map { it.id })
        assertEquals("the pod name is learnt", "elyndor-5090", c.settings.current.runpodPodNames[p.id])

        val q = provider("https://gone1-8000.proxy.runpod.net/v1")
        c.settings.update { it.copy(runpodPodNames = it.runpodPodNames + (q.id to "jumeau")) }
        pods = "[${pod("a1", "jumeau", "RUNNING")},${pod("a2", "jumeau-migration", "RUNNING")}]"
        val amb = resolver().refresh(q.id)
        assertEquals("https://gone1-8000.proxy.runpod.net/v1", c.providers.get(q.id)!!.baseUrl)
        assertTrue(amb.problems.single().contains("plusieurs pods"))
    }

    @Test fun withoutAKeyNothingIsAsked() = runBlocking {
        key = null
        provider("https://oldpod1-8000.proxy.runpod.net/v1")
        assertTrue(resolver().refresh().changes.isEmpty())
        assertTrue(bodies.isEmpty())
    }

    @Test fun theGatewayRetriesOnceAtTheNewAddressAndSaysSo() = runBlocking {
        val broken = c.providers.create(c.presets.byId("custom")!!, "Pod", "http://127.0.0.1:1/v1", null)
        val moved = broken.copy(baseUrl = server.url("/v1").toString())
        c.gateway.addressRecovery = { p -> if (p.id == broken.id) moved to RunPodResolver.Change(p.id, p.displayName, p.baseUrl, moved.baseUrl, "elyndor-5090") else null }
        server.enqueue(text("Bonjour"))
        val res = c.gateway.complete(ModelRoute(broken.id, "m", "Pod", false), listOf(ChatMessage("user", "Salut")), emptyList(), onDelta = {}, allowFallback = false)
        assertNull(res.error)
        assertEquals("Bonjour", res.text)
        assertTrue(res.notice!!.contains("a changé d'adresse"))
    }

    private fun run(tools: RunPodTools, cap: String, args: String): ToolResult = runBlocking {
        val def = tools.tools().single { it.capability == cap }
        val ctx = object : ToolContext {
            override val taskId = "t-rp"; override val sessionId = "s"; override val tainted = false; override val lastUserText = ""; override val incognito = false
            override val approvedRisk = Risk.L3; override val toolset = "full"
            override fun resolveSecret(handle: String?): String? = null; override fun markUiAutomation() {}
        }
        def.invokeAuthorized(AppJson.parseToJsonElement(args) as JsonObject, ctx, PolicyDecision(cap, def.baseRisk, def.baseRisk, Requirement.ALLOW, emptyList(), false))
    }

    @Test fun startingAPodIsAPaidConfirmedActionAndExplainsAMissingGpu() {
        val tools = RunPodTools(client(), resolver(), c.settings)
        val start = tools.tools().single { it.capability == "runpod.start" }
        assertEquals(Risk.L3, start.baseRisk)
        assertTrue(Trait.FINANCIAL in start.traits)

        pods = "[${pod("stopped1", "elyndor-5090", "EXITED")},${pod("cv1", "cortana-code-vision", "RUNNING")}]"
        val ok = run(tools, "runpod.start", "{}")
        assertTrue(ok.text, ok.ok && ok.text.contains("« elyndor-5090 » démarré") && ok.text.contains("0.69"))
        assertTrue(bodies.any { it.contains("podResume") && it.contains("stopped1") })

        resume = { MockResponse().setBody("""{"errors":[{"message":"There are not enough free GPUs on the host machine to start this pod."}]}""") }
        val busy = run(tools, "runpod.start", """{"pod":"elyndor-5090"}""")
        assertFalse(busy.ok)
        assertTrue(busy.text, busy.text.contains("aucun GPU libre") && busy.text.contains("secours"))

        assertTrue(run(tools, "runpod.start", """{"pod":"inconnu"}""").text.contains("aucun pod « inconnu »"))
    }

    @Test fun startThePodIsADirectShortcutAndNotAnAppToOpen() {
        val reg = FastPathRegistry()
        val ctx = FastPathContext(System.currentTimeMillis(), ZoneId.of("Europe/Paris"), false, setOf("runpod.start", "android.app.open"))
        val m = reg.match("Démarre le pod elyndor-5090", ctx)!!
        assertEquals("runpod.start" to "elyndor-5090", m.capability to (m.args["pod"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("runpod.start", reg.match("Relance le pod", ctx)?.capability)
        assertEquals("android.app.open", reg.match("Lance Spotify", ctx)?.capability)
    }
}
