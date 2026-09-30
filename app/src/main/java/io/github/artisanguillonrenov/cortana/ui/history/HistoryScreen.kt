package io.github.artisanguillonrenov.cortana.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.ui.common.EmptyState
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.delay

/** §11/§14 — history with SQLite FTS search across all conversations. */
@Composable
fun HistoryScreen(onOpenSession: (String) -> Unit) {
    val c = LocalContainer.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    val sessions by c.conversations.observeSessions().collectAsState(initial = emptyList())
    val titles = sessions.associate { it.id to it.title }
    LaunchedEffect(query) {
        delay(250)
        results = if (query.isBlank()) emptyList() else c.conversations.search(query)
    }
    ScreenScaffold("Historique") { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Rechercher dans toutes les discussions…") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (query.isBlank()) {
                if (sessions.isEmpty()) EmptyState("Aucune discussion pour l'instant.")
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }.padding(vertical = 12.dp)) {
                            Text((if (s.incognito) "🕶 " else "") + s.title, style = MaterialTheme.typography.titleSmall)
                            Text("${TimeFmt.short(s.updatedAt)} · ${s.modelId ?: "modèle par défaut"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                if (results.isEmpty()) EmptyState("Aucun résultat pour « $query ».")
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenSession(m.sessionId) }.padding(vertical = 10.dp)) {
                            Text("${titles[m.sessionId] ?: "Discussion"} · ${TimeFmt.short(m.createdAt)} · ${if (m.role == "user") "vous" else "Cortana"}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(m.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
