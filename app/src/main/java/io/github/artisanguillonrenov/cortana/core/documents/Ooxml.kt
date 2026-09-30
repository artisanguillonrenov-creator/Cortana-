package io.github.artisanguillonrenov.cortana.core.documents

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Office Open XML without a heavy library (Apache POI does not fit Android well): packages are ZIP
 * files of XML parts, read and written here directly. Readers are tolerant (unknown elements are
 * skipped), writers produce minimal valid packages (validated with LibreOffice in the tests), and
 * editors change only the parts they must so the owner's formatting survives.
 */
internal object Pkg {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val RT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun read(bytes: ByteArray, maxEntries: Int = 5_000, maxTotal: Long = 300_000_000): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>(); var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val n = e.name.replace('\\', '/').trimStart('/')
                    if (n.split('/').any { it == ".." }) throw DocumentException("chemin interdit dans le document : ${e.name}")
                    if (out.size >= maxEntries) throw DocumentException("document trop complexe")
                    val buf = ByteArrayOutputStream(); val tmp = ByteArray(64 * 1024); var k: Int
                    while (z.read(tmp).also { k = it } >= 0) { total += k; if (total > maxTotal) throw DocumentException("document trop volumineux une fois décompressé"); buf.write(tmp, 0, k) }
                    out[n] = buf.toByteArray()
                }
            }
        } catch (e: java.util.zip.ZipException) { throw DocumentException("fichier Office illisible (archive invalide)") }
        if ("[Content_Types].xml" !in out) throw DocumentException("ce n'est pas un document Office Open XML")
        return out
    }

    fun write(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val ordered = listOfNotNull(parts.keys.firstOrNull { it == "[Content_Types].xml" }) + parts.keys.filter { it != "[Content_Types].xml" }
            for (k in ordered) { z.putNextEntry(ZipEntry(k)); z.write(parts[k]!!); z.closeEntry() }
        }
        return out.toByteArray()
    }

    fun dom(bytes: ByteArray): Document {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        if (head.contains("<!DOCTYPE", ignoreCase = true)) throw DocumentException("déclaration DOCTYPE refusée (protection XXE)")
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        return try { f.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) } catch (e: Exception) { throw DocumentException("XML illisible dans le document : ${e.message?.take(80)}") }
    }

    fun bytes(d: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8"); t.setOutputProperty(OutputKeys.STANDALONE, "yes")
        val out = ByteArrayOutputStream(); t.transform(DOMSource(d), StreamResult(out)); return out.toByteArray()
    }

    fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            in '\u0000'..'\u0008', '\u000b', '\u000c', in '\u000e'..'\u001f' -> {} // not allowed in XML 1.0
            else -> append(c)
        }
    }

    fun children(e: Node, ns: String, local: String): List<Element> {
        val out = mutableListOf<Element>(); var c = e.firstChild
        while (c != null) { if (c is Element && c.namespaceURI == ns && c.localName == local) out += c; c = c.nextSibling }
        return out
    }
    fun child(e: Node, ns: String, local: String) = children(e, ns, local).firstOrNull()
    fun all(e: Element, ns: String, local: String): List<Element> { val l = e.getElementsByTagNameNS(ns, local); return (0 until l.length).map { l.item(it) as Element } }

    /** `part.rels` targets by relationship id, resolved to package paths. */
    fun rels(parts: Map<String, ByteArray>, part: String): Map<String, Pair<String, String>> {
        val dir = part.substringBeforeLast('/', "")
        val b = parts[relsPath(part)] ?: return emptyMap()
        val d = dom(b)
        return all(d.documentElement, REL, "Relationship").associate { r ->
            val target = r.getAttribute("Target")
            val resolved = if (r.getAttribute("TargetMode") == "External") target else normalize(if (target.startsWith("/")) target.removePrefix("/") else (if (dir.isEmpty()) target else "$dir/$target"))
            r.getAttribute("Id") to (resolved to r.getAttribute("Type"))
        }
    }

    fun normalize(p: String): String { val out = ArrayDeque<String>(); p.split('/').forEach { s -> when (s) { "", "." -> {}; ".." -> out.removeLastOrNull(); else -> out.addLast(s) } }; return out.joinToString("/") }

    fun core(title: String?): ByteArray = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title ?: "")}</dc:title><dc:creator>Cortana</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">${Instant.now().toString().substringBefore('.').trimEnd('Z')}Z</dcterms:created></cp:coreProperties>""".toByteArray()

    fun rootRels(main: String): ByteArray = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="$REL"><Relationship Id="rId1" Type="$RT/officeDocument" Target="$main"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""".toByteArray()

    fun x(s: String) = s.trimIndent().replace("\n", "").toByteArray()

    fun relsPath(part: String): String { val dir = part.substringBeforeLast('/', ""); return (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels" }

    /** Adds a relationship from [part] (creating its .rels part) and returns its new id. */
    fun addRel(parts: MutableMap<String, ByteArray>, part: String, type: String, target: String): String {
        val rp = relsPath(part)
        val xml = parts[rp]?.let { String(it, Charsets.UTF_8) } ?: """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL"></Relationships>"""
        var n = (Regex("Id=\"rId(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0) + 1
        while (xml.contains("Id=\"rId$n\"")) n++
        val out = if (xml.contains("</Relationships>")) xml.replace("</Relationships>", "<Relationship Id=\"rId$n\" Type=\"$type\" Target=\"${esc(target)}\"/></Relationships>")
            else xml.replace(Regex("<Relationships([^>]*)/>"), "<Relationships$1><Relationship Id=\"rId$n\" Type=\"$type\" Target=\"${esc(target)}\"/></Relationships>")
        parts[rp] = out.toByteArray(); dom(parts[rp]!!)
        return "rId$n"
    }

    fun addOverride(parts: MutableMap<String, ByteArray>, partName: String, contentType: String) {
        val ct = String(parts["[Content_Types].xml"]!!, Charsets.UTF_8)
        if (ct.contains("PartName=\"$partName\"")) return
        parts["[Content_Types].xml"] = ct.replace("</Types>", "<Override PartName=\"$partName\" ContentType=\"$contentType\"/></Types>").toByteArray()
    }

    fun addDefault(parts: MutableMap<String, ByteArray>, ext: String, contentType: String) {
        val ct = String(parts["[Content_Types].xml"]!!, Charsets.UTF_8)
        if (Regex("Extension=\"${Regex.escape(ext)}\"", RegexOption.IGNORE_CASE).containsMatchIn(ct)) return
        parts["[Content_Types].xml"] = ct.replace("</Types>", "<Default Extension=\"$ext\" ContentType=\"$contentType\"/></Types>").toByteArray()
    }
}

// ================================================================ DOCX

object Docx {
    private const val MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml"

