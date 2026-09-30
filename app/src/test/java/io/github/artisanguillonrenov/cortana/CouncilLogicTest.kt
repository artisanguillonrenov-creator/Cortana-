package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.context.Tokens
import io.github.artisanguillonrenov.cortana.core.council.ArgumentKind
import io.github.artisanguillonrenov.cortana.core.council.Assessment
import io.github.artisanguillonrenov.cortana.core.council.CandidateNormalizer
import io.github.artisanguillonrenov.cortana.core.council.Claim
import io.github.artisanguillonrenov.cortana.core.council.ClaimType
import io.github.artisanguillonrenov.cortana.core.council.Concern
import io.github.artisanguillonrenov.cortana.core.council.ConcernSeverity
import io.github.artisanguillonrenov.cortana.core.council.ConfidenceFeatures
import io.github.artisanguillonrenov.cortana.core.council.ContributionParser
import io.github.artisanguillonrenov.cortana.core.council.CouncilAgentProfile
import io.github.artisanguillonrenov.cortana.core.council.CouncilAgentSlot
import io.github.artisanguillonrenov.cortana.core.council.AgentBudget
import io.github.artisanguillonrenov.cortana.core.council.CouncilAssessor
import io.github.artisanguillonrenov.cortana.core.council.CouncilConfig
import io.github.artisanguillonrenov.cortana.core.council.CouncilContribution
import io.github.artisanguillonrenov.cortana.core.council.CouncilMode
import io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs
import io.github.artisanguillonrenov.cortana.core.council.CouncilRunStatus
import io.github.artisanguillonrenov.cortana.core.council.CouncilSelectionInput
import io.github.artisanguillonrenov.cortana.core.council.CouncilTopology
import io.github.artisanguillonrenov.cortana.core.council.DecisionInput
import io.github.artisanguillonrenov.cortana.core.council.DecisionProtocol
import io.github.artisanguillonrenov.cortana.core.council.DefaultCouncilProfileRegistry
import io.github.artisanguillonrenov.cortana.core.council.DefaultCouncilRetainer
import io.github.artisanguillonrenov.cortana.core.council.DefaultDecisionEngine
import io.github.artisanguillonrenov.cortana.core.council.EvidenceLedger
import io.github.artisanguillonrenov.cortana.core.council.EvidenceStatus
import io.github.artisanguillonrenov.cortana.core.council.JudgeVerdict
import io.github.artisanguillonrenov.cortana.core.council.ParseResult
import io.github.artisanguillonrenov.cortana.core.council.ProfileValidationException
import io.github.artisanguillonrenov.cortana.core.council.RetentionConfig
import io.github.artisanguillonrenov.cortana.core.council.RetentionInput
import io.github.artisanguillonrenov.cortana.core.council.RoleModelConfig
import io.github.artisanguillonrenov.cortana.core.council.RuleCouncilPolicySelector
import io.github.artisanguillonrenov.cortana.core.tools.SchemaValidator
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Council pure logic (docs/council_pack C1, C2, C4, C5, C6 and the selector): deterministic fixtures, no model. */
class CouncilLogicTest {
    // ------------------------------------------------------------------ C1 contracts + flag

    @Test fun c1ConfigMatchesTheSchemaAndTheFlagDefaultsOff() {
        val schema = AppJson.parseToJsonElement(File("../docs/council_pack/council_engine_config.schema.json").readText()).jsonObject
        val default = CouncilConfig()
        assertFalse(default.enabled)
        assertEquals(CouncilMode.OFF, default.effectiveMode)
        assertEquals(emptyList<String>(), SchemaValidator.validate(schema, default.toSchemaJson(), "config"))
        val custom = default.copy(enabled = true, mode = CouncilMode.CUSTOM, maxAgents = 16, roles = listOf(RoleModelConfig("challenger", "p2", "m2", listOf("p2/m3"), localOnly = true, maxOutputTokens = 800, timeoutMs = 60_000)))
        assertEquals(CouncilMode.CUSTOM, custom.effectiveMode)
        assertEquals(emptyList<String>(), SchemaValidator.validate(schema, custom.toSchemaJson(), "config"))
        // The schema rejects what the engine must never accept.
        assertTrue(SchemaValidator.validate(schema, default.copy(maxAgents = 17).toSchemaJson(), "config").isNotEmpty())
        assertTrue(SchemaValidator.validate(schema, default.copy(maxRounds = 6).toSchemaJson(), "config").isNotEmpty())
        assertTrue(SchemaValidator.validate(schema, default.copy(retention = default.retention.copy(alwaysKeepCritical = false)).toSchemaJson(), "config").isNotEmpty())
        val stored = AppJson.encodeToString(CouncilConfig.serializer(), custom)
        assertEquals(custom, AppJson.decodeFromString(CouncilConfig.serializer(), stored))
    }

