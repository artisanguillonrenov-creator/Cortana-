package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.effectiveUi
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard

/** Chat Workspace settings (doc 12): the defaults keep the simple mode simple. */
@Composable
fun ChatWorkspaceSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val p = s.chat
    fun set(change: (ChatPrefs) -> ChatPrefs) = upd { it.copy(chat = change(it.chat)) }
    SectionCard("Espace de discussion") {
        Text("Interface", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("cortana" to "Cortana", "workspace" to "Workspace (rc4)", "classic" to "Classique"), p.effectiveUi) { v -> set { it.copy(ui = v, uiChosen = true) } }
        Text("Densité", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("compact" to "Compacte", "comfort" to "Confort", "large" to "Large"), p.density) { v -> set { it.copy(density = v) } }
        Text("Thème", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre", "contrast" to "Contraste élevé"), p.theme) { v -> set { it.copy(theme = v) } }

        Text("Saisie", style = MaterialTheme.typography.titleSmall)
        Toggle("Entrée envoie (Maj+Entrée : nouvelle ligne)", p.enterToSend) { v -> set { it.copy(enterToSend = v) } }
        Toggle("Mettre en file les messages écrits pendant une réponse", p.queueWhileGenerating) { v -> set { it.copy(queueWhileGenerating = v) } }
        Chips(listOf("10" to "Revalider après 10 min", "30" to "30 min", "120" to "2 h"), p.queueRevalidateMinutes.toString()) { v -> set { it.copy(queueRevalidateMinutes = v.toInt()) } }
        Toggle("Confirmer avant de supprimer une discussion", p.confirmDelete) { v -> set { it.copy(confirmDelete = v) } }

        Text("Messages", style = MaterialTheme.typography.titleSmall)
        Toggle("Mise en forme (Markdown)", p.markdown) { v -> set { it.copy(markdown = v) } }
        Toggle("Formules mathématiques (LaTeX)", p.latex) { v -> set { it.copy(latex = v) } }
        Toggle("Numéros de ligne dans le code", p.codeLineNumbers) { v -> set { it.copy(codeLineNumbers = v) } }
        Toggle("Heure des messages", p.timestamps) { v -> set { it.copy(timestamps = v) } }
        Toggle("Modèle qui a répondu", p.modelBadges) { v -> set { it.copy(modelBadges = v) } }
        Toggle("Sources des réponses", p.showSources) { v -> set { it.copy(showSources = v) } }
        Toggle("Actions de Cortana (outils)", p.showActivity) { v -> set { it.copy(showActivity = v) } }
        Toggle("Défilement automatique", p.autoScroll) { v -> set { it.copy(autoScroll = v) } }

        Text("Contexte", style = MaterialTheme.typography.titleSmall)
        Toggle("Indicateur de contexte", p.contextMeter) { v -> set { it.copy(contextMeter = v) } }

        Text("Lecture à voix haute", style = MaterialTheme.typography.titleSmall)
        val rate = when { p.speechRate < 0.9f -> "0.8"; p.speechRate < 1.1f -> "1.0"; p.speechRate < 1.4f -> "1.25"; else -> "1.5" }
        Chips(listOf("0.8" to "Lente", "1.0" to "Normale", "1.25" to "Rapide", "1.5" to "Très rapide"), rate) { v -> set { it.copy(speechRate = v.toFloat()) } }

        Text("Accessibilité", style = MaterialTheme.typography.titleSmall)
        Toggle("Annoncer le début et la fin des réponses (lecteur d'écran)", p.announceStreaming) { v -> set { it.copy(announceStreaming = v) } }
        Toggle("Réduire les animations", p.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }

        Text("Développeur", style = MaterialTheme.typography.titleSmall)
        Toggle("Afficher fournisseur, route et inspecteur", p.developer) { v -> set { it.copy(developer = v) } }
        Text("Le raisonnement privé des modèles n'est jamais affiché, même en mode développeur.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, label) -> FilterChip(current == k, { onPick(k) }, label = { Text(label) }) }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
