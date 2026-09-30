package io.github.artisanguillonrenov.cortana.ui.tasks

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.TaskEntity
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.TimeFmt

fun stateLabel(s: String): String = when (TaskState.fromWireOrNull(s)) {
    TaskState.RECEIVED -> "reçue"
    TaskState.CLASSIFIED -> "classée"
    TaskState.PLANNING -> "planification"
    TaskState.READY -> "prête"
    TaskState.WAITING_AUTHORIZATION -> "attend votre autorisation"
    TaskState.RUNNING -> "en cours"
    TaskState.WAITING_TOOL -> "attend un outil"
    TaskState.WAITING_USER -> "attend votre réponse"
    TaskState.VERIFYING -> "vérification"
    TaskState.RECOVERING -> "récupération"
    TaskState.REPLANNING -> "replanification"
    TaskState.PAUSED -> "en pause"
    TaskState.INTERRUPTED -> "interrompue"
    TaskState.COMPLETED -> "terminée"
    TaskState.FAILED -> "échec"
    TaskState.CANCELLED -> "annulée"
    TaskState.TIMED_OUT -> "délai dépassé"
    TaskState.HALTED -> "arrêtée (STOP)"
    null -> if (s == "limit") "limite atteinte" else s
}

/** §14 — active task (objective, current step, STOP) and recent tasks with their audited tool calls. */
@Composable
fun TasksScreen(onOpenSession: (String) -> Unit) {
    val c = LocalContainer.current
    val active by c.orchestrator.active.collectAsState()
    val tasks by c.tasksFlow.collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf<String?>(null) }
    ScreenScaffold("Tâches") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val a = active
                SectionCard("Tâche active") {
                    if (a == null) Text("Aucune tâche en cours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text(a.objective, style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Étape : ${a.status}")
                        a.steps.forEach { st ->
                            Text((when (st.status) { io.github.artisanguillonrenov.cortana.contracts.StepStatus.SUCCEEDED -> "✓ "; io.github.artisanguillonrenov.cortana.contracts.StepStatus.RUNNING -> "▶ "; io.github.artisanguillonrenov.cortana.contracts.StepStatus.FAILED -> "✗ "; else -> "○ " }) + st.title,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Appels modèle : ${a.modelCalls} · actions : ${a.toolCalls}" + (if (a.usesUi) " · contrôle de l'écran" else "") + (if (a.tainted) " · contenu non fiable lu" else ""),
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { c.orchestrator.cancel() }) { Text("Annuler") }
                            Button(onClick = { c.killSwitch.halt("écran Tâches") }, colors = ButtonDefaults.buttonColors(containerColor = Cortana.colors.danger, contentColor = Cortana.colors.onAccent)) { Text("STOP") }
                            TextButton(onClick = { onOpenSession(a.sessionId) }) { Text("Voir la discussion") }
                        }
                    }
                }
            }
            item { MetricsCard(tasks.firstOrNull()?.let { it.id + it.state }) }
            if (tasks.isEmpty()) item { EmptyState("Aucune tâche enregistrée.") }
            items(tasks, key = { it.id }) { t ->
                TaskCard(t, expanded == t.id, onToggle = { expanded = if (expanded == t.id) null else t.id }, onOpen = { onOpenSession(t.sessionId) })
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskEntity, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = LocalContainer.current
    val calls by remember(t.id) { c.taskQueries.toolCalls(t.id) }.collectAsState(initial = emptyList())
    var trace by remember { mutableStateOf(false) }
    if (trace) TraceDialog(t.id) { trace = false }
    SectionCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.objective.lineSequence().first().take(120), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stateLabel(t.state), color = when (t.state) {
                    TaskState.COMPLETED.wire -> Cortana.colors.successText; TaskState.FAILED.wire, TaskState.HALTED.wire, TaskState.TIMED_OUT.wire -> Cortana.colors.dangerText; else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
            Text("${TimeFmt.short(t.createdAt)} · mode ${t.mode}" + (if (t.tainted) " · influencée par du contenu externe" else "") + (t.terminationReason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            PlanView(t.id)
            if (calls.isEmpty()) Text("Aucune action.", style = MaterialTheme.typography.bodySmall)
            calls.forEach { tc ->
                val d = runCatching { AppJson.decodeFromString(PolicyDecision.serializer(), tc.policyDecisionJson) }.getOrNull()
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("• ${tc.capability} → ${tc.outcome}" + (d?.let { " [${it.effectiveRisk.name}${if (it.grantUsed) ", autorisation permanente" else ""}]" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(tc.inputJson.take(300), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d?.reasons?.takeIf { it.isNotEmpty() }?.let { Text("Raisons : " + it.joinToString("; "), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row {
                TextButton(onClick = onOpen) { Text("Ouvrir la discussion") }
                TextButton(onClick = { trace = true }) { Text("Trace") }
            }
        }
    }
}
