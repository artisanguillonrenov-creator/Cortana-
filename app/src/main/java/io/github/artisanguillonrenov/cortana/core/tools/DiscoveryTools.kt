package io.github.artisanguillonrenov.cortana.core.tools

import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `tools.discover` (doc 04 §9): the model asks for tools it was not offered. Results are limited to
 * the session toolset and become callable on the next model turn of the same task.
 */
class DiscoveryTools(private val registry: ToolRegistry, private val discovery: ToolDiscovery) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            CapabilityMatcher.DISCOVER,
            "Trouve des outils supplémentaires quand aucun outil proposé ne convient. Décris l'action voulue en quelques mots ; les outils trouvés deviennent utilisables à l'appel suivant.",
            S.obj(
                "query" to S.str("Action recherchée, en quelques mots (ex. « régler la luminosité »)"),
                "category" to S.str("Catégorie facultative", enum = ToolCategory.entries.map { it.name.lowercase() }),
                required = listOf("query"),
            ),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE,
            label = "Chercher un outil", tags = listOf("outil", "capacite", "fonction"), allowWhenHalted = false,
        ) { a, ctx ->
            val category = a.str("category")?.let { c -> ToolCategory.entries.firstOrNull { it.name.equals(c, ignoreCase = true) } }
            val pool = registry.forToolset(ctx.toolset).filter { it.capability != CapabilityMatcher.DISCOVER }
            val hits = discovery.search(a.str("query")!!, pool, category, limit = 8, minScore = 1.0)
            if (hits.isEmpty()) {
                ToolResult.ok("Aucun outil ne correspond à « ${a.str("query")} » dans cette session. Réponds au propriétaire avec ce que tu peux faire.")
            } else {
                ToolResult(
                    ok = true,
                    text = "Outils disponibles à partir de maintenant :\n" + hits.joinToString("\n") { "- ${it.functionName} (${it.baseRisk.name}) : ${it.description}" },
                    control = "discover",
                    data = JsonArray(hits.map { JsonPrimitive(it.capability) }),
                )
            }
        },
    )
}
