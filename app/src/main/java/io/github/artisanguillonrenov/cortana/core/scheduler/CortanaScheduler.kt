package io.github.artisanguillonrenov.cortana.core.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleDao
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleEntity
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleRunEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.KillSwitch
import io.github.artisanguillonrenov.cortana.service.Notifications
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ScheduleKinds {
    const val REMINDER = "reminder"
    const val ONCE = "once"
    const val INTERVAL = "interval"
    const val CRON = "cron"
    /** Checked every N minutes; the task runs only when its condition is met (blueprint §39.4). */
    const val CONDITION = "condition_watch"
    val CONCURRENCY = listOf("allow", "skip", "queue", "replace")
}

/**
 * §12 — reminders fire a notification with NO model call; once/interval/cron/condition schedules
 * record a durable run (`schedule_runs`) under their concurrency policy, which the orchestrator
 * executes when it is free (LAW-006: the scheduler never runs a tool nor calls a model).
 * Exact-time via AlarmManager, maintenance via WorkManager. Timezone stored (IANA).
 */
class CortanaScheduler(
    private val context: Context,
    private val dao: ScheduleDao,
    private val killSwitch: KillSwitch,
    private val audit: AuditLog,
    private val notifications: Notifications,
) {
    /** Set by the container: asks the orchestrator side to execute queued runs (never awaited here). */
    var onRunQueued: (() -> Unit)? = null
    /** Set by the container: cancels the task of a running run (replace policy). */
    var cancelRunning: (suspend (ScheduleRunEntity) -> Unit)? = null

    fun observe(): Flow<List<ScheduleEntity>> = dao.observeAll()
    suspend fun all(): List<ScheduleEntity> = dao.all()
    suspend fun get(id: String): ScheduleEntity? = dao.get(id)

    suspend fun create(
        name: String, kind: String, spec: ScheduleSpec, action: ScheduleAction, zone: ZoneId = ZoneId.systemDefault(),
        concurrency: String = "skip", missed: String? = null,
    ): ScheduleEntity {
        require(concurrency in ScheduleKinds.CONCURRENCY) { "politique de concurrence inconnue : $concurrency (${ScheduleKinds.CONCURRENCY.joinToString()})" }
        require(missed == null || missed in setOf("skip", "catch_up_once")) { "politique de rattrapage inconnue : $missed" }
        if (kind == ScheduleKinds.CONDITION) require(spec.condition != null && spec.everyMinutes != null && action.type == "task") { "une surveillance a besoin d'une condition, d'un intervalle et d'un objectif" }
        val now = System.currentTimeMillis()
        val next = spec.nextAfter(now - 1, zone) ?: throw IllegalArgumentException("Cette date est déjà passée.")
        val s = ScheduleEntity(
            id = Ids.new(), name = name.take(120), kind = kind, specJson = spec.toJson(), timezone = zone.id,
            actionJson = action.toJson(), enabled = true, nextRunAt = next, lastRunAt = null,
            missedRunPolicy = missed ?: if (kind == ScheduleKinds.REMINDER) "catch_up_once" else "skip", createdAt = now, concurrencyPolicy = concurrency,
        )
        dao.upsert(s)
        arm(s)
        audit.record("cortana", "schedule.create", s.name, "ok", """{"kind":"$kind","next":$next}""")
        return s
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val s = dao.get(id) ?: return
        val zone = zoneOf(s)
        val next = if (enabled) ScheduleSpec.parse(s.specJson).nextAfter(System.currentTimeMillis(), zone) else s.nextRunAt
        val upd = s.copy(enabled = enabled && next != null, nextRunAt = next)
        dao.upsert(upd)
        if (upd.enabled) arm(upd) else disarm(id)
        if (!enabled) {
            val now = System.currentTimeMillis()
            dao.openRuns(id).filter { it.status == "queued" }.forEach { dao.upsertRun(it.copy(status = "cancelled", finishedAt = now, detail = "planification désactivée")) }
        }
    }

    suspend fun rename(id: String, name: String) {
        dao.get(id)?.let { dao.upsert(it.copy(name = name)) }
    }

    suspend fun delete(id: String) {
        disarm(id)
        dao.openRuns(id).forEach { r -> if (r.status == "running") cancelRunning?.invoke(r) }
        dao.deleteRuns(id)
        dao.delete(id)
        audit.record("owner", "schedule.delete", id, "ok")
    }

    suspend fun runNow(id: String) {
        val s = dao.get(id) ?: return
        execute(s, late = false, manual = true)
    }

    /** Called by [AlarmReceiver]. */
    suspend fun fire(id: String) {
        val s = dao.get(id) ?: return
        if (!s.enabled) return
        val now = System.currentTimeMillis()
        val late = s.nextRunAt != null && now - s.nextRunAt > 5 * 60_000
        val outcome = execute(s, late, manual = false)
        val next = ScheduleSpec.parse(s.specJson).nextAfter(now, zoneOf(s))
        val upd = s.copy(lastRunAt = now, nextRunAt = next, enabled = next != null, lastOutcome = outcome)
        dao.upsert(upd)
        if (next != null) arm(upd)
    }

    private suspend fun execute(s: ScheduleEntity, late: Boolean, manual: Boolean): String {
        val action = runCatching { ScheduleAction.parse(s.actionJson) }.getOrElse { return "action invalide" }
        return when (action.type) {
            "notify" -> {
                // A reminder is a plain notification (no autonomy, no model call): delivered even when STOP is active.
                notifications.reminder(s.id, s.name, action.message ?: s.name, late)
                if (late) "rappel délivré en retard" else "rappel délivré"
            }
            else -> {
                if (killSwitch.isHalted()) {
                    audit.record("scheduler", "schedule.fire", s.name, "skipped", """{"reason":"kill_switch"}""")
                    notifications.owner("Tâche planifiée ignorée", "« ${s.name} » n'a pas été exécutée : l'autonomie est arrêtée (STOP).")
                    "ignorée (STOP actif)"
                } else enqueueRun(s, if (manual) System.currentTimeMillis() else s.nextRunAt ?: System.currentTimeMillis(), late, manual)
            }
        }
    }

    /** On boot/app start/maintenance: re-arm every alarm and apply the missed-run policy. */
    /** Boot, startup maintenance and restore may re-arm at the same time: one pass at a time, so a missed run is caught up once. */
    private val rearmLock = kotlinx.coroutines.sync.Mutex()

    suspend fun rearmAll(catchUp: Boolean) = rearmLock.withLock { rearmAllLocked(catchUp) }

    private suspend fun rearmAllLocked(catchUp: Boolean) {
        val now = System.currentTimeMillis()
        for (s in dao.enabled()) {
            val due = s.nextRunAt
            if (catchUp && due != null && due < now - 60_000) {
                val spec = ScheduleSpec.parse(s.specJson)
                val outcome = if (s.missedRunPolicy == "catch_up_once" && s.kind == ScheduleKinds.REMINDER) {
                    val action = runCatching { ScheduleAction.parse(s.actionJson) }.getOrNull()
                    notifications.reminder(s.id, s.name, action?.message ?: s.name, late = true)
                    "rattrapé (en retard)"
                } else if (s.missedRunPolicy == "catch_up_once" && !killSwitch.isHalted()) {
                    "rattrapage : " + enqueueRun(s, due, late = true, manual = false)
                } else "manqué (ignoré)"
                val next = spec.nextAfter(now, zoneOf(s))
                val upd = s.copy(nextRunAt = next, enabled = next != null, lastRunAt = now, lastOutcome = outcome)
                dao.upsert(upd)
                if (next != null) arm(upd)
            } else if (due != null) arm(s)
        }
    }

    // ------------------------------------------------------------------ durable runs (blueprint §39.2-39.5)

    /**
     * Records a run of [s] before anything executes, applying its concurrency policy against the
     * runs still queued or running: allow (always), skip (not while one is open), queue (at most
     * one waiting), replace (waiting ones cancelled, the running one stopped). Returns the outcome.
     */
    suspend fun enqueueRun(s: ScheduleEntity, dueAt: Long, late: Boolean, manual: Boolean): String {
        val now = System.currentTimeMillis()
        val open = dao.openRuns(s.id)
        fun run(status: String, detail: String) = ScheduleRunEntity(Ids.new(), s.id, dueAt, now, status, finishedAt = if (status == "queued") null else now, detail = detail, late = late)
        when (s.concurrencyPolicy) {
            "skip" -> if (open.isNotEmpty()) { dao.upsertRun(run("skipped", "exécution précédente encore en cours ou en attente")); return "ignorée (exécution précédente en cours)" }
            "queue" -> if (open.any { it.status == "queued" }) { dao.upsertRun(run("skipped", "une exécution attend déjà")); return "ignorée (une exécution attend déjà)" }
            "replace" -> {
                open.filter { it.status == "queued" }.forEach { dao.upsertRun(it.copy(status = "cancelled", finishedAt = now, detail = "remplacée par une exécution plus récente")) }
                open.filter { it.status == "running" }.forEach { cancelRunning?.invoke(it) }
            }
        }
        dao.upsertRun(run("queued", if (manual) "lancée manuellement" else if (late) "en retard" else "à l'heure"))
        audit.record("scheduler", "schedule.run.queued", s.name, "ok", """{"policy":"${s.concurrencyPolicy}","late":$late}""")
        onRunQueued?.invoke()
        return if (manual) "lancée manuellement" else "mise en file"
    }

    suspend fun runs(id: String, limit: Int = 20) = dao.runs(id, limit)

    /** At startup: runs that were executing when the process died are marked interrupted (their task is recovered by the orchestrator). */
    suspend fun recoverRuns(): Int {
        val now = System.currentTimeMillis()
        val running = dao.runningRuns()
        running.forEach { dao.upsertRun(it.copy(status = "interrupted", finishedAt = now, detail = "processus arrêté pendant l'exécution")) }
        dao.purgeRuns(now - 60L * 24 * 3_600_000)
        nudge()
        return running.size
    }

    /** Periodic safety net: asks for queued runs to be executed (e.g. after a long busy period). */
    suspend fun nudge() {
        if (dao.queuedRuns().isNotEmpty()) onRunQueued?.invoke()
    }

    private fun zoneOf(s: ScheduleEntity): ZoneId = runCatching { ZoneId.of(s.timezone) }.getOrDefault(ZoneId.systemDefault())

    private fun pending(id: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ID, id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun arm(s: ScheduleEntity) {
        val at = s.nextRunAt ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(s.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            CLog.w("exact alarm refused, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun disarm(id: String) {
        val pi = pending(id, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    fun schedulePeriodicMaintenance() {
        val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("cortana-maintenance", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext as CortanaApp
        val pr = goAsync()
        // An exact alarm lets the app start its foreground service for a short window.
        app.container.orchestrator.fgsAllowedUntil = System.currentTimeMillis() + 8_000
        app.container.appScope.launch {
            try {
                app.container.scheduler.fire(id)
            } catch (t: Throwable) {
                CLog.e("schedule fire failed", t)
            } finally {
                pr.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "io.github.artisanguillonrenov.cortana.FIRE_SCHEDULE"
        const val EXTRA_ID = "schedule_id"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val app = context.applicationContext as CortanaApp
        val pr = goAsync()
        app.container.appScope.launch {
            try {
                app.container.scheduler.rearmAll(catchUp = true)
            } finally {
                pr.finish()
            }
        }
    }
}

/** Periodic maintenance: re-arm alarms, apply missed-run policy, keep FTS indexes healthy. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as CortanaApp).container
        // Class name kept from 1.2.0: WorkManager persisted it for the unique periodic work.
        c.maintenance.periodic()
        return Result.success()
    }
}
