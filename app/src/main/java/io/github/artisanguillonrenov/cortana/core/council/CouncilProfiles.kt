package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory

enum class ProfileKind { AGENT, JUDGE, SYNTHESIZER }

/** A validated cognitive profile (doc 03 §3.1). Tool scope is by category and always read-only (the planner enforces it). */
data class CouncilAgentProfile(
    val id: String,
    val role: String,
    val mission: String,
    val usefulBias: String,
    val objectives: List<String>,
    /** Role-specific output lists the parser accepts in `details` (doc 14 §14.4–14.7). */
    val outputFields: List<String> = emptyList(),
    val toolCategories: Set<ToolCategory> = emptySet(),
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.JSON),
    val preferredModel: String? = null,
    val fallbacks: List<String> = emptyList(),
    val maxOutputTokens: Int = 900,
    val maxToolCalls: Int = 3,
    /** Relevant memory facts in the brief (never the whole memory, never a write). */
    val readsMemory: Boolean = true,
    val successCriteria: List<String> = emptyList(),
    val kind: ProfileKind = ProfileKind.AGENT,
    val forbidden: List<String> = emptyList(),
)

/** A preset (doc 16): roles, rounds, protocol — one runtime, many desks. */
data class CouncilPreset(
    val id: String,
    val label: String,
    val agents: List<String>,
    val rounds: Int,
    val topology: CouncilTopology,
    val protocol: DecisionProtocol,
    val challenge: Boolean,
    val judge: JudgeSetting,
    val retention: Boolean = true,
    val maxArgumentsPerTarget: Int = 6,
    val maxConcurrent: Int = 4,
    /** Minimum evidence coverage before the decision is considered well supported. */
    val minEvidenceCoverage: Double = 0.3,
    val strictVerifier: Boolean = false,
    /** Diversity first: weak consensus is acceptable, convergence is not forced (creative). */
    val diversityFirst: Boolean = false,
    /** Evidence-weighted decisions: an important claim without evidence stays explicitly unverified (research). */
    val evidenceWeighted: Boolean = false,
    /** Above this projected token count the owner must confirm (DEEP). */
    val confirmAboveTokens: Int? = null,
)

class ProfileValidationException(val errors: List<String>) : Exception(errors.joinToString(" ; "))

class DefaultCouncilProfileRegistry(extra: List<CouncilAgentProfile> = emptyList()) : CouncilProfileRegistry {
    private val profiles: Map<String, CouncilAgentProfile>

    init {
        val all = STANDARD + DYNAMIC + INTERNAL + extra
        val errors = validate(all)
        if (errors.isNotEmpty()) throw ProfileValidationException(errors)
        profiles = all.associateBy { it.id }
    }

    override fun get(profileId: String): CouncilAgentProfile? = profiles[profileId]
    override fun list(): List<CouncilAgentProfile> = profiles.values.toList()
    fun agents(): List<CouncilAgentProfile> = profiles.values.filter { it.kind == ProfileKind.AGENT }

    fun preset(id: String): CouncilPreset? = PRESETS[id]
    fun presets(): List<CouncilPreset> = PRESETS.values.toList()

    /** Owner role configuration checks (doc 10 C2): unknown profile, duplicate role, malformed or unknown fallback, bad limits. */
    fun validateRoles(roles: List<RoleModelConfig>, knownProviders: Set<String>): List<String> = buildList {
        roles.groupBy { it.profileId }.filter { it.value.size > 1 }.keys.forEach { add("rôle configuré deux fois : $it") }
        for (r in roles) {
            if (r.profileId !in profiles) add("profil inconnu : ${r.profileId}")
            if (r.providerId != null && r.providerId !in knownProviders) add("${r.profileId} : fournisseur inconnu ${r.providerId}")
            if (r.modelId != null && r.providerId == null) add("${r.profileId} : un modèle exige son fournisseur")
            r.fallbackModelIds.forEach { f ->
                val parts = f.split('/', limit = 2)
                when {
                    f.isBlank() || f.contains(' ') -> add("${r.profileId} : repli mal formé « $f »")
                    parts.size == 2 && (parts[0].isBlank() || parts[1].isBlank()) -> add("${r.profileId} : repli mal formé « $f »")
                    parts.size == 2 && parts[0] !in knownProviders -> add("${r.profileId} : repli vers un fournisseur inconnu « ${parts[0]} »")
                    parts.size == 1 && r.providerId == null -> add("${r.profileId} : repli « $f » sans fournisseur de référence")
                }
            }
            if ((r.maxInputTokens ?: 1) < 1 || (r.maxOutputTokens ?: 1) < 1) add("${r.profileId} : limites de jetons invalides")
            if ((r.timeoutMs ?: 1_000) < 1_000) add("${r.profileId} : délai inférieur à 1 s")
        }
    }

