package io.github.artisanguillonrenov.cortana.core.recovery

import io.github.artisanguillonrenov.cortana.contracts.PlanStep
import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.contracts.RecommendedAction
import io.github.artisanguillonrenov.cortana.contracts.VerificationResult
import io.github.artisanguillonrenov.cortana.contracts.VerificationStatus
import io.github.artisanguillonrenov.cortana.core.verifier.Verifier

enum class RecoveryAction { CONTINUE, RETRY_STEP, REPLAN, ASK_USER, FAIL }

/** [repair]: the retry is a Software Factory repair iteration (counted separately). */
data class RecoveryDecision(val action: RecoveryAction, val reason: String, val backoffMs: Long = 0, val repair: Boolean = false)

/** Separate counters (doc 04 §6: "compteurs distincts : retries, repairs, replans"). */
data class RecoveryBudget(
    val retriesUsed: Int, val replansUsed: Int, val maxReplans: Int,
    val repairsUsed: Int = 0, val maxRepairs: Int = 3, val sameFailureLimit: Int = 2,
)

/**
 * The one RecoveryEngine (§12.2, doc 04 §6). Bounded, layered: retry (idempotent/transient) →
 * replan → ask the owner → stop. The same failure repeating is not retried again.
 */
class RecoveryEngine {
    private val signatures = mutableMapOf<String, MutableList<String>>()

    fun decide(
        taskId: String,
        strategy: PlanStrategy,
        step: PlanStep,
        verification: VerificationResult,
        budget: RecoveryBudget,
        uncertainSideEffect: Boolean,
        sideEffectsDone: Boolean,
    ): RecoveryDecision {
        if (uncertainSideEffect) return RecoveryDecision(RecoveryAction.ASK_USER, "Une action a peut-être déjà été exécutée : confirmation nécessaire avant de continuer")
        if (verification.status == VerificationStatus.PASSED || verification.status == VerificationStatus.PARTIAL ||
            (verification.status == VerificationStatus.UNCERTAIN && verification.recommendedAction == RecommendedAction.CONTINUE)
        ) return RecoveryDecision(RecoveryAction.CONTINUE, verification.reason)
        when (verification.recommendedAction) {
            RecommendedAction.FAIL -> return RecoveryDecision(RecoveryAction.FAIL, verification.reason)
            RecommendedAction.ASK_USER -> return RecoveryDecision(RecoveryAction.ASK_USER, verification.reason)
            else -> Unit
        }
        if (verification.evidence.any { it.kind == Verifier.GATE_EVIDENCE }) return repair(taskId, strategy, verification, budget)
        val sig = verification.reason.take(120)
        val history = signatures.getOrPut("$taskId:${step.stepId}") { mutableListOf() }
        val repeats = history.count { it == sig }
        history += sig
        val canRetry = step.attempts < step.retryPolicy.maxAttempts && repeats < 1 &&
            (!step.retryPolicy.retryOnlyIdempotent || !sideEffectsDone || verification.recommendedAction == RecommendedAction.RETRY)
        if (canRetry) return RecoveryDecision(RecoveryAction.RETRY_STEP, "Nouvel essai de l'étape : ${verification.reason}", backoffMs = step.retryPolicy.backoffMs * step.attempts)
        if (strategy == PlanStrategy.DAG && budget.replansUsed < budget.maxReplans) {
            return RecoveryDecision(RecoveryAction.REPLAN, "Replanification : ${verification.reason}")
        }
        return RecoveryDecision(RecoveryAction.FAIL, "Échec après ${step.attempts} essai(s) et ${budget.replansUsed} replanification(s) : ${verification.reason}")
    }

    /**
     * Repair loop of a task refused by a completion gate (doc 03 §15): bounded by [RecoveryBudget.maxRepairs];
     * each iteration must bring new diagnostics — the same failure [RecoveryBudget.sameFailureLimit] times
     * triggers a replan (DAG, once), and if that does not help either, the owner is asked instead of looping.
     */
    private fun repair(taskId: String, strategy: PlanStrategy, verification: VerificationResult, budget: RecoveryBudget): RecoveryDecision {
        val sig = verification.failureSignature ?: verification.reason.take(200)
        val history = signatures.getOrPut("$taskId:#repair") { mutableListOf() }
        history += sig
        val same = history.count { it == sig }
        val replanned = signatures["$taskId:#replanned"]
        if (budget.repairsUsed >= budget.maxRepairs) {
            return RecoveryDecision(RecoveryAction.ASK_USER, "Limite de ${budget.maxRepairs} réparation(s) atteinte sans réussite : ${verification.reason}")
        }
        if (same >= budget.sameFailureLimit) {
            if (strategy == PlanStrategy.DAG && replanned?.contains(sig) != true && budget.replansUsed < budget.maxReplans) {
                signatures.getOrPut("$taskId:#replanned") { mutableListOf() } += sig
                return RecoveryDecision(RecoveryAction.REPLAN, "Même échec $same fois, nouvelle approche : ${verification.reason}")
            }
            return RecoveryDecision(RecoveryAction.ASK_USER, "Même échec $same fois malgré les corrections : ${verification.reason}")
        }
        return RecoveryDecision(RecoveryAction.RETRY_STEP, "Réparation ${budget.repairsUsed + 1}/${budget.maxRepairs} : ${verification.reason}", repair = true)
    }

    fun forget(taskId: String) {
        signatures.keys.removeAll { it.startsWith("$taskId:") }
    }
}
