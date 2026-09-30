package io.github.artisanguillonrenov.cortana.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs
import io.github.artisanguillonrenov.cortana.core.chat.MdAlign
import io.github.artisanguillonrenov.cortana.core.chat.MdBlock
import io.github.artisanguillonrenov.cortana.core.chat.MdInline
import io.github.artisanguillonrenov.cortana.core.chat.MessageText
import io.github.artisanguillonrenov.cortana.core.chat.TexLite

/** What a rendered message can ask of its screen (never an action by itself). */
data class RenderActions(
    val openLink: (String) -> Unit = {},
    val openCitation: (Int) -> Unit = {},
    val saveText: (text: String, name: String) -> Unit = { _, _ -> },
    val say: (String) -> Unit = {},
)

/** A screen may draw code blocks itself (the Cortana Workspace design); null = the default block. */
val LocalCodeRenderer = androidx.compose.runtime.staticCompositionLocalOf<(@Composable (language: String?, code: String) -> Unit)?> { null }

/**
 * Markdown blocks as Compose (doc 03 §3.2). No HTML, no WebView: every element is a native component;
 * links were sanitised by the parser; long code and tables scroll inside their own container.
 */
@Composable
fun MarkdownView(blocks: List<MdBlock>, prefs: ChatPrefs, actions: RenderActions, modifier: Modifier = Modifier, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    val t = LocalWorkspace.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(t.gap)) {
        blocks.forEach { b -> BlockView(b, prefs, actions, textColor, depth = 0) }
    }
}

@Composable
private fun BlockView(b: MdBlock, prefs: ChatPrefs, actions: RenderActions, color: Color, depth: Int) {
    val t = LocalWorkspace.current
    val type = MaterialTheme.typography
    when (b) {
        is MdBlock.Heading -> {
            val style = when (b.level) { 1 -> type.headlineSmall; 2 -> type.titleLarge; 3 -> type.titleMedium; else -> type.titleSmall }
            RichText(b.inlines, style.copy(fontWeight = FontWeight.SemiBold), color, actions, Modifier.semantics { heading() })
        }
        is MdBlock.Paragraph -> RichText(b.inlines, type.bodyLarge, color, actions)
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(t.gap / 2)) {
            b.items.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth()) {
                    val marker = when (item.task) {
                        true -> "☑"
                        false -> "☐"
                        null -> if (b.ordered) "${b.start + i}." else if (depth % 2 == 0) "•" else "◦"
                    }
                    Text(marker, style = type.bodyLarge, color = if (item.task != null) t.muted else color,
                        modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp)
                            .semantics { if (item.task != null) contentDescription = if (item.task == true) "tâche faite" else "tâche à faire" })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.gap / 2)) {
                        item.blocks.forEach { BlockView(it, prefs, actions, color, depth + 1) }
                    }
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(t.border, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(t.gap / 2)) {
                b.blocks.forEach { BlockView(it, prefs, actions, t.muted, depth + 1) }
            }
        }
        is MdBlock.Code -> LocalCodeRenderer.current?.invoke(b.language, b.code) ?: CodeBlock(b.language, b.code, prefs.codeLineNumbers, actions)
        is MdBlock.Math -> MathBlock(b.tex, prefs.latex)
        is MdBlock.Diagram -> DiagramBlock(b.kind, b.source, actions)
        is MdBlock.Table -> TableBlock(b, color, actions)
        MdBlock.Rule -> HorizontalDivider(color = t.border)
    }
}

/** Inline Markdown as one AnnotatedString (selection, TalkBack reading order and links keep working). */
@Composable
fun RichText(inlines: List<MdInline>, style: TextStyle, color: Color, actions: RenderActions, modifier: Modifier = Modifier) {
    val t = LocalWorkspace.current
    val codeBg = t.code
    val link = t.link
    val text = remember(inlines, codeBg, link) { annotate(inlines, codeBg, link, actions) }
    Text(text, style = style, color = color, modifier = modifier)
}

