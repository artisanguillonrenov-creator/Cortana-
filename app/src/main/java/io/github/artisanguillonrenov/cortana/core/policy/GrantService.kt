package io.github.artisanguillonrenov.cortana.core.policy

import io.github.artisanguillonrenov.cortana.contracts.AuthorizationGrant
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.GrantEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Scoped authorization grants (§23.2, doc 06 §6): capability + optional target scope + exact
 * argument constraints + validity window + max uses + revocation. Grants never apply to L3 and
 * never to egress in a tainted task (enforced by [PolicyEngine]).
 */
class GrantService(private val db: CortanaDatabase, private val settings: SettingsRepository, private val audit: AuditLog) {
    private val dao get() = db.runtime()
    private val mutex = Mutex()

    fun observe(): Flow<List<GrantEntity>> = dao.observeGrants()

    suspend fun create(
        capability: String,
        scope: String? = null,
        constraints: Map<String, String> = emptyMap(),
        validForMs: Long? = null,
        maxUses: Int? = null,
        taskId: String? = null,
        scheduleId: String? = null,
        createdBy: String = "owner",
    ): GrantEntity {
        val now = System.currentTimeMillis()
        val g = GrantEntity(
            grantId = Ids.new(), capability = capability, scope = scope,
            constraintsJson = AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), constraints),
            taskId = taskId, scheduleId = scheduleId, validFrom = now, validUntil = validForMs?.let { now + it },
            maxUses = maxUses, createdBy = createdBy, createdAt = now,
        )
        dao.upsertGrant(g)
        audit.record(createdBy, "grant.create", capability + (scope?.let { " @ $it" } ?: ""), "ok", """{"maxUses":${maxUses ?: "null"}}""")
        return g
    }

    suspend fun revoke(grantId: String) {
        val g = dao.grant(grantId) ?: return
        dao.upsertGrant(g.copy(revoked = true))
        audit.record("owner", "grant.revoke", g.capability, "ok")
    }

    /** Finds a valid grant covering this exact call and consumes one use. Returns the grant id. */
    suspend fun consume(capability: String, destination: String?, args: JsonObject, taskId: String?, scheduleId: String?): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        val g = dao.grantsFor(capability).firstOrNull { g ->
            (g.validUntil == null || now <= g.validUntil) && now >= g.validFrom &&
                (g.maxUses == null || g.uses < g.maxUses) &&
                (g.scope == null || g.scope.equals(destination, ignoreCase = true)) &&
                (g.taskId == null || g.taskId == taskId) &&
                (g.scheduleId == null || g.scheduleId == scheduleId) &&
                constraintsMatch(g.constraintsJson, args)
        } ?: return@withLock null
        dao.upsertGrant(g.copy(uses = g.uses + 1))
        g.grantId
    }

    private fun constraintsMatch(json: String, args: JsonObject): Boolean {
        val c = runCatching { AppJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json) }.getOrDefault(emptyMap())
        return c.all { (k, v) -> (args[k] as? JsonPrimitive)?.contentOrNull == v }
    }

    /** One-time data migration of the v1 "grants" setting (capability list) into grant rows. */
    suspend fun migrateLegacySettingGrants() {
        val legacy = settings.current.grants
        if (legacy.isEmpty()) return
        for (cap in legacy) if (dao.grantsFor(cap).isEmpty()) create(cap, createdBy = "migration-v1")
        settings.update { it.copy(grants = emptyList()) }
    }

    fun toContract(g: GrantEntity): AuthorizationGrant = AuthorizationGrant(
        grantId = g.grantId, capability = g.capability, scope = g.scope,
        argumentConstraints = runCatching { AppJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), g.constraintsJson) }.getOrDefault(emptyMap()),
        taskId = g.taskId, sessionId = g.sessionId, scheduleId = g.scheduleId, validFrom = g.validFrom, validUntil = g.validUntil,
        maxUses = g.maxUses, uses = g.uses, revoked = g.revoked, createdBy = g.createdBy, createdAt = g.createdAt,
    )
}
