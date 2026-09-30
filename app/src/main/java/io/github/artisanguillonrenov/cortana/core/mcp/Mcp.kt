package io.github.artisanguillonrenov.cortana.core.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * An MCP server the owner configured (doc 05 §16). `http` = Streamable HTTP endpoint; `worker` = a
 * stdio server declared in a paired worker's own configuration (the tablet can only name it, never
 * send a command line). Tokens live in the secret store ([authHandle]); nothing sensitive here.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: String = "http",
    val url: String? = null,
    val workerId: String? = null,
    val stdioName: String? = null,
    val authHandle: String? = null,
    /** OAuth-protected server: name of the connection whose access token is sent (phase 26). */
    val connection: String? = null,
    /** Owner vouches for this server: its read-only annotations may lower a tool to L1. */
    val trusted: Boolean = false,
    val enabled: Boolean = true,
    val timeoutSec: Int = 60,
    /** Per-tool owner policy: "L0".."L3", or "off" to hide the tool. */
    val toolPolicy: Map<String, String> = emptyMap(),
    val createdAt: Long = 0,
)

class McpException(val code: Int?, message: String, val data: JsonElement? = null) : Exception(message)

/** HTTP-level failure without a recognized JSON-RPC body (drives the legacy fallback). */
class McpHttpStatus(val status: Int, val body: String) : Exception("HTTP $status")

data class McpTool(val name: String, val title: String?, val description: String?, val inputSchema: JsonElement?, val annotations: JsonObject?)
data class McpResource(val uri: String, val name: String?, val mimeType: String?, val description: String?)
data class McpPrompt(val name: String, val description: String?, val arguments: List<String>)
data class McpServerInfo(val era: String, val version: String, val serverName: String?, val serverVersion: String?, val capabilities: JsonObject, val instructions: String?)

/** Per-request details a transport may need (HTTP mirrors some of them into headers). */
data class McpCall(val method: String, val name: String?, val protocolVersion: String?, val paramHeaders: Map<String, String> = emptyMap(), val timeoutMs: Long)

/** One way of reaching a server. Requests return the raw JSON-RPC response (result or error). */
interface McpTransport {
    val kind: String
    suspend fun request(message: JsonObject, call: McpCall): JsonObject
    suspend fun notify(message: JsonObject, call: McpCall)
    /** Legacy (initialize-based) servers only: remember/forget the negotiated session. */
    fun resetSession() {}
    fun close() {}
}

object McpProtocol {
    const val MODERN = "2026-07-28"
    val MODERN_VERSIONS = listOf(MODERN)
    val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    const val HEADER_MISMATCH = -32020
    const val MISSING_CAPABILITY = -32021
    const val UNSUPPORTED_VERSION = -32022
    val MODERN_ERRORS = setOf(HEADER_MISMATCH, MISSING_CAPABILITY, UNSUPPORTED_VERSION)
    /** -32004 is the draft numbering of the same error, still sent by some servers. */
    fun isUnsupportedVersion(code: Int?) = code == UNSUPPORTED_VERSION || code == -32004
}

/**
 * JSON-RPC client for one server, dual-era: modern (2026-07-28, stateless, `_meta` on every request,
 * `server/discover`) first, legacy `initialize` handshake as fallback (2025-03-26 … 2025-11-25),
 * following the spec's detection rules. Cortana advertises no client capabilities (no sampling,
 * elicitation or roots): a server asking for input gets a clear refusal.
 */
class McpClient(val config: McpServerConfig, private val transport: McpTransport, private val clientVersion: String = "vnext") {
    private val ids = AtomicLong(1)
    @Volatile var info: McpServerInfo? = null; private set
    private val clientInfo = buildJsonObject { put("name", "Cortana"); put("version", clientVersion) }
    private var toolsCache: Pair<Long, List<McpTool>>? = null

    private val timeoutMs get() = config.timeoutSec.coerceIn(5, 600) * 1000L
    val modern get() = info?.era == "modern"

