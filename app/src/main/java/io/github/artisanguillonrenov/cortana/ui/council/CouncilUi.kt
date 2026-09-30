package io.github.artisanguillonrenov.cortana.ui.council

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.council.BudgetProfile
import io.github.artisanguillonrenov.cortana.core.council.CouncilConfig
import io.github.artisanguillonrenov.cortana.core.council.CouncilMode
import io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs
import io.github.artisanguillonrenov.cortana.core.council.CouncilProgress
import io.github.artisanguillonrenov.cortana.core.council.CouncilSummary
import io.github.artisanguillonrenov.cortana.core.council.CouncilTopology
import io.github.artisanguillonrenov.cortana.core.council.DecisionProtocol
import io.github.artisanguillonrenov.cortana.core.council.JudgeSetting
import io.github.artisanguillonrenov.cortana.core.council.ModelRouting
import io.github.artisanguillonrenov.cortana.core.council.ProfileKind
import io.github.artisanguillonrenov.cortana.core.council.ReasoningEffort
import io.github.artisanguillonrenov.cortana.core.council.RoleModelConfig
import io.github.artisanguillonrenov.cortana.core.council.SlotStatus
import io.github.artisanguillonrenov.cortana.core.memory.AppSettings
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard

/**
 * Words for the council UI (doc 07): every state is said in text, never by colour alone, so
 * TalkBack reads the same thing the screen shows. Pure functions, tested on the JVM.
 */
object CouncilTexts {
    val MODES = listOf(
        CouncilMode.AUTO to "Auto", CouncilMode.FAST to "Rapide", CouncilMode.REINFORCED to "Renforcé",
        CouncilMode.COUNCIL_4 to "Conseil 4", CouncilMode.DEEP to "Approfondi", CouncilMode.CUSTOM to "Personnalisé",
    )
    val MODE_HELP = mapOf(
        CouncilMode.AUTO to "Cortana décide : rien pour une demande simple, un conseil pour une décision, un sujet à risque ou un développement non trivial.",
        CouncilMode.FAST to "Un seul spécialiste (ingénieur solution), sans débat.",
        CouncilMode.REINFORCED to "Deux avis indépendants, puis synthèse.",
        CouncilMode.COUNCIL_4 to "Quatre spécialistes adaptés au sujet, un tour de confrontation et un défi final.",
        CouncilMode.DEEP to "Quatre spécialistes, jusqu'à trois tours, juge anonyme et vérification stricte. Plus lent et plus coûteux.",
        CouncilMode.CUSTOM to "Vos rôles, vos tours, votre topologie et votre protocole de décision.",
    )
    val BUDGETS = listOf(BudgetProfile.ECO to "Éco", BudgetProfile.BALANCED to "Équilibré", BudgetProfile.QUALITY to "Qualité", BudgetProfile.CUSTOM to "Personnalisé")
    val ROUTINGS = listOf(ModelRouting.SAME_MODEL to "Même modèle pour tous", ModelRouting.PER_ROLE to "Par rôle", ModelRouting.AUTO_CAPABILITY to "Automatique", ModelRouting.HYBRID to "Hybride")
    val TOPOLOGIES = listOf(
        CouncilTopology.AUTO to "Auto", CouncilTopology.INDEPENDENT to "Indépendants", CouncilTopology.FULL_MESH to "Tous avec tous",
        CouncilTopology.HUB to "En étoile", CouncilTopology.RING to "En anneau", CouncilTopology.SPARSE_DYNAMIC to "Ciblée",
    )
    val PROTOCOLS = listOf(
        DecisionProtocol.AUTO to "Auto (hybride)", DecisionProtocol.HYBRID to "Hybride (votes + preuves + objections)", DecisionProtocol.SIMPLE_MAJORITY to "Majorité simple",
        DecisionProtocol.SUPERMAJORITY to "Majorité des deux tiers", DecisionProtocol.MAJORITY_CONSENSUS to "Majorité sans objection importante", DecisionProtocol.UNANIMITY to "Unanimité",
        DecisionProtocol.APPROVAL to "Approbation", DecisionProtocol.RANKED to "Classement (Borda)", DecisionProtocol.CUMULATIVE to "Points cumulés",
        DecisionProtocol.JUDGE to "Juge", DecisionProtocol.BLIND_JUDGE_THEN_VOTE to "Juge anonyme puis vote",
    )
    val JUDGES = listOf(JudgeSetting.OFF to "Jamais", JudgeSetting.AUTO to "Si nécessaire", JudgeSetting.ON to "Toujours")
    val EFFORTS = listOf(ReasoningEffort.AUTO to "Par défaut", ReasoningEffort.LOW to "Faible", ReasoningEffort.MEDIUM to "Moyen", ReasoningEffort.HIGH to "Élevé", ReasoningEffort.XHIGH to "Très élevé")

