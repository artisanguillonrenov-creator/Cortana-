package io.github.artisanguillonrenov.cortana.ui.cortana

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.components.AssistantMessage
import io.github.artisanguillonrenov.cortana.ui.components.BodyText
import io.github.artisanguillonrenov.cortana.ui.components.BubbleText
import io.github.artisanguillonrenov.cortana.ui.components.CodeBlock
import io.github.artisanguillonrenov.cortana.ui.components.ConversationKind
import io.github.artisanguillonrenov.cortana.ui.components.CortanaComposer
import io.github.artisanguillonrenov.cortana.ui.components.DemoData
import io.github.artisanguillonrenov.cortana.ui.components.FileChange
import io.github.artisanguillonrenov.cortana.ui.components.LiveStatusLine
import io.github.artisanguillonrenov.cortana.ui.components.ModelOption
import io.github.artisanguillonrenov.cortana.ui.components.PlanCard
import io.github.artisanguillonrenov.cortana.ui.components.RunStatus
import io.github.artisanguillonrenov.cortana.ui.components.UserBubble
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaDark
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

/**
 * The prototype's initial state (step 3/6, logs 14:25:12 → 14:25:24) with its demonstration data:
 * the reference for the side-by-side check (PROMPT step 3 and 7). Previews only.
 */
internal object DesignDemo {
    fun navEntries(tasksBadge: String = "3") = listOf(
        NavEntry("chat", Symbols.Forum, "Discussion"), NavEntry("history", Symbols.History, "Historique"),
        NavEntry("tasks", Symbols.EventAvailable, "Tâches", badge = tasksBadge), NavEntry("memory", Symbols.Layers, "Mémoire"),
        NavEntry("schedules", Symbols.Notifications, "Rappels"),
        NavEntry("providers", Symbols.DeployedCode, "Fournisseurs"), NavEntry("dev", Symbols.Code, "Développement", devOnly = true),
        NavEntry("devices", Symbols.Memory, "Worker", devOnly = true, dot = true), NavEntry("mcp", Symbols.Cable, "MCP", devOnly = true, badge = "4"),
        NavEntry("health", Symbols.MonitorHeart, "Santé"), NavEntry("settings", Symbols.Settings, "Réglages"),
    )

    val models = listOf(
        ModelOption("0", "O", CortanaDark.providerOpenAi, "GPT-4.1", "OpenAI", "Polyvalent, rapide"),
        ModelOption("1", "A", CortanaDark.providerAnthropic, "Claude Sonnet 4.5", "Anthropic", "Code et raisonnement"),
        ModelOption("2", "G", CortanaDark.providerGoogle, "Gemini 2.5 Pro", "Google", "Très long contexte"),
        ModelOption("3", "L", CortanaDark.providerLocal, "Qwen 2.5 Coder", "Local · Worker", "Hors ligne, privé"),
    )

    val panel = TaskPanelUi(
        task = TaskRunProjection.Panel(
            run = DemoData.initialRun, steps = DemoData.planSteps(DemoData.initialRun), progressLabel = "3/6", remaining = "~ 2-3 min restantes",
            planLabel = "En cours…", stepLabel = "Étape 3/6 · Implémenter le stockage local (Room)",
            files = listOf(FileChange("data/Note.kt", 18, 0), FileChange("data/NoteDao.kt", 31, 0), FileChange("data/NoteDatabase.kt", 28, 0, open = true)),
            startedAt = 0,
        ),
        title = "Application Android - Notes", subtitle = "Créer, builder, tester et installer",
        tags = listOf(TagTone.Purple to "Développement", TagTone.Green to "Android", TagTone.Blue to "Local"),
        folder = "app/src/main/…/notes",
        cards = InfoCardsUi("Pixel 8 (Android 14)", "ADB connecté • 1 app en cours", true, 4, 4, "Filesystem • GitHub • Play Store • Web", 12,
            "Préférences, contexte projet, style, appareils…", 0, "Approbation requise : installation, accès système"),
    )

    val tools = listOf(
        ToolChipUi("tools", Symbols.Handyman, Symbols.Handyman, "Outils", false), ToolChipUi("system", Symbols.Hexagon, Symbols.HexagonFill, "Système", true),
        ToolChipUi("files", Symbols.Folder, Symbols.FolderFill, "Fichiers", false), ToolChipUi("terminal", Symbols.Terminal, Symbols.Terminal, "Terminal", false),
        ToolChipUi("git", Symbols.Commit, Symbols.Commit, "Git", false), ToolChipUi("web", Symbols.Language, Symbols.Language, "Navigateur", false),
    )
}

