package io.github.artisanguillonrenov.cortana.core.council

import java.util.Locale

/**
 * Rule-based mode selection (doc 01 §1.3, doc 13 §13.10, doc 16 §16.10). OFF when the feature flag
 * is off, for fast paths, trivial requests (greetings, conversions, timers, simple navigation,
 * deterministic settings, short Android commands) and inside a council (no recursion). A learned
 * policy is only for later, with real data.
 *
 * Word boundaries are explicit Unicode lookarounds and the text is lower-cased first: on the JVM
 * `\b`, `\w` and `(?i)` are ASCII-only while Android's ICU engine applies them to all letters, so
 * accented words ("études", "sécurité") would otherwise match on one engine and not the other.
 */
class RuleCouncilPolicySelector : CouncilPolicySelector {
    private companion object {
        const val WS = "(?<![\\p{L}\\p{N}_])"
        const val WE = "(?![\\p{L}\\p{N}_])"
        const val WORD = "[\\p{L}\\p{N}_]*"
    }

    private val greeting = Regex("(?i)^\\s*(bonjour|bonsoir|salut|coucou|hello|hi|merci|ok|d'accord|super|parfait|bonne nuit)$WE[\\s!.,?]*$")
    private val conversion = Regex("(?i)$WS(convertis|conversion|combien font|combien fait|en (euros|dollars|km|miles|celsius|fahrenheit))$WE")
    private val timer = Regex("(?i)$WS(minuteur|chronomètre|réveil|alarme|rappelle[- ]moi|rappel|timer)$WE")
    private val command = Regex("(?i)^\\s*(ouvre|lance|ferme|mets|monte|baisse|active|désactive|allume|éteins|appelle|règle|affiche|va sur|retour)$WE")
    private val deep = Regex("(?i)$WS(en profondeur|approfondi[es]?|réfléchis bien|analyse complète|conseil de réflexion|tous les angles|très attentivement)$WE")
    private val compare = Regex("(?i)$WS(compare[rz]?|comparaison|avantages et inconvénients|pour et contre|lequel|laquelle|vaut-il mieux|choisir entre|versus|vs\\.?|revue|relis|vérifie|contre-vérif$WORD|critique)$WE")
    private val highRisk = Regex("(?i)$WS(sécurité|securite|vulnérab$WORD|mot de passe|chiffrement|données personnelles|juridique|contrat|médical|santé|finances?|investi$WORD|licenci$WORD|production|irréversible|supprim$WORD définitivement|force[rz]? le push|push forcé|migration de données)$WE")
    private val research = Regex("(?i)$WS(sources?|études?|recherche sur|état de l'art|que dit|que disent|preuves|contradictoires?|actualité sur)$WE")
    private val android = Regex("(?i)$WS(ma tablette|mon téléphone|batterie|stockage|lent(e)?|rame|mémoire pleine|applications? inutiles?|optimise$WORD)$WE")
    private val business = Regex("(?i)$WS(business|marché|clients?|prix|tarif$WORD|chiffre d'affaires|rentab$WORD|concurren$WORD|stratégie commerciale|devis|marge|recrut$WORD|embauch$WORD|salarié$WORD|apprenti$WORD|fournisseurs?)$WE")
    private val creative = Regex("(?i)$WS(slogan|logo|nom pour|idées? de|concept|scénario|histoire|créati$WORD|campagne|affiche)$WE")
    private val architecture = Regex("(?i)$WS(architecture|conception|refactor$WORD|migrer|choix technique|base de données|api|déboguer|debug$WORD|pourquoi .* (plante|échoue|crash))$WE")
    private val constraint = Regex("(?i)$WS(mais|sans|avec|doit|ne doit pas|contrainte|budget|délai)$WE")
    private val decision = Regex("(?i)$WS(dois-je|devrais-je|faut-il|vaut-il mieux|choisir entre|quelle (option|solution)|recommand$WORD|décide$WORD|plan (d'action|de)|stratégie)$WE")
    private val diagnose = Regex("(?i)$WS(pourquoi|diagnosti$WORD|analyse$WORD|que faire|d'où vient)$WE")

    fun trivial(text: String): Boolean {
        val t = text.trim().lowercase(Locale.ROOT)
        return t.length < 12 || greeting.matches(t) || conversion.containsMatchIn(t) || timer.containsMatchIn(t) || (command.containsMatchIn(t) && t.length < 80)
    }