    fun slotState(s: SlotStatus): String = when (s) {
        SlotStatus.PENDING -> "en attente"
        SlotStatus.RUNNING -> "en cours"
        SlotStatus.SUCCEEDED -> "terminé"
        SlotStatus.TIMED_OUT -> "délai dépassé"
        SlotStatus.CANCELLED -> "annulé"
        SlotStatus.SKIPPED -> "ignoré"
        SlotStatus.BUDGET -> "budget atteint"
        SlotStatus.INVALID -> "réponse invalide"
        SlotStatus.FAILED -> "indisponible"
    }

    fun slotSymbol(s: SlotStatus): String = when (s) { SlotStatus.SUCCEEDED -> "✓"; SlotStatus.RUNNING -> "…"; SlotStatus.PENDING -> "○"; else -> "✗" }

    /** What TalkBack reads for the live progress: the phase, then every specialist with its state in words. */
    fun progressDescription(p: CouncilProgress, roleLabel: (String) -> String): String =
        p.label(roleLabel).substringBefore(" — ") + ". " + p.slots.joinToString(" ; ") { (id, st) -> "${roleLabel(id)} : ${slotState(st)}" }

    fun consensus(c: String): String = when (c) {
        "fort" -> "Consensus fort"
        "moyen" -> "Consensus moyen"
        "faible" -> "Consensus faible"
        "pas de consensus" -> "Pas de consensus"
        "un seul avis" -> "Un seul avis"
        else -> "Aucun consensus"
    }

    fun duration(ms: Long): String = if (ms < 60_000) "${(ms + 500) / 1000} s" else "${ms / 60_000} min ${(ms % 60_000) / 1000} s"

    /** The summary card as text (doc 07 §7.7): no prompt, no reasoning, no secret — only the structured summary. */
    fun summaryLines(s: CouncilSummary): List<Pair<String, List<String>>> = listOfNotNull(
        ("Points d'accord" to s.agreements).takeIf { it.second.isNotEmpty() },
        ("Risques restants" to s.objections).takeIf { it.second.isNotEmpty() },
        ("Incertitudes" to s.uncertainties).takeIf { it.second.isNotEmpty() },
        ("Preuves" to s.evidence).takeIf { it.second.isNotEmpty() },
        ("À savoir" to s.notices).takeIf { it.second.isNotEmpty() },
    )

    fun header(s: CouncilSummary): String = "${consensus(s.consensus)} · ${s.agentsOk}/${s.agentsTotal} spécialistes · ${duration(s.durationMs)}" +
        (if (s.rounds > 0) " · ${s.rounds} tour${if (s.rounds > 1) "s" else ""} de confrontation" else "")

    fun technical(s: CouncilSummary): List<String> = listOfNotNull(
        "Modèles : ${s.models.joinToString()}".takeIf { s.models.isNotEmpty() },
        "Jetons : ${s.totalTokens}",
        "Votes : ${s.votes.entries.sortedByDescending { it.value }.joinToString { "${it.key.take(6)}=${it.value}" }}".takeIf { s.votes.isNotEmpty() },
        "Arguments retenus : ${s.retainedArguments}",
        "Statut : ${s.status.name.lowercase()}",
    )
}

