package io.github.artisanguillonrenov.cortana.core.connections

import io.github.artisanguillonrenov.cortana.contracts.TaskConstraints
import io.github.artisanguillonrenov.cortana.contracts.TaskRequest
import io.github.artisanguillonrenov.cortana.contracts.TaskSource
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionDao
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionEventEntity
import io.github.artisanguillonrenov.cortana.core.outbox.Outbox
import io.github.artisanguillonrenov.cortana.core.outbox.OutboxRetryException
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** A message from any channel, normalized (blueprint §35.1). */
data class GatewayMessage(
    val source: String, val connection: String, val conversationId: String, val senderId: String?, val senderName: String?,
    val text: String, val receivedAt: Long, val trusted: Boolean,
)

/**
 * Inbound channels (blueprint §35.2, §37.2): events are validated (allowed identity, signature on
 * the worker), recorded durably with a dedupe id, and turned into ordinary TaskRequests handed to
 * the one orchestrator — no adapter ever calls a tool. Webhook payloads are untrusted data (the
 * task is tainted); messages from the owner's authorized chats are the owner's requests. Replies
 * go out through the outbox (exactly once, retried).
 */
class InboundService(
    private val mgr: ConnectionManager,
    private val dao: ConnectionDao,
    private val hooks: WebhookInConnector,
    private val telegram: TelegramConnector,
    private val submit: suspend (String, TaskRequest) -> Boolean,
    private val sessionFor: suspend (String?, String) -> String,
    private val outbox: Outbox,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dispatching = Mutex()
    /** session → (connection name, chat id) of messaging conversations awaiting replies. */
    private val replyTo = ConcurrentHashMap<String, Pair<String, String>>()

    init {
        outbox.register(KIND_TELEGRAM) { p ->
            val c = try { mgr.usable(p.str("connection")!!, "telegram") } catch (e: ConnectionException) { throw IllegalStateException(e.message) }
            try { telegram.send(c, p.str("chat")!!, p.str("text")!!) } catch (e: ConnectionException) { throw OutboxRetryException(e.message ?: "Telegram") }
        }
    }

    // ------------------------------------------------------------------ collection

    /** Collects queued webhook events from the workers hosting them. */
    suspend fun pollHooks(): Int {
        var n = 0
        for (e in dao.all().filter { it.kind == "webhook_in" && it.state == "active" }) {
            val c = mgr.conn(e)
            try {
                val after = c.config["hook_after"]?.toLongOrNull() ?: 0
                val got = hooks.events(c, after)
                for (ev in got.events) {
                    val payload = buildJsonObject {
                        put("headers", JsonObject(ev.headers.mapValues { JsonPrimitive(it.value) })); put("body", ev.body)
                    }.toString()
                    if (mgr.record(e.connectionId, "inbound", "queued", "événement reçu (${ev.headers["X-GitHub-Event"] ?: ev.headers["X-Cortana-Event"] ?: "webhook"}, ${ev.body.length} caractères)",
                            payload = payload, id = "hook:${e.connectionId}:${ev.hookId}:" + "%012d".format(ev.seq))) n++
                }
                if (got.next != after) mgr.setConfig(e.connectionId, mapOf("hook_after" to got.next.toString()))
            } catch (x: CancellationException) { throw x } catch (x: Exception) { mgr.record(e.connectionId, "inbound", "error", "collecte impossible : ${x.message}") }
        }
        return n
    }

    /** One long-poll round on a Telegram connection; unknown senders are rejected and audited. */
    suspend fun pollTelegram(name: String, timeoutSec: Int): Int {
        val c = mgr.usable(name, "telegram")
        val offset = c.config["offset"]?.toLongOrNull() ?: 0
        val ups = telegram.updates(c, offset, timeoutSec)
        var n = 0
        val allowed = telegram.allowedChats(c)
        for (u in ups) {
            val id = "tg:${c.id}:${u.updateId}"
            when {
                u.text.isNullOrBlank() -> mgr.record(c.id, "inbound", "rejected", "message sans texte ignoré", id = id)
                u.chatId !in allowed -> {
                    if (mgr.record(c.id, "inbound", "rejected", "expéditeur non autorisé (discussion ${u.chatId}${u.fromName?.let { ", $it" } ?: ""})", id = id))
                        audit.record("cortana", "messaging.reject", c.name, "rejected", """{"chat":"${u.chatId}"}""")
                }
                else -> {
                    val payload = AppJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("chat", u.chatId); put("from", u.fromName ?: ""); put("text", u.text.take(8000)) })
                    if (mgr.record(c.id, "inbound", "queued", "message de ${u.fromName ?: u.chatId}", payload = payload, id = id)) n++
                }
            }
        }
        ups.maxOfOrNull { it.updateId }?.let { mgr.setConfig(c.id, mapOf("offset" to (it + 1).toString())) }
        return n
    }

    // ------------------------------------------------------------------ dispatch

    private fun GatewayMessage.request(sessionId: String, objective: String, untrusted: String?): TaskRequest = TaskRequest(
        requestId = Ids.new(), sessionId = sessionId, source = if (source == "telegram") TaskSource.MESSAGING else TaskSource.WEBHOOK, objective = objective,
        constraints = TaskConstraints(toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL), createdAt = clock(),
        contextHints = mapOf("channel" to "$source:$connection") + (if (untrusted != null) mapOf("untrusted_source" to "$source:$connection", "untrusted_content" to untrusted) else emptyMap()),
    )

    /** Hands queued events to the orchestrator, oldest first; stops when it is busy (retried later). */
    suspend fun dispatch(): Int = dispatching.withLock {
        var n = 0
        for (ev in dao.queued()) {
            val e = dao.get(ev.connectionId)
            if (e == null || e.state != "active") { dao.upsertEvent(ev.copy(outcome = "rejected", detail = "connexion inactive : événement abandonné", payload = null)); continue }
            val p = runCatching { AppJson.parseToJsonElement(ev.payload ?: "{}") as JsonObject }.getOrElse { JsonObject(emptyMap()) }
            val c = mgr.conn(e)
            val (sessionId, req) = when (e.kind) {
                "telegram" -> {
                    val chat = p.str("chat") ?: continue
                    val msg = GatewayMessage("telegram", e.name, chat, chat, p.str("from"), p.str("text").orEmpty(), ev.at, trusted = true)
                    val sid = sessionFor(c.config["session.$chat"], "💬 Telegram · ${e.name}")
                    if (c.config["session.$chat"] != sid) mgr.setConfig(e.connectionId, mapOf("session.$chat" to sid))
                    replyTo[sid] = e.name to chat
                    sid to msg.request(sid, msg.text, null)
                }
                "webhook_in" -> {
                    val headers = (p["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                    val body = p.str("body").orEmpty()
                    val msg = GatewayMessage("webhook", e.name, e.name, null, headers["User-Agent"], body, ev.at, trusted = false)
                    val sid = sessionFor(c.config["session"], "🔗 Webhook · ${e.name}")
                    if (c.config["session"] != sid) mgr.setConfig(e.connectionId, mapOf("session" to sid))
                    // Third-party content: enveloped as data by the orchestrator, and passages addressing the assistant removed here.
                    val described = headers.entries.joinToString("\n") { "${it.key} : ${it.value}" } + "\n\n" + io.github.artisanguillonrenov.cortana.core.browser.InjectionGuard.scrubAny(body.take(8000)).text
                    sid to msg.request(sid, c.cfg("objective")!!, described)
                }
                else -> continue
            }
            if (!submit(sessionId, req)) break
            dao.upsertEvent(ev.copy(outcome = "processed", taskId = req.requestId, payload = null))
            n++
        }
        n
    }

    /** Task end on a messaging session: the answer goes back to the chat through the outbox. */
    suspend fun onTaskEnd(sessionId: String, text: String?, requestId: String?) {
        // After a restart the binding is found again in the connection's configuration.
        val (connection, chat) = replyTo[sessionId] ?: dao.all().filter { it.kind == "telegram" }.firstNotNullOfOrNull { e ->
            mgr.configOf(e).entries.firstOrNull { it.key.startsWith("session.") && it.value == sessionId }?.let { e.name to it.key.removePrefix("session.") }
        } ?: return
        val body = text?.takeIf { it.isNotBlank() } ?: "Je n'ai pas de réponse à donner (tâche en attente ou interrompue ; voir la tablette)."
        outbox.enqueue(KIND_TELEGRAM, buildJsonObject { put("connection", connection); put("chat", chat); put("text", body) }, "tg-reply:${requestId ?: Ids.new()}")
        outbox.drain()
    }

    /** Background loops while Cortana runs: Telegram long polling, webhook collection, dispatch. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            val running = HashMap<String, kotlinx.coroutines.Job>()
            while (isActive) {
                val tg = dao.all().filter { it.kind == "telegram" && it.state == "active" }.map { it.name }.toSet()
                (running.keys - tg).forEach { running.remove(it)?.cancel() }
                (tg - running.keys).forEach { name ->
                    running[name] = scope.launch {
                        var backoff = 5_000L
                        while (isActive) {
                            try { pollTelegram(name, 30); dispatch(); backoff = 5_000L }
                            catch (e: CancellationException) { throw e }
                            catch (e: ConnectionException) { CLog.w("telegram $name: ${e.message}"); delay(backoff); backoff = (backoff * 2).coerceAtMost(300_000) }
                            catch (e: Exception) { CLog.w("telegram $name", e); delay(backoff); backoff = (backoff * 2).coerceAtMost(300_000) }
                        }
                    }
                }
                runCatching { pollHooks(); dispatch() }.onFailure { if (it is CancellationException) throw it }
                delay(60_000)
            }
        }
    }

    companion object { const val KIND_TELEGRAM = "telegram.send" }
}
