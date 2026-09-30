package io.github.artisanguillonrenov.cortana.core.observability

import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One finished span. Attribute names follow OpenTelemetry GenAI conventions where they exist. */
@Serializable
data class SpanRecord(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val taskId: String?,
    val startMs: Long,
    val endMs: Long,
    /** ok | error | cancelled */
    val status: String,
    val errorType: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    val durationMs: Long get() = endMs - startMs
}

class Span internal constructor(val name: String, val taskId: String?, val traceId: String, val parentSpanId: String?) {
    val spanId: String = Ids.new().replace("-", "").take(16)
    val startMs: Long = System.currentTimeMillis()
    internal val attributes = ConcurrentHashMap<String, String>()
    @Volatile internal var errorType: String? = null

    /**
     * Values are redacted and bounded; content-bearing keys (prompts, completions, arguments,
     * bodies…) are dropped: a span says what happened, never what was said (doc 06 §15).
     */
    fun attr(key: String, value: String) {
        if (Tracer.isContentKey(key)) return
        attributes[key] = Redactor.redact(value).take(300)
    }

    fun error(type: String) {
        errorType = type
    }
}

/** The span running in the current coroutine: spans started inside it become its children. */
class SpanContext(val span: Span) : AbstractCoroutineContextElement(SpanContext) {
    companion object Key : CoroutineContext.Key<SpanContext>
}

interface SpanSink {
    fun onSpan(span: SpanRecord)
}

/**
 * Structured tracing (doc 06 §15): Task → step / plan / verify → model (gen_ai.*) / tool spans.
 * Parents follow the coroutine (a span started inside another is its child); the top spans of a
 * task hang under the task's root span, recorded when the task ends. Works fully offline; sinks
 * (persistence, OTLP export) are attached by the container.
 */
class Tracer {
    private val _recent = MutableStateFlow<List<SpanRecord>>(emptyList())
    val recent: StateFlow<List<SpanRecord>> = _recent
    private val sinks = CopyOnWriteArrayList<SpanSink>()
    private val taskTrace = ConcurrentHashMap<String, String>()

    fun addSink(s: SpanSink) {
        sinks += s
    }

    fun bindTrace(taskId: String, traceId: String) {
        taskTrace[taskId] = traceId
    }

    fun traceOf(taskId: String?): String = taskId?.let { taskTrace[it] } ?: ("trace-" + (taskId ?: Ids.new()))

    suspend fun <T> span(name: String, taskId: String?, attrs: Map<String, String> = emptyMap(), parent: Span? = null, block: suspend (Span) -> T): T {
        val outer = parent ?: currentCoroutineContext()[SpanContext]?.span
        // A span of another task (e.g. a specialist's child task) starts its own tree.
        val inherited = outer?.takeIf { taskId == null || it.taskId == taskId }
        val tid = taskId ?: inherited?.taskId
        val span = Span(name, tid, inherited?.traceId ?: traceOf(tid), inherited?.spanId ?: tid?.let(::rootSpanId))
        attrs.forEach { (k, v) -> span.attr(k, v) }
        var status = "ok"
        try {
            return withContext(SpanContext(span)) { block(span) }
        } catch (e: CancellationException) {
            status = "cancelled"
            throw e
        } catch (t: Throwable) {
            status = "error"
            if (span.errorType == null) span.error(t.javaClass.simpleName)
            throw t
        } finally {
            if (span.errorType != null && status == "ok") status = "error"
            record(
                SpanRecord(span.traceId, span.spanId, span.parentSpanId, name, tid, span.startMs, System.currentTimeMillis(), status, span.errorType, span.attributes.toMap())
            )
        }
    }

    /** The root span of a finished task: its whole duration, outcome and counters. */
    fun recordTask(t: TaskEntity) {
        val code = t.terminationReason?.substringBefore(':')?.trim()
        val status = when (t.state) { "completed" -> "ok"; "cancelled", "halted" -> "cancelled"; else -> "error" }
        val counters = runCatching { io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(t.countersJson) as kotlinx.serialization.json.JsonObject }.getOrNull()
        val attrs = buildMap {
            put("cortana.task.state", t.state); put("cortana.task.mode", t.mode); put("cortana.task.source", t.source)
            put("cortana.task.tainted", t.tainted.toString())
            counters?.forEach { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { put("cortana.task.$k", it.take(40)) } }
        }
        record(SpanRecord(t.traceId.ifEmpty { traceOf(t.id) }, rootSpanId(t.id), null, "task", t.id, t.createdAt, t.endedAt ?: System.currentTimeMillis(), status,
            if (status == "ok") null else code, attrs))
    }

    fun record(r: SpanRecord) {
        _recent.value = (_recent.value + r).takeLast(500)
        sinks.forEach { runCatching { it.onSpan(r) } }
    }

    companion object {
        /** Deterministic id of a task's root span, so spans can point at it before it is recorded. */
        fun rootSpanId(taskId: String): String = Hash.sha256("task-root:$taskId").take(16)

        private val CONTENT = Regex("(^|[._])(prompt|prompts|completion|content|messages?|input|output|arguments|args|body|text|objective|query|reply)$")

        fun isContentKey(key: String) = CONTENT.containsMatchIn(key.lowercase())
    }
}
