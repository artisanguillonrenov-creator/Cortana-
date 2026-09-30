package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.model.ModelRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Cognitive Council Engine (docs/council_pack, D-20260929-067): a bounded, audited multi-agent
 * reasoning mode that stays a sub-operation of the one orchestrator. These are its contracts;
 * the engine owns no task state, no model client, no tool path, no memory, no second database.
 */

// ------------------------------------------------------------------ configuration (council_engine_config.schema.json)

@Serializable
enum class CouncilMode {
    @SerialName("off") OFF, @SerialName("fast") FAST, @SerialName("reinforced") REINFORCED,
    @SerialName("council4") COUNCIL_4, @SerialName("deep") DEEP, @SerialName("auto") AUTO, @SerialName("custom") CUSTOM,
}

@Serializable
enum class CouncilTopology {
    @SerialName("auto") AUTO, @SerialName("independent") INDEPENDENT, @SerialName("full_mesh") FULL_MESH,
    @SerialName("hub") HUB, @SerialName("ring") RING, @SerialName("sparse_dynamic") SPARSE_DYNAMIC,
}

@Serializable
enum class DecisionProtocol {
    @SerialName("auto") AUTO, @SerialName("simple_majority") SIMPLE_MAJORITY, @SerialName("approval") APPROVAL,
    @SerialName("ranked") RANKED, @SerialName("cumulative") CUMULATIVE, @SerialName("majority_consensus") MAJORITY_CONSENSUS,
    @SerialName("supermajority") SUPERMAJORITY, @SerialName("unanimity") UNANIMITY, @SerialName("hybrid") HYBRID,
    @SerialName("judge") JUDGE, @SerialName("blind_judge_then_vote") BLIND_JUDGE_THEN_VOTE,
}

@Serializable
enum class ReasoningEffort { @SerialName("auto") AUTO, @SerialName("low") LOW, @SerialName("medium") MEDIUM, @SerialName("high") HIGH, @SerialName("xhigh") XHIGH }

@Serializable
data class CouncilBudgetConfig(
    val maxTotalTokens: Int = 60_000,
    /** Null = no cost cap (the token cap always applies; cost may be unknown). */
    val maxCostMicros: Long? = null,
    val maxWallTimeMs: Long = 240_000,
    val maxProviderCalls: Int = 16,
    val maxToolCalls: Int = 12,
    val maxConcurrentAgents: Int = 4,
)

@Serializable
data class RetentionConfig(
    val enabled: Boolean = true,
    val maxArgumentsPerTarget: Int = 6,
    val maxTokensPerTarget: Int = 2_500,
    /** Schema `const: true`: critical objections are never filtered out. */
    val alwaysKeepCritical: Boolean = true,
    val minorityProtection: Boolean = true,
)

/** A role's model routing. [providerId]/[modelId] null = inherit the main route (doc 18 §18.5). */
@Serializable
data class RoleModelConfig(
    val profileId: String,
    val providerId: String? = null,
    val modelId: String? = null,
    /** "providerId/modelId" or a bare model id of the same provider, tried in order. */
    val fallbackModelIds: List<String> = emptyList(),
    val localOnly: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val timeoutMs: Long? = null,
)

