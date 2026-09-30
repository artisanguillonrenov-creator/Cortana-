package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.context.Tokens

/** Retention weights (doc 04 §4.6), configurable and to be calibrated. */
data class RetentionWeights(
    val novelty: Double = 0.25,
    val disagreement: Double = 0.25,
    val evidence: Double = 0.20,
    val severity: Double = 0.15,
    val minority: Double = 0.10,
    val roleRelevance: Double = 0.05,
    val redundancy: Double = 0.15,
    val tokenCost: Double = 0.10,
)

data class RetentionInput(
    val contributions: List<CouncilContribution>,
    val assessment: Assessment,
    val targets: List<CouncilAgentSlot>,
    /** Resolved topology (never AUTO here). */
    val topology: CouncilTopology,
    val config: RetentionConfig,
    val weights: RetentionWeights = RetentionWeights(),
    val hubSlotId: String? = null,
    /** Degradation overrides (doc 15 §15.11): fewer arguments per target. */
    val maxArgumentsPerTarget: Int = config.maxArgumentsPerTarget,
    val maxTokensPerTarget: Int = config.maxTokensPerTarget,
    val maxPerSource: Int = 2,
    val highDivergence: Double = 0.5,
)

/**
 * Selective retention (doc 04 §4.5–4.8, doc 15 §15.6): each agent of the next round receives only
 * the arguments that can change its mind — critical objections always, then contradictory evidence,
 * novelty, disagreement, the best evidence, a minority point — never the whole transcript.
 */
object DefaultCouncilRetainer : CouncilMessageRetainer {
    private data class Arg(val a: RetainedArgument, val sourceCluster: String?, val evidence: Double, val tokens: Set<String>)

    override fun select(input: RetentionInput): RetentionPlan {
        val a = input.assessment
        val slotCluster = a.slotCluster
        val leaderSupport = a.clusters.maxOfOrNull { it.supporters.size } ?: 0
        val minorityClusters = a.clusters.filter { it.supporters.size < leaderSupport }.map { it.key }.toSet()
        val args = mutableListOf<Arg>()
        for (c in input.contributions) {
            val cluster = slotCluster[c.slotId]
            val toolBacked = c.toolEvidence.isNotEmpty()
            c.concerns.forEach { k ->
                val contradicts = toolBacked && k.targetClaimId != null && a.claimStatuses[k.targetClaimId] == EvidenceStatus.CONTRADICTED
                val kind = when { k.severity == ConcernSeverity.CRITICAL -> ArgumentKind.CRITICAL_CONCERN; contradicts -> ArgumentKind.CONTRADICTORY_EVIDENCE; else -> ArgumentKind.CONCERN }
                val text = "Objection ${label(k.severity)}${k.targetCandidateKey?.let { " (candidat $it)" } ?: ""} : ${k.text}"
                args += arg(k.id, c, text, emptyList(), kind, k.severity, k.targetCandidateKey, if (contradicts) 1.0 else 0.3, cluster)
            }
            c.candidate?.let { cand ->
                val key = cluster ?: cand.key
                val text = "Candidat $key : ${cand.summary}${cand.expectedResult.takeIf { it.isNotBlank() }?.let { " — attendu : $it" } ?: ""}"
                args += arg("${c.slotId}-cand", c, text, emptyList(), ArgumentKind.CANDIDATE, null, key, a.cluster(key)?.evidenceScore?.coerceAtLeast(0.0) ?: 0.2, cluster)
            }
            c.claims.filter { it.type == ClaimType.FACT || it.type == ClaimType.INFERENCE }.forEach { cl ->
                val status = a.claimStatuses[cl.id] ?: EvidenceStatus.MODEL_ONLY
                val text = "Affirmation (${status.name.lowercase()}) : ${cl.text}"
                // The contradiction itself travels as the tool-backed objection (mandatory); the claim competes on evidence.
                // The argument id is the claim id: an agent of the next round can target it (targetClaim).
                args += arg(cl.id, c, text, cl.evidenceRefs, ArgumentKind.CLAIM, null, null, EvidenceLedger.weight(status).coerceAtLeast(0.0), cluster, cl.tainted)
            }
        }
        val perTarget = LinkedHashMap<String, List<RetainedArgument>>()
        var dropped = 0
        val order = input.targets.map { it.id }
        for (t in input.targets) {
            val ownCluster = slotCluster[t.id]
            val own = input.contributions.firstOrNull { it.slotId == t.id }
            val ownTokens = own?.let { CandidateNormalizer.tokens(listOfNotNull(it.candidate?.summary).plus(it.claims.map { c -> c.text }).joinToString(" ")) }.orEmpty()
            val eligible = args.filter { it.a.sourceSlotId != t.id }.filter { x ->
                x.a.kind == ArgumentKind.CRITICAL_CONCERN || when (input.topology) {
                    CouncilTopology.INDEPENDENT -> false
                    CouncilTopology.FULL_MESH, CouncilTopology.AUTO -> true
                    CouncilTopology.HUB -> t.id == input.hubSlotId || x.a.sourceSlotId == input.hubSlotId
                    CouncilTopology.RING -> order.size <= 2 || x.a.sourceSlotId in neighbours(order, t.id)
                    CouncilTopology.SPARSE_DYNAMIC -> disagreement(x, ownCluster, t.id, input.contributions) > 0 || x.evidence >= 0.8 || (x.a.severity?.weight ?: 0.0) >= ConcernSeverity.HIGH.weight
                }
            }
            fun score(x: Arg): Double {
                val novelty = 1.0 - jaccard(x.tokens, ownTokens)
                val redundancy = args.filter { it !== x && it.a.sourceSlotId != x.a.sourceSlotId && it.a.kind == x.a.kind }.maxOfOrNull { jaccard(it.tokens, x.tokens) } ?: 0.0
                val minority = if (x.sourceCluster != null && x.sourceCluster in minorityClusters && (x.evidence >= 0.8 || (x.a.severity?.weight ?: 0.0) >= ConcernSeverity.HIGH.weight)) 1.0 else if (x.sourceCluster in minorityClusters) 0.5 else 0.0
                val relevance = if (x.a.candidateKey != null && x.a.candidateKey == ownCluster) 1.0 else 0.5
                val w = input.weights
                return w.novelty * novelty + w.disagreement * disagreement(x, ownCluster, t.id, input.contributions) + w.evidence * x.evidence +
                    w.severity * (x.a.severity?.weight ?: 0.0) + w.minority * minority + w.roleRelevance * relevance -
                    w.redundancy * redundancy - w.tokenCost * (x.a.tokenEstimate.toDouble() / input.maxTokensPerTarget).coerceAtMost(1.0)
            }
            val mandatory = eligible.filter { it.a.kind == ArgumentKind.CRITICAL_CONCERN || (it.a.kind == ArgumentKind.CONTRADICTORY_EVIDENCE && it.a.severity != null) }
            val chosen = mandatory.toMutableList()
            var tokens = chosen.sumOf { it.a.tokenEstimate }
            val perSource = chosen.groupingBy { it.a.sourceSlotId }.eachCount().toMutableMap()
            val ranked = eligible.filter { it !in mandatory }.sortedWith(compareByDescending<Arg> { score(it) }.thenBy { it.a.id })
            // Minority protection: with a divided council, at least one minority point reaches every agent.
            if (input.config.minorityProtection && a.divergence >= input.highDivergence && chosen.none { it.sourceCluster in minorityClusters }) {
                ranked.firstOrNull { it.sourceCluster in minorityClusters && it.sourceCluster != ownCluster }?.let { m ->
                    if (tokens + m.a.tokenEstimate <= input.maxTokensPerTarget) { chosen += m; tokens += m.a.tokenEstimate; perSource.merge(m.a.sourceSlotId, 1, Int::plus) }
                }
            }
            for (x in ranked) {
                if (x in chosen) continue
                if (chosen.size >= input.maxArgumentsPerTarget) break
                if ((perSource[x.a.sourceSlotId] ?: 0) >= input.maxPerSource) continue
                if (tokens + x.a.tokenEstimate > input.maxTokensPerTarget) continue
                if (chosen.any { jaccard(it.tokens, x.tokens) >= 0.8 }) continue // duplicate
                chosen += x; tokens += x.a.tokenEstimate; perSource.merge(x.a.sourceSlotId, 1, Int::plus)
            }
            dropped += eligible.size - chosen.size
            perTarget[t.id] = chosen.map { it.a.copy(targetSlotIds = setOf(t.id)) }
        }
        return RetentionPlan(perTarget, dropped, minorityReport(a, minorityClusters))
    }

