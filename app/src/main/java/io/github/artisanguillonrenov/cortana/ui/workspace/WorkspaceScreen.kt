package io.github.artisanguillonrenov.cortana.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalOpenDrawer
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

/** Layout class from the available width (doc 01 §1.2–1.4, doc 16 §16.10). */
enum class WorkspaceLayout { COMPACT, MEDIUM, WIDE }

fun layoutFor(width: Dp): WorkspaceLayout = when {
    width >= 1100.dp -> WorkspaceLayout.WIDE
    width >= 720.dp -> WorkspaceLayout.MEDIUM
    else -> WorkspaceLayout.COMPACT
}

/**
 * Cortana Chat Workspace (D-20260930-068): sidebar · conversation · context panel. Tablet first:
 * landscape shows three columns, portrait keeps the conversation dominant with panels over it, a
 * phone gets a drawer and a bottom sheet. The composer stays reachable above the keyboard.
 */
@Composable
fun WorkspaceScreen(openSessionId: String?, openMessageId: String? = null, onOpenProviders: () -> Unit, onOpenSettings: () -> Unit, onUseClassic: () -> Unit) {
    val c = LocalContainer.current
    val vm: WorkspaceViewModel = viewModel { WorkspaceViewModel(c) }
    LaunchedEffect(openSessionId, openMessageId) { openSessionId?.let { vm.open(it, openMessageId) } }
    val prefs by vm.prefs.collectAsState()
    val share by ShareInbox.pending.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(share) { if (share != null) ShareInbox.take()?.let { vm.receiveShare(it, ctx) } }
    WorkspaceTheme(prefs) {
        Surface(color = LocalWorkspace.current.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                WorkspaceBody(vm, prefs, layoutFor(maxWidth), onOpenProviders, onOpenSettings, onUseClassic)
            }
        }
    }
}

