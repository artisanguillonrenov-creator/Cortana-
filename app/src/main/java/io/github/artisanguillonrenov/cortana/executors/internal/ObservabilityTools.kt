package io.github.artisanguillonrenov.cortana.executors.internal

import io.github.artisanguillonrenov.cortana.core.observability.ObservabilityService
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str

/** Read-only diagnostics (phase 28): metrics and one task's trace, content-free and redacted. */
class ObservabilityTools(private val obs: ObservabilityService) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "observability.metrics", "Métriques de fonctionnement de Cortana : tâches, durées, appels au modèle (jetons, latence), outils (erreurs, latence), vérifications, coût.",
            S.obj("hours" to S.int("Fenêtre en heures (défaut 24)", 1, 24 * 30)),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Métriques",
            tags = listOf("métriques", "performance", "latence", "diagnostic"),
        ) { a, _ -> ToolResult.ok(obs.metrics((a.int("hours") ?: 24) * 3_600_000L).text()) },
        ToolDefinition(
            "observability.trace", "Trace d'une tâche (étapes, appels au modèle, outils, vérifications, durées, erreurs) pour diagnostiquer une lenteur ou un échec.",
            S.obj("task_id" to S.str("Identifiant de la tâche (8 caractères suffisent)"), required = listOf("task_id")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SERVICE, label = "Trace d'une tâche",
            tags = listOf("trace", "diagnostic", "span"),
        ) { a, _ ->
            val id = a.str("task_id")!!
            val full = obs.resolveTask(id) ?: return@ToolDefinition ToolResult.error("Tâche introuvable : $id")
            val nodes = obs.trace(full).flatMap { it.flatten() }
            ToolResult.ok(if (nodes.isEmpty()) "Aucune trace enregistrée pour cette tâche." else nodes.take(120).joinToString("\n") { n ->
                "  ".repeat(n.depth) + "${n.span.name} · ${n.span.durationMs} ms · ${n.span.status}" + (n.span.errorType?.let { " ($it)" } ?: "") +
                    n.span.attributes.entries.filter { it.key.startsWith("gen_ai.") || it.key in setOf("tool", "risk", "step") }.joinToString("") { " ${it.key}=${it.value}" }
            })
        },
    )
}
