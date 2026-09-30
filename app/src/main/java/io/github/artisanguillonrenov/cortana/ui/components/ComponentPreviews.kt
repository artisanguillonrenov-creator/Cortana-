package io.github.artisanguillonrenov.cortana.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

/**
 * One preview per state of every component of the design (PROMPT step 2). They are also rendered to
 * images by `DesignGalleryTest`, so each one must stay self-contained (demo data only).
 */
@Composable
internal fun PreviewSurface(width: Int = 420, content: @Composable () -> Unit) {
    CortanaTheme(reduceMotion = true) {
        Box(Modifier.width(width.dp).background(Cortana.colors.background).padding(16.dp)) { content() }
    }
}

// NavItem
@Preview @Composable internal fun NavItemInactive() = PreviewSurface(288) { NavItem(Symbols.History, "Historique", false, {}) }
@Preview @Composable internal fun NavItemActive() = PreviewSurface(288) { NavItem(Symbols.Forum, "Discussion", true, {}) }
@Preview @Composable internal fun NavItemBadge() = PreviewSurface(288) { NavItem(Symbols.EventAvailable, "Tâches", false, {}, badge = "3") }
@Preview @Composable internal fun NavItemDot() = PreviewSurface(288) { NavItem(Symbols.Memory, "Worker", false, {}, dot = true) }

// Toggle
@Preview @Composable internal fun ToggleSizes() = PreviewSurface(260) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleSize.entries.forEach { s -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CortanaToggle(true, {}, s); CortanaToggle(false, {}, s) } }
    }
}

// HeaderPill
@Preview @Composable internal fun HeaderPills() = PreviewSurface(620) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelPill("O", Cortana.colors.providerOpenAi, "GPT-4.1", "OpenAI", {})
        SearchPill(true, {})
        VoicePill(false, {})
        TaskPill("3/6", {})
    }
}
@Preview @Composable internal fun HeaderPillsActive() = PreviewSurface(420) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SearchPill(false, {}); VoicePill(true, {}) }
}

// ToolChip
@Preview @Composable internal fun ToolChips() = PreviewSurface(700) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolChip(Symbols.Handyman, Symbols.Handyman, "Outils", false, {})
        ToolChip(Symbols.Hexagon, Symbols.HexagonFill, "Système", true, {})
        ToolChip(Symbols.Folder, Symbols.FolderFill, "Fichiers", false, {})
        ToolChip(Symbols.Terminal, Symbols.Terminal, "Terminal", false, {})
    }
}

// FilterChip
@Preview @Composable internal fun FilterChips() = PreviewSurface(720) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip("Tout", true, {}, icon = Symbols.Apps, count = "8")
        FilterChip("Épinglées", false, {}, icon = Symbols.PushPin, count = "2")
        FilterChip("Profil", false, {}, count = "3")
        DropChip("Projet : tous", {})
    }
}

// SegmentedControl
@Preview @Composable internal fun SegmentedHeader() = PreviewSurface(480) { SegmentedControl(listOf("Toutes", "En cours", "Planifiées", "Terminées"), 0, {}) }
@Preview @Composable internal fun SegmentedSettings() = PreviewSurface(360) { SegmentedControl(listOf("Compact", "Confort", "Large"), 1, {}, style = SegmentedStyle.Settings) }

// StopButton
@Preview @Composable internal fun StopLargeRunning() = PreviewSurface(288) { StopButton(RunStatus.Running, {}, {}, StopSize.Large) }
@Preview @Composable internal fun StopLargePaused() = PreviewSurface(288) { StopButton(RunStatus.Paused, {}, {}, StopSize.Large) }
@Preview @Composable internal fun StopLargeDone() = PreviewSurface(288) { StopButton(RunStatus.Done, {}, {}, StopSize.Large) }
@Preview @Composable internal fun StopPanelStates() = PreviewSurface(400) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StopButton(RunStatus.Running, {}, {}, StopSize.Panel)
        StopButton(RunStatus.Paused, {}, {}, StopSize.Panel)
        StopButton(RunStatus.Done, {}, {}, StopSize.Panel, doneLabel = "Rejouer la démo")
    }
}
@Preview @Composable internal fun StopRailStates() = PreviewSurface(300) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StopButton(RunStatus.Running, {}, {}, StopSize.Rail); StopButton(RunStatus.Paused, {}, {}, StopSize.Rail) }
}

// StatusChip
@Preview @Composable internal fun StatusChips() = PreviewSurface(560) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { RunStatus.entries.forEach { StatusChip(it) } }
}

