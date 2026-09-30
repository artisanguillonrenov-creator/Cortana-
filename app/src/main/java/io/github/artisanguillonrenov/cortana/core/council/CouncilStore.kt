package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.memory.CouncilAgentSlotEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilClaimEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilConcernEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilContributionEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilDao
import io.github.artisanguillonrenov.cortana.core.memory.CouncilDecisionEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilEvidenceRefEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilRoundEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilRunEntity
import io.github.artisanguillonrenov.cortana.core.memory.CouncilVoteEntity
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.ZoneId

/** Council metrics (doc 06 §6.10), computed from the stored runs. */
data class CouncilMetrics(
    val runsTotal: Int,
    val partialRate: Double,
    val avgAgents: Double,
    val avgRounds: Double,
    val avgTokens: Double,
    /** Null when no run had a known price. */
    val avgCostMicros: Double?,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val earlyStopRate: Double,
    val fallbackRate: Double,
    val verifierRejectionRate: Double,
    val minorityOverturnRate: Double,
    val reductions413: Int,
    val rateLimitEvents: Int,
    val byStatus: Map<String, Int>,
) {
    fun text(): String = if (runsTotal == 0) "- Conseil de réflexion : aucun conseil" else buildString {
        fun pct(x: Double) = "${(x * 100).toInt()} %"
        append("- Conseil de réflexion : $runsTotal conseil(s) (${byStatus.entries.joinToString { "${it.value} ${it.key}" }}), partiels ${pct(partialRate)}, ")
        append("${"%.1f".format(java.util.Locale.ROOT, avgAgents)} spécialistes et ${"%.1f".format(java.util.Locale.ROOT, avgRounds)} tours en moyenne, ${avgTokens.toInt()} jetons en moyenne")
        avgCostMicros?.let { append(", coût moyen ${"%.4f".format(java.util.Locale.ROOT, it / 1_000_000)} $") }
        append(", médiane ${p50LatencyMs / 1000} s, p95 ${p95LatencyMs / 1000} s ; arrêt anticipé ${pct(earlyStopRate)}, replis ${pct(fallbackRate)}, ")
        append("rejets du vérificateur ${pct(verifierRejectionRate)}, minorité victorieuse ${pct(minorityOverturnRate)}, 413 compactés $reductions413, 429 $rateLimitEvents")
    }
}

/**
 * The council's recorder on the canonical database (doc 06 §6.1, C11): structured rows only — never
 * a prompt, a raw output, private reasoning or a secret. A persistence failure never breaks a
 * council (logged, the run goes on); cancellation still propagates.
 */
class CouncilStore(private val dao: CouncilDao, private val clock: () -> Long = System::currentTimeMillis) : CouncilRecorder {

    private suspend fun safe(what: String, block: suspend () -> Unit) {
        try { block() } catch (c: CancellationException) { throw c } catch (t: Throwable) { CLog.w("council store: $what", t) }
    }

    override suspend fun runCreated(runId: String, request: CouncilRunRequest, configSnapshot: String) = safe("run") {
        dao.insertRun(CouncilRunEntity(runId, request.parentTaskId, request.mode.name.lowercase(), request.presetId, CouncilRunStatus.CREATED.name.lowercase(), clock(),
            configSnapshotJson = Redactor.redact(configSnapshot)))
    }

    override suspend fun status(runId: String, status: CouncilRunStatus) = safe("status") { dao.setStatus(runId, status.name.lowercase()) }

    override suspend fun planCreated(plan: CouncilPlan) = safe("plan") {
        dao.setPreset(plan.runId, plan.presetId)
        dao.insertSlots(plan.slots.map { s ->
            val route = (listOfNotNull(s.route) + s.fallbacks).joinToString(" → ") { "${it.providerName.ifBlank { it.providerId }} · ${it.modelId}" }
            CouncilAgentSlotEntity(s.id, plan.runId, s.profileId, route.ifEmpty { "-" }, Hash.sha256(s.toolScope.sorted().joinToString(",")).take(16), s.required, SlotStatus.PENDING.name.lowercase())
        })
    }

    override suspend fun roundStarted(runId: String, round: Int, type: String): String {
        val id = "$runId-r$round-$type"
        safe("round") { dao.insertRound(CouncilRoundEntity(id, runId, round, type, startedAt = clock())) }
        return id
    }

    override suspend fun roundEnded(roundId: String, assessment: Assessment) = safe("round end") {
        dao.endRound(roundId, clock(), assessment.divergence, assessment.agreement, assessment.evidenceCoverage)
        assessment.claimStatuses.forEach { (claimId, st) -> dao.setClaimStatus("$roundId:$claimId", st.name.lowercase()) }
    }

