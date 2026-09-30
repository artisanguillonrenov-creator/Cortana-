package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkspaceTrust
import io.github.artisanguillonrenov.cortana.core.exec.ExecResult
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionBackend
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionRefused
import io.github.artisanguillonrenov.cortana.core.exec.ProcessSpec
import io.github.artisanguillonrenov.cortana.core.exec.SandboxManager
import io.github.artisanguillonrenov.cortana.core.exec.SandboxMode
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** VNext phase 11 gate: sandboxed command + timeout + kill + logs; the sandbox never over-promises. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExecutionTest : CortanaTestBase() {

    private fun project(trust: WorkspaceTrust = WorkspaceTrust.TRUSTED_LOCAL): WorkspaceEntity = runBlocking {
        val w = c.workspaces.create("exec")
        c.workspaces.setTrust(w.workspaceId, trust)
        c.workspaces.get(w.workspaceId)!!
    }

    @Test fun commandRunsConfinedWithScrubbedEnvironmentAndLogs() = runBlocking {
        val w = project()
        val lines = mutableListOf<String>()
        val r = c.localBackend.run(w, ProcessSpec("echo bonjour; pwd; echo \"home=\$HOME\"; echo \"leak=\${JAVA_TOOL_OPTIONS:-none}\"; echo oops >&2; exit 3")) { lines += it }
        assertEquals("failed", r.status)
        assertEquals(3, r.exitCode)
        assertTrue(r.stdout.contains("bonjour"))
        assertTrue(r.stdout.lines().contains(File(w.rootPath).canonicalPath) || r.stdout.lines().contains(w.rootPath))
        assertTrue(r.stdout.contains("home=${File(w.rootPath).canonicalPath}") || r.stdout.contains("home=${w.rootPath}"))
        assertTrue("host environment is not inherited", r.stdout.contains("leak=none"))
        assertTrue(r.stderr.contains("oops"))
        assertTrue(lines.containsAll(listOf("bonjour", "oops")))
    }

    @Test fun timeoutKillsTheWholeProcessTree() = runBlocking {
        val w = project()
        val started = System.currentTimeMillis()
        val r = c.localBackend.run(w, ProcessSpec("(sleep 3; echo tard > late.txt) & sleep 30", timeoutMs = 800)) { }
        assertEquals("timed_out", r.status)
        assertNull(r.exitCode)
        assertTrue(System.currentTimeMillis() - started < 5_000)
        delay(3_500)
        assertFalse("the background child was killed too", File(w.rootPath, "late.txt").exists())
    }

    @Test fun cancellingTheTaskKillsTheProcess() = runBlocking {
        val w = project()
        val job = async(Dispatchers.IO) { c.localBackend.run(w, ProcessSpec("(sleep 2; echo tard > late.txt) & sleep 30", timeoutMs = 60_000)) { } }
        delay(500)
        job.cancel()
        runCatching { job.await() }
        delay(2_500)
        assertFalse(File(w.rootPath, "late.txt").exists())
    }

    @Test fun outputIsBoundedToItsTail() = runBlocking {
        val w = project()
        val r = c.localBackend.run(w, ProcessSpec("i=0; while [ \$i -lt 5000 ]; do i=\$((i+1)); echo ligne \$i; done", maxOutputBytes = 2_000)) { }
        assertTrue(r.ok)
        assertTrue(r.stdout.length <= 2_100)
        assertTrue(r.stdout.startsWith("…"))
        assertTrue(r.stdout.trimEnd().endsWith("ligne 5000"))
    }

    @Test fun cwdCannotEscapeTheProject() = runBlocking {
        val w = project()
        try { c.localBackend.run(w, ProcessSpec("ls", cwd = "../..")) { }; fail() } catch (e: Exception) { assertTrue(e.message!!.contains("hors du projet")) }
    }

    private class FakeIsolated : ExecutionBackend {
        override val id = "worker-test"; override val label = "worker de test"
        override suspend fun capabilities() = WorkerCapabilities("linux", "x86_64", 4, 1024, sandboxModes = listOf("process", "isolated"), networkModes = NetworkMode.entries)
        override fun supports(mode: SandboxMode, network: NetworkMode) = true
        override suspend fun run(w: WorkspaceEntity, spec: ProcessSpec, onLog: (String) -> Unit) = ExecResult("succeeded", 0, "isolé", "", 1, id, spec.sandbox.wire)
    }

    @Test fun sandboxChoiceFollowsTrustAndNeverOverPromises() {
        val trusted = project()
        val untrusted = project(WorkspaceTrust.UNTRUSTED)
        val readOnly = project(WorkspaceTrust.READ_ONLY)
        val localOnly = SandboxManager { listOf(c.localBackend) }
        val choice = localOnly.choose(trusted, ProcessSpec("true", network = NetworkMode.DENY))
        assertEquals(SandboxMode.PROCESS, choice.spec.sandbox)
        assertTrue(choice.note!!.contains("Réseau non isolé"))
        try { localOnly.choose(untrusted, ProcessSpec("true")); fail() } catch (e: ExecutionRefused) { assertTrue(e.message!!.contains("bac à sable isolé")) }
        try { localOnly.choose(readOnly, ProcessSpec("true")); fail() } catch (e: ExecutionRefused) { }
        val withWorker = SandboxManager { listOf(c.localBackend, FakeIsolated()) }
        val iso = withWorker.choose(untrusted, ProcessSpec("true"))
        assertEquals("worker-test", iso.backend.id); assertEquals(SandboxMode.ISOLATED, iso.spec.sandbox)
        assertEquals("worker-test", withWorker.choose(trusted, ProcessSpec("true", network = NetworkMode.DENY)).backend.id) // isolation preferred when available
        assertEquals("android-local", withWorker.choose(trusted, ProcessSpec("true", network = NetworkMode.ALLOW), preferred = "android-local").backend.id)
    }

    @Test fun modelRunsACommandOnlyAfterApprovalAndItIsNoted() {
        val w = project()
        val s = session()
        server.enqueue(toolCall("exec_run", """{"workspace":"${w.workspaceId}","command":"echo 42 > resultat.txt && cat resultat.txt"}"""))
        server.enqueue(text("La commande a écrit 42."))
        runAndWait(s, "Exécute la commande dans le projet exec", approve = true)
        val t = lastTask()
        val call = runBlocking { c.db.tasks().toolCalls(t.id) }.single()
        assertEquals("ok", call.outcome)
        assertTrue(call.policyDecisionJson.contains("\"requirement\":\"CONFIRM\""))
        assertEquals("42", File(w.rootPath, "resultat.txt").readText().trim())
        assertTrue(runBlocking { c.checkpoints.notebookOrNull(t.id) }!!.commandsRun.single().contains("succeeded"))
    }

    @Test fun untrustedProjectCommandIsDeniedBeforeAnything() {
        val w = project(WorkspaceTrust.UNTRUSTED)
        val s = session()
        server.enqueue(toolCall("exec_run", """{"workspace":"${w.workspaceId}","command":"touch pirate.txt"}"""))
        server.enqueue(text("Impossible sans bac à sable."))
        runAndWait(s, "Exécute le script du projet")
        assertEquals("denied", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single { it.capability == "exec.run" }.outcome)
        assertFalse(File(w.rootPath, "pirate.txt").exists())
    }
}
