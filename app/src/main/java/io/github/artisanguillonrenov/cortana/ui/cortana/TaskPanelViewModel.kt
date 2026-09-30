package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.artisanguillonrenov.cortana.AppContainer
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest
import io.github.artisanguillonrenov.cortana.ui.components.Approval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * State of the design's "Tâche active" panel and its cards, from the one runtime (no second backend):
 * the conversation's task (the running one, else its latest), its plan, tool calls and transitions
 * ([TaskRunProjection]), and the worker, MCP, memory and policy state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskPanelViewModel(private val c: AppContainer) : ViewModel() {
    val sessionId = MutableStateFlow<String?>(null)

    /** Elapsed times move without inventing anything: a tick every 30 s. */
    private val tick: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    val task: StateFlow<TaskEntity?> = sessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(null) else combine(c.orchestrator.active, c.tasksFlow) { a, tasks ->
            val running = a?.takeIf { it.sessionId == sid }?.taskId?.let { id -> tasks.firstOrNull { it.id == id } }
            running ?: tasks.filter { it.sessionId == sid }.maxByOrNull { it.createdAt }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val projected: Flow<TaskRunProjection.Panel?> = task.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(null) else combine(
            combine(task, c.taskQueries.activePlan(id), c.taskQueries.toolCalls(id), c.taskQueries.events(id)) { t, p, calls, ev -> Quad(t, p, calls, ev) },
            c.orchestrator.active, c.approvals.pending, tick,
        ) { q, active, pending, now ->
            val t = q.task ?: return@combine null
            val live = active?.takeIf { it.taskId == t.id }?.let { TaskRunProjection.Live(it.status, it.waitingApproval) }
            TaskRunProjection.project(t, q.plan, q.calls, q.events, live, approvalPending = live != null && pending != null,
                label = ::label, now = now)
        }
    }

    private data class Quad(val task: TaskEntity?, val plan: io.github.artisanguillonrenov.cortana.contracts.Plan?, val calls: List<io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity>, val events: List<io.github.artisanguillonrenov.cortana.core.memory.TaskEventEntity>)

    private val cards: Flow<InfoCardsUi> = combine(
        c.workers.observe(), c.mcp.status, c.settings.state.map { it.mcpServers.filter { s -> s.enabled } }.distinctUntilChanged(),
        c.memory.observe(MemoryStatus.ACTIVE), c.approvals.pending,
    ) { workers, mcp, servers, memories, pending -> cardsOf(workers, mcp, servers, memories, pending) }

    val panel: StateFlow<TaskPanelUi> = combine(projected, task, cards, sessionFlow()) { p, t, k, s -> panelOf(p, t, k, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskPanelUi(null, "", "", emptyList(), "", emptyCards))

    private fun label(capability: String) = c.registry.byCapability(capability)?.label ?: capability

    /** The approval this conversation's running task waits for: the thread's card (approved only on the secure screen). */
    val approval: StateFlow<ThreadApproval?> = combine(c.approvals.pending, c.orchestrator.active, sessionId) { p, a, sid ->
        if (p == null || a == null || sid == null || a.sessionId != sid) null
        else ThreadApproval(p.id, p.action.take(160), label(p.capability), p.target?.take(80)?.ifBlank { null } ?: "—", p.risk.label)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The last decision on an approval of the panel's task, from the durable approvals record (the thread's chip). */
    val decision: StateFlow<ThreadDecision?> = task.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(null) else c.taskQueries.approvals(id).map { list ->
            val last = list.lastOrNull() ?: return@map null
            when (last.status) {
                "approved" -> ThreadDecision(Approval.Granted, label(last.capability))
                "refused", "cancelled", "expired" -> ThreadDecision(Approval.Refused, label(last.capability))
                else -> null
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun sessionFlow() = sessionId.flatMapLatest { id -> if (id == null) flowOf(null) else c.conversations.observeSession(id) }

    private fun panelOf(p: TaskRunProjection.Panel?, t: TaskEntity?, k: InfoCardsUi, s: io.github.artisanguillonrenov.cortana.core.memory.SessionEntity?): TaskPanelUi {
        if (p == null || t == null) return TaskPanelUi(null, "", "", emptyList(), "", k)
        val title = t.objective.lineSequence().firstOrNull().orEmpty().take(70)
        val started = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t.createdAt).atZone(ZoneId.systemDefault()))
        val subtitle = if (p.steps.isEmpty()) "Démarrée à $started" else "Plan en ${p.steps.size} étapes · démarrée à $started"
        val tags = buildList {
            val mode = ChatMode.of(s?.mode)
            add(TagTone.Purple to when (mode) { ChatMode.DEV -> "Développement"; ChatMode.RESEARCH -> "Recherche"; ChatMode.AGENT -> "Agent"; ChatMode.VOICE -> "Voix"; else -> "Discussion" })
            when (t.source) { "schedule" -> add(TagTone.Green to "Planifiée"); "voice" -> add(TagTone.Green to "Voix"); else -> {} }
            val local = c.settings.current.privacyMode == "local_only"
            add(TagTone.Blue to if (local) "Local" else (s?.modelId?.take(24) ?: "Modèle par défaut"))
        }
        val folder = p.files.firstOrNull()?.name?.substringBeforeLast('/', "")?.ifEmpty { "Fichiers" } ?: "Fichiers"
        return TaskPanelUi(p, title, subtitle, tags, folder, k)
    }

    private fun cardsOf(
        workers: List<io.github.artisanguillonrenov.cortana.core.memory.WorkerEntity>,
        mcp: Map<String, io.github.artisanguillonrenov.cortana.core.mcp.McpManager.Status>,
        servers: List<io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig>,
        memories: List<io.github.artisanguillonrenov.cortana.core.memory.MemoryEntity>,
        pending: ApprovalRequest?,
    ): InfoCardsUi {
        val w = workers.filter { !it.revoked }.maxByOrNull { it.lastSeenAt ?: it.pairedAt }
        val now = System.currentTimeMillis()
        val online = w != null && w.lastError == null && (w.lastSeenAt ?: 0) > now - 5 * 60_000
        val workerLine = when {
            w == null -> "Appairer un PC ou un serveur depuis Worker"
            w.lastError != null -> "Erreur : ${w.lastError.take(60)}"
            w.lastSeenAt != null -> "Vu il y a ${((now - w.lastSeenAt) / 60_000).coerceAtLeast(0)} min"
            else -> "Jamais connecté"
        }
        val ok = servers.filter { mcp[it.id]?.state == "ok" }
        val types = memories.map { it.type }.distinct().mapNotNull { t ->
            when (t) { MemoryTypes.PROFILE -> "profil"; MemoryTypes.PREFERENCE -> "préférences"; MemoryTypes.SEMANTIC -> "faits"; else -> null }
        }
        return InfoCardsUi(
            workerName = w?.name, workerLine = workerLine, workerOnline = online,
            mcpConnected = ok.size, mcpTotal = servers.size, mcpNames = servers.joinToString(" • ") { it.name },
            memoryCount = memories.size, memorySummary = types.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Aucun souvenir confirmé" },
            policyPending = if (pending != null) 1 else 0,
            policySummary = pending?.let { "En attente : ${it.action.take(70)}" } ?: "Approbation requise pour les actions sensibles (L2, L3)",
        )
    }

    companion object {
        val emptyCards = InfoCardsUi(null, "", false, 0, 0, "", 0, "", 0, "")
    }
}