    @Test fun c1StateMachineFollowsDoc01() {
        val happy = listOf(CouncilRunStatus.CREATED, CouncilRunStatus.PLANNING, CouncilRunStatus.RESOLVING_MODELS, CouncilRunStatus.PREPARING_CONTEXT, CouncilRunStatus.ROUND_INITIAL,
            CouncilRunStatus.ASSESSING, CouncilRunStatus.ROUND_CRITIQUE, CouncilRunStatus.ASSESSING, CouncilRunStatus.DECIDING, CouncilRunStatus.CHALLENGING,
            CouncilRunStatus.SYNTHESIZING, CouncilRunStatus.VERIFYING, CouncilRunStatus.COMPLETED)
        happy.zipWithNext().forEach { (a, b) -> assertTrue("$a → $b", CouncilRunStatus.allowed(a, b)) }
        assertFalse(CouncilRunStatus.allowed(CouncilRunStatus.ROUND_INITIAL, CouncilRunStatus.COMPLETED)) // COMPLETED needs a decision
        assertFalse(CouncilRunStatus.allowed(CouncilRunStatus.CREATED, CouncilRunStatus.SYNTHESIZING))
        CouncilRunStatus.entries.filter { !it.terminal }.forEach { s -> assertTrue(CouncilRunStatus.allowed(s, CouncilRunStatus.CANCELLED)); assertTrue(CouncilRunStatus.allowed(s, CouncilRunStatus.PARTIAL)) }
        CouncilRunStatus.entries.filter { it.terminal }.forEach { t -> CouncilRunStatus.entries.forEach { assertFalse(CouncilRunStatus.allowed(t, it)) } }
    }

    // ------------------------------------------------------------------ C2 profiles

    @Test fun c2ProfilesAreValidatedDuplicatesCapabilitiesAndFallbacks() {
        val reg = DefaultCouncilProfileRegistry()
        listOf("strategist", "evidence_analyst", "solution_engineer", "challenger", "judge", "synthesizer").forEach { assertTrue(it, reg.get(it) != null) }
        reg.presets().forEach { p -> p.agents.forEach { assertTrue("${p.id}:$it", reg.get(it) != null) } }
        assertEquals(4, reg.preset("balanced")!!.agents.size)
        // Duplicate profile.
        val dup = runCatching { DefaultCouncilProfileRegistry(listOf(reg.get("strategist")!!)) }.exceptionOrNull()
        assertTrue(dup is ProfileValidationException && dup.message!!.contains("profil en double : strategist"))
        // Invalid capability (from configuration strings) and a judge with tools.
        val (caps, errors) = DefaultCouncilProfileRegistry.parseCapabilities(listOf("json", "telepathy"))
        assertEquals(1, caps.size); assertEquals(listOf("capacité inconnue : telepathy"), errors)
        val badJudge = CouncilAgentProfile("judge2", "J", "m", "b", listOf("o"), toolCategories = setOf(io.github.artisanguillonrenov.cortana.core.tools.ToolCategory.WEB), kind = io.github.artisanguillonrenov.cortana.core.council.ProfileKind.JUDGE)
        assertTrue(DefaultCouncilProfileRegistry.validate(listOf(badJudge)).any { it.contains("lecture seule") })
        assertTrue(DefaultCouncilProfileRegistry.validate(listOf(reg.get("strategist")!!.copy(id = "x1", capabilities = emptySet()))).any { it.contains("JSON") })
        // Fallback configuration.
        val roleErrors = reg.validateRoles(listOf(
            RoleModelConfig("challenger", "p1", "m", listOf("p9/m2", "bad model", "/m")),
            RoleModelConfig("challenger"),
            RoleModelConfig("oracle"),
            RoleModelConfig("strategist", fallbackModelIds = listOf("m-only")),
        ), setOf("p1", "p2"))
        assertTrue(roleErrors.toString(), roleErrors.any { it.contains("fournisseur inconnu « p9 »") })
        assertTrue(roleErrors.any { it.contains("repli mal formé « bad model »") } && roleErrors.any { it.contains("« /m »") })
        assertTrue(roleErrors.any { it.contains("deux fois : challenger") } && roleErrors.any { it.contains("profil inconnu : oracle") })
        assertTrue(roleErrors.any { it.contains("sans fournisseur de référence") })
        assertEquals(emptyList<String>(), reg.validateRoles(listOf(RoleModelConfig("challenger", "p1", "m", listOf("p2/m2", "m3"))), setOf("p1", "p2")))
    }

