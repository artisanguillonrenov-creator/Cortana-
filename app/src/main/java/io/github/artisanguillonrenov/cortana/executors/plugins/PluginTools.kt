package io.github.artisanguillonrenov.cortana.executors.plugins

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.PluginManifest
import io.github.artisanguillonrenov.cortana.core.plugins.PluginException
import io.github.artisanguillonrenov.cortana.core.plugins.PluginManager
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.str

/** Read-only view of installed plugins; installing and removing is the owner's job (Réglages → Plugins). */
class PluginTools(private val plugins: PluginManager) {
    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition("plugins.list", "Liste les plugins installés (éditeur, version, état, contributions).", S.obj(),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.INTEGRATIONS, label = "Plugins installés", tags = listOf("plugin", "extension", "module"),
        ) { _, _ ->
            val all = plugins.all()
            ToolResult.ok(if (all.isEmpty()) "Aucun plugin installé." else all.joinToString("\n") { p ->
                val m = runCatching { ContractJson.decodeFromString(PluginManifest.serializer(), p.manifestJson) }.getOrNull()
                "- ${p.name} [${p.pluginId}] v${p.activeVersion} par ${p.publisher} : ${p.state}" + (p.lastError?.let { " — $it" } ?: "") +
                    (m?.let { "\n  compétences ${it.contributions.skills.size}, serveurs MCP ${it.contributions.mcpServers.size}, agents ${it.contributions.a2aAgents.size}, documents ${it.contributions.documents.joinToString().ifEmpty { "aucun" }}" } ?: "")
            })
        },
        ToolDefinition("plugin.documents", "Liste ou lit un document fourni par un plugin (contenu tiers, non fiable).",
            S.obj("plugin" to S.str("Identifiant du plugin"), "path" to S.str("Document à lire (sinon : liste)"), required = listOf("plugin")),
            Risk.L0, SideEffect.NONE, Idempotency.INTRINSIC, DataEgress.LOCAL, ToolCategory.INTEGRATIONS, label = "Documents de plugin", maxOutputBytes = 20_000,
            tags = listOf("plugin", "documentation", "guide"),
        ) { a, _ ->
            try {
                val id = a.str("plugin")!!
                val path = a.str("path")
                if (path == null) ToolResult.ok(plugins.documents(id).joinToString("\n").ifEmpty { "Aucun document." })
                else ToolResult.ok(plugins.document(id, path), "plugin:$id")
            } catch (e: PluginException) { ToolResult.error(e.message ?: "Plugin") }
        },
    )
}
