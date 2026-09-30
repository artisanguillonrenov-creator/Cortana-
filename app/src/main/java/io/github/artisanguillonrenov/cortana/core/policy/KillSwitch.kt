package io.github.artisanguillonrenov.cortana.core.policy

import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * §9.4 — global, local kill switch. Halting is immediate (< 1 s): the in-memory flag flips
 * synchronously, listeners (orchestrator, accessibility executor) cancel in-flight work, then the
 * state is persisted. Resume requires device credential (enforced by the UI) and is audited.
 */
class KillSwitch(
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var haltedNow: Boolean = settings.current.autonomyState == "halted"
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    val halted: StateFlow<Boolean> = settings.state.map { it.autonomyState == "halted" }
        .stateIn(scope, SharingStarted.Eagerly, haltedNow)

    fun isHalted(): Boolean = haltedNow

    fun addListener(l: (String) -> Unit) {
        listeners += l
    }

    fun halt(source: String) {
        haltedNow = true
        listeners.forEach { runCatching { it(source) } }
        scope.launch {
            settings.update { it.copy(autonomyState = "halted") }
            audit.record("owner", "kill_switch.halt", source, "halted")
        }
    }

    /** Call only after a successful device-credential/biometric check. */
    fun resume(source: String) {
        haltedNow = false
        scope.launch {
            settings.update { it.copy(autonomyState = "active") }
            audit.record("owner", "kill_switch.resume", source, "active")
        }
    }
}
