package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatService
import io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.components.BranchRow
import io.github.artisanguillonrenov.cortana.ui.components.ButtonKind
import io.github.artisanguillonrenov.cortana.ui.components.CardTitle
import io.github.artisanguillonrenov.cortana.ui.components.ConversationKind
import io.github.artisanguillonrenov.cortana.ui.components.ConversationRow
import io.github.artisanguillonrenov.cortana.ui.components.CortanaButton
import io.github.artisanguillonrenov.cortana.ui.components.CortanaCard
import io.github.artisanguillonrenov.cortana.ui.components.DropChip
import io.github.artisanguillonrenov.cortana.ui.components.FileRow
import io.github.artisanguillonrenov.cortana.ui.components.FilterChip
import io.github.artisanguillonrenov.cortana.ui.components.Overline
import io.github.artisanguillonrenov.cortana.ui.components.SearchField
import io.github.artisanguillonrenov.cortana.ui.components.SmallIconButton
import io.github.artisanguillonrenov.cortana.ui.components.Symbol
import io.github.artisanguillonrenov.cortana.ui.components.look
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.workspace.RenameDialog
import io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceViewModel
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------------ state

@Immutable
data class HistoryRowUi(
    val id: String, val title: String, val preview: String, val time: String, val group: String,
    val kind: ConversationKind, val modeLabel: String, val pinned: Boolean, val live: Boolean, val branches: Int,
)

@Immutable
data class HistoryFileUi(val id: String, @DrawableRes val icon: Int, val name: String, val meta: String)

@Immutable
data class HistoryBranchUi(val leafId: String, val label: String, val meta: String, val current: Boolean)

@Immutable
data class HistoryPreviewUi(
    val id: String, val title: String, val kind: ConversationKind, val meta: String,
    val messages: Int, val branches: List<HistoryBranchUi>, val files: List<HistoryFileUi>, val pinned: Boolean,
)

@Immutable
data class HistoryFilterUi(val key: String, val label: String, @DrawableRes val icon: Int, val count: Int)

@Immutable
data class HistoryUi(
    val query: String,
    val filters: List<HistoryFilterUi>,
    val filter: String,
    val project: String,
    val model: String,
    val period: String,
    val groups: List<Pair<String, List<HistoryRowUi>>>,
    val selected: String?,
    val preview: HistoryPreviewUi?,
)

class HistoryActions(
    val query: (String) -> Unit = {},
    val filter: (String) -> Unit = {},
    val select: (String) -> Unit = {},
    val open: (String) -> Unit = {},
    val branch: (String) -> Unit = {},
    val openBranch: (String, String) -> Unit = { _, _ -> },
    val export: () -> Unit = {},
    val download: (String) -> Unit = {},
    /** The drop chips: project, model, period — each opens its own menu. */
    val projectMenu: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    val modelMenu: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    val periodMenu: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    val moreMenu: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
)

// ------------------------------------------------------------------ screen

