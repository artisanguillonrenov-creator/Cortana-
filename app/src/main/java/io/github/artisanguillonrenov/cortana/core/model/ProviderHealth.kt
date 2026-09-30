package io.github.artisanguillonrenov.cortana.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-provider circuit breaker (doc 02 §models, checklist "circuit breaker / health"): after
 * [threshold] consecutive retryable failures a provider is skipped for [cooldownMs]; the first call
 * after the cool-down is a probe (half-open) — success closes the circuit, failure re-opens it.
 */
class ProviderHealth(private val threshold: Int = 3, private val cooldownMs: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    data class Status(val state: State = State.CLOSED, val consecutiveFailures: Int = 0, val openUntil: Long = 0, val lastError: String? = null, val lastSuccessAt: Long = 0)

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status

    fun state(providerId: String): State {
        val s = _status.value[providerId] ?: return State.CLOSED
        return when {
            s.state == State.OPEN && clock() >= s.openUntil -> State.HALF_OPEN
            else -> s.state
        }
    }

    /** False while the circuit is open; a half-open provider gets one probe. */
    fun allow(providerId: String): Boolean = state(providerId) != State.OPEN

    fun onSuccess(providerId: String) {
        _status.update { it + (providerId to Status(lastSuccessAt = clock())) }
    }

    fun onFailure(providerId: String, error: String?, retryable: Boolean) {
        if (!retryable) return // a 4xx is a request problem, not an outage
        _status.update { m ->
            val cur = m[providerId] ?: Status()
            val failures = cur.consecutiveFailures + 1
            val open = failures >= threshold || state(providerId) == State.HALF_OPEN
            m + (providerId to cur.copy(
                state = if (open) State.OPEN else State.CLOSED, consecutiveFailures = failures,
                openUntil = if (open) clock() + cooldownMs else 0, lastError = error,
            ))
        }
    }
}
