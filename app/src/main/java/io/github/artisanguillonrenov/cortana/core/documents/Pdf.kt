package io.github.artisanguillonrenov.cortana.core.documents

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * PDF pipeline (doc 02 §34.2) on PdfBox: text per page, metadata, page rendering for visual
 * inspection, creation from the neutral [Doc] model, merge / extract / reorder / rotate / delete
 * pages, stamps and page numbers. Encrypted PDFs can be read when their permissions allow it but
 * are never modified (no protection is ever removed). No script, form action or embedded file of
 * a PDF is ever run or opened.
 */
class PdfEngine(private val context: Context) {
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) { if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true } }
    }

    data class Info(
        val pages: Int, val title: String?, val author: String?, val subject: String?, val keywords: String?, val creator: String?, val producer: String?,
        val created: String?, val modified: String?, val encrypted: Boolean, val pageSizes: List<String>, val formFields: List<String>, val attachments: Int,
    )

    data class PageText(val page: Int, val text: String)

    private fun <T> open(bytes: ByteArray, block: (PDDocument) -> T): T {
        init()
        val doc = try { PDDocument.load(bytes) } catch (_: InvalidPasswordException) {
            throw DocumentException("PDF protégé par un mot de passe : lecture impossible sans lui")
        } catch (e: java.io.IOException) { throw DocumentException("PDF illisible : ${e.message}") }
        return doc.use(block)
    }

    private fun save(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    private fun editable(doc: PDDocument) { if (doc.isEncrypted) throw DocumentException("PDF chiffré : Cortana ne le modifie pas (la protection n'est jamais retirée)") }

    private fun checkPages(doc: PDDocument, pages: Collection<Int>) {
        val n = doc.numberOfPages
        pages.firstOrNull { it < 1 || it > n }?.let { throw DocumentException("page $it hors limites (1 à $n)") }
    }

    fun info(bytes: ByteArray): Info = open(bytes) { d ->
        val i = d.documentInformation
        fun cal(c: Calendar?) = c?.let { String.format(java.util.Locale.ROOT, "%tF %<tR", it) }
        val acro = runCatching { d.documentCatalog.acroForm?.fieldTree?.map { f -> "${f.fullyQualifiedName} (${f.fieldType ?: "?"})" } }.getOrNull().orEmpty()
        val files = runCatching { d.documentCatalog.names?.embeddedFiles?.names?.size ?: 0 }.getOrDefault(0)
        Info(
            d.numberOfPages, i.title?.takeIf { it.isNotBlank() }, i.author, i.subject, i.keywords, i.creator, i.producer, cal(i.creationDate), cal(i.modificationDate),
            d.isEncrypted, d.pages.take(20).map { p -> val r = p.mediaBox; "${r.width.toInt()}×${r.height.toInt()} pt" + if (p.rotation != 0) " (rotation ${p.rotation}°)" else "" },
            acro.take(100), files,
        )
    }

    /** Text of [pages] (1-based, all when null), capped at [maxPages]. */
    fun text(bytes: ByteArray, pages: List<Int>? = null, maxPages: Int = 300): List<PageText> = open(bytes) { d ->
        if (d.isEncrypted && !d.currentAccessPermission.canExtractContent()) throw DocumentException("PDF chiffré : l'extraction du texte est interdite par ses permissions")
        val wanted = (pages ?: (1..d.numberOfPages).toList()).also { checkPages(d, it) }.take(maxPages)
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        wanted.map { p -> stripper.startPage = p; stripper.endPage = p; PageText(p, stripper.getText(d).trim()) }
    }

    /** Renders one page (1-based) to PNG for visual inspection (vision, OCR). */
    fun render(bytes: ByteArray, page: Int, dpi: Int = 110): ByteArray = open(bytes) { d ->
        checkPages(d, listOf(page))
        val bmp = PDFRenderer(d).renderImageWithDPI(page - 1, dpi.coerceIn(36, 200).toFloat())
        try { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } finally { bmp.recycle() }
    }

    fun merge(files: List<ByteArray>): ByteArray {
        if (files.size < 2) throw DocumentException("il faut au moins deux PDF à fusionner")
        init()
        return PDDocument().use { out ->
            val opened = files.map { b -> try { PDDocument.load(b) } catch (_: InvalidPasswordException) { throw DocumentException("un des PDF est protégé par un mot de passe") } }
            try {
                opened.forEach { editable(it) }
                val m = PDFMergerUtility()
                opened.forEach { m.appendDocument(out, it) }
                save(out)
            } finally { opened.forEach { it.close() } }
        }
    }

    /** New PDF made of [pages] (1-based) in that order: extraction, split and reordering. */
    fun select(bytes: ByteArray, pages: List<Int>): ByteArray = open(bytes) { d ->
        editable(d); checkPages(d, pages)
        if (pages.isEmpty()) throw DocumentException("aucune page choisie")
        PDDocument().use { out ->
            out.documentInformation = d.documentInformation
            pages.forEach { p -> out.importPage(d.getPage(p - 1)).also { it.rotation = d.getPage(p - 1).rotation } }
            save(out)
        }
    }

    fun rotate(bytes: ByteArray, pages: List<Int>?, degrees: Int): ByteArray = open(bytes) { d ->
        editable(d)
        if (degrees % 90 != 0) throw DocumentException("rotation par multiples de 90° uniquement")
        val list = pages ?: (1..d.numberOfPages).toList(); checkPages(d, list)
        list.forEach { p -> val pg = d.getPage(p - 1); pg.rotation = ((pg.rotation + degrees) % 360 + 360) % 360 }
        save(d)
    }

    fun delete(bytes: ByteArray, pages: List<Int>): ByteArray = open(bytes) { d ->
        editable(d); checkPages(d, pages)
        if (pages.toSet().size >= d.numberOfPages) throw DocumentException("un PDF doit garder au moins une page")
        pages.toSet().sortedDescending().forEach { d.removePage(it - 1) }
        save(d)
    }

    fun setMetadata(bytes: ByteArray, title: String?, author: String?, subject: String?, keywords: String?): ByteArray = open(bytes) { d ->
        editable(d)
        d.documentInformation.apply {
            title?.let { this.title = it }; author?.let { this.author = it }; subject?.let { this.subject = it }; keywords?.let { this.keywords = it }
            modificationDate = Calendar.getInstance()
        }
        save(d)
    }

    /**
     * Adds a text stamp on [pages] (all when null): "header", "footer" or "diagonal" (watermark).
     * `{page}` and `{pages}` are replaced by the page number and the page count.
     */
    fun stamp(bytes: ByteArray, text: String, position: String, pages: List<Int>?): ByteArray = open(bytes) { d ->
        editable(d)
        if (text.isBlank()) throw DocumentException("texte du tampon vide")
        val list = pages ?: (1..d.numberOfPages).toList(); checkPages(d, list)
        val font = font(d)
        list.forEach { p ->
            val page = d.getPage(p - 1)
            val box = page.cropBox
            val t = clean(font, text.replace("{page}", p.toString()).replace("{pages}", d.numberOfPages.toString()))
            PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                when (position) {
                    "diagonal" -> {
                        val size = 54f
                        val w = font.getStringWidth(t) / 1000 * size
                        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.18f })
                        cs.setNonStrokingColor(0.6f, 0f, 0f)
                        cs.beginText(); cs.setFont(font, size)
                        val a = Math.atan2(box.height.toDouble(), box.width.toDouble())
                        val cx = box.lowerLeftX + box.width / 2; val cy = box.lowerLeftY + box.height / 2
                        cs.setTextMatrix(Matrix(Math.cos(a).toFloat(), Math.sin(a).toFloat(), -Math.sin(a).toFloat(), Math.cos(a).toFloat(),
                            (cx - Math.cos(a) * w / 2 + Math.sin(a) * size / 3).toFloat(), (cy - Math.sin(a) * w / 2 - Math.cos(a) * size / 3).toFloat()))
                        cs.showText(t); cs.endText()
                    }
                    "header", "footer" -> {
                        val size = 9f
                        val w = font.getStringWidth(t) / 1000 * size
                        val y = if (position == "header") box.upperRightY - 24 else box.lowerLeftY + 18
                        cs.setNonStrokingColor(0.35f, 0.35f, 0.35f)
                        cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(box.lowerLeftX + (box.width - w) / 2, y); cs.showText(t); cs.endText()
                    }
                    else -> throw DocumentException("position inconnue : $position (header, footer, diagonal)")
                }
            }
        }
        save(d)
    }

    // ------------------------------------------------------------------ creation

    private fun font(d: PDDocument): PDFont = context.assets.open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use { PDType0Font.load(d, it) }

    /** Keeps only characters the embedded font can draw; others become "?" (emoji, CJK…). */
    private fun clean(font: PDFont, s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); val ch = String(Character.toChars(cp)); i += Character.charCount(cp)
            when {
                ch == "\t" -> sb.append("    ")
                cp < 32 -> {}
                runCatching { font.encode(ch) }.isSuccess -> sb.append(ch)
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    /** A document image (PNG/JPEG) placed after the block at [afterBlock] (-1 = at the start). */
    data class Image(val bytes: ByteArray, val afterBlock: Int, val caption: String? = null)

    /** Lays out [doc] on A4 pages: headings, paragraphs with bold/italic, lists, tables, page breaks, images; page numbers in the footer. */
    fun create(doc: Doc, images: List<Image> = emptyList()): ByteArray {
        init()
        return PDDocument().use { d ->
            doc.title?.let { d.documentInformation.title = it }
            d.documentInformation.creator = "Cortana"
            d.documentInformation.creationDate = Calendar.getInstance()
            Layout(d, font(d)).run {
                images.filter { it.afterBlock < 0 }.forEach { image(it) }
                doc.blocks.forEachIndexed { i, b -> block(b); images.filter { it.afterBlock == i }.forEach { image(it) } }
                finish()
            }
            val total = d.numberOfPages
            val f = font(d)
            d.pages.forEachIndexed { i, p ->
                PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    val t = "${i + 1} / $total"; val w = f.getStringWidth(t) / 1000 * 9
                    cs.setNonStrokingColor(0.45f, 0.45f, 0.45f); cs.beginText(); cs.setFont(f, 9f); cs.newLineAtOffset((PAGE.width - w) / 2, 28f); cs.showText(t); cs.endText()
                }
            }
            save(d)
        }
    }

    private data class Word(val text: String, val bold: Boolean, val italic: Boolean)

    private inner class Layout(val d: PDDocument, val font: PDFont) {
        var page: PDPage? = null
        var cs: PDPageContentStream? = null
        var y = 0f
        val left = MARGIN; val right = PAGE.width - MARGIN; val bottom = MARGIN + 14
        var ordinal = IntArray(3)

        fun newPage() {
            cs?.close()
            page = PDPage(PAGE).also { d.addPage(it) }
            cs = PDPageContentStream(d, page)
            y = PAGE.height - MARGIN
        }

        fun ensure(h: Float) { if (cs == null || y - h < bottom) newPage() }

        fun width(t: String, size: Float) = font.getStringWidth(t) / 1000 * size

        fun words(text: String, bold: Boolean = false): List<Word> = Markdownish.spans(text).flatMap { (t, b, i) ->
            clean(font, t).split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }.map { Word(it, b || bold, i) }
        }

        /** Greedy line breaking; a word wider than the line is cut. */
        fun lines(ws: List<Word>, size: Float, maxW: Float): List<List<Word>> {
            val out = mutableListOf<MutableList<Word>>(); var cur = mutableListOf<Word>(); var w = 0f
            fun push() { while (cur.lastOrNull()?.text?.isBlank() == true) cur.removeAt(cur.lastIndex); out += cur; cur = mutableListOf(); w = 0f }
            for (word in ws) {
                if (word.text == "\n") { push(); continue }
                if (cur.isEmpty() && word.text.isBlank()) continue
                val ww = width(word.text, size)
                if (w + ww <= maxW) { cur += word; w += ww; continue }
                if (word.text.isBlank()) { push(); continue }
                if (cur.isNotEmpty()) push()
                if (ww <= maxW) { cur += word; w = ww; continue }
                var rest = word.text
                while (rest.isNotEmpty()) {
                    var n = rest.length
                    while (n > 1 && width(rest.take(n), size) > maxW) n--
                    cur += word.copy(text = rest.take(n)); w = width(rest.take(n), size); rest = rest.drop(n)
                    if (rest.isNotEmpty()) push()
                }
            }
            if (cur.isNotEmpty()) push()
            return out
        }

        fun drawLine(ws: List<Word>, x0: Float, baseline: Float, size: Float) {
            val c = cs!!
            var x = x0
            for (w in ws) {
                c.beginText(); c.setFont(font, size)
                if (w.bold) { c.setRenderingMode(RenderingMode.FILL_STROKE); c.setLineWidth(size / 28) } else c.setRenderingMode(RenderingMode.FILL)
                c.setTextMatrix(Matrix(1f, 0f, if (w.italic) 0.2f else 0f, 1f, x, baseline))
                c.showText(w.text); c.endText()
                x += width(w.text, size)
            }
            c.setRenderingMode(RenderingMode.FILL)
        }

        fun text(t: String, size: Float, indent: Float = 0f, bold: Boolean = false, gapBefore: Float = 0f, gapAfter: Float = size * 0.6f, marker: String? = null) {
            val lead = size * 1.3f
            val ls = lines(words(t, bold), size, right - left - indent)
            ensure(gapBefore + lead * minOf(ls.size, 2).coerceAtLeast(1))
            y -= gapBefore
            ls.forEachIndexed { i, l ->
                ensure(lead)
                y -= lead
                if (i == 0 && marker != null) drawLine(listOf(Word(clean(font, marker), false, false)), left + indent - width(marker, size) - 5, y + size * 0.25f, size)
                drawLine(l, left + indent, y + size * 0.25f, size)
            }
            y -= gapAfter
        }

        fun block(b: Block) {
            if (b !is Block.Bullet) ordinal = IntArray(3)
            when (b) {
                is Block.Heading -> { val s = floatArrayOf(20f, 16f, 13f)[b.level.coerceIn(1, 3) - 1]; text(b.text, s, bold = true, gapBefore = if (y < PAGE.height - MARGIN - 1) s * 0.8f else 0f, gapAfter = s * 0.4f) }
                is Block.Para -> text(b.text, 11f)
                is Block.Bullet -> {
                    val l = b.level.coerceIn(0, 2)
                    for (k in l + 1 until 3) ordinal[k] = 0
                    val marker = if (b.ordered) "${++ordinal[l]}." else if (l == 0) "•" else "–"
                    text(b.text, 11f, indent = 18f + 18f * l, gapAfter = 2f, marker = marker)
                }
                is Block.Table -> table(b.rows)
                Block.PageBreak -> newPage()
            }
        }

        fun table(rows: List<List<String>>) {
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.size }.coerceAtLeast(1)
            val size = if (cols > 5) 8.5f else 10f
            val avail = right - left
            // Column widths follow content length, each at least 8% of the table.
            val want = (0 until cols).map { c -> rows.maxOf { r -> width(clean(font, Markdownish.plain(r.getOrElse(c) { "" })), size) }.coerceIn(avail * 0.08f, avail * 0.6f) }
            val ws = want.map { it * avail / want.sum() }
            val pad = 4f; val lead = size * 1.25f
            y -= 4
            rows.forEachIndexed { ri, r ->
                val cells = (0 until cols).map { c -> lines(words(r.getOrElse(c) { "" }, bold = ri == 0), size, ws[c] - 2 * pad) }
                val h = (cells.maxOf { it.size }.coerceAtLeast(1)) * lead + 2 * pad
                ensure(h)
                val c = cs!!
                if (ri == 0) { c.setNonStrokingColor(0.9f, 0.92f, 0.96f); c.addRect(left, y - h, avail, h); c.fill(); c.setNonStrokingColor(0f, 0f, 0f) }
                var x = left
                cells.forEachIndexed { ci, ls ->
                    ls.forEachIndexed { li, l -> drawLine(l, x + pad, y - pad - lead * (li + 1) + size * 0.25f, size) }
                    x += ws[ci]
                }
                c.setStrokingColor(0.5f, 0.5f, 0.5f); c.setLineWidth(0.5f)
                c.addRect(left, y - h, avail, h); c.stroke()
                x = left; for (ci in 0 until cols - 1) { x += ws[ci]; c.moveTo(x, y); c.lineTo(x, y - h); c.stroke() }
                c.setStrokingColor(0f, 0f, 0f)
                y -= h
            }
            y -= 10
        }

        fun image(img: Image) {
            val x = runCatching { PDImageXObject.createFromByteArray(d, img.bytes, "image") }.getOrElse { throw DocumentException("image illisible (PNG ou JPEG attendu)") }
            val maxW = right - left; val maxH = (PAGE.height - 2 * MARGIN) * 0.6f
            val scale = minOf(maxW / x.width, maxH / x.height, 1f)
            val w = x.width * scale; val h = x.height * scale
            ensure(h + 8)
            y -= h + 4
            cs!!.drawImage(x, left + (maxW - w) / 2, y, w, h)
            y -= 6
            img.caption?.let { text("*$it*", 9f) }
        }

        fun finish() { if (cs == null) newPage(); cs?.close() }
    }

    companion object {
        val PAGE: PDRectangle = PDRectangle.A4
        const val MARGIN = 56f

        /** "1-3,5" → [1,2,3,5]. */
        fun pageList(spec: String?): List<Int>? {
            if (spec.isNullOrBlank()) return null
            return spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
                val m = Regex("(\\d+)\\s*-\\s*(\\d+)").matchEntire(part)
                if (m != null) { val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); if (b >= a) (a..b).toList() else (a downTo b).toList() }
                else listOf(part.toIntOrNull() ?: throw DocumentException("pages invalides : $spec (ex. 1-3,5)"))
            }.also { if (it.size > 5_000) throw DocumentException("trop de pages demandées") }
        }
    }
}
