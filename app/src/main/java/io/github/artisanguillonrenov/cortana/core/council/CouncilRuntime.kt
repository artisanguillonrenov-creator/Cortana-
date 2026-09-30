package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.contracts.VerificationStatus
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.model.ChatMessage
import io.github.artisanguillonrenov.cortana.core.observability.Tracer
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.tools.SchemaValidator
import io.github.artisanguillonrenov.cortana.core.verifier.Verifier
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Marks coroutines running inside a council: a nested council is refused (doc 08 §8.14). */
class CouncilMarker(val runId: String) : AbstractCoroutineContextElement(CouncilMarker) {
    companion object Key : CoroutineContext.Key<CouncilMarker>
}

/** Persistence hooks (C11). Only structured summaries: never prompts, raw outputs or reasoning. */
interface CouncilRecorder {
    suspend fun runCreated(runId: String, request: CouncilRunRequest, configSnapshot: String) {}
    suspend fun status(runId: String, status: CouncilRunStatus) {}
    suspend fun planCreated(plan: CouncilPlan) {}
    suspend fun roundStarted(runId: String, round: Int, type: String): String = "$runId-r$round-$type"
    suspend fun roundEnded(roundId: String, assessment: Assessment) {}
    suspend fun contribution(roundId: String, result: AgentTurnResult, statuses: Map<String, EvidenceStatus>, evidence: Map<String, Pair<String, Boolean>>) {}
    suspend fun slotStatus(slotId: String, status: SlotStatus) {}
    suspend fun votes(roundId: String, ballots: List<BallotSummary>) {}
    suspend fun decision(runId: String, roundId: String?, decision: CouncilDecision) {}
    suspend fun finished(runId: String, result: CouncilResult, termination: String, metrics: CouncilRunMetrics) {}
}

object NoCouncilRecorder : CouncilRecorder

/** Per-run metrics feeding the council metrics (doc 06 §6.10). */
@kotlinx.serialization.Serializable
data class CouncilRunMetrics(
    val agents: Int, val validAgents: Int, val rounds: Int, val earlyStop: Boolean, val fallbacks: Int, val verifierRejected: Boolean,
    val minorityOverturn: Boolean, val reductions413: Int, val rateLimited: Int, val retainedArguments: Int, val droppedArguments: Int, val judgeUsed: Boolean,
)

/**
 * CouncilRuntime — the Cognitive Council Engine (doc 15 §15.1). A sub-operation of the task: the
 * orchestrator delegates, the runtime returns a [CouncilResult]; it never transitions the task,
 * never acts, never writes memory. Structured concurrency: STOP (task cancellation) cancels every
 * agent, discards late results and marks the run CANCELLED.
 */
