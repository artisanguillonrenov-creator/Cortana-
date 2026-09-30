package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.chat.Envelopes
import io.github.artisanguillonrenov.cortana.core.chat.Markdown
import io.github.artisanguillonrenov.cortana.core.chat.MdAlign
import io.github.artisanguillonrenov.cortana.core.chat.MdBlock
import io.github.artisanguillonrenov.cortana.core.chat.MdInline
import io.github.artisanguillonrenov.cortana.core.chat.MessageText
import io.github.artisanguillonrenov.cortana.core.chat.SafeLinks
import io.github.artisanguillonrenov.cortana.core.chat.Sources
import io.github.artisanguillonrenov.cortana.core.chat.TexLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H4 (built in H1 for the adapters): Markdown, math, links, sources — pure, no Android. */
class ChatRenderLogicTest {
    private fun inl(s: String) = Markdown.inlines(s)

    @Test fun h4BlocksHeadingsListsTasksQuotesRules() {
        val md = """
            # Titre principal
            Sous-titre
            ---
            Un paragraphe
            sur deux lignes.

            - un
            - deux
              - imbriqué
            - [x] fait
            - [ ] à faire

            3. trois
            4. quatre

            > citation **forte**
            > suite

            ***
        """.trimIndent()
        val b = Markdown.parse(md)
        assertEquals(MdBlock.Heading(1, listOf(MdInline.Text("Titre principal")), "titre-principal"), b[0])
        assertTrue(b[1] is MdBlock.Heading && (b[1] as MdBlock.Heading).level == 2)
        val para = b[2] as MdBlock.Paragraph
        assertTrue(para.inlines.contains(MdInline.LineBreak))
        val list = b[3] as MdBlock.ListBlock
        assertFalse(list.ordered); assertEquals(4, list.items.size)
        assertTrue("nested list", list.items[1].blocks.any { it is MdBlock.ListBlock })
        assertEquals(true, list.items[2].task); assertEquals(false, list.items[3].task); assertNull(list.items[0].task)
        val ol = b[4] as MdBlock.ListBlock
        assertTrue(ol.ordered); assertEquals(3, ol.start); assertEquals(2, ol.items.size)
        val q = b[5] as MdBlock.Quote
        assertTrue((q.blocks[0] as MdBlock.Paragraph).inlines.any { it is MdInline.Strong })
        assertEquals(MdBlock.Rule, b[6])
    }