@Composable
private fun WorkspaceBody(vm: WorkspaceViewModel, prefs: ChatPrefs, layout: WorkspaceLayout, onOpenProviders: () -> Unit, onOpenSettings: () -> Unit, onUseClassic: () -> Unit) {
    val t = LocalWorkspace.current
    val session by vm.session.collectAsState()
    val items by vm.timeline.collectAsState()
    val active by vm.active.collectAsState()
    val runs by vm.runs.collectAsState()
    val queue by vm.queue.collectAsState()
    val draft by vm.draft.collectAsState()
    val files by vm.draftFiles.collectAsState()
    val level by vm.contextLevel.collectAsState()
    val online by vm.online.collectAsState()
    val halted by vm.halted.collectAsState()
    val developer by vm.developer.collectAsState()
    val providers by vm.providers.collectAsState()
    val announcement by vm.announcement.collectAsState()
    val c = LocalContainer.current
    val voice by vm.voice.collectAsState()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    var sidebar by rememberSaveable { mutableStateOf(layout != WorkspaceLayout.COMPACT) }
    var panel by rememberSaveable { mutableStateOf(false) }
    var panelTab by rememberSaveable { mutableStateOf("context") }
    var modelPicker by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var details by remember { mutableStateOf<TimelineItem.Assistant?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Resizable columns on a wide screen (doc 01 §1.2), in dp.
    var sidebarWidth by rememberSaveable { mutableStateOf(290f) }
    var panelWidth by rememberSaveable { mutableStateOf(360f) }
    LaunchedEffect(layout) { if (layout == WorkspaceLayout.COMPACT) sidebar = false }

    val sid = session?.id
    val busyHere = active?.sessionId == sid && active != null
    val runStatus = if (busyHere) (runs[sid]?.status?.takeIf { it.isNotBlank() } ?: active?.status ?: "Préparation…") else null
    val focus = remember { FocusRequester() }

    fun copy(text: String) { clipboard.setText(AnnotatedString(text)); vm.say("Copié") }
    fun openLink(url: String) {
        if (url.startsWith("artifact:")) { panelTab = "files"; panel = true; return }
        runCatching { uri.openUri(url) }.onFailure { vm.say("Lien impossible à ouvrir") }
    }
    val render = RenderActions(openLink = ::openLink, openCitation = { panelTab = "sources"; panel = true }, saveText = { text, name -> vm.saveArtifact(text, name) }, say = vm::say)
    val actions = TimelineActions(
        render = render, copy = ::copy, edit = { id, text -> vm.edit(id, text) }, resend = vm::resend, branchFrom = { vm.branchFrom(it) }, fork = { vm.fork(it) },
        quote = vm::quote, regenerate = { vm.regenerate(it) }, continueAnswer = { vm.continueAnswer(it) }, switchTo = { vm.switchTo(it) },
        speak = { vm.speak(it) }, report = { vm.reportProblem(it) }, pin = { id, label -> vm.pin(id, label) }, newChat = { vm.newChat() },
        pickModel = { modelPicker = true }, compact = { vm.compactNow() }, showDetails = { details = it },
        saveAnswer = { text, name, id -> vm.saveArtifact(text, name, messageId = id) },
        stopLane = vm::stopLane, mergeLanes = { ids, analyse -> vm.mergeLanes(ids, analyse) },
        thumbnail = { id -> vm.thumbnail(id) }, analyseImage = { vm.analyseImage(it) }, exportArtifact = { vm.exportArtifact(it) },
        deleteFrom = { vm.deleteFrom(it) }, convertToTask = vm::convertToTask, compareAnswer = { vm.say("Comparaison : choisissez 2 à 4 modèles, puis renvoyez la question."); modelPicker = true },
        saveToDownloads = { text, id -> vm.saveToDownloads(text, id) },
    )
    val commands = {
        listOf(
            SlashCommand("new", "nouvelle discussion") { vm.newChat() },
            SlashCommand("model", "choisir le modèle") { modelPicker = true },
            SlashCommand("mode", "changer de mode") { palette = true },
            SlashCommand("web", "mode recherche web") { vm.setMode(ChatMode.RESEARCH) },
            SlashCommand("tools", "outils : " + (Toolsets.labels[session?.toolset] ?: "Complet")) { vm.setToolset(if (session?.toolset == Toolsets.FULL) Toolsets.ASSISTANT else Toolsets.FULL) },
            SlashCommand("file", "joindre un fichier (menu +)") { vm.say("Utilisez le bouton + pour joindre un fichier.") },
            SlashCommand("context", "panneau de contexte") { panelTab = "context"; panel = true },
            SlashCommand("remember", "retenir quelque chose") { vm.setDraft("Retiens que ") },
            SlashCommand("council", "mode conseil de réflexion") { vm.setMode(ChatMode.COUNCIL) },
            SlashCommand("compare", "comparer des modèles") { vm.say("Comparaison : choisissez 2 à 4 modèles dans le sélecteur de modèle.") ; modelPicker = true },
            SlashCommand("voice", "conversation vocale") { c.voice.startHandsFree() },
            SlashCommand("project", "projets (barre latérale)") { sidebar = true },
        )
    }
    val composerState = ComposerState(draft, files, queue, active != null, prefs, ChatMode.of(session?.mode), level, voice.phase != io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.OFF)
    val composerActions = ComposerActions(
        setText = vm::setDraft, send = vm::send, stop = vm::stop, attach = { n, m, s, o -> vm.attach(n, m, s, o) },
        setAttachmentMode = vm::setAttachmentMode, removeAttachment = vm::removeAttachment, addAttachment = vm::addAttachment,
        setMode = { vm.setMode(it) }, commands = commands, mentions = { q -> vm.mentions(q) },
        queueCancel = { vm.cancelQueued(it) }, queueConfirm = { vm.confirmQueued(it) }, queueMove = { id, up -> vm.moveQueued(id, up) },
        queueSendNow = { vm.sendNow(it) }, handsFree = { c.voice.startHandsFree() }, openPalette = { palette = true },
    )

    val main: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().imePadding()) {
            Header(
                session, layout, online, halted, busyHere, level, prefs, providers.firstOrNull { it.id == session?.providerId }?.displayName, developer,
                onMenu = LocalOpenDrawer.current, onSidebar = { sidebar = !sidebar }, onPanel = { panel = !panel }, onModel = { modelPicker = true },
                onRename = { renaming = session }, onMode = { vm.setMode(it) }, onFork = { vm.fork() }, onClassic = onUseClassic, onSettings = onOpenSettings,
                onNewChat = { vm.newChat() }, onExport = { fmt -> vm.export(fmt) },
            )
            if (providers.isEmpty()) Surface(color = t.warning, contentColor = t.onWarning, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProviders)) {
                Text("Aucun fournisseur de modèle : touchez ici pour en ajouter un.", Modifier.padding(12.dp))
            }
            TimelineArea(vm, items, prefs, runStatus, developer, actions, Modifier.weight(1f), announcement?.text) { s -> vm.setDraft(s); scope.launch { runCatching { focus.requestFocus() } } }
            val approval by vm.pendingApproval.collectAsState()
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Approval requested by this conversation's task (doc 09, doc 07 §7.6): refuse here, approve only on the secure screen.
                approval?.takeIf { busyHere }?.let { p ->
                    Box(Modifier.widthIn(max = t.readingWidth + 96.dp).padding(bottom = 8.dp)) { ApprovalCard(p, onRefuse = { vm.refuseApproval(p.id) }, onReview = { vm.reviewApproval(p.id) }) }
                }
                if (busyHere) ActivityBar(active, runStatus, Modifier.widthIn(max = t.readingWidth + 96.dp).padding(bottom = 6.dp), onStop = vm::stop, onDetails = { panelTab = "activity"; panel = true })
                if (voice.phase != io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.OFF) VoiceOverlay(voice, Modifier.widthIn(max = t.readingWidth + 96.dp).padding(bottom = 6.dp),
                    onInterrupt = vm::interruptVoice, onBackToText = { vm.stopVoice(); scope.launch { runCatching { focus.requestFocus() } } }, onStop = { vm.stopVoice(); vm.stop() })
                val reading by vm.reading.collectAsState()
                if (reading != io.github.artisanguillonrenov.cortana.util.Speaker.Reading.IDLE) ReadingBar(reading, Modifier.widthIn(max = t.readingWidth + 96.dp).padding(bottom = 6.dp),
                    onPause = vm::pauseReading, onResume = vm::resumeReading, onStop = vm::stopReading)
                Composer(composerState, composerActions, Modifier.widthIn(max = t.readingWidth + 96.dp), focus)
            }
        }
    }

    // Keyboard (doc 02 §2.8, doc 10 §10.6): Ctrl+N new, Ctrl+F search, Ctrl+B sidebar, Ctrl+I panel, Échap stops.
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        when {
            ctrl && e.key == Key.N -> { vm.newChat(); true }
            ctrl && e.key == Key.F -> { searching = true; true }
            ctrl && e.key == Key.B -> { sidebar = !sidebar; true }
            ctrl && e.key == Key.I -> { panel = !panel; true }
            e.key == Key.Escape && active != null -> { vm.stop(); true }
            else -> false
        }
    }
    Box(Modifier.fillMaxSize().then(keys)) {
        when (layout) {
            WorkspaceLayout.WIDE -> Row(Modifier.fillMaxSize()) {
                if (sidebar) {
                    Sidebar(vm, Modifier.width(sidebarWidth.dp).fillMaxHeight(), onRename = { renaming = it }, onClose = null, onSearch = { searching = true })
                    ResizeHandle("Redimensionner la barre latérale") { d -> sidebarWidth = (sidebarWidth + d).coerceIn(220f, 440f) }
                }
                Box(Modifier.weight(1f)) { main() }
                if (panel) {
                    ResizeHandle("Redimensionner le panneau") { d -> panelWidth = (panelWidth - d).coerceIn(300f, 560f) }
                    ContextPanel(vm, items, panelTab, { panelTab = it }, Modifier.width(panelWidth.dp).fillMaxHeight(), onClose = { panel = false })
                }
            }
            WorkspaceLayout.MEDIUM -> {
                Row(Modifier.fillMaxSize()) {
                    if (sidebar) { Sidebar(vm, Modifier.width(270.dp).fillMaxHeight(), onRename = { renaming = it }, onClose = null, onSearch = { searching = true }); VerticalDivider(color = t.border) }
                    Box(Modifier.weight(1f)) { main() }
                }
                OverlaySheet(panel, fromStart = false, width = 380.dp, onDismiss = { panel = false }) {
                    ContextPanel(vm, items, panelTab, { panelTab = it }, Modifier.fillMaxSize(), onClose = { panel = false })
                }
            }
            WorkspaceLayout.COMPACT -> {
                main()
                OverlaySheet(sidebar, fromStart = true, width = 320.dp, onDismiss = { sidebar = false }) {
                    Sidebar(vm, Modifier.fillMaxSize(), onRename = { renaming = it }, onClose = { sidebar = false }, onSearch = { searching = true })
                }
                if (panel) ModalBottomSheet(onDismissRequest = { panel = false }) {
                    ContextPanel(vm, items, panelTab, { panelTab = it }, Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 640.dp), onClose = { panel = false })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (modelPicker) ModelPicker(vm, session, onDismiss = { modelPicker = false }, onOpenProviders = { modelPicker = false; onOpenProviders() })
    if (palette) CommandPalette(commands() + ChatMode.entries.filter { it != ChatMode.COMPARE }.map { m -> SlashCommand("mode-${m.wire}", "mode ${m.label}") { vm.setMode(m) } }) { palette = false }
    renaming?.let { s -> RenameDialog(s.title, onDismiss = { renaming = null }) { vm.rename(s.id, it); renaming = null } }
    details?.let { d -> DetailsDialog(d) { details = null } }
    if (searching) SearchDialog(vm, onDismiss = { searching = false }) { sid, mid -> searching = false; vm.open(sid, mid); if (layout == WorkspaceLayout.COMPACT) sidebar = false }
}

/** A draggable column edge (8 dp touch area, 1 dp line), labelled for accessibility services. */
@Composable
private fun ResizeHandle(label: String, onDelta: (Float) -> Unit) {
    val t = LocalWorkspace.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val state = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDelta(px / density.density) }
    Box(Modifier.width(8.dp).fillMaxHeight().draggable(state, androidx.compose.foundation.gestures.Orientation.Horizontal).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        VerticalDivider(color = t.border)
    }
}

/** Hands-free voice session (doc 10 §10.2): listening, transcript, answer, interrupt, back to text, STOP. */
@Composable
internal fun VoiceOverlay(v: io.github.artisanguillonrenov.cortana.core.voice.VoiceState, modifier: Modifier, onInterrupt: () -> Unit, onBackToText: () -> Unit, onStop: () -> Unit) {
    val t = LocalWorkspace.current
    val phase = when (v.phase) {
        io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.LISTENING -> "🎙 J'écoute…"
        io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.THINKING -> "💭 Je réfléchis…"
        io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.SPEAKING -> "🔊 Je réponds…"
        else -> "Voix"
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(t.radiusCard),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(phase + if (v.handsFree) " · mains libres" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (v.micOn) Text("micro ouvert", style = MaterialTheme.typography.labelSmall)
            }
            val heard = v.partial.ifBlank { v.lastHeard.orEmpty() }
            if (heard.isNotBlank()) Text("« $heard »", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (v.phase == io.github.artisanguillonrenov.cortana.core.voice.VoicePhase.SPEAKING) TextButton(onClick = onInterrupt) { Text("Interrompre") }
                TextButton(onClick = onBackToText) { Text("Revenir au texte") }
                TextButton(onClick = onStop) { Text("⏹ STOP") }
            }
        }
    }
}

