package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.memory.ChatQueueEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.ui.components.Approval
import io.github.artisanguillonrenov.cortana.ui.components.ApprovalCard
import io.github.artisanguillonrenov.cortana.ui.components.ArtifactChip
import io.github.artisanguillonrenov.cortana.ui.components.AssistantMessage
import io.github.artisanguillonrenov.cortana.ui.components.BubbleText
import io.github.artisanguillonrenov.cortana.ui.components.CodeBlock
import io.github.artisanguillonrenov.cortana.ui.components.LiveStatusLine
import io.github.artisanguillonrenov.cortana.ui.components.PlanCard
import io.github.artisanguillonrenov.cortana.ui.components.QueuedBubble
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.SmallIconButton
import io.github.artisanguillonrenov.cortana.ui.components.UserBubble
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.workspace.AttachmentChip
import io.github.artisanguillonrenov.cortana.ui.workspace.CompareItem
import io.github.artisanguillonrenov.cortana.ui.workspace.EditBox
import io.github.artisanguillonrenov.cortana.ui.workspace.ImageAttachment
import io.github.artisanguillonrenov.cortana.ui.workspace.LocalCodeRenderer
import io.github.artisanguillonrenov.cortana.ui.workspace.MarkdownView
import io.github.artisanguillonrenov.cortana.ui.workspace.PartView
import io.github.artisanguillonrenov.cortana.ui.workspace.SystemItem
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import io.github.artisanguillonrenov.cortana.ui.workspace.TimelineActions
import io.github.artisanguillonrenov.cortana.ui.workspace.VariantNav

/** The approval waiting in this conversation, as the thread shows it (tool label, target, risk). */
data class ThreadApproval(val requestId: String, val title: String, val tool: String, val target: String, val risk: String)

/** The last decision on an approval of the task (from the durable approvals record), shown as a chip. */
data class ThreadDecision(val approval: Approval, val tool: String)

/** Messages written while Cortana works (doc 05 §5.5): remove, confirm an old one, send now, reorder. */
class QueueActions(
    val remove: (String) -> Unit = {},
    val confirm: (String) -> Unit = {},
    val sendNow: (String) -> Unit = {},
    val move: (String, Boolean) -> Unit = { _, _ -> },
)

/**
 * The conversation in the design's style (README "Fil de discussion"): owner bubbles with avatar, Cortana's
 * turns with the ring avatar, the plan of the running task, the design's code blocks, the approval card,
 * the live status line, artifacts at the end and queued messages. Every rc4 feature (variants, edit,
 * branches, continue, comparison, system lines) stays reachable from the messages' menus.
 */