    fun domainPreset(raw: String, coding: Boolean): String = raw.lowercase(Locale.ROOT).let { text -> when {
        coding || architecture.containsMatchIn(text) -> "code_review"
        research.containsMatchIn(text) -> "research"
        android.containsMatchIn(text) -> "android_diagnostic"
        business.containsMatchIn(text) -> "business"
        creative.containsMatchIn(text) -> "creative"
        else -> "balanced"
    } }

    override fun select(input: CouncilSelectionInput, config: CouncilConfig, prefs: CouncilPrefs): CouncilSelection {
        val mode = config.effectiveMode
        if (mode == CouncilMode.OFF) return CouncilSelection(CouncilMode.OFF, null, "conseil désactivé")
        if (input.insideCouncil) return CouncilSelection(CouncilMode.OFF, null, "conseil imbriqué refusé")
        if (input.fastPath) return CouncilSelection(CouncilMode.OFF, null, "raccourci déterministe")
        // A council needs room in the task's own budget: at least 3 calls for 2 agents + synthesis.
        if (input.remainingModelCalls < 3) return CouncilSelection(CouncilMode.OFF, null, "budget de la tâche insuffisant")
        val text = input.objective.lowercase(Locale.ROOT)
        // The owner asked for a council in the conversation: honoured (the owner's own mode, else four agents).
        if (input.explicitRequest) {
            val m = if (mode == CouncilMode.AUTO) CouncilMode.COUNCIL_4 else mode
            return CouncilSelection(m, if (m == CouncilMode.COUNCIL_4) domainPreset(text, input.coding) else null, "demandé dans la discussion")
        }
        if (trivial(input.objective)) return CouncilSelection(CouncilMode.OFF, null, "demande simple")
        if (mode != CouncilMode.AUTO) {
            val preset = if (mode == CouncilMode.COUNCIL_4) domainPreset(text, input.coding) else null
            return CouncilSelection(mode, preset, "mode choisi par le propriétaire")
        }
        val lowBattery = input.batteryPercent != null && prefs.lowBatteryPercent > 0 && input.batteryPercent < prefs.lowBatteryPercent
        val long = text.length > 400
        val constraints = text.count { it == ',' || it == ';' } + constraint.findAll(text).count()
        val selected = when {
            deep.containsMatchIn(text) -> CouncilSelection(CouncilMode.DEEP, "deep", "demande explicite d'approfondissement")
            highRisk.containsMatchIn(text) && (long || constraints >= 3 || decision.containsMatchIn(text)) -> CouncilSelection(CouncilMode.COUNCIL_4, "quality", "sujet à risque élevé")
            input.coding && (input.multiStep || architecture.containsMatchIn(text) || text.length > 160) -> CouncilSelection(CouncilMode.COUNCIL_4, "code_review", "développement non trivial")
            research.containsMatchIn(text) && (compare.containsMatchIn(text) || long) -> CouncilSelection(CouncilMode.COUNCIL_4, "research", "recherche à recouper")
            architecture.containsMatchIn(text) && text.length > 120 -> CouncilSelection(CouncilMode.COUNCIL_4, domainPreset(text, input.coding), "architecture ou débogage")
            android.containsMatchIn(text) && diagnose.containsMatchIn(text) && constraints >= 3 -> CouncilSelection(CouncilMode.COUNCIL_4, "android_diagnostic", "diagnostic de l'appareil à plusieurs symptômes")
            (decision.containsMatchIn(text) && constraints >= 3) || (long && constraints >= 4) -> CouncilSelection(CouncilMode.COUNCIL_4, domainPreset(text, input.coding), "décision multi-critères")
            compare.containsMatchIn(text) -> CouncilSelection(CouncilMode.REINFORCED, "eco", "comparaison ou vérification")
            else -> CouncilSelection(CouncilMode.OFF, null, "pas de bénéfice attendu")
        }
        if (selected.mode == CouncilMode.OFF) return selected
        // Degrade with the battery and the task's remaining budget (doc 08 §8.17, doc 05 §5.3).
        return when {
            lowBattery && selected.mode == CouncilMode.DEEP -> CouncilSelection(CouncilMode.COUNCIL_4, "balanced", selected.reason + " ; batterie faible")
            lowBattery && selected.mode == CouncilMode.COUNCIL_4 -> CouncilSelection(CouncilMode.REINFORCED, "eco", selected.reason + " ; batterie faible")
            input.remainingModelCalls < 7 && (selected.mode == CouncilMode.COUNCIL_4 || selected.mode == CouncilMode.DEEP) -> CouncilSelection(CouncilMode.REINFORCED, "eco", selected.reason + " ; budget de la tâche limité")
            else -> selected
        }
    }
}
