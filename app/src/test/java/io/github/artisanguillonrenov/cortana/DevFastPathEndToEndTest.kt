package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.core.memory.TaskStates
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Explicit development commands take the deterministic fast path (no model call), still through the
 * dispatcher and the policy; ambiguous requests stay with the Planner; a failure or a refusal ends the
 * task instead of looping; the step loop never re-dispatches an identical failed call for nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevFastPathEndToEndTest : CortanaTestBase() {

    private fun repo(name: String = "calc"): WorkspaceEntity = runBlocking {
        val src = SampleProjects.kotlinCalc(File(app.cacheDir, "fixture-$name-${System.nanoTime()}"))
        val w = c.workspaces.importDocument(DocumentFile.fromFile(src), name, "imported:test")
        c.git.init(w)
        c.git.commit(w, "Version initiale")
        c.workspaces.get(w.workspaceId)!!
    }

    private fun calls(taskId: String = lastTask().id) = runBlocking { c.db.tasks().toolCalls(taskId) }

    @Test fun explicitGitStatusUsesTheFastPathWithoutAnyModelCall() {
        repo()
        val s = session()
        runAndWait(s, "git status du projet calc")
        assertEquals(0, server.requestCount)
        val t = lastTask()
        assertEquals(TaskStates.COMPLETED, t.state)
        assertEquals(listOf("repo.status" to "ok"), calls(t.id).map { it.capability to it.outcome })
        assertTrue(messages(s).last().text.contains("Branche main"))
    }

    @Test fun branchThenPushIsTwoDispatcherCallsAndNoModelCall() {
        val w = repo()
        val bare = File(app.cacheDir, "remote-${System.nanoTime()}.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        Git.open(File(w.rootPath)).use { g -> g.remoteAdd().setName("origin").setUri(URIish(bare.toURI().toString())).call() }
        val s = session()
        // Even to a local remote a push is visible to others: the owner is still asked (approved here).
        runAndWait(s, "Crée la branche feature/x et pousse-la", approve = true)
        assertEquals(0, server.requestCount)
        val t = lastTask()
        assertEquals(TaskStates.COMPLETED, t.state)
        assertEquals(listOf("repo.branch.create" to "ok", "repo.push" to "ok"), calls(t.id).map { it.capability to it.outcome })
        // The push went through the policy (decision recorded, approval audited) and reached the remote.
        val d = AppJson.decodeFromString(PolicyDecision.serializer(), calls(t.id).last().policyDecisionJson)
        assertTrue(d.requirement == Requirement.CONFIRM || d.requirement == Requirement.BIOMETRIC)
        assertTrue(runBlocking { c.db.audit().allAscending() }.any { it.action == "approval.repo.push" && it.outcome == "approved" })
        assertNotNull(Git.open(bare).use { it.repository.resolve("refs/heads/feature/x") })
    }

    @Test fun remotePushInTheSequenceStillNeedsBiometricApprovalAndARefusalEndsTheTask() {
        val w = repo()
        Git.open(File(w.rootPath)).use { g -> g.remoteAdd().setName("origin").setUri(URIish("https://example.com/moi/calc.git")).call() }
        val s = session()
        var seen: ApprovalRequest? = null
        val approver = Thread {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) { c.approvals.pending.value?.let { seen = it; c.approvals.resolve(it.id, ApprovalDecision(false)); return@Thread }; Thread.sleep(20) }
        }.also { it.start() }
        runAndWait(s, "crée la branche feature/y puis pousse-la")
        approver.join()
        val t = lastTask()
        assertEquals(0, server.requestCount)
        assertEquals(listOf("repo.branch.create" to "ok", "repo.push" to "refused"), calls(t.id).map { it.capability to it.outcome })
        val d = AppJson.decodeFromString(PolicyDecision.serializer(), calls(t.id).last().policyDecisionJson)
        assertEquals(Risk.L3, d.effectiveRisk)
        assertEquals(Requirement.BIOMETRIC, d.requirement)
        assertTrue(seen!!.biometric)
        assertTrue(seen!!.target!!.contains("example.com") && seen!!.target!!.contains("feature/y"))
        assertEquals(TaskStates.FAILED, t.state)
        assertTrue(t.terminationReason.orEmpty().startsWith("fast_path.refused"))
        val audit = runBlocking { c.db.audit().allAscending() }
        assertTrue(audit.any { it.action == "approval.repo.push" && it.outcome == "refused" })
        assertNull(runBlocking { c.audit.verify() })
    }

    @Test fun aFailedFastPathToolEndsTheTaskWithoutHandingOverToTheModel() {
        repo() // no remote configured: fetch fails
        val s = session()
        runAndWait(s, "git fetch")
        val t = lastTask()
        assertEquals(0, server.requestCount)
        assertEquals(listOf("repo.fetch" to "error"), calls(t.id).map { it.capability to it.outcome })
        assertEquals(TaskStates.FAILED, t.state)
        assertTrue(t.terminationReason.orEmpty().startsWith("fast_path.tool_failed"))
        assertTrue(messages(s).last().text.contains("inconnu"))
    }

    @Test fun ambiguousRequestsGoToThePlanner() {
        repo()
        repo("notes")
        val s = session()
        server.enqueue(text("Dans quel projet ?"))
        runAndWait(s, "git status") // two projects, none named
        assertEquals(1, server.requestCount)
        assertTrue(calls().none { it.capability.startsWith("repo.") })
        server.enqueue(text("Quel nom de branche voulez-vous ?"))
        runAndWait(s, "prépare une branche pour corriger le bug de calcul dans le projet calc")
        assertEquals(2, server.requestCount)
        assertTrue(calls().none { it.capability.startsWith("repo.") })
    }

    @Test fun theStepLoopDoesNotRepeatAnIdenticalFailedCall() {
        repo()
        val s = session()
        (1..4).forEach { i -> server.enqueue(toolCall("repo_fetch", """{"workspace":"calc"}""", id = "c$i")) }
        repeat(4) { server.enqueue(text("Je ne peux pas synchroniser : aucun dépôt distant.")) }
        runAndWait(s, "Synchronise le projet calc avec son dépôt distant")
        val t = lastTask()
        // Dispatched once; the repetitions were answered by the guard, and the step stopped well before the model budget.
        assertEquals(1, calls(t.id).count { it.capability == "repo.fetch" })
        assertTrue("model calls: ${server.requestCount}", server.requestCount <= 6)
        assertTrue(messages(s).any { it.text.contains("Appel identique déjà échoué") })
    }

    @Test fun aRelevantStateChangeReallowsTheSameCall() {
        repo()
        val s = session()
        server.enqueue(toolCall("repo_fetch", """{"workspace":"calc"}""", id = "c1"))
        server.enqueue(toolCall("repo_branch_create", """{"workspace":"calc","name":"sync"}""", id = "c2"))
        server.enqueue(toolCall("repo_fetch", """{"workspace":"calc"}""", id = "c3"))
        repeat(4) { server.enqueue(text("Aucun dépôt distant n'est configuré.")) }
        runAndWait(s, "Synchronise le projet calc avec son dépôt distant")
        val t = lastTask()
        // Git state changed (a branch was created and checked out) between the two fetches: both were dispatched.
        assertEquals(listOf("repo.fetch", "repo.branch.create", "repo.fetch"), calls(t.id).map { it.capability }.take(3))
    }
}