// ProgressBar
@Preview @Composable internal fun ProgressBars() = PreviewSurface(360) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { ProgressBar(0.54f, 6.dp); ProgressBar(0.54f, 4.dp, glow = false); ProgressBar(1f, 8.dp) }
}

// PlanCard + StepRow
@Preview @Composable internal fun PlanCardRunning() = PreviewSurface(680) { PlanCard(DemoData.planSteps(DemoData.initialRun), "En cours…", "~ 2-3 min", onMenu = {}) }
@Preview @Composable internal fun PlanCardWaiting() = PreviewSurface(680) {
    PlanCard(DemoData.planSteps(DemoData.initialRun.copy(status = RunStatus.AwaitingApproval, step = 5)), "En attente", "< 1 min", onMenu = {})
}
@Preview @Composable internal fun StepRows() = PreviewSurface(420) {
    Column { StepState.entries.forEachIndexed { i, s -> StepRow(i + 1, PlanStep("Étape ${s.name}", s)) } }
}

// CodeBlock
@Preview @Composable internal fun CodeBlockCollapsed() = PreviewSurface(680) {
    CodeBlock(DemoData.code, fileName = "NoteDatabase.kt", path = "src/main/…/data/", language = "Kotlin", onCopy = {}, onToggleExpand = {})
}
@Preview @Composable internal fun CodeBlockExpandedCopied() = PreviewSurface(680) {
    CodeBlock(DemoData.code, fileName = "NoteDatabase.kt", path = "src/main/…/data/", language = "Kotlin", expanded = true, copied = true, onCopy = {}, onToggleExpand = {})
}

// ApprovalCard
@Preview @Composable internal fun ApprovalPending() = PreviewSurface(680) {
    ApprovalCard(Approval.Pending, "Installer « Notes » sur Pixel 8 (Android 14)", "adb install", "com.cortana.notes", "Modéré", {}, {})
}
@Preview @Composable internal fun ApprovalGranted() = PreviewSurface(680) {
    ApprovalCard(Approval.Granted, "", "adb install", "", "", {}, {}, grantedLabel = "Installation autorisée une fois")
}
@Preview @Composable internal fun ApprovalRefused() = PreviewSurface(680) {
    ApprovalCard(Approval.Refused, "", "adb install", "", "", {}, {}, refusedLabel = "Installation refusée")
}

// StreamingDots
@Preview @Composable internal fun LiveLine() = PreviewSurface(680) { LiveStatusLine(DemoData.liveMessage, running = true) }

// LogConsole
@Preview @Composable internal fun LogConsoleRunning() = PreviewSurface(400) {
    Box(Modifier.height(230.dp)) { LogConsole(DemoData.initialLogs + LogLine("14:25:26", LogKind.Wait, "Approbation requise · adb install") + LogLine("14:25:27", LogKind.Stop, "Arrêt demandé · tâche suspendue") + LogLine("14:25:30", LogKind.Play, "Reprise de la tâche"), running = true) }
}

// ContextTabs
@Preview @Composable internal fun ContextTabsPreview() = PreviewSurface(400) {
    ContextTabs(listOf(TabSpec(Symbols.Article, "Logs en direct"), TabSpec(Symbols.FileCopy, "Fichiers"), TabSpec(Symbols.Preview, "Aperçu")), 0, {})
}

// InfoCard
@Preview @Composable internal fun InfoCards() = PreviewSurface(400) {
    val c = Cortana.colors
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        InfoCard(Symbols.SmartToy, c.workerIcon, c.tile, c.tileBorderAlt, "Worker appairé", InfoStatus("ACTIF", c.successText),
            listOf("Pixel 8 (Android 14)" to c.infoSubtitle, "ADB connecté • 1 app en cours" to c.textMuted), tileSize = 46.dp, iconSize = 26.dp, alignTop = true,
            padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, top = 14.dp, end = 12.dp, bottom = 14.dp), onMenu = {})
        InfoCard(Symbols.Hub, c.successText, c.success.copy(alpha = 0.1f), c.success.copy(alpha = 0.25f), "MCP connectés", InfoStatus("4/4", c.successText),
            listOf("Filesystem • GitHub • Play Store • Web" to c.textTertiary))
        InfoCard(Symbols.Notes, c.purpleText, c.purpleTint, c.purple.copy(alpha = 0.35f), "Mémoire active", InfoStatus("12 items", c.successText),
            listOf("Préférences, contexte projet, style, appareils…" to c.textTertiary), onClick = {})
        InfoCard(Symbols.VerifiedUser, c.successText, c.success.copy(alpha = 0.1f), c.success.copy(alpha = 0.3f), "Politique sécurité", InfoStatus("1 demande", c.warningText, Symbols.FrontHand),
            listOf("Approbation requise : installation, accès système" to c.textTertiary), highlight = true,
            padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, top = 15.dp, end = 16.dp, bottom = 15.dp))
    }
}

