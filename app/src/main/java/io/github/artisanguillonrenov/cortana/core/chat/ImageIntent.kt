package io.github.artisanguillonrenov.cortana.core.chat

/**
 * What the owner wants from a picture request: find existing images on the web, generate a new one,
 * or neither. Searching and generating are never confused: "trouve-moi une photo de la tour Eiffel"
 * must show real photos found on the web, "dessine-moi un dragon" must create one.
 *
 * Deliberately strict: only explicit, unambiguous requests are recognised here; anything else is left
 * to the model's normal tool choice.
 */
object ImageIntent {
    /** An explicit web search for pictures (mode images) or videos (mode videos) about [query]. */
    data class Search(val query: String, val mode: String)

    private val generation = Regex(
        "(?i)\\b(g[ée]n[èeé]r\\w*|dessin\\w*|dessiner|cr[ée]{1,2}(?:r|z|s|e)?\\b|cr[ée]e-moi|imagin\\w*|fabriqu\\w*|produi\\w*|illustr\\w*|pein[st]\\w*|retouch\\w*|modifi\\w*)",
    )
    private val searchVerb =
        "(?:cherche|recherche|trouve|montre|affiche|donne|envoie|chercher|rechercher|trouver|montrer|afficher|donner|voir)"
    private val explicit = Regex(
        "(?i)^\\s*(?:(?:est-ce que\\s+)?(?:tu peux|peux-tu|pourrais-tu|tu pourrais)\\s+)?(?:me\\s+|nous\\s+)?" + searchVerb +
            "(?:[- ](?:moi|nous))?\\s+(?:sur (?:internet|le web|google)\\s+)?(?:une?|des|la|les|quelques|\\d+)?\\s*" +
            "(images?|photos?|photographies?|vid[ée]os?)\\b(.*)$",
    )
    private val online = Regex("(?i)\\b(sur (?:internet|le web|google|youtube)|en ligne|du web|d'internet)\\b")
    private val picture = Regex("(?i)\\b(images?|photos?|photographies?)\\b")
    private val followUp = Regex("(?i)\\b(puis|ensuite|et (?:envoie|partage|enregistre|mets|sauvegarde|imprime|transf[èe]re))\\b")
    private val leadingOf = Regex("(?i)^\\s*(?:de la |de l'|de l’|d'|d’|des |du |de |sur |avec )")
    private val trailing = Regex("(?i)[\\s,]*(?:(?:sur|depuis) (?:internet|le web|google|youtube)|en ligne|s'il te pla[iî]t|s’il te pla[iî]t|stp|svp|merci|please)[\\s.,!?]*$")
    private val article = Regex("(?i)^(?:la |le |les |l'|l’|un |une |des )")

    /** The explicit web search in [text], or null (ambiguous, generation, follow-up actions…). */
    fun search(text: String): Search? {
        val t = text.trim()
        if (t.length > 300 || generation.containsMatchIn(t) || followUp.containsMatchIn(t)) return null
        val m = explicit.find(t) ?: return null
        val mode = if (m.groupValues[1].lowercase().startsWith("vid")) "videos" else "images"
        var q = m.groupValues[2].trim()
        repeat(3) { q = q.replace(trailing, "").trim() }
        q = q.replace(leadingOf, "").trim()
        q = q.replace(article, "").trim().trimEnd('.', '!', '?', ',', ';', ':').trim()
        if (q.isEmpty() || q.length > 120) return null
        return Search(q, mode)
    }

    /** The owner explicitly wants pictures found on the web (not created), even phrased loosely. */
    fun isWebImageSearch(text: String): Boolean =
        search(text)?.mode == "images" || (picture.containsMatchIn(text) && online.containsMatchIn(text) && !generation.containsMatchIn(text))

    /** The owner explicitly wants a picture created (generation or retouching). */
    fun isGeneration(text: String): Boolean = generation.containsMatchIn(text) && (picture.containsMatchIn(text) ||
        Regex("(?i)\\b(dessin|illustration|logo|affiche|portrait|avatar|fond d'[ée]cran)\\b").containsMatchIn(text) ||
        Regex("(?i)\\b(dessine|imagine)\\w*").containsMatchIn(text))
}
