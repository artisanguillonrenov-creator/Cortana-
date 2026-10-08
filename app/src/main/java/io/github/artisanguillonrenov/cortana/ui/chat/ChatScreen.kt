package io.github.artisanguillonrenov.cortana.ui.chat

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

@Composable
fun ChatScreen(openSessionId: String?, onOpenProviders: () -> Unit) {
    val c = LocalContainer.current
    val vm: ChatViewModel = viewModel { ChatViewModel(c) }
    LaunchedEffect(openSessionId) { openSessionId?.let(vm::open) }
    val session by vm.session.collectAsState()
    val messages by vm.messages.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val active by vm.active.collectAsState()
    val halted by vm.halted.collectAsState()
    val providers by vm.providers.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var toolsetMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                SessionList(sessions, session?.id, vm::open, { vm.newSession() }, { vm.newSession(incognito = true) }, vm::deleteSession, Modifier.width(300.dp).fillMaxHeight())
                VerticalDivider()
            }
            ScreenScaffold(
                title = session?.let { (if (it.incognito) "🕶 " else "") + it.title } ?: "Cortana",
                snackbar = snackbar,
                actions = {
                    if (!wide) IconButton(onClick = { showSessions = true }) { Icon(Icons.AutoMirrored.Filled.List, "Discussions") }
                    IconButton(onClick = { vm.newSession() }) { Icon(Icons.Default.Add, "Nouvelle discussion") }
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                    // Session controls: model + toolset
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val providerName = providers.firstOrNull { it.id == session?.providerId }?.displayName
                        FilterChip(
                            selected = true, onClick = { showModelPicker = true },
                            label = { Text(if (session?.modelId != null) "${providerName ?: "?"} · ${session?.modelId}" else "Choisir un modèle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Box {
                            FilterChip(selected = false, onClick = { toolsetMenu = true }, label = { Text("Outils : " + (Toolsets.labels[session?.toolset] ?: "Complet")) })
                            DropdownMenu(toolsetMenu, { toolsetMenu = false }) {
                                Toolsets.labels.forEach { (k, v) ->
                                    DropdownMenuItem(text = { Text(v + when (k) { Toolsets.CONVERSATION -> " (sans outils)"; Toolsets.ASSISTANT -> " (web, fichiers, système)"; else -> " (+ contrôle de l'écran)" }) },
                                        onClick = { vm.setToolset(k); toolsetMenu = false })
                                }
                            }
                        }
                    }
                    if (halted) {
                        Surface(color = Cortana.colors.warning.copy(alpha = 0.16f), contentColor = Cortana.colors.warningText, modifier = Modifier.fillMaxWidth()) {
                            Text("⏹️ STOP actif : Cortana peut discuter mais n'exécute aucune action. Reprise dans Réglages (empreinte).", Modifier.padding(10.dp), color = Cortana.colors.warningText)
                        }
                    }
                    if (providers.isEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProviders)) {
                            Text("Aucun fournisseur configuré — touchez ici pour en ajouter un (Infermatic, OpenRouter, Groq…).", Modifier.padding(12.dp))
                        }
                    }
                    MessageList(messages, active?.takeIf { it.sessionId == session?.id }?.streamingText, Modifier.weight(1f))
                    val a = active
                    val council = a?.council
                    if (council != null && council.slots.isNotEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                            io.github.artisanguillonrenov.cortana.ui.council.CouncilProgressPanel(council) { id -> c.councilProfiles.get(id)?.role ?: id }
                        }
                    }
                    AnimatedVisibility(a != null) {
                        if (a != null) ActivityBar(a.status + (if (a.toolCalls > 0) " · ${a.toolCalls} action(s)" else "") + (if (a.tainted) " · contenu externe lu" else ""),
                            usesUi = a.usesUi, onCancel = vm::cancel, onHalt = vm::halt)
                    }
                    InputBar(enabled = a == null, onSend = { vm.send(it) })
                }
            }
        }
    }

    if (showSessions) {
        AlertDialog(
            onDismissRequest = { showSessions = false },
            confirmButton = { TextButton(onClick = { showSessions = false }) { Text("Fermer") } },
            title = { Text("Discussions") },
            text = {
                SessionList(sessions, session?.id, { vm.open(it); showSessions = false }, { vm.newSession(); showSessions = false },
                    { vm.newSession(incognito = true); showSessions = false }, vm::deleteSession, Modifier.heightIn(max = 520.dp))
            },
        )
    }
    if (showModelPicker) {
        ModelPickerDialog(vm, session, onDismiss = { showModelPicker = false }, onOpenProviders = { showModelPicker = false; onOpenProviders() })
    }
}

