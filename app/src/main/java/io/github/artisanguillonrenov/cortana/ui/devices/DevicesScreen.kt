package io.github.artisanguillonrenov.cortana.ui.devices

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.core.memory.WorkerEntity
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

/** Paired workers (doc 03 §16-17, doc 05 §19): pairing, capabilities, health, revocation. */
@Composable
fun DevicesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val workers by c.workers.observe().collectAsState(initial = emptyList())
    var code by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ScreenScaffold("Appareils") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("Appairer un worker") {
                    Text("Sur votre PC : java -jar cortana-worker.jar serve — puis collez ici la ligne cortana-worker://… affichée (code valable 10 minutes). " +
                        "Le worker n'exécute que ce que Cortana lui envoie ; la politique reste sur la tablette.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it.trim() }, label = { Text("cortana-worker://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(enabled = code.startsWith("cortana-worker://") && !busy, onClick = {
                        busy = true
                        scope.launch {
                            info = runCatching { c.workers.pair(code).let { w -> "Worker « ${w.name} » appairé (certificat épinglé)." } }.getOrElse { "Appairage impossible : ${it.message}" }
                            code = ""; busy = false
                        }
                    }) { Text(if (busy) "Appairage…" else "Appairer") }
                    Text("Clé de l'appareil : " + if (c.workers.deviceKey.hardwareBacked) "Android Keystore (matériel)" else "logicielle (Keystore indisponible)", style = MaterialTheme.typography.bodySmall)
                }
            }
            info?.let { item { Text(it) } }
            if (workers.isEmpty()) item { EmptyState("Aucun worker appairé : les compilations et projets non fiables ne peuvent pas s'exécuter en bac à sable isolé.") }
            items(workers, key = { it.workerId }) { w -> WorkerCard(w) { info = it } }
        }
    }
}

@Composable
private fun WorkerCard(w: WorkerEntity, onInfo: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val caps = remember(w.capabilitiesJson) { runCatching { ContractJson.decodeFromString(WorkerCapabilities.serializer(), w.capabilitiesJson) }.getOrNull() }
    SectionCard(w.name) {
        Text(if (w.revoked) "Révoqué" else "Actif" + (w.lastSeenAt?.let { " · vu ${TimeFmt.short(it)}" } ?: ""), color = if (w.revoked) Cortana.colors.dangerText else Cortana.colors.successText)
        Text("${w.baseUrl} · empreinte ${w.certificateSha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
        caps?.let {
            Text("${it.os} ${it.arch} · ${it.cpus} processeurs · bac à sable : ${it.sandboxModes.joinToString()} · réseau : ${it.networkModes.joinToString { m -> m.name.lowercase() }}", style = MaterialTheme.typography.bodySmall)
            Text("Outils : ${it.toolchains.joinToString().ifEmpty { "aucun détecté" }}", style = MaterialTheme.typography.bodySmall)
        }
        w.lastError?.let { Text("Dernière erreur : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!w.revoked) OutlinedButton(onClick = { scope.launch { val r = c.workers.refresh(w.workerId); onInfo(if (r?.lastError == null) "Worker joignable." else "Injoignable : ${r.lastError}") } }) { Text("Tester") }
            if (!w.revoked) TextButton(onClick = { scope.launch { c.workers.revoke(w.workerId); onInfo("Worker révoqué.") } }) { Text("Révoquer") }
            TextButton(onClick = { scope.launch { c.workers.forget(w.workerId); onInfo("Worker oublié.") } }) { Text("Oublier") }
        }
    }
}
