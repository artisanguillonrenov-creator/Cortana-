package io.github.artisanguillonrenov.cortana.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaDimens
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaPalette
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

// ------------------------------------------------------------------ sidebar

/** Brand block of the sidebar: pulsing ring (3.2 s), "Cortana" in Sora, subtitle. */
@Composable
fun CortanaBrand(modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.height(66.dp).padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CortanaRing(CortanaDimens.LogoRing, 4.dp, 18.dp, 12.dp, pulsePeriod = CortanaMotion.LogoGlowPeriod)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Cortana", style = CortanaType.Wordmark, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Text("Votre alliée IA au quotidien", style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.brandSubtitle, maxLines = 1, softWrap = false)
        }
    }
}

/** Menu item (48 dp): active = gradient, inner rim and glowing left bar; badge or green dot. */
@Composable
fun NavItem(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    dot: Boolean = false,
) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val hover = src.active()
    val shape = CortanaShapes.Md
    Row(
        modifier.fillMaxWidth().height(CortanaDimens.NavItemHeight).clip(shape)
            .background(if (selected) c.navActive else Brush.linearGradient(listOf(if (hover) c.navHover else Color.Transparent, if (hover) c.navHover else Color.Transparent)))
            .let { if (selected) it.border(1.dp, c.navActiveEdge, shape) else it }
            .drawBehind {
                if (selected) {
                    val top = 9.dp.toPx(); val w = 3.dp.toPx()
                    drawGlow(c.accentBar.copy(alpha = 0.9f), 10.dp, corner = 3.dp, topLeft = Offset(0f, top), area = Size(w, size.height - 2 * top))
                    drawRoundRect(c.accentBar, Offset(0f, top), Size(w, size.height - 2 * top), CornerRadius(w))
                }
            }
            .selectable(selected, src, null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(label, badge?.let { "$it éléments" }, if (dot) "actif" else null).joinToString(", ") }
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Symbol(icon, if (selected) c.accentIcon else c.navIcon, 25.dp)
        Text(label, style = CortanaType.Nav.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = if (selected) c.navTextActive else c.navText,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) CountBadge(badge)
        if (dot) Dot(c.success, 10.dp, Modifier.padding(end = 10.dp), glow = c.success.copy(alpha = 0.8f))
    }
}

/** 1 dp separator of the menu (margin 16 vertical, 12 horizontal). */
@Composable
fun NavDivider() = Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp).fillMaxWidth().height(1.dp).background(Cortana.colors.dividerAlt))

/** "Mode développeur" card at the bottom of the sidebar (68 dp). */
@Composable
fun DevModeCard(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(
        modifier.fillMaxWidth().height(68.dp).clip(CortanaShapes.Md).background(c.devCard).border(1.dp, c.devCardBorder, CortanaShapes.Md).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Symbol(Symbols.AutoFixHighFill, c.accentIcon, 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mode développeur", style = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, softWrap = false)
            Text(if (enabled) "Outils avancés activés" else "Outils avancés masqués", style = CortanaType.Secondary, color = c.devSubtitle, maxLines = 1, softWrap = false)
        }
        CortanaToggle(enabled, onChange, ToggleSize.Large, label = "Mode développeur")
    }
}

// ------------------------------------------------------------------ model menu

data class ModelOption(val key: String, val initial: String, val color: Color, val name: String, val provider: String, val description: String)

/** Model menu (310 dp): overline "MODÈLE", one row per model, check on the active one, "Gérer les fournisseurs →". */
@Composable
fun ModelMenu(options: List<ModelOption>, selectedKey: String?, onPick: (ModelOption) -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Column(
        modifier.width(310.dp).glow(c.shadow, 60.dp, 16.dp, spread = (-12).dp, offsetY = 24.dp).clip(CortanaShapes.Xl).background(c.elevated)
            .border(1.dp, c.elevatedBorder, CortanaShapes.Xl).padding(8.dp).enter("menu", 160),
    ) {
        Overline("Modèle", modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        options.forEach { o ->
            val on = o.key == selectedKey
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) c.accent.copy(alpha = 0.1f) else if (src.active()) c.menuHover else Color.Transparent)
                    .selectable(on, src, null, role = Role.RadioButton) { onPick(o) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelInitial(o.initial, o.color, 32.dp, 9.dp, 14f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(o.name, style = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${o.provider} · ${o.description}", style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.pillSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (on) Symbol(Symbols.Check, c.accentIcon, 20.dp, contentDescription = "Sélectionné")
            }
        }
        Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(c.menuDivider))
        val src = remember { MutableInteractionSource() }
        val hover = src.active()
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (hover) c.controlHoverAlt else Color.Transparent).tap(src, onManage, label = "Gérer les fournisseurs").padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Gérer les fournisseurs", style = CortanaType.Secondary, color = if (hover) c.navTextActive else c.pillChevron, modifier = Modifier.weight(1f))
            Symbol(Symbols.ArrowForward, if (hover) c.navTextActive else c.pillChevron, 18.dp)
        }
    }
}

