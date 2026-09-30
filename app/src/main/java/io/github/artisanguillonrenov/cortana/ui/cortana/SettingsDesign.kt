package io.github.artisanguillonrenov.cortana.ui.cortana

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.effectiveUi
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.common.rememberFixHandler
import io.github.artisanguillonrenov.cortana.ui.components.ScreenHeader
import io.github.artisanguillonrenov.cortana.ui.components.SettingsNavItem
import io.github.artisanguillonrenov.cortana.ui.components.SettingsSegmentRow
import io.github.artisanguillonrenov.cortana.ui.components.SettingsToggleRow
import io.github.artisanguillonrenov.cortana.ui.components.SettingsValueRow
import io.github.artisanguillonrenov.cortana.ui.components.Symbol
import io.github.artisanguillonrenov.cortana.ui.health.Fix
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ------------------------------------------------------------------ state

/** One row of a settings section: an interrupteur, a segmented control or a value that opens something. */
sealed interface SettingRow {
    val label: String
    val description: String?

    class Toggle(override val label: String, val checked: Boolean, override val description: String? = null, val onChange: (Boolean) -> Unit) : SettingRow
    class Segment(override val label: String, val options: List<String>, val selected: Int, override val description: String? = null, val onSelect: (Int) -> Unit) : SettingRow
    class Value(override val label: String, val value: String, override val description: String? = null, val onClick: () -> Unit) : SettingRow
}

class SettingsSection(val key: String, @DrawableRes val icon: Int, val title: String, val description: String, val rows: List<SettingRow>)

// ------------------------------------------------------------------ screen