    private fun arg(id: String, c: CouncilContribution, text: String, refs: List<String>, kind: ArgumentKind, sev: ConcernSeverity?, candidateKey: String?, evidence: Double, cluster: String?, tainted: Boolean = c.tainted): Arg {
        val clean = ContributionParser.sanitize(text).take(900)
        return Arg(RetainedArgument(id, c.slotId, emptySet(), clean, refs, 0.0, 0.0, sev, Tokens.estimate(clean), kind, candidateKey, tainted), cluster, evidence, CandidateNormalizer.tokens(clean))
    }

    private fun disagreement(x: Arg, ownCluster: String?, target: String, contributions: List<CouncilContribution>): Double = when (x.a.kind) {
        ArgumentKind.CRITICAL_CONCERN, ArgumentKind.CONCERN -> when {
            x.a.candidateKey != null && x.a.candidateKey == ownCluster -> 1.0
            contributions.firstOrNull { it.slotId == target }?.claims?.any { cl -> x.a.summary.contains(cl.id) } == true -> 1.0
            else -> 0.5
        }
        ArgumentKind.CONTRADICTORY_EVIDENCE -> 1.0
        ArgumentKind.CANDIDATE, ArgumentKind.CLAIM, ArgumentKind.SUPPORT -> if (x.sourceCluster != null && x.sourceCluster != ownCluster) 1.0 else 0.0
    }

    private fun neighbours(order: List<String>, id: String): Set<String> {
        val i = order.indexOf(id)
        if (i < 0) return emptySet()
        return setOf(order[(i - 1 + order.size) % order.size], order[(i + 1) % order.size]) - id
    }

    private fun jaccard(x: Set<String>, y: Set<String>): Double = if (x.isEmpty() || y.isEmpty()) 0.0 else (x intersect y).size.toDouble() / (x union y).size

    private fun label(s: ConcernSeverity) = when (s) { ConcernSeverity.LOW -> "mineure"; ConcernSeverity.MEDIUM -> "moyenne"; ConcernSeverity.HIGH -> "importante"; ConcernSeverity.CRITICAL -> "CRITIQUE" }

    /** Doc 04 §4.8: a minority report when a minority is better supported or raises a high/critical risk. */
    fun minorityReport(a: Assessment, minority: Set<String>): List<String> {
        val leader = a.cluster(a.leaderKey)
        return a.clusters.filter { it.key in minority }.filter { c ->
            (leader != null && c.evidenceScore > leader.evidenceScore) || c.toolVerifiedShare > 0.0 ||
                a.contributions.filter { it.slotId in c.supporters }.any { k -> k.concerns.any { it.severity.weight >= ConcernSeverity.HIGH.weight } }
        }.map { "Minorité (${it.supporters.size}) : ${it.candidate.summary.take(240)}" }
    }
}
