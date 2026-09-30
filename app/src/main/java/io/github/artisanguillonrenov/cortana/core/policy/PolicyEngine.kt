package io.github.artisanguillonrenov.cortana.core.policy

import android.content.Context
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.tools.PolicyContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.util.CLog
import kotlinx.serialization.json.JsonObject

/** Bundled UI-risk patterns, overridable by the owner in Settings (versioned). */
class UiPatternsStore(context: Context, private val settings: SettingsRepository) {
    val bundledJson: String = context.assets.open("configs/ui_risk_patterns.json").bufferedReader().use { it.readText() }
    private val bundled: UiRiskPatterns = UiRiskPatterns.parse(bundledJson)
    @Volatile private var cachedOverride: Pair<String, UiRiskPatterns>? = null

    fun current(): UiRiskPatterns {
        val o = settings.current.uiRiskPatternsJson ?: return bundled
        cachedOverride?.let { if (it.first == o) return it.second }
        return runCatching { UiRiskPatterns.parse(o) }.onFailure { CLog.w("invalid UI risk override, using bundled", it) }
            .getOrDefault(bundled).also { cachedOverride = o to it }
    }

    fun validate(json: String): String? = runCatching { UiRiskPatterns.parse(json); null }.getOrElse { it.message ?: "JSON invalide" }
}

/**
 * §9 — authorizes every side-effecting tool call before it runs.
 * effectiveRisk = max(baseRisk, classifier, context, taint); never lowered below baseRisk.
 */
