package io.github.artisanguillonrenov.cortana.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentMode
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.MessageText
import io.github.artisanguillonrenov.cortana.core.chat.Source
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.chat.Variants
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.TimeFmt

/** Everything a message can ask; the screen decides (doc 03 §3.4–3.5). */
data class TimelineActions(
    val render: RenderActions = RenderActions(),
    val copy: (String) -> Unit = {},
    val edit: (messageId: String, text: String) -> Unit = { _, _ -> },
    val resend: (String) -> Unit = {},
    val branchFrom: (String) -> Unit = {},
    val fork: (String) -> Unit = {},
    val quote: (String) -> Unit = {},
    val regenerate: (String) -> Unit = {},
    val continueAnswer: (String) -> Unit = {},
    val switchTo: (String) -> Unit = {},
    val speak: (String) -> Unit = {},
    val report: (String) -> Unit = {},
    val pin: (messageId: String, label: String) -> Unit = { _, _ -> },
    val newChat: () -> Unit = {},
    val pickModel: () -> Unit = {},
    val compact: () -> Unit = {},
    val showDetails: (TimelineItem.Assistant) -> Unit = {},
    val stopLane: (Int) -> Unit = {},
    val deleteFrom: (String) -> Unit = {},
    val convertToTask: (String) -> Unit = {},
    val compareAnswer: () -> Unit = {},
    val saveToDownloads: (text: String, messageId: String) -> Unit = { _, _ -> },
    /** Images (doc 10 §10.4): thumbnail, analyse, save. */
    val thumbnail: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    val analyseImage: (AttachmentRef) -> Unit = {},
    val exportArtifact: (String) -> Unit = {},
    val mergeLanes: (ids: List<String>, analyse: Boolean) -> Unit = { _, _ -> },
    /** Saves an answer as an artifact linked to its message ("revenir au message source"). */
    val saveAnswer: (text: String, name: String, messageId: String) -> Unit = { _, _, _ -> },
)

/** The conversation (doc 03): virtualised, keyed, only changed items recompose while an answer streams. */
@Composable
fun Timeline(
    items: List<TimelineItem>, prefs: ChatPrefs, runStatus: String?, developer: Boolean, actions: TimelineActions,
    state: LazyListState, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null,
) {
    val t = LocalWorkspace.current
    val lastUserBefore = remember(items) {
        var last: String? = null
        items.associate { item -> (item.key to last).also { if (item is TimelineItem.User) last = item.message.id } }
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(t.gap * 2)) {
        if (header != null) item("header") { header() }
        items(items, key = { it.key }, contentType = { it::class.simpleName }) { item ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = t.readingWidth + 96.dp).fillMaxWidth()) {
                    when (item) {
                        is TimelineItem.User -> UserMessage(item, prefs, actions)
                        is TimelineItem.Assistant -> AssistantMessage(item, prefs, developer, actions)
                        is TimelineItem.System -> SystemItem(item, lastUserBefore[item.key], actions)
                        is TimelineItem.Compare -> CompareItem(item, prefs, actions)
                    }
                }
            }
        }
        val last = items.lastOrNull()
        val streaming = last is TimelineItem.Assistant && last.status == MessageStatus.STREAMING
        if (runStatus != null && !streaming) item("thinking") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = t.readingWidth + 96.dp).fillMaxWidth()) { Thinking(runStatus) }
            }
        }
    }
}

@Composable
fun Thinking(status: String) {
    val t = LocalWorkspace.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (t.reduceMotion) Text("…", color = t.muted) else CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = t.muted)
    }
}