    // ------------------------------------------------------------------ C4 structured contribution

    @Test fun c4ParserIsStrictBoundedSanitisedAndFingerprinted() {
        val ok = ContributionParser.parse("""Voici : {"candidate":{"summary":"Utiliser SQLite","action":"utiliser","target":"base SQLite locale","risk":"low"},
            "claims":[{"text":"SQLite suffit\u0007 pour un utilisateur","type":"fact","confidence":1.7,"evidenceRefs":["tool:1"]}],
            "concerns":[{"severity":"high","text":"sauvegardes","targetClaim":0}],"shortRationale":"court","confidence":0.8,
            "details":{"planSteps":["a","b"],"inconnu":["x"]}}""", "s1", "strategist", 0, setOf("planSteps"))
        assertTrue("$ok", ok is ParseResult.Valid)
        val c = (ok as ParseResult.Valid).contribution
        assertEquals("SQLite suffit pour un utilisateur", c.claims.single().text) // control character removed
        assertEquals(1.0, c.claims.single().confidence!!, 0.0) // clamped
        assertEquals(c.claims.single().id, c.concerns.single().targetClaimId)
        assertEquals(mapOf("planSteps" to listOf("a", "b")), c.details) // unknown detail keys dropped
        assertEquals(0.8, c.confidence.selfReported!!, 0.0)
        // A tool call smuggled into the output is a rejection, never executed (doc 17 §17.3).
        val smuggled = ContributionParser.parse("""{"candidate":{"summary":"x"},"toolCall":{"name":"sms_send","arguments":{}}}""", "s1", "p", 0, emptySet())
        assertTrue(smuggled is ParseResult.Invalid && smuggled.errors.contains("champ non autorisé : toolCall"))
        assertTrue(ContributionParser.parse("""{"candidate":{"summary":"x","exec":"rm -rf"}}""", "s1", "p", 0, emptySet()) is ParseResult.Invalid)
        assertTrue(ContributionParser.parse("pas de JSON", "s1", "p", 0, emptySet()) is ParseResult.Invalid)
        assertTrue(ContributionParser.parse("""{"claims":[{"text":"a","type":"rumeur"}]}""", "s1", "p", 0, emptySet()) is ParseResult.Invalid)
        // Limits: at most 12 claims, bounded text; secrets masked.
        val many = (1..20).joinToString(",") { """{"text":"claim $it","type":"inference"}""" }
        val bounded = ContributionParser.parse("""{"candidate":{"summary":"${"z".repeat(5_000)}"},"claims":[$many],"shortRationale":"clé sk-abcdefghijklmnopqrstuvwxyz0123"}""", "s1", "p", 0, emptySet()) as ParseResult.Valid
        assertEquals(12, bounded.contribution.claims.size)
        assertEquals(2_000, bounded.contribution.candidate!!.summary.length)
        assertTrue(bounded.truncated)
        assertFalse(bounded.contribution.rationaleSummary.contains("sk-abcdefghijklmnopqrstuvwxyz0123"))
        // Fingerprints: synonyms and accents normalised; different effects or risks never merged.
        val a = CandidateNormalizer.candidate("Installer l'application Météo", "installer", "application Météo", emptyList(), "", emptyList(), "low")
        val b = CandidateNormalizer.candidate("Ajoute l'app météo", "ajouter", "l'application meteo", emptyList(), "", emptyList(), "low")
        assertEquals(a.key, b.key)
        val c2 = CandidateNormalizer.candidate("Installer Météo", "installer", "application Météo", emptyList(), "", listOf("android.app.install"), "low")
        assertNotEquals(a.key, c2.key); assertFalse(CandidateNormalizer.equivalent(a, c2))
        assertFalse(CandidateNormalizer.equivalent(a, a.copy(key = "other", risk = "high")))
    }

