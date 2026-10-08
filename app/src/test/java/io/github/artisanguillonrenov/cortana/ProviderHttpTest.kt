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
}
