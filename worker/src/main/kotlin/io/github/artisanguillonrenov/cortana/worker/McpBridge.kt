package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * A stdio MCP server the worker's owner declared in `worker.json` (the tablet can only name it:
 * no command line ever travels over the network).
 */
@Serializable
data class McpStdioSpec(val command: List<String>, val workDir: String? = null, val env: Map<String, String> = emptyMap())

/**
 * Bridges the tablet's MCP client to stdio servers (doc 05 §16 "stdio via worker"): one process per
 * (paired device, server), started on demand with a minimal environment, restarted after a crash,
 * stopped when idle. Messages are newline-delimited JSON-RPC; responses are matched by id. Legacy
 * servers that send their own requests (ping, roots, sampling) get "method not found".
 */
class McpBridge(private val specs: () -> Map<String, McpStdioSpec>, private val logDir: File, private val idleMs: Long = 10 * 60_000) {
    private class Proc(val process: Process, val writer: BufferedWriter) {
        val pending = ConcurrentHashMap<String, CompletableFuture<String>>()
        @Volatile var lastUse = System.currentTimeMillis()
        val alive get() = process.isAlive
    }

    private val procs = ConcurrentHashMap<String, Proc>()

    fun names(): List<String> = specs().keys.sorted()

    /** Sends one message; returns the response line for a request, null for a notification. */
    fun send(deviceId: String, name: String, message: String, timeoutMs: Long): String? {
        val spec = specs()[name] ?: throw AuthException(404, "serveur MCP inconnu sur ce worker")
        val msg = runCatching { ContractJson.parseToJsonElement(message) as JsonObject }.getOrNull() ?: throw IllegalArgumentException("message JSON-RPC invalide")
        require((msg["jsonrpc"] as? JsonPrimitive)?.content == "2.0" && msg["method"] is JsonPrimitive) { "seuls les requêtes et notifications JSON-RPC sont acceptées" }
        reapIdle()
        val key = "$deviceId/$name"
        val p = synchronized(procs) { procs[key]?.takeIf { it.alive } ?: start(key, spec).also { procs[key] = it } }
        p.lastUse = System.currentTimeMillis()
        val id = msg["id"]?.takeIf { it !is JsonNull }?.toString()
        val future = id?.let { CompletableFuture<String>().also { f -> p.pending[it] = f } }
        synchronized(p.writer) { p.writer.write(msg.toString()); p.writer.newLine(); p.writer.flush() } // re-serialized: one line, no embedded newline
        if (future == null) return null
        return try { future.get(timeoutMs.coerceIn(1_000, 600_000), TimeUnit.MILLISECONDS) }
        catch (e: TimeoutException) { p.pending.remove(id); throw IllegalStateException("délai dépassé") }
        catch (e: java.util.concurrent.ExecutionException) { p.pending.remove(id); throw IllegalStateException(e.cause?.message ?: "serveur arrêté") }
    }

    private fun start(key: String, spec: McpStdioSpec): Proc {
        require(spec.command.isNotEmpty()) { "commande vide" }
        val pb = ProcessBuilder(spec.command)
        spec.workDir?.let { pb.directory(File(it)) }
        // Minimal environment: the worker's own variables (tokens, keys) never leak into the server.
        val env = pb.environment()
        val keep = env.filterKeys { it in setOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT", "TEMP") }
        env.clear(); env.putAll(keep); env.putAll(spec.env)
        logDir.mkdirs()
        pb.redirectError(ProcessBuilder.Redirect.appendTo(File(logDir, key.replace(Regex("[^\\w.-]"), "_") + ".stderr.log")))
        val process = pb.start()
        val p = Proc(process, process.outputStream.bufferedWriter())
        thread(isDaemon = true, name = "mcp-$key") {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val o = runCatching { ContractJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                        val id = o["id"]?.takeIf { it !is JsonNull }?.toString()
                        when {
                            id != null && (o["result"] != null || o["error"] != null) -> p.pending.remove(id)?.complete(line)
                            id != null && o["method"] != null -> synchronized(p.writer) {
                                p.writer.write(buildJsonObject { put("jsonrpc", "2.0"); put("id", o["id"]!!); put("error", buildJsonObject { put("code", -32601); put("message", "Non pris en charge") }) }.toString())
                                p.writer.newLine(); p.writer.flush()
                            }
                            else -> {} // progress, logging, subscription notifications: not relayed
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                p.pending.values.forEach { it.completeExceptionally(IllegalStateException("le serveur MCP s'est arrêté")) }
                p.pending.clear()
            }
        }
        return p
    }

    private fun reapIdle() {
        val now = System.currentTimeMillis()
        procs.entries.filter { now - it.value.lastUse > idleMs || !it.value.alive }.forEach { (k, p) -> procs.remove(k); stop(p) }
    }

    private fun stop(p: Proc) {
        runCatching { p.writer.close() } // closing stdin is the graceful shutdown signal
        if (!p.process.waitFor(2, TimeUnit.SECONDS)) { p.process.destroy(); if (!p.process.waitFor(2, TimeUnit.SECONDS)) p.process.destroyForcibly() }
    }

    fun stopAll() { procs.values.forEach(::stop); procs.clear() }
    fun running(): Int = procs.values.count { it.alive }
}