/** "Historique" (README screen 3): search, filters, conversations by date, and the selected one's preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(ui: HistoryUi, actions: HistoryActions, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    DesignPage(
        "Historique", "Retrouvez vos conversations, fichiers et artefacts.", modifier,
        headerTrailing = {
            SearchField(ui.query, actions.query, "Rechercher titres, messages, fichiers…", Modifier.width(380.dp))
            CortanaButton("Exporter", actions.export, icon = Symbols.Download, padding = PaddingValues(horizontal = 16.dp), gap = 10.dp)
        },
        band = {
            FlowRow(Modifier.padding(top = 18.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                ui.filters.forEach { f -> FilterChip(f.label, ui.filter == f.key, { actions.filter(f.key) }, icon = f.icon, count = "${f.count}") }
                Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(c.dividerAlt))
                MenuChip(ui.project, actions.projectMenu)
                MenuChip(ui.model, actions.modelMenu)
                MenuChip(ui.period, actions.periodMenu)
            }
        },
        side = { HistorySide(ui.preview, actions) },
    ) {
        if (ui.groups.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Symbols.SearchOff, c.textTertiary, 36.dp)
                Text("Aucune conversation ne correspond.", style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.textSecondary)
            }
        }
        ui.groups.forEachIndexed { gi, (group, rows) ->
            item("g-$group") { Overline(group, modifier = Modifier.padding(start = 4.dp, top = if (gi == 0) 4.dp else 12.dp, bottom = 10.dp)) }
            items(rows, key = { it.id }) { r ->
                ConversationRow(r.title, r.preview, r.time, r.kind, r.modeLabel, r.id == ui.selected, { actions.select(r.id) },
                    Modifier.padding(bottom = 10.dp), pinned = r.pinned, liveLabel = if (r.live) "En cours" else null, branches = r.branches)
            }
        }
    }
}

@Composable
private fun MenuChip(label: String, menu: @Composable (Boolean, () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DropChip(label, { open = true })
        menu(open) { open = false }
    }
}

@Composable
private fun HistorySide(p: HistoryPreviewUi?, actions: HistoryActions) {
    val c = Cortana.colors
    if (p == null) {
        CortanaCard { Text("Choisissez une conversation pour voir ses branches, ses fichiers et ses artefacts.", style = CortanaType.Secondary, color = c.textTertiary) }
        return
    }
    val look = p.kind.look(c)
    CortanaCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(CortanaShapes.Md).background(look.tile), contentAlignment = Alignment.Center) { Symbol(look.icon, look.tint, 26.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, style = CortanaType.PanelTitle.copy(fontSize = 17.sp), color = c.textStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.meta, style = CortanaType.Caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${p.messages}", "messages", Modifier.weight(1f))
            Stat("${p.branches.size.coerceAtLeast(1)}", "branches", Modifier.weight(1f))
            Stat("${p.files.size}", "artefacts", Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CortanaButton("Ouvrir", { actions.open(p.id) }, Modifier.weight(1f), ButtonKind.Primary, Symbols.OpenInNew, 44.dp, 10.dp, fill = true)
            CortanaButton("Brancher", { actions.branch(p.id) }, kind = ButtonKind.Secondary, icon = Symbols.CallSplit, height = 44.dp, corner = 10.dp)
            var more by remember { mutableStateOf(false) }
            Box {
                SmallIconButton(Symbols.MoreHoriz, "Plus d'actions sur la conversation", { more = true }, size = 44.dp, iconSize = 20.dp)
                actions.moreMenu(more) { more = false }
            }
        }
    }
    CortanaCard {
        CardTitle(Symbols.AccountTree, "Branches", trailing = "${p.branches.size}", bottom = 8.dp)
        if (p.branches.isEmpty()) Text("Une seule branche.", style = CortanaType.Caption, color = c.textMuted)
        p.branches.forEachIndexed { i, b ->
            BranchRow(b.label, b.meta, b.current, sub = i > 0, modifier = Modifier.clip(CortanaShapes.Sm).clickable(onClickLabel = "Ouvrir cette branche") { actions.openBranch(p.id, b.leafId) })
        }
    }
    CortanaCard {
        CardTitle(Symbols.Inventory2, "Fichiers et artefacts", trailing = "${p.files.size}", bottom = 4.dp)
        if (p.files.isEmpty()) Text("Aucun fichier ni artefact dans cette conversation.", style = CortanaType.Caption, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
        p.files.forEach { f ->
            FileRow(f.icon, f.name, f.meta) { SmallIconButton(Symbols.Download, "Télécharger ${f.name}", { actions.download(f.id) }) }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = Cortana.colors
    Column(modifier.clip(CortanaShapes.Md).background(c.sunken).border(1.dp, c.surfaceBorder, CortanaShapes.Md).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = CortanaType.PanelTitle.copy(fontSize = 18.sp), color = c.textStrong)
        Text(label, style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted)
    }
}

// ------------------------------------------------------------------ route

private const val ALL = "Tout l'historique"

/**
 * History on the real conversations: title and preview filtered without case or accents, message hits of
 * the full-text search, filters counted from the conversations' facts, and the selected conversation's
 * branches and artifacts (its own [WorkspaceViewModel], so the Discussion screen is not disturbed).
 */
