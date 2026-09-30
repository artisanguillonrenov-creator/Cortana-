package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.artisanguillonrenov.cortana.ui.components.ActionTile
import io.github.artisanguillonrenov.cortana.ui.components.ActivityRail
import io.github.artisanguillonrenov.cortana.ui.components.ButtonKind
import io.github.artisanguillonrenov.cortana.ui.components.ContextTabs
import io.github.artisanguillonrenov.cortana.ui.components.CortanaButton
import io.github.artisanguillonrenov.cortana.ui.components.FileChange
import io.github.artisanguillonrenov.cortana.ui.components.FileTree
import io.github.artisanguillonrenov.cortana.ui.components.InfoCard
import io.github.artisanguillonrenov.cortana.ui.components.InfoStatus
import io.github.artisanguillonrenov.cortana.ui.components.LogConsole
import io.github.artisanguillonrenov.cortana.ui.components.ModelMenu
import io.github.artisanguillonrenov.cortana.ui.components.ModelOption
import io.github.artisanguillonrenov.cortana.ui.components.ModelPill
import io.github.artisanguillonrenov.cortana.ui.components.ProgressBar
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.SearchPill
import io.github.artisanguillonrenov.cortana.ui.components.StatusChip
import io.github.artisanguillonrenov.cortana.ui.components.StopButton
import io.github.artisanguillonrenov.cortana.ui.components.StopSize
import io.github.artisanguillonrenov.cortana.ui.components.Symbol
import io.github.artisanguillonrenov.cortana.ui.components.TabSpec
import io.github.artisanguillonrenov.cortana.ui.components.TagSpec
import io.github.artisanguillonrenov.cortana.ui.components.TaskPill
import io.github.artisanguillonrenov.cortana.ui.components.TaskSummary
import io.github.artisanguillonrenov.cortana.ui.components.TaskTags
import io.github.artisanguillonrenov.cortana.ui.components.ToolChip
import io.github.artisanguillonrenov.cortana.ui.components.TouchTarget
import io.github.artisanguillonrenov.cortana.ui.components.VoicePill
import io.github.artisanguillonrenov.cortana.ui.components.active
import io.github.artisanguillonrenov.cortana.ui.components.drawGlow
import io.github.artisanguillonrenov.cortana.ui.components.ScreenHeader
import io.github.artisanguillonrenov.cortana.ui.components.tap
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaPalette
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

// ------------------------------------------------------------------ state

@Immutable
data class DiscussionHeaderUi(
    val model: ModelOption?,
    val models: List<ModelOption>,
    val searchOn: Boolean,
    val voiceOn: Boolean,
)

@Immutable
data class ToolChipUi(val key: String, @DrawableRes val icon: Int, @DrawableRes val iconFilled: Int, val label: String, val active: Boolean)

enum class TagTone { Purple, Green, Blue }

@Immutable
data class InfoCardsUi(
    /** Paired worker: name and connection line; null = none paired. */
    val workerName: String?,
    val workerLine: String,
    val workerOnline: Boolean,
    val mcpConnected: Int,
    val mcpTotal: Int,
    val mcpNames: String,
    val memoryCount: Int,
    val memorySummary: String,
    val policyPending: Int,
    val policySummary: String,
)

@Immutable
data class TaskPanelUi(
    /** The task of this conversation (the running one, else the last one); null = none yet. */
    val task: TaskRunProjection.Panel?,
    val title: String,
    val subtitle: String,
    val tags: List<Pair<TagTone, String>>,
    val folder: String,
    val cards: InfoCardsUi,
)

/** One entry of the thread's menu (⋮): the conversation's own actions, kept out of the header. */
@Immutable
data class ThreadMenuItem(@DrawableRes val icon: Int, val label: String, val shortcut: String? = null, val dividerBefore: Boolean = false, val onClick: () -> Unit)

/** What the screen asks of its owner (the route binds them to the one runtime; previews to nothing). */
class DiscussionActions(
    val pickModel: (ModelOption) -> Unit = {},
    val manageProviders: () -> Unit = {},
    val setSearch: (Boolean) -> Unit = {},
    val toggleVoice: () -> Unit = {},
    val threadMenu: () -> Unit = {},
    val toggleTool: (String, Boolean) -> Unit = { _, _ -> },
    val stop: () -> Unit = {},
    val resume: () -> Unit = {},
    val showPlan: () -> Unit = {},
    val openTasks: () -> Unit = {},
    val openMemory: () -> Unit = {},
    val openWorker: () -> Unit = {},
    val openMcp: () -> Unit = {},
    val openPolicy: () -> Unit = {},
    val reviewGit: () -> Unit = {},
    val build: () -> Unit = {},
    val tests: () -> Unit = {},
    val artifacts: () -> Unit = {},
    val openFile: (FileChange) -> Unit = {},
    val doneAction: () -> Unit = {},
)

