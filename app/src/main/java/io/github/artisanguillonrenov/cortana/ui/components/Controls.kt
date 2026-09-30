package io.github.artisanguillonrenov.cortana.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

// ------------------------------------------------------------------ toggle

/** The three switch sizes of the design: 44×26 (settings, sidebar), 38×22 (memory card), 34×20 (header pills). */
enum class ToggleSize(val width: Dp, val height: Dp, val pad: Dp, val knob: Dp, val shift: Dp) {
    Large(44.dp, 26.dp, 3.dp, 20.dp, 18.dp),
    Medium(38.dp, 22.dp, 3.dp, 16.dp, 16.dp),
    Small(34.dp, 20.dp, 2.dp, 16.dp, 14.dp),
}

@Composable
fun CortanaToggle(
    checked: Boolean,
    onChange: ((Boolean) -> Unit)?,
    size: ToggleSize = ToggleSize.Large,
    modifier: Modifier = Modifier,
    trackOn: Color = Cortana.colors.accent,
    knobOff: Color = Cortana.colors.onAccent,
    label: String? = null,
) {
    val c = Cortana.colors
    val spec = if (Cortana.reduceMotion) snap<Dp>() else tween(CortanaMotion.Toggle, easing = CortanaMotion.Standard)
    val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Toggle)
    val x by animateDpAsState(if (checked) size.shift else 0.dp, spec, label = "knob")
    val track by animateColorAsState(if (checked) trackOn else c.toggleOff, colorSpec, label = "track")
    val knob by animateColorAsState(if (checked) c.onAccent else knobOff, colorSpec, label = "knobColor")
    val interaction = remember { MutableInteractionSource() }
    val visual: @Composable () -> Unit = {
        Box(Modifier.size(size.width, size.height).clip(CircleShape).background(track).padding(size.pad)) {
            Box(Modifier.offset(x = x).size(size.knob).clip(CircleShape).background(knob))
        }
    }
    if (onChange == null) Box(modifier) { visual() }
    else TouchTarget(Modifier.toggleable(checked, interaction, null, role = Role.Switch, onValueChange = onChange).semantics { if (label != null) contentDescription = label }, modifier, visual)
}

// ------------------------------------------------------------------ header

/** Square 40 dp button of the headers (collapse the sidebar, menu). */
@Composable
fun HeaderIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val on = src.active()
    TouchTarget(Modifier.tap(src, onClick, label = label).semantics { contentDescription = label }, modifier) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.controlHoverAlt else c.control)
                .border(1.dp, c.headerButtonBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Symbol(icon, if (on) c.navTextActive else c.headerButtonIcon, 22.dp) }
    }
}

/** 48 dp pill of the header (model, Recherche, Voix, Tâche). */
@Composable
fun HeaderPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 10.dp),
    gap: Dp = 8.dp,
    label: String? = null,
    role: Role = Role.Button,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val hover = src.active()
    val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Toggle)
    val bg by animateColorAsState(if (active) c.selectedBg else if (hover) c.controlHover else c.control, colorSpec, label = "pillBg")
    val bd by animateColorAsState(if (active) c.accentIcon.copy(alpha = 0.55f) else c.controlBorder, colorSpec, label = "pillBd")
    Row(
        modifier.height(48.dp).clip(CortanaShapes.Md).background(bg).border(1.dp, bd, CortanaShapes.Md)
            .tap(src, onClick, role = role, label = label).padding(padding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap), content = content,
    )
}

