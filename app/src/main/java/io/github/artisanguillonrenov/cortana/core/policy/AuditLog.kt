package io.github.artisanguillonrenov.cortana.core.policy

import io.github.artisanguillonrenov.cortana.core.memory.AuditDao
import io.github.artisanguillonrenov.cortana.core.memory.AuditEntity
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Hash-chained, redacted audit log for sensitive actions (§16). */
class AuditLog(private val dao: AuditDao) {
    private val mutex = Mutex()

    suspend fun record(actor: String, action: String, target: String? = null, outcome: String, meta: String = "{}") {
        try {
            mutex.withLock {
                val prev = dao.last()?.hash ?: GENESIS
                val now = System.currentTimeMillis()
                val id = Ids.new()
                val t = target?.let(Redactor::redact)
                val m = Redactor.redact(meta)
                val hash = Hash.sha256(listOf(prev, id, now.toString(), actor, action, t ?: "", outcome, m).joinToString("|"))
                dao.insert(AuditEntity(id = id, occurredAt = now, actor = actor, action = action, targetJson = t, outcome = outcome, metaJson = m, prevHash = prev, hash = hash))
            }
        } catch (t: Throwable) {
            CLog.e("audit write failed", t)
        }
    }

    fun observeRecent(limit: Int = 300): kotlinx.coroutines.flow.Flow<List<AuditEntity>> = dao.observeRecent(limit)

    /** Returns null when the chain is intact, otherwise the seq of the first broken row. */
    suspend fun verify(): Long? {
        var prev = GENESIS
        for (row in dao.allAscending()) {
            val expected = Hash.sha256(listOf(prev, row.id, row.occurredAt.toString(), row.actor, row.action, row.targetJson ?: "", row.outcome, row.metaJson).joinToString("|"))
            if (row.prevHash != prev || row.hash != expected) return row.seq
            prev = row.hash
        }
        return null
    }

    companion object {
        const val GENESIS = "genesis"
    }
}
