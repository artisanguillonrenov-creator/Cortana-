package io.github.artisanguillonrenov.cortana.executors.internal

import io.github.artisanguillonrenov.cortana.core.backup.DatabaseDoctor
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult

/** Read-only database diagnosis (phase 29). Repairs, backups and restores are the owner's, in the app. */
class DoctorTools(private val doctor: DatabaseDoctor) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "doctor.check", "Diagnostic de la base de Cortana : intégrité, index, artefacts, procédures, tâches bloquées, planificateur, secrets manquants, audit, espace disque. Les réparations se font par le propriétaire dans Santé.",
            S.obj(), Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SYSTEM, label = "Diagnostic de la base",
            tags = listOf("diagnostic", "santé", "base de données", "réparation"),
        ) { _, _ ->
            ToolResult.ok(doctor.run().joinToString("\n") { c -> "- ${when (c.status) { "ok" -> "✓"; "warn" -> "⚠"; else -> "✗" }} ${c.label} : ${c.detail}" + (c.repair?.let { " (réparation possible : $it)" } ?: "") })
        },
    )
}
