package io.github.artisanguillonrenov.cortana.ui.workspace

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.artisanguillonrenov.cortana.AppContainer
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentMode
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.ChatService
import io.github.artisanguillonrenov.cortana.core.chat.ChatStreamEvent
import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.Markdown
import io.github.artisanguillonrenov.cortana.core.chat.MdBlock
import io.github.artisanguillonrenov.cortana.core.chat.StreamCursor
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.context.Tokens
import io.github.artisanguillonrenov.cortana.core.memory.ChatQueueEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.core.orchestrator.ActiveTaskState
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.channels.awaitClose

/** Size of the context the next request would carry, in words the owner understands (doc 02 §2.10: never a fake precision). */
enum class ContextLevel(val label: String) {
    LOW("Contexte faible"), MEDIUM("Contexte moyen"), HIGH("Contexte élevé"), COMPACT("Compactage proche");
    val ok get() = this == LOW || this == MEDIUM
}

/** An announcement for assistive technologies (doc 10): start and end of an answer, never each token. */
data class Announcement(val text: String, val at: Long = System.currentTimeMillis())

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WorkspaceViewModel(private val c: AppContainer) : ViewModel() {
    private val chat: ChatService get() = c.chat
    private fun share() = SharingStarted.WhileSubscribed(5_000)

    val prefs: StateFlow<ChatPrefs> = c.settings.state.map { it.chat }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, c.settings.current.chat)
    val developer: StateFlow<Boolean> = prefs.map { it.developer }.stateIn(viewModelScope, share(), false)

    val sessionId = MutableStateFlow<String?>(null)
    val session: StateFlow<SessionEntity?> = sessionId.flatMapLatest { id -> if (id == null) flowOf(null) else c.conversations.observeSession(id) }
        .stateIn(viewModelScope, share(), null)
    val sessions: StateFlow<List<SessionEntity>> = c.conversations.observeActiveSessions().stateIn(viewModelScope, share(), emptyList())
    val archived: StateFlow<List<SessionEntity>> = c.conversations.observeArchivedSessions().stateIn(viewModelScope, share(), emptyList())
    val previews: StateFlow<Map<String, String>> = c.conversations.observePreviews().debounce(400).stateIn(viewModelScope, share(), emptyMap())
    val providers: StateFlow<List<ProviderEntity>> = c.providers.observe().stateIn(viewModelScope, share(), emptyList())
    val active: StateFlow<ActiveTaskState?> = c.orchestrator.active
    val runs = c.chatHub.runsBySession
    val halted: StateFlow<Boolean> = c.killSwitch.halted
    val pendingApproval: StateFlow<ApprovalRequest?> = c.approvals.pending
    val queue: StateFlow<List<ChatQueueEntity>> = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.observeQueue(id) }
        .stateIn(viewModelScope, share(), emptyList())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-line feedback for the snackbar. */
    val events: SharedFlow<String> = _events
    private val _jump = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Scroll the timeline to this message (search result, branch point). */
    val jump: SharedFlow<String> = _jump
    val announcement = MutableStateFlow<Announcement?>(null)

    // ------------------------------------------------------------------ timeline

    private val path: StateFlow<List<MessageEntity>> = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.conversations.observePath(id) }
        .stateIn(viewModelScope, share(), emptyList())

    /** Markdown parsed once per distinct text (a streaming answer re-parses only itself). */
    private val parsed = object : LinkedHashMap<String, List<MdBlock>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?) = size > 600
    }
    private fun parse(text: String): List<MdBlock> = synchronized(parsed) { parsed[text] } ?: Markdown.parse(text).also { synchronized(parsed) { parsed[text] = it } }

    private var previousItems: Map<String, TimelineItem> = emptyMap()

    val timeline: StateFlow<List<TimelineItem>> = combine(
        path.mapLatest { p -> p to (sessionId.value?.let { c.conversations.branchMap(it, p) } ?: emptyMap()) },
        c.chatHub.live.map { live -> live.values.filter { it.sessionId == sessionId.value }.associate { it.messageId to it.text } }.distinctUntilChanged(),
        prefs,
    ) { (p, branches), live, pr ->
        val items = withCompaction(ChatTimeline.build(p, branches, live, label = { cap -> c.registry.byCapability(cap)?.label ?: cap }, markdown = pr.markdown, parse = ::parse), latestCheckpoint)
        // Comparison lanes off the active branch: full rows, with the live text of the ones still streaming.
        val withLanes = items.map { item ->
            if (item !is TimelineItem.Compare) item
            else item.copy(lanes = item.lanes.map { l ->
                val row = runCatching { c.conversations.message(l.id) }.getOrNull() ?: l
                live[row.id]?.takeIf { row.status == io.github.artisanguillonrenov.cortana.core.memory.MessageStatus.STREAMING }?.let { row.copy(text = it) } ?: row
            })
        }
        // Unchanged items keep their instance, so the list skips them while an answer streams.
        val reused = withLanes.map { item -> previousItems[item.key]?.takeIf { it == item } ?: item }
        previousItems = reused.associateBy { it.key }
        reused
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, share(), emptyList())

    @Volatile private var latestCheckpoint: io.github.artisanguillonrenov.cortana.core.memory.ContextCheckpointEntity? = null

    /** "Contexte compacté" (doc 06 §6.4): a discreet event after the last message the summary covers — never a message of its own. */
    private fun withCompaction(items: List<TimelineItem>, cp: io.github.artisanguillonrenov.cortana.core.memory.ContextCheckpointEntity?): List<TimelineItem> {
        val until = cp?.coveredUntilMessageId ?: return items
        val at = items.indexOfFirst { it.key == until || (it is TimelineItem.Assistant && it.rows.any { r -> r.id == until }) || (it is TimelineItem.User && it.message.id == until) }
        if (at < 0) return items
        val row = MessageEntity("cp-${cp.id}", cp.sessionId, io.github.artisanguillonrenov.cortana.core.memory.Roles.SYSTEM, "", cp.createdAt)
        val line = TimelineItem.System(row, io.github.artisanguillonrenov.cortana.core.chat.MessagePart.SystemEvent("compacted",
            "Contexte compacté : ${cp.coveredCount} message(s) plus anciens sont résumés pour Cortana (panneau Contexte)."))
        return items.take(at + 1) + line + items.drop(at + 1)
    }

    /** Older messages exist above the loaded window. */
    val hasOlder: StateFlow<Boolean> = path.map { p -> p.firstOrNull()?.parentId != null }.stateIn(viewModelScope, share(), false)

    // ------------------------------------------------------------------ composer state (doc 02)

    val draft = MutableStateFlow("")
    val draftFiles = MutableStateFlow<List<AttachmentRef>>(emptyList())
    private var draftJob: Job? = null
    private var loadedDraftFor: String? = null

    val contextLevel: StateFlow<ContextLevel> = combine(path, draft, session) { p, d, s -> Triple(p, d, s) }.debounce(500).mapLatest { (p, d, s) ->
        val window = s?.let { sess -> sess.providerId?.let { pid -> sess.modelId?.let { mid -> runCatching { c.gateway.capabilities.resolve(pid, mid).contextWindow }.getOrNull() } } } ?: 8_192
        val used = p.filter { !it.hidden }.sumOf { Tokens.estimate(it.text) } + Tokens.estimate(d) + 2_500
        val ratio = used.toDouble() / window
        when { ratio < 0.3 -> ContextLevel.LOW; ratio < 0.6 -> ContextLevel.MEDIUM; ratio < 0.85 -> ContextLevel.HIGH; else -> ContextLevel.COMPACT }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, share(), ContextLevel.LOW)

    // ------------------------------------------------------------------ connectivity (doc 01 §1.5 statut hors ligne)

    val online: StateFlow<Boolean> = callbackFlow {
        val cm = c.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        fun now() = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        trySend(now())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(now()) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        awaitClose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }.stateIn(viewModelScope, share(), true)

    init {
        viewModelScope.launch {
            if (sessionId.value == null) sessionId.value = (c.conversations.latestSession() ?: newSessionEntity()).id
        }
        // Drafts follow the conversation (doc 02 §2.7).
        viewModelScope.launch {
            sessionId.collect { id ->
                if (id == null || id == loadedDraftFor) return@collect
                loadedDraftFor = id
                val d = chat.draft(id)
                draft.value = d?.text.orEmpty()
                draftFiles.value = chat.draftFiles(d)
            }
        }
        // Screen-reader announcements (start and end of an answer).
        viewModelScope.launch {
            val cursor = StreamCursor()
            c.chatHub.events.collect { e ->
                if (!cursor.accept(e) || e.sessionId != sessionId.value || !prefs.value.announceStreaming) return@collect
                when (e) {
                    is ChatStreamEvent.GenerationStarted -> announcement.value = Announcement("Cortana répond…")
                    is ChatStreamEvent.GenerationCompleted -> announcement.value = Announcement(if (e.state == "completed") "Réponse terminée." else "Réponse interrompue.")
                    is ChatStreamEvent.ApprovalRequired -> announcement.value = Announcement("Autorisation demandée : ${e.action}")
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ conversations

    private suspend fun newSessionEntity(incognito: Boolean = false): SessionEntity {
        val s = c.settings.current
        val def = s.defaultProviderId?.let { c.providers.get(it) } ?: c.providers.all().firstOrNull { it.enabled && it.defaultModelId != null }
        return c.conversations.createSession(incognito = incognito, providerId = def?.id, modelId = def?.defaultModelId, toolset = s.defaultToolset)
    }

    fun open(id: String, messageId: String? = null) {
        sessionId.value = id
        if (messageId != null) viewModelScope.launch {
            // A search result may sit on another branch: show that branch, then scroll to it.
            if (c.conversations.path(id).none { it.id == messageId }) runCatching { chat.switchTo(id, messageId) }
            delay(250)
            _jump.tryEmit(messageId)
        }
    }

    fun newChat(incognito: Boolean = false, projectId: String? = null) = viewModelScope.launch {
        val s = newSessionEntity(incognito)
        if (projectId != null) c.conversations.updateSession(s.copy(projectId = projectId))
        sessionId.value = s.id
    }

    fun delete(id: String) = viewModelScope.launch {
        c.conversations.deleteSession(id)
        if (sessionId.value == id) sessionId.value = (c.conversations.latestSession() ?: newSessionEntity()).id
    }

    private fun update(id: String? = sessionId.value, change: (SessionEntity) -> SessionEntity) = viewModelScope.launch {
        val s = id?.let { c.conversations.session(it) } ?: return@launch
        c.conversations.updateSession(change(s))
    }

    fun rename(id: String, title: String) = update(id) { it.copy(title = title.trim().take(80).ifEmpty { it.title }) }
    fun setPinned(id: String, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }
    fun setArchived(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }
    fun setMode(mode: ChatMode) = update { it.copy(mode = mode.wire) }
    fun setTags(id: String, tags: List<String>) = update(id) {
        it.copy(tagsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), tags.distinct().take(12).map { t -> t.take(30) }))
    }
    fun setToolset(t: String) = update { it.copy(toolset = t) }

    fun setModel(providerId: String?, modelId: String?) {
        val before = session.value
        update { it.copy(providerId = providerId, modelId = modelId) }
        // A visible system event (doc 03 §3.11), never a fake assistant message.
        if (before != null && (before.providerId != providerId || before.modelId != modelId) && path.value.isNotEmpty()) viewModelScope.launch {
            val name = providers.value.firstOrNull { it.id == providerId }?.displayName
            c.conversations.addMessage(before.id, io.github.artisanguillonrenov.cortana.core.memory.Roles.SYSTEM, "Modèle changé : ${name ?: "?"} · ${modelId ?: "?"}",
                metaJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.MessageMeta.serializer(),
                    io.github.artisanguillonrenov.cortana.core.chat.MessageMeta(event = "model_changed", providerId = providerId, modelId = modelId)))
            c.settings.update { st -> st.copy(chat = st.chat.copy(recentModels = (listOf("$providerId/$modelId") + st.chat.recentModels).distinct().take(8))) }
        }
    }

    suspend fun models(providerId: String, refresh: Boolean = false): Result<List<ModelDescriptor>> = runCatching { c.providers.listModels(providerId, refresh) }

    fun fork(messageId: String? = null) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        val copy = chat.fork(id, messageId)
        sessionId.value = copy.id
        _events.tryEmit("Copie créée : « ${copy.title} »")
    }

    // ------------------------------------------------------------------ composer

    fun setDraft(text: String) {
        draft.value = text
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        val id = sessionId.value ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(500); chat.saveDraft(id, draft.value, draftFiles.value) }
    }

    fun attach(name: String, mime: String?, size: Long, open: () -> java.io.InputStream?) = viewModelScope.launch {
        val ref = chat.attach(name, mime, size, sessionId.value, open)
        if (ref.status != "ready") _events.tryEmit("« $name » : ${ref.note ?: ref.status}")
        else ref.note?.let { _events.tryEmit("« $name » : $it") }
        draftFiles.value = draftFiles.value + ref
        scheduleDraftSave()
    }

    fun setAttachmentMode(index: Int, mode: AttachmentMode) {
        draftFiles.value = draftFiles.value.mapIndexed { i, a -> if (i == index) a.copy(mode = mode) else a }
        scheduleDraftSave()
    }

    fun addAttachment(ref: AttachmentRef) {
        if (draftFiles.value.none { it.artifactId == ref.artifactId }) draftFiles.value = draftFiles.value + ref
        scheduleDraftSave()
    }

    fun removeAttachment(index: Int) {
        draftFiles.value = draftFiles.value.filterIndexed { i, _ -> i != index }
        scheduleDraftSave()
    }

    /** Sends the composer's text; returns true when the composer can be cleared (sent or queued). */
    fun send(): Boolean {
        val id = sessionId.value ?: return false
        val text = draft.value
        val files = draftFiles.value
        if (text.isBlank() && files.none { it.status == "ready" }) return false
        draft.value = ""; draftFiles.value = emptyList(); draftJob?.cancel()
        viewModelScope.launch {
            val mode = ChatMode.of(session.value?.mode)
            when (val r = chat.send(id, text, files, mode)) {
                is ChatService.SendResult.Queued -> _events.tryEmit("Message mis en file : il partira dès que Cortana aura fini.")
                ChatService.SendResult.Busy -> { draft.value = text; draftFiles.value = files; _events.tryEmit("Cortana est occupée. Attendez la fin ou appuyez sur STOP.") }
                is ChatService.SendResult.Refused -> { draft.value = text; draftFiles.value = files; _events.tryEmit(r.reason) }
                else -> Unit
            }
        }
        return true
    }

    fun stop() = chat.stop()

    // ------------------------------------------------------------------ comparison (H9, doc 08)

    fun stopLane(lane: Int) { sessionId.value?.let { sid -> runs.value[sid]?.runId?.let { chat.stopLane(it, lane) } } }
    fun mergeLanes(ids: List<String>, analyse: Boolean) = act { chat.mergeCompared(it, ids, analyse) }
    fun setCompareRoutes(routes: List<String>) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        chat.setCompareRoutes(id, routes)
        _events.tryEmit(if (routes.size >= 2) "Mode comparaison : votre prochain message ira à ${routes.size} modèles." else "Comparaison désactivée.")
    }
    fun compareRoutes(s: SessionEntity?): List<String> = s?.let { settingsOf(it).compareRoutes } ?: emptyList()
    fun halt() = c.killSwitch.halt("workspace")

    // ------------------------------------------------------------------ message actions (doc 03 §3.4–3.8)

    private fun act(block: suspend (String) -> ChatService.SendResult) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        when (val r = block(id)) {
            ChatService.SendResult.Busy -> _events.tryEmit("Cortana est occupée : réessayez après la réponse en cours.")
            is ChatService.SendResult.Refused -> _events.tryEmit(r.reason)
            else -> Unit
        }
    }

    fun regenerate(answerId: String) = act { chat.regenerate(it, answerId) }
    fun continueAnswer(answerLastId: String) = act { chat.continueAnswer(it, answerLastId) }
    fun edit(messageId: String, text: String) = act { chat.edit(it, messageId, text) }

    /** Re-sends an owner message as a new turn at the end of the conversation. */
    fun resend(text: String) { draft.value = text; send() }

    fun switchTo(messageId: String) = viewModelScope.launch { sessionId.value?.let { chat.switchTo(it, messageId) } }

    fun branchFrom(messageId: String) = viewModelScope.launch {
        sessionId.value?.let { chat.branchFrom(it, messageId) }
        _events.tryEmit("La suite partira de ce message ; l'ancienne suite reste accessible.")
    }

    fun deleteFrom(messageId: String) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        val n = c.conversations.deleteFrom(id, messageId)
        _events.tryEmit(if (n > 0) "$n message(s) supprimé(s)." else "Rien à supprimer.")
    }

    /** "Convertir en tâche" (doc 03 §3.4): a draft asking Cortana to plan it; the owner reviews and sends. */
    fun convertToTask(text: String) = setDraft("Transforme ceci en tâche planifiée (propose l'heure et demande-moi confirmation) : " + text.take(1_000))

    /** "Enregistrer fichier": the answer as a Markdown file in Downloads/Cortana. */
    fun saveToDownloads(text: String, messageId: String) = viewModelScope.launch {
        val meta = listOfNotNull(sessionId.value?.let { "sessionId" to it }, "messageId" to messageId, "origin" to "chat").toMap()
        runCatching {
            val a = c.artifacts.registerText(text, "document", "reponse-cortana.md", metadata = meta)
            c.artifacts.exportToDownloads(a.artifactId) ?: error("copie impossible")
        }.onSuccess { _events.tryEmit("Enregistré : $it") }.onFailure { _events.tryEmit("Enregistrement impossible : ${it.message}") }
    }

    /** The tools this conversation's toolset offers, by category (Tools tab, doc 09 §9.7). */
    fun toolsByCategory(): Map<String, List<Pair<String, String>>> {
        val set = session.value?.toolset ?: return emptyMap()
        return c.registry.forToolset(set).groupBy { it.category.name }.mapValues { (_, l) -> l.map { it.label to "${it.capability} · ${it.baseRisk.name}" } }
    }

    fun quote(text: String) {
        val quoted = text.lineSequence().take(12).joinToString("\n") { "> $it" }
        setDraft((if (draft.value.isBlank()) "" else draft.value.trimEnd() + "\n\n") + quoted + "\n\n")
    }

    // ------------------------------------------------------------------ voice and reading (H11, doc 10)

    val reading = c.speaker.reading
    val voice = c.voice.state

    fun speak(text: String) {
        c.speaker.rate = prefs.value.speechRate
        c.speaker.speak(io.github.artisanguillonrenov.cortana.core.chat.MessageText.plainBlocks(Markdown.parse(text)))
    }
    fun pauseReading() = c.speaker.pause()
    fun resumeReading() { c.speaker.rate = prefs.value.speechRate; c.speaker.resume() }
    fun stopReading() = c.speaker.stop()
    fun interruptVoice() = c.voice.interrupt()
    fun stopVoice() = c.voice.stop("Retour au texte")

    /** A thumbnail of an image attachment (sampled down, never the full bitmap in memory). */
    suspend fun thumbnail(artifactId: String, maxPx: Int = 640): androidx.compose.ui.graphics.ImageBitmap? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val a = c.artifacts.get(artifactId) ?: return@withContext null
        val f = c.artifacts.file(a)
        if (!f.isFile || !a.mime.startsWith("image/")) return@withContext null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
    }

    /** "Analyser" an image (doc 10 §10.4): a new message with the image attached for analysis. */
    fun analyseImage(ref: AttachmentRef) {
        draftFiles.value = listOf(ref.copy(mode = AttachmentMode.ANALYZE))
        draft.value = "Analyse cette image."
        send()
    }

    fun saveArtifact(text: String, name: String, type: String = "document", messageId: String? = null) = viewModelScope.launch {
        val meta = listOfNotNull(sessionId.value?.let { "sessionId" to it }, messageId?.let { "messageId" to it }, "origin" to "chat").toMap()
        runCatching { c.artifacts.registerText(text, type, io.github.artisanguillonrenov.cortana.core.dev.ArtifactService.safeName(name), metadata = meta) }
            .onSuccess { _events.tryEmit("Enregistré dans les artefacts : ${it.name}") }
            .onFailure { _events.tryEmit("Enregistrement impossible : ${it.message}") }
    }

    fun reportProblem(messageId: String) = viewModelScope.launch {
        // Local evaluation signal only (doc 03 §3.10): nothing leaves the tablet.
        c.audit.record("owner", "chat.feedback", messageId, "ok", """{"signal":"problem"}""")
        _events.tryEmit("Merci : signalement enregistré sur la tablette.")
    }

    // ------------------------------------------------------------------ queue (doc 02 §2.6)

    fun cancelQueued(id: String) = viewModelScope.launch { chat.cancelQueued(id) }
    fun confirmQueued(id: String) = viewModelScope.launch { chat.confirmQueued(id) }
    fun moveQueued(id: String, up: Boolean) = viewModelScope.launch { sessionId.value?.let { chat.moveQueued(it, id, up) } }
    fun sendNow(id: String) = viewModelScope.launch { chat.sendNow(id) }

    // ------------------------------------------------------------------ approvals (never approved from the chat)

    fun refuseApproval(requestId: String) = c.approvals.resolve(requestId, ApprovalDecision(false, reason = "Refusé depuis la discussion"))
    fun reviewApproval(requestId: String) = c.approvals.reopen(requestId)

    fun setPrefs(change: (ChatPrefs) -> ChatPrefs) = viewModelScope.launch { c.settings.update { it.copy(chat = change(it.chat)) } }

    fun say(text: String) { _events.tryEmit(text) }

    // ------------------------------------------------------------------ context, pins, memory (H7, doc 06)

    val contextReport: StateFlow<io.github.artisanguillonrenov.cortana.core.context.ContextReport?> =
        combine(c.contextEngine.snapshots, sessionId) { snaps, id -> id?.let { snaps[it]?.report } }.stateIn(viewModelScope, share(), null)
    val pins: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.ChatPinEntity>> =
        sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.conversations.observePins(id) }.stateIn(viewModelScope, share(), emptyList())
    val checkpoints: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.ContextCheckpointEntity>> =
        sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.conversations.observeCheckpoints(id) }.stateIn(viewModelScope, share(), emptyList())

    // Declared after [checkpoints]: initialisers run in order (the timeline reads the latest one).
    init { viewModelScope.launch { checkpoints.collect { latestCheckpoint = it.firstOrNull() } } }
    /** The memories the last request of this conversation used, plus those switched off here (read-only; edited in the Memory screen). */
    val relevantMemories: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.MemoryEntity>> =
        combine(c.contextEngine.snapshots, session) { snaps, s -> (s?.let { snaps[it.id]?.memoryIds } ?: emptyList()) + (s?.let { settingsOf(it).disabledMemoryIds } ?: emptyList()) }
            .distinctUntilChanged().mapLatest { ids -> ids.distinct().mapNotNull { runCatching { c.memory.get(it) }.getOrNull() } }
            .stateIn(viewModelScope, share(), emptyList())

    fun memoryDisabledHere(id: String): Boolean = session.value?.let { id in settingsOf(it).disabledMemoryIds } == true

    /** Tool families the design's chips show as active for this conversation ([ToolFamilies.active]). */
    val toolFamiliesOn: StateFlow<Set<String>> = session.map { s -> s?.let { io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies.active(it.toolset, settingsOf(it).disabledToolFamilies) } ?: emptySet() }
        .stateIn(viewModelScope, share(), emptySet())

    /** A chip switched on or off: the toolset and the families switched off follow ([ToolFamilies.toggle]). */
    fun setToolFamily(family: String, on: Boolean) = update { s ->
        val st = settingsOf(s)
        val (toolset, off) = io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies.toggle(s.toolset, st.disabledToolFamilies, family, on)
        s.copy(toolset = toolset, settingsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), st.copy(disabledToolFamilies = off)))
    }

    /** Switches one memory off (or back on) for this conversation only (doc 06 §6.6); the memory itself is unchanged. */
    fun setMemoryHere(id: String, used: Boolean) = update { s ->
        val st = settingsOf(s)
        val ids = if (used) st.disabledMemoryIds - id else (st.disabledMemoryIds + id).distinct()
        s.copy(settingsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), st.copy(disabledMemoryIds = ids)))
    }

    /** "Proposer une correction": a draft for Cortana, which changes memories only through the memory service and its policy. */
    fun proposeCorrection(text: String) = setDraft("Corrige ce souvenir : « ${text.take(300)} » → ")

    val contextSnapshot: StateFlow<io.github.artisanguillonrenov.cortana.core.context.ContextSnapshot?> =
        combine(c.contextEngine.snapshots, sessionId) { snaps, id -> id?.let { snaps[it] } }.stateIn(viewModelScope, share(), null)

    fun addNotePin(text: String) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        if (text.isBlank()) return@launch
        c.conversations.pin(id, "note", io.github.artisanguillonrenov.cortana.util.Ids.new(), text.lineSequence().first().take(60), text.take(2_000))
    }

    fun pinArtifact(artifactId: String, name: String) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        c.conversations.pin(id, "artifact", artifactId, name)
        _events.tryEmit("Fichier épinglé au contexte.")
    }

    /** Undoes "Réduire le contexte": the older messages are sent verbatim again while they fit. */
    fun clearReduction() = update { s ->
        s.copy(settingsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), settingsOf(s).copy(compactedUntil = null)))
    }

    fun reduced(s: SessionEntity?): Boolean = s?.let { settingsOf(it).compactedUntil != null } == true

    // ------------------------------------------------------------------ projects (doc 06 §6.8, doc 04 §4.7)

    val projects: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.ProjectEntity>> =
        c.conversations.observeProjects().stateIn(viewModelScope, share(), emptyList())
    val projectFilter = MutableStateFlow<String?>(null)

    fun saveProject(id: String?, name: String, instructions: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val existing = id?.let { c.conversations.project(it) }
        c.conversations.saveProject(existing?.copy(name = name, instructions = instructions)
            ?: io.github.artisanguillonrenov.cortana.core.memory.ProjectEntity(io.github.artisanguillonrenov.cortana.util.Ids.new(), name, instructions, null, now, now))
    }

    fun deleteProject(id: String) = viewModelScope.launch { c.conversations.deleteProject(id); if (projectFilter.value == id) projectFilter.value = null }
    fun moveToProject(sessionId: String, projectId: String?) = update(sessionId) { it.copy(projectId = projectId) }
    fun setInheritProject(inherit: Boolean) = update { s ->
        s.copy(settingsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), settingsOf(s).copy(inheritProject = inherit)))
    }
    fun inheritsProject(s: SessionEntity?): Boolean = s?.let { settingsOf(it).inheritProject } != false

    fun pin(messageId: String, label: String) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        chat.pinMessage(id, messageId, label)
        _events.tryEmit("Épinglé : gardé dans le contexte tant que la place le permet.")
    }

    fun unpin(id: String) = viewModelScope.launch { c.conversations.unpin(id) }

    fun compactNow() = viewModelScope.launch {
        val s = session.value ?: return@launch
        val n = c.contextEngine.compactNow(s)
        _events.tryEmit(if (n > 0) "Échanges anciens résumés ($n messages) : les prochaines demandes seront plus légères." else "Rien à résumer pour l'instant.")
    }

    fun memoryOff(s: SessionEntity?): Boolean = s?.let { settingsOf(it).memoryOff } == true

    fun setMemoryOff(off: Boolean) = update { s ->
        s.copy(settingsJson = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), settingsOf(s).copy(memoryOff = off)))
    }

    private fun settingsOf(s: SessionEntity) = runCatching {
        io.github.artisanguillonrenov.cortana.util.AppJson.decodeFromString(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings.serializer(), s.settingsJson)
    }.getOrDefault(io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings())

    fun toggleFavorite(ref: String) = setPrefs { p -> p.copy(favoriteModels = if (ref in p.favoriteModels) p.favoriteModels - ref else (p.favoriteModels + ref).takeLast(20)) }

    // ------------------------------------------------------------------ artifacts (H8, doc 07)

    val artifacts: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity>> =
        sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.observeArtifacts(id) }.stateIn(viewModelScope, share(), emptyList())

    fun metadataOf(a: io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity): Map<String, String> = runCatching {
        io.github.artisanguillonrenov.cortana.util.AppJson.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), a.metadataJson)
    }.getOrDefault(emptyMap())

    /** All versions of [a] (same root), oldest first (doc 07 §7.3). */
    fun versionsOf(a: io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity): List<io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity> {
        val root = metadataOf(a)["rootId"] ?: a.artifactId
        return artifacts.value.filter { (metadataOf(it)["rootId"] ?: it.artifactId) == root }.sortedBy { it.createdAt }
    }

    fun isText(a: io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity) =
        a.mime.startsWith("text/") || a.mime in setOf("application/json", "application/xml", "text/x-diff") || a.name.substringAfterLast('.', "") in setOf("md", "kt", "kts", "java", "py", "js", "ts", "json", "yml", "yaml", "sh", "sql", "mmd", "txt", "csv")

    /** Text of an artifact for the panel (at most [max] characters), through the Document Service. */
    suspend fun artifactText(id: String, max: Int = 200_000): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val a = c.artifacts.get(id) ?: return@withContext ""
        if (isText(a)) runCatching { c.artifacts.file(a).readText().take(max) }.getOrDefault("")
        else runCatching { c.documents.render(c.documents.load("artifact:$id"), maxChars = max) }.getOrElse { "Aperçu indisponible : ${it.message}" }
    }

    /** An edit never overwrites: it becomes a new version linked to the previous one. */
    fun saveVersion(a: io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity, text: String) = viewModelScope.launch {
        val meta = metadataOf(a)
        val versions = versionsOf(a)
        val next = meta + mapOf("rootId" to (meta["rootId"] ?: a.artifactId), "previousVersionId" to a.artifactId, "version" to (versions.size + 1).toString()) +
            (sessionId.value?.let { mapOf("sessionId" to it) } ?: emptyMap())
        runCatching { c.artifacts.registerText(text, a.type, a.name, metadata = next) }
            .onSuccess { _events.tryEmit("Version ${versions.size + 1} enregistrée.") }.onFailure { _events.tryEmit("Enregistrement impossible : ${it.message}") }
    }

    suspend fun diff(before: String, after: String): io.github.artisanguillonrenov.cortana.core.chat.TextDiff.Summary =
        kotlinx.coroutines.withContext(Dispatchers.Default) { io.github.artisanguillonrenov.cortana.core.chat.TextDiff.unified(before, after) }

    fun exportArtifact(id: String) = viewModelScope.launch {
        _events.tryEmit(c.artifacts.exportToDownloads(id)?.let { "Exporté : $it" } ?: "Export impossible (fichier modifié ou introuvable).")
    }

    // ------------------------------------------------------------------ mentions (doc 02 §2.5)

    private val recentArtifacts: StateFlow<List<io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity>> =
        c.artifacts.observe().map { l -> l.filter { !it.deleted }.take(200) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun mentions(query: String): List<Mention> {
        val q = query.lowercase()
        val files = recentArtifacts.value.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(6).map { a ->
            Mention(a.name, "fichier", AttachmentRef(a.artifactId, a.name, a.mime, a.sizeBytes, AttachmentMode.REFERENCE))
        }
        val chats = sessions.value.filter { it.id != sessionId.value && (q.isEmpty() || it.title.lowercase().contains(q)) }.take(3).map { Mention(it.title, "discussion") }
        return files + chats
    }

    // ------------------------------------------------------------------ export / import / search (H10, doc 04)

    fun export(format: String) = viewModelScope.launch {
        val id = sessionId.value ?: return@launch
        runCatching {
            val (name, content) = chat.export(id, format)
            val a = c.artifacts.registerText(content, "export", name)
            c.artifacts.exportToDownloads(a.artifactId) ?: "artefact ${a.name}"
        }.onSuccess { _events.tryEmit("Exporté : $it") }.onFailure { _events.tryEmit("Export impossible : ${it.message}") }
    }

    fun importChat(json: String) = viewModelScope.launch {
        runCatching { chat.import(json) }.onSuccess { sessionId.value = it.id; _events.tryEmit("Conversation importée.") }
            .onFailure { _events.tryEmit(it.message ?: "Import impossible.") }
    }

    data class SearchFilters(val role: String? = null, val pinnedOnly: Boolean = false, val withFiles: Boolean = false, val projectId: String? = null)
    val searchFilters = MutableStateFlow(SearchFilters())
    private val searchQuery = MutableStateFlow("")
    val searchResults: StateFlow<List<ChatService.SearchHit>> = combine(searchQuery.debounce(250), searchFilters) { q, f -> q to f }
        .mapLatest { (q, f) -> runCatching { chat.search(q, f.role, f.pinnedOnly, f.withFiles, projectId = f.projectId) }.getOrDefault(emptyList()) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, share(), emptyList())

    fun search(q: String) { searchQuery.value = q }

    // ------------------------------------------------------------------ branches (doc 04 §4.3)

    /** One leaf of the conversation tree: where a branch ends, labelled by its last owner message. */
    data class BranchInfo(val leafId: String, val label: String, val at: Long, val messages: Int, val current: Boolean)

    val branches: StateFlow<List<BranchInfo>> = combine(
        sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.conversations.observeMessages(id) }.debounce(300),
        session,
    ) { all, s ->
        val byId = all.associateBy { it.id }
        val parents = all.mapNotNullTo(HashSet()) { it.parentId }
        val leaf = s?.activeLeafId
        all.filter { it.id !in parents && !it.hidden }.map { l ->
            var cur: MessageEntity? = l
            var label: String? = null
            var n = 0
            while (cur != null && n < 100_000) {
                if (label == null && cur.role == io.github.artisanguillonrenov.cortana.core.memory.Roles.USER && !cur.hidden) label = cur.text
                n++
                cur = cur.parentId?.let(byId::get)
            }
            BranchInfo(l.id, (label ?: l.text).lineSequence().first().take(80), l.createdAt, n, l.id == leaf)
        }.sortedByDescending { it.at }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, share(), emptyList())

    fun openBranch(leafId: String) = viewModelScope.launch { sessionId.value?.let { c.conversations.setLeaf(it, leafId) } }

    /** Shared content opens a new conversation as a draft with its files attached; the owner decides to send. */
    fun receiveShare(share: SharedContent, context: android.content.Context) = viewModelScope.launch {
        val s = newSessionEntity()
        loadedDraftFor = s.id
        sessionId.value = s.id
        draft.value = listOfNotNull(share.subject, share.text).joinToString("\n\n")
        draftFiles.value = emptyList()
        share.uris.forEach { uri ->
            val resolver = context.contentResolver
            var name = uri.lastPathSegment ?: "fichier"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { n -> name = n } }
                        c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                    }
                }
            }
            val ref = chat.attach(name, runCatching { resolver.getType(uri) }.getOrNull(), size, s.id) { resolver.openInputStream(uri) }
            draftFiles.value = draftFiles.value + ref
        }
        scheduleDraftSave()
        _events.tryEmit("Contenu partagé prêt : relisez-le puis envoyez.")
    }
}
