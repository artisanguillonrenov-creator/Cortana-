package io.github.artisanguillonrenov.cortana.core.dev

class PatchConflict(message: String) : Exception(message)

/** Line diff (Myers O(ND)) → unified diff, and a strict unified-diff applier with small offset search. */
object Diff {
    sealed interface Edit { val line: String }
    data class Keep(override val line: String) : Edit
    data class Del(override val line: String) : Edit
    data class Ins(override val line: String) : Edit

    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

    fun edits(a: List<String>, b: List<String>): List<Edit> {
        val n = a.size; val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) v[max + k + 1] else v[max + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[max + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            if (found) break
        }
        // Backtrack.
        val out = ArrayList<Edit>()
        var x = n; var y = m
        for (d in trace.size - 1 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[max + k - 1] < vd[max + k + 1])) k + 1 else k - 1
            val prevX = vd[max + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { out += Keep(a[x - 1]); x--; y-- }
            if (d > 0) { if (x == prevX) out += Ins(b[y - 1]) else out += Del(a[x - 1]) }
            x = prevX; y = prevY
        }
        return out.asReversed()
    }

    fun unified(pathA: String, pathB: String, old: String?, new: String?, context: Int = 3): String {
        val a = lines(old ?: ""); val b = lines(new ?: "")
        if (a == b && old != null && new != null) return ""
        val e = edits(a, b)
        val sb = StringBuilder()
        sb.append("--- ").append(if (old == null) "/dev/null" else "a/$pathA").append('\n')
        sb.append("+++ ").append(if (new == null) "/dev/null" else "b/$pathB").append('\n')
        // Group edits into hunks with [context] lines around changes.
        val changeIdx = e.indices.filter { e[it] !is Keep }
        if (changeIdx.isEmpty()) return sb.toString()
        var i = 0
        while (i < changeIdx.size) {
            val start = (changeIdx[i] - context).coerceAtLeast(0)
            var end = (changeIdx[i] + context).coerceAtMost(e.size - 1)
            var j = i
            while (j + 1 < changeIdx.size && changeIdx[j + 1] - context <= end + 1) { j++; end = (changeIdx[j] + context).coerceAtMost(e.size - 1) }
            // Line numbers at hunk start.
            var aLine = 1; var bLine = 1
            for (t in 0 until start) { when (e[t]) { is Keep -> { aLine++; bLine++ }; is Del -> aLine++; is Ins -> bLine++ } }
            val hunk = e.subList(start, end + 1)
            val aCount = hunk.count { it !is Ins }; val bCount = hunk.count { it !is Del }
            sb.append("@@ -").append(if (aCount == 0) aLine - 1 else aLine).append(',').append(aCount)
                .append(" +").append(if (bCount == 0) bLine - 1 else bLine).append(',').append(bCount).append(" @@\n")
            hunk.forEach { ed -> sb.append(when (ed) { is Keep -> ' '; is Del -> '-'; is Ins -> '+' }).append(ed.line).append('\n') }
            i = j + 1
        }
        return sb.toString()
    }

    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /** Applies a single-file unified diff; context must match exactly (offset search ±[fuzzLines]). */
    fun applyUnified(original: String, diff: String, fuzzLines: Int = 40): String {
        val src = lines(original).toMutableList()
        val hadTrailingNewline = original.isEmpty() || original.endsWith("\n")
        var markerOld = false
        var markerNew = false
        val diffLines = diff.replace("\r\n", "\n").split("\n")
        var i = 0
        var offset = 0
        var any = false
        while (i < diffLines.size) {
            val m = hunkHeader.find(diffLines[i])
            if (m == null) { i++; continue }
            any = true
            val oldStart = m.groupValues[1].toInt()
            val before = mutableListOf<String>(); val after = mutableListOf<String>()
            i++
            while (i < diffLines.size && !diffLines[i].startsWith("@@")) {
                val l = diffLines[i]
                when {
                    l.startsWith("\\") -> {
                        val prev = diffLines.getOrNull(i - 1).orEmpty()
                        if (prev.startsWith("+")) markerNew = true
                        if (prev.startsWith("-")) markerOld = true
                        if (prev.startsWith(" ")) { markerOld = true; markerNew = true }
                    }
                    l.startsWith(" ") -> { before += l.substring(1); after += l.substring(1) }
                    l.startsWith("-") && !l.startsWith("---") -> before += l.substring(1)
                    l.startsWith("+") && !l.startsWith("+++") -> after += l.substring(1)
                    l.isEmpty() && i == diffLines.size - 1 -> Unit
                    l.isEmpty() -> { before += ""; after += "" } // blank context line whose leading space was stripped
                    l.startsWith("---") || l.startsWith("+++") -> Unit
                    else -> throw PatchConflict("ligne de diff invalide : « ${l.take(60)} »")
                }
                i++
            }
            val expected = (if (before.isEmpty()) oldStart else oldStart - 1) + offset
            val at = locate(src, before, expected.coerceAtLeast(0), fuzzLines)
                ?: throw PatchConflict("le contexte du bloc @@ -$oldStart ne correspond pas au fichier actuel")
            repeat(before.size) { src.removeAt(at) }
            src.addAll(at, after)
            offset += after.size - before.size + (at - expected)
        }
        if (!any) throw PatchConflict("aucun bloc @@ dans le diff")
        val text = src.joinToString("\n")
        val trailing = when { markerNew -> false; markerOld -> true; else -> hadTrailingNewline }
        return if (src.isEmpty()) "" else if (trailing) "$text\n" else text
    }

    private fun locate(src: List<String>, block: List<String>, expected: Int, fuzz: Int): Int? {
        fun matchesAt(p: Int) = p >= 0 && p + block.size <= src.size && (block.indices).all { src[p + it] == block[it] }
        if (block.isEmpty()) return expected.coerceIn(0, src.size)
        if (matchesAt(expected)) return expected
        for (d in 1..fuzz) { if (matchesAt(expected - d)) return expected - d; if (matchesAt(expected + d)) return expected + d }
        return null
    }
}
