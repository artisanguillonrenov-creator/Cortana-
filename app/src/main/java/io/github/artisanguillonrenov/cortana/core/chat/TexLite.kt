package io.github.artisanguillonrenov.cortana.core.chat

/**
 * Readable math without a TeX engine (doc 03 §3.2): the common LaTeX of chat answers — Greek letters,
 * operators, arrows, fractions, roots, sub/superscripts, \text — rendered with Unicode. Anything it does
 * not know stays as source and [Rendered.exact] is false, so the UI can offer the source.
 */
object TexLite {
    data class Rendered(val text: String, val exact: Boolean)

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞", "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦", "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
        "supset" to "⊃", "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃", "emptyset" to "∅", "varnothing" to "∅", "neg" to "¬",
        "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "circ" to "∘", "degree" to "°",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "langle" to "⟨", "rangle" to "⟩", "mid" to "∣",
        "log" to "log", "ln" to "ln", "exp" to "exp", "sin" to "sin", "cos" to "cos", "tan" to "tan", "lim" to "lim", "max" to "max", "min" to "min",
        "det" to "det", "gcd" to "pgcd",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "", " " to " ", "%" to "%", "$" to "$", "{" to "{", "}" to "}", "_" to "_", "&" to "&", "#" to "#",
    )
    private val TEXT_COMMANDS = setOf("text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "operatorname", "textbf", "textit", "boldsymbol", "mathbb", "mathcal")
    private val IGNORED = setOf("left", "right", "big", "Big", "bigg", "Bigg", "displaystyle", "limits", "nolimits")
    private const val SUP = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ"
    private const val SUP_SRC = "0123456789+-=()ni"
    private const val SUB = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓₕₖₗₘₙₚₛₜᵢⱼ"
    private const val SUB_SRC = "0123456789+-=()aeoxhklmnpstij"

    fun render(tex: String): Rendered {
        val r = Renderer(tex.take(20_000))
        val out = r.group(endOn = null)
        return Rendered(out.trim(), r.exact)
    }

    private class Renderer(val s: String) {
        var i = 0
        var exact = true
        var depth = 0

        fun group(endOn: Char?): String {
            val out = StringBuilder()
            depth++
            if (depth > 40) { exact = false; out.append(s.substring(i)); i = s.length; depth--; return out.toString() }
            while (i < s.length) {
                val c = s[i]
                if (endOn != null && c == endOn) { i++; break }
                when (c) {
                    '\\' -> out.append(command())
                    '{' -> { i++; out.append(group('}')) }
                    '^' -> { i++; out.append(script(atom(), SUP_SRC, SUP, "^")) }
                    '_' -> { i++; out.append(script(atom(), SUB_SRC, SUB, "_")) }
                    '~' -> { out.append(' '); i++ }
                    '&' -> { out.append("  "); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            depth--
            return out.toString()
        }

        /** The next argument: a braced group, a command or one character. */
        fun atom(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return ""
            return when (s[i]) {
                '{' -> { i++; group('}') }
                '\\' -> command()
                else -> s[i++].toString()
            }
        }

        fun command(): String {
            i++ // backslash
            if (i >= s.length) return "\\"
            val start = i
            if (!s[i].isLetter()) { i++; return SYMBOLS[s[start].toString()] ?: s[start].toString().also { if (s[start] == '\\') exact = exact } }
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            SYMBOLS[name]?.let { return it }
            return when {
                name in IGNORED -> ""
                name in TEXT_COMMANDS -> atom()
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    val a = atom(); val b = atom()
                    if (a.length <= 3 && b.length <= 3 && (a + b).all { it.isLetterOrDigit() }) "$a⁄$b" else "($a)/($b)"
                }
                name == "sqrt" -> {
                    var n = ""
                    if (i < s.length && s[i] == '[') { val e = s.indexOf(']', i); if (e > 0) { n = s.substring(i + 1, e); i = e + 1 } }
                    val a = atom()
                    (if (n.isNotEmpty()) script(n, SUP_SRC, SUP, "^") else "") + "√" + (if (a.length > 1) "($a)" else a)
                }
                name == "overline" || name == "bar" -> atom() + "̅"
                name == "vec" -> atom() + "⃗"
                name == "hat" -> atom() + "̂"
                name == "binom" -> { val a = atom(); val b = atom(); "C($a, $b)" }
                name == "begin" || name == "end" -> { atom(); "" }
                name == "\\" || name == "newline" || name == "cr" -> "\n"
                else -> { exact = false; "\\" + name }
            }
        }

        fun script(v: String, src: String, dst: String, marker: String): String =
            if (v.isNotEmpty() && v.all { src.indexOf(it) >= 0 }) v.map { dst[src.indexOf(it)] }.joinToString("")
            else if (v.length == 1) "$marker$v" else "$marker($v)"
    }
}

/** Link hygiene (doc 11 §11.8): only destinations a tap can safely open. */
object SafeLinks {
    private val ALLOWED = listOf("https://", "http://", "mailto:", "tel:", "artifact:")

    /** The url when safe to open, else null (javascript:, data:, file:, content:, intent:, relative…). */
    fun sanitize(raw: String?): String? {
        val u = raw?.trim() ?: return null
        if (u.isEmpty() || u.length > 4096) return null
        if (u.any { it.isISOControl() || it == ' ' }) return null
        val lower = u.lowercase()
        if (ALLOWED.none { lower.startsWith(it) }) return null
        if (lower.startsWith("http") && runCatching { java.net.URI(u).host }.getOrNull().isNullOrBlank()) return null
        return u
    }
}