    companion object {
        /** Profiles from strings (owner configuration, desks): an unknown capability is an error, never ignored. */
        fun parseCapabilities(names: List<String>): Pair<Set<ModelCapability>, List<String>> {
            val ok = mutableSetOf<ModelCapability>()
            val bad = mutableListOf<String>()
            for (n in names) ModelCapability.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) }?.let { ok += it } ?: run { bad += "capacité inconnue : $n" }
            return ok to bad
        }

        fun validate(profiles: List<CouncilAgentProfile>): List<String> = buildList {
            profiles.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("profil en double : $it") }
            for (p in profiles) {
                if (!Regex("[a-z][a-z0-9_]{1,40}").matches(p.id)) add("identifiant de profil invalide : ${p.id}")
                if (p.mission.isBlank() || p.objectives.isEmpty()) add("${p.id} : mission et objectifs requis")
                if (p.kind != ProfileKind.SYNTHESIZER && ModelCapability.JSON !in p.capabilities) add("${p.id} : une sortie structurée (JSON) est requise")
                if (p.maxOutputTokens !in 64..8_000) add("${p.id} : sortie maximale hors limites")
                if (p.maxToolCalls !in 0..8) add("${p.id} : trop d'appels d'outils")
                p.fallbacks.filter { it.isBlank() || it.contains(' ') }.forEach { add("${p.id} : repli mal formé « $it »") }
                if (p.kind == ProfileKind.JUDGE && p.toolCategories.isNotEmpty()) add("${p.id} : le juge est en lecture seule, sans outils")
            }
        }

        private fun agent(id: String, role: String, mission: String, bias: String, objectives: List<String>, fields: List<String>, cats: Set<ToolCategory>,
                          caps: Set<ModelCapability> = setOf(ModelCapability.JSON), tools: Int = 3, forbidden: List<String> = emptyList(), success: List<String> = emptyList()) =
            CouncilAgentProfile(id, role, mission, bias, objectives, fields, cats, caps + (if (cats.isNotEmpty()) setOf(ModelCapability.TOOLS) else emptySet()),
                maxToolCalls = if (cats.isEmpty()) 0 else tools, forbidden = forbidden, successCriteria = success)

        val STANDARD = listOf(
            agent("strategist", "Stratège", "Décompose le problème, identifie les contraintes et les dépendances, compare 2 à 4 stratégies et en retient une.",
                "voit la structure et les alternatives", listOf("reformuler le problème", "lister 2 à 4 stratégies", "choisir une stratégie candidate", "identifier les dépendances", "expliciter les hypothèses"),
                listOf("planSteps", "constraints", "risks"), emptySet(), forbidden = listOf("inventer des faits"), success = listOf("stratégie applicable", "hypothèses explicites")),
            agent("evidence_analyst", "Analyste factuel", "Sépare faits, hypothèses et inférences ; cherche des preuves ; vérifie la fraîcheur et la qualité des sources ; signale ce qui reste non vérifié.",
                "exige des preuves", listOf("identifier les affirmations vérifiables", "chercher des preuves si un outil le permet", "marquer la fraîcheur", "distinguer USER_PROVIDED, TOOL_VERIFIED et MODEL_ONLY", "signaler les contradictions"),
                listOf("unsupportedClaims", "staleFacts"), setOf(ToolCategory.WEB, ToolCategory.FILES, ToolCategory.DOCUMENTS, ToolCategory.SERVICE), tools = 4,
                success = listOf("chaque fait important a un statut de preuve")),
            agent("solution_engineer", "Ingénieur solution", "Propose une solution concrète et exécutable, en vérifie la faisabilité et les compromis, avec un plan de test et de retour arrière.",
                "cherche ce qui marche en pratique", listOf("produire une solution exécutable", "détailler les préconditions", "vérifier la compatibilité", "minimiser le changement", "proposer des tests"),
                listOf("implementationPlan", "testPlan", "dependencies", "rollbackPlan"), setOf(ToolCategory.DEV, ToolCategory.FILES, ToolCategory.SYSTEM), success = listOf("solution testable")),
            agent("challenger", "Challenger", "Cherche la faille : contre-exemple, contrainte oubliée, hypothèse fragile, scénario de panne, risque de sécurité, de coût ou de latence.",
                "attaque ce qui est fragile, pas ce qui est solide", listOf("chercher un contre-exemple", "attaquer les hypothèses fragiles", "tester les scénarios extrêmes", "signaler sécurité, coût et latence"),
                listOf("counterExamples"), setOf(ToolCategory.WEB, ToolCategory.SERVICE), tools = 2,
                forbidden = listOf("inventer une objection uniquement pour s'opposer"), success = listOf("objections étayées ou accord explicite")),
        )

        private fun dyn(id: String, role: String, mission: String, cats: Set<ToolCategory>, fields: List<String> = emptyList()) =
            agent(id, role, mission, "regard spécialisé", listOf(mission), fields, cats)

        val DYNAMIC = listOf(
            dyn("security_reviewer", "Relecteur sécurité", "Cherche secrets exposés, entrées non validées, injections, permissions excessives et effets irréversibles.", setOf(ToolCategory.DEV, ToolCategory.FILES)),
            dyn("code_reviewer", "Relecteur de code", "Relit la solution de code : exactitude, lisibilité, cas limites, régressions.", setOf(ToolCategory.DEV, ToolCategory.FILES)),
            dyn("test_analyst", "Analyste de tests", "Définit les tests qui prouveraient la solution et interprète les échecs connus.", setOf(ToolCategory.DEV), listOf("testPlan")),
            dyn("data_analyst", "Analyste de données", "Examine les données disponibles, leurs biais et ce qu'elles permettent de conclure.", setOf(ToolCategory.FILES, ToolCategory.DOCUMENTS)),
            dyn("researcher", "Chercheur", "Cherche et recoupe des sources, en citant chacune.", setOf(ToolCategory.WEB)),
            dyn("ux_reviewer", "Relecteur UX", "Évalue l'expérience d'utilisation, l'accessibilité et la clarté.", emptySet()),
            dyn("product_analyst", "Analyste produit", "Évalue la valeur pour l'utilisateur, les priorités et les compromis.", emptySet()),
            dyn("commercial_analyst", "Analyste commercial", "Évalue le marché, les prix, la concurrence et la faisabilité commerciale.", setOf(ToolCategory.WEB)),
            dyn("creative_director", "Directeur de création", "Fixe l'intention créative, le ton et la cohérence d'ensemble.", emptySet()),
            dyn("architect", "Architecte", "Évalue la structure, les dépendances et l'évolution de la solution logicielle.", setOf(ToolCategory.DEV, ToolCategory.FILES)),
            dyn("implementer", "Implémenteur", "Propose le changement de code minimal et vérifiable (sans l'appliquer).", setOf(ToolCategory.DEV, ToolCategory.FILES), listOf("implementationPlan")),
            dyn("query_planner", "Planificateur de recherche", "Découpe la question en requêtes et critères de sources.", emptySet(), listOf("planSteps")),
            dyn("source_researcher", "Chercheur de sources", "Trouve et lit les sources pertinentes et récentes.", setOf(ToolCategory.WEB, ToolCategory.DOCUMENTS)),
            dyn("fact_checker", "Vérificateur de faits", "Vérifie chaque affirmation importante contre une source.", setOf(ToolCategory.WEB, ToolCategory.DOCUMENTS)),
            dyn("skeptic", "Sceptique", "Doute méthodiquement : sources faibles, corrélations, conclusions hâtives.", emptySet()),
            dyn("device_analyst", "Analyste de l'appareil", "Lit l'état de la tablette : applications, stockage, réglages.", setOf(ToolCategory.SYSTEM, ToolCategory.UI)),
            dyn("performance_analyst", "Analyste performances", "Identifie ce qui consomme batterie, mémoire et stockage.", setOf(ToolCategory.SYSTEM)),
            dyn("permissions_reviewer", "Relecteur permissions", "Vérifie que chaque changement proposé respecte permissions, sécurité et vie privée.", setOf(ToolCategory.SYSTEM)),
            dyn("market_analyst", "Analyste marché", "Apporte faits et chiffres de marché, sourcés.", setOf(ToolCategory.WEB)),
            dyn("financial_analyst", "Analyste financier", "Évalue coûts, revenus, trésorerie et contraintes opérationnelles.", setOf(ToolCategory.FILES, ToolCategory.DOCUMENTS)),
            dyn("risk_challenger", "Challenger des risques", "Cherche ce qui peut mal tourner : risques, dépendances, scénarios défavorables.", emptySet(), listOf("counterExamples")),
            dyn("concept_generator", "Générateur de concepts", "Propose des concepts variés et originaux.", emptySet()),
            dyn("style_reviewer", "Relecteur de style", "Vérifie la cohérence de style, de ton et de marque.", emptySet()),
            dyn("audience_challenger", "Challenger du public", "Se met à la place du public visé et conteste ce qui ne lui parlera pas.", emptySet()),
        )

        val INTERNAL = listOf(
            CouncilAgentProfile("judge", "Juge", "Classe des candidats anonymisés selon les critères, les preuves et les objections, sans connaître le vote.",
                "impartial, lecture seule", listOf("classer les candidats", "disqualifier ce qui viole une contrainte", "évaluer les preuves"), listOf("disqualifications", "unresolvedIssues"),
                kind = ProfileKind.JUDGE, maxToolCalls = 0),
            CouncilAgentProfile("synthesizer", "Synthèse", "Rédige la réponse unique au propriétaire à partir de la décision structurée.",
                "clair et fidèle", listOf("réponse directe", "séparer faits et incertitudes", "ne pas inventer de consensus"),
                capabilities = emptySet(), kind = ProfileKind.SYNTHESIZER, maxToolCalls = 0, maxOutputTokens = 1_500, readsMemory = false),
        )

        private fun preset(id: String, label: String, agents: List<String>, rounds: Int, topology: CouncilTopology, protocol: DecisionProtocol, challenge: Boolean, judge: JudgeSetting,
                           retention: Boolean = true, maxConcurrent: Int = 4, minEvidence: Double = 0.3, strict: Boolean = false, diversity: Boolean = false,
                           evidenceWeighted: Boolean = false, confirmAbove: Int? = null) =
            CouncilPreset(id, label, agents, rounds, topology, protocol, challenge, judge, retention, 6, maxConcurrent, minEvidence, strict, diversity, evidenceWeighted, confirmAbove)

        private val FOUR = listOf("strategist", "evidence_analyst", "solution_engineer", "challenger")

        val PRESETS: Map<String, CouncilPreset> = listOf(
            preset("fast", "Rapide", listOf("solution_engineer"), 0, CouncilTopology.INDEPENDENT, DecisionProtocol.SIMPLE_MAJORITY, false, JudgeSetting.OFF, retention = false, maxConcurrent = 1),
            preset("eco", "Éco (renforcé)", listOf("solution_engineer", "evidence_analyst"), 0, CouncilTopology.INDEPENDENT, DecisionProtocol.HYBRID, false, JudgeSetting.OFF, retention = false, maxConcurrent = 2),
            preset("balanced", "Équilibré (conseil 4)", FOUR, 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.HYBRID, true, JudgeSetting.OFF),
            preset("quality", "Qualité", FOUR, 2, CouncilTopology.AUTO, DecisionProtocol.HYBRID, true, JudgeSetting.AUTO, minEvidence = 0.5),
            preset("deep", "Approfondi", FOUR, 3, CouncilTopology.AUTO, DecisionProtocol.BLIND_JUDGE_THEN_VOTE, true, JudgeSetting.ON, minEvidence = 0.6, strict = true, confirmAbove = 80_000),
            preset("code_review", "Revue de code", listOf("architect", "implementer", "test_analyst", "security_reviewer"), 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.HYBRID, true, JudgeSetting.OFF),
            preset("research", "Recherche", listOf("query_planner", "source_researcher", "fact_checker", "skeptic"), 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.HYBRID, true, JudgeSetting.OFF, minEvidence = 0.6, evidenceWeighted = true),
            preset("android_diagnostic", "Diagnostic Android", listOf("device_analyst", "performance_analyst", "permissions_reviewer", "challenger"), 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.HYBRID, true, JudgeSetting.OFF),
            preset("business", "Affaires", listOf("strategist", "market_analyst", "financial_analyst", "risk_challenger"), 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.RANKED, true, JudgeSetting.OFF),
            preset("creative", "Création", listOf("creative_director", "concept_generator", "style_reviewer", "audience_challenger"), 1, CouncilTopology.SPARSE_DYNAMIC, DecisionProtocol.RANKED, false, JudgeSetting.OFF, diversity = true),
        ).associateBy { it.id }
    }
}
