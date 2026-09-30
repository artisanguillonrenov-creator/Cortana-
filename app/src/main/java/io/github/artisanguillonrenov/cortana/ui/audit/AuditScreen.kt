package io.github.artisanguillonrenov.cortana.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

/** §16 — hash-chained audit log viewer with integrity verification. */
@Composable
fun AuditScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val rows by remember { c.audit.observeRecent(300) }.collectAsState(initial = emptyList())
    var verify by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Journal d'audit") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            actions = {
                TextButton(onClick = { scope.launch { verify = c.audit.verify()?.let { "⚠️ Chaîne rompue à l'entrée $it" } ?: "✓ Chaîne intacte" } }) { Text("Vérifier") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            verify?.let { Text(it, Modifier.padding(16.dp)) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.seq }) { a ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${TimeFmt.short(a.occurredAt)} · ${a.actor} · ${a.action} → ${a.outcome}", style = MaterialTheme.typography.bodyMedium)
                        a.targetJson?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (a.metaJson != "{}") Text(a.metaJson, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("#${a.hash.take(12)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
