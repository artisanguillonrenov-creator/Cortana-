package io.github.artisanguillonrenov.cortana.core.council

/** The judge's verdict on anonymised candidates (doc 14 §14.9), mapped back to candidate keys. */
data class JudgeVerdict(
    val ranking: List<String>,
    val disqualified: Set<String> = emptySet(),
    val preferred: String? = null,
    val confidence: Double = 0.5,
    val unresolvedIssues: List<String> = emptyList(),
)

data class DecisionInput(
    val protocol: DecisionProtocol,
    val assessment: Assessment,
    val previous: Assessment? = null,
    /** Candidates the verifier (or tool evidence) invalidated: never selected. */
    val verifierRejected: Set<String> = emptySet(),
    val judge: JudgeVerdict? = null,
    val minEvidence: Double = 0.3,
    /** Last decision of the run: a weak but clear leader is selected instead of asking for another round. */
    val final: Boolean = false,
    val evidenceWeighted: Boolean = false,
    /** Distinct models among valid agents (diverse-model support is relative to it). */
    val totalModels: Int = 1,
)

/**
 * Deterministic decisions (doc 04 §4.1–4.3, doc 13 §13.7, doc 15 §15.5): identical inputs give the
 * identical decision. A majority is never a proof — an unresolved critical concern blocks any
 * protocol, and tool-verified evidence can overturn a majority that it contradicts.
 */
object DefaultDecisionEngine : CouncilDecisionEngine {
    override fun decide(input: DecisionInput): CouncilDecision {
        val a = input.assessment
        val protocol = if (input.protocol == DecisionProtocol.AUTO) DecisionProtocol.HYBRID else input.protocol
        val eligible = a.clusters.filter { it.key !in input.verifierRejected && it.key !in input.judge?.disqualified.orEmpty() }
        val ballots = a.ballots
        val n = ballots.size.coerceAtLeast(1).toDouble()
        fun first(k: String) = ballots.count { it.ranking.firstOrNull() == k } / n
        val m = eligible.size
        fun borda(k: String) = ballots.sumOf { b -> val i = b.ranking.filter { r -> eligible.any { it.key == r } }.indexOf(k); if (i < 0) 0 else m - i }
        fun approvals(k: String) = ballots.count { k in it.approvals }
        fun points(k: String) = ballots.sumOf { it.points[k] ?: 0 }
        val prevSupport = input.previous?.clusters?.associate { it.key to it.supporters.size }.orEmpty()
        fun score(c: CandidateCluster): Double {
            val stability = when { input.previous == null -> 0.5; (prevSupport[c.key] ?: -1) >= c.supporters.size -> 1.0; prevSupport.containsKey(c.key) -> 0.6; else -> 0.3 }
            val judge = input.judge?.let { j -> when (val i = j.ranking.indexOf(c.key)) { 0 -> 1.0; -1 -> 0.0; else -> 0.5 / i } } ?: 0.0
            val verifier = 0.5 + 0.5 * c.toolVerifiedShare
            val diverse = if (input.totalModels <= 0) 0.0 else c.models.size.toDouble() / input.totalModels
            val evidenceWeight = if (input.evidenceWeighted) 0.35 else 0.25
            val penalty = (0.5 * c.criticalAgainst.size + 0.15 * c.highAgainst.size).coerceAtMost(1.0)
            return (0.35 * first(c.key) + evidenceWeight * c.evidenceScore.coerceAtLeast(0.0) + 0.15 * verifier + 0.10 * diverse + 0.10 * stability + 0.05 * judge - penalty)
        }
        val scores = eligible.associate { it.key to score(it) }
        val byScore = eligible.sortedWith(compareByDescending<CandidateCluster> { scores[it.key] }.thenByDescending { it.evidenceScore }.thenBy { it.key })

        fun minority(selected: CandidateCluster?): List<String> = eligible.filter { it.key != selected?.key }.filter { c ->
            (selected != null && c.evidenceScore > selected.evidenceScore + 0.1) || c.toolVerifiedShare > 0.0 || c.supporters.isNotEmpty() && a.contributions
                .filter { it.slotId in c.supporters }.any { k -> k.concerns.any { it.severity.weight >= ConcernSeverity.HIGH.weight } }
        }.map { c ->
            val why = listOfNotNull(
                "preuves d'outil".takeIf { c.toolVerifiedShare > 0.0 },
                "mieux étayée".takeIf { selected != null && c.evidenceScore > selected.evidenceScore + 0.1 },
                "objection importante".takeIf { a.contributions.filter { it.slotId in c.supporters }.any { k -> k.concerns.any { it.severity.weight >= ConcernSeverity.HIGH.weight } } },
            ).joinToString(", ")
            "Minorité (${c.supporters.size}) : ${c.candidate.summary.take(300)}${if (why.isNotEmpty()) " — $why" else ""}"
        }.take(4)

        fun result(selected: CandidateCluster?, reason: String?, overturned: Boolean = false): CouncilDecision {
            val critical = if (selected != null) selected.criticalAgainst else a.criticalConcerns
            return CouncilDecision(
                protocol = protocol, selectedCandidateKey = selected?.key, agreementScore = a.agreement, divergenceScore = a.divergence,
                evidenceCoverageScore = a.evidenceCoverage, unresolvedCriticalConcerns = critical, minorityReport = minority(selected), ballots = ballots,
                unresolvedReason = reason, scores = scores, judgeUsed = input.judge != null, overturnedMajority = overturned,
            )
        }
        if (eligible.isEmpty()) return result(null, if (a.clusters.isEmpty()) "no_candidate" else "all_rejected")

        fun tieBreak(x: CandidateCluster, y: CandidateCluster): CandidateCluster? = when {
            x.evidenceScore != y.evidenceScore -> if (x.evidenceScore > y.evidenceScore) x else y
            x.toolVerifiedShare != y.toolVerifiedShare -> if (x.toolVerifiedShare > y.toolVerifiedShare) x else y
            (x.criticalAgainst.size + x.highAgainst.size) != (y.criticalAgainst.size + y.highAgainst.size) -> if (x.criticalAgainst.size + x.highAgainst.size < y.criticalAgainst.size + y.highAgainst.size) x else y
            input.judge?.preferred == x.key -> x
            input.judge?.preferred == y.key -> y
            else -> null
        }
        /** The best by [metric]; equal best → tie-break; still equal → null (no invented winner). */
        fun best(metric: (String) -> Double): CandidateCluster? {
            val top = eligible.maxOf { metric(it.key) }
            val tied = eligible.filter { metric(it.key) == top }.sortedBy { it.key }
            return tied.drop(1).fold(tied.firstOrNull()) { acc, c -> acc?.let { tieBreak(it, c) } }
        }
        fun gated(c: CandidateCluster?, reasonIfNull: String, overturned: Boolean = false): CouncilDecision = when {
            c == null -> result(null, reasonIfNull)
            c.criticalAgainst.isNotEmpty() -> result(null, "critical_concern")
            else -> result(c, null, overturned)
        }
        val plurality = eligible.maxWith(compareBy<CandidateCluster> { first(it.key) }.thenBy { it.evidenceScore }.thenByDescending { it.key })

        return when (protocol) {
            DecisionProtocol.SIMPLE_MAJORITY -> gated(plurality.takeIf { first(it.key) > 0.5 }, "no_majority")
            DecisionProtocol.SUPERMAJORITY -> gated(plurality.takeIf { first(it.key) >= 2.0 / 3 - 1e-9 }, "no_supermajority")
            DecisionProtocol.UNANIMITY -> gated(plurality.takeIf { ballots.isNotEmpty() && first(it.key) == 1.0 }, "no_unanimity")
            DecisionProtocol.MAJORITY_CONSENSUS -> gated(plurality.takeIf { first(it.key) > 0.5 && it.highAgainst.isEmpty() }, "no_consensus")
            DecisionProtocol.APPROVAL -> gated(best { approvals(it).toDouble() }, "tie")
            DecisionProtocol.RANKED -> gated(best { borda(it).toDouble() }, "tie")
            DecisionProtocol.CUMULATIVE -> gated(best { points(it).toDouble() }, "tie")
            DecisionProtocol.JUDGE -> gated(eligible.firstOrNull { it.key == input.judge?.preferred }, "no_judge")
            DecisionProtocol.BLIND_JUDGE_THEN_VOTE -> {
                val judged = eligible.firstOrNull { it.key == input.judge?.preferred }
                if (judged != null && judged.key == plurality.key) gated(judged, "critical_concern") else hybrid(input, eligible, byScore, scores, plurality, ::first, ::gated, ::tieBreak)
            }
            DecisionProtocol.HYBRID, DecisionProtocol.AUTO -> hybrid(input, eligible, byScore, scores, plurality, ::first, ::gated, ::tieBreak)
        }
    }

