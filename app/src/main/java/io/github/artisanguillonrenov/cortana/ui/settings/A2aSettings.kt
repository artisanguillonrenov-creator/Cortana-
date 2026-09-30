package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.a2a.A2aAgentConfig
import io.github.artisanguillonrenov.cortana.core.a2a.A2aProtocol
import io.github.artisanguillonrenov.cortana.core.mcp.McpAdapter
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Agents externes (A2A, phase 21): add by address (its agent card is read), token, state, skills. */
@Composable
fun A2aSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val status by c.a2a.status.collectAsState()
    val scope = rememberCoroutineScope()
    SectionCard("Agents externes (A2A)") {
        Text("Cortana peut confier une sous-tâche à un agent externe. Seul ce que l'accord affiche est envoyé (jamais la mémoire ni l'historique) ; chaque délégation demande votre accord ; la réponse est traitée comme non fiable.", style = MaterialTheme.typography.bodySmall)
        s.a2aAgents.forEach { a ->
            val st = status[a.id]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (st?.state) { "ok" -> "🟢"; "error" -> "🔴"; "disabled" -> "⚪"; else -> "🟡" } + " ${a.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { scope.launch { c.a2a.refresh(a.id) } }) { Text("Actualiser") }
                    Switch(a.enabled, { v -> upd { st2 -> st2.copy(a2aAgents = st2.a2aAgents.map { if (it.id == a.id) it.copy(enabled = v) else it }) }; scope.launch { c.a2a.refresh(a.id) } })
                }
                Text(a.cardUrl + (st?.card?.let { " · « ${it.name} » v${it.version}${it.provider?.let { p -> " ($p)" } ?: ""} · A2A ${it.protocolVersion}${if (it.signed) " · carte signée (non vérifiée)" else ""}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                st?.card?.skills?.forEach { k -> Text("• ${k.name} : ${k.description.take(140)}", style = MaterialTheme.typography.bodySmall) }
                st?.error?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    c.secrets.remove(a.authHandle)
                    upd { st2 -> st2.copy(a2aAgents = st2.a2aAgents.filterNot { it.id == a.id }) }
                    scope.launch { c.a2a.refreshAll() }
                }) { Text("Supprimer cet agent", color = MaterialTheme.colorScheme.error) }
            }
        }
        var adding by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        if (!adding) OutlinedButton(onClick = { adding = true }) { Text("Ajouter un agent externe") }
        else {
            OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("Adresse de l'agent ou de sa carte (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Jeton d'accès (facultatif, chiffré)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { adding = false }) { Text("Annuler") }
                TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                    val card = runCatching { A2aProtocol.cardUrl(url) }.getOrElse { error = it.message; return@TextButton }
                    if (!card.isHttps && !SsrfGuard.isBlockedLiteral(card.host)) { error = "Un agent sur Internet doit utiliser https"; return@TextButton }
                    val id = McpAdapter.serverSlug(name).let { b -> if (s.a2aAgents.any { it.id == b }) b + "_" + (s.a2aAgents.size + 1) else b }
                    val h = token.takeIf { it.isNotBlank() }?.let { t -> c.secrets.newHandle().also { c.secrets.put(it, t.trim()) } }
                    val cfg = A2aAgentConfig(id, name.trim(), url.trim(), h, createdAt = System.currentTimeMillis())
                    scope.launch { c.settings.update { it.copy(a2aAgents = it.a2aAgents + cfg) }; c.a2a.refresh(id) }
                    adding = false; name = ""; url = ""; token = ""; error = null
                }) { Text("Ajouter") }
            }
        }
    }
}
