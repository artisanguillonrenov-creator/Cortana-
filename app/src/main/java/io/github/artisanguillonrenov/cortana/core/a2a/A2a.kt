package io.github.artisanguillonrenov.cortana.core.a2a

import io.github.artisanguillonrenov.cortana.core.browser.InjectionGuard
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.model.await
import io.github.artisanguillonrenov.cortana.core.vision.TextMatch
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** An external agent the owner added (doc 05 §17). The token lives in the secret store. */
@Serializable
data class A2aAgentConfig(
    val id: String,
    val name: String,
    /** Agent card URL, or the agent's base URL (then `/.well-known/agent-card.json`). */
    val cardUrl: String,
    val authHandle: String? = null,
    val enabled: Boolean = true,
    val timeoutSec: Int = 180,
    val createdAt: Long = 0,
)

class A2aException(val code: Int?, message: String) : Exception(message)

data class A2aSkill(val id: String, val name: String, val description: String, val tags: List<String>, val examples: List<String>)

data class A2aCard(
    val name: String, val description: String, val version: String, val rpcUrl: String, val tenant: String?, val protocolVersion: String,
    val streaming: Boolean, val skills: List<A2aSkill>, val outputModes: List<String>, val signed: Boolean, val provider: String?,
)

/** What came back from a delegation: text and data are untrusted; files became artifacts. */
data class A2aOutcome(
    val agent: String, val taskId: String?, val contextId: String?, val state: String, val text: String,
    val artifactIds: List<String>, val injections: Int, val rejectedFiles: List<String>,
)

object A2aProtocol {
    const val VERSION = "1.0"
    const val VERSION_NOT_SUPPORTED = -32009
    val TERMINAL = setOf("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_REJECTED")
    val INTERRUPTED = setOf("TASK_STATE_INPUT_REQUIRED", "TASK_STATE_AUTH_REQUIRED")

    fun cardUrl(raw: String): HttpUrl = cardUrls(raw).first()

    /**
     * Where to look for the agent card: the given `.json` URL; otherwise the well-known path under
     * the given base path, then at the domain root (the spec's location).
     */
    fun cardUrls(raw: String): List<HttpUrl> {
        val u = raw.trim().toHttpUrlOrNull() ?: throw A2aException(null, "Adresse de l'agent invalide")
        if (u.encodedPath.endsWith(".json")) return listOf(u)
        val root = u.newBuilder().encodedPath("/.well-known/agent-card.json").query(null).build()
        val base = u.encodedPath.trimEnd('/')
        return if (base.isEmpty()) listOf(root) else listOf(u.newBuilder().encodedPath("$base/.well-known/agent-card.json").query(null).build(), root)
    }

