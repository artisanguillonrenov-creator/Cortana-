package io.github.artisanguillonrenov.cortana.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- plans (§6.3, doc 04 §4)

@Serializable
enum class StepStatus {
    @SerialName("pending") PENDING, @SerialName("running") RUNNING, @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED, @SerialName("skipped") SKIPPED, @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class RiskHint { @SerialName("L0") L0, @SerialName("L1") L1, @SerialName("L2") L2, @SerialName("L3") L3 }

@Serializable
data class RetryPolicy(val maxAttempts: Int = 2, val backoffMs: Long = 1000, val retryOnlyIdempotent: Boolean = true)

@Serializable
data class ExpectedOutcome(val description: String, val checks: List<OutcomeCheck> = emptyList())

/** Deterministic checks the Verifier can run without the model (§12.1). */
@Serializable
data class OutcomeCheck(
    /** tool_succeeded | file_exists | file_hash | text_contains | ui_contains | test_passed | exit_code | model_judgement */
    val type: String,
    val target: String? = null,
    val expected: String? = null,
)

@Serializable
data class PlanStep(
    val stepId: String,
    val ordinal: Int,
    val title: String,
    val objective: String,
    val requiredCapabilities: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val expectedOutcome: ExpectedOutcome,
    val verificationStrategy: String = "auto",
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val timeoutMs: Long = 300_000,
    val riskEstimate: RiskHint = RiskHint.L1,
    val canParallelize: Boolean = false,
    val checkpointAfter: Boolean = true,
    val undoCapability: String? = null,
    val status: StepStatus = StepStatus.PENDING,
    val attempts: Int = 0,
    val resultSummary: String? = null,
    /** Specialist profile that runs this step for the orchestrator (doc 04 §17), or null for the main agent. */
    val specialist: String? = null,
)

@Serializable
data class PlanBudget(val maxSteps: Int = 12, val maxReplans: Int = 3, val maxToolCalls: Int = 30, val maxModelCalls: Int = 20, val maxMinutes: Int = 15)

@Serializable
enum class PlanStrategy {
    /** A single adaptive step: the model reasons with tools until done (1.2.0 behaviour). */
    @SerialName("interactive") INTERACTIVE,
    /** An explicit multi-step DAG produced by the Planner. */
    @SerialName("dag") DAG,
    /** Deterministic fast path, no model involved. */
    @SerialName("fast_path") FAST_PATH,
    /** Chat only. */
    @SerialName("direct") DIRECT,
}

@Serializable
data class Plan(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val planId: String,
    val taskId: String,
    val version: Int,
    val objective: String,
    val strategy: PlanStrategy,
    val steps: List<PlanStep>,
    val createdAt: Long,
    val createdByModel: String? = null,
    val budget: PlanBudget = PlanBudget(),
    val supersedesPlanId: String? = null,
    val rationale: String? = null,
) {
    fun step(id: String): PlanStep? = steps.firstOrNull { it.stepId == id }

    /** Steps whose dependencies all succeeded and that have not finished. */
    fun readySteps(): List<PlanStep> = steps.filter { s ->
        s.status == StepStatus.PENDING && s.dependencies.all { d -> step(d)?.status == StepStatus.SUCCEEDED || step(d)?.status == StepStatus.SKIPPED }
    }.sortedBy { it.ordinal }

    fun isFinished(): Boolean = steps.all { it.status == StepStatus.SUCCEEDED || it.status == StepStatus.SKIPPED }

    fun withStep(updated: PlanStep): Plan = copy(steps = steps.map { if (it.stepId == updated.stepId) updated else it })
}

object PlanValidator {
    /** Returns human-readable problems; empty when the plan is a valid DAG within budget. */
    fun validate(plan: Plan): List<String> {
        val problems = mutableListOf<String>()
        if (plan.steps.isEmpty()) problems += "plan vide"
        if (plan.steps.size > plan.budget.maxSteps) problems += "trop d'étapes (${plan.steps.size} > ${plan.budget.maxSteps})"
        val ids = plan.steps.map { it.stepId }
        if (ids.toSet().size != ids.size) problems += "identifiants d'étapes dupliqués"
        val idSet = ids.toSet()
        plan.steps.forEach { s ->
            s.dependencies.filter { it !in idSet }.forEach { problems += "étape ${s.stepId} : dépendance inconnue $it" }
            if (s.stepId in s.dependencies) problems += "étape ${s.stepId} dépend d'elle-même"
            if (s.objective.isBlank()) problems += "étape ${s.stepId} sans objectif"
        }
        if (problems.isEmpty() && hasCycle(plan)) problems += "le plan contient un cycle"
        return problems
    }

    private fun hasCycle(plan: Plan): Boolean {
        val deps = plan.steps.associate { it.stepId to it.dependencies }
        val state = mutableMapOf<String, Int>() // 0 unvisited, 1 visiting, 2 done
        fun visit(n: String): Boolean {
            when (state[n]) { 1 -> return true; 2 -> return false }
            state[n] = 1
            for (d in deps[n].orEmpty()) if (visit(d)) return true
            state[n] = 2
            return false
        }
        return deps.keys.any { visit(it) }
    }

