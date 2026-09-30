package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artisanguillonrenov.cortana.core.memory.MemoryEntity
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.components.ButtonKind
import io.github.artisanguillonrenov.cortana.ui.components.CardTitle
import io.github.artisanguillonrenov.cortana.ui.components.ContextGauge
import io.github.artisanguillonrenov.cortana.ui.components.CortanaButton
import io.github.artisanguillonrenov.cortana.ui.components.CortanaCard
import io.github.artisanguillonrenov.cortana.ui.components.CortanaToggle
import io.github.artisanguillonrenov.cortana.ui.components.FileRow
import io.github.artisanguillonrenov.cortana.ui.components.FilterChip
import io.github.artisanguillonrenov.cortana.ui.components.HeaderPill
import io.github.artisanguillonrenov.cortana.ui.components.MemoryCard
import io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory
import io.github.artisanguillonrenov.cortana.ui.components.Overline
import io.github.artisanguillonrenov.cortana.ui.components.SearchField
import io.github.artisanguillonrenov.cortana.ui.components.SmallIconButton
import io.github.artisanguillonrenov.cortana.ui.components.Symbol
import io.github.artisanguillonrenov.cortana.ui.components.Tag
import io.github.artisanguillonrenov.cortana.ui.components.ToggleSize
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.workspace.ContextLevel
import io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceViewModel
import io.github.artisanguillonrenov.cortana.util.TimeFmt
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ state

@Immutable
data class MemoryItemUi(
    val id: String, val fact: String, val category: MemoryCategory, val confirmed: Boolean,
    val source: String, @DrawableRes val sourceIcon: Int, val active: Boolean, val locked: Boolean,
)

@Immutable
data class MemorySuggestionUi(val id: String, val quote: String, val source: String)

@Immutable
data class MemoryPinUi(val id: String, @DrawableRes val icon: Int, val label: String, val meta: String)

@Immutable
data class ProjectRowUi(val label: String, val tag: String, val own: Boolean)

@Immutable
data class MemoryUi(
    val query: String,
    val temporary: Boolean,
    val suggestion: MemorySuggestionUi?,
    val categories: List<Pair<MemoryCategory?, Int>>,
    val category: MemoryCategory?,
    val items: List<MemoryItemUi>,
    val conversation: String,
    val level: Int,
    val levelNote: String,
    /** Developer mode only: the estimated split of the last request ("≈"); null = hidden. */
    val breakdown: List<Triple<String, Color, Int>>?,
    val pins: List<MemoryPinUi>,
    val compactTime: String?,
    val compactSummary: String?,
    val projectName: String?,
    val projectRows: List<ProjectRowUi>,
)

class MemoryActions(
    val query: (String) -> Unit = {},
    val temporary: (Boolean) -> Unit = {},
    val keep: (String) -> Unit = {},
    val ignore: (String) -> Unit = {},
    val category: (MemoryCategory?) -> Unit = {},
    val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val edit: (String) -> Unit = {},
    val forget: (String) -> Unit = {},
    val add: () -> Unit = {},
    val unpin: (String) -> Unit = {},
    val inspect: () -> Unit = {},
    val restore: () -> Unit = {},
)

private val levelNames = listOf("Faible", "Moyen", "Élevé", "Compactage")

// ------------------------------------------------------------------ screen

