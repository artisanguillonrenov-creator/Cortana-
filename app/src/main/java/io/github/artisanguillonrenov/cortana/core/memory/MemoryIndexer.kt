package io.github.artisanguillonrenov.cortana.core.memory

import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains the derived vector index and entity relations of active memories (doc 04 §11).
 * Idempotent: [sync] embeds only what changed (text hash) for the current embedder fingerprint,
 * drops vectors of other fingerprints (controlled re-index) and of inactive memories. Any failure
 * leaves retrieval on the lexical (FTS) path.
 */
class MemoryIndexer(
    private val db: CortanaDatabase,
    private val scope: CoroutineScope,
    private val embedderSource: () -> Embedder,
) {
    data class Status(val fingerprint: String = "", val indexed: Int = 0, val total: Int = 0, val running: Boolean = false, val lastError: String? = null)

    private val dao get() = db.memories()
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    @Volatile private var cache: Pair<String, Map<String, FloatArray>>? = null
    private val queryCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 64
    }
    private var job: Job? = null
    @Volatile private var again = false

    /** Schedules a background [sync]; coalesces bursts of changes. */
    fun request() {
        synchronized(this) {
            if (job?.isActive == true) { again = true; return }
            job = scope.launch {
                do { again = false; runCatching { sync() }.onFailure { if (it is CancellationException) throw it } } while (again)
            }
        }
    }

    suspend fun sync(): Int = mutex.withLock {
        val embedder = embedderSource()
        val fp = embedder.fingerprint
        dao.deleteOtherVectors(fp)
        val active = dao.allActive()
        val existing = dao.vectors(fp).associateBy { it.memoryId }
        val activeIds = active.map { it.id }.toSet()
        (existing.keys - activeIds).takeIf { it.isNotEmpty() }?.let { dao.deleteVectors(it.toList()) }
        val todo = active.filter { m -> existing[m.id]?.textHash != Hash.sha256(m.text) }
        var done = active.size - todo.size
        // The previous error stays visible until a pass succeeds (a retry starting must not hide it).
        val previousError = _status.value.lastError.takeIf { _status.value.fingerprint == fp }
        _status.value = Status(fp, done, active.size, running = todo.isNotEmpty(), lastError = previousError)
        // Entities named by already-indexed memories, so a newcomer that mentions them is linked too
        // (edges must not depend on the order in which memories were indexed).
        val named = if (todo.isEmpty()) emptyMap() else active.filter { it.id in existing.keys }.flatMap { m -> entities(m.text).map { it to m.id } }
            .groupBy({ it.first }, { it.second })
        try {
            for (batch in todo.chunked(BATCH)) {
                val vectors = embedder.embed(batch.map { it.text })
                require(vectors.size == batch.size) { "embedder returned ${vectors.size} vectors for ${batch.size} texts" }
                val now = System.currentTimeMillis()
                // Embedding takes time: never write a vector for a memory forgotten or edited meanwhile.
                val stillActive = dao.byIds(batch.map { it.id }).filter { it.status == MemoryStatus.ACTIVE }.associateBy { it.id }
                batch.zip(vectors).filter { (m, _) -> stillActive[m.id]?.text == m.text }.forEach { (m, v) ->
                    dao.upsertVector(MemoryVectorEntity(m.id, fp, v.size, Vectors.toBytes(Vectors.normalize(v.copyOf())), Hash.sha256(m.text), now))
                    link(m, named)
                }
                done += batch.size
                _status.value = Status(fp, done, active.size, running = done < active.size, lastError = previousError)
            }
            _status.value = Status(fp, done, active.size, running = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CLog.w("memory indexing failed (${embedder.fingerprint})", e)
            _status.value = Status(fp, done, active.size, running = false, lastError = e.message ?: e.javaClass.simpleName)
        } finally {
            cache = null
        }
        todo.size
    }

    /** Cosine similarity of the query to indexed memories (current fingerprint), or empty if unavailable. */
    suspend fun similar(query: String, limit: Int): Map<String, Double> {
        val embedder = embedderSource()
        val index = index(embedder.fingerprint)
        if (index.isEmpty()) return emptyMap()
        val q = queryVector(embedder, query) ?: return emptyMap()
        return index.entries.asSequence()
            .map { it.key to Vectors.cosine(q, it.value) }
            .sortedByDescending { it.second }
            .take(limit)
            .toMap()
    }

    private suspend fun index(fp: String): Map<String, FloatArray> {
        cache?.takeIf { it.first == fp }?.let { return it.second }
        val map = dao.vectors(fp).associate { it.memoryId to Vectors.fromBytes(it.vector) }
        cache = fp to map
        return map
    }

    private suspend fun queryVector(embedder: Embedder, query: String): FloatArray? {
        val key = embedder.fingerprint + "|" + query
        synchronized(queryCache) { queryCache[key] }?.let { return it }
        val v = runCatching { embedder.embed(listOf(query)).firstOrNull() }.getOrNull() ?: return null
        synchronized(queryCache) { queryCache[key] = v }
        return v
    }

    /**
     * Relations: memories that mention the same proper noun or long number (optional graph, doc 04 §10),
     * in both directions — entities [m] names found in others, and entities others name found in [m].
     */
    private suspend fun link(m: MemoryEntity, named: Map<String, List<String>>) {
        val now = System.currentTimeMillis()
        for (entity in entities(m.text)) {
            dao.activeContaining(entity, m.id, 5).forEach { other ->
                dao.upsertEdge(MemoryEdgeEntity(m.id, other.id, REL_SAME_ENTITY, 1.0, entity, now))
            }
        }
        for ((entity, owners) in named) {
            if (!m.text.contains(entity, ignoreCase = true)) continue
            owners.filter { it != m.id }.take(5).forEach { other -> dao.upsertEdge(MemoryEdgeEntity(other, m.id, REL_SAME_ENTITY, 1.0, entity, now)) }
        }
    }

    fun invalidate() { cache = null }

    /** Runs [block] while no indexing pass can write (erase, retention). */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { try { block() } finally { cache = null } }

    companion object {
        const val BATCH = 32
        const val REL_SAME_ENTITY = "same_entity"
        const val REL_SUPERSEDES = "supersedes"
        private val properNoun = Regex("(?<=[\\p{L}\\p{N},;:] )\\p{Lu}[\\p{L}'-]{2,}")
        private val longNumber = Regex("\\b\\d{4,}\\b")

        fun entities(text: String): Set<String> =
            (properNoun.findAll(text).map { it.value.trimEnd('\'', '-') } + longNumber.findAll(text).map { it.value }).take(8).toSet()
    }
}