@Composable
private fun SessionList(
    sessions: List<SessionEntity>, current: String?, onOpen: (String) -> Unit, onNew: () -> Unit, onNewIncognito: () -> Unit,
    onDelete: (String) -> Unit, modifier: Modifier,
) {
    var confirmDelete by remember { mutableStateOf<SessionEntity?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("Nouvelle") }
            TextButton(onClick = onNewIncognito) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(4.dp)); Text("Incognito") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(sessions, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(s.id) }
                        .background(if (s.id == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (s.incognito) "🕶 " else "") + s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(TimeFmt.short(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer la discussion ?") },
            text = { Text("« ${s.title} » et ses messages seront supprimés de la tablette.") },
            confirmButton = { TextButton(onClick = { onDelete(s.id); confirmDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun MessageList(messages: List<MessageEntity>, streaming: String?, modifier: Modifier) {
    // A streaming row is shown by the live bubble below (never twice); stopped or interrupted answers stay.
    val visible = messages.filter { !it.hidden && !(it.status == io.github.artisanguillonrenov.cortana.core.memory.MessageStatus.STREAMING && !streaming.isNullOrEmpty()) }
    val state = rememberLazyListState()
    val count = visible.size + (if (!streaming.isNullOrEmpty()) 1 else 0)
    LaunchedEffect(count, streaming?.length) { if (count > 0) state.animateScrollToItem(count - 1) }
    if (visible.isEmpty() && streaming.isNullOrEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)) {
                Text("Bonjour, je suis Cortana.", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Exemples : « Rappelle-moi dans 5 minutes de boire de l'eau », « Retiens que je préfère des réponses courtes », « Ouvre les paramètres et mets la luminosité au maximum ».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visible, key = { it.id }) { m -> MessageItem(m) }
        if (!streaming.isNullOrEmpty()) item("streaming") { Bubble(streaming, mine = false, alpha = 0.85f) }
    }
}

@Composable
private fun MessageItem(m: MessageEntity) {
    when (m.role) {
        Roles.USER -> Bubble(m.text, mine = true)
        Roles.ASSISTANT -> if (m.text.isNotBlank()) Bubble(m.text, mine = false)
        Roles.TOOL -> ToolRow(m)
        else -> if (m.role == Roles.SYSTEM && m.toolCallsJson?.contains("\"council\"") == true) CouncilCard(m) else Text(m.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    }
}

/** The council summary card (doc 07 §7.7), from the structured summary the orchestrator stored — never from model text. */
@Composable
private fun CouncilCard(m: MessageEntity) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val summary = remember(m.toolCallsJson) {
        m.toolCallsJson?.let { j ->
            runCatching {
                AppJson.decodeFromJsonElement(io.github.artisanguillonrenov.cortana.core.council.CouncilSummary.serializer(), AppJson.parseToJsonElement(j).jsonObject["summary"]!!)
            }.getOrNull()
        }
    }
    if (summary == null) Text(m.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    else io.github.artisanguillonrenov.cortana.ui.council.CouncilSummaryCard(summary, settings.councilPrefs.developerDetails)
}

@Composable
private fun Bubble(text: String, mine: Boolean, alpha: Float = 1f) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = (if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = alpha),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 720.dp),
        ) {
            SelectionContainer { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun ToolRow(m: MessageEntity) {
    var expanded by remember { mutableStateOf(false) }
    val meta = remember(m.toolCallsJson) { m.toolCallsJson?.let { runCatching { AppJson.parseToJsonElement(it).jsonObject }.getOrNull() } }
    val ok = meta?.str("ok") != "false"
    val cap = meta?.str("capability") ?: meta?.str("name") ?: "outil"
    // Same pictures as the Workspace: web images/videos/cards and images Cortana produced, never a bare file.
    val rich = remember(m.metaJson) {
        m.metaJson?.let { runCatching { AppJson.decodeFromString(io.github.artisanguillonrenov.cortana.core.chat.MessageMeta.serializer(), it) }.getOrNull() }
    }
    val web = remember(rich) { if (ok && cap == "web.search") io.github.artisanguillonrenov.cortana.core.chat.WebResults.sanitize(rich?.webResults.orEmpty()) else emptyList() }
    val images = remember(rich) {
        if (ok && cap in io.github.artisanguillonrenov.cortana.core.chat.ProducedImages.CAPABILITIES) io.github.artisanguillonrenov.cortana.core.chat.ProducedImages.sanitize(rich?.images.orEmpty()) else emptyList()
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val c = LocalContainer.current
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        if (web.isNotEmpty()) io.github.artisanguillonrenov.cortana.ui.components.WebResultsView(web, onOpen = { url ->
            if (url.startsWith("https://")) runCatching { uri.openUri(url) }
        })
        images.forEach { io.github.artisanguillonrenov.cortana.ui.components.ArtifactThumbnail(c.artifacts, it, Modifier.padding(vertical = 4.dp)) }
        Text(
            "${if (ok) "🔧" else "⚠️"} $cap ${if (expanded) "▾" else "▸"}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp),
        )
        if (expanded) {
            SelectionContainer {
                Text(m.text.take(6000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(8.dp))
            }
        }
    }
}

@Composable
private fun ActivityBar(status: String, usesUi: Boolean, onCancel: () -> Unit, onHalt: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text((if (usesUi) "📱 " else "") + status, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onCancel) { Text("Annuler") }
            Button(onClick = onHalt, colors = ButtonDefaults.buttonColors(containerColor = Cortana.colors.danger, contentColor = Cortana.colors.onAccent)) { Text("STOP") }
        }
    }
}

@Composable
private fun InputBar(enabled: Boolean, onSend: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) text = if (text.isBlank()) spoken else "$text $spoken"
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez à Cortana")
            runCatching { voice.launch(i) }
        }) { Text("🎤", style = MaterialTheme.typography.titleLarge) }
        io.github.artisanguillonrenov.cortana.ui.voice.HandsFreeButton()
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 200.dp),
            placeholder = { Text("Écrivez à Cortana…") },
        )
        IconButton(
            enabled = enabled && text.isNotBlank(),
            onClick = { if (onSend(text)) text = "" },
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
    }
}

@Composable
private fun ModelPickerDialog(vm: ChatViewModel, session: SessionEntity?, onDismiss: () -> Unit, onOpenProviders: () -> Unit) {
    val providers by vm.providers.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedProvider by remember { mutableStateOf(session?.providerId ?: providers.firstOrNull()?.id) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(selectedProvider) {
        val p = selectedProvider ?: return@LaunchedEffect
        loading = true; error = null
        vm.models(p).onSuccess { models = it }.onFailure { error = it.message; models = emptyList() }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modèle de cette discussion") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = onOpenProviders) { Text("Gérer les fournisseurs") } },
        text = {
            Column(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (providers.isEmpty()) { Text("Aucun fournisseur. Ajoutez-en un d'abord."); return@Column }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    providers.filter { it.enabled }.take(4).forEach { p ->
                        FilterChip(selected = p.id == selectedProvider, onClick = { selectedProvider = p.id }, label = { Text(p.displayName, maxLines = 1) })
                    }
                }
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filtrer…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> {
                        Text("Liste des modèles indisponible : $error", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { selectedProvider?.let { p -> vm.models(p, refresh = true).onSuccess { models = it; error = null } } } }) { Text("Réessayer") }
                    }
                }
                val shown = models.filter { filter.isBlank() || it.id.contains(filter, true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { m ->
                        Text(
                            (if (m.id == session?.modelId && selectedProvider == session?.providerId) "✓ " else "") + m.id + (m.contextWindow?.let { "  (${it / 1000}k)" } ?: ""),
                            modifier = Modifier.fillMaxWidth().clickable { vm.setModel(selectedProvider, m.id); onDismiss() }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}
