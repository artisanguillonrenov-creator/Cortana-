package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.components.AuditKind
import io.github.artisanguillonrenov.cortana.ui.components.AuditRow
import io.github.artisanguillonrenov.cortana.ui.components.ButtonKind
import io.github.artisanguillonrenov.cortana.ui.components.CardTitle
import io.github.artisanguillonrenov.cortana.ui.components.CortanaButton
import io.github.artisanguillonrenov.cortana.ui.components.CortanaCard
import io.github.artisanguillonrenov.cortana.ui.components.CortanaToggle
import io.github.artisanguillonrenov.cortana.ui.components.FileRow
import io.github.artisanguillonrenov.cortana.ui.components.ListRow
import io.github.artisanguillonrenov.cortana.ui.components.LogKind
import io.github.artisanguillonrenov.cortana.ui.components.ProgressBar
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.SectionHeader
import io.github.artisanguillonrenov.cortana.ui.components.SegmentedControl
import io.github.artisanguillonrenov.cortana.ui.components.SmallIconButton
import io.github.artisanguillonrenov.cortana.ui.components.StatusChip
import io.github.artisanguillonrenov.cortana.ui.components.StepIcon
import io.github.artisanguillonrenov.cortana.ui.components.StepState
import io.github.artisanguillonrenov.cortana.ui.components.StopButton
import io.github.artisanguillonrenov.cortana.ui.components.StopSize
import io.github.artisanguillonrenov.cortana.ui.components.Symbol
import io.github.artisanguillonrenov.cortana.ui.components.Tag
import io.github.artisanguillonrenov.cortana.ui.components.ToggleSize
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.tasks.TraceDialog
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// ------------------------------------------------------------------ state

@Immutable
data class TaskLiveUi(
    val taskId: String, val sessionId: String, val title: String, val meta: String, val status: RunStatus,
    val progress: Float, val progressLabel: String, val stepState: StepState, val stepLabel: String, val remaining: String,
)

@Immutable
data class QueuedTaskUi(val id: String, val sessionId: String, val title: String, val subtitle: String, val confirm: Boolean)

@Immutable
data class ScheduleUi(val id: String, @DrawableRes val icon: Int, val name: String, val subtitle: String, @DrawableRes val whenIcon: Int, val whenLabel: String, val enabled: Boolean)

@Immutable
data class DoneTaskUi(val taskId: String, val sessionId: String, @DrawableRes val icon: Int, val title: String, val meta: String, val ok: Boolean, val status: String)

@Immutable
data class JournalUi(val kind: AuditKind, val title: String, val detail: String, val time: String, val duration: String)

@Immutable
data class TasksUi(
    val segment: Int,
    val live: TaskLiveUi?,
    val queued: List<QueuedTaskUi>,
    val schedules: List<ScheduleUi>,
    val done: List<DoneTaskUi>,
    val selectedTask: String?,
    val journalFor: String,
    val journal: List<JournalUi>,
    val exec: List<Pair<String, String>>,
    val artifacts: List<HistoryFileUi>,
    val devMode: Boolean,
)

class TasksActions(
    val segment: (Int) -> Unit = {},
    val newTask: () -> Unit = {},
    val openSession: (String) -> Unit = {},
    val stop: () -> Unit = {},
    val resume: () -> Unit = {},
    val moveQueued: (QueuedTaskUi, Boolean) -> Unit = { _, _ -> },
    val removeQueued: (QueuedTaskUi) -> Unit = {},
    val confirmQueued: (QueuedTaskUi) -> Unit = {},
    val toggleSchedule: (String, Boolean) -> Unit = { _, _ -> },
    val select: (String) -> Unit = {},
    val download: (String) -> Unit = {},
    val trace: (String) -> Unit = {},
)

// ------------------------------------------------------------------ screen

