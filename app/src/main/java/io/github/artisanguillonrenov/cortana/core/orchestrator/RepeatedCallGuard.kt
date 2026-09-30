package io.github.artisanguillonrenov.cortana.core.orchestrator

/**
 * Anti-repetition for one plan step's model ↔ tools loop (kept across retries of that step).
 *
 * An identical call (same capability, same canonical arguments) that already failed is not
 * dispatched again unless a relevant state change happened since (files or Git state modified, the
 * owner approved an action, a connection was re-authorised, a fetch refreshed the remote…). The
 * first blocked repetition only informs the model; the next one ends the step cleanly instead of
 * spending the model budget. A capability the owner explicitly refused is never asked again in the
 * same step. `test.run → patch → test.run` stays allowed: the patch is a state change.
 * Pure (no Android, no I/O) — unit-tested.
 */
class RepeatedCallGuard {
    enum class Verdict { RUN, BLOCK_INFORM, BLOCK_STOP }

    data class Decision(val verdict: Verdict, val reason: String? = null)

    /** Signature of a failed call → state epoch at the time of the failure. */
    private val failedAt = HashMap<String, Long>()
    private val lastError = HashMap<String, String>()
    private val refusedByOwner = HashSet<String>()
    private var epoch = 0L
    private var blocked = 0

    fun check(capability: String, signature: String): Decision {
        val reason = when {
            capability in refusedByOwner ->
                "Le propriétaire a explicitement refusé « $capability » dans cette étape : ne la redemande pas. Conclus en l'informant, ou propose une autre voie sans cette action."
            failedAt[signature] == epoch ->
                "Appel identique déjà échoué sans changement d'état depuis (${lastError[signature].orEmpty().take(200)}). Il n'a pas été relancé : change d'approche ou conclus en expliquant le blocage."
            else -> return Decision(Verdict.RUN)
        }
        blocked++
        return Decision(if (blocked >= 2) Verdict.BLOCK_STOP else Verdict.BLOCK_INFORM, reason)
    }

    /**
     * [stateChanged]: the call succeeded and objectively changed something that could resolve an
     * earlier failure. [ownerRefused]: the owner said no to this action.
     */
    fun record(capability: String, signature: String, ok: Boolean, stateChanged: Boolean, ownerRefused: Boolean, error: String? = null) {
        if (ownerRefused) refusedByOwner += capability
        if (ok) {
            failedAt.remove(signature)
            if (stateChanged) epoch++
        } else {
            failedAt[signature] = epoch
            lastError[signature] = error.orEmpty()
        }
    }

    /** A change observed outside tool results (e.g. the owner granted an approval during the step). */
    fun stateChanged() { epoch++ }

    companion object {
        /** Successful calls of these capabilities refresh state a failure may depend on, although they declare no side effect. */
        val STATE_REFRESH = setOf("repo.fetch", "connection.authorize", "connection.add", "connection.check")
    }
}
