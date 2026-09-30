package io.github.artisanguillonrenov.cortana.core.memory

import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MemoryTypes {
    const val PROFILE = "profile"
    const val PREFERENCE = "preference"
    const val SEMANTIC = "semantic"
    const val EPISODIC = "episodic"
    val all = listOf(PROFILE, PREFERENCE, SEMANTIC, EPISODIC)
}

/**
 * Memory store (§11): supersession instead of overwrite, confirmation for extracted facts,
 * pending for tainted tasks, nothing for incognito sessions.
 */
class MemoryRepository(private val db: CortanaDatabase) {
    private val dao = db.memories()

    /** Derived vector index; optional — retrieval falls back to FTS when absent or not ready. */
    var indexer: MemoryIndexer? = null

    /** Owner-facing facts (profile, preference, semantic). Episodic task records are listed separately. */
    fun observe(status: String): Flow<List<MemoryEntity>> = dao.observeByStatus(status).map { l -> l.filter { it.type != MemoryTypes.EPISODIC } }
    fun observeEpisodes(): Flow<List<MemoryEntity>> = dao.observeByStatus(MemoryStatus.ACTIVE).map { l -> l.filter { it.type == MemoryTypes.EPISODIC } }
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    data class SaveResult(val memory: MemoryEntity, val duplicate: Boolean)

