package io.github.artisanguillonrenov.cortana.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.memory.EvalCaseEntity
import io.github.artisanguillonrenov.cortana.core.memory.ImprovementProposalEntity
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard
import kotlinx.coroutines.launch

/** Improvement proposals (phase 27): the owner applies, rejects or rolls back; nothing changes on its own. */
@Composable
fun ImprovementSettingsSection() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val all by c.improvements.observe().collectAsState(initial = emptyList())
    var message by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf<List<EvalCaseEntity>>(emptyList()) }
    LaunchedEffect(all) { cases = c.improvements.evalCases() }
    fun act(block: suspend () -> String) = scope.launch { message = runCatching { block() }.getOrElse { "Refusé : ${it.message}" } }

    SectionCard("Améliorations proposées") {
        Text("Cortana analyse ses échecs, ses coûts et ses habitudes et propose des réglages, des raccourcis, des procédures ou des cas de test. Rien n'est appliqué sans vous ; chaque changement appliqué peut être annulé. Son propre code ne change que par la fabrique logicielle, avec revue et approbation.",
            style = MaterialTheme.typography.bodySmall)
        val open = all.filter { it.status == "open" }
        val applied = all.filter { it.status == "applied" }
        if (open.isEmpty()) Text("Aucune proposition en attente.", style = MaterialTheme.typography.bodySmall)
        open.forEach { p -> Proposal(p, c.improvements.change(p)?.describe()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.changeJson != null) TextButton(onClick = { act { "Appliqué : " + c.improvements.apply(p.proposalId) } }) { Text("Appliquer") }
                TextButton(onClick = { act { c.improvements.reject(p.proposalId); "Proposition écartée" } }) { Text(if (p.changeJson != null) "Refuser" else "Vu") }
            }
        } }
        if (applied.isNotEmpty()) Text("Appliquées", style = MaterialTheme.typography.titleSmall)
        applied.forEach { p -> Proposal(p, c.improvements.change(p)?.describe()) {
            TextButton(onClick = { act { c.improvements.rollback(p.proposalId) } }) { Text("Annuler ce changement") }
        } }
        if (cases.isNotEmpty()) {
            Text("Cas de test gardés (${cases.size})", style = MaterialTheme.typography.titleSmall)
            cases.forEach { k ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("« ${k.objective.take(60)} »", style = MaterialTheme.typography.bodySmall, modifier = androidx.compose.ui.Modifier.weight(1f))
                    TextButton(onClick = { act { c.improvements.deleteCase(k.caseId); cases = c.improvements.evalCases(); "Cas supprimé" } }) { Text("Supprimer") }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { act { c.improvements.analyze().let { r -> "Analyse faite : ${r.created} nouvelle(s), ${r.updated} mise(s) à jour" } } }) { Text("Analyser maintenant") }
    }
}

@Composable
private fun Proposal(p: ImprovementProposalEntity, change: String?, actions: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(p.title, style = MaterialTheme.typography.titleSmall)
        Text(p.rationale, style = MaterialTheme.typography.bodySmall)
        change?.let { Text("Changement : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text("v${p.version} · ${p.analyzer} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}