// ---------------------------------------------------------------- owner message

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(item: TimelineItem.User, prefs: ChatPrefs, actions: TimelineActions) {
    val t = LocalWorkspace.current
    val m = item.message
    var editing by rememberSaveable(m.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer ce message ?") },
        text = { Text("Le message et tout ce qui le suit, sur toutes ses branches, seront supprimés de la tablette. Pour garder l'ancienne version, utilisez plutôt « Modifier ».") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.deleteFrom(m.id) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        RoleLabel("Vous", if (prefs.timestamps) TimeFmt.short(m.createdAt) else null)
        if (item.meta.attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            item.meta.attachments.forEach { a -> if (a.mime.startsWith("image/")) ImageAttachment(a, actions) else AttachmentChip(a) }
        }
        if (editing) EditBox(m.text, onCancel = { editing = false }, onSend = { actions.edit(m.id, it); editing = false })
        else Surface(
            color = t.userBubble, contentColor = t.onUserBubble, shape = RoundedCornerShape(t.radiusCard),
            modifier = Modifier.widthIn(max = t.readingWidth * 0.85f).combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Actions du message"),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Column { item.parts.filter { it is MessagePart.Markdown || it is MessagePart.Plain }.forEach { PartView(it, prefs, actions, t.onUserBubble) } }
                }
            }
        }
        if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
            item.versions?.let { VariantNav(it, "Version", actions.switchTo) }
            TextButton(onClick = { editing = true }) { Text("Modifier") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'actions sur votre message") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Copier") }, onClick = { menu = false; actions.copy(m.text) })
                    DropdownMenuItem(text = { Text("Réenvoyer") }, onClick = { menu = false; actions.resend(m.text) })
                    DropdownMenuItem(text = { Text("Brancher depuis ici") }, onClick = { menu = false; actions.branchFrom(m.id) })
                    DropdownMenuItem(text = { Text("Dupliquer jusqu'ici") }, onClick = { menu = false; actions.fork(m.id) })
                    DropdownMenuItem(text = { Text("Citer") }, onClick = { menu = false; actions.quote(m.text) })
                    DropdownMenuItem(text = { Text("Épingler au contexte") }, onClick = { menu = false; actions.pin(m.id, m.text.take(60)) })
                    DropdownMenuItem(text = { Text("Convertir en tâche") }, onClick = { menu = false; actions.convertToTask(m.text) })
                    DropdownMenuItem(text = { Text("Supprimer ce message et la suite…") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
}

@Composable
internal fun EditBox(initial: String, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val t = LocalWorkspace.current
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().widthIn(max = t.readingWidth), horizontalAlignment = Alignment.End) {
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), label = { Text("Nouvelle version") })
        Text("L'ancienne version et sa suite restent accessibles (« Version 1/2 »).", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 4.dp))
        Row {
            TextButton(onClick = onCancel) { Text("Annuler") }
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank() && text != initial) { Text("Envoyer la nouvelle version") }
        }
    }
}

// ---------------------------------------------------------------- Cortana's answer

@Composable
private fun AssistantMessage(item: TimelineItem.Assistant, prefs: ChatPrefs, developer: Boolean, actions: TimelineActions) {
    val t = LocalWorkspace.current
    var menu by remember { mutableStateOf(false) }
    val streaming = item.status == MessageStatus.STREAMING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.gap)) {
        val badge = if (prefs.modelBadges) item.meta.modelId?.let { id -> (item.meta.providerName?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + id } else null
        RoleLabel("Cortana", listOfNotNull(badge, if (prefs.timestamps) TimeFmt.short(item.last.createdAt) else null).joinToString(" · ").ifEmpty { null })
        val body = item.parts
        val tools = body.filterIsInstance<MessagePart.ToolResult>()
        if (tools.isNotEmpty() && prefs.showActivity) ToolGroup(tools)
        body.filter { it !is MessagePart.ToolResult && !(it is MessagePart.Citations && !prefs.showSources) }.forEach { p ->
            when {
                p is MessagePart.SystemEvent && (p.kind == "stopped" || p.kind == "interrupted") -> CutNotice(p, item, actions)
                p is MessagePart.Error -> ErrorNotice(p, onRetry = { actions.regenerate(item.first.id) })
                else -> PartView(p, prefs, actions, MaterialTheme.colorScheme.onSurface)
            }
        }
        if (streaming && item.text.isEmpty()) Thinking("Cortana écrit…")
        if (!streaming) Row(verticalAlignment = Alignment.CenterVertically) {
            item.variants?.let { VariantNav(it, "Réponse", actions.switchTo) }
            TextButton(onClick = { actions.copy(item.text) }) { Text("Copier") }
            TextButton(onClick = { actions.speak(item.text) }) { Text("Lire") }
            TextButton(onClick = { actions.regenerate(item.first.id) }) { Text("Régénérer") }
            if (item.canContinue && item.status == MessageStatus.COMPLETE) TextButton(onClick = { actions.continueAnswer(item.last.id) }) { Text("Continuer") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Plus d'actions sur la réponse") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Copier le Markdown") }, onClick = { menu = false; actions.copy(item.text) })
                    DropdownMenuItem(text = { Text("Copier le texte seul") }, onClick = { menu = false; actions.copy(MessageText.plainBlocks(io.github.artisanguillonrenov.cortana.core.chat.Markdown.parse(item.text))) })
                    DropdownMenuItem(text = { Text("Enregistrer comme artefact") }, onClick = { menu = false; actions.saveAnswer(item.text, "reponse-cortana.md", item.last.id) })
                    DropdownMenuItem(text = { Text("Enregistrer dans Téléchargements") }, onClick = { menu = false; actions.saveToDownloads(item.text, item.last.id) })
                    DropdownMenuItem(text = { Text("Comparer avec d'autres modèles…") }, onClick = { menu = false; actions.compareAnswer() })
                    DropdownMenuItem(text = { Text("Brancher depuis ici") }, onClick = { menu = false; actions.branchFrom(item.last.id) })
                    DropdownMenuItem(text = { Text("Dupliquer jusqu'ici") }, onClick = { menu = false; actions.fork(item.last.id) })
                    DropdownMenuItem(text = { Text("Citer") }, onClick = { menu = false; actions.quote(item.text) })
                    DropdownMenuItem(text = { Text("Épingler au contexte") }, onClick = { menu = false; actions.pin(item.last.id, item.text.take(60)) })
                    DropdownMenuItem(text = { Text("Signaler un problème") }, onClick = { menu = false; actions.report(item.last.id) })
                    if (developer) DropdownMenuItem(text = { Text("Détails techniques") }, onClick = { menu = false; actions.showDetails(item) })
                }
            }
        }
    }
}

