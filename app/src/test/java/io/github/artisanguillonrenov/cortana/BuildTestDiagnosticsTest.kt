package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.RepositoryProfile
import io.github.artisanguillonrenov.cortana.core.dev.BuildAdapters
import io.github.artisanguillonrenov.cortana.core.dev.Diagnostics
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

/** VNext phase 13 gate: repair a sample project — failing tests, fix, green tests, artifact produced — plus normalized diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuildTestDiagnosticsTest : CortanaTestBase() {
    private lateinit var worker: WorkerServer
    private lateinit var workerDir: File

    @Before fun startWorker() {
        workerDir = Files.createTempDirectory("cortana-worker").toFile()
        worker = WorkerServer(workerDir, overridePort = 0).start()
    }

    @After fun stopWorker() { worker.stop(); workerDir.deleteRecursively() }

    // ---------------------------------------------------------------- diagnostics (real tool output formats)

    @Test fun compilerAndToolOutputsBecomeNormalizedDiagnostics() {
        val out = """
            e: file:///tmp/ws/app/src/main/kotlin/calc/Calc.kt:4:40 Unresolved reference 'x'.
            w: file:///tmp/ws/app/src/main/kotlin/calc/Calc.kt:5:9 Variable 'y' is never used.
            src/A.java:12: error: cannot find symbol
            src/app.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'.
            main.c:10:5: error: expected ';' before 'return'
            ./main.go:7:2: undefined: fmt.Printn
            FAILED tests/test_calc.py::test_add - assert -1 == 5
              File "/tmp/ws/calc.py", line 3, in add
                at calc.Calc.add(Calc.kt:4)
            [ERROR] /tmp/ws/src/main/java/A.java:[12,5] cannot find symbol
            error[E0425]: cannot find value `z` in this scope
              --> src/main.rs:3:5
            * What went wrong:
            Execution failed for task ':app:compileKotlin'.
        """.trimIndent()
        val d = Diagnostics.parse(out, listOf("/data/ws"))
        fun has(src: String, file: String?, line: Int?) = d.any { it.source == src && it.file == file && it.line == line }
        assertTrue(d.toString(), has("kotlinc", "app/src/main/kotlin/calc/Calc.kt", 4))
        assertEquals("warning", d.first { it.source == "kotlinc" && it.line == 5 }.severity)
        assertTrue(has("javac", "src/A.java", 12))
        assertTrue(d.any { it.source == "tsc" && it.code == "TS2322" && it.column == 7 })
        assertTrue(has("cc", "main.c", 10))
        assertTrue(has("go", "./main.go", 7))
        assertTrue(d.any { it.source == "pytest" && it.code == "test_add" && it.message.contains("-1 == 5") })
        assertTrue(has("python-trace", "calc.py", 3))
        assertTrue(has("jvm-trace", "Calc.kt", 4))
        assertTrue(has("maven", "src/main/java/A.java", 12))
        assertTrue(d.any { it.source == "rustc" && it.code == "E0425" && it.file == "src/main.rs" && it.line == 3 })
        assertTrue(d.any { it.source == "gradle" && it.message.contains(":app:compileKotlin") })
    }

    @Test fun junitReportsAreParsedAndXxeIsRefused() {
        val xml = """<?xml version="1.0"?><testsuites><testsuite name="calc"><testcase classname="calc.CalcTest" name="add"><failure message="expected:&lt;5&gt; but was:&lt;-1&gt;">java.lang.AssertionError
	at calc.CalcTest.add(CalcTest.kt:7)</failure></testcase><testcase classname="calc.CalcTest" name="mul"/><testcase name="later"><skipped/></testcase></testsuite></testsuites>"""
        val s = Diagnostics.junit(xml)
        assertEquals(3, s.tests); assertEquals(1, s.failures); assertEquals(1, s.skipped)
        assertEquals("expected:<5> but was:<-1>", s.failed.single().message)
        assertEquals("CalcTest.kt", s.failed.single().file); assertEquals(7, s.failed.single().line)
        assertEquals(listOf("calc.CalcTest.mul"), s.passed)
        val xxe = """<?xml version="1.0"?><!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]><testsuites><testcase name="&x;"/></testsuites>"""
        assertTrue(runCatching { Diagnostics.junit(xxe) }.isFailure)
    }

    @Test fun adaptersAreDetectedNotHardCoded() {
        fun p(vararg b: String) = RepositoryProfile(workspaceId = "w", fileCount = 0, totalBytes = 0, totalLines = 0, buildSystems = b.toList(), generatedAt = 0)
        val root = File(app.cacheDir, "adapters").apply { mkdirs() }
        val android = BuildAdapters.detect(p("gradle-android"))!!
        assertTrue(android.build(root, null).command.contains("assembleDebug"))
        assertTrue(android.build(root, null).artifactGlobs.any { it.endsWith("*.apk") })
        assertTrue(android.test(root, "calc.CalcTest").command.contains("--tests 'calc.CalcTest'"))
        assertEquals("maven", BuildAdapters.detect(p("maven"))!!.id)
        assertEquals("pnpm", BuildAdapters.detect(p("pnpm"))!!.id)
        assertTrue(BuildAdapters.detect(p("cargo"))!!.test(root, "add").command.startsWith("cargo test"))
        assertTrue(BuildAdapters.detect(p("go"))!!.test(root, "TestAdd").command.contains("-run 'TestAdd'"))
        assertEquals(null, BuildAdapters.detect(p()))
    }

    // ---------------------------------------------------------------- gate: repair a sample project

    private fun project(): WorkspaceEntity = runBlocking {
        val src = SampleProjects.jsCalc(File(app.cacheDir, "fixture-js"))
        val w = c.workspaces.importDocument(DocumentFile.fromFile(src), "calc-js", "imported:test")
        c.repoIntelligence.inspectAndStore(w)
        c.workspaces.get(w.workspaceId)!!
    }

    private fun runApprovingAll(s: SessionEntity, text: String) {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val approver = Thread {
            while (running.get()) { c.approvals.pending.value?.let { c.approvals.resolve(it.id, ApprovalDecision(true)) }; Thread.sleep(20) }
        }.also { it.start() }
        try { check(c.orchestrator.submit(s.id, text)); val deadline = System.currentTimeMillis() + 120_000; while (c.orchestrator.isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(50) }
        finally { running.set(false); approver.join() }
    }

    @Test fun modelRepairsAnUntrustedProjectOnTheIsolatedWorker() {
        assumeTrue("user namespaces unavailable", worker.sandbox.isolatedAvailable)
        runBlocking { c.workers.pair(worker.pairingString("127.0.0.1")) }
        val w = project()
        val s = session()
        server.enqueue(toolCall("test_run", """{"workspace":"calc-js"}""", id = "t1"))
        server.enqueue(toolCall("code_patch_apply", """{"workspace":"calc-js","operations":[{"op":"replace","path":"src/calc.js","find":"add: (a, b) => a - b","replace":"add: (a, b) => a + b"}]}""", id = "t2"))
        server.enqueue(toolCall("test_run", """{"workspace":"calc-js"}""", id = "t3"))
        server.enqueue(toolCall("build_run", """{"workspace":"calc-js"}""", id = "t4"))
        server.enqueue(text("Corrigé : add additionne, tests verts, paquet produit."))
        runApprovingAll(s, "Répare le projet calc-js et produis son paquet.")
        val t = lastTask()
        val calls = runBlocking { c.db.tasks().toolCalls(t.id) }
        assertEquals(listOf("test.run", "code.patch.apply", "test.run", "build.run"), calls.map { it.capability })
        val first = calls[0]; val second = calls[2]; val build = calls[3]
        assertEquals(first.outputRef, "error", first.outcome)
        assertTrue(first.outputRef!!.contains("1 échoué") && first.outputRef!!.contains("add"))
        assertTrue(first.outputRef!!.contains("isolated+no-network"))
        assertEquals(second.outputRef, "ok", second.outcome)
        assertTrue(second.outputRef!!.contains("Corrigés depuis le dernier passage") && second.outputRef!!.contains("add"))
        assertEquals(build.outputRef, "ok", build.outcome)
        val pkg = runBlocking { c.artifacts.forTask(t.id) }.single { it.name.endsWith(".tgz") }
        assertEquals("dist_calc-js-1.0.0.tgz", pkg.name)
        assertTrue(runBlocking { c.artifacts.verify(pkg.artifactId) })
        val nb = runBlocking { c.checkpoints.notebookOrNull(t.id) }!!
        assertEquals(listOf("src/calc.js"), nb.filesChanged)
        assertEquals(2, nb.testsRun.size); assertTrue(nb.testsRun.last().contains("passed"))
        assertTrue(File(w.rootPath, "src/calc.js").readText().contains("a + b"))
    }

    @Test fun flakyTestsAreIdentifiedNotHiddenAndInfraFailuresAreSeparated() = runBlocking {
        assumeTrue("user namespaces unavailable", worker.sandbox.isolatedAvailable)
        c.workers.pair(worker.pairingString("127.0.0.1"))
        val w = project()
        // First run fails and leaves a marker in the workspace copy; the retry passes → flaky.
        File(w.rootPath, "test/flaky.test.js").writeText("const test=require('node:test');const fs=require('fs');\ntest('instable', () => { if (!fs.existsSync('.marker')) { fs.writeFileSync('.marker','1'); throw new Error('raté'); } });\n")
        File(w.rootPath, "src/calc.js").writeText("module.exports = { add: (a, b) => a + b, mul: (a, b) => a * b };\n")
        val r = c.builds.test(w, null, NetworkMode.DENY, "t-flaky")
        assertEquals(r.render(), listOf("instable"), r.flaky)
        assertEquals("failed", r.status) // never silently green
        File(w.rootPath, "package.json").writeText("""{"name":"calc-js","version":"1.0.0","scripts":{"test":"echo 'Cannot find module ./missing' >&2; exit 3"}}""")
        val infra = c.builds.test(w, null, NetworkMode.DENY, "t-infra")
        assertEquals(infra.render(), "infra", infra.status)
        assertTrue(infra.infraFailure!!.isNotBlank())
        assertEquals(0, infra.failed)
    }
}
