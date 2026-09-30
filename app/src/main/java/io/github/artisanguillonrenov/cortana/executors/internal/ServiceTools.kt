package io.github.artisanguillonrenov.cortana.executors.internal

import io.github.artisanguillonrenov.cortana.core.memory.MemoryRepository
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPaths
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.scheduler.ConditionSpec
import io.github.artisanguillonrenov.cortana.core.scheduler.CortanaScheduler
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.core.outbox.Outbox
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.obj
import io.github.artisanguillonrenov.cortana.util.str
import java.time.ZoneId

/** §8.1 — internal capabilities, registered so they get the same validation, policy and audit. */
private val FP = Regex("^fp=[0-9a-f]{16} · ")

class ServiceTools(
    private val memory: MemoryRepository,
    private val scheduler: CortanaScheduler,
    private val outbox: Outbox,
    private val settings: SettingsRepository,
    /** Why a capability cannot back a condition watch (read-only, non-UI only), or null when it can. */
    private val watchCheck: (String) -> String? = { null },
) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "memory.search", "Cherche dans les souvenirs enregistrés sur le propriétaire.",
            S.obj("query" to S.str("Mots-clés"), required = listOf("query")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Chercher dans la mémoire",
        ) { a, _ ->
            val hits = memory.search(a.str("query")!!, 15)
            ToolResult.ok(if (hits.isEmpty()) "Aucun souvenir correspondant" else hits.joinToString("\n") { "- [${it.id.take(8)}] (${it.type}) ${it.text}" })
        },
        ToolDefinition(
            "memory.save", "Enregistre un fait durable ou une préférence du propriétaire. explicit=true uniquement si le propriétaire a demandé explicitement de retenir.",
            S.obj(
                "text" to S.str("Le fait, formulé à la 3e personne ou comme préférence, ex. « Préfère des réponses courtes »"),
                "type" to S.str("Type", listOf(MemoryTypes.PROFILE, MemoryTypes.PREFERENCE, MemoryTypes.SEMANTIC, MemoryTypes.EPISODIC)),
                "explicit" to S.bool("Le propriétaire a dit « retiens que… »"),
                "importance" to S.int("1-5", 1, 5),
                required = listOf("text"),
            ),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Enregistrer un souvenir",
            allowWhenHalted = true, tags = listOf("mémoire", "retiens", "souvenir", "préférence"),
        ) { a, ctx ->
            if (ctx.incognito) return@ToolDefinition ToolResult.error("Session incognito : aucune mémoire n'est enregistrée.")
            val explicitAsked = a.bool("explicit") == true && FastPaths.isExplicitMemoryRequest(ctx.lastUserText)
            val status = when {
                ctx.tainted -> MemoryStatus.PENDING // tainted task writes are always pending (§9.3)
                explicitAsked -> MemoryStatus.ACTIVE
                settings.current.memoryWriteMode == "auto" -> MemoryStatus.ACTIVE
                else -> MemoryStatus.PENDING
            }
            val r = memory.save(
                a.str("text")!!, a.str("type") ?: MemoryTypes.SEMANTIC, status, source = if (explicitAsked) "explicit" else "extracted",
                sessionId = ctx.sessionId, taskId = ctx.taskId, importance = a.int("importance") ?: 3,
            )
            val where = if (r.memory.status == MemoryStatus.ACTIVE) "enregistré" else "proposé (à confirmer par le propriétaire dans l'écran Mémoire)"
            ToolResult.ok(if (r.duplicate) "Déjà connu : ${r.memory.text}" else "Souvenir $where : ${r.memory.text}")
        },
        ToolDefinition(
            "memory.forget", "Oublie un souvenir (par identifiant court ou par texte).",
            S.obj("id" to S.str("Identifiant (8 premiers caractères suffisent)"), "query" to S.str("Texte du souvenir")),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Oublier un souvenir",
        ) { a, _ ->
            val q = a.str("query") ?: a.str("id") ?: return@ToolDefinition ToolResult.error("id ou query requis")
            val hits = memory.search(q, 20)
            val target = hits.firstOrNull { a.str("id") != null && it.id.startsWith(a.str("id")!!) } ?: hits.firstOrNull()
                ?: return@ToolDefinition ToolResult.error("Aucun souvenir correspondant")
            memory.forget(target.id)
            ToolResult.ok("Oublié : ${target.text}")
        },
        ToolDefinition(
            "schedule.create",
            "Crée un rappel (kind=reminder : simple notification, sans IA), une tâche planifiée (once/interval/cron : Cortana exécutera l'objectif) " +
                "ou une surveillance (condition_watch : toutes les every_minutes, une lecture est vérifiée et l'objectif n'est exécuté que si la condition est remplie). " +
                "Utilise in_minutes pour « dans N minutes », sinon at (HH:mm ou AAAA-MM-JJTHH:mm, heure locale).",
            S.obj(
                "kind" to S.str("Type", listOf(ScheduleKinds.REMINDER, ScheduleKinds.ONCE, ScheduleKinds.INTERVAL, ScheduleKinds.CRON, ScheduleKinds.CONDITION)),
                "message" to S.str("Texte du rappel (reminder)"),
                "objective" to S.str("Objectif de la tâche (once/interval/cron)"),
                "name" to S.str("Nom court"),
                "in_minutes" to S.int("Dans combien de minutes", 1, 525600),
                "in_seconds" to S.int("Dans combien de secondes (précis)", 1, 31_536_000),
                "at" to S.str("Heure locale HH:mm ou date AAAA-MM-JJTHH:mm"),
                "every_minutes" to S.int("Intervalle en minutes (interval, ou rappel récurrent)", 15, 525600),
                "cron" to S.str("Expression cron 5 champs, ex. « 0 8 * * 1-5 »"),
                "concurrency" to S.str("Si l'exécution précédente n'est pas finie : skip (défaut), queue (une en attente), replace (arrête la précédente), allow", ScheduleKinds.CONCURRENCY),
                "missed" to S.str("Exécution manquée (tablette éteinte) : skip ou catch_up_once", listOf("skip", "catch_up_once")),
                "watch" to S.obj(
                    "capability" to S.str("Capacité de lecture à appeler, ex. web.fetch"),
                    "args" to S.anyObj("Arguments de cette capacité"),
                    "test" to S.str("Condition sur le résultat", ConditionSpec.TESTS),
                    "value" to S.str("Texte, motif ou nombre comparé (sauf changed)"),
                    required = listOf("capability", "test"),
                ),
                required = listOf("kind"),
            ),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Planifier",
            allowWhenHalted = true, tags = listOf("rappel", "reminder", "planification", "cron", "alarme"),
            riskClassifier = { a, _ ->
                if (a.str("kind") != ScheduleKinds.REMINDER) RiskAssessment(Risk.L2, listOf("Tâche autonome future"), targetDescription = a.str("objective")) else null
            },
        ) { a, _ ->
            val kind = a.str("kind")!!
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val spec = try {
                val watch = a.obj("watch")
                when {
                    kind == ScheduleKinds.CONDITION -> {
                        if (watch == null) return@ToolDefinition ToolResult.error("watch requis pour une surveillance")
                        val every = a.int("every_minutes") ?: return@ToolDefinition ToolResult.error("every_minutes requis pour une surveillance")
                        val cap = watch.str("capability")!!
                        watchCheck(cap)?.let { return@ToolDefinition ToolResult.error(it) }
                        ScheduleSpec(everyMinutes = every, startAt = now + every * 60_000L,
                            condition = ConditionSpec(cap, (watch.obj("args") ?: JsonObject(emptyMap())).toString(), watch.str("test")!!, watch.str("value")))
                    }
                    a.str("cron") != null -> ScheduleSpec(cron = a.str("cron")!!.also { io.github.artisanguillonrenov.cortana.core.scheduler.CronExpression.parse(it) })
                    a.int("every_minutes") != null -> ScheduleSpec(everyMinutes = a.int("every_minutes"), startAt = a.int("in_minutes")?.let { now + it * 60_000L }
                        ?: a.str("at")?.let { FastPaths.parseAt(it, zone, now) } ?: (now + a.int("every_minutes")!! * 60_000L))
                    a.int("in_seconds") != null -> ScheduleSpec(at = now + a.int("in_seconds")!! * 1_000L)
                    a.int("in_minutes") != null -> ScheduleSpec(at = now + a.int("in_minutes")!! * 60_000L)
                    a.str("at") != null -> ScheduleSpec(at = FastPaths.parseAt(a.str("at")!!, zone, now) ?: return@ToolDefinition ToolResult.error("Heure « ${a.str("at")} » non comprise (HH:mm ou AAAA-MM-JJTHH:mm)"))
                    else -> return@ToolDefinition ToolResult.error("Indique in_minutes, at, every_minutes ou cron")
                }
            } catch (e: IllegalArgumentException) {
                return@ToolDefinition ToolResult.error(e.message ?: "Planification invalide")
            }
            val action = if (kind == ScheduleKinds.REMINDER) {
                ScheduleAction("notify", message = a.str("message") ?: a.str("name") ?: return@ToolDefinition ToolResult.error("message requis"))
            } else ScheduleAction("task", objective = a.str("objective") ?: return@ToolDefinition ToolResult.error("objective requis"))
            val name = a.str("name") ?: action.message ?: action.objective ?: "Planification"
            val effectiveKind = if (kind == ScheduleKinds.REMINDER || kind == ScheduleKinds.CONDITION) kind else when {
                spec.cron != null -> ScheduleKinds.CRON
                spec.everyMinutes != null -> ScheduleKinds.INTERVAL
                else -> ScheduleKinds.ONCE
            }
            val s = try {
                scheduler.create(name, effectiveKind, spec, action, zone, concurrency = a.str("concurrency") ?: "skip", missed = a.str("missed"))
            } catch (e: IllegalArgumentException) {
                return@ToolDefinition ToolResult.error(e.message ?: "Planification invalide")
            }
            val watched = spec.condition?.let { " — déclenchée si ${it.describe()}" } ?: ""
            ToolResult.ok("Planifié « ${s.name} » (${spec.describe(zone)}$watched) — prochaine exécution : ${s.nextRunAt?.let { TimeFmt.full(it) } ?: "?"} [id ${s.id.take(8)}]")
        },
        ToolDefinition(
            "schedule.list", "Liste les rappels et tâches planifiées.", S.obj(),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Lister les planifications",
        ) { _, _ ->
            val all = scheduler.all()
            ToolResult.ok(if (all.isEmpty()) "Aucune planification" else all.joinToString("\n") { s ->
                val cond = runCatching { ScheduleSpec.parse(s.specJson).condition }.getOrNull()?.let { " si ${it.describe()}" } ?: ""
                val policy = if (s.kind == ScheduleKinds.REMINDER) "" else ", concurrence ${s.concurrencyPolicy}"
                "- [${s.id.take(8)}] ${s.name} (${s.kind}$cond, ${if (s.enabled) "active" else "inactive"}$policy) prochaine : ${s.nextRunAt?.let { TimeFmt.short(it) } ?: "—"}" +
                    (s.lastOutcome?.let { " · dernier : $it" } ?: "")
            })
        },
        ToolDefinition(
            "schedule.runs", "Historique des exécutions d'une tâche planifiée ou d'une surveillance (en file, en cours, réussie, condition non remplie…).",
            S.obj("id" to S.str("Identifiant (8 caractères suffisent)"), "limit" to S.int("Nombre d'exécutions", 1, 50), required = listOf("id")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Historique d'une planification",
        ) { a, _ ->
            val s = scheduler.all().firstOrNull { it.id.startsWith(a.str("id")!!) } ?: return@ToolDefinition ToolResult.error("Planification introuvable")
            val runs = scheduler.runs(s.id, a.int("limit") ?: 10)
            ToolResult.ok(if (runs.isEmpty()) "Aucune exécution pour « ${s.name} »" else "« ${s.name} » :\n" + runs.joinToString("\n") { r ->
                "- ${TimeFmt.short(r.queuedAt)} · ${r.status}${if (r.late) " (en retard)" else ""}${r.detail?.let { " · ${it.replace(FP, "")}" } ?: ""}${r.taskId?.let { " [tâche ${it.take(8)}]" } ?: ""}"
            })
        },
        ToolDefinition(
            "schedule.update", "Active/désactive ou renomme une planification.",
            S.obj("id" to S.str("Identifiant (8 caractères suffisent)"), "enabled" to S.bool("Activer"), "name" to S.str("Nouveau nom"), required = listOf("id")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Modifier une planification",
        ) { a, _ ->
            val s = scheduler.all().firstOrNull { it.id.startsWith(a.str("id")!!) } ?: return@ToolDefinition ToolResult.error("Planification introuvable")
            a.str("name")?.let { scheduler.rename(s.id, it) }
            a.bool("enabled")?.let { scheduler.setEnabled(s.id, it) }
            ToolResult.ok("Planification « ${s.name} » mise à jour")
        },
        ToolDefinition(
            "schedule.delete", "Supprime une planification.",
            S.obj("id" to S.str("Identifiant (8 caractères suffisent)"), required = listOf("id")),
            Risk.L2, SideEffect.IRREVERSIBLE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Supprimer une planification",
        ) { a, _ ->
            val s = scheduler.all().firstOrNull { it.id.startsWith(a.str("id")!!) } ?: return@ToolDefinition ToolResult.error("Planification introuvable")
            scheduler.delete(s.id)
            ToolResult.ok("Supprimé : ${s.name}")
        },
        ToolDefinition(
            "notify.owner", "Envoie une notification au propriétaire.",
            S.obj("title" to S.str("Titre"), "message" to S.str("Message"), required = listOf("message")),
            Risk.L1, SideEffect.EXTERNAL, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.SERVICE, label = "Notifier le propriétaire",
            // Durable effect: recorded in the outbox under the ledger key, then delivered (retried if needed).
            reconcile = { _, ctx -> ctx.idempotencyKey?.let { outbox.isRecorded(it) } },
        ) { a, ctx ->
            val payload = buildJsonObject {
                put("title", a.str("title") ?: "Cortana"); put("message", a.str("message")!!); put("sessionId", ctx.sessionId)
            }
            outbox.enqueue(Outbox.KIND_NOTIFY_OWNER, payload, ctx.idempotencyKey ?: "notify:" + Ids.new())
            outbox.drain()
            ToolResult.ok("Notification envoyée")
        },
        ToolDefinition(
            "ask_user", "Pose une question au propriétaire quand une information indispensable manque. La tâche s'arrête jusqu'à sa réponse.",
            S.obj("question" to S.str("La question, courte"), required = listOf("question")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Question au propriétaire",
        ) { a, _ -> ToolResult(true, a.str("question")!!, control = "ask_user") },
    )
}
