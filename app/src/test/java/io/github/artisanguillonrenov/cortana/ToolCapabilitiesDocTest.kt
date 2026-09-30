package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.policy.Trait
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * docs/TOOL_CAPABILITIES.md is generated from the live registry and must never drift from it.
 * To regenerate after changing tools: run this test with -Dcortana.regenerateDocs=true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolCapabilitiesDocTest : CortanaTestBase() {
    @Test fun toolCapabilitiesDocMatchesTheRegistry() {
        val tools = c.registry.all()
        val md = buildString {
            append("# Capacités d'outils (générées depuis le registre)\n\n")
            append("Ne pas modifier à la main : `ToolCapabilitiesDocTest` compare ce fichier au registre réel ")
            append("(régénérer avec `-Dcortana.regenerateDocs=true`). Nom de fonction exposé au modèle = identifiant avec `_` à la place de `.`.\n\n")
            append("Risque de base : L0 lecture, L1 réversible local, L2 confirmation du propriétaire, L3 empreinte/biométrie. ")
            append("Le risque effectif n'est jamais inférieur au risque de base : traits, classification des arguments, contenu non fiable et destination peuvent l'élever (`PolicyEngine`).\n\n")
            append("Total : ${tools.size} capacités.\n\n")
            for ((cat, list) in tools.groupBy { it.category.name }.toSortedMap()) {
                append("## ${cat.lowercase()}\n\n| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |\n|---|---|---|---|---|---|---|\n")
                for (t in list) {
                    val traits = t.traits.filter { it != Trait.READ_ONLY && it != Trait.REVERSIBLE && it != Trait.EXTERNAL_SIDE_EFFECT }.joinToString(", ") { it.name.lowercase() }
                    append("| `${t.capability}` | ${t.baseRisk.name} | ${t.sideEffect.name.lowercase()} | ${t.idempotency.name.lowercase()} | ${t.dataEgress.name.lowercase()} | $traits | ${t.label.replace("|", "/")} |\n")
                }
                append('\n')
            }
        }
        val doc = listOf(File("../docs/TOOL_CAPABILITIES.md"), File("docs/TOOL_CAPABILITIES.md")).firstOrNull { it.parentFile?.isDirectory == true } ?: File("../docs/TOOL_CAPABILITIES.md")
        if (System.getProperty("cortana.regenerateDocs") == "true") doc.writeText(md)
        assertEquals("docs/TOOL_CAPABILITIES.md is out of date: regenerate it", md, doc.readText())
    }
}
