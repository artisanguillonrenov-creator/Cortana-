package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.contracts.PrivacyLevel
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.core.model.ModelRoute
import io.github.artisanguillonrenov.cortana.core.model.RouteNeed
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.CapabilityMatcher
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import kotlin.math.ceil

class CouncilPlanException(message: String) : Exception(message)

/**
 * Context Budget Manager (doc 05 §5.4): before every call, the input must fit
 * min(model context, provider request limit learned from a 413) − reserved output − safety margin,
 * and the role's own input cap.
 */
object ContextBudget {
    fun margin(window: Int) = maxOf(256, window / 20)
    fun allowedInput(contextWindow: Int, roleMaxInput: Int?, reservedOutput: Int, learnedLimit: Int? = null): Int {
        val window = listOfNotNull(contextWindow, learnedLimit).min()
        return minOf(window - reservedOutput - margin(window), roleMaxInput ?: Int.MAX_VALUE).coerceAtLeast(0)
    }

    /** A 413 on a request of [input] + [reservedOutput] tokens: the provider limit is below it; aim 30 % lower. */
    fun learnedAfter413(input: Int, reservedOutput: Int): Int = ((input + reservedOutput) * 7 / 10).coerceAtLeast(1_024)
}

/** Run-level budget (doc 05 §5.2): tokens, cost, calls, time; thread-safe, never exceeded. */
class RunBudgetGuard(
    val budget: CouncilBudgetConfig,
    private val remainingModelCalls: Int,
    private val remainingToolCalls: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : CouncilBudgetGuard {
    val startedAt = clock()
    private var nextId = 0L
    private var reservedTokens = 0
    private var usedTokens = 0
    var inputTokens = 0; private set
    var outputTokens = 0; private set
    var costMicros: Long? = null; private set
    var providerCalls = 0; private set
    var toolCalls = 0; private set
    private val maxCalls = minOf(budget.maxProviderCalls, remainingModelCalls)
    private val maxTools = minOf(budget.maxToolCalls, remainingToolCalls)
    /** Tokens and calls held back for the synthesis (and verification) so the rounds cannot starve them. */
    var reserveForFinal = 0

    fun deadline() = startedAt + budget.maxWallTimeMs
    fun timeLeft() = deadline() - clock()

    @Synchronized override fun reserve(call: PlannedModelCall): BudgetReservation {
        val id = ++nextId
        val tokens = call.inputTokens + call.outputTokens
        val reason = when {
            timeLeft() <= 0 -> "délai du conseil atteint"
            providerCalls >= maxCalls -> "appels au modèle épuisés ($maxCalls)"
            usedTokens + reservedTokens + tokens > budget.maxTotalTokens -> "jetons épuisés (${budget.maxTotalTokens})"
            budget.maxCostMicros != null && (costMicros ?: 0) >= budget.maxCostMicros -> "coût maximal atteint"
            else -> null
        }
        if (reason != null) return BudgetReservation(id, call, false, reason)
        reservedTokens += tokens
        providerCalls++
        return BudgetReservation(id, call, true)
    }

    /** A final call (synthesis, verification) may use the reserve kept for it. */
    @Synchronized fun reserveFinal(call: PlannedModelCall): BudgetReservation {
        val id = ++nextId
        val tokens = call.inputTokens + call.outputTokens
        if (providerCalls >= maxCalls + FINAL_CALLS || usedTokens + reservedTokens + tokens > budget.maxTotalTokens + reserveForFinal) return BudgetReservation(id, call, false, "réserve finale épuisée")
        reservedTokens += tokens
        providerCalls++
        return BudgetReservation(id, call, true)
    }

    @Synchronized override fun commit(reservation: BudgetReservation, inputTokens: Int, outputTokens: Int, costMicros: Long?) {
        if (!reservation.granted) return
        reservedTokens -= reservation.call.inputTokens + reservation.call.outputTokens
        usedTokens += inputTokens + outputTokens
        this.inputTokens += inputTokens
        this.outputTokens += outputTokens
        if (costMicros != null) this.costMicros = (this.costMicros ?: 0) + costMicros
    }

    @Synchronized fun tryToolCall(): Boolean = if (toolCalls >= maxTools) false else { toolCalls++; true }

    @Synchronized override fun canContinue(projectedTokens: Int): Boolean =
        timeLeft() > 0 && usedTokens + reservedTokens + projectedTokens + reserveForFinal <= budget.maxTotalTokens && providerCalls < maxCalls

    @Synchronized fun remainingTokens() = budget.maxTotalTokens - usedTokens - reservedTokens
    @Synchronized fun remainingCalls() = maxCalls - providerCalls
    val totalTokens get() = inputTokens + outputTokens

    companion object { const val FINAL_CALLS = 2 }
}

/**
 * CouncilPlanner (doc 13 §13.1): preset → slots (profile, model route and fallbacks, read-only tool
 * scope, budgets), quorum, protocol, rounds; then projection against the budget and degradation in
 * the documented order (rounds, arguments, output, judge, 4 → 2, single agent).
 */
class CouncilPlanner(
    private val profiles: DefaultCouncilProfileRegistry,
    private val gateway: ModelGateway,
    private val registry: ToolRegistry,
    private val matcher: CapabilityMatcher,
) {
    fun presetFor(mode: CouncilMode, presetId: String?, config: CouncilConfig, prefs: CouncilPrefs): CouncilPreset {
        val base = when (mode) {
            CouncilMode.FAST -> profiles.preset("fast")!!
            CouncilMode.REINFORCED -> profiles.preset("eco")!!
            CouncilMode.DEEP -> profiles.preset("deep")!!
            CouncilMode.COUNCIL_4, CouncilMode.AUTO -> presetId?.let { profiles.preset(it) } ?: profiles.preset("balanced")!!
            CouncilMode.CUSTOM -> {
                val agents = config.roles.map { it.profileId }.filter { profiles.get(it)?.kind == ProfileKind.AGENT }.distinct()
                    .ifEmpty { listOf("strategist", "evidence_analyst", "solution_engineer", "challenger") }.take(config.maxAgents)
                CouncilPreset("custom", "Personnalisé", agents, config.maxRounds, config.topology, config.decisionProtocol, config.challengeFinal, prefs.judge)
            }
            CouncilMode.OFF -> throw CouncilPlanException("conseil désactivé")
        }
        // The owner's budget profile adjusts the preset (doc 07 §7.2).
        return when (prefs.budgetProfile) {
            BudgetProfile.ECO -> base.copy(rounds = minOf(base.rounds, 1), judge = if (base.judge == JudgeSetting.ON) JudgeSetting.AUTO else JudgeSetting.OFF)
            BudgetProfile.QUALITY -> base.copy(rounds = minOf(maxOf(base.rounds, 1) + if (mode == CouncilMode.FAST || mode == CouncilMode.REINFORCED) 0 else 1, 3))
            else -> base
        }
    }

    fun budgetFor(req: CouncilRunRequest, preset: CouncilPreset, prefs: CouncilPrefs): CouncilBudgetConfig {
        val b = req.budget
        val tokens = when (prefs.budgetProfile) { BudgetProfile.ECO -> minOf(b.maxTotalTokens, 30_000); BudgetProfile.QUALITY -> b.maxTotalTokens * 2; else -> b.maxTotalTokens }
        return b.copy(
            maxTotalTokens = tokens,
            maxProviderCalls = minOf(b.maxProviderCalls, req.remainingModelCalls),
            maxToolCalls = minOf(b.maxToolCalls, req.remainingToolCalls),
            maxConcurrentAgents = minOf(b.maxConcurrentAgents, preset.maxConcurrent).coerceAtLeast(1),
        )
    }

    suspend fun plan(runId: String, req: CouncilRunRequest, config: CouncilConfig, prefs: CouncilPrefs, learnedLimits: Map<String, Int> = emptyMap()): CouncilPlan {
        var preset = presetFor(req.mode, req.presetId, config, prefs)
        val budget = budgetFor(req, preset, prefs)
        val degradations = mutableListOf<String>()
        // Battery-aware Auto (doc 08 §8.17).
        if (req.mode == CouncilMode.AUTO && req.batteryPercent != null && prefs.lowBatteryPercent > 0 && req.batteryPercent < prefs.lowBatteryPercent && preset.agents.size > 2) {
            preset = preset.copy(agents = twoOf(preset.agents), rounds = 0)
            degradations += "batterie faible : 2 spécialistes, sans tour de confrontation"
        }
        val main = req.mainRoute
        val pool = readOnlyPool(req.toolPool)
        val slots = preset.agents.mapIndexedNotNull { i, id -> profiles.get(id)?.let { slot(runId, i, it, req, config, prefs, pool, main, learnedLimits) } }
        var usable = slots.filter { it.route != null }
        if (usable.isEmpty()) throw CouncilPlanException("aucun modèle disponible pour le conseil")
        // Diversity when one model serves several roles (doc 13 §13.8): role, context order and sampling.
        val sameModel = usable.groupBy { "${it.route!!.providerId}/${it.route.modelId}" }
        usable = usable.map { s ->
            val peers = sameModel["${s.route!!.providerId}/${s.route.modelId}"]!!
            if (peers.size > 1) s.copy(temperature = TEMPERATURES[peers.indexOf(s) % TEMPERATURES.size]) else s
        }
        var rounds = preset.rounds.coerceIn(0, 3)
        if (preset.topology == CouncilTopology.INDEPENDENT) rounds = 0
        var retention = config.retention.copy(enabled = preset.retention && config.retention.enabled, maxArgumentsPerTarget = if (req.mode == CouncilMode.CUSTOM) config.retention.maxArgumentsPerTarget else preset.maxArgumentsPerTarget)
        var judge = when (prefs.judge) { JudgeSetting.OFF -> false; JudgeSetting.ON -> true; JudgeSetting.AUTO -> preset.judge != JudgeSetting.OFF } ||
            preset.protocol == DecisionProtocol.JUDGE || preset.protocol == DecisionProtocol.BLIND_JUDGE_THEN_VOTE
        var challenge = preset.challenge && usable.any { it.profileId.contains("challenger") || it.profileId == "skeptic" || it.profileId == "audience_challenger" }
        var outputScale = 1.0

        fun projection(slots: List<CouncilAgentSlot>): Pair<Int, Int> {
            val perCall = slots.sumOf { estimateInput(it, req) + (it.budget.maxOutputTokens * outputScale).toInt() }
            val retained = if (rounds > 0) slots.size * retention.maxTokensPerTarget else 0
            val finalCalls = 2 + (if (judge) 1 else 0) + (if (challenge) 1 else 0)
            val tokens = perCall * (1 + rounds) + retained * rounds + finalCalls * 3_500
            val calls = slots.size * (1 + rounds) + slots.sumOf { it.budget.maxToolCalls.coerceAtMost(1) } + finalCalls
            return tokens to calls
        }
        val maxCalls = budget.maxProviderCalls
        // A mode the owner did not choose explicitly stays under the warning threshold, and a DEEP run under its
        // confirmation threshold (doc 07 §7.5, doc 16): reduced, and the owner is told, instead of spending more.
        val softCap = if (req.explicitMode) Int.MAX_VALUE else minOf(prefs.warnAboveTokens.takeIf { it > 0 } ?: Int.MAX_VALUE, preset.confirmAboveTokens ?: Int.MAX_VALUE)
        val tokenCap = minOf(budget.maxTotalTokens, softCap)
        var p = projection(usable)
        if (p.first > tokenCap && tokenCap < budget.maxTotalTokens) degradations += "conseil réduit pour rester sous $tokenCap jetons (seuil d'avertissement)"
        // Degradation order (doc 05 §5.3, doc 15 §15.11).
        while ((p.first > tokenCap || p.second > maxCalls) && degradations.size < 9) {
            when {
                rounds > 0 -> { rounds--; degradations += "tours de confrontation réduits à $rounds" }
                retention.enabled && retention.maxArgumentsPerTarget > 3 -> { retention = retention.copy(maxArgumentsPerTarget = retention.maxArgumentsPerTarget * 3 / 4, maxTokensPerTarget = retention.maxTokensPerTarget * 3 / 4); degradations += "arguments retenus réduits" }
                outputScale > 0.8 -> { outputScale = 0.8; degradations += "sorties réduites de 20 %" }
                judge -> { judge = false; degradations += "juge supprimé" }
                usable.size > 2 -> { usable = keepTwo(usable); challenge = challenge && usable.any { it.profileId.contains("challenger") }; degradations += "conseil réduit à 2 spécialistes" }
                usable.size > 1 -> { usable = usable.take(1); challenge = false; degradations += "un seul spécialiste" }
                else -> break
            }
            p = projection(usable)
        }
        if (p.second > maxCalls && usable.size == 1 && maxCalls < 3) throw CouncilPlanException("budget d'appels insuffisant pour un conseil ($maxCalls)")
        if (outputScale < 1.0) usable = usable.map { it.copy(budget = it.budget.copy(maxOutputTokens = (it.budget.maxOutputTokens * outputScale).toInt())) }
        val quorum = quorum(usable.size, config.quorumRatio)
        val topology = if (req.mode == CouncilMode.CUSTOM) config.topology else preset.topology
        val protocol = (if (req.mode == CouncilMode.CUSTOM && config.decisionProtocol != DecisionProtocol.AUTO) config.decisionProtocol else preset.protocol)
            .let { if (it == DecisionProtocol.AUTO) DecisionProtocol.HYBRID else it }
        val judgeRoute = if (judge) (prefs.judgeRoute?.let { gateway.routeFor(it) }?.takeIf { !req.privacy.localOnly || it.local } ?: distinctRoute(usable, main)) else null
        val synthesis = prefs.synthesisRoute?.let { gateway.routeFor(it) }?.takeIf { !req.privacy.localOnly || it.local } ?: main ?: usable.first().route
        return CouncilPlan(runId, preset.id, req.mode, usable, topology, protocol, rounds, quorum, challenge, judge, judgeRoute, synthesis,
            budget, retention, earlyStop = prefs.earlyStop, degradations = degradations)
    }

    /** Doc 01 §1.7: 1 → 1, 2 → 2, 4 → 3, N → ceil(ratio·N). */
    fun quorum(n: Int, ratio: Double): Int = when (n) { 0 -> 0; 1 -> 1; 2 -> 2; 4 -> 3; else -> ceil(ratio.coerceIn(0.5, 1.0) * n).toInt().coerceIn(1, n) }

    /** Read-only tools only (doc 05 §5.7): no side effect, at most L1, no screen control, no owner prompt, no memory write. */
    fun readOnlyPool(capabilities: Set<String>): List<ToolDefinition> = registry.all().filter { d ->
        d.capability in capabilities && d.sideEffect == SideEffect.NONE && d.baseRisk.ordinal <= Risk.L1.ordinal &&
            d.category != ToolCategory.UI && d.capability !in EXCLUDED
    }

    private suspend fun slot(runId: String, index: Int, profile: CouncilAgentProfile, req: CouncilRunRequest, config: CouncilConfig, prefs: CouncilPrefs,
                             pool: List<ToolDefinition>, main: ModelRoute?, learned: Map<String, Int>): CouncilAgentSlot {
        val role = config.roles.firstOrNull { it.profileId == profile.id }
        val localOnly = req.privacy.localOnly || role?.localOnly == true
        fun allowed(r: ModelRoute?) = r != null && (!localOnly || r.local)
        var note: String? = null
        val preferred: ModelRoute? = when (prefs.routing) {
            ModelRouting.SAME_MODEL -> main
            ModelRouting.PER_ROLE -> roleRoute(role) ?: main
            ModelRouting.AUTO_CAPABILITY -> capabilityRoute(profile, req) ?: main
            ModelRouting.HYBRID -> roleRoute(role) ?: capabilityRoute(profile, req) ?: main
        }
        val fallbacks = buildList {
            role?.fallbackModelIds?.forEach { f -> (if ('/' in f) gateway.routeFor(f) else role.providerId?.let { gateway.routeFor("$it/$f") })?.let { add(it) } }
            main?.let { add(it) }
        }.filter { allowed(it) }.distinctBy { "${it.providerId}/${it.modelId}" }
        val route = when {
            allowed(preferred) -> preferred
            preferred != null -> { note = "modèle préféré ${preferred.modelId} refusé (confidentialité locale)"; fallbacks.firstOrNull() }
            else -> { note = "modèle préféré indisponible"; fallbacks.firstOrNull() }
        }
        val caps = route?.let { gateway.capabilities.resolve(it.providerId, it.modelId) }
        val window = caps?.contextWindow ?: 8_192
        val output = role?.maxOutputTokens ?: profile.maxOutputTokens
        val maxInput = ContextBudget.allowedInput(window, role?.maxInputTokens, output, route?.let { learned["${it.providerId}/${it.modelId}"] })
        val tools = if (profile.toolCategories.isEmpty() || profile.maxToolCalls == 0) emptyList() else {
            val scoped = pool.filter { it.category in profile.toolCategories || it.capability in ALWAYS }
            matcher.select(scoped, req.taskBrief.userGoal, profile.toolCategories, emptyList(), emptySet(), PlanStrategy.INTERACTIVE, MAX_TOOLS)
                .offered.filter { it.capability in scoped.map { d -> d.capability }.toSet() }.map { it.capability }
        }
        val toolCalls = if (tools.isEmpty()) 0 else profile.maxToolCalls
        return CouncilAgentSlot(
            id = "$runId-s$index", profileId = profile.id, route = route, fallbacks = fallbacks.filter { it != route },
            requiredCapabilities = profile.capabilities, toolScope = tools, required = true,
            // Model calls: the answer, one per tool round, and RECOVERY_CALLS after a 413/429/5xx (the run guard still caps the total).
            budget = AgentBudget(maxInput, output, role?.timeoutMs ?: if (req.mode == CouncilMode.DEEP) 150_000 else 90_000, toolCalls, 1 + toolCalls + RECOVERY_CALLS),
            routeNote = note,
            reasoningEffort = role?.reasoningEffort?.takeIf { it != ReasoningEffort.AUTO }?.name?.lowercase(),
        )
    }

    private suspend fun roleRoute(role: RoleModelConfig?): ModelRoute? =
        if (role?.providerId != null && role.modelId != null) gateway.routeFor("${role.providerId}/${role.modelId}") else null

    /** AUTO_CAPABILITY: the coding route for code roles; otherwise the main route. */
    private suspend fun capabilityRoute(profile: CouncilAgentProfile, req: CouncilRunRequest): ModelRoute? =
        if (ModelCapability.CODING in profile.capabilities || profile.toolCategories.contains(ToolCategory.DEV))
            gateway.resolveRoute(null, RouteNeed(privacy = if (req.privacy.localOnly) PrivacyLevel.LOCAL_ONLY else PrivacyLevel.NORMAL, coding = true))
        else null

    /** The judge prefers a model none of the agents used (doc 13 §13.9). */
    private fun distinctRoute(slots: List<CouncilAgentSlot>, main: ModelRoute?): ModelRoute? {
        val used = slots.mapNotNull { it.route }.map { "${it.providerId}/${it.modelId}" }.toSet()
        return slots.flatMap { it.fallbacks }.firstOrNull { "${it.providerId}/${it.modelId}" !in used } ?: main
    }

    fun estimateInput(slot: CouncilAgentSlot, req: CouncilRunRequest): Int =
        CouncilPrompts.BASE_TOKENS + io.github.artisanguillonrenov.cortana.core.context.Tokens.estimate(req.taskBrief.toString()) + slot.toolScope.size * 180

    private fun twoOf(agents: List<String>): List<String> {
        val challenger = agents.firstOrNull { it.contains("challenger") || it == "skeptic" }
        val lead = agents.firstOrNull { it == "solution_engineer" } ?: agents.first()
        return listOfNotNull(lead, challenger).distinct().ifEmpty { agents.take(2) }.let { if (it.size < 2) agents.take(2) else it }
    }

    private fun keepTwo(slots: List<CouncilAgentSlot>): List<CouncilAgentSlot> {
        val ids = twoOf(slots.map { it.profileId })
        return slots.filter { it.profileId in ids }.ifEmpty { slots.take(2) }
    }

    companion object {
        const val MAX_TOOLS = 10
        const val RECOVERY_CALLS = 2
        val TEMPERATURES = listOf(0.3, 0.55, 0.8, 1.0)
        /** Owner prompts, memory writes and control tools are never offered to a council agent. */
        val EXCLUDED = setOf("ask_user", "memory.save", "memory.forget", "notify.owner", "skill.run")
        val ALWAYS = setOf(CapabilityMatcher.DISCOVER, "memory.search")
    }
}