/** Initial tile of a model (no provider logo): the provider color on a 13 % tint of itself. */
@Composable
fun ModelInitial(initial: String, color: Color, size: Dp, corner: Dp, textSize: Float) {
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(color.copy(alpha = 0x22 / 255f)), contentAlignment = Alignment.Center) {
        Text(initial, color = color, style = TextStyle(fontFamily = CortanaType.Control.fontFamily, fontSize = textSize.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
fun ModelPill(initial: String, initialColor: Color, name: String, provider: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    HeaderPill(onClick, modifier, padding = PaddingValues(start = 9.dp, end = 8.dp), label = "Choisir le modèle, actuel : $name") {
        ModelInitial(initial, initialColor, 28.dp, 8.dp, 13f)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1)
            Text(provider, style = CortanaType.Secondary.copy(fontSize = 11.sp), color = c.pillSub, maxLines = 1)
        }
        Symbol(Symbols.ExpandMore, c.pillChevron, 20.dp)
    }
}

@Composable
fun SearchPill(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    HeaderPill({ onToggle(!enabled) }, modifier.semantics { stateDescription = if (enabled) "activée" else "désactivée" }, role = Role.Switch, label = "Recherche web") {
        Symbol(Symbols.Language, c.pillIcon, 21.dp)
        Text("Recherche", style = CortanaType.ControlSmall, color = c.textControl)
        CortanaToggle(enabled, null, ToggleSize.Small, Modifier.padding(start = 2.dp), knobOff = c.toggleKnobOff)
    }
}

@Composable
fun VoicePill(active: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    HeaderPill(onToggle, modifier.semantics { stateDescription = if (active) "écoute en cours" else "inactive" }, active = active,
        padding = PaddingValues(start = 10.dp, end = 11.dp), gap = 7.dp, label = "Voix") {
        Symbol(Symbols.GraphicEq, c.accentText, 21.dp)
        Text("Voix", style = CortanaType.ControlSmall, color = c.textControl)
    }
}

/** Portrait header: "Tâche n/6" opens the task panel. */
@Composable
fun TaskPill(progress: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    HeaderPill(onClick, modifier, padding = PaddingValues(start = 10.dp, end = 12.dp), label = "Ouvrir la tâche active") {
        Symbol(Symbols.AssignmentFill, c.accentText, 21.dp)
        Text("Tâche", style = CortanaType.ControlSmall, color = c.textControl)
        Text(progress, style = CortanaType.Caption.copy(fontFeatureSettings = "tnum"), color = c.textSecondaryAlt)
    }
}

// ------------------------------------------------------------------ chips

/** Tool chip under the thread (38 dp): active = filled icon, green. */
@Composable
fun ToolChip(@DrawableRes icon: Int, @DrawableRes iconFilled: Int, label: String, active: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Fast)
    val bg by animateColorAsState(if (active) c.toolChipActiveBg else c.control, colorSpec, label = "bg")
    val bd by animateColorAsState(if (active) c.toolChipActiveBorder else c.chipBorder, colorSpec, label = "bd")
    val src = remember { MutableInteractionSource() }
    TouchTarget(Modifier.toggleable(active, src, null, role = Role.Switch, onValueChange = onToggle).semantics { contentDescription = "Outils $label" }, modifier) {
        Row(
            Modifier.height(38.dp).clip(CortanaShapes.Sm).background(bg).border(1.dp, bd, CortanaShapes.Sm).padding(start = 11.dp, end = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Symbol(if (active) iconFilled else icon, if (active) c.toolChipActiveIcon else c.chipIcon, 19.dp)
            Text(label, style = CortanaType.ControlSmall, color = if (active) c.toolChipActiveText else c.chipText, maxLines = 1)
        }
    }
}

/** Filter chip with a counter (36 dp): History filters, memory categories. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, count: String? = null) {
    val c = Cortana.colors
    val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Fast)
    val bg by animateColorAsState(if (selected) c.selectedBg else c.control, colorSpec, label = "bg")
    val bd by animateColorAsState(if (selected) c.selectedBorder else c.chipBorder, colorSpec, label = "bd")
    val src = remember { MutableInteractionSource() }
    TouchTarget(Modifier.selectable(selected, src, null, role = Role.Tab, onClick = onClick).semantics { contentDescription = if (count != null) "$label, $count" else label }, modifier) {
        Row(
            Modifier.height(36.dp).clip(CortanaShapes.Sm).background(bg).border(1.dp, bd, CortanaShapes.Sm).padding(start = if (icon != null) 10.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (icon != null) Symbol(icon, if (selected) c.accentSoft else c.chipIcon, 18.dp)
            Text(label, style = CortanaType.ControlSmall, color = if (selected) c.navTextActive else c.chipText, maxLines = 1)
            if (count != null) Text(count, style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted)
        }
    }
}

/** Selector chip ("Projet : tous ▾"). */
@Composable
fun DropChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    TouchTarget(Modifier.tap(src, onClick, label = label).semantics { contentDescription = label }, modifier) {
        Row(
            Modifier.height(36.dp).clip(CortanaShapes.Sm).background(if (src.active()) c.controlHover else c.control)
                .border(1.dp, c.chipBorder, CortanaShapes.Sm).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.chipText, maxLines = 1)
            Symbol(Symbols.ExpandMore, c.textTertiary, 18.dp)
        }
    }
}

