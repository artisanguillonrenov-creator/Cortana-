package io.github.artisanguillonrenov.cortana.core.documents

/** A basic chart (doc 02 §34.3-34.4): categories on one axis, one or more numeric series. */
data class ChartSeries(val name: String, val values: List<Double?>)

data class ChartSpec(val kind: String, val title: String?, val categories: List<String>, val series: List<ChartSeries>) {
    init {
        if (kind !in KINDS) throw DocumentException("type de graphique inconnu : $kind (${KINDS.joinToString()})")
        if (series.isEmpty() || categories.isEmpty()) throw DocumentException("graphique sans données")
        if (kind == "pie" && series.size > 1) throw DocumentException("un camembert n'a qu'une série")
        if (categories.size > 500 || series.size > 20) throw DocumentException("graphique trop grand (500 catégories, 20 séries au plus)")
    }

    companion object {
        val KINDS = listOf("column", "bar", "line", "pie")

        /** From a data table: first column = categories, the other (numeric) columns = series. */
        fun of(kind: String, title: String?, t: DataTable, columns: List<String>? = null): ChartSpec {
            if (t.header.size < 2) throw DocumentException("il faut une colonne de catégories et au moins une colonne de valeurs")
            val idx = columns?.map { t.column(it) } ?: (1 until t.header.size).filter { c -> t.rows.any { Tabular.number(t.cell(it, c)) != null } }
            if (idx.isEmpty()) throw DocumentException("aucune colonne numérique à représenter")
            return ChartSpec(kind, title, t.rows.map { t.cell(it, 0) }, idx.map { c -> ChartSeries(t.header[c], t.rows.map { Tabular.number(t.cell(it, c)) }) })
        }
    }
}

/** Where the chart data lives in a workbook, so the chart follows later edits of the cells. */
data class ChartRefs(val sheet: String, val firstRow: Int, val lastRow: Int, val categoryCol: Int, val seriesCols: List<Int>) {
    private fun q() = "'" + sheet.replace("'", "''") + "'"
    fun cat() = "${q()}!\$${Xlsx.col(categoryCol)}\$${firstRow + 1}:\$${Xlsx.col(categoryCol)}\$${lastRow + 1}"
    fun value(i: Int) = "${q()}!\$${Xlsx.col(seriesCols[i])}\$${firstRow + 1}:\$${Xlsx.col(seriesCols[i])}\$${lastRow + 1}"
    fun name(i: Int) = "${q()}!\$${Xlsx.col(seriesCols[i])}\$$firstRow"
}

/** DrawingML chart parts (c:chartSpace) — shared by the spreadsheet and presentation writers. */
object Charts {
    const val C = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    const val CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
    const val REL = "${Pkg.RT}/chart"
    const val GRAPHIC_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"

    private fun e(s: String) = Pkg.esc(s)

    private fun strPts(v: List<String>) = "<c:ptCount val=\"${v.size}\"/>" + v.mapIndexed { i, s -> "<c:pt idx=\"$i\"><c:v>${e(s)}</c:v></c:pt>" }.joinToString("")
    private fun numPts(v: List<Double?>) = "<c:formatCode>General</c:formatCode><c:ptCount val=\"${v.size}\"/>" +
        v.mapIndexedNotNull { i, d -> d?.takeIf { it.isFinite() }?.let { "<c:pt idx=\"$i\"><c:v>${Tabular.fmt(it)}</c:v></c:pt>" } }.joinToString("")

    private fun series(spec: ChartSpec, i: Int, refs: ChartRefs?): String {
        val s = spec.series[i]
        val tx = if (refs != null) "<c:tx><c:strRef><c:f>${e(refs.name(i))}</c:f><c:strCache>${strPts(listOf(s.name))}</c:strCache></c:strRef></c:tx>" else "<c:tx><c:v>${e(s.name)}</c:v></c:tx>"
        val cat = if (refs != null) "<c:cat><c:strRef><c:f>${e(refs.cat())}</c:f><c:strCache>${strPts(spec.categories)}</c:strCache></c:strRef></c:cat>" else "<c:cat><c:strLit>${strPts(spec.categories)}</c:strLit></c:cat>"
        val v = if (refs != null) "<c:val><c:numRef><c:f>${e(refs.value(i))}</c:f><c:numCache>${numPts(s.values)}</c:numCache></c:numRef></c:val>" else "<c:val><c:numLit>${numPts(s.values)}</c:numLit></c:val>"
        return when (spec.kind) {
            "line" -> "<c:ser><c:idx val=\"$i\"/><c:order val=\"$i\"/>$tx<c:marker><c:symbol val=\"circle\"/></c:marker>$cat$v<c:smooth val=\"0\"/></c:ser>"
            "pie" -> "<c:ser><c:idx val=\"$i\"/><c:order val=\"$i\"/>$tx$cat$v</c:ser>"
            else -> "<c:ser><c:idx val=\"$i\"/><c:order val=\"$i\"/>$tx<c:invertIfNegative val=\"0\"/>$cat$v</c:ser>"
        }
    }

