package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.model.ChatMessage
import io.github.artisanguillonrenov.cortana.core.model.ModelStreamEvent
import io.github.artisanguillonrenov.cortana.core.model.SseParser
import io.github.artisanguillonrenov.cortana.core.model.ToolCall
import io.github.artisanguillonrenov.cortana.core.model.ToolCallAccumulator
import io.github.artisanguillonrenov.cortana.core.model.ToolCallEmulation
import io.github.artisanguillonrenov.cortana.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseAndEmulationTest {
    @Test fun parsesTextDeltaAndDone() {
        val ev = SseParser.parseLine("""data: {"choices":[{"index":0,"delta":{"content":"Bonjour"}}]}""")!!
        assertEquals(listOf(ModelStreamEvent.TextDelta("Bonjour")), ev)
        assertEquals(listOf(ModelStreamEvent.Done(null)), SseParser.parseLine("data: [DONE]"))
        assertNull(SseParser.parseLine(": keep-alive"))
    }

    @Test fun accumulatesToolCallFragments() {
        val acc = ToolCallAccumulator()
        listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_fetch","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"url\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"https://a.fr\"}"}}]}}]}""",
        ).forEach { line -> SseParser.parseLine(line)!!.filterIsInstance<ModelStreamEvent.ToolCallDelta>().forEach(acc::add) }
        assertEquals(listOf(ToolCall("call_1", "web_fetch", """{"url":"https://a.fr"}""")), acc.build())
    }

    @Test fun parsesUsageCostAndErrors() {
        val u = SseParser.parseLine("""data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"cost":0.001}}""")!!
        assertEquals(ModelStreamEvent.UsageEvent(io.github.artisanguillonrenov.cortana.core.model.Usage(10, 5, 0.001)), u.single())
        val e = SseParser.parseLine("""data: {"error":{"message":"rate limited","code":429}}""")!!.single() as ModelStreamEvent.Error
        assertTrue(e.retryable)
    }

    @Test fun parsesNonStreamingCompletion() {
        val r = SseParser.parseCompletion("""{"choices":[{"message":{"content":"ok","tool_calls":[{"id":"c","function":{"name":"ask_user","arguments":"{\"question\":\"?\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""")
        assertEquals("ok", r.text)
        assertEquals("ask_user", r.toolCalls.single().name)
        assertEquals(3, r.usage?.inputTokens)
    }

    private val specs = listOf(ToolSpec("memory_search", "d", JsonObject(emptyMap())), ToolSpec("web_fetch", "d", JsonObject(emptyMap())))

    @Test fun emulationParsesPlainAndFencedJson() {
        val names = specs.map { it.name }.toSet()
        val a = ToolCallEmulation.parse("""{"tool_calls":[{"name":"memory_search","arguments":{"query":"café"}}]}""", names)
        assertEquals("memory_search", a.calls.single().name)
        assertEquals("""{"query":"café"}""", a.calls.single().arguments)
        val b = ToolCallEmulation.parse("Je regarde.\n```json\n{\"name\":\"web.fetch\",\"arguments\":{\"url\":\"https://x.fr\"}}\n```", names)
        assertEquals("web_fetch", b.calls.single().name)
        assertEquals("Je regarde.", b.preamble)
    }

    @Test fun emulationFlagsMalformedAndUnknownTools() {
        val names = specs.map { it.name }.toSet()
        assertTrue(ToolCallEmulation.parse("""{"tool_calls":[{"name":"rm_rf","arguments":{}}]}""", names).malformed)
        assertTrue(ToolCallEmulation.parse("""{"tool_calls":[{"name":"web_fetch","arguments":{"url": }]}""", names).malformed)
        val plain = ToolCallEmulation.parse("Voici la réponse, sans outil.", names)
        assertFalse(plain.malformed)
        assertTrue(plain.calls.isEmpty())
    }

    @Test fun emulationTransformKeepsAlternation() {
        val msgs = listOf(
            ChatMessage("system", "sys"), ChatMessage("user", "q"),
            ChatMessage("assistant", null, toolCalls = listOf(ToolCall("1", "memory_search", """{"query":"a"}"""))),
            ChatMessage("tool", "résultat", toolCallId = "1", name = "memory_search"),
            ChatMessage("user", "suite"),
        )
        val out = ToolCallEmulation.transform(msgs, specs)
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out[0].content!!.contains("memory_search"))
        assertTrue(out[3].content!!.startsWith(ToolCallEmulation.RESULT_PREFIX))
        assertTrue(out[3].content!!.contains("suite"))
    }
}
