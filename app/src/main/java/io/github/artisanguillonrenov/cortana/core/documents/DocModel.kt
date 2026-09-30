package io.github.artisanguillonrenov.cortana.core.documents

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class DocumentException(message: String) : Exception(message)

/** Format-neutral document (doc 02 §34): what every reader produces and every writer consumes. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Bullet(val text: String, val level: Int = 0, val ordered: Boolean = false) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object PageBreak : Block
}

data class Doc(val title: String?, val blocks: List<Block>) {
    fun text(): String = Markdownish.render(this)
}

/**
 * The small markup the model writes to create documents: `#`…`###` headings, `-`/`*` bullets
 * (two spaces per level), `1.` numbered items, `| a | b |` tables, `---pagebreak---`, blank lines
 * between paragraphs; `**gras**` and `*italique*` inside text.
 */
object Markdownish {
    private val heading = Regex("^(#{1,3})\\s+(.+)$")
    private val bullet = Regex("^(\\s*)[-*•]\\s+(.+)$")
    private val ordered = Regex("^(\\s*)\\d+[.)]\\s+(.+)$")

    fun parse(text: String, title: String? = null): Doc {
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        val table = mutableListOf<List<String>>()
        fun flushPara() { if (para.isNotBlank()) blocks += Block.Para(para.toString().trim()); para.clear() }
        fun flushTable() { if (table.isNotEmpty()) blocks += Block.Table(table.toList()); table.clear() }
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.startsWith("|") && t.endsWith("|") && t.length > 1 -> {
                    flushPara()
                    val cells = t.trim('|').split('|').map { it.trim() }
                    if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) table += cells
                }
                t == "---pagebreak---" || t == "\u000c" -> { flushPara(); flushTable(); blocks += Block.PageBreak }
                t.isEmpty() -> { flushPara(); flushTable() }
                heading.matches(t) -> { flushPara(); flushTable(); val m = heading.find(t)!!; blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2].trim()) }
                bullet.matches(line) -> { flushPara(); flushTable(); val m = bullet.find(line)!!; blocks += Block.Bullet(m.groupValues[2].trim(), (m.groupValues[1].length / 2).coerceAtMost(2)) }
                ordered.matches(line) -> { flushPara(); flushTable(); val m = ordered.find(line)!!; blocks += Block.Bullet(m.groupValues[2].trim(), (m.groupValues[1].length / 2).coerceAtMost(2), ordered = true) }
                else -> { flushTable(); if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        flushPara(); flushTable()
        return Doc(title ?: (blocks.firstOrNull() as? Block.Heading)?.text, blocks)
    }

    fun render(d: Doc): String = buildString {
        var n = 0
        for (b in d.blocks) {
            when (b) {
                is Block.Heading -> append("#".repeat(b.level.coerceIn(1, 3))).append(' ').append(b.text).append("\n\n")
                is Block.Para -> append(b.text).append("\n\n")
                is Block.Bullet -> { append("  ".repeat(b.level)).append(if (b.ordered) "${++n}. " else "- ").append(b.text).append('\n'); continue }
                is Block.Table -> { b.rows.forEachIndexed { i, r -> append("| ").append(r.joinToString(" | ")).append(" |\n"); if (i == 0) append("|").append(r.joinToString("|") { "---" }).append("|\n") }; append('\n') }
                Block.PageBreak -> append("---pagebreak---\n\n")
            }
            n = 0
        }
    }.trim()

    /** `**bold**` / `*italic*` spans of a text: (text, bold, italic). */
    fun spans(text: String): List<Triple<String, Boolean, Boolean>> {
        val out = mutableListOf<Triple<String, Boolean, Boolean>>()
        val re = Regex("\\*\\*(.+?)\\*\\*|\\*(?!\\s)(.+?)(?<!\\s)\\*")
        var last = 0
        for (m in re.findAll(text)) {
            if (m.range.first > last) out += Triple(text.substring(last, m.range.first), false, false)
            if (m.groupValues[1].isNotEmpty()) out += Triple(m.groupValues[1], true, false) else out += Triple(m.groupValues[2], false, true)
            last = m.range.last + 1
        }
        if (last < text.length) out += Triple(text.substring(last), false, false)
        return out.ifEmpty { listOf(Triple(text, false, false)) }
    }

    fun plain(text: String) = spans(text).joinToString("") { it.first }
}