@Composable
private fun RoleLabel(role: String, detail: String?) {
    val t = LocalWorkspace.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Text(role, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (detail != null) Text("  $detail", style = MaterialTheme.typography.labelSmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun VariantNav(v: Variants, what: String, onSwitch: (String) -> Unit) {
    val t = LocalWorkspace.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = false) { contentDescription = "$what ${v.label}" }) {
        TextButton(onClick = { onSwitch(v.ids[v.index - 1]) }, enabled = v.index > 0) { Text("‹", style = MaterialTheme.typography.titleMedium) }
        Text("$what ${v.label}", style = MaterialTheme.typography.labelMedium, color = t.muted)
        TextButton(onClick = { onSwitch(v.ids[v.index + 1]) }, enabled = v.index < v.total - 1) { Text("›", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Stopped or interrupted (doc 05 §5.6): what was received stays, with Continue and Regenerate. */
@Composable
private fun CutNotice(p: MessagePart.SystemEvent, item: TimelineItem.Assistant, actions: TimelineActions) {
    val t = LocalWorkspace.current
    Surface(color = t.warning, contentColor = t.onWarning, shape = RoundedCornerShape(t.radiusSmall)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.kind == "stopped") "⏹ " else "⚡ ") + p.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { actions.continueAnswer(item.last.id) }) { Text("Continuer") }
            TextButton(onClick = { actions.regenerate(item.first.id) }) { Text("Régénérer") }
        }
    }
}

/** Inline error (doc 05 §5.7): an understandable reason, retry, technical details hidden by default. */
@Composable
private fun ErrorNotice(p: MessagePart.Error, onRetry: () -> Unit) {
    val t = LocalWorkspace.current
    var details by remember { mutableStateOf(false) }
    Surface(color = t.critical, contentColor = t.onCritical, shape = RoundedCornerShape(t.radiusSmall)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠ " + p.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer") }
                if (p.detail != null) TextButton(onClick = { details = !details }) { Text(if (details) "Masquer" else "Détails") }
            }
            if (details && p.detail != null) Text(p.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

// ---------------------------------------------------------------- parts

@Composable
fun PartView(p: MessagePart, prefs: ChatPrefs, actions: TimelineActions, color: androidx.compose.ui.graphics.Color) {
    when (p) {
        is MessagePart.Markdown -> MarkdownView(p.blocks, prefs, actions.render, textColor = color)
        is MessagePart.Plain -> Text(p.text, style = MaterialTheme.typography.bodyLarge, color = color)
        is MessagePart.Citations -> SourcesView(p.sources, actions.render)
        is MessagePart.File -> AttachmentChip(p.attachment)
        // A picture (sent, generated, retouched) is shown as a picture; a chip only when it cannot be decoded.
        is MessagePart.Image -> ImageAttachment(p.attachment, actions)
        is MessagePart.Artifact -> AssistChip(onClick = { actions.render.openLink("artifact:${p.artifactId}") }, label = { Text("📄 ${p.name}") })
        is MessagePart.SystemEvent -> SystemLine(p.kind, p.text)
        is MessagePart.Error -> ErrorNotice(p) {}
        is MessagePart.ToolCall -> SystemLine("tool", "${p.label} : ${p.argsSummary}")
        is MessagePart.ToolResult -> ToolGroup(listOf(p))
        is MessagePart.Council -> CouncilPart(p)
        // Images, videos and page cards: typed views, links opened through the sanitised link action.
        is MessagePart.WebResults -> io.github.artisanguillonrenov.cortana.ui.components.WebResultsView(p.items, onOpen = actions.render.openLink)
    }
}

@Composable
private fun CouncilPart(p: MessagePart.Council) {
    val summary = remember(p.summaryJson) {
        runCatching { AppJson.decodeFromString(io.github.artisanguillonrenov.cortana.core.council.CouncilSummary.serializer(), p.summaryJson) }.getOrNull()
    }
    if (summary != null) io.github.artisanguillonrenov.cortana.ui.council.CouncilSummaryCard(summary, false) else SystemLine("council", "Résumé du conseil indisponible.")
}

@Composable
fun AttachmentChip(a: AttachmentRef) {
    val t = LocalWorkspace.current
    val mode = when (a.mode) { AttachmentMode.READ -> "lu"; AttachmentMode.ANALYZE -> "à analyser"; AttachmentMode.REFERENCE -> "référence" }
    val icon = when { a.mime.startsWith("image/") -> "🖼"; a.mime.startsWith("audio/") -> "🎵"; a.mime.startsWith("video/") -> "🎞"; else -> "📄" }
    Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.border(1.dp, t.border, RoundedCornerShape(t.radiusSmall))) {
        Text("$icon ${a.name} · ${sizeLabel(a.sizeBytes)} · $mode", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** An image in the conversation: thumbnail, zoom, analyse, save (doc 10 §10.4). */
@Composable
internal fun ImageAttachment(a: AttachmentRef, actions: TimelineActions) {
    val t = LocalWorkspace.current
    var image by remember(a.artifactId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var zoom by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(a.artifactId) { image = runCatching { actions.thumbnail(a.artifactId) }.getOrNull() }
    val img = image
    if (img == null) { AttachmentChip(a); return }
    androidx.compose.foundation.Image(img, contentDescription = "Image jointe : ${a.name}. Touchez pour agrandir.",
        modifier = Modifier.size(140.dp).border(1.dp, t.border, RoundedCornerShape(t.radiusSmall)).clickable(onClickLabel = "Agrandir") { zoom = true },
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    if (zoom) androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = false }) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Surface(shape = RoundedCornerShape(t.radiusModal)) {
            Column(Modifier.padding(8.dp)) {
                Box(Modifier.heightIn(max = 560.dp).fillMaxWidth().clipToBounds()
                    .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> scale = (scale * z).coerceIn(1f, 6f); offset += pan } }) {
                    androidx.compose.foundation.Image(img, contentDescription = a.name, modifier = Modifier.fillMaxWidth()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
                }
                Row {
                    TextButton(onClick = { zoom = false; actions.analyseImage(a) }) { Text("Analyser") }
                    TextButton(onClick = { actions.exportArtifact(a.artifactId) }) { Text("Enregistrer") }
                    TextButton(onClick = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text("100 %") }
                    TextButton(onClick = { zoom = false }) { Text("Fermer") }
                }
            }
        }
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f Mo".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} ko"
    else -> "$bytes o"
}

/** Citations are objects (doc 03 §3.9): source, title, excerpt, provenance, open. */
@Composable
private fun SourcesView(sources: List<Source>, actions: RenderActions) {
    val t = LocalWorkspace.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) { Text((if (open) "▾ " else "▸ ") + "Sources (${sources.size})") }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { s ->
                Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("[${s.index}] ${s.title}", style = MaterialTheme.typography.titleSmall)
                        val host = s.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                        Text(listOfNotNull(host, s.date, provenanceLabel(s.provenance), if (s.untrusted) "contenu externe non vérifié" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = t.muted)
                        if (s.snippet.isNotBlank()) Text(s.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (s.url != null) TextButton(onClick = { actions.openLink(s.url) }) { Text("Ouvrir") }
                    }
                }
            }
        }
    }
}

