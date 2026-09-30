package io.github.artisanguillonrenov.cortana.ui

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.remember
import io.github.artisanguillonrenov.cortana.core.chat.effectiveUi
import io.github.artisanguillonrenov.cortana.ui.audit.AuditScreen
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.cortana.CortanaShell
import io.github.artisanguillonrenov.cortana.ui.cortana.DiscussionRoute
import io.github.artisanguillonrenov.cortana.ui.cortana.NavEntry
import io.github.artisanguillonrenov.cortana.ui.cortana.StopUi
import io.github.artisanguillonrenov.cortana.ui.settings.McpScreen
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import io.github.artisanguillonrenov.cortana.ui.chat.ChatScreen
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalOpenDrawer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.health.HealthScreen
import io.github.artisanguillonrenov.cortana.ui.history.HistoryScreen
import io.github.artisanguillonrenov.cortana.ui.memory.MemoryScreen
import io.github.artisanguillonrenov.cortana.ui.skills.SkillsScreen
import io.github.artisanguillonrenov.cortana.ui.dev.DevScreen
import io.github.artisanguillonrenov.cortana.ui.devices.DevicesScreen
import io.github.artisanguillonrenov.cortana.ui.onboarding.OnboardingScreen
import io.github.artisanguillonrenov.cortana.ui.providers.ProviderEditScreen
import io.github.artisanguillonrenov.cortana.ui.providers.ProvidersScreen
import io.github.artisanguillonrenov.cortana.ui.schedules.SchedulesScreen
import io.github.artisanguillonrenov.cortana.ui.settings.SettingsScreen
import io.github.artisanguillonrenov.cortana.ui.tasks.TasksScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val CHAT = "chat?session={session}&message={message}"

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    Dest("chat", "Discussion", Icons.Default.Face),
    Dest("history", "Historique", Icons.Default.Search),
    Dest("tasks", "Tâches", Icons.AutoMirrored.Filled.List),
    Dest("memory", "Mémoire", Icons.Default.Star),
    Dest("skills", "Procédures", Icons.Default.PlayArrow),
    Dest("dev", "Développement", Icons.Default.Edit),
    Dest("devices", "Appareils", Icons.Default.Share),
    Dest("schedules", "Rappels", Icons.Default.DateRange),
    Dest("providers", "Fournisseurs", Icons.Default.Build),
    Dest("health", "Santé", Icons.Default.Favorite),
    Dest("settings", "Réglages", Icons.Default.Settings),
)

@Composable
fun AppNav(startOnboarding: Boolean, openSession: String?, onSessionConsumed: () -> Unit) {
    val nav = rememberNavController()
    val c = LocalContainer.current
    val pending by c.memory.observePendingCount().collectAsState(initial = 0)
    val halted by c.killSwitch.halted.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')?.substringBefore('/')
    val resume = LocalResumeAutonomy.current

    // A share from another app opens the conversation screen, where it becomes a draft.
    val share by io.github.artisanguillonrenov.cortana.ui.workspace.ShareInbox.pending.collectAsState()
    // Both wait for the NavHost's graph: it is composed inside a layout pass, after these effects start.
    LaunchedEffect(share) {
        if (share == null) return@LaunchedEffect
        val at = nav.currentBackStackEntryFlow.first().destination.route?.substringBefore('?')
        if (at != "chat" && at != "onboarding") nav.navigate("chat") { popUpTo(CHAT) { inclusive = true }; launchSingleTop = true }
    }

    LaunchedEffect(openSession) {
        if (openSession != null) {
            nav.currentBackStackEntryFlow.first()
            nav.navigate("chat?session=$openSession") { popUpTo(CHAT) { inclusive = true }; launchSingleTop = true }
            onSessionConsumed()
        }
    }

    fun go(route: String) {
        nav.navigate(route) {
            popUpTo(CHAT) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val host: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            io.github.artisanguillonrenov.cortana.ui.voice.VoiceIndicator()
            Box(Modifier.weight(1f)) { CortanaNavHost(nav, if (startOnboarding) "onboarding" else CHAT, ::go) }
        }
    }
    val showNav = current != "onboarding"

    // The Cortana Workspace design (default): its shell replaces the rail and the drawer.
    val settings by c.settings.state.collectAsState()
    if (settings.chat.effectiveUi == "cortana") {
        if (showNav) DesignShell(current, ::go, host) else host()
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                if (showNav) {
                    NavigationRail(Modifier.fillMaxHeight().systemBarsPadding()) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            topLevel.forEach { d ->
                                NavigationRailItem(
                                    selected = current == d.route, onClick = { go(d.route) },
                                    icon = { NavIcon(d, pending) }, label = { Text(d.label) },
                                )
                            }
                            Spacer(Modifier.padding(8.dp))
                            StopButton(halted, onHalt = { c.killSwitch.halt("rail") }, onResume = resume)
                        }
                    }
                }
                Box(Modifier.weight(1f)) { host() }
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer, gesturesEnabled = showNav,
                drawerContent = {
                    ModalDrawerSheet {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("Cortana", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                            topLevel.forEach { d ->
                                NavigationDrawerItem(
                                    label = { Text(d.label) }, selected = current == d.route, icon = { NavIcon(d, pending) },
                                    onClick = { scope.launch { drawer.close() }; go(d.route) },
                                )
                            }
                            Spacer(Modifier.padding(8.dp))
                            StopButton(halted, onHalt = { c.killSwitch.halt("menu") }, onResume = resume)
                        }
                    }
                },
            ) {
                CompositionLocalProvider(LocalOpenDrawer provides (if (showNav) ({ scope.launch { drawer.open() } ; Unit }) else null)) { host() }
            }
        }
    }
}

