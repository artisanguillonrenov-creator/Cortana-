package io.github.artisanguillonrenov.cortana.core.documents

import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.memory.ArtifactEntity
import io.github.artisanguillonrenov.cortana.executors.files.FileExecutor
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File

/**
 * Document & Data Workbench (doc 02 §34) — the one owner of document formats. Sources are
 * artifacts ("artifact:<id>") or files of the owner's working folder; every result is a new
 * artifact carrying its provenance (task, capability, operation, sources and their hashes), and
 * is written into the working folder only when asked (a separate, approved effect). Sources are
 * never modified in place otherwise, and nothing in a document is ever executed.
 */
class DocumentService(
    private val pdf: PdfEngine,
    private val artifacts: ArtifactService,
    private val folder: FileExecutor,
    private val cacheDir: File,
    private val maxBytes: Long = 50_000_000,
) {
    data class Source(val ref: String, val name: String, val bytes: ByteArray, val format: String, val provenance: String, val sha256: String) {
        val label get() = if (provenance.startsWith("artifact:")) "$name (${provenance})" else name
    }

    data class Output(val artifact: ArtifactEntity, val savedTo: String?) {
        fun describe() = "« ${artifact.name} » : artefact ${artifact.artifactId} (${artifact.sizeBytes} octets, sha256 ${artifact.sha256.take(16)}…)" + (savedTo?.let { "\nEnregistré aussi dans le dossier de travail : $it" } ?: "")
    }

    /** Who produced an output, for its provenance. */
    data class Origin(val taskId: String?, val capability: String, val operation: String)

    // ------------------------------------------------------------------ sources

    suspend fun load(ref: String): Source {
        val r = ref.trim()
        if (r.isEmpty()) throw DocumentException("source vide")
        val id = r.removePrefix("artifact:").trim()
        val art = if (r.startsWith("artifact:") || (!r.contains('/') && !r.contains('.'))) artifacts.get(id)?.takeIf { !it.deleted } else null
        if (r.startsWith("artifact:") && art == null) throw DocumentException("artefact introuvable : $id")
        val (name, bytes, prov) = if (art != null) {
            if (!artifacts.verify(art.artifactId)) throw DocumentException("artefact ${art.artifactId} altéré (empreinte différente) : refusé")
            val f = artifacts.file(art)
            if (f.length() > maxBytes) throw DocumentException("fichier trop volumineux (${f.length()} octets, maximum $maxBytes)")
            Triple(art.name, withContext(Dispatchers.IO) { f.readBytes() }, "artifact:${art.artifactId}")
        } else {
            val (n, b) = try { folder.readBytes(r, maxBytes) } catch (e: IllegalStateException) { throw DocumentException(e.message ?: "lecture impossible") }
                catch (e: IllegalArgumentException) { throw DocumentException(e.message ?: "chemin refusé") }
            Triple(n, b, "fichier:$r")
        }
        return Source(r, name, bytes, detect(name, bytes), prov, Hash.sha256Bytes(bytes))
    }

    /** Format from content first (magic bytes), then from the extension. */
    fun detect(name: String, b: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val head = String(b, 0, minOf(b.size, 512), Charsets.ISO_8859_1)
        return when {
            head.startsWith("%PDF") -> "pdf"
            ImageSize.mime(b) == "image/png" -> "png"
            ImageSize.mime(b) == "image/jpeg" -> "jpeg"
            b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> when {
                ext in setOf("docx", "xlsx", "pptx") -> ext
                else -> runCatching { Pkg.read(b).keys }.getOrNull()?.let { k ->
                    when { "word/document.xml" in k -> "docx"; "xl/workbook.xml" in k -> "xlsx"; "ppt/presentation.xml" in k -> "pptx"; else -> "zip" }
                } ?: "zip"
            }
            Archives.kind(name) != null -> Archives.kind(name)!!
            ext in setOf("csv", "tsv", "json", "md", "markdown", "html", "htm", "txt", "log", "xml", "yaml", "yml") -> when (ext) { "markdown" -> "md"; "htm" -> "html"; "log", "xml", "yaml", "yml" -> "txt"; else -> ext }
            head.trimStart().startsWith("<!DOCTYPE html", true) || head.trimStart().startsWith("<html", true) -> "html"
            head.trimStart().let { it.startsWith("[") || it.startsWith("{") } -> "json"
            b.take(4096).none { it == 0.toByte() } -> "txt"
            else -> "binary"
        }
    }

    private fun text(s: Source) = String(s.bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    suspend fun folderHas(path: String): Boolean = folder.exists(path)

    // ------------------------------------------------------------------ outputs

    suspend fun emit(bytes: ByteArray, name: String, origin: Origin, sources: List<Source>, saveTo: String?, extra: Map<String, String> = emptyMap(), type: String = "document"): Output {
        val safe = ArtifactService.safeName(name)
        val tmp = File(cacheDir, "doc-${Ids.new()}").apply { withContext(Dispatchers.IO) { writeBytes(bytes) } }
        val meta = linkedMapOf("operation" to origin.operation, "format" to safe.substringAfterLast('.', "").lowercase())
        sources.forEachIndexed { i, s -> meta["source${i + 1}"] = s.provenance; meta["source${i + 1}Sha256"] = s.sha256 }
        meta.putAll(extra)
        val art = artifacts.register(tmp, type, safe, origin.taskId, origin.capability, meta, sources.map { it.provenance }, move = true)
        saveTo?.takeIf { it.isNotBlank() }?.let { p ->
            try { folder.writeBytes(p, bytes) } catch (e: IllegalStateException) { throw DocumentException("artefact créé (${art.artifactId}) mais écriture dans le dossier impossible : ${e.message}") }
        }
        return Output(art, saveTo?.takeIf { it.isNotBlank() })
    }

    // ------------------------------------------------------------------ neutral models

    /** Any readable source as a document (headings, paragraphs, lists, tables, page breaks). */
    fun asDoc(s: Source, pages: List<Int>? = null): Doc = when (s.format) {
        "docx" -> Docx.read(s.bytes)
        "md", "txt" -> Markdownish.parse(text(s), s.name.substringBeforeLast('.'))
        "html" -> Html.toDoc(text(s))
        "pdf" -> {
            val info = pdf.info(s.bytes)
            val blocks = mutableListOf<Block>()
            pdf.text(s.bytes, pages).forEachIndexed { i, p ->
                if (i > 0) blocks += Block.PageBreak
                p.text.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }.forEach { blocks += Block.Para(it) }
            }
            Doc(info.title ?: s.name.substringBeforeLast('.'), blocks)
        }
        "pptx" -> Doc(s.name.substringBeforeLast('.'), Pptx.read(s.bytes).flatMapIndexed { i, sl ->
            listOfNotNull(if (i > 0) Block.PageBreak else null, Block.Heading(1, sl.title ?: "Diapositive ${sl.index}")) + sl.paragraphs.map { Block.Bullet(it) } +
                sl.charts.map { Block.Para("[${it}]") }
        })
        "xlsx", "csv", "tsv", "json" -> Doc(s.name.substringBeforeLast('.'), asTables(s).flatMap { (n, t) -> listOf(Block.Heading(2, n), Block.Table(listOf(t.header) + t.rows)) })
        else -> throw DocumentException("format non pris en charge pour cette opération : ${s.format} (${s.name})")
    }

    /** Tabular sources (and the tables of documents) as named data tables. */
    fun asTables(s: Source, sheet: String? = null): List<Pair<String, DataTable>> = when (s.format) {
        "xlsx" -> Xlsx.read(s.bytes).filter { sheet == null || it.name.equals(sheet, true) }.also { if (it.isEmpty()) throw DocumentException("feuille « $sheet » introuvable") }.map { it.name to it.table() }
        "csv" -> listOf(s.name to Tabular.parseCsv(text(s)))
        "tsv" -> listOf(s.name to Tabular.parseCsv(text(s), '\t'))
        "json" -> listOf(s.name to Tabular.parseJson(text(s)))
        "docx", "md", "txt", "html", "pptx" -> asDoc(s).blocks.filterIsInstance<Block.Table>().filter { it.rows.isNotEmpty() }
            .mapIndexed { i, t -> "Tableau ${i + 1}" to DataTable(t.rows.first(), t.rows.drop(1)) }
            .ifEmpty { throw DocumentException("aucun tableau dans ${s.name}") }
        else -> throw DocumentException("pas de données tabulaires dans ${s.name} (${s.format})")
    }

    /** A document as slides: one per `#`/`##` heading (bullets and paragraphs below it), or the slides of a deck. */
    fun asSlides(s: Source): List<SlideSpec> = when (s.format) {
        "pptx" -> Pptx.read(s.bytes).map { SlideSpec(it.title ?: "Diapositive ${it.index}", it.paragraphs) }
        else -> outlineSlides(asDoc(s))
    }

    fun outlineSlides(d: Doc): List<SlideSpec> {
        val out = mutableListOf<SlideSpec>(); var title: String? = null; val items = mutableListOf<String>()
        fun flush() { title?.let { out += SlideSpec(it, items.toList()) } ?: if (items.isNotEmpty()) out += SlideSpec(d.title ?: "Diapositive", items.toList()) else Unit; items.clear() }
        for (b in d.blocks) when (b) {
            is Block.Heading -> if (b.level <= 2) { flush(); title = b.text } else items += b.text
            is Block.Para -> items += b.text
            is Block.Bullet -> items += "  ".repeat(b.level) + b.text
            is Block.Table -> b.rows.forEach { items += it.joinToString(" · ") }
            Block.PageBreak -> {}
        }
        flush()
        if (out.isEmpty()) throw DocumentException("rien à mettre en diapositives")
        if (out.size > 200) throw DocumentException("trop de diapositives (200 au plus)")
        return out
    }

    // ------------------------------------------------------------------ writers

    val docFormats = listOf("docx", "pdf", "md", "txt", "html", "pptx")
    val dataFormats = listOf("xlsx", "csv", "json", "md", "pdf", "docx", "html")

    fun writeDoc(d: Doc, format: String): ByteArray = when (format) {
        "docx" -> Docx.write(d)
        "pdf" -> pdf.create(d)
        "md" -> (d.title?.takeIf { t -> (d.blocks.firstOrNull() as? Block.Heading)?.text != t }?.let { "<!-- $it -->\n\n" }.orEmpty() + d.text()).toByteArray()
        "txt" -> Markdownish.plain(d.text()).toByteArray()
        "html" -> Html.fromDoc(d).toByteArray()
        "pptx" -> Pptx.write(d.title, outlineSlides(d))
        else -> throw DocumentException("format de sortie inconnu : $format (${docFormats.joinToString()})")
    }

    fun writeTables(tables: List<Pair<String, DataTable>>, format: String, title: String?): ByteArray = when (format) {
        "xlsx" -> Xlsx.write(tables.map { (n, t) -> Sheet.of(n, t) }, title)
        "csv" -> Tabular.writeCsv(tables.single("CSV").second).toByteArray()
        "json" -> Tabular.writeJson(tables.single("JSON").second).toByteArray()
        "md", "pdf", "docx", "html" -> writeDoc(Doc(title, tables.flatMap { (n, t) -> listOf(Block.Heading(2, n), Block.Table(listOf(t.header) + t.rows)) }), format)
        else -> throw DocumentException("format de sortie inconnu : $format (${dataFormats.joinToString()})")
    }

    private fun <T> List<T>.single(what: String): T = if (size == 1) first() else throw DocumentException("$what ne contient qu'un tableau : choisissez la feuille (sheet)")

    /** Converts any supported source to [format]; tabular sources keep their cells. */
    fun convert(s: Source, format: String, sheet: String? = null): ByteArray {
        val f = format.lowercase().removePrefix(".")
        if (f == s.format) throw DocumentException("le document est déjà au format $f")
        val tabular = s.format in setOf("xlsx", "csv", "tsv", "json")
        return when {
            tabular && f in dataFormats -> writeTables(asTables(s, sheet), f, s.name.substringBeforeLast('.'))
            f in setOf("xlsx", "csv", "json") -> writeTables(asTables(s, sheet), f, s.name.substringBeforeLast('.'))
            s.format == "pptx" && f == "pdf" -> pptxToPdf(s)
            else -> writeDoc(asDoc(s), f)
        }
    }

    /** Deck → PDF: one page per slide with its text and its images. */
    private fun pptxToPdf(s: Source): ByteArray {
        val parts = Pkg.read(s.bytes)
        val slides = Pptx.read(s.bytes)
        val blocks = mutableListOf<Block>(); val images = mutableListOf<PdfEngine.Image>()
        val order = Pkg.rels(parts, "ppt/presentation.xml").filterValues { it.second.endsWith("/slide") }
        val pres = String(parts["ppt/presentation.xml"]!!)
        val paths = Regex("<p:sldId [^>]*r:id=\"([^\"]+)\"").findAll(pres).mapNotNull { order[it.groupValues[1]]?.first }.toList()
        slides.forEachIndexed { i, sl ->
            if (i > 0) blocks += Block.PageBreak
            blocks += Block.Heading(1, sl.title ?: "Diapositive ${sl.index}")
            sl.paragraphs.forEach { blocks += Block.Bullet(it) }
            sl.charts.forEach { blocks += Block.Para("*[$it]*") }
            paths.getOrNull(i)?.let { p -> Pkg.rels(parts, p).values.filter { it.second.endsWith("/image") }.mapNotNull { parts[it.first] }.filter { ImageSize.mime(it) != null }.forEach { images += PdfEngine.Image(it, blocks.lastIndex) } }
        }
        return pdf.create(Doc(s.name.substringBeforeLast('.'), blocks), images)
    }

    // ------------------------------------------------------------------ reading for the model

    /** What `document.read` shows: structure first, bounded, with the way to read more. */
    fun render(s: Source, pages: List<Int>? = null, sheet: String? = null, range: String? = null, maxChars: Int = 20_000): String {
        val body = when (s.format) {
            "pdf" -> {
                val i = pdf.info(s.bytes)
                buildString {
                    append("PDF, ${i.pages} page(s)").append(i.title?.let { " — « $it »" } ?: "").append(if (i.encrypted) " — chiffré" else "").append('\n')
                    val ps = pdf.text(s.bytes, pages)
                    ps.forEach { p -> append("\n--- page ${p.page} ---\n").append(p.text.ifBlank { "(pas de texte : page scannée ? utilisez pdf_read mode=render puis l'analyse d'image)" }) }
                }
            }
            "xlsx" -> spreadsheet(s, sheet, range)
            "csv", "tsv", "json" -> asTables(s).joinToString("\n\n") { (n, t) -> "$n\n" + Tabular.describe(t) + "\n\n" + preview(t, 30) }
            "pptx" -> Pptx.read(s.bytes).joinToString("\n\n") { sl ->
                "Diapositive ${sl.index} : ${sl.title ?: "(sans titre)"}" + sl.paragraphs.joinToString("") { "\n  • $it" } +
                    (if (sl.images > 0) "\n  [${sl.images} image(s)]" else "") + sl.charts.joinToString("") { "\n  [$it]" }
            }
            "zip", "tar", "tgz", "gz" -> archiveListing(s)
            "png", "jpeg" -> ImageSize.of(s.bytes).let { "Image ${s.format.uppercase()} ${it?.first}×${it?.second} px : son contenu se lit avec l'analyse d'image, pas comme texte." }
            "binary" -> throw DocumentException("fichier binaire non pris en charge : ${s.name}")
            else -> asDoc(s).let { d -> (d.title?.let { "Titre : $it\n\n" } ?: "") + d.text() }
        }
        return if (body.length <= maxChars) body else body.take(maxChars) + "\n\n…(tronqué : ${body.length - maxChars} caractères de plus ; lisez par pages, feuille ou plage)"
    }

    fun spreadsheet(s: Source, sheet: String?, range: String?): String {
        val insp = Xlsx.inspect(s.bytes)
        val targets = if (sheet == null && range == null) insp.sheets else insp.sheets.filter { sheet == null || it.name.equals(sheet, true) }.ifEmpty {
            throw DocumentException("feuille « $sheet » introuvable (feuilles : ${insp.sheets.joinToString { it.name }})")
        }.let { if (range != null && sheet == null) it.take(1) else it }
        return buildString {
            append("Classeur : ${insp.sheets.size} feuille(s) — ${insp.sheets.joinToString { "${it.name} (${it.rows.size}×${it.rows.maxOfOrNull { r -> r.size } ?: 0})" }}\n")
            for (sh in targets) {
                append("\n## ${sh.name}\n")
                insp.charts[sh.name]?.forEach { append("[$it]\n") }
                if (range != null) {
                    val ref = range.uppercase().replace("$", "")
                    val (a, b) = ref.split(':').let { l -> l[0] to l.getOrElse(1) { l[0] } }
                    val r0 = minOf(Xlsx.rowIndex(a), Xlsx.rowIndex(b)); val c0 = minOf(Xlsx.colIndex(a), Xlsx.colIndex(b))
                    append(grid(Xlsx.range(sh, ref), r0, c0))
                } else append(grid(sh.rows.take(40), 0, 0)).append(if (sh.rows.size > 40) "\n…(${sh.rows.size - 40} lignes de plus : précisez range, ex. A41:F80)" else "")
            }
        }
    }

    /** Cells with their A1 coordinates; formulas are shown with their value. */
    private fun grid(rows: List<List<Cell>>, r0: Int, c0: Int): String {
        val w = (rows.maxOfOrNull { it.size } ?: 0).coerceAtMost(26)
        if (rows.isEmpty() || w == 0) return "(vide)"
        fun show(c: Cell) = when (c) { is Cell.Formula -> "${c.cached ?: "?"} (=${c.f})"; else -> c.display() }.replace("|", "\\|").replace("\n", " ").take(80)
        return buildString {
            append("| | ").append((0 until w).joinToString(" | ") { Xlsx.col(c0 + it) }).append(" |\n|---|").append("---|".repeat(w)).append('\n')
            rows.forEachIndexed { i, r -> append("| ${r0 + i + 1} | ").append((0 until w).joinToString(" | ") { show(r.getOrElse(it) { Cell.Empty }) }).append(" |\n") }
        }.trimEnd()
    }

    fun preview(t: DataTable, n: Int): String = buildString {
        append("| ").append(t.header.joinToString(" | ")).append(" |\n|").append("---|".repeat(t.header.size.coerceAtLeast(1))).append('\n')
        t.rows.take(n).forEach { r -> append("| ").append(t.header.indices.joinToString(" | ") { t.cell(r, it).replace("|", "\\|").take(80) }).append(" |\n") }
        if (t.rows.size > n) append("…(${t.rows.size - n} lignes de plus)")
    }.trimEnd()

    fun archiveListing(s: Source): String {
        val entries = Archives.read(s.name, s.bytes, keepBytes = false)
        val total = entries.sumOf { it.size }
        return "Archive ${s.format} : ${entries.size} fichier(s), ${total} octets décompressés\n" +
            entries.take(300).joinToString("\n") { e -> "${e.path}  (${e.size} o)" + if (EXECUTABLE.containsMatchIn(e.path)) "  ⚠️ exécutable (jamais ouvert)" else "" } +
            if (entries.size > 300) "\n…(${entries.size - 300} de plus)" else ""
    }

    // ------------------------------------------------------------------ comparison

    /** Line diff of two documents' text; spreadsheets and tables are compared cell by cell. */
    fun compare(a: Source, b: Source, maxLines: Int = 400): String {
        val tabular = setOf("xlsx", "csv", "tsv", "json")
        if (a.format in tabular && b.format in tabular) {
            val sa = cells(a); val sb = cells(b)
            // One sheet each (e.g. a workbook and its CSV export): compare them whatever their names.
            val pairs = if (sa.size == 1 && sb.size == 1) listOf("" to (sa.values.first() to sb.values.first()))
                else (sa.keys + sb.keys).distinct().map { n -> n to (sa[n].orEmpty() to sb[n].orEmpty()) }
            val changes = pairs.flatMap { (sheet, ab) ->
                val (ca, cb) = ab
                (ca.keys + cb.keys).distinct().sortedWith(compareBy({ Xlsx.rowIndex(it) }, { Xlsx.colIndex(it) }))
                    .mapNotNull { k -> val x = ca[k].orEmpty(); val y = cb[k].orEmpty(); if (x == y) null else "${if (sheet.isEmpty()) "" else "$sheet!"}$k : ${x.ifEmpty { "(vide)" }} → ${y.ifEmpty { "(vide)" }}" }
            }
            return if (changes.isEmpty()) "Aucune différence de contenu entre ${a.label} et ${b.label}."
            else "${changes.size} cellule(s) différente(s) entre ${a.label} et ${b.label} :\n" + changes.take(maxLines).joinToString("\n") + if (changes.size > maxLines) "\n…(${changes.size - maxLines} de plus)" else ""
        }
        fun lines(s: Source) = when (s.format) { "pdf" -> pdf.text(s.bytes).joinToString("\n") { it.text }; else -> asDoc(s).text() }
        val ta = RawText(lines(a).toByteArray()); val tb = RawText(lines(b).toByteArray())
        val edits = HistogramDiff().diff(RawTextComparator.WS_IGNORE_TRAILING, ta, tb)
        if (edits.isEmpty()) return "Aucune différence de texte entre ${a.label} et ${b.label}."
        val removed = edits.sumOf { it.lengthA }; val added = edits.sumOf { it.lengthB }
        val same = (ta.size() - removed).coerceAtLeast(0)
        val sim = if (ta.size() + tb.size() == 0) 100 else (200 * same / (ta.size() + tb.size()))
        val out = StringBuilder("${edits.size} zone(s) modifiée(s) : −$removed ligne(s), +$added ligne(s) ; ~$sim % du texte identique.\n")
        var shown = 0
        for (e in edits) {
            if (shown > maxLines) { out.append("…(différences suivantes non affichées)\n"); break }
            out.append("@@ ${a.name} l.${e.beginA + 1}–${e.endA} / ${b.name} l.${e.beginB + 1}–${e.endB} (${kind(e)})\n")
            for (i in e.beginA until e.endA) { out.append("- ").append(ta.getString(i)).append('\n'); shown++ }
            for (i in e.beginB until e.endB) { out.append("+ ").append(tb.getString(i)).append('\n'); shown++ }
        }
        return out.toString().trimEnd()
    }

    private fun kind(e: Edit) = when (e.type) { Edit.Type.INSERT -> "ajout"; Edit.Type.DELETE -> "suppression"; else -> "modification" }

    /** Non-empty cells by sheet (or table) name, then A1 reference. */
    private fun cells(s: Source): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        if (s.format == "xlsx") Xlsx.read(s.bytes).forEach { sh ->
            out[sh.name] = LinkedHashMap<String, String>().also { m -> sh.rows.forEachIndexed { r, row -> row.forEachIndexed { c, v -> v.display().takeIf { it.isNotEmpty() }?.let { m["${Xlsx.col(c)}${r + 1}"] = it } } } }
        }
        else asTables(s).forEach { (n, t) ->
            out[n] = LinkedHashMap<String, String>().also { m -> (listOf(t.header) + t.rows).forEachIndexed { r, row -> row.forEachIndexed { c, v -> if (v.isNotEmpty()) m["${Xlsx.col(c)}${r + 1}"] = v } } }
        }
        return out
    }

    companion object {
        val EXECUTABLE = Regex("(?i)\\.(apk|aab|dex|jar|exe|msi|bat|cmd|com|scr|ps1|sh|bash|elf|so|dll|dylib|app|deb|rpm|js|vbs|wsf|hta|lnk)$")
    }
}