    override suspend fun contribution(roundId: String, result: AgentTurnResult, statuses: Map<String, EvidenceStatus>, evidence: Map<String, Pair<String, Boolean>>) = safe("contribution") {
        val id = "$roundId:${result.slotId}"
        val c = result.contribution
        dao.insertContribution(CouncilContributionEntity(
            id, roundId, result.slotId, c?.candidate?.key, c?.let(::structured)?.toString() ?: "{}",
            c?.let { AppJson.encodeToString(ConfidenceFeatures.serializer(), it.confidence) } ?: "{}",
            result.inputTokens, result.outputTokens, result.latencyMs,
            result.errorCode ?: result.status.takeIf { it != SlotStatus.SUCCEEDED }?.name?.lowercase(),
        ))
        if (c == null) return@safe
        dao.insertClaims(c.claims.map { cl ->
            CouncilClaimEntity("$roundId:${cl.id}", id, cl.text.take(600), cl.type.name.lowercase(), cl.confidence, cl.tainted, (statuses[cl.id] ?: EvidenceStatus.UNVERIFIED).name.lowercase())
        })
        dao.insertEvidence(c.claims.flatMap { cl ->
            cl.evidenceRefs.mapIndexed { i, ref ->
                val r = ref.trim().lowercase()
                val type = when { r.startsWith("tool:") -> "tool"; r == "user" || r.startsWith("user:") -> "user"; r.startsWith("http") -> "url"; else -> "other" }
                val trust = when (type) { "tool" -> evidence[r]?.let { if (it.second) "untrusted" else "trusted" } ?: "unverified"; "user" -> "trusted"; else -> "unverified" }
                CouncilEvidenceRefEntity("$roundId:${cl.id}:$i", "$roundId:${cl.id}", type, Redactor.redact(ref).take(200), null, trust, clock())
            }
        })
        dao.insertConcerns(c.concerns.map { k ->
            CouncilConcernEntity("$roundId:${k.id}", id, k.severity.name.lowercase(), k.targetClaimId, k.text.take(1_000), k.resolved, null)
        })
    }

    override suspend fun slotStatus(slotId: String, status: SlotStatus) = safe("slot") { dao.setSlotStatus(slotId, status.name.lowercase()) }

    override suspend fun votes(roundId: String, ballots: List<BallotSummary>) = safe("votes") {
        dao.insertVotes(ballots.map { b -> CouncilVoteEntity("$roundId:${b.slotId}", roundId, b.slotId, b.protocol.name.lowercase(), AppJson.encodeToString(BallotSummary.serializer(), b)) })
    }

    override suspend fun decision(runId: String, roundId: String?, decision: CouncilDecision) = safe("decision") {
        val metrics = buildJsonObject {
            put("agreement", decision.agreementScore); put("divergence", decision.divergenceScore); put("evidenceCoverage", decision.evidenceCoverageScore)
            decision.unresolvedReason?.let { put("unresolvedReason", it) }
            put("judgeUsed", decision.judgeUsed); put("overturnedMajority", decision.overturnedMajority)
            putJsonObject("scores") { decision.scores.forEach { (k, v) -> put(k, v) } }
        }
        dao.insertDecision(CouncilDecisionEntity(
            "$runId-decision", runId, roundId, decision.protocol.name.lowercase(), decision.selectedCandidateKey, metrics.toString(),
            AppJson.encodeToString(ListSerializer(String.serializer()), decision.minorityReport.map { Redactor.redact(it) }),
            buildJsonObject { putJsonArray("concerns") { decision.unresolvedCriticalConcerns.forEach { k -> addJsonObject { put("severity", k.severity.name.lowercase()); put("text", k.text.take(500)) } } } }.toString(),
        ))
    }

    override suspend fun finished(runId: String, result: CouncilResult, termination: String, metrics: CouncilRunMetrics) = safe("finish") {
        dao.finish(runId, result.status.name.lowercase(), clock(), Redactor.redact(termination).take(300), result.usage.totalTokens, result.usage.costMicros, result.usage.wallTimeMs,
            AppJson.encodeToString(CouncilSummary.serializer(), result.summary), AppJson.encodeToString(CouncilRunMetrics.serializer(), metrics))
        dao.closeSlots(runId, if (result.status == CouncilRunStatus.CANCELLED) SlotStatus.CANCELLED.name.lowercase() else SlotStatus.SKIPPED.name.lowercase())
    }

    /**
     * Restart boundary (doc 08 §8.8): a council alive when the process died is not resumed — it had
     * no side effect to replay; its task follows the orchestrator's own recovery. Returns the runs closed.
     */
    suspend fun recoverInterrupted(): Int {
        val open = dao.unfinished()
        open.forEach { r ->
            dao.finish(r.id, CouncilRunStatus.FAILED.name.lowercase(), clock(), "interrompu : processus arrêté (aucune reprise, aucun effet à rejouer)", r.totalTokens, r.totalCostMicros,
                r.wallTimeMs, r.summaryJson ?: "{}", r.metricsJson ?: "{}")
            dao.closeSlots(r.id, SlotStatus.CANCELLED.name.lowercase())
        }
        return open.size
    }