/** Exactly the fields of `council_engine_config.schema.json` (validated by `CouncilContractsTest`). */
@Serializable
data class CouncilConfig(
    val enabled: Boolean = false,
    val mode: CouncilMode = CouncilMode.AUTO,
    val maxAgents: Int = 4,
    val maxRounds: Int = 1,
    val quorumRatio: Double = 0.67,
    val topology: CouncilTopology = CouncilTopology.AUTO,
    val decisionProtocol: DecisionProtocol = DecisionProtocol.AUTO,
    val challengeFinal: Boolean = true,
    val showCouncilSummary: Boolean = true,
    val budget: CouncilBudgetConfig = CouncilBudgetConfig(),
    val retention: RetentionConfig = RetentionConfig(),
    val roles: List<RoleModelConfig> = emptyList(),
) {
    /** The mode actually in force: disabled = OFF, whatever the stored mode (feature flag). */
    val effectiveMode: CouncilMode get() = if (enabled) mode else CouncilMode.OFF

    fun toSchemaJson(): JsonObject = SCHEMA_JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        private val SCHEMA_JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

@Serializable
enum class ModelRouting { @SerialName("same_model") SAME_MODEL, @SerialName("per_role") PER_ROLE, @SerialName("auto_capability") AUTO_CAPABILITY, @SerialName("hybrid") HYBRID }

@Serializable
enum class BudgetProfile { @SerialName("eco") ECO, @SerialName("balanced") BALANCED, @SerialName("quality") QUALITY, @SerialName("custom") CUSTOM }

@Serializable
enum class JudgeSetting { @SerialName("off") OFF, @SerialName("auto") AUTO, @SerialName("on") ON }

/** Owner preferences shown in the settings screen that the config schema does not carry (doc 07). */
@Serializable
data class CouncilPrefs(
    val budgetProfile: BudgetProfile = BudgetProfile.BALANCED,
    val routing: ModelRouting = ModelRouting.SAME_MODEL,
    /** "providerId/modelId" for the synthesis and the judge (null = main route / auto). */
    val synthesisRoute: String? = null,
    val judgeRoute: String? = null,
    val judge: JudgeSetting = JudgeSetting.AUTO,
    val earlyStop: Boolean = true,
    /** Tokens the council may spend per day, all runs together (null = no daily cap). */
    val maxDailyTokens: Int? = null,
    /** Above this projected size a run is reduced in Auto mode (and the owner warned). */
    val warnAboveTokens: Int = 40_000,
    /** Battery below this percentage: Auto mode reduces agents and rounds (0 = never). */
    val lowBatteryPercent: Int = 20,
    val developerDetails: Boolean = false,
)

// ------------------------------------------------------------------ run inputs

/** The shared reference of round 0 (doc 14 §14.2). */
@Serializable
data class TaskBrief(
    val taskId: String,
    val userGoal: String,
    val taskType: String,
    val constraints: List<String> = emptyList(),
    val knownFacts: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val riskClass: String = "low",
    val allowedActions: List<String> = emptyList(),
    val forbiddenActions: List<String> = emptyList(),
    val artifactRefs: List<String> = emptyList(),
    val deadline: Long? = null,
    val expectedOutput: String = "réponse directe",
)

data class PrivacyConstraints(val localOnly: Boolean = false, val incognito: Boolean = false)

data class CouncilRunRequest(
    val parentTaskId: String,
    val sessionId: String,
    val taskBrief: TaskBrief,
    val mode: CouncilMode,
    val presetId: String?,
    val budget: CouncilBudgetConfig,
    val privacy: PrivacyConstraints,
    /** The main route of the task (inherited by roles without their own model). */
    val mainRoute: ModelRoute?,
    /** Capabilities of the session toolset: the widest boundary any agent can get (read-only subset only). */
    val toolPool: Set<String> = emptySet(),
    val tainted: Boolean = false,
    val taintSources: List<String> = emptyList(),
    /** What remains of the task's own limits: the council never exceeds them. */
    val remainingModelCalls: Int = Int.MAX_VALUE,
    val remainingToolCalls: Int = Int.MAX_VALUE,
    val batteryPercent: Int? = null,
    /** Session toolset (the owner's boundary), passed to tool contexts. */
    val toolset: String = "full",
    /** What remains of the task's tool-call limit, for the dispatcher's own check. */
    val maxTaskToolCalls: Int = 30,
    /** The owner chose this mode in the settings (not Auto): the warning threshold does not reduce it. */
    val explicitMode: Boolean = false,
)

// ------------------------------------------------------------------ plan

data class AgentBudget(
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val timeoutMs: Long,
    val maxToolCalls: Int,
    val maxModelCalls: Int,
)

data class CouncilAgentSlot(
    val id: String,
    val profileId: String,
    val route: ModelRoute?,
    val fallbacks: List<ModelRoute> = emptyList(),
    val requiredCapabilities: Set<ModelCapability> = emptySet(),
    val toolScope: List<String> = emptyList(),
    val required: Boolean = true,
    val budget: AgentBudget,
    /** The owner's reasoning effort for this role (low|medium|high|xhigh), null = the model's default. */
    val reasoningEffort: String? = null,
    /** Sampling diversity when several slots share one model (doc 13 §13.8). */
    val temperature: Double? = null,
    /** Why the preferred model was not used (shown to the owner). */
    val routeNote: String? = null,
)

enum class ModelCapability { JSON, TOOLS, LONG_CONTEXT, CODING, VISION }

data class CouncilPlan(
    val runId: String,
    val presetId: String,
    val mode: CouncilMode,
    val slots: List<CouncilAgentSlot>,
    val topology: CouncilTopology,
    val decisionProtocol: DecisionProtocol,
    val maxRounds: Int,
    val quorum: Int,
    val challengeFinal: Boolean,
    val judge: Boolean,
    val judgeRoute: ModelRoute?,
    val synthesisRoute: ModelRoute?,
    val budget: CouncilBudgetConfig,
    val retention: RetentionConfig,
    val earlyStop: Boolean = true,
    /** Degradations applied to fit the budget, in order (doc 05 §5.3). */
    val degradations: List<String> = emptyList(),
)

// ------------------------------------------------------------------ contributions

@Serializable enum class ClaimType { @SerialName("fact") FACT, @SerialName("inference") INFERENCE, @SerialName("assumption") ASSUMPTION, @SerialName("recommendation") RECOMMENDATION }
@Serializable enum class ConcernSeverity(val weight: Double) { @SerialName("low") LOW(0.25), @SerialName("medium") MEDIUM(0.5), @SerialName("high") HIGH(0.75), @SerialName("critical") CRITICAL(1.0) }

@Serializable
enum class EvidenceStatus { UNVERIFIED, SUPPORTED, CONTRADICTED, STALE, TOOL_VERIFIED, USER_PROVIDED, MODEL_ONLY;
    val supports get() = this == SUPPORTED || this == TOOL_VERIFIED || this == USER_PROVIDED
}

@Serializable
data class Claim(
    val id: String,
    val text: String,
    val type: ClaimType,
    val evidenceRefs: List<String> = emptyList(),
    val confidence: Double? = null,
    val tainted: Boolean = false,
    val status: EvidenceStatus = EvidenceStatus.MODEL_ONLY,
)

@Serializable
data class Concern(
    val id: String,
    val severity: ConcernSeverity,
    val text: String,
    val targetClaimId: String? = null,
    /** Candidate key the concern is about (null = the task in general). */
    val targetCandidateKey: String? = null,
    val sourceSlotId: String = "",
    val resolved: Boolean = false,
)

/** A normalised answer: agents vote on fingerprints, never on raw text (doc 04 §4.9). */
@Serializable
data class Candidate(
    val key: String,
    val summary: String,
    val action: String,
    val target: String,
    val parameters: List<String> = emptyList(),
    val expectedResult: String = "",
    /** Side effects the candidate implies (capabilities or effect words); never merged across different sets. */
    val sideEffects: List<String> = emptyList(),
    val risk: String = "low",
)

@Serializable
data class ConfidenceFeatures(
    val selfReported: Double? = null,
    val logprobDerived: Double? = null,
    val crossAgentAgreement: Double = 0.0,
    val evidenceSupport: Double = 0.0,
    val verifierSupport: Double? = null,
    val consistencyAcrossRounds: Double = 0.0,
    val toolResultSupport: Double = 0.0,
    val sourceQuality: Double = 0.0,
    val contradictionPenalty: Double = 0.0,
) {
    /** A calibrated heuristic, never a probability of truth (doc 04 §4.4): self-report weighs little. */
    fun score(): Double = (0.10 * (selfReported ?: 0.5) + 0.25 * crossAgentAgreement + 0.25 * evidenceSupport +
        0.15 * (verifierSupport ?: 0.5) + 0.10 * consistencyAcrossRounds + 0.10 * toolResultSupport + 0.05 * sourceQuality -
        0.30 * contradictionPenalty).coerceIn(0.0, 1.0)
}

@Serializable
data class RequestedCheck(val text: String, val capability: String? = null)

@Serializable
data class CouncilContribution(
    val slotId: String,
    val profileId: String,
    val round: Int,
    val candidate: Candidate?,
    val claims: List<Claim> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val concerns: List<Concern> = emptyList(),
    val confidence: ConfidenceFeatures = ConfidenceFeatures(),
    val requestedChecks: List<RequestedCheck> = emptyList(),
    /** Short, verifiable justification — never a chain of thought. */
    val rationaleSummary: String = "",
    /** Role-specific lists (planSteps, testPlan, counterExamples…), bounded by the parser. */
    val details: Map<String, List<String>> = emptyMap(),
    /** Candidate keys the Challenger attacks. */
    val challengedCandidateKeys: List<String> = emptyList(),
    /** Evidence gathered by read-only tools during this turn ("capability: short result"). */
    val toolEvidence: List<String> = emptyList(),
    val tainted: Boolean = false,
)

// ------------------------------------------------------------------ retention, votes, decision

data class RetainedArgument(
    val id: String,
    val sourceSlotId: String,
    val targetSlotIds: Set<String>,
    val summary: String,
    val evidenceRefs: List<String>,
    val novelty: Double,
    val disagreement: Double,
    val severity: ConcernSeverity?,
    val tokenEstimate: Int,
    val kind: ArgumentKind,
    val candidateKey: String? = null,
    val tainted: Boolean = false,
)

enum class ArgumentKind { CRITICAL_CONCERN, CONTRADICTORY_EVIDENCE, CONCERN, CANDIDATE, CLAIM, SUPPORT }

data class RetentionPlan(val perTarget: Map<String, List<RetainedArgument>>, val dropped: Int, val minorityReport: List<String>)

@Serializable
data class BallotSummary(
    val slotId: String,
    val protocol: DecisionProtocol,
    /** Preference order (best first); approvals for APPROVAL; points per key for CUMULATIVE. */
    val ranking: List<String> = emptyList(),
    val approvals: List<String> = emptyList(),
    val points: Map<String, Int> = emptyMap(),
)

@Serializable
data class CouncilDecision(
    val protocol: DecisionProtocol,
    val selectedCandidateKey: String?,
    val agreementScore: Double,
    val divergenceScore: Double,
    val evidenceCoverageScore: Double,
    val unresolvedCriticalConcerns: List<Concern> = emptyList(),
    val minorityReport: List<String> = emptyList(),
    val ballots: List<BallotSummary> = emptyList(),
    /** tie | critical_concern | verifier_rejected | no_quorum | … when no candidate could be selected. */
    val unresolvedReason: String? = null,
    val scores: Map<String, Double> = emptyMap(),
    val judgeUsed: Boolean = false,
    val overturnedMajority: Boolean = false,
)

// ------------------------------------------------------------------ result

@Serializable
enum class CouncilRunStatus(val terminal: Boolean) {
    CREATED(false), PLANNING(false), RESOLVING_MODELS(false), PREPARING_CONTEXT(false), ROUND_INITIAL(false),
    ASSESSING(false), ROUND_CRITIQUE(false), DECIDING(false), CHALLENGING(false), SYNTHESIZING(false), VERIFYING(false),
    COMPLETED(true), CANCELLED(true), FAILED(true), TIMED_OUT(true), BUDGET_EXHAUSTED(true), PARTIAL(true);

    companion object {
        private val ENDINGS = setOf(CANCELLED, FAILED, TIMED_OUT, BUDGET_EXHAUSTED, PARTIAL)
        /** Doc 01 §1.5; every live state may end in CANCELLED/FAILED/TIMED_OUT/BUDGET_EXHAUSTED/PARTIAL. */
        val TRANSITIONS: Map<CouncilRunStatus, Set<CouncilRunStatus>> = mapOf(
            CREATED to setOf(PLANNING),
            PLANNING to setOf(RESOLVING_MODELS),
            RESOLVING_MODELS to setOf(PREPARING_CONTEXT),
            PREPARING_CONTEXT to setOf(ROUND_INITIAL),
            ROUND_INITIAL to setOf(ASSESSING),
            ASSESSING to setOf(ROUND_CRITIQUE, DECIDING),
            ROUND_CRITIQUE to setOf(ASSESSING),
            DECIDING to setOf(CHALLENGING, SYNTHESIZING),
            CHALLENGING to setOf(ROUND_CRITIQUE, DECIDING, SYNTHESIZING),
            SYNTHESIZING to setOf(VERIFYING, COMPLETED),
            VERIFYING to setOf(COMPLETED, SYNTHESIZING),
        ).mapValues { (from, to) -> if (from.terminal) to else to + ENDINGS }

        fun allowed(from: CouncilRunStatus, to: CouncilRunStatus) = TRANSITIONS[from]?.contains(to) == true
    }
}

@Serializable
data class CouncilUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costMicros: Long? = null,
    val providerCalls: Int = 0,
    val toolCalls: Int = 0,
    val wallTimeMs: Long = 0,
    val reductions413: Int = 0,
    val rateLimited: Int = 0,
    val fallbacks: Int = 0,
) {
    val totalTokens get() = inputTokens + outputTokens
}

