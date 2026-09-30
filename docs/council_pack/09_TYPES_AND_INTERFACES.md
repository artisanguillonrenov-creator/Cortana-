# 9. Types et interfaces conceptuels

Adapter aux conventions réelles du dépôt.

```kotlin
enum class CouncilMode {
    OFF, FAST, REINFORCED, COUNCIL_4, DEEP, AUTO, CUSTOM
}

data class CouncilRunRequest(
    val parentTaskId: String,
    val taskBrief: TaskBrief,
    val mode: CouncilMode,
    val presetId: String?,
    val budget: CouncilBudget,
    val privacy: PrivacyConstraints
)

data class CouncilPlan(
    val runId: String,
    val slots: List<CouncilAgentSlot>,
    val topology: CouncilTopology,
    val decisionProtocol: String,
    val maxRounds: Int,
    val quorum: Int,
    val challengeFinal: Boolean,
    val budget: CouncilBudget
)

data class CouncilAgentSlot(
    val id: String,
    val profileId: String,
    val requiredCapabilities: Set<ModelCapability>,
    val preferredModel: ModelPreference?,
    val toolScope: Set<String>,
    val required: Boolean,
    val budget: AgentBudget
)

data class CouncilContribution(
    val slotId: String,
    val round: Int,
    val candidate: Candidate?,
    val claims: List<Claim>,
    val assumptions: List<String>,
    val concerns: List<Concern>,
    val confidence: ConfidenceFeatures,
    val requestedChecks: List<RequestedCheck>,
    val rationaleSummary: String
)

data class Claim(
    val id: String,
    val text: String,
    val type: ClaimType,
    val evidenceRefs: List<String>,
    val confidence: Double?,
    val tainted: Boolean
)

data class RetainedArgument(
    val id: String,
    val sourceSlotId: String,
    val targetSlotIds: Set<String>,
    val summary: String,
    val evidenceRefs: List<String>,
    val novelty: Double,
    val disagreement: Double,
    val severity: ConcernSeverity?,
    val tokenEstimate: Int
)

data class CouncilDecision(
    val protocol: String,
    val selectedCandidateKey: String?,
    val agreementScore: Double,
    val divergenceScore: Double,
    val evidenceCoverageScore: Double,
    val unresolvedCriticalConcerns: List<Concern>,
    val minorityReport: List<String>,
    val ballots: List<BallotSummary>
)

data class CouncilResult(
    val status: CouncilRunStatus,
    val finalAnswerDraft: String,
    val decision: CouncilDecision,
    val verification: VerificationSummary?,
    val usage: CouncilUsage,
    val diagnosticsRef: String?
)
```

Interfaces :

```kotlin
interface CognitiveCouncilEngine {
    suspend fun run(request: CouncilRunRequest): CouncilResult
    suspend fun cancel(runId: String)
}

interface CouncilPolicySelector {
    fun select(input: CouncilSelectionInput): CouncilSelection
}

interface CouncilProfileRegistry {
    fun get(profileId: String): CouncilAgentProfile?
    fun list(): List<CouncilAgentProfile>
}

interface CouncilMessageRetainer {
    suspend fun select(input: RetentionInput): RetentionPlan
}

interface CouncilDecisionEngine {
    suspend fun decide(input: DecisionInput): CouncilDecision
}

interface CouncilBudgetGuard {
    fun reserve(call: PlannedModelCall): BudgetReservation
    fun commit(reservation: BudgetReservation, actual: Usage)
    fun canContinue(state: CouncilState): Boolean
}
```

Events :
- CouncilRunCreated
- CouncilPlanCreated
- CouncilAgentStarted
- CouncilAgentCompleted
- CouncilAgentFailed
- CouncilRoundCompleted
- CouncilArgumentsRetained
- CouncilVoteRecorded
- CouncilDecisionProposed
- CouncilDecisionChallenged
- CouncilVerificationCompleted
- CouncilRunCompleted
- CouncilRunCancelled