/** "Mémoire" (README screen 5): what Cortana keeps, where it comes from, and what is active in this conversation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(ui: MemoryUi, actions: MemoryActions, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    DesignPage(
        "Mémoire", "Ce que Cortana retient, d’où ça vient, et ce qui est actif ici.", modifier,
        headerTrailing = {
            SearchField(ui.query, actions.query, "Rechercher un souvenir…", Modifier.width(280.dp))
            HeaderPill({ actions.temporary(!ui.temporary) }, active = ui.temporary, padding = PaddingValues(start = 12.dp, end = 12.dp), gap = 10.dp,
                label = "Chat temporaire", role = androidx.compose.ui.semantics.Role.Switch) {
                Symbol(Symbols.VisibilityOff, if (ui.temporary) c.purpleText else c.pillIcon, 20.dp)
                Text("Chat temporaire", style = CortanaType.Control, color = c.textControl)
                CortanaToggle(ui.temporary, null, ToggleSize.Medium, trackOn = c.purple)
            }
            SmallIconButton(Symbols.Add, "Ajouter un souvenir", actions.add, size = 44.dp, iconSize = 22.dp)
        },
        side = { MemorySide(ui, actions) },
        sideTop = 18.dp,
    ) {
        item("top") {
            Column(Modifier.padding(top = 18.dp)) {
                if (ui.temporary) Banner(Symbols.VisibilityOff, c.purpleText, c.purpleTint, c.purple.copy(alpha = 0.35f),
                    "Chat temporaire activé : Cortana n’écrit rien en mémoire durable pendant cette conversation et n’y lit aucun souvenir.")
                else ui.suggestion?.let { s -> SuggestionCard(s, actions) }
                FlowRow(Modifier.padding(top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.categories.forEach { (cat, n) -> FilterChip(cat?.label ?: "Tout", ui.category == cat, { actions.category(cat) }, count = "$n") }
                }
            }
        }
        if (ui.items.isEmpty()) item("empty") {
            CortanaCard { Text("Aucun souvenir. Dites par exemple « Retiens que je préfère des réponses courtes », ou touchez +.", style = CortanaType.Secondary, color = c.textTertiary) }
        }
        items(ui.items.chunked(2), key = { row -> row.first().id }) { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { m ->
                    MemoryCard(m.fact, m.category, m.confirmed, m.source, m.sourceIcon, m.active, m.locked,
                        { on -> actions.toggle(m.id, on) }, { actions.edit(m.id) }, { actions.forget(m.id) }, Modifier.weight(1f))
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Banner(@DrawableRes icon: Int, fg: Color, bg: Color, border: Color, text: String) {
    val c = Cortana.colors
    Row(Modifier.fillMaxWidth().clip(CortanaShapes.Lg).background(bg).border(1.dp, border, CortanaShapes.Lg).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Symbol(icon, fg, 22.dp)
        Text(text, style = CortanaType.Secondary, color = c.textControl)
    }
}

@Composable
private fun SuggestionCard(s: MemorySuggestionUi, actions: MemoryActions) {
    val c = Cortana.colors
    Row(
        Modifier.fillMaxWidth().clip(CortanaShapes.Lg).background(c.accent.copy(alpha = 0.1f)).border(1.dp, c.accentIcon.copy(alpha = 0.4f), CortanaShapes.Lg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CortanaShapes.Sm).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Symbol(Symbols.Lightbulb, c.accentIcon, 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Cortana propose de retenir", style = CortanaType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.accentLink)
            Text("« ${s.quote} »", style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Source : ${s.source}", style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted)
        }
        CortanaButton("Ignorer", { actions.ignore(s.id) }, height = 40.dp, corner = 10.dp, padding = PaddingValues(horizontal = 14.dp))
        CortanaButton("Retenir", { actions.keep(s.id) }, kind = ButtonKind.Primary, icon = Symbols.Check, height = 40.dp, corner = 10.dp, padding = PaddingValues(horizontal = 16.dp))
    }
}

@Composable
private fun MemorySide(ui: MemoryUi, actions: MemoryActions) {
    val c = Cortana.colors
    CortanaCard {
        CardTitle(Symbols.DataUsage, "Contexte de la conversation", iconColor = c.accentIcon, size = 16.5f)
        Text(ui.conversation, style = CortanaType.Caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(levelNames[ui.level.coerceIn(0, 3)], style = CortanaType.PanelTitle.copy(fontSize = 22.sp), color = c.accentSoft)
            Text(ui.levelNote, style = CortanaType.Caption.copy(fontSize = 12.5.sp), color = c.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
        }
        ContextGauge(ui.level)
        ui.breakdown?.let { parts ->
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(c.divider))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(Symbols.Code, c.textMuted, 16.dp); Overline("Estimation · mode développeur")
            }
            val total = parts.sumOf { it.third }.coerceAtLeast(1)
            Row(Modifier.padding(top = 10.dp, bottom = 10.dp).fillMaxWidth().height(6.dp).clip(CortanaShapes.Sm), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                parts.filter { it.third > 0 }.forEach { (_, col, n) -> Box(Modifier.weight(n.toFloat() / total).height(6.dp).background(col)) }
            }
            parts.forEach { (label, col, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CortanaShapes.Xs).background(col))
                    Text(label, style = CortanaType.Caption.copy(fontSize = 13.sp), color = c.textControl, modifier = Modifier.weight(1f))
                    Text("≈ " + kilo(n), style = CortanaType.Code.copy(fontSize = 12.sp), color = c.textSecondary)
                }
            }
        }
    }
    CortanaCard {
        CardTitle(Symbols.PushPin, "Épinglé au contexte", trailing = "${ui.pins.size}", size = 16.5f, bottom = 4.dp)
        if (ui.pins.isEmpty()) Text("Rien d’épinglé. Épinglez un message ou un fichier depuis son menu dans la discussion.", style = CortanaType.Caption, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
        ui.pins.forEach { p -> FileRow(p.icon, p.label, p.meta) { SmallIconButton(Symbols.Close, "Désépingler ${p.label}", { actions.unpin(p.id) }) } }
    }
    CortanaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(Symbols.Compress, "Dernier compactage", Modifier.weight(1f), size = 16.5f)
            ui.compactTime?.let { Text(it, style = CortanaType.Caption, color = c.textMuted) }
        }
        Text(ui.compactSummary ?: "Aucun compactage : toute la conversation tient dans le contexte.", style = CortanaType.Secondary, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
        if (ui.compactSummary != null) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CortanaButton("Inspecter", actions.inspect, icon = Symbols.ManageSearch, height = 40.dp, corner = 10.dp)
            CortanaButton("Restaurer", actions.restore, icon = Symbols.Restore, height = 40.dp, corner = 10.dp)
        }
    }
    ui.projectName?.let { name ->
        CortanaCard {
            CardTitle(Symbols.FolderSpecial, "Projet $name", size = 16.5f, bottom = 6.dp)
            ui.projectRows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, style = CortanaType.Secondary, color = c.textControl, modifier = Modifier.weight(1f))
                    Tag(r.tag, if (r.own) c.accentLink else c.textTertiary, border = if (r.own) c.accentIcon.copy(alpha = 0.5f) else c.controlBorder, height = 24.dp)
                }
            }
        }
    }
}

private fun kilo(n: Int): String = if (n < 1000) "$n" else String.format(java.util.Locale.FRANCE, "%.1f k", n / 1000.0)

// ------------------------------------------------------------------ route

/**
 * Memory on the memory service and the current conversation (its own [WorkspaceViewModel]: the latest
 * conversation, as in Discussion): confirmed and suggested memories, per-conversation switches,
 * temporary chat (incognito: no durable write, no memory read), pins, compaction and project.
 */
