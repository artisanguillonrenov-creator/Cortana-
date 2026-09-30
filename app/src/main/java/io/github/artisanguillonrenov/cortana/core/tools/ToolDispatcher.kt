package io.github.artisanguillonrenov.cortana.core.tools

import io.github.artisanguillonrenov.cortana.core.memory.ApprovalEntity
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.IdempotencyEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.memory.StepEntity
import io.github.artisanguillonrenov.cortana.core.memory.ToolCallEntity
import io.github.artisanguillonrenov.cortana.core.model.ToolCall
import io.github.artisanguillonrenov.cortana.core.observability.Tracer
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalBroker
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.GrantService
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.KillSwitch
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.PolicyEngine
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.PrettyJson
import io.github.artisanguillonrenov.cortana.util.Redactor
import io.github.artisanguillonrenov.cortana.util.canonicalJson
import io.github.artisanguillonrenov.cortana.util.truncateBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class DispatchRequest(
    val taskId: String,
    val sessionId: String,
    val call: ToolCall,
    /** Capabilities offered to the model for this step; anything else is refused. */
    val allowed: Set<String>,
    val callIndex: Int,
    val tainted: Boolean,
    val taintSources: List<String>,
    val maxToolCalls: Int,
    val ctxFactory: (Risk) -> ToolContext,
    val planStepId: String? = null,
    val ownerDirect: Boolean = false,
    val scheduleId: String? = null,
    /** False for council agents (D-20260929-067): a call needing the owner's approval is refused, never asked. */
    val interactive: Boolean = true,
)

data class DispatchOutcome(
    val result: ToolResult,
    val def: ToolDefinition?,
    val decision: PolicyDecision? = null,
    val cancelTask: Boolean = false,
    val duplicate: Boolean = false,
    /** The side effect may or may not have happened (crash between run and commit) → ask the owner. */
    val uncertain: Boolean = false,
    val idempotencyKey: String? = null,
)

/** Orchestrator callbacks: state transitions stay the orchestrator's job (LAW-002). */
interface DispatchHooks {
    fun status(text: String) {}
    /** true before the owner is asked, false once decided; a cancellation in between leaves it true (the action never ran). */
    suspend fun awaitingApproval(waiting: Boolean) {}
    /** Called before an L3 action and after a successful external side effect (checkpoint points, doc 04 §7). */
    suspend fun checkpoint(reason: String) {}
}

