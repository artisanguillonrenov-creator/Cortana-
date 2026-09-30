package io.github.artisanguillonrenov.cortana.util

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Removes known secret values (API keys) and common key shapes from any text that is about to
 * reach the model context, logs, audit rows, or tool observations (§9.5).
 */
object Redactor {
    const val MASK = "[SECRET]"
    private val known = CopyOnWriteArraySet<String>()

    private val patterns = listOf(
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{16,}"""),
        Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bgsk_[A-Za-z0-9]{20,}"""),
        Regex("""\bsk-or-v1-[A-Za-z0-9]{20,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
        Regex("""\bghp_[A-Za-z0-9]{30,}"""),
    )

    fun register(secret: String?) {
        if (secret != null && secret.length >= 6) known.add(secret)
    }

    fun unregister(secret: String?) {
        if (secret != null) known.remove(secret)
    }

    fun clear() = known.clear()

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (s in known) {
            if (out.contains(s)) out = out.replace(s, MASK)
        }
        for (p in patterns) out = p.replace(out, MASK)
        return out
    }
}
