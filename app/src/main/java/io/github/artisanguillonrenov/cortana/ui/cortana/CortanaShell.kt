package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.components.CortanaBrand
import io.github.artisanguillonrenov.cortana.ui.components.DevModeCard
import io.github.artisanguillonrenov.cortana.ui.components.NavDivider
import io.github.artisanguillonrenov.cortana.ui.components.NavItem
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.StopButton
import io.github.artisanguillonrenov.cortana.ui.components.StopSize
import io.github.artisanguillonrenov.cortana.ui.components.drawGlow
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaDimens
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

/**
 * Size rules of the design (README "Adaptation aux tailles réelles"): three columns from 1200 dp in
 * landscape (compact columns below 1400 dp); below 1200 dp or in portrait, the "portrait" behaviour
 * (sidebar in a drawer, task panel in an overlay, activity rail).
 */
enum class ShellMode { Wide, Compact, Overlay }

@Immutable
data class ShellLayout(val mode: ShellMode, val sidebarWidth: Dp, val panelWidth: Dp) {
    val overlay get() = mode == ShellMode.Overlay
}

fun shellLayoutFor(width: Dp, height: Dp): ShellLayout = when {
    height > width || width < CortanaDimens.ThreeColumnBreakpoint -> ShellLayout(ShellMode.Overlay, CortanaDimens.SidebarWidth, CortanaDimens.ContextPanelOverlayWidth)
    width < CortanaDimens.WideBreakpoint -> ShellLayout(ShellMode.Compact, CortanaDimens.SidebarWidthCompact, CortanaDimens.ContextPanelWidthCompact)
    else -> ShellLayout(ShellMode.Wide, CortanaDimens.SidebarWidth, CortanaDimens.ContextPanelWidth)
}

/** One entry of the sidebar. [devOnly] entries are shown in developer mode only (spec 16.9). */
data class NavEntry(val route: String, @DrawableRes val icon: Int, val label: String, val devOnly: Boolean = false, val badge: String? = null, val dot: Boolean = false)

/** The sidebar's STOP: what runs (or is paused) and the actions (README "Grand bouton STOP"). */
data class StopUi(
    val status: RunStatus?,
    val onStop: () -> Unit,
    val onResume: () -> Unit,
    /** Long press: the emergency stop of all autonomy (kill switch, §9.4), resumed with the owner's biometrics. */
    val onEmergency: () -> Unit,
)

/** What a screen of the shell needs to draw its header button (collapse the sidebar or open the drawer). */
data class ShellScope(val layout: ShellLayout, val sidebarOpen: Boolean, val toggleSidebar: () -> Unit) {
    @get:DrawableRes val navIcon get() = if (layout.overlay) Symbols.Menu else if (sidebarOpen) Symbols.LeftPanelClose else Symbols.LeftPanelOpen
    val navLabel get() = if (layout.overlay) "Ouvrir le menu" else if (sidebarOpen) "Masquer la barre latérale" else "Afficher la barre latérale"
}

val LocalShell = staticCompositionLocalOf<ShellScope?> { null }

/**
 * The app shell of the design: background with its halo, the sidebar (brand, menu, developer mode,
 * STOP), then the screen. [content] receives the scope that its header uses.
 */