@Composable
fun MemoryRoute() {
    val c = LocalContainer.current
    val vm: WorkspaceViewModel = viewModel { WorkspaceViewModel(c) }
    val scope = rememberCoroutineScope()
    val active by remember { c.memory.observe(MemoryStatus.ACTIVE) }.collectAsState(initial = emptyList())
    val pending by remember { c.memory.observe(MemoryStatus.PENDING) }.collectAsState(initial = emptyList())
    val session by vm.session.collectAsState()
    val level by vm.contextLevel.collectAsState()
    val report by vm.contextReport.collectAsState()
    val pins by vm.pins.collectAsState()
    val checkpoints by vm.checkpoints.collectAsState()
    val projects by vm.projects.collectAsState()
    val settings by c.settings.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<MemoryCategory?>(null) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    val p = Cortana.colors
    val disabled = session?.let { s -> active.filter { vm.memoryDisabledHere(it.id) }.map { it.id }.toSet() }.orEmpty()

    fun categoryOf(m: MemoryEntity) = when (m.type) { MemoryTypes.PROFILE -> MemoryCategory.Profile; MemoryTypes.PREFERENCE -> MemoryCategory.Preferences; else -> MemoryCategory.Facts }
    val all = (pending + active)
    val q = fold(query.trim())
    val shown = all.filter { (category == null || categoryOf(it) == category) && (q.isEmpty() || fold(it.text).contains(q)) }
    val categories = listOf<MemoryCategory?>(null, MemoryCategory.Profile, MemoryCategory.Preferences, MemoryCategory.Facts)
        .map { cat -> cat to all.count { cat == null || categoryOf(it) == cat } }
    val project = projects.firstOrNull { it.id == session?.projectId }
    val inherit = vm.inheritsProject(session)
    val r = report
    val ui = MemoryUi(
        query, session?.incognito == true,
        pending.firstOrNull()?.let { m -> MemorySuggestionUi(m.id, m.text.take(160), sourceOf(m).first + " · " + TimeFmt.short(m.createdAt)) },
        categories, category,
        shown.map { m ->
            val (src, icon) = sourceOf(m)
            MemoryItemUi(m.id, m.text, categoryOf(m), m.status == MemoryStatus.ACTIVE, src + " · " + TimeFmt.short(m.updatedAt).substringBefore(' '), icon,
                m.status == MemoryStatus.ACTIVE && m.id !in disabled && session?.incognito != true, locked = false)
        },
        session?.title.orEmpty(), level.ordinal, if (level.ok) "Compactage non nécessaire" else if (level == ContextLevel.HIGH) "Compactage bientôt" else "Compactage conseillé",
        if (!settings.chat.developer || r == null) null else listOf(
            Triple("Historique", p.accentBar, (r.sections["window"] ?: 0) + (r.sections["summary"] ?: 0)),
            Triple("Fichiers épinglés", p.success, (r.sections["pins"] ?: 0) + (r.sections["attachments"] ?: 0)),
            Triple("Schémas d’outils", p.amber, r.toolTokens),
            Triple("Mémoire", p.purple, r.sections["memory"] ?: 0),
            Triple("Réserve de sortie", p.textMuted, r.outputReserve),
        ),
        pins.map { pin ->
            MemoryPinUi(pin.id, when (pin.targetType) { "artifact" -> Symbols.Description; "note" -> Symbols.StickyNote2; else -> Symbols.ChatBubble },
                pin.label, (when (pin.targetType) { "artifact" -> "Fichier"; "note" -> "Note"; else -> "Message" }) + " · épinglé à " + TimeFmt.time(pin.createdAt))
        },
        checkpoints.firstOrNull()?.let { TimeFmt.time(it.createdAt) },
        checkpoints.firstOrNull()?.let { cp -> "${cp.coveredCount} anciens messages résumés" + (cp.modelRef?.let { " par $it" } ?: "") + ". Le résumé remplace ces messages pour Cortana ; ils restent dans l’historique." },
        project?.name,
        if (project == null) emptyList() else listOfNotNull(
            project.instructions.takeIf { it.isNotBlank() }?.let { ProjectRowUi("Instructions du projet", if (inherit) "Hérité" else "Ignoré", false) },
            project.preferredModel?.let { ProjectRowUi("Modèle préféré : $it", "Hérité", false) },
            ProjectRowUi("Hériter du projet dans cette conversation", if (inherit) "Oui" else "Non", true),
        ),
    )
    MemoryScreen(ui, MemoryActions(
        query = { query = it },
        temporary = { on -> session?.let { s -> scope.launch { c.conversations.updateSession(s.copy(incognito = on)) } } },
        keep = { id -> scope.launch { c.memory.confirm(id) } },
        ignore = { id -> scope.launch { c.memory.reject(id) } },
        category = { category = it },
        toggle = { id, on -> vm.setMemoryHere(id, on) },
        edit = { id -> editing = all.firstOrNull { it.id == id } },
        forget = { id -> scope.launch { val m = c.memory.get(id); c.memory.forget(id); c.audit.record("owner", "memory.forget", m?.text?.take(60), "ok") } },
        add = { adding = true },
        unpin = vm::unpin,
        inspect = { inspecting = true },
        restore = { vm.clearReduction() },
    ))
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Corriger le souvenir") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { scope.launch { c.memory.edit(m.id, text) }; editing = null }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annuler") } })
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(MemoryTypes.PREFERENCE) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Nouveau souvenir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ex. Je préfère des réponses courtes") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(MemoryTypes.PREFERENCE to "Préférence", MemoryTypes.PROFILE to "Profil", MemoryTypes.SEMANTIC to "Fait").forEach { (t, l) ->
                            FilterChip(l, type == t, { type = t })
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { scope.launch { c.memory.save(text, type, MemoryStatus.ACTIVE, "owner_manual") }; adding = false }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Annuler") } })
    }
    if (inspecting) checkpoints.firstOrNull()?.let { cp ->
        AlertDialog(onDismissRequest = { inspecting = false }, title = { Text("Résumé du compactage") },
            text = { Text(cp.summary.take(4_000), modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { inspecting = false }) { Text("Fermer") } })
    }
}

/** Where a memory comes from: asked explicitly, added by hand, or inferred from a conversation. */
private fun sourceOf(m: MemoryEntity): Pair<String, Int> = when {
    m.provenanceJson.contains("owner_manual") -> "Ajouté à la main" to Symbols.Edit
    m.provenanceJson.contains("\"explicit\"") -> "Demandé explicitement" to Symbols.Person
    else -> "Déduit d’une discussion" to Symbols.AutoAwesome
}