    // ------------------------------------------------------------------ C5 decisions

    private var n = 0
    private fun claim(slot: String, text: String, type: ClaimType = ClaimType.FACT, refs: List<String> = emptyList()) = Claim("$slot-c${n++}", text, type, refs)
    private fun contrib(slot: String, summary: String, target: String = summary, claims: List<Claim> = emptyList(), concerns: List<Concern> = emptyList(),
                        effects: List<String> = emptyList(), ranking: List<String> = emptyList(), tool: List<String> = emptyList(), conf: Double = 0.7) =
        CouncilContribution(slot, "p", 0, CandidateNormalizer.candidate(summary, "utiliser", target, emptyList(), "", effects, "low"), claims, emptyList(), concerns,
            ConfidenceFeatures(selfReported = conf), details = if (ranking.isEmpty()) emptyMap() else mapOf(ContributionParser.RANKING to ranking), toolEvidence = tool)

    private fun assess(list: List<CouncilContribution>, ledger: EvidenceLedger = EvidenceLedger(userFacts = listOf("le propriétaire est seul")), previous: Assessment? = null, protocol: DecisionProtocol = DecisionProtocol.HYBRID) =
        CouncilAssessor.assess(list, 0, ledger, previous, list.associate { it.slotId to "m-${it.slotId}" }, protocol)

    private fun decide(a: Assessment, protocol: DecisionProtocol, final: Boolean = false, judge: JudgeVerdict? = null) =
        DefaultDecisionEngine.decide(DecisionInput(protocol, a, judge = judge, final = final, totalModels = 4))

    private fun majorityFixture(): List<CouncilContribution> = listOf(
        contrib("s0", "Utiliser PostgreSQL", "postgresql serveur", listOf(claim("s0", "le propriétaire est seul", refs = listOf("user")))),
        contrib("s1", "Utiliser PostgreSQL", "postgresql serveur", listOf(claim("s1", "données relationnelles", refs = listOf("user")))),
        contrib("s2", "Utiliser PostgreSQL", "serveur postgresql", listOf(claim("s2", "besoin de requêtes", refs = listOf("user")))),
        contrib("s3", "Utiliser SQLite", "sqlite fichier", listOf(claim("s3", "un seul utilisateur", refs = listOf("user")))),
    )

    @Test fun c5ProtocolsAreDeterministicAndMajorityIsNeverProof() {
        val a = assess(majorityFixture())
        val pg = a.clusters.first { it.candidate.summary.contains("PostgreSQL") }.key
        val lite = a.clusters.first { it.candidate.summary.contains("SQLite") }.key
        assertEquals(0.75, a.agreement, 1e-9)
        assertEquals(pg, decide(a, DecisionProtocol.SIMPLE_MAJORITY).selectedCandidateKey)
        assertEquals(pg, decide(a, DecisionProtocol.SUPERMAJORITY).selectedCandidateKey)
        assertEquals("no_unanimity", decide(a, DecisionProtocol.UNANIMITY).unresolvedReason)
        assertEquals(pg, decide(a, DecisionProtocol.HYBRID).selectedCandidateKey)
        assertEquals(pg, decide(a, DecisionProtocol.APPROVAL).selectedCandidateKey)
        assertEquals(pg, decide(a, DecisionProtocol.RANKED).selectedCandidateKey)
        assertEquals(pg, decide(a, DecisionProtocol.CUMULATIVE).selectedCandidateKey)
        assertEquals(pg, decide(a, DecisionProtocol.MAJORITY_CONSENSUS).selectedCandidateKey)
        // Identical inputs → identical decision (doc 13 §13.13).
        assertEquals(decide(a, DecisionProtocol.HYBRID), decide(a, DecisionProtocol.HYBRID))
        // An unresolved critical concern blocks every protocol; the final hybrid falls back to the unblocked alternative.
        val critical = majorityFixture().toMutableList().also { it[3] = it[3].copy(concerns = listOf(Concern("k1", ConcernSeverity.CRITICAL, "perte de données", targetCandidateKey = pg, sourceSlotId = "s3"))) }
        val b = assess(critical)
        listOf(DecisionProtocol.SIMPLE_MAJORITY, DecisionProtocol.SUPERMAJORITY, DecisionProtocol.RANKED, DecisionProtocol.HYBRID).forEach { p ->
            assertEquals(p.name, "critical_concern", decide(b, p).unresolvedReason)
        }
        val fin = decide(b, DecisionProtocol.HYBRID, final = true)
        assertEquals(lite, fin.selectedCandidateKey)
        assertTrue(fin.overturnedMajority)
    }

