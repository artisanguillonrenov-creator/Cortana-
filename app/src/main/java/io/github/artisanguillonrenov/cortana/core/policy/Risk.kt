package io.github.artisanguillonrenov.cortana.core.policy

import kotlinx.serialization.Serializable

/** §9.1 — L0 observe, L1 reversible navigation, L2 external/data side effect, L3 sensitive/irreversible. */
@Serializable
enum class Risk(val level: Int, val label: String) {
    L0(0, "L0 · lecture"),
    L1(1, "L1 · réversible"),
    L2(2, "L2 · effet externe"),
    L3(3, "L3 · sensible / irréversible");

    companion object {
        fun max(a: Risk, b: Risk): Risk = if (a.level >= b.level) a else b
    }
}

@Serializable enum class SideEffect { NONE, REVERSIBLE, EXTERNAL, IRREVERSIBLE }
@Serializable enum class Idempotency { INTRINSIC, KEYED, NONE }
@Serializable enum class DataEgress { NONE, LOCAL, EXTERNAL }

@Serializable
enum class Requirement { ALLOW, CONFIRM, BIOMETRIC, DENY }

/** Capability metadata used by the policy (doc 06 §5). Traits can only raise risk. */
@Serializable
enum class Trait {
    READ_ONLY, REVERSIBLE, EXTERNAL_SIDE_EFFECT, DESTRUCTIVE, CREDENTIAL_USE, FINANCIAL, PRIVACY_SENSITIVE,
    EXECUTES_CODE, NETWORK_EGRESS, MODIFIES_SECURITY, USER_VISIBLE_TO_THIRD_PARTY,
}

/** Output of a capability-specific risk classifier (e.g. the UI-action classifier, §9.2). */
data class RiskAssessment(
    val risk: Risk,
    val reasons: List<String> = emptyList(),
    val deny: Boolean = false,
    val denyReason: String? = null,
    /** Human description of the resolved target (shown on the approval screen). */
    val targetDescription: String? = null,
)

@Serializable
data class PolicyDecision(
    val capability: String,
    val baseRisk: Risk,
    val effectiveRisk: Risk,
    val requirement: Requirement,
    val reasons: List<String>,
    val tainted: Boolean,
    val destination: String? = null,
    val grantUsed: Boolean = false,
    val targetDescription: String? = null,
    val grantId: String? = null,
)
