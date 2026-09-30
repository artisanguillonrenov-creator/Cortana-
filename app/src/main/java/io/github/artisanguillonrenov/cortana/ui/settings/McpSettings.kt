package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.mcp.McpAdapter
import io.github.artisanguillonrenov.cortana.core.mcp.McpEndpoints
import io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/**
 * Serveurs MCP (phase 20): add an HTTP endpoint (token kept in the secret store) or a stdio server
 * declared on a paired worker; see state, protocol era, tools, rejected or suspicious tools; set
 * trust and per-tool policy. External tools always go through the same policy as the others.
 */
@Composable
fun McpSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val status by c.mcp.status.collectAsState()
    val scope = rememberCoroutineScope()
    SectionCard("Serveurs MCP") {
        Text("Outils externes au standard MCP. Leurs outils rejoignent ceux de Cortana avec la même politique : accord selon le risque, résultats traités comme non fiables. Les indications d'un serveur non « fiable » ne baissent jamais le risque.", style = MaterialTheme.typography.bodySmall)
        s.mcpServers.forEach { srv ->
            val st = status[srv.id]
            var open by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (st?.state) { "ok" -> "🟢"; "error" -> "🔴"; "disabled" -> "⚪"; else -> "🟡" } + " ${srv.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { scope.launch { c.mcp.sync(srv.id) } }) { Text("Actualiser") }
                    TextButton(onClick = { open = !open }) { Text(if (open) "Moins" else "Détails") }
                }
                Text((if (srv.transport == "worker") "stdio sur le worker · ${srv.stdioName}" else srv.url.orEmpty()) +
                    (st?.version?.let { " · MCP $it (${if (st.era == "modern") "moderne" else "ancien"})" } ?: "") +
                    " · ${st?.tools?.size ?: 0} outil(s)", style = MaterialTheme.typography.bodySmall)
                st?.lastError?.let { Text("Erreur : $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (open) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activé", Modifier.weight(1f)); Switch(srv.enabled, { v -> save(upd, srv.copy(enabled = v)); scope.launch { c.mcp.sync(srv.id) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Serveur fiable (ses outils en lecture seule passent en L1)", Modifier.weight(1f)); Switch(srv.trusted, { v -> save(upd, srv.copy(trusted = v)); scope.launch { c.mcp.sync(srv.id) } })
                    }
                    st?.flagged?.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodySmall) }
                    st?.rejected?.forEach { Text("⛔ $it", style = MaterialTheme.typography.bodySmall) }
                    val names = (st?.tools.orEmpty().map { c.registry.byCapability(it)?.let { d -> d.label.substringBefore(" (MCP") to d.baseRisk.name } ?: (it to "?") } +
                        st?.hidden.orEmpty().map { it to "off" })
                    if (names.isNotEmpty()) Text("Outils (appuyer pour changer : auto → L1 → L2 → L3 → masqué)", style = MaterialTheme.typography.labelLarge)
                    names.forEach { (label, risk) ->
                        val key = srv.toolPolicy.keys.firstOrNull { k -> label == k } ?: label
                        val cur = srv.toolPolicy[key]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            FilterChip(cur != null, onClick = {
                                val next = when (cur) { null -> "L1"; "L1" -> "L2"; "L2" -> "L3"; "L3" -> "off"; else -> null }
                                save(upd, srv.copy(toolPolicy = if (next == null) srv.toolPolicy - key else srv.toolPolicy + (key to next)))
                                scope.launch { c.mcp.sync(srv.id) }
                            }, label = { Text(cur ?: "auto ($risk)") })
                        }
                    }
                    TextButton(onClick = {
                        c.secrets.remove(srv.authHandle)
                        upd { it.copy(mcpServers = it.mcpServers.filterNot { x -> x.id == srv.id }) }
                        c.mcp.remove(srv.id)
                    }) { Text("Supprimer ce serveur", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        AddMcpServer(s, upd)
    }
}

private fun save(upd: ((AppSettings) -> AppSettings) -> Unit, srv: McpServerConfig) = upd { st -> st.copy(mcpServers = st.mcpServers.map { if (it.id == srv.id) srv else it }) }

@Composable
private fun AddMcpServer(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val workers by c.workers.observe().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("http") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    var workerId by remember { mutableStateOf<String?>(null) }
    var stdio by remember { mutableStateOf<String?>(null) }
    var stdioNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    if (!adding) { OutlinedButton(onClick = { adding = true }) { Text("Ajouter un serveur MCP") }; return }
    OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(transport == "http", { transport = "http" }, label = { Text("HTTP (adresse)") })
        FilterChip(transport == "worker", { transport = "worker" }, label = { Text("stdio sur un worker") })
    }
    if (transport == "http") {
        OutlinedTextField(url, { url = it }, label = { Text("Adresse (https://…/mcp ; http seulement sur le réseau local)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!oauth, { oauth = false }, label = { Text("Jeton") })
            FilterChip(oauth, { oauth = true }, label = { Text("Connexion OAuth") })
        }
        if (!oauth) OutlinedTextField(token, { token = it }, label = { Text("Jeton d'accès (facultatif, chiffré sur la tablette)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        else Text("Le serveur d'autorisation est découvert depuis le serveur MCP (RFC 9728) ; la connexion apparaît dans Réglages → Connexions.", style = MaterialTheme.typography.bodySmall)
    } else {
        val active = workers.filter { !it.revoked }
        if (active.isEmpty()) Text("Aucun worker appairé (Projets → Workers).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { active.forEach { w -> FilterChip(workerId == w.workerId, { workerId = w.workerId; stdio = null }, label = { Text(w.name) }) } }
        LaunchedEffect(workerId) { stdioNames = workerId?.let { id -> runCatching { c.workers.mcpServers(id) }.getOrElse { e -> error = e.message; emptyList() } }.orEmpty() }
        if (workerId != null && stdioNames.isEmpty()) Text("Ce worker ne déclare aucun serveur stdio (clé « mcpServers » de son worker.json).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { stdioNames.forEach { n -> FilterChip(stdio == n, { stdio = n }, label = { Text(n) }) } }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { adding = false }) { Text("Annuler") }
        TextButton(enabled = name.isNotBlank(), onClick = {
            val id = McpAdapter.serverSlug(name).let { base -> if (s.mcpServers.any { it.id == base }) base + "_" + (s.mcpServers.size + 1) else base }
            val cfg = try {
                if (transport == "http") {
                    McpEndpoints.check(url)
                    val h = token.takeIf { it.isNotBlank() }?.let { t -> c.secrets.newHandle().also { h -> c.secrets.put(h, t.trim()) } }
                    McpServerConfig(id, name.trim(), "http", url = url.trim(), authHandle = h, createdAt = System.currentTimeMillis())
                } else McpServerConfig(id, name.trim(), "worker", workerId = workerId ?: throw IllegalArgumentException("Choisis un worker"),
                    stdioName = stdio ?: throw IllegalArgumentException("Choisis un serveur"), createdAt = System.currentTimeMillis())
            } catch (e: Exception) { error = e.message; return@TextButton }
            token = ""
            scope.launch {
                val final = if (transport == "http" && oauth) {
                    val r = runCatching { c.connections.addOAuthForResource("mcp-" + id.replace('_', '-').take(36), cfg.url!!, null) }
                    r.exceptionOrNull()?.let { error = it.message; return@launch }
                    val (conn, authUrl) = r.getOrThrow()
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    cfg.copy(connection = conn.name)
                } else cfg
                c.settings.update { it.copy(mcpServers = it.mcpServers + final) }; c.mcp.sync(final.id)
            }
            adding = false; name = ""; url = ""; error = null
        }) { Text("Ajouter et connecter") }
    }
}

/** "MCP" of the design's sidebar (developer mode): the MCP servers section as a screen of its own. */
@Composable
fun McpScreen() {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    io.github.artisanguillonrenov.cortana.ui.common.ScreenScaffold("MCP") { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.widthIn(max = 840.dp)) { McpSettingsSection(s) { t -> scope.launch { c.settings.update(t) } } }
        }
    }
}