/** Initial and color of a provider (no logo): O, A, G, L (README "Logos de marques"). */
fun providerLook(c: CortanaPalette, providerName: String, local: Boolean): Pair<String, Color> {
    val n = providerName.lowercase()
    return when {
        local -> "L" to c.providerLocal
        "openai" in n || "gpt" in n -> "O" to c.providerOpenAi
        "anthropic" in n || "claude" in n -> "A" to c.providerAnthropic
        "google" in n || "gemini" in n -> "G" to c.providerGoogle
        else -> (providerName.firstOrNull()?.uppercase() ?: "?") to c.providerOpenAi
    }
}

// ------------------------------------------------------------------ history

/** Mode of a conversation: icon, tint and tile of History rows (Discussion, Agent/Dev, Recherche, Conseil, Voix). */
enum class ConversationKind { Chat, Dev, Search, Council, Voice }

data class KindLook(@DrawableRes val icon: Int, val tint: Color, val tile: Color)

fun ConversationKind.look(c: CortanaPalette): KindLook = when (this) {
    ConversationKind.Chat -> KindLook(Symbols.Forum, c.accentIcon, c.accent.copy(alpha = 0.14f))
    ConversationKind.Dev -> KindLook(Symbols.Code, c.providerLocal, c.purpleTint)
    ConversationKind.Search -> KindLook(Symbols.TravelExplore, c.cyan, c.cyanBg)
    ConversationKind.Council -> KindLook(Symbols.Groups, c.amber, c.warning.copy(alpha = 0.14f))
    ConversationKind.Voice -> KindLook(Symbols.GraphicEq, c.successText, c.success.copy(alpha = 0.13f))
}

@Composable
fun ConversationRow(
    title: String,
    preview: String,
    time: String,
    kind: ConversationKind,
    modeLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    liveLabel: String? = null,
    branches: Int = 1,
) {
    val c = Cortana.colors
    val look = kind.look(c)
    val src = remember { MutableInteractionSource() }
    val colorSpec = if (Cortana.reduceMotion) snap<Color>() else tween(CortanaMotion.Fast)
    val bd by animateColorAsState(if (selected) c.accentIcon.copy(alpha = 0.45f) else if (src.active()) c.infoCardHover else c.surfaceBorder, colorSpec, label = "bd")
    Row(
        modifier.fillMaxWidth().clip(CortanaShapes.Lg).background(if (selected) c.accent.copy(alpha = 0.1f) else c.surface).border(1.dp, bd, CortanaShapes.Lg)
            .selectable(selected, src, null, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CortanaShapes.Md).background(look.tile), contentAlignment = Alignment.Center) { Symbol(look.icon, look.tint, 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = CortanaType.ItemTitle, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (pinned) Symbol(Symbols.PushPinFill, c.accentIcon, 16.dp, contentDescription = "Épinglée")
            }
            Text(preview, style = CortanaType.Secondary, color = c.kebab, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(time, style = CortanaType.Caption, color = c.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val small = CortanaType.Caption.copy(fontSize = 11.5.sp)
                if (liveLabel != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PulseDot(7.dp); Text(liveLabel, style = small.copy(fontWeight = FontWeight.SemiBold), color = c.successText)
                }
                if (branches > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Symbol(Symbols.CallSplit, c.metaText, 15.dp); Text("$branches", style = small, color = c.metaText)
                }
                Tag(modeLabel, look.tint, bg = look.tile, textStyle = small.copy(fontWeight = FontWeight.Medium))
            }
        }
    }
}

// ------------------------------------------------------------------ memory

enum class MemoryCategory(val label: String) { Profile("Profil"), Preferences("Préférences"), Projects("Projets"), Devices("Appareils"), Facts("Faits") }

