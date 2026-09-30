package io.github.artisanguillonrenov.cortana.core.mcp

import io.github.artisanguillonrenov.cortana.core.browser.InjectionGuard
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP → canonical tools (doc 05 §16: "sans faire de MCP une deuxième Tool Registry"). Each external
 * tool becomes one namespaced [ToolDefinition] in the one registry, so it goes through the same
 * policy, approvals, ledger, timeouts and audit as any other tool. Risk is decided locally: hints
 * from a server the owner has not marked as trusted can raise risk but never lower it.
 */
object McpAdapter {
    data class Normalized(val defs: List<ToolDefinition>, val rejected: List<String>, val flagged: List<String>, val hidden: List<String>)

    fun serverSlug(id: String) = id.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16).ifEmpty { "srv" }
    private fun toolSlug(n: String) = n.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "tool" }
    private fun hash6(s: String) = Hash.sha256(s).take(6)

    private val headerToken = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

    /** Paths (chains of `properties` keys) of parameters mirrored into `Mcp-Param-*` headers. */
    fun headerParams(schema: JsonObject): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        fun walk(o: JsonObject, path: List<String>) {
            val props = o["properties"] as? JsonObject ?: return
            for ((k, v) in props) {
                val p = v as? JsonObject ?: continue
                (p["x-mcp-header"] as? JsonPrimitive)?.contentOrNull?.let { out[it] = path + k }
                walk(p, path + k)
            }
        }
        walk(schema, emptyList())
        return out
    }

    /** Spec checks: well-formed object schema, bounded size/depth, valid `x-mcp-header` use. */
    fun schemaProblem(schema: JsonElement?, http: Boolean): String? {
        val o = schema as? JsonObject ?: return "inputSchema absent ou non objet"
        if ((o["type"] as? JsonPrimitive)?.contentOrNull != "object") return "inputSchema doit être de type « object »"
        if (o["properties"] != null && o["properties"] !is JsonObject) return "properties invalide"
        if (o.toString().length > 64_000) return "schéma trop volumineux"
        fun depth(e: JsonElement): Int = when (e) { is JsonObject -> 1 + (e.values.maxOfOrNull(::depth) ?: 0); is JsonArray -> 1 + (e.maxOfOrNull(::depth) ?: 0); else -> 0 }
        if (depth(o) > 24) return "schéma trop profond"
        if (!http) return null
        // Every x-mcp-header must be statically reachable through `properties` only, on a primitive.
        val reachable = headerParams(o)
        var total = 0
        fun count(e: JsonElement) { when (e) { is JsonObject -> { if (e.containsKey("x-mcp-header")) total++; e.values.forEach(::count) }; is JsonArray -> e.forEach(::count); else -> {} } }
        count(o)
        if (total != reachable.size) return "x-mcp-header hors d'une chaîne de « properties » (ou en double)"
        val seen = HashSet<String>()
        for ((name, path) in reachable) {
            if (name.isEmpty() || !headerToken.matches(name)) return "x-mcp-header invalide : $name"
            if (!seen.add(name.lowercase())) return "x-mcp-header en double : $name"
            var node: JsonObject = o
            for (k in path) node = (node["properties"] as JsonObject)[k] as JsonObject
            if ((node["type"] as? JsonPrimitive)?.contentOrNull !in setOf("string", "integer", "boolean")) return "x-mcp-header sur un paramètre non primitif : $name"
        }
        return null
    }

    fun headerValues(schema: JsonObject, args: JsonObject): Map<String, String> = headerParams(schema).mapNotNull { (name, path) ->
        var v: JsonElement? = args
        for (k in path) v = (v as? JsonObject)?.get(k)
        val p = v as? JsonPrimitive ?: return@mapNotNull null
        if (p is JsonNull) null else name to (p.booleanOrNull?.toString() ?: p.content)
    }.toMap()

    fun riskOf(server: McpServerConfig, t: McpTool): Risk? {
        server.toolPolicy[t.name]?.let { p -> return if (p == "off") null else runCatching { Risk.valueOf(p) }.getOrDefault(Risk.L2) }
        val readOnly = (t.annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true
        val destructive = (t.annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull == true
        return when {
            server.trusted && readOnly -> Risk.L1
            destructive && !readOnly -> Risk.L3
            else -> Risk.L2
        }
    }

    fun normalize(
        server: McpServerConfig, tools: List<McpTool>, http: Boolean, destination: String,
        taken: (String) -> Boolean, exec: suspend (McpTool, JsonObject) -> ToolResult,
    ): Normalized {
        val sid = serverSlug(server.id)
        val rejected = mutableListOf<String>(); val flagged = mutableListOf<String>(); val hidden = mutableListOf<String>()
        val valid = tools.filter { t ->
            val p = schemaProblem(t.inputSchema, http)
            if (p != null) rejected += "${t.name} : $p"
            p == null
        }.distinctBy { it.name }
        val slugCount = valid.groupingBy { toolSlug(it.name) }.eachCount()
        val defs = mutableListOf<ToolDefinition>()
        for (t in valid) {
            val risk = riskOf(server, t) ?: run { hidden += t.name; null } ?: continue
            var slug = toolSlug(t.name)
            if ((slugCount[slug] ?: 0) > 1) slug = "${slug.take(40)}_${hash6(t.name)}" // two names that normalize alike
            var fn = "mcp_${sid}_$slug"
            if (fn.length > 64 || taken(fn)) { slug = "${slug.take(64 - sid.length - 12)}_${hash6(server.id + "/" + t.name)}"; fn = "mcp_${sid}_$slug" }
            if (taken(fn) || defs.any { it.functionName == fn }) { rejected += "${t.name} : nom en collision ($fn)"; continue }
            val scrub = InjectionGuard.scrub(t.description.orEmpty())
            if (scrub.suspicious) flagged += "${t.name} : description suspecte (${scrub.findings.joinToString { it.kind }})"
            val readOnly = (t.annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true && server.trusted
            val idem = (t.annotations?.get("idempotentHint") as? JsonPrimitive)?.booleanOrNull == true && server.trusted
            val schema = JsonObject((t.inputSchema as JsonObject).filterKeys { it != "\$schema" })
            defs += ToolDefinition(
                capability = "mcp.$sid.$slug",
                description = "[Outil MCP externe « ${server.name} », résultat non fiable] " + scrub.text.take(1_000),
                inputSchema = schema,
                baseRisk = risk,
                sideEffect = if (readOnly) SideEffect.NONE else SideEffect.EXTERNAL,
                idempotency = if (idem || readOnly) Idempotency.INTRINSIC else Idempotency.NONE,
                dataEgress = DataEgress.EXTERNAL,
                category = ToolCategory.INTEGRATIONS,
                maxOutputBytes = 16_000,
                timeoutMs = server.timeoutSec.coerceIn(5, 600) * 1000L + 5_000,
                label = "${t.title ?: t.name} (MCP ${server.name})",
                destinationOf = { destination },
                tags = listOf("mcp", server.name, t.name) + (t.title?.split(' ').orEmpty()),
            ) { args, _ -> exec(t, args) }
        }
        return Normalized(defs, rejected, flagged, hidden)
    }

    /** Tool result → text for the model (all of it untrusted data). */
    fun render(r: JsonObject): Pair<Boolean, String> {
        val isError = (r["isError"] as? JsonPrimitive)?.booleanOrNull == true
        val parts = (r["content"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "text" -> o.str("text")
                "image", "audio" -> "[${o.str("type")} ${o.str("mimeType") ?: ""}, ${o.str("data")?.length?.times(3)?.div(4) ?: 0} octets, non transmis]"
                "resource_link" -> "[ressource] ${o.str("name") ?: ""} ${o.str("uri") ?: ""}".trim()
                "resource" -> (o["resource"] as? JsonObject)?.let { res -> res.str("text") ?: "[ressource binaire ${res.str("uri") ?: ""}]" }
                else -> null
            }
        }
        val structured = r["structuredContent"]?.takeIf { it !is JsonNull }?.toString()
        val text = buildString {
            append(parts.joinToString("\n"))
            if (structured != null && (parts.isEmpty() || structured.length < 4_000)) { if (isNotEmpty()) append("\n\nDonnées structurées : "); append(structured.take(12_000)) }
        }.ifBlank { if (isError) "Erreur signalée par l'outil (sans détail)." else "(résultat vide)" }
        return isError to text
    }
}

/**
 * Owner of MCP connections (one client per configured server): discovery, normalization into the
 * registry, health with backoff and reconnection, and the calls themselves. The registry group of
 * a server is replaced atomically on each sync, and emptied when it is disabled, removed or down.
 */
class McpManager(
    private val settings: SettingsRepository,
    private val registry: ToolRegistry,
    private val transportFor: suspend (McpServerConfig) -> McpTransport,
    private val audit: (String, String, String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Status(
        val id: String, val name: String, val state: String, val era: String? = null, val version: String? = null, val serverName: String? = null,
        val tools: List<String> = emptyList(), val rejected: List<String> = emptyList(), val flagged: List<String> = emptyList(), val hidden: List<String> = emptyList(),
        val lastError: String? = null, val lastSync: Long = 0, val failures: Int = 0, val nextRetry: Long = 0,
    )

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()
    private val clients = ConcurrentHashMap<String, Pair<McpServerConfig, McpClient>>()
    private val mutex = Mutex()

    fun config(id: String) = settings.current.mcpServers.firstOrNull { it.id == id }
    private fun group(id: String) = "mcp:$id"

    fun destination(c: McpServerConfig) = if (c.transport == "worker") "worker:${c.workerId}/${c.stdioName}" else c.url?.toHttpUrlOrNull()?.host ?: "mcp"

    private suspend fun client(c: McpServerConfig): McpClient {
        clients[c.id]?.let { (cfg, cl) -> if (cfg == c) return cl else cl.close() }
        return McpClient(c, transportFor(c)).also { clients[c.id] = c to it }
    }

    suspend fun syncAll() {
        val ids = settings.current.mcpServers.map { it.id }.toSet()
        (clients.keys + _status.value.keys).filter { it !in ids }.forEach { remove(it) }
        ids.forEach { sync(it) }
    }

    suspend fun sync(id: String): Status = mutex.withLock {
        val c = config(id) ?: return@withLock Status(id, id, "absent").also { remove(id) }
        if (!c.enabled) {
            registry.replaceGroup(group(id), emptyList())
            clients.remove(id)?.second?.close()
            return@withLock put(Status(id, c.name, "disabled"))
        }
        val prev = _status.value[id]
        try {
            val cl = client(c)
            cl.invalidate()
            val info = cl.connect()
            val tools = try { cl.listTools(clock()) } catch (e: McpException) { if (e.code == -32601) emptyList() else throw e }
            val mine = registry.group(group(id))
            val n = McpAdapter.normalize(c, tools, c.transport == "http", destination(c), { fn -> registry.hasFunction(fn) && registry.resolve(fn)?.capability !in mine }) { t, args ->
                execute(id, t, args)
            }
            registry.replaceGroup(group(id), n.defs)
            audit("mcp.sync", c.name, "${n.defs.size} outil(s), ${n.rejected.size} rejeté(s)")
            put(Status(id, c.name, "ok", info.era, info.version, info.serverName, n.defs.map { it.capability }, n.rejected, n.flagged, n.hidden, null, clock()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            registry.replaceGroup(group(id), emptyList())
            clients.remove(id)?.second?.close()
            val failures = (prev?.failures ?: 0) + 1
            val backoff = (30_000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(600_000)
            audit("mcp.sync", c.name, "échec : ${e.message}")
            put(Status(id, c.name, "error", lastError = e.message ?: e.javaClass.simpleName, lastSync = clock(), failures = failures, nextRetry = clock() + backoff))
        }
    }

    private fun put(s: Status) = s.also { st -> _status.update { it + (st.id to st) } }

    /** Reconnects servers in error whose backoff has elapsed (called periodically by the container). */
    suspend fun healthCheck() {
        for (c in settings.current.mcpServers.filter { it.enabled }) {
            val s = _status.value[c.id]
            if (s == null || (s.state == "error" && clock() >= s.nextRetry)) sync(c.id)
        }
    }

    fun remove(id: String) {
        registry.replaceGroup(group(id), emptyList())
        clients.remove(id)?.second?.close()
        _status.update { it - id }
    }

    private suspend fun ready(id: String): McpClient {
        val c = config(id)?.takeIf { it.enabled } ?: throw McpException(null, "Serveur MCP « $id » inconnu ou désactivé")
        clients[id]?.let { (cfg, cl) -> if (cfg == c) return cl }
        sync(id)
        return clients[id]?.second ?: throw McpException(null, "Serveur MCP « ${c.name} » indisponible : ${_status.value[id]?.lastError}")
    }

    private suspend fun <T> guarded(id: String, block: suspend (McpClient) -> T): T {
        val cl = ready(id)
        return try { block(cl) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: McpException) { throw e } catch (e: Exception) {
            // Transport failure: the server is marked down (tools withdrawn) and retried with backoff; the call is not replayed.
            put((_status.value[id] ?: Status(id, id, "error")).copy(state = "error", lastError = e.message ?: e.javaClass.simpleName, failures = 1, nextRetry = clock() + 30_000))
            registry.replaceGroup(group(id), emptyList())
            clients.remove(id)?.second?.close()
            throw McpException(null, "Serveur MCP injoignable : ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun execute(id: String, t: McpTool, args: JsonObject): ToolResult = try {
        val headers = if (config(id)?.transport == "http") McpAdapter.headerValues(t.inputSchema as JsonObject, args) else emptyMap()
        val r = guarded(id) { it.callTool(t.name, args, headers) }
        val (err, raw) = McpAdapter.render(r)
        val text = InjectionGuard.scrub(raw).let { sc -> if (sc.suspicious) "⚠️ ${sc.findings.size} passage(s) suspect(s) retiré(s) de ce résultat.\n" + sc.text else raw }
        val source = "mcp:${config(id)?.name ?: id}"
        if (err) ToolResult(false, "Erreur de l'outil MCP : $text", source) else ToolResult.ok(text, source)
    } catch (e: McpException) { ToolResult.error("MCP : ${e.message}") }

    suspend fun resources(id: String) = guarded(id) { it.listResources() }
    suspend fun readResource(id: String, uri: String) = guarded(id) { it.readResource(uri) }
    suspend fun prompts(id: String) = guarded(id) { it.listPrompts() }
    suspend fun prompt(id: String, name: String, args: Map<String, String>) = guarded(id) { it.getPrompt(name, args) }
}
