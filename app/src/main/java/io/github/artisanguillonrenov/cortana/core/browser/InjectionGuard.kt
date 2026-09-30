package io.github.artisanguillonrenov.cortana.core.browser

import io.github.artisanguillonrenov.cortana.core.vision.TextMatch

/**
 * Prompt-injection defense for web content (doc 05 §12: "aucune source Web ne peut injecter des
 * instructions système"). Web text is always handed to the model as data (envelope, taint); on top
 * of that, passages that address the assistant (ignore your instructions, call a tool, send data…)
 * and text hidden from human readers are removed and reported, so they cannot steer extraction.
 */
object InjectionGuard {
    data class Finding(val kind: String, val snippet: String)
    data class Scrubbed(val text: String, val findings: List<Finding>) { val suspicious get() = findings.isNotEmpty() }

    private val patterns: List<Pair<String, Regex>> = listOf(
        "instruction_override" to Regex("""(?i)\b(ignore|disregard|forget|override)\b.{0,40}\b(previous|prior|above|all|your|the)\b.{0,20}\b(instructions?|rules|prompt|guidelines)\b"""),
        "instruction_override" to Regex("""(?i)\b(ignore|oublie|oubliez|ignorez)\b.{0,30}\b(tes|vos|les|toutes les|des)\b.{0,20}\b(instructions?|consignes|règles)\b"""),
        "role_change" to Regex("""(?i)\b(you are now|from now on you|act as|tu es (maintenant|désormais)|vous êtes (maintenant|désormais)|nouveau rôle)\b"""),
        "prompt_probe" to Regex("""(?i)\b(system prompt|invite système|prompt système|developer message|message développeur)\b"""),
        "tool_call" to Regex("""(?i)\b(call|use|invoke|appelle|utilise|exécute)\b.{0,20}\b(the |l'|la |le )?(tool|outil|function|fonction|commande)\b"""),
        "tool_name" to Regex("""\b(sms_send|exec_run|repo_push|code_delete|file_delete|android_ui_click|notifications_reply|phone_call_start|memory_save|browser_upload)\b"""),
        "exfiltration" to Regex("""(?i)\b(send|envoie|envoyez|transmets|forward|exfiltr\w*|post)\b.{0,40}\b(password|mot de passe|api key|clé|token|secret|cookies?|contacts|conversation|données)\b"""),
        "assistant_address" to Regex("""(?i)\b(dear|cher|chère)?\s*(ai|ia|assistant|llm|chatbot|cortana|claude|gpt)\b\s*[,:]\s*(please|merci de|veuillez|tu dois|you must)"""),
    )

    /** Scans a text; suspicious sentences are replaced by a visible marker. */
    fun scrub(text: String): Scrubbed {
        val findings = mutableListOf<Finding>()
        val sentences = text.split(Regex("(?<=[.!?\\n])"))
        val out = StringBuilder()
        for (s in sentences) {
            val hit = patterns.firstOrNull { it.second.containsMatchIn(s) }
            if (hit != null) { findings += Finding(hit.first, s.trim().take(160)); out.append(" [passage retiré : tentative d'instruction] ") }
            else out.append(s)
        }
        return Scrubbed(out.toString().replace(Regex("[ \\t]+"), " ").trim(), findings)
    }

    /**
     * JSON is scrubbed value by value (a JSON document has no sentences: a single hostile string
     * must not take the whole response with it); anything else sentence by sentence.
     */
    fun scrubAny(text: String): Scrubbed {
        val t = text.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return scrub(text)
        val el = runCatching { io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(t) }.getOrNull() ?: return scrub(text)
        val findings = mutableListOf<Finding>()
        fun walk(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
            is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.mapValues { walk(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { walk(it) })
            is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) scrub(e.content).let { s -> findings += s.findings; if (s.suspicious) kotlinx.serialization.json.JsonPrimitive(s.text) else e } else e
        }
        return Scrubbed(walk(el).toString(), findings)
    }

    fun isSuspicious(text: String) = patterns.any { it.second.containsMatchIn(text) }

    /** Lines (1-based) that address an assistant, for content that must stay intact (code, files). */
    fun suspiciousLines(text: String, max: Int = 10): List<Int> =
        text.lineSequence().withIndex().filter { (_, l) -> patterns.any { it.second.containsMatchIn(l) } }
            .map { (i, l) -> NUMBERED.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1) }.take(max).toList()

    /** Numbered listings (code.read: "   12| …") are reported with the file's own line numbers. */
    private val NUMBERED = Regex("^\\s*(\\d+)\\| ")

    /** Taint source recorded when content read by a task tried to give instructions (phase 32). */
    fun injectionSource(source: String) = "injection:$source"

    /** Similarity helper shared by extraction/deduplication. */
    fun sameStatement(a: String, b: String): Boolean {
        val x = TextMatch.normalize(a).split(' ').filter { it.length > 2 }.toSet()
        val y = TextMatch.normalize(b).split(' ').filter { it.length > 2 }.toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        return x.intersect(y).size.toDouble() / x.union(y).size >= 0.7
    }
}