fun MemoryCategory.colors(c: CortanaPalette): Pair<Color, Color> = when (this) {
    MemoryCategory.Profile -> c.accentLink to c.accent.copy(alpha = 0.14f)
    MemoryCategory.Preferences -> c.purpleText to c.purpleTint
    MemoryCategory.Projects -> c.successLog to c.success.copy(alpha = 0.13f)
    MemoryCategory.Devices -> c.amber to c.warning.copy(alpha = 0.14f)
    MemoryCategory.Facts -> c.cyan to c.cyanBg
}

/**
 * A memory (spec 6): category, confirmed/suggested, fact, provenance; active in this conversation or
 * ignored (55 % opacity); locked by the security policy, or editable (Corriger, Oublier).
 */
@Composable
fun MemoryCard(
    fact: String,
    category: MemoryCategory,
    confirmed: Boolean,
    source: String,
    @DrawableRes sourceIcon: Int,
    active: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Cortana.colors
    val (fg, bg) = category.colors(c)
    Column(
        modifier.fillMaxWidth().alpha(if (active) 1f else 0.55f).clip(CortanaShapes.Lg).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Lg)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(category.label, fg, bg = bg, textStyle = CortanaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val sc = if (confirmed) c.successText else c.warningText
                Symbol(if (confirmed) Symbols.CheckCircleFill else Symbols.HelpFill, sc, 15.dp)
                Text(if (confirmed) "Confirmé" else "Suggéré", style = CortanaType.Caption.copy(fontSize = 12.sp), color = sc)
            }
        }
        Text(fact, style = CortanaType.Bubble.copy(lineHeight = 21.sp), color = c.textControl, modifier = Modifier.heightIn(min = 42.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(sourceIcon, c.textMuted, 15.dp)
            Text(source, style = CortanaType.Caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (locked) {
                Symbol(Symbols.Lock, c.textTertiary, 18.dp)
                Text("Géré par la politique de sécurité", style = CortanaType.Caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            } else {
                CortanaToggle(active, onToggle, ToggleSize.Medium, label = "Utiliser ce souvenir dans cette conversation")
                Text(if (active) "Actif dans cette conversation" else "Ignoré dans cette conversation", style = CortanaType.Caption, color = c.metaText, modifier = Modifier.weight(1f))
                SmallIconButton(Symbols.Edit, "Corriger", onEdit)
                SmallIconButton(Symbols.Delete, "Oublier", onForget, danger = true)
            }
        }
    }
}

/** 30–34 dp icon button (Corriger, Oublier, Désépingler, monter, descendre). */
@Composable
fun SmallIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 30.dp, iconSize: Dp = 18.dp, danger: Boolean = false, tint: Color = Cortana.colors.metaText) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val on = src.active()
    TouchTarget(Modifier.tap(src, onClick).semantics { contentDescription = label; role = Role.Button }, modifier) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (size >= 34.dp) 9.dp else 8.dp))
                .background(if (!on) Color.Transparent else if (danger) c.dangerText.copy(alpha = 0.12f) else c.controlPressed),
            contentAlignment = Alignment.Center,
        ) { Symbol(icon, if (!on) tint else if (danger) c.dangerTextSoft else c.navTextActive, iconSize) }
    }
}

// ------------------------------------------------------------------ settings

/** Settings row (62 dp min): label and description, then a switch, a segmented control or a value ›. */
@Composable
fun SettingsRow(label: String, modifier: Modifier = Modifier, description: String? = null, first: Boolean = false, control: @Composable RowScope.() -> Unit) {
    val c = Cortana.colors
    Row(
        // The 1 dp top border counts in the row's height, as in the prototype (border-box, min 62).
        modifier.fillMaxWidth().heightIn(min = 62.dp).drawBehind { if (!first) drawLine(c.divider, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(start = 18.dp, top = 13.dp, end = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = CortanaType.Bubble.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp), color = c.textPrimary)
            if (!description.isNullOrEmpty()) Text(description, style = CortanaType.Caption, color = c.textTertiary)
        }
        control()
    }
}

@Composable
fun SettingsToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, first: Boolean = false) =
    SettingsRow(label, description = description, first = first) { CortanaToggle(checked, onChange, ToggleSize.Large, label = label) }

@Composable
fun SettingsSegmentRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, description: String? = null, first: Boolean = false) =
    SettingsRow(label, description = description, first = first) { SegmentedControl(options, selected, onSelect, style = SegmentedStyle.Settings) }

