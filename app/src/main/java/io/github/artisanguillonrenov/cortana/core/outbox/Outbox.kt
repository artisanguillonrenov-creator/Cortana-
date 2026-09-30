package io.github.artisanguillonrenov.cortana.core.outbox

import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.OutboxEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import io.github.artisanguillonrenov.cortana.util.truncateBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import io.github.artisanguillonrenov.cortana.util.AppJson
import java.util.concurrent.ConcurrentHashMap

/** Thrown by a handler when delivery is possible later (permission missing, device offline…). */
class OutboxRetryException(message: String) : Exception(message)

/**
 * Durable effects (doc 04 §7, doc 06 §10) — the single owner of "deliver this later, exactly once".
 * An effect is recorded atomically with a dedupe key *before* any delivery attempt; delivery is
 * retried with exponential backoff and never duplicated. A crash between the tool call and its
 * ledger commit is reconciled by [isRecorded]: a recorded effect is durable, an absent one never ran.
 */
class Outbox(private val db: CortanaDatabase, private val audit: AuditLog) {
    private val handlers = ConcurrentHashMap<String, suspend (JsonObject) -> Unit>()
    private val mutex = Mutex()

    fun register(kind: String, handler: suspend (JsonObject) -> Unit) {
        check(handlers.putIfAbsent(kind, handler) == null) { "outbox handler already registered for $kind" }
    }

    /** Records the effect once per [dedupeKey]; returns the stored row (existing one on duplicate). */
    suspend fun enqueue(kind: String, payload: JsonObject, dedupeKey: String): OutboxEntity {
        require(handlers.containsKey(kind)) { "no outbox handler for $kind" }
        val now = System.currentTimeMillis()
        val row = OutboxEntity(Ids.new(), kind, payload.toString(), dedupeKey, STATUS_PENDING, 0, now, now)
        db.runtime().enqueueOutbox(row)
        return db.runtime().outboxByKey(dedupeKey)!!
    }

    suspend fun isRecorded(dedupeKey: String): Boolean = db.runtime().outboxByKey(dedupeKey) != null

    suspend fun status(dedupeKey: String): String? = db.runtime().outboxByKey(dedupeKey)?.status

    /** Delivers every due effect; safe to call concurrently and repeatedly. Returns delivered count. */
    suspend fun drain(now: Long = System.currentTimeMillis()): Int = mutex.withLock {
        var delivered = 0
        for (row in db.runtime().dueOutbox(now)) {
            val handler = handlers[row.kind]
            if (handler == null) {
                fail(row, "aucun gestionnaire pour ${row.kind}", now)
                continue
            }
            try {
                handler(AppJson.parseToJsonElement(row.payloadJson).jsonObject)
                db.runtime().upsertOutbox(row.copy(status = STATUS_DELIVERED, attempts = row.attempts + 1, deliveredAt = System.currentTimeMillis(), lastError = null))
                delivered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val attempts = row.attempts + 1
                val msg = Redactor.redact(e.message ?: e.javaClass.simpleName).truncateBytes(300)
                if (e !is OutboxRetryException || attempts >= MAX_ATTEMPTS) {
                    fail(row.copy(attempts = attempts), msg, now)
                } else {
                    db.runtime().upsertOutbox(row.copy(attempts = attempts, nextAttemptAt = now + backoff(attempts), lastError = msg))
                }
            }
        }
        delivered
    }

    private suspend fun fail(row: OutboxEntity, reason: String, now: Long) {
        CLog.w("outbox ${row.kind} failed: $reason")
        db.runtime().upsertOutbox(row.copy(status = STATUS_FAILED, lastError = reason, nextAttemptAt = now))
        audit.record("system", "outbox.${row.kind}", null, "failed", """{"attempts":${row.attempts}}""")
    }

    companion object {
        const val KIND_NOTIFY_OWNER = "notify.owner"
        const val STATUS_PENDING = "pending"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_FAILED = "failed"
        const val MAX_ATTEMPTS = 6
        fun backoff(attempts: Int): Long = 30_000L shl (attempts - 1).coerceIn(0, 10)
    }
}
