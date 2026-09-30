package io.github.artisanguillonrenov.cortana.ui.tasks

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.artisanguillonrenov.cortana.core.observability.MetricsSummary
import io.github.artisanguillonrenov.cortana.core.observability.TraceNode
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard

/** Local trace viewer (phase 28): the span tree of one task with a timeline bar per span. */
@Composable
fun TraceDialog(taskId: String, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    var nodes by remember { mutableStateOf<List<TraceNode>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(taskId) { nodes = c.observability.trace(taskId).flatMap { it.flatten() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trace de la tâche") },
        text = {
            val list = nodes
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                when {
                    list == null -> Text("Chargement…")
                    list.isEmpty() -> Text("Aucune trace pour cette tâche (tâche antérieure à la phase 28 ou purgée).", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val t0 = list.minOf { it.span.startMs }
                        val total = (list.maxOf { it.span.endMs } - t0).coerceAtLeast(1)
                        list.forEach { n ->
                            val s = n.span
                            Column(Modifier.fillMaxWidth().clickable { open = if (open == s.spanId) null else s.spanId }.padding(start = (n.depth * 10).dp, top = 3.dp, bottom = 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(when (s.status) { "ok" -> "✓ "; "cancelled" -> "⏹ "; else -> "✗ " } + s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = if (s.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    Text("${s.durationMs} ms", style = MaterialTheme.typography.labelSmall)
                                }
                                Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    Row {
                                        Box(Modifier.weight(((s.startMs - t0).toFloat() / total).coerceAtLeast(0.0001f)))
                                        Box(Modifier.weight((s.durationMs.toFloat() / total).coerceAtLeast(0.01f)).height(4.dp).background(if (s.status == "error") Cortana.colors.danger else MaterialTheme.colorScheme.primary))
                                        Box(Modifier.weight(((total - (s.startMs - t0) - s.durationMs).toFloat() / total).coerceAtLeast(0.0001f)))
                                    }
                                }
                                if (open == s.spanId) {
                                    (listOfNotNull(s.errorType?.let { "error.type=$it" }) + s.attributes.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).forEach {
                                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}

/** Metrics over the last 24 hours (phase 28). */
@Composable
fun MetricsCard(refreshKey: Any?) {
    val c = LocalContainer.current
    var m by remember { mutableStateOf<MetricsSummary?>(null) }
    LaunchedEffect(refreshKey) { m = runCatching { c.observability.metrics() }.getOrNull() }
    val s = m ?: return
    if (s.spans == 0 && s.tasksByState.isEmpty()) return
    SectionCard("Métriques (24 h)") {
        Text(s.text().lines().drop(1).joinToString("\n") { it.removePrefix("- ") }, style = MaterialTheme.typography.bodySmall)
    }
}

/** Task plan view (phase 32): the active plan's steps with status, attempts, dependencies, result; and its versions. */
@Composable
fun PlanView(taskId: String) {
    val c = LocalContainer.current
    val plan by androidx.compose.runtime.remember(taskId) { c.taskQueries.activePlan(taskId) }.collectAsState(initial = null)
    var versions by remember { mutableStateOf(0) }
    LaunchedEffect(taskId, plan?.version) { versions = runCatching { c.taskQueries.planVersions(taskId).size }.getOrDefault(0) }
    val p = plan ?: return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("Plan v${p.version}${if (versions > 1) " ($versions versions : replanifié)" else ""} · ${p.strategy.name.lowercase()} · ${p.steps.count { it.status == io.github.artisanguillonrenov.cortana.contracts.StepStatus.SUCCEEDED }}/${p.steps.size} étape(s)",
            style = MaterialTheme.typography.titleSmall)
        p.rationale?.takeIf { it.isNotBlank() }?.let { Text(it.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        p.steps.sortedBy { it.ordinal }.forEach { s ->
            val mark = when (s.status) {
                io.github.artisanguillonrenov.cortana.contracts.StepStatus.SUCCEEDED -> "✓"; io.github.artisanguillonrenov.cortana.contracts.StepStatus.RUNNING -> "▶"
                io.github.artisanguillonrenov.cortana.contracts.StepStatus.FAILED -> "✗"; io.github.artisanguillonrenov.cortana.contracts.StepStatus.SKIPPED -> "↷"
                io.github.artisanguillonrenov.cortana.contracts.StepStatus.CANCELLED -> "⏹"; else -> "○"
            }
            Text("$mark ${s.ordinal}. ${s.title}" + (if (s.attempts > 1) " (${s.attempts} essais)" else "") + (if (s.dependencies.isNotEmpty()) " ← ${s.dependencies.joinToString()}" else "") +
                (s.specialist?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            s.resultSummary?.let { Text("   " + it.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