class CouncilRuntime(
    private val planner: CouncilPlanner,
    private val profiles: DefaultCouncilProfileRegistry,
    private val caller: CouncilAgentCaller,
    private val verifier: Verifier,
    private val tracer: Tracer,
    private val settings: SettingsRepository,
    private val audit: AuditLog? = null,
    private val recorder: CouncilRecorder = NoCouncilRecorder,
    private val decisionEngine: CouncilDecisionEngine = DefaultDecisionEngine,
    private val retainer: CouncilMessageRetainer = DefaultCouncilRetainer,
) : CognitiveCouncilEngine {
    private val jobs = ConcurrentHashMap<String, Job>()

    override suspend fun cancel(runId: String) { jobs[runId]?.cancel(CancellationException("council.cancel")) }

    override suspend fun run(request: CouncilRunRequest, onEvent: (CouncilEvent) -> Unit): CouncilResult {
        val runId = Ids.new()
        if (coroutineContext[CouncilMarker] != null) return failed(runId, CouncilRunStatus.FAILED, "Conseil imbriqué refusé", CouncilUsage())
        if (request.mode == CouncilMode.OFF) return failed(runId, CouncilRunStatus.FAILED, "Conseil désactivé", CouncilUsage())
        return withContext(CouncilMarker(runId)) {
            coroutineScope {
                jobs[runId] = coroutineContext.job
                try { execute(runId, request, onEvent) } finally { jobs.remove(runId) }
            }
        }
    }

    private class RunState(val runId: String) {
        var status = CouncilRunStatus.CREATED
        var partial = false
        val notices = mutableListOf<String>()
        var retained = 0
        var dropped = 0
        var earlyStop = false
        var verifierRejected = false
        var rounds = 0
    }

    private suspend fun execute(runId: String, request: CouncilRunRequest, onEvent: (CouncilEvent) -> Unit): CouncilResult {
        val s = settings.current
        val config = s.council
        val prefs = s.councilPrefs
        val st = RunState(runId)
        val started = System.currentTimeMillis()
        suspend fun move(to: CouncilRunStatus) {
            check(CouncilRunStatus.allowed(st.status, to)) { "transition du conseil interdite : ${st.status} → $to" }
            st.status = to
            recorder.status(runId, to)
            onEvent(CouncilEvent.Phase(runId, to))
        }
        recorder.runCreated(runId, request, Redactor.redact(AppJson.encodeToString(CouncilConfig.serializer(), config)))
        onEvent(CouncilEvent.RunCreated(runId, request.mode))
        audit?.record("cortana", "council.run", runId, "created", """{"mode":"${request.mode.name}","task":"${request.parentTaskId}"}""")
        var env: CouncilRunEnv? = null
        var plan: CouncilPlan? = null
        return try {
            tracer.span("council.run", request.parentTaskId, mapOf("council.run" to runId, "council.mode" to request.mode.name)) { runSpan ->
                move(CouncilRunStatus.PLANNING)
                val p = try {
                    tracer.span("council.plan", request.parentTaskId, mapOf("council.run" to runId)) { planner.plan(runId, request, config, prefs) }
                } catch (e: CouncilPlanException) {
                    return@span finish(st, runId, request, null, null, null, "", CouncilRunStatus.FAILED, e.message ?: "plan", started, onEvent)
                }
                plan = p
                runSpan.attr("council.preset", p.presetId); runSpan.attr("council.agents", p.slots.size.toString())
                st.notices += p.degradations
                p.slots.mapNotNull { it.routeNote }.forEach { st.notices += "Modèle incompatible ou refusé : repli appliqué ($it)" }
                recorder.planCreated(p)
                onEvent(CouncilEvent.PlanCreated(runId, p.slots.map { it.id to it.profileId }, p.maxRounds))
                move(CouncilRunStatus.RESOLVING_MODELS)
                move(CouncilRunStatus.PREPARING_CONTEXT)
                val guard = RunBudgetGuard(p.budget, request.remainingModelCalls, request.remainingToolCalls)
                guard.reserveForFinal = SYNTHESIS_TOKENS + VERIFY_TOKENS
                val e = CouncilRunEnv(runId, request.parentTaskId, request.sessionId, request.toolset, request.privacy.incognito, request.taskBrief.userGoal, guard,
                    request.maxTaskToolCalls, request.tainted, request.taintSources)
                env = e
                withTimeout(p.budget.maxWallTimeMs) { deliberate(st, p, request, e, onEvent, started, ::move) }
            }
        } catch (t: TimeoutCancellationException) {
            withContext(NonCancellable) { finish(st, runId, request, plan, env, null, "", CouncilRunStatus.TIMED_OUT, "Délai du conseil atteint", started, onEvent) }
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                if (!st.status.terminal) { st.status = CouncilRunStatus.CANCELLED; recorder.status(runId, CouncilRunStatus.CANCELLED) }
                onEvent(CouncilEvent.RunCancelled(runId))
                audit?.record("cortana", "council.run", runId, "cancelled", "{}")
                recorder.finished(runId, result(runId, CouncilRunStatus.CANCELLED, "", null, null, usage(env, started), summary(st, runId, CouncilRunStatus.CANCELLED, null, null, plan, env, started)), "cancelled", metrics(st, plan, null, env, null))
            }
            throw c
        }
    }

    private suspend fun deliberate(st: RunState, p: CouncilPlan, req: CouncilRunRequest, env: CouncilRunEnv, onEvent: (CouncilEvent) -> Unit, started: Long,
                                   move: suspend (CouncilRunStatus) -> Unit): CouncilResult {
        val runId = p.runId
        val pool = planner.readOnlyPool(req.toolPool)
        val models = p.slots.associate { it.id to (it.route?.let { r -> "${r.providerName}/${r.modelId}" } ?: "?") }
        val latest = LinkedHashMap<String, CouncilContribution>()
        val preset = profiles.preset(p.presetId)
        val minEvidence = preset?.minEvidenceCoverage ?: 0.3

        // ---- round 0: independent, parallel (doc 01 §1.8)
        move(CouncilRunStatus.ROUND_INITIAL)
        val r0 = recorder.roundStarted(runId, 0, "initial")
        val first = tracer.span("council.round", req.parentTaskId, mapOf("council.run" to runId, "council.round" to "0")) {
            parallel(p, env, onEvent, r0, p.slots.map { slot ->
                val profile = profiles.get(slot.profileId)!!
                val tools = pool.filter { it.capability in slot.toolScope }
                AgentTurn(slot, profile, 0, false, { level -> CouncilPrompts.initial(profile, req.taskBrief, tools.isNotEmpty(), level) }, tools, pool)
            })
        }
        first.filter { it.status == SlotStatus.SUCCEEDED }.forEach { latest[it.slotId] = it.contribution!! }
        if (latest.isEmpty()) {
            val budget = first.any { it.status == SlotStatus.BUDGET }
            return finish(st, runId, req, p, env, null, "", if (budget) CouncilRunStatus.BUDGET_EXHAUSTED else CouncilRunStatus.FAILED,
                if (budget) "Budget épuisé avant le premier avis" else "Aucun spécialiste n'a répondu", started, onEvent)
        }
        val failedCount = p.slots.size - latest.size
        if (failedCount > 0) st.notices += "$failedCount spécialiste(s) indisponible(s), réponse produite avec ${latest.size}/${p.slots.size}."
        if (latest.size < p.quorum) { st.partial = true; st.notices += "Quorum non atteint (${latest.size}/${p.quorum}) : conseil partiel." }
        move(CouncilRunStatus.ASSESSING)
        var assessment = assess(latest, 0, null, env, models, p.decisionProtocol, req)
        recorder.roundEnded(r0, assessment)
        tracer.span("council.vote", req.parentTaskId, mapOf("council.run" to runId, "council.round" to "0", "council.ballots" to assessment.ballots.size.toString())) { recorder.votes(r0, assessment.ballots) }
        onEvent(CouncilEvent.RoundCompleted(runId, 0, assessment.agreement, assessment.divergence))
        var previous: Assessment? = null
        var round = 0
        var maxArgs = p.retention.maxArgumentsPerTarget
        var maxArgTokens = p.retention.maxTokensPerTarget

        // ---- critique rounds (doc 01 §1.10–1.11, doc 15 §15.3)
        while (!st.partial && p.retention.enabled && shouldContinue(st, p, assessment, round, env, latest.size, minEvidence)) {
            round++
            move(CouncilRunStatus.ROUND_CRITIQUE)
            val rid = recorder.roundStarted(runId, round, "critique")
            val active = p.slots.filter { it.id in latest }
            val topology = resolveTopology(p.topology, assessment.divergence)
            val retention = tracer.span("council.retain", req.parentTaskId, mapOf("council.run" to runId, "council.round" to round.toString(), "council.topology" to topology.name)) { span ->
                retainer.select(RetentionInput(latest.values.toList(), assessment, active, topology, p.retention, hubSlotId = active.firstOrNull { it.profileId == "strategist" }?.id ?: active.first().id,
                    maxArgumentsPerTarget = maxArgs, maxTokensPerTarget = maxArgTokens)).also { span.attr("council.retained", it.perTarget.values.sumOf { l -> l.size }.toString()); span.attr("council.dropped", it.dropped.toString()) }
            }
            st.retained += retention.perTarget.values.sumOf { it.size }; st.dropped += retention.dropped
            onEvent(CouncilEvent.ArgumentsRetained(runId, round, retention.perTarget.values.sumOf { it.size }, retention.dropped))
            val candidates = assessment.clusters.map { it.key to it.candidate.summary }
            val provisional = assessment.leaderKey?.takeIf { assessment.agreement >= 0.5 }
            val unresolved = assessment.criticalConcerns
            val snapshot = assessment
            val results = tracer.span("council.round", req.parentTaskId, mapOf("council.run" to runId, "council.round" to round.toString())) {
                parallel(p, env, onEvent, rid, active.map { slot ->
                    val profile = profiles.get(slot.profileId)!!
                    val tools = pool.filter { it.capability in slot.toolScope }
                    val own = latest[slot.id]
                    val args = retention.perTarget[slot.id].orEmpty()
                    AgentTurn(slot, profile, round, true, { level -> CouncilPrompts.critique(profile, req.taskBrief, own, candidates, provisional, args, unresolved, tools.isNotEmpty(), level) }, tools, pool)
                })
            }
            results.filter { it.status == SlotStatus.SUCCEEDED }.forEach { latest[it.slotId] = it.contribution!! }
            move(CouncilRunStatus.ASSESSING)
            previous = snapshot
            assessment = assess(latest, round, previous, env, models, p.decisionProtocol, req)
            recorder.roundEnded(rid, assessment)
            tracer.span("council.vote", req.parentTaskId, mapOf("council.run" to runId, "council.round" to round.toString(), "council.ballots" to assessment.ballots.size.toString())) { recorder.votes(rid, assessment.ballots) }
            onEvent(CouncilEvent.RoundCompleted(runId, round, assessment.agreement, assessment.divergence))
            // Degradation between rounds (doc 15 §15.10–15.11): fewer arguments if the projection no longer fits.
            if (!env.guard.canContinue(projectRound(p, latest.size, maxArgTokens))) { maxArgs = (maxArgs * 3 / 4).coerceAtLeast(2); maxArgTokens = maxArgTokens * 3 / 4 }
        }
        st.rounds = round

        // ---- decision, optional judge (doc 04 §4.2–4.3)
        move(CouncilRunStatus.DECIDING)
        var judge: JudgeVerdict? = null
        val judgeFirst = p.decisionProtocol == DecisionProtocol.JUDGE || p.decisionProtocol == DecisionProtocol.BLIND_JUDGE_THEN_VOTE
        if (p.judge && judgeFirst && assessment.clusters.size > 1) judge = runJudge(p, req, assessment, env)
        fun decide(a: Assessment, prev: Assessment?, final: Boolean) = decisionEngine.decide(DecisionInput(p.decisionProtocol, a, prev, emptySet(), judge, minEvidence, final,
            preset?.evidenceWeighted == true, a.contributions.mapNotNull { models[it.slotId] }.toSet().size))
        var decision = tracer.span("council.decision", req.parentTaskId, mapOf("council.run" to runId)) { decide(assessment, previous, false) }
        if (decision.selectedCandidateKey == null && p.judge && judge == null && assessment.clusters.size > 1) {
            judge = runJudge(p, req, assessment, env)
            decision = decide(assessment, previous, false)
        }
        if (decision.selectedCandidateKey == null) decision = decide(assessment, previous, true)
        onEvent(CouncilEvent.DecisionProposed(runId, decision.selectedCandidateKey))

        // ---- final challenge and mini repair round (doc 01 §1.12)
        val challengerSlot = p.slots.firstOrNull { it.id in latest && (it.profileId.contains("challenger") || it.profileId == "skeptic") }
        if (p.challengeFinal && decision.selectedCandidateKey != null && challengerSlot != null && !st.partial) {
            move(CouncilRunStatus.CHALLENGING)
            val selected = assessment.cluster(decision.selectedCandidateKey)!!
            val profile = profiles.get(challengerSlot.profileId)!!
            val claims = assessment.contributions.filter { it.slotId in selected.supporters }.flatMap { it.claims }.map { "${it.text.take(200)} [${(assessment.claimStatuses[it.id] ?: EvidenceStatus.MODEL_ONLY).name.lowercase()}]" }
            val challengeRound = recorder.roundStarted(runId, round + 1, "challenge")
            val res = tracer.span("council.challenge", req.parentTaskId, mapOf("council.run" to runId)) {
                parallel(p, env, onEvent, challengeRound, listOf(AgentTurn(challengerSlot, profile, round + 1, false,
                    { _ -> CouncilPrompts.challenge(profile, req.taskBrief, selected.candidate, claims, (selected.criticalAgainst + selected.highAgainst).map { it.text }, env.toolEvidence.values.map { it.first }) },
                    emptyList(), emptyList()))).single()
            }
            val critical = res.contribution?.concerns.orEmpty().filter { it.severity == ConcernSeverity.CRITICAL && (it.targetCandidateKey == null || it.targetCandidateKey == selected.key) }
                .map { it.copy(targetCandidateKey = selected.key) }
            recorder.roundEnded(challengeRound, assessment)
            onEvent(CouncilEvent.DecisionChallenged(runId, critical.isNotEmpty()))
            if (critical.isNotEmpty()) {
                latest[challengerSlot.id] = res.contribution!!.copy(concerns = res.contribution.concerns.map { k -> critical.firstOrNull { it.id == k.id } ?: k })
                val repairRoundFits = env.guard.canContinue(projectRound(p, latest.size, maxArgTokens))
                if (repairRoundFits) {
                    round++
                    move(CouncilRunStatus.ROUND_CRITIQUE)
                    val rid = recorder.roundStarted(runId, round, "repair")
                    val challenged = assess(latest, round, assessment, env, models, p.decisionProtocol, req)
                    val active = p.slots.filter { it.id in latest }
                    val retention = retainer.select(RetentionInput(latest.values.toList(), challenged, active, CouncilTopology.FULL_MESH, p.retention, maxArgumentsPerTarget = 4, maxTokensPerTarget = maxArgTokens))
                    val candidates = challenged.clusters.map { it.key to it.candidate.summary }
                    val repaired = parallel(p, env, onEvent, rid, active.map { slot ->
                        val prof = profiles.get(slot.profileId)!!
                        val own = latest[slot.id]
                        val args = retention.perTarget[slot.id].orEmpty()
                        AgentTurn(slot, prof, round, true, { level -> CouncilPrompts.critique(prof, req.taskBrief, own, candidates, selected.key, args, critical, false, level) }, emptyList(), emptyList())
                    })
                    repaired.filter { it.status == SlotStatus.SUCCEEDED }.forEach { latest[it.slotId] = it.contribution!! }
                    move(CouncilRunStatus.ASSESSING)
                    previous = challenged
                    assessment = assess(latest, round, challenged, env, models, p.decisionProtocol, req)
                    recorder.roundEnded(rid, assessment)
                    st.rounds = round
                } else {
                    st.notices += "Objection critique du défi final, sans budget pour un tour de réparation."
                    previous = assessment
                    assessment = assess(latest, round, assessment, env, models, p.decisionProtocol, req)
                }
                move(CouncilRunStatus.DECIDING)
                decision = decide(assessment, previous, true)
            }
        }
        recorder.decision(runId, null, decision)

        // ---- synthesis and verification (doc 14 §14.10, doc 02 §2.4 steps 15–17)
        move(CouncilRunStatus.SYNTHESIZING)
        val selected = assessment.cluster(decision.selectedCandidateKey)
        val verified = assessment.contributions.flatMap { it.claims }.filter { assessment.claimStatuses[it.id]?.supports == true }.map { it.text.take(300) }.distinct()
        val unresolved = (decision.unresolvedCriticalConcerns + (selected?.highAgainst ?: emptyList())).map { it.text.take(300) }.distinct()
        val alternatives = assessment.clusters.filter { it.key != selected?.key }
        val route = p.synthesisRoute ?: p.slots.first().route!!
        suspend fun synthesize(verifierReason: String?): String? = tracer.span("council.synthesize", req.parentTaskId, mapOf("council.run" to runId)) {
            caller.text(route, listOf(ChatMessage("system", CouncilPrompts.synthesisSystem()),
                ChatMessage("user", CouncilPrompts.synthesisUser(req.taskBrief, decision, selected, verified, unresolved, decision.minorityReport, alternatives, st.notices.take(3), verifierReason))),
                SYNTHESIS_OUTPUT, "council.synthesizer", env, final = true).first
        }
        var answer = synthesize(null)
        if (answer.isNullOrBlank()) {
            st.partial = true
            st.notices += "Synthèse indisponible : réponse construite à partir de la décision."
            answer = fallbackAnswer(selected, decision, alternatives, unresolved)
        }
        move(CouncilRunStatus.VERIFYING)
        val evidenceLines = env.toolEvidence.values.map { it.first } + verified
        var verification = verify(req, answer, evidenceLines, unresolved, route, preset?.strictVerifier == true, env)
        if (verification.status == "failed") {
            st.verifierRejected = true
            move(CouncilRunStatus.SYNTHESIZING)
            val repaired = synthesize(verification.reason)
            move(CouncilRunStatus.VERIFYING)
            if (!repaired.isNullOrBlank()) {
                answer = repaired
                verification = verify(req, answer, evidenceLines, unresolved, route, preset?.strictVerifier == true, env).copy(repaired = true)
            }
            if (verification.status == "failed") { st.partial = true; st.notices += "Vérification non concluante : réponse à considérer avec prudence." }
        }
        onEvent(CouncilEvent.VerificationCompleted(runId, verification.status))
        val status = if (st.partial) CouncilRunStatus.PARTIAL else CouncilRunStatus.COMPLETED
        return finish(st, runId, req, p, env, decision, answer, status, if (status == CouncilRunStatus.COMPLETED) "completed" else "partial", started, onEvent, verification, assessment)
    }

    private suspend fun verify(req: CouncilRunRequest, answer: String, evidence: List<String>, unresolved: List<String>, route: io.github.artisanguillonrenov.cortana.core.model.ModelRoute,
                               strict: Boolean, env: CouncilRunEnv): VerificationSummary {
        val r = env.guard.reserveFinal(PlannedModelCall("verifier", VERIFY_TOKENS, 300))
        if (!r.granted) return VerificationSummary("uncertain", "budget de vérification épuisé")
        val v = tracer.span("council.verify", req.parentTaskId, mapOf("council.run" to env.runId)) {
            verifier.verifyAnswer(req.parentTaskId, req.taskBrief.userGoal, answer, evidence, unresolved, route, strict)
        }
        env.guard.commit(r, VERIFY_TOKENS, 200, null)
        return VerificationSummary(when (v.status) { VerificationStatus.PASSED -> "passed"; VerificationStatus.FAILED -> "failed"; else -> "uncertain" }, v.reason)
    }

    private suspend fun runJudge(p: CouncilPlan, req: CouncilRunRequest, a: Assessment, env: CouncilRunEnv): JudgeVerdict? {
        val route = p.judgeRoute ?: return null
        // Anonymised, order by key (never by popularity), no identities, no votes (doc 14 §14.9).
        val anonymous = a.clusters.sortedBy { it.key }.mapIndexed { i, c -> ('A' + i).toString() to c }
        val byLabel = anonymous.toMap()
        val obj = tracer.span("council.judge", req.parentTaskId, mapOf("council.run" to p.runId)) {
            caller.structured(route, CouncilPrompts.judgeSystem(), CouncilPrompts.judgeUser(req.taskBrief, anonymous, a.claimStatuses, a.contributions,
                listOf("répond à l'objectif", "respecte les contraintes et actions interdites", "étayé par des preuves", "risques maîtrisés")),
                """{"ranking":["A"],"preferredCandidate":"A","confidence":0.0}""", { o -> SchemaValidator.validate(JUDGE_SCHEMA, o) }, env)
        } ?: return null
        fun key(label: String?) = label?.trim()?.uppercase()?.let { byLabel[it]?.key }
        val ranking = (obj["ranking"] as? JsonArray)?.mapNotNull { key((it as? JsonPrimitive)?.contentOrNull) }.orEmpty().distinct()
        val disq = (obj["disqualifications"] as? JsonArray)?.mapNotNull { d -> key(((d as? JsonObject)?.get("candidate") as? JsonPrimitive)?.contentOrNull) }.orEmpty().toSet()
        val issues = (obj["unresolvedIssues"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ContributionParser::sanitize)?.take(300) }.orEmpty()
        return JudgeVerdict(ranking, disq, key((obj["preferredCandidate"] as? JsonPrimitive)?.contentOrNull), ((obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.5).coerceIn(0.0, 1.0), issues)
    }

    private fun assess(latest: Map<String, CouncilContribution>, round: Int, previous: Assessment?, env: CouncilRunEnv, models: Map<String, String>, protocol: DecisionProtocol, req: CouncilRunRequest): Assessment =
        CouncilAssessor.assess(latest.values.toList(), round, EvidenceLedger(env.toolEvidence.toMap(), req.taskBrief.knownFacts), previous, models, protocol)

    private fun shouldContinue(st: RunState, p: CouncilPlan, a: Assessment, round: Int, env: CouncilRunEnv, active: Int, minEvidence: Double): Boolean {
        if (round >= p.maxRounds || p.topology == CouncilTopology.INDEPENDENT) return false
        if (env.guard.timeLeft() < MIN_ROUND_MS) { st.notices += "Temps limite : arrêt après le tour $round."; return false }
        if (!env.guard.canContinue(projectRound(p, active, p.retention.maxTokensPerTarget))) { st.notices += "Budget atteint : arrêt après le tour $round."; return false }
        if (a.criticalConcerns.isNotEmpty()) return true
        if (a.divergence > HIGH_DIVERGENCE) return true
        if (a.evidenceCoverage < minEvidence && p.slots.any { it.toolScope.isNotEmpty() }) return true
        if (p.earlyStop && a.agreement >= STRONG_AGREEMENT && (a.stable || a.agreement >= 1.0) && a.evidenceCoverage >= minEvidence) { st.earlyStop = true; return false }
        if (!p.earlyStop) return true
        val gain = a.divergence * 0.6 + (1 - a.evidenceCoverage) * 0.3
        if (gain <= MIN_GAIN) st.earlyStop = true
        return gain > MIN_GAIN
    }

    private fun projectRound(p: CouncilPlan, active: Int, argTokens: Int): Int =
        active * (CouncilPrompts.BASE_TOKENS + argTokens + (p.slots.maxOfOrNull { it.budget.maxOutputTokens } ?: 900))

    private fun resolveTopology(t: CouncilTopology, divergence: Double) = when (t) {
        CouncilTopology.AUTO -> if (divergence >= HIGH_DIVERGENCE) CouncilTopology.FULL_MESH else CouncilTopology.SPARSE_DYNAMIC
        else -> t
    }

    private suspend fun parallel(p: CouncilPlan, env: CouncilRunEnv, onEvent: (CouncilEvent) -> Unit, roundId: String, turns: List<AgentTurn>): List<AgentTurnResult> = coroutineScope {
        val global = Semaphore(p.budget.maxConcurrentAgents.coerceAtLeast(1))
        val perProvider = ConcurrentHashMap<String, Semaphore>()
        val results = turns.map { t ->
            async {
                global.withPermit {
                    perProvider.computeIfAbsent(t.slot.route?.providerId ?: "-") { Semaphore(PROVIDER_CONCURRENCY) }.withPermit {
                        recorder.slotStatus(t.slot.id, SlotStatus.RUNNING)
                        onEvent(CouncilEvent.AgentStarted(p.runId, t.slot.id, t.slot.profileId, t.round))
                        val limit = minOf(t.slot.budget.timeoutMs, env.guard.timeLeft()).coerceAtLeast(1)
                        val r = withTimeoutOrNull(limit) { caller.run(t, env) } ?: AgentTurnResult(t.slot.id, t.round, SlotStatus.TIMED_OUT, errorCode = "timeout", latencyMs = limit)
                        recorder.slotStatus(t.slot.id, r.status)
                        onEvent(if (r.status == SlotStatus.SUCCEEDED) CouncilEvent.AgentCompleted(p.runId, t.slot.id, t.round) else CouncilEvent.AgentFailed(p.runId, t.slot.id, t.round, r.errorCode ?: r.status.name))
                        r
                    }
                }
            }
        }.awaitAll()
        results.forEach { recorder.contribution(roundId, it, emptyMap(), env.toolEvidence.toMap()) }
        results
    }

    private fun fallbackAnswer(selected: CandidateCluster?, d: CouncilDecision, alternatives: List<CandidateCluster>, unresolved: List<String>): String = buildString {
        if (selected != null) append(selected.candidate.summary) else {
            append("Le conseil n'a pas pu trancher (${d.unresolvedReason ?: "désaccord"}). Options examinées :\n")
            alternatives.take(3).forEach { append("• ").append(it.candidate.summary.take(400)).append('\n') }
        }
        if (unresolved.isNotEmpty()) append("\n\nPoints non résolus : ").append(unresolved.take(3).joinToString(" ; "))
    }

    private fun usage(env: CouncilRunEnv?, started: Long) = CouncilUsage(
        inputTokens = env?.guard?.inputTokens ?: 0, outputTokens = env?.guard?.outputTokens ?: 0, costMicros = env?.guard?.costMicros,
        providerCalls = env?.guard?.providerCalls ?: 0, toolCalls = env?.guard?.toolCalls ?: 0, wallTimeMs = System.currentTimeMillis() - started,
        reductions413 = env?.reductions413?.get() ?: 0, rateLimited = env?.rateLimited?.get() ?: 0, fallbacks = env?.fallbacks?.get() ?: 0,
    )

    private fun metrics(st: RunState, p: CouncilPlan?, a: Assessment?, env: CouncilRunEnv?, d: CouncilDecision?) = CouncilRunMetrics(
        agents = p?.slots?.size ?: 0, validAgents = a?.contributions?.size ?: 0, rounds = st.rounds, earlyStop = st.earlyStop, fallbacks = env?.fallbacks?.get() ?: 0,
        verifierRejected = st.verifierRejected, minorityOverturn = d?.overturnedMajority == true, reductions413 = env?.reductions413?.get() ?: 0,
        rateLimited = env?.rateLimited?.get() ?: 0, retainedArguments = st.retained, droppedArguments = st.dropped, judgeUsed = d?.judgeUsed == true,
    )

    private fun summary(st: RunState, runId: String, status: CouncilRunStatus, d: CouncilDecision?, a: Assessment?, p: CouncilPlan?, env: CouncilRunEnv?, started: Long): CouncilSummary {
        val selected = a?.cluster(d?.selectedCandidateKey)
        val single = (a?.contributions?.size ?: 0) <= 1
        val minEvidence = p?.let { profiles.preset(it.presetId)?.minEvidenceCoverage } ?: 0.3
        val consensus = when {
            a == null || d == null -> "aucun"
            single -> "un seul avis"
            d.selectedCandidateKey == null -> "pas de consensus"
            d.agreementScore >= STRONG_AGREEMENT && d.evidenceCoverageScore >= minEvidence && d.unresolvedCriticalConcerns.isEmpty() -> "fort"
            d.agreementScore >= 0.5 -> "moyen"
            else -> "faible"
        }
        val supportersClaims = a?.contributions?.filter { selected != null && it.slotId in selected.supporters }?.flatMap { it.claims }.orEmpty()
        val notices = st.notices.toMutableList()
        if ((env?.reductions413?.get() ?: 0) > 0) notices += "Contexte trop volumineux : compactage appliqué."
        if ((env?.rateLimited?.get() ?: 0) > 0) notices += "Fournisseur saturé (429) : attente ou repli appliqué."
        return CouncilSummary(
            runId = runId, status = status, consensus = consensus,
            agreements = listOfNotNull(selected?.let { "${it.supporters.size}/${a?.contributions?.size ?: 0} spécialistes soutiennent la décision" }) +
                supportersClaims.filter { a?.claimStatuses?.get(it.id)?.supports == true }.map { it.text.take(160) }.distinct().take(3),
            objections = (d?.unresolvedCriticalConcerns.orEmpty() + selected?.highAgainst.orEmpty()).map { it.text.take(200) }.distinct().take(4),
            uncertainties = (supportersClaims.filter { a?.claimStatuses?.get(it.id) in setOf(EvidenceStatus.MODEL_ONLY, EvidenceStatus.UNVERIFIED, EvidenceStatus.STALE) }.map { it.text.take(160) } +
                a?.contributions?.flatMap { it.assumptions }.orEmpty().map { it.take(160) }).distinct().take(4),
            evidence = env?.toolEvidence?.entries?.sortedBy { it.key }?.map { "${it.key} ${it.value.first.take(160)}" }.orEmpty().take(6),
            agentsOk = a?.contributions?.size ?: 0, agentsTotal = p?.slots?.size ?: 0, rounds = st.rounds, durationMs = System.currentTimeMillis() - started,
            models = p?.slots?.mapNotNull { it.route?.let { r -> "${r.providerName.ifBlank { r.providerId }} · ${r.modelId}" } }.orEmpty().distinct(),
            notices = notices.distinct().take(8), totalTokens = env?.guard?.totalTokens ?: 0,
            votes = a?.ballots?.mapNotNull { it.ranking.firstOrNull() }?.groupingBy { it }?.eachCount().orEmpty(), retainedArguments = st.retained,
        )
    }

    private fun result(runId: String, status: CouncilRunStatus, answer: String, d: CouncilDecision?, v: VerificationSummary?, usage: CouncilUsage, summary: CouncilSummary,
                       actions: List<String> = emptyList(), tainted: Boolean = false) =
        CouncilResult(runId, status, answer, d, v, usage, summary, actions, tainted, diagnosticsRef = runId)

    private fun failed(runId: String, status: CouncilRunStatus, reason: String, usage: CouncilUsage) =
        CouncilResult(runId, status, "", null, null, usage, CouncilSummary(runId, status, "aucun", notices = listOf(reason)))

    private suspend fun finish(st: RunState, runId: String, req: CouncilRunRequest, p: CouncilPlan?, env: CouncilRunEnv?, d: CouncilDecision?, answer: String,
                               status: CouncilRunStatus, reason: String, started: Long, onEvent: (CouncilEvent) -> Unit,
                               v: VerificationSummary? = null, a: Assessment? = null): CouncilResult {
        if (!st.status.terminal) { st.status = status; recorder.status(runId, status) }
        if (status != CouncilRunStatus.COMPLETED && status != CouncilRunStatus.PARTIAL) st.notices += reason
        val selected = a?.cluster(d?.selectedCandidateKey)
        val res = result(runId, status, Redactor.redact(answer), d, v, usage(env, started), summary(st, runId, status, d, a, p, env, started),
            selected?.candidate?.sideEffects.orEmpty(), (env?.tainted ?: req.tainted) || a?.tainted == true)
        recorder.finished(runId, res, reason, metrics(st, p, a, env, d))
        audit?.record("cortana", "council.run", runId, status.name.lowercase(), """{"agents":${p?.slots?.size ?: 0},"rounds":${st.rounds},"tokens":${res.usage.totalTokens}}""")
        onEvent(CouncilEvent.RunCompleted(runId, status))
        return res
    }

    companion object {
        const val SYNTHESIS_TOKENS = 4_000
        const val SYNTHESIS_OUTPUT = 1_500
        const val VERIFY_TOKENS = 2_500
        const val PROVIDER_CONCURRENCY = 4
        const val HIGH_DIVERGENCE = 0.6
        const val STRONG_AGREEMENT = 0.75
        const val MIN_GAIN = 0.25
        const val MIN_ROUND_MS = 20_000L
        val JUDGE_SCHEMA: JsonObject = AppJson.parseToJsonElement(
            """{"type":"object","required":["ranking","preferredCandidate"],"properties":{"ranking":{"type":"array","items":{"type":"string"}},"disqualifications":{"type":"array"},"evidenceAssessment":{"type":"string"},"unresolvedIssues":{"type":"array","items":{"type":"string"}},"preferredCandidate":{"type":"string"},"confidence":{"type":"number"}}}"""
        ).jsonObject
    }
}