/** Reading an answer aloud (doc 10 §10.3): pause, resume, stop. */
@Composable
internal fun ReadingBar(r: io.github.artisanguillonrenov.cortana.util.Speaker.Reading, modifier: Modifier, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    val t = LocalWorkspace.current
    Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusCard), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (r == io.github.artisanguillonrenov.cortana.util.Speaker.Reading.PAUSED) "Lecture en pause" else "🔊 Lecture en cours", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (r == io.github.artisanguillonrenov.cortana.util.Speaker.Reading.PAUSED) TextButton(onClick = onResume) { Text("Reprendre") }
            else TextButton(onClick = onPause) { Text("Pause") }
            TextButton(onClick = onStop) { Text("Arrêter") }
        }
    }
}

/** Task progress (doc 07 §7.7): one stable line, steps on demand, STOP always reachable, never reasoning. */
@Composable
private fun ActivityBar(a: io.github.artisanguillonrenov.cortana.core.orchestrator.ActiveTaskState?, status: String?, modifier: Modifier, onStop: () -> Unit, onDetails: () -> Unit) {
    val t = LocalWorkspace.current
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(color = t.activity, contentColor = t.onActivity, shape = RoundedCornerShape(t.radiusCard), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!t.reduceMotion) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text("…")
                Text("  " + (status ?: "Cortana travaille…") + (a?.let { if (it.toolCalls > 0) " · ${it.toolCalls} action(s)" else "" } ?: "") + (if (a?.usesUi == true) " · pilote l'écran" else ""),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if ((a?.steps?.size ?: 0) > 1) TextButton(onClick = { open = !open }) { Text(if (open) "Masquer" else "Étapes") }
                TextButton(onClick = onDetails) { Text("Détails") }
                TextButton(onClick = onStop) { Text("⏹ Arrêter") }
            }
            if (open) a?.steps?.forEach { s ->
                val mark = when (s.status.name) { "SUCCEEDED" -> "✓"; "RUNNING" -> "▶"; "FAILED" -> "⚠"; "SKIPPED" -> "–"; else -> "○" }
                Text("$mark ${s.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 24.dp, bottom = 2.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- timeline area

@Composable
private fun TimelineArea(
    vm: WorkspaceViewModel, items: List<TimelineItem>, prefs: ChatPrefs, runStatus: String?, developer: Boolean, actions: TimelineActions,
    modifier: Modifier, announcement: String?, onSuggestion: (String) -> Unit,
) {
    val t = LocalWorkspace.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { !state.canScrollForward } }
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { stick = atBottom }
    // Follow new content only when the owner is already at the bottom (doc 12 auto-scroll); never on every token re-layout.
    val lastLength = (items.lastOrNull() as? TimelineItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(items.size, lastLength, runStatus != null) {
        if (prefs.autoScroll && stick && items.isNotEmpty()) state.scrollBy(100_000f)
    }
    LaunchedEffect(Unit) {
        vm.jump.collect { id ->
            val index = items.indexOfFirst { it.key == id || (it is TimelineItem.Assistant && it.rows.any { r -> r.id == id }) || (it is TimelineItem.User && it.message.id == id) }
            if (index >= 0) { stick = false; if (t.reduceMotion) state.scrollToItem(index) else state.animateScrollToItem(index) }
        }
    }
    Box(modifier.fillMaxWidth()) {
        if (items.isEmpty() && runStatus == null) EmptyState(vm, onSuggestion)
        else Timeline(items, prefs, runStatus, developer, actions, state, Modifier.fillMaxSize(), header = {
            val older by vm.hasOlder.collectAsState()
            if (older) Text("Les messages plus anciens de cette branche sont résumés pour Cortana et restent dans l'export.", style = MaterialTheme.typography.labelSmall, color = t.muted,
                modifier = Modifier.fillMaxWidth().padding(8.dp))
        })
        if (!atBottom && items.isNotEmpty()) {
            AssistChip(onClick = { scope.launch { stick = true; state.scrollBy(100_000f) } }, label = { Text("↓ Aller en bas") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
        // Screen readers hear "Cortana répond…" and "Réponse terminée.", never each token (doc 10).
        if (announcement != null) Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}

@Composable
private fun EmptyState(vm: WorkspaceViewModel, onSuggestion: (String) -> Unit) {
    val t = LocalWorkspace.current
    val sessions by vm.sessions.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Bonjour, je suis Cortana.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Posez une question, joignez un fichier ou confiez-moi une tâche.", color = t.muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        listOf(
            "Résume ce document pour moi",
            "Rappelle-moi dans 10 minutes de faire une pause",
            "Aide-moi à planifier ma semaine",
        ).forEach { s -> AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) }, modifier = Modifier.padding(2.dp)) }
        val recent = sessions.filter { it.id != vm.sessionId.value }.take(3)
        if (recent.isNotEmpty()) {
            Text("Récentes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            recent.forEach { s -> TextButton(onClick = { vm.open(s.id) }) { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

// ---------------------------------------------------------------- header (doc 01 §1.5)

@Composable
private fun Header(
    session: SessionEntity?, layout: WorkspaceLayout, online: Boolean, halted: Boolean, busy: Boolean, level: ContextLevel, prefs: ChatPrefs,
    providerName: String?, developer: Boolean,
    onMenu: (() -> Unit)?, onSidebar: () -> Unit, onPanel: () -> Unit, onModel: () -> Unit, onRename: () -> Unit, onMode: (ChatMode) -> Unit,
    onFork: () -> Unit, onClassic: () -> Unit, onSettings: () -> Unit, onNewChat: () -> Unit, onExport: (String) -> Unit,
) {
    val t = LocalWorkspace.current
    var menu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val mode = ChatMode.of(session?.mode)
    Surface(color = t.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu de l'application") }
                IconButton(onClick = onSidebar) { Icon(Icons.AutoMirrored.Filled.List, "Discussions") }
                Column(Modifier.weight(1f).clickable(onClickLabel = "Renommer la discussion", onClick = onRename).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text((if (session?.incognito == true) "🕶 " else "") + (session?.title ?: "Cortana"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    val status = listOfNotNull(
                        if (!online) "Hors ligne : lecture et brouillons disponibles" else null,
                        if (halted) "STOP actif : aucune action" else null,
                        if (busy) "Cortana répond…" else null,
                        if (mode != ChatMode.CHAT) "Mode ${mode.label}" else null,
                        if (developer && session?.modelId != null) "${providerName ?: "?"} · ${session.modelId}" else null,
                    )
                    if (status.isNotEmpty()) Text(status.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (layout != WorkspaceLayout.COMPACT) {
                    AssistChip(onClick = onModel, label = { Text(session?.modelId?.let { (providerName?.let { p -> "$p · " } ?: "") + it } ?: "Choisir un modèle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 4.dp))
                    Box {
                        AssistChip(onClick = { modeMenu = true }, label = { Text(mode.label) }, modifier = Modifier.padding(horizontal = 4.dp))
                        DropdownMenu(modeMenu, { modeMenu = false }) {
                            ChatMode.entries.filter { it != ChatMode.COMPARE }.forEach { m -> DropdownMenuItem(text = { Text((if (m == mode) "✓ " else "   ") + m.label) }, onClick = { modeMenu = false; onMode(m) }) }
                        }
                    }
                } else IconButton(onClick = onModel) { Icon(Icons.Default.Star, "Modèle : ${session?.modelId ?: "aucun"}") }
                if (prefs.contextMeter && !level.ok) TextButton(onClick = onPanel) { Text(if (level == ContextLevel.COMPACT) "⚠ Compactage proche" else "Contexte élevé") }
                IconButton(onClick = onPanel) { Icon(Icons.Default.Info, "Panneau de contexte") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu de la discussion") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Nouvelle discussion") }, onClick = { menu = false; onNewChat() })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Dupliquer la discussion") }, onClick = { menu = false; onFork() })
                        DropdownMenuItem(text = { Text("Exporter en Markdown") }, onClick = { menu = false; onExport("md") })
                        DropdownMenuItem(text = { Text("Exporter en JSON") }, onClick = { menu = false; onExport("json") })
                        DropdownMenuItem(text = { Text("Exporter en texte") }, onClick = { menu = false; onExport("txt") })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Réglages de la discussion") }, onClick = { menu = false; onSettings() })
                        DropdownMenuItem(text = { Text("Interface classique") }, onClick = { menu = false; onClassic() })
                    }
                }
            }
            if (halted) Surface(color = t.warning, contentColor = t.onWarning, modifier = Modifier.fillMaxWidth()) {
                Text("⏹ STOP actif : Cortana peut discuter mais n'exécute aucune action. Reprise dans Réglages.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = t.border.copy(alpha = 0.6f))
        }
    }
}

// ---------------------------------------------------------------- sidebar (doc 01 §1.6)

@Composable
private fun Sidebar(vm: WorkspaceViewModel, modifier: Modifier, onRename: (SessionEntity) -> Unit, onClose: (() -> Unit)?, onSearch: () -> Unit) {
    val t = LocalWorkspace.current
    val sessions by vm.sessions.collectAsState()
    val archived by vm.archived.collectAsState()
    val previews by vm.previews.collectAsState()
    val current by vm.sessionId.collectAsState()
    val runs by vm.runs.collectAsState()
    val projects by vm.projects.collectAsState()
    val projectFilter by vm.projectFilter.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var editProject by remember { mutableStateOf<io.github.artisanguillonrenov.cortana.core.memory.ProjectEntity?>(null) }
    var newProject by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(20_000_000).toByteArray().decodeToString() } }.getOrNull()?.let { vm.importChat(it); onClose?.invoke() }
    }
    fun pick(id: String) { vm.open(id); onClose?.invoke() }
    Surface(color = t.surface, modifier = modifier) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.newChat(projectId = projectFilter); onClose?.invoke() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text(" Nouvelle discussion") }
                TextButton(onClick = { vm.newChat(incognito = true); onClose?.invoke() }) { Text("🕶") }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Filtrer les discussions") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            TextButton(onClick = onSearch, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Rechercher dans tous les messages… (Ctrl+F)") }
            val q = query.trim().lowercase()
            fun match(s: SessionEntity) = (projectFilter == null || s.projectId == projectFilter) &&
                (q.isEmpty() || s.title.lowercase().contains(q) || previews[s.id]?.lowercase()?.contains(q) == true || tagsOf(s).any { "#$it".contains(q.removePrefix("#")) && q.startsWith("#") })
            LazyColumn(Modifier.weight(1f)) {
                // Projects (doc 06 §6.8): a light grouping with inherited instructions, never deep folders.
                item("h-projects") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Projets")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { newProject = true }) { Text("+ Projet") }
                    }
                }
                if (projectFilter != null) item("p-all") { TextButton(onClick = { vm.projectFilter.value = null }) { Text("← Toutes les discussions") } }
                items(projects, key = { "proj-" + it.id }) { p ->
                    Row(Modifier.fillMaxWidth().background(if (p.id == projectFilter) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(t.radiusSmall))
                        .clickable { vm.projectFilter.value = if (p.id == projectFilter) null else p.id }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📁 ${p.name}" + " (${sessions.count { it.projectId == p.id }})", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editProject = p }) { Text("Modifier") }
                    }
                }
                val pinned = sessions.filter { it.pinned && match(it) }
                val rest = sessions.filter { !it.pinned && match(it) }
                if (pinned.isNotEmpty()) {
                    item("h-pinned") { SectionTitle("Épinglées") }
                    items(pinned, key = { "p-" + it.id }) { s -> SessionRow(s, previews[s.id], s.id == current, runs.containsKey(s.id), vm, { pick(s.id) }, onRename) }
                }
                if (rest.isNotEmpty()) {
                    item("h-recent") { SectionTitle("Récentes") }
                    items(rest, key = { "r-" + it.id }) { s -> SessionRow(s, previews[s.id], s.id == current, runs.containsKey(s.id), vm, { pick(s.id) }, onRename) }
                }
                if (archived.isNotEmpty()) {
                    item("h-archived") { TextButton(onClick = { showArchived = !showArchived }) { Text((if (showArchived) "▾ " else "▸ ") + "Archivées (${archived.size})") } }
                    if (showArchived) items(archived.filter(::match), key = { "a-" + it.id }) { s -> SessionRow(s, previews[s.id], s.id == current, false, vm, { pick(s.id) }, onRename) }
                }
                item("import") { TextButton(onClick = { runCatching { importer.launch(arrayOf("application/json", "text/*")) } }) { Text("Importer une discussion (JSON Cortana)…") } }
            }
        }
    }
    if (newProject || editProject != null) ProjectDialog(editProject, onDismiss = { newProject = false; editProject = null },
        onDelete = { id -> vm.deleteProject(id); editProject = null }) { name, instructions ->
        vm.saveProject(editProject?.id, name, instructions); newProject = false; editProject = null
    }
}

