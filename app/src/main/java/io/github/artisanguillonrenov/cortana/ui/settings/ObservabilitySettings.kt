package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.observability.ObservabilityService
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Phase 28: trace retention and the optional OTLP export to the owner's own collector. */
@Composable
fun ObservabilitySettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var endpoint by remember(s.otlpEndpoint) { mutableStateOf(s.otlpEndpoint.orEmpty()) }
    var header by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    SectionCard("Traces et métriques") {
        Text("Chaque tâche est tracée sur la tablette (étapes, appels au modèle, outils, vérifications, durées), sans contenu ni secret. Les traces se consultent depuis l'écran Tâches.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conserver les traces ${s.spanRetentionDays} jour(s)", Modifier.weight(1f))
            TextButton(onClick = { upd { it.copy(spanRetentionDays = (it.spanRetentionDays - 1).coerceAtLeast(1)) } }) { Text("−") }
            TextButton(onClick = { upd { it.copy(spanRetentionDays = (it.spanRetentionDays + 1).coerceAtMost(90)) } }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Exporter vers mon collecteur OpenTelemetry (OTLP/HTTP)", Modifier.weight(1f))
            Switch(s.otlpEnabled, { on ->
                message = if (on) runCatching { ObservabilityService.checkEndpoint(endpoint.trim()); upd { it.copy(otlpEnabled = true, otlpEndpoint = endpoint.trim()) }; null }.getOrElse { it.message }
                else null.also { upd { it.copy(otlpEnabled = false) } }
            })
        }
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Adresse du collecteur (https://…:4318)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(header, { header = it }, label = { Text(if (c.secrets.has(s.otlpHeaderHandle)) "En-tête d'authentification (enregistré 🔒)" else "En-tête facultatif « Nom: valeur »") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                message = runCatching {
                    ObservabilityService.checkEndpoint(endpoint.trim())
                    if (header.isNotBlank()) {
                        val h = s.otlpHeaderHandle ?: c.secrets.newHandle()
                        c.secrets.put(h, header.trim()); header = ""
                        upd { it.copy(otlpEndpoint = endpoint.trim(), otlpHeaderHandle = h) }
                    } else upd { it.copy(otlpEndpoint = endpoint.trim()) }
                    "Enregistré"
                }.getOrElse { it.message }
            }) { Text("Enregistrer") }
            TextButton(enabled = s.otlpEnabled, onClick = { scope.launch { message = runCatching { "${c.observability.export()} span(s) envoyé(s)" }.getOrElse { it.message } } }) { Text("Exporter maintenant") }
        }
        (message ?: c.observability.lastExport)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