private val panelTabs = listOf(TabSpec(Symbols.Article, "Logs en direct"), TabSpec(Symbols.FileCopy, "Fichiers"), TabSpec(Symbols.Preview, "Aperçu"))

fun TagTone.spec(c: CortanaPalette, text: String): TagSpec = when (this) {
    TagTone.Purple -> TaskTags.dev(c, text)
    TagTone.Green -> TaskTags.green(c, text)
    TagTone.Blue -> TaskTags.blue(c, text)
}

// ------------------------------------------------------------------ screen

/**
 * "Discussion" (README screen 1 and 2): header, activity rail in portrait, the thread, tool chips and
 * the composer; the "Tâche active" panel in a third column, or over the conversation in portrait.
 */
@Composable
fun DiscussionScreen(
    shell: ShellScope,
    header: DiscussionHeaderUi,
    panel: TaskPanelUi,
    tools: List<ToolChipUi>,
    actions: DiscussionActions,
    listState: LazyListState,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    panelTab: Int = 0,
    onPanelTab: (Int) -> Unit = {},
    menu: List<ThreadMenuItem> = emptyList(),
    thread: LazyListScope.() -> Unit,
) {
    val c = Cortana.colors
    var panelOpen by remember { mutableStateOf(false) }
    val overlay = shell.layout.overlay
    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp, top = 40.dp, end = 14.dp, bottom = 24.dp)) {
                Box(Modifier.zIndex(50f)) { DiscussionHeader(shell, header, panel, actions, overlay) { panelOpen = true } }
                val run = panel.task
                if (overlay && run != null) ActivityRail(
                    run.run.status, run.stepLabel, run.run.progress, run.remaining, onDetails = { panelOpen = true },
                    onStop = actions.stop, onResume = actions.resume, modifier = Modifier.padding(top = 14.dp).testTag("activity-rail"),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(top = 18.dp).semantics { contentDescription = "Conversation" }, state = listState,
                        contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp, end = 14.dp),
                    ) { thread() }
                    // "Menu du fil": top right of the thread (README: 100 dp under the top of the column).
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = 0.dp)) {
                        var open by remember { mutableStateOf(false) }
                        val src = remember { MutableInteractionSource() }
                        TouchTarget(Modifier.tap(src, { if (menu.isEmpty()) actions.threadMenu() else open = true }).semantics { contentDescription = "Menu de la discussion" }) {
                            Symbol(Symbols.MoreVert, c.kebab, 20.dp)
                        }
                        ThreadMenu(menu, open) { open = false }
                    }
                }
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tools.forEach { t -> ToolChip(t.icon, t.iconFilled, t.label, t.active, { on -> actions.toggleTool(t.key, on) }) }
                }
                Box(Modifier.padding(top = 10.dp)) { composer() }
            }
            if (!overlay) TaskPanel(panel, actions, panelTab, onPanelTab, overlay = false, onClose = {},
                modifier = Modifier.width(shell.layout.panelWidth).fillMaxHeight().padding(start = 0.dp, top = 44.dp, end = 12.dp, bottom = 28.dp))
        }
        if (overlay) {
            Scrim(panelOpen) { panelOpen = false }
            val reduce = Cortana.reduceMotion
            val x by animateDpAsState(if (panelOpen) 0.dp else 440.dp, if (reduce) snap() else tween(CortanaMotion.Overlay, easing = CortanaMotion.Standard), label = "panel")
            if (panelOpen || x < 440.dp) Box(
                Modifier.align(Alignment.CenterEnd).offset(x = x).width(shell.layout.panelWidth).fillMaxHeight()
                    .drawBehind { drawGlow(c.drawerShadow, 60.dp) }.background(c.background)
                    .drawBehind { drawLine(c.surfaceBorder, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
            ) {
                TaskPanel(panel, actions, panelTab, onPanelTab, overlay = true, onClose = { panelOpen = false },
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 28.dp),
                    onShowPlan = { panelOpen = false; actions.showPlan() })
            }
        }
    }
}