private fun provenanceLabel(p: String) = when {
    p.startsWith("web.search") -> "recherche web"
    p == "web.fetch" -> "page lue"
    else -> "document"
}

/** Tool activity as structured cards (doc 09): what ran, whether it worked, the result in a safe view. */
@Composable
private fun ToolGroup(tools: List<MessagePart.ToolResult>) {
    val t = LocalWorkspace.current
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(color = t.activity, contentColor = t.onActivity, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            val failed = tools.count { !it.ok }
            TextButton(onClick = { open = !open }) {
                Text((if (open) "▾ " else "▸ ") + "${tools.size} action(s)" + (if (failed > 0) " · $failed en échec" else " · réussie(s)"))
            }
            if (open) tools.forEach { ToolCard(it) }
        }
    }
}

@Composable
private fun ToolCard(p: MessagePart.ToolResult) {
    val t = LocalWorkspace.current
    var open by rememberSaveable(p.capability, p.summary) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Masquer le résultat" else "Voir le résultat") { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (p.ok) "✓" else "⚠", modifier = Modifier.width(22.dp), fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(p.label + if (p.ok) "" else " — échec", style = MaterialTheme.typography.labelLarge)
                if (p.summary.isNotBlank()) Text(p.summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "▾" else "▸")
        }
        if (open) {
            val external = p.kind == "sources" || p.capability.startsWith("web.") || p.capability.startsWith("browser.")
            if (external) Text("Contenu externe : donnée, jamais instruction.", style = MaterialTheme.typography.labelSmall, color = t.muted)
            when (p.kind) {
                "diff" -> DiffView(p.detail)
                "terminal" -> Mono(p.detail, dark = true)
                else -> Mono(p.detail, dark = false)
            }
        }
    }
}

