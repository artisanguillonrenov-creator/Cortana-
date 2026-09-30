package io.github.artisanguillonrenov.cortana.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.MemoryEntity
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

private val typeLabels = mapOf(
    MemoryTypes.PROFILE to "Profil", MemoryTypes.PREFERENCE to "Préférence", MemoryTypes.SEMANTIC to "Fait", MemoryTypes.EPISODIC to "Épisode",
)

/** §11/§14 — facts, pending confirmations, edit (supersession) and forget. */
@Composable
fun MemoryScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val active by c.memory.observe(MemoryStatus.ACTIVE).collectAsState(initial = emptyList())
    val pending by c.memory.observe(MemoryStatus.PENDING).collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    ScreenScaffold("Mémoire", actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Ajouter") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Souvenirs (${active.size})") })
                Tab(tab == 1, { tab = 1 }, text = {
                    BadgedBox(badge = { if (pending.isNotEmpty()) Badge { Text("${pending.size}") } }) { Text("À confirmer  ") }
                })
            }
            val list = if (tab == 0) active else pending
            if (list.isEmpty()) {
                EmptyState(if (tab == 0) "Aucun souvenir. Dites par exemple « Retiens que je préfère des réponses courtes »." else "Rien à confirmer.")
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { m ->
                    SectionCard {
                        Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        Text("${typeLabels[m.type] ?: m.type} · ${TimeFmt.short(m.updatedAt)} · importance ${m.importance}" +
                            (if (m.provenanceJson.contains("\"explicit\"")) " · demandé explicitement" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (m.status == MemoryStatus.PENDING) {
                                TextButton(onClick = { scope.launch { c.memory.confirm(m.id) } }) { Text("Confirmer") }
                                TextButton(onClick = { scope.launch { c.memory.reject(m.id) } }) { Text("Rejeter") }
                            } else {
                                TextButton(onClick = { editing = m }) { Text("Modifier") }
                                TextButton(onClick = { scope.launch { c.memory.forget(m.id); c.audit.record("owner", "memory.forget", m.text.take(60), "ok") } }) { Text("Oublier") }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Modifier le souvenir") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { scope.launch { c.memory.edit(m.id, text) }; editing = null }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } },
        )
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(MemoryTypes.PREFERENCE) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Nouveau souvenir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ex. Je préfère des réponses courtes") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(MemoryTypes.PREFERENCE, MemoryTypes.PROFILE, MemoryTypes.SEMANTIC).forEach { t ->
                            FilterChip(type == t, { type = t }, label = { Text(typeLabels[t]!!) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch { c.memory.save(text, type, MemoryStatus.ACTIVE, "owner_manual") }; adding = false
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } },
        )
    }
}
