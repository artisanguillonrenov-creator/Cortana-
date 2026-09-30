package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses OpenAI-compatible streaming chunks (the payload after "data: ").
 * Pure and side-effect free so it is unit-testable.
 */
object SseParser {
    const val DONE = "[DONE]"

    fun parseLine(line: String): List<ModelStreamEvent>? {
        val trimmed = line.trimEnd()
        if (!trimmed.startsWith("data:")) return null
        val data = trimmed.removePrefix("data:").trim()
        if (data.isEmpty()) return emptyList()
        if (data == DONE) return listOf(ModelStreamEvent.Done(null))
        return parseChunk(data)
    }

    fun parseChunk(data: String): List<ModelStreamEvent> {
        val obj = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<ModelStreamEvent>()
        (obj["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString()
            val code = (err["code"] as? JsonPrimitive)?.intOrNull
            out += ModelStreamEvent.Error(msg, code, retryable = code == null || code == 429 || code >= 500)
            return out
        }
        val choices = obj["choices"] as? JsonArray
        choices?.forEach { c ->
            val choice = c as? JsonObject ?: return@forEach
            val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject)
            if (delta != null) {
                (delta["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.let {
                    if (it.isNotEmpty()) out += ModelStreamEvent.TextDelta(it)
                }
                delta["reasoning_details"]?.takeIf { it !is JsonNull }?.let { out += ModelStreamEvent.Reasoning(it) }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { i, tc ->
                    val t = tc as? JsonObject ?: return@forEachIndexed
                    val index = (t["index"] as? JsonPrimitive)?.intOrNull ?: i
                    val fn = t["function"] as? JsonObject
                    out += ModelStreamEvent.ToolCallDelta(
                        index = index,
                        id = (t["id"] as? JsonPrimitive)?.contentOrNull,
                        name = (fn?.get("name") as? JsonPrimitive)?.contentOrNull,
                        argumentsDelta = fn?.get("arguments")?.let { a ->
                            if (a is JsonPrimitive) a.contentOrNull else a.toString()
                        },
                    )
                }
            }
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { out += ModelStreamEvent.Done(it) }
        }
        (obj["usage"] as? JsonObject)?.let { out += ModelStreamEvent.UsageEvent(parseUsage(it)) }
        return out
    }

    fun parseUsage(u: JsonObject): Usage = Usage(
        inputTokens = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
        outputTokens = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull,
        costUsd = (u["cost"] as? JsonPrimitive)?.doubleOrNull,
    )

    /** Parses a non-streaming chat completion body. */
    fun parseCompletion(body: String): ModelResponse {
        val obj = AppJson.parseToJsonElement(body).jsonObject
        (obj["error"] as? JsonObject)?.let { err ->
            throw ModelException((err["message"] as? JsonPrimitive)?.contentOrNull ?: err.toString())
        }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw ModelException("Réponse sans « choices »")
        val msg = choice["message"]?.jsonObject ?: JsonObject(emptyMap())
        val text = (msg["content"] as? JsonPrimitive)?.contentOrNull ?: ""
        val calls = (msg["tool_calls"] as? JsonArray)?.mapIndexedNotNull { i, e ->
            val t = e as? JsonObject ?: return@mapIndexedNotNull null
            val fn = t["function"] as? JsonObject ?: return@mapIndexedNotNull null
            ToolCall(
                id = (t["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$i",
                name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null,
                arguments = fn["arguments"]?.let { a -> if (a is JsonPrimitive) a.contentOrNull ?: "{}" else a.toString() } ?: "{}",
            )
        } ?: emptyList()
        return ModelResponse(
            text = text,
            toolCalls = calls,
            usage = (obj["usage"] as? JsonObject)?.let(::parseUsage),
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull,
            reasoningDetails = msg["reasoning_details"]?.takeIf { it !is JsonNull },
        )
    }
}

/** Accumulates streamed tool-call fragments by index. */
class ToolCallAccumulator {
    private data class Partial(var id: String? = null, var name: StringBuilder = StringBuilder(), var args: StringBuilder = StringBuilder())
    private val parts = sortedMapOf<Int, Partial>()

    fun add(d: ModelStreamEvent.ToolCallDelta) {
        val p = parts.getOrPut(d.index) { Partial() }
        if (d.id != null && p.id == null) p.id = d.id
        if (d.name != null) {
            // Some providers resend the full name on every chunk.
            if (p.name.isEmpty() || !p.name.toString().endsWith(d.name)) p.name.append(d.name)
        }
        if (d.argumentsDelta != null) p.args.append(d.argumentsDelta)
    }

    fun isEmpty() = parts.isEmpty()

    fun build(): List<ToolCall> = parts.entries.mapNotNull { (i, p) ->
        val name = p.name.toString().trim()
        if (name.isEmpty()) null else ToolCall(p.id ?: "call_$i", name, p.args.toString().ifBlank { "{}" })
    }
}