// ---------------------------------------------------------------- tabular data (CSV / JSON)

data class DataTable(val header: List<String>, val rows: List<List<String>>) {
    fun column(name: String): Int = header.indexOfFirst { it.equals(name.trim(), true) }.takeIf { it >= 0 }
        ?: throw DocumentException("Colonne « $name » inconnue (colonnes : ${header.joinToString()})")
    fun cell(r: List<String>, i: Int) = r.getOrElse(i) { "" }
}

object Tabular {
    fun number(s: String): Double? {
        val t = s.trim().replace(" ", "").replace(" ", "").removeSuffix("€").removeSuffix("%")
        if (t.isEmpty()) return null
        val norm = if (t.count { it == ',' } == 1 && !t.contains('.')) t.replace(',', '.') else t.replace(",", "")
        return norm.toDoubleOrNull()
    }

    fun fmt(d: Double): String = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else "%.4f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')

    /** RFC 4180 with delimiter sniffing (`,` `;` tab `|`). */
    fun parseCsv(text: String, delimiter: Char? = null): DataTable {
        val src = text.removePrefix("\uFEFF")
        val d = delimiter ?: listOf(',', ';', '\t', '|').maxByOrNull { c -> src.lineSequence().take(5).sumOf { l -> l.count { it == c } } } ?: ','
        val rows = mutableListOf<List<String>>()
        var cur = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < src.length && src[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(ch)
            } else when (ch) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(ch)
                d -> { cur += cell.toString(); cell.clear() }
                '\n', '\r' -> { if (ch == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++; cur += cell.toString(); cell.clear(); if (cur.any { it.isNotEmpty() }) rows += cur; cur = mutableListOf() }
                else -> cell.append(ch)
            }
            i++
            if (rows.size > 200_000) throw DocumentException("Fichier trop grand (plus de 200 000 lignes)")
        }
        if (cell.isNotEmpty() || cur.isNotEmpty()) { cur += cell.toString(); if (cur.any { it.isNotEmpty() }) rows += cur }
        if (rows.isEmpty()) return DataTable(emptyList(), emptyList())
        return DataTable(rows.first().map { it.trim() }, rows.drop(1))
    }

    fun writeCsv(t: DataTable, delimiter: Char = ','): String {
        fun q(s: String) = if (s.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || s.startsWith(" ") || s.endsWith(" ")) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return (listOf(t.header) + t.rows).joinToString("\r\n") { r -> r.joinToString(delimiter.toString()) { q(it) } } + "\r\n"
    }

    fun parseJson(text: String): DataTable {
        val e = runCatching { AppJson.parseToJsonElement(text) }.getOrElse { throw DocumentException("JSON invalide : ${it.message}") }
        val arr = when (e) {
            is JsonArray -> e
            is JsonObject -> e.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(listOf(e))
            else -> throw DocumentException("JSON : un tableau d'objets est attendu")
        }
        fun str(v: kotlinx.serialization.json.JsonElement?): String = when (v) { null, JsonNull -> ""; is JsonPrimitive -> v.content; else -> v.toString() }
        if (arr.all { it is JsonArray }) {
            val rows = arr.map { (it as JsonArray).map(::str) }
            return DataTable(rows.firstOrNull().orEmpty(), rows.drop(1))
        }
        val keys = LinkedHashSet<String>()
        arr.forEach { (it as? JsonObject)?.keys?.let(keys::addAll) }
        return DataTable(keys.toList(), arr.map { o -> keys.map { k -> str((o as? JsonObject)?.get(k)) } })
    }

    fun writeJson(t: DataTable): String = buildJsonArray {
        t.rows.forEach { r ->
            add(buildJsonObject {
                t.header.forEachIndexed { i, h ->
                    val v = r.getOrElse(i) { "" }
                    val num = number(v)?.takeIf { v.trim().matches(Regex("-?\\d+([.,]\\d+)?")) }
                    put(h, when { v.isEmpty() -> JsonNull; num != null -> JsonPrimitive(num); v == "true" || v == "false" -> JsonPrimitive(v.toBoolean()); else -> JsonPrimitive(v) })
                }
            })
        }
    }.toString()

    /** Per-column summary: type, filled count, distinct values, numeric statistics. */
    fun describe(t: DataTable): String = buildString {
        append("${t.rows.size} ligne(s), ${t.header.size} colonne(s)\n")
        t.header.forEachIndexed { i, h ->
            val vals = t.rows.map { t.cell(it, i) }
            val filled = vals.filter { it.isNotBlank() }
            val nums = filled.mapNotNull(::number)
            append("- $h : ")
            if (nums.size == filled.size && nums.isNotEmpty()) append("nombre, ${nums.size} valeur(s), somme ${fmt(nums.sum())}, moyenne ${fmt(nums.average())}, min ${fmt(nums.min())}, max ${fmt(nums.max())}")
            else append("texte, ${filled.size} rempli(s), ${filled.distinct().size} distinct(s)" + filled.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString(prefix = ", fréquents : ") { "${it.key} (${it.value})" })
            if (filled.size < vals.size) append(", ${vals.size - filled.size} vide(s)")
            append('\n')
        }
    }.trim()

    private val cond = Regex("^(.+?)\\s*(==|!=|>=|<=|>|<|=|contient|contains|commence par|startswith)\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun filter(t: DataTable, expr: String): DataTable {
        val m = cond.find(expr.trim()) ?: throw DocumentException("Filtre illisible : « $expr » (ex. « montant > 100 », « ville == Lyon », « nom contient du »)")
        val i = t.column(m.groupValues[1].trim().trim('"', '\''))
        val op = m.groupValues[2].lowercase(); val rhs = m.groupValues[3].trim().trim('"', '\'')
        val rn = number(rhs)
        return t.copy(rows = t.rows.filter { r ->
            val v = t.cell(r, i); val vn = number(v)
            when (op) {
                "==", "=" -> if (rn != null && vn != null) vn == rn else v.equals(rhs, true)
                "!=" -> if (rn != null && vn != null) vn != rn else !v.equals(rhs, true)
                ">" -> vn != null && rn != null && vn > rn
                "<" -> vn != null && rn != null && vn < rn
                ">=" -> vn != null && rn != null && vn >= rn
                "<=" -> vn != null && rn != null && vn <= rn
                "contient", "contains" -> v.contains(rhs, true)
                else -> v.startsWith(rhs, true)
            }
        })
    }

    fun sort(t: DataTable, column: String, descending: Boolean): DataTable {
        val i = t.column(column)
        val numeric = t.rows.all { number(t.cell(it, i)) != null || t.cell(it, i).isBlank() }
        val cmp: Comparator<List<String>> = if (numeric) compareBy { number(t.cell(it, i)) ?: Double.NEGATIVE_INFINITY } else compareBy { t.cell(it, i).lowercase() }
        return t.copy(rows = t.rows.sortedWith(if (descending) cmp.reversed() else cmp))
    }

    /** Group-by with sum | avg | count | min | max of a numeric column. */
    fun group(t: DataTable, by: String, value: String?, fn: String): DataTable {
        val g = t.column(by); val v = value?.let { t.column(it) }
        val f = fn.lowercase()
        val rows = t.rows.groupBy { t.cell(it, g) }.map { (k, rs) ->
            val nums = v?.let { i -> rs.mapNotNull { number(t.cell(it, i)) } }.orEmpty()
            val r = when (f) {
                "count", "nombre" -> rs.size.toDouble()
                "avg", "mean", "moyenne" -> if (nums.isEmpty()) 0.0 else nums.average()
                "min" -> nums.minOrNull() ?: 0.0
                "max" -> nums.maxOrNull() ?: 0.0
                else -> nums.sum()
            }
            listOf(k, fmt(r))
        }.sortedBy { it[0] }
        return DataTable(listOf(t.header[g], if (f == "count" || f == "nombre") "nombre" else "$f(${value ?: ""})"), rows)
    }

    fun select(t: DataTable, columns: List<String>): DataTable {
        val idx = columns.map { t.column(it) }
        return DataTable(idx.map { t.header[it] }, t.rows.map { r -> idx.map { t.cell(r, it) } })
    }
}

