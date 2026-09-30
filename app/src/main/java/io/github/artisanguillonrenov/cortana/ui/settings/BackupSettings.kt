package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.backup.BackupOptions
import io.github.artisanguillonrenov.cortana.core.backup.Inspection
import io.github.artisanguillonrenov.cortana.core.backup.RestoreMode
import io.github.artisanguillonrenov.cortana.core.backup.RestoreReport
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch
import java.io.File

/** Backup and restore (phase 29): encrypted export, local copies, verified restore with simulation. */
@Composable
fun BackupSettingsSection() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var secrets by remember { mutableStateOf(false) }
    var withArtifacts by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(c.backups.list()) }
    var restoring by remember { mutableStateOf<File?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { c.backups.importFrom(ctx, uri) }.onSuccess { files = c.backups.list(); restoring = it }.onFailure { message = it.message } }
    }
    SectionCard("Sauvegarde et restauration") {
        Text("Une sauvegarde contient discussions, mémoire, procédures, planifications, fournisseurs, connexions (sans leurs secrets), réglages et historique des tâches. Les secrets n'y figurent que si vous le demandez, et alors toujours chiffrés par votre phrase de passe.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(pass, { pass = it }, label = { Text("Phrase de passe (facultative, 12 caractères au moins)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (pass.isNotEmpty()) OutlinedTextField(pass2, { pass2 = it }, label = { Text("Confirmer la phrase de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Inclure les secrets (clés, jetons)", Modifier.weight(1f)); Switch(secrets && pass.isNotEmpty(), { secrets = it }, enabled = pass.isNotEmpty())
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Inclure les artefacts", Modifier.weight(1f)); Switch(withArtifacts, { withArtifacts = it }) }
        OutlinedButton(enabled = !busy && (pass.isEmpty() || pass == pass2), onClick = {
            scope.launch {
                busy = true
                message = runCatching {
                    val ids = if (withArtifacts) c.artifacts.list(200).filter { !it.deleted }.map { it.artifactId } else emptyList()
                    val f = c.backups.newFile()
                    c.backups.create(f, BackupOptions(pass.ifEmpty { null }, secrets && pass.isNotEmpty(), ids))
                    pass = ""; pass2 = ""; secrets = false
                    "Sauvegarde créée : ${f.name}"
                }.getOrElse { "Échec : ${it.message}" }
                files = c.backups.list(); busy = false
            }
        }) { Text(if (busy) "…" else "Créer une sauvegarde") }
        files.take(8).forEach { f ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${f.name} · ${f.length() / 1024} Ko · ${TimeFmt.short(f.lastModified())}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { scope.launch { message = c.backups.exportToDownloads(ctx, f)?.let { "Copiée dans $it" } ?: "Copie impossible" } }) { Text("Copier dans Téléchargements") }
                    TextButton(onClick = { restoring = f }) { Text("Restaurer…") }
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Restaurer depuis un fichier…") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    restoring?.let { f -> RestoreDialog(f, onDone = { restoring = null; message = it; files = c.backups.list() }) }
}

@Composable
private fun RestoreDialog(file: File, onDone: (String?) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<Inspection?>(null) }
    var merge by remember { mutableStateOf<RestoreReport?>(null) }
    var replace by remember { mutableStateOf<RestoreReport?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun check() = scope.launch {
        error = null
        val i = c.backups.inspect(file, pass.ifEmpty { null }); inspection = i
        if (i.verified && i.compatible) {
            merge = runCatching { c.backups.restore(file, pass.ifEmpty { null }, RestoreMode.MERGE, dryRun = true) }.getOrElse { error = it.message; null }
            replace = runCatching { c.backups.restore(file, pass.ifEmpty { null }, RestoreMode.REPLACE, dryRun = true) }.getOrNull()
        }
    }
    fun apply(mode: RestoreMode) = scope.launch {
        if (c.orchestrator.isBusy()) { error = "Une tâche est en cours : attendez qu'elle se termine."; return@launch }
        runCatching { c.backups.restore(file, pass.ifEmpty { null }, mode, dryRun = false) }
            .onSuccess { onDone(it.summary() + " — état précédent sauvegardé.") }.onFailure { error = it.message }
    }
    if (inspection == null) check()
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text("Restaurer ${file.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val i = inspection
                i?.manifest?.let { m ->
                    Text("Créée le ${TimeFmt.full(m.createdAt)} par Cortana ${m.compatibility.appVersion} (schéma v${m.compatibility.dbSchema})" +
                        (if (m.encryption != null) " · chiffrée" else "") + (if (m.includesSecrets) " · avec secrets" else "") + (if (m.artifacts.isNotEmpty()) " · ${m.artifacts.size} artefact(s)" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (i != null && !i.verified) {
                    Text(i.problems.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (i.manifest?.encryption != null) {
                        OutlinedTextField(pass, { pass = it }, label = { Text("Phrase de passe") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { check() }) { Text("Vérifier") }
                    }
                }
                if (i != null && i.verified && !i.compatible) Text(i.problems.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                merge?.let { Text("Fusionner : " + it.summary().substringAfter(": "), style = MaterialTheme.typography.bodySmall) }
                replace?.let { Text("Remplacer : " + it.summary().substringAfter(": "), style = MaterialTheme.typography.bodySmall) }
                if (merge != null) Text("La fusion garde vos données actuelles en cas de conflit ; le remplacement les remplace par celles de la sauvegarde. Dans les deux cas, l'état actuel est d'abord sauvegardé.", style = MaterialTheme.typography.bodySmall)
                if (confirmReplace) Text("Confirmer le remplacement de vos données actuelles ?", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = merge != null, onClick = { apply(RestoreMode.MERGE) }) { Text("Fusionner") }
                TextButton(enabled = replace != null, onClick = { if (confirmReplace) apply(RestoreMode.REPLACE) else confirmReplace = true }) { Text(if (confirmReplace) "Remplacer" else "Remplacer…", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Annuler") } })
}