/** "Tâches" (README screen 4): the task in progress, the queue, schedules, recent ones; the audited journal aside. */
@Composable
fun TasksScreen(ui: TasksUi, actions: TasksActions, modifier: Modifier = Modifier) {
    DesignPage(
        "Tâches", "Suivez, planifiez et auditez le travail de Cortana.", modifier,
        headerTrailing = {
            SegmentedControl(listOf("Toutes", "En cours", "Planifiées", "Terminées"), ui.segment, actions.segment)
            CortanaButton("Nouvelle tâche", actions.newTask, kind = ButtonKind.Primary, icon = Symbols.Add, padding = PaddingValues(horizontal = 16.dp), gap = 10.dp, shadow = true)
        },
        sideScrolls = false,
        sideTop = 18.dp,
        side = { TasksSide(ui, actions) },
    ) {
        val all = ui.segment == 0
        if (all || ui.segment == 1) {
            item("h-live") { SectionHeader("En cours", if (ui.live != null) 1 else 0, Modifier.padding(top = 18.dp, bottom = 10.dp)) }
            item("live") { LiveCard(ui.live, actions) }
            if (ui.queued.isNotEmpty()) {
                item("h-queue") { SectionHeader("En file", ui.queued.size, Modifier.padding(top = 22.dp, bottom = 10.dp)) }
                items(ui.queued, key = { "q" + it.id }) { q -> QueuedRow(q, actions) }
            }
        }
        if (all || ui.segment == 2) {
            item("h-sched") { SectionHeader("Planifiées", ui.schedules.size, Modifier.padding(top = if (all) 22.dp else 18.dp, bottom = 10.dp)) }
            if (ui.schedules.isEmpty()) item("s-empty") { Empty("Aucune planification. Dites « Rappelle-moi demain à 8 h de… » ou « Chaque nuit, sauvegarde… ».") }
            items(ui.schedules, key = { "s" + it.id }) { s -> ScheduleRow(s, actions) }
        }
        if (all || ui.segment == 3) {
            item("h-done") { SectionHeader("Terminées récemment", ui.done.size, Modifier.padding(top = if (all) 22.dp else 18.dp, bottom = 10.dp)) }
            if (ui.done.isEmpty()) item("d-empty") { Empty("Aucune tâche terminée pour l’instant.") }
            items(ui.done, key = { "d" + it.taskId }) { d -> DoneRow(d, d.taskId == ui.selectedTask, actions) }
        }
    }
}

@Composable
private fun Empty(text: String) {
    val c = Cortana.colors
    CortanaCard { Text(text, style = CortanaType.Secondary, color = c.textTertiary) }
}

@Composable
private fun LiveCard(t: TaskLiveUi?, actions: TasksActions) {
    val c = Cortana.colors
    if (t == null) { Empty("Aucune tâche en cours. Demandez quelque chose à Cortana dans Discussion : la tâche et ses étapes s’afficheront ici."); return }
    CortanaCard(padding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp).clip(CortanaShapes.Lg).background(c.tile).border(1.dp, c.taskTileBorder, CortanaShapes.Lg), contentAlignment = Alignment.Center) {
                Symbol(Symbols.StickyNote2Fill, c.taskTileIcon, 32.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.title, style = CortanaType.PanelTitle, color = c.textStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.meta, style = CortanaType.Caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(t.status)
        }
        if (t.progressLabel.isNotEmpty()) Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressBar(t.progress, 8.dp, Modifier.weight(1f), label = "Progression ${t.progressLabel}")
            Text(t.progressLabel, style = CortanaType.Numeric, color = c.textSecondary)
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(CortanaShapes.Md).background(c.sunken).border(1.dp, c.surfaceBorder, CortanaShapes.Md).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StepIcon(t.stepState, 18.dp)
                Text(t.stepLabel, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.textControl, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(t.remaining, style = CortanaType.Caption.copy(fontSize = 12.5.sp), color = c.textTertiary, maxLines = 1)
            }
            CortanaButton("Discussion", { actions.openSession(t.sessionId) }, icon = Symbols.Forum, padding = PaddingValues(horizontal = 16.dp))
            StopButton(t.status, actions.stop, actions.resume, StopSize.Card, onDone = { actions.openSession(t.sessionId) }, doneLabel = "Voir")
        }
    }
}

@Composable
private fun QueuedRow(q: QueuedTaskUi, actions: TasksActions) {
    val c = Cortana.colors
    ListRow(Symbols.Palette, c.purpleText, c.purpleTint, q.title, q.subtitle, Modifier.padding(bottom = 10.dp).let { if (q.confirm) it.clickable(onClickLabel = "Confirmer l'envoi") { actions.confirmQueued(q) } else it }) {
        SmallIconButton(Symbols.ArrowUpward, "Monter", { actions.moveQueued(q, true) })
        SmallIconButton(Symbols.ArrowDownward, "Descendre", { actions.moveQueued(q, false) })
        SmallIconButton(Symbols.Close, "Retirer de la file", { actions.removeQueued(q) }, danger = true)
    }
}

