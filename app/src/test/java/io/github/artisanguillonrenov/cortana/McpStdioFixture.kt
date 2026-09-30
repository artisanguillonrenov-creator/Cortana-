package io.github.artisanguillonrenov.cortana

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * A stdio MCP server run as a real subprocess by the worker in [McpTest] (plain JVM, no Android).
 * Modern by default; with the argument "legacy" it only understands the initialize handshake.
 * Cancellation notifications are appended to the file named by FIXTURE_LOG.
 */
object McpStdioFixture {
    @JvmStatic fun main(args: Array<String>) {
        val legacy = args.contains("legacy")
        val log = System.getenv("FIXTURE_LOG")?.let(::File)
        // The worker must pass only a minimal environment (plus what its owner declared).
        log?.appendText("extra-env:" + (System.getenv().keys - setOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT", "TEMP", "FIXTURE_LOG")).sorted() + "\n")
        val out = System.out.bufferedWriter()
        fun send(o: JsonObject) { synchronized(out) { out.write(o.toString()); out.newLine(); out.flush() } }
        fun result(id: kotlinx.serialization.json.JsonElement, r: JsonObject) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r) })
        fun error(id: kotlinx.serialization.json.JsonElement, code: Int, m: String) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", m) }) })
        var initialized = false
        System.`in`.bufferedReader().forEachLine { line ->
            val m = Json.parseToJsonElement(line).jsonObject
            val id = m["id"]
            val method = (m["method"] as JsonPrimitive).content
            val params = m["params"] as? JsonObject
            if (id == null) {
                if (method == "notifications/cancelled") log?.appendText("cancelled:" + params!!["requestId"] + "\n")
                if (method == "notifications/initialized") initialized = true
                return@forEachLine
            }
            val modernMeta = (params?.get("_meta") as? JsonObject)?.get("io.modelcontextprotocol/protocolVersion")
            when {
                method == "server/discover" && legacy -> error(id, -32601, "Method not found")
                method == "server/discover" -> result(id, buildJsonObject {
                    put("resultType", "complete"); put("supportedVersions", buildJsonArray { add(JsonPrimitive("2026-07-28")) })
                    put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                    put("_meta", buildJsonObject { put("io.modelcontextprotocol/serverInfo", buildJsonObject { put("name", "fixture-stdio"); put("version", "1") }) })
                })
                method == "initialize" && legacy -> result(id, buildJsonObject {
                    put("protocolVersion", "2025-11-25"); put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                    put("serverInfo", buildJsonObject { put("name", "fixture-legacy"); put("version", "1") })
                })
                legacy && !initialized -> error(id, -32600, "not initialized")
                !legacy && modernMeta == null -> error(id, -32602, "missing _meta")
                method == "tools/list" -> result(id, buildJsonObject {
                    put("resultType", "complete")
                    put("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "echo"); put("description", "Renvoie le texte"); put("inputSchema", Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")) })
                        add(buildJsonObject { put("name", "wait"); put("description", "Attend longtemps"); put("inputSchema", Json.parseToJsonElement("""{"type":"object"}""")) })
                    })
                })
                method == "tools/call" -> {
                    val name = (params!!["name"] as JsonPrimitive).content
                    if (name == "echo") result(id, buildJsonObject {
                        put("resultType", "complete")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "écho : " + ((params["arguments"] as JsonObject)["text"] as JsonPrimitive).content) }) })
                    }) // "wait" never answers: only cancellation ends it
                }
                else -> error(id, -32601, "Method not found")
            }
        }
    }
}