    /** Groups of step ids that may run concurrently (topological layers). */
    fun layers(plan: Plan): List<List<String>> {
        val remaining = plan.steps.associate { it.stepId to it.dependencies.toMutableSet() }.toMutableMap()
        val out = mutableListOf<List<String>>()
        while (remaining.isNotEmpty()) {
            val layer = remaining.filter { it.value.isEmpty() }.keys.sorted()
            if (layer.isEmpty()) break
            out += layer
            layer.forEach { remaining.remove(it) }
            remaining.values.forEach { it.removeAll(layer.toSet()) }
        }
        return out
    }
}

// ---------------------------------------------------------------- checkpoints & notebook (§67, doc 03 §14, doc 04 §7)

@Serializable
data class TaskNotebook(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val taskId: String,
    val objective: String,
    val constraints: List<String> = emptyList(),
    val currentPlanSummary: String? = null,
    val completedMilestones: List<String> = emptyList(),
    val openItems: List<String> = emptyList(),
    val filesChanged: List<String> = emptyList(),
    val commandsRun: List<String> = emptyList(),
    val testsRun: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val currentWorkspaceRevision: String? = null,
    val lastCheckpointId: String? = null,
    val nextRecommendedAction: String? = null,
    /** Capabilities discovered during the task (dynamic tool discovery), re-offered after a resume. */
    val activeCapabilities: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class Checkpoint(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val checkpointId: String,
    val taskId: String,
    val planId: String?,
    val planVersion: Int,
    val state: TaskState,
    val completedSteps: List<String>,
    val pendingSteps: List<String>,
    val currentStepId: String? = null,
    val notebook: TaskNotebook? = null,
    val workspaceRevisions: Map<String, String> = emptyMap(),
    val idempotencyKeys: List<String> = emptyList(),
    val externalFingerprints: Map<String, String> = emptyMap(),
    val counters: Map<String, Int> = emptyMap(),
    val reason: String,
    val createdAt: Long,
)

// ---------------------------------------------------------------- authorization (§23.2, doc 06 §6)

@Serializable
data class AuthorizationGrant(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val grantId: String,
    val capability: String,
    /** e.g. a host, a package name, a workspace id; null = any target of this capability. */
    val scope: String? = null,
    /** Exact argument constraints (key → required value). */
    val argumentConstraints: Map<String, String> = emptyMap(),
    val taskId: String? = null,
    val sessionId: String? = null,
    val scheduleId: String? = null,
    val validFrom: Long,
    val validUntil: Long? = null,
    val maxUses: Int? = null,
    val uses: Int = 0,
    val revoked: Boolean = false,
    val createdBy: String = "owner",
    val createdAt: Long,
)

// ---------------------------------------------------------------- skills (§6.10, doc 04 §12)

@Serializable
enum class SkillLifecycle {
    @SerialName("candidate") CANDIDATE, @SerialName("tested") TESTED, @SerialName("validated") VALIDATED,
    @SerialName("active") ACTIVE, @SerialName("degraded") DEGRADED, @SerialName("retired") RETIRED,
}

@Serializable
data class SkillCondition(
    /** package_foreground | ui_contains | ui_absent | capability_available | app_version */
    val type: String,
    val target: String? = null,
    val expected: String? = null,
)

@Serializable
data class SkillStep(
    val ordinal: Int,
    val capability: String,
    /** Arguments; "{{param}}" placeholders are substituted from skill parameters. */
    val arguments: Map<String, String> = emptyMap(),
    val preconditions: List<SkillCondition> = emptyList(),
    val postconditions: List<SkillCondition> = emptyList(),
    /** Coordinate-only steps are fallbacks and never make a skill "validated". */
    val coordinateFallback: Boolean = false,
)

@Serializable
data class SkillDefinition(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val skillId: String,
    val name: String,
    val version: Int,
    val description: String,
    val triggerHints: List<String> = emptyList(),
    val parameters: Map<String, String> = emptyMap(),
    val requiredCapabilities: List<String> = emptyList(),
    val preconditions: List<SkillCondition> = emptyList(),
    val steps: List<SkillStep>,
    val postconditions: List<SkillCondition> = emptyList(),
    val riskLevel: RiskHint = RiskHint.L1,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val lifecycle: SkillLifecycle = SkillLifecycle.CANDIDATE,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val confidence: Double = 0.0,
    val createdFrom: String? = null,
    val lastValidatedAt: Long? = null,
    val enabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Capability sequence the skill was learned from (dedupes candidates). */
    val signature: String? = null,
    val lastFailureReason: String? = null,
) {
    /** JSON Schema of the parameters (all strings, all required) for validation and export. */
    fun parametersSchema(): Map<String, String> = parameters
}

/** Portable skill bundle (blueprint §17.4): metadata, description, schema, optional examples — never secrets. */
@Serializable
data class SkillBundle(
    val format: String = "cortana.skill",
    val bundleVersion: Int = 1,
    val skill: SkillDefinition,
    val examples: List<Map<String, String>> = emptyList(),
    val exportedAt: Long,
)

// ---------------------------------------------------------------- specialists (§40, doc 04 §17)

@Serializable
data class SpecialistProfile(
    val profileId: String,
    val role: String,
    val description: String,
    val toolset: List<String>,
    val preferredModelRole: String = "chat",
    val maxToolCalls: Int = 12,
    val maxModelCalls: Int = 8,
    val maxMinutes: Int = 6,
    val outputSchemaHint: String = "texte structuré",
)

@Serializable
data class SpecialistTask(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val specialistTaskId: String,
    val parentTaskId: String,
    val profileId: String,
    val objective: String,
    val context: String,
    val allowedCapabilities: List<String>,
    val createdAt: Long,
)

@Serializable
data class SpecialistResult(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val specialistTaskId: String,
    val profileId: String,
    val success: Boolean,
    val summary: String,
    val findings: List<String> = emptyList(),
    val toolCalls: Int = 0,
    val error: StructuredError? = null,
)
