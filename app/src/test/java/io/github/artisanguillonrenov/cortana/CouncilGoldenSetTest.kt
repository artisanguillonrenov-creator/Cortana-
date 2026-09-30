package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.council.Assessment
import io.github.artisanguillonrenov.cortana.core.council.CandidateNormalizer
import io.github.artisanguillonrenov.cortana.core.council.Claim
import io.github.artisanguillonrenov.cortana.core.council.ClaimType
import io.github.artisanguillonrenov.cortana.core.council.Concern
import io.github.artisanguillonrenov.cortana.core.council.ConcernSeverity
import io.github.artisanguillonrenov.cortana.core.council.ConfidenceFeatures
import io.github.artisanguillonrenov.cortana.core.council.CouncilAssessor
import io.github.artisanguillonrenov.cortana.core.council.CouncilConfig
import io.github.artisanguillonrenov.cortana.core.council.CouncilContribution
import io.github.artisanguillonrenov.cortana.core.council.CouncilDecision
import io.github.artisanguillonrenov.cortana.core.council.CouncilMode
import io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs
import io.github.artisanguillonrenov.cortana.core.council.CouncilSelectionInput
import io.github.artisanguillonrenov.cortana.core.council.DecisionInput
import io.github.artisanguillonrenov.cortana.core.council.DecisionProtocol
import io.github.artisanguillonrenov.cortana.core.council.DefaultDecisionEngine
import io.github.artisanguillonrenov.cortana.core.council.EvidenceLedger
import io.github.artisanguillonrenov.cortana.core.council.RuleCouncilPolicySelector
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C12 golden set (doc 11 §11.10): 52 Cortana tasks — code, research, Android, planning, business,
 * security, ambiguous — each with input, facts, allowed and forbidden actions and an oracle.
 * The selector must reach every oracle mode; on the fixtures with scripted opinions, the decision
 * layer is benchmarked against its ablations (doc 11 §11.7–11.9). Real-model quality baselines need
 * real providers and stay BLOCKED_EXTERNAL; this bench measures the deterministic decision layer.
 */
class CouncilGoldenSetTest {
    private val fixtures: List<JsonObject> = AppJson.parseToJsonElement(File("src/test/resources/council/golden.json").readText()).jsonObject["fixtures"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }

    @Test fun theGoldenSetCoversEveryDomainWithCompleteFixtures() {
        assertTrue(fixtures.size >= 50)
        assertEquals(fixtures.size, fixtures.map { it.str("id") }.toSet().size)
        val domains = fixtures.groupingBy { it.str("domain")!! }.eachCount()
        listOf("code", "research", "android", "planning", "business", "security", "ambiguous").forEach { assertTrue("$it: $domains", (domains[it] ?: 0) >= 6) }
        fixtures.forEach { f ->
            assertTrue(f.str("id"), f.str("input")!!.isNotBlank() && f["facts"] is JsonArray && f["allowedActions"] is JsonArray && f.list("forbiddenActions").isNotEmpty())
            assertTrue(f.str("id"), f["oracle"]!!.jsonObject.str("mode") != null)
        }
        assertTrue(fixtures.count { it["opinions"] != null } >= 12)
    }

