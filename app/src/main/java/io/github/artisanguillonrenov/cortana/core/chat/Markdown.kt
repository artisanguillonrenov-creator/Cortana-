package io.github.artisanguillonrenov.cortana.core.chat

/*
 * Markdown for the chat (doc 03 §3.2): GFM blocks (headings, paragraphs, lists with tasks, quotes,
 * fenced code, tables, rules), math ($…$, $$…$$, \(…\), \[…\]) and Mermaid fences. Written for this
 * app, no third-party code. Line-based, linear, bounded nesting: malformed or huge input degrades to
 * text, never to an exception. HTML is never interpreted — it is shown as text.
 */

enum class MdAlign { LEFT, CENTER, RIGHT, NONE }

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emph(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    /** [url] is null when the target was not safe (the text stays, the link goes). */
    data class Link(val children: List<MdInline>, val url: String?) : MdInline
    data class Math(val tex: String) : MdInline
    /** A citation marker [n] that matches a source of the message (resolved by MessageParts). */
    data class Cite(val index: Int) : MdInline
    object LineBreak : MdInline
}

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>, val anchor: String) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    /** [task] null = not a task item, else checked or not. */
    data class ListItem(val blocks: List<MdBlock>, val task: Boolean?)
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Math(val tex: String) : MdBlock
    data class Diagram(val kind: String, val source: String) : MdBlock
    data class Table(val header: List<List<MdInline>>, val align: List<MdAlign>, val rows: List<List<List<MdInline>>>) : MdBlock
    object Rule : MdBlock
}

object Markdown {
    private const val MAX_DEPTH = 8
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*#*[ \\t]*$")

    private val BULLET = Regex("^( {0,3})([-*+])[ \\t]+(.*)$")
    private val ORDERED = Regex("^( {0,3})([0-9]{1,9})([.)])[ \\t]+(.*)$")
    private val TASK = Regex("^\\[([ xX])\\][ \\t]+(.*)$", RegexOption.DOT_MATCHES_ALL)

    private val SETEXT_1 = Regex("^ {0,3}=+[ \\t]*$")
    private val SETEXT_2 = Regex("^ {0,3}-+[ \\t]*$")

    /** A thematic break (`---`, `***`, `___`, spaces allowed) — checked by hand: a regex with a back-reference overflows the stack on long lines. */
    private fun isRule(line: String): Boolean {
        if (leading(line) > 3) return false
        val t = line.filter { it != ' ' && it != '\t' }
        return t.length >= 3 && t[0] in "-*_" && t.all { it == t[0] }
    }

    /** A table delimiter row (`|:--|--:|`): cells of dashes with optional colons. */
    private fun isTableSeparator(line: String): Boolean {
        val cells = cells(line)
        return cells.isNotEmpty() && cells.all { c -> val t = c.trim(); t.isNotEmpty() && t.trim(':').isNotEmpty() && t.trim(':').all { it == '-' } && t.count { it == ':' } <= 2 }
    }