@Composable
private fun DiscussionHeader(shell: ShellScope, header: DiscussionHeaderUi, panel: TaskPanelUi, actions: DiscussionActions, overlay: Boolean, openPanel: () -> Unit) {
    val c = Cortana.colors
    var menu by remember { mutableStateOf(false) }
    ScreenHeader("Discussion", "Discutez, créez, automatisez, tout est possible.", shell.navIcon, shell.navLabel, shell.toggleSidebar) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                header.model?.let { m -> ModelPill(m.initial, m.color, m.name, m.provider, { menu = !menu }) }
                if (menu) {
                    androidx.compose.ui.window.Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.roundToPx() }),
                        onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                        ModelMenu(header.models, header.model?.key, { menu = false; actions.pickModel(it) }, { menu = false; actions.manageProviders() })
                    }
                }
            }
            SearchPill(header.searchOn, actions.setSearch)
            VoicePill(header.voiceOn, actions.toggleVoice)
            if (overlay && panel.task != null && panel.task.progressLabel.isNotEmpty()) TaskPill(panel.task.progressLabel, openPanel)
        }
    }
}

// ------------------------------------------------------------------ task panel

/**
 * "Tâche active" and the state cards. On a tall screen the task card takes the remaining height (logs
 * fill it, as in the prototype); on a short one (the Galaxy Tab A11 is ~560 dp high) the panel scrolls
 * and the logs keep a fixed height.
 */
@Composable
fun TaskPanel(panel: TaskPanelUi, actions: DiscussionActions, tab: Int, onTab: (Int) -> Unit, overlay: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, onShowPlan: () -> Unit = actions.showPlan) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.testTag("task-panel")) {
        val tall = maxHeight >= 860.dp
        Column(
            Modifier.fillMaxSize().let { if (tall) it else it.verticalScroll(rememberScrollState()) },
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TaskCard(panel, actions, tab, onTab, overlay, onClose, if (tall) Modifier.weight(1f) else Modifier, tall, onShowPlan)
            InfoCards(panel.cards, actions)
        }
    }
}

@Composable
private fun TaskCard(panel: TaskPanelUi, actions: DiscussionActions, tab: Int, onTab: (Int) -> Unit, overlay: Boolean, onClose: () -> Unit, modifier: Modifier, tall: Boolean, onShowPlan: () -> Unit) {
    val c = Cortana.colors
    Column(
        modifier.fillMaxWidth().clip(CortanaShapes.Xl).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Xl)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
    ) {
        val t = panel.task
        Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol(Symbols.AssignmentFill, c.accentText, 22.dp)
            Text("Tâche active", style = CortanaType.PanelTitle, color = c.textStrong, modifier = Modifier.weight(1f).semantics { heading() })
            if (t != null) StatusChip(t.run.status)
            val src = remember { MutableInteractionSource() }
            val on = src.active()
            TouchTarget(Modifier.tap(src, if (overlay) onClose else actions.openTasks).semantics { contentDescription = if (overlay) "Fermer" else "Ouvrir dans Tâches" }, Modifier.padding(start = 2.dp)) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.controlHoverAlt else Color.Transparent).border(1.dp, c.controlBorder, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) { Symbol(if (overlay) Symbols.Close else Symbols.OpenInFull, if (on) c.navTextActive else c.pillChevron, 18.dp) }
            }
        }
        if (t == null) {
            Text("Aucune tâche dans cette discussion. Demandez à Cortana de faire quelque chose : son plan, ses étapes et ses actions s’afficheront ici.",
                style = CortanaType.Control.copy(fontWeight = FontWeight.Normal, lineHeight = 21.sp), color = c.textSecondary, modifier = Modifier.padding(top = 16.dp))
            return@Column
        }
        TaskSummary(panel.title, panel.subtitle, panel.tags.map { (tone, text) -> tone.spec(c, text) }, Modifier.padding(top = 16.dp))
        if (t.progressLabel.isNotEmpty()) Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressBar(t.run.progress, 6.dp, Modifier.weight(1f), label = "Progression ${t.progressLabel}")
            Text(t.progressLabel, style = CortanaType.Numeric, color = c.textSecondary)
        }
        Text(t.remaining, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.textSecondaryAlt, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CortanaButton("Voir le plan", onShowPlan, Modifier.weight(1f), ButtonKind.Secondary, Symbols.Description, 48.dp, 12.dp, PaddingValues(0.dp), gap = 9.dp, fill = true)
            StopButton(t.run.status, actions.stop, actions.resume, StopSize.Panel, Modifier.weight(1f), onDone = actions.doneAction, doneLabel = "Nouvelle tâche")
        }
        ContextTabs(panelTabs, tab, onTab, Modifier.padding(top = 16.dp))
        Box((if (tall) Modifier.weight(1f) else Modifier.height(220.dp)).fillMaxWidth().padding(top = 12.dp)) {
            when (tab) {
                0 -> LogConsole(t.run.logs, t.run.status == RunStatus.Running)
                1 -> if (t.files.isEmpty()) EmptyPane("Aucun fichier modifié par cette tâche.") else FileTree(panel.folder, t.files, onOpen = actions.openFile)
                else -> EmptyPane("Aucun aperçu : l’aperçu en direct s’affiche quand un worker exécute l’application.")
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            ActionTile(Symbols.CallSplit, "Review Git", actions.reviewGit, Modifier.weight(1f))
            ActionTile(Symbols.HardwareFill, "Build", actions.build, Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            ActionTile(Symbols.Science, "Tests", actions.tests, Modifier.weight(1f))
            ActionTile(Symbols.Inventory2Fill, "Artefacts", actions.artifacts, Modifier.weight(1f))
        }
    }
}

