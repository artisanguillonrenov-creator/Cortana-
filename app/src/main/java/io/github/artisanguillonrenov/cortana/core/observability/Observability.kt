package io.github.artisanguillonrenov.cortana.core.observability

import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.memory.SpanEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.secrets.SecretStore
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Persists finished spans off the calling thread, in batches (bounded queue: oldest dropped under pressure). */
class SpanStore(private val db: CortanaDatabase, scope: CoroutineScope) : SpanSink {
    private val queue = ConcurrentLinkedQueue<SpanRecord>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()

    init {
        scope.launch { for (x in signal) runCatching { flush() }.onFailure { CLog.w("span store flush failed", it) } }
    }

    override fun onSpan(span: SpanRecord) {
        queue.add(span)
        while (queue.size > MAX_QUEUE) queue.poll()
        signal.trySend(Unit)
    }

    suspend fun flush() = lock.withLock {
        val batch = generateSequence { queue.poll() }.toList()
        if (batch.isNotEmpty()) db.spans().insertAll(batch.map(::entity))
    }

    private fun entity(r: SpanRecord) = SpanEntity(r.spanId, r.traceId, r.parentSpanId, r.name, r.taskId, r.startMs, r.endMs, r.status, r.errorType,
        AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), r.attributes))

    companion object {
        const val MAX_QUEUE = 5_000
        fun record(e: SpanEntity) = SpanRecord(e.traceId, e.spanId, e.parentSpanId, e.name, e.taskId, e.startMs, e.endMs, e.status, e.errorType,
            runCatching { AppJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), e.attributesJson) }.getOrDefault(emptyMap()))
    }
}

/** A span with its children, for the local viewer. */
data class TraceNode(val span: SpanRecord, val depth: Int, val children: List<TraceNode>) {
    fun flatten(): List<TraceNode> = listOf(this) + children.flatMap { it.flatten() }
}

data class LatencyStat(val count: Int, val errors: Int, val p50Ms: Long, val p95Ms: Long)
data class ModelStat(val model: String, val stat: LatencyStat, val inputTokens: Long, val outputTokens: Long)
data class MetricsSummary(
    val windowMs: Long,
    val tasksByState: Map<String, Int>,
    val taskDuration: LatencyStat,
    val tools: Map<String, LatencyStat>,
    val models: List<ModelStat>,
    val verification: LatencyStat,
    val costUsd: Double,
    val spans: Int,
    /** Cognitive Council metrics (doc 06 §6.10), null when unavailable. */
    val council: io.github.artisanguillonrenov.cortana.core.council.CouncilMetrics? = null,
) {
    fun text(): String = buildString {
        val h = windowMs / 3_600_000
        appendLine("Sur ${if (h >= 24) "${h / 24} jour(s)" else "$h h"} :")
        appendLine("- Tâches : " + (tasksByState.entries.joinToString { "${it.value} ${it.key}" }.ifEmpty { "aucune" }) +
            if (taskDuration.count > 0) " · durée médiane ${taskDuration.p50Ms / 1000} s, p95 ${taskDuration.p95Ms / 1000} s" else "")
        models.forEach { m -> appendLine("- Modèle ${m.model} : ${m.stat.count} appel(s), ${m.stat.errors} erreur(s), médiane ${m.stat.p50Ms} ms, p95 ${m.stat.p95Ms} ms, ${m.inputTokens}/${m.outputTokens} jetons") }
        tools.entries.sortedByDescending { it.value.count }.take(15).forEach { (k, v) -> appendLine("- Outil $k : ${v.count} appel(s), ${v.errors} erreur(s), médiane ${v.p50Ms} ms, p95 ${v.p95Ms} ms") }
        if (verification.count > 0) appendLine("- Vérifications : ${verification.count}, ${verification.errors} échec(s)")
        council?.takeIf { it.runsTotal > 0 }?.let { appendLine(it.text()) }
        append("- Coût : ${"%.4f".format(costUsd)} $ · $spans span(s)")
    }
}

/**
 * Observability (phase 28): the local trace viewer's queries, metrics computed from spans, tasks
 * and usage, retention, and the optional OTLP/HTTP export to the owner's collector (off by default;
 * https or local network only; content-free, redacted attributes).
 */