private fun tagsOf(s: SessionEntity): List<String> = runCatching {
    io.github.artisanguillonrenov.cortana.util.AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), s.tagsJson)
}.getOrDefault(emptyList())

@Composable
private fun ProjectDialog(p: io.github.artisanguillonrenov.cortana.core.memory.ProjectEntity?, onDismiss: () -> Unit, onDelete: (String) -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(p?.name.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(p?.instructions.orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (p == null) "Nouveau projet" else "Projet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions héritées par ses discussions") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                Text("Envoyées à Cortana comme votre note, jamais comme règle de sécurité. Chaque discussion peut les ignorer.", style = MaterialTheme.typography.bodySmall)
                if (p != null) TextButton(onClick = { confirm = true }) { Text("Supprimer le projet") }
                if (confirm && p != null) Text("Les discussions restent ; elles sortent seulement du projet.", style = MaterialTheme.typography.bodySmall)
                if (confirm && p != null) TextButton(onClick = { onDelete(p.id) }) { Text("Confirmer la suppression") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), instructions) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    val t = LocalWorkspace.current
    Text(text, style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun SessionRow(s: SessionEntity, preview: String?, selected: Boolean, running: Boolean, vm: WorkspaceViewModel, onOpen: () -> Unit, onRename: (SessionEntity) -> Unit) {
    val t = LocalWorkspace.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val prefs by vm.prefs.collectAsState()
    val projects by vm.projects.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(t.radiusSmall))
            .clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) Text("● ", color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "réponse en cours" })
                Text((if (s.pinned) "📌 " else "") + (if (s.incognito) "🕶 " else "") + s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (!preview.isNullOrBlank()) Text(preview.replace('\n', ' ').take(120), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = t.muted)
            val mode = ChatMode.of(s.mode)
            val tags = tagsOf(s)
            Text(TimeFmt.short(s.updatedAt) + (if (mode != ChatMode.CHAT) " · ${mode.label}" else "") + (if (tags.isNotEmpty()) " · " + tags.joinToString(" ") { "#$it" } else ""),
                style = MaterialTheme.typography.labelSmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions sur « ${s.title} »") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename(s) })
                DropdownMenuItem(text = { Text(if (s.pinned) "Désépingler" else "Épingler") }, onClick = { menu = false; vm.setPinned(s.id, !s.pinned) })
                DropdownMenuItem(text = { Text(if (s.archived) "Désarchiver" else "Archiver") }, onClick = { menu = false; vm.setArchived(s.id, !s.archived) })
                DropdownMenuItem(text = { Text("Étiquettes…") }, onClick = { menu = false; tagging = true })
                DropdownMenuItem(text = { Text("Déplacer vers un projet…") }, onClick = { menu = false; moving = true })
                DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; if (prefs.confirmDelete) confirmDelete = true else vm.delete(s.id) })
            }
        }
    }
    if (tagging) {
        var text by rememberSaveable { mutableStateOf(tagsOf(s).joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { tagging = false }, title = { Text("Étiquettes") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("travail, maison…") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.setTags(s.id, text.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }); tagging = false }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { tagging = false }) { Text("Annuler") } },
        )
    }
    if (moving) AlertDialog(
        onDismissRequest = { moving = false }, title = { Text("Déplacer vers un projet") },
        text = {
            Column {
                Text("Aucun projet", Modifier.fillMaxWidth().clickable { vm.moveToProject(s.id, null); moving = false }.padding(vertical = 10.dp))
                projects.forEach { p -> Text((if (p.id == s.projectId) "✓ " else "") + p.name, Modifier.fillMaxWidth().clickable { vm.moveToProject(s.id, p.id); moving = false }.padding(vertical = 10.dp)) }
                if (projects.isEmpty()) Text("Créez d'abord un projet (+ Projet).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { moving = false }) { Text("Fermer") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Supprimer la discussion ?") },
        text = { Text("« ${s.title} », toutes ses branches, son brouillon et sa file seront supprimés de la tablette.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(s.id) }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- context panel (doc 01 §1.7)

@Composable
internal fun ContextPanel(vm: WorkspaceViewModel, items: List<TimelineItem>, tab: String, onTab: (String) -> Unit, modifier: Modifier, onClose: () -> Unit) {
    val t = LocalWorkspace.current
    val developer by vm.developer.collectAsState()
    val branches by vm.branches.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val files = remember(items) { items.filterIsInstance<TimelineItem.User>().flatMap { it.meta.attachments } }
    val sources = remember(items) { items.filterIsInstance<TimelineItem.Assistant>().flatMap { it.sources } }
    val tools = remember(items) { items.filterIsInstance<TimelineItem.Assistant>().flatMap { a -> a.parts.filterIsInstance<MessagePart.ToolResult>() } }
    // Only the useful tabs (doc 01 §1.7).
    val tabs = buildList {
        add("context" to "Contexte")
        if (files.isNotEmpty()) add("files" to "Fichiers (${files.size})")
        if (sources.isNotEmpty()) add("sources" to "Sources (${sources.size})")
        if (branches.size > 1) add("branches" to "Branches (${branches.size})")
        if (artifacts.isNotEmpty()) add("artifacts" to "Artefacts (${artifacts.size})")
        add("activity" to "Activité")
        add("tools" to "Outils")
        add("memory" to "Mémoire")
        if (developer) add("inspector" to "Inspecteur")
    }
    val selected = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    Surface(color = t.surface, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Panneau", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text("Fermer") }
            }
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, (id, label) -> Tab(selected = i == selected, onClick = { onTab(id) }, text = { Text(label) }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tabs[selected].first) {
                    "context" -> ContextTab(vm)
                    "files" -> ListTab(files.map { "${it.name} · ${sizeLabel(it.sizeBytes)} · ${it.mime}" to (it.note ?: "artifact:${it.artifactId}") })
                    "sources" -> ListTab(sources.map { "[${it.index}] ${it.title}" to (listOfNotNull(it.url, it.snippet.takeIf { s -> s.isNotBlank() }).joinToString("\n")) })
                    "activity" -> ActivityTab(vm, tools)
                    "branches" -> BranchesTab(branches, onOpen = { vm.openBranch(it) })
                    "artifacts" -> ArtifactsTab(vm, artifacts)
                    "memory" -> MemoryTab(vm)
                    "tools" -> ToolsTab(vm)
                    "inspector" -> InspectorTab(vm, items)
                }
            }
        }
    }
}

@Composable
private fun ListTab(rows: List<Pair<String, String>>) {
    val t = LocalWorkspace.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(rows) { (title, detail) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = t.border.copy(alpha = 0.5f))
        }
    }
}

