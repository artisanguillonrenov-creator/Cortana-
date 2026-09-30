package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Model routing (privacy, coding, vision, embeddings) and provider health. */
@Composable
fun ModelRoutingSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val providers by c.providers.observe().collectAsState(initial = emptyList())
    val listed by c.providers.models.collectAsState()
    val health by c.gateway.health.status.collectAsState()
    val choices = providers.filter { it.enabled }.flatMap { p ->
        ((listed[p.id]?.map { it.id } ?: emptyList()) + listOfNotNull(p.defaultModelId)).distinct().map { m -> "${p.id}/$m" to "${p.displayName} · $m" }
    }
    SectionCard("Modèles spécialisés et confidentialité") {
        Text("Confidentialité", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.privacyMode == ModelGateway.PRIVACY_STANDARD, { upd { it.copy(privacyMode = ModelGateway.PRIVACY_STANDARD) } }, label = { Text("Standard") })
            FilterChip(s.privacyMode == ModelGateway.PRIVACY_LOCAL_ONLY, { upd { it.copy(privacyMode = ModelGateway.PRIVACY_LOCAL_ONLY) } }, label = { Text("Local uniquement") })
        }
        Text("« Local uniquement » : seuls les fournisseurs sur la tablette ou le réseau local (préréglage Serveur local, adresses privées) sont utilisés, repli compris.", style = MaterialTheme.typography.bodySmall)
        RoutePicker("Modèle pour le code", s.codingRoute, choices) { v -> upd { it.copy(codingRoute = v) } }
        RoutePicker("Modèle pour la vision (captures, images)", s.visionRoute, choices) { v -> upd { it.copy(visionRoute = v) } }
        Text("Lecture visuelle de l'écran (quand l'accessibilité ne suffit pas)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off" to "Désactivée", "local" to "Sur la tablette", "remote" to "+ modèle de vision").forEach { (v, label) ->
                FilterChip(s.visionFallback == v, { upd { it.copy(visionFallback = v) } }, label = { Text(label) })
            }
        }
        Text("Capture ponctuelle, jamais enregistrée ni continue ; jamais sur l'écran verrouillé ni dans une application sensible ; mots de passe masqués. " +
            "« Sur la tablette » : texte lu hors ligne (OCR). « + modèle de vision » : la capture masquée (mots de passe, cartes, IBAN, codes) peut être envoyée au modèle choisi ci-dessus — jamais en navigation privée, et seulement à un modèle local en mode « Local uniquement ».",
            style = MaterialTheme.typography.bodySmall)
        RoutePicker("Modèle de génération et de retouche d'images", s.imageRoute, choices, defaultLabel = "Aucun") { v -> upd { it.copy(imageRoute = v) } }
        RoutePicker("Modèle de génération vidéo", s.videoRoute, choices, defaultLabel = "Aucun") { v -> upd { it.copy(videoRoute = v) } }
        Text("Les images envoyées à un fournisseur sont des copies sans métadonnées (position GPS, appareil) ; chaque résultat devient un artefact, jamais ouvert automatiquement. Le mode « Local uniquement » et les plafonds de dépense s'appliquent aussi.",
            style = MaterialTheme.typography.bodySmall)
        var engines by remember { mutableStateOf<List<String>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(s) { engines = runCatching { c.media.engines().map { it.render() } }.getOrDefault(emptyList()) }
        if (engines.isNotEmpty()) {
            Text("Capacités média", style = MaterialTheme.typography.labelLarge)
            engines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        RoutePicker("Modèle d'embeddings (mémoire sémantique)", s.embeddingRoute, choices, defaultLabel = "Local hors ligne (intégré)") { v ->
            upd { it.copy(embeddingRoute = v) }; c.memoryIndexer.request()
        }
        val unhealthy = health.filterValues { it.state != io.github.artisanguillonrenov.cortana.core.model.ProviderHealth.State.CLOSED }
        if (unhealthy.isNotEmpty()) {
            Text("Fournisseurs en pause après des erreurs :", style = MaterialTheme.typography.labelLarge)
            unhealthy.forEach { (id, st) -> Text("• ${providers.firstOrNull { it.id == id }?.displayName ?: id} — ${st.lastError ?: "erreurs répétées"}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RoutePicker(label: String, value: String?, choices: List<Pair<String, String>>, defaultLabel: String = "Par défaut", onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value?.let { v -> choices.firstOrNull { it.first == v }?.second ?: v } ?: defaultLabel)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { open = false; onChange(null) })
            choices.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onChange(k) }) }
        }
    }
}

