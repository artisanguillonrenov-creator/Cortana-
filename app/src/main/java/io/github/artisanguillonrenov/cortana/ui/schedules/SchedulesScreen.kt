package io.github.artisanguillonrenov.cortana.ui.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleEntity
import io.github.artisanguillonrenov.cortana.core.memory.ScheduleRunEntity
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPaths
import io.github.artisanguillonrenov.cortana.core.scheduler.CronExpression
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.ui.common.ConfirmDialog
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch
import java.time.ZoneId

/** §12/§14 — list, enable/disable, run now, delete, add reminder or task. */
@Composable
fun SchedulesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list by c.scheduler.observe().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduleEntity?>(null) }
    var history by remember { mutableStateOf<Pair<ScheduleEntity, List<ScheduleRunEntity>>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    ScreenScaffold("Rappels et planifications", snackbar = snackbar, actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Ajouter") } }) { pad ->
        if (list.isEmpty()) EmptyState("Aucune planification. Dites « Rappelle-moi dans 5 minutes de boire de l'eau » ou touchez +.", Modifier.padding(pad))
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { s ->
                val action = remember(s.actionJson) { runCatching { ScheduleAction.parse(s.actionJson) }.getOrNull() }
                val spec = remember(s.specJson) { runCatching { ScheduleSpec.parse(s.specJson) }.getOrNull() }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((when (s.kind) { ScheduleKinds.REMINDER -> "🔔 "; ScheduleKinds.CONDITION -> "👁 "; else -> "⚙️ " }) + s.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                (if (s.kind == ScheduleKinds.REMINDER) "Rappel (sans IA)" else "Tâche : ${action?.objective ?: ""}") +
                                    " · " + (spec?.describe(ZoneId.systemDefault()) ?: s.kind) +
                                    (spec?.condition?.let { " · si ${it.describe()}" } ?: "") +
                                    (if (s.kind == ScheduleKinds.REMINDER) "" else " · ${concurrencyLabel(s.concurrencyPolicy)}"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Prochaine : ${s.nextRunAt?.let { TimeFmt.full(it) } ?: "—"}" + (s.lastRunAt?.let { " · dernière : ${TimeFmt.short(it)} (${s.lastOutcome ?: ""})" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.enabled, { v -> scope.launch { c.scheduler.setEnabled(s.id, v) } })
                    }
                    Row {
                        TextButton(onClick = { scope.launch { c.scheduler.runNow(s.id); snackbar.showSnackbar("Exécuté maintenant") } }) { Text("Exécuter maintenant") }
                        if (s.kind != ScheduleKinds.REMINDER) TextButton(onClick = { scope.launch { history = s to c.scheduler.runs(s.id, 15) } }) { Text("Historique") }
                        TextButton(onClick = { deleting = s }) { Text("Supprimer") }
                    }
                }
            }
        }
    }
    deleting?.let { s -> ConfirmDialog("Supprimer ?", "« ${s.name} » ne sera plus déclenché.", "Supprimer", { scope.launch { c.scheduler.delete(s.id) } }, { deleting = null }) }
    if (adding) AddScheduleDialog(onDismiss = { adding = false }, onError = { scope.launch { snackbar.showSnackbar(it) } })
    history?.let { (s, runs) ->
        AlertDialog(onDismissRequest = { history = null }, title = { Text("Exécutions · ${s.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (runs.isEmpty()) Text("Aucune exécution pour l'instant.", style = MaterialTheme.typography.bodySmall)
                    runs.forEach { r ->
                        Text("${TimeFmt.short(r.queuedAt)} · ${runLabel(r.status)}${if (r.late) " (en retard)" else ""}" + (r.detail?.let { " — " + it.replace(FINGERPRINT, "") } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { history = null }) { Text("Fermer") } })
    }
}

private val FINGERPRINT = Regex("^fp=[0-9a-f]{16} · ")

private fun concurrencyLabel(p: String) = when (p) {
    "skip" -> "jamais deux à la fois"; "queue" -> "une en attente au plus"; "replace" -> "la plus récente remplace"; "allow" -> "chevauchement permis"; else -> p
}

private fun runLabel(status: String) = when (status) {
    "queued" -> "en file"; "running" -> "en cours"; "succeeded" -> "réussie"; "failed" -> "échouée"; "cancelled" -> "annulée"; "skipped" -> "ignorée"
    "condition_not_met" -> "condition non remplie"; "waiting" -> "attend le propriétaire"; "interrupted" -> "interrompue"; else -> status
}

@Composable
private fun AddScheduleDialog(onDismiss: () -> Unit, onError: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var isTask by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var inMinutes by remember { mutableStateOf("") }
    var at by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("none") }
    var every by remember { mutableStateOf("60") }
    var cron by remember { mutableStateOf("0 8 * * *") }
    var concurrency by remember { mutableStateOf("skip") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle planification") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!isTask, { isTask = false }, label = { Text("Rappel") })
                    FilterChip(isTask, { isTask = true }, label = { Text("Tâche pour Cortana") })
                }
                OutlinedTextField(text, { text = it }, label = { Text(if (isTask) "Objectif (ex. Résume les actualités tech)" else "Message du rappel") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "Une fois", "every" to "Intervalle", "cron" to "Cron").forEach { (k, l) -> FilterChip(repeat == k, { repeat = k }, label = { Text(l) }) }
                }
                when (repeat) {
                    "none" -> {
                        OutlinedTextField(inMinutes, { inMinutes = it.filter(Char::isDigit) }, label = { Text("Dans N minutes") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(at, { at = it }, label = { Text("ou à (HH:mm ou AAAA-MM-JJ HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "every" -> OutlinedTextField(every, { every = it.filter(Char::isDigit) }, label = { Text("Toutes les N minutes (≥ 15)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    else -> OutlinedTextField(cron, { cron = it }, label = { Text("Cron : minute heure jour mois jour-semaine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (isTask && repeat != "none") {
                    Text("Si la précédente n'est pas finie :", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("skip" to "Ignorer", "queue" to "Attendre", "replace" to "Remplacer").forEach { (k, l) -> FilterChip(concurrency == k, { concurrency = k }, label = { Text(l) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                scope.launch {
                    val zone = ZoneId.systemDefault()
                    val now = System.currentTimeMillis()
                    try {
                        val spec = when (repeat) {
                            "every" -> ScheduleSpec(everyMinutes = (every.toIntOrNull() ?: 60).coerceAtLeast(15), startAt = now + (every.toIntOrNull() ?: 60).coerceAtLeast(15) * 60_000L)
                            "cron" -> ScheduleSpec(cron = cron.trim().also { CronExpression.parse(it) })
                            else -> ScheduleSpec(at = inMinutes.toIntOrNull()?.let { now + it * 60_000L } ?: FastPaths.parseAt(at, zone, now)
                                ?: throw IllegalArgumentException("Indiquez « dans N minutes » ou une heure"))
                        }
                        val kind = if (!isTask) ScheduleKinds.REMINDER else when (repeat) { "every" -> ScheduleKinds.INTERVAL; "cron" -> ScheduleKinds.CRON; else -> ScheduleKinds.ONCE }
                        val action = if (isTask) ScheduleAction("task", objective = text.trim()) else ScheduleAction("notify", message = text.trim())
                        c.scheduler.create(text.trim().take(60), kind, spec, action, zone, concurrency = if (isTask) concurrency else "skip")
                        onDismiss()
                    } catch (e: IllegalArgumentException) {
                        onError(e.message ?: "Planification invalide")
                    }
                }
            }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