    fun write(doc: Doc): ByteArray {
        val body = StringBuilder()
        doc.blocks.forEach { body.append(blockXml(it)) }
        body.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1417" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        return Pkg.write(linkedMapOf(
            "[Content_Types].xml" to Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="${Pkg.CT}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="$MAIN.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="$MAIN.styles+xml"/>
                <Override PartName="/word/numbering.xml" ContentType="$MAIN.numbering+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""),
            "_rels/.rels" to Pkg.rootRels("word/document.xml"),
            "docProps/core.xml" to Pkg.core(doc.title),
            "word/_rels/document.xml.rels" to Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="${Pkg.REL}"><Relationship Id="rId1" Type="${Pkg.RT}/styles" Target="styles.xml"/><Relationship Id="rId2" Type="${Pkg.RT}/numbering" Target="numbering.xml"/></Relationships>"""),
            "word/styles.xml" to STYLES,
            "word/numbering.xml" to NUMBERING,
            "word/document.xml" to ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="${Pkg.W}" xmlns:r="${Pkg.R}"><w:body>$body</w:body></w:document>""").toByteArray(),
        ))
    }

    private fun runs(text: String, extraRpr: String = ""): String = Markdownish.spans(text).joinToString("") { (t, b, i) ->
        val rpr = (if (b) "<w:b/>" else "") + (if (i) "<w:i/>" else "") + extraRpr
        "<w:r>${if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""}<w:t xml:space=\"preserve\">${Pkg.esc(t)}</w:t></w:r>"
    }

    /** Block → WordprocessingML. Direct formatting is added to headings so they read as headings even in a document without our styles. */
    fun blockXml(b: Block): String = when (b) {
        is Block.Heading -> { val l = b.level.coerceIn(1, 3); """<w:p><w:pPr><w:pStyle w:val="Heading$l"/><w:outlineLvl w:val="${l - 1}"/></w:pPr>${runs(b.text, "<w:b/><w:sz w:val=\"${intArrayOf(32, 28, 24)[l - 1]}\"/>")}</w:p>""" }
        is Block.Para -> "<w:p>${runs(b.text)}</w:p>"
        is Block.Bullet -> """<w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="${b.level}"/><w:numId w:val="${if (b.ordered) 2 else 1}"/></w:numPr></w:pPr>${runs(b.text)}</w:p>"""
        is Block.Table -> {
            val cols = b.rows.maxOfOrNull { it.size } ?: 1
            val w = 9000 / cols
            buildString {
                append("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="0" w:type="auto"/><w:tblBorders>""")
                listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>") }
                append("</w:tblBorders></w:tblPr><w:tblGrid>")
                repeat(cols) { append("<w:gridCol w:w=\"$w\"/>") }
                append("</w:tblGrid>")
                b.rows.forEachIndexed { i, r ->
                    append("<w:tr>")
                    for (c in 0 until cols) append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/></w:tcPr><w:p>${runs(r.getOrElse(c) { "" }, if (i == 0) "<w:b/>" else "")}</w:p></w:tc>")
                    append("</w:tr>")
                }
                append("</w:tbl><w:p/>")
            }
        }
        Block.PageBreak -> """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""
    }

    fun read(bytes: ByteArray): Doc {
        val parts = Pkg.read(bytes)
        val main = Pkg.rels(parts, "").values.firstOrNull { it.second.endsWith("/officeDocument") }?.first?.takeIf { it in parts } ?: "word/document.xml"
        val d = Pkg.dom(parts[main] ?: throw DocumentException("document Word sans corps"))
        val headingIds = headingStyles(parts)
        val numbering = numberingFormats(parts)
        val body = Pkg.child(d.documentElement, Pkg.W, "body") ?: throw DocumentException("document Word sans corps")
        val blocks = mutableListOf<Block>()
        fun walk(container: Element) {
            var n = container.firstChild
            while (n != null) {
                if (n is Element && n.namespaceURI == Pkg.W) when (n.localName) {
                    "p" -> paragraph(n, headingIds, numbering)?.let { blocks += it }
                    "tbl" -> blocks += Block.Table(Pkg.children(n, Pkg.W, "tr").map { tr -> Pkg.children(tr, Pkg.W, "tc").map { tc -> Pkg.children(tc, Pkg.W, "p").joinToString(" ") { text(it) }.trim() } })
                    "sdt" -> Pkg.child(n, Pkg.W, "sdtContent")?.let { walk(it) }
                }
                n = n.nextSibling
            }
        }
        walk(body)
        val title = runCatching { Pkg.dom(parts["docProps/core.xml"]!!).getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0)?.textContent }.getOrNull()?.ifBlank { null }
        return Doc(title ?: (blocks.firstOrNull { it is Block.Heading } as? Block.Heading)?.text, blocks)
    }

    /** Style ids that are headings, whatever the language of the editor ("Heading1", "Titre1"…). */
    private fun headingStyles(parts: Map<String, ByteArray>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (i in 1..9) { out["Heading$i"] = i; out["heading$i"] = i }
        out["Title"] = 1
        parts["word/styles.xml"]?.let { b ->
            runCatching { Pkg.all(Pkg.dom(b).documentElement, Pkg.W, "style") }.getOrDefault(emptyList()).forEach { s ->
                val name = Pkg.child(s, Pkg.W, "name")?.getAttributeNS(Pkg.W, "val")?.lowercase().orEmpty()
                Regex("^heading (\\d)$").find(name)?.let { out[s.getAttributeNS(Pkg.W, "styleId")] = it.groupValues[1].toInt() }
                if (name == "title") out[s.getAttributeNS(Pkg.W, "styleId")] = 1
            }
        }
        return out
    }

    /** numId → level → list format ("bullet", "decimal"…), to tell bullets from numbered items. */
    private fun numberingFormats(parts: Map<String, ByteArray>): Map<String, Map<Int, String>> {
        val d = parts["word/numbering.xml"]?.let { runCatching { Pkg.dom(it) }.getOrNull() } ?: return emptyMap()
        val abstract = Pkg.children(d.documentElement, Pkg.W, "abstractNum").associate { a ->
            a.getAttributeNS(Pkg.W, "abstractNumId") to Pkg.children(a, Pkg.W, "lvl").associate { l ->
                (l.getAttributeNS(Pkg.W, "ilvl").toIntOrNull() ?: 0) to (Pkg.child(l, Pkg.W, "numFmt")?.getAttributeNS(Pkg.W, "val") ?: "bullet")
            }
        }
        return Pkg.children(d.documentElement, Pkg.W, "num").associate { n ->
            n.getAttributeNS(Pkg.W, "numId") to (abstract[Pkg.child(n, Pkg.W, "abstractNumId")?.getAttributeNS(Pkg.W, "val")] ?: emptyMap())
        }
    }

    private fun paragraph(p: Element, headings: Map<String, Int>, numbering: Map<String, Map<Int, String>>): Block? {
        val txt = text(p).trim()
        if (Pkg.all(p, Pkg.W, "br").any { it.getAttributeNS(Pkg.W, "type") == "page" } && txt.isEmpty()) return Block.PageBreak
        if (txt.isEmpty()) return null
        val ppr = Pkg.child(p, Pkg.W, "pPr")
        val style = ppr?.let { Pkg.child(it, Pkg.W, "pStyle")?.getAttributeNS(Pkg.W, "val") }
        val outline = ppr?.let { Pkg.child(it, Pkg.W, "outlineLvl")?.getAttributeNS(Pkg.W, "val")?.toIntOrNull() }
        headings[style]?.let { return Block.Heading(it.coerceAtMost(3), txt) }
        if (outline != null && outline < 3) return Block.Heading(outline + 1, txt)
        ppr?.let { Pkg.child(it, Pkg.W, "numPr") }?.let { np ->
            val lvl = Pkg.child(np, Pkg.W, "ilvl")?.getAttributeNS(Pkg.W, "val")?.toIntOrNull() ?: 0
            val fmt = numbering[Pkg.child(np, Pkg.W, "numId")?.getAttributeNS(Pkg.W, "val")]?.get(lvl) ?: "bullet"
            return Block.Bullet(txt, lvl.coerceAtMost(2), ordered = fmt != "bullet" && fmt != "none")
        }
        return Block.Para(txt)
    }

    /** Visible text of a paragraph: runs, tabs, breaks, hyperlinks, insertions; deletions skipped. */
    fun text(p: Element): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element && c.namespaceURI == Pkg.W) when (c.localName) {
                    "t" -> sb.append(c.textContent)
                    "tab" -> sb.append('\t')
                    "br", "cr" -> if (c.getAttributeNS(Pkg.W, "type") != "page") sb.append('\n')
                    "del", "pPr", "rPr", "instrText" -> {}
                    else -> walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(p)
        return sb.toString()
    }

    /**
     * Replaces texts (template placeholders like `{{nom}}` or plain text), even when Word split them
     * across runs: the paragraph's text is rebuilt in its first run, which keeps its formatting.
     */
    fun replace(bytes: ByteArray, replacements: Map<String, String>): Pair<ByteArray, Int> {
        val parts = Pkg.read(bytes)
        var count = 0
        for (name in parts.keys.filter { it.matches(Regex("word/(document|header\\d*|footer\\d*)\\.xml")) }) {
            val d = Pkg.dom(parts[name]!!)
            var changed = false
            for (p in Pkg.all(d.documentElement, Pkg.W, "p")) {
                val ts = Pkg.all(p, Pkg.W, "t").filter { t -> generateSequence(t.parentNode) { it.parentNode }.none { it is Element && it.localName == "del" } }
                if (ts.isEmpty()) continue
                val full = ts.joinToString("") { it.textContent }
                var out = full
                for ((k, v) in replacements) { if (k.isEmpty()) continue; val n = out.split(k).size - 1; if (n > 0) { count += n; out = out.replace(k, v) } }
                if (out != full) {
                    ts.first().textContent = out
                    ts.first().setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
                    ts.drop(1).forEach { it.textContent = "" }
                    changed = true
                }
            }
            if (changed) parts[name] = Pkg.bytes(d)
        }
        return Pkg.write(parts) to count
    }

    /** Appends blocks at the end of the body (before the section properties). */
    fun append(bytes: ByteArray, blocks: List<Block>): ByteArray {
        val parts = Pkg.read(bytes)
        val main = "word/document.xml"
        val xml = String(parts[main] ?: throw DocumentException("document Word sans corps"), Charsets.UTF_8)
        val insert = blocks.joinToString("") { blockXml(it) }
        val at = xml.lastIndexOf("<w:sectPr").takeIf { it > xml.lastIndexOf("</w:tbl>") && it > 0 } ?: xml.lastIndexOf("</w:body>")
        if (at < 0) throw DocumentException("structure Word inattendue")
        parts[main] = (xml.substring(0, at) + insert + xml.substring(at)).toByteArray()
        Pkg.dom(parts[main]!!) // still well-formed
        return Pkg.write(parts)
    }

    private val STYLES = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="${Pkg.W}"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="fr-FR"/></w:rPr></w:rPrDefault>
        <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
        <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="32"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="28"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="40"/><w:ind w:left="720"/></w:pPr></w:style>
        <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr></w:style></w:styles>""")

    private val NUMBERING = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:numbering xmlns:w="${Pkg.W}">
        <w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>
        <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
        <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="◦"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
        <w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="▪"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="2160" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
        <w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>
        <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
        <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
        <w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="lowerRoman"/><w:lvlText w:val="%3."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="2160" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num></w:numbering>""")
}

// ================================================================ XLSX

sealed interface Cell {
    data class Text(val v: String) : Cell
    data class Num(val v: Double) : Cell
    data class Bool(val v: Boolean) : Cell
    data class Formula(val f: String, val cached: String? = null) : Cell
    /** A date (or date-time) as a spreadsheet serial number (1900 system: days since 1899-12-30). */
    data class Date(val serial: Double) : Cell
    data object Empty : Cell
    fun display(): String = when (this) { is Text -> v; is Num -> Tabular.fmt(v); is Bool -> v.toString(); is Formula -> cached ?: "=$f"; is Date -> Xlsx.iso(serial); Empty -> "" }
}

data class Sheet(val name: String, val rows: List<List<Cell>>) {
    fun table(): DataTable = DataTable(rows.firstOrNull().orEmpty().map { it.display() }, rows.drop(1).map { r -> r.map { it.display() } })
    companion object {
        /** A data table as a sheet: header row in bold, numbers typed; "=…" cells become formulas. */
        fun of(name: String, t: DataTable): Sheet = Sheet(name, listOf(t.header.map { Cell.Text(it) }) + t.rows.map { r -> r.map(::cellOf) })
        fun cellOf(s: String): Cell = when {
            s.isEmpty() -> Cell.Empty
            s.startsWith("=") && s.length > 1 -> Cell.Formula(s.drop(1))
            Xlsx.parseDate(s.trim()) != null -> Cell.Date(Xlsx.parseDate(s.trim())!!)
            s.trim().matches(Regex("-?\\d+([.,]\\d+)?")) -> Cell.Num(Tabular.number(s)!!)
            s == "true" || s == "false" -> Cell.Bool(s.toBoolean())
            else -> Cell.Text(s)
        }
    }
}

object Xlsx {
    private const val MAIN = "application/vnd.openxmlformats-officedocument.spreadsheetml"

    private val EPOCH = java.time.LocalDate.of(1899, 12, 30)
    private val EPOCH_1904 = java.time.LocalDate.of(1904, 1, 1)

    /** "2026-03-01", "2026-03-01T14:30" or the French "01/03/2026" → serial number; null otherwise. */
    fun parseDate(s: String): Double? {
        val d = runCatching {
            when {
                s.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> java.time.LocalDate.parse(s).atStartOfDay()
                s.matches(Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?")) -> java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                s.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) -> java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")).atStartOfDay()
                else -> null
            }
        }.getOrNull() ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(EPOCH, d.toLocalDate()) + d.toLocalTime().toSecondOfDay() / 86_400.0
    }

    fun iso(serial: Double, date1904: Boolean = false): String {
        val days = Math.floor(serial).toLong()
        val secs = Math.round((serial - days) * 86_400).toInt()
        val date = (if (date1904) EPOCH_1904 else EPOCH).plusDays(days)
        return if (secs == 0) date.toString() else "$date ${java.time.LocalTime.ofSecondOfDay(secs.toLong().coerceAtMost(86_399)).toString().take(5)}"
    }

    private val builtinDateFormats = (14..22).toSet() + (27..36) + (45..47) + (50..58)
    private fun isDateFormat(id: Int, code: String?): Boolean = id in builtinDateFormats ||
        (code != null && code.replace(Regex("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\."), "").let { c -> c.contains(Regex("[dDyY]")) || (c.contains(Regex("[hH]")) && c.contains('m')) })

    /** Indices of the cell formats (cellXfs) that display dates. */
    private fun dateStyles(parts: Map<String, ByteArray>): Set<Int> {
        val d = parts["xl/styles.xml"]?.let { runCatching { Pkg.dom(it) }.getOrNull() } ?: return emptySet()
        val codes = Pkg.all(d.documentElement, Pkg.S, "numFmt").associate { (it.getAttribute("numFmtId").toIntOrNull() ?: -1) to it.getAttribute("formatCode") }
        val xfs = Pkg.child(d.documentElement, Pkg.S, "cellXfs")?.let { Pkg.children(it, Pkg.S, "xf") }.orEmpty()
        return xfs.withIndex().filter { (_, x) -> val id = x.getAttribute("numFmtId").toIntOrNull() ?: 0; isDateFormat(id, codes[id]) }.map { it.index }.toSet()
    }

    private fun date1904(parts: Map<String, ByteArray>) = parts["xl/workbook.xml"]?.let { String(it).contains(Regex("date1904=\"(1|true)\"")) } == true

    /** Index of a date cell format in the workbook's styles, added when missing. */
    private fun ensureDateStyle(parts: MutableMap<String, ByteArray>): Int {
        dateStyles(parts).minOrNull()?.let { return it }
        val xml = String(parts["xl/styles.xml"] ?: throw DocumentException("classeur sans styles"), Charsets.UTF_8)
        val m = Regex("<(\\w+:)?cellXfs([^>]*)>(.*?)</(\\w+:)?cellXfs>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: throw DocumentException("styles du classeur illisibles")
        val prefix = m.groupValues[1]
        val count = Regex("<${prefix}xf[ >/]").findAll(m.groupValues[3]).count()
        val xf = "<${prefix}xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
        val attrs = m.groupValues[2].replace(Regex("count=\"\\d+\""), "count=\"${count + 1}\"")
        parts["xl/styles.xml"] = xml.replaceRange(m.range, "<${prefix}cellXfs$attrs>${m.groupValues[3]}$xf</${prefix}cellXfs>").toByteArray()
        Pkg.dom(parts["xl/styles.xml"]!!)
        return count
    }

    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }; return sb.reverse().toString() }
    fun colIndex(ref: String): Int = ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    fun rowIndex(ref: String): Int = (ref.dropWhile { it.isLetter() }.toIntOrNull() ?: throw DocumentException("référence de cellule invalide : $ref")) - 1

    private fun sheetName(n: String, used: MutableSet<String>): String {
        var s = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifEmpty { "Feuille" }
        var k = 2; val base = s
        while (!used.add(s.lowercase())) s = base.take(28) + " " + k++
        return s
    }

    private fun cellXml(ref: String, c: Cell, header: Boolean, cached: Double?): String {
        val st = if (header) " s=\"1\"" else ""
        return when (c) {
            is Cell.Text -> "<c r=\"$ref\" t=\"inlineStr\"$st><is><t xml:space=\"preserve\">${Pkg.esc(c.v)}</t></is></c>"
            is Cell.Num -> "<c r=\"$ref\"$st><v>${if (c.v.isFinite()) Tabular.fmt(c.v) else "0"}</v></c>"
            is Cell.Bool -> "<c r=\"$ref\" t=\"b\"$st><v>${if (c.v) 1 else 0}</v></c>"
            is Cell.Formula -> "<c r=\"$ref\"$st><f>${Pkg.esc(c.f.removePrefix("="))}</f>${cached?.let { "<v>${Tabular.fmt(it)}</v>" } ?: ""}</c>"
            is Cell.Date -> "<c r=\"$ref\" s=\"2\"><v>${Tabular.fmt(c.serial)}</v></c>"
            Cell.Empty -> ""
        }
    }

    /** A sheet whose first row is all text and that has data under it is a table: bold frozen header and an autofilter. */
    private fun isTable(sh: Sheet) = sh.rows.size > 1 && sh.rows[0].isNotEmpty() && sh.rows[0].all { it is Cell.Text }
    private fun tableRef(sh: Sheet) = "A1:${col((sh.rows.maxOf { it.size } - 1).coerceAtLeast(0))}${sh.rows.size}"
    private fun quoted(n: String) = "'" + n.replace("'", "''") + "'"

    fun write(sheets: List<Sheet>, title: String? = null): ByteArray {
        if (sheets.isEmpty()) throw DocumentException("classeur sans feuille")
        val used = HashSet<String>()
        val names = sheets.map { sheetName(it.name, used) }
        val parts = linkedMapOf<String, ByteArray>()
        parts["[Content_Types].xml"] = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="${Pkg.CT}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="$MAIN.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="$MAIN.styles+xml"/>
            ${sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"$MAIN.worksheet+xml\"/>" }}
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
        parts["_rels/.rels"] = Pkg.rootRels("xl/workbook.xml")
        parts["docProps/core.xml"] = Pkg.core(title)
        parts["xl/workbook.xml"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="${Pkg.S}" xmlns:r="${Pkg.R}"><sheets>""" +
            names.mapIndexed { i, n -> "<sheet name=\"${Pkg.esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets>" +
            sheets.withIndex().filter { isTable(it.value) }.joinToString("") { (i, sh) -> "<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"$i\" hidden=\"1\">${Pkg.esc(quoted(names[i]) + "!" + tableRef(sh).split(':').joinToString(":") { r -> "$" + r.takeWhile(Char::isLetter) + "$" + r.dropWhile(Char::isLetter) })}</definedName>" }
                .let { if (it.isEmpty()) "" else "<definedNames>$it</definedNames>" } +
            """<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""").toByteArray()
        parts["xl/_rels/workbook.xml.rels"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${Pkg.REL}">""" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"${Pkg.RT}/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"${Pkg.RT}/styles\" Target=\"styles.xml\"/></Relationships>").toByteArray()
        parts["xl/styles.xml"] = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="${Pkg.S}"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
            <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        sheets.forEachIndexed { i, sh ->
            val widths = (0 until (sh.rows.maxOfOrNull { it.size } ?: 0)).map { c -> (sh.rows.maxOfOrNull { it.getOrNull(c)?.display()?.length ?: 0 } ?: 8).coerceIn(8, 60) + 2 }
            val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="${Pkg.S}" xmlns:r="${Pkg.R}">""")
            if (isTable(sh)) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            if (widths.isNotEmpty()) sb.append("<cols>").append(widths.mapIndexed { c, w -> "<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")).append("</cols>")
            val cached = Formulas.cache(sh.rows)
            sb.append("<sheetData>")
            sh.rows.forEachIndexed { r, row ->
                sb.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, cell -> val ref = "${col(c)}${r + 1}"; sb.append(cellXml(ref, cell, r == 0 && isTable(sh), cached[ref])) }
                sb.append("</row>")
            }
            sb.append("</sheetData>")
            if (isTable(sh)) sb.append("<autoFilter ref=\"${tableRef(sh)}\"/>")
            sb.append("</worksheet>")
            parts["xl/worksheets/sheet${i + 1}.xml"] = sb.toString().toByteArray()
        }
        return Pkg.write(parts)
    }

    private fun sheetPaths(parts: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = Pkg.dom(parts["xl/workbook.xml"] ?: throw DocumentException("classeur sans workbook.xml"))
        val rels = Pkg.rels(parts, "xl/workbook.xml")
        return Pkg.all(wb.documentElement, Pkg.S, "sheet").mapNotNull { s ->
            val path = rels[s.getAttributeNS(Pkg.R, "id")]?.first ?: return@mapNotNull null
            s.getAttribute("name") to path
        }
    }

    private fun sheetPath(parts: Map<String, ByteArray>, sheet: String?): Pair<String, String> {
        val sheets = sheetPaths(parts)
        return (if (sheet.isNullOrBlank()) sheets.firstOrNull() else sheets.firstOrNull { it.first.equals(sheet.trim(), true) })
            ?: throw DocumentException("Feuille « $sheet » introuvable (feuilles : ${sheets.joinToString { it.first }})")
    }

    private fun sharedStrings(parts: Map<String, ByteArray>): List<String> = parts["xl/sharedStrings.xml"]?.let { b ->
        Pkg.all(Pkg.dom(b).documentElement, Pkg.S, "si").map { si -> Pkg.all(si, Pkg.S, "t").filter { t -> (t.parentNode as? Element)?.localName != "rPh" }.joinToString("") { it.textContent } }
    }.orEmpty()

    private fun parseCell(c: Element, shared: List<String>, dates: Set<Int> = emptySet(), d1904: Boolean = false): Cell {
        val v = Pkg.child(c, Pkg.S, "v")?.textContent
        val f = Pkg.child(c, Pkg.S, "f")?.textContent
        val t = c.getAttribute("t")
        return when {
            f != null && f.isNotEmpty() -> Cell.Formula(f, v?.let { raw -> if (t == "s") shared.getOrNull(raw.toInt()) else raw })
            t == "s" -> Cell.Text(v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: "")
            t == "inlineStr" -> Cell.Text(Pkg.child(c, Pkg.S, "is")?.let { Pkg.all(it, Pkg.S, "t").joinToString("") { x -> x.textContent } } ?: "")
            t == "b" -> Cell.Bool(v == "1")
            t == "str" || t == "e" -> Cell.Text(v ?: "")
            v != null && (c.getAttribute("s").toIntOrNull() ?: 0) in dates && v.toDoubleOrNull() != null ->
                Cell.Date(v.toDouble() + if (d1904) 1462 else 0)
            v != null -> v.toDoubleOrNull()?.let { Cell.Num(it) } ?: Cell.Text(v)
            else -> Cell.Empty
        }
    }

    private fun sheetRows(d: org.w3c.dom.Document, shared: List<String>, maxRows: Int, dates: Set<Int> = emptySet(), d1904: Boolean = false): List<List<Cell>> {
        val rows = sortedMapOf<Int, MutableMap<Int, Cell>>()
        for (c in Pkg.all(d.documentElement, Pkg.S, "c")) {
            val ref = c.getAttribute("r").ifEmpty { continue }
            val r = rowIndex(ref); if (r >= maxRows) continue
            rows.getOrPut(r) { sortedMapOf() }[colIndex(ref)] = parseCell(c, shared, dates, d1904)
        }
        val maxRow = rows.keys.maxOrNull() ?: -1
        return (0..maxRow).map { r -> val m = rows[r].orEmpty(); val w = (m.keys.maxOrNull() ?: -1) + 1; (0 until w).map { m[it] ?: Cell.Empty } }
    }

    data class Inspection(val sheets: List<Sheet>, val charts: Map<String, List<String>>)

    fun read(bytes: ByteArray, maxRows: Int = 100_000): List<Sheet> = inspect(bytes, maxRows).sheets

    /** Sheets with their cells, and the charts drawn on each sheet. */
    fun inspect(bytes: ByteArray, maxRows: Int = 100_000): Inspection {
        val parts = Pkg.read(bytes)
        val shared = sharedStrings(parts)
        val dates = dateStyles(parts); val d1904 = date1904(parts)
        val charts = LinkedHashMap<String, List<String>>()
        val sheets = sheetPaths(parts).map { (name, path) ->
            val d = Pkg.dom(parts[path] ?: throw DocumentException("feuille manquante : $name"))
            val drawings = Pkg.rels(parts, path).values.filter { it.second.endsWith("/drawing") }.map { it.first }
            val cs = drawings.flatMap { dr -> Pkg.rels(parts, dr).values.filter { it.second == Charts.REL }.mapNotNull { parts[it.first]?.let(Charts::describe) } }
            if (cs.isNotEmpty()) charts[name] = cs
            Sheet(name, sheetRows(d, shared, maxRows, dates, d1904))
        }
        return Inspection(sheets, charts)
    }

    /** Cells of a rectangular range such as "B2:D10" (or a single cell). */
    fun range(sheet: Sheet, ref: String): List<List<Cell>> {
        val (a, b) = ref.uppercase().replace("$", "").split(':').let { if (it.size == 1) it[0] to it[0] else it[0] to it[1] }
        val r1 = minOf(rowIndex(a), rowIndex(b)); val r2 = maxOf(rowIndex(a), rowIndex(b))
        val c1 = minOf(colIndex(a), colIndex(b)); val c2 = maxOf(colIndex(a), colIndex(b))
        if (c1 < 0 || r1 < 0) throw DocumentException("plage invalide : $ref")
        if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 50_000) throw DocumentException("plage trop grande (50 000 cellules au plus)")
        return (r1..r2).map { r -> (c1..c2).map { c -> sheet.rows.getOrNull(r)?.getOrNull(c) ?: Cell.Empty } }
    }

    /**
     * Writes cells into an existing workbook in place (styles, other sheets and charts kept):
     * values become inline strings/numbers; formulas get a cached value when Cortana can compute
     * it and are recalculated on open anyway (the calc chain, now stale, is removed). A new sheet
     * is added when [sheet] names one that does not exist and [create] is set.
     */
    fun setCells(bytes: ByteArray, sheet: String?, updates: Map<String, Cell>, create: Boolean = false): ByteArray {
        var parts = Pkg.read(bytes)
        if (create && sheet != null && sheetPaths(parts).none { it.first.equals(sheet.trim(), true) }) parts = Pkg.read(addSheet(parts, sheet.trim()))
        val (_, path) = sheetPath(parts, sheet)
        val dateStyle = if (updates.values.any { it is Cell.Date }) ensureDateStyle(parts) else -1
        val d1904 = date1904(parts)
        val d = Pkg.dom(parts[path]!!)
        val data = Pkg.child(d.documentElement, Pkg.S, "sheetData") ?: throw DocumentException("feuille sans données")
        for ((ref0, value) in updates) {
            val ref = ref0.uppercase().replace("$", "")
            val r = rowIndex(ref) + 1; val c = colIndex(ref)
            if (c < 0 || c > 16_383 || r < 1 || r > 1_048_576) throw DocumentException("référence hors limites : $ref0")
            val rows = Pkg.children(data, Pkg.S, "row")
            val row = rows.firstOrNull { it.getAttribute("r").toIntOrNull() == r } ?: d.createElementNS(Pkg.S, "row").also { nr ->
                nr.setAttribute("r", r.toString())
                data.insertBefore(nr, rows.firstOrNull { (it.getAttribute("r").toIntOrNull() ?: 0) > r })
            }
            val cells = Pkg.children(row, Pkg.S, "c")
            var cell = cells.firstOrNull { it.getAttribute("r").equals(ref, true) }
            if (cell == null) {
                cell = d.createElementNS(Pkg.S, "c").also { it.setAttribute("r", ref) }
                row.insertBefore(cell, cells.firstOrNull { colIndex(it.getAttribute("r")) > c })
            }
            val style = cell.getAttribute("s")
            while (cell.firstChild != null) cell.removeChild(cell.firstChild)
            cell.removeAttribute("t")
            if (style.isNotEmpty()) cell.setAttribute("s", style)
            when (value) {
                is Cell.Text -> { cell.setAttribute("t", "inlineStr"); val isE = d.createElementNS(Pkg.S, "is"); val t = d.createElementNS(Pkg.S, "t"); t.textContent = value.v; isE.appendChild(t); cell.appendChild(isE) }
                is Cell.Num -> cell.appendChild(d.createElementNS(Pkg.S, "v").also { it.textContent = Tabular.fmt(value.v) })
                is Cell.Bool -> { cell.setAttribute("t", "b"); cell.appendChild(d.createElementNS(Pkg.S, "v").also { it.textContent = if (value.v) "1" else "0" }) }
                is Cell.Formula -> cell.appendChild(d.createElementNS(Pkg.S, "f").also { it.textContent = value.f.removePrefix("=") })
                is Cell.Date -> { cell.setAttribute("s", dateStyle.toString()); cell.appendChild(d.createElementNS(Pkg.S, "v").also { it.textContent = Tabular.fmt(value.serial - if (d1904) 1462 else 0) }) }
                Cell.Empty -> row.removeChild(cell)
            }
        }
        // Cached values of every formula of the sheet, recomputed from the new cells.
        val cache = Formulas.cache(sheetRows(d, sharedStrings(parts), 1_048_576, dateStyles(parts), d1904))
        for (c in Pkg.all(d.documentElement, Pkg.S, "c")) {
            if (Pkg.child(c, Pkg.S, "f") == null) continue
            Pkg.children(c, Pkg.S, "v").forEach { c.removeChild(it) }
            if (c.getAttribute("t") == "str" || c.getAttribute("t") == "e" || c.getAttribute("t") == "b") c.removeAttribute("t")
            cache[c.getAttribute("r")]?.let { v -> c.appendChild(d.createElementNS(Pkg.S, "v").also { it.textContent = Tabular.fmt(v) }) }
        }
        // Keep <dimension> honest enough: drop it (optional) rather than leave a wrong range.
        Pkg.child(d.documentElement, Pkg.S, "dimension")?.let { d.documentElement.removeChild(it) }
        parts[path] = Pkg.bytes(d)
        parts.remove("xl/calcChain.xml")
        parts["[Content_Types].xml"] = String(parts["[Content_Types].xml"]!!).replace(Regex("<Override[^>]*PartName=\"/xl/calcChain.xml\"[^>]*/>"), "").toByteArray()
        parts["xl/_rels/workbook.xml.rels"]?.let { parts["xl/_rels/workbook.xml.rels"] = String(it).replace(Regex("<Relationship[^>]*Target=\"(/xl/)?calcChain.xml\"[^>]*/>"), "").toByteArray() }
        val wb = String(parts["xl/workbook.xml"]!!)
        parts["xl/workbook.xml"] = (if (wb.contains("<calcPr")) wb.replace(Regex("<calcPr([^>]*?)/>")) { m -> if (m.value.contains("fullCalcOnLoad")) m.value else "<calcPr${m.groupValues[1]} fullCalcOnLoad=\"1\"/>" }
            else wb.replace("</workbook>", "<calcPr fullCalcOnLoad=\"1\"/></workbook>")).toByteArray()
        Pkg.dom(parts["xl/workbook.xml"]!!)
        return Pkg.write(parts)
    }

    /** Adds an empty sheet at the end of the workbook. */
    private fun addSheet(parts: LinkedHashMap<String, ByteArray>, name: String): ByteArray {
        val used = sheetPaths(parts).map { it.first.lowercase() }.toMutableSet()
        val n = sheetName(name, used)
        if (!n.equals(name, true)) throw DocumentException("nom de feuille invalide : $name")
        var k = 1; while ("xl/worksheets/sheet$k.xml" in parts) k++
        val path = "xl/worksheets/sheet$k.xml"
        parts[path] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="${Pkg.S}" xmlns:r="${Pkg.R}"><sheetData/></worksheet>""".toByteArray()
        val rid = Pkg.addRel(parts, "xl/workbook.xml", "${Pkg.RT}/worksheet", "worksheets/sheet$k.xml")
        val wb = String(parts["xl/workbook.xml"]!!)
        val next = (Regex("sheetId=\"(\\d+)\"").findAll(wb).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0) + 1
        val prefix = Regex("<(\\w+:)?sheets[ >]").find(wb)?.groupValues?.get(1).orEmpty()
        val rPrefix = Regex("xmlns:(\\w+)=\"${Regex.escape(Pkg.R)}\"").find(wb)?.groupValues?.get(1) ?: "r"
        parts["xl/workbook.xml"] = wb.replace("</${prefix}sheets>", "<${prefix}sheet name=\"${Pkg.esc(n)}\" sheetId=\"$next\" $rPrefix:id=\"$rid\"/></${prefix}sheets>").toByteArray()
        Pkg.addOverride(parts, "/$path", "$MAIN.worksheet+xml")
        return Pkg.write(parts)
    }

    /**
     * Draws a native chart on [sheet] from the cells of [range] (first column = categories, first
     * row = series names, other columns = values), anchored at [anchor]. The chart references the
     * cells, so it follows later edits.
     */
    fun addChart(bytes: ByteArray, sheet: String?, range: String, kind: String, title: String?, anchor: String? = null): ByteArray {
        val parts = Pkg.read(bytes)
        val (name, path) = sheetPath(parts, sheet)
        val sh = Sheet(name, sheetRows(Pkg.dom(parts[path]!!), sharedStrings(parts), 1_048_576))
        val cells = range(sh, range)
        if (cells.size < 2 || cells[0].size < 2) throw DocumentException("la plage doit avoir une ligne d'en-tête et au moins une colonne de valeurs")
        val (a, b) = range.uppercase().replace("$", "").split(':').let { l -> l[0] to l.getOrElse(1) { l[0] } }
        val r0 = minOf(rowIndex(a), rowIndex(b)); val c0 = minOf(colIndex(a), colIndex(b))
        val cache = Formulas.cache(sh.rows)
        fun num(c: Cell, ref: String) = when (c) { is Cell.Num -> c.v; is Cell.Formula -> cache[ref] ?: c.cached?.toDoubleOrNull(); else -> null }
        val spec = ChartSpec(kind, title, cells.drop(1).map { it[0].display() },
            (1 until cells[0].size).map { j -> ChartSeries(cells[0][j].display().ifBlank { "Série $j" }, cells.drop(1).mapIndexed { i, row -> num(row[j], "${col(c0 + j)}${r0 + i + 2}") }) })
        val refs = ChartRefs(name, r0 + 1, r0 + cells.size - 1, c0, (1 until cells[0].size).map { c0 + it })
        var n = 1; while ("xl/charts/chart$n.xml" in parts) n++
        parts["xl/charts/chart$n.xml"] = Charts.chartXml(spec, refs)
        Pkg.addOverride(parts, "/xl/charts/chart$n.xml", Charts.CONTENT_TYPE)
        // One drawing per sheet: reuse it when the sheet already has one.
        val existing = Pkg.rels(parts, path).values.firstOrNull { it.second.endsWith("/drawing") }?.first
        val drawing = existing ?: run {
            var k = 1; while ("xl/drawings/drawing$k.xml" in parts) k++
            "xl/drawings/drawing$k.xml".also {
                parts[it] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="$XDR" xmlns:a="${Pkg.A}"></xdr:wsDr>""".toByteArray()
                Pkg.addOverride(parts, "/$it", "application/vnd.openxmlformats-officedocument.drawing+xml")
                val rid = Pkg.addRel(parts, path, "${Pkg.RT}/drawing", "../drawings/drawing$k.xml")
                val d = Pkg.dom(parts[path]!!)
                val el = d.createElementNS(Pkg.S, "drawing").also { e -> e.setAttributeNS(Pkg.R, "r:id", rid) }
                // <drawing> goes before these elements (schema order), otherwise at the end.
                val after = setOf("legacyDrawing", "legacyDrawingHF", "drawingHF", "picture", "oleObjects", "controls", "webPublishItems", "tableParts", "extLst")
                var ref: org.w3c.dom.Node? = d.documentElement.firstChild
                while (ref != null && !(ref is Element && ref.localName in after)) ref = ref.nextSibling
                d.documentElement.insertBefore(el, ref)
                parts[path] = Pkg.bytes(d)
            }
        }
        val rid = Pkg.addRel(parts, drawing, Charts.REL, "../charts/chart$n.xml")
        val at = (anchor ?: "${col(c0 + cells[0].size + 1)}${r0 + 1}").uppercase()
        val ac = colIndex(at); val ar = rowIndex(at)
        val dd = String(parts[drawing]!!)
        val xdr = Regex("<(\\w+):wsDr").find(dd)?.groupValues?.get(1)?.let { "$it:" } ?: ""
        val ids = Regex("<\\w*:?cNvPr[^>]* id=\"(\\d+)\"").findAll(dd).map { it.groupValues[1].toInt() }.maxOrNull() ?: 1
        val frame = "<${xdr}twoCellAnchor editAs=\"oneCell\"><${xdr}from><${xdr}col>$ac</${xdr}col><${xdr}colOff>0</${xdr}colOff><${xdr}row>$ar</${xdr}row><${xdr}rowOff>0</${xdr}rowOff></${xdr}from>" +
            "<${xdr}to><${xdr}col>${ac + 8}</${xdr}col><${xdr}colOff>0</${xdr}colOff><${xdr}row>${ar + 16}</${xdr}row><${xdr}rowOff>0</${xdr}rowOff></${xdr}to>" +
            "<${xdr}graphicFrame macro=\"\"><${xdr}nvGraphicFramePr><${xdr}cNvPr id=\"${ids + 1}\" name=\"Graphique $n\"/><${xdr}cNvGraphicFramePr/></${xdr}nvGraphicFramePr>" +
            "<${xdr}xfrm><a:off x=\"0\" y=\"0\" xmlns:a=\"${Pkg.A}\"/><a:ext cx=\"0\" cy=\"0\" xmlns:a=\"${Pkg.A}\"/></${xdr}xfrm>" +
            "<a:graphic xmlns:a=\"${Pkg.A}\"><a:graphicData uri=\"${Charts.GRAPHIC_URI}\"><c:chart xmlns:c=\"${Charts.C}\" xmlns:r=\"${Pkg.R}\" r:id=\"$rid\"/></a:graphicData></a:graphic>" +
            "</${xdr}graphicFrame><${xdr}clientData/></${xdr}twoCellAnchor>"
        parts[drawing] = dd.replace("</${xdr}wsDr>", "$frame</${xdr}wsDr>").toByteArray()
        Pkg.dom(parts[drawing]!!)
        return Pkg.write(parts)
    }

    private const val XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
}

// ================================================================ PPTX

/**
 * One slide. [layout]: "title" (title slide with [subtitle]), "content" (title + bullets),
 * "two_column" ([bullets] | [right]), "image" and "chart" (bullets beside the visual, or the
 * visual alone); null = chosen from what the slide carries.
 */
data class SlideSpec(
    val title: String, val bullets: List<String> = emptyList(), val image: ByteArray? = null, val layout: String? = null,
    val subtitle: String? = null, val right: List<String> = emptyList(), val chart: ChartSpec? = null,
) {
    val effectiveLayout: String get() = layout ?: when {
        chart != null -> "chart"; image != null -> "image"; right.isNotEmpty() -> "two_column"
        bullets.isEmpty() && subtitle != null -> "title"; else -> "content"
    }
    init {
        if (layout != null && layout !in LAYOUTS) throw DocumentException("disposition inconnue : $layout (${LAYOUTS.joinToString()})")
        if (effectiveLayout == "image" && image == null) throw DocumentException("disposition image sans image")
        if (effectiveLayout == "chart" && chart == null) throw DocumentException("disposition graphique sans graphique")
        if (image != null && ImageSize.mime(image) == null) throw DocumentException("image PNG ou JPEG attendue")
    }
    companion object { val LAYOUTS = listOf("title", "content", "two_column", "image", "chart") }
}

object Pptx {
    private const val MAIN = "application/vnd.openxmlformats-officedocument.presentationml"
    private const val CX = 12_192_000L; private const val CY = 6_858_000L

    private fun sp(id: Int, name: String, ph: String, x: Long, y: Long, w: Long, h: Long, paragraphs: String, anchorCenter: Boolean = false) =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${Pkg.esc(name)}"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm></p:spPr><p:txBody><a:bodyPr wrap="square"${if (anchorCenter) " anchor=\"ctr\"" else ""}><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"""

    private fun para(text: String, size: Int, bold: Boolean = false, bullet: Boolean = false, level: Int = 0, center: Boolean = false): String {
        val ppr = if (bullet) """<a:pPr marL="${342900 + level * 457200}" lvl="$level" indent="-342900"><a:buFont typeface="Arial"/><a:buChar char="•"/></a:pPr>""" else "<a:pPr${if (center) " algn=\"ctr\"" else ""}><a:buNone/></a:pPr>"
        val runs = Markdownish.spans(text).joinToString("") { (t, b, i) -> """<a:r><a:rPr lang="fr-FR" sz="$size"${if (bold || b) " b=\"1\"" else ""}${if (i) " i=\"1\"" else ""} dirty="0"/><a:t>${Pkg.esc(t)}</a:t></a:r>""" }
        return "<a:p>$ppr$runs</a:p>"
    }

    private fun bulletParas(items: List<String>) = items.joinToString("") { b ->
        val lvl = (b.takeWhile { it == ' ' }.length / 2).coerceAtMost(2)
        para(b.trim().removePrefix("- ").removePrefix("• "), 2000 - lvl * 200, bullet = true, level = lvl)
    }.ifEmpty { "<a:p><a:endParaRPr lang=\"fr-FR\"/></a:p>" }

    /** Visual box: the image keeps its aspect ratio inside (x, y, w, h). */
    private fun fit(img: ByteArray, x: Long, y: Long, w: Long, h: Long): LongArray {
        val (pw, ph) = ImageSize.of(img) ?: (4 to 3)
        val s = minOf(w.toDouble() / pw, h.toDouble() / ph)
        val iw = (pw * s).toLong(); val ih = (ph * s).toLong()
        return longArrayOf(x + (w - iw) / 2, y + (h - ih) / 2, iw, ih)
    }

    private fun slideXml(s: SlideSpec, imageRid: String?, chartRid: String?): String {
        val m = 600_000L; val top = 1_500_000L; val bodyH = 4_900_000L; val full = CX - 2 * m; val half = CX / 2 - m - 100_000
        val shapes = StringBuilder()
        when (s.effectiveLayout) {
            "title" -> {
                shapes.append(sp(2, "Titre", "<p:ph type=\"ctrTitle\"/>", m, 2_000_000, full, 1_500_000, para(s.title, 4400, bold = true, center = true), anchorCenter = true))
                shapes.append(sp(3, "Sous-titre", "<p:ph type=\"subTitle\" idx=\"1\"/>", m, 3_600_000, full, 1_200_000, s.subtitle?.let { para(it, 2400, center = true) } ?: "<a:p><a:endParaRPr lang=\"fr-FR\"/></a:p>"))
            }
            else -> {
                shapes.append(sp(2, "Titre", "<p:ph type=\"title\"/>", m, 300_000, full, 1_000_000, para(s.title, 3600, bold = true)))
                val visual = s.effectiveLayout == "image" || s.effectiveLayout == "chart"
                val split = s.effectiveLayout == "two_column" || (visual && s.bullets.isNotEmpty())
                if (!visual || s.bullets.isNotEmpty()) shapes.append(sp(3, "Contenu", "<p:ph idx=\"1\"/>", m, top, if (split) half else full, bodyH, bulletParas(s.bullets)))
                val vx = if (split) CX / 2 + 100_000 else m; val vw = if (split) half else full
                when (s.effectiveLayout) {
                    "two_column" -> shapes.append(sp(4, "Contenu 2", "<p:ph idx=\"2\"/>", vx, top, vw, bodyH, bulletParas(s.right)))
                    "image" -> { val b = fit(s.image!!, vx, top, vw, bodyH)
                        shapes.append("""<p:pic><p:nvPicPr><p:cNvPr id="5" name="Image"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="$imageRid"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="${b[0]}" y="${b[1]}"/><a:ext cx="${b[2]}" cy="${b[3]}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""") }
                    "chart" -> shapes.append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="Graphique"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="$vx" y="$top"/><a:ext cx="$vw" cy="$bodyH"/></p:xfrm><a:graphic><a:graphicData uri="${Charts.GRAPHIC_URI}"><c:chart xmlns:c="${Charts.C}" r:id="$chartRid"/></a:graphicData></a:graphic></p:graphicFrame>""")
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="${Pkg.A}" xmlns:r="${Pkg.R}" xmlns:p="${Pkg.P}"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            shapes + """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    fun write(title: String?, slides: List<SlideSpec>): ByteArray {
        if (slides.isEmpty()) throw DocumentException("présentation sans diapositive")
        val parts = linkedMapOf<String, ByteArray>()
        parts["[Content_Types].xml"] = byteArrayOf()
        parts["_rels/.rels"] = Pkg.rootRels("ppt/presentation.xml")
        parts["docProps/core.xml"] = Pkg.core(title)
        parts["ppt/theme/theme1.xml"] = THEME
        parts["ppt/slideMasters/slideMaster1.xml"] = MASTER
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${Pkg.REL}"><Relationship Id="rId1" Type="${Pkg.RT}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="${Pkg.RT}/theme" Target="../theme/theme1.xml"/></Relationships>""")
        parts["ppt/slideLayouts/slideLayout1.xml"] = LAYOUT
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${Pkg.REL}"><Relationship Id="rId1" Type="${Pkg.RT}/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>""")
        parts["ppt/presentation.xml"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:a="${Pkg.A}" xmlns:r="${Pkg.R}" xmlns:p="${Pkg.P}" saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>""" +
            slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" } +
            """</p:sldIdLst><p:sldSz cx="$CX" cy="$CY"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""").toByteArray()
        parts["ppt/_rels/presentation.xml.rels"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${Pkg.REL}"><Relationship Id="rId1" Type="${Pkg.RT}/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="${Pkg.RT}/theme" Target="theme/theme1.xml"/>""" +
            slides.indices.joinToString("") { "<Relationship Id=\"rId${it + 3}\" Type=\"${Pkg.RT}/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } + "</Relationships>").toByteArray()
        parts["[Content_Types].xml"] = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="${Pkg.CT}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$MAIN.presentation.main+xml\"/><Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$MAIN.slideMaster+xml\"/><Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$MAIN.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>").toByteArray()
        slides.forEachIndexed { i, s -> addSlideParts(parts, i + 1, s, "slideLayout1.xml") }
        return Pkg.write(parts)
    }

    /** Writes slide [n]'s part, its relationships and its media/chart parts. */
    private fun addSlideParts(parts: MutableMap<String, ByteArray>, n: Int, s: SlideSpec, layout: String) {
        val path = "ppt/slides/slide$n.xml"
        parts.remove(Pkg.relsPath(path))
        Pkg.addRel(parts, path, "${Pkg.RT}/slideLayout", "../slideLayouts/$layout")
        val imageRid = s.image?.takeIf { s.effectiveLayout == "image" }?.let { b ->
            val ext = if (ImageSize.mime(b) == "image/jpeg") "jpeg" else "png"
            var k = n; while ("ppt/media/image$k.$ext" in parts) k++
            parts["ppt/media/image$k.$ext"] = b
            Pkg.addDefault(parts, ext, "image/$ext")
            Pkg.addRel(parts, path, "${Pkg.RT}/image", "../media/image$k.$ext")
        }
        val chartRid = s.chart?.takeIf { s.effectiveLayout == "chart" }?.let { c ->
            var k = 1; while ("ppt/charts/chart$k.xml" in parts) k++
            parts["ppt/charts/chart$k.xml"] = Charts.chartXml(c)
            Pkg.addOverride(parts, "/ppt/charts/chart$k.xml", Charts.CONTENT_TYPE)
            Pkg.addRel(parts, path, Charts.REL, "../charts/chart$k.xml")
        }
        parts[path] = slideXml(s, imageRid, chartRid).toByteArray()
        Pkg.addOverride(parts, "/$path", "$MAIN.slide+xml")
    }

    data class SlideText(val index: Int, val title: String?, val paragraphs: List<String>, val images: Int = 0, val charts: List<String> = emptyList())

    private fun slidePaths(parts: Map<String, ByteArray>): List<Pair<String, String>> {
        val pres = Pkg.dom(parts["ppt/presentation.xml"] ?: throw DocumentException("présentation sans presentation.xml"))
        val rels = Pkg.rels(parts, "ppt/presentation.xml")
        return Pkg.all(pres.documentElement, Pkg.P, "sldId").mapNotNull { s -> val rid = s.getAttributeNS(Pkg.R, "id"); rels[rid]?.first?.let { rid to it } }
    }

    fun read(bytes: ByteArray): List<SlideText> {
        val parts = Pkg.read(bytes)
        return slidePaths(parts).mapIndexed { i, (_, path) ->
            val d = Pkg.dom(parts[path] ?: return@mapIndexed SlideText(i + 1, null, emptyList()))
            var title: String? = null; val paras = mutableListOf<String>()
            for (sp in Pkg.all(d.documentElement, Pkg.P, "sp")) {
                val ph = Pkg.all(sp, Pkg.P, "ph").firstOrNull()?.getAttribute("type")
                val texts = Pkg.all(sp, Pkg.A, "p").map { p -> Pkg.all(p, Pkg.A, "t").joinToString("") { it.textContent } }.filter { it.isNotBlank() }
                if ((ph == "title" || ph == "ctrTitle") && title == null) title = texts.joinToString(" ") else paras += texts
            }
            val rels = Pkg.rels(parts, path).values
            SlideText(i + 1, title, paras, Pkg.all(d.documentElement, Pkg.P, "pic").size,
                rels.filter { it.second == Charts.REL }.mapNotNull { parts[it.first]?.let(Charts::describe) })
        }
    }

    /** Media parts of a slide (images, charts) that no other part still references. */
    private fun dropOwned(parts: MutableMap<String, ByteArray>, slide: String) {
        val owned = Pkg.rels(parts, slide).values.filter { it.second.endsWith("/image") || it.second == Charts.REL }.map { it.first }
        parts.remove(Pkg.relsPath(slide))
        for (p in owned) {
            val stillUsed = parts.keys.filter { it.endsWith(".rels") }.any { rp ->
                val owner = rp.replace("_rels/", "").removeSuffix(".rels")
                Pkg.rels(parts, owner).values.any { it.first == p }
            }
            if (!stillUsed) { parts.remove(p); parts.remove(Pkg.relsPath(p)); parts["[Content_Types].xml"] = String(parts["[Content_Types].xml"]!!).replace(Regex("<Override PartName=\"/${Regex.escape(p)}\"[^>]*/>"), "").toByteArray() }
        }
    }

    private fun firstLayout(parts: Map<String, ByteArray>) = parts.keys.filter { it.matches(Regex("ppt/slideLayouts/slideLayout\\d+\\.xml")) }
        .minByOrNull { it.filter(Char::isDigit).toInt() }?.substringAfterLast('/') ?: throw DocumentException("présentation sans disposition")

    /** Appends slides to an existing deck (its first layout is reused), or inserts them before position [at] (1-based). */
    fun addSlides(bytes: ByteArray, slides: List<SlideSpec>, at: Int? = null): ByteArray {
        val parts = Pkg.read(bytes)
        val layout = firstLayout(parts)
        var next = (parts.keys.mapNotNull { Regex("ppt/slides/slide(\\d+)\\.xml").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1
        var pres = String(parts["ppt/presentation.xml"]!!)
        var nextId = (Regex("<p:sldId [^>]*id=\"(\\d+)\"").findAll(pres).map { it.groupValues[1].toLong() }.maxOrNull() ?: 255L) + 1
        val entries = mutableListOf<String>()
        for (s in slides) {
            addSlideParts(parts, next, s, layout)
            val rid = Pkg.addRel(parts, "ppt/presentation.xml", "${Pkg.RT}/slide", "slides/slide$next.xml")
            entries += "<p:sldId id=\"$nextId\" r:id=\"$rid\"/>"
            next++; nextId++
        }
        val existing = Regex("<p:sldId [^>]*/>").findAll(pres).map { it.value }.toMutableList()
        val pos = ((at ?: (existing.size + 1)) - 1).coerceIn(0, existing.size)
        existing.addAll(pos, entries)
        pres = if (pres.contains("<p:sldIdLst>")) pres.replace(Regex("<p:sldIdLst>.*?</p:sldIdLst>", RegexOption.DOT_MATCHES_ALL), "<p:sldIdLst>${existing.joinToString("")}</p:sldIdLst>".replace("$", "\\$"))
            else pres.replace("</p:sldMasterIdLst>", "</p:sldMasterIdLst><p:sldIdLst>${existing.joinToString("")}</p:sldIdLst>")
        parts["ppt/presentation.xml"] = pres.toByteArray()
        Pkg.dom(parts["ppt/presentation.xml"]!!)
        return Pkg.write(parts)
    }

    /** Replaces the content of slide [number] (1-based) by [spec], at the same position. */
    fun replaceSlide(bytes: ByteArray, number: Int, spec: SlideSpec): ByteArray {
        val parts = Pkg.read(bytes)
        val order = slidePaths(parts)
        if (number < 1 || number > order.size) throw DocumentException("numéro de diapositive hors limites (1 à ${order.size})")
        val path = order[number - 1].second
        val layout = Pkg.rels(parts, path).values.firstOrNull { it.second.endsWith("/slideLayout") }?.first?.substringAfterLast('/') ?: firstLayout(parts)
        dropOwned(parts, path)
        val n = Regex("slide(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toInt() ?: throw DocumentException("diapositive au chemin inattendu : $path")
        if (path != "ppt/slides/slide$n.xml") throw DocumentException("diapositive au chemin inattendu : $path")
        addSlideParts(parts, n, spec, layout)
        return Pkg.write(parts)
    }

    /** New slide order: [order] lists every current slide number once. */
    fun reorder(bytes: ByteArray, order: List<Int>): ByteArray {
        val parts = Pkg.read(bytes)
        var pres = String(parts["ppt/presentation.xml"]!!)
        val entries = Regex("<p:sldId [^>]*/>").findAll(pres).map { it.value }.toList()
        if (order.sorted() != (1..entries.size).toList()) throw DocumentException("l'ordre doit citer chaque diapositive une fois (1 à ${entries.size})")
        pres = pres.replace(Regex("<p:sldIdLst>.*?</p:sldIdLst>", RegexOption.DOT_MATCHES_ALL), "<p:sldIdLst>${order.joinToString("") { entries[it - 1] }}</p:sldIdLst>".replace("$", "\\$"))
        parts["ppt/presentation.xml"] = pres.toByteArray()
        return Pkg.write(parts)
    }

    /** Replaces text in every slide (paragraph by paragraph, across runs, first run keeps its formatting). */
    fun replace(bytes: ByteArray, replacements: Map<String, String>): Pair<ByteArray, Int> {
        val parts = Pkg.read(bytes); var count = 0
        for ((_, path) in slidePaths(parts)) {
            val d = Pkg.dom(parts[path] ?: continue); var changed = false
            for (p in Pkg.all(d.documentElement, Pkg.A, "p")) {
                val ts = Pkg.all(p, Pkg.A, "t"); if (ts.isEmpty()) continue
                val full = ts.joinToString("") { it.textContent }; var out = full
                for ((k, v) in replacements) { if (k.isEmpty()) continue; val n = out.split(k).size - 1; if (n > 0) { count += n; out = out.replace(k, v) } }
                if (out != full) { ts.first().textContent = out; ts.drop(1).forEach { it.textContent = "" }; changed = true }
            }
            if (changed) parts[path] = Pkg.bytes(d)
        }
        return Pkg.write(parts) to count
    }

    /** Removes slides (1-based) from the deck order; their parts (and media only they used) are removed too. */
    fun deleteSlides(bytes: ByteArray, numbers: Set<Int>): ByteArray {
        val parts = Pkg.read(bytes)
        val order = slidePaths(parts)
        if (numbers.any { it < 1 || it > order.size }) throw DocumentException("numéro de diapositive hors limites (1 à ${order.size})")
        if (numbers.size >= order.size) throw DocumentException("une présentation doit garder au moins une diapositive")
        var pres = String(parts["ppt/presentation.xml"]!!); var rels = String(parts["ppt/_rels/presentation.xml.rels"]!!)
        for (n in numbers) {
            val (rid, path) = order[n - 1]
            pres = pres.replace(Regex("<p:sldId [^>]*r:id=\"${Regex.escape(rid)}\"[^>]*/>"), "")
            rels = rels.replace(Regex("<Relationship [^>]*Id=\"${Regex.escape(rid)}\"[^>]*/>"), "")
            parts["ppt/presentation.xml"] = pres.toByteArray(); parts["ppt/_rels/presentation.xml.rels"] = rels.toByteArray()
            parts.remove(path)
            dropOwned(parts, path)
            parts["[Content_Types].xml"] = String(parts["[Content_Types].xml"]!!).replace(Regex("<Override PartName=\"/${Regex.escape(path)}\"[^>]*/>"), "").toByteArray()
        }
        return Pkg.write(parts)
    }

    private val MASTER = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldMaster xmlns:a="${Pkg.A}" xmlns:r="${Pkg.R}" xmlns:p="${Pkg.P}"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
        <p:sp><p:nvSpPr><p:cNvPr id="2" name="Titre"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="600000" y="300000"/><a:ext cx="10992000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="fr-FR"/></a:p></p:txBody></p:sp>
        <p:sp><p:nvSpPr><p:cNvPr id="3" name="Texte"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="600000" y="1500000"/><a:ext cx="10992000" cy="4600000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="fr-FR"/></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
        <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
        <p:txStyles><p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="3600" b="1"><a:solidFill><a:schemeClr val="tx2"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>
        <p:bodyStyle><a:lvl1pPr marL="342900" indent="-342900"><a:buFont typeface="Arial"/><a:buChar char="•"/><a:defRPr sz="2000"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr></p:bodyStyle>
        <p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>""")

    private val LAYOUT = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldLayout xmlns:a="${Pkg.A}" xmlns:r="${Pkg.R}" xmlns:p="${Pkg.P}" type="obj" preserve="1"><p:cSld name="Titre et contenu"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
        <p:sp><p:nvSpPr><p:cNvPr id="2" name="Titre"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="fr-FR"/></a:p></p:txBody></p:sp>
        <p:sp><p:nvSpPr><p:cNvPr id="3" name="Contenu"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="fr-FR"/></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")

    private val THEME = Pkg.x("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <a:theme xmlns:a="${Pkg.A}" name="Cortana"><a:themeElements><a:clrScheme name="Cortana"><a:dk1><a:srgbClr val="1A1A1A"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="EEF1F6"/></a:lt2>
        <a:accent1><a:srgbClr val="2F5496"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="70AD47"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="A5A5A5"/></a:accent6>
        <a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
        <a:fontScheme name="Cortana"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>
        <a:fmtScheme name="Cortana"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
        <a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
        <a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
        <a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>""")
}
