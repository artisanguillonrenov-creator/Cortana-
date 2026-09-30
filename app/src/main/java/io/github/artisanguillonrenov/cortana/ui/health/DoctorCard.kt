package io.github.artisanguillonrenov.cortana.ui.health

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.backup.DoctorCheck
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Database Doctor (phase 29): checks on demand, one explicit repair at a time. */
@Composable
fun DoctorCard() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<DoctorCheck>?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run() = scope.launch { running = true; checks = runCatching { c.doctor.run() }.getOrElse { message = it.message; null }; running = false }
    SectionCard("Diagnostic de la base") {
        Text("Intégrité, index, artefacts, procédures, tâches bloquées, planificateur, secrets manquants, chaîne d'audit, espace disque. Les réparations sont réversibles quand c'est possible (quarantaine plutôt que suppression).",
            style = MaterialTheme.typography.bodySmall)
        checks?.forEach { k ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (k.status) { "ok" -> "✓"; "warn" -> "⚠"; else -> "✗" } + " " + k.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        color = when (k.status) { "ok" -> Cortana.colors.successText; "warn" -> Cortana.colors.warningText; else -> MaterialTheme.colorScheme.error })
                    k.repair?.takeIf { k.status != "ok" }?.let { label ->
                        TextButton(onClick = { scope.launch { message = runCatching { c.doctor.repair(k.id) }.getOrElse { "Refusé : ${it.message}" }; checks = runCatching { c.doctor.run() }.getOrNull() } }) { Text("Réparer") }
                    }
                }
                Text(k.detail + (k.repair?.takeIf { k.status != "ok" }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(enabled = !running, onClick = { run() }) { Text(if (running) "Diagnostic…" else if (checks == null) "Lancer le diagnostic" else "Relancer") }
    }
}