@Serializable
data class VerificationSummary(val status: String, val reason: String, val repaired: Boolean = false)

/** The "Résumé du conseil" card (doc 07 §7.7): no reasoning, no prompts, no secrets. */
@Serializable
data class CouncilSummary(
    val runId: String,
    val status: CouncilRunStatus,
    val consensus: String,
    val agreements: List<String> = emptyList(),
    val objections: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    val agentsOk: Int = 0,
    val agentsTotal: Int = 0,
    val rounds: Int = 0,
    val durationMs: Long = 0,
    val models: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
    val totalTokens: Int = 0,
    val votes: Map<String, Int> = emptyMap(),
    val retainedArguments: Int = 0,
)

data class CouncilResult(
    val runId: String,
    val status: CouncilRunStatus,
    val finalAnswerDraft: String,
    val decision: CouncilDecision?,
    val verification: VerificationSummary?,
    val usage: CouncilUsage,
    val summary: CouncilSummary,
    /** The selected candidate implies side effects: the orchestrator executes them through its normal path. */
    val proposedActions: List<String> = emptyList(),
    val tainted: Boolean = false,
    val diagnosticsRef: String? = null,
)

// ------------------------------------------------------------------ progress events (UI, audit)

sealed interface CouncilEvent {
    val runId: String
    data class RunCreated(override val runId: String, val mode: CouncilMode) : CouncilEvent
    data class PlanCreated(override val runId: String, val slots: List<Pair<String, String>>, val rounds: Int) : CouncilEvent
    data class Phase(override val runId: String, val status: CouncilRunStatus) : CouncilEvent
    data class AgentStarted(override val runId: String, val slotId: String, val profileId: String, val round: Int) : CouncilEvent
    data class AgentCompleted(override val runId: String, val slotId: String, val round: Int) : CouncilEvent
    data class AgentFailed(override val runId: String, val slotId: String, val round: Int, val code: String) : CouncilEvent
    data class RoundCompleted(override val runId: String, val round: Int, val agreement: Double, val divergence: Double) : CouncilEvent
    data class ArgumentsRetained(override val runId: String, val round: Int, val retained: Int, val dropped: Int) : CouncilEvent
    data class DecisionProposed(override val runId: String, val candidateKey: String?) : CouncilEvent
    data class DecisionChallenged(override val runId: String, val critical: Boolean) : CouncilEvent
    data class VerificationCompleted(override val runId: String, val status: String) : CouncilEvent
    data class RunCompleted(override val runId: String, val status: CouncilRunStatus) : CouncilEvent
    data class RunCancelled(override val runId: String) : CouncilEvent
}

