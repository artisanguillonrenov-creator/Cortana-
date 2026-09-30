package io.github.artisanguillonrenov.cortana.core.maintenance

import io.github.artisanguillonrenov.cortana.core.memory.MemoryIndexer
import io.github.artisanguillonrenov.cortana.core.memory.MemoryRepository
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.orchestrator.Orchestrator
import io.github.artisanguillonrenov.cortana.core.outbox.Outbox
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.GrantService
import io.github.artisanguillonrenov.cortana.core.scheduler.CortanaScheduler
import io.github.artisanguillonrenov.cortana.util.CLog

/**
 * Startup and periodic housekeeping, in one place: crash recovery, durable effects, schedules,
 * memory retention and the derived memory index. The scheduler only owns time (LAW-006).
 */
class Maintenance(
    private val grants: GrantService,
    private val orchestrator: Orchestrator,
    private val outbox: Outbox,
    private val scheduler: CortanaScheduler,
    private val memory: MemoryRepository,
    private val indexer: MemoryIndexer,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val workers: io.github.artisanguillonrenov.cortana.core.worker.WorkerService? = null,
    private val connections: io.github.artisanguillonrenov.cortana.core.connections.ConnectionManager? = null,
    private val improvements: io.github.artisanguillonrenov.cortana.core.improvement.ImprovementService? = null,
    private val observability: io.github.artisanguillonrenov.cortana.core.observability.ObservabilityService? = null,
    private val updates: io.github.artisanguillonrenov.cortana.core.update.UpdateService? = null,
    private val council: io.github.artisanguillonrenov.cortana.core.council.CouncilStore? = null,
) {
    @Volatile private var lastUpdateCheck = 0L

    suspend fun onStartup() {
        step("grants") { grants.migrateLegacySettingGrants() }
        step("council") { council?.recoverInterrupted() } // councils are never resumed mid-run (doc 08 §8.8)
        step("recovery") { orchestrator.recoverOnStartup() }
        step("runs") { scheduler.recoverRuns() }
        step("outbox") { outbox.drain() }
        step("retention") { retention() }
        step("index") { indexer.request() } // backfill / re-index after an embedder change
        step("workers") { workers?.registerAll() }
        step("schedules") { scheduler.rearmAll(catchUp = true) }
        step("periodic") { scheduler.schedulePeriodicMaintenance() }
    }

    suspend fun periodic() {
        step("schedules") { scheduler.rearmAll(catchUp = true) }
        step("runs") { scheduler.nudge() }
        step("outbox") { outbox.drain() }
        step("retention") { retention() }
        step("index") { indexer.sync() }
        step("workers") { workers?.all()?.filter { !it.revoked }?.forEach { workers.refresh(it.workerId) } }
        step("connections") { connections?.checkDue() }
        step("improvements") { improvements?.analyze() }
        step("updates") { checkUpdates() }
        step("traces") { observability?.retention(days = settings.current.spanRetentionDays.coerceIn(1, 90)); observability?.export() }
    }

    /** Daily, when the owner enabled it: tells the owner a verified update exists (never downloads or installs). */
    suspend fun checkUpdates(now: Long = System.currentTimeMillis()) {
        val u = updates ?: return
        if (!settings.current.updateAutoCheck || settings.current.updateManifestUrl == null || now - lastUpdateCheck < 20 * 3_600_000L) return
        lastUpdateCheck = now
        val st = u.check()
        if (st is io.github.artisanguillonrenov.cortana.core.update.UpdateState.Available) {
            outbox.enqueue(Outbox.KIND_NOTIFY_OWNER, kotlinx.serialization.json.buildJsonObject {
                put("title", kotlinx.serialization.json.JsonPrimitive("Mise à jour disponible"))
                put("message", kotlinx.serialization.json.JsonPrimitive("Cortana ${st.manifest.versionName} est disponible et signée. Réglages → Mises à jour pour la préparer."))
            }, "update:${st.manifest.versionCode}")
            outbox.drain()
        }
    }

    suspend fun retention(now: Long = System.currentTimeMillis()): Int {
        val s = settings.current
        val n = memory.applyRetention(now, s.episodicRetentionDays, s.pendingRetentionDays)
        if (n > 0) audit.record("system", "memory.retention", null, "ok", """{"purged":$n}""")
        return n
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { CLog.e("maintenance step $name failed", it) }
    }
}
