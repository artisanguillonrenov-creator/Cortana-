package io.github.artisanguillonrenov.cortana.core.council

import io.github.artisanguillonrenov.cortana.core.model.ToolCallEmulation
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.Normalizer

/** Field limits (doc 14 §14.12). */
data class ContributionLimits(
    val candidateChars: Int = 2_000,
    val rationaleChars: Int = 1_500,
    val concernChars: Int = 1_000,
    val claimChars: Int = 600,
    val maxClaims: Int = 12,
    val maxConcerns: Int = 8,
    val maxEvidenceRefs: Int = 20,
    val maxRequestedChecks: Int = 8,
    val maxAssumptions: Int = 8,
    val maxDetailItems: Int = 10,
    val detailChars: Int = 400,
)

sealed interface ParseResult {
    data class Valid(val contribution: CouncilContribution, val truncated: Boolean) : ParseResult
    data class Invalid(val errors: List<String>) : ParseResult
}

/**
 * Strict parser of an agent's structured output (doc 10 C4, doc 14 §14.11–14.13): one JSON object,
 * only known fields (a `toolCall` or any other field is a rejection, never executed), typed values,
 * bounded sizes, control characters removed, secrets masked. Invalid output is never used.
 */
object ContributionParser {
    val TOP_LEVEL = setOf("candidate", "claims", "assumptions", "concerns", "requestedChecks", "shortRationale", "confidence", "details", "challengedCandidateKeys", "ranking")
    private val CANDIDATE_KEYS = setOf("summary", "action", "target", "parameters", "expectedResult", "sideEffects", "risk")
    private val CLAIM_KEYS = setOf("text", "type", "confidence", "evidenceRefs")
    private val CONCERN_KEYS = setOf("severity", "text", "targetClaim", "targetCandidate")

    /** The schema shown to agents (built by code, never free text). */
    fun schemaText(details: List<String>, critique: Boolean): String = buildString {
        append("""{"candidate":{"summary":"ta réponse ou solution candidate","action":"verbe principal (ex. utiliser, installer, désactiver, recommander, ne_rien_faire)","target":"objet principal","parameters":["paramètre structurant"],"expectedResult":"résultat attendu","sideEffects":["effet externe ou irréversible impliqué, sinon vide"],"risk":"low|medium|high"},""")
        append(""""claims":[{"text":"affirmation","type":"fact|inference|assumption|recommendation","confidence":0.0,"evidenceRefs":["référence de preuve (ex. tool:1, user, url)"]}],""")
        append(""""assumptions":["hypothèse"],"concerns":[{"severity":"low|medium|high|critical","text":"préoccupation","targetClaim":null,"targetCandidate":null}],""")
        append(""""requestedChecks":["vérification demandée"],"shortRationale":"justification courte et vérifiable (pas de raisonnement détaillé)","confidence":0.0""")
        if (details.isNotEmpty()) append(""","details":{${details.joinToString(",") { "\"$it\":[\"…\"]" }}}""")
        if (critique) append(""","ranking":["clés de candidats du meilleur au moins bon"],"challengedCandidateKeys":["clé attaquée"]""")
        append("}")
    }

    fun parse(text: String, slotId: String, profileId: String, round: Int, allowedDetails: Set<String>, limits: ContributionLimits = ContributionLimits(), tainted: Boolean = false): ParseResult {
        val obj = ToolCallEmulation.extractJsonObjects(text).firstNotNullOfOrNull { (_, j) -> runCatching { AppJson.parseToJsonElement(j) as? JsonObject }.getOrNull() }
            ?: return ParseResult.Invalid(listOf("aucun objet JSON"))
        return parseObject(obj, slotId, profileId, round, allowedDetails, limits, tainted)
    }