    @Test fun h4CodeTablesMathAndDiagrams() {
        val md = "```kotlin\nval x = 1\n```\n\n| Nom | Prix |\n|:--|--:|\n| thé | 3 € |\n| café \\| noir | `4 | 5` |\n\n$$\\frac{a}{b}$$\n\n```mermaid\ngraph TD; A-->B\n```\n\n```\nnon terminé"
        val b = Markdown.parse(md)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[0])
        val t = b[1] as MdBlock.Table
        assertEquals(listOf(MdAlign.LEFT, MdAlign.RIGHT), t.align)
        assertEquals(2, t.rows.size)
        assertEquals("café | noir", MessageText.plain(t.rows[1][0]))
        assertEquals("4 | 5", (t.rows[1][1].single() as MdInline.Code).text) // a pipe inside code stays in the cell
        assertEquals(MdBlock.Math("\\frac{a}{b}"), b[2])
        assertEquals(MdBlock.Diagram("mermaid", "graph TD; A-->B"), b[3])
        assertEquals(MdBlock.Code(null, "non terminé"), b[4]) // an unterminated fence runs to the end
    }

    @Test fun h4InlinesEmphasisCodeMathMoneyAndLinks() {
        assertEquals(listOf(MdInline.Text("a "), MdInline.Strong(listOf(MdInline.Text("gras"))), MdInline.Text(" et "), MdInline.Emph(listOf(MdInline.Text("italique")))), inl("a **gras** et *italique*"))
        assertEquals(listOf(MdInline.Strike(listOf(MdInline.Text("barré")))), inl("~~barré~~"))
        assertEquals(listOf(MdInline.Text("snake_case_name reste")), inl("snake_case_name reste")) // no intraword emphasis
        assertEquals(listOf(MdInline.Code("a*b*c")), inl("`a*b*c`"))
        assertEquals(listOf(MdInline.Text("aire "), MdInline.Math("\\pi r^2")), inl("aire \$\\pi r^2\$"))
        assertEquals(listOf(MdInline.Text("coûte \$5 et \$10 demain")), inl("coûte \$5 et \$10 demain")) // prices are not math
        assertEquals(listOf(MdInline.Link(listOf(MdInline.Text("site")), "https://ex.fr/a")), inl("[site](https://ex.fr/a)"))
        assertEquals(listOf(MdInline.Link(listOf(MdInline.Text("clic")), null)), inl("[clic](javascript:alert(1))")) // unsafe target dropped
        val bare = inl("voir https://ex.fr/page.")
        assertEquals(MdInline.Link(listOf(MdInline.Text("https://ex.fr/page")), "https://ex.fr/page"), bare[1])
        assertEquals(MdInline.Text("."), bare[2]) // trailing punctuation stays outside the link
        assertEquals(listOf(MdInline.Text("selon "), MdInline.Cite(2)), inl("selon [2]"))
        assertEquals(listOf(MdInline.Text("<script>alert(1)</script> <b>x</b>")), inl("<script>alert(1)</script> <b>x</b>")) // HTML is text
        assertEquals(listOf(MdInline.Text("*pas fermé et [lien(")), inl("*pas fermé et [lien("))
    }

    @Test fun h4MalformedAndHugeInputNeverThrowAndStayLinear() {
        val nasty = listOf("```", "|", "| a |\n|--", "> > > > > > > > > > > > x", "- - - - - - - - - - x", "**" .repeat(5_000), "[" .repeat(3_000) + "]", "\$\$\n", "\\(", "~~~\n~~")
        nasty.forEach { Markdown.parse(it) }
        val deep = (1..200).joinToString("\n") { "  ".repeat(it % 40) + "- niveau $it" }
        Markdown.parse(deep)
        val huge = buildString { repeat(4_000) { append("Ligne **$it** avec `code` et [lien](https://ex.fr/$it) et du texte.\n") } }
        val t0 = System.nanoTime()
        val blocks = Markdown.parse(huge)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("parse of ${huge.length} chars took $ms ms", ms < 3_000)
        assertTrue(blocks.isNotEmpty())
    }

    @Test fun h4TexLiteRendersCommonMathAndKeepsTheRestAsSource() {
        assertEquals(TexLite.Rendered("a⁄b", true), TexLite.render("\\frac{a}{b}"))
        assertEquals(TexLite.Rendered("(x+1)/(2y)", true), TexLite.render("\\frac{x+1}{2y}"))
        assertEquals("E = mc²", TexLite.render("E = mc^2").text)
        assertEquals("x₁ + x₂ ≤ α", TexLite.render("x_1 + x_2 \\leq \\alpha").text)
        assertEquals("∑ᵢ aᵢ", TexLite.render("\\sum_i a_i").text)
        assertEquals("√(x+y)", TexLite.render("\\sqrt{x+y}").text)
        assertEquals("prix total", TexLite.render("\\text{prix total}").text)
        assertEquals("f(x) → ∞", TexLite.render("f(x) \\to \\infty").text)
        val unknown = TexLite.render("\\weird{x}")
        assertFalse(unknown.exact); assertTrue(unknown.text.contains("\\weird"))
        TexLite.render("{".repeat(500)) // unbalanced, deep: no exception
    }

    @Test fun h4LinksAreSanitised() {
        listOf("https://ex.fr", "http://ex.fr/a?b=c", "mailto:a@b.fr", "tel:+33600000000", "artifact:1234").forEach { assertEquals(it, SafeLinks.sanitize(it)) }
        listOf("javascript:alert(1)", "JAVASCRIPT:x", "data:text/html,<b>", "file:///sdcard/x", "content://x/y", "intent://x#Intent;end", "vbscript:x", "/relatif", "../../etc/passwd",
            "https://ex.fr/a b", "https://", "https://\u0000x.fr").forEach { assertNull(it, SafeLinks.sanitize(it)) }
    }

    @Test fun h11ReadingIsSplitIntoSentencesSoItCanPauseAndResume() {
        val parts = io.github.artisanguillonrenov.cortana.util.Speaker.split("Bonjour. Comment vas-tu ? Très bien !\nFin")
        assertEquals(listOf("Bonjour.", "Comment vas-tu ?", "Très bien !", "Fin"), parts)
        val long = io.github.artisanguillonrenov.cortana.util.Speaker.split(("mot, ".repeat(300)).trim())
        assertTrue(long.size > 1 && long.all { it.length <= 400 })
        assertEquals(emptyList<String>(), io.github.artisanguillonrenov.cortana.util.Speaker.split("   "))
    }

    @Test fun h4SourcesComeFromToolResultsNotFromProse() {
        val search = "1. Titre A\n   https://a.fr/x\n   extrait A\n2. Titre B\n   https://b.fr\n   extrait B"
        val s = Sources.from("web.search", """{"query":"q"}""", search, 0)
        assertEquals(listOf("Titre A", "Titre B"), s.map { it.title }); assertEquals(listOf(1, 2), s.map { it.index })
        assertEquals("https://a.fr/x", s[0].url); assertTrue(s.all { it.untrusted })
        val f = Sources.from("web.fetch", """{"url":"https://c.fr/article"}""", "Mon article\nCorps du texte", 2)
        assertEquals(3, f.single().index); assertEquals("Mon article", f.single().title); assertEquals("https://c.fr/article", f.single().url)
        assertEquals(emptyList<Any>(), Sources.from("android.settings.open", null, "ok", 0))
        assertEquals("contenu", Envelopes.unwrap("<donnees_non_fiables source=\"web\">\ncontenu\n</donnees_non_fiables>"))
    }
}
