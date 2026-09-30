package io.github.artisanguillonrenov.cortana.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaPalette
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

// ------------------------------------------------------------------ logs

/** Icon, icon color, text color and fill of each log kind (README "Couleurs des logs"). */
fun LogKind.look(c: CortanaPalette): LogLook = when (this) {
    LogKind.Run -> LogLook(Symbols.ChevronRight, c.logRunIcon, c.logText)
    LogKind.Ok -> LogLook(Symbols.Check, c.successText, c.successLog)
    LogKind.Dir -> LogLook(Symbols.FolderFill, c.amber, c.logText)
    LogKind.Stop -> LogLook(Symbols.StopFill, c.dangerText, c.dangerTextSoft)
    LogKind.Play -> LogLook(Symbols.PlayArrowFill, c.accentLink, c.logPlayText)
    LogKind.Wait -> LogLook(Symbols.FrontHand, c.warningText, c.warningLog)
}

data class LogLook(@DrawableRes val icon: Int, val iconColor: Color, val textColor: Color)

/** Live console: mono 11.5/18.6, auto-scroll to the newest line, blinking cursor while running. */
@Composable
fun LogConsole(lines: List<LogLine>, running: Boolean, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, running) { val last = lines.size + (if (running) 1 else 0) - 1; if (last >= 0) state.scrollToItem(last) }
    LazyColumn(
        modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.console).border(1.dp, c.consoleBorder, RoundedCornerShape(10.dp))
            .semantics { contentDescription = "Logs en direct" },
        state = state, contentPadding = PaddingValues(10.dp),
    ) {
        itemsIndexed(lines, key = { i, l -> "$i/${l.time}" }) { i, l ->
            val look = l.kind.look(c)
            Row(Modifier.enter(i).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(l.time, style = CortanaType.Log, color = c.textFaint, maxLines = 1)
                Box(Modifier.width(14.dp), contentAlignment = Alignment.CenterStart) { Symbol(look.icon, look.iconColor, 15.dp) }
                Text(l.text, style = CortanaType.Log, color = look.textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (running) item(key = "cursor") {
            val t by loop(CortanaMotion.CursorBlink, rest = 0f)
            Box(Modifier.height(18.6.dp).padding(start = 86.dp), contentAlignment = Alignment.CenterStart) {
                // steps(1): visible the first half of the second, hidden the second half.
                Box(Modifier.size(7.dp, 13.dp).graphicsLayer { alpha = if (t < 0.5f) 1f else 0f }.background(c.accentLink))
            }
        }
    }
}

// ------------------------------------------------------------------ tabs

data class TabSpec(@DrawableRes val icon: Int, val label: String)

/** Three equal tabs of the panel (Logs en direct, Fichiers, Aperçu). */
@Composable
fun ContextTabs(tabs: List<TabSpec>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tabs.forEachIndexed { i, t ->
            val on = i == selected
            val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Fast)
            val bd by animateColorAsState(if (on) c.selectedBorder else c.tabBorder, colorSpec, label = "tabBd")
            val shape = RoundedCornerShape(10.dp)
            val src = remember { MutableInteractionSource() }
            TouchTarget(Modifier.tap(src, { onSelect(i) }, role = Role.Tab).semantics { contentDescription = t.label; this.selected = on }, Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(shape)
                        .background(if (on) Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent.copy(alpha = 0.10f))) else Brush.linearGradient(listOf(c.input, c.input)))
                        .border(1.dp, bd, shape),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                ) {
                    Symbol(t.icon, if (on) c.accentSoft else c.tabIcon, 18.dp)
                    Text(t.label, style = CortanaType.Secondary.copy(fontWeight = FontWeight.Medium), color = if (on) c.navTextActive else c.tabText, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ task summary

/** Task tile (78 dp, gradient) with its title, subtitle and tags. */
@Composable
fun TaskSummary(title: String, subtitle: String, tags: List<TagSpec>, modifier: Modifier = Modifier, @DrawableRes icon: Int = Symbols.StickyNote2Fill, tile: Dp = 78.dp, iconSize: Dp = 42.dp, titleSize: Float = 17f) {
    val c = Cortana.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(tile).clip(RoundedCornerShape(if (tile > 70.dp) 16.dp else 15.dp)).background(c.taskTile).border(1.dp, c.taskTileBorder, RoundedCornerShape(if (tile > 70.dp) 16.dp else 15.dp)), contentAlignment = Alignment.Center) {
            Symbol(icon, c.taskTileIcon, iconSize)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = CortanaType.ItemTitle.copy(fontSize = titleSize.sp), color = c.textStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tags.isNotEmpty()) Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t -> Tag(t.text, t.fg, bg = t.bg, border = t.border, height = 26.dp, corner = 7.dp, textStyle = CortanaType.Caption.copy(fontWeight = FontWeight.Medium)) }
            }
        }
    }
}

data class TagSpec(val text: String, val fg: Color, val bg: Color, val border: Color)

/** The three tag colors of the task card: Développement (purple), Android (green), Local (blue). */
object TaskTags {
    fun dev(c: CortanaPalette, text: String = "Développement") = TagSpec(text, c.purpleTag, c.purpleTint, c.purpleBorder)
    fun green(c: CortanaPalette, text: String) = TagSpec(text, c.tagAndroidText, c.successTint, c.successBorder)
    fun blue(c: CortanaPalette, text: String) = TagSpec(text, c.logPlayText, c.accent.copy(alpha = 0.12f), c.accentText.copy(alpha = 0.4f))
}

// ------------------------------------------------------------------ action tiles, info cards

