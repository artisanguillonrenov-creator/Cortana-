package io.github.artisanguillonrenov.cortana.ui.cortana

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.core.voice.VoicePhase
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.components.AssistantMessage
import io.github.artisanguillonrenov.cortana.ui.components.BodyText
import io.github.artisanguillonrenov.cortana.ui.components.CortanaComposer
import io.github.artisanguillonrenov.cortana.ui.components.LinkChip
import io.github.artisanguillonrenov.cortana.ui.components.ModelOption
import io.github.artisanguillonrenov.cortana.ui.components.providerLook
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaPalette
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.workspace.CommandPalette
import io.github.artisanguillonrenov.cortana.ui.workspace.ComposerActions
import io.github.artisanguillonrenov.cortana.ui.workspace.ContextPanel
import io.github.artisanguillonrenov.cortana.ui.workspace.DetailsDialog
import io.github.artisanguillonrenov.cortana.ui.workspace.DraftAttachment
import io.github.artisanguillonrenov.cortana.ui.workspace.LocalWorkspace
import io.github.artisanguillonrenov.cortana.ui.workspace.ModelPicker
import io.github.artisanguillonrenov.cortana.ui.workspace.OverlaySheet
import io.github.artisanguillonrenov.cortana.ui.workspace.ReadingBar
import io.github.artisanguillonrenov.cortana.ui.workspace.RenameDialog
import io.github.artisanguillonrenov.cortana.ui.workspace.RenderActions
import io.github.artisanguillonrenov.cortana.ui.workspace.SearchDialog
import io.github.artisanguillonrenov.cortana.ui.workspace.ShareInbox
import io.github.artisanguillonrenov.cortana.ui.workspace.SlashAndMentions
import io.github.artisanguillonrenov.cortana.ui.workspace.SlashCommand
import io.github.artisanguillonrenov.cortana.ui.workspace.TimelineActions
import io.github.artisanguillonrenov.cortana.ui.workspace.VoiceOverlay
import io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceTokens
import io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceViewModel
import io.github.artisanguillonrenov.cortana.ui.workspace.describe
import io.github.artisanguillonrenov.cortana.util.Speaker
import kotlinx.coroutines.launch

/**
 * "Discussion" of the Cortana Workspace design on the one runtime (no second backend): the conversation
 * of [WorkspaceViewModel] (rc4 features included: variants, edit, branches, queue, attachments, voice,
 * search, export), the task of [TaskPanelViewModel] (plan, logs, files, cards), STOP as a pause that
 * "Reprendre" continues, and approvals that are refused here and granted only on the secure screen.
 */
@Composable
fun DiscussionRoute(openSessionId: String?, openMessageId: String?, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val vm: WorkspaceViewModel = viewModel { WorkspaceViewModel(c) }
    val pvm: TaskPanelViewModel = viewModel { TaskPanelViewModel(c) }
    LaunchedEffect(openSessionId, openMessageId) { openSessionId?.let { vm.open(it, openMessageId) } }
    LaunchedEffect(vm, pvm) { vm.sessionId.collect { pvm.sessionId.value = it } }
    val share by ShareInbox.pending.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(share) { if (share != null) ShareInbox.take()?.let { vm.receiveShare(it, ctx) } }
    val prefs by vm.prefs.collectAsState()
    val palette = Cortana.colors
    val tokens = remember(palette, prefs.density, prefs.reduceMotion, prefs.theme) { designWorkspaceTokens(palette, prefs) }
    CompositionLocalProvider(LocalWorkspace provides tokens) {
        val shell = LocalShell.current
        if (shell != null) DiscussionBody(vm, pvm, prefs, shell, onNavigate)
        else BoxWithConstraints(Modifier.fillMaxSize()) {
            DiscussionBody(vm, pvm, prefs, ShellScope(shellLayoutFor(maxWidth, maxHeight), false) {}, onNavigate)
        }
    }
}