// ------------------------------------------------------------------ interfaces (doc 09)

interface CognitiveCouncilEngine {
    suspend fun run(request: CouncilRunRequest, onEvent: (CouncilEvent) -> Unit = {}): CouncilResult
    suspend fun cancel(runId: String)
}

data class CouncilSelectionInput(
    val objective: String,
    val coding: Boolean,
    val multiStep: Boolean,
    val toolsAvailable: Boolean,
    val fastPath: Boolean,
    val source: String,
    val batteryPercent: Int? = null,
    val remainingModelCalls: Int = Int.MAX_VALUE,
    /** Set when the request comes from inside a council run: nested councils are refused (doc 08 §8.14). */
    val insideCouncil: Boolean = false,
    /** The owner chose "Conseil" in the conversation (Chat Workspace): run it even for a short request, never when disabled. */
    val explicitRequest: Boolean = false,
)

data class CouncilSelection(val mode: CouncilMode, val presetId: String?, val reason: String)

interface CouncilPolicySelector { fun select(input: CouncilSelectionInput, config: CouncilConfig, prefs: CouncilPrefs): CouncilSelection }

interface CouncilProfileRegistry {
    fun get(profileId: String): CouncilAgentProfile?
    fun list(): List<CouncilAgentProfile>
}

interface CouncilMessageRetainer { fun select(input: RetentionInput): RetentionPlan }

interface CouncilDecisionEngine { fun decide(input: DecisionInput): CouncilDecision }

data class PlannedModelCall(val slotId: String, val inputTokens: Int, val outputTokens: Int, val costMicros: Long? = null)
data class BudgetReservation(val id: Long, val call: PlannedModelCall, val granted: Boolean, val reason: String? = null)

interface CouncilBudgetGuard {
    fun reserve(call: PlannedModelCall): BudgetReservation
    fun commit(reservation: BudgetReservation, inputTokens: Int, outputTokens: Int, costMicros: Long?)
    fun canContinue(projectedTokens: Int): Boolean
}
