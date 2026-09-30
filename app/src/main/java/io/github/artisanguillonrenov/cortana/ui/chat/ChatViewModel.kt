package io.github.artisanguillonrenov.cortana.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.artisanguillonrenov.cortana.AppContainer
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.core.orchestrator.ActiveTaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer) : ViewModel() {
    val sessionId = MutableStateFlow<String?>(null)
    val session: StateFlow<SessionEntity?> = sessionId.flatMapLatest { id -> if (id == null) flowOf(null) else c.conversations.observeSession(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val messages: StateFlow<List<MessageEntity>> = sessionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.conversations.observePath(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions: StateFlow<List<SessionEntity>> = c.conversations.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val providers: StateFlow<List<ProviderEntity>> = c.providers.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val active: StateFlow<ActiveTaskState?> = c.orchestrator.active
    val halted: StateFlow<Boolean> = c.killSwitch.halted
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    init {
        viewModelScope.launch {
            if (sessionId.value == null) sessionId.value = (c.conversations.latestSession() ?: newSessionEntity()).id
        }
    }

    private suspend fun newSessionEntity(incognito: Boolean = false): SessionEntity {
        val s = c.settings.current
        val def = s.defaultProviderId?.let { c.providers.get(it) } ?: c.providers.all().firstOrNull { it.enabled && it.defaultModelId != null }
        return c.conversations.createSession(incognito = incognito, providerId = def?.id, modelId = def?.defaultModelId, toolset = s.defaultToolset)
    }

    fun open(id: String) { sessionId.value = id }

    fun newSession(incognito: Boolean = false) {
        viewModelScope.launch { sessionId.value = newSessionEntity(incognito).id }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            c.conversations.deleteSession(id)
            if (sessionId.value == id) sessionId.value = (c.conversations.latestSession() ?: newSessionEntity()).id
        }
    }

    fun send(text: String): Boolean {
        val id = sessionId.value ?: return false
        if (text.isBlank()) return false
        if (!c.orchestrator.submit(id, text.trim())) {
            _events.tryEmit("Une tâche est déjà en cours. Attendez sa fin ou appuyez sur STOP.")
            return false
        }
        return true
    }

    fun cancel() = c.orchestrator.cancel()

    fun halt() = c.killSwitch.halt("chat")

    fun setModel(providerId: String?, modelId: String?) {
        val s = session.value ?: return
        viewModelScope.launch { c.conversations.updateSession(s.copy(providerId = providerId, modelId = modelId)) }
    }

    fun setToolset(t: String) {
        val s = session.value ?: return
        viewModelScope.launch { c.conversations.updateSession(s.copy(toolset = t)) }
    }

    fun rename(title: String) {
        val s = session.value ?: return
        viewModelScope.launch { c.conversations.updateSession(s.copy(title = title.take(80))) }
    }

    suspend fun models(providerId: String, refresh: Boolean = false): Result<List<ModelDescriptor>> =
        runCatching { c.providers.listModels(providerId, refresh) }
}
