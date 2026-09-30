package io.github.artisanguillonrenov.cortana.ui.dev

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.contracts.ChangeSet
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.RepositoryProfile
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.dev.ReviewService
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

private val trustLabels = linkedMapOf(
    WorkspaceTrust.UNTRUSTED to "Non fiable", WorkspaceTrust.TRUSTED_LOCAL to "De confiance", WorkspaceTrust.READ_ONLY to "Lecture seule",
)

/** Developer space (doc 03 §19): projects, repository profile, changes (diff, rollback), review, activity (task, plan, STOP, backend, logs, runs), artifacts. */
@Composable
fun DevScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val projects by c.workspaces.observe().collectAsState(initial = emptyList())
    val artifacts by c.artifacts.observe().collectAsState(initial = emptyList())
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            info = runCatching { c.workspaces.importTree(uri).let { w -> c.repoIntelligence.inspectAndStore(w); "Projet « ${w.name} » importé (non fiable par défaut)." } }
                .getOrElse { "Import impossible : ${it.message}" }
        }
    }
    ScreenScaffold("Développement") { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Projets (${projects.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Activité") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Artefacts (${artifacts.size})") })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info?.let { item { Text(it) } }
                if (tab == 0) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importer.launch(null) }) { Text("Importer un dossier") }
                            OutlinedButton(onClick = { creating = true }) { Text("Nouveau projet") }
                        }
                    }
                    if (projects.isEmpty()) item { EmptyState("Aucun projet. Importez un dossier ou demandez à Cortana d'en créer un.") }
                    items(projects, key = { it.workspaceId }) { w -> ProjectCard(w) { msg -> info = msg } }
                } else if (tab == 1) {
                    item { DevActivity(projects) }
                } else {
                    if (artifacts.isEmpty()) item { EmptyState("Aucun artefact produit pour l'instant.") }
                    items(artifacts, key = { it.artifactId }) { a -> ArtifactRow(a) { msg -> info = msg } }
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false }, title = { Text("Nouveau projet") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { creating = false; scope.launch { c.workspaces.create(name.ifBlank { "projet" }); info = "Projet créé." } }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ProjectCard(w: WorkspaceEntity, onInfo: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var changes by remember(w.workspaceId) { mutableStateOf<List<ChangeSet>>(emptyList()) }
    var shownDiff by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var review by remember(w.workspaceId) { mutableStateOf<String?>(null) }
    val profile = remember(w.profileJson) { w.profileJson?.let { runCatching { ContractJson.decodeFromString(RepositoryProfile.serializer(), it) }.getOrNull() } }
    LaunchedEffect(expanded, w.lastOpenedAt) { if (expanded) changes = c.patchEngine.changeSets(w.workspaceId) }
    SectionCard {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(trustLabels[WorkspaceManager.trustOf(w)] ?: w.trust, color = if (w.trust == "untrusted") Cortana.colors.warningText else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(listOfNotNull(w.currentBranch?.let { "branche $it" }, profile?.let { "${it.fileCount} fichiers" }, profile?.buildSystems?.joinToString()?.ifEmpty { null },
                "ouvert ${TimeFmt.short(w.lastOpenedAt)}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (w.lockTaskId != null && (w.lockUntil ?: 0) > System.currentTimeMillis()) Text("🔒 en cours de modification par une tâche", style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                trustLabels.forEach { (t, label) ->
                    FilterChip(WorkspaceManager.trustOf(w) == t, { scope.launch { c.workspaces.setTrust(w.workspaceId, t); onInfo("Confiance : $label") } }, label = { Text(label) })
                }
            }
            Text("Le contenu d'un projet reste une donnée pour Cortana, jamais une instruction. « Non fiable » : aucun script n'est exécuté hors bac à sable strict.", style = MaterialTheme.typography.bodySmall)
            profile?.let { p ->
                Text("Langages : ${p.languages.joinToString().ifEmpty { "?" }} · build : ${p.buildSystems.joinToString().ifEmpty { "?" }} · tests : ${p.testFiles}", style = MaterialTheme.typography.bodySmall)
                if (p.potentialSecrets.isNotEmpty()) Text("⚠️ Secrets potentiels : ${p.potentialSecrets.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { c.repoIntelligence.inspectAndStore(w); onInfo("Analyse mise à jour.") } }) { Text("Analyser") }
                TextButton(onClick = { confirmDelete = true }) { Text("Supprimer") }
            }
            if (w.vcsType == "git") GitPanel(w)
            if (w.vcsType == "git") OutlinedButton(onClick = {
                scope.launch { review = runCatching { ReviewService.render(c.review.reviewUncommitted(w)) }.getOrElse { "Revue impossible : ${it.message}" } }
            }) { Text("Relire les modifications non commitées") }
            review?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val runs by c.builds.runs.collectAsState()
            runs.filter { it.workspaceId == w.workspaceId }.takeLast(3).reversed().forEach { RunRow(it, null) }
            if (changes.isNotEmpty()) Text("Modifications", style = MaterialTheme.typography.labelLarge)
            changes.take(20).forEach { cs ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${TimeFmt.short(cs.createdAt)} · ${cs.files.size} fichier(s) · ${if (cs.status == "applied") "appliquée" else "annulée"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { shownDiff = if (shownDiff == cs.diff) null else cs.diff }) { Text("Diff") }
                    if (cs.status == "applied") TextButton(onClick = {
                        scope.launch { onInfo(runCatching { c.patchEngine.rollback(cs.changeSetId); "Modification annulée." }.getOrElse { "Annulation refusée : ${it.message}" }); changes = c.patchEngine.changeSets(w.workspaceId) }
                    }) { Text("Annuler") }
                }
                if (shownDiff == cs.diff) DiffView(cs.diff)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Supprimer « ${w.name} » ?") },
        text = { Text("La copie de travail de Cortana et son historique de modifications seront supprimés. Le dossier d'origine n'est pas touché.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { c.workspaces.delete(w.workspaceId); onInfo("Projet supprimé.") } }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

@Composable
fun DiffView(diff: String) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        diff.lineSequence().take(400).forEach { l ->
            Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = when {
                l.startsWith("+++") || l.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                l.startsWith("+") -> Cortana.colors.successText; l.startsWith("-") -> Cortana.colors.dangerText; l.startsWith("@@") -> Cortana.colors.accentLink
                else -> MaterialTheme.colorScheme.onSurface
            })
        }
    }
}

@Composable
private fun ArtifactRow(a: ArtifactEntity, onInfo: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(a.mime)) { uri ->
        if (uri != null) scope.launch { onInfo(if (c.artifacts.exportTo(a.artifactId, uri)) "Exporté et vérifié (sha256)." else "Export impossible ou empreinte différente.") }
    }
    SectionCard {
        Text(a.name, style = MaterialTheme.typography.titleSmall)
        Text("${a.type} · ${a.sizeBytes / 1024} Ko · ${TimeFmt.short(a.createdAt)} · sha256 ${a.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exporter.launch(a.name) }) { Text("Exporter") }
            TextButton(onClick = { scope.launch { onInfo(if (c.artifacts.verify(a.artifactId)) "Intégrité vérifiée." else "⚠️ Empreinte différente : fichier altéré.") } }) { Text("Vérifier") }
            TextButton(onClick = { scope.launch { c.artifacts.delete(a.artifactId); onInfo("Artefact supprimé.") } }) { Text("Supprimer") }
        }
    }
}

@Composable
private fun GitPanel(w: WorkspaceEntity) {
    val c = LocalContainer.current
    var status by remember(w.workspaceId) { mutableStateOf<String?>(null) }
    var log by remember(w.workspaceId) { mutableStateOf<List<io.github.artisanguillonrenov.cortana.core.dev.GitService.LogEntry>>(emptyList()) }
    var diff by remember(w.workspaceId) { mutableStateOf<String?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    LaunchedEffect(w.workspaceId, w.lastOpenedAt) {
        status = runCatching { c.git.status(w).render() }.getOrElse { "Git : ${it.message}" }
        log = runCatching { c.git.log(w, 8) }.getOrDefault(emptyList())
        diff = runCatching { c.git.diff(w) }.getOrNull()
    }
    Text("Git", style = MaterialTheme.typography.labelLarge)
    status?.let { Text(it.trim(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    log.forEach { e -> Text("${e.id.take(8)} ${TimeFmt.short(e.time)} ${e.message.lineSequence().first().take(80)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    if (!diff.isNullOrBlank()) {
        TextButton(onClick = { showDiff = !showDiff }) { Text(if (showDiff) "Masquer le diff" else "Diff non commité") }
        if (showDiff) DiffView(diff!!)
    }
}