@Composable
private fun DiscussionBody(vm: WorkspaceViewModel, pvm: TaskPanelViewModel, prefs: ChatPrefs, shell: ShellScope, onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val palette = Cortana.colors
    val reduce = Cortana.reduceMotion
    val session by vm.session.collectAsState()
    val items by vm.timeline.collectAsState()
    val active by vm.active.collectAsState()
    val runs by vm.runs.collectAsState()
    val queue by vm.queue.collectAsState()
    val draft by vm.draft.collectAsState()
    val files by vm.draftFiles.collectAsState()
    val halted by vm.halted.collectAsState()
    val providers by vm.providers.collectAsState()
    val voice by vm.voice.collectAsState()
    val reading by vm.reading.collectAsState()
    val announcement by vm.announcement.collectAsState()
    val toolsOn by vm.toolFamiliesOn.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val panel by pvm.panel.collectAsState()
    val task by pvm.task.collectAsState()
    val approval by pvm.approval.collectAsState()
    val decision by pvm.decision.collectAsState()
    val resumeAutonomy = LocalResumeAutonomy.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    var contextPanel by rememberSaveable { mutableStateOf(false) }
    var contextTab by rememberSaveable { mutableStateOf("context") }
    var panelTab by rememberSaveable { mutableIntStateOf(0) }
    var modelPicker by remember { mutableStateOf(false) }
    var palettes by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var details by remember { mutableStateOf<TimelineItem.Assistant?>(null) }
    var searching by remember { mutableStateOf(false) }
    var planFlash by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()

    val sid = session?.id
    val busyHere = active != null && active?.sessionId == sid
    val liveStatus = if (busyHere) runs[sid]?.status?.takeIf { it.isNotBlank() } ?: active?.status else null

    // STOP everywhere (header rail, panel, Échap; the sidebar has its own): a pause that "Reprendre" continues.
    fun stop() { if (!stopTask(c)) vm.say("Aucune tâche en cours.") }
    fun resume() {
        if (halted) { resumeAutonomy(); return }
        val id = task?.id ?: return
        if (!c.orchestrator.resumePaused(id)) vm.say("Reprise impossible : Cortana est occupée ou l'autonomie est arrêtée.")
    }
    fun copy(text: String) { clipboard.setText(AnnotatedString(text)); vm.say("Copié") }
    fun openLink(url: String) {
        if (url.startsWith("artifact:")) { contextTab = "files"; contextPanel = true; return }
        runCatching { uri.openUri(url) }.onFailure { vm.say("Lien impossible à ouvrir") }
    }
    fun showPlan() {
        val t = panel.task ?: return
        if (t.steps.size < 2) { vm.say("Cette tâche n'a pas de plan en plusieurs étapes."); return }
        val rows = designItems(items, t)
        val index = planHost(rows, t).takeIf { it >= 0 } ?: rows.size
        scope.launch { if (reduce) list.scrollToItem(index) else list.animateScrollToItem(index) }
        planFlash++
    }

    val actions = TimelineActions(
        render = RenderActions(openLink = ::openLink, openCitation = { contextTab = "sources"; contextPanel = true }, saveText = { text, name -> vm.saveArtifact(text, name) }, say = vm::say),
        copy = ::copy, edit = { id, text -> vm.edit(id, text) }, resend = vm::resend, branchFrom = { vm.branchFrom(it) }, fork = { vm.fork(it) },
        quote = vm::quote, regenerate = { vm.regenerate(it) }, continueAnswer = { vm.continueAnswer(it) }, switchTo = { vm.switchTo(it) },
        speak = { vm.speak(it) }, report = { vm.reportProblem(it) }, pin = { id, label -> vm.pin(id, label) }, newChat = { vm.newChat() },
        pickModel = { modelPicker = true }, compact = { vm.compactNow() }, showDetails = { details = it },
        saveAnswer = { text, name, id -> vm.saveArtifact(text, name, messageId = id) },
        stopLane = vm::stopLane, mergeLanes = { ids, analyse -> vm.mergeLanes(ids, analyse) },
        thumbnail = { id -> vm.thumbnail(id) }, analyseImage = { vm.analyseImage(it) }, exportArtifact = { vm.exportArtifact(it) },
        deleteFrom = { vm.deleteFrom(it) }, convertToTask = vm::convertToTask,
        compareAnswer = { vm.say("Comparaison : choisissez 2 à 4 modèles, puis renvoyez la question."); modelPicker = true },
        saveToDownloads = { text, id -> vm.saveToDownloads(text, id) },
    )
    val commands = {
        listOf(
            SlashCommand("new", "nouvelle discussion") { vm.newChat() },
            SlashCommand("model", "choisir le modèle") { modelPicker = true },
            SlashCommand("web", "mode recherche web") { vm.setMode(ChatMode.RESEARCH) },
            SlashCommand("tools", "outils : " + (Toolsets.labels[session?.toolset] ?: "Complet")) { vm.setToolset(if (session?.toolset == Toolsets.FULL) Toolsets.ASSISTANT else Toolsets.FULL) },
            SlashCommand("context", "contexte de la discussion") { contextTab = "context"; contextPanel = true },
            SlashCommand("remember", "retenir quelque chose") { vm.setDraft("Retiens que ") },
            SlashCommand("council", "mode conseil de réflexion") { vm.setMode(ChatMode.COUNCIL) },
            SlashCommand("compare", "comparer des modèles") { vm.say("Comparaison : choisissez 2 à 4 modèles dans le sélecteur de modèle."); modelPicker = true },
            SlashCommand("voice", "conversation vocale") { c.voice.startHandsFree() },
            SlashCommand("tasks", "voir les tâches") { onNavigate("tasks") },
        )
    }
    val composerActions = ComposerActions(
        setText = vm::setDraft, send = vm::send, stop = ::stop, attach = { n, m, s, o -> vm.attach(n, m, s, o) },
        setAttachmentMode = vm::setAttachmentMode, removeAttachment = vm::removeAttachment, addAttachment = vm::addAttachment,
        setMode = { vm.setMode(it) }, commands = commands, mentions = { q -> vm.mentions(q) },
        queueCancel = { vm.cancelQueued(it) }, queueConfirm = { vm.confirmQueued(it) }, queueMove = { id, up -> vm.moveQueued(id, up) },
        queueSendNow = { vm.sendNow(it) }, handsFree = { c.voice.startHandsFree() }, openPalette = { palettes = true },
    )

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.take(10).forEach { u -> describe(ctx, u)?.let { (name, mime, size) -> vm.attach(name, mime, size) { ctx.contentResolver.openInputStream(u) } } }
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            // The transcription stays editable before sending (doc 02 §2.9).
            if (!spoken.isNullOrBlank()) vm.setDraft(if (draft.isBlank()) spoken else "${draft.trimEnd()} $spoken")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) c.voice.startHandsFree() }
    val voiceOn = voice.phase != VoicePhase.OFF
    fun toggleVoice() {
        when {
            voiceOn -> vm.stopVoice()
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> c.voice.startHandsFree()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val models = remember(session?.providerId, session?.modelId, prefs.favoriteModels, prefs.recentModels, providers, palette) {
        modelOptions(palette, providers, session, prefs)
    }
    val current = session?.let { s -> models.firstOrNull { it.key == "${s.providerId}/${s.modelId}" } }
    val header = DiscussionHeaderUi(current, models, searchOn = ChatMode.of(session?.mode) == ChatMode.RESEARCH, voiceOn = voiceOn)
    val tools = ToolFamilies.all.map { f -> chipOf(f, f in toolsOn) }
    val taskArtifacts = remember(artifacts, task?.id) {
        artifacts.filter { a -> !a.deleted && a.producerTaskId != null && a.producerTaskId == task?.id }.take(6).map { a -> Triple(a.artifactId, a.name, artifactMeta(a)) }
    }

    val discussionActions = DiscussionActions(
        pickModel = { o -> o.key.split('/', limit = 2).takeIf { it.size == 2 }?.let { (p, m) -> vm.setModel(p, m) } },
        manageProviders = { onNavigate("providers") },
        setSearch = { on -> vm.setMode(if (on) ChatMode.RESEARCH else ChatMode.CHAT) },
        toggleVoice = ::toggleVoice,
        toggleTool = { family, on -> vm.setToolFamily(family, on) },
        stop = ::stop, resume = ::resume, showPlan = ::showPlan,
        openTasks = { onNavigate("tasks") }, openMemory = { onNavigate("memory") }, openWorker = { onNavigate("devices") },
        openMcp = { onNavigate("mcp") }, openPolicy = { onNavigate("audit") },
        reviewGit = { vm.setDraft("Fais la revue Git des changements de cette tâche : diff, risques et message de commit proposé.") ; scope.launch { runCatching { focus.requestFocus() } } },
        build = { vm.setDraft("Lance le build du projet de cette tâche et résume le résultat.") ; scope.launch { runCatching { focus.requestFocus() } } },
        tests = { vm.setDraft("Lance les tests du projet de cette tâche et résume les échecs.") ; scope.launch { runCatching { focus.requestFocus() } } },
        artifacts = { contextTab = "files"; contextPanel = true },
        openFile = { contextTab = "files"; contextPanel = true },
        doneAction = { scope.launch { runCatching { focus.requestFocus() } } },
    )
    val menu = listOf(
        ThreadMenuItem(Symbols.Add, "Nouvelle discussion", "Ctrl+N") { vm.newChat() },
        ThreadMenuItem(Symbols.Search, "Rechercher dans les discussions", "Ctrl+F") { searching = true },
        ThreadMenuItem(Symbols.Layers, "Contexte, mémoire et fichiers", "Ctrl+I") { contextTab = "context"; contextPanel = true },
        ThreadMenuItem(Symbols.Tune, "Choisir un modèle ou comparer…") { modelPicker = true },
        ThreadMenuItem(Symbols.Terminal, "Commandes…", "Ctrl+K") { palettes = true },
        ThreadMenuItem(Symbols.Edit, "Renommer", dividerBefore = true) { renaming = session },
        ThreadMenuItem(Symbols.ContentCopy, "Dupliquer la discussion") { vm.fork() },
        ThreadMenuItem(Symbols.Download, "Exporter en Markdown") { vm.export("md") },
        ThreadMenuItem(Symbols.DataObject, "Exporter en JSON") { vm.export("json") },
        ThreadMenuItem(Symbols.Description, "Exporter en texte") { vm.export("txt") },
        ThreadMenuItem(Symbols.Settings, "Réglages de la discussion", dividerBefore = true) { onNavigate("settings") },
    )

    // Follow new content only when the owner is already at the bottom (doc 12 auto-scroll).
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { stick = atBottom }
    val lastLength = (items.lastOrNull() as? TimelineItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(items.size, lastLength, liveStatus != null, queue.size, approval != null) {
        if (prefs.autoScroll && stick && items.isNotEmpty()) list.scrollToItem(Int.MAX_VALUE / 2)
    }
    LaunchedEffect(vm) {
        vm.jump.collect { id ->
            val index = designItems(vm.timeline.value, pvm.panel.value.task).indexOfFirst { it.key == id || (it is TimelineItem.Assistant && it.rows.any { r -> r.id == id }) || (it is TimelineItem.User && it.message.id == id) }
            if (index >= 0) { stick = false; list.animateScrollToItem(index) }
        }
    }

    // Keyboard (doc 02 §2.8): Ctrl+N new, Ctrl+F search, Ctrl+B sidebar, Ctrl+I context, Ctrl+K commands, Échap = STOP.
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        when {
            ctrl && e.key == Key.N -> { vm.newChat(); true }
            ctrl && e.key == Key.F -> { searching = true; true }
            ctrl && e.key == Key.B -> { shell.toggleSidebar(); true }
            ctrl && e.key == Key.I -> { contextPanel = !contextPanel; true }
            ctrl && e.key == Key.K -> { palettes = true; true }
            e.key == Key.Escape && active != null -> { stop(); true }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().then(keys)) {
        DiscussionScreen(
            shell, header, panel, tools, discussionActions, list,
            composer = {
                Column(Modifier.fillMaxWidth().imePadding()) {
                    if (files.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        files.forEachIndexed { i, f -> DraftAttachment(f, onMode = { m -> vm.setAttachmentMode(i, m) }, onRemove = { vm.removeAttachment(i) }) }
                    }
                    SlashAndMentions(draft, composerActions)
                    if (voiceOn) VoiceOverlay(voice, Modifier.fillMaxWidth().padding(bottom = 8.dp), onInterrupt = vm::interruptVoice,
                        onBackToText = { vm.stopVoice(); scope.launch { runCatching { focus.requestFocus() } } }, onStop = { vm.stopVoice(); stop() })
                    if (reading != Speaker.Reading.IDLE) ReadingBar(reading, Modifier.fillMaxWidth().padding(bottom = 8.dp), onPause = vm::pauseReading, onResume = vm::resumeReading, onStop = vm::stopReading)
                    Box {
                        var plus by remember { mutableStateOf(false) }
                        CortanaComposer(
                            draft, vm::setDraft, onSend = { vm.send() }, onAttach = { plus = true },
                            onMic = {
                                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictez votre message")
                                runCatching { dictation.launch(i) }.onFailure { vm.say("Dictée indisponible sur cet appareil.") }
                            },
                            listening = voiceOn, enterToSend = prefs.enterToSend, onEscape = if (active != null) ::stop else null, focus = focus,
                        )
                        DropdownMenu(plus, { plus = false }) {
                            DropdownMenuItem(text = { Text("Joindre des fichiers ou images") }, onClick = { plus = false; runCatching { picker.launch(arrayOf("*/*")) } })
                            DropdownMenuItem(text = { Text("Commandes…  (/)") }, onClick = { plus = false; palettes = true })
                            HorizontalDivider()
                            ChatMode.entries.filter { it != ChatMode.COMPARE }.forEach { m ->
                                val mode = ChatMode.of(session?.mode)
                                DropdownMenuItem(text = { Text((if (m == mode) "✓ " else "   ") + "Mode " + m.label) }, onClick = { plus = false; vm.setMode(m) })
                            }
                        }
                    }
                }
            },
            panelTab = panelTab, onPanelTab = { panelTab = it }, menu = menu,
        ) {
            if (items.isEmpty() && task == null) emptyThread { s -> vm.setDraft(s); scope.launch { runCatching { focus.requestFocus() } } }
            designThread(
                items, prefs, actions, panel, planFlash, liveStatus, approval, decision.takeIf { approval == null },
                onRefuse = { a -> vm.say("Action refusée : la tâche est suspendue."); c.orchestrator.refuseAndPause(a.tool) },
                onAllow = { a -> vm.reviewApproval(a.requestId) },
                artifacts = taskArtifacts,
                queue = queue, queueActions = QueueActions(remove = { vm.cancelQueued(it) }, confirm = { vm.confirmQueued(it) }, sendNow = { vm.sendNow(it) }, move = { id, up -> vm.moveQueued(id, up) }),
                onOpenArtifact = { contextTab = "files"; contextPanel = true },
            )
        }
        OverlaySheet(contextPanel, fromStart = false, width = 420.dp, onDismiss = { contextPanel = false }) {
            ContextPanel(vm, items, contextTab, { contextTab = it }, Modifier.fillMaxSize(), onClose = { contextPanel = false })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)) { d ->
            androidx.compose.material3.Snackbar(d, Modifier.border(1.dp, palette.elevatedBorder, io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes.Lg),
                shape = io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes.Lg, containerColor = palette.elevated,
                contentColor = palette.textControl, actionColor = palette.accentLink)
        }
        // Screen readers hear "Cortana répond…" and "Réponse terminée.", never each token (doc 10).
        announcement?.text?.let { a -> Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = a }) }
    }

    if (modelPicker) ModelPicker(vm, session, onDismiss = { modelPicker = false }, onOpenProviders = { modelPicker = false; onNavigate("providers") })
    if (palettes) CommandPalette(commands() + ChatMode.entries.filter { it != ChatMode.COMPARE }.map { m -> SlashCommand("mode-${m.wire}", "mode ${m.label}") { vm.setMode(m) } }) { palettes = false }
    renaming?.let { s -> RenameDialog(s.title, onDismiss = { renaming = null }) { vm.rename(s.id, it); renaming = null } }
    details?.let { d -> DetailsDialog(d) { details = null } }
    if (searching) SearchDialog(vm, onDismiss = { searching = false }) { s, m -> searching = false; vm.open(s, m) }
}