/** The thread's menu in the design's colors (elevated surface, 14 dp corners). */
@Composable
private fun ThreadMenu(items: List<ThreadMenuItem>, open: Boolean, onDismiss: () -> Unit) {
    val c = Cortana.colors
    androidx.compose.material3.DropdownMenu(
        open, onDismiss, shape = CortanaShapes.Xl, containerColor = c.elevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.elevatedBorder), modifier = Modifier.width(300.dp),
    ) {
        items.forEach { m ->
            if (m.dividerBefore) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.menuDivider))
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(m.label, style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.textControl) },
                leadingIcon = { Symbol(m.icon, c.pillIcon, 20.dp) },
                trailingIcon = m.shortcut?.let { k -> { Text(k, style = CortanaType.Caption, color = c.textTertiary) } },
                onClick = { onDismiss(); m.onClick() },
            )
        }
    }
}

@Composable
private fun EmptyPane(text: String) {
    val c = Cortana.colors
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.console).border(1.dp, c.consoleBorder, RoundedCornerShape(10.dp)).padding(14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = CortanaType.Caption, color = c.textMuted)
    }
}

@Composable
private fun InfoCards(k: InfoCardsUi, actions: DiscussionActions) {
    val c = Cortana.colors
    InfoCard(
        Symbols.SmartToy, c.workerIcon, c.tile, c.tileBorderAlt, if (k.workerName != null) "Worker appairé" else "Worker",
        if (k.workerName == null) null else if (k.workerOnline) InfoStatus("ACTIF", c.successText) else InfoStatus("HORS LIGNE", c.textTertiary, Symbols.Cable),
        listOfNotNull((k.workerName ?: "Aucun appareil appairé") to c.infoSubtitle, k.workerLine to c.textMuted),
        tileSize = 46.dp, iconSize = 26.dp, alignTop = true, padding = PaddingValues(start = 14.dp, top = 14.dp, end = 12.dp, bottom = 14.dp), onMenu = actions.openWorker,
    )
    InfoCard(
        Symbols.Hub, c.successText, c.success.copy(alpha = 0.1f), c.success.copy(alpha = 0.25f), "MCP connectés",
        InfoStatus("${k.mcpConnected}/${k.mcpTotal}", if (k.mcpTotal > 0 && k.mcpConnected == k.mcpTotal) c.successText else c.textTertiary),
        listOf(k.mcpNames.ifEmpty { "Aucun serveur MCP" } to c.textTertiary), onClick = actions.openMcp,
    )
    InfoCard(
        Symbols.Notes, c.purpleText, c.purpleTint, c.purple.copy(alpha = 0.35f), "Mémoire active", InfoStatus("${k.memoryCount} items", c.successText),
        listOf(k.memorySummary to c.textTertiary), onClick = actions.openMemory,
    )
    InfoCard(
        Symbols.VerifiedUser, c.successText, c.success.copy(alpha = 0.1f), c.success.copy(alpha = 0.3f), "Politique sécurité",
        if (k.policyPending > 0) InfoStatus(if (k.policyPending == 1) "1 demande" else "${k.policyPending} demandes", c.warningText, Symbols.FrontHand) else InfoStatus("Sécurisée", c.successText),
        listOf(k.policySummary to c.textTertiary), highlight = k.policyPending > 0,
        padding = PaddingValues(start = 14.dp, top = 15.dp, end = 16.dp, bottom = 15.dp), onClick = actions.openPolicy,
    )
}