/** The whole "Discussion" screen of the prototype, landscape or portrait. */
@Composable
internal fun DesignDiscussionDemo(listAtBottom: Boolean = true) {
    CortanaTheme(reduceMotion = true, devMode = true) {
        CortanaShell(DesignDemo.navEntries(), "chat", {}, devMode = true, onDevMode = {}, stop = StopUi(RunStatus.Running, {}, {}, {})) { shell ->
            val list = rememberLazyListState()
            DiscussionScreen(
                shell, DiscussionHeaderUi(DesignDemo.models[0], DesignDemo.models, searchOn = true, voiceOn = false), DesignDemo.panel, DesignDemo.tools,
                DiscussionActions(), list,
                composer = { CortanaComposer("", {}, {}, {}, {}, listening = false) },
            ) {
                item { UserBubble("14:22") { BubbleText(DemoData.userMessage) } }
                item {
                    AssistantMessage("14:22", running = true, modifier = Modifier.padding(top = 22.dp)) {
                        BodyText(DemoData.intro, Modifier.padding(top = 6.dp))
                        PlanCard(DemoData.planSteps(DemoData.initialRun), "En cours…", "~ 2-3 min", onMenu = {})
                        BodyText(DemoData.progressText, Modifier.padding(top = 12.dp))
                        CodeBlock(DemoData.code, fileName = "NoteDatabase.kt", path = "src/main/…/data/", language = "Kotlin", onCopy = {}, onToggleExpand = {})
                        LiveStatusLine(DemoData.liveMessage, running = true)
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 1448, heightDp = 1086) @Composable internal fun DiscussionLandscape() = Box(Modifier.fillMaxSize()) { DesignDiscussionDemo() }
@Preview(widthDp = 1086, heightDp = 1448) @Composable internal fun DiscussionPortrait() = Box(Modifier.fillMaxSize()) { DesignDiscussionDemo() }
@Preview(widthDp = 1280, heightDp = 800) @Composable internal fun DiscussionTablet1280() = Box(Modifier.fillMaxSize()) { DesignDiscussionDemo() }

// ------------------------------------------------------------------ screens 3 to 6 (prototype data, previews only)

internal object DesignScreensDemo {
    val history = HistoryUi(
        "",
        listOf(
            HistoryFilterUi("all", "Tout", Symbols.Apps, 8), HistoryFilterUi("pinned", "Épinglées", Symbols.PushPin, 2),
            HistoryFilterUi("files", "Avec fichiers", Symbols.AttachFile, 3), HistoryFilterUi("artifacts", "Avec artefacts", Symbols.Inventory2, 6),
            HistoryFilterUi("agent", "Tâches agent", Symbols.SmartToy, 2),
        ),
        "all", "Projet : tous", "Modèle : tous", "30 derniers jours",
        listOf(
            "Aujourd'hui" to listOf(
                HistoryRowUi("1", "Application Android - Notes", "Je continue avec le DAO et le repository…", "14:22", "", ConversationKind.Dev, "Agent", true, true, 3),
                HistoryRowUi("2", "Résumé réunion équipe produit", "Voici les 5 décisions clés et les actions assignées à chacun.", "11:08", "", ConversationKind.Chat, "Discussion", false, false, 1),
                HistoryRowUi("3", "Comparatif forfaits mobiles", "D’après 6 sources, deux offres sortent du lot pour ton usage.", "09:41", "", ConversationKind.Search, "Recherche", false, false, 1),
            ),
            "Hier" to listOf(
                HistoryRowUi("4", "Plan d’entraînement semi-marathon", "Semaine 3 : on passe la sortie longue à 14 km.", "Hier", "", ConversationKind.Chat, "Discussion", true, false, 2),
                HistoryRowUi("5", "Script de sauvegarde NAS", "Le script rsync est prêt et planifié chaque nuit à 2 h.", "Hier", "", ConversationKind.Dev, "Développement", false, false, 1),
            ),
            "Cette semaine" to listOf(
                HistoryRowUi("6", "Nom pour l’association du quartier", "Le conseil recommande « Les Jardins Partagés ».", "Lun.", "", ConversationKind.Council, "Conseil", false, false, 2),
                HistoryRowUi("7", "Traduction contrat de location", "Traduction terminée : 4 clauses à vérifier ensemble.", "Dim.", "", ConversationKind.Chat, "Discussion", false, false, 1),
                HistoryRowUi("8", "Dîner sans gluten pour 8", "Menu validé : velouté, poulet rôti, fondant au chocolat.", "Sam.", "", ConversationKind.Voice, "Voix", false, false, 1),
            ),
        ),
        "1",
        HistoryPreviewUi("1", "Application Android - Notes", ConversationKind.Dev, "Agent · GPT-4.1 · Cortana Labs", 38,
            listOf(HistoryBranchUi("a", "Principale", "actuelle", true), HistoryBranchUi("b", "Variante Material 3", "13:58", false), HistoryBranchUi("c", "Essai sans Room", "14:05", false)),
            listOf(HistoryFileUi("f1", Symbols.DataObject, "NoteDatabase.kt", "Kotlin · 17 lignes"), HistoryFileUi("f2", Symbols.ListAlt, "Plan d’exécution", "6 étapes"),
                HistoryFileUi("f3", Symbols.Android, "app-debug.apk", "Build debug #7")), true),
    )

    val tasks = TasksUi(
        0,
        TaskLiveUi("t", "s", "Application Android - Notes", "Démarrée à 14:22 · Worker Pixel 8 · GPT-4.1", RunStatus.Running, 0.5f, "3/6",
            io.github.artisanguillonrenov.cortana.ui.components.StepState.Running, "Étape 3/6 · Implémenter le stockage local (Room)", "~ 2-3 min restantes"),
        listOf(QueuedTaskUi("q", "s", "Générer les icônes de l’application", "Démarre après la tâche en cours · Application Android - Notes", false)),
        listOf(
            ScheduleUi("1", Symbols.Backup, "Sauvegarde du NAS", "Script backup-nas.sh · Worker PC", Symbols.Repeat, "Chaque nuit · 02:00", true),
            ScheduleUi("2", Symbols.Mail, "Résumé de mes e-mails", "Boîte principale · messages non lus", Symbols.Event, "Demain · 08:00", true),
            ScheduleUi("3", Symbols.SystemUpdate, "Vérifier les mises à jour Play Store", "Pixel 8 · applications suivies", Symbols.Event, "Vendredi · 10:00", false),
        ),
        listOf(
            DoneTaskUi("d1", "s", Symbols.Translate, "Traduction contrat de location", "Dimanche · 12 min · 2 artefacts", true, "Réussie"),
            DoneTaskUi("d2", "s", Symbols.TravelExplore, "Comparatif forfaits mobiles", "Aujourd'hui 09:41 · 4 min · 6 sources", true, "Réussie"),
            DoneTaskUi("d3", "s", Symbols.PhotoLibrary, "Nettoyage des photos en double", "Samedi · 3 min · 2 fichiers ignorés", false, "Partielle"),
        ),
        "t", "Application Android - Notes",
        listOf(
            JournalUi(io.github.artisanguillonrenov.cortana.ui.components.AuditKind.Ok, "Projet Android créé", "Gradle · Kotlin DSL", "14:22:10", "18 s"),
            JournalUi(io.github.artisanguillonrenov.cortana.ui.components.AuditKind.Ok, "Interface Compose générée", "6 fichiers", "14:23:02", "51 s"),
            JournalUi(io.github.artisanguillonrenov.cortana.ui.components.AuditKind.Ok, "Build et installation v0", "app-debug.apk", "14:24:40", "42 s"),
            JournalUi(io.github.artisanguillonrenov.cortana.ui.components.AuditKind.Ok, "Tests d’interface", "8/8 réussis", "14:25:24", "9 s"),
            JournalUi(io.github.artisanguillonrenov.cortana.ui.components.AuditKind.Running, "Stockage local (Room)", "En cours…", "en cours", ""),
        ),
        listOf("Modèle" to "GPT-4.1 · OpenAI", "Appareil" to "Pixel 8 (Android 14) · ADB", "Outils" to "Gradle, ADB, Filesystem, GitHub", "Politique" to "Approbation pour l’installation"),
        listOf(HistoryFileUi("f1", Symbols.DataObject, "NoteDatabase.kt", "Kotlin · 17 lignes")), true,
    )

    val memory = MemoryUi(
        "", false, MemorySuggestionUi("s", "Kotlin + Jetpack Compose pour tes applications Android.", "Application Android - Notes · aujourd'hui"),
        listOf(null to 12, io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Profile to 3, io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Preferences to 4,
            io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Projects to 3, io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Devices to 2),
        null,
        listOf(
            MemoryItemUi("1", "Tu développes principalement des applications Android.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Profile, true, "Déduit de 9 conversations", Symbols.AutoAwesome, true, false),
            MemoryItemUi("2", "Ta langue principale est le français.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Profile, true, "Réglages · 2 avr.", Symbols.Settings, true, false),
            MemoryItemUi("3", "Fuseau horaire : Europe/Paris.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Profile, true, "Appareil · automatique", Symbols.Smartphone, true, false),
            MemoryItemUi("4", "Réponses concises, sans détours.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Preferences, true, "Discussion · 18 avr.", Symbols.Forum, true, false),
            MemoryItemUi("5", "Design sombre et moderne, accent bleu.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Preferences, true, "Discussion · 20 avr.", Symbols.Forum, true, false),
            MemoryItemUi("6", "Toujours demander avant d’installer une application.", io.github.artisanguillonrenov.cortana.ui.components.MemoryCategory.Preferences, true, "Politique sécurité", Symbols.VerifiedUser, true, true),
        ),
        "Application Android - Notes", 1, "Compactage non nécessaire",
        listOf(Triple("Historique", CortanaDark.accentBar, 18_200), Triple("Fichiers épinglés", CortanaDark.success, 6_400), Triple("Schémas d’outils", CortanaDark.amber, 3_800),
            Triple("Mémoire", CortanaDark.purple, 1_100), Triple("Réserve de sortie", CortanaDark.textMuted, 8_000)),
        listOf(MemoryPinUi("p1", Symbols.DataObject, "NoteDatabase.kt", "Fichier · épinglé à 14:24"), MemoryPinUi("p2", Symbols.ChatBubble, "« Utilise un design moderne… »", "Message · 14:22"),
            MemoryPinUi("p3", Symbols.ListAlt, "Plan d’exécution", "Artefact · 6 étapes")),
        "14:02", "42 anciens messages résumés par GPT-4.1. Décisions et faits ouverts conservés.",
        "Cortana Labs", listOf(ProjectRowUi("Instructions du projet", "Hérité", false), ProjectRowUi("2 fichiers : README, charte graphique", "Hérité", false),
            ProjectRowUi("Modèle préféré : GPT-4.1", "Cette conversation", true)),
    )

    fun settings(): List<SettingsSection> = listOf(
        SettingsSection("general", Symbols.Tune, "Général", "Affichage et comportements de base.", listOf(
            SettingRow.Segment("Densité", listOf("Compact", "Confort", "Large"), 1) {}, SettingRow.Segment("Thème", listOf("Sombre", "Contraste élevé"), 0) {},
            SettingRow.Value("Taille du texte", "100 %") {}, SettingRow.Toggle("Envoyer avec Entrée", true, "Maj + Entrée pour aller à la ligne.") {},
            SettingRow.Toggle("Confirmer les suppressions", true) {}, SettingRow.Toggle("Titre automatique des conversations", true) {}, SettingRow.Toggle("Défilement automatique", true) {},
        )),
    ) + listOf(
        "messages" to (Symbols.Chat to "Messages"), "streaming" to (Symbols.Stream to "Streaming"), "context" to (Symbols.DataUsage to "Contexte"),
        "models" to (Symbols.Psychology to "Modèles"), "tools" to (Symbols.Handyman to "Outils"), "voice" to (Symbols.GraphicEq to "Voix"), "files" to (Symbols.Folder to "Fichiers"),
        "memory" to (Symbols.Layers to "Mémoire"), "developer" to (Symbols.Code to "Développeur"), "privacy" to (Symbols.Lock to "Confidentialité"),
    ).map { (k, v) -> SettingsSection(k, v.first, v.second, "", emptyList()) }
}

/** A screen of the design inside the shell, with [route] selected in the sidebar. */
@Composable
internal fun DesignScreenDemo(route: String, content: @Composable () -> Unit) {
    CortanaTheme(reduceMotion = true, devMode = true) {
        CortanaShell(DesignDemo.navEntries(), route, {}, devMode = true, onDevMode = {}, stop = StopUi(RunStatus.Running, {}, {}, {})) { content() }
    }
}

@Preview(widthDp = 1448, heightDp = 1086) @Composable internal fun HistoryLandscape() = DesignScreenDemo("history") { HistoryScreen(DesignScreensDemo.history, HistoryActions()) }
@Preview(widthDp = 1448, heightDp = 1086) @Composable internal fun TasksLandscape() = DesignScreenDemo("tasks") { TasksScreen(DesignScreensDemo.tasks, TasksActions()) }
@Preview(widthDp = 1448, heightDp = 1086) @Composable internal fun MemoryLandscape() = DesignScreenDemo("memory") { MemoryScreen(DesignScreensDemo.memory, MemoryActions()) }
@Preview(widthDp = 1448, heightDp = 1086) @Composable internal fun SettingsLandscape() = DesignScreenDemo("settings") { SettingsDesignScreen(DesignScreensDemo.settings(), "general", {}) }
@Preview(widthDp = 1086, heightDp = 1448) @Composable internal fun HistoryPortrait() = DesignScreenDemo("history") { HistoryScreen(DesignScreensDemo.history, HistoryActions()) }
@Preview(widthDp = 1086, heightDp = 1448) @Composable internal fun TasksPortrait() = DesignScreenDemo("tasks") { TasksScreen(DesignScreensDemo.tasks, TasksActions()) }