class ObservabilityService(
    private val db: CortanaDatabase,
    private val store: SpanStore,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val audit: AuditLog,
    http: OkHttpClient,
    private val serviceVersion: String,
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
    private val exportLock = Mutex()
    @Volatile var lastExport: String? = null
        private set
    /** Resolves the collector header from its vault handle (a seam for tests without AndroidKeyStore). */
    @Volatile var headerOf: (String) -> String? = { h -> secrets.get(h) }

    /** The span tree of [taskId] (root = the task; spans whose parent is unknown are shown at the top). */
    suspend fun trace(taskId: String): List<TraceNode> {
        store.flush()
        val spans = db.spans().forTask(taskId).map(SpanStore::record)
        val ids = spans.map { it.spanId }.toSet()
        val byParent = spans.groupBy { it.parentSpanId?.takeIf { p -> p in ids } }
        fun build(s: SpanRecord, depth: Int): TraceNode = TraceNode(s, depth, byParent[s.spanId].orEmpty().sortedBy { it.startMs }.map { build(it, depth + 1) })
        return byParent[null].orEmpty().sortedWith(compareBy({ it.name != "task" }, { it.startMs })).map { build(it, 0) }
    }

    /** Full task id from a prefix (recent tasks). */
    suspend fun resolveTask(prefix: String): String? = db.tasks().get(prefix)?.id ?: db.tasks().since(0, 5000).firstOrNull { it.id.startsWith(prefix) }?.id

    suspend fun metrics(windowMs: Long = 24 * 3_600_000L, now: Long = System.currentTimeMillis()): MetricsSummary {
        store.flush()
        val since = now - windowMs
        val spans = db.spans().since(since).map(SpanStore::record)
        val tasks = db.tasks().since(since)
        fun stat(xs: List<SpanRecord>): LatencyStat {
            val d = xs.map { it.durationMs }.sorted()
            fun pct(p: Double) = if (d.isEmpty()) 0L else d[((d.size - 1) * p).toInt()]
            return LatencyStat(xs.size, xs.count { it.status == "error" }, pct(0.5), pct(0.95))
        }
        val terminal = tasks.filter { it.endedAt != null }
        val durations = terminal.map { SpanRecord("", "", null, "task", it.id, it.createdAt, it.endedAt!!, if (it.state == "completed") "ok" else "error") }
        val models = spans.filter { it.name.startsWith("gen_ai.") }.groupBy { it.attributes["gen_ai.request.model"] ?: "?" }.map { (m, xs) ->
            ModelStat(m, stat(xs), xs.sumOf { it.attributes["gen_ai.usage.input_tokens"]?.toLongOrNull() ?: 0L }, xs.sumOf { it.attributes["gen_ai.usage.output_tokens"]?.toLongOrNull() ?: 0L })
        }.sortedByDescending { it.stat.count }
        return MetricsSummary(
            windowMs, tasks.groupingBy { it.state }.eachCount(), stat(durations),
            spans.filter { it.name == "tool.execute" }.groupBy { it.attributes["tool"] ?: "?" }.mapValues { stat(it.value) },
            models, stat(spans.filter { it.name == "task.verify" }), db.usage().costSince(since), spans.size,
            runCatching { io.github.artisanguillonrenov.cortana.core.council.CouncilStore.aggregate(db.council().runsSince(since)) }.getOrNull(),
        )
    }

    /** Keeps [days] of spans and at most [maxRows]. */
    suspend fun retention(now: Long = System.currentTimeMillis(), days: Int = 7, maxRows: Int = 50_000): Int {
        store.flush()
        var n = db.spans().purge(now - days * 86_400_000L)
        val extra = db.spans().count() - maxRows
        if (extra > 0) n += db.spans().deleteOldest(extra)
        return n
    }

    /** Sends not-yet-exported spans to the owner's OTLP/HTTP collector. Returns how many were accepted. */
    suspend fun export(batch: Int = 500): Int = exportLock.withLock {
        val s = settings.current
        if (!s.otlpEnabled) return 0
        val base = s.otlpEndpoint?.trimEnd('/') ?: return 0
        checkEndpoint(base)
        store.flush()
        var sent = 0
        while (true) {
            val rows = db.spans().unexported(batch)
            if (rows.isEmpty()) break
            val body = Otlp.encode(rows.map(SpanStore::record), serviceVersion).toString()
            val req = Request.Builder().url("$base/v1/traces").post(body.toRequestBody("application/json".toMediaType()))
            s.otlpHeaderHandle?.let { h -> headerOf(h) }?.let { raw ->
                val name = raw.substringBefore(':').trim(); val value = raw.substringAfter(':', "").trim()
                require(name.matches(Regex("[A-Za-z0-9-]{1,64}")) && value.isNotEmpty()) { "en-tête OTLP invalide (attendu « Nom: valeur »)" }
                req.header(name, value)
            }
            val code = withContext(Dispatchers.IO) { runCatching { client.newCall(req.build()).execute().use { it.code } }.getOrElse { e -> lastExport = "échec : ${e.message}"; -1 } }
            if (code !in 200..299) {
                if (code != -1) lastExport = "refusé par le collecteur (HTTP $code)"
                audit.record("system", "observability.export", Redactor.redact(base), "error", """{"code":$code}""")
                break
            }
            db.spans().markExported(rows.map { it.spanId })
            sent += rows.size
            lastExport = "$sent span(s) exporté(s)"
            if (rows.size < batch) break
        }
        sent
    }

    companion object {
        /** https, or plain http only to this device / the local network (like OAuth and MCP). */
        fun checkEndpoint(url: String) {
            val u = runCatching { URI(url) }.getOrNull() ?: throw IllegalArgumentException("adresse OTLP invalide")
            val host = u.host?.lowercase()?.trim('[', ']') ?: throw IllegalArgumentException("adresse OTLP invalide")
            if (u.userInfo != null) throw IllegalArgumentException("identifiants interdits dans l'adresse ; utilisez l'en-tête du coffre")
            when (u.scheme?.lowercase()) {
                "https" -> Unit
                "http" -> if (!local(host)) throw IllegalArgumentException("http n'est permis que sur le réseau local ; utilisez https")
                else -> throw IllegalArgumentException("adresse OTLP : https attendu")
            }
        }

        private fun local(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
            if (!(host.matches(Regex("[0-9.]+")) || host.contains(':'))) return false
            val a = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
        }
    }
}