/** The Cortana Workspace design is the interface in use. */
@Composable
private fun designUi(): Boolean {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    return settings.chat.effectiveUi == "cortana"
}

/** Routes of the design's sidebar (a route opened from elsewhere, such as "audit", is selected under its parent). */
private val topRoutes = setOf("chat", "history", "tasks", "memory", "schedules", "providers", "dev", "devices", "mcp", "health", "settings", "skills")

private fun sidebarRoute(route: String?): String = when (route) {
    null -> "chat"
    "provider" -> "providers"
    "capabilities" -> "health"
    "audit", "skills" -> "settings"
    else -> route
}

/**
 * The shell of the Cortana Workspace design around every screen: sidebar (drawer in portrait), developer
 * mode, and the big STOP bound to the one runtime: a tap pauses the running task, "Reprendre" continues
 * the paused one, a long press is the emergency stop of all autonomy (resumed with the owner's credential).
 */
@Composable
private fun DesignShell(current: String?, go: (String) -> Unit, host: @Composable () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.state.collectAsState()
    val active by c.orchestrator.active.collectAsState()
    val halted by c.killSwitch.halted.collectAsState()
    val tasks by c.tasksFlow.collectAsState(initial = emptyList())
    val workers by remember { c.workers.observe() }.collectAsState(initial = emptyList())
    val mcp by c.mcp.status.collectAsState()
    val pending by remember { c.memory.observePendingCount() }.collectAsState(initial = 0)
    val resume = LocalResumeAutonomy.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val open = tasks.count { t -> io.github.artisanguillonrenov.cortana.contracts.TaskState.fromWireOrNull(t.state)?.terminal == false }
    val online = workers.any { w -> !w.revoked && w.lastError == null && (w.lastSeenAt ?: 0) > now - 5 * 60_000 }
    val connected = settings.mcpServers.count { s -> s.enabled && mcp[s.id]?.state == "ok" }
    val entries = listOf(
        NavEntry("chat", Symbols.Forum, "Discussion"),
        NavEntry("history", Symbols.History, "Historique"),
        NavEntry("tasks", Symbols.EventAvailable, "Tâches", badge = open.takeIf { it > 0 }?.toString()),
        NavEntry("memory", Symbols.Layers, "Mémoire", badge = pending.takeIf { it > 0 }?.toString()),
        NavEntry("schedules", Symbols.Notifications, "Rappels"),
        NavEntry("providers", Symbols.DeployedCode, "Fournisseurs"),
        NavEntry("dev", Symbols.Code, "Développement", devOnly = true),
        NavEntry("devices", Symbols.Memory, "Worker", devOnly = true, dot = online),
        NavEntry("mcp", Symbols.Cable, "MCP", devOnly = true, badge = connected.takeIf { it > 0 }?.toString()),
        NavEntry("health", Symbols.MonitorHeart, "Santé"),
        NavEntry("settings", Symbols.Settings, "Réglages"),
    )
    val latest = tasks.maxByOrNull { it.createdAt }
    val paused = latest?.takeIf { it.state == io.github.artisanguillonrenov.cortana.contracts.TaskState.PAUSED.wire }
    val status = when {
        halted -> RunStatus.Paused
        active != null -> if (active?.waitingApproval == true) RunStatus.AwaitingApproval else RunStatus.Running
        paused != null -> RunStatus.Paused
        else -> null
    }
    val stop = StopUi(
        status,
        onStop = { io.github.artisanguillonrenov.cortana.ui.cortana.stopTask(c) },
        onResume = { if (halted) resume() else paused?.let { c.orchestrator.resumePaused(it.id) } },
        onEmergency = { c.killSwitch.halt("sidebar") },
    )
    CortanaShell(entries, sidebarRoute(current), go, devMode = settings.chat.developer,
        onDevMode = { on -> scope.launch { c.settings.update { it.copy(chat = it.chat.copy(developer = on)) } } }, stop = stop) { shell ->
        // Screens not redrawn by the design keep their top bar; its menu button opens the drawer or shows the sidebar.
        CompositionLocalProvider(LocalOpenDrawer provides (if (shell.layout.overlay || !shell.sidebarOpen) shell.toggleSidebar else null)) { host() }
    }
}