/** Live progress during a council (doc 07 §7.6): phases and slot states, never content. */
@Composable
fun CouncilProgressPanel(p: CouncilProgress, roleLabel: (String) -> String) {
    val description = CouncilTexts.progressDescription(p, roleLabel)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Conseil de réflexion — " + p.label(roleLabel).substringBefore(" — "), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            p.slots.forEach { (id, st) ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text("${CouncilTexts.slotSymbol(st)} ${roleLabel(id)} · ${CouncilTexts.slotState(st)}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The "Résumé du conseil" card (doc 07 §7.7) under Cortana's single answer. */
@Composable
fun CouncilSummaryCard(s: CouncilSummary, developer: Boolean) {
    var details by rememberSaveable(s.runId) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Résumé du conseil", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(CouncilTexts.header(s), style = MaterialTheme.typography.bodyMedium)
            CouncilTexts.summaryLines(s).forEach { (title, lines) ->
                Text(title, style = MaterialTheme.typography.labelLarge)
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (developer || details) {
                HorizontalDivider()
                CouncilTexts.technical(s).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
            if (!developer) TextButton(onClick = { details = !details }) { Text(if (details) "Masquer les détails techniques" else "Détails techniques") }
        }
    }
}

/** Réglages › Intelligence › Conseil de réflexion (doc 07 §7.1–7.5). Off by default. */
@Composable
fun CouncilSettingsSection(s: AppSettings, upd: ((AppSettings) -> AppSettings) -> Unit) {
    val c = LocalContainer.current
    val cfg = s.council
    val prefs = s.councilPrefs
    fun cfgUpd(f: (CouncilConfig) -> CouncilConfig) = upd { it.copy(council = f(it.council)) }
    fun prefUpd(f: (CouncilPrefs) -> CouncilPrefs) = upd { it.copy(councilPrefs = f(it.councilPrefs)) }
    val providers by c.providers.observe().collectAsState(initial = emptyList())
    val listed by c.providers.models.collectAsState()
    val choices = providers.filter { it.enabled }.flatMap { p ->
        ((listed[p.id]?.map { it.id } ?: emptyList()) + listOfNotNull(p.defaultModelId)).distinct().map { m -> "${p.id}/$m" to "${p.displayName} · $m" }
    }
    SectionCard("Intelligence — Conseil de réflexion") {
        LabeledSwitch("Conseil de réflexion", cfg.enabled) { v -> cfgUpd { it.copy(enabled = v) } }
        Text("Pour les décisions, sujets à risque et problèmes complexes, plusieurs spécialistes temporaires analysent la demande en parallèle, confrontent leurs arguments " +
            "et Cortana répond une seule fois. Ils ne font que proposer : toute action reste soumise à vos règles et confirmations. Plus d'appels au modèle, donc plus de temps et de coût.",
            style = MaterialTheme.typography.bodySmall)
        if (!cfg.enabled) return@SectionCard

        Header("Mode")
        Chips(CouncilTexts.MODES, cfg.mode) { m -> cfgUpd { it.copy(mode = m) } }
        CouncilTexts.MODE_HELP[cfg.mode]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Header("Profil de budget")
        Chips(CouncilTexts.BUDGETS, prefs.budgetProfile) { b -> prefUpd { it.copy(budgetProfile = b) } }

        Header("Modèles")
        Chips(CouncilTexts.ROUTINGS, prefs.routing) { r -> prefUpd { it.copy(routing = r) } }
        RoutePicker("Modèle de la synthèse", prefs.synthesisRoute, choices, "Modèle de la discussion") { v -> prefUpd { it.copy(synthesisRoute = v) } }
        RoutePicker("Modèle du juge (un petit modèle suffit)", prefs.judgeRoute, choices, "Automatique") { v -> prefUpd { it.copy(judgeRoute = v) } }
        if (prefs.routing != ModelRouting.SAME_MODEL || cfg.mode == CouncilMode.CUSTOM) RoleEditors(cfg, choices, providers.map { it.id }.toSet(), ::cfgUpd)

        Header("Débat")
        Text("Tours de confrontation (mode Personnalisé)", style = MaterialTheme.typography.bodyMedium)
        Chips((0..3).map { it to it.toString() }, cfg.maxRounds.coerceIn(0, 3)) { n -> cfgUpd { it.copy(maxRounds = n) } }
        Text("Qui lit qui (mode Personnalisé)", style = MaterialTheme.typography.bodyMedium)
        Chips(CouncilTexts.TOPOLOGIES, cfg.topology) { t -> cfgUpd { it.copy(topology = t) } }
        Dropdown("Protocole de décision (mode Personnalisé)", CouncilTexts.PROTOCOLS, cfg.decisionProtocol) { d -> cfgUpd { it.copy(decisionProtocol = d) } }
        LabeledSwitch("Défi final avant de répondre (mode Personnalisé)", cfg.challengeFinal) { v -> cfgUpd { it.copy(challengeFinal = v) } }
        Text("Juge anonyme", style = MaterialTheme.typography.bodyMedium)
        Chips(CouncilTexts.JUDGES, prefs.judge) { j -> prefUpd { it.copy(judge = j) } }
        NumberField("Quorum (% des spécialistes qui doivent répondre)", (cfg.quorumRatio * 100).toInt()) { v -> cfgUpd { it.copy(quorumRatio = (v.coerceIn(50, 100)) / 100.0) } }
        LabeledSwitch("Arrêt anticipé quand l'accord est fort et étayé", prefs.earlyStop) { v -> prefUpd { it.copy(earlyStop = v) } }

        Header("Coûts et limites")
        val b = cfg.budget
        NumberField("Jetons max par conseil", b.maxTotalTokens) { v -> cfgUpd { it.copy(budget = it.budget.copy(maxTotalTokens = v.coerceIn(4_000, 1_000_000))) } }
        DecimalField("Coût max par conseil (USD, vide = aucun)", b.maxCostMicros?.let { it / 1_000_000.0 }) { v -> cfgUpd { it.copy(budget = it.budget.copy(maxCostMicros = v?.let { d -> (d * 1_000_000).toLong().coerceAtLeast(0) })) } }
        NumberField("Appels au modèle max par conseil", b.maxProviderCalls) { v -> cfgUpd { it.copy(budget = it.budget.copy(maxProviderCalls = v.coerceIn(2, 64))) } }
        NumberField("Durée max d'un conseil (secondes)", (b.maxWallTimeMs / 1000).toInt()) { v -> cfgUpd { it.copy(budget = it.budget.copy(maxWallTimeMs = v.coerceIn(10, 1_800) * 1000L)) } }
        NumberField("Spécialistes en parallèle", b.maxConcurrentAgents) { v -> cfgUpd { it.copy(budget = it.budget.copy(maxConcurrentAgents = v.coerceIn(1, 16))) } }
        OptionalNumberField("Jetons max par jour, tous conseils (vide = aucun)", prefs.maxDailyTokens) { v -> prefUpd { it.copy(maxDailyTokens = v?.coerceAtLeast(10_000)) } }
        NumberField("En mode Auto, réduire le conseil au-delà de (jetons)", prefs.warnAboveTokens) { v -> prefUpd { it.copy(warnAboveTokens = v.coerceIn(0, 1_000_000)) } }
        NumberField("Batterie faible : moins de spécialistes sous (%)", prefs.lowBatteryPercent) { v -> prefUpd { it.copy(lowBatteryPercent = v.coerceIn(0, 100)) } }

        Header("Affichage")
        LabeledSwitch("Afficher le résumé du conseil sous la réponse", cfg.showCouncilSummary) { v -> cfgUpd { it.copy(showCouncilSummary = v) } }
        LabeledSwitch("Détails techniques (modèles, jetons, votes)", prefs.developerDetails) { v -> prefUpd { it.copy(developerDetails = v) } }
        Text("Jamais affiché ni enregistré : le raisonnement privé des modèles, les invites complètes, les secrets.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoleEditors(cfg: CouncilConfig, choices: List<Pair<String, String>>, providerIds: Set<String>, cfgUpd: ((CouncilConfig) -> CouncilConfig) -> Unit) {
    val c = LocalContainer.current
    val registry = c.councilProfiles
    val custom = cfg.mode == CouncilMode.CUSTOM
    val shown = if (custom) registry.agents().map { it.id } else (listOf("strategist", "evidence_analyst", "solution_engineer", "challenger") + cfg.roles.map { it.profileId }).distinct()
    fun roleUpd(id: String, f: (RoleModelConfig) -> RoleModelConfig) = cfgUpd { conf ->
        val cur = conf.roles.firstOrNull { it.profileId == id } ?: RoleModelConfig(id)
        conf.copy(roles = conf.roles.filter { it.profileId != id } + f(cur))
    }
    Header(if (custom) "Rôles du conseil et leurs modèles" else "Modèle de chaque rôle")
    if (custom) Text("Membres : ${cfg.roles.size} (au plus ${cfg.maxAgents} utilisés, dans cet ordre).", style = MaterialTheme.typography.bodySmall)
    val errors = registry.validateRoles(cfg.roles, providerIds)
    errors.forEach { Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    shown.forEach { id ->
        val profile = registry.get(id) ?: return@forEach
        if (profile.kind != ProfileKind.AGENT) return@forEach
        val role = cfg.roles.firstOrNull { it.profileId == id }
        var open by rememberSaveable(id) { mutableStateOf(false) }
        val model = role?.let { r -> r.modelId?.let { m -> choices.firstOrNull { it.first == "${r.providerId}/$m" }?.second ?: m } } ?: "modèle de la discussion"
        Text("${if (open) "▾" else "▸"} ${profile.role} — $model" + (if (custom && role != null) " · membre" else ""),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Replier" else "Configurer") { open = !open }.padding(vertical = 10.dp))
        if (open) Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.mission, style = MaterialTheme.typography.bodySmall)
            if (custom) LabeledSwitch("Membre du conseil", role != null) { v ->
                if (v) roleUpd(id) { it } else cfgUpd { conf -> conf.copy(roles = conf.roles.filter { it.profileId != id }) }
            }
            RoutePicker("Modèle", role?.let { r -> r.modelId?.let { "${r.providerId}/$it" } }, choices, "Modèle de la discussion") { v ->
                roleUpd(id) { it.copy(providerId = v?.substringBefore('/'), modelId = v?.substringAfter('/')) }
            }
            RoutePicker("Repli", role?.fallbackModelIds?.firstOrNull(), choices, "Modèle de la discussion") { v -> roleUpd(id) { it.copy(fallbackModelIds = listOfNotNull(v)) } }
            Text("Effort de raisonnement (seulement si le modèle le prend en charge)", style = MaterialTheme.typography.bodySmall)
            Chips(CouncilTexts.EFFORTS, role?.reasoningEffort ?: ReasoningEffort.AUTO) { e -> roleUpd(id) { it.copy(reasoningEffort = e) } }
            LabeledSwitch("Modèle local uniquement pour ce rôle", role?.localOnly == true) { v -> roleUpd(id) { it.copy(localOnly = v) } }
            OptionalNumberField("Jetons de réponse max (vide = ${profile.maxOutputTokens})", role?.maxOutputTokens) { v -> roleUpd(id) { it.copy(maxOutputTokens = v?.coerceIn(64, 8_000)) } }
            OptionalNumberField("Délai max (secondes, vide = automatique)", role?.timeoutMs?.let { (it / 1000).toInt() }) { v -> roleUpd(id) { it.copy(timeoutMs = v?.coerceIn(5, 900)?.times(1000L)) } }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(v == selected, { onSelect(v) }, label = { Text(label) },
                modifier = Modifier.semantics { stateDescription = if (v == selected) "sélectionné" else "non sélectionné" })
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString()) }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

@Composable
private fun RoutePicker(label: String, value: String?, choices: List<Pair<String, String>>, defaultLabel: String, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(value?.let { v -> choices.firstOrNull { it.first == v }?.second ?: v } ?: defaultLabel) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { open = false; onChange(null) })
            choices.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onChange(k) }) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { t -> text = t.filter(Char::isDigit).take(9); text.toIntOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OptionalNumberField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(text, { t -> text = t.filter(Char::isDigit).take(9); onChange(text.toIntOrNull()) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DecimalField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(text, { t -> text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(12); onChange(text.replace(',', '.').toDoubleOrNull()) },
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
