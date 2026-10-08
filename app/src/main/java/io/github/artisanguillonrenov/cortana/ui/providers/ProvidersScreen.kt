package io.github.artisanguillonrenov.cortana.ui.providers

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.core.model.ProviderQuirks
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(onEdit: (String) -> Unit, onAdd: (String) -> Unit) {
    val c = LocalContainer.current
    val providers by c.providers.observe().collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showPresets by remember { mutableStateOf(false) }
    ScreenScaffold("Fournisseurs de modèles") { pad ->
        Scaffold(
            modifier = Modifier.padding(pad),
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { showPresets = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Ajouter") })
            },
        ) { inner ->
            LazyColumn(Modifier.padding(inner).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        "Ajoutez autant de fournisseurs compatibles OpenAI que vous voulez. L'ordre ci-dessous est l'ordre de repli : si le fournisseur d'une discussion échoue, Cortana essaie ceux marqués « repli autorisé ». Astuce : OpenRouter donne accès à Claude, Gemini, Llama, Mistral… avec une seule clé.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (providers.isEmpty()) item { SectionCard { Text("Aucun fournisseur. Touchez « Ajouter » et choisissez Infermatic (votre fournisseur actuel), OpenRouter ou Groq.") } }
                items(providers, key = { it.id }) { p ->
                    ProviderCard(
                        p, isDefault = settings.defaultProviderId == p.id, hasKey = c.providers.hasKey(p),
                        onEdit = { onEdit(p.id) },
                        onToggle = { en -> scope.launch { c.providers.update(p.copy(enabled = en), null) } },
                        onDefault = { scope.launch { c.settings.update { it.copy(defaultProviderId = p.id) } } },
                        onFallback = { f -> scope.launch { c.providers.update(p.copy(allowFallback = f), null) } },
                        onUp = { scope.launch { c.providers.move(p.id, -1) } },
                        onDown = { scope.launch { c.providers.move(p.id, 1) } },
                    )
                }
                item { Spacer(Modifier.padding(40.dp)) }
            }
        }
    }
    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("Choisir un préréglage") },
            confirmButton = { TextButton(onClick = { showPresets = false }) { Text("Annuler") } },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(c.presets.all, key = { it.id }) { pr ->
                        Column(Modifier.fillMaxWidth().clickable { showPresets = false; onAdd(pr.id) }.padding(vertical = 10.dp)) {
                            Text(pr.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(pr.baseUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            if (pr.notes.isNotBlank()) Text(pr.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    p: ProviderEntity, isDefault: Boolean, hasKey: Boolean, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onDefault: () -> Unit,
    onFallback: (Boolean) -> Unit, onUp: () -> Unit, onDown: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (isDefault) "★ " else "") + p.displayName, style = MaterialTheme.typography.titleMedium)
                Text(p.baseUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (hasKey) "clé enregistrée 🔒" else "sans clé",
                        p.defaultModelId?.let { "modèle : $it" } ?: "aucun modèle choisi",
                        p.spendCapUsd?.let { "plafond ${"%.2f".format(it)} $/j" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = if (p.defaultModelId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(p.enabled, onToggle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onUp) { Icon(Icons.Default.KeyboardArrowUp, "Monter") }
            IconButton(onClick = onDown) { Icon(Icons.Default.KeyboardArrowDown, "Descendre") }
            FilterChip(selected = p.allowFallback, onClick = { onFallback(!p.allowFallback) }, label = { Text("Repli autorisé") })
            if (!isDefault) TextButton(onClick = onDefault) { Icon(Icons.Default.Star, null); Text("Par défaut") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Modifier") }
        }
    }
}

/** Edit an existing provider ([providerId]) or create one from [presetId]. */
@Composable
fun ProviderEditScreen(providerId: String?, presetId: String?, onBack: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entity by remember { mutableStateOf<ProviderEntity?>(null) }
    val preset = remember(presetId, entity) { c.presets.byId(entity?.presetId ?: presetId ?: "custom") }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var spendCap by remember { mutableStateOf("") }
    var quirks by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var toolMode by remember { mutableStateOf(-1) }
    var confirmDelete by remember { mutableStateOf(false) }
    val settings by c.settings.state.collectAsState()

    LaunchedEffect(providerId) {
        if (providerId != null) {
            c.providers.get(providerId)?.let { p ->
                entity = p; name = p.displayName; baseUrl = p.baseUrl; hasKey = c.providers.hasKey(p)
                spendCap = p.spendCapUsd?.toString().orEmpty(); quirks = p.quirksJson.orEmpty()
                models = c.providers.cachedModels(p.id)
                p.defaultModelId?.let { m -> toolMode = c.capabilities.override(p.id, m)?.nativeTools ?: -1 }
            }
        } else {
            name = preset?.displayName.orEmpty(); baseUrl = preset?.baseUrl.orEmpty(); quirks = preset?.quirks?.takeIf { it.isNotEmpty() }?.toString().orEmpty()
        }
    }

    suspend fun save(): ProviderEntity? {
        val q = quirks.trim()
        if (q.isNotEmpty() && runCatching { kotlinx.serialization.json.Json.parseToJsonElement(q) }.isFailure) {
            status = "Particularités (JSON) invalides"; return null
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            status = "L'adresse doit commencer par https:// (ou http:// pour un serveur local)"; return null
        }
        var e = entity
        if (e == null) {
            val pr = preset ?: c.presets.byId("custom")!!
            e = c.providers.create(pr, name, baseUrl, key.ifBlank { null })
            c.audit.record("owner", "provider.create", e.displayName, "ok")
            if (settings.defaultProviderId == null) c.settings.update { it.copy(defaultProviderId = e.id) }
        } else {
            c.providers.update(e.copy(displayName = name, baseUrl = baseUrl), if (key.isNotBlank()) key else null)
            if (key.isNotBlank()) c.audit.record("owner", "provider.key_update", e.displayName, "ok")
        }
        val updated = c.providers.get(e.id)!!.copy(spendCapUsd = spendCap.replace(',', '.').toDoubleOrNull(), quirksJson = q.ifEmpty { null })
        c.providers.update(updated, null)
        entity = c.providers.get(e.id)
        hasKey = entity?.let { c.providers.hasKey(it) } == true
        key = ""
        return entity
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (providerId == null) "Nouveau fournisseur" else "Modifier ${entity?.displayName ?: ""}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                preset?.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(name, { name = it }, label = { Text("Nom affiché") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baseUrl, { baseUrl = it.trim() }, label = { Text("Adresse de base (base URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(
                    key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hasKey) "Clé API (enregistrée 🔒 — laisser vide pour la garder)" else if (preset?.needsKey == false) "Clé API (optionnelle)" else "Clé API") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                preset?.keyUrl?.let { url ->
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Text("Obtenir une clé : $url")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !testing, onClick = {
                        scope.launch {
                            testing = true; status = "Test en cours…"
                            val e = save()
                            if (e != null) {
                                val d = c.providers.diagnose(e.id)
                                if (d.models.isNotEmpty()) models = d.models
                                status = when {
                                    d.ok -> "✓ ${d.message} Choisissez-en un ci-dessous."
                                    d.kind == io.github.artisanguillonrenov.cortana.core.model.ProviderHealthCheck.Kind.MODEL_MISSING -> "⚠ ${d.message}"
                                    else -> "✗ ${d.message}"
                                }
                            }
                            testing = false
                        }
                    }) { Text("Tester la connexion") }
                    OutlinedButton(onClick = { scope.launch { if (save() != null) { status = "Enregistré"; onBack() } } }) { Text("Enregistrer") }
                    if (testing) CircularProgressIndicator()
                }
                status?.let { Text(it, color = if (it.startsWith("✗") || it.startsWith("⚠") || it.contains("invalide")) MaterialTheme.colorScheme.error else Cortana.colors.successText) }

                entity?.let { e ->
                    SectionCard("Modèle par défaut de ce fournisseur") {
                        Text(e.defaultModelId ?: "Aucun — testez la connexion puis touchez un modèle.", fontFamily = FontFamily.Monospace)
                        if (e.defaultModelId != null) {
                            Text("Appel d'outils pour ce modèle :", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-1 to "Auto", 1 to "Natif", 0 to "Émulé (JSON)").forEach { (v, l) ->
                                    FilterChip(selected = toolMode == v, onClick = {
                                        toolMode = v
                                        scope.launch { c.capabilities.setToolMode(e.id, e.defaultModelId, v) }
                                    }, label = { Text(l) })
                                }
                            }
                            val caps = remember(e.defaultModelId, toolMode) { c.capabilities.bundled(e.defaultModelId) }
                            Text("Auto = ${if (caps.nativeTools) "natif" else "émulé"} d'après la table intégrée ; Cortana bascule seule en émulé si le fournisseur refuse les outils.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (models.isNotEmpty()) {
                            OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filtrer ${models.size} modèles…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            val shown = models.filter { filter.isBlank() || it.id.contains(filter, true) }.take(300)
                            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                                shown.forEach { m ->
                                    Text(
                                        (if (m.id == e.defaultModelId) "✓ " else "") + m.id + (m.contextWindow?.let { "  (${it / 1000}k)" } ?: ""),
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            scope.launch {
                                                c.providers.update(e.copy(defaultModelId = m.id), null)
                                                c.capabilities.rememberContext(e.id, m.id, m.contextWindow)
                                                entity = c.providers.get(e.id)
                                                toolMode = c.capabilities.override(e.id, m.id)?.nativeTools ?: -1
                                            }
                                        }.padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    SectionCard("Options") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Utiliser comme repli automatique", Modifier.weight(1f))
                            Switch(e.allowFallback, { v -> scope.launch { c.providers.update(e.copy(allowFallback = v), null); entity = c.providers.get(e.id) } })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fournisseur par défaut des nouvelles discussions", Modifier.weight(1f))
                            Switch(settings.defaultProviderId == e.id, { v -> if (v) scope.launch { c.settings.update { it.copy(defaultProviderId = e.id) } } })
                        }
                        OutlinedTextField(spendCap, { spendCap = it }, label = { Text("Plafond de dépense par jour (USD, optionnel)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Masquer les options avancées" else "Options avancées") }
                        if (showAdvanced) {
                            OutlinedTextField(quirks, { quirks = it }, label = { Text("Particularités (JSON)") }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            Text("Clés possibles : " + ProviderQuirks().let { "minTemperature, forceN1, noLogprobs, noStreamUsage, usageInclude, noToolChoice, extraHeaders" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Supprimer ce fournisseur", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ${entity?.displayName} ?") },
            text = { Text("La clé chiffrée sera effacée de la tablette.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        entity?.let { e ->
                            c.providers.delete(e.id)
                            c.audit.record("owner", "provider.delete", e.displayName, "ok")
                            if (c.settings.current.defaultProviderId == e.id) c.settings.update { it.copy(defaultProviderId = null) }
                        }
                        onBack()
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