@Composable
fun SettingsValueRow(label: String, value: String, onClick: () -> Unit, description: String? = null, first: Boolean = false) =
    SettingsRow(label, description = description, first = first) {
        val c = Cortana.colors
        val src = remember { MutableInteractionSource() }
        val on = src.active()
        TouchTarget(Modifier.tap(src, onClick).semantics { contentDescription = "$label : $value"; role = Role.Button }) {
            Row(
                Modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.controlHoverAlt else Color.Transparent).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(value, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = if (on) c.navTextActive else c.pillChevron, maxLines = 1)
                Symbol(Symbols.ChevronRight, if (on) c.navTextActive else c.pillChevron, 20.dp)
            }
        }
    }

/** Item of the settings sub-menu (44 dp). */
@Composable
fun SettingsNavItem(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(if (selected) Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent.copy(alpha = 0.07f))) else Brush.linearGradient(listOf(if (src.active()) c.navHover else Color.Transparent, if (src.active()) c.navHover else Color.Transparent)))
            .let { if (selected) it.border(1.dp, c.navActiveEdge, shape) else it }
            .selectable(selected, src, null, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Symbol(icon, if (selected) c.accentIcon else c.chipIcon, 21.dp)
        Text(label, style = CortanaType.Bubble.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, lineHeight = 18.sp),
            color = if (selected) c.navTextActive else c.chipText, maxLines = 1)
    }
}

// ------------------------------------------------------------------ generic lists and cards

/** Card (radius 16) of the secondary columns. */
@Composable
fun CortanaCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = Cortana.colors
    Column(modifier.fillMaxWidth().clip(CortanaShapes.Xl).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Xl).padding(padding), content = content)
}

/** Card title: icon, 15/600 title, trailing count or tag. */
@Composable
fun CardTitle(@DrawableRes icon: Int?, title: String, modifier: Modifier = Modifier, iconColor: Color = Cortana.colors.planIcon, trailing: String? = null, size: Float = 15f, bottom: Dp = 0.dp) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth().padding(bottom = bottom), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Symbol(icon, iconColor, if (size > 15.5f) 21.dp else 20.dp)
        Text(title, style = CortanaType.ItemTitle.copy(fontSize = size.sp), color = if (size > 15.5f) c.textStrong else c.textDefault, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = CortanaType.Caption, color = c.textMuted)
    }
}

/** Section overline with a small counter ("EN COURS 1"). */
@Composable
fun SectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(title)
        if (count != null) Box(Modifier.height(18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(5.dp)).background(c.badge).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
            Text("$count", style = CortanaType.Caption.copy(fontSize = 11.sp), color = c.pillChevron)
        }
    }
}

/** List row (radius 14): tile, title and subtitle, trailing content (tasks, queue, schedules, done). */
@Composable
fun ListRow(
    @DrawableRes icon: Int,
    iconColor: Color,
    tileBg: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    tileBorder: Color? = null,
    contentAlpha: Float = 1f,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Cortana.colors
    Row(
        modifier.fillMaxWidth().clip(CortanaShapes.Lg).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Lg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.weight(1f).alpha(contentAlpha), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val shape = RoundedCornerShape(11.dp)
            Box(Modifier.size(40.dp).clip(shape).background(tileBg).let { if (tileBorder != null) it.border(1.dp, tileBorder, shape) else it }, contentAlignment = Alignment.Center) {
                Symbol(icon, iconColor, 21.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = CortanaType.Bubble.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp), color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = CortanaType.Secondary, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/** File or artifact row (34 dp tile, name, meta, trailing action). */
@Composable
fun FileRow(@DrawableRes icon: Int, name: String, meta: String, modifier: Modifier = Modifier, iconColor: Color = Cortana.colors.pillIcon, vertical: Dp = 8.dp, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth().padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.tile).border(1.dp, c.tileBorder, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Symbol(icon, iconColor, 19.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = CortanaType.ControlSmall, color = c.textControl, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Kind of an action of the audit log. */
enum class AuditKind { Ok, Running, Waiting, Stopped }

/** Audit timeline row: state icon and connector line, title, duration, detail, time in mono. */
@Composable
fun AuditRow(kind: AuditKind, title: String, detail: String, time: String, duration: String, last: Boolean, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth().enter(title + time).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (kind) {
                AuditKind.Ok -> Symbol(Symbols.CheckCircleFill, c.successText, 20.dp)
                AuditKind.Running -> {
                    val a by loop(CortanaMotion.ProgressIconPeriod, rest = 0f, to = 360f)
                    Symbol(Symbols.ProgressActivity, c.accentIcon, 20.dp, rotation = a)
                }
                AuditKind.Waiting -> Symbol(Symbols.FrontHand, c.warningText, 20.dp)
                AuditKind.Stopped -> Symbol(Symbols.StopCircleFill, c.dangerText, 20.dp)
            }
            if (!last) Box(Modifier.padding(vertical = 4.dp).width(1.5.dp).heightIn(min = 12.dp).height(40.dp).background(c.controlBorder))
        }
        Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = CortanaType.Control, color = c.textControl, modifier = Modifier.weight(1f))
                if (duration.isNotEmpty()) Text(duration, style = CortanaType.Caption.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = c.textMuted)
            }
            Text(detail, style = CortanaType.Caption, color = c.textTertiary)
            Text(time, style = CortanaType.Code.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.auditTime)
        }
    }
}