    private fun hybrid(
        input: DecisionInput, eligible: List<CandidateCluster>, byScore: List<CandidateCluster>, scores: Map<String, Double>, plurality: CandidateCluster,
        first: (String) -> Double, gated: (CandidateCluster?, String, Boolean) -> CouncilDecision, tieBreak: (CandidateCluster, CandidateCluster) -> CandidateCluster?,
    ): CouncilDecision {
        // Tool-verified evidence against the vote leader: the best-supported alternative wins (false-consensus guard).
        if (plurality.contradicted || plurality.evidenceScore < 0) {
            val alt = eligible.filter { it.key != plurality.key && it.toolVerifiedShare > 0.0 && it.criticalAgainst.isEmpty() }.maxByOrNull { it.evidenceScore }
            if (alt != null) return gated(alt, "critical_concern", true)
        }
        val leader = byScore.first()
        val clearMajority = first(leader.key) > 0.5
        val enoughEvidence = leader.evidenceScore >= input.minEvidence
        if (leader.criticalAgainst.isEmpty() && clearMajority && enoughEvidence) return gated(leader, "critical_concern", leader.key != plurality.key)
        // A critical concern on the score leader or on the vote leader blocks the round; only the final
        // decision may fall back to an unblocked, sufficiently supported alternative.
        if (leader.criticalAgainst.isNotEmpty() || plurality.criticalAgainst.isNotEmpty()) {
            if (!input.final) return gated(null, "critical_concern", false)
            val alt = byScore.firstOrNull { it.criticalAgainst.isEmpty() && it.evidenceScore >= input.minEvidence / 2 }
            return gated(alt, "critical_concern", alt != null && alt.key != plurality.key)
        }
        if (!input.final) return gated(null, if (!clearMajority) "weak_consensus" else "insufficient_evidence", false)
        // Final decision: a clear majority that nothing blocks or contradicts stands, even weakly supported (the summary
        // says the consensus is weak); evidence overturns a majority only by contradicting it (above), never by outscoring it.
        if (first(plurality.key) > 0.5 && !plurality.contradicted) return gated(plurality, "critical_concern", false)
        val second = byScore.getOrNull(1)
        if (second == null || scores[leader.key]!! - scores[second.key]!! >= 0.05) return gated(leader, "critical_concern", leader.key != plurality.key)
        val tb = tieBreak(leader, second)
        return gated(tb, "tie", tb != null && tb.key != plurality.key)
    }
}
