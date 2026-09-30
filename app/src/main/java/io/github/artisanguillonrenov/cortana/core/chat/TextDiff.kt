package io.github.artisanguillonrenov.cortana.core.chat

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

/** Line diff of two versions of a text artifact (doc 07 §7.3), with the JGit diff already used by the documents. */
object TextDiff {
    data class Summary(val added: Int, val removed: Int, val text: String)

    fun unified(before: String, after: String, context: Int = 2, maxLines: Int = 2_000): Summary {
        val a = RawText(before.toByteArray()); val b = RawText(after.toByteArray())
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)
        if (edits.isEmpty()) return Summary(0, 0, "Aucune différence.")
        val out = StringBuilder()
        var lines = 0
        for (e in edits) {
            if (lines > maxLines) { out.append("… (différences suivantes non affichées)\n"); break }
            val from = (e.beginA - context).coerceAtLeast(0)
            out.append("@@ −").append(e.beginA + 1).append(",").append(e.lengthA).append(" +").append(e.beginB + 1).append(",").append(e.lengthB).append(" @@\n")
            for (i in from until e.beginA) { out.append("  ").append(a.getString(i)).append('\n'); lines++ }
            for (i in e.beginA until e.endA) { out.append("- ").append(a.getString(i)).append('\n'); lines++ }
            for (i in e.beginB until e.endB) { out.append("+ ").append(b.getString(i)).append('\n'); lines++ }
            for (i in e.endA until minOf(e.endA + context, a.size())) { out.append("  ").append(a.getString(i)).append('\n'); lines++ }
        }
        return Summary(edits.sumOf { it.lengthB }, edits.sumOf { it.lengthA }, out.toString().trimEnd())
    }
}