    fun parseObject(obj: JsonObject, slotId: String, profileId: String, round: Int, allowedDetails: Set<String>, limits: ContributionLimits = ContributionLimits(), tainted: Boolean = false): ParseResult {
        val errors = mutableListOf<String>()
        var truncated = false
        (obj.keys - TOP_LEVEL).forEach { errors += "champ non autorisé : $it" }
        fun str(e: JsonElement?, max: Int, path: String, required: Boolean = false): String? {
            if (e == null || e is JsonNull) { if (required) errors += "$path requis"; return null }
            val p = e as? JsonPrimitive
            if (p == null || !p.isString) { errors += "$path doit être une chaîne"; return null }
            val clean = sanitize(p.content)
            if (clean.length > max) truncated = true
            return clean.take(max)
        }
        fun strList(e: JsonElement?, maxItems: Int, maxChars: Int, path: String): List<String> {
            if (e == null || e is JsonNull) return emptyList()
            val arr = e as? JsonArray ?: run { errors += "$path doit être un tableau"; return emptyList() }
            if (arr.size > maxItems) truncated = true
            return arr.take(maxItems).mapIndexedNotNull { i, x -> str(x, maxChars, "$path[$i]") }.filter { it.isNotBlank() }
        }
        fun num(e: JsonElement?, path: String): Double? {
            if (e == null || e is JsonNull) return null
            val d = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: run { errors += "$path doit être un nombre"; return null }
            return d.coerceIn(0.0, 1.0)
        }
        fun objOf(e: JsonElement?, path: String): JsonObject? = when (e) {
            null, is JsonNull -> null
            is JsonObject -> e
            else -> { errors += "$path doit être un objet"; null }
        }

        val candidate = objOf(obj["candidate"], "candidate")?.let { c ->
            (c.keys - CANDIDATE_KEYS).forEach { errors += "candidate.$it non autorisé" }
            val summary = str(c["summary"], limits.candidateChars, "candidate.summary", required = true).orEmpty()
            val action = str(c["action"], 80, "candidate.action").orEmpty()
            val target = str(c["target"], 200, "candidate.target").orEmpty()
            val params = strList(c["parameters"], 8, 160, "candidate.parameters")
            val expected = str(c["expectedResult"], 400, "candidate.expectedResult").orEmpty()
            val effects = strList(c["sideEffects"], 8, 120, "candidate.sideEffects")
            val risk = str(c["risk"], 10, "candidate.risk")?.lowercase()?.takeIf { it in setOf("low", "medium", "high") } ?: "low"
            CandidateNormalizer.candidate(summary, action, target, params, expected, effects, risk)
        }
        val claims = ((obj["claims"] as? JsonArray) ?: run { if (obj["claims"] != null && obj["claims"] !is JsonNull) errors += "claims doit être un tableau"; JsonArray(emptyList()) })
            .also { if (it.size > limits.maxClaims) truncated = true }.take(limits.maxClaims).mapIndexedNotNull { i, e ->
                val c = e as? JsonObject ?: run { errors += "claims[$i] doit être un objet"; return@mapIndexedNotNull null }
                (c.keys - CLAIM_KEYS).forEach { errors += "claims[$i].$it non autorisé" }
                val t = str(c["text"], limits.claimChars, "claims[$i].text", required = true) ?: return@mapIndexedNotNull null
                val type = when (str(c["type"], 20, "claims[$i].type")?.lowercase()) {
                    "fact" -> ClaimType.FACT; "inference" -> ClaimType.INFERENCE; "assumption" -> ClaimType.ASSUMPTION; "recommendation" -> ClaimType.RECOMMENDATION
                    null -> ClaimType.INFERENCE
                    else -> { errors += "claims[$i].type inconnu"; ClaimType.INFERENCE }
                }
                Claim("$slotId-r$round-c$i", t, type, strList(c["evidenceRefs"], limits.maxEvidenceRefs, 200, "claims[$i].evidenceRefs"), num(c["confidence"], "claims[$i].confidence"), tainted)
            }
        val concerns = ((obj["concerns"] as? JsonArray) ?: run { if (obj["concerns"] != null && obj["concerns"] !is JsonNull) errors += "concerns doit être un tableau"; JsonArray(emptyList()) })
            .also { if (it.size > limits.maxConcerns) truncated = true }.take(limits.maxConcerns).mapIndexedNotNull { i, e ->
                val c = e as? JsonObject ?: run { errors += "concerns[$i] doit être un objet"; return@mapIndexedNotNull null }
                (c.keys - CONCERN_KEYS).forEach { errors += "concerns[$i].$it non autorisé" }
                val t = str(c["text"], limits.concernChars, "concerns[$i].text", required = true) ?: return@mapIndexedNotNull null
                val sev = when (str(c["severity"], 10, "concerns[$i].severity")?.lowercase()) {
                    "low" -> ConcernSeverity.LOW; "medium" -> ConcernSeverity.MEDIUM; "high" -> ConcernSeverity.HIGH; "critical" -> ConcernSeverity.CRITICAL
                    else -> { errors += "concerns[$i].severity inconnue"; ConcernSeverity.LOW }
                }
                val targetClaim = (c["targetClaim"] as? JsonPrimitive)?.contentOrNull?.let { tc -> tc.toIntOrNull()?.let { claims.getOrNull(it)?.id } ?: sanitize(tc).take(60) }
                Concern("$slotId-r$round-k$i", sev, t, targetClaim, (c["targetCandidate"] as? JsonPrimitive)?.contentOrNull?.let { sanitize(it).take(40) }, slotId)
            }
        val details = objOf(obj["details"], "details")?.let { d ->
            d.filterKeys { it in allowedDetails }.mapValues { (k, v) -> strList(v, limits.maxDetailItems, limits.detailChars, "details.$k") }.filterValues { it.isNotEmpty() }
        } ?: emptyMap()
        val self = num(obj["confidence"], "confidence")
        val rationale = str(obj["shortRationale"], limits.rationaleChars, "shortRationale").orEmpty()
        val ranking = strList(obj["ranking"], 12, 40, "ranking")
        if (candidate == null && claims.isEmpty() && concerns.isEmpty()) errors += "contribution vide (ni candidat, ni affirmation, ni préoccupation)"
        if (errors.isNotEmpty()) return ParseResult.Invalid(errors.distinct().take(12))
        return ParseResult.Valid(CouncilContribution(
            slotId = slotId, profileId = profileId, round = round, candidate = candidate, claims = claims,
            assumptions = strList(obj["assumptions"], limits.maxAssumptions, 300, "assumptions"),
            concerns = concerns, confidence = ConfidenceFeatures(selfReported = self),
            requestedChecks = strList(obj["requestedChecks"], limits.maxRequestedChecks, 300, "requestedChecks").map { RequestedCheck(it) },
            rationaleSummary = rationale, details = details + (if (ranking.isNotEmpty()) mapOf(RANKING to ranking) else emptyMap()),
            challengedCandidateKeys = strList(obj["challengedCandidateKeys"], 8, 40, "challengedCandidateKeys"), tainted = tainted,
        ), truncated)
    }