    fun parse(text: String): List<MdBlock> = blocks(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), 0)

    private fun blocks(lines: List<String>, depth: Int): List<MdBlock> {
        if (depth > MAX_DEPTH) return listOf(MdBlock.Paragraph(listOf(MdInline.Text(lines.joinToString("\n")))))
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(inlines(para.joinToString("\n"))); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flush(); i++; continue }
            // fenced code, math and diagrams
            FENCE.matchEntire(line)?.let { m ->
                flush()
                val fence = m.groupValues[1]
                val lang = m.groupValues[2].lowercase().ifEmpty { null }
                val body = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && !(lines[j].trimStart().startsWith(fence.take(3)) && lines[j].trim().all { it == fence[0] } && lines[j].trim().length >= fence.length)) { body += lines[j]; j++ }
                val code = body.joinToString("\n")
                out += when (lang) {
                    "mermaid" -> MdBlock.Diagram("mermaid", code)
                    "math", "latex", "tex", "katex" -> MdBlock.Math(code)
                    else -> MdBlock.Code(lang, code)
                }
                i = j + 1
                return@let
            }?.let { continue }
            val trimmed = line.trim()
            // display math: $$ … $$ or \[ … \]
            if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                val close = if (trimmed.startsWith("$$")) "$$" else "\\]"
                val first = trimmed.drop(2)
                if (first.endsWith(close) && first.length >= close.length) {
                    flush(); out += MdBlock.Math(first.dropLast(close.length).trim()); i++; continue
                }
                val body = mutableListOf(first)
                var j = i + 1
                while (j < lines.size && !lines[j].trim().endsWith(close)) { body += lines[j]; j++ }
                if (j < lines.size) {
                    flush(); body += lines[j].trim().dropLast(close.length)
                    out += MdBlock.Math(body.joinToString("\n").trim()); i = j + 1; continue
                }
                // unterminated: plain text
            }
            HEADING.matchEntire(line)?.let { m ->
                flush()
                val text = m.groupValues[2]
                out += MdBlock.Heading(m.groupValues[1].length, inlines(text), anchor(text))
                i++
                return@let
            }?.let { continue }
            // setext headings (a paragraph followed by === or ---)
            if (para.isNotEmpty() && (SETEXT_1.matches(line) || SETEXT_2.matches(line))) {
                val level = if (SETEXT_1.matches(line)) 1 else 2
                val text = para.joinToString(" ")
                para.clear()
                out += MdBlock.Heading(level, inlines(text), anchor(text))
                i++; continue
            }
            if (isRule(line)) { flush(); out += MdBlock.Rule; i++; continue }
            if (trimmed.startsWith(">")) {
                flush()
                val body = mutableListOf<String>()
                var j = i
                while (j < lines.size && lines[j].trim().startsWith(">")) { body += lines[j].trim().removePrefix(">").removePrefix(" "); j++ }
                out += MdBlock.Quote(blocks(body, depth + 1))
                i = j; continue
            }
            if (BULLET.matches(line) || ORDERED.matches(line)) {
                flush()
                val (list, next) = list(lines, i, depth)
                out += list; i = next; continue
            }
            if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) && lines[i + 1].contains('-')) {
                flush()
                val header = cells(line)
                val align = cells(lines[i + 1]).map { c ->
                    val t = c.trim()
                    when { t.startsWith(":") && t.endsWith(":") -> MdAlign.CENTER; t.endsWith(":") -> MdAlign.RIGHT; t.startsWith(":") -> MdAlign.LEFT; else -> MdAlign.NONE }
                }
                val rows = mutableListOf<List<List<MdInline>>>()
                var j = i + 2
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                    val cs = cells(lines[j])
                    rows += header.indices.map { k -> inlines(cs.getOrElse(k) { "" }.trim()) }
                    j++
                }
                out += MdBlock.Table(header.map { inlines(it.trim()) }, header.indices.map { align.getOrElse(it) { MdAlign.NONE } }, rows)
                i = j; continue
            }
            para += line.trimEnd().let { if (line.endsWith("  ")) "$it  " else it }
            i++
        }
        flush()
        return out
    }

    /** One list starting at [start]: items with their continuation lines, nested lists parsed from the dedented content. */
    private fun list(lines: List<String>, start: Int, depth: Int): Pair<MdBlock, Int> {
        val first = lines[start]
        val ordered = ORDERED.matches(first)
        val startNo = ORDERED.matchEntire(first)?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val items = mutableListOf<MdBlock.ListItem>()
        var i = start
        while (i < lines.size) {
            val m = (if (ordered) ORDERED else BULLET).matchEntire(lines[i]) ?: break
            val indent = m.groupValues[1].length
            val content = if (ordered) m.groupValues[4] else m.groupValues[3]
            val contentIndent = lines[i].length - content.length
            val body = mutableListOf(content)
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank()) {
                    // a blank line continues the item only if the next line is indented into it
                    if (j + 1 < lines.size && lines[j + 1].isNotBlank() && leading(lines[j + 1]) >= contentIndent.coerceAtMost(indent + 2)) { body += ""; j++; continue }
                    break
                }
                val lead = leading(l)
                if (lead <= indent && ((if (ordered) ORDERED else BULLET).matches(l) || BULLET.matches(l) || ORDERED.matches(l))) break
                if (lead >= indent + 2) { body += l.drop(minOf(lead, contentIndent)); j++; continue }
                if (FENCE.matches(l) || HEADING.matches(l) || l.trim().startsWith(">")) break
                body += l.trim(); j++ // lazy continuation
            }
            val task = TASK.matchEntire(body.first())
            val itemLines = if (task != null) listOf(task.groupValues[2]) + body.drop(1) else body
            items += MdBlock.ListItem(blocks(itemLines, depth + 1), task?.let { it.groupValues[1] != " " })
            i = j
            if (i < lines.size && lines[i].isBlank()) {
                val k = i + 1
                if (k < lines.size && (if (ordered) ORDERED else BULLET).matches(lines[k]) && leading(lines[k]) == indent) i = k
            }
        }
        return MdBlock.ListBlock(ordered, startNo, items) to i
    }

    private fun leading(s: String) = s.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) s.length else it }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        var inCode = false
        while (k < t.length) {
            val c = t[k]
            when {
                c == '\\' && k + 1 < t.length && t[k + 1] == '|' -> { cur.append('|'); k++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            k++
        }
        out += cur.toString()
        return out
    }

    fun anchor(text: String): String = MessageText.fold(text).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)

    // ---------------------------------------------------------------- inlines

    fun inlines(text: String): List<MdInline> = merge(InlineParser(text).parse(0))

    private fun merge(list: List<MdInline>): List<MdInline> {
        val out = mutableListOf<MdInline>()
        for (x in list) {
            val last = out.lastOrNull()
            if (x is MdInline.Text && last is MdInline.Text) out[out.lastIndex] = MdInline.Text(last.text + x.text) else out += x
        }
        return out
    }

    private class InlineParser(val s: String) {
        var i = 0
        fun parse(depth: Int, until: String? = null): List<MdInline> {
            val out = mutableListOf<MdInline>()
            val buf = StringBuilder()
            fun text() { if (buf.isNotEmpty()) { out += MdInline.Text(buf.toString()); buf.clear() } }
            while (i < s.length) {
                if (until != null && s.startsWith(until, i) && (buf.isNotEmpty() || out.isNotEmpty())) { i += until.length; text(); return out }
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length && s[i + 1] == '\n' -> { text(); out += MdInline.LineBreak; i += 2 }
                    c == '\\' && i + 1 < s.length && (s[i + 1] == '(' || s[i + 1] == '[') && closeAt(if (s[i + 1] == '(') "\\)" else "\\]", i + 2) > 0 -> {
                        val close = if (s[i + 1] == '(') "\\)" else "\\]"
                        val end = closeAt(close, i + 2)
                        text(); out += MdInline.Math(s.substring(i + 2, end).trim()); i = end + 2
                    }
                    c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> { buf.append(s[i + 1]); i += 2 }
                    c == '\n' -> {
                        text()
                        out += MdInline.LineBreak
                        i++
                    }
                    c == '`' -> {
                        val run = s.drop(i).takeWhile { it == '`' }.length
                        val end = s.indexOf("`".repeat(run), i + run)
                        if (end < 0) { buf.append(s, i, i + run); i += run }
                        else { text(); out += MdInline.Code(s.substring(i + run, end).let { if (it.startsWith(' ') && it.endsWith(' ') && it.isNotBlank()) it.substring(1, it.length - 1) else it }); i = end + run }
                    }
                    c == '$' && s.startsWith("$$", i) && s.indexOf("$$", i + 2).let { it > i + 2 } -> {
                        val end = s.indexOf("$$", i + 2)
                        text(); out += MdInline.Math(s.substring(i + 2, end).trim()); i = end + 2
                    }
                    c == '$' && inlineMathEnd(i) > 0 -> {
                        val end = inlineMathEnd(i)
                        text(); out += MdInline.Math(s.substring(i + 1, end)); i = end + 1
                    }
                    c == '!' && i + 1 < s.length && s[i + 1] == '[' && link(i + 1) != null -> {
                        val (label, url, next) = link(i + 1)!!
                        text(); out += MdInline.Link(listOf(MdInline.Text("🖼 " + label.ifBlank { "image" })), SafeLinks.sanitize(url)); i = next
                    }
                    c == '[' && link(i) != null -> {
                        val (label, url, next) = link(i)!!
                        text(); out += MdInline.Link(if (depth < MAX_DEPTH) Markdown.inlines(label) else listOf(MdInline.Text(label)), SafeLinks.sanitize(url)); i = next
                    }
                    c == '[' && cite(i) != null -> { val (n, next) = cite(i)!!; text(); out += MdInline.Cite(n); i = next }
                    c == '<' && autolink(i) != null -> { val (url, next) = autolink(i)!!; text(); out += MdInline.Link(listOf(MdInline.Text(url)), SafeLinks.sanitize(url)); i = next }
                    (c == 'h' || c == 'H') && (i == 0 || !s[i - 1].isLetterOrDigit()) && bareUrl(i) != null -> {
                        val url = bareUrl(i)!!
                        text(); out += MdInline.Link(listOf(MdInline.Text(url)), SafeLinks.sanitize(url)); i += url.length
                    }
                    (c == '*' || c == '_' || c == '~') && depth < MAX_DEPTH && delimited(i) != null -> {
                        val (marker, inner, next) = delimited(i)!!
                        text()
                        val children = merge(InlineParser(inner).parse(depth + 1))
                        out += when (marker) { "**", "__" -> MdInline.Strong(children); "~~" -> MdInline.Strike(children); else -> MdInline.Emph(children) }
                        i = next
                    }
                    else -> { buf.append(c); i++ }
                }
            }
            text()
            return out
        }

        fun closeAt(close: String, from: Int): Int = s.indexOf(close, from).let { if (it < 0 || s.substring(from, it).contains("\n\n")) -1 else it }

        /** Pandoc rule: `$x$` — no space after the opening `$`, none before the closing one, no digit right after it. */
        fun inlineMathEnd(start: Int): Int {
            if (start + 1 >= s.length || s[start + 1].isWhitespace() || s[start + 1] == '$') return -1
            var k = start + 1
            while (k < s.length && s[k] != '\n') {
                if (s[k] == '\\') { k += 2; continue }
                if (s[k] == '$') return if (!s[k - 1].isWhitespace() && (k + 1 >= s.length || !s[k + 1].isDigit())) k else -1
                k++
            }
            return -1
        }

        /** [label](url "title") — balanced brackets, no newline in the target. */
        fun link(start: Int): Triple<String, String, Int>? {
            var depthB = 0
            var k = start
            while (k < s.length) {
                when (s[k]) { '[' -> depthB++; ']' -> { depthB--; if (depthB == 0) break } ; '\n' -> if (k + 1 < s.length && s[k + 1] == '\n') return null }
                k++
            }
            if (k >= s.length || k + 1 >= s.length || s[k + 1] != '(') return null
            var close = -1
            var depthP = 0
            var p = k + 2
            while (p < s.length && p - k < 4096) {
                when (s[p]) { '(' -> depthP++; ')' -> { if (depthP == 0) { close = p; break }; depthP-- }; '\n' -> return null }
                p++
            }
            if (close < 0) return null
            val target = s.substring(k + 2, close)
            if (target.contains('\n')) return null
            val url = target.trim().substringBefore(' ').removePrefix("<").removeSuffix(">")
            return Triple(s.substring(start + 1, k), url, close + 1)
        }

        fun cite(start: Int): Pair<Int, Int>? {
            val close = s.indexOf(']', start)
            if (close < 0 || close - start > 4) return null
            val n = s.substring(start + 1, close).toIntOrNull() ?: return null
            return if (n in 1..99) n to close + 1 else null
        }

        fun autolink(start: Int): Pair<String, Int>? {
            val close = s.indexOf('>', start)
            if (close < 0) return null
            val inner = s.substring(start + 1, close)
            return if ((inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:")) && inner.none { it.isWhitespace() }) inner to close + 1 else null
        }

        fun bareUrl(start: Int): String? {
            val rest = s.substring(start, minOf(s.length, start + 2048))
            if (!rest.startsWith("http://", ignoreCase = true) && !rest.startsWith("https://", ignoreCase = true)) return null
            var end = rest.indexOfFirst { it.isWhitespace() || it == '<' || it == '"' }.let { if (it < 0) rest.length else it }
            while (end > 0 && rest[end - 1] in ".,;:!?)]'") {
                if (rest[end - 1] == ')' && rest.substring(0, end).count { it == '(' } >= rest.substring(0, end).count { it == ')' }) break
                end--
            }
            return rest.substring(0, end).takeIf { it.length > 8 }
        }

        /** **x**, __x__, *x*, _x_ (not inside a word), ~~x~~ — closed on the same paragraph, non-space inside the edges. */
        fun delimited(start: Int): Triple<String, String, Int>? {
            val c = s[start]
            val marker = when {
                c == '~' && s.startsWith("~~", start) -> "~~"
                c == '~' -> return null
                s.startsWith("$c$c", start) -> "$c$c"
                else -> "$c"
            }
            if (c == '_' && start > 0 && s[start - 1].isLetterOrDigit()) return null
            val from = start + marker.length
            if (from >= s.length || s[from].isWhitespace()) return null
            var k = from
            while (k < s.length) {
                if (s[k] == '\\') { k += 2; continue }
                if (s[k] == '`') { val e = s.indexOf('`', k + 1); if (e < 0) return null; k = e + 1; continue }
                if (s.startsWith("\n\n", k)) return null
                if (s.startsWith(marker, k) && !s[k - 1].isWhitespace() && k > from) {
                    if (marker.length == 1 && k + 1 < s.length && s[k + 1] == c) { k += 2; continue } // part of a double marker
                    if (c == '_' && k + marker.length < s.length && s[k + marker.length].isLetterOrDigit()) { k++; continue }
                    return Triple(marker, s.substring(from, k), k + marker.length)
                }
                k++
            }
            return null
        }

        companion object { const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>$" }
    }
}

/** Text helpers shared by the chat (accent folding for anchors and search). */
object MessageText {
    fun fold(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Plain text of inlines (copy, TalkBack, search). */
    fun plain(inlines: List<MdInline>): String = buildString {
        for (x in inlines) when (x) {
            is MdInline.Text -> append(x.text)
            is MdInline.Strong -> append(plain(x.children))
            is MdInline.Emph -> append(plain(x.children))
            is MdInline.Strike -> append(plain(x.children))
            is MdInline.Code -> append(x.text)
            is MdInline.Link -> append(plain(x.children))
            is MdInline.Math -> append(TexLite.render(x.tex).text)
            is MdInline.Cite -> append("[").append(x.index).append("]")
            MdInline.LineBreak -> append('\n')
        }
    }

    fun plainBlocks(blocks: List<MdBlock>): String = blocks.joinToString("\n\n") { b ->
        when (b) {
            is MdBlock.Heading -> plain(b.inlines)
            is MdBlock.Paragraph -> plain(b.inlines)
            is MdBlock.ListBlock -> b.items.mapIndexed { i, it -> (if (b.ordered) "${b.start + i}. " else "• ") + (when (it.task) { true -> "☑ "; false -> "☐ "; null -> "" }) + plainBlocks(it.blocks) }.joinToString("\n")
            is MdBlock.Quote -> plainBlocks(b.blocks).lines().joinToString("\n") { "> $it" }
            is MdBlock.Code -> b.code
            is MdBlock.Math -> TexLite.render(b.tex).text
            is MdBlock.Diagram -> b.source
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { row -> row.joinToString(" | ") { plain(it) } }
            MdBlock.Rule -> "—"
        }
    }
}
