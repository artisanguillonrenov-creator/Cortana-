package io.github.artisanguillonrenov.cortana.core.connections

import io.github.artisanguillonrenov.cortana.contracts.HookRegistration
import io.github.artisanguillonrenov.cortana.contracts.WebhookSignature
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

/** HTTP response as a tool may see it (bounded, text only). */
data class HttpReply(val code: Int, val contentType: String, val text: String?, val size: Long, val location: String?) {
    val ok get() = code in 200..299
}

/**
 * Shared HTTP plumbing for connectors: the client comes from [clientFor] (SSRF rule, with the
 * connection's own configured host allowed even on the local network), redirects are never
 * followed, bodies are bounded.
 */
class ConnectorHttp(private val clientFor: (HttpUrl) -> OkHttpClient) {
    suspend fun call(url: HttpUrl, method: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, maxBytes: Int = 1_000_000): HttpReply = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .method(method, if (method in setOf("GET", "HEAD", "DELETE") && (body == null || body.isEmpty())) null else (body ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull()))
        clientFor(url).newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(req.build()).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
            r.body?.byteStream()?.use { input -> while (true) { val n = input.read(buf); if (n < 0) break; if (out.size() + n > maxBytes) throw ConnectionException("réponse trop volumineuse (plus de $maxBytes octets)"); out.write(buf, 0, n) } }
            val bytes = out.toByteArray()
            val textual = type.isEmpty() || type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript")
            HttpReply(r.code, type, if (textual) bytes.decodeToString() else null, bytes.size.toLong(), r.header("Location"))
        }
    }
}

/** Generic REST API (blueprint §37.3): host and base path fixed by the owner, auth injected, methods allowlisted. */
class HttpConnector(private val mgr: ConnectionManager, private val http: ConnectorHttp) : ConnectorAdapter {
    private val forbidden = setOf("authorization", "cookie", "host", "proxy-authorization", "x-api-key", "content-length", "transfer-encoding")

    fun base(c: Conn): HttpUrl = c.cfg("base_url")?.toHttpUrlOrNull() ?: throw ConnectionException("adresse de base invalide")

    /** The URL [path] resolves to, refused if it leaves the connection's host or base path. */
    fun resolve(c: Conn, path: String, query: Map<String, String>): HttpUrl {
        val base = base(c)
        if (path.contains("://") || path.startsWith("//") || path.contains('\\')) throw ConnectionException("chemin relatif attendu")
        val u = (base.toString().trimEnd('/') + "/" + path.trimStart('/')).toHttpUrlOrNull() ?: throw ConnectionException("chemin invalide : $path")
        val prefix = base.encodedPath.trimEnd('/')
        if (u.host != base.host || u.port != base.port || u.scheme != base.scheme || !(u.encodedPath == prefix || u.encodedPath.startsWith("$prefix/")) || u.pathSegments.any { it == ".." })
            throw ConnectionException("chemin hors de l'API configurée")
        return u.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    fun methods(c: Conn) = (c.cfg("methods") ?: "GET").split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    suspend fun request(c: Conn, method: String, path: String, query: Map<String, String>, body: String?, contentType: String?, headers: Map<String, String>): HttpReply {
        val m = method.uppercase()
        if (m !in methods(c)) throw ConnectionException("méthode $m non permise sur « ${c.name} » (permises : ${methods(c).joinToString()})")
        headers.keys.firstOrNull { it.lowercase() in forbidden }?.let { throw ConnectionException("en-tête $it géré par Cortana") }
        mgr.acquire(c)
        return http.call(resolve(c, path, query), m, headers + c.authHeaders(), body?.toByteArray(), contentType ?: if (body != null) "application/json" else null)
    }

    override suspend fun probe(c: Conn): String {
        val r = http.call(resolve(c, c.cfg("health_path") ?: "", emptyMap()), "GET", c.authHeaders(), null, null)
        if (r.code >= 500 || r.code == 401 || r.code == 403) throw ConnectionException("HTTP ${r.code}")
        return "HTTP ${r.code}"
    }
}

/** Outbound webhooks (blueprint §37.2): policy-controlled, idempotent (key header), signed when a secret is set. */
class WebhookOutConnector(private val mgr: ConnectionManager, private val http: ConnectorHttp, private val clock: () -> Long = System::currentTimeMillis) : ConnectorAdapter {
    fun url(c: Conn): HttpUrl = c.cfg("url")?.toHttpUrlOrNull() ?: throw ConnectionException("adresse de destination invalide")