@Composable
fun HistoryRoute(onOpen: (sessionId: String, messageId: String?) -> Unit) {
    val c = LocalContainer.current
    val vm: WorkspaceViewModel = viewModel { WorkspaceViewModel(c) }
    val scope = rememberCoroutineScope()
    val sessions by vm.sessions.collectAsState()
    val previews by vm.previews.collectAsState()
    val hits by vm.searchResults.collectAsState()
    val projects by vm.projects.collectAsState()
    val providers by vm.providers.collectAsState()
    val active by vm.active.collectAsState()
    val selectedSession by vm.session.collectAsState()
    val branches by vm.branches.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableStateOf<Int?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }
    LaunchedEffect(query) { vm.search(query) }
    LaunchedEffect(selected) { selected?.let { vm.open(it) } }
    val facts by produceState(emptyMap<String, ChatService.SessionFacts>(), sessions.size, sessions.maxOfOrNull { it.updatedAt }) {
        value = withContext(Dispatchers.IO) { runCatching { c.chat.sessionFacts() }.getOrDefault(emptyMap()) }
    }

    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val base = sessions.filter { s ->
        (project == null || s.projectId == project) && (model == null || s.modelId == model) && (days == null || s.updatedAt >= now - days!! * 86_400_000L)
    }
    fun agent(s: SessionEntity) = facts[s.id]?.agentTasks == true || ChatMode.of(s.mode) == ChatMode.AGENT || ChatMode.of(s.mode) == ChatMode.DEV
    val byFilter: (SessionEntity) -> Boolean = { s ->
        when (filter) {
            "pinned" -> s.pinned
            "files" -> facts[s.id]?.withFiles == true
            "artifacts" -> (facts[s.id]?.artifacts ?: 0) > 0
            "agent" -> agent(s)
            else -> true
        }
    }
    val q = fold(query.trim())
    val hitBySession = hits.groupBy { it.sessionId }
    val shown = base.filter(byFilter).filter { s ->
        q.isEmpty() || fold(s.title).contains(q) || fold(previews[s.id].orEmpty()).contains(q) || hitBySession.containsKey(s.id)
    }.sortedWith(compareByDescending<SessionEntity> { it.updatedAt })
    val today = LocalDate.now(zone)
    fun group(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        return when { d == today -> "Aujourd'hui"; d == today.minusDays(1) -> "Hier"; d.isAfter(today.minusDays(7)) -> "Cette semaine"; else -> "Plus ancien" }
    }
    val rows = shown.map { s ->
        val kind = kindOf(s.mode)
        val hit = hitBySession[s.id]?.firstOrNull()?.takeIf { q.isNotEmpty() }
        HistoryRowUi(
            s.id, (if (s.incognito) "Temporaire · " else "") + s.title, hit?.excerpt ?: previews[s.id].orEmpty(),
            if (group(s.updatedAt) == "Aujourd'hui") TimeFmt.time(s.updatedAt) else dayLabel(s.updatedAt, zone, short = true), group(s.updatedAt),
            kind, modeLabel(s.mode), s.pinned, active?.sessionId == s.id, facts[s.id]?.branches ?: 1,
        )
    }
    val groups = rows.groupBy { it.group }.toList()
    LaunchedEffect(rows.firstOrNull()?.id, selected) { if (selected == null || rows.none { it.id == selected }) rows.firstOrNull()?.let { selected = it.id } }

    val filters = listOf(
        HistoryFilterUi("all", "Tout", Symbols.Apps, base.size),
        HistoryFilterUi("pinned", "Épinglées", Symbols.PushPin, base.count { it.pinned }),
        HistoryFilterUi("files", "Avec fichiers", Symbols.AttachFile, base.count { facts[it.id]?.withFiles == true }),
        HistoryFilterUi("artifacts", "Avec artefacts", Symbols.Inventory2, base.count { (facts[it.id]?.artifacts ?: 0) > 0 }),
        HistoryFilterUi("agent", "Tâches agent", Symbols.SmartToy, base.count(::agent)),
    )
    val sel = selectedSession?.takeIf { it.id == selected }
    val preview = sel?.let { s ->
        val provider = providers.firstOrNull { it.id == s.providerId }?.displayName
        val meta = listOfNotNull(modeLabel(s.mode), s.modelId ?: provider, projects.firstOrNull { it.id == s.projectId }?.name).joinToString(" · ")
        HistoryPreviewUi(
            s.id, s.title, kindOf(s.mode), meta, facts[s.id]?.messages ?: 0,
            branches.map { b -> HistoryBranchUi(b.leafId, if (b.current) "Actuelle · ${b.label}" else b.label, if (b.current) "actuelle" else TimeFmt.short(b.at), b.current) }
                .sortedByDescending { it.current },
            artifacts.filter { !it.deleted }.map { a -> HistoryFileUi(a.artifactId, iconOf(a), a.name, artifactMeta(a)) },
            s.pinned,
        )
    }
    val models = sessions.mapNotNull { it.modelId }.distinct().sorted()
    HistoryScreen(
        HistoryUi(query, filters, filter,
            "Projet : " + (projects.firstOrNull { it.id == project }?.name ?: "tous"), "Modèle : " + (model ?: "tous"),
            when (days) { null -> ALL; 7 -> "7 derniers jours"; else -> "$days derniers jours" }, groups, selected, preview),
        HistoryActions(
            query = { query = it }, filter = { filter = it }, select = { selected = it },
            open = { id -> onOpen(id, hitBySession[id]?.firstOrNull()?.takeIf { q.isNotEmpty() }?.messageId) },
            branch = { id -> scope.launch { runCatching { c.chat.fork(id, null) }.onSuccess { copy -> onOpen(copy.id, null) }.onFailure { vm.say("Impossible de brancher.") } } },
            openBranch = { id, leaf -> scope.launch { runCatching { c.conversations.setLeaf(id, leaf) }; onOpen(id, null) } },
            export = { if (selected != null) vm.export("md") else vm.say("Choisissez une conversation à exporter.") },
            download = { vm.exportArtifact(it) },
            projectMenu = { open, close ->
                DropdownMenu(open, close) {
                    DropdownMenuItem(text = { Text("Tous les projets") }, onClick = { project = null; close() })
                    projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { project = p.id; close() }) }
                }
            },
            modelMenu = { open, close ->
                DropdownMenu(open, close) {
                    DropdownMenuItem(text = { Text("Tous les modèles") }, onClick = { model = null; close() })
                    models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { model = m; close() }) }
                }
            },
            periodMenu = { open, close ->
                DropdownMenu(open, close) {
                    listOf<Pair<Int?, String>>(7 to "7 derniers jours", 30 to "30 derniers jours", null to ALL).forEach { (d, l) ->
                        DropdownMenuItem(text = { Text(l) }, onClick = { days = d; close() })
                    }
                }
            },
            moreMenu = { open, close ->
                DropdownMenu(open, close) {
                    sel?.let { s ->
                        DropdownMenuItem(text = { Text(if (s.pinned) "Désépingler" else "Épingler") }, onClick = { vm.setPinned(s.id, !s.pinned); close() })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { renaming = s; close() })
                        DropdownMenuItem(text = { Text("Archiver") }, onClick = { vm.setArchived(s.id, true); selected = null; close() })
                        DropdownMenuItem(text = { Text("Exporter en JSON") }, onClick = { vm.export("json"); close() })
                        DropdownMenuItem(text = { Text("Supprimer…") }, onClick = { deleting = s; close() })
                    }
                }
            },
        ),
    )
    renaming?.let { s -> RenameDialog(s.title, onDismiss = { renaming = null }) { vm.rename(s.id, it); renaming = null } }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer la conversation ?") },
            text = { Text("« ${s.title} », ses branches et ses messages seront supprimés. Les artefacts restent disponibles.") },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); selected = null; deleting = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } })
    }
}