/** Small tag (22–26 dp): categories, modes, "Hérité". */
@Composable
fun Tag(text: String, fg: Color, modifier: Modifier = Modifier, bg: Color = Color.Transparent, border: Color? = null, height: Dp = 22.dp, corner: Dp = 6.dp, textStyle: TextStyle = CortanaType.Overline.copy(letterSpacing = 0.em, fontWeight = FontWeight.Medium)) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.height(height).clip(shape).background(bg).let { if (border != null) it.border(1.dp, border, shape) else it }.padding(horizontal = if (height >= 26.dp) 9.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = textStyle, color = fg, maxLines = 1) }
}

/** Counter badge of the sidebar (32×32 min, radius 9). */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Box(
        modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).clip(RoundedCornerShape(9.dp)).background(c.badge).border(1.dp, c.badgeBorder, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.badgeText) }
}

// ------------------------------------------------------------------ segmented

enum class SegmentedStyle { Header, Settings }

@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, style: SegmentedStyle = SegmentedStyle.Header) {
    val c = Cortana.colors
    val header = style == SegmentedStyle.Header
    val outer = if (header) CortanaShapes.Md else CortanaShapes.Sm
    Row(
        modifier.let { if (header) it.height(48.dp) else it }.clip(outer).background(if (header) c.control else c.sunken)
            // CSS: the settings control's 1 px border adds to its 3 px padding (40 dp in all); Compose draws borders inside.
            .border(1.dp, if (header) c.controlBorder else c.tabBorder, outer).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val shape = RoundedCornerShape(if (header) 9.dp else 7.dp)
            val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Fast)
            val bg by animateColorAsState(if (on) c.selectedSegment else Color.Transparent, colorSpec, label = "seg")
            val src = remember { MutableInteractionSource() }
            TouchTarget(Modifier.selectable(on, src, null, role = Role.Tab) { onSelect(i) }.semantics { contentDescription = o }) {
                Box(
                    Modifier.height(if (header) 38.dp else 32.dp).clip(shape).background(bg)
                        .let { if (on && !header) it.border(1.dp, c.accentIcon.copy(alpha = 0.45f), shape) else it }
                        .padding(horizontal = if (header) 14.dp else 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(o, style = if (header) CortanaType.ControlSmall else CortanaType.Secondary.copy(fontWeight = FontWeight.Medium),
                        color = if (on) c.navTextActive else if (header) c.pillChevron else c.chipIcon, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ buttons

enum class ButtonKind { Primary, Secondary, Link, Danger }

/** Filled (accent, danger) or outlined buttons of the design; the icon is optional. */
@Composable
fun CortanaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    @DrawableRes icon: Int? = null,
    height: Dp = 48.dp,
    corner: Dp = 12.dp,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp),
    gap: Dp = 7.dp,
    iconSize: Dp = 20.dp,
    textStyle: TextStyle = CortanaType.Control,
    shadow: Boolean = false,
    fill: Boolean = false,
    centered: Boolean = true,
    border: Color? = null,
    tracking: Float = 0f,
) {
    val c = Cortana.colors
    val shape = RoundedCornerShape(corner)
    val src = remember { MutableInteractionSource() }
    val on = src.active()
    val filled = kind == ButtonKind.Primary || kind == ButtonKind.Danger
    val fg = when (kind) { ButtonKind.Primary, ButtonKind.Danger -> c.onAccent; ButtonKind.Link -> c.accentLinkHover; ButtonKind.Secondary -> c.textControl }
    TouchTarget(Modifier.tap(src, onClick, label = label).semantics { contentDescription = label; role = Role.Button }, modifier.let { if (fill) it.fillMaxWidth() else it }) {
    Row(
        Modifier.let { if (fill) it.fillMaxWidth() else it }.height(height)
            .let { m -> if (shadow && filled) m.glow(if (kind == ButtonKind.Danger) c.danger.copy(alpha = 0.7f) else c.accent.copy(alpha = 0.7f), 22.dp, corner, spread = (-8).dp, offsetY = 8.dp) else m }
            .clip(shape)
            .let { m ->
                when (kind) {
                    ButtonKind.Primary -> m.background(c.accentButton)
                    ButtonKind.Danger -> m.background(c.dangerButton)
                    else -> m.background(if (on) c.controlPressed else c.input)
                }
            }
            .let { m -> (border ?: when (kind) { ButtonKind.Secondary -> c.secondaryButtonBorder; ButtonKind.Link -> c.linkButtonBorder; ButtonKind.Danger -> c.dangerEdge; else -> null })?.let { m.border(1.dp, it, shape) } ?: m }
            .brighten(filled && on, c.onAccent)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.spacedBy(gap, Alignment.CenterHorizontally) else Arrangement.spacedBy(gap),
    ) {
        if (icon != null) Symbol(icon, fg, iconSize)
        Text(label, style = textStyle.copy(letterSpacing = tracking.em), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

// ------------------------------------------------------------------ STOP

enum class StopSize { Large, Panel, Rail, Card }

/**
 * STOP everywhere (sidebar, panel, rail, task card, Échap): red while the task runs, blue "Reprendre" when
 * paused, neutral when there is nothing to stop. [doneLabel] replaces the neutral label of panel buttons.
 */
@Composable
fun StopButton(
    status: RunStatus?,
    onStop: () -> Unit,
    onResume: () -> Unit,
    size: StopSize,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    doneLabel: String = "Aucune tâche en cours",
    /** Large button only: long press = emergency stop of all autonomy (kill switch). */
    onLongPress: (() -> Unit)? = null,
) {
    val c = Cortana.colors
    val running = status == RunStatus.Running || status == RunStatus.AwaitingApproval
    val paused = status == RunStatus.Paused
    val src = remember { MutableInteractionSource() }
    val on = src.active()
    val reduce = Cortana.reduceMotion
    when (size) {
        StopSize.Large -> {
            val shape = CortanaShapes.Xl
            val base = modifier.fillMaxWidth().height(84.dp)
            when {
                running -> Row(
                    base.glow(c.dangerGlow, 30.dp, 16.dp, spread = (-8).dp, offsetY = 10.dp).pressScale(on, 0.985f, reduce).clip(shape).background(c.dangerButton)
                        .border(1.dp, c.dangerEdge, shape).topHighlight(c.onAccent.copy(alpha = 0.18f), 16.dp).brighten(on, c.onAccent)
                        .combinedClickable(src, null, role = Role.Button, onClickLabel = "Arrêter la tâche (STOP)",
                            onLongClickLabel = if (onLongPress != null) "Arrêt d'urgence de toute l'autonomie" else null, onLongClick = onLongPress, onClick = onStop),
                    horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.onAccent), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c.dangerInner))
                    }
                    Text("STOP", style = CortanaType.Control.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em), color = c.onAccent)
                }
                paused -> Row(
                    base.glow(c.accent.copy(alpha = 0.55f), 30.dp, 16.dp, spread = (-8).dp, offsetY = 10.dp).pressScale(on, 0.985f, reduce).clip(shape).background(c.accentButton)
                        .border(1.dp, c.userBubbleHighlight.copy(alpha = 0.45f), shape).topHighlight(c.onAccent.copy(alpha = 0.18f), 16.dp).brighten(on, c.onAccent)
                        .tap(src, onResume, label = "Reprendre la tâche"),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(Symbols.PlayArrowFill, c.onAccent, 32.dp)
                    Text("Reprendre", style = CortanaType.Control.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.03.em), color = c.onAccent)
                }
                else -> Row(
                    base.clip(shape).background(c.neutralButton).border(1.dp, c.neutralButtonBorder, shape).semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(Symbols.CheckCircle, c.textTertiary, 24.dp)
                    Text(doneLabel, style = CortanaType.Control.copy(fontSize = 15.5.sp), color = c.textTertiary)
                }
            }
        }
        StopSize.Panel, StopSize.Card -> {
            val card = size == StopSize.Card
            when {
                running -> CortanaButton("STOP", onStop, modifier, ButtonKind.Danger, Symbols.StopCircleFill, 48.dp, 12.dp,
                    PaddingValues(start = if (card) 14.dp else 0.dp, end = if (card) 18.dp else 0.dp), gap = if (card) 8.dp else 10.dp,
                    iconSize = if (card) 22.dp else 24.dp, textStyle = CortanaType.Control.copy(fontSize = if (card) 14.5.sp else 15.sp, fontWeight = FontWeight.SemiBold),
                    shadow = true, fill = !card, tracking = 0.06f)
                paused -> CortanaButton("Reprendre", onResume, modifier, ButtonKind.Primary, Symbols.PlayArrowFill, 48.dp, 12.dp,
                    PaddingValues(start = if (card) 12.dp else 0.dp, end = if (card) 18.dp else 0.dp), gap = if (card) 6.dp else 8.dp,
                    iconSize = if (card) 22.dp else 24.dp, textStyle = CortanaType.Control.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold), fill = !card,
                    border = if (card) null else c.userBubbleHighlight.copy(alpha = 0.45f))
                else -> CortanaButton(doneLabel, onDone ?: {}, modifier, if (card) ButtonKind.Secondary else ButtonKind.Link, Symbols.Replay, 48.dp, if (card) 11.dp else 12.dp,
                    PaddingValues(start = if (card) 12.dp else 0.dp, end = if (card) 16.dp else 0.dp), gap = if (card) 7.dp else 8.dp, fill = !card,
                    border = if (card) c.secondaryButtonBorder else c.linkButtonBorder)
            }
        }
        StopSize.Rail -> when {
            running -> CortanaButton("STOP", onStop, modifier, ButtonKind.Danger, Symbols.StopCircleFill, 38.dp, 10.dp, PaddingValues(start = 10.dp, end = 14.dp),
                gap = 6.dp, iconSize = 20.dp, textStyle = CortanaType.ControlSmall.copy(fontWeight = FontWeight.SemiBold), border = Color.Transparent, tracking = 0.05f)
            paused -> CortanaButton("Reprendre", onResume, modifier, ButtonKind.Primary, Symbols.PlayArrowFill, 38.dp, 10.dp, PaddingValues(start = 10.dp, end = 14.dp),
                gap = 4.dp, iconSize = 20.dp, textStyle = CortanaType.ControlSmall.copy(fontWeight = FontWeight.SemiBold))
            else -> {}
        }
    }
}

