package io.github.artisanguillonrenov.cortana.ui.skills

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.contracts.SkillDefinition
import io.github.artisanguillonrenov.cortana.contracts.SkillLifecycle
import io.github.artisanguillonrenov.cortana.core.memory.SkillEntity
import io.github.artisanguillonrenov.cortana.core.memory.SkillRunEntity
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun lifecycleLabel(l: SkillLifecycle): String = when (l) {
    SkillLifecycle.CANDIDATE -> "à vérifier"
    SkillLifecycle.TESTED -> "vérifiée, inactive"
    SkillLifecycle.VALIDATED -> "validée"
    SkillLifecycle.ACTIVE -> "active"
    SkillLifecycle.DEGRADED -> "désactivée (écart constaté)"
    SkillLifecycle.RETIRED -> "retirée"
}

/** Procedural memory management (doc 04 §12–15): review, validate, activate, retire, export/import. */
@Composable
fun SkillsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills by c.skills.observeAll().collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            info = if (text == null) "Lecture du fichier impossible." else c.skills.import(text).fold(
                { "Procédure « ${it.name} » importée : vérifiez-la puis activez-la." }, { "Import refusé : ${it.message}" },
            )
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        if (uri != null && id != null) scope.launch {
            val json = c.skills.export(id)
            info = if (json == null) "Export impossible." else runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                "Procédure exportée (sans secrets)."
            }.getOrElse { "Export impossible : ${it.message}" }
        }
    }
    ScreenScaffold("Procédures", actions = { IconButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Icon(Icons.Default.Add, "Importer") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Cortana propose une procédure quand une même suite d'actions a réussi plusieurs fois. Rien n'est rejoué sans votre activation, et chaque étape reste soumise à vos autorisations.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                info?.let { Text(it, Modifier.padding(top = 8.dp)) }
            }
            if (skills.isEmpty()) item { EmptyState("Aucune procédure apprise pour l'instant.") }
            items(skills, key = { it.skillId }) { s ->
                SkillCard(
                    s, expanded == s.skillId, onToggle = { expanded = if (expanded == s.skillId) null else s.skillId },
                    onAction = { action ->
                        scope.launch {
                            info = when (action) {
                                "validate" -> c.skills.validate(s.skillId).let { r -> if (r.ok) "Vérification réussie : vous pouvez l'activer." else "Vérification échouée : " + r.problems.joinToString("; ") }
                                "activate" -> if (c.skills.activate(s.skillId)) "Procédure activée." else "Vérifiez d'abord la procédure."
                                "disable" -> { c.skills.disable(s.skillId); "Procédure désactivée." }
                                "retire" -> { c.skills.retire(s.skillId); "Procédure retirée." }
                                "delete" -> { c.skills.delete(s.skillId); "Procédure supprimée." }
                                "export" -> { exportId = s.skillId; exporter.launch("procedure-${s.name.take(30).replace(Regex("[^A-Za-z0-9]+"), "-")}.json"); null }
                                else -> null
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SkillCard(s: SkillEntity, expanded: Boolean, onToggle: () -> Unit, onAction: (String) -> Unit) {
    val c = LocalContainer.current
    val life = SkillService.lifecycleOf(s)
    var def by remember(s.skillId, s.currentVersion) { mutableStateOf<SkillDefinition?>(null) }
    var runs by remember(s.skillId, s.updatedAt) { mutableStateOf<List<SkillRunEntity>>(emptyList()) }
    LaunchedEffect(s.skillId, s.currentVersion, expanded, s.updatedAt) {
        if (expanded) { def = c.skills.definition(s.skillId); runs = c.skills.runs(s.skillId).take(8) }
    }
    SectionCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(lifecycleLabel(life), color = when (life) {
                    SkillLifecycle.ACTIVE -> Cortana.colors.successText; SkillLifecycle.DEGRADED -> Cortana.colors.dangerText; else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
            Text("v${s.currentVersion} · fiabilité ${(s.confidence * 100).toInt()} % · ${s.successCount} réussites, ${s.failureCount} échecs" +
                (s.lastUsedAt?.let { " · utilisée ${TimeFmt.short(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.lastFailureReason?.takeIf { life == SkillLifecycle.DEGRADED || life == SkillLifecycle.CANDIDATE }?.let {
                Text("Motif : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (expanded) {
            def?.let { d ->
                Text(d.description, style = MaterialTheme.typography.bodyMedium)
                if (d.parameters.isNotEmpty()) Text("Paramètres : " + d.parameters.entries.joinToString { "${it.key} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                d.appPackage?.let { Text("Application : $it" + (d.appVersion?.let { v -> " v$v" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                Text("Risque maximal : ${d.riskLevel.name}", style = MaterialTheme.typography.bodySmall)
                d.steps.forEach { st ->
                    Text("${st.ordinal}. ${st.capability} " + st.arguments.entries.joinToString(", ") { "${it.key}=${it.value}" } + if (st.coordinateFallback) " (coordonnées, dernier recours)" else "",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            if (runs.isNotEmpty()) {
                Text("Historique", style = MaterialTheme.typography.labelLarge)
                runs.forEach { r -> Text("${TimeFmt.short(r.at)} · ${r.kind} → ${r.outcome}" + (r.failedStep?.let { " (étape $it)" } ?: "") + (r.reason?.let { " : ${it.take(120)}" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (life in setOf(SkillLifecycle.CANDIDATE, SkillLifecycle.DEGRADED, SkillLifecycle.TESTED)) OutlinedButton(onClick = { onAction("validate") }) { Text("Vérifier") }
                if (life in setOf(SkillLifecycle.TESTED, SkillLifecycle.VALIDATED) || (life == SkillLifecycle.ACTIVE && !s.enabled)) OutlinedButton(onClick = { onAction("activate") }) { Text("Activer") }
                if (s.enabled) OutlinedButton(onClick = { onAction("disable") }) { Text("Désactiver") }
                if (life != SkillLifecycle.RETIRED) TextButton(onClick = { onAction("retire") }) { Text("Retirer") }
                TextButton(onClick = { onAction("export") }) { Text("Exporter") }
                TextButton(onClick = { onAction("delete") }) { Text("Supprimer") }
            }
        }
    }
}