    /** Tokens the council used since local midnight (daily cap, doc 07 §7.5). */
    suspend fun tokensToday(): Int = dao.tokensSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

    suspend fun metrics(since: Long): CouncilMetrics = aggregate(dao.runsSince(since))

    /** A redacted diagnostic of one run (doc 06 §6.9): its rows, no prompt, no raw output, no reasoning. */
    suspend fun diagnostics(runId: String): String? {
        val run = dao.run(runId) ?: return null
        val slots = dao.slots(runId); val rounds = dao.rounds(runId); val contributions = dao.contributions(runId); val claims = dao.claims(runId)
        val evidence = dao.evidence(runId); val concerns = dao.concerns(runId); val votes = dao.votes(runId); val decisions = dao.decisions(runId)
        val json = buildJsonObject {
            put("run", AppJson.encodeToJsonElement(CouncilRunEntity.serializer(), run))
            put("slots", AppJson.encodeToJsonElement(ListSerializer(CouncilAgentSlotEntity.serializer()), slots))
            put("rounds", AppJson.encodeToJsonElement(ListSerializer(CouncilRoundEntity.serializer()), rounds))
            put("contributions", AppJson.encodeToJsonElement(ListSerializer(CouncilContributionEntity.serializer()), contributions))
            put("claims", AppJson.encodeToJsonElement(ListSerializer(CouncilClaimEntity.serializer()), claims))
            put("evidence", AppJson.encodeToJsonElement(ListSerializer(CouncilEvidenceRefEntity.serializer()), evidence))
            put("concerns", AppJson.encodeToJsonElement(ListSerializer(CouncilConcernEntity.serializer()), concerns))
            put("votes", AppJson.encodeToJsonElement(ListSerializer(CouncilVoteEntity.serializer()), votes))
            put("decisions", AppJson.encodeToJsonElement(ListSerializer(CouncilDecisionEntity.serializer()), decisions))
        }
        return Redactor.redact(json.toString())
    }

    companion object {
        /** The stored form of a contribution: what it proposes and why, briefly — never a reasoning trace. */
        fun structured(c: CouncilContribution): JsonObject = buildJsonObject {
            c.candidate?.let { cand ->
                putJsonObject("candidate") {
                    put("summary", cand.summary); put("action", cand.action); put("target", cand.target)
                    putJsonArray("parameters") { cand.parameters.forEach { add(it) } }
                    put("expectedResult", cand.expectedResult)
                    putJsonArray("sideEffects") { cand.sideEffects.forEach { add(it) } }
                    put("risk", cand.risk)
                }
            }
            putJsonArray("assumptions") { c.assumptions.forEach { add(it) } }
            putJsonArray("requestedChecks") { c.requestedChecks.forEach { add(it.text) } }
            put("shortRationale", c.rationaleSummary.take(300))
            putJsonArray("ranking") { c.details[ContributionParser.RANKING].orEmpty().forEach { add(it) } }
            putJsonArray("challenged") { c.challengedCandidateKeys.forEach { add(it) } }
            put("tainted", c.tainted)
        }

        fun aggregate(runs: List<CouncilRunEntity>): CouncilMetrics {
            val done = runs.filter { it.completedAt != null }
            val metrics = done.mapNotNull { r -> r.metricsJson?.let { j -> runCatching { AppJson.decodeFromString(CouncilRunMetrics.serializer(), j) }.getOrNull() } }
            fun rate(n: Int) = if (done.isEmpty()) 0.0 else n.toDouble() / done.size
            fun mrate(p: (CouncilRunMetrics) -> Boolean) = if (metrics.isEmpty()) 0.0 else metrics.count(p).toDouble() / metrics.size
            val latencies = done.map { it.wallTimeMs }.sorted()
            fun pct(p: Double) = if (latencies.isEmpty()) 0L else latencies[((latencies.size - 1) * p).toInt()]
            val costs = done.mapNotNull { it.totalCostMicros }
            return CouncilMetrics(
                runsTotal = runs.size, partialRate = rate(done.count { it.status == "partial" }),
                avgAgents = if (metrics.isEmpty()) 0.0 else metrics.map { it.agents }.average(),
                avgRounds = if (metrics.isEmpty()) 0.0 else metrics.map { it.rounds }.average(),
                avgTokens = if (done.isEmpty()) 0.0 else done.map { it.totalTokens }.average(),
                avgCostMicros = costs.takeIf { it.isNotEmpty() }?.average(),
                p50LatencyMs = pct(0.5), p95LatencyMs = pct(0.95),
                earlyStopRate = mrate { it.earlyStop }, fallbackRate = mrate { it.fallbacks > 0 }, verifierRejectionRate = mrate { it.verifierRejected },
                minorityOverturnRate = mrate { it.minorityOverturn }, reductions413 = metrics.sumOf { it.reductions413 }, rateLimitEvents = metrics.sumOf { it.rateLimited },
                byStatus = runs.groupingBy { it.status }.eachCount(),
            )
        }
    }
}