internal fun annotate(inlines: List<MdInline>, codeBg: Color, link: Color, actions: RenderActions): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    fun walk(list: List<MdInline>) {
        for (x in list) when (x) {
            is MdInline.Text -> append(x.text)
            is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(x.children) }
            is MdInline.Emph -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(x.children) }
            is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(x.children) }
            is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append("\u2009"); append(x.text); append("\u2009") }
            is MdInline.Link -> {
                val url = x.url
                if (url == null) walk(x.children)
                else withLink(LinkAnnotation.Clickable("link:$url", linkStyles) { actions.openLink(url) }) { walk(x.children) }
            }
            is MdInline.Math -> {
                val r = TexLite.render(x.tex)
                withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) { append(if (r.exact) r.text else x.tex) }
            }
            is MdInline.Cite -> withLink(LinkAnnotation.Clickable("cite:${x.index}", linkStyles) { actions.openCitation(x.index) }) {
                withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp)) { append("[${x.index}]") }
            }
            MdInline.LineBreak -> append('\n')
        }
    }
    walk(inlines)
}

@Composable
private fun BlockHeader(label: String, actions: @Composable () -> Unit) {
    val t = LocalWorkspace.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = t.muted, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Code (doc 03 §3.3): language, copy, save as artifact, optional line numbers; scrolls sideways, never wraps. */
@Composable
fun CodeBlock(language: String?, code: String, lineNumbers: Boolean, actions: RenderActions) {
    val t = LocalWorkspace.current
    val clipboard = LocalClipboardManager.current
    val colors = SyntaxColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, t.muted, MaterialTheme.colorScheme.secondary)
    val highlighted = remember(code, language, colors) { Syntax.highlight(code, language, colors) }
    Surface(color = t.code, contentColor = t.onCode, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(t.radiusSmall))) {
        Column {
            BlockHeader(language?.takeIf { it.isNotBlank() } ?: "code") {
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)); actions.say("Code copié") }) { Text("Copier") }
                TextButton(onClick = { actions.saveText(code, "extrait." + Syntax.extension(language)) }) { Text("Enregistrer") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                if (lineNumbers) {
                    val n = code.count { it == '\n' } + 1
                    Text((1..n).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = t.muted,
                        textAlign = TextAlign.End, modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics {})
                }
                SelectionContainer { Text(highlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), softWrap = false) }
            }
        }
    }
}