@Composable
private fun NavIcon(d: Dest, pending: Int) {
    if (d.route == "memory" && pending > 0) BadgedBox(badge = { Badge { Text("$pending") } }) { Icon(d.icon, d.label) }
    else Icon(d.icon, d.label)
}

@Composable
private fun StopButton(halted: Boolean, onHalt: () -> Unit, onResume: () -> Unit) {
    if (halted) Button(onClick = onResume, modifier = Modifier.padding(4.dp)) { Text("Reprendre") }
    else Button(onClick = onHalt, colors = ButtonDefaults.buttonColors(containerColor = Cortana.colors.danger, contentColor = Cortana.colors.onAccent), modifier = Modifier.padding(4.dp)) { Text("STOP") }
}

@Composable
private fun CortanaNavHost(nav: NavHostController, start: String, go: (String) -> Unit) {
    NavHost(nav, startDestination = start) {
        composable(CHAT, arguments = listOf(
            navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("message") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { e ->
            val c = LocalContainer.current
            val settings by c.settings.state.collectAsState()
            val scope = rememberCoroutineScope()
            // The Cortana Workspace design by default; the rc4 and rc3 screens stay available on the same data.
            when (settings.chat.effectiveUi) {
                "classic" -> ChatScreen(openSessionId = e.arguments?.getString("session"), onOpenProviders = { nav.navigate("providers") })
                "workspace" -> io.github.artisanguillonrenov.cortana.ui.workspace.WorkspaceScreen(
                    openSessionId = e.arguments?.getString("session"), openMessageId = e.arguments?.getString("message"),
                    onOpenProviders = { nav.navigate("providers") }, onOpenSettings = { nav.navigate("settings") },
                    onUseClassic = { scope.launch { c.settings.update { it.copy(chat = it.chat.copy(ui = "classic", uiChosen = true)) } } },
                )
                else -> DiscussionRoute(e.arguments?.getString("session"), e.arguments?.getString("message")) { route ->
                    if (route in topRoutes) go(route) else nav.navigate(route)
                }
            }
        }
        // The design redraws History, Tasks, Memory and Settings; the other interfaces keep their screens.
        val openSession: (String, String?) -> Unit = { id, message ->
            nav.navigate("chat?session=$id" + (message?.let { "&message=$it" } ?: "")) { popUpTo(CHAT) { inclusive = true } }
        }
        composable("history") {
            if (designUi()) io.github.artisanguillonrenov.cortana.ui.cortana.HistoryRoute(openSession)
            else HistoryScreen(onOpenSession = { id -> openSession(id, null) })
        }
        composable("tasks") {
            if (designUi()) io.github.artisanguillonrenov.cortana.ui.cortana.TasksRoute { id -> openSession(id, null) }
            else TasksScreen(onOpenSession = { id -> openSession(id, null) })
        }
        composable("memory") { if (designUi()) io.github.artisanguillonrenov.cortana.ui.cortana.MemoryRoute() else MemoryScreen() }
        composable("skills") { SkillsScreen() }
        composable("dev") { DevScreen() }
        composable("devices") { DevicesScreen() }
        composable("schedules") { SchedulesScreen() }
        composable("providers") { ProvidersScreen(onEdit = { nav.navigate("provider/$it") }, onAdd = { nav.navigate("provider/new/$it") }) }
        composable("provider/{id}") { e -> ProviderEditScreen(providerId = e.arguments?.getString("id"), presetId = null, onBack = { nav.popBackStack() }) }
        composable("provider/new/{preset}") { e -> ProviderEditScreen(providerId = null, presetId = e.arguments?.getString("preset"), onBack = { nav.popBackStack() }) }
        composable("health") { HealthScreen(onProviders = { nav.navigate("providers") }, onCapabilities = { nav.navigate("capabilities") }) }
        composable("capabilities") { io.github.artisanguillonrenov.cortana.ui.capabilities.CapabilitiesScreen() }
        composable("settings") {
            if (designUi()) io.github.artisanguillonrenov.cortana.ui.cortana.SettingsDesignRoute { route -> if (route in topRoutes) go(route) else nav.navigate(route) }
            else SettingsScreen(onProviders = { nav.navigate("providers") }, onOnboarding = { nav.navigate("onboarding") }, onAudit = { nav.navigate("audit") },
                onSkills = { nav.navigate("skills") }, onCapabilities = { nav.navigate("capabilities") })
        }
        composable("settings/advanced") {
            SettingsScreen(onProviders = { nav.navigate("providers") }, onOnboarding = { nav.navigate("onboarding") }, onAudit = { nav.navigate("audit") },
                onSkills = { nav.navigate("skills") }, onCapabilities = { nav.navigate("capabilities") })
        }
        composable("audit") { AuditScreen(onBack = { nav.popBackStack() }) }
        composable("mcp") { McpScreen() }
        composable("onboarding") {
            OnboardingScreen(
                onDone = { nav.navigate("chat") { popUpTo(nav.graph.id) { inclusive = true } } },
                onProviders = { nav.navigate("providers") },
            )
        }
    }
}