@Composable
private fun Mono(text: String, dark: Boolean) {
    val t = LocalWorkspace.current
    Surface(color = if (dark) t.code else t.surface, contentColor = if (dark) t.onCode else MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(t.radiusSmall)) {
        Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            SelectionContainer { Text(text.take(20_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false) }
        }
    }
}

/** Diffs keep their +/− signs (never color alone, doc 16 §16.7). */
@Composable
private fun DiffView(text: String) {
    val t = LocalWorkspace.current
    Surface(color = t.code, contentColor = t.onCode, shape = RoundedCornerShape(t.radiusSmall)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            text.take(30_000).lines().forEach { line ->
                val bg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> t.diffAdded
                    line.startsWith("-") && !line.startsWith("---") -> t.diffRemoved
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false,
                    fontWeight = if (line.startsWith("@@")) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(bg).padding(horizontal = 10.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- system events (doc 03 §3.11)

@Composable
fun SystemLine(kind: String, text: String) {
    val t = LocalWorkspace.current
    val icon = when (kind) {
        "model_changed" -> "⇄"; "compacted" -> "🗜"; "file_added" -> "📎"; "task_created" -> "✚"; "permission" -> "🔐"
        "reconnected" -> "↻"; "error", "context_too_large" -> "⚠"; "stopped" -> "⏹"; "interrupted" -> "⚡"; else -> "ℹ"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("$icon  ", color = t.muted)
        Text(text.removePrefix("ℹ️ ").removePrefix("⚠️ "), style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

@Composable
internal fun SystemItem(item: TimelineItem.System, lastUserId: String?, actions: TimelineActions) {
    val t = LocalWorkspace.current
    when (val p = item.part) {
        is MessagePart.Council -> CouncilPart(p)
        is MessagePart.SystemEvent -> if (p.kind == "context_too_large") {
            // HTTP 413 (doc 05 §5.8): never a bare error — reduce, change model, or start fresh.
            Surface(color = t.warning, contentColor = t.onWarning, shape = RoundedCornerShape(t.radiusCard), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚠ Contexte trop volumineux", style = MaterialTheme.typography.titleSmall)
                    Text("La réduction automatique n'a pas suffi pour ce modèle.", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = actions.compact) { Text("Réduire le contexte") }
                        if (lastUserId != null) TextButton(onClick = { actions.regenerate(lastUserId) }) { Text("Réessayer") }
                        TextButton(onClick = actions.pickModel) { Text("Changer de modèle") }
                        TextButton(onClick = actions.newChat) { Text("Nouvelle discussion") }
                    }
                }
            }
        } else SystemLine(p.kind, p.text)
        else -> SystemLine("notice", item.message.text)
    }
}

/**
 * 2 to 4 answers to one message (doc 08 §8.2): columns on a wide screen, tabs on a phone. The conversation
 * follows the selected lane; merging or comparing is a new, visible request.
 */
@Composable
internal fun CompareItem(item: TimelineItem.Compare, prefs: ChatPrefs, actions: TimelineActions) {
    val t = LocalWorkspace.current
    val running = item.lanes.any { it.status == MessageStatus.STREAMING }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.gap)) {
        RoleLabel("Comparaison", "${item.lanes.size} modèles" + if (running) " · en cours" else "")
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 900.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.lanes.forEach { lane -> LaneCard(lane, lane.id == item.selected, prefs, actions, Modifier.weight(1f)) }
            } else {
                var tab by rememberSaveable(item.group) { mutableStateOf(item.lanes.indexOfFirst { it.id == item.selected }.coerceAtLeast(0)) }
                androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(0, item.lanes.lastIndex), edgePadding = 0.dp) {
                    item.lanes.forEachIndexed { i, lane ->
                        val m = io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline.meta(lane)
                        androidx.compose.material3.Tab(selected = i == tab, onClick = { tab = i }, text = { Text((m.modelId ?: "modèle ${i + 1}").take(24), maxLines = 1) })
                    }
                }
                item.lanes.getOrNull(tab)?.let { lane -> LaneCard(lane, lane.id == item.selected, prefs, actions, Modifier.fillMaxWidth()) }
            }
        }
        val done = item.lanes.filter { it.status == MessageStatus.COMPLETE && it.text.isNotBlank() }
        if (!running && done.size >= 2) Row {
            TextButton(onClick = { actions.mergeLanes(done.map { it.id }, false) }) { Text("Fusionner") }
            TextButton(onClick = { actions.mergeLanes(done.map { it.id }, true) }) { Text("Faire comparer par Cortana") }
        }
    }
}

