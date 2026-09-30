package io.github.artisanguillonrenov.cortana.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.connections.ConnectorKinds
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionEntity
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Connections (phase 26): add, authorize, test, disable, revoke — the owner's view of the ConnectionManager. */
@Composable
fun ConnectionSettingsSection() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by c.connections.observe().collectAsState(initial = emptyList())
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRevoke by remember { mutableStateOf<ConnectionEntity?>(null) }
    var details by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    SectionCard("Connexions") {
        Text("Comptes et services externes : API, webhooks, Home Assistant, e-mail, Telegram. Les secrets restent chiffrés sur la tablette ; chaque action passe par la même politique.", style = MaterialTheme.typography.bodySmall)
        all.forEach { e ->
            val kind = ConnectorKinds.get(e.kind)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.name} · ${kind?.label ?: e.kind}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(e.state == "active" || e.state == "pending_auth", { v -> scope.launch { runCatching { c.connections.setEnabled(e.connectionId, v) }.onFailure { error = it.message } } },
                        enabled = e.state != "revoked")
                }
                Text("État : ${label(e.state)} · santé : ${e.health}${e.lastError?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                c.connections.configOf(e)["hook_url"]?.let { Text("Adresse à donner à l'expéditeur : $it", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (e.state == "pending_auth") TextButton(onClick = { scope.launch { runCatching { open(c.connections.beginOAuth(e.connectionId)) }.onFailure { error = it.message } } }) { Text("Se connecter") }
                    if (e.state == "active") TextButton(onClick = { scope.launch { runCatching { c.connections.check(e.connectionId) }.onFailure { error = it.message } } }) { Text("Tester") }
                    TextButton(onClick = { scope.launch { details = e.name to c.connections.events(e.connectionId, 10).map { ev -> "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ev.at))} · ${ev.type} · ${ev.outcome} · ${ev.detail}" } } }) { Text("Historique") }
                    if (e.state != "revoked") TextButton(onClick = { confirmRevoke = e }) { Text("Révoquer", color = MaterialTheme.colorScheme.error) }
                    else TextButton(onClick = { scope.launch { c.connections.remove(e.connectionId) } }) { Text("Supprimer") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        AddConnection(onError = { error = it }, onOAuth = ::open)
    }
    confirmRevoke?.let { e ->
        AlertDialog(onDismissRequest = { confirmRevoke = null },
            title = { Text("Révoquer « ${e.name} » ?") },
            text = { Text("Les jetons sont révoqués chez le fournisseur quand c'est possible, tous les secrets sont effacés de la tablette et plus aucun outil ne peut l'utiliser. L'historique est conservé.") },
            confirmButton = { TextButton(onClick = { scope.launch { runCatching { c.connections.revoke(e.connectionId) }.onFailure { error = it.message } }; confirmRevoke = null }) { Text("Révoquer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("Annuler") } })
    }
    details?.let { (name, lines) ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("Historique · $name") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { (lines.ifEmpty { listOf("Aucun événement.") }).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Fermer") } })
    }
}

private fun label(state: String) = when (state) {
    "active" -> "active"; "disabled" -> "désactivée"; "pending_auth" -> "à autoriser"; "revoked" -> "révoquée"; else -> state
}

@Composable
private fun AddConnection(onError: (String?) -> Unit, onOAuth: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var kindId by remember { mutableStateOf(ConnectorKinds.HTTP.id) }
    var auth by remember { mutableStateOf(ConnectorKinds.HTTP.authSchemes.first()) }
    var name by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, String>() }
    val workers by c.workers.observe().collectAsState(initial = emptyList())
    if (!adding) { OutlinedButton(onClick = { adding = true }) { Text("Ajouter une connexion") }; return }
    val kind = ConnectorKinds.get(kindId)!!
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectorKinds.all.take(3).forEach { k -> FilterChip(kindId == k.id, { kindId = k.id; auth = k.authSchemes.first(); values.clear() }, label = { Text(k.label) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConnectorKinds.all.drop(3).forEach { k -> FilterChip(kindId == k.id, { kindId = k.id; auth = k.authSchemes.first(); values.clear() }, label = { Text(k.label) }) }
    }
    Text(kind.description, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(name, { name = it.lowercase() }, label = { Text("Nom court (ex. maison, boite-pro)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (kind.authSchemes.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { kind.authSchemes.forEach { a -> FilterChip(auth == a, { auth = a }, label = { Text(a) }) } }
    val oauthOnly = kind.fields.filter { it.key.startsWith("oauth_") }
    kind.fields.filter { it !in oauthOnly || auth == "oauth2" }.forEach { f ->
        if (f.key == "worker_id") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { workers.filter { !it.revoked }.forEach { w -> FilterChip(values["worker_id"] == w.workerId, { values["worker_id"] = w.workerId }, label = { Text(w.name) }) } }
            if (workers.none { !it.revoked }) Text("Aucun worker appairé : un webhook entrant a besoin d'un worker joignable depuis l'extérieur.", style = MaterialTheme.typography.bodySmall)
        } else OutlinedTextField(values[f.key] ?: "", { values[f.key] = it }, label = { Text(f.label + (f.default?.let { " (défaut : $it)" } ?: "") + if (!f.required) " — facultatif" else "") },
            singleLine = f.key != "objective", visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { adding = false; values.clear(); onError(null) }) { Text("Annuler") }
        TextButton(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                runCatching {
                    val secretKeys = kind.fields.filter { it.secret }.map { it.key }.toSet()
                    val e = c.connections.add(kind.id, name.trim(), values.filterKeys { it !in secretKeys }.filterValues { it.isNotBlank() }, values.filterKeys { it in secretKeys }.filterValues { it.isNotBlank() }, auth)
                    values.clear(); adding = false; onError(null)
                    if (e.authScheme == "oauth2") onOAuth(c.connections.beginOAuth(e.connectionId)) else c.connections.check(e.connectionId)
                }.onFailure { onError(it.message) }
            }
        }) { Text(if (auth == "oauth2") "Ajouter et se connecter" else "Ajouter et tester") }
    }
}
