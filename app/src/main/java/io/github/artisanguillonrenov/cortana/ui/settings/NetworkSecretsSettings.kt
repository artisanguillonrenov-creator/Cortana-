package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.policy.EgressRules
import io.github.artisanguillonrenov.cortana.core.secrets.SecretRef
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Phase 32: network egress policy (mode, known and blocked hosts) and the secret inventory. */
@Composable
fun NetworkSecretsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var refs by remember { mutableStateOf<List<SecretRef>>(emptyList()) }
    var orphans by remember { mutableStateOf(0) }
    var rotating by remember { mutableStateOf<SecretRef?>(null) }
    var value by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { refs = c.secretInventory.references(); orphans = c.secretInventory.orphans().size }
    LaunchedEffect(Unit) { runCatching { reload() } }

    SectionCard("Réseau") {
        Text("Où les actions de Cortana peuvent envoyer des données. Les hôtes bloqués ne sont jamais joints, par aucune partie de Cortana.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(EgressRules.STANDARD to "Standard", EgressRules.CONFIRM_NEW to "Confirmer les nouvelles", EgressRules.KNOWN_ONLY to "Connues seulement").forEach { (m, l) ->
                FilterChip(s.egressMode == m, { upd { it.copy(egressMode = m) } }, label = { Text(l) })
            }
        }
        Text(when (s.egressMode) {
            EgressRules.KNOWN_ONLY -> "Seules les destinations connues sont permises ; les autres sont refusées."
            EgressRules.CONFIRM_NEW -> "Toute destination nouvelle demande votre confirmation, même hors contenu externe."
            else -> "Une destination nouvelle demande confirmation quand la tâche a lu du contenu externe."
        }, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(host, { host = it }, label = { Text("Hôte (ex. exemple.fr)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = host.isNotBlank(), onClick = { val h = EgressRules.normalize(host); upd { it.copy(knownDestinations = (it.knownDestinations + h).distinct()) }; host = "" }) { Text("Autoriser") }
            TextButton(enabled = host.isNotBlank(), onClick = { val h = EgressRules.normalize(host); upd { it.copy(egressBlockedHosts = (it.egressBlockedHosts + h).distinct(), knownDestinations = it.knownDestinations - h) }; host = "" }) { Text("Bloquer", color = MaterialTheme.colorScheme.error) }
        }
        if (s.egressBlockedHosts.isNotEmpty()) Text("Bloqués", style = MaterialTheme.typography.titleSmall)
        s.egressBlockedHosts.forEach { h -> Row(verticalAlignment = Alignment.CenterVertically) { Text(h, Modifier.weight(1f)); TextButton(onClick = { upd { it.copy(egressBlockedHosts = it.egressBlockedHosts - h) } }) { Text("Débloquer") } } }
        if (s.knownDestinations.isNotEmpty()) Text("Destinations connues (${s.knownDestinations.size})", style = MaterialTheme.typography.titleSmall)
        s.knownDestinations.takeLast(30).forEach { h -> Row(verticalAlignment = Alignment.CenterVertically) { Text(h, Modifier.weight(1f)); TextButton(onClick = { upd { it.copy(knownDestinations = it.knownDestinations - h) } }) { Text("Retirer") } } }
    }
    SectionCard("Secrets") {
        Text("Les clés et jetons sont chiffrés sur la tablette ; la base n'en garde que des poignées. Cortana ne montre jamais une valeur.", style = MaterialTheme.typography.bodySmall)
        refs.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (r.present) "🔒" else "⚠"} ${r.kind} · ${r.owner}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = if (r.present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                TextButton(onClick = { rotating = r; value = "" }) { Text(if (r.present) "Remplacer" else "Saisir") }
            }
            if (rotating == r) {
                OutlinedTextField(value, { value = it }, label = { Text("Nouvelle valeur") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(enabled = value.isNotBlank(), onClick = { scope.launch { message = runCatching { c.secretInventory.rotate(r.handle, value); value = ""; rotating = null; reload(); "Secret remplacé" }.getOrElse { it.message } } }) { Text("Enregistrer") }
            }
        }
        if (orphans > 0) TextButton(onClick = { scope.launch { message = "${c.secretInventory.purgeOrphans()} secret(s) inutilisé(s) effacé(s)"; reload() } }) { Text("Effacer $orphans secret(s) inutilisé(s)") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