/** Artifacts of the conversation (doc 07 §7.1–7.3): durable objects beside the messages, versioned, never overwritten. */
@Composable
private fun ArtifactsTab(vm: WorkspaceViewModel, artifacts: List<io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity>) {
    val t = LocalWorkspace.current
    var open by remember { mutableStateOf<String?>(null) }
    val current = artifacts.firstOrNull { it.artifactId == open }
    if (current == null) {
        // Latest version of each artifact only.
        val latest = remember(artifacts) { artifacts.groupBy { vm.metadataOf(it)["rootId"] ?: it.artifactId }.values.map { v -> v.maxBy { it.createdAt } }.sortedByDescending { it.createdAt } }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(latest, key = { it.artifactId }) { a ->
                val versions = vm.versionsOf(a).size
                Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Ouvrir l'artefact") { open = a.artifactId }.padding(vertical = 10.dp)) {
                    Text("📄 ${a.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(artifactType(a.type), sizeLabel(a.sizeBytes), TimeFmt.short(a.createdAt), if (versions > 1) "$versions versions" else null).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = t.muted)
                }
                HorizontalDivider(color = t.border.copy(alpha = 0.5f))
            }
        }
    } else ArtifactViewer(vm, current, onBack = { open = null }, onOpen = { open = it })
}

private fun artifactType(t: String) = when (t) {
    "attachment" -> "pièce jointe"; "document" -> "document"; "export" -> "export"; "report" -> "rapport"; "patch" -> "patch"; "apk" -> "application"; else -> t
}