@Composable
fun CortanaShell(
    entries: List<NavEntry>,
    current: String,
    onNavigate: (String) -> Unit,
    devMode: Boolean,
    onDevMode: (Boolean) -> Unit,
    stop: StopUi,
    modifier: Modifier = Modifier,
    content: @Composable (ShellScope) -> Unit,
) {
    val c = Cortana.colors
    val reduce = Cortana.reduceMotion
    BoxWithConstraints(modifier.fillMaxSize().background(c.background).drawBehind {
        // Halo: ellipse 900×520 centred at 58 % / −8 %, fading into the background at 62 %.
        val cx = size.width * 0.58f; val cy = size.height * -0.08f
        val rx = 900.dp.toPx(); val ry = 520.dp.toPx()
        scale(1f, ry / rx, Offset(cx, cy)) {
            drawCircle(Brush.radialGradient(0f to c.backgroundGlow, 0.62f to c.background, center = Offset(cx, cy), radius = rx), rx, Offset(cx, cy))
        }
    }) {
        val layout = shellLayoutFor(maxWidth, maxHeight)
        var sidebarOpen by rememberSaveable { mutableStateOf(true) }
        var drawerOpen by remember { mutableStateOf(false) }
        val scope = ShellScope(layout, if (layout.overlay) drawerOpen else sidebarOpen) { if (layout.overlay) drawerOpen = !drawerOpen else sidebarOpen = !sidebarOpen }
        val go: (String) -> Unit = { r -> drawerOpen = false; onNavigate(r) }
        val sidebar: @Composable (Modifier) -> Unit = { m -> Sidebar(entries.filter { devMode || !it.devOnly }, current, go, devMode, onDevMode, stop, layout.sidebarWidth, m) }
        CompositionLocalProvider(LocalShell provides scope) {
            if (!layout.overlay) {
                val shift by animateDpAsState(if (sidebarOpen) 0.dp else -(layout.sidebarWidth + 1.dp),
                    if (reduce) snap() else tween(CortanaMotion.SidebarCollapse, easing = CortanaMotion.Standard), label = "sidebar")
                Row(Modifier.fillMaxSize().clipToBounds()) {
                    Box(Modifier.width((layout.sidebarWidth + shift).coerceAtLeast(0.dp)).fillMaxHeight()) {
                        if (layout.sidebarWidth + shift > 0.dp) sidebar(Modifier.offset(x = shift))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content(scope) }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    content(scope)
                    Scrim(drawerOpen) { drawerOpen = false }
                    val x by animateDpAsState(if (drawerOpen) 0.dp else (-300).dp, if (reduce) snap() else tween(CortanaMotion.Overlay, easing = CortanaMotion.Standard), label = "drawer")
                    if (drawerOpen || x > (-300).dp) sidebar(
                        Modifier.offset(x = x).drawBehind { drawGlow(c.drawerShadow, 60.dp, offsetY = 0.dp, spread = 0.dp) }
                            .semantics { contentDescription = "Menu" },
                    )
                }
            }
        }
    }
}

/** Scrim of the overlays: rgba(3,6,12,.55), 200 ms fade; a tap closes. */
@Composable
fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    val c = Cortana.colors
    AnimatedVisibility(visible, enter = fadeIn(tween(if (Cortana.reduceMotion) 0 else CortanaMotion.Scrim)), exit = fadeOut(tween(if (Cortana.reduceMotion) 0 else CortanaMotion.Scrim))) {
        val src = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().background(c.scrim).combinedClickable(src, null, onClickLabel = "Fermer", onClick = onDismiss))
    }
}

@Composable
private fun Sidebar(
    entries: List<NavEntry>,
    current: String,
    onNavigate: (String) -> Unit,
    devMode: Boolean,
    onDevMode: (Boolean) -> Unit,
    stop: StopUi,
    width: Dp,
    modifier: Modifier,
) {
    val c = Cortana.colors
    BoxWithConstraints(
        modifier.width(width).fillMaxHeight().testTag("sidebar").background(c.sidebarBrush).drawBehind {
            drawLine(c.sidebarBorder, Offset(size.width - 0.5.dp.toPx(), 0f), Offset(size.width - 0.5.dp.toPx(), size.height), 1.dp.toPx())
        },
    ) {
        val viewport = maxHeight
        // The bottom block (developer mode, STOP) sticks to the bottom; on a short screen the whole sidebar scrolls.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 44.dp, bottom = 54.dp)) {
            PinnedBottom(viewport - 98.dp, top = {
                Column {
                    CortanaBrand()
                    Column(Modifier.padding(top = 38.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        entries.take(5).forEach { e -> NavItem(e.icon, e.label, e.route == current, { onNavigate(e.route) }, badge = e.badge, dot = e.dot) }
                    }
                    NavDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        entries.drop(5).forEach { e -> NavItem(e.icon, e.label, e.route == current, { onNavigate(e.route) }, badge = e.badge, dot = e.dot) }
                    }
                }
            }, bottom = {
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    DevModeCard(devMode, onDevMode)
                    StopButton(stop.status, stop.onStop, stop.onResume, StopSize.Large, onLongPress = stop.onEmergency)
                }
            })
        }
    }
}

/** Top block, then the bottom block pushed to the bottom of [minHeight] (CSS `margin-top: auto`). */
@Composable
private fun PinnedBottom(minHeight: Dp, top: @Composable () -> Unit, bottom: @Composable () -> Unit) {
    Layout(content = { top(); bottom() }) { ms, cs ->
        val loose = cs.copy(minHeight = 0)
        val t = ms[0].measure(loose); val b = ms[1].measure(loose)
        val h = maxOf(minHeight.roundToPx(), t.height + b.height)
        layout(cs.maxWidth, h) { t.place(0, 0); b.place(0, h - b.height) }
    }
}