/** Memory index status, retention, export and erase (doc 04 §10–11, doc 06 §12). */
@Composable
fun MemoryDataSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit, numberField: @Composable (String, Int, (Int) -> Unit) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val index by c.memoryIndexer.status.collectAsState()
    var confirmErase by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            info = runCatching {
                val json = c.memory.exportJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                "Souvenirs exportés."
            }.getOrElse { "Export impossible : ${it.message}" }
        }
    }
    SectionCard("Données de mémoire") {
        Text("Index sémantique : ${index.indexed}/${index.total} souvenirs" + (if (index.fingerprint.isNotEmpty()) " (${index.fingerprint})" else ""), style = MaterialTheme.typography.bodyMedium)
        if (index.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        index.lastError?.let { Text("Index indisponible ($it) : la recherche plein texte reste active.", style = MaterialTheme.typography.bodySmall) }
        numberField("Conserver les épisodes (jours)", s.episodicRetentionDays) { v -> upd { it.copy(episodicRetentionDays = v.coerceIn(1, 3650)) } }
        numberField("Conserver les souvenirs non confirmés (jours)", s.pendingRetentionDays) { v -> upd { it.copy(pendingRetentionDays = v.coerceIn(1, 365)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exporter.launch("cortana-memoire.json") }) { Text("Exporter") }
            OutlinedButton(onClick = { confirmErase = true }) { Text("Tout effacer") }
        }
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text("Effacer toute la mémoire ?") },
        text = { Text("Tous les souvenirs, leur index et leurs relations seront supprimés définitivement. Les conversations ne sont pas touchées.") },
        confirmButton = {
            TextButton(onClick = {
                confirmErase = false
                scope.launch { c.memory.eraseAll(); c.audit.record("owner", "memory.erase_all", null, "ok"); info = "Mémoire effacée." }
            }) { Text("Effacer") }
        },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Annuler") } },
    )
}

