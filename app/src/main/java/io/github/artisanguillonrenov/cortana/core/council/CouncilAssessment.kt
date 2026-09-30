package io.github.artisanguillonrenov.cortana.core.council

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Evidence ledger (doc 13 §13.3, doc 06 §6.7): the status of every claim, from the references the
 * agents gave and what the run actually observed. A consensus that is MODEL_ONLY never gets a high
 * confidence; content from untrusted sources carries its taint into the decision.
 */
class EvidenceLedger(
    /** Tool evidence observed during the run: ref ("tool:3") → (short text, untrusted source?). */
    private val toolEvidence: Map<String, Pair<String, Boolean>> = emptyMap(),
    /** Facts the owner gave in the brief: "user:N" references resolve to them. */
    private val userFacts: List<String> = emptyList(),
) {
    fun status(claim: Claim, contradictedIds: Set<String>): EvidenceStatus {
        if (claim.id in contradictedIds) return EvidenceStatus.CONTRADICTED
        val refs = claim.evidenceRefs.map { it.trim().lowercase() }
        return when {
            refs.any { it in toolEvidence.keys } -> EvidenceStatus.TOOL_VERIFIED
            refs.any { it == "user" || it.startsWith("user:") } && userFacts.isNotEmpty() -> EvidenceStatus.USER_PROVIDED
            refs.any { it.startsWith("stale") || it.contains("ancien") } -> EvidenceStatus.STALE
            refs.any { it.startsWith("http") } -> EvidenceStatus.UNVERIFIED // a URL a model cites is not a fetched source
            claim.type == ClaimType.ASSUMPTION -> EvidenceStatus.UNVERIFIED
            else -> EvidenceStatus.MODEL_ONLY
        }
    }

    fun tainted(claim: Claim): Boolean = claim.tainted || claim.evidenceRefs.any { toolEvidence[it.trim().lowercase()]?.second == true }

    fun describe(ref: String): String? = toolEvidence[ref.trim().lowercase()]?.first

    companion object {
        fun weight(s: EvidenceStatus): Double = when (s) {
            EvidenceStatus.TOOL_VERIFIED, EvidenceStatus.USER_PROVIDED -> 1.0
            EvidenceStatus.SUPPORTED -> 0.8
            EvidenceStatus.UNVERIFIED -> 0.3
            EvidenceStatus.MODEL_ONLY, EvidenceStatus.STALE -> 0.2
            EvidenceStatus.CONTRADICTED -> -0.5
        }
        fun importance(t: ClaimType): Double = when (t) { ClaimType.FACT -> 1.0; ClaimType.INFERENCE -> 0.7; ClaimType.RECOMMENDATION -> 0.5; ClaimType.ASSUMPTION -> 0.3 }
    }
}

data class CandidateCluster(
    val key: String,
    val candidate: Candidate,
    val supporters: List<String>,
    val models: Set<String>,
    val evidenceScore: Double,
    val toolVerifiedShare: Double,
    val contradicted: Boolean,
    val criticalAgainst: List<Concern>,
    val highAgainst: List<Concern>,
    val tainted: Boolean,
)

data class Assessment(
    val round: Int,
    val contributions: List<CouncilContribution>,
    val clusters: List<CandidateCluster>,
    val ballots: List<BallotSummary>,
    val leaderKey: String?,
    val agreement: Double,
    val divergence: Double,
    val evidenceCoverage: Double,
    /** Unresolved critical concerns: raised in the latest contribution of their author. */
    val criticalConcerns: List<Concern>,
    val stable: Boolean,
    val claimStatuses: Map<String, EvidenceStatus>,
    val tainted: Boolean,
) {
    fun cluster(key: String?) = clusters.firstOrNull { it.key == key }
    /** Cluster key of each slot's latest candidate. */
    val slotCluster: Map<String, String> by lazy { clusters.flatMap { c -> c.supporters.map { it to c.key } }.toMap() }
}