    const val RANKING = "_ranking"

    /** Control characters out (newlines and tabs kept), secrets masked, whitespace trimmed (doc 14 §14.13). */
    fun sanitize(s: String): String = Redactor.redact(s.filter { it == '\n' || it == '\t' || !it.isISOControl() }).trim()
}

/** Deterministic candidate fingerprints (doc 15 §15.4): never merge different side effects or risks. */
object CandidateNormalizer {
    private val SYNONYMS: Map<String, String> = mapOf(
        "installer" to "install", "ajouter" to "install", "add" to "install", "install" to "install",
        "supprimer" to "remove", "effacer" to "remove", "desinstaller" to "remove", "delete" to "remove", "remove" to "remove", "retirer" to "remove",
        "desactiver" to "disable", "couper" to "disable", "disable" to "disable", "arreter" to "disable",
        "activer" to "enable", "enable" to "enable", "allumer" to "enable",
        "utiliser" to "use", "use" to "use", "choisir" to "use", "adopter" to "use", "prendre" to "use", "opter" to "use", "privilegier" to "use",
        "garder" to "keep", "conserver" to "keep", "keep" to "keep", "maintenir" to "keep",
        "migrer" to "migrate", "migrate" to "migrate", "passer" to "migrate",
        "mettre_a_jour" to "update", "update" to "update", "upgrade" to "update", "actualiser" to "update",
        "creer" to "create", "create" to "create", "ecrire" to "create", "rediger" to "create",
        "configurer" to "configure", "regler" to "configure", "set" to "configure", "configure" to "configure", "parametrer" to "configure",
        "ne_rien_faire" to "none", "aucune_action" to "none", "none" to "none", "attendre" to "none", "rien" to "none",
        "recommander" to "recommend", "recommend" to "recommend", "conseiller" to "recommend",
        "corriger" to "fix", "reparer" to "fix", "fix" to "fix",
        "repondre" to "answer", "answer" to "answer", "expliquer" to "answer",
    )
    private val STOP = setOf("le", "la", "les", "l", "un", "une", "des", "de", "du", "d", "the", "a", "an", "of", "to", "et", "and", "pour", "for", "en", "au", "aux", "sur", "avec")

    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun action(raw: String): String {
        val f = fold(raw).trim().replace(Regex("[\\s-]+"), "_")
        return SYNONYMS[f] ?: SYNONYMS[f.substringBefore('_')] ?: f.ifBlank { "answer" }
    }

    fun tokens(s: String): Set<String> = fold(s).split(Regex("[^\\p{L}\\p{N}.]+")).filter { it.length > 1 && it !in STOP }.toSet()

    fun candidate(summary: String, action: String, target: String, params: List<String>, expected: String, effects: List<String>, risk: String): Candidate {
        val a = action(action.ifBlank { summary.split(' ').firstOrNull().orEmpty() })
        val t = tokens(target.ifBlank { summary }).sorted()
        val p = params.map { tokens(it).sorted().joinToString(" ") }.filter { it.isNotBlank() }.sorted()
        val e = effects.map { fold(it).trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val key = Hash.sha256("$a|${t.joinToString(" ")}|${p.joinToString(";")}|${e.joinToString(";")}|$risk").take(10)
        return Candidate(key, summary, a, t.joinToString(" "), p, expected, e, risk)
    }

    /** Same action, same effects, same risk and close targets → the same proposal (doc 04 §4.9). */
    fun equivalent(x: Candidate, y: Candidate): Boolean {
        if (x.key == y.key) return true
        if (x.action != y.action || x.sideEffects != y.sideEffects || x.risk != y.risk) return false
        val a = x.target.split(' ').filter { it.isNotBlank() }.toSet()
        val b = y.target.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return (a intersect b).size.toDouble() / (a union b).size >= 0.6
    }
}
