package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.model.ChatMessage
import io.github.artisanguillonrenov.cortana.core.model.ModelException
import io.github.artisanguillonrenov.cortana.core.model.ModelRequest
import io.github.artisanguillonrenov.cortana.core.model.ModelStreamEvent
import io.github.artisanguillonrenov.cortana.core.model.OpenAiCompatibleProvider
import io.github.artisanguillonrenov.cortana.core.model.ProviderQuirks
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProviderHttpTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider(quirks: ProviderQuirks = ProviderQuirks()) =
        OpenAiCompatibleProvider("p", server.url("/v1").toString(), { "sk-test-0123456789abcdef" }, quirks, client)

    @Test fun streamsTextAndSendsBearer() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Bon\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"jour\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n" +
                "data: [DONE]\n\n"
        ))
        val events = provider().chatStream(ModelRequest("m", listOf(ChatMessage("user", "salut")))).toList()
        assertEquals("Bonjour", events.filterIsInstance<ModelStreamEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.any { it is ModelStreamEvent.UsageEvent })
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test-0123456789abcdef", req.getHeader("Authorization"))
        val body = AppJson.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
    }

    @Test fun groqQuirksApplied() {
        val body = provider(ProviderQuirks(minTemperature = 1e-8, forceN1 = true)).buildBody(ModelRequest("m", emptyList(), temperature = 0.0))
        assertEquals(1e-8, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("1", body["n"]!!.jsonPrimitive.content)
    }

    @Test fun extraBodyQuirkSelectsLlamaServerLora() {
        val quirks = ProviderQuirks.parse("""{"extraBody":{"lora":[{"id":0,"scale":0.0},{"id":1,"scale":1.0}]}}""")
        val body = provider(quirks).buildBody(ModelRequest("m", emptyList()))
        val lora = body["lora"]!!.jsonArray
        assertEquals("1.0", lora[1].jsonObject["scale"]!!.jsonPrimitive.content)
        assertEquals("0.0", lora[0].jsonObject["scale"]!!.jsonPrimitive.content)
    }

    @Test fun listsModelsAndMapsAuthErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b-model","context_length":32768},{"id":"a-model"}]}"""))
        val models = provider().listModels()
        assertEquals(listOf("a-model", "b-model"), models.map { it.id })
        assertEquals(32768, models[1].contextWindow)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        try {
            provider().listModels(); fail()
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("Clé refusée"))
            assertEquals(401, e.httpCode)
        }
    }

    @Test fun nonStreamingFallbackWhenServerIgnoresStream() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = provider().chatStream(ModelRequest("m", listOf(ChatMessage("user", "x")))).toList()
        assertEquals("ok", events.filterIsInstance<ModelStreamEvent.TextDelta>().single().text)
    }

    @Test fun theLoraModuleIsSentOnlyByTheElyndorCloudPreset() {
        val presets = AppJson.parseToJsonElement(java.io.File("src/main/assets/configs/providers.json").readText()).jsonObject["presets"]!!.jsonArray.map { it.jsonObject }
        val withExtra = presets.filter { (it["quirks"] as? kotlinx.serialization.json.JsonObject)?.containsKey("extraBody") == true }.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("elyndor-cloud"), withExtra)
        // A provider at the same address but from another preset (the preconfigured pods use "custom") sends none.
        val custom = presets.single { it["id"]!!.jsonPrimitive.content == "custom" }
        val body = provider(ProviderQuirks.parse(custom["quirks"].toString())).buildBody(ModelRequest("cydonia-24b-elyndor", emptyList()))
        assertTrue(body.toString(), !body.containsKey("lora"))
    }

    @Test fun theConnectionCheckTellsWhatToDo() = runBlocking {
        fun entity(url: String, model: String? = "m1") = io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity("p", "P", "custom", url, null, null, true, 0, true, model, createdAt = 0)
        val check = io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck
        suspend fun run(r: MockResponse, model: String? = "m1", pod: Boolean = false): io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Diagnosis {
            server.enqueue(r)
            // The pod address form is recognised from the configured base URL; requests still go to the local server.
            return check.run(entity(if (pod) "https://abc123-8000.proxy.runpod.net/v1" else server.url("/v1").toString(), model), provider())
        }
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.OK, run(MockResponse().setBody("""{"data":[{"id":"m1"}]}""")).kind)
        val missing = run(MockResponse().setBody("""{"data":[{"id":"autre"}]}"""))
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.MODEL_MISSING, missing.kind); assertTrue(missing.message.contains("autre"))
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.AUTH, run(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}""")).kind)
        val gone = run(MockResponse().setResponseCode(404).setBody("not found"), pod = true)
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.ADDRESS, gone.kind); assertTrue(gone.message.contains("nouvelle adresse"))
        val stopped = run(MockResponse().setResponseCode(502).setBody("bad gateway"), pod = true)
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.POD_STOPPED, stopped.kind); assertTrue(stopped.message.contains("console RunPod"))
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.BUSY, run(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"Loading model"}}""")).kind)
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.SERVER, run(MockResponse().setResponseCode(500).setBody("boom")).kind)
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.ADDRESS, check.classify(java.net.UnknownHostException("abc.proxy.runpod.net"), pod = true).kind)
        assertEquals(io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.TIMEOUT, check.classify(java.net.SocketTimeoutException("read timed out"), pod = false).kind)
        assertTrue(check.isRunPodProxy("https://dfq6g338899rau-8080.proxy.runpod.net/v1"))
        assertTrue(!check.isRunPodProxy("https://api.openai.com/v1"))
    }

    @Test fun aKeyIsNeverSentInClearTextOverTheInternet() = runBlocking {
        val public = OpenAiCompatibleProvider("p", "http://api.example.com/v1", { "sk-secret-0123456789" }, ProviderQuirks(), client)
        val e = runCatching { public.listModels() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e is ModelException && e.message!!.contains("https://"))
        // The local network keeps plain http (MockWebServer is on loopback), with its key.
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m"}]}"""))
        assertEquals(listOf("m"), provider().listModels().map { it.id })
        assertEquals("Bearer sk-test-0123456789abcdef", server.takeRequest().getHeader("Authorization"))
    }
}