/** HTML ⇄ neutral document (jsoup; scripts, styles and forms are dropped, links keep their text). */
object Html {
    fun toDoc(html: String): Doc {
        val d = Jsoup.parse(html)
        d.select("script, style, noscript, template, form, iframe, object, embed, svg").remove()
        val blocks = mutableListOf<Block>()
        fun inline(e: Element): String = e.text().trim()
        fun walk(e: Element) {
            for (c in e.children()) when (c.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> inline(c).takeIf { it.isNotEmpty() }?.let { blocks += Block.Heading(c.tagName()[1].digitToInt().coerceAtMost(3), it) }
                "p", "blockquote", "pre" -> inline(c).takeIf { it.isNotEmpty() }?.let { blocks += Block.Para(it) }
                "ul", "ol" -> list(c, 0, blocks)
                "table" -> c.select("tr").map { tr -> tr.select("> th, > td").map { it.text().trim() } }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { blocks += Block.Table(it) }
                "br", "hr" -> {}
                else -> if (c.children().isEmpty()) inline(c).takeIf { it.isNotEmpty() }?.let { blocks += Block.Para(it) } else walk(c)
            }
        }
        walk(d.body())
        return Doc(d.title().ifBlank { null } ?: (blocks.firstOrNull() as? Block.Heading)?.text, blocks)
    }