@Composable
private fun ArtifactViewer(vm: WorkspaceViewModel, a: io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val t = LocalWorkspace.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember(a.artifactId) { mutableStateOf<String?>(null) }
    var editing by remember(a.artifactId) { mutableStateOf(false) }
    var draft by remember(a.artifactId) { mutableStateOf("") }
    var diff by remember(a.artifactId) { mutableStateOf<io.github.artisanguillonrenov.cortana.core.chat.TextDiff.Summary?>(null) }
    LaunchedEffect(a.artifactId) { text = vm.artifactText(a.artifactId) }
    val meta = remember(a) { vm.metadataOf(a) }
    val versions = vm.versionsOf(a)
    val index = versions.indexOfFirst { it.artifactId == a.artifactId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("← Artefacts") }
        Text(a.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(listOf(artifactType(a.type), a.mime, sizeLabel(a.sizeBytes), TimeFmt.short(a.createdAt)).joinToString(" · ") + if (versions.size > 1) " · version ${index + 1}/${versions.size}" else "",
            style = MaterialTheme.typography.labelSmall, color = t.muted)
        if (versions.size > 1) Row {
            TextButton(onClick = { onOpen(versions[index - 1].artifactId) }, enabled = index > 0) { Text("‹ Précédente") }
            TextButton(onClick = { onOpen(versions[index + 1].artifactId) }, enabled = index < versions.lastIndex) { Text("Suivante ›") }
            if (index > 0) TextButton(onClick = { scope.launch { diff = vm.diff(vm.artifactText(versions[index - 1].artifactId), text.orEmpty()) } }) { Text("Comparer") }
        }
        Row(Modifier.horizontalScrollSafe()) {
            TextButton(onClick = { text?.let { clipboard.setText(AnnotatedString(it)); vm.say("Copié") } }, enabled = text != null) { Text("Copier") }
            if (vm.isText(a)) TextButton(onClick = { draft = text.orEmpty(); editing = true }, enabled = text != null) { Text("Modifier") }
            TextButton(onClick = { vm.exportArtifact(a.artifactId) }) { Text("Exporter") }
            TextButton(onClick = { vm.pinArtifact(a.artifactId, a.name) }) { Text("Épingler") }
            meta["messageId"]?.let { mid -> TextButton(onClick = { vm.sessionId.value?.let { vm.open(it, mid) } }) { Text("Message source") } }
        }
        diff?.let { d ->
            Text("Différences : +${d.added} / −${d.removed} ligne(s)", style = MaterialTheme.typography.labelLarge)
            Surface(color = t.code, contentColor = t.onCode, shape = RoundedCornerShape(t.radiusSmall)) {
                Text(d.text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
        if (editing) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), label = { Text("Nouvelle version") })
            Row {
                TextButton(onClick = { editing = false }) { Text("Annuler") }
                TextButton(onClick = { vm.saveVersion(a, draft); editing = false }, enabled = draft != text) { Text("Enregistrer une nouvelle version") }
            }
        } else when (val body = text) {
            null -> Text("Chargement…", color = t.muted)
            else -> Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall)) {
                Text(body.take(40_000), style = MaterialTheme.typography.bodySmall, fontFamily = if (vm.isText(a)) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                    modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

/** The conversation's branches (doc 04 §4.3): every ending, the active one marked; nothing is ever deleted. */
@Composable
private fun BranchesTab(branches: List<WorkspaceViewModel.BranchInfo>, onOpen: (String) -> Unit) {
    val t = LocalWorkspace.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(branches, key = { it.leafId }) { b ->
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Afficher cette branche") { onOpen(b.leafId) }.padding(vertical = 10.dp)) {
                Text((if (b.current) "● " else "○ ") + b.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (b.current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${TimeFmt.short(b.at)} · ${b.messages} message(s)" + if (b.current) " · affichée" else "", style = MaterialTheme.typography.labelSmall, color = t.muted)
            }
            HorizontalDivider(color = t.border.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ContextTab(vm: WorkspaceViewModel) {
    val t = LocalWorkspace.current
    val level by vm.contextLevel.collectAsState()
    val session by vm.session.collectAsState()
    val snapshot by vm.contextSnapshot.collectAsState()
    val pins by vm.pins.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()
    val developer by vm.developer.collectAsState()
    val projects by vm.projects.collectAsState()
    var note by rememberSaveable { mutableStateOf("") }
    var inspect by remember { mutableStateOf<io.github.artisanguillonrenov.cortana.core.memory.ContextCheckpointEntity?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Meter (doc 06 §6.2): words, never a false precision.
        Text(level.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text("Cortana garde les échanges récents mot pour mot et résume les plus anciens quand la fenêtre du modèle se remplit.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        val r = snapshot?.report
        if (r != null) {
            Text("Dernière demande : ${r.windowMessages} message(s) récents" + (if (r.droppedMessages > 0) ", ${r.droppedMessages} résumé(s)" else "") +
                (r.summaryMethod?.let { m -> " (${if (m == "model") "résumé par le modèle" else "résumé extractif"})" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            if (r.truncated.isNotEmpty()) Text("Raccourci pour tenir : " + r.truncated.distinct().joinToString(", ") { truncatedLabel(it) }, style = MaterialTheme.typography.bodySmall, color = t.muted)
            if (developer) {
                Text("Estimations : ${r.used} / ${r.budget} jetons · réserve de réponse ${r.outputReserve} · définitions d'outils ${r.toolTokens}", style = MaterialTheme.typography.bodySmall)
                Text(r.sections.entries.joinToString(" · ") { (k, v) -> "${sectionLabel(k)} $v" }, style = MaterialTheme.typography.bodySmall, color = t.muted)
            }
        }
        HorizontalDivider(color = t.border.copy(alpha = 0.5f))
        // What is actually active (doc 06 §6.1).
        Text("Actif dans cette discussion", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        session?.let { s ->
            Text("Modèle : ${s.modelId ?: "route par défaut"} · outils : ${Toolsets.labels[s.toolset] ?: s.toolset}" + if (s.incognito) " · incognito (aucun souvenir écrit)" else "", style = MaterialTheme.typography.bodySmall)
            val project = projects.firstOrNull { it.id == s.projectId }
            if (project != null) {
                val inherit = vm.inheritsProject(s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Projet « ${project.name} » : " + (if (project.instructions.isBlank()) "sans instructions" else if (inherit) "instructions héritées" else "instructions ignorées ici"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (project.instructions.isNotBlank()) TextButton(onClick = { vm.setInheritProject(!inherit) }) { Text(if (inherit) "Ignorer ici" else "Hériter") }
                }
            }
            Text("Souvenirs utilisés : ${snapshot?.memoryIds?.size ?: 0}" + if (vm.memoryOff(s)) " (mémoire désactivée ici)" else "", style = MaterialTheme.typography.bodySmall)
            if (vm.reduced(s)) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Contexte réduit : les échanges anciens ne sont envoyés qu'en résumé.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearReduction() }) { Text("Annuler") }
            }
        }
        HorizontalDivider(color = t.border.copy(alpha = 0.5f))
        // Pins (doc 06 §6.3): message, file, artifact, note.
        Text("Épinglés (${pins.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (pins.isEmpty()) Text("Épinglez un message (menu ⋮), un fichier (onglet Fichiers) ou une note : ils restent prioritaires tant que la place le permet.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        pins.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (p.targetType) { "note" -> "📝"; "artifact" -> "📄"; else -> "📌" }
                Text("$icon ${p.label}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.unpin(p.id) }) { Text("Retirer") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(note, { note = it }, placeholder = { Text("Note à garder en tête") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.addNotePin(note); note = "" }, enabled = note.isNotBlank()) { Text("Épingler") }
        }
        HorizontalDivider(color = t.border.copy(alpha = 0.5f))
        // Compaction (doc 06 §6.4–6.5): inspectable checkpoints.
        Text("Compactages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (checkpoints.isEmpty()) Text("Aucun résumé pour l'instant.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        checkpoints.take(8).forEach { k ->
            Text("🗜 ${TimeFmt.short(k.createdAt)} · ${k.coveredCount} message(s) · ${if (k.method == "model") "résumé par le modèle" else "extractif"}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Voir le résumé") { inspect = k }.padding(vertical = 6.dp))
        }
        TextButton(onClick = { vm.compactNow() }) { Text("Résumer maintenant les échanges anciens") }
    }
    inspect?.let { k ->
        AlertDialog(
            onDismissRequest = { inspect = null },
            title = { Text("Résumé conservé") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${TimeFmt.short(k.createdAt)} · ${k.coveredCount} message(s) couverts · " + (if (k.method == "model") "résumé par le modèle ${k.modelRef ?: ""}" else "résumé extractif (sans modèle)"),
                        style = MaterialTheme.typography.labelMedium)
                    Text("Les messages résumés restent intacts dans la conversation et dans l'export ; seul ce que Cortana reçoit est raccourci.", style = MaterialTheme.typography.bodySmall, color = t.muted,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Text(k.summary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { inspect = null }) { Text("Fermer") } },
            dismissButton = { if (k.coveredUntilMessageId != null) TextButton(onClick = { vm.fork(k.coveredUntilMessageId); inspect = null }) { Text("Dupliquer depuis ce point") } },
        )
    }
}

private fun truncatedLabel(k: String) = when (k) {
    "tool_outputs" -> "anciens résultats d'outils"; "latest_turn" -> "dernier échange"; "memory" -> "souvenirs"; "attachments" -> "fichiers joints"
    "pins" -> "épinglés"; "task_state" -> "état de la tâche"; "objective" -> "objectif"; else -> k
}

private fun sectionLabel(k: String) = when (k) {
    "policy" -> "règles"; "objective" -> "objectif"; "task_state" -> "tâche"; "memory" -> "mémoire"; "attachments" -> "fichiers"; "pins" -> "épinglés"
    "window" -> "historique"; "summary" -> "résumé"; else -> k
}

@Composable
private fun ActivityTab(vm: WorkspaceViewModel, tools: List<MessagePart.ToolResult>) {
    val t = LocalWorkspace.current
    val active by vm.active.collectAsState()
    val approval by vm.pendingApproval.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val a = active
        if (a == null) Text("Aucune tâche en cours.", color = t.muted)
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!t.reduceMotion) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  " + a.status, style = MaterialTheme.typography.bodyMedium)
            }
            Text("${a.modelCalls} appel(s) au modèle · ${a.toolCalls} action(s)" + (if (a.tainted) " · contenu externe lu" else ""), style = MaterialTheme.typography.bodySmall, color = t.muted)
            a.steps.forEach { s ->
                val mark = when (s.status.name) { "SUCCEEDED" -> "✓"; "RUNNING" -> "▶"; "FAILED" -> "⚠"; "SKIPPED" -> "–"; else -> "○" }
                Text("$mark ${s.title}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::stop) { Text("⏹ Arrêter") }
        }
        approval?.let { p -> ApprovalCard(p, onRefuse = { vm.refuseApproval(p.id) }, onReview = { vm.reviewApproval(p.id) }) }
        if (tools.isNotEmpty()) {
            HorizontalDivider(color = t.border.copy(alpha = 0.5f))
            Text("Actions de la conversation (${tools.size})", style = MaterialTheme.typography.titleSmall)
            tools.takeLast(30).reversed().forEach { tr -> Text((if (tr.ok) "✓ " else "⚠ ") + tr.label + " — " + tr.summary.take(80), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

/**
 * Approval in the conversation (doc 09, decision 1 of the mapping): the card shows what would happen and
 * can refuse; approving is only possible on the secure approval screen (FLAG_SECURE, bound to the action).
 */
@Composable
fun ApprovalCard(p: io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest, onRefuse: () -> Unit, onReview: () -> Unit) {
    val t = LocalWorkspace.current
    Surface(color = t.warning, contentColor = t.onWarning, shape = RoundedCornerShape(t.radiusCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🔐 Autorisation demandée", style = MaterialTheme.typography.titleSmall)
            Text(p.action, style = MaterialTheme.typography.bodyMedium)
            p.target?.let { Text("Cible : $it", style = MaterialTheme.typography.bodySmall) }
            Text("Risque : ${p.risk.name}" + (if (p.reversible) " · réversible" else " · irréversible") + (if (p.tainted) " · après lecture de contenu externe" else ""), style = MaterialTheme.typography.bodySmall)
            if (p.reasons.isNotEmpty()) Text(p.reasons.joinToString(" ; "), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onRefuse) { Text("Refuser") }
                Button(onClick = onReview) { Text(if (p.biometric) "Examiner et autoriser (empreinte)" else "Examiner et autoriser") }
            }
        }
    }
}

/** Tools of this conversation (doc 09 §9.7): what is available and at which risk; permissions stay in Capabilities. */
@Composable
private fun ToolsTab(vm: WorkspaceViewModel) {
    val t = LocalWorkspace.current
    val session by vm.session.collectAsState()
    val groups = remember(session?.toolset) { vm.toolsByCategory() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Outils de cette discussion : " + (Toolsets.labels[session?.toolset] ?: "—"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Toolsets.labels.forEach { (k, v) -> FilterChip(session?.toolset == k, { vm.setToolset(k) }, label = { Text(v) }) }
        }
        Text("Chaque action passe par vos règles et confirmations ; permissions et santé : écran Capacités.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        if (groups.isEmpty()) Text("Mode discussion : aucun outil.", color = t.muted)
        groups.forEach { (cat, tools) ->
            Text("${cat.lowercase().replaceFirstChar { it.uppercase() }} (${tools.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            tools.forEach { (label, detail) -> Text("• $label — $detail", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun MemoryTab(vm: WorkspaceViewModel) {
    val t = LocalWorkspace.current
    val session by vm.session.collectAsState()
    val memories by vm.relevantMemories.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session?.incognito == true) { Text("Discussion incognito : aucun souvenir n'est lu ni enregistré.", color = t.muted); return@Column }
        val off = vm.memoryOff(session)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (off) "Mémoire désactivée pour cette discussion" else "Souvenirs utilisés par la dernière demande", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = { vm.setMemoryOff(!off) }) { Text(if (off) "Activer" else "Désactiver ici") }
        }
        Text("La mémoire permanente se gère dans l'écran Mémoire ; ici, vous choisissez seulement ce que cette discussion utilise.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        if (!off && memories.isEmpty()) Text("Aucun souvenir utilisé pour l'instant.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        if (!off) memories.forEach { m ->
            val used = !vm.memoryDisabledHere(m.id)
            val source = runCatching { io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(m.provenanceJson) as kotlinx.serialization.json.JsonObject }.getOrNull()
                ?.get("source")?.toString()?.trim('"')
            Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(memoryType(m.type), source?.let { "source : $it" }, TimeFmt.short(m.updatedAt), if (m.confidence < 0.8) "confiance ${(m.confidence * 100).toInt()} %" else null,
                        if (m.status != "active") m.status else null).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = t.muted)
                    Row {
                        TextButton(onClick = { vm.setMemoryHere(m.id, !used) }) { Text(if (used) "Ne pas utiliser ici" else "Utiliser ici") }
                        TextButton(onClick = { vm.proposeCorrection(m.text) }) { Text("Proposer une correction") }
                    }
                }
            }
        }
    }
}

private fun memoryType(t: String) = when (t) { "profile" -> "profil"; "preference" -> "préférence"; "semantic" -> "fait"; "episodic" -> "souvenir de tâche"; else -> t }

@Composable
private fun InspectorTab(vm: WorkspaceViewModel, items: List<TimelineItem>) {
    val t = LocalWorkspace.current
    val report by vm.contextReport.collectAsState()
    val runs by vm.runs.collectAsState()
    val sid by vm.sessionId.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Inspecteur (développeur)", style = MaterialTheme.typography.titleSmall)
        Text("Éléments du fil : ${items.size}", style = MaterialTheme.typography.bodySmall)
        runs[sid]?.let { Text("Run ${it.runId.take(8)} · séquence ${it.sequence} · ${it.status}", style = MaterialTheme.typography.bodySmall) }
        report?.let { r ->
            Text("Budget ${r.budget} · utilisé ${r.used} (estimations)", style = MaterialTheme.typography.bodySmall)
            r.sections.forEach { (k, v) -> Text("  $k : $v", style = MaterialTheme.typography.bodySmall, color = t.muted) }
        }
        Text("Le raisonnement privé des modèles n'est jamais affiché.", style = MaterialTheme.typography.labelSmall, color = t.muted)
    }
}

// ---------------------------------------------------------------- overlays and dialogs

/** A panel over the conversation (portrait tablet, phone), with scrim and back handling. */
@Composable
internal fun BoxScope.OverlaySheet(visible: Boolean, fromStart: Boolean, width: Dp, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalWorkspace.current
    if (visible) BackHandler(onBack = onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(tween(t.motion())), exit = fadeOut(tween(t.motion()))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Fermer le panneau", onClick = onDismiss))
    }
    AnimatedVisibility(
        visible, modifier = Modifier.align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(tween(t.motion(200))) { w -> if (fromStart) -w else w },
        exit = slideOutHorizontally(tween(t.motion(160))) { w -> if (fromStart) -w else w },
    ) {
        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxHeight().width(width)) { content() }
    }
}

@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Renommer") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
internal fun CommandPalette(commands: List<SlashCommand>, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Commandes") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Rechercher une commande") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(commands.filter { q.isBlank() || it.name.contains(q, true) || it.label.contains(q, true) }, key = { it.name }) { c ->
                        Text("/${c.name} — ${c.label}", modifier = Modifier.fillMaxWidth().clickable { onDismiss(); c.run() }.padding(vertical = 12.dp))
                    }
                }
            }
        },
    )
}

@Composable
internal fun DetailsDialog(item: TimelineItem.Assistant, onDismiss: () -> Unit) {
    val usage = item.rows.mapNotNull { it.usageJson }.joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Détails techniques") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Fournisseur : ${item.meta.providerName ?: item.meta.providerId ?: "?"}")
                Text("Modèle : ${item.meta.modelId ?: "?"}")
                Text("Fin : ${item.meta.finishReason ?: "?"} · statut : ${item.status}")
                Text("Lignes : ${item.rows.size} · tâche : ${item.first.taskId?.take(8) ?: "-"}")
                if (usage.isNotBlank()) Text("Usage : $usage")
                Text("Le raisonnement privé n'est jamais affiché.", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** Model selector (doc 08 §8.1): search, recents, favorites, per-conversation choice. */
@Composable
internal fun ModelPicker(vm: WorkspaceViewModel, session: SessionEntity?, onDismiss: () -> Unit, onOpenProviders: () -> Unit) {
    val providers by vm.providers.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(session?.providerId ?: providers.firstOrNull()?.id) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var comparing by remember { mutableStateOf(vm.compareRoutes(session).size >= 2) }
    var picked by remember { mutableStateOf(vm.compareRoutes(session)) }
    LaunchedEffect(provider) {
        val p = provider ?: return@LaunchedEffect
        loading = true; error = null
        vm.models(p).onSuccess { models = it }.onFailure { error = it.message; models = emptyList() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Modèle de cette discussion") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = onOpenProviders) { Text("Fournisseurs") } },
        text = {
            Column(Modifier.heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (providers.isEmpty()) { Text("Aucun fournisseur. Ajoutez-en un d'abord."); return@Column }
                // Comparison (doc 08 §8.2): the next message goes to 2 to 4 models, answers side by side.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Comparer plusieurs modèles", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(comparing, { on -> comparing = on; if (!on) { picked = emptyList(); vm.setCompareRoutes(emptyList()) } })
                }
                if (comparing) {
                    Text("Choisis : ${picked.size}/4" + if (picked.isNotEmpty()) " — " + picked.joinToString { it.substringAfter('/') } else "", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.setCompareRoutes(picked); onDismiss() }, enabled = picked.size >= 2) { Text("Comparer ces ${picked.size} modèles") }
                }
                val recents = prefs.recentModels.filter { r -> providers.any { r.startsWith(it.id + "/") } }.take(4)
                if (recents.isNotEmpty()) {
                    Text("Récents", style = MaterialTheme.typography.labelLarge)
                    recents.forEach { r ->
                        val (pid, mid) = r.substringBefore('/') to r.substringAfter('/')
                        Text("↺ ${providers.firstOrNull { it.id == pid }?.displayName ?: "?"} · $mid", modifier = Modifier.fillMaxWidth().clickable { vm.setModel(pid, mid); onDismiss() }.padding(vertical = 8.dp))
                    }
                    HorizontalDivider()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.filter { it.enabled }.take(4).forEach { p -> FilterChip(selected = p.id == provider, onClick = { provider = p.id }, label = { Text(p.displayName, maxLines = 1) }) }
                }
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Rechercher un modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> {
                        Text("Liste indisponible : $error", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { provider?.let { p -> vm.models(p, refresh = true).onSuccess { models = it; error = null } } } }) { Text("Réessayer") }
                    }
                }
                val favorites = prefs.favoriteModels.toSet()
                val shown = models.filter { filter.isBlank() || it.id.contains(filter, true) }.sortedByDescending { "$provider/${it.id}" in favorites }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(shown, key = { it.id }) { m ->
                        val ref = "$provider/${m.id}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (comparing) {
                                val on = ref in picked
                                androidx.compose.material3.Checkbox(on, { v -> picked = if (v) (picked + ref).distinct().take(4) else picked - ref })
                                Text(m.id + (m.contextWindow?.let { "  (${it / 1000}k)" } ?: ""), modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                            } else Text((if (m.id == session?.modelId && provider == session?.providerId) "✓ " else "") + m.id + (m.contextWindow?.let { "  (${it / 1000}k)" } ?: ""),
                                modifier = Modifier.weight(1f).clickable { vm.setModel(provider, m.id); onDismiss() }.padding(vertical = 10.dp))
                            TextButton(onClick = { vm.toggleFavorite(ref) }) { Text(if (ref in favorites) "★" else "☆") }
                        }
                    }
                }
            }
        },
    )
}