    @Test fun c5ToolVerifiedEvidenceOverturnsAFalseConsensus() {
        // 3 agents assert X without evidence; 1 agent brings tool-verified non-X and contradicts their claim (doc 17 §17.3).
        val x = listOf("s0", "s1", "s2").map { s -> contrib(s, "La tablette a 128 Go", "stockage 128", listOf(claim(s, "la tablette a 128 Go de stockage"))) }
        val targetClaim = x[0].claims.single().id
        val minority = contrib("s3", "La tablette a 64 Go", "stockage 64", listOf(claim("s3", "le stockage mesuré est de 64 Go", refs = listOf("tool:1"))),
            concerns = listOf(Concern("k", ConcernSeverity.HIGH, "contredit par la mesure", targetClaimId = targetClaim, sourceSlotId = "s3")), tool = listOf("tool:1 system.storage : 64 Go"))
        val ledger = EvidenceLedger(mapOf("tool:1" to ("system.storage : 64 Go" to false)))
        val a = assess(x + minority, ledger)
        assertEquals(EvidenceStatus.TOOL_VERIFIED, a.claimStatuses[minority.claims.single().id])
        assertEquals(EvidenceStatus.CONTRADICTED, a.claimStatuses[targetClaim])
        val d = decide(a, DecisionProtocol.HYBRID)
        assertEquals(minority.candidate!!.key, d.selectedCandidateKey)
        assertTrue(d.overturnedMajority)
        assertTrue(d.minorityReport.isEmpty() || d.minorityReport.none { it.contains("64 Go") })
        // A MODEL_ONLY consensus never gets a high confidence.
        val modelOnly = assess(x)
        assertTrue(modelOnly.evidenceCoverage < 0.3)
    }

    @Test fun c5TiesAreNeverInventedAndTheJudgeIsBlindToVotes() {
        val tie = listOf(contrib("s0", "Utiliser Kotlin", "kotlin"), contrib("s1", "Utiliser Kotlin", "kotlin"), contrib("s2", "Utiliser Rust", "rust"), contrib("s3", "Utiliser Rust", "rust"))
        val a = assess(tie)
        val d = decide(a, DecisionProtocol.HYBRID, final = true)
        assertNull(d.selectedCandidateKey); assertEquals("tie", d.unresolvedReason)
        val rust = a.clusters.first { it.candidate.target == "rust" }.key
        val kotlin = a.clusters.first { it.candidate.target == "kotlin" }.key
        assertEquals(rust, decide(a, DecisionProtocol.JUDGE, judge = JudgeVerdict(listOf(rust, kotlin), preferred = rust)).selectedCandidateKey)
        assertEquals(kotlin, decide(a, DecisionProtocol.BLIND_JUDGE_THEN_VOTE, final = true, judge = JudgeVerdict(listOf(kotlin, rust), preferred = kotlin)).selectedCandidateKey)
        // A disqualified candidate is never selected.
        assertEquals(rust, decide(a, DecisionProtocol.RANKED, judge = JudgeVerdict(emptyList(), disqualified = setOf(kotlin))).selectedCandidateKey)
        // Ranked ballots from a critique round (Borda).
        val ranked = listOf(contrib("s0", "Utiliser Kotlin", "kotlin", ranking = listOf(kotlin, rust)), contrib("s1", "Utiliser Rust", "rust", ranking = listOf(rust, kotlin)),
            contrib("s2", "Utiliser Rust", "rust", ranking = listOf(rust, kotlin)))
        assertEquals(rust, decide(assess(ranked), DecisionProtocol.RANKED).selectedCandidateKey)
    }

    // ------------------------------------------------------------------ C6 retention

    private fun slot(id: String) = CouncilAgentSlot(id, "p", null, budget = AgentBudget(8_000, 900, 60_000, 0, 2))