@Composable
private fun LaneCard(lane: io.github.artisanguillonrenov.cortana.core.memory.MessageEntity, selected: Boolean, prefs: ChatPrefs, actions: TimelineActions, modifier: Modifier) {
    val t = LocalWorkspace.current
    val m = io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline.meta(lane)
    val blocks = remember(lane.text) { io.github.artisanguillonrenov.cortana.core.chat.Markdown.parse(lane.text) }
    val status = when (lane.status) {
        MessageStatus.STREAMING -> "en cours…"; MessageStatus.STOPPED -> "arrêtée"; MessageStatus.ERROR -> "échec"; MessageStatus.INTERRUPTED -> "interrompue"; else -> if (selected) "✓ suivie" else "terminée"
    }
    Surface(color = if (selected) t.elevated else t.surface, shape = RoundedCornerShape(t.radiusCard),
        modifier = modifier.border(if (selected) 2.dp else 1.dp, t.border, RoundedCornerShape(t.radiusCard))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((m.modelId ?: "modèle ${m.lane ?: "?"}") + (m.providerName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() })
            Text(status, style = MaterialTheme.typography.labelSmall, color = t.muted)
            if (lane.text.isNotBlank()) MarkdownView(blocks, prefs, actions.render)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (lane.status == MessageStatus.STREAMING) TextButton(onClick = { actions.stopLane(m.lane ?: 0) }) { Text("⏹ Arrêter") }
                else {
                    if (!selected && lane.status == MessageStatus.COMPLETE) TextButton(onClick = { actions.switchTo(lane.id) }) { Text("Suivre cette réponse") }
                    if (lane.text.isNotBlank()) {
                        TextButton(onClick = { actions.copy(lane.text) }) { Text("Copier") }
                        TextButton(onClick = { actions.saveAnswer(lane.text, "reponse-${(m.modelId ?: "modele").substringAfterLast('/')}.md", lane.id) }) { Text("Enregistrer") }
                    }
                }
            }
        }
    }
}

@Composable
fun Gap() = Spacer(Modifier.size(LocalWorkspace.current.gap))
