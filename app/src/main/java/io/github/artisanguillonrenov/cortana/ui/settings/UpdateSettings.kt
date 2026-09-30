package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.BuildConfig
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.update.UpdateState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Update system (phase 30): check the signed channel, prepare (backup + verified download), hand off to Android. */
@Composable
fun UpdateSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val state by c.updates.state.collectAsState()
    var url by remember(s.updateManifestUrl) { mutableStateOf(s.updateManifestUrl.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> String?) = scope.launch { busy = true; message = runCatching { block() }.getOrElse { "Refusé : ${it.message}" }; busy = false }
    SectionCard("Mises à jour") {
        Text("Version installée : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Une mise à jour n'est acceptée que si son manifeste est signé par la clé de Cortana, si l'APK a la même signature, le même paquet, une version supérieure et un schéma compatible. Vos données sont sauvegardées avant, et Android vous demande de confirmer l'installation.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, label = { Text("Adresse du manifeste (https://…/cortana-update.json)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vérifier chaque jour", Modifier.weight(1f)); Switch(s.updateAutoCheck, { v -> upd { it.copy(updateAutoCheck = v) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { upd { it.copy(updateManifestUrl = url.trim().ifEmpty { null }) }; message = "Source enregistrée" }) { Text("Enregistrer") }
            OutlinedButton(enabled = !busy && s.updateManifestUrl != null, onClick = { act { null.also { c.updates.check() } } }) { Text("Vérifier maintenant") }
        }
        when (val st = state) {
            is UpdateState.UpToDate -> Text("À jour.", style = MaterialTheme.typography.bodySmall)
            is UpdateState.Refused -> Text("Refusée : " + st.reasons.joinToString(" ; "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is UpdateState.Available -> {
                Text("Disponible : ${st.manifest.versionName} (${st.manifest.versionCode}) · ${st.manifest.size / 1_000_000} Mo", style = MaterialTheme.typography.titleSmall)
                if (st.manifest.releaseNotes.isNotBlank()) Text(st.manifest.releaseNotes.take(1500), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { act { c.updates.prepare(st.manifest).let { "Prête : sauvegarde ${it.backup.name}, APK vérifié" } } }) { Text("Préparer (sauvegarde + téléchargement vérifié)") }
            }
            is UpdateState.Ready -> {
                Text("Prête à installer : ${st.manifest.versionName}. Sauvegarde : ${st.backup.name}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 ${st.manifest.sha256.take(16)}…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { act { c.updates.install(st) } }) { Text("Installer") }
            }
            is UpdateState.HandedOff -> Text(st.detail, style = MaterialTheme.typography.bodySmall)
            UpdateState.Idle -> Unit
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