    suspend fun send(c: Conn, event: String, payload: String, idempotencyKey: String): HttpReply {
        mgr.acquire(c)
        val body = payload.toByteArray()
        val ts = clock() / 1000
        val headers = mutableMapOf("X-Cortana-Event" to event, "Idempotency-Key" to idempotencyKey, "User-Agent" to "Cortana-Webhook/1")
        c.secret("signing_secret")?.let { s -> headers[WebhookSignature.TIMESTAMP] = ts.toString(); headers[WebhookSignature.HEADER] = WebhookSignature.sign(s.toByteArray(), ts, body) }
        return http.call(url(c), "POST", headers, body, "application/json", 64_000)
    }

    override suspend fun probe(c: Conn): String { url(c); return "adresse ${url(c).host} (aucun envoi de test)" }
}

/** Inbound webhooks hosted by a paired worker: registered on add, removed on revoke. */
class WebhookInConnector(private val mgr: ConnectionManager, private val worker: WorkerHooks) : ConnectorAdapter {
    interface WorkerHooks {
        suspend fun register(workerId: String, name: String, reg: HookRegistration): io.github.artisanguillonrenov.cortana.contracts.HookInfo
        suspend fun delete(workerId: String, name: String)
        suspend fun events(workerId: String, after: Long): io.github.artisanguillonrenov.cortana.contracts.HookEvents
        suspend fun baseUrl(workerId: String): String
    }

    override suspend fun onAdd(c: Conn) {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = java.util.Base64.getEncoder().encodeToString(secret)
        val info = worker.register(c.cfg("worker_id")!!, c.name, HookRegistration(b64, c.cfg("max_per_minute")?.toIntOrNull() ?: 30, c.name))
        mgr.putSecret(c.id, "hook_secret", b64)
        mgr.setConfig(c.id, mapOf("hook_id" to info.hookId, "hook_url" to worker.baseUrl(c.cfg("worker_id")!!).trimEnd('/') + info.path))
    }

    override suspend fun onRevoke(c: Conn) { worker.delete(c.cfg("worker_id") ?: return, c.name) }

    override suspend fun probe(c: Conn): String {
        // Re-registering is idempotent (same hook id) and proves the worker still hosts it.
        val info = worker.register(c.cfg("worker_id")!!, c.name, HookRegistration(c.secret("hook_secret") ?: throw ConnectionException("secret du webhook manquant"), c.cfg("max_per_minute")?.toIntOrNull() ?: 30, c.name))
        return "hébergé par le worker (${info.path})"
    }

