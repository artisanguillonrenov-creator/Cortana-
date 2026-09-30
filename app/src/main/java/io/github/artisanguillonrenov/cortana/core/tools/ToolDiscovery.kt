package io.github.artisanguillonrenov.cortana.core.tools

import java.text.Normalizer

/**
 * Lexical search over tool definitions (doc 04 §9). Used by [CapabilityMatcher] to pre-select the
 * tools offered to the model and by the `tools.discover` meta-tool. The [ToolRegistry] stays the
 * canonical source; this only ranks what it holds.
 */
class ToolDiscovery {
    data class Hit(val def: ToolDefinition, val score: Double)

    private class Doc(val id: Set<String>, val tags: Set<String>, val label: Set<String>, val category: Set<String>, val description: Set<String>)

    private val docs = java.util.concurrent.ConcurrentHashMap<String, Doc>()

    // Keyed by definition identity: a dynamic tool (MCP) re-registered with a new description is re-indexed.
    private fun doc(d: ToolDefinition): Doc = docs.getOrPut(d.capability + "#" + System.identityHashCode(d)) {
        Doc(
            id = tokens(d.capability.replace('.', ' ').replace('_', ' ')),
            tags = d.tags.flatMap { tokens(it) }.toSet(),
            label = tokens(d.label),
            category = tokens(categoryWords[d.category].orEmpty()),
            description = tokens(d.description),
        )
    }

    fun rank(query: String, pool: List<ToolDefinition>, category: ToolCategory? = null): List<Hit> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        return pool.asSequence()
            .filter { category == null || it.category == category }
            .map { d ->
                val doc = doc(d)
                var score = 0.0
                for (t in q) {
                    score += when {
                        matches(t, doc.id) -> 4.0
                        matches(t, doc.tags) -> 3.0
                        matches(t, doc.label) -> 3.0
                        matches(t, doc.category) -> 1.5
                        matches(t, doc.description) -> 1.0
                        else -> 0.0
                    }
                }
                Hit(d, score)
            }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.def.capability })
            .toList()
    }

    fun search(query: String, pool: List<ToolDefinition>, category: ToolCategory? = null, limit: Int = 8, minScore: Double = MIN_SCORE): List<ToolDefinition> =
        rank(query, pool, category).filter { it.score >= minScore }.take(limit).map { it.def }

    companion object {
        const val MIN_SCORE = 2.0

        private val stop = setOf(
            "le", "la", "les", "l", "de", "du", "des", "d", "un", "une", "et", "a", "au", "aux", "en", "pour", "par", "sur", "dans", "avec",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "ce", "cet", "cette", "ces", "que", "qui", "quoi", "je", "j", "tu",
            "il", "elle", "nous", "vous", "me", "m", "moi", "te", "se", "s", "y", "ne", "pas", "plus", "est", "sont", "the", "to", "of", "and",
            "or", "an", "is", "it", "stp", "svp", "peux", "veux", "voudrais", "fais", "fait", "faire", "outil", "outils",
        )

        private val categoryWords = mapOf(
            ToolCategory.SERVICE to "memoire souvenir rappel planification notification question",
            ToolCategory.WEB to "web internet site page recherche lien url",
            ToolCategory.FILES to "fichier fichiers dossier document texte",
            ToolCategory.SYSTEM to "systeme parametres reglages tablette telephone application horloge",
            ToolCategory.UI to "ecran application cliquer toucher taper saisir interface bouton",
            ToolCategory.DEV to "code projet depot git build compilation test programme developpement",
            ToolCategory.DOCUMENTS to "document pdf tableur tableau presentation diapositive csv json",
            ToolCategory.COMMS to "contact sms message appel telephone calendrier agenda notification",
            ToolCategory.MEDIA to "image photo audio video vision voix capture",
            ToolCategory.INTEGRATIONS to "connecteur email courriel webhook domotique api",
        )

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        fun tokens(s: String): Set<String> =
            normalize(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in stop }.toSet()

        private val suffixes = listOf("ations", "ation", "ements", "ement", "euses", "euse", "eurs", "eur", "ions", "ent", "ez", "er", "es", "e", "s", "x")

        /** Light French stemmer: chercher/cherche/cherches → cherch, fichiers → fichi. */
        fun stem(t: String): String {
            for (suf in suffixes) if (t.endsWith(suf) && t.length - suf.length >= 4) return t.dropLast(suf.length)
            return t
        }

        /** Same stem, a shared stem prefix (≥ 4 letters) or one stem inside the other (≥ 5 letters: cherch ⊂ recherch). */
        private fun matches(t: String, set: Set<String>): Boolean {
            if (t in set) return true
            val a = stem(t)
            return set.any { d ->
                val b = stem(d)
                a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) ||
                    (a.length >= 5 && b.contains(a)) || (b.length >= 5 && a.contains(b))
            }
        }
    }
}