    private fun meta(version: String) = buildJsonObject {
        put(McpProtocol.META_VERSION, version)
        put(McpProtocol.META_CLIENT_INFO, clientInfo)
        put(McpProtocol.META_CLIENT_CAPS, JsonObject(emptyMap()))
    }

    private fun message(method: String, params: JsonObject?, id: Long?) = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    /** Detects the server's era and version (doc 05 §16 "version negotiation"). */
    suspend fun connect(): McpServerInfo {
        info?.let { return it }
        val probe = runCatching { rawModern("server/discover", JsonObject(emptyMap()), McpProtocol.MODERN, null, if (transport.kind == "stdio") 8_000 else timeoutMs) }
        val r = probe.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            val supported = ((e as? McpException)?.data as? JsonObject)?.get("supported").let { it as? JsonArray }?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            when {
                // A recognized modern error identifies a modern server: never fall back to initialize.
                e is McpException && (e.code == McpProtocol.UNSUPPORTED_VERSION || (e.code == -32004 && supported.isNotEmpty())) -> {
                    val v = supported.firstOrNull { it in McpProtocol.MODERN_VERSIONS }
                        ?: if (supported.any { it in McpProtocol.LEGACY_VERSIONS }) return legacyInitialize()
                        else throw McpException(e.code, "Serveur MCP sans version commune (il propose : ${supported.joinToString().ifEmpty { "?" }})")
                    rawModern("server/discover", JsonObject(emptyMap()), v, null, timeoutMs)
                }
                e is McpException && e.code in McpProtocol.MODERN_ERRORS -> throw McpException(e.code, "Serveur MCP moderne : ${e.message}")
                else -> return legacyInitialize()
            }
        }
        val versions = (r["supportedVersions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val version = versions.firstOrNull { it in McpProtocol.MODERN_VERSIONS } ?: if (versions.any { it in McpProtocol.LEGACY_VERSIONS }) return legacyInitialize() else McpProtocol.MODERN
        val si = (r["_meta"] as? JsonObject)?.get(McpProtocol.META_SERVER_INFO) as? JsonObject
        return McpServerInfo("modern", version, si?.str("name"), si?.str("version"), r["capabilities"] as? JsonObject ?: JsonObject(emptyMap()), r.str("instructions"))
            .also { info = it }
    }

    private suspend fun legacyInitialize(): McpServerInfo {
        transport.resetSession()
        val params = buildJsonObject {
            put("protocolVersion", McpProtocol.LEGACY_VERSIONS.first())
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", clientInfo)
        }
        val resp = exchange(message("initialize", params, ids.getAndIncrement()), McpCall("initialize", null, null, timeoutMs = timeoutMs))
        val r = result(resp)
        val v = r.str("protocolVersion") ?: throw McpException(null, "Réponse d'initialisation invalide")
        if (v !in McpProtocol.LEGACY_VERSIONS) throw McpException(null, "Version MCP non prise en charge : $v")
        transport.notify(message("notifications/initialized", null, null), McpCall("notifications/initialized", null, v, timeoutMs = timeoutMs))
        val si = r["serverInfo"] as? JsonObject
        return McpServerInfo("legacy", v, si?.str("name"), si?.str("version"), r["capabilities"] as? JsonObject ?: JsonObject(emptyMap()), r.str("instructions")).also { info = it }
    }

    private suspend fun rawModern(method: String, params: JsonObject, version: String, name: String?, timeout: Long, headers: Map<String, String> = emptyMap()): JsonObject {
        val p = JsonObject(params + ("_meta" to JsonObject(((params["_meta"] as? JsonObject) ?: JsonObject(emptyMap())) + meta(version))))
        return result(exchange(message(method, p, ids.getAndIncrement()), McpCall(method, name, version, headers, timeout)))
    }

    /** Sends a request with a deadline; on timeout or cancellation the server is told (stdio/legacy). */
    private suspend fun exchange(msg: JsonObject, call: McpCall): JsonObject {
        val id = (msg["id"] as? JsonPrimitive)?.longOrNull
        try {
            return withTimeout(call.timeoutMs) { transport.request(msg, call) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Streamable HTTP (modern): closing the stream is the cancellation. Otherwise notify.
            if (id != null && (transport.kind == "stdio" || info?.era == "legacy")) withContext(NonCancellable) {
                runCatching {
                    transport.notify(message("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", "délai dépassé ou tâche annulée") }, null),
                        McpCall("notifications/cancelled", null, info?.version, timeoutMs = 5_000))
                }
            }
            if (e is TimeoutCancellationException) throw McpException(null, "Délai dépassé (${call.timeoutMs / 1000} s) pour ${call.method}")
            throw e
        }
    }