// ---------------------------------------------------------------- archives

/**
 * Safe archive handling (checklist "archive handling safe"): ZIP, TAR, TAR.GZ/TGZ and single GZ are
 * read in memory with limits on entries, per-file and total uncompressed size (zip bombs), no path
 * escaping (zip slip), no links or devices, and nested archives are listed, never expanded.
 */
object Archives {
    data class Entry(val path: String, val size: Long, val bytes: ByteArray?)

    fun kind(name: String): String? = name.lowercase().let { n ->
        when { n.endsWith(".zip") -> "zip"; n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"; n.endsWith(".tar") -> "tar"; n.endsWith(".gz") -> "gz"; else -> null }
    }

    private fun safe(path: String): String {
        val p = path.replace('\\', '/').trimStart('/')
        if (p.isEmpty() || p.split('/').any { it == ".." } || p.contains(':') || path.startsWith("/")) throw DocumentException("chemin interdit dans l'archive : $path")
        return p
    }

    fun read(name: String, bytes: ByteArray, keepBytes: Boolean, maxEntries: Int = 2_000, maxFile: Long = 50_000_000, maxTotal: Long = 150_000_000): List<Entry> {
        val out = mutableListOf<Entry>(); var total = 0L
        fun take(path: String, input: java.io.InputStream) {
            if (out.size >= maxEntries) throw DocumentException("archive trop fournie (plus de $maxEntries fichiers)")
            val buf = ByteArrayOutputStream(); val tmp = ByteArray(64 * 1024); var n: Int; var size = 0L
            while (input.read(tmp).also { n = it } >= 0) {
                size += n; total += n
                if (size > maxFile) throw DocumentException("fichier trop volumineux une fois décompressé : $path")
                if (total > maxTotal) throw DocumentException("archive trop volumineuse une fois décompressée (bombe de décompression ?)")
                if (keepBytes) buf.write(tmp, 0, n)
            }
            out += Entry(safe(path), size, if (keepBytes) buf.toByteArray() else null)
        }
        when (kind(name) ?: throw DocumentException("Format d'archive non pris en charge : $name")) {
            "zip" -> ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) take(e.name, z) } }
            "gz" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { take(name.substringBeforeLast('.').substringAfterLast('/'), it) }
            "tar" -> tar(ByteArrayInputStream(bytes), ::take)
            "tgz" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { tar(it, ::take) }
        }
        return out
    }

    private fun tar(input: java.io.InputStream, take: (String, java.io.InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (readFully(input, header) < 512 || header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
            val prefix = String(header, 345, 155, Charsets.UTF_8).substringBefore('\u0000')
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8) ?: throw DocumentException("en-tête TAR invalide")
            if (size < 0 || size > 1L shl 34) throw DocumentException("en-tête TAR invalide")
            val type = header[156].toInt().toChar()
            val full = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            val bounded = Bounded(input, size)
            when (type) {
                '0', '\u0000', '7' -> { take(full, bounded); longName = null }
                '5' -> longName = null
                // GNU long name / PAX path: the next entry's real name (bounded, small).
                'L' -> { if (size > 4096) throw DocumentException("nom trop long dans l'archive"); longName = String(bounded.readBytesBounded(), Charsets.UTF_8).substringBefore('\u0000') }
                'x' -> { if (size > 65_536) throw DocumentException("en-tête PAX trop long"); longName = String(bounded.readBytesBounded(), Charsets.UTF_8).lines().firstNotNullOfOrNull { l -> Regex("^\\d+ path=(.*)$").find(l)?.groupValues?.get(1) } }
                'g' -> {}
                else -> throw DocumentException("lien ou fichier spécial refusé dans l'archive : $full") // hard/sym links, devices, FIFOs…
            }
            val skip = ByteArray(8192)
            while (bounded.left > 0) if (bounded.read(skip, 0, skip.size) < 0) break
            val pad = ((512 - size % 512) % 512).toInt()
            if (pad > 0) readFully(input, ByteArray(pad))
        }
    }

    private fun readFully(input: java.io.InputStream, b: ByteArray): Int { var off = 0; while (off < b.size) { val n = input.read(b, off, b.size - off); if (n < 0) break; off += n }; return off }

    /** Exactly [left] bytes of a TAR entry. */
    private class Bounded(private val input: java.io.InputStream, var left: Long) : java.io.InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if (left <= 0) return -1; val n = input.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n }
        fun readBytesBounded(): ByteArray { val out = ByteArrayOutputStream(); val b = ByteArray(4096); while (true) { val n = read(b, 0, b.size); if (n < 0) break; out.write(b, 0, n) }; return out.toByteArray() }
    }
}

internal fun jsonNum(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonPrimitive)?.doubleOrNull
internal fun jsonBool(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonPrimitive)?.booleanOrNull
