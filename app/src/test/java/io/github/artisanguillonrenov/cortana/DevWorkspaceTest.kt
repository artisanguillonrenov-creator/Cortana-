package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.PatchOperation
import io.github.artisanguillonrenov.cortana.contracts.PatchSet
import io.github.artisanguillonrenov.cortana.core.dev.CodeSearch
import io.github.artisanguillonrenov.cortana.core.dev.Diff
import io.github.artisanguillonrenov.cortana.core.dev.PatchConflict
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** VNext phase 9 gate: import a project, inspect, search, patch preview/apply/rollback — confined and atomic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevWorkspaceTest : CortanaTestBase() {

    private fun imported(): WorkspaceEntity = runBlocking {
        val src = SampleProjects.kotlinCalc(File(app.cacheDir, "fixture-calc"))
        c.workspaces.importDocument(DocumentFile.fromFile(src), "calc", "imported:test")
    }

    private fun file(w: WorkspaceEntity, path: String) = File(w.rootPath, path)
    private fun patch(w: WorkspaceEntity, vararg ops: PatchOperation, hashes: Map<String, String> = emptyMap()) =
        PatchSet(patchId = "p-" + Random.nextInt(), workspaceId = w.workspaceId, operations = ops.toList(), expectedFileHashes = hashes, generatedAt = 1)

    @Test fun importedProjectIsProfiledBeforeAnyChange() = runBlocking {
        val w = imported()
        assertEquals("untrusted", w.trust)
        assertFalse("generated dirs are not imported", file(w, "build").exists())
        val p = c.repoIntelligence.inspectAndStore(w)
        assertEquals(listOf("kotlin"), p.languages.filter { it == "kotlin" })
        assertEquals(listOf("gradle"), p.buildSystems)
        assertEquals(listOf(":app"), p.modules)
        assertTrue(p.dependencies.contains("junit:junit:4.13.2"))
        assertTrue(p.testDirs.contains("app/src/test"))
        assertEquals(listOf(".github/workflows/ci.yml"), p.ciFiles)
        assertEquals(listOf("AGENTS.md", "README.md"), p.instructionFiles)
        assertEquals(1, p.binaryFiles)
        assertTrue(p.potentialSecrets.single().startsWith("config/local.properties"))
        assertTrue(p.tree.contains("app/"))
        assertTrue(c.workspaces.get(w.workspaceId)!!.buildSystemsJson.contains("gradle"))
    }

    @Test fun searchReturnsLocatedStructuredHits() = runBlocking {
        val w = imported()
        val add = c.codeSearch.search(w, "add", CodeSearch.Mode.TEXT)
        assertEquals("app/src/main/kotlin/calc/Calc.kt", add.first().path) // the definition ranks first
        assertEquals(4, add.first().startLine)
        assertEquals(listOf("app/src/test/kotlin/calc/CalcTest.kt"), c.codeSearch.search(w, "CalcTest", CodeSearch.Mode.FILES).map { it.path })
        assertEquals(2, c.codeSearch.search(w, "fun \\w+\\(a: Int", CodeSearch.Mode.REGEX, glob = "**/*.kt").size)
        assertEquals(1, c.codeSearch.search(w, "", CodeSearch.Mode.TODO).size)
        val secret = c.codeSearch.search(w, "", CodeSearch.Mode.SECRETS).single()
        assertFalse(secret.preview.contains("abcd1234efgh5678"))
    }

    @Test fun patchPreviewApplyAndExactRollback() = runBlocking {
        val w = imported()
        val path = "app/src/main/kotlin/calc/Calc.kt"
        val original = file(w, path).readBytes()
        val hash = Hash.sha256Bytes(original)
        val ps = patch(w, PatchOperation.Replace(path, "a - b", "a + b"), PatchOperation.Create("CHANGELOG.md", "- corrige add\n"), hashes = mapOf(path to hash))
        val preview = c.patchEngine.preview(w, ps)
        assertTrue(preview.conflicts.toString(), preview.ok)
        assertTrue(preview.diff.contains("-    fun add(a: Int, b: Int): Int = a - b") && preview.diff.contains("+    fun add(a: Int, b: Int): Int = a + b"))
        assertTrue(preview.diff.contains("+++ b/CHANGELOG.md"))
        assertEquals(hash, Hash.sha256Bytes(file(w, path).readBytes())) // preview wrote nothing
        val cs = c.patchEngine.apply(w, ps, "task-1")
        assertTrue(file(w, path).readText().contains("a + b"))
        assertEquals(setOf(path, "CHANGELOG.md"), cs.files.toSet())
        assertEquals(null, cs.beforeHashes["CHANGELOG.md"])
        assertEquals(1, c.patchEngine.changeSetsForTask("task-1").size)
        c.patchEngine.rollback(cs.changeSetId)
        assertTrue(original.contentEquals(file(w, path).readBytes()))
        assertFalse(file(w, "CHANGELOG.md").exists())
        try { c.patchEngine.rollback(cs.changeSetId); fail() } catch (e: WorkspaceException) { }
    }

    @Test fun conflictsLeaveEveryFileUntouched() = runBlocking {
        val w = imported()
        val calc = "app/src/main/kotlin/calc/Calc.kt"
        val before = file(w, calc).readText()
        fun rejects(ps: PatchSet, why: String) {
            val p = runBlocking { c.patchEngine.preview(w, ps) }
            assertFalse(why, p.ok)
            try { runBlocking { c.patchEngine.apply(w, ps, null) }; fail(why) } catch (e: PatchConflict) { }
            assertEquals(why, before, file(w, calc).readText())
        }
        rejects(patch(w, PatchOperation.Replace(calc, "a - b", "a + b"), hashes = mapOf(calc to "0".repeat(64))), "stale hash")
        rejects(patch(w, PatchOperation.Replace(calc, "a / b", "a + b")), "find not found")
        rejects(patch(w, PatchOperation.Replace(calc, "Int", "Long")), "ambiguous find (several occurrences)")
        rejects(patch(w, PatchOperation.Create("README.md", "x")), "create over existing")
        rejects(patch(w, PatchOperation.Replace(calc, "a - b", "a + b"), PatchOperation.Replace("README.md", "absent", "x")), "multi-file: one bad op cancels all")
    }

    @Test fun pathsCannotEscapeTheWorkspace() = runBlocking {
        val w = imported()
        val fs = c.workspaces.fs(w)
        for (bad in listOf("../evil.txt", "app/../../evil.txt")) {
            try { fs.resolve(bad); fail(bad) } catch (e: WorkspaceException) { }
        }
        try { fs.resolve(".git/config", forWrite = true); fail() } catch (e: WorkspaceException) { }
        val outside = File(app.cacheDir, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(w.rootPath, "link").toPath(), outside.toPath())
        try { fs.resolve("link/secret.txt"); fail("symlink escape") } catch (e: WorkspaceException) { }
        assertFalse(c.patchEngine.preview(w, patch(w, PatchOperation.Create("../x.txt", "x"))).ok)
    }

    @Test fun lineEndingsAndBomArePreserved() = runBlocking {
        val w = imported()
        file(w, "win.txt").writeText("\uFEFFligne un\r\nligne deux\r\n")
        c.patchEngine.apply(w, patch(w, PatchOperation.Replace("win.txt", "ligne deux", "ligne 2\nligne 3")), null)
        assertEquals("\uFEFFligne un\r\nligne 2\r\nligne 3\r\n", file(w, "win.txt").readText())
        c.patchEngine.apply(w, patch(w, PatchOperation.UnifiedDiff("win.txt", "@@ -1,2 +1,2 @@\n ligne un\n-ligne 2\n+ligne deux\n")), null)
        assertEquals("\uFEFFligne un\r\nligne deux\r\nligne 3\r\n", file(w, "win.txt").readText())
    }

    @Test fun unifiedDiffRoundTripsAndToleratesOffsets() {
        val rnd = Random(7)
        repeat(60) {
            val old = (1..rnd.nextInt(1, 40)).map { "ligne ${rnd.nextInt(10)}" }
            val new = old.toMutableList().apply {
                repeat(rnd.nextInt(1, 6)) {
                    when (rnd.nextInt(3)) {
                        0 -> if (isNotEmpty()) removeAt(rnd.nextInt(size))
                        1 -> add(rnd.nextInt(size + 1), "ajout ${rnd.nextInt(100)}")
                        else -> if (isNotEmpty()) set(rnd.nextInt(size), "modifié ${rnd.nextInt(100)}")
                    }
                }
            }
            val a = old.joinToString("\n", postfix = "\n"); val b = if (new.isEmpty()) "" else new.joinToString("\n", postfix = "\n")
            assertEquals(b, Diff.applyUnified(a, Diff.unified("f", "f", a, b)))
        }
        val base = (1..30).joinToString("\n", postfix = "\n") { "l$it" }
        val d = Diff.unified("f", "f", base, base.replace("l20\n", "L20\n"))
        val shifted = "entête 1\nentête 2\n$base"
        assertEquals(shifted.replace("l20\n", "L20\n"), Diff.applyUnified(shifted, d))
        try { Diff.applyUnified(base.replace("l19", "autre").replace("l21", "autre"), d); fail() } catch (e: PatchConflict) { }
    }

    @Test fun rollbackRefusesToOverwriteLaterWork() = runBlocking {
        val w = imported()
        val cs = c.patchEngine.apply(w, patch(w, PatchOperation.Replace("README.md", "Calc", "Calculatrice")), null)
        file(w, "README.md").appendText("modifié à la main\n")
        try { c.patchEngine.rollback(cs.changeSetId); fail() } catch (e: PatchConflict) { assertTrue(e.message!!.contains("README.md")) }
        c.patchEngine.rollback(cs.changeSetId, force = true)
        assertTrue(file(w, "README.md").readText().startsWith("# Calc\n"))
    }

    @Test fun artifactsAreHashedAndVerified() = runBlocking {
        val a = c.artifacts.registerText("rapport", "report", "rapport.txt", taskId = "t")
        assertEquals(Hash.sha256("rapport"), a.sha256)
        assertTrue(c.artifacts.verify(a.artifactId))
        c.artifacts.file(a).appendText("falsifié")
        assertFalse(c.artifacts.verify(a.artifactId))
        assertEquals(1, c.artifacts.forTask("t").size)
    }

    @Test fun modelDrivenFixUpdatesTheNotebook() {
        val w = imported()
        val s = session()
        server.enqueue(toolCall("code_search", """{"workspace":"${w.workspaceId.take(8)}","query":"fun add"}"""))
        server.enqueue(toolCall("code_patch_apply", """{"workspace":"calc","operations":[{"op":"replace","path":"app/src/main/kotlin/calc/Calc.kt","find":"a - b","replace":"a + b"}]}""", id = "call_2"))
        server.enqueue(text("J'ai corrigé add dans Calc.kt."))
        // Phase 15: a coding task is not "done" while its tests were never run on the new code; the
        // same refusal twice → the owner is asked instead of looping (the Gradle sample cannot run here).
        server.enqueue(text("Je ne peux pas lancer les tests Gradle ici."))
        runAndWait(s, "Corrige la fonction add du projet calc")
        val t = lastTask()
        assertEquals("waiting_user", t.state)
        assertTrue(messages(s).any { it.text.contains("Même échec 2 fois") && it.text.contains("termine sans vérification") })
        val calls = runBlocking { c.db.tasks().toolCalls(t.id) }
        assertEquals(listOf("code.search" to "ok", "code.patch.apply" to "ok"), calls.map { it.capability to it.outcome })
        assertTrue(file(w, "app/src/main/kotlin/calc/Calc.kt").readText().contains("a + b"))
        assertEquals(listOf("app/src/main/kotlin/calc/Calc.kt"), runBlocking { c.checkpoints.notebookOrNull(t.id) }!!.filesChanged)
        assertTrue("repository content taints the task", t.tainted)
    }
}