/** Normalisation, clustering, pre-vote, divergence, coverage (doc 15 §15.2, §15.7, §15.8, §15.9). */
object CouncilAssessor {
    fun assess(
        contributions: List<CouncilContribution>,
        round: Int,
        ledger: EvidenceLedger,
        previous: Assessment?,
        slotModels: Map<String, String>,
        protocol: DecisionProtocol,
    ): Assessment {
        // Canonical clusters: the first candidate of an equivalence class names it; different effects or risks never merge.
        val clusters = mutableListOf<Pair<Candidate, MutableList<CouncilContribution>>>()
        for (c in contributions) {
            val cand = c.candidate ?: continue
            val home = clusters.firstOrNull { (k, _) -> CandidateNormalizer.equivalent(k, cand) }
                ?: previous?.clusters?.firstOrNull { CandidateNormalizer.equivalent(it.candidate, cand) }?.let { prev -> (prev.candidate to mutableListOf<CouncilContribution>()).also { clusters += it } }
                ?: (cand to mutableListOf<CouncilContribution>()).also { clusters += it }
            home.second += c
        }
        val allClaims = contributions.flatMap { it.claims }
        // A concern may target a claim seen in the previous round; the same statement restated by its author
        // (same normalised wording) is still the claim under attack, whatever its new id.
        val known = (previous?.contributions.orEmpty().flatMap { it.claims } + allClaims).associateBy { it.id }
        val claimTokens = allClaims.associate { it.id to CandidateNormalizer.tokens(it.text) }
        fun hits(k: Concern): Set<String> {
            val target = k.targetClaimId?.let { known[it] } ?: return setOfNotNull(k.targetClaimId)
            val tokens = CandidateNormalizer.tokens(target.text)
            return allClaims.filter { c -> c.id == target.id || (!c.id.startsWith(k.sourceSlotId + "-") && jaccard(claimTokens[c.id].orEmpty(), tokens) >= RESTATED) }.map { it.id }.toSet()
        }
        val concerns = contributions.flatMap { it.concerns }
        val concernHits = concerns.filter { it.targetClaimId != null }.associate { it.id to hits(it) }
        // Contradictions: a concern that targets a claim, backed by tool evidence of its author, contradicts that claim.
        val contradicted = contributions.flatMap { c ->
            val backed = c.claims.any { ledger.status(it, emptySet()) == EvidenceStatus.TOOL_VERIFIED } || c.toolEvidence.isNotEmpty()
            c.concerns.filter { backed && it.targetClaimId != null && it.severity.weight >= ConcernSeverity.HIGH.weight }.flatMap { concernHits[it.id].orEmpty() }
        }.toSet()
        val statuses = allClaims.associate { it.id to ledger.status(it, contradicted) }
        // A concern is against a proposal when it names it, targets a claim of its supporters, or — critical and
        // untargeted — questions every proposal but its author's own.
        fun against(cand: Candidate, members: List<CouncilContribution>, sev: ConcernSeverity) = concerns.filter { k ->
            k.severity == sev && !k.resolved && when {
                k.targetCandidateKey != null -> k.targetCandidateKey == cand.key
                k.targetClaimId != null -> members.any { m -> m.claims.any { it.id in concernHits[k.id].orEmpty() } }
                else -> sev == ConcernSeverity.CRITICAL && members.none { it.slotId == k.sourceSlotId }
            }
        }
        val built = clusters.map { (cand, members) ->
            val memberClaims = members.flatMap { it.claims }
            val weights = memberClaims.map { EvidenceLedger.importance(it.type) }
            val evidence = if (memberClaims.isEmpty()) 0.2 else memberClaims.sumOf { EvidenceLedger.weight(statuses[it.id]!!) * EvidenceLedger.importance(it.type) } / weights.sum()
            CandidateCluster(
                key = cand.key, candidate = cand, supporters = members.map { it.slotId }, models = members.mapNotNull { slotModels[it.slotId] }.toSet(),
                evidenceScore = evidence.coerceIn(-1.0, 1.0),
                toolVerifiedShare = if (memberClaims.isEmpty()) 0.0 else memberClaims.count { statuses[it.id] == EvidenceStatus.TOOL_VERIFIED }.toDouble() / memberClaims.size,
                contradicted = memberClaims.any { statuses[it.id] == EvidenceStatus.CONTRADICTED },
                criticalAgainst = against(cand, members, ConcernSeverity.CRITICAL), highAgainst = against(cand, members, ConcernSeverity.HIGH),
                tainted = members.any { m -> m.tainted || m.claims.any { ledger.tainted(it) } },
            )
        }.sortedWith(compareByDescending<CandidateCluster> { it.supporters.size }.thenByDescending { it.evidenceScore }.thenBy { it.key })
        val keys = built.map { it.key }.toSet()
        val slotCluster = built.flatMap { c -> c.supporters.map { it to c.key } }.toMap()
        val ballots = contributions.filter { it.candidate != null || it.details[ContributionParser.RANKING] != null }.map { c -> ballot(c, slotCluster[c.slotId], keys, protocol) }
        val firsts = ballots.mapNotNull { it.ranking.firstOrNull() }
        val leader = if (firsts.isEmpty()) null else firsts.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { e -> built.first { it.key == e.key }.evidenceScore }.thenBy { it.key }).first().key
        val agreement = if (firsts.isEmpty()) 0.0 else firsts.count { it == leader }.toDouble() / firsts.size
        val critical = concerns.filter { it.severity == ConcernSeverity.CRITICAL && !it.resolved }
        val coverage = coverage(allClaims, statuses)
        val divergence = divergence(firsts, built, contributions, statuses)
        val stable = previous != null && previous.leaderKey == leader && agreement >= previous.agreement - 0.1 &&
            critical.none { k -> previous.criticalConcerns.none { it.text == k.text } } && coverage >= previous.evidenceCoverage - 1e-9
        return Assessment(round, contributions, built, ballots, leader, agreement, divergence, coverage, critical, stable, statuses,
            built.any { it.tainted } || contributions.any { it.tainted })
    }

    private fun ballot(c: CouncilContribution, own: String?, keys: Set<String>, protocol: DecisionProtocol): BallotSummary {
        val stated = c.details[ContributionParser.RANKING].orEmpty().filter { it in keys }.distinct()
        val ranking = (listOfNotNull(own) + stated).distinct().ifEmpty { stated }
        val objected = c.concerns.filter { it.severity.weight >= ConcernSeverity.HIGH.weight }.mapNotNull { it.targetCandidateKey }.toSet()
        val approvals = ranking.filter { it !in objected }
        val shares = listOf(6, 3, 1)
        val points = ranking.take(3).mapIndexed { i, k -> k to shares[i] }.toMap()
        return BallotSummary(c.slotId, protocol, ranking, approvals, points)
    }

    /** Two claims with this token overlap are the same statement. */
    private const val RESTATED = 0.8

    private fun jaccard(x: Set<String>, y: Set<String>): Double = if (x.isEmpty() || y.isEmpty()) 0.0 else (x intersect y).size.toDouble() / (x union y).size

    fun coverage(claims: List<Claim>, statuses: Map<String, EvidenceStatus>): Double {
        val total = claims.sumOf { EvidenceLedger.importance(it.type) }
        if (total == 0.0) return 0.0
        return claims.filter { statuses[it.id]?.supports == true }.sumOf { EvidenceLedger.importance(it.type) } / total
    }

    /** .30 vote entropy + .20 semantic spread + .20 evidence conflict + .15 concern disagreement + .15 confidence spread. */
    fun divergence(firsts: List<String>, clusters: List<CandidateCluster>, contributions: List<CouncilContribution>, statuses: Map<String, EvidenceStatus>): Double {
        if (contributions.size <= 1) return 0.0
        val counts = firsts.groupingBy { it }.eachCount().values
        val n = firsts.size.toDouble()
        val entropy = if (n <= 1 || counts.size <= 1) 0.0 else -counts.sumOf { val p = it / n; p * ln(p) } / ln(minOf(n, contributions.size.toDouble()))
        val targets = clusters.map { it.candidate.target.split(' ').filter { t -> t.isNotBlank() }.toSet() }
        val spread = if (targets.size <= 1) 0.0 else {
            val pairs = targets.indices.flatMap { i -> (i + 1 until targets.size).map { j -> i to j } }
            pairs.map { (i, j) -> val u = targets[i] union targets[j]; if (u.isEmpty()) 1.0 else 1.0 - (targets[i] intersect targets[j]).size.toDouble() / u.size }.average()
        }
        val conflict = if (statuses.isEmpty()) 0.0 else statuses.values.count { it == EvidenceStatus.CONTRADICTED }.toDouble() / statuses.size
        val worried = contributions.count { c -> c.concerns.any { it.severity.weight >= ConcernSeverity.HIGH.weight } }.toDouble() / contributions.size
        val concernDisagreement = 1.0 - kotlin.math.abs(2 * worried - 1.0) // highest when half the council worries
        val conf = contributions.mapNotNull { it.confidence.selfReported }
        val confSpread = if (conf.size <= 1) 0.0 else { val m = conf.average(); (sqrt(conf.sumOf { (it - m) * (it - m) } / conf.size) / 0.5).coerceAtMost(1.0) }
        return (0.30 * entropy + 0.20 * spread + 0.20 * conflict + 0.15 * concernDisagreement + 0.15 * confSpread).coerceIn(0.0, 1.0)
    }
}