/** Search field of the headers (48 dp, magnifier at 14 dp, focus rim and halo). */
@Composable
fun SearchField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    BasicTextField(
        value, onValue, modifier.height(48.dp).let { if (focused) it.glow(c.focusHalo, 0.dp, 12.dp, spread = 3.dp) else it }
            .clip(CortanaShapes.Md).background(c.control).border(1.dp, if (focused) c.focusBorder else c.controlBorder, CortanaShapes.Md)
            .semantics { contentDescription = placeholder },
        singleLine = true, textStyle = CortanaType.Control.copy(fontWeight = FontWeight.Normal, color = c.textPrimary), interactionSource = src,
        cursorBrush = SolidColor(c.accentIcon),
        decorationBox = { inner ->
            Row(Modifier.fillMaxHeight().padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(Symbols.Search, c.textTertiary, 20.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.placeholder, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** Branch mini-tree of History: current branch = filled dot; others = hollow dot with an elbow. */
@Composable
fun BranchRow(label: String, meta: String, current: Boolean, sub: Boolean, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth().height(34.dp).padding(start = if (sub) 5.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sub) Canvas(Modifier.size(14.dp, 22.dp).padding(bottom = 0.dp)) {
            val w = 1.5.dp.toPx(); val r = 7.dp.toPx()
            // elbow: left border and bottom border of a box raised by its own height, bottom-left radius 7
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2, -size.height); lineTo(w / 2, size.height / 2 - r)
                quadraticTo(w / 2, size.height / 2, r, size.height / 2); lineTo(size.width, size.height / 2)
            }
            drawPath(path, c.branchLine, style = Stroke(w))
        }
        Canvas(Modifier.size(11.dp)) {
            val w = 2.dp.toPx()
            if (current) drawCircle(c.accentBar)
            drawCircle(if (current) c.accentBar else c.branchDot, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        }
        Text(label, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = if (current) c.navTextActive else c.codeChipText,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(meta, style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted)
    }
}

/** Title of a screen: 27/700 and its subtitle (12, one line), with a collapse/menu button and trailing controls. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    @DrawableRes navIcon: Int,
    navLabel: String,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth().height(CortanaDimens.HeaderHeight), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        HeaderIconButton(navIcon, navLabel, onNav)
        // The title's box is 27 dp as in the prototype (CSS line-height 1); the subtitle may overflow the 56 dp row, unclipped.
        Column(Modifier.weight(1f).wrapContentHeight(Alignment.Top, unbounded = true).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = CortanaType.ScreenTitle, color = c.textStrong, maxLines = 1, modifier = Modifier.lineBox(27.dp).semantics { heading() })
            Text(subtitle, style = CortanaType.Caption.copy(fontSize = 12.sp, lineHeight = 14.sp), color = c.textSecondaryAlt, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Context level of the design: 4 segments (Faible · Moyen · Élevé · Compactage), never a fake percentage. */
@Composable
fun ContextGauge(level: Int, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val names = listOf("Faible", "Moyen", "Élevé", "Compactage")
    Column(modifier.semantics(mergeDescendants = true) { stateDescription = "Contexte ${names[level.coerceIn(0, 3)].lowercase()}" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { i -> Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (i <= level) c.accentBar else c.surfaceBorder)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            names.forEachIndexed { i, n -> Text(n, style = CortanaType.Caption.copy(fontSize = 11.5.sp), color = if (i == level) c.accentSoft else c.textMuted, modifier = Modifier.weight(1f)) }
        }
    }
}