/** Math (doc 03 §3.2): readable Unicode for common LaTeX; the source stays one tap away when the rendering is partial. */
@Composable
private fun MathBlock(tex: String, latex: Boolean) {
    val t = LocalWorkspace.current
    val r = remember(tex) { TexLite.render(tex) }
    var source by rememberSaveable(tex) { mutableStateOf(!latex) }
    Surface(color = t.elevated, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                if (source) Text(tex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                else Text(r.text, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = "Formule : ${r.text}" })
            }
            if (!r.exact || source) TextButton(onClick = { source = !source }) {
                Text(if (source) "Afficher la formule" else "Rendu partiel · voir la source LaTeX", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Diagrams (doc 03 §3.2 "si safe renderer"): no offline renderer is embedded, so the source is shown, labelled, and can be saved. */
@Composable
private fun DiagramBlock(kind: String, source: String, actions: RenderActions) {
    val t = LocalWorkspace.current
    val clipboard = LocalClipboardManager.current
    Surface(color = t.code, contentColor = t.onCode, shape = RoundedCornerShape(t.radiusSmall), modifier = Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(t.radiusSmall))) {
        Column {
            BlockHeader("Diagramme ${kind.replaceFirstChar { it.uppercase() }} (source)") {
                TextButton(onClick = { clipboard.setText(AnnotatedString(source)); actions.say("Diagramme copié") }) { Text("Copier") }
                TextButton(onClick = { actions.saveText(source, "diagramme.mmd") }) { Text("Enregistrer") }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                SelectionContainer { Text(source, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false) }
            }
        }
    }
}

@Composable
private fun TableBlock(b: MdBlock.Table, color: Color, actions: RenderActions) {
    val t = LocalWorkspace.current
    val clipboard = LocalClipboardManager.current
    val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
    // Column widths from the longest cell (bounded): stable, no measuring pass per row.
    val widths = remember(b) {
        (0 until cols).map { c ->
            val longest = (listOf(b.header) + b.rows).maxOfOrNull { r -> r.getOrNull(c)?.let { MessageText.plain(it).length } ?: 0 } ?: 0
            (longest * 8 + 24).coerceIn(72, 320).dp
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BlockHeader("Tableau · ${b.rows.size} ligne(s)") {
            TextButton(onClick = { clipboard.setText(AnnotatedString(MessageText.plainBlocks(listOf(b)))); actions.say("Tableau copié") }) { Text("Copier") }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, t.border, RoundedCornerShape(t.radiusSmall))) {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                TableRow(b.header, b.align, widths, color, actions, header = true)
                b.rows.forEachIndexed { i, r ->
                    HorizontalDivider(color = t.border)
                    TableRow(r, b.align, widths, color, actions, header = false, shaded = i % 2 == 1)
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<MdInline>>, align: List<MdAlign>, widths: List<androidx.compose.ui.unit.Dp>, color: Color, actions: RenderActions, header: Boolean, shaded: Boolean = false) {
    val t = LocalWorkspace.current
    Row(Modifier.background(if (header) t.elevated else if (shaded) t.surface.copy(alpha = 0.6f) else Color.Transparent)) {
        widths.forEachIndexed { c, w ->
            val a = when (align.getOrNull(c)) { MdAlign.RIGHT -> TextAlign.End; MdAlign.CENTER -> TextAlign.Center; else -> TextAlign.Start }
            val style = MaterialTheme.typography.bodyMedium.copy(textAlign = a, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal)
            RichText(cells.getOrNull(c).orEmpty(), style, color, actions, Modifier.width(w).padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- syntax highlighting (small, local, no third-party code)

data class SyntaxColors(val keyword: Color, val string: Color, val comment: Color, val number: Color)

object Syntax {
    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "import", "package", "private", "public",
        "internal", "protected", "override", "suspend", "data", "sealed", "enum", "true", "false", "null", "this", "super", "is", "in", "as", "try", "catch",
        "finally", "throw", "new", "function", "const", "let", "def", "self", "None", "True", "False", "elif", "from", "lambda", "with", "yield", "async", "await",
        "static", "void", "int", "long", "double", "float", "boolean", "String", "struct", "impl", "fn", "mut", "pub", "use", "match", "select", "insert",
        "update", "delete", "where", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "echo", "then", "fi", "esac", "export",
    )

    fun extension(language: String?): String = when (language?.lowercase()) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
        "json" -> "json"; "xml" -> "xml"; "html" -> "html"; "css" -> "css"; "sql" -> "sql"; "bash", "sh", "shell" -> "sh"; "yaml", "yml" -> "yml"
        "markdown", "md" -> "md"; "rust", "rs" -> "rs"; "go" -> "go"; "c" -> "c"; "cpp", "c++" -> "cpp"; "gradle" -> "gradle"
        else -> "txt"
    }

    /** One linear pass: comments, strings, numbers and keywords. Unknown languages get the same safe rules. */
    fun highlight(code: String, language: String?, c: SyntaxColors): AnnotatedString = buildAnnotatedString {
        val src = if (code.length > 60_000) code.take(60_000) else code
        val hash = language?.lowercase() in setOf("python", "py", "bash", "sh", "shell", "yaml", "yml", "ruby", "r", "toml")
        val sql = language?.lowercase() == "sql"
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            val next = src.getOrNull(i + 1)
            when {
                (ch == '/' && next == '/') || (hash && ch == '#') || (sql && ch == '-' && next == '-') -> {
                    val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    withStyle(SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)) { append(src, i, end) }
                    i = end
                }
                ch == '/' && next == '*' -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    withStyle(SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)) { append(src, i, end) }
                    i = end
                }
                ch == '"' || ch == '\'' || ch == '`' -> {
                    var j = i + 1
                    while (j < src.length && src[j] != ch && src[j] != '\n') { if (src[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(src.length)
                    withStyle(SpanStyle(color = c.string)) { append(src, i, end) }
                    i = end
                }
                ch.isDigit() && (i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                    var j = i
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '.' || src[j] == '_')) j++
                    withStyle(SpanStyle(color = c.number)) { append(src, i, j) }
                    i = j
                }
                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    val word = src.substring(i, j)
                    if (word in KEYWORDS) withStyle(SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold)) { append(word) } else append(word)
                    i = j
                }
                else -> { append(ch); i++ }
            }
        }
        if (src.length < code.length) append("\n… (${code.length - src.length} caractères non affichés — copiez ou enregistrez pour tout voir)")
    }
}