/** Git identity, protected branches and per-host credentials (tokens only in the Keystore-backed SecretStore). */
@Composable
fun GitSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    SectionCard("Git") {
        var author by remember(s.gitAuthorName) { mutableStateOf(s.gitAuthorName) }
        var email by remember(s.gitAuthorEmail) { mutableStateOf(s.gitAuthorEmail) }
        var protectedText by remember(s.protectedBranches) { mutableStateOf(s.protectedBranches.joinToString(", ")) }
        androidx.compose.material3.OutlinedTextField(author, { author = it; upd { st -> st.copy(gitAuthorName = it.trim().ifEmpty { "Cortana" }) } }, label = { Text("Nom des commits de Cortana") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(email, { email = it; upd { st -> st.copy(gitAuthorEmail = it.trim().ifEmpty { "cortana@localhost" }) } }, label = { Text("E-mail des commits") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(protectedText, { protectedText = it; upd { st -> st.copy(protectedBranches = it.split(',').map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf("main", "master") }) } },
            label = { Text("Branches protégées (séparées par des virgules)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Aucune fusion ni publication vers une branche protégée sans votre empreinte. Jamais de push forcé.", style = MaterialTheme.typography.bodySmall)
        Text("Identifiants par hôte", style = MaterialTheme.typography.labelLarge)
        s.gitCredentials.forEach { (host, v) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$host — ${v.substringBefore('|')} 🔒", Modifier.weight(1f))
                TextButton(onClick = { c.secrets.remove(v.substringAfter('|')); upd { it.copy(gitCredentials = it.gitCredentials - host) } }) { Text("Retirer") }
            }
        }
        var host by remember { mutableStateOf("github.com") }
        var user by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(host, { host = it }, label = { Text("Hôte (ex. github.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(user, { user = it }, label = { Text("Utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(token, { token = it }, label = { Text("Jeton d'accès") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton(enabled = host.isNotBlank() && token.isNotBlank(), onClick = {
            val h = c.secrets.newHandle(); c.secrets.put(h, token.trim()); token = ""
            val key = host.trim().lowercase()
            upd { it.copy(gitCredentials = it.gitCredentials + (key to "${user.trim().ifEmpty { "git" }}|$h")) }
        }) { Text("Enregistrer l'identifiant") }
    }
}

/** Voice VNext (phase 17): engines, voice, wake phrase, barge-in, hands-free limit. */
@Composable
fun VoiceSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val providers by c.providers.observe().collectAsState(initial = emptyList())
    val listed by c.providers.models.collectAsState()
    val choices = providers.filter { it.enabled }.flatMap { p ->
        ((listed[p.id]?.map { it.id } ?: emptyList()) + listOfNotNull(p.defaultModelId)).distinct().map { m -> "${p.id}/$m" to "${p.displayName} · $m" }
    }
    SectionCard("Voix") {
        Text("Langue", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("fr-FR" to "Français", "en-US" to "English").forEach { (v, l) -> FilterChip(s.voiceLanguage == v, { upd { it.copy(voiceLanguage = v) } }, label = { Text(l) }) }
        }
        Text("Reconnaissance de la parole", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.sttMode == "android", { upd { it.copy(sttMode = "android") } }, label = { Text("Tablette (hors ligne si possible)") })
            FilterChip(s.sttMode == "remote", { upd { it.copy(sttMode = "remote") } }, label = { Text("Fournisseur (Whisper…)") })
        }
        if (s.sttMode == "remote") RoutePicker("Modèle de transcription", s.sttRoute, choices) { v -> upd { it.copy(sttRoute = v) } }
        Text("Voix de Cortana", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.ttsMode == "android", { upd { it.copy(ttsMode = "android") } }, label = { Text("Tablette") })
            FilterChip(s.ttsMode == "remote", { upd { it.copy(ttsMode = "remote") } }, label = { Text("Fournisseur") })
        }
        if (s.ttsMode == "remote") RoutePicker("Modèle de synthèse vocale", s.ttsRoute, choices) { v -> upd { it.copy(ttsRoute = v) } }
        else {
            val voices = remember { c.ttsAndroid.voices().filter { it.locale.startsWith(s.voiceLanguage.substringBefore('-')) } }
            if (voices.isNotEmpty()) RoutePicker("Voix", s.ttsVoice, voices.map { it.name to (it.name + if (it.local) " (hors ligne)" else " (réseau)") }, defaultLabel = "Voix hors ligne par défaut") { v -> upd { it.copy(ttsVoice = v) } }
        }
        ToggleRowLocal("Interrompre Cortana en parlant (mode mains libres)", s.bargeIn) { v -> upd { it.copy(bargeIn = v) } }
        ToggleRowLocal("Mot d'éveil « ${s.wakePhrase} » en mode mains libres", s.wakeWordEnabled) { v -> upd { it.copy(wakeWordEnabled = v) } }
        Text("Arrêt automatique de l'écoute sans parole", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (v, l) -> FilterChip(s.handsFreeTimeoutSec == v, { upd { it.copy(handsFreeTimeoutSec = v) } }, label = { Text(l) }) }
        }
        Text("Le micro ne s'ouvre que sur votre demande (🎤 ou 🗣️ mains libres) ; une barre rouge et une notification « Cortana écoute » l'indiquent tant qu'il est ouvert. " +
            "Le mot d'éveil n'est reconnu que pendant le mode mains libres, jamais en arrière-plan à votre insu. Avec « Fournisseur », l'audio est envoyé au modèle choisi (un serveur local en mode « Local uniquement »), jamais conservé.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToggleRowLocal(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        androidx.compose.material3.Switch(value, onChange)
    }
}

/** Notifications Cortana may see (phase 18): per-app allow-list, content sharing, triggers. */
@Composable
fun NotificationSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val candidates = (s.notificationApps + c.notificationHub.seenPackages).distinct().sorted()
    SectionCard("Notifications") {
        Text("Cortana ne garde que les notifications des applications cochées ci-dessous (les autres sont ignorées à l'arrivée). Leur texte est une donnée, jamais une instruction ; les codes à usage unique sont masqués.", style = MaterialTheme.typography.bodySmall)
        if (candidates.isEmpty()) Text("Aucune application vue pour l'instant : autorisez l'accès aux notifications (Santé → Communications) puis revenez ici.", style = MaterialTheme.typography.bodySmall)
        candidates.forEach { pkg ->
            ToggleRowLocal(pkg, pkg in s.notificationApps) { on -> upd { it.copy(notificationApps = if (on) (it.notificationApps + pkg).distinct() else it.notificationApps - pkg) } }
        }
        ToggleRowLocal("Montrer le contenu des notifications à un modèle distant", s.notificationContentToModel) { v -> upd { it.copy(notificationContentToModel = v) } }
        Text("Désactivé : seul un modèle local (tablette ou réseau local) voit le texte ; sinon seuls l'application et l'heure sont transmises.", style = MaterialTheme.typography.bodySmall)
        if (s.notificationTriggers.isNotEmpty()) Text("Déclencheurs", style = MaterialTheme.typography.labelLarge)
        s.notificationTriggers.forEach { t ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${t.packageName ?: "toute app"}${t.contains?.let { " « $it »" } ?: ""} → ${t.objective}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { upd { st -> st.copy(notificationTriggers = st.notificationTriggers.filterNot { it.id == t.id }) } }) { Text("Supprimer") }
            }
        }
    }
}

