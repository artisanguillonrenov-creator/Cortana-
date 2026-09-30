package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity

/**
 * What the orchestrator needs to delegate to the council (doc 02 §2.2): the selector, the engine,
 * the relevant facts read through the ContextEngine, and the battery level for Auto mode.
 */
class CouncilGate(
    val selector: CouncilPolicySelector,
    val engine: CognitiveCouncilEngine,
    val facts: suspend (session: SessionEntity, objective: String) -> List<String>,
    val battery: () -> Int? = { null },
    val roleLabel: (String) -> String = { it },
    /** Tokens the council used since midnight (all runs), for the owner's daily cap. */
    val tokensToday: suspend () -> Int = { 0 },
)

/** Live progress shown while a council runs (doc 07 §7.6): phases and slot states, never content. */
data class CouncilProgress(
    val runId: String,
    val mode: CouncilMode,
    val phase: CouncilRunStatus = CouncilRunStatus.CREATED,
    val round: Int = 0,
    val slots: List<Pair<String, SlotStatus>> = emptyList(),
) {
    fun on(e: CouncilEvent): CouncilProgress = when (e) {
        is CouncilEvent.PlanCreated -> copy(slots = e.slots.map { (_, profile) -> profile to SlotStatus.PENDING })
        is CouncilEvent.Phase -> copy(phase = e.status)
        is CouncilEvent.AgentStarted -> copy(round = e.round, slots = mark(e.slotId, SlotStatus.RUNNING))
        is CouncilEvent.AgentCompleted -> copy(slots = mark(e.slotId, SlotStatus.SUCCEEDED))
        is CouncilEvent.AgentFailed -> copy(slots = mark(e.slotId, if (e.code == "timeout") SlotStatus.TIMED_OUT else SlotStatus.FAILED))
        else -> this
    }

    private fun mark(slotId: String, status: SlotStatus): List<Pair<String, SlotStatus>> {
        val index = slotId.substringAfterLast("-s").toIntOrNull() ?: return slots
        return slots.mapIndexed { i, s -> if (i == index) s.first to status else s }
    }

    /** "4 analyses en cours", "Confrontation", … (doc 07 §7.6). */
    fun label(profiles: (String) -> String): String {
        val running = slots.count { it.second == SlotStatus.RUNNING }
        val phaseText = when (phase) {
            CouncilRunStatus.CREATED, CouncilRunStatus.PLANNING, CouncilRunStatus.RESOLVING_MODELS, CouncilRunStatus.PREPARING_CONTEXT -> "Préparation du conseil"
            CouncilRunStatus.ROUND_INITIAL -> if (running > 0) "$running analyse${if (running > 1) "s" else ""} en cours" else "Analyses"
            CouncilRunStatus.ASSESSING, CouncilRunStatus.DECIDING -> "Comparaison des avis"
            CouncilRunStatus.ROUND_CRITIQUE -> "Confrontation (tour $round)"
            CouncilRunStatus.CHALLENGING -> "Défi final"
            CouncilRunStatus.SYNTHESIZING -> "Synthèse"
            CouncilRunStatus.VERIFYING -> "Vérification"
            else -> "Conseil terminé"
        }
        val states = slots.joinToString(" · ") { (p, s) -> "${profiles(p)} ${when (s) { SlotStatus.SUCCEEDED -> "✓"; SlotStatus.RUNNING -> "…"; SlotStatus.PENDING -> "○"; else -> "✗" }}" }
        return if (states.isEmpty()) phaseText else "$phaseText — $states"
    }
}