/** An empty conversation: Cortana's greeting and three suggestions, in the design's thread. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.emptyThread(onSuggestion: (String) -> Unit) {
    item("empty") {
        AssistantMessage("", running = false) {
            BodyText("Bonjour, je suis Cortana. Posez une question, joignez un fichier ou confiez-moi une tâche : son plan, ses étapes et ses actions s’afficheront ici.",
                Modifier.padding(top = 6.dp))
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Résume ce document pour moi", "Rappelle-moi dans 10 minutes de faire une pause", "Aide-moi à planifier ma semaine")
                    .forEach { s -> LinkChip(s, { onSuggestion(s) }) }
            }
        }
    }
}

/** Header models: the conversation's, the owner's favorites and recents, and each provider's default model. */
internal fun modelOptions(p: CortanaPalette, providers: List<ProviderEntity>, session: SessionEntity?, prefs: ChatPrefs): List<ModelOption> {
    val refs = buildList {
        session?.let { s -> if (s.providerId != null && s.modelId != null) add("${s.providerId}/${s.modelId}" to "Actuel") }
        prefs.favoriteModels.forEach { add(it to "Favori") }
        prefs.recentModels.forEach { add(it to "Récent") }
        providers.filter { it.enabled && it.defaultModelId != null }.forEach { add("${it.id}/${it.defaultModelId}" to "Par défaut") }
    }
    return refs.distinctBy { it.first }.mapNotNull { (ref, note) ->
        val parts = ref.split('/', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val provider = providers.firstOrNull { it.id == parts[0] } ?: return@mapNotNull null
        val (initial, color) = providerLook(p, provider.displayName, isLocal(provider))
        ModelOption(ref, initial, color, parts[1], provider.displayName, note)
    }.take(8)
}

private fun isLocal(p: ProviderEntity): Boolean {
    val u = p.baseUrl.lowercase()
    return "localhost" in u || "127.0.0.1" in u || p.presetId in setOf("ollama", "lmstudio", "llamacpp", "local", "worker")
}

/** The design's six chips (Outils, Système, Fichiers, Terminal, Git, Navigateur). */
internal fun chipOf(family: String, on: Boolean): ToolChipUi = when (family) {
    ToolFamilies.SYSTEM -> ToolChipUi(family, Symbols.Hexagon, Symbols.HexagonFill, "Système", on)
    ToolFamilies.FILES -> ToolChipUi(family, Symbols.Folder, Symbols.FolderFill, "Fichiers", on)
    ToolFamilies.TERMINAL -> ToolChipUi(family, Symbols.Terminal, Symbols.Terminal, "Terminal", on)
    ToolFamilies.GIT -> ToolChipUi(family, Symbols.Commit, Symbols.Commit, "Git", on)
    ToolFamilies.WEB -> ToolChipUi(family, Symbols.Language, Symbols.Language, "Navigateur", on)
    else -> ToolChipUi(family, Symbols.Handyman, Symbols.Handyman, "Outils", on)
}

internal fun artifactMeta(a: ArtifactEntity): String {
    val kind = a.name.substringAfterLast('.', "").uppercase().ifEmpty { a.type.replaceFirstChar { it.uppercase() } }
    return "$kind · ${sizeLabel(a.sizeBytes)}"
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes < 0 -> "taille inconnue"
    bytes < 1024 -> "$bytes o"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} Ko"
    else -> String.format(java.util.Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0))
}

/**
 * The rc4 components the design reuses (edit box, system lines, comparisons, dialogs, context panel)
 * take their colors from the design's palette, never from a second set of colors.
 */
internal fun designWorkspaceTokens(p: CortanaPalette, prefs: ChatPrefs): WorkspaceTokens = WorkspaceTokens(
    background = p.background, surface = p.surface, elevated = p.elevated, composer = p.composer,
    activity = p.badge, onActivity = p.accentText,
    warning = p.warning.copy(alpha = 0.16f), onWarning = p.warningText,
    critical = p.danger.copy(alpha = 0.16f), onCritical = p.dangerText,
    userBubble = p.userBubbleBottom, onUserBubble = p.userText,
    code = p.console, onCode = p.syntaxPlain,
    border = p.surfaceBorder, muted = p.textSecondary, link = p.accentLink,
    diffAdded = p.success.copy(alpha = 0.14f), diffRemoved = p.danger.copy(alpha = 0.14f),
    gap = when (prefs.density) { "compact" -> 6.dp; "large" -> 12.dp; else -> 8.dp },
    readingWidth = if (prefs.density == "large") 820.dp else 760.dp,
    reduceMotion = prefs.reduceMotion,
    highContrast = prefs.theme == "contrast",
)