    fun parseCard(o: JsonObject, base: HttpUrl): A2aCard {
        val name = o.s("name") ?: throw A2aException(null, "Carte d'agent sans nom")
        val ifaces = (o["supportedInterfaces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // JSON-RPC 1.x only: the client does not silently fall back to 0.x (spec §3.6.3).
        val rpc = ifaces.firstOrNull { it.s("protocolBinding").equals("JSONRPC", true) && it.s("protocolVersion")?.substringBefore('.') == "1" }
            ?: throw A2aException(A2aProtocol.VERSION_NOT_SUPPORTED, "L'agent « $name » ne propose pas d'interface JSON-RPC A2A 1.x (interfaces : ${ifaces.joinToString { "${it.s("protocolBinding")} ${it.s("protocolVersion")}" }.ifEmpty { "aucune" }})")
        val url = rpc.s("url")?.let { base.resolve(it) } ?: throw A2aException(null, "Interface sans URL")
        val skills = (o["skills"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            A2aSkill(s.s("id") ?: return@mapNotNull null, s.s("name") ?: "", s.s("description") ?: "",
                (s["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, (s["examples"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        }
        return A2aCard(name, o.s("description") ?: "", o.s("version") ?: "?", url.toString(), rpc.s("tenant"), rpc.s("protocolVersion")!!,
            ((o["capabilities"] as? JsonObject)?.get("streaming") as? JsonPrimitive)?.booleanOrNull == true, skills,
            (o["defaultOutputModes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            (o["signatures"] as? JsonArray)?.isNotEmpty() == true, (o["provider"] as? JsonObject)?.s("organization"))
    }
}

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** JSON-RPC binding of A2A 1.0 (`A2A-Version: 1.0` on every request). */
class A2aClient(private val http: OkHttpClient, private val token: () -> String?) {
    private val json = "application/json".toMediaType()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("A2A-Version", A2aProtocol.VERSION).header("Accept", "application/json").apply {
        token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    suspend fun card(cardUrl: HttpUrl): A2aCard {
        val body = http.newCall(request(cardUrl).get().build()).await().use { r ->
            if (r.code == 401 || r.code == 403) throw A2aException(r.code, "Carte d'agent refusée (HTTP ${r.code})")
            if (!r.isSuccessful) throw A2aException(r.code, "Carte d'agent introuvable (HTTP ${r.code})")
            r.body?.source()?.let { src -> src.request(1_000_001); if (src.buffer.size > 1_000_000) throw A2aException(null, "Carte d'agent trop volumineuse"); src.buffer.readUtf8() }.orEmpty()
        }
        val o = runCatching { AppJson.parseToJsonElement(body) as JsonObject }.getOrElse { throw A2aException(null, "Carte d'agent illisible") }
        return A2aProtocol.parseCard(o, cardUrl)
    }

    suspend fun rpc(card: A2aCard, method: String, params: JsonObject): JsonObject {
        val p = if (card.tenant != null && params["tenant"] == null) JsonObject(params + ("tenant" to JsonPrimitive(card.tenant))) else params
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", Ids.new()); put("method", method); put("params", p) }
        val u = card.rpcUrl.toHttpUrlOrNull() ?: throw A2aException(null, "URL d'agent invalide")
        val text = http.newCall(request(u).post(msg.toString().toRequestBody(json)).build()).await().use { r ->
            if (r.code == 401 || r.code == 403) throw A2aException(r.code, "Authentification refusée par l'agent (HTTP ${r.code}) : vérifie le jeton dans Réglages → Agents externes")
            val src = r.body?.source() ?: throw A2aException(r.code, "Réponse vide")
            src.request(30_000_001)
            if (src.buffer.size > 30_000_000) throw A2aException(null, "Réponse de l'agent trop volumineuse")
            src.buffer.readUtf8().also { if (!r.isSuccessful && !it.trimStart().startsWith("{")) throw A2aException(r.code, "Agent : HTTP ${r.code}") }
        }
        val o = runCatching { AppJson.parseToJsonElement(text) as JsonObject }.getOrElse { throw A2aException(null, "Réponse de l'agent illisible") }
        (o["error"] as? JsonObject)?.let { e -> throw A2aException((e["code"] as? JsonPrimitive)?.intOrNull, "Agent : ${e.s("message") ?: "erreur"}") }
        return o["result"] as? JsonObject ?: throw A2aException(null, "Réponse de l'agent sans résultat")
    }
}

/**
 * Delegation to external agents (doc 05 §17: "un agent externe est une capacité distante. Il
 * n'obtient pas la mémoire interne complète ni le contrôle de l'orchestrateur"). The outgoing
 * message is built only from what the tool call states (objective, explicit context, attached
 * artifacts); nothing is added from memory, history or prompts. Answers are untrusted data;
 * files become artifacts (size-capped, never opened); a cancelled or late task is cancelled remotely.
 */
class A2aService(
    private val settings: SettingsRepository,
    private val clientFor: (A2aAgentConfig) -> A2aClient,
    private val saveFile: suspend (bytes: ByteArray, name: String, mime: String, taskId: String?, meta: Map<String, String>) -> String,
    private val download: suspend (A2aAgentConfig, String, Long) -> Pair<ByteArray, String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = 2_000,
    private val maxFileBytes: Long = 20_000_000,
    private val perMinute: Int = 6,
) {
    data class Status(val id: String, val name: String, val state: String, val card: A2aCard? = null, val error: String? = null, val checkedAt: Long = 0)

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()
    private val recent = ConcurrentHashMap<String, MutableList<Long>>()

    fun config(key: String): A2aAgentConfig? = settings.current.a2aAgents.firstOrNull { it.id == key || it.name.equals(key, true) }

    fun host(c: A2aAgentConfig): String = _status.value[c.id]?.card?.rpcUrl?.toHttpUrlOrNull()?.host ?: c.cardUrl.toHttpUrlOrNull()?.host ?: c.id

    suspend fun refresh(id: String): Status {
        val c = config(id) ?: return Status(id, id, "absent").also { st -> _status.update { it - id } }
        if (!c.enabled) return put(Status(id, c.name, "disabled"))
        return try {
            val client = clientFor(c)
            val urls = A2aProtocol.cardUrls(c.cardUrl)
            var card: A2aCard? = null
            for ((i, u) in urls.withIndex()) {
                try { card = client.card(u); break } catch (e: A2aException) { if (e.code != 404 || i == urls.lastIndex) throw e }
            }
            put(Status(id, c.name, "ok", card, checkedAt = clock()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            put(Status(id, c.name, "error", error = e.message ?: e.javaClass.simpleName, checkedAt = clock()))
        }
    }

    suspend fun refreshAll() {
        val ids = settings.current.a2aAgents.map { it.id }.toSet()
        _status.update { m -> m.filterKeys { it in ids } }
        ids.forEach { refresh(it) }
    }

    private fun put(s: Status) = s.also { st -> _status.update { it + (st.id to st) } }

    private suspend fun card(c: A2aAgentConfig): A2aCard {
        val s = _status.value[c.id]
        if (s?.card != null && clock() - s.checkedAt < 3_600_000) return s.card
        val r = refresh(c.id)
        return r.card ?: throw A2aException(null, "Agent « ${c.name} » indisponible : ${r.error}")
    }

    /** Capability match: agents ranked by overlap between the objective and their skills. */
    fun match(objective: String): List<Pair<A2aAgentConfig, Double>> {
        val q = TextMatch.normalize(objective).split(' ').filter { it.length > 3 }.toSet()
        if (q.isEmpty()) return emptyList()
        return settings.current.a2aAgents.filter { it.enabled }.mapNotNull { c ->
            val card = _status.value[c.id]?.card ?: return@mapNotNull null
            val words = card.skills.flatMap { s -> (s.tags + s.name + s.description + s.examples).flatMap { TextMatch.normalize(it).split(' ') } }.filter { it.length > 3 }.toSet()
            val tags = card.skills.flatMap { s -> s.tags.map(TextMatch::normalize) }.toSet()
            val score = q.count { it in words } + 2.0 * q.count { it in tags }
            if (score > 0) c to score else null
        }.sortedByDescending { it.second }
    }

    fun resolve(agent: String?, objective: String): A2aAgentConfig {
        if (!agent.isNullOrBlank()) return config(agent)?.takeIf { it.enabled } ?: throw A2aException(null, "Agent « $agent » inconnu ou désactivé")
        val m = match(objective)
        if (m.isEmpty()) throw A2aException(null, "Aucun agent externe ne correspond (agents : ${settings.current.a2aAgents.joinToString { it.name }.ifEmpty { "aucun" }})")
        if (m.size > 1 && m[0].second == m[1].second) throw A2aException(null, "Plusieurs agents conviennent (${m.filter { it.second == m[0].second }.joinToString { it.first.name }}) : précise lequel")
        return m.first().first
    }

    /** The exact outgoing text: objective plus explicit context, nothing else. */
    fun outgoingText(objective: String, context: String?) = objective.trim() + (context?.takeIf { it.isNotBlank() }?.let { "\n\nContexte fourni :\n" + it.trim() } ?: "")

    suspend fun delegate(
        c: A2aAgentConfig, objective: String, context: String?, files: List<Triple<String, String, ByteArray>>,
        remoteTaskId: String?, contextId: String?, cortanaTaskId: String?,
    ): A2aOutcome {
        val now = clock()
        val window = recent.getOrPut(c.id) { mutableListOf() }
        synchronized(window) {
            window.removeAll { now - it > 60_000 }
            if (window.size >= perMinute) throw A2aException(null, "Limite atteinte : $perMinute délégations par minute vers « ${c.name} »")
            window += now
        }
        val card = card(c)
        val client = clientFor(c)
        val message = buildJsonObject {
            put("messageId", Ids.new()); put("role", "ROLE_USER")
            remoteTaskId?.let { put("taskId", it) }; contextId?.let { put("contextId", it) }
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", outgoingText(objective, context)); put("mediaType", "text/plain") })
                files.forEach { (name, mime, bytes) -> add(buildJsonObject { put("raw", Base64.getEncoder().encodeToString(bytes)); put("filename", name); put("mediaType", mime) }) }
            })
        }
        val params = buildJsonObject {
            put("message", message)
            put("configuration", buildJsonObject {
                put("acceptedOutputModes", buildJsonArray { listOf("text/plain", "application/json", "text/markdown", "application/pdf", "image/png", "image/jpeg").forEach { add(JsonPrimitive(it)) } })
                put("historyLength", 0)
            })
        }
        val deadline = clock() + c.timeoutSec.coerceIn(10, 1_800) * 1000L
        var taskId: String? = remoteTaskId
        try {
            var r = client.rpc(card, "SendMessage", params)
            var task = r["task"] as? JsonObject
            if (task == null) {
                val m = r["message"] as? JsonObject ?: (if (r["status"] != null) { task = r; null } else r) // tolerate an unwrapped Task
                if (task == null) return render(c, null, m!!, cortanaTaskId)
            }
            taskId = task!!.s("id")
            while (state(task!!) !in A2aProtocol.TERMINAL && state(task) !in A2aProtocol.INTERRUPTED) {
                if (clock() > deadline) throw A2aException(null, "Délai dépassé (${c.timeoutSec} s) : tâche distante annulée")
                delay(pollMs)
                task = client.rpc(card, "GetTask", buildJsonObject { put("id", taskId!!); put("historyLength", 0) }).let { (it["task"] as? JsonObject) ?: it }
            }
            return render(c, task, null, cortanaTaskId)
        } catch (e: Throwable) {
            val id = taskId
            if (id != null && (e is kotlinx.coroutines.CancellationException || (e is A2aException && e.message!!.startsWith("Délai")))) {
                withContext(NonCancellable) { runCatching { client.rpc(card, "CancelTask", buildJsonObject { put("id", id) }) } }
            }
            throw e
        }
    }

    suspend fun task(c: A2aAgentConfig, id: String, cancel: Boolean): A2aOutcome {
        val card = card(c)
        val r = clientFor(c).rpc(card, if (cancel) "CancelTask" else "GetTask", buildJsonObject { put("id", id); if (!cancel) put("historyLength", 0) })
        return render(c, (r["task"] as? JsonObject) ?: r, null, null)
    }

    private fun state(t: JsonObject) = (t["status"] as? JsonObject)?.s("state") ?: "TASK_STATE_UNSPECIFIED"

    private suspend fun render(c: A2aAgentConfig, task: JsonObject?, message: JsonObject?, cortanaTaskId: String?): A2aOutcome {
        val texts = mutableListOf<String>()
        val arts = mutableListOf<String>(); val rejected = mutableListOf<String>()
        suspend fun parts(ps: JsonArray?, label: String?) {
            ps.orEmpty().forEach { e ->
                val p = e as? JsonObject ?: return@forEach
                val mime = p.s("mediaType") ?: "application/octet-stream"
                when {
                    p.s("text") != null -> texts += (label?.let { "[$it] " } ?: "") + p.s("text")!!
                    p["data"] != null && p["data"] !is JsonNull -> texts += (label?.let { "[$it] " } ?: "") + "Données : " + p["data"].toString().take(8_000)
                    p.s("raw") != null || p.s("url") != null -> {
                        val name = (p.s("filename") ?: label ?: "fichier").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^\\w.\\- ]"), "_").trimStart('.').take(100).ifEmpty { "fichier" }
                        try {
                            val (bytes, type) = p.s("raw")?.let { raw ->
                                if (raw.length > maxFileBytes * 4 / 3 + 4) throw A2aException(null, "fichier trop volumineux")
                                Base64.getDecoder().decode(raw) to mime
                            } ?: download(c, p.s("url")!!, maxFileBytes)
                            if (bytes.size > maxFileBytes) throw A2aException(null, "fichier trop volumineux")
                            arts += saveFile(bytes, name, type.ifEmpty { mime }, cortanaTaskId, mapOf("agent" to c.name, "remoteTask" to (task?.s("id") ?: ""), "source" to (p.s("url") ?: "inline")))
                        } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { rejected += "$name : ${x.message}" }
                    }
                }
            }
        }
        val state = task?.let(::state) ?: "message"
        (task?.get("status") as? JsonObject)?.get("message")?.let { parts((it as? JsonObject)?.get("parts") as? JsonArray, null) }
        (task?.get("artifacts") as? JsonArray).orEmpty().forEach { a -> (a as? JsonObject)?.let { parts(it["parts"] as? JsonArray, it.s("name")) } }
        message?.let { parts(it["parts"] as? JsonArray, null) }
        val scrub = InjectionGuard.scrub(texts.joinToString("\n\n"))
        return A2aOutcome(c.name, task?.s("id"), task?.s("contextId") ?: message?.s("contextId"), state, scrub.text, arts, scrub.findings.size, rejected)
    }
}
