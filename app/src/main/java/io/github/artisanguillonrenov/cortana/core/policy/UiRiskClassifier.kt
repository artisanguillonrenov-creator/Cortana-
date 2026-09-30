package io.github.artisanguillonrenov.cortana.core.policy

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class UiRiskPatterns(
    val version: Int = 1,
    val l3Keywords: List<String> = emptyList(),
    val l3ResourceIds: List<String> = emptyList(),
    val l2Keywords: List<String> = emptyList(),
    val l2ResourceIds: List<String> = emptyList(),
    val messagingPackages: List<String> = emptyList(),
    val sensitivePackages: List<String> = emptyList(),
    val sensitiveSettingsKeywords: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: String): UiRiskPatterns = AppJson.decodeFromString(serializer(), json)
    }
}

/** What the classifier knows about the target of a UI action. */
data class UiTarget(
    val packageName: String?,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    /** Visible text of the whole screen (used to detect security settings pages). */
    val screenText: String? = null,
)

enum class UiActionKind { CLICK, LONG_CLICK, CLICK_POINT, TYPE, SUBMIT, PASTE }

/**
 * §9.2 — inspects the target node and foreground package for every click/long-click/tap/type/
 * submit/paste and escalates to L2/L3. Locale-aware keyword + resource-id lists (FR + EN),
 * versioned, editable in Settings.
 */
class UiRiskClassifier(private val patternsProvider: () -> UiRiskPatterns) {

    fun isSensitiveApp(pkg: String?): Boolean {
        if (pkg == null) return false
        val p = patternsProvider()
        return p.sensitivePackages.any { pkg == it || pkg.startsWith("$it.") }
    }

    fun classify(kind: UiActionKind, target: UiTarget, allowlist: List<String>): RiskAssessment {
        val p = patternsProvider()
        val reasons = mutableListOf<String>()
        var risk = Risk.L1

        val pkg = target.packageName
        if (isSensitiveApp(pkg)) {
            if (pkg !in allowlist) {
                return RiskAssessment(
                    Risk.L3, listOf("Application sensible ($pkg)"), deny = true,
                    denyReason = "L'automatisation est interdite dans cette application sensible ($pkg). Autorisez-la explicitement dans Réglages si vous le souhaitez.",
                )
            }
            risk = Risk.L3; reasons += "Application sensible autorisée ($pkg) : empreinte requise"
        }
        if (target.isPassword) {
            risk = Risk.L3; reasons += "Champ de mot de passe"
        }

        val label = normalize(listOfNotNull(target.text, target.contentDescription).joinToString(" "))
        val rid = target.resourceId?.substringAfterLast('/')?.lowercase().orEmpty()

        matchKeyword(label, p.l3Keywords)?.let { risk = Risk.L3; reasons += "Libellé sensible « $it »" }
        p.l3ResourceIds.firstOrNull { rid.contains(it) }?.let { risk = Risk.L3; reasons += "Identifiant sensible « $it »" }

        if (pkg == "com.android.settings" || pkg == "com.samsung.android.settings" || pkg?.contains("settings") == true) {
            val screen = normalize(target.screenText.orEmpty())
            matchKeyword(screen, p.sensitiveSettingsKeywords)?.let {
                if (kind != UiActionKind.TYPE || target.isEditable) {
                    risk = Risk.max(risk, Risk.L3); reasons += "Écran de réglages de sécurité/comptes (« $it »)"
                }
            }
        }

        if (risk.level < Risk.L2.level) {
            matchKeyword(label, p.l2Keywords)?.let { risk = Risk.L2; reasons += "Action d'envoi/publication « $it »" }
            p.l2ResourceIds.firstOrNull { rid.contains(it) }?.let { risk = Risk.L2; reasons += "Identifiant d'envoi « $it »" }
            val messaging = pkg != null && p.messagingPackages.any { pkg == it || pkg.startsWith("$it.") }
            if (messaging && (kind == UiActionKind.TYPE || kind == UiActionKind.PASTE || kind == UiActionKind.SUBMIT)) {
                risk = Risk.L2; reasons += "Saisie dans une messagerie ($pkg)"
            }
        }
        if (kind == UiActionKind.CLICK_POINT) reasons += "Clic par coordonnées (repli)"
        return RiskAssessment(risk, reasons)
    }

    companion object {
        fun normalize(s: String): String {
            val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            return n.replace(Regex("\\p{Mn}+"), "").replace('’', '\'')
        }

        /** Whole-word (or whole-phrase) match against normalized keywords. */
        fun matchKeyword(haystack: String, keywords: List<String>): String? {
            if (haystack.isBlank()) return null
            for (k in keywords) {
                val nk = normalize(k)
                if (nk.isBlank()) continue
                val re = Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(nk) + "($|[^\\p{L}\\p{N}])")
                if (re.containsMatchIn(haystack)) return k
            }
            return null
        }
    }
}