/** 46 dp action of the panel (Review Git, Build, Tests, Artefacts). */
@Composable
fun ActionTile(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.height(46.dp).clip(CortanaShapes.Md).background(if (src.active()) c.controlPressed else c.input).border(1.dp, c.controlBorder, CortanaShapes.Md)
            .tap(src, onClick, label = label).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Symbol(icon, c.pillIcon, 22.dp)
        Text(label, style = CortanaType.Bubble.copy(fontWeight = FontWeight.Medium, lineHeight = 18.sp), color = c.textControl, maxLines = 1)
    }
}

/** Status of an info card: a dot and a text (● ACTIF, ● 4/4), or an icon and a text (✋ 1 demande). */
data class InfoStatus(val text: String, val color: Color, @DrawableRes val icon: Int? = null)

/**
 * State cards under the task (Worker, MCP, Mémoire, Politique): tile, title, status, one or two lines.
 * [highlight] draws the amber outline of a pending approval.
 */
@Composable
fun InfoCard(
    @DrawableRes icon: Int,
    iconColor: Color,
    tileBg: Color,
    tileBorder: Color,
    title: String,
    status: InfoStatus?,
    lines: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
    tileSize: Dp = 40.dp,
    iconSize: Dp = 23.dp,
    padding: PaddingValues = PaddingValues(start = 14.dp, top = 13.dp, end = 16.dp, bottom = 13.dp),
    alignTop: Boolean = false,
    highlight: Boolean = false,
    onMenu: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val hover = onClick != null && src.active()
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (highlight) it.glow(c.warning.copy(alpha = 0.5f), 16.dp, 14.dp, spread = (-4).dp) else it }
                .clip(CortanaShapes.Lg).background(c.surface)
                .border(1.dp, if (highlight) c.warning.copy(alpha = 0.6f) else if (hover) c.infoCardHover else c.surfaceBorder, CortanaShapes.Lg)
                .let { if (onClick != null) it.tap(src, onClick, label = title) else it.semantics(mergeDescendants = true) {} }
                .padding(padding),
            verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val tileShape = RoundedCornerShape(if (tileSize >= 46.dp) 12.dp else 11.dp)
            Box(Modifier.size(tileSize).clip(tileShape).background(tileBg).border(1.dp, tileBorder, tileShape), contentAlignment = Alignment.Center) { Symbol(icon, iconColor, iconSize) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = CortanaType.ItemTitle, color = c.textStrong, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status != null) Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (status.icon != null) 6.dp else 7.dp),
                    ) {
                        if (status.icon != null) Symbol(status.icon, status.color, 17.dp) else Dot(c.success, 8.dp)
                        Text(status.text, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.SemiBold), color = status.color, maxLines = 1)
                    }
                    if (onMenu != null) {
                        val m = remember { MutableInteractionSource() }
                        TouchTarget(Modifier.tap(m, onMenu).semantics { contentDescription = "Actions : $title" }, Modifier.padding(start = 8.dp)) { Symbol(Symbols.MoreVert, c.tabIcon, 20.dp) }
                    }
                }
                lines.forEachIndexed { i, (text, color) ->
                    Text(text, style = if (i == 0 && lines.size > 1) CortanaType.Control.copy(fontWeight = FontWeight.Normal) else CortanaType.Secondary, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ activity rail

/** Portrait rail under the header (spec 9.1): status, current step and progress, time left, Détails, STOP. */
@Composable
fun ActivityRail(status: RunStatus, stepLabel: String, progress: Float, remaining: String, onDetails: () -> Unit, onStop: () -> Unit, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(
        modifier.fillMaxWidth().height(54.dp).clip(CortanaShapes.Lg).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Lg).padding(start = 16.dp, end = 8.dp)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusChip(status)
        Box(Modifier.width(1.dp).height(24.dp).background(c.controlBorder))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stepLabel, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.textControl, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ProgressBar(progress, 4.dp, Modifier.fillMaxWidth(), glow = false)
        }
        Text(remaining, style = CortanaType.Secondary, color = c.textSecondaryAlt, maxLines = 1)
        val src = remember { MutableInteractionSource() }
        TouchTarget(Modifier.tap(src, onDetails).semantics { contentDescription = "Détails de la tâche"; role = Role.Button }) {
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(if (src.active()) c.controlPressed else Color.Transparent)
                    .border(1.dp, c.secondaryButtonBorder, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Détails", style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.textControl)
                Symbol(Symbols.ChevronRight, c.textControl, 20.dp)
            }
        }
        StopButton(status, onStop, onResume, StopSize.Rail)
    }
}

// ------------------------------------------------------------------ files and preview tabs

data class FileChange(val name: String, val added: Int, val removed: Int, @DrawableRes val icon: Int = Symbols.Description, val open: Boolean = false)

/** "Fichiers" tab: folder, then each file with its +n / −n (mono 12). */
@Composable
fun FileTree(folder: String, files: List<FileChange>, modifier: Modifier = Modifier, onOpen: ((FileChange) -> Unit)? = null) {
    val c = Cortana.colors
    val mono = CortanaType.Code.copy(fontSize = 12.sp, lineHeight = 16.sp)
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.console).border(1.dp, c.consoleBorder, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(Symbols.FolderOpen, c.textFaint, 15.dp)
            Text(folder, style = mono.copy(fontSize = 11.5.sp), color = c.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        files.forEach { f ->
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp)).background(if (f.open) c.accent.copy(alpha = 0.1f) else Color.Transparent)
                    .let { if (onOpen != null) it.tap(src, { onOpen(f) }, label = f.name) else it }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(f.icon, if (f.open) c.accentLink else c.fileIcon, 15.dp)
                Text(f.name, style = mono, color = if (f.open) c.navTextActive else c.logText, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.added > 0) Text("+${f.added}", style = mono, color = c.successText)
                if (f.removed > 0) Text("−${f.removed}", style = mono, color = c.dangerText)
            }
        }
    }
}