    @Test fun theSelectorReachesEveryOracleMode() {
        val selector = RuleCouncilPolicySelector()
        val config = CouncilConfig(enabled = true, mode = CouncilMode.AUTO)
        val misses = fixtures.mapNotNull { f ->
            val oracle = f["oracle"]!!.jsonObject
            val want = AppJson.decodeFromString(CouncilMode.serializer(), "\"${oracle.str("mode")}\"")
            val got = selector.select(CouncilSelectionInput(f.str("input")!!, f["coding"]!!.jsonPrimitive.boolean, f["multiStep"]!!.jsonPrimitive.boolean, true, false, "chat", remainingModelCalls = 20), config, CouncilPrefs())
            val presetOk = oracle.str("preset") == null || oracle.str("preset") == got.presetId
            if (got.mode == want && presetOk) null else "${f.str("id")}: attendu ${want.name}/${oracle.str("preset")}, obtenu ${got.mode.name}/${got.presetId} (${got.reason})"
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    // ------------------------------------------------------------------ decision bench and ablations

    private data class Case(val id: String, val contributions: List<CouncilContribution>, val ledger: EvidenceLedger, val oracle: String?)

    private fun cases(): List<Case> = fixtures.filter { it["opinions"] != null }.map { f ->
        val opinions = f["opinions"]!!.jsonArray.map { it.jsonObject }
        val candidates = opinions.map { o ->
            CandidateNormalizer.candidate(o.str("target")!!, o.str("action")!!, o.str("target")!!, emptyList(), "", o.list("effects"), "low")
        }
        val contributions = opinions.mapIndexed { i, o ->
            val slot = "s$i"
            val claims = o["claims"]!!.jsonArray.mapIndexed { j, c -> Claim("$slot-r0-c$j", c.jsonObject.str("text")!!, ClaimType.FACT, c.jsonObject.list("refs")) }
            val concerns = o["concerns"]!!.jsonArray.mapIndexed { j, k ->
                val target = k.jsonObject.str("target").orEmpty()
                Concern("$slot-r0-k$j", ConcernSeverity.valueOf(k.jsonObject.str("severity")!!.uppercase()), k.jsonObject.str("text")!!,
                    targetClaimId = target.takeIf { it.startsWith("#") }?.removePrefix("#")?.split(':')?.let { (oi, ci) -> "s$oi-r0-c$ci" },
                    targetCandidateKey = when { target == "@self" -> candidates[i].key; target.startsWith("@") -> candidates[target.removePrefix("@").toInt()].key; else -> null },
                    sourceSlotId = slot)
            }
            CouncilContribution(slot, "p$i", 0, candidates[i], claims, emptyList(), concerns, ConfidenceFeatures(selfReported = 0.7),
                toolEvidence = claims.flatMap { it.evidenceRefs }.filter { it.startsWith("tool:") })
        }
        val evidence = (f["evidence"] as? JsonObject).orEmpty().mapValues { (_, v) -> v.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.boolean } }
        Case(f.str("id")!!, contributions, EvidenceLedger(evidence, f.list("facts").ifEmpty { listOf(f.str("input")!!) }), f["oracle"]!!.jsonObject.str("decision"))
    }

    private fun assess(c: Case, ledger: EvidenceLedger = c.ledger, contributions: List<CouncilContribution> = c.contributions): Assessment =
        CouncilAssessor.assess(contributions, 0, ledger, null, contributions.associate { it.slotId to "m" }, DecisionProtocol.HYBRID)

    /** The runtime's sequence: a regular decision, then the final one if nothing was selected. */
    private fun hybrid(a: Assessment): CouncilDecision {
        val d = DefaultDecisionEngine.decide(DecisionInput(DecisionProtocol.HYBRID, a, totalModels = 4))
        return if (d.selectedCandidateKey != null) d else DefaultDecisionEngine.decide(DecisionInput(DecisionProtocol.HYBRID, a, final = true, totalModels = 4))
    }

    private data class Score(val name: String, val correct: Int, val unsafe: Int, val invented: Int, val total: Int, val misses: List<String> = emptyList())

    private fun score(name: String, cases: List<Case>, pick: (Case) -> Pair<Assessment, String?>): Score {
        var correct = 0; var unsafe = 0; var invented = 0
        val misses = mutableListOf<String>()
        for (c in cases) {
            val (reference, key) = pick(c)
            val truth = assess(c) // safety is judged on the full information, whatever the configuration saw
            val selected = reference.cluster(key)
            val target = selected?.candidate?.target
            val want = c.oracle?.let { CandidateNormalizer.tokens(it).sorted().joinToString(" ") }
            if (target == want) correct++ else misses += "${c.id} → ${target ?: "aucune"} (attendu ${want ?: "aucune"})"
            if (selected != null && truth.cluster(selected.key)?.let { it.criticalAgainst.isNotEmpty() || it.contradicted } == true) unsafe++
            if (c.oracle == null && selected != null) invented++
        }
        return Score(name, correct, unsafe, invented, cases.size, misses)
    }

    @Test fun decisionBenchAndAblations() {
        val cases = cases()
        val b4 = score("B4 hybride : votes + preuves + objections (moteur livré)", cases) { c -> assess(c).let { it to hybrid(it).selectedCandidateKey } }
        val b2 = score("B2 majorité simple (le vote seul)", cases) { c ->
            assess(c).let { a -> a to DefaultDecisionEngine.decide(DecisionInput(DecisionProtocol.SIMPLE_MAJORITY, a.let { x -> x.copy(clusters = x.clusters.map { it.copy(criticalAgainst = emptyList()) }) }, totalModels = 4)).selectedCandidateKey }
        }
        val noEvidence = score("Ablation : sans registre de preuves", cases) { c -> assess(c, EvidenceLedger()).let { it to hybrid(it).selectedCandidateKey } }
        val noConcerns = score("Ablation : sans objections", cases) { c -> assess(c, contributions = c.contributions.map { it.copy(concerns = emptyList()) }).let { it to hybrid(it).selectedCandidateKey } }
        val noVote = score("Ablation : sans vote (preuves seules)", cases) { c -> assess(c).let { a -> a to a.clusters.maxByOrNull { it.evidenceScore }?.key } }
        val all = listOf(b4, b2, noEvidence, noConcerns, noVote)
        val report = buildString {
            appendLine("# Banc du conseil — couche de décision (déterministe, ${cases.size} cas du jeu de référence)")
            appendLine()
            appendLine("| Configuration | Exactes | Dangereuses | Décisions inventées |")
            appendLine("|---|---|---|---|")
            all.forEach { appendLine("| ${it.name} | ${it.correct}/${it.total} | ${it.unsafe} | ${it.invented} |") }
            appendLine()
            all.filter { it.misses.isNotEmpty() }.forEach { sc -> appendLine("- ${sc.name} : " + sc.misses.joinToString(" ; ")) }
            appendLine()
            appendLine("B0 (modèle seul), B1, B3, B5, B6 (juge) et B7 (Auto) mesurent la qualité de modèles réels : BLOCKED_EXTERNAL (fournisseurs réels requis).")
        }
        File("build/reports").mkdirs()
        File("build/reports/council-benchmark.md").writeText(report)
        println(report)
        // The shipped engine gets every oracle, never selects a blocked or contradicted candidate, never invents a winner.
        assertEquals(report, b4.total, b4.correct)
        assertEquals(report, 0, b4.unsafe); assertEquals(report, 0, b4.invented)
        // A majority is not proof: the plain vote selects unsafe candidates the shipped engine rejects.
        assertTrue(report, b2.unsafe > 0 && b2.correct < b4.correct)
        // Each ablated component earns its place.
        assertTrue(report, noEvidence.correct < b4.correct)
        assertTrue(report, noConcerns.correct < b4.correct && noConcerns.unsafe > 0)
        assertTrue(report, noVote.correct < b4.correct)
    }
}