/** Lower case without accents: « Réunion » matches « reunion ». */
internal fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(COMBINING, "")
private val COMBINING = Regex("\\p{Mn}+")

internal fun kindOf(mode: String?): ConversationKind = when (ChatMode.of(mode)) {
    ChatMode.AGENT, ChatMode.DEV -> ConversationKind.Dev
    ChatMode.RESEARCH -> ConversationKind.Search
    ChatMode.COUNCIL -> ConversationKind.Council
    ChatMode.VOICE -> ConversationKind.Voice
    else -> ConversationKind.Chat
}

internal fun modeLabel(mode: String?): String = when (ChatMode.of(mode)) {
    ChatMode.AGENT -> "Agent"; ChatMode.DEV -> "Développement"; ChatMode.RESEARCH -> "Recherche"
    ChatMode.COUNCIL -> "Conseil"; ChatMode.VOICE -> "Voix"; else -> "Discussion"
}

@DrawableRes
internal fun iconOf(a: ArtifactEntity): Int = when {
    a.mime.startsWith("image/") -> Symbols.PhotoLibrary
    a.mime == "application/pdf" -> Symbols.PictureAsPdf
    a.name.endsWith(".apk") -> Symbols.Android
    a.name.endsWith(".kt") || a.name.endsWith(".java") || a.mime.contains("json") -> Symbols.DataObject
    a.type == "plan" -> Symbols.ListAlt
    else -> Symbols.Description
}