/** "Réglages" (README screen 6): the 256 dp sub-menu of eleven sections, and the section's rows (760 dp at most). */
@Composable
fun SettingsDesignScreen(sections: List<SettingsSection>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val shell = currentShell()
    val section = sections.firstOrNull { it.key == selected } ?: sections.first()
    Column(modifier.fillMaxSize().padding(start = 18.dp, top = 40.dp, end = 12.dp, bottom = 24.dp)) {
        ScreenHeader("Réglages", "Affichage, modèles, outils, voix et confidentialité.", shell.navIcon, shell.navLabel, shell.toggleSidebar)
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 18.dp)) {
            val menuWidth = if (maxWidth < 760.dp) 200.dp else 256.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.width(menuWidth).clip(CortanaShapes.Xl).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Xl)
                        .verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    sections.forEach { s -> SettingsNavItem(s.icon, s.title, s.key == section.key, { onSelect(s.key) }) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(end = 6.dp)) {
                    Column(Modifier.widthIn(max = 760.dp)) {
                        Row(Modifier.padding(horizontal = 2.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                Symbol(section.icon, c.accentIcon, 26.dp)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(section.title, style = CortanaType.PanelTitle.copy(fontSize = 21.sp), color = c.textStrong, modifier = Modifier.semantics { heading() })
                                Text(section.description, style = CortanaType.Secondary, color = c.textTertiary)
                            }
                        }
                        Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(CortanaShapes.Xl).background(c.surface).border(1.dp, c.surfaceBorder, CortanaShapes.Xl)) {
                            section.rows.forEachIndexed { i, r ->
                                when (r) {
                                    is SettingRow.Toggle -> SettingsToggleRow(r.label, r.checked, r.onChange, r.description, first = i == 0)
                                    is SettingRow.Segment -> SettingsSegmentRow(r.label, r.options, r.selected, r.onSelect, r.description, first = i == 0)
                                    is SettingRow.Value -> SettingsValueRow(r.label, r.value, r.onClick, r.description, first = i == 0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ route

/**
 * The design's settings on the real settings (chat preferences and app settings). What the design does not
 * show stays one tap away in "Tous les réglages avancés" (the complete previous screen).
 */
@Composable
fun SettingsDesignRoute(onNavigate: (String) -> Unit) {
    val c = LocalContainer.current
    val s by c.settings.state.collectAsState()
    val halted by c.killSwitch.halted.collectAsState()
    val providers by remember { c.providers.observe() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val resume = LocalResumeAutonomy.current
    val fix = rememberFixHandler(onProviders = { onNavigate("providers") })
    val fontScale = LocalDensity.current.fontScale
    var selected by rememberSaveable { mutableStateOf("general") }
    var number by remember { mutableStateOf<NumberEdit?>(null) }
    fun upd(t: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(t) } }
    fun chat(t: (ChatPrefs) -> ChatPrefs) = upd { it.copy(chat = t(it.chat)) }
    val p = s.chat
    val defaultProvider = providers.firstOrNull { it.id == s.defaultProviderId } ?: providers.firstOrNull { it.enabled && it.defaultModelId != null }

    val sections = listOf(
        SettingsSection("general", Symbols.Tune, "Général", "Affichage et comportements de base.", listOf(
            SettingRow.Segment("Interface", listOf("Cortana", "Workspace (rc4)", "Classique"), listOf("cortana", "workspace", "classic").indexOf(p.effectiveUi).coerceAtLeast(0),
                "Les trois écrans utilisent les mêmes conversations.") { i -> chat { it.copy(ui = listOf("cortana", "workspace", "classic")[i], uiChosen = true) } },
            SettingRow.Segment("Densité", listOf("Compact", "Confort", "Large"), listOf("compact", "comfort", "large").indexOf(p.density).coerceAtLeast(1)) { i ->
                chat { it.copy(density = listOf("compact", "comfort", "large")[i]) }
            },
            SettingRow.Segment("Thème", listOf("Sombre", "Contraste élevé"), if (p.theme == "contrast") 1 else 0) { i -> chat { it.copy(theme = if (i == 1) "contrast" else "dark") } },
            SettingRow.Value("Taille du texte", "${(fontScale * 100).roundToInt()} %", "Réglée dans les paramètres d’affichage d’Android.") {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            SettingRow.Toggle("Envoyer avec Entrée", p.enterToSend, "Maj + Entrée pour aller à la ligne.") { v -> chat { it.copy(enterToSend = v) } },
            SettingRow.Toggle("Confirmer les suppressions", p.confirmDelete) { v -> chat { it.copy(confirmDelete = v) } },
            SettingRow.Toggle("Titre automatique des conversations", p.autoTitle) { v -> chat { it.copy(autoTitle = v) } },
            SettingRow.Toggle("Défilement automatique", p.autoScroll) { v -> chat { it.copy(autoScroll = v) } },
            SettingRow.Toggle("Réduire les animations", p.reduceMotion, "Les animations du système comptent aussi.") { v -> chat { it.copy(reduceMotion = v) } },
            SettingRow.Value("Assistant de configuration", "Relancer") { onNavigate("onboarding") },
        )),
        SettingsSection("messages", Symbols.Chat, "Messages", "Rendu du contenu dans la conversation.", listOf(
            SettingRow.Toggle("Markdown", p.markdown) { v -> chat { it.copy(markdown = v) } },
            SettingRow.Toggle("Formules LaTeX", p.latex) { v -> chat { it.copy(latex = v) } },
            SettingRow.Toggle("Diagrammes Mermaid", p.mermaid) { v -> chat { it.copy(mermaid = v) } },
            SettingRow.Toggle("Numéros de ligne dans le code", p.codeLineNumbers) { v -> chat { it.copy(codeLineNumbers = v) } },
            SettingRow.Toggle("Horodatage des messages", p.timestamps) { v -> chat { it.copy(timestamps = v) } },
            SettingRow.Toggle("Badge du modèle", p.modelBadges, "Affiche le modèle sous chaque réponse.") { v -> chat { it.copy(modelBadges = v) } },
            SettingRow.Toggle("Afficher les sources", p.showSources) { v -> chat { it.copy(showSources = v) } },
        )),
        SettingsSection("streaming", Symbols.Stream, "Streaming", "Génération en direct, reprise et arrêt.", listOf(
            SettingRow.Toggle("Affichage fluide", p.smoothStreaming) { v -> chat { it.copy(smoothStreaming = v) } },
            SettingRow.Toggle("Reprise automatique", p.autoResume, "Reprend la génération après une coupure réseau.") { v -> chat { it.copy(autoResume = v) } },
            SettingRow.Toggle("File d’attente pendant la génération", p.queueWhileGenerating, "Vos messages attendent la fin de la tâche.") { v -> chat { it.copy(queueWhileGenerating = v) } },
            SettingRow.Segment("Revalider un message resté en file après", listOf("10 min", "30 min", "2 h"), listOf(10, 30, 120).indexOf(p.queueRevalidateMinutes).coerceAtLeast(1)) { i ->
                chat { it.copy(queueRevalidateMinutes = listOf(10, 30, 120)[i]) }
            },
            SettingRow.Segment("Bouton STOP", listOf("Mettre en pause", "Annuler la tâche"), if (p.stopAction == "cancel") 1 else 0,
                "Un appui long sur le grand STOP arrête toujours toute l’autonomie.") { i -> chat { it.copy(stopAction = if (i == 1) "cancel" else "pause") } },
            SettingRow.Toggle("Annoncer le début et la fin des réponses", p.announceStreaming, "Pour les lecteurs d’écran.") { v -> chat { it.copy(announceStreaming = v) } },
        )),
        SettingsSection("context", Symbols.DataUsage, "Contexte", "Ce que Cortana garde en tête pendant une conversation.", listOf(
            SettingRow.Toggle("Jauge de contexte", p.contextMeter) { v -> chat { it.copy(contextMeter = v) } },
            SettingRow.Toggle("Épingles prioritaires", p.pins) { v -> chat { it.copy(pins = v) } },
            SettingRow.Toggle("Hériter du contexte du projet", p.projectInheritance) { v -> chat { it.copy(projectInheritance = v) } },
        )),
        SettingsSection("models", Symbols.Psychology, "Modèles", "Choix des modèles et limites des tâches.", listOf(
            SettingRow.Value("Modèle par défaut", defaultProvider?.let { (it.defaultModelId ?: "?") + " · " + it.displayName } ?: "Aucun fournisseur") { onNavigate("providers") },
            SettingRow.Segment("Planification des tâches", listOf("Automatique", "Interactive", "Toujours"), listOf("auto", "interactive", "always").indexOf(s.planningMode).coerceAtLeast(0),
                "Automatique : un plan en étapes seulement pour les objectifs en plusieurs étapes.") { i -> upd { it.copy(planningMode = listOf("auto", "interactive", "always")[i]) } },
            SettingRow.Value("Appels d’outils par tâche", "${s.maxToolCallsPerTask}") { number = NumberEdit("Appels d’outils par tâche", s.maxToolCallsPerTask, 1, 200) { v -> upd { it.copy(maxToolCallsPerTask = v) } } },
            SettingRow.Value("Appels au modèle par tâche", "${s.maxModelCallsPerTask}") { number = NumberEdit("Appels au modèle par tâche", s.maxModelCallsPerTask, 1, 100) { v -> upd { it.copy(maxModelCallsPerTask = v) } } },
            SettingRow.Value("Durée maximale d’une tâche", "${s.maxTaskMinutes} min") { number = NumberEdit("Durée maximale (minutes)", s.maxTaskMinutes, 1, 120) { v -> upd { it.copy(maxTaskMinutes = v) } } },
            SettingRow.Value("Jetons de réponse", "${s.maxOutputTokens}") { number = NumberEdit("Jetons de réponse max", s.maxOutputTokens, 128, 32_000) { v -> upd { it.copy(maxOutputTokens = v) } } },
        )),
        SettingsSection("tools", Symbols.Handyman, "Outils", "Activité des outils et approbations.", listOf(
            SettingRow.Segment("Outils des nouvelles discussions", listOf("Discussion", "Assistant", "Complet"), listOf(Toolsets.CONVERSATION, Toolsets.ASSISTANT, Toolsets.FULL).indexOf(s.defaultToolset).coerceAtLeast(2)) { i ->
                upd { it.copy(defaultToolset = listOf(Toolsets.CONVERSATION, Toolsets.ASSISTANT, Toolsets.FULL)[i]) }
            },
            SettingRow.Toggle("Afficher l’activité", p.showActivity) { v -> chat { it.copy(showActivity = v) } },
            SettingRow.Value("Procédures", "Ouvrir") { onNavigate("skills") },
            SettingRow.Value("Capacités de l’appareil", "Ouvrir", "Permissions, accès et dernières utilisations.") { onNavigate("capabilities") },
            SettingRow.Value("Serveurs MCP", "${s.mcpServers.count { it.enabled }} actif(s)") { onNavigate("mcp") },
        )),
        SettingsSection("voice", Symbols.GraphicEq, "Voix", "Dictée, synthèse vocale et lecture.", listOf(
            SettingRow.Toggle("Lecture automatique des réponses", s.ttsEnabled) { v -> upd { it.copy(ttsEnabled = v) } },
            SettingRow.Segment("Vitesse de lecture", listOf("Lente", "Normale", "Rapide", "Très rapide"),
                when { p.speechRate < 0.9f -> 0; p.speechRate < 1.1f -> 1; p.speechRate < 1.4f -> 2; else -> 3 }) { i -> chat { it.copy(speechRate = listOf(0.8f, 1f, 1.25f, 1.5f)[i]) } },
            SettingRow.Value("Réveil et session vocale", "Ouvrir", "Gérés par le module Voix.") { onNavigate("settings/advanced") },
        )),
        SettingsSection("files", Symbols.Folder, "Fichiers", "Import, analyse et indexation.", listOf(
            SettingRow.Toggle("Analyse automatique", p.autoParse) { v -> chat { it.copy(autoParse = v) } },
            SettingRow.Segment("Taille maximale des images", listOf("1024 px", "2048 px", "4096 px"), listOf(1024, 2048, 4096).indexOf(p.imageMaxDimension).coerceAtLeast(1)) { i ->
                chat { it.copy(imageMaxDimension = listOf(1024, 2048, 4096)[i]) }
            },
            SettingRow.Segment("Alerte de taille d’envoi", listOf("10 Mo", "20 Mo", "50 Mo"), listOf(10, 20, 50).indexOf(p.uploadWarnMb).coerceAtLeast(1)) { i ->
                chat { it.copy(uploadWarnMb = listOf(10, 20, 50)[i]) }
            },
            SettingRow.Value("Dossier de travail", s.workingFolderUri?.let { android.net.Uri.decode(it).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Choisi" } } ?: "Choisir") { fix(Fix.FOLDER) },
        )),
        SettingsSection("memory", Symbols.Layers, "Mémoire", "Ce que Cortana peut retenir durablement.", listOf(
            SettingRow.Toggle("Suggestions de mémoire", p.memorySuggestions, "Cortana propose, vous confirmez.") { v -> chat { it.copy(memorySuggestions = v) } },
            SettingRow.Value("Souvenirs", "Ouvrir") { onNavigate("memory") },
            SettingRow.Value("Réglages avancés de la mémoire", "Ouvrir", "Indexation, compactage, rétention.") { onNavigate("settings/advanced") },
        )),
        SettingsSection("developer", Symbols.Code, "Développeur", "Informations techniques pour déboguer.", listOf(
            SettingRow.Toggle("Mode développeur", p.developer, "Affiche les outils avancés dans la barre latérale.") { v -> chat { it.copy(developer = v) } },
            SettingRow.Value("Journal d’audit", "Ouvrir") { onNavigate("audit") },
            SettingRow.Value("Tous les réglages avancés", "Ouvrir", "Connexions, plugins, sauvegardes, mises à jour, observabilité…") { onNavigate("settings/advanced") },
        )),
        SettingsSection("privacy", Symbols.Lock, "Confidentialité", "Où vont vos données, et ce qui reste local.", listOf(
            SettingRow.Toggle("Local uniquement", s.privacyMode == ModelGateway.PRIVACY_LOCAL_ONLY, "Désactive tous les fournisseurs cloud.") { v ->
                upd { it.copy(privacyMode = if (v) ModelGateway.PRIVACY_LOCAL_ONLY else ModelGateway.PRIVACY_STANDARD) }
            },
            if (halted) SettingRow.Value("Autonomie arrêtée (STOP)", "Reprendre", "Empreinte ou code de l’appareil.") { resume() }
            else SettingRow.Value("Arrêt d’urgence", "Tout arrêter", "Aussi : appui long sur le grand STOP, notification, tuile des réglages rapides.") { c.killSwitch.halt("réglages") },
            SettingRow.Value("Sécurité avancée", "Ouvrir", "Applications sensibles, réseau, secrets.") { onNavigate("settings/advanced") },
        )),
    )
    SettingsDesignScreen(sections, selected, { selected = it })
    number?.let { n -> NumberDialog(n) { number = null } }
}

class NumberEdit(val title: String, val value: Int, val min: Int, val max: Int, val onSave: (Int) -> Unit)

@Composable
private fun NumberDialog(n: NumberEdit, onDismiss: () -> Unit) {
    var text by remember(n) { mutableStateOf(n.value.toString()) }
    val v = text.toIntOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(n.title) },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Entre ${n.min} et ${n.max}") }, isError = v == null || v !in n.min..n.max)
        },
        confirmButton = { TextButton(enabled = v != null && v in n.min..n.max, onClick = { n.onSave(v!!); onDismiss() }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
