package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * §5.3 — tool-call emulation for models without reliable native tool calling: tool specs are
 * rendered into the system prompt with a strict JSON call format; replies are parsed and
 * validated by the caller. Malformed calls are never executed.
 */
object ToolCallEmulation {
    const val RESULT_PREFIX = "RÉSULTAT D'OUTIL"

    fun instructions(tools: List<ToolSpec>): String = buildString {
        appendLine("## Outils disponibles (mode JSON)")
        appendLine("Pour appeler un ou plusieurs outils, réponds UNIQUEMENT avec un objet JSON de cette forme, sans aucun autre texte :")
        appendLine("""{"tool_calls":[{"name":"nom_de_l_outil","arguments":{ ... }}]}""")
        appendLine("Tu recevras ensuite les résultats dans un message commençant par « $RESULT_PREFIX ». Quand la tâche est terminée, réponds normalement en texte, sans JSON.")
        appendLine("N'invente jamais de résultat d'outil. Un seul objet JSON par réponse.")
        appendLine()
        appendLine("Outils (nom — description — paramètres JSON Schema) :")
        tools.forEach { t -> appendLine("- ${t.name} — ${t.description} — ${t.parameters}") }
    }

    /** Converts a native-tools conversation into a plain one the model can follow. */
    fun transform(messages: List<ChatMessage>, tools: List<ToolSpec>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var systemDone = false
        for (m in messages) {
            when {
                m.role == "system" && !systemDone -> {
                    out += ChatMessage("system", (m.content ?: "") + "\n\n" + instructions(tools)); systemDone = true
                }
                m.role == "assistant" && !m.toolCalls.isNullOrEmpty() -> {
                    val json = buildJsonObject {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", runCatching { AppJson.parseToJsonElement(tc.arguments) }.getOrElse { JsonObject(emptyMap()) })
                                })
                            }
                        }
                    }
                    val pre = m.content?.takeIf { it.isNotBlank() }?.let { "$it\n" } ?: ""
                    out += ChatMessage("assistant", pre + json.toString())
                }
                m.role == "tool" -> out += ChatMessage("user", "$RESULT_PREFIX (${m.name ?: "outil"}) :\n${m.content ?: ""}")
                else -> out += ChatMessage(m.role, m.content ?: "", images = m.images)
            }
        }
        if (!systemDone) out.add(0, ChatMessage("system", instructions(tools)))
        return mergeConsecutive(out)
    }

    /** Some chat templates require strict user/assistant alternation. */
    fun mergeConsecutive(messages: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (m in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == m.role && m.role != "system") {
                out[out.size - 1] = last.copy(content = (last.content ?: "") + "\n\n" + (m.content ?: ""))
            } else out += m
        }
        return out
    }

    data class ParseOutcome(val calls: List<ToolCall>, val preamble: String, val malformed: Boolean)

    fun looksLikeToolJson(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") || t.startsWith("```") || t.contains("\"tool_calls\"")
    }

    fun parse(text: String, knownNames: Set<String>): ParseOutcome {
        val candidates = extractJsonObjects(text)
        var seq = 0
        for ((start, json) in candidates) {
            val el = runCatching { AppJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: continue
            val raw: List<JsonObject> = when {
                el["tool_calls"] is JsonArray -> (el["tool_calls"] as JsonArray).mapNotNull { it as? JsonObject }
                el["name"] != null && (el["arguments"] != null || el["parameters"] != null) -> listOf(el)
                el["tool"] != null -> listOf(el)
                else -> emptyList()
            }
            if (raw.isEmpty()) continue
            val calls = raw.mapNotNull { o ->
                val fn = o["function"] as? JsonObject
                val name = ((o["name"] ?: o["tool"] ?: fn?.get("name")) as? JsonPrimitive)?.contentOrNull
                    ?.replace('.', '_') ?: return@mapNotNull null
                if (name !in knownNames) return@mapNotNull null
                val args: JsonElement = o["arguments"] ?: o["parameters"] ?: o["args"] ?: fn?.get("arguments") ?: JsonObject(emptyMap())
                val argStr = if (args is JsonPrimitive) args.contentOrNull ?: "{}" else args.toString()
                ToolCall("emu_${System.nanoTime()}_${seq++}", name, argStr)
            }
            if (calls.isNotEmpty()) {
                val pre = text.substring(0, start).replace("```json", "").replace("```", "").trim()
                return ParseOutcome(calls, pre, malformed = false)
            }
        }
        // Looked like a tool call but nothing valid could be extracted → ask the model to repair.
        val t = text.trim().removePrefix("```json").removePrefix("```").trimStart()
        val malformed = t.startsWith("{") || text.contains("\"tool_calls\"")
        return ParseOutcome(emptyList(), text, malformed = malformed)
    }

    /** Finds balanced top-level JSON objects in free text, respecting strings and escapes. */
    fun extractJsonObjects(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                var depth = 0
                var inStr = false
                var esc = false
                var j = i
                while (j < text.length) {
                    val c = text[j]
                    if (inStr) {
                        if (esc) esc = false
                        else if (c == '\\') esc = true
                        else if (c == '"') inStr = false
                    } else {
                        when (c) {
                            '"' -> inStr = true
                            '{' -> depth++
                            '}' -> { depth--; if (depth == 0) break }
                        }
                    }
                    j++
                }
                if (depth == 0 && j < text.length) {
                    out += i to text.substring(i, j + 1)
                    i = j + 1
                    continue
                } else break
            }
            i++
        }
        return out
    }
}