fun LazyListScope.designThread(
    all: List<TimelineItem>,
    prefs: ChatPrefs,
    actions: TimelineActions,
    panel: TaskPanelUi,
    planFlash: Int,
    liveStatus: String?,
    approval: ThreadApproval?,
    decision: ThreadDecision?,
    onRefuse: (ThreadApproval) -> Unit,
    onAllow: (ThreadApproval) -> Unit,
    artifacts: List<Triple<String, String, String>>,
    queue: List<ChatQueueEntity>,
    queueActions: QueueActions,
    onOpenArtifact: (String) -> Unit,
) {
    val task = panel.task
    val items = designItems(all, task)
    val planIndex = if (task != null && task.steps.size >= 2) planHost(items, task) else -1
    var lastUser: String? = null
    val lastUserBefore = HashMap<String, String?>()
    items.forEach { it -> lastUserBefore[it.key] = lastUser; if (it is TimelineItem.User) lastUser = it.message.id }
    val running = task != null && (task.run.status == RunStatus.Running || task.run.status == RunStatus.AwaitingApproval)
    // Approval, decision and artifacts go with Cortana's last turn (a system line such as "Tâche suspendue" may follow it).
    val lastAssistant = items.indexOfLast { it is TimelineItem.Assistant }
    // The running task has no answer row yet: Cortana's turn shows its plan, approval and live status (below).
    val last = items.lastOrNull()
    val paused = task?.run?.status == RunStatus.Paused
    val liveTurn = (running || paused) && (last !is TimelineItem.Assistant || last.status != MessageStatus.STREAMING) &&
        (lastAssistant == -1 || (items[lastAssistant] as TimelineItem.Assistant).first.createdAt < task!!.startedAt)
    itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, it -> it::class.simpleName }) { index, item ->
        val gap = if (index == 0) 0.dp else if (item is TimelineItem.User) 18.dp else 22.dp
        Box(Modifier.fillMaxWidth().padding(top = gap)) {
            when (item) {
                is TimelineItem.User -> DesignUser(item, prefs, actions)
                is TimelineItem.Assistant -> DesignAssistant(
                    item, prefs, actions,
                    plan = if (index == planIndex) task else null, planFlash = planFlash,
                    live = when {
                        item.status == MessageStatus.STREAMING -> liveStatus
                        index == lastAssistant && running && !liveTurn -> liveStatus.orEmpty() // the task goes on after this answer (next step, resumed)
                        else -> null
                    },
                    approval = if (index == lastAssistant && !liveTurn) approval else null,
                    decision = if (index == lastAssistant && !liveTurn) decision else null,
                    onRefuse = onRefuse, onAllow = onAllow,
                    artifacts = if (index == lastAssistant && task?.run?.status == RunStatus.Done) artifacts else emptyList(), onOpenArtifact = onOpenArtifact,
                )
                is TimelineItem.System -> SystemItem(item, lastUserBefore[item.key], actions)
                is TimelineItem.Compare -> CompareItem(item, prefs, actions)
            }
        }
    }
    if (liveTurn) item("live-turn") {
        Box(Modifier.padding(top = 22.dp)) {
            AssistantMessage(TimeFmt.time(task!!.startedAt), running = !paused) {
                if (planIndex == -1 && task.steps.size >= 2) PlanCard(task.steps, task.planLabel, task.run.eta, flashKey = planFlash)
                approval?.let { a -> ApprovalCard(Approval.Pending, a.title, a.tool, a.target, a.risk, { onRefuse(a) }, { onAllow(a) }) }
                decision?.let { d -> ApprovalCard(d.approval, "", d.tool, "", "", {}, {}) }
                LiveStatusLine(if (paused) "Tâche en pause · « Reprendre » la continue là où elle s’est arrêtée."
                    else liveStatus?.takeIf { it.isNotBlank() } ?: "Je travaille…", running = task.run.status == RunStatus.Running)
            }
        }
    }
    items(queue, key = { "q-" + it.id }) { q -> Box(Modifier.padding(top = 18.dp)) { DesignQueued(q, queueActions) } }
}

/** A queued message: the design's bubble; a long press offers the queue's other actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesignQueued(q: ChatQueueEntity, a: QueueActions) {
    var menu by remember { mutableStateOf(false) }
    val confirm = q.status == "confirm"
    Box(Modifier.fillMaxWidth().combinedClickable(remember { MutableInteractionSource() }, null, onClickLabel = if (confirm) "Confirmer l'envoi" else null,
        onLongClickLabel = "Actions du message en file", onLongClick = { menu = true }, onClick = { if (confirm) a.confirm(q.id) else menu = true })) {
        QueuedBubble(q.text, delivered = false, onRemove = { a.remove(q.id) },
            label = when (q.status) { "confirm" -> "Ancien message · touchez pour confirmer l'envoi"; "sending" -> "Envoi…"; else -> null })
        DropdownMenu(menu, { menu = false }) {
            if (confirm) DropdownMenuItem(text = { Text("Confirmer l'envoi") }, onClick = { menu = false; a.confirm(q.id) })
            DropdownMenuItem(text = { Text("Envoyer maintenant (arrête la réponse en cours)") }, onClick = { menu = false; a.sendNow(q.id) })
            DropdownMenuItem(text = { Text("Monter") }, onClick = { menu = false; a.move(q.id, true) })
            DropdownMenuItem(text = { Text("Descendre") }, onClick = { menu = false; a.move(q.id, false) })
            DropdownMenuItem(text = { Text("Retirer de la file") }, onClick = { menu = false; a.remove(q.id) })
        }
    }
}

/**
 * The thread's rows: an answer stopped before its first word (a turn that only called a tool) is left
 * out, unless it carries the plan or Cortana's last turn (approval, decision). The route scrolls on these rows.
 */