    fun chartXml(spec: ChartSpec, refs: ChartRefs? = null): ByteArray {
        val sers = spec.series.indices.joinToString("") { series(spec, it, refs) }
        val axes = "<c:axId val=\"50010\"/><c:axId val=\"50020\"/>"
        val plot = when (spec.kind) {
            "pie" -> "<c:pieChart><c:varyColors val=\"1\"/>$sers<c:dLbls><c:showLegendKey val=\"0\"/><c:showVal val=\"0\"/><c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"1\"/><c:showBubbleSize val=\"0\"/><c:showLeaderLines val=\"1\"/></c:dLbls><c:firstSliceAng val=\"0\"/></c:pieChart>"
            "line" -> "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>$sers<c:marker val=\"1\"/>$axes</c:lineChart>" + AXES
            else -> "<c:barChart><c:barDir val=\"${if (spec.kind == "bar") "bar" else "col"}\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>$sers<c:gapWidth val=\"150\"/>$axes</c:barChart>" +
                if (spec.kind == "bar") AXES.replace("<c:axPos val=\"b\"/>", "<c:axPos val=\"l\"/>").replace("<c:axPos val=\"l\"/><c:majorGridlines/>", "<c:axPos val=\"b\"/><c:majorGridlines/>") else AXES
        }
        val title = spec.title?.let { "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"1400\" b=\"1\"/></a:pPr><a:r><a:rPr lang=\"fr-FR\" sz=\"1400\" b=\"1\"/><a:t>${e(it)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>" }
            ?: "<c:autoTitleDeleted val=\"1\"/>"
        return ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><c:chartSpace xmlns:c="$C" xmlns:a="${Pkg.A}" xmlns:r="${Pkg.R}"><c:roundedCorners val="0"/><c:chart>$title<c:plotArea><c:layout/>$plot</c:plotArea>""" +
            """<c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>""").toByteArray()
    }

    private const val AXES = "<c:catAx><c:axId val=\"50010\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"b\"/><c:numFmt formatCode=\"General\" sourceLinked=\"0\"/><c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"50020\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>" +
        "<c:valAx><c:axId val=\"50020\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:majorTickMark val=\"out\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"50010\"/><c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx>"

    /** Reads back the chart kinds, titles and series names of chart parts (inspection). */
    fun describe(xml: ByteArray): String = runCatching {
        val d = Pkg.dom(xml).documentElement
        val kind = listOf("barChart" to "barres", "lineChart" to "courbes", "pieChart" to "secteurs", "areaChart" to "aires", "scatterChart" to "nuage").firstOrNull { Pkg.all(d, C, it.first).isNotEmpty() }?.second ?: "autre"
        val title = Pkg.all(d, C, "title").firstOrNull()?.let { t -> Pkg.all(t, Pkg.A, "t").joinToString("") { it.textContent } }?.ifBlank { null }
        val names = Pkg.all(d, C, "ser").map { s -> Pkg.child(s, C, "tx")?.let { tx -> Pkg.all(tx, C, "v").firstOrNull()?.textContent } ?: "?" }
        "graphique en $kind" + (title?.let { " « $it »" } ?: "") + if (names.isNotEmpty()) " (séries : ${names.joinToString()})" else ""
    }.getOrDefault("graphique")
}

/** Pixel size of a PNG or JPEG from its header (no decoding), for aspect-correct placement. */
object ImageSize {
    fun of(b: ByteArray): Pair<Int, Int>? {
        if (b.size > 24 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) {
            fun i(o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
            return i(16) to i(20)
        }
        if (b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) {
            var p = 2
            while (p + 9 < b.size) {
                if (b[p] != 0xFF.toByte()) { p++; continue }
                val m = b[p + 1].toInt() and 0xff
                val len = ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    val h = ((b[p + 5].toInt() and 0xff) shl 8) or (b[p + 6].toInt() and 0xff)
                    val w = ((b[p + 7].toInt() and 0xff) shl 8) or (b[p + 8].toInt() and 0xff)
                    return w to h
                }
                p += 2 + len
            }
        }
        return null
    }

    fun mime(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        else -> null
    }
}

/**
 * The few spreadsheet formulas Cortana evaluates itself so that a written workbook carries cached
 * values (read back without a spreadsheet app): numbers, cell references, + - * / ^, parentheses,
 * SUM, AVERAGE, MIN, MAX, COUNT, ROUND, ABS. Anything else yields null and is left to the
 * spreadsheet app, which recalculates on open (fullCalcOnLoad).
 */
object Formulas {
    fun evaluate(formula: String, lookup: (String) -> Cell): Double? = runCatching { Parser(formula.removePrefix("="), lookup, 0).parse() }.getOrNull()?.takeIf { it.isFinite() }

    /** Values for every formula cell of a sheet (references to other formulas are followed, cycles give null). */
    fun cache(rows: List<List<Cell>>): Map<String, Double> {
        val cells = HashMap<String, Cell>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> if (cell != Cell.Empty) cells["${Xlsx.col(c)}${r + 1}"] = cell } }
        val done = HashMap<String, Double?>(); val visiting = HashSet<String>()
        fun value(ref: String): Cell {
            val c = cells[ref] ?: return Cell.Empty
            if (c !is Cell.Formula) return c
            if (ref in done) return done[ref]?.let { Cell.Num(it) } ?: Cell.Text("#ERR")
            if (!visiting.add(ref)) return Cell.Text("#CYCLE")
            val v = evaluate(c.f, ::value)
            visiting.remove(ref); done[ref] = v
            return v?.let { Cell.Num(it) } ?: Cell.Text("#ERR")
        }
        cells.filterValues { it is Cell.Formula }.keys.forEach { value(it) }
        return done.filterValues { it != null }.mapValues { it.value!! }
    }

    private class Parser(val s: String, val lookup: (String) -> Cell, var p: Int) {
        fun parse(): Double { val v = expr(); ws(); if (p != s.length) error("reste"); return v }
        fun ws() { while (p < s.length && s[p] == ' ') p++ }
        fun peek(): Char? { ws(); return s.getOrNull(p) }
        fun expr(): Double { var v = term(); while (true) { when (peek()) { '+' -> { p++; v += term() }; '-' -> { p++; v -= term() }; else -> return v } } }
        fun term(): Double { var v = power(); while (true) { when (peek()) { '*' -> { p++; v *= power() }; '/' -> { p++; val d = power(); if (d == 0.0) error("div0"); v /= d }; else -> return v } } }
        fun power(): Double { val b = unary(); return if (peek() == '^') { p++; Math.pow(b, power()) } else b }
        fun unary(): Double = when (peek()) { '-' -> { p++; -unary() }; '+' -> { p++; unary() }; else -> primary() }
        fun primary(): Double {
            val c = peek() ?: error("fin")
            if (c == '(') { p++; val v = expr(); if (peek() != ')') error(")"); p++; return v }
            if (c.isDigit() || c == '.') { val st = p; while (p < s.length && (s[p].isDigit() || s[p] == '.' || s[p] == 'E' || s[p] == 'e')) p++; return s.substring(st, p).toDouble() }
            val st = p; while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '$' || s[p] == '_')) p++
            val word = s.substring(st, p).uppercase()
            if (peek() == '(') { p++; val args = args(); return fn(word, args) }
            return num(ref(word))
        }
        fun ref(w: String): String { val r = w.replace("$", ""); if (!r.matches(Regex("[A-Z]{1,3}[0-9]{1,7}"))) error("ref"); return r }
        fun num(ref: String): Double = when (val c = lookup(ref)) { is Cell.Num -> c.v; is Cell.Date -> c.serial; is Cell.Bool -> if (c.v) 1.0 else 0.0; Cell.Empty -> 0.0; else -> error("texte") }
        /** Arguments: expressions or ranges (A1:B3), flattened to the numbers they hold (text and blanks skipped in ranges). */
        fun args(): List<Double?> {
            val out = mutableListOf<Double?>()
            if (peek() == ')') { p++; return out }
            while (true) {
                val save = p; ws()
                val m = Regex("\\$?[A-Za-z]{1,3}\\$?[0-9]{1,7}:\\$?[A-Za-z]{1,3}\\$?[0-9]{1,7}").find(s, p)?.takeIf { it.range.first == p }
                if (m != null) {
                    p = m.range.last + 1
                    val (a, b) = m.value.uppercase().replace("$", "").split(':')
                    val r1 = minOf(Xlsx.rowIndex(a), Xlsx.rowIndex(b)); val r2 = maxOf(Xlsx.rowIndex(a), Xlsx.rowIndex(b))
                    val c1 = minOf(Xlsx.colIndex(a), Xlsx.colIndex(b)); val c2 = maxOf(Xlsx.colIndex(a), Xlsx.colIndex(b))
                    if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 200_000) error("plage")
                    for (r in r1..r2) for (c in c1..c2) out += when (val v = lookup("${Xlsx.col(c)}${r + 1}")) { is Cell.Num -> v.v; is Cell.Date -> v.serial; else -> null }
                } else { p = save; out += expr() }
                when (peek()) { ',', ';' -> p++; ')' -> { p++; return out }; else -> error("args") }
            }
        }
        fun fn(name: String, a: List<Double?>): Double {
            val n = a.filterNotNull()
            return when (name) {
                "SUM", "SOMME" -> n.sum()
                "AVERAGE", "MOYENNE" -> if (n.isEmpty()) error("div0") else n.average()
                "MIN" -> n.minOrNull() ?: 0.0
                "MAX" -> n.maxOrNull() ?: 0.0
                "COUNT", "NB" -> n.size.toDouble()
                "ABS" -> Math.abs(n.single())
                "ROUND", "ARRONDI" -> { val f = Math.pow(10.0, (n.getOrNull(1) ?: 0.0)); Math.round(n[0] * f) / f }
                else -> error("fonction")
            }
        }
        fun error(m: String): Nothing = throw IllegalArgumentException(m)
    }
}