/**
 * LAW-003/004 — the single path from a model (or fast-path) tool call to an executor:
 * registry resolution → schema validation → takeover check → policy → owner approval →
 * idempotency ledger (`started` before, `succeeded`/`failed` after) → timeout → audit → records.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val policy: PolicyEngine,
    private val approvals: ApprovalBroker,
    private val grants: GrantService,
    private val killSwitch: KillSwitch,
    private val audit: AuditLog,
    private val settings: SettingsRepository,
    private val db: CortanaDatabase,
    private val tracer: Tracer,
) {
    private val tasks get() = db.tasks()
    private val runtime get() = db.runtime()

    suspend fun dispatch(req: DispatchRequest, hooks: DispatchHooks = object : DispatchHooks {}): DispatchOutcome {
        val call = req.call
        val def = registry.resolve(call.name)
        if (def == null || def.capability !in req.allowed) {
            return DispatchOutcome(ToolResult.error("Outil inconnu ou indisponible pour cette étape : ${call.name}. Utilise tools_discover pour trouver un outil adapté."), null)
        }
        return tracer.span("tool.execute", req.taskId, mapOf("tool" to def.capability)) { span ->
            hooks.status("${def.label}…")
            val stepId = Ids.new()
            val args: JsonObject = runCatching { AppJson.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject }.getOrElse {
                span.error("validation")
                return@span DispatchOutcome(ToolResult.error("Arguments JSON invalides : ${it.message}. Renvoie un objet JSON valide."), def)
            }
            val schemaErrors = SchemaValidator.validate(def.inputSchema, args)
            if (schemaErrors.isNotEmpty()) {
                span.error("validation")
                return@span DispatchOutcome(ToolResult.error("Arguments invalides pour ${def.functionName} : ${schemaErrors.joinToString("; ")}"), def)
            }
            tasks.upsertStep(StepEntity(stepId, req.taskId, if (def.capability == "ask_user") "ask_user" else "action", "running", def.capability, Redactor.redact(args.toString()), System.currentTimeMillis()))

            // User takeover (§10): the owner touched the screen during automation → ask before continuing.
            if (def.category == ToolCategory.UI && AccessibilityBridge.takeover.value) {
                hooks.awaitingApproval(true)
                val d = approvals.request(
                    ApprovalRequest(
                        capability = "automation.continue", action = "Vous avez touché l'écran pendant l'automatisation. Cortana doit-elle continuer ?",
                        target = AccessibilityBridge.foregroundPackage, params = "Prochaine action : ${def.label}", risk = Risk.L2, reversible = true,
                        tainted = req.tainted, taintSources = req.taintSources.distinct(), reasons = listOf("Reprise en main détectée"), biometric = false,
                        allowGrant = false, destination = null, bindingHash = Hash.sha256("takeover${req.taskId}${req.callIndex}"),
                    )
                )
                hooks.awaitingApproval(false)
                AccessibilityBridge.takeover.value = false
                if (!d.approved) {
                    return@span finish(req, stepId, def, args, null, "refused", ToolResult.error("Automatisation interrompue : le propriétaire a repris la main."), cancel = true)
                }
            }

            val pctx = PolicyContext(
                req.taskId, req.tainted, req.taintSources.distinct(), req.callIndex - 1, ownerDirect = req.ownerDirect,
                sessionId = req.sessionId, scheduleId = req.scheduleId, maxToolCalls = req.maxToolCalls,
            )
            val decision = policy.evaluate(def, args, pctx)
            span.attr("risk", decision.effectiveRisk.name)
            if (decision.requirement == Requirement.DENY) {
                audit.record("cortana", def.capability, decision.targetDescription ?: decision.destination, "denied", decisionMeta(decision))
                span.error("policy")
                return@span finish(req, stepId, def, args, decision, "denied", ToolResult.error("Refusé par la politique : ${decision.reasons.joinToString("; ")}"))
            }
            if ((decision.requirement == Requirement.CONFIRM || decision.requirement == Requirement.BIOMETRIC) && !req.interactive) {
                audit.record("cortana", def.capability, decision.targetDescription ?: decision.destination, "needs_owner", decisionMeta(decision))
                span.error("policy")
                return@span finish(req, stepId, def, args, decision, "denied",
                    ToolResult.error("Non exécuté : cette action demande l'accord du propriétaire, qu'un agent du conseil ne sollicite jamais. Propose-la dans ta contribution."))
            }
            if (decision.requirement == Requirement.CONFIRM || decision.requirement == Requirement.BIOMETRIC) {
                if (decision.requirement == Requirement.BIOMETRIC) hooks.checkpoint("before_l3:${def.capability}")
                hooks.status(if (decision.requirement == Requirement.BIOMETRIC) "En attente de votre empreinte…" else "En attente de votre confirmation…")
                val approvalReq = ApprovalRequest(
                    capability = def.capability,
                    action = def.label,
                    target = decision.targetDescription ?: decision.destination,
                    params = Redactor.redact(PrettyJson.encodeToString(JsonObject.serializer(), args)).truncateBytes(1500),
                    risk = decision.effectiveRisk,
                    reversible = def.sideEffect == SideEffect.NONE || def.sideEffect == SideEffect.REVERSIBLE,
                    tainted = req.tainted,
                    taintSources = req.taintSources.distinct(),
                    reasons = decision.reasons,
                    biometric = decision.requirement == Requirement.BIOMETRIC,
                    allowGrant = decision.effectiveRisk == Risk.L2 && !req.tainted,
                    destination = decision.destination,
                    bindingHash = Hash.sha256(def.capability + canonicalJson(args)),
                )
                val now = System.currentTimeMillis()
                runtime.upsertApproval(
                    ApprovalEntity(approvalReq.id, req.taskId, def.capability, approvalReq.bindingHash, decision.effectiveRisk.name, "pending", approvalDetails(approvalReq), now)
                )
                hooks.awaitingApproval(true)
                val d = try {
                    approvals.request(approvalReq)
                } catch (e: CancellationException) {
                    // STOP or pause while waiting: the request is closed in the durable record, never left pending.
                    val outcome = if (e.message.orEmpty().contains(io.github.artisanguillonrenov.cortana.core.policy.OWNER_REFUSAL)) "refused" else "cancelled"
                    withContext(NonCancellable) {
                        runCatching {
                            runtime.upsertApproval(ApprovalEntity(approvalReq.id, req.taskId, def.capability, approvalReq.bindingHash, decision.effectiveRisk.name,
                                outcome, approvalDetails(approvalReq), now, System.currentTimeMillis()))
                        }
                        runCatching { audit.record("owner", "approval." + def.capability, approvalReq.target, outcome, decisionMeta(decision, e.message?.substringAfter(':')?.trim() ?: "tâche arrêtée")) }
                    }
                    throw e
                }
                hooks.awaitingApproval(false)
                // The decision is recorded even when STOP cancels the task at this very moment.
                withContext(NonCancellable) {
                    runtime.upsertApproval(
                        ApprovalEntity(approvalReq.id, req.taskId, def.capability, approvalReq.bindingHash, decision.effectiveRisk.name,
                            if (d.approved) "approved" else if (d.reason == "délai dépassé") "expired" else "refused", approvalDetails(approvalReq), now, System.currentTimeMillis())
                    )
                    audit.record("owner", "approval." + def.capability, approvalReq.target, if (d.approved) "approved" else "refused", decisionMeta(decision, d.reason))
                }
                if (!d.approved) {
                    return@span finish(req, stepId, def, args, decision, "refused",
                        ToolResult.error("Le propriétaire a refusé cette action${d.reason?.let { " ($it)" } ?: ""}. Ne réessaie pas sans lui demander."))
                }
                if (d.rememberGrant && approvalReq.allowGrant) grants.create(def.capability, scope = decision.destination)
                val dest = decision.destination
                if (d.rememberDestination && dest != null) {
                    settings.update { it.copy(knownDestinations = (it.knownDestinations + dest).distinct()) }
                }
                if (killSwitch.isHalted() && !(def.allowWhenHalted && req.ownerDirect)) throw CancellationException("halt:approval")
            }

            // §66 — idempotency ledger for non-repeatable effects.
            val idemKey = if (def.idempotency == Idempotency.KEYED) req.taskId + ":" + Hash.sha256(def.capability + canonicalJson(args)) else null
            if (idemKey != null) {
                val prev = runtime.ledger(idemKey)
                when (prev?.status) {
                    "succeeded", "reconciled" -> return@span finish(req, stepId, def, args, decision, "skipped_duplicate",
                        ToolResult.ok("Déjà exécuté dans cette tâche (protection anti-répétition) : ${prev.outcomeRef ?: "ok"}"), idemKey = null, duplicate = true)
                    "started", "unknown" -> {
                        val verdict = runCatching { def.reconcile?.invoke(args, KeyedToolContext(req.ctxFactory(decision.effectiveRisk), idemKey)) }.getOrNull()
                        when (verdict) {
                            true -> {
                                runtime.upsertLedger(prev.copy(status = "reconciled", updatedAt = System.currentTimeMillis()))
                                return@span finish(req, stepId, def, args, decision, "skipped_duplicate",
                                    ToolResult.ok("Action déjà effectuée avant l'interruption (vérifié)."), idemKey = null, duplicate = true)
                            }
                            false -> Unit // verifiably not done: safe to execute
                            null -> {
                                runtime.upsertLedger(prev.copy(status = "unknown", updatedAt = System.currentTimeMillis()))
                                return@span DispatchOutcome(
                                    ToolResult.error("État incertain : « ${def.label} » a peut-être déjà été exécuté avant une interruption. Demande au propriétaire avant de recommencer."),
                                    def, decision, uncertain = true, idempotencyKey = idemKey,
                                )
                            }
                        }
                    }
                }
                val now = System.currentTimeMillis()
                runtime.upsertLedger(IdempotencyEntity(idemKey, req.taskId, def.capability, Hash.sha256(canonicalJson(args)), "started", createdAt = now, updatedAt = now,
                    reversible = def.sideEffect == SideEffect.REVERSIBLE))
            }
            hooks.status("${def.label}…")
            val result = try {
                withTimeout(def.timeoutMs) { def.invokeAuthorized(args, KeyedToolContext(req.ctxFactory(decision.effectiveRisk), idemKey), decision) }
            } catch (e: TimeoutCancellationException) {
                ToolResult.error("Délai dépassé (${def.timeoutMs / 1000} s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CLog.w("tool ${def.capability} failed", e)
                ToolResult.error("Erreur de l'outil : ${e.message ?: e.javaClass.simpleName}")
            }
            if (idemKey != null) {
                val now = System.currentTimeMillis()
                runtime.ledger(idemKey)?.let { runtime.upsertLedger(it.copy(status = if (result.ok) "succeeded" else "failed", outcomeRef = Redactor.redact(result.text).truncateBytes(500), updatedAt = now)) }
            }
            val usedDest = decision.destination
            if (result.ok && !req.tainted && usedDest != null && def.dataEgress == DataEgress.EXTERNAL) {
                // A destination used in an untainted task becomes known (§9.3).
                settings.update { it.copy(knownDestinations = (it.knownDestinations + usedDest).distinct().takeLast(500)) }
            }
            if (decision.effectiveRisk.level >= Risk.L2.level) {
                audit.record("cortana", def.capability, decision.targetDescription ?: decision.destination, if (result.ok) "ok" else "error", decisionMeta(decision))
            }
            if (result.ok && (def.sideEffect == SideEffect.EXTERNAL || def.sideEffect == SideEffect.IRREVERSIBLE)) hooks.checkpoint("after_effect:${def.capability}")
            if (!result.ok) span.error("executor")
            finish(req, stepId, def, args, decision, if (result.ok) "ok" else "error", result, idemKey = idemKey)
        }
    }

    private suspend fun finish(
        req: DispatchRequest, stepId: String, def: ToolDefinition, args: JsonObject, decision: PolicyDecision?,
        outcome: String, result: ToolResult, cancel: Boolean = false, idemKey: String? = null, duplicate: Boolean = false,
    ): DispatchOutcome {
        tasks.upsertStep(StepEntity(stepId, req.taskId, "action", outcome, def.capability, Redactor.redact(args.toString()), System.currentTimeMillis()))
        tasks.insertToolCall(
            ToolCallEntity(
                id = Ids.new(), taskId = req.taskId, stepId = req.planStepId ?: stepId, capability = def.capability,
                inputJson = Redactor.redact(args.toString()).truncateBytes(4000), idempotencyKey = idemKey,
                policyDecisionJson = decision?.let { AppJson.encodeToString(PolicyDecision.serializer(), it) } ?: "{}",
                outcome = outcome, outputRef = Redactor.redact(result.text).truncateBytes(2000), createdAt = System.currentTimeMillis(),
            )
        )
        return DispatchOutcome(result, def, decision, cancel, duplicate, idempotencyKey = idemKey)
    }

    private fun approvalDetails(r: ApprovalRequest): String = buildJsonObject {
        put("action", r.action)
        r.target?.let { put("target", Redactor.redact(it)) }
        put("reasons", r.reasons.joinToString("; "))
        put("tainted", r.tainted)
        put("biometric", r.biometric)
    }.toString()

    private fun decisionMeta(d: PolicyDecision, extra: String? = null): String = buildJsonObject {
        put("risk", d.effectiveRisk.name)
        put("requirement", d.requirement.name)
        put("tainted", d.tainted)
        put("reasons", d.reasons.joinToString("; "))
        d.grantId?.let { put("grant", it) }
        extra?.let { put("note", it) }
    }.toString()
}

private class KeyedToolContext(base: ToolContext, override val idempotencyKey: String?) : ToolContext by base