    suspend fun events(c: Conn, after: Long) = worker.events(c.cfg("worker_id")!!, after)
}

/** Home Assistant REST API (doc 05 §15): entity states, service calls limited to allowed domains. */
class HomeAssistantConnector(private val mgr: ConnectionManager, private val http: ConnectorHttp) : ConnectorAdapter {
    private fun url(c: Conn, path: String) = (c.cfg("base_url")?.trimEnd('/') + path).toHttpUrlOrNull() ?: throw ConnectionException("adresse Home Assistant invalide")
    fun allowed(c: Conn) = (c.cfg("allowed_domains") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun sensitive(c: Conn) = (c.cfg("sensitive_domains") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private suspend fun json(c: Conn, method: String, path: String, body: String? = null): JsonElement {
        mgr.acquire(c)
        val r = http.call(url(c, path), method, c.authHeaders() + ("Accept" to "application/json"), body?.toByteArray(), "application/json", 4_000_000)
        if (r.code == 401 || r.code == 403) throw ConnectionException("Home Assistant refuse l'accès (jeton invalide ou révoqué)")
        if (r.code == 404) throw ConnectionException("introuvable dans Home Assistant")
        if (!r.ok) throw ConnectionException("Home Assistant : HTTP ${r.code} ${r.text?.take(200).orEmpty()}")
        return runCatching { AppJson.parseToJsonElement(r.text.orEmpty()) }.getOrElse { throw ConnectionException("réponse de Home Assistant illisible") }
    }

    data class Entity(val id: String, val state: String, val name: String?, val attributes: Map<String, String>)

    private fun entity(o: JsonObject): Entity? {
        val id = (o["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val attrs = (o["attributes"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it.take(80) } }?.toMap().orEmpty()
        return Entity(id, (o["state"] as? JsonPrimitive)?.contentOrNull ?: "?", attrs["friendly_name"], attrs - "friendly_name")
    }

    suspend fun states(c: Conn, domain: String?, search: String?): List<Entity> =
        ((json(c, "GET", "/api/states") as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonObject)?.let(::entity) }
            .filter { e -> e.id.substringBefore('.') in allowed(c) && (domain == null || e.id.startsWith("$domain.")) && (search == null || e.id.contains(search, true) || e.name?.contains(search, true) == true) }

    suspend fun state(c: Conn, entityId: String): Entity {
        if (entityId.substringBefore('.') !in allowed(c)) throw ConnectionException("domaine ${entityId.substringBefore('.')} non permis sur « ${c.name} »")
        return (json(c, "GET", "/api/states/$entityId") as? JsonObject)?.let(::entity) ?: throw ConnectionException("état illisible")
    }

    suspend fun call(c: Conn, domain: String, service: String, data: JsonObject): Int {
        if (domain !in allowed(c)) throw ConnectionException("domaine $domain non permis sur « ${c.name} » (permis : ${allowed(c).joinToString()})")
        if (!service.matches(Regex("[a-z0-9_]{1,64}")) || !domain.matches(Regex("[a-z0-9_]{1,64}"))) throw ConnectionException("service invalide")
        return ((json(c, "POST", "/api/services/$domain/$service", data.toString()) as? JsonArray)?.size ?: 0)
    }

    override suspend fun probe(c: Conn): String {
        val o = json(c, "GET", "/api/") as? JsonObject
        return (o?.get("message") as? JsonPrimitive)?.contentOrNull ?: "API joignable"
    }
}

/** Telegram Bot API (blueprint §35.2): long polling in, sendMessage out; nothing else. */
class TelegramConnector(private val mgr: ConnectionManager, private val http: ConnectorHttp) : ConnectorAdapter {
    private suspend fun api(c: Conn, method: String, body: String?, maxBytes: Int = 2_000_000): JsonObject {
        val token = c.secret("bot_token") ?: throw ConnectionException("jeton du bot manquant")
        val url = ((c.cfg("api_base") ?: "https://api.telegram.org").trimEnd('/') + "/bot$token/$method").toHttpUrlOrNull() ?: throw ConnectionException("adresse Telegram invalide")
        val r = http.call(url, if (body == null) "GET" else "POST", emptyMap(), body?.toByteArray(), "application/json", maxBytes)
        val o = runCatching { AppJson.parseToJsonElement(r.text.orEmpty()) as JsonObject }.getOrNull() ?: throw ConnectionException("Telegram : HTTP ${r.code}")
        if ((o["ok"] as? JsonPrimitive)?.contentOrNull != "true") throw ConnectionException("Telegram : ${(o["description"] as? JsonPrimitive)?.contentOrNull ?: "erreur"}")
        return o
    }

    fun allowedChats(c: Conn) = (c.cfg("allowed_chats") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    data class Update(val updateId: Long, val chatId: String, val fromId: String?, val fromName: String?, val text: String?, val date: Long)

    suspend fun updates(c: Conn, offset: Long, timeoutSec: Int): List<Update> {
        val o = api(c, "getUpdates", """{"offset":$offset,"timeout":$timeoutSec,"allowed_updates":["message"]}""")
        return ((o["result"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { u ->
            val uo = u as? JsonObject ?: return@mapNotNull null
            val id = (uo["update_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            val m = uo["message"] as? JsonObject ?: return@mapNotNull Update(id, "", null, null, null, 0)
            val chat = (m["chat"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
            val from = m["from"] as? JsonObject
            Update(id, chat, (from?.get("id") as? JsonPrimitive)?.contentOrNull, (from?.get("first_name") as? JsonPrimitive)?.contentOrNull,
                (m["text"] as? JsonPrimitive)?.contentOrNull, (m["date"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0)
        }
    }

    suspend fun send(c: Conn, chatId: String, text: String) {
        mgr.acquire(c)
        text.chunked(4000).forEach { part ->
            api(c, "sendMessage", kotlinx.serialization.json.buildJsonObject { put("chat_id", JsonPrimitive(chatId)); put("text", JsonPrimitive(part)); put("disable_web_page_preview", JsonPrimitive(true)) }.toString())
        }
    }

    override suspend fun probe(c: Conn): String {
        val me = api(c, "getMe", null)["result"] as? JsonObject
        return "bot @" + ((me?.get("username") as? JsonPrimitive)?.contentOrNull ?: "?")
    }
}