    private fun list(e: Element, level: Int, out: MutableList<Block>) {
        for (li in e.children().filter { it.tagName() == "li" }) {
            val own = li.ownText().trim().ifEmpty { li.children().filter { it.tagName() !in setOf("ul", "ol") }.joinToString(" ") { it.text() }.trim() }
            if (own.isNotEmpty()) out += Block.Bullet(own, level.coerceAtMost(2), ordered = e.tagName() == "ol")
            li.children().filter { it.tagName() in setOf("ul", "ol") }.forEach { list(it, level + 1, out) }
        }
    }

    private fun inl(t: String) = Markdownish.spans(t).joinToString("") { (s, b, i) -> val x = org.jsoup.nodes.Entities.escape(s); when { b -> "<strong>$x</strong>"; i -> "<em>$x</em>"; else -> x } }

    fun fromDoc(d: Doc): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"fr\"><head><meta charset=\"utf-8\"><title>").append(org.jsoup.nodes.Entities.escape(d.title ?: "Document")).append("</title>")
        append("<style>body{font-family:sans-serif;max-width:48em;margin:2em auto;line-height:1.5}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}th{background:#eef1f6}</style></head><body>\n")
        var open: Boolean? = null
        fun close() { open?.let { append(if (it) "</ol>\n" else "</ul>\n") }; open = null }
        for (b in d.blocks) {
            if (b !is Block.Bullet) close()
            when (b) {
                is Block.Heading -> append("<h${b.level}>").append(inl(b.text)).append("</h${b.level}>\n")
                is Block.Para -> append("<p>").append(inl(b.text)).append("</p>\n")
                is Block.Bullet -> { if (open != b.ordered) { close(); append(if (b.ordered) "<ol>\n" else "<ul>\n"); open = b.ordered }; append("<li>").append(inl(b.text)).append("</li>\n") }
                is Block.Table -> { append("<table>\n"); b.rows.forEachIndexed { i, r -> append("<tr>").append(r.joinToString("") { c -> if (i == 0) "<th>${inl(c)}</th>" else "<td>${inl(c)}</td>" }).append("</tr>\n") }; append("</table>\n") }
                Block.PageBreak -> append("<hr>\n")
            }
        }
        close()
        append("</body></html>\n")
    }
}