    private fun richFixture(): List<CouncilContribution> {
        val base = majorityFixture()
        return base.mapIndexed { i, c ->
            c.copy(claims = c.claims + (1..4).map { k -> claim(c.slotId, "argument $k de ${c.slotId} sur la solution ${if (i < 3) "serveur" else "fichier"} avec des détails ${"x".repeat(k * 30)}", ClaimType.INFERENCE) },
                concerns = if (i == 3) listOf(Concern("crit", ConcernSeverity.CRITICAL, "risque critique : perte de données sans sauvegarde", sourceSlotId = c.slotId),
                    Concern("m", ConcernSeverity.MEDIUM, "coût de maintenance", sourceSlotId = c.slotId)) else listOf(Concern("l$i", ConcernSeverity.LOW, "détail mineur $i", sourceSlotId = c.slotId)))
        }
    }

    @Test fun c6RetentionKeepsCriticalBoundsTrafficAndProtectsTheMinority() {
        val contributions = richFixture()
        val a = assess(contributions)
        val targets = contributions.map { slot(it.slotId) }
        val plan = DefaultCouncilRetainer.select(RetentionInput(contributions, a, targets, CouncilTopology.FULL_MESH, RetentionConfig(maxArgumentsPerTarget = 4, maxTokensPerTarget = 400), highDivergence = 0.0))
        for (t in targets) {
            val args = plan.perTarget[t.id]!!
            assertTrue("no own argument", args.none { it.sourceSlotId == t.id })
            if (t.id != "s3") assertTrue("critical kept for ${t.id}", args.any { it.kind == ArgumentKind.CRITICAL_CONCERN })
            val optional = args.filter { it.kind != ArgumentKind.CRITICAL_CONCERN }
            assertTrue(optional.size <= 4)
            assertTrue(optional.groupBy { it.sourceSlotId }.values.all { it.size <= 2 })
            if (t.id != "s3") assertTrue("minority point for ${t.id}", args.any { it.sourceSlotId == "s3" })
        }
        // Retention reduces the inter-agent traffic compared with forwarding every contribution to everyone.
        val full = targets.sumOf { t -> contributions.filter { it.slotId != t.id }.sumOf { c -> Tokens.estimate(c.claims.joinToString { it.text } + c.concerns.joinToString { it.text } + c.candidate!!.summary) } }
        val kept = plan.perTarget.values.sumOf { l -> l.sumOf { it.tokenEstimate } }
        assertTrue("$kept < $full", kept < full * 0.6)
        // Ring: only neighbours (plus critical concerns); independent: nothing but critical concerns.
        val ring = DefaultCouncilRetainer.select(RetentionInput(contributions, a, targets, CouncilTopology.RING, RetentionConfig()))
        assertTrue(ring.perTarget["s0"]!!.filter { it.kind != ArgumentKind.CRITICAL_CONCERN }.all { it.sourceSlotId in setOf("s1", "s3") })
        val alone = DefaultCouncilRetainer.select(RetentionInput(contributions, a, targets, CouncilTopology.INDEPENDENT, RetentionConfig()))
        assertTrue(alone.perTarget["s1"]!!.all { it.kind == ArgumentKind.CRITICAL_CONCERN })
        // Even a tiny budget never drops a critical objection.
        val tiny = DefaultCouncilRetainer.select(RetentionInput(contributions, a, targets, CouncilTopology.FULL_MESH, RetentionConfig(maxArgumentsPerTarget = 1, maxTokensPerTarget = 128)))
        assertTrue(tiny.perTarget.filterKeys { it != "s3" }.values.all { l -> l.any { it.kind == ArgumentKind.CRITICAL_CONCERN } })
    }

    // ------------------------------------------------------------------ selector

