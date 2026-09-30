package io.github.artisanguillonrenov.cortana.ui.health

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.artisanguillonrenov.cortana.BuildConfig
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.ui.common.StatusDot
import io.github.artisanguillonrenov.cortana.ui.common.rememberFixHandler

/** §14 — works even when nothing else is configured. Re-checks on every return to the screen. */
@Composable
fun HealthScreen(onProviders: () -> Unit, onCapabilities: () -> Unit = {}) {
    val c = LocalContainer.current
    var items by remember { mutableStateOf<List<HealthItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val fix = rememberFixHandler(onProviders) { refresh++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {
        loading = true
        items = runCatching { c.health.run() }.getOrElse { listOf(HealthItem("err", "Diagnostic", HealthStatus.ERROR, it.message ?: "Erreur")) }
        loading = false
    }
    ScreenScaffold("Santé et diagnostic", actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Actualiser") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { androidx.compose.material3.OutlinedButton(onClick = onCapabilities, modifier = Modifier.fillMaxWidth()) { Text("Capacités et permissions : état, corrections, révocations") } }
                items(items, key = { it.id }) { h ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatusDot(h.status)
                            Text(h.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (h.fix != Fix.NONE && h.fixLabel != null) OutlinedButton(onClick = { fix(h.fix) }) { Text(h.fixLabel) }
                        }
                        Text(h.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { CommsCard() }
                item { DoctorCard() }
                item {
                    Text("Cortana ${BuildConfig.VERSION_NAME} · invite système v${c.contextEngine.promptVersion} · ${c.registry.all().size} capacités enregistrées",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
