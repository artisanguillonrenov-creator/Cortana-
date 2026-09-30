package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.core.dev.LexicalProvider
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceException
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.worker.WorkerServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** VNext phase 14 gate: symbols, references, impact, static diagnostics, rename with diagnostics before/after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeIntelligenceTest : CortanaTestBase() {
    private lateinit var worker: WorkerServer
    private lateinit var workerDir: File

    @Before fun startWorker() {
        workerDir = Files.createTempDirectory("cortana-worker").toFile()
        worker = WorkerServer(workerDir, overridePort = 0).start()
    }

    @After fun stopWorker() { worker.stop(); workerDir.deleteRecursively() }

    private fun import(dir: File, name: String): WorkspaceEntity = runBlocking {
        val w = c.workspaces.importDocument(DocumentFile.fromFile(dir), name, "imported:test")
        c.repoIntelligence.inspectAndStore(w)
        c.workspaces.get(w.workspaceId)!!
    }

    @Test fun lexicalProviderCoversTheMandatoryLanguages() {
        val p = LexicalProvider
        fun names(file: String, text: String) = p.symbols(file, text, LexicalProvider.language(file)!!).map { it.kind + ":" + it.name }
        assertEquals(listOf("class:Repo", "function:load", "property:cache"), names("a/Repo.kt", "class Repo {\n    suspend fun load(id: String) = 1\n    private val cache = 2\n}\n"))
        assertEquals(listOf("class:Calc", "function:add"), names("Calc.java", "public class Calc {\n  public static int add(int a, int b) {\n    return a + b;\n  }\n}\n"))
        assertEquals(listOf("function:load", "class:Store", "method:get", "variable:x"), names("s.ts", "export async function load() {}\nclass Store {\n  get(k) {\n    if (k) { return 1 }\n  }\n}\nconst x = 3\n"))
        assertEquals(listOf("class:Calc", "function:add"), names("calc.py", "class Calc:\n    def add(self, a, b):\n        return a + b\n"))
        assertEquals(listOf("function:Add", "type:Point"), names("m.go", "func (c *Calc) Add(a int) int {\n}\ntype Point struct {\n}\n"))
        assertEquals(listOf("type:Calc", "function:add"), names("lib.rs", "pub struct Calc;\npub fn add(a: i32) -> i32 { a }\n"))
        assertEquals(listOf("function:add", "type:point"), names("m.c", "int add(int a, int b) {\n  return a + b;\n}\nstruct point {\n};\n"))
        assertEquals(listOf("./util", "lodash", "../x"), p.imports("const u = require('./util');\nimport _ from 'lodash';\nexport * from '../x';\n", "javascript"))
        assertEquals(listOf("calc.Calc", "org.junit.Test"), p.imports("import calc.Calc\nimport org.junit.Test\n", "kotlin"))
        // Strings and comments are blanked with columns preserved.
        val s = p.stripCommentsAndStrings("""val a = add("add") // add""", "kotlin")
        assertEquals(25, s.length); assertEquals(1, Regex("add").findAll(s).count())
        assertEquals(1, Regex("x").findAll(p.stripCommentsAndStrings("x = 1  # x", "python")).count())
    }

    @Test fun symbolsDefinitionsReferencesAndHoverOnAKotlinProject() = runBlocking {
        val w = import(SampleProjects.kotlinCalc(File(app.cacheDir, "fixture-kt")), "calc-kt")
        val add = c.codeIntel.symbols(w, "add").first()
        assertEquals("app/src/main/kotlin/calc/Calc.kt", add.file); assertEquals(4, add.line); assertEquals("Calc", add.container); assertEquals("function", add.kind)
        assertEquals(setOf("Calc", "CalcTest"), c.codeIntel.symbols(w, kind = "class").map { it.name }.toSet())
        val refs = c.codeIntel.references(w, "Calc")
        assertTrue(refs.first().isDefinition)
        assertEquals(setOf("app/src/main/kotlin/calc/Calc.kt:3", "app/src/test/kotlin/calc/CalcTest.kt:7", "app/src/test/kotlin/calc/CalcTest.kt:8"), refs.map { "${it.file}:${it.line}" }.toSet())
        assertTrue(c.codeIntel.hover(w, "mul")!!.contains("fun mul(a: Int, b: Int): Int"))
        // Same-package Kotlin tests have no import: impact is found through the symbols they use.
        assertEquals(listOf("app/src/test/kotlin/calc/CalcTest.kt"), c.codeIntel.impactedTests(w, listOf("app/src/main/kotlin/calc/Calc.kt")))
        // The index follows file changes (hash cache), no manual invalidation needed.
        File(w.rootPath, "app/src/main/kotlin/calc/Extra.kt").writeText("package calc\n\nfun square(x: Int) = Calc.mul(x, x)\n")
        assertEquals("app/src/main/kotlin/calc/Extra.kt", c.codeIntel.definitions(w, "square").single().file)
        assertTrue(c.codeIntel.references(w, "mul").any { it.file.endsWith("Extra.kt") })
    }

    @Test fun dependencyGraphImpactAndStaticDiagnosticsOnAJsProject() = runBlocking {
        val w = import(SampleProjects.jsCalc(File(app.cacheDir, "fixture-js")), "calc-js")
        val graph = c.codeIntel.dependencyGraph(w)
        assertEquals(setOf("src/calc.js"), graph["test/calc.test.js"])
        assertEquals(listOf("test/calc.test.js"), c.codeIntel.impactedTests(w, listOf("src/calc.js")))
        assertTrue(c.codeIntel.staticDiagnostics(w).isEmpty())
        File(w.rootPath, "src/broken.js").writeText("const m = require('./missing');\nfunction twice() {}\nfunction twice() {}\n")
        val d = c.codeIntel.staticDiagnostics(w)
        assertTrue(d.toString(), d.any { it.severity == "error" && it.file == "src/broken.js" && it.message.contains("./missing") })
        assertTrue(d.any { it.severity == "warning" && it.message.contains("twice") && it.line == 3 })
    }

    @Test fun renamePlanTouchesCodeOnlyAndRefusesConflicts() = runBlocking {
        val w = import(SampleProjects.jsCalc(File(app.cacheDir, "fixture-js")), "calc-js")
        val plan = c.codeIntel.renamePlan(w, "mul", "multiply")
        assertEquals(listOf("src/calc.js", "test/calc.test.js"), plan.files.sorted())
        assertEquals(2, plan.occurrences) // definition + c.mul(...); the test title 'mul' is a string
        assertTrue(plan.diff.contains("+  multiply: (a, b) => a * b,"))
        assertTrue(plan.diff.contains("+test('mul', () => assert.strictEqual(c.multiply(2, 3), 6));"))
        fun refused(block: suspend () -> Unit) = runCatching { runBlocking { block() } }.exceptionOrNull() is WorkspaceException
        assertTrue(refused { c.codeIntel.renamePlan(w, "add", "mul") })       // existing symbol
        assertTrue(refused { c.codeIntel.renamePlan(w, "add", "assert") })    // already used in a touched file
        assertTrue(refused { c.codeIntel.renamePlan(w, "nothing", "x2") })    // unknown symbol
        assertTrue(refused { c.codeIntel.renamePlan(w, "add", "class") })     // reserved word
        assertTrue(refused { c.codeIntel.renamePlan(w, "add", "a-b") })       // invalid identifier
        // A scoped rename leaves the rest of the project untouched.
        assertEquals(listOf("src/calc.js"), c.codeIntel.renamePlan(w, "mul", "times", scope = "src/**").files)
        assertTrue(File(w.rootPath, "src/calc.js").readText().contains("mul:")) // plans never write
    }

    @Test fun aProjectLockIsReleasedWhenItsTaskHasEnded() = runBlocking {
        val w = import(SampleProjects.jsCalc(File(app.cacheDir, "fixture-js")), "calc-js")
        fun task(id: String, state: String) = io.github.artisanguillonrenov.cortana.core.memory.TaskEntity(id, "s", "o", "interactive", state, false, "{}", 0)
        c.db.tasks().upsert(task("t-running", "running"))
        assertTrue(c.workspaces.lock(w.workspaceId, "t-running"))
        assertTrue("a running holder keeps the lock", !c.workspaces.lock(w.workspaceId, "t-other"))
        c.db.tasks().upsert(task("t-running", "completed"))
        assertTrue("an ended holder no longer blocks the project", c.workspaces.lock(w.workspaceId, "t-other"))
        assertEquals("t-other", c.workspaces.get(w.workspaceId)!!.lockTaskId)
        // A holder that no longer exists (crash, purge) does not block either.
        assertTrue(c.workspaces.lock(w.workspaceId, "t-third"))
    }

    // ---------------------------------------------------------------- gate: rename through the tool, tests before/after

    private fun runApprovingAll(s: SessionEntity, text: String) {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val approver = Thread {
            while (running.get()) { c.approvals.pending.value?.let { c.approvals.resolve(it.id, ApprovalDecision(true)) }; Thread.sleep(20) }
        }.also { it.start() }
        try { check(c.orchestrator.submit(s.id, text)); val deadline = System.currentTimeMillis() + 180_000; while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(50) }
        finally { running.set(false); approver.join() }
    }

    @Test fun renameWithTestsBeforeAndAfterAndAutomaticRollbackOnRegression() {
        assumeTrue("user namespaces unavailable", worker.sandbox.isolatedAvailable)
        runBlocking { c.workers.pair(worker.pairingString("127.0.0.1")) }
        val w = import(SampleProjects.jsCalc(File(app.cacheDir, "fixture-js")), "calc-js")
        File(w.rootPath, "src/calc.js").writeText("module.exports = {\n  add: (a, b) => a + b,\n  mul: (a, b) => a * b,\n};\n")
        // A dynamic lookup no lexical tool can follow: renaming `add` breaks it.
        File(w.rootPath, "test/dyn.test.js").writeText("const test = require('node:test');\nconst assert = require('node:assert');\nconst c = require('../src/calc');\n\ntest('dyn', () => assert.strictEqual(c['add'](1, 1), 2));\n")
        val s = session()
        server.enqueue(toolCall("code_rename", """{"workspace":"calc-js","symbol":"mul","new_name":"multiply"}""", id = "r0"))
        server.enqueue(toolCall("code_rename", """{"workspace":"calc-js","symbol":"mul","new_name":"multiply","apply":true,"verify":"tests"}""", id = "r1"))
        server.enqueue(toolCall("code_rename", """{"workspace":"calc-js","symbol":"add","new_name":"sum","apply":true,"verify":"tests"}""", id = "r2"))
        server.enqueue(text("mul renommé en multiply ; le renommage de add a été annulé car il cassait un test."))
        runApprovingAll(s, "Renomme mul en multiply puis add en sum dans calc-js, en vérifiant les tests.")
        val t = lastTask()
        val calls = runBlocking { c.db.tasks().toolCalls(t.id) }
        assertEquals(listOf("code.rename", "code.rename", "code.rename"), calls.map { it.capability })
        val (preview, ok, regress) = calls
        assertEquals(preview.outputRef, "ok", preview.outcome)
        assertTrue(preview.outputRef!!, preview.outputRef!!.contains("Aperçu : mul → multiply, 2 occurrence(s)"))
        assertEquals(ok.outputRef, "ok", ok.outcome)
        assertTrue(ok.outputRef!!, ok.outputRef!!.contains("Vérification (tests) : avant passed (3 ok, 0 ko) · après passed (3 ok, 0 ko)"))
        assertTrue(ok.outputRef!!.contains("isolated") || ok.outputRef!!.contains("✅ Renommage appliqué"))
        assertEquals(regress.outputRef, "error", regress.outcome)
        assertTrue(regress.outputRef!!, regress.outputRef!!.contains("↩️ Renommage annulé (régression détectée)"))
        assertTrue(regress.outputRef!!.contains("avant passed (3 ok, 0 ko) · après failed (2 ok, 1 ko)"))
        assertTrue(regress.outputRef!!.contains("Mentions non modifiées") && regress.outputRef!!.contains("test/dyn.test.js:5"))
        val calc = File(w.rootPath, "src/calc.js").readText()
        assertTrue(calc.contains("multiply: (a, b)") && calc.contains("add: (a, b)") && !calc.contains("sum"))
        assertTrue(File(w.rootPath, "test/calc.test.js").readText().contains("test('mul', () => assert.strictEqual(c.multiply(2, 3), 6));"))
        val sets = runBlocking { c.patchEngine.changeSetsForTask(t.id) }
        assertEquals(listOf("applied", "rolled_back"), sets.sortedBy { it.createdAt }.map { it.status })
        val nb = runBlocking { c.checkpoints.notebookOrNull(t.id) }!!
        assertEquals(listOf("src/calc.js", "test/calc.test.js"), nb.filesChanged.sorted())
        assertTrue(nb.testsRun.any { it.startsWith("après renommage → failed") })
        // Only the two verified applies (they run project code) needed the owner's approval; the preview did not.
        val approvals = runBlocking { c.db.runtime().approvalsForTask(t.id) }
        assertEquals(listOf("code.rename" to "L2", "code.rename" to "L2"), approvals.map { it.capability to it.risk })
    }
}