/** OTLP/HTTP JSON encoding (opentelemetry-proto `ExportTraceServiceRequest`). */
object Otlp {
    fun hexId(raw: String, len: Int): String {
        val clean = raw.removePrefix("trace-").replace("-", "").lowercase()
        return if (clean.length >= len && clean.take(len).all { it in "0123456789abcdef" }) clean.take(len) else Hash.sha256(raw).take(len)
    }

    fun encode(spans: List<SpanRecord>, serviceVersion: String): JsonObject = buildJsonObject {
        putJsonArray("resourceSpans") {
            add(buildJsonObject {
                putJsonObject("resource") {
                    putJsonArray("attributes") {
                        add(kv("service.name", "cortana")); add(kv("service.version", serviceVersion)); add(kv("telemetry.sdk.language", "kotlin"))
                    }
                }
                putJsonArray("scopeSpans") {
                    add(buildJsonObject {
                        putJsonObject("scope") { put("name", "io.github.artisanguillonrenov.cortana"); put("version", serviceVersion) }
                        put("spans", JsonArray(spans.map(::span)))
                    })
                }
            })
        }
    }

    private fun span(s: SpanRecord) = buildJsonObject {
        put("traceId", hexId(s.traceId, 32))
        put("spanId", hexId(s.spanId, 16))
        s.parentSpanId?.let { put("parentSpanId", hexId(it, 16)) }
        put("name", s.name)
        put("kind", if (s.name.startsWith("gen_ai.") || s.name == "tool.execute") 3 else 1) // CLIENT / INTERNAL
        put("startTimeUnixNano", (s.startMs * 1_000_000).toString())
        put("endTimeUnixNano", (s.endMs * 1_000_000).toString())
        put("attributes", buildJsonArray {
            s.taskId?.let { add(kv("cortana.task.id", it)) }
            s.attributes.filterKeys { !Tracer.isContentKey(it) }.forEach { (k, v) -> add(kv(k, Redactor.redact(v))) }
            s.errorType?.let { add(kv("error.type", it)) }
        })
        putJsonObject("status") {
            put("code", when (s.status) { "ok" -> 1; "error" -> 2; else -> 0 })
            if (s.status == "cancelled") put("message", "cancelled")
        }
    }

    private fun kv(k: String, v: String) = buildJsonObject { put("key", k); putJsonObject("value") { put("stringValue", v) } }
}
