package io.github.artisanguillonrenov.cortana.executors.internal

import io.github.artisanguillonrenov.cortana.core.improvement.ImprovementService
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult

/**
 * Read-only view of the improvement proposals for the model (phase 27). Applying, rejecting or
 * rolling back is the owner's decision in Réglages → Améliorations: no capability does it.
 */
class ImprovementTools(private val improvements: ImprovementService) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "improvement.list", "Liste les propositions d'amélioration de Cortana (échecs répétés, raccourcis, procédures, cas de test, coûts…). Seul le propriétaire peut les appliquer, dans Réglages → Améliorations.",
            S.obj("all" to S.bool("Inclure les propositions déjà appliquées, refusées ou annulées")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Propositions d'amélioration",
            tags = listOf("amélioration", "proposition", "échecs", "raccourci"),
        ) { a, _ ->
            val list = if ((a["all"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") improvements.all() else improvements.open()
            ToolResult.ok(if (list.isEmpty()) "Aucune proposition d'amélioration en cours." else list.take(30).joinToString("\n") { p ->
                "- [${p.proposalId.take(8)}] (${p.kind}, v${p.version}, ${p.status}) ${p.title} — ${p.rationale.take(240)}" +
                    (improvements.change(p)?.let { " ⇒ ${it.describe()}" } ?: "")
            })
        },
    )
}
