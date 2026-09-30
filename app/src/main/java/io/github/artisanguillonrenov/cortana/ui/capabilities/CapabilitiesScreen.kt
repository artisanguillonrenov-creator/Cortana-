package io.github.artisanguillonrenov.cortana.ui.capabilities

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.artisanguillonrenov.cortana.core.permissions.AccessRow
import io.github.artisanguillonrenov.cortana.core.permissions.CapabilityRow
import io.github.artisanguillonrenov.cortana.executors.permissions.AndroidAccessProbe
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

/** Capabilities & permissions (doc 05 §21, phase 32): state, reason, risk, fix, last use, revocation, dependencies. */
@Composable
fun CapabilitiesScreen() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CapabilityRow>>(emptyList()) }
    var accesses by remember { mutableStateOf<List<AccessRow>>(emptyList()) }
    var onlyIssues by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { rows = c.capabilityHealth.rows(); accesses = c.capabilityHealth.accesses() }
    // A permission changed in Android's settings is seen as soon as the owner comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    ScreenScaffold("Capacités et permissions") { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard("Accès Android") {
                    accesses.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (a.granted) "✓ " else "✗ ") + a.access.label, style = MaterialTheme.typography.titleSmall,
                                    color = if (a.granted) Cortana.colors.successText else if (a.blocking > 0) MaterialTheme.colorScheme.error else Cortana.colors.warningText)
                                Text("Pour ${a.access.why}" + if (a.dependents.isNotEmpty()) " · ${a.dependents.size} capacité(s) (${a.blocking} bloquée(s) sans lui)" else "", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { runCatching { ctx.startActivity(AndroidAccessProbe.fixIntent(ctx, a.access)) } }) { Text(if (a.granted) "Gérer" else "Corriger") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(onlyIssues, { onlyIssues = true }, label = { Text("À corriger (${rows.count { it.status != "available" }})") })
                    FilterChip(!onlyIssues, { onlyIssues = false }, label = { Text("Toutes (${rows.size})") })
                }
            }
            items(rows.filter { !onlyIssues || it.status != "available" || it.activeGrants > 0 }, key = { it.capability }) { r ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(r.risk.name, style = MaterialTheme.typography.labelMedium, color = when (r.risk.level) { 0, 1 -> MaterialTheme.colorScheme.onSurfaceVariant; 2 -> Cortana.colors.warningText; else -> MaterialTheme.colorScheme.error })
                    }
                    Text("${r.capability} · ${when (r.status) { "available" -> "disponible"; "degraded" -> "dégradée"; "stopped" -> "arrêtée (STOP)"; else -> "indisponible" }}", style = MaterialTheme.typography.bodySmall)
                    r.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    if (r.needs.isNotEmpty()) Text("Dépend de : " + r.needs.joinToString { it.access.label + if (it.blocking) "" else " (conseillé)" }, style = MaterialTheme.typography.bodySmall)
                    Text("Dernière utilisation : " + (r.lastUsedAt?.let { "${TimeFmt.short(it)} (${r.uses} fois)" } ?: "jamais"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        r.missing.firstOrNull()?.let { a -> TextButton(onClick = { runCatching { ctx.startActivity(AndroidAccessProbe.fixIntent(ctx, a)) } }) { Text("Corriger") } }
                        if (r.activeGrants > 0) TextButton(onClick = { scope.launch { c.capabilityHealth.revokeGrants(r.capability); refresh++ } }) { Text("Révoquer les autorisations (${r.activeGrants})", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