class PolicyEngine(
    private val settings: SettingsRepository,
    private val killSwitch: KillSwitch,
    private val grants: GrantService? = null,
) {
    suspend fun evaluate(def: ToolDefinition, args: JsonObject, ctx: PolicyContext): PolicyDecision {
        val s = settings.current
        val reasons = mutableListOf<String>()
        if (killSwitch.isHalted() && !(def.allowWhenHalted && ctx.ownerDirect)) {
            return PolicyDecision(def.capability, def.baseRisk, def.baseRisk, Requirement.DENY, listOf("Autonomie arrêtée (STOP activé)"), ctx.tainted)
        }
        val maxCalls = ctx.maxToolCalls ?: s.maxToolCallsPerTask
        if (ctx.toolCallsSoFar >= maxCalls) {
            return PolicyDecision(def.capability, def.baseRisk, def.baseRisk, Requirement.DENY, listOf("Quota d'appels d'outils atteint pour cette tâche"), ctx.tainted)
        }
        var risk = def.baseRisk
        var target: String? = null
        // Capability traits can only raise risk (§9.1: never lowered below base risk).
        val t = def.traits
        if (Trait.FINANCIAL in t || Trait.MODIFIES_SECURITY in t || Trait.CREDENTIAL_USE in t) {
            risk = Risk.max(risk, Risk.L3); reasons += "Capacité financière/sécurité/identifiants"
        }
        if (Trait.USER_VISIBLE_TO_THIRD_PARTY in t) { risk = Risk.max(risk, Risk.L2); reasons += "Visible par un tiers" }
        if (Trait.EXECUTES_CODE in t) { risk = Risk.max(risk, Risk.L2); reasons += "Exécute du code" }
        if (Trait.PRIVACY_SENSITIVE in t && ctx.tainted) { risk = Risk.max(risk, Risk.L2); reasons += "Données personnelles dans une tâche influencée par du contenu externe" }
        val assessment = runCatching { def.riskClassifier?.invoke(args, ctx) }.getOrElse {
            // A classifier failure never lowers risk: treat as sensitive.
            RiskAssessment(Risk.L3, listOf("Classification impossible (${it.message})"))
        }
        if (assessment != null) {
            if (assessment.deny) {
                return PolicyDecision(
                    def.capability, def.baseRisk, Risk.max(risk, assessment.risk), Requirement.DENY,
                    listOfNotNull(assessment.denyReason) + assessment.reasons, ctx.tainted, targetDescription = assessment.targetDescription,
                )
            }
            risk = Risk.max(risk, assessment.risk)
            reasons += assessment.reasons
            target = assessment.targetDescription
        }
        val destination = runCatching { def.destinationResolver?.invoke(args, ctx) ?: def.destinationOf?.invoke(args) }.getOrNull()
        var taintEscalated = false
        val known = destination != null && s.knownDestinations.any { it.equals(destination, ignoreCase = true) }
        // Network egress policy (phase 32): blocked hosts never, then the owner's mode for new destinations.
        if (def.dataEgress == DataEgress.EXTERNAL) {
            if (destination != null && EgressRules.blocked(destination, s.egressBlockedHosts)) {
                return PolicyDecision(def.capability, def.baseRisk, risk, Requirement.DENY, listOf("Destination bloquée par la politique réseau : $destination"), ctx.tainted, destination)
            }
            when (s.egressMode) {
                EgressRules.KNOWN_ONLY -> if (!known) return PolicyDecision(def.capability, def.baseRisk, risk, Requirement.DENY,
                    listOf("Destination non autorisée (mode « destinations connues seulement ») : ${destination ?: "inconnue"}. Ajoutez-la dans Réglages → Réseau."), ctx.tainted, destination)
                EgressRules.CONFIRM_NEW -> if (!known) {
                    risk = Risk.max(risk, Risk.L2); taintEscalated = true
                    reasons += "Destination nouvelle (mode confirmation)" + (destination?.let { " : $it" } ?: "")
                }
            }
        }
        // Content read by this task tried to instruct the assistant: no standing grant, every sensitive action confirmed.
        val injected = ctx.taintSources.any { it.startsWith("injection:") }
        if (injected && (Trait.EXECUTES_CODE in t || def.dataEgress == DataEgress.EXTERNAL || def.sideEffect == SideEffect.EXTERNAL || def.sideEffect == SideEffect.IRREVERSIBLE)) {
            risk = Risk.max(risk, Risk.L2); taintEscalated = true
            reasons += "Un contenu lu par cette tâche tente de donner des instructions : chaque action sensible est confirmée"
        }
        if (ctx.tainted && def.dataEgress == DataEgress.EXTERNAL) {
            if (!known) {
                risk = Risk.max(risk, Risk.L2)
                taintEscalated = true
                reasons += "Tâche influencée par du contenu non fiable (${ctx.taintSources.distinct().joinToString()}) et destination nouvelle" +
                    (destination?.let { " : $it" } ?: "")
            }
        }
        var grantId: String? = null
        val requirement = when (risk) {
            Risk.L0, Risk.L1 -> Requirement.ALLOW
            Risk.L2 -> {
                grantId = if (!taintEscalated) grants?.consume(def.capability, destination, args, ctx.taskId, ctx.scheduleId) else null
                if (grantId != null) Requirement.ALLOW else Requirement.CONFIRM
            }
            Risk.L3 -> Requirement.BIOMETRIC
        }
        return PolicyDecision(def.capability, def.baseRisk, risk, requirement, reasons, ctx.tainted, destination, grantId != null, target, grantId)
    }
}

/** Network egress rules shared by the policy (per capability) and the HTTP guard (every request). */
object EgressRules {
    const val STANDARD = "standard"
    const val CONFIRM_NEW = "confirm_new"
    const val KNOWN_ONLY = "known_only"
    val MODES = listOf(STANDARD, CONFIRM_NEW, KNOWN_ONLY)

    fun normalize(host: String): String = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("*.").trim('.')

    /** [host] is blocked when it is, or is under, one of [blocked] (suffix match on labels). */
    fun blocked(host: String, blocked: List<String>): Boolean {
        val h = normalize(host)
        return blocked.map(::normalize).filter { it.isNotEmpty() }.any { b -> h == b || h.endsWith(".$b") }
    }
}

/** Defence in depth: no request of any kind (tools, providers, connectors, workers) reaches a blocked host. */
class EgressGuard(private val blockedHosts: () -> List<String>) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val host = chain.request().url.host
        if (EgressRules.blocked(host, blockedHosts())) throw java.io.IOException("Destination bloquée par la politique réseau : $host")
        return chain.proceed(chain.request())
    }
}
