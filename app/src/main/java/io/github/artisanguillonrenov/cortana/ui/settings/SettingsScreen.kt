package io.github.artisanguillonrenov.cortana.ui.settings

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.ui.common.rememberFixHandler
import io.github.artisanguillonrenov.cortana.ui.health.Fix
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onProviders: () -> Unit, onOnboarding: () -> Unit, onAudit: () -> Unit, onSkills: (() -> Unit)? = null, onCapabilities: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    val halted by c.killSwitch.halted.collectAsState()
    val scope = rememberCoroutineScope()
    val resume = LocalResumeAutonomy.current
    val fix = rememberFixHandler(onProviders)
    fun upd(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }

    ScreenScaffold("Réglages") { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.widthIn(max = 840.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard("Autonomie et arrêt d'urgence") {
                    Text(if (halted) "⏹️ STOP actif : aucune action n'est autorisée." else "✅ Active. STOP est disponible ici, dans la notification, sur l'indicateur à l'écran et via la tuile « Stop Cortana » des réglages rapides.")
                    if (halted) Button(onClick = resume) { Text("Reprendre (empreinte ou code)") }
                    else Button(onClick = { c.killSwitch.halt("réglages") }, colors = ButtonDefaults.buttonColors(containerColor = Cortana.colors.danger, contentColor = Cortana.colors.onAccent)) { Text("STOP — tout arrêter") }
                    Text("Astuce : ajoutez la tuile via le panneau des réglages rapides → ✏️ → faites glisser « Stop Cortana ».", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("Limites par tâche") {
                    NumberField("Appels d'outils max", s.maxToolCallsPerTask) { v -> upd { it.copy(maxToolCallsPerTask = v.coerceIn(1, 200)) } }
                    NumberField("Appels au modèle max", s.maxModelCallsPerTask) { v -> upd { it.copy(maxModelCallsPerTask = v.coerceIn(1, 100)) } }
                    NumberField("Durée max (minutes)", s.maxTaskMinutes) { v -> upd { it.copy(maxTaskMinutes = v.coerceIn(1, 120)) } }
                    NumberField("Jetons de réponse max", s.maxOutputTokens) { v -> upd { it.copy(maxOutputTokens = v.coerceIn(128, 32000)) } }
                    var cap by remember(s.dailySpendCapUsd) { mutableStateOf(s.dailySpendCapUsd?.toString().orEmpty()) }
                    OutlinedTextField(cap, { cap = it; upd { st -> st.copy(dailySpendCapUsd = it.replace(',', '.').toDoubleOrNull()) } },
                        label = { Text("Plafond de dépense quotidien (USD, vide = aucun)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                SectionCard("Discussions") {
                    Text("Outils par défaut des nouvelles discussions", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Toolsets.labels.forEach { (k, v) -> FilterChip(s.defaultToolset == k, { upd { it.copy(defaultToolset = k) } }, label = { Text(v) }) }
                    }
                    ToggleRow("Lire les réponses à voix haute", s.ttsEnabled) { v -> upd { it.copy(ttsEnabled = v) } }
                }
                ChatWorkspaceSettingsSection(s, ::upd)
                SectionCard("Mémoire") {
                    Text("« Retiens que… » est toujours enregistré directement. Pour les faits que Cortana déduit seule :")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(s.memoryWriteMode == "confirm", { upd { it.copy(memoryWriteMode = "confirm") } }, label = { Text("Me demander confirmation") })
                        FilterChip(s.memoryWriteMode == "auto", { upd { it.copy(memoryWriteMode = "auto") } }, label = { Text("Enregistrer directement") })
                    }
                    Text("Une tâche qui a lu du contenu externe propose toujours ses souvenirs à confirmation.", style = MaterialTheme.typography.bodySmall)
                }
                MemoryDataSection(s, ::upd) { label, value, onChange -> NumberField(label, value, onChange) }
                ModelRoutingSection(s, ::upd)
                io.github.artisanguillonrenov.cortana.ui.council.CouncilSettingsSection(s, ::upd)
                VoiceSettingsSection(s, ::upd)
                NotificationSettingsSection(s, ::upd)
                McpSettingsSection(s, ::upd)
                A2aSettingsSection(s, ::upd)
                ConnectionSettingsSection()
                PluginSettingsSection()
                ImprovementSettingsSection()
                ObservabilitySettingsSection(s, ::upd)
                BackupSettingsSection()
                UpdateSettingsSection(s, ::upd)
                NetworkSecretsSection(s, ::upd)
                GitSettingsSection(s, ::upd)
                SectionCard("RunPod") {
                    Text("Avec votre clé API RunPod (lecture et écriture), Cortana retrouve un pod migré sur un autre GPU et met son adresse à jour toute seule, " +
                        "et démarre un pod arrêté quand vous le demandez (« démarre le pod »), toujours après votre confirmation.", style = MaterialTheme.typography.bodySmall)
                    var rpKey by remember { mutableStateOf("") }
                    var rpStatus by remember { mutableStateOf<String?>(null) }
                    val rpScope = rememberCoroutineScope()
                    OutlinedTextField(rpKey, { rpKey = it }, label = { Text(if (c.secrets.has(s.runpodKeyHandle)) "Clé RunPod (enregistrée 🔒)" else "Clé API RunPod") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = rpKey.isNotBlank(), onClick = {
                            val h = s.runpodKeyHandle ?: c.secrets.newHandle()
                            c.secrets.put(h, rpKey.trim()); rpKey = ""
                            upd { it.copy(runpodKeyHandle = h) }
                        }) { Text("Enregistrer la clé") }
                        TextButton(enabled = c.secrets.has(s.runpodKeyHandle), onClick = {
                            rpScope.launch {
                                rpStatus = "Vérification…"
                                rpStatus = runCatching {
                                    val pods = c.runpod.pods()
                                    val r = c.runpodResolver.refresh()
                                    (pods.joinToString("\n") { "${it.name} : ${if (it.running) "en marche" else "arrêté"}${it.gpu?.let { g -> " · $g" }.orEmpty()}" } +
                                        r.changes.joinToString("") { "\nAdresse mise à jour : ${it.providerName}" } + r.problems.joinToString("") { "\nÀ vérifier : $it" }).ifBlank { "Aucun pod." }
                                }.getOrElse { "✗ ${it.message}" }
                            }
                        }) { Text("Vérifier mes pods") }
                    }
                    rpStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                SectionCard("Recherche web") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("duckduckgo" to "DuckDuckGo (sans clé)", "brave" to "Brave Search", "searxng" to "SearXNG").forEach { (k, l) ->
                            FilterChip(s.searchProvider == k, { upd { it.copy(searchProvider = k) } }, label = { Text(l) })
                        }
                    }
                    if (s.searchProvider == "brave") {
                        var key by remember { mutableStateOf("") }
                        OutlinedTextField(key, { key = it }, label = { Text(if (c.secrets.has(s.searchKeyHandle)) "Clé Brave (enregistrée 🔒)" else "Clé Brave Search") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = key.isNotBlank(), onClick = {
                            val h = s.searchKeyHandle ?: c.secrets.newHandle()
                            c.secrets.put(h, key.trim()); key = ""
                            upd { it.copy(searchKeyHandle = h) }
                        }) { Text("Enregistrer la clé") }
                    }
                    if (s.searchProvider == "searxng") {
                        var url by remember(s.searchBaseUrl) { mutableStateOf(s.searchBaseUrl.orEmpty()) }
                        OutlinedTextField(url, { url = it; upd { st -> st.copy(searchBaseUrl = it.trim()) } }, label = { Text("Adresse SearXNG (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    ToggleRow("Autoriser la lecture d'adresses du réseau local (désactive la protection SSRF)", s.allowPrivateNetworkFetch) { v -> upd { it.copy(allowPrivateNetworkFetch = v) } }
                }
                SectionCard("Dossier de travail (fichiers)") {
                    Text(s.workingFolderUri?.let { "Autorisé : " + Uri.parse(it).lastPathSegment } ?: "Aucun. Cortana ne peut lire/écrire que dans ce dossier.")
                    OutlinedButton(onClick = { fix(Fix.FOLDER) }) { Text(if (s.workingFolderUri == null) "Choisir un dossier" else "Changer de dossier") }
                    if (s.workingFolderUri != null) TextButton(onClick = { upd { it.copy(workingFolderUri = null) } }) { Text("Retirer l'accès") }
                }
                SectionCard("Sécurité des actions") {
                    Text("Autorisations permanentes (niveau L2, révocables)", style = MaterialTheme.typography.labelLarge)
                    val grantRows by c.grants.observe().collectAsState(initial = emptyList())
                    val live = grantRows.filter { !it.revoked }
                    if (live.isEmpty()) Text("Aucune.", style = MaterialTheme.typography.bodySmall)
                    live.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.capability + (g.scope?.let { " @ $it" } ?: "") + (g.maxUses?.let { " (${g.uses}/$it)" } ?: " (${g.uses} utilisations)"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { scope.launch { c.grants.revoke(g.grantId) } }) { Text("Révoquer") }
                        }
                    }
                    Text("Destinations de confiance", style = MaterialTheme.typography.labelLarge)
                    if (s.knownDestinations.isEmpty()) Text("Aucune.", style = MaterialTheme.typography.bodySmall)
                    ChipList(s.knownDestinations.takeLast(60)) { d -> upd { it.copy(knownDestinations = it.knownDestinations - d) } }
                    Text("Applications sensibles autorisées à l'automatisation (nom de paquet, une par ligne)", style = MaterialTheme.typography.labelLarge)
                    var allow by remember(s.sensitiveAllowlist) { mutableStateOf(s.sensitiveAllowlist.joinToString("\n")) }
                    OutlinedTextField(allow, { allow = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("ex. com.paypal.android.p2pmobile") })
                    TextButton(onClick = {
                        val list = allow.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        upd { it.copy(sensitiveAllowlist = list) }
                        scope.launch { c.audit.record("owner", "policy.sensitive_allowlist", list.joinToString(), "updated") }
                    }) { Text("Enregistrer la liste") }
                    UiPatternsEditor()
                }
                SectionCard("Autres") {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOnboarding) { Text("Relancer l'assistant de configuration") }
                        OutlinedButton(onClick = onAudit) { Text("Journal d'audit") }
                        // The design's sidebar has no entry for these screens: they stay one tap away from here.
                        onSkills?.let { OutlinedButton(onClick = it) { Text("Procédures") } }
                        onCapabilities?.let { OutlinedButton(onClick = it) { Text("Capacités de l'appareil") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun UiPatternsEditor() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember(open) { mutableStateOf(s.uiRiskPatternsJson ?: c.uiPatterns.bundledJson) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Masquer les motifs de risque" else "Motifs de risque des actions à l'écran (avancé)") }
    if (open) {
        Text("Version active : ${c.uiPatterns.current().version}" + if (s.uiRiskPatternsJson != null) " (personnalisée)" else " (intégrée)", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it; error = null }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 6, maxLines = 20)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val err = c.uiPatterns.validate(text)
                if (err != null) error = "JSON invalide : $err" else scope.launch {
                    c.settings.update { it.copy(uiRiskPatternsJson = text) }
                    c.audit.record("owner", "policy.ui_patterns", null, "updated")
                }
            }) { Text("Enregistrer") }
            TextButton(onClick = { scope.launch { c.settings.update { it.copy(uiRiskPatternsJson = null) } }; text = c.uiPatterns.bundledJson }) { Text("Rétablir les motifs intégrés") }
        }
    }
}

@Composable
private fun ChipList(items: List<String>, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { g -> InputChip(selected = false, onClick = { onRemove(g) }, label = { Text("$g  ✕") }) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { t -> text = t.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}