internal fun designItems(items: List<TimelineItem>, task: TaskRunProjection.Panel?): List<TimelineItem> {
    val plan = if (task != null && task.steps.size >= 2) planHost(items, task) else -1
    val lastAssistant = items.indexOfLast { it is TimelineItem.Assistant }
    return items.filterIndexed { i, it ->
        !(it is TimelineItem.Assistant && i != plan && i != lastAssistant && it.status != MessageStatus.STREAMING && it.text.isBlank() &&
            it.variants == null && it.parts.all { p -> p is MessagePart.ToolCall || p is MessagePart.ToolResult })
    }
}

/** The assistant turn that shows the plan: the last answer of the panel's task, if any. */
internal fun planHost(items: List<TimelineItem>, task: TaskRunProjection.Panel): Int =
    items.indexOfLast { it is TimelineItem.Assistant && it.first.createdAt >= task.startedAt }

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun DesignUser(item: TimelineItem.User, prefs: ChatPrefs, actions: TimelineActions) {
    val m = item.message
    var editing by rememberSaveable(m.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (editing) { EditBox(m.text, onCancel = { editing = false }, onSend = { actions.edit(m.id, it); editing = false }); return }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(Modifier.combinedClickable(remember { MutableInteractionSource() }, null, onLongClickLabel = "Actions du message", onLongClick = { menu = true }, onClick = {})) {
            UserBubble(TimeFmt.time(m.createdAt)) {
                if (item.meta.attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    item.meta.attachments.forEach { a -> if (a.mime.startsWith("image/")) ImageAttachment(a, actions) else AttachmentChip(a) }
                }
                BubbleText(m.text)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Modifier") }, onClick = { menu = false; editing = true })
                DropdownMenuItem(text = { Text("Copier") }, onClick = { menu = false; actions.copy(m.text) })
                DropdownMenuItem(text = { Text("Réenvoyer") }, onClick = { menu = false; actions.resend(m.text) })
                DropdownMenuItem(text = { Text("Brancher depuis ici") }, onClick = { menu = false; actions.branchFrom(m.id) })
                DropdownMenuItem(text = { Text("Dupliquer jusqu'ici") }, onClick = { menu = false; actions.fork(m.id) })
                DropdownMenuItem(text = { Text("Citer") }, onClick = { menu = false; actions.quote(m.text) })
                DropdownMenuItem(text = { Text("Épingler au contexte") }, onClick = { menu = false; actions.pin(m.id, m.text.take(60)) })
                DropdownMenuItem(text = { Text("Convertir en tâche") }, onClick = { menu = false; actions.convertToTask(m.text) })
                DropdownMenuItem(text = { Text("Supprimer ce message et la suite…") }, onClick = { menu = false; actions.deleteFrom(m.id) })
            }
        }
        item.versions?.let { v -> Box(Modifier.padding(top = 4.dp)) { VariantNav(v, "Version", actions.switchTo) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignAssistant(
    item: TimelineItem.Assistant,
    prefs: ChatPrefs,
    actions: TimelineActions,
    plan: TaskRunProjection.Panel?,
    planFlash: Int,
    live: String?,
    approval: ThreadApproval?,
    decision: ThreadDecision?,
    onRefuse: (ThreadApproval) -> Unit,
    onAllow: (ThreadApproval) -> Unit,
    artifacts: List<Triple<String, String, String>>,
    onOpenArtifact: (String) -> Unit,
) {
    val c = Cortana.colors
    var menu by remember { mutableStateOf(false) }
    val streaming = item.status == MessageStatus.STREAMING
    AssistantMessage(TimeFmt.time(item.first.createdAt), running = streaming) {
        if (plan != null) PlanCard(plan.steps, plan.planLabel, plan.run.eta, flashKey = planFlash)
        // Body 15/25 in the design's colors; code blocks become the design's CodeBlock.
        MaterialTheme(typography = MaterialTheme.typography.copy(bodyLarge = CortanaType.Body)) {
            CompositionLocalProvider(LocalCodeRenderer provides { language, code ->
                var expanded by remember(code) { mutableStateOf(false) }
                var copied by remember(code) { mutableStateOf(false) }
                CodeBlock(code, language = language, expanded = expanded, onToggleExpand = { expanded = !expanded }, copied = copied,
                    onCopy = { actions.copy(code); copied = true }, lineNumbers = true)
            }) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item.parts.forEach { p ->
                        when (p) {
                            is MessagePart.Markdown -> MarkdownView(p.blocks, prefs, actions.render, textColor = c.textBody)
                            // Tool activity is shown as structured events in the task panel's logs.
                            is MessagePart.ToolCall, is MessagePart.ToolResult -> Unit
                            else -> PartView(p, prefs, actions, c.textBody)
                        }
                    }
                }
            }
        }
        approval?.let { a -> ApprovalCard(Approval.Pending, a.title, a.tool, a.target, a.risk, { onRefuse(a) }, { onAllow(a) }) }
        decision?.let { d -> ApprovalCard(d.approval, "", d.tool, "", "", {}, {}) }
        if (live != null) LiveStatusLine(live.ifBlank { "Je travaille…" }, running = true)
        if (artifacts.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            artifacts.forEach { (id, name, meta) -> ArtifactChip(Symbols.Inventory2Fill, c.accentLink, name, meta, onClick = { onOpenArtifact(id) }) }
        }
        // An answer stopped before its first word has nothing to copy, read or continue.
        if (!streaming && item.text.isNotBlank()) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            item.variants?.let { v -> VariantNav(v, "Réponse", actions.switchTo) }
            SmallIconButton(Symbols.ContentCopy, "Copier la réponse", { actions.copy(item.text) })
            SmallIconButton(Symbols.Replay, "Régénérer", { actions.regenerate(item.last.id) })
            if (item.canContinue) SmallIconButton(Symbols.ArrowForward, "Continuer la réponse", { actions.continueAnswer(item.last.id) })
            SmallIconButton(Symbols.GraphicEq, "Lire à voix haute", { actions.speak(item.text) })
            Box {
                SmallIconButton(Symbols.MoreHoriz, "Plus d'actions sur la réponse", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Brancher depuis ici") }, onClick = { menu = false; actions.branchFrom(item.last.id) })
                    DropdownMenuItem(text = { Text("Dupliquer jusqu'ici") }, onClick = { menu = false; actions.fork(item.last.id) })
                    DropdownMenuItem(text = { Text("Citer") }, onClick = { menu = false; actions.quote(item.text) })
                    DropdownMenuItem(text = { Text("Épingler au contexte") }, onClick = { menu = false; actions.pin(item.last.id, item.text.take(60)) })
                    DropdownMenuItem(text = { Text("Enregistrer comme artefact") }, onClick = { menu = false; actions.saveAnswer(item.text, "reponse-cortana.md", item.last.id) })
                    DropdownMenuItem(text = { Text("Enregistrer dans Téléchargements") }, onClick = { menu = false; actions.saveToDownloads(item.text, item.last.id) })
                    DropdownMenuItem(text = { Text("Comparer avec d'autres modèles…") }, onClick = { menu = false; actions.compareAnswer() })
                    DropdownMenuItem(text = { Text("Détails techniques") }, onClick = { menu = false; actions.showDetails(item) })
                    DropdownMenuItem(text = { Text("Signaler un problème") }, onClick = { menu = false; actions.report(item.last.id) })
                }
            }
        }
    }
}
