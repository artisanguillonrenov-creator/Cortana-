package io.github.artisanguillonrenov.cortana.ui.onboarding

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.model.ModelDescriptor
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import io.github.artisanguillonrenov.cortana.ui.common.StatusDot
import io.github.artisanguillonrenov.cortana.ui.common.rememberFixHandler
import io.github.artisanguillonrenov.cortana.ui.health.Fix
import io.github.artisanguillonrenov.cortana.ui.health.HealthStatus
import kotlinx.coroutines.launch

private const val STEPS = 7

/** §15 — onboarding, all taps, French. Every step detects its own state, so re-running is safe. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onProviders: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val fix = rememberFixHandler(onProviders) { tick++ }
    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
                Text("Étape ${step + 1} sur $STEPS", style = MaterialTheme.typography.labelLarge)
                // tick forces state re-detection after returning from system screens
                androidx.compose.runtime.key(tick) {
                    when (step) {
                        0 -> Welcome()
                        1 -> ProviderStep()
                        2 -> PermissionStep(
                            "Notifications", "Pour vos rappels, les demandes de confirmation et le bouton STOP de la notification.",
                            c.health.notificationsGranted(), "Autoriser les notifications",
                        ) { fix(Fix.NOTIFICATIONS) }
                        3 -> AccessibilityStep(fix)
                        4 -> BatteryStep(fix)
                        5 -> OptionalStep(fix)
                        else -> StopStep()
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Précédent") }
                    Spacer(Modifier.weight(1f))
                    if (step < STEPS - 1) {
                        TextButton(onClick = { step++ }) { Text("Passer") }
                        Button(onClick = { step++ }) { Text("Suivant") }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                c.settings.update { it.copy(onboardingDone = true) }
                                onDone()
                            }
                        }) { Text("Commencer à utiliser Cortana") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

@Composable
private fun Welcome() {
    Title("Bienvenue dans Cortana")
    Text("Cortana est votre assistante personnelle qui fonctionne entièrement sur cette tablette. Seules les questions posées au modèle d'IA partent sur Internet, chez le fournisseur que vous choisissez (Infermatic, OpenRouter, Groq…).")
    SectionCard("Ce qu'elle sait faire") {
        Text("• Discuter et chercher sur le web\n• Retenir vos préférences (écran Mémoire)\n• Vous rappeler des choses à l'heure\n• Lire et modifier les fichiers d'un dossier que vous choisissez\n• Piloter l'écran : ouvrir des applis, toucher, écrire, faire défiler")
    }
    SectionCard("Vos garde-fous") {
        Text("• Un bouton STOP toujours disponible (écran, notification, tuile des réglages rapides)\n• Les actions sensibles (payer, supprimer, sécurité) exigent votre empreinte\n• Les envois et partages demandent votre confirmation\n• Les banques et gestionnaires de mots de passe sont interdits à l'automatisation\n• Vos clés API sont chiffrées et ne sont jamais montrées au modèle")
    }
}

@Composable
private fun PermissionStep(title: String, why: String, granted: Boolean, button: String, onClick: () -> Unit) {
    Title(title)
    Text(why)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (granted) HealthStatus.OK else HealthStatus.WARN)
        Text(if (granted) "Accordé ✓" else "Pas encore accordé")
    }
    if (!granted) Button(onClick = onClick) { Text(button) }
}

@Composable
private fun ProviderStep() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var presetId by rememberSaveable { mutableStateOf("infermatic") }
    var key by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf<ProviderEntity?>(null) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Title("Fournisseur de modèle")
    Text("Choisissez un fournisseur, collez votre clé API, puis « Tester la connexion ». Vous pourrez en ajouter d'autres plus tard (écran Fournisseurs).")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("infermatic" to "Infermatic", "openrouter" to "OpenRouter", "groq" to "Groq").forEach { (id, l) ->
            FilterChip(presetId == id, { presetId = id; provider = null; models = emptyList(); status = null }, label = { Text(l) })
        }
    }
    val preset = c.presets.byId(presetId)
    Text(preset?.baseUrl.orEmpty(), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(key, { key = it }, label = { Text("Clé API ${preset?.displayName}") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = key.isNotBlank() && !busy && preset != null, onClick = {
            scope.launch {
                busy = true; status = "Test en cours…"
                val existing = provider
                val p = if (existing == null) c.providers.create(preset!!, preset.displayName, preset.baseUrl, key).also {
                    c.audit.record("owner", "provider.create", it.displayName, "ok")
                } else { c.providers.update(existing, key); existing }
                provider = p
                if (c.settings.current.defaultProviderId == null) c.settings.update { it.copy(defaultProviderId = p.id) }
                runCatching { c.providers.listModels(p.id, refresh = true) }
                    .onSuccess { models = it; status = "✓ Connexion réussie : ${it.size} modèles. Touchez celui que vous voulez utiliser." }
                    .onFailure { status = "✗ ${it.message}" }
                busy = false
            }
        }) { Text("Tester la connexion") }
        if (busy) CircularProgressIndicator()
    }
    status?.let { Text(it, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else Cortana.colors.successText) }
    val p = provider
    if (p != null && models.isNotEmpty()) {
        p.defaultModelId?.let { Text("Modèle choisi : $it", fontWeight = FontWeight.Bold) }
        OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filtrer les modèles…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            models.filter { filter.isBlank() || it.id.contains(filter, true) }.take(200).forEach { m ->
                Text((if (m.id == p.defaultModelId) "✓ " else "") + m.id, Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        c.providers.update(p.copy(defaultModelId = m.id), null)
                        c.capabilities.rememberContext(p.id, m.id, m.contextWindow)
                        provider = c.providers.get(p.id)
                    }
                }.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun AccessibilityStep(fix: (Fix) -> Unit) {
    val c = LocalContainer.current
    val enabled = c.health.accessibilityEnabled()
    val restricted = c.health.restrictedSettingsState()
    Title("Contrôle de l'écran (Accessibilité)")
    Text("C'est ce qui permet à Cortana d'ouvrir des applications, toucher des boutons et écrire à votre place — uniquement quand vous lui confiez une tâche, avec l'indicateur « Cortana contrôle l'écran » et le bouton STOP visibles.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (enabled) HealthStatus.OK else HealthStatus.ERROR)
        Text(if (enabled) "Accessibilité activée ✓" else "Accessibilité désactivée")
    }
    if (!enabled) {
        SectionCard("1. Autoriser les paramètres restreints" + if (restricted == "allowed") " ✓" else "") {
            Text("Android bloque l'accessibilité des applications installées depuis un fichier. Pour la débloquer :")
            Text("a. Touchez « Ouvrir Infos de l'appli » ci-dessous.\nb. En haut à droite, touchez ⋮ (trois points).\nc. Touchez « Autoriser les paramètres restreints » et confirmez avec votre code ou empreinte.\nd. Revenez ici avec la touche Retour.")
            Text("Si le menu ⋮ n'affiche pas cette option, faites d'abord l'étape 2 une fois (l'interrupteur sera grisé), puis revenez ici.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { fix(Fix.APP_INFO) }) { Text("Ouvrir Infos de l'appli") }
        }
        SectionCard("2. Activer Cortana") {
            Text("a. Touchez « Ouvrir Accessibilité ».\nb. Touchez « Applications installées » (ou « Services installés »).\nc. Touchez « Cortana — contrôle de l'écran » puis activez l'interrupteur et confirmez « Autoriser ».\nd. Revenez ici.")
            Button(onClick = { fix(Fix.ACCESSIBILITY) }) { Text("Ouvrir Accessibilité") }
        }
    }
}

@Composable
private fun BatteryStep(fix: (Fix) -> Unit) {
    val c = LocalContainer.current
    val ok = c.health.ignoringBatteryOptimizations()
    Title("Batterie (Samsung)")
    Text("One UI met en veille les applications de façon agressive : rappels en retard, tâches interrompues. Trois réglages évitent cela.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (ok) HealthStatus.OK else HealthStatus.WARN)
        Text(if (ok) "Optimisation de batterie désactivée ✓" else "Optimisation de batterie active")
    }
    if (!ok) Button(onClick = { fix(Fix.BATTERY) }) { Text("1. Désactiver l'optimisation (Autoriser)") }
    SectionCard("2. Batterie « Non restreinte »") {
        Text("Infos de l'appli → Batterie → choisissez « Non restreinte ».")
        OutlinedButton(onClick = { fix(Fix.APP_INFO) }) { Text("Ouvrir Infos de l'appli") }
    }
    SectionCard("3. Jamais mise en veille") {
        Text("Paramètres → Entretien de l'appareil → Batterie → Limites d'utilisation en arrière-plan → « Applis jamais en veille » → + → Cortana.")
    }
}

@Composable
private fun OptionalStep(fix: (Fix) -> Unit) {
    val c = LocalContainer.current
    Title("Options")
    Text("Facultatif, modifiable plus tard dans Réglages et Santé.")
    SectionCard("Dossier de travail") {
        Text(if (c.settings.current.workingFolderUri != null) "Dossier autorisé ✓" else "Choisissez un dossier (par ex. vos projets) que Cortana pourra lire et modifier. Elle n'aura accès à rien d'autre.")
        OutlinedButton(onClick = { fix(Fix.FOLDER) }) { Text("Choisir un dossier") }
    }
    SectionCard("Microphone") {
        Text(if (c.health.microphoneGranted()) "Autorisé ✓" else "Pour dicter vos demandes (bouton 🎤).")
        if (!c.health.microphoneGranted()) OutlinedButton(onClick = { fix(Fix.MICROPHONE) }) { Text("Autoriser le micro") }
    }
    SectionCard("Luminosité directe") {
        Text(if (c.health.canWriteSettings()) "Autorisé ✓" else "« Modifier les paramètres système » permet de régler la luminosité sans passer par l'écran des paramètres.")
        if (!c.health.canWriteSettings()) OutlinedButton(onClick = { fix(Fix.WRITE_SETTINGS) }) { Text("Autoriser") }
    }
    SectionCard("Rappels à l'heure exacte") {
        Text(if (c.health.exactAlarms()) "Autorisé ✓" else "Autorisez les alarmes exactes pour des rappels précis.")
        if (!c.health.exactAlarms()) OutlinedButton(onClick = { fix(Fix.EXACT_ALARM) }) { Text("Autoriser") }
    }
    SectionCard("Empreinte / code") {
        Text(if (c.health.deviceSecure()) "Verrouillage configuré ✓ — utilisé pour autoriser les actions sensibles." else "Aucun verrouillage d'écran : les actions sensibles seront toujours refusées. Configurez un code et une empreinte.")
        if (!c.health.deviceSecure()) OutlinedButton(onClick = { fix(Fix.SECURITY) }) { Text("Ouvrir Sécurité") }
    }
}

@Composable
private fun StopStep() {
    Title("Le bouton STOP")
    Text("Vous gardez toujours la main :")
    SectionCard {
        Text("• Pendant que Cortana pilote l'écran, un bandeau « Cortana contrôle l'écran » avec un bouton STOP rouge reste affiché en haut.\n• La notification « Cortana travaille » contient aussi STOP.\n• Ajoutez la tuile « Stop Cortana » : tirez le panneau des réglages rapides vers le bas, touchez ✏️ (modifier), puis faites glisser « Stop Cortana » dans vos tuiles.\n• Si vous touchez l'écran pendant une automatisation, Cortana se met en pause et vous demande si elle doit continuer.")
    }
    Text("Après un STOP, rien ne reprend tout seul : il faut rouvrir Cortana et confirmer avec votre empreinte ou votre code.")
}
