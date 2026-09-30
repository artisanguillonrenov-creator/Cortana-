package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.plugins.PluginPreview
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plugins (phase 22): install a signed package after reviewing its publisher key and permissions. */
@Composable
fun PluginSettingsSection() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by c.plugins.observe().collectAsState(initial = emptyList())
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<PluginPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val secretValues = remember { mutableStateMapOf<String, String>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null; preview = null; secretValues.clear()
            runCatching {
                val b = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 50 * 1024 * 1024) throw IllegalArgumentException("Paquet trop volumineux") }
                        out.toByteArray()
                    }
                }
                bytes = b
                preview = c.plugins.inspect(b)
            }.onFailure { error = it.message }
        }
    }
    SectionCard("Plugins") {
        Text("Extensions déclaratives et signées : compétences, serveurs MCP, agents, documents. Aucun code n'est chargé dans Cortana ; tout passe par la même politique.", style = MaterialTheme.typography.bodySmall)
        installed.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.name} v${p.activeVersion}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(p.state == "active", { v -> scope.launch { runCatching { c.plugins.setEnabled(p.pluginId, v) }.onFailure { error = it.message } } }, enabled = p.state == "active" || p.state == "disabled")
                }
                Text("${p.publisher} · clé ${p.keyFingerprint} · ${p.state}", style = MaterialTheme.typography.bodySmall)
                p.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { scope.launch { runCatching { c.plugins.uninstall(p.pluginId) }.onFailure { error = it.message } } }) { Text("Désinstaller", color = MaterialTheme.colorScheme.error) }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Installer un plugin…") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        preview?.let { pv ->
            val m = pv.manifest
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.name} ${m.version} — ${m.publisher}" + (pv.installedVersion?.let { " (mise à jour depuis $it)" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text(m.description, style = MaterialTheme.typography.bodySmall)
                Text("Clé de l'éditeur : ${pv.fingerprint}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Text("Vérifiez cette empreinte auprès de l'éditeur : les mises à jour devront être signées par la même clé.", style = MaterialTheme.typography.bodySmall)
                Text("Outils utilisables par ses compétences : ${m.permissions.tools.joinToString().ifEmpty { "aucun" }}", style = MaterialTheme.typography.bodySmall)
                Text("Réseau : ${m.permissions.network.joinToString().ifEmpty { "aucun" }}", style = MaterialTheme.typography.bodySmall)
                Text("Apporte : ${m.contributions.skills.size} compétence(s), ${m.contributions.mcpServers.size} serveur(s) MCP, ${m.contributions.a2aAgents.size} agent(s), ${m.contributions.documents.size} document(s)", style = MaterialTheme.typography.bodySmall)
                pv.warnings.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodySmall) }
                pv.problems.forEach { Text("⛔ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                m.permissions.secrets.forEach { name ->
                    OutlinedTextField(secretValues[name].orEmpty(), { secretValues[name] = it }, label = { Text("Secret « $name » (chiffré)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { preview = null; bytes = null }) { Text("Annuler") }
                    Button(enabled = pv.problems.isEmpty() && !busy && m.permissions.secrets.all { !secretValues[it].isNullOrBlank() }, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { c.plugins.install(bytes!!, pv.fingerprint, secretValues.toMap()) }
                                .onSuccess { preview = null; bytes = null; secretValues.clear() }.onFailure { error = it.message }
                            busy = false
                        }
                    }) { Text(if (pv.installedVersion != null) "Mettre à jour" else "Installer") }
                }
            }
        }
    }
}