// Composer
@Preview @Composable internal fun ComposerNormal() = PreviewSurface(760) { CortanaComposer("", {}, {}, {}, {}, listening = false) }
@Preview @Composable internal fun ComposerVoice() = PreviewSurface(760) { CortanaComposer("", {}, {}, {}, {}, listening = true) }

// UserBubble, QueuedBubble
@Preview @Composable internal fun UserBubblePreview() = PreviewSurface(760) { UserBubble("14:22") { BubbleText(DemoData.userMessage) } }
@Preview @Composable internal fun QueuedBubbles() = PreviewSurface(760) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        QueuedBubble("Ajoute aussi un mode sombre.", delivered = false, onRemove = {})
        QueuedBubble("Ajoute aussi un mode sombre.", delivered = true, onRemove = null)
    }
}

// ModelMenu
@Preview @Composable internal fun ModelMenuPreview() = PreviewSurface(360) {
    val c = Cortana.colors
    ModelMenu(listOf(
        ModelOption("0", "O", c.providerOpenAi, "GPT-4.1", "OpenAI", "Polyvalent, rapide"),
        ModelOption("1", "A", c.providerAnthropic, "Claude Sonnet 4.5", "Anthropic", "Code et raisonnement"),
        ModelOption("2", "G", c.providerGoogle, "Gemini 2.5 Pro", "Google", "Très long contexte"),
        ModelOption("3", "L", c.providerLocal, "Qwen 2.5 Coder", "Local · Worker", "Hors ligne, privé"),
    ), "0", {}, {})
}

// ActivityRail
@Preview @Composable internal fun ActivityRailRunning() = PreviewSurface(1054) {
    ActivityRail(RunStatus.Running, "Étape 3/6 · Implémenter le stockage local (Room)", 0.54f, "~ 2-3 min restantes", {}, {}, {})
}
@Preview @Composable internal fun ActivityRailPaused() = PreviewSurface(1054) {
    ActivityRail(RunStatus.Paused, "Étape 3/6 · Implémenter le stockage local (Room)", 0.54f, "En pause · ~ 2-3 min restantes", {}, {}, {})
}

// ConversationRow
@Preview @Composable internal fun ConversationRows() = PreviewSurface(700) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConversationRow("Application Android - Notes", "Je continue avec le DAO et le repository…", "14:22", ConversationKind.Dev, "Agent", true, {}, pinned = true, liveLabel = "En cours", branches = 3)
        ConversationRow("Comparatif forfaits mobiles", "D’après 6 sources, deux offres sortent du lot pour ton usage.", "09:41", ConversationKind.Search, "Recherche", false, {})
        ConversationRow("Nom pour l’association du quartier", "Le conseil recommande « Les Jardins Partagés ».", "Lun.", ConversationKind.Council, "Conseil", false, {}, branches = 2)
    }
}

// MemoryCard
@Preview @Composable internal fun MemoryCardFree() = PreviewSurface(420) {
    MemoryCard("Tu développes principalement des applications Android.", MemoryCategory.Profile, true, "Déduit de 9 conversations", Symbols.AutoAwesome, true, false, {}, {}, {})
}
@Preview @Composable internal fun MemoryCardLocked() = PreviewSurface(420) {
    MemoryCard("Toujours demander avant d’installer une application.", MemoryCategory.Preferences, true, "Politique sécurité", Symbols.VerifiedUser, true, true, {}, {}, {})
}
@Preview @Composable internal fun MemoryCardInactive() = PreviewSurface(420) {
    MemoryCard("GPT-4.1 de préférence pour le code.", MemoryCategory.Preferences, false, "Déduit de 6 conversations", Symbols.AutoAwesome, false, false, {}, {}, {})
}

// SettingsRow
@Preview @Composable internal fun SettingsRows() = PreviewSurface(760) {
    CortanaCard(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        SettingsToggleRow("Envoyer avec Entrée", true, {}, description = "Maj + Entrée pour aller à la ligne.", first = true)
        SettingsSegmentRow("Densité", listOf("Compact", "Confort", "Large"), 1, {})
        SettingsValueRow("Taille du texte", "100 %", {})
    }
}

// Sidebar pieces
@Preview @Composable internal fun SidebarPieces() = PreviewSurface(288) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { CortanaBrand(); DevModeCard(true, {}); DevModeCard(false, {}) }
}