/** `inset 0 1px 0 color`: the thin highlight at the top of the filled buttons and bubbles. */
fun Modifier.topHighlight(color: Color, corner: Dp): Modifier = drawBehind {
    val r = corner.toPx()
    val h = 1.dp.toPx()
    clipRect(0f, 0f, size.width, h + r / 2) {
        drawRoundRect(color, Offset(0f, 0f), Size(size.width, size.height), CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(h))
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRect(l: Float, t: Float, r: Float, b: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
    drawContext.canvas.let { canvas -> canvas.save(); canvas.clipRect(l, t, r, b); block(); canvas.restore() }

// ------------------------------------------------------------------ status, progress, dots

/** Status of the task: always an icon and a text (spec 16.7). */
@Composable
fun StatusChip(status: RunStatus, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (status == RunStatus.Running) 8.dp else 6.dp)) {
        val style = CortanaType.Control
        when (status) {
            RunStatus.Running -> { PulseDot(9.dp); Text("En cours", style = style, color = c.successText) }
            RunStatus.AwaitingApproval -> { Symbol(Symbols.FrontHand, c.warningText, 18.dp); Text("Approbation", style = style, color = c.warningText) }
            RunStatus.Paused -> { Symbol(Symbols.PauseCircle, c.warning, 18.dp); Text("En pause", style = style, color = c.warning) }
            RunStatus.Done -> { Symbol(Symbols.CheckCircleFill, c.successText, 18.dp); Text("Terminée", style = style, color = c.successText) }
            RunStatus.Failed -> { Symbol(Symbols.WarningFill, c.dangerText, 18.dp); Text("Échec", style = style, color = c.dangerText) }
            RunStatus.Stopped -> { Symbol(Symbols.StopCircleFill, c.dangerText, 18.dp); Text("Arrêtée", style = style, color = c.dangerText) }
        }
    }
}

/** Green dot with a ring pulsing from 0 to 7 dp that fades (1.8 s). */
@Composable
fun PulseDot(size: Dp, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val t by loop(CortanaMotion.PulsePeriod, rest = 1f)
    Canvas(modifier.size(size)) {
        // CSS: box-shadow 0 0 0 0 → 7px at 70 % of the cycle, alpha .6 → 0
        val p = (t / 0.7f).coerceAtMost(1f)
        val alpha = if (t >= 0.7f) 0f else 0.6f * (1f - p)
        if (alpha > 0f) drawCircle(c.success.copy(alpha = alpha), radius = this.size.minDimension / 2 + 7.dp.toPx() * p)
        drawCircle(c.success)
    }
}

/** Progress bar: rounded track, blue gradient with a glow; the width animates over 600 ms. */
@Composable
fun ProgressBar(fraction: Float, height: Dp, modifier: Modifier = Modifier, glow: Boolean = true, label: String? = null) {
    val c = Cortana.colors
    val spec = if (Cortana.reduceMotion) snap<Float>() else tween(CortanaMotion.Progress, easing = CortanaMotion.Standard)
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spec, label = "progress")
    BoxWithConstraints(modifier.height(height).semantics { if (label != null) contentDescription = label }) {
        val w = maxWidth * f
        Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(c.surfaceBorder))
        if (glow) Box(Modifier.width(w).fillMaxHeight().glow(c.progressGlow, 10.dp, height / 2))
        Box(Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(c.progressBrush))
    }
}

/** Three 7 dp dots of the live status line (1.2 s, staggered by 150 ms). Static when motion is reduced. */
@Composable
fun StreamingDots(modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.semantics { contentDescription = "Cortana travaille" }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val t by loop(CortanaMotion.DotsPeriod, rest = 0.4f, delay = i * CortanaMotion.DotsStagger)
            // keyframes: 0 %, 80 %, 100 % → opacity .25, y 0; 40 % → opacity 1, y −3
            val k = when { t <= 0.4f -> t / 0.4f; t <= 0.8f -> 1f - (t - 0.4f) / 0.4f; else -> 0f }
            Box(Modifier.size(7.dp).graphicsLayer { alpha = 0.25f + 0.75f * k; translationY = -3.dp.toPx() * k }.clip(CircleShape).background(c.accentBar))
        }
    }
}

/** Spacer that keeps a Row's flexible middle. */
@Composable
fun RowScope.Flex() = Spacer(Modifier.weight(1f))