/** Global search (doc 04 §4.5–4.6): titles and message contents; a result opens the conversation at the message. */
@Composable
internal fun SearchDialog(vm: WorkspaceViewModel, onDismiss: () -> Unit, onOpen: (sessionId: String, messageId: String) -> Unit) {
    val t = LocalWorkspace.current
    var q by rememberSaveable { mutableStateOf("") }
    val results by vm.searchResults.collectAsState()
    val f by vm.searchFilters.collectAsState()
    val projects by vm.projects.collectAsState()
    LaunchedEffect(q) { vm.search(q) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rechercher") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Mots à chercher") }, modifier = Modifier.fillMaxWidth())
                // Filters (doc 04 §4.5): author, pinned, with a file, project.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(f.role == io.github.artisanguillonrenov.cortana.core.memory.Roles.USER, { vm.searchFilters.value = f.copy(role = if (f.role == "user") null else "user") }, label = { Text("Vous") })
                    FilterChip(f.role == io.github.artisanguillonrenov.cortana.core.memory.Roles.ASSISTANT, { vm.searchFilters.value = f.copy(role = if (f.role == "assistant") null else "assistant") }, label = { Text("Cortana") })
                    FilterChip(f.pinnedOnly, { vm.searchFilters.value = f.copy(pinnedOnly = !f.pinnedOnly) }, label = { Text("Épinglées") })
                    FilterChip(f.withFiles, { vm.searchFilters.value = f.copy(withFiles = !f.withFiles) }, label = { Text("Avec fichier") })
                    projects.forEach { p -> FilterChip(f.projectId == p.id, { vm.searchFilters.value = f.copy(projectId = if (f.projectId == p.id) null else p.id) }, label = { Text("📁 ${p.name}") }) }
                }
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(results, key = { it.sessionId + it.messageId + it.role }) { r ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(r.sessionId, r.messageId) }.padding(vertical = 8.dp)) {
                            Text(r.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.excerpt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(TimeFmt.short(r.at) + " · " + r.role, style = MaterialTheme.typography.labelSmall, color = t.muted)
                        }
                    }
                }
                if (q.length >= 2 && results.isEmpty()) Text("Aucun résultat.", color = t.muted)
            }
        },
    )
}

@Suppress("unused")
private fun attachmentsOf(items: List<TimelineItem>): List<AttachmentRef> = items.filterIsInstance<TimelineItem.User>().flatMap { it.meta.attachments }