    suspend fun save(
        text: String,
        type: String,
        status: String,
        source: String,
        sessionId: String? = null,
        taskId: String? = null,
        importance: Int = 3,
        confidence: Double = 1.0,
        supersedesId: String? = null,
    ): SaveResult {
        val clean = Redactor.redact(text.trim())
        dao.findSameText(clean)?.let { existing ->
            // Same fact already known: an explicit save confirms a pending one.
            if (existing.status == MemoryStatus.PENDING && status == MemoryStatus.ACTIVE) {
                val upd = existing.copy(status = MemoryStatus.ACTIVE, updatedAt = System.currentTimeMillis())
                dao.upsert(upd)
                indexer?.request()
                return SaveResult(upd, true)
            }
            return SaveResult(existing, true)
        }
        val now = System.currentTimeMillis()
        val provenance = buildJsonObject {
            put("source", source)
            sessionId?.let { put("sessionId", it) }
            taskId?.let { put("taskId", it) }
            put("at", now)
        }.toString()
        val m = MemoryEntity(
            id = Ids.new(),
            type = if (type in MemoryTypes.all) type else MemoryTypes.SEMANTIC,
            text = clean,
            confidence = confidence,
            importance = importance.coerceIn(1, 5),
            status = status,
            supersedesId = supersedesId,
            provenanceJson = provenance,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(m)
        if (supersedesId != null) dao.upsertEdge(MemoryEdgeEntity(m.id, supersedesId, MemoryIndexer.REL_SUPERSEDES, 1.0, null, now))
        if (supersedesId != null && status == MemoryStatus.ACTIVE) supersede(supersedesId)
        indexer?.request()
        return SaveResult(m, false)
    }

    suspend fun confirm(id: String) {
        val m = dao.get(id) ?: return
        dao.upsert(m.copy(status = MemoryStatus.ACTIVE, updatedAt = System.currentTimeMillis()))
        m.supersedesId?.let { supersede(it) }
        indexer?.request()
    }

    suspend fun reject(id: String) = forget(id)

    suspend fun forget(id: String) {
        val m = dao.get(id) ?: return
        dao.upsert(m.copy(status = MemoryStatus.DELETED, updatedAt = System.currentTimeMillis()))
        indexer?.request()
    }

    /** Editing creates a new row that supersedes the old one (old kept, hidden from retrieval). */
    suspend fun edit(id: String, newText: String): MemoryEntity? {
        val old = dao.get(id) ?: return null
        val res = save(newText, old.type, MemoryStatus.ACTIVE, "owner_edit", importance = old.importance, supersedesId = old.id)
        return res.memory
    }

    private suspend fun supersede(id: String) {
        val old = dao.get(id) ?: return
        if (old.status == MemoryStatus.ACTIVE || old.status == MemoryStatus.PENDING) {
            dao.upsert(old.copy(status = MemoryStatus.SUPERSEDED, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun get(id: String): MemoryEntity? = dao.get(id)

    suspend fun search(query: String, limit: Int = 10): List<MemoryEntity> {
        val fts = FtsQuery.build(query) ?: return emptyList()
        val hits = runCatching { dao.search(fts, limit) }.getOrElse { emptyList() }
        return hits.ifEmpty { runCatching { dao.searchLike(query.trim(), limit) }.getOrElse { emptyList() } }
    }

    /** Retrieval for the context engine: profile/preferences first, then hybrid-ranked facts. */
    suspend fun retrieveForContext(query: String, limit: Int = 12): List<MemoryEntity> {
        val base = dao.profileAndPreferences(6)
        return (base + retrieve(query, limit).map { it.memory }).distinctBy { it.id }.take(limit)
    }

    data class Scored(val memory: MemoryEntity, val score: Double, val signals: Map<String, Double>)

    /**
     * Hybrid ranking (doc 04 §11): lexical FTS rank, semantic cosine (if the derived index is ready),
     * recency, importance, type, provenance, plus one hop of graph relations. FTS alone when the
     * vector index is unavailable (offline remote embedder, index not built yet).
     */
    suspend fun retrieve(query: String, limit: Int = 12, now: Long = System.currentTimeMillis()): List<Scored> {
        val fts = FtsQuery.build(query)?.let { q ->
            // OR semantics so partial overlaps still match
            runCatching { dao.search(q.split(" ").joinToString(" OR "), 30) }.getOrElse { emptyList() }
        } ?: emptyList()
        val lexical = fts.mapIndexed { i, m -> m.id to 1.0 / (1 + i * 0.35) }.toMap()
        val semantic = runCatching { indexer?.similar(query, 30) }.getOrNull().orEmpty().filterValues { it >= MIN_COSINE }
        val ids = (lexical.keys + semantic.keys)
        if (ids.isEmpty()) return emptyList()
        val byId = (fts + dao.byIds((ids - lexical.keys).toList())).filter { it.status == MemoryStatus.ACTIVE }.associateBy { it.id }
        fun score(m: MemoryEntity, graph: Double = 0.0): Scored {
            val ageDays = (now - m.updatedAt).coerceAtLeast(0) / 86_400_000.0
            val signals = linkedMapOf(
                "fts" to (lexical[m.id] ?: 0.0), "vector" to (semantic[m.id] ?: 0.0), "recency" to Math.exp(-ageDays / 60.0),
                "importance" to m.importance / 5.0, "graph" to graph,
            )
            val type = when (m.type) { MemoryTypes.PROFILE, MemoryTypes.PREFERENCE -> 0.05; MemoryTypes.EPISODIC -> -0.05; else -> 0.0 }
            val explicit = if (m.provenanceJson.contains("\"explicit\"") || m.provenanceJson.contains("\"owner")) 0.03 else 0.0
            val total = 0.45 * signals["vector"]!! + 0.35 * signals["fts"]!! + 0.08 * signals["recency"]!! + 0.07 * signals["importance"]!! + 0.15 * graph + type + explicit
            return Scored(m, total, signals)
        }
        val ranked = byId.values.map { score(it) }.sortedByDescending { it.score }.toMutableList()
        // One hop of relations from the three best hits (same person/place/number, supersession chain).
        val top = ranked.take(3)
        if (top.isNotEmpty()) {
            val edges = dao.edgesOf(top.map { it.memory.id }).filter { it.relation == MemoryIndexer.REL_SAME_ENTITY }
            val neighbours = edges.flatMap { listOf(it.fromId, it.toId) }.toSet() - ranked.map { it.memory.id }.toSet()
            if (neighbours.isNotEmpty()) {
                dao.byIds(neighbours.toList()).filter { it.status == MemoryStatus.ACTIVE }.forEach { n -> ranked += score(n, graph = top.first().score) }
            }
        }
        return ranked.sortedByDescending { it.score }.take(limit)
    }

    // ---------------------------------------------------------------- retention, export, erase (doc 04 §10, doc 06 §12)

    /** Purges expired episodic records, unconfirmed facts and old deleted/superseded rows (with their vectors and edges). */
    suspend fun applyRetention(now: Long, episodicDays: Int, pendingDays: Int, deletedDays: Int = 30, supersededDays: Int = 365): Int {
        val day = 86_400_000L
        val purge: suspend () -> Int = {
            val ids = dao.expired(now - episodicDays * day, now - pendingDays * day, now - deletedDays * day, now - supersededDays * day)
            ids.chunked(500).forEach { chunk -> dao.deleteVectors(chunk); dao.deleteEdges(chunk); dao.hardDelete(chunk) }
            ids.size
        }
        return indexer?.exclusive(purge) ?: purge()
    }

    /** Owner export: every non-deleted memory with its provenance, as JSON. */
    suspend fun exportJson(): String = kotlinx.serialization.json.buildJsonObject {
        put("format", "cortana.memories")
        put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("memories", kotlinx.serialization.json.JsonArray(dao.allForExport().map { m ->
            buildJsonObject {
                put("id", m.id); put("type", m.type); put("text", m.text); put("status", m.status)
                put("importance", m.importance); put("confidence", m.confidence); put("sensitivity", m.sensitivity)
                m.supersedesId?.let { put("supersedes", it) }
                put("provenance", runCatching { io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(m.provenanceJson) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(m.provenanceJson) })
                put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
            }
        }))
    }.toString()

    /** Erases every memory, vector and relation (owner request). */
    suspend fun eraseAll() {
        val erase: suspend () -> Unit = { dao.deleteAllVectors(); dao.deleteAllEdges(); dao.hardDeleteAll() }
        indexer?.exclusive(erase) ?: erase()
    }

    suspend fun rebuildFts() = dao.rebuildFts()

    companion object {
        /** Below this cosine a vector-only match is noise (hashing and neural embeddings alike). */
        const val MIN_COSINE = 0.25
    }
}
