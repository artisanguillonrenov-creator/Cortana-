package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.components.ScreenHeader

/** STOP of the design: a pause (default, "Reprendre" continues) or a cancellation, as chosen in Réglages › Streaming. */
internal fun stopTask(c: io.github.artisanguillonrenov.cortana.AppContainer): Boolean {
    if (!c.orchestrator.isBusy()) return false
    if (c.settings.current.chat.stopAction == "cancel") c.orchestrator.cancel("Annulé par le propriétaire") else c.orchestrator.pause()
    return true
}

/** The shell's header button outside the shell (previews, tests): no sidebar to toggle. */
@Composable
internal fun currentShell(): ShellScope = LocalShell.current ?: ShellScope(shellLayoutFor(1448.dp, 1086.dp), true) {}

/**
 * Screens 3 to 6 of the design (README "Mise en page de base"): padding 40 / 12 / 24 / 18, the header of
 * Discussion, an optional band (filters), then a flexible main column and a 388 dp side column, 16 dp
 * apart. Under 760 dp the side column goes under the main one.
 */
@Composable
fun DesignPage(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    headerTrailing: @Composable RowScope.() -> Unit = {},
    band: (@Composable () -> Unit)? = null,
    sideWidth: Dp = 388.dp,
    side: (@Composable ColumnScope.() -> Unit)? = null,
    sideScrolls: Boolean = true,
    /** Where the side column starts under the header (the main column's first section has its own margin). */
    sideTop: Dp = 0.dp,
    main: LazyListScope.() -> Unit,
) {
    val shell = currentShell()
    Column(modifier.fillMaxSize().padding(start = 18.dp, top = 40.dp, end = 12.dp, bottom = 24.dp)) {
        ScreenHeader(title, subtitle, shell.navIcon, shell.navLabel, shell.toggleSidebar, trailing = headerTrailing)
        band?.invoke()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val list = rememberLazyListState()
            if (side == null || maxWidth >= 760.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = list, contentPadding = PaddingValues(bottom = 8.dp)) { main() }
                if (side != null) Column(
                    Modifier.width(sideWidth).fillMaxHeight().padding(top = sideTop).let { if (sideScrolls) it.verticalScroll(rememberScrollState()) else it },
                    verticalArrangement = Arrangement.spacedBy(16.dp), content = side,
                )
            } else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
                main()
                item("side") { Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = side) }
            }
        }
    }
}
