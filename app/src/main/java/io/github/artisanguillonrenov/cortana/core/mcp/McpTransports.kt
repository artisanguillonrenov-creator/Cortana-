package io.github.artisanguillonrenov.cortana.core.mcp

import io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streamable HTTP (doc 05 §16 "HTTP/streaming transport"). Modern requests mirror method, name
 * and tool parameters into headers; legacy servers get their session header. A response is either
 * one JSON object or an SSE stream scoped to the request; cancelling the coroutine closes the
 * stream, which is the modern cancellation signal. Every URL and redirect passes the SSRF rule.
 */
class McpHttpTransport(
    baseClient: OkHttpClient,
    private val endpoint: HttpUrl,
    private val token: suspend () -> String?,
    allowed: (HttpUrl) -> Boolean,
    private val maxBytes: Long = 8_000_000,
) : McpTransport {
    override val kind = "http"
    private val http = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(SsrfGuard.redirectGuard(allowed)).readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    @Volatile private var sessionId: String? = null
    private val json = "application/json".toMediaType()

    override fun resetSession() { sessionId = null }

    /** Last token obtained, for the fire-and-forget requests made outside a coroutine. */
    @Volatile private var lastToken: String? = null

    private suspend fun build(message: JsonObject, call: McpCall): Request = buildWith(message, call, token().also { lastToken = it })

    private fun buildWith(message: JsonObject, call: McpCall, token: String?): Request {
        val b = Request.Builder().url(endpoint).post(message.toString().toRequestBody(json))
            .header("Accept", "application/json, text/event-stream")
        token?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        call.protocolVersion?.let { b.header("MCP-Protocol-Version", it) }
        if (call.protocolVersion in McpProtocol.MODERN_VERSIONS) {
            b.header("Mcp-Method", call.method)
            call.name?.let { b.header("Mcp-Name", headerValue(it)) }
            call.paramHeaders.forEach { (k, v) -> b.header("Mcp-Param-$k", headerValue(v)) }
        } else sessionId?.let { b.header("Mcp-Session-Id", it) }
        return b.build()
    }

    override suspend fun request(message: JsonObject, call: McpCall): JsonObject {
        val id = message["id"]
        val c = http.newCall(build(message, call))
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { c.cancel() } }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val r = runCatching { response.use { read(it, id, message["method"].let { m -> (m as? JsonPrimitive)?.content }) } }
                    if (!cont.isCancelled) r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                }
            })
        }
    }

    private fun read(r: Response, id: kotlinx.serialization.json.JsonElement?, method: String?): JsonObject {
        if (method == "initialize") r.header("Mcp-Session-Id")?.let { sessionId = it }
        val type = r.header("Content-Type").orEmpty()
        val source = r.body?.source() ?: throw McpHttpStatus(r.code, "")
        if (r.isSuccessful && type.startsWith("text/event-stream", true)) {
            var data = StringBuilder()
            var total = 0L
            while (true) {
                val line = source.readUtf8Line() ?: break
                total += line.length
                if (total > maxBytes) throw McpException(null, "Réponse MCP trop volumineuse")
                when {
                    line.isEmpty() -> {
                        val ev = data.toString(); data = StringBuilder()
                        if (ev.isBlank()) continue
                        val o = runCatching { AppJson.parseToJsonElement(ev) as? JsonObject }.getOrNull() ?: continue
                        if (o["id"] == id && (o["result"] != null || o["error"] != null)) return o
                        // Legacy servers may send requests on the stream (ping, roots, sampling…): answer "not supported".
                        if (o["method"] != null && o["id"] != null) respondUnsupported(o)
                    }
                    line.startsWith(":") -> {}
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                }
            }
            throw McpException(null, "Flux MCP interrompu avant la réponse")
        }
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) throw McpException(null, "Réponse MCP trop volumineuse")
        val body = source.buffer.readUtf8()
        val o = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (o != null && (o["result"] != null || o["error"] is JsonObject)) return o
        if (r.code == 401 || r.code == 403) {
            val hint = r.header("WWW-Authenticate")?.let { if (it.contains("resource_metadata")) " (le serveur demande une connexion OAuth)" else "" }.orEmpty()
            throw McpException(r.code, "Accès refusé par le serveur MCP (HTTP ${r.code})$hint : vérifie le jeton dans Réglages → Serveurs MCP")
        }
        throw McpHttpStatus(r.code, body.take(300))
    }

    private fun respondUnsupported(req: JsonObject) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", req["id"]!!)
            put("error", buildJsonObject { put("code", -32601); put("message", "Non pris en charge par Cortana") })
        }
        runCatching { http.newCall(buildWith(msg, McpCall("response", null, null, timeoutMs = 5_000), lastToken)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        }) }
    }

    override suspend fun notify(message: JsonObject, call: McpCall) {
        val c = http.newCall(build(message, call))
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { c.cancel() } }
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resume(Unit) }
                override fun onResponse(call: Call, response: Response) { response.close(); if (!cont.isCancelled) cont.resume(Unit) }
            })
        }
    }

    override fun close() {
        val sid = sessionId ?: return
        sessionId = null
        runCatching {
            http.newCall(Request.Builder().url(endpoint).delete().header("Mcp-Session-Id", sid).apply { lastToken?.let { header("Authorization", "Bearer $it") } }.build())
                .enqueue(object : Callback { override fun onFailure(call: Call, e: IOException) {}; override fun onResponse(call: Call, response: Response) = response.close() })
        }
    }

    companion object {
        /** Header value encoding of the 2026-07-28 transport (Base64 sentinel for anything unsafe). */
        fun headerValue(v: String): String {
            val plain = v.isNotEmpty() && v.all { it.code in 0x20..0x7E } && v.first() != ' ' && v.last() != ' ' && !(v.startsWith("=?base64?") && v.endsWith("?="))
            return if (plain) v else "=?base64?" + Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8)) + "?="
        }
    }
}

/**
 * stdio servers run on a paired worker, declared in the worker's own configuration; the tablet
 * sends one JSON-RPC message at a time through the signed worker channel ([send] returns the
 * response line, or null for a notification).
 */
class McpWorkerTransport(private val send: suspend (message: String, timeoutMs: Long) -> String?) : McpTransport {
    override val kind = "stdio"
    override suspend fun request(message: JsonObject, call: McpCall): JsonObject {
        val line = send(message.toString(), call.timeoutMs) ?: throw McpException(null, "Le serveur MCP n'a pas répondu")
        return runCatching { AppJson.parseToJsonElement(line) as JsonObject }.getOrElse { throw McpException(null, "Réponse MCP illisible") }
    }
    override suspend fun notify(message: JsonObject, call: McpCall) { send(message.toString(), call.timeoutMs) }
}

object McpEndpoints {
    /** https everywhere; plain http only towards the local network (a token must never cross the Internet in clear). */
    fun check(url: String?): okhttp3.HttpUrl {
        val u = url?.trim()?.toHttpUrlOrNull() ?: throw McpException(null, "Adresse du serveur MCP invalide")
        if (!u.isHttps && !SsrfGuard.isBlockedLiteral(u.host)) throw McpException(null, "Un serveur MCP sur Internet doit utiliser https")
        return u
    }
}