@Composable
private fun ScheduleRow(s: ScheduleUi, actions: TasksActions) {
    val c = Cortana.colors
    ListRow(s.icon, c.accentIcon, c.accent.copy(alpha = 0.12f), s.name, s.subtitle, Modifier.padding(bottom = 10.dp), contentAlpha = if (s.enabled) 1f else 0.5f) {
        Row(Modifier.alpha(if (s.enabled) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(s.whenIcon, c.metaText, 16.dp)
            Text(s.whenLabel, style = CortanaType.Caption.copy(fontSize = 13.sp), color = c.metaText, maxLines = 1)
        }
        CortanaToggle(s.enabled, { on -> actions.toggleSchedule(s.id, on) }, ToggleSize.Large, Modifier.padding(start = 12.dp), label = "Activer « ${s.name} »")
    }
}

@Composable
private fun DoneRow(d: DoneTaskUi, selected: Boolean, actions: TasksActions) {
    val c = Cortana.colors
    ListRow(d.icon, c.pillIcon, c.tile, d.title, d.meta,
        Modifier.padding(bottom = 10.dp).clip(CortanaShapes.Lg).let { if (selected) it.border(1.dp, c.accentIcon.copy(alpha = 0.45f), CortanaShapes.Lg) else it }
            .clickable(onClickLabel = "Voir le journal de cette tâche") { actions.select(d.taskId) }, tileBorder = c.tileBorder) {
        val fg = if (d.ok) c.successText else c.warningText
        Row(
            Modifier.height(28.dp).clip(CortanaShapes.Sm).background((if (d.ok) c.success else c.warning).copy(alpha = 0.12f)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Symbol(if (d.ok) Symbols.CheckCircleFill else Symbols.WarningFill, fg, 16.dp)
            Text(d.status, style = CortanaType.Caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = fg)
        }
    }
}

@Composable
private fun ColumnScope.TasksSide(ui: TasksUi, actions: TasksActions) {
    val c = Cortana.colors
    CortanaCard(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(Symbols.FactCheck, "Journal d’actions", Modifier.weight(1f), size = 16.5f)
            Tag("Auditable", c.textTertiary, border = c.controlBorder, height = 24.dp)
        }
        Text(if (ui.journalFor.isEmpty()) "Actions réellement exécutées par Cortana." else "Actions réellement exécutées pour « ${ui.journalFor} »",
            style = CortanaType.Caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (ui.journal.isEmpty()) Text("Aucune action pour l’instant.", style = CortanaType.Caption, color = c.textMuted)
            ui.journal.forEachIndexed { i, j -> AuditRow(j.kind, j.title, j.detail, j.time, j.duration, last = i == ui.journal.lastIndex) }
        }
    }
    CortanaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Exécution", style = CortanaType.ItemTitle.copy(fontSize = 16.sp), color = c.textStrong, modifier = Modifier.weight(1f))
            if (ui.devMode && ui.selectedTask != null) CortanaButton("Trace", { actions.trace(ui.selectedTask) }, kind = ButtonKind.Link, height = 32.dp, padding = PaddingValues(horizontal = 8.dp))
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.exec.forEach { (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, style = CortanaType.Caption.copy(fontSize = 13.sp), color = c.textMuted, modifier = Modifier.width(66.dp))
                    Text(v, style = CortanaType.Caption.copy(fontSize = 13.sp), color = c.textControl, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    CortanaCard {
        CardTitle(null, "Artefacts produits", trailing = "${ui.artifacts.size}", size = 16f, bottom = 4.dp)
        if (ui.artifacts.isEmpty()) Text("Aucun artefact pour cette tâche.", style = CortanaType.Caption, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
        ui.artifacts.forEach { f -> FileRow(f.icon, f.name, f.meta) { SmallIconButton(Symbols.Download, "Télécharger ${f.name}", { actions.download(f.id) }) } }
    }
}

// ------------------------------------------------------------------ route

/**
 * Tasks on the one runtime: the running (or paused) task projected by [TaskPanelViewModel], the queue of
 * every conversation, the scheduler's schedules, the recent tasks, and the journal of the selected task
 * built from its durable tool calls and transitions.
 */
@Composable
fun TasksRoute(onOpenSession: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val resumeAutonomy = LocalResumeAutonomy.current
    val pvm: TaskPanelViewModel = viewModel { TaskPanelViewModel(c) }
    val active by c.orchestrator.active.collectAsState()
    val halted by c.killSwitch.halted.collectAsState()
    val tasks by c.tasksFlow.collectAsState(initial = emptyList())
    val schedules by remember { c.scheduler.observe() }.collectAsState(initial = emptyList())
    val queue by remember { c.chat.observeAllQueued() }.collectAsState(initial = emptyList())
    val sessions by remember { c.conversations.observeSessions() }.collectAsState(initial = emptyList())
    val workers by remember { c.workers.observe() }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    var segment by rememberSaveable { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var trace by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    val current: TaskEntity? = active?.taskId?.let { id -> tasks.firstOrNull { it.id == id } }
        ?: tasks.maxByOrNull { it.createdAt }?.takeIf { it.state == TaskState.PAUSED.wire }
    LaunchedEffect(current?.sessionId) { pvm.sessionId.value = current?.sessionId }
    val panel by pvm.panel.collectAsState()
    val titles = sessions.associate { it.id to it.title }
    val worker = workers.filter { !it.revoked }.maxByOrNull { it.lastSeenAt ?: it.pairedAt }

    val live = current?.let { t ->
        val p = panel.task?.takeIf { panel.title.isNotEmpty() }
        val s = sessions.firstOrNull { it.id == t.sessionId }
        TaskLiveUi(
            t.id, t.sessionId, titles[t.sessionId]?.takeIf { it != "Nouvelle discussion" } ?: t.objective.lineSequence().first().take(70),
            listOfNotNull("Démarrée à ${TimeFmt.time(t.createdAt)}", worker?.name?.let { "Worker $it" }, s?.modelId).joinToString(" · "),
            p?.run?.status ?: if (t.state == TaskState.PAUSED.wire) RunStatus.Paused else RunStatus.Running,
            p?.run?.progress ?: 0f, p?.progressLabel.orEmpty(),
            p?.steps?.getOrNull(p.run.step)?.state ?: StepState.Running,
            p?.stepLabel ?: t.objective.lineSequence().first().take(80), p?.remaining.orEmpty(),
        )
    }
    val selectedTask = selected?.let { id -> tasks.firstOrNull { it.id == id } } ?: current ?: tasks.firstOrNull()
    val calls by produceState(emptyList<io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity>(), selectedTask?.id) {
        val id = selectedTask?.id ?: run { value = emptyList(); return@produceState }
        c.taskQueries.toolCalls(id).collect { value = it }
    }
    val events by produceState(emptyList<io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity>(), selectedTask?.id) {
        val id = selectedTask?.id ?: run { value = emptyList(); return@produceState }
        c.taskQueries.events(id).collect { value = it }
    }
    val artifacts by produceState(emptyList<io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity>(), selectedTask?.id, selectedTask?.state) {
        value = selectedTask?.id?.let { runCatching { c.artifacts.forTask(it) }.getOrNull() }.orEmpty()
    }
    val running = selectedTask != null && active?.taskId == selectedTask.id
    val logs = TaskRunProjection.logs(calls, events, { cap -> c.registry.byCapability(cap)?.label ?: cap }, zone, null, System.currentTimeMillis())
    val journal = logs.mapIndexed { i, l ->
        val kind = when (l.kind) {
            LogKind.Stop -> AuditKind.Stopped
            LogKind.Wait -> AuditKind.Waiting
            LogKind.Run -> if (running && i == logs.lastIndex) AuditKind.Running else AuditKind.Ok
            else -> AuditKind.Ok
        }
        val detail = when (l.kind) { LogKind.Dir -> "Fichier"; LogKind.Ok -> "Action réussie"; LogKind.Stop -> "Arrêt ou refus"; LogKind.Wait -> "Politique de sécurité"; LogKind.Play -> "Reprise"; LogKind.Run -> "En cours" }
        JournalUi(kind, l.text, detail, l.time, "")
    }
    val session = selectedTask?.let { t -> sessions.firstOrNull { it.id == t.sessionId } }
    val exec = if (selectedTask == null) emptyList() else listOf(
        "Modèle" to (session?.modelId ?: "Modèle par défaut"),
        "Appareil" to (worker?.name?.let { "$it (worker)" } ?: "Cette tablette"),
        "Outils" to (Toolsets.labels[session?.toolset] ?: "Complet") + " · ${calls.size} action(s)",
        "Politique" to "Approbation pour les actions sensibles (L2, L3)" + (if (selectedTask.tainted) " · contenu externe lu" else ""),
    )
    val queued = queue.map { q ->
        QueuedTaskUi(q.id, q.sessionId, q.text.lineSequence().first().take(90),
            (if (q.status == "confirm") "À confirmer · touchez pour l’envoyer" else "Démarre après la tâche en cours") + " · " + (titles[q.sessionId] ?: "Discussion"), q.status == "confirm")
    }
    val scheduleRows = schedules.map { s ->
        val spec = runCatching { ScheduleSpec.parse(s.specJson) }.getOrNull()
        val action = runCatching { ScheduleAction.parse(s.actionJson) }.getOrNull()
        ScheduleUi(
            s.id, when (s.kind) { ScheduleKinds.REMINDER -> Symbols.Notifications; ScheduleKinds.CONDITION -> Symbols.Preview; else -> Symbols.EventAvailable }, s.name,
            listOfNotNull(if (s.kind == ScheduleKinds.REMINDER) "Rappel" else action?.objective?.take(60), spec?.describe(zone)).joinToString(" · "),
            if (spec?.isRecurring() == true) Symbols.Repeat else Symbols.Event,
            s.nextRunAt?.let { dayLabel(it, zone) + " · " + TimeFmt.time(it) } ?: "—", s.enabled,
        )
    }
    val done = tasks.filter { TaskState.fromWireOrNull(it.state)?.terminal == true }.take(12).map { t ->
        val ended = t.endedAt ?: t.updatedAt
        val (ok, label) = when (t.state) {
            TaskState.COMPLETED.wire -> true to "Réussie"
            TaskState.FAILED.wire -> false to "Échec"
            TaskState.TIMED_OUT.wire -> false to "Délai dépassé"
            else -> false to "Arrêtée"
        }
        DoneTaskUi(t.id, t.sessionId, if (t.source == "schedule") Symbols.Schedule else Symbols.AssignmentFill,
            titles[t.sessionId]?.takeIf { it != "Nouvelle discussion" } ?: t.objective.lineSequence().first().take(70),
            dayLabel(t.createdAt, zone) + " · " + TaskRunProjection.duration(ended - t.createdAt), ok, label)
    }
    TasksScreen(
        TasksUi(segment, live, queued, scheduleRows, done, selectedTask?.id,
            selectedTask?.let { titles[it.sessionId] ?: it.objective.take(60) }.orEmpty(), journal, exec,
            artifacts.map { a -> HistoryFileUi(a.artifactId, iconOf(a), a.name, artifactMeta(a)) }, settings.chat.developer),
        TasksActions(
            segment = { segment = it },
            newTask = {
                scope.launch {
                    val st = c.settings.current
                    val def = st.defaultProviderId?.let { c.providers.get(it) } ?: c.providers.all().firstOrNull { it.enabled && it.defaultModelId != null }
                    onOpenSession(c.conversations.createSession(providerId = def?.id, modelId = def?.defaultModelId, toolset = st.defaultToolset).id)
                }
            },
            openSession = onOpenSession,
            stop = { stopTask(c) },
            resume = { if (halted) resumeAutonomy() else current?.let { c.orchestrator.resumePaused(it.id) } },
            moveQueued = { q, up -> scope.launch { c.chat.moveQueued(q.sessionId, q.id, up) } },
            removeQueued = { q -> scope.launch { c.chat.cancelQueued(q.id) } },
            confirmQueued = { q -> scope.launch { c.chat.confirmQueued(q.id) } },
            toggleSchedule = { id, on -> scope.launch { c.scheduler.setEnabled(id, on) } },
            select = { selected = it },
            download = { id -> scope.launch { runCatching { c.artifacts.exportToDownloads(id) } } },
            trace = { trace = it },
        ),
    )
    trace?.let { id -> TraceDialog(id) { trace = null } }
}

/** "Aujourd'hui", "Demain", "Hier", else the weekday within a week, else the date. */
internal fun dayLabel(at: Long, zone: ZoneId, short: Boolean = false): String {
    val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d == today -> "Aujourd'hui"
        d == today.plusDays(1) -> "Demain"
        d == today.minusDays(1) -> "Hier"
        d.isAfter(today.minusDays(7)) && d.isBefore(today.plusDays(7)) -> d.dayOfWeek.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }
        else -> TimeFmt.short(at).substringBefore(' ')
    }
}
