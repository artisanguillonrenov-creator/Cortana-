package io.github.artisanguillonrenov.cortana.core.skills

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.obj
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonPrimitive

/**
 * `skill.run` only *prepares* a replay (checks and parameters); the StepRunner then executes every
 * step through the ToolDispatcher, so each step keeps its own policy, approval and audit.
 */
class SkillTools(private val skills: SkillService, private val registry: ToolRegistry) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "skill.run",
            "Rejoue une procédure apprise et validée par le propriétaire (voir « Procédures apprises »). Chaque étape est vérifiée ; en cas d'écart la procédure s'arrête et tu continues pas à pas.",
            S.obj("skill" to S.str("Identifiant ou nom exact de la procédure"), "params" to S.anyObj("Valeurs des paramètres, ex. {\"message\":\"Bonjour\"}"), required = listOf("skill")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE,
            label = "Rejouer une procédure", tags = listOf("procedure", "routine", "habitude", "skill"),
        ) { a, ctx ->
            val params = a.obj("params")?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() } ?: emptyMap()
            val available = registry.forToolset(ctx.toolset).map { it.capability }.toSet()
            skills.prepare(a.str("skill")!!, params, available).fold(
                onSuccess = { plan -> ToolResult(true, "Procédure « ${plan.name} » : ${plan.steps.size} étapes.", control = CONTROL, data = ContractJson.encodeToJsonElement(ReplayPlan.serializer(), plan)) },
                onFailure = { ToolResult.error(it.message ?: "Procédure indisponible") },
            )
        },
        ToolDefinition(
            "skill.list", "Liste les procédures apprises actives et leurs paramètres.",
            S.obj(), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Lister les procédures",
            tags = listOf("procedure", "routine", "skill"),
        ) { _, ctx ->
            val available = registry.forToolset(ctx.toolset).map { it.capability }.toSet()
            val list = skills.activeDefinitions(available)
            ToolResult.ok(if (list.isEmpty()) "Aucune procédure active." else list.joinToString("\n") { d ->
                "- ${d.skillId} « ${d.name} » : ${d.description}" + (if (d.parameters.isNotEmpty()) " — paramètres : " + d.parameters.entries.joinToString { "${it.key} (${it.value})" } else "")
            })
        },
    )

    companion object { const val CONTROL = "skill" }
}