    private fun result(resp: JsonObject): JsonObject {
        (resp["error"] as? JsonObject)?.let { err ->
            throw McpException((err["code"] as? JsonPrimitive)?.intOrNull, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Erreur MCP", err["data"])
        }
        val r = resp["result"] as? JsonObject ?: throw McpException(null, "Réponse MCP sans résultat")
        if (r.str("resultType") == "input_required") throw McpException(null, "Le serveur demande une saisie supplémentaire (élicitation/échantillonnage), que Cortana ne fournit pas")
        return r
    }

    /** A request in the negotiated era; a modern version mismatch is renegotiated once. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), name: String? = null, timeout: Long = timeoutMs, headers: Map<String, String> = emptyMap()): JsonObject {
        val i = connect()
        if (i.era == "legacy") {
            return try { result(exchange(message(method, params, ids.getAndIncrement()), McpCall(method, name, i.version, headers, timeout))) }
            catch (e: McpHttpStatus) {
                if (e.status != 404) throw e
                // Legacy session expired: initialize again once.
                info = null; legacyInitialize()
                result(exchange(message(method, params, ids.getAndIncrement()), McpCall(method, name, info!!.version, headers, timeout)))
            }
        }
        return try { rawModern(method, params, i.version, name, timeout, headers) } catch (e: McpException) {
            if (!McpProtocol.isUnsupportedVersion(e.code)) throw e
            info = null
            rawModern(method, params, connect().version, name, timeout, headers)
        }
    }

    suspend fun listTools(now: Long = System.currentTimeMillis()): List<McpTool> {
        toolsCache?.let { (until, list) -> if (now < until) return list }
        val out = mutableListOf<McpTool>()
        var cursor: String? = null
        var ttl = 0L
        var pages = 0
        do {
            val r = call("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            ttl = (r["ttlMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            (r["tools"] as? JsonArray)?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val name = o.str("name") ?: return@forEach
                out += McpTool(name, o.str("title") ?: (o["annotations"] as? JsonObject)?.str("title"), o.str("description"), o["inputSchema"], o["annotations"] as? JsonObject)
            }
            cursor = r.str("nextCursor")
        } while (cursor != null && ++pages < 20)
        if (ttl > 0) toolsCache = (now + ttl.coerceAtMost(3_600_000)) to out
        return out
    }

    fun invalidate() { toolsCache = null }

    suspend fun callTool(name: String, args: JsonObject, headers: Map<String, String>, timeout: Long = timeoutMs): JsonObject =
        call("tools/call", buildJsonObject { put("name", name); put("arguments", args) }, name, timeout, headers)

    suspend fun listResources(): List<McpResource> = (call("resources/list")["resources"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        McpResource(o.str("uri") ?: return@mapNotNull null, o.str("name"), o.str("mimeType"), o.str("description"))
    }

    suspend fun readResource(uri: String): JsonObject = call("resources/read", buildJsonObject { put("uri", uri) }, uri)

    suspend fun listPrompts(): List<McpPrompt> = (call("prompts/list")["prompts"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        McpPrompt(o.str("name") ?: return@mapNotNull null, o.str("description"),
            (o["arguments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") })
    }

    suspend fun getPrompt(name: String, arguments: Map<String, String>): JsonObject =
        call("prompts/get", buildJsonObject { put("name", name); put("arguments", JsonObject(arguments.mapValues { JsonPrimitive(it.value) })) }, name)

    fun close() = transport.close()
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
