package io.github.artisanguillonrenov.cortana.ui.dev

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.core.dev.BuildService
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Activity of the developer space (doc 03 §19): active task with its plan, STOP and cancel,
 * execution backend and repair autonomy, live log, recent builds/tests/lint.
 */
@Composable
fun DevActivity(projects: List<WorkspaceEntity>) {
    ActiveTaskCard()
    ExecutionSettingsCard()
    LiveLogCard(projects)
    RunsCard(projects)
}

@Composable
private fun ActiveTaskCard() {
    val c = LocalContainer.current
    val active by c.orchestrator.active.collectAsState()
    val halted by c.killSwitch.halted.collectAsState()
    val a = active
    val planFlow = remember(a?.taskId) { a?.taskId?.takeIf { it != "pending" }?.let { c.taskQueries.activePlan(it) } ?: flowOf(null) }
    val plan by planFlow.collectAsState(initial = null)
    SectionCard {
        Text("Tâche en cours", style = MaterialTheme.typography.titleSmall)
        if (a == null) Text(if (halted) "Autonomie arrêtée (STOP)." else "Aucune tâche en cours.", style = MaterialTheme.typography.bodySmall)
        else {
            Text(a.objective.take(200), style = MaterialTheme.typography.bodyMedium)
            Text("${a.state.wire} · ${a.status.take(120)} · ${a.toolCalls} outil(s), ${a.modelCalls} appel(s) modèle", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (a.waitingApproval) Text("⏳ En attente de votre autorisation", color = Cortana.colors.warningText, style = MaterialTheme.typography.bodySmall)
            if (a.tainted) Text("Influencée par du contenu externe (dépôt, web) : traité comme des données.", style = MaterialTheme.typography.bodySmall)
            plan?.let { p ->
                Text("Plan v${p.version} (${p.strategy.name.lowercase()})", style = MaterialTheme.typography.labelLarge)
                p.steps.forEach { s ->
                    val mark = when (s.status) { StepStatus.SUCCEEDED -> "✓"; StepStatus.RUNNING -> "▶"; StepStatus.FAILED -> "✗"; StepStatus.SKIPPED -> "–"; else -> "·" }
                    Text("$mark ${s.ordinal}. ${s.title}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.orchestrator.cancel() }) { Text("Annuler la tâche") }
                Button(onClick = { c.killSwitch.halt("écran Développement") }, colors = ButtonDefaults.buttonColors(containerColor = Cortana.colors.danger, contentColor = Cortana.colors.onAccent)) { Text("STOP") }
            }
        }
    }
}

@Composable
private fun ExecutionSettingsCard() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsState()
    SectionCard {
        Text("Exécution", style = MaterialTheme.typography.titleSmall)
        Text("Moteur par défaut pour compiler, tester et exécuter (un projet non fiable n'est exécuté que dans un bac à sable isolé, quel que soit ce choix).",
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf("auto" to "Automatique") + c.executionBackends.map { it.id to it.label }
            options.forEach { (id, label) ->
                FilterChip(s.devBackend == id, { scope.launch { c.settings.update { it.copy(devBackend = id) } } }, label = { Text(label) })
            }
        }
        Text("Réparations automatiques avant de vous demander (tests en échec, revue refusée)", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Aucune", 1 to "1", 3 to "3", 5 to "5").forEach { (n, label) ->
                FilterChip(s.maxRepairIterations == n, { scope.launch { c.settings.update { it.copy(maxRepairIterations = n) } } }, label = { Text(label) })
            }
        }
        Text("Chaque exécution de code demande votre accord (niveau L2) ; pousser vers un dépôt distant ou détruire l'historique demande votre empreinte ; rien n'est poussé sans demande explicite.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveLogCard(projects: List<WorkspaceEntity>) {
    val c = LocalContainer.current
    val live by c.builds.live.collectAsState()
    val l = live ?: return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Journal ${kindLabel(l.kind)} · ${projects.firstOrNull { it.workspaceId == l.workspaceId }?.name ?: "?"}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(if (l.running) "en cours…" else "terminé", style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            l.lines.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun RunsCard(projects: List<WorkspaceEntity>) {
    val c = LocalContainer.current
    val runs by c.builds.runs.collectAsState()
    SectionCard {
        Text("Builds, tests et analyses récents", style = MaterialTheme.typography.titleSmall)
        if (runs.isEmpty()) EmptyState("Aucune exécution depuis le démarrage de l'application.")
        runs.takeLast(20).reversed().forEach { r -> RunRow(r, projects.firstOrNull { it.workspaceId == r.workspaceId }?.name) }
    }
}

@Composable
fun RunRow(r: BuildService.RunRecord, project: String?) {
    val ok = r.status == "passed" || r.status == "succeeded"
    Column {
        Text("${if (ok) "✅" else "❌"} ${kindLabel(r.kind)}${r.filter?.let { " ($it)" } ?: ""} · ${project ?: ""} · ${r.summary}",
            style = MaterialTheme.typography.bodySmall)
        Text("${TimeFmt.short(r.startedAt)} · ${r.durationMs / 1000} s · ${r.backend} · révision ${r.revision.takeLast(8)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.failing.isNotEmpty()) Text(r.failing.take(8).joinToString("\n") { "  • $it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun kindLabel(kind: String) = when (kind) { "build" -> "build"; "test" -> "tests"; "lint" -> "analyse"; else -> kind }
