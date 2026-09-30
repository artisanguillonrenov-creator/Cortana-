package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.orchestrator.DevCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit development commands are recognised only when intent and parameters are certain (pure JVM test). */
class DevCommandsTest {
    private val one = listOf("calc")
    private val two = listOf("calc", "notes")

    private fun caps(text: String, ws: List<String> = one) = DevCommands.parse(text, ws)?.calls?.map { it.capability }

    @Test fun explicitCommandsMapToOneCanonicalCall() {
        assertEquals(listOf("repo.status"), caps("git status"))
        assertEquals(listOf("repo.status"), caps("État git du projet calc"))
        assertEquals(listOf("repo.status"), caps("Cortana, donne-moi l'état git."))
        assertEquals(listOf("repo.fetch"), caps("git fetch"))
        assertEquals(listOf("repo.pull"), caps("fais un pull dans le projet calc"))
        assertEquals(listOf("repo.branch.create"), caps("Crée la branche feature/login"))
        assertEquals(listOf("repo.branch.create"), caps("git checkout -b fix-42"))
        assertEquals(listOf("repo.push"), caps("Pousse le projet calc"))
        assertEquals(listOf("repo.push"), caps("git push"))
        assertEquals(listOf("test.run"), caps("Lance les tests"))
        assertEquals(listOf("test.run"), caps("exécute les tests du projet calc !"))
        assertEquals(listOf("build.run"), caps("lance le build"))
        assertEquals(listOf("build.run"), caps("compile le projet calc"))
        assertEquals(listOf("workspace.inspect"), caps("inspecte le projet calc"))
        assertEquals(listOf("workspace.open"), caps("liste mes projets", emptyList()))
    }

    @Test fun parametersAreExtractedExactly() {
        val b = DevCommands.parse("crée une nouvelle branche cortana/fix-add dans le projet CALC", two)!!
        assertEquals("calc", b.workspace)
        assertEquals(mapOf("workspace" to "calc", "name" to "cortana/fix-add"), b.calls.single().args)
        val p = DevCommands.parse("pousse la branche feature/x", one)!!
        assertEquals(mapOf("workspace" to "calc", "branch" to "feature/x"), p.calls.single().args)
        // Defaults stay the tools' own (ff-only pull, network denied for tests): nothing is added.
        assertEquals(mapOf("workspace" to "calc"), DevCommands.parse("git pull", one)!!.calls.single().args)
        assertEquals(mapOf("workspace" to "calc"), DevCommands.parse("lance les tests", one)!!.calls.single().args)
    }

    @Test fun branchThenPushIsAFixedTwoCallSequence() {
        for (t in listOf("Crée la branche feature/x et pousse-la", "crée la branche feature/x puis pousse-la sur origin", "create branch feature/x and push it")) {
            val c = DevCommands.parse(t, one)
            assertNotNull(t, c)
            assertEquals(t, listOf("repo.branch.create", "repo.push"), c!!.calls.map { it.capability })
            assertEquals("feature/x", c.calls[1].args["branch"])
            assertEquals("calc", c.calls[1].args["workspace"])
        }
    }

    @Test fun ambiguousRequestsStayWithThePlanner() {
        // Several projects and none named, or an unknown project.
        assertNull(caps("git status", two))
        assertNull(caps("lance les tests", emptyList()))
        assertNull(caps("git status du projet inconnu", two))
        // More than the command: compound, extra intent, questions, filters.
        assertNull(caps("lance les tests et corrige ce qui échoue"))
        assertNull(caps("crée la branche feature/x et corrige le bug"))
        assertNull(caps("prépare une branche pour corriger le bug de calcul"))
        assertNull(caps("peux-tu lancer les tests ?"))
        assertNull(caps("lance les tests CalcTest"))
        assertNull(caps("pousse-la"))
        assertNull(caps("analyse le projet calc"))
        assertNull(caps("git status\ngit push"))
        // Forced pushes are never a shortcut.
        assertNull(caps("git push --force"))
        assertNull(caps("git push -f"))
        assertNull(caps("pousse en force la branche main"))
    }

    @Test fun invalidBranchNamesAreRefused() {
        for (bad in listOf("-x", "a..b", "a//b", "a/", "a.lock", "HEAD", "a@{1}", "é", "x/.y")) {
            assertFalse(bad, DevCommands.validRef(bad))
            assertNull(bad, caps("crée la branche $bad"))
        }
        assertTrue(DevCommands.validRef("cortana/corrige-add_2.1"))
    }

    @Test fun projectResolution() {
        assertEquals("calc", DevCommands.resolve("", one))
        assertNull(DevCommands.resolve("", two))
        assertEquals("notes", DevCommands.resolve("NOTES", two))
        assertNull(DevCommands.resolve("autre", two))
        assertNull(DevCommands.resolve("calc", listOf("calc", "Calc")))
    }
}