    @Test fun selectorKeepsTrivialRequestsOffAndMapsAutoRules() {
        val sel = RuleCouncilPolicySelector()
        val on = CouncilConfig(enabled = true, mode = CouncilMode.AUTO)
        val prefs = CouncilPrefs()
        fun pick(text: String, coding: Boolean = false, config: CouncilConfig = on, battery: Int? = null, calls: Int = 20, inside: Boolean = false) =
            sel.select(CouncilSelectionInput(text, coding, false, true, false, "chat", battery, calls, inside), config, prefs)
        assertEquals(CouncilMode.OFF, pick("Compare PostgreSQL et SQLite pour mon application", config = CouncilConfig()).mode) // flag off
        listOf("Bonjour !", "Mets le volume à 5", "Rappelle-moi dans 5 minutes de boire", "Convertis 10 miles en km", "ouvre YouTube").forEach {
            assertEquals(it, CouncilMode.OFF, pick(it).mode)
        }
        assertEquals(CouncilMode.OFF, pick("Compare PostgreSQL et SQLite", inside = true).mode) // no recursion
        assertEquals(CouncilMode.REINFORCED, pick("Compare PostgreSQL et SQLite pour mon application de notes").mode)
        assertEquals("code_review", pick("Pourquoi mon build Gradle échoue sur la tâche kapt alors que le module compile seul ? Voici le log complet…", coding = true).presetId)
        assertEquals(CouncilMode.DEEP, pick("Analyse en profondeur la migration de mon serveur vers un autre hébergeur").mode)
        assertEquals("quality", pick("Dois-je chiffrer les sauvegardes de ma base de données personnelles, avec quel outil, sans ralentir l'app, en gardant la restauration simple ?").presetId)
        // Degradations: battery, remaining task budget.
        val deep = "Analyse en profondeur la migration de mon serveur vers un autre hébergeur"
        assertEquals(CouncilMode.COUNCIL_4, pick(deep, battery = 10).mode)
        assertEquals(CouncilMode.REINFORCED, pick(deep, calls = 5).mode)
        assertEquals(CouncilMode.OFF, pick(deep, calls = 2).mode)
        // An explicit mode is honoured (non-trivial requests), with the domain preset in Conseil 4.
        assertEquals("research", sel.select(CouncilSelectionInput("Que disent les études récentes sur le sommeil et les écrans ?", false, false, true, false, "chat"), on.copy(mode = CouncilMode.COUNCIL_4), prefs).presetId)
        // Accented and upper-case words match the same way on the JVM and on Android's ICU engine.
        assertEquals("research", sel.domainPreset("ÉTUDES sur le sommeil", false))
        assertEquals("business", sel.domainPreset("Analyse du marché local", false))
        assertEquals("quality", pick("SÉCURITÉ : dois-je activer le chiffrement des sauvegardes, avec une clé matérielle, sans perdre l'accès, mais simplement ?").presetId)
        assertEquals("balanced", sel.domainPreset("Écris un texte sur les marchés de Noël", false)) // "marchés" ≠ "marché"
    }

    /** Regression (found by DocumentTest): a type list accepts a value of any listed type, not only the first. */
    @Test fun schemaValidatorTypeListsConstAndUntypedEnums() {
        fun schema(j: String) = io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(j) as kotlinx.serialization.json.JsonObject
        val v = io.github.artisanguillonrenov.cortana.core.tools.SchemaValidator
        val cell = schema("""{"type":["string","number"]}""")
        assertTrue(v.validate(cell, kotlinx.serialization.json.JsonPrimitive(120)).isEmpty())
        assertTrue(v.validate(cell, kotlinx.serialization.json.JsonPrimitive("=B2-C2")).isEmpty())
        assertFalse(v.validate(cell, kotlinx.serialization.json.JsonArray(emptyList())).isEmpty())
        val nullable = schema("""{"type":["integer","null"],"minimum":0}""")
        assertTrue(v.validate(nullable, kotlinx.serialization.json.JsonNull).isEmpty())
        assertTrue(v.validate(nullable, kotlinx.serialization.json.JsonPrimitive(3)).isEmpty())
        assertFalse(v.validate(nullable, kotlinx.serialization.json.JsonPrimitive(-1)).isEmpty())
        assertFalse(v.validate(schema("""{"type":"string"}"""), kotlinx.serialization.json.JsonNull).isEmpty())
        assertTrue(v.validate(schema("""{"const":true}"""), kotlinx.serialization.json.JsonPrimitive(true)).isEmpty())
        assertFalse(v.validate(schema("""{"const":true}"""), kotlinx.serialization.json.JsonPrimitive(false)).isEmpty())
        assertTrue(v.validate(schema("""{"enum":["auto","low"]}"""), kotlinx.serialization.json.JsonPrimitive("low")).isEmpty())
        assertFalse(v.validate(schema("""{"enum":["auto","low"]}"""), kotlinx.serialization.json.JsonPrimitive("max")).isEmpty())
    }
}
