package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.PatchOperation
import io.github.artisanguillonrenov.cortana.contracts.PatchSet
import io.github.artisanguillonrenov.cortana.core.dev.GitRefused
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** VNext phase 10 gate: isolated branch workflow without touching the main branch; destructive shortcuts refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitTest : CortanaTestBase() {

    private fun repo(): WorkspaceEntity = runBlocking {
        val src = SampleProjects.kotlinCalc(File(app.cacheDir, "fixture-git"))
        val w = c.workspaces.importDocument(DocumentFile.fromFile(src), "calc", "imported:test")
        c.git.init(w)
        c.git.commit(w, "Version initiale")
        c.workspaces.get(w.workspaceId)!!
    }

    private fun f(w: WorkspaceEntity, p: String) = File(w.rootPath, p)
    private fun ps(w: WorkspaceEntity, vararg ops: PatchOperation) = PatchSet(patchId = "p" + System.nanoTime(), workspaceId = w.workspaceId, operations = ops.toList(), generatedAt = 1)

    @Test fun isolatedWorktreeBranchNeverTouchesMain() = runBlocking {
        val main = repo()
        val mainHead = c.git.status(main).head!!
        val calcBefore = f(main, "app/src/main/kotlin/calc/Calc.kt").readText()
        val wt = c.git.createWorktree(main, "cortana/corrige-add")
        assertEquals("worktree:${main.workspaceId}", wt.origin)
        c.patchEngine.apply(wt, ps(wt, PatchOperation.Replace("app/src/main/kotlin/calc/Calc.kt", "a - b", "a + b")), "t")
        val commit = c.git.commit(wt, "Corrige add")
        val plan = c.git.pushPlan(wt)
        assertTrue(plan.local); assertFalse(plan.protectedTarget); assertEquals(1, plan.commits)
        c.git.push(wt)
        // main: same HEAD, same files, clean; the fix is available as a branch for review.
        assertEquals(mainHead, c.git.status(main).head)
        assertEquals("main", c.git.status(main).branch)
        assertTrue(c.git.status(main).clean)
        assertEquals(calcBefore, f(main, "app/src/main/kotlin/calc/Calc.kt").readText())
        assertTrue(c.git.branches(main).first.contains("cortana/corrige-add"))
        assertTrue(c.git.diff(main, from = "main", to = "cortana/corrige-add").contains("+    fun add(a: Int, b: Int): Int = a + b"))
        assertEquals(commit, Git.open(File(main.rootPath)).use { it.repository.resolve("cortana/corrige-add").name })
        assertEquals(mainHead, c.git.mergeBase(main, "main", "cortana/corrige-add"))
        assertEquals(listOf(wt.workspaceId), c.git.worktrees(main).map { it.workspaceId })
    }

    @Test fun readsCoverStatusDiffLogShowBlame() = runBlocking {
        val w = repo()
        f(w, "README.md").appendText("Nouvelle ligne\n")
        f(w, "notes.txt").writeText("brouillon\n")
        val st = c.git.status(w)
        assertEquals(setOf("README.md"), st.modified)
        assertEquals(setOf("notes.txt"), st.untracked)
        assertTrue(c.git.diff(w).contains("+Nouvelle ligne"))
        c.git.commit(w, "Ajoute une ligne", listOf("README.md"))
        assertEquals(setOf("notes.txt"), c.git.status(w).untracked)
        val log = c.git.log(w)
        assertEquals(listOf("Ajoute une ligne", "Version initiale"), log.map { it.message })
        assertTrue(c.git.show(w, log.first().id).contains("+Nouvelle ligne"))
        assertTrue(c.git.blame(w, "README.md").lines().last().contains("Nouvelle ligne"))
        val profile = c.repoIntelligence.profile(w)
        assertEquals("git", profile.vcs); assertEquals("main", profile.branch); assertEquals(1, profile.uncommittedChanges)
    }

    @Test fun destructiveShortcutsAreRefused() = runBlocking {
        val w = repo()
        c.git.createBranch(w, "autre", checkout = true)
        f(w, "README.md").writeText("version autre\n"); c.git.commit(w, "autre")
        c.git.switch(w, "main")
        f(w, "README.md").writeText("travail en cours non commité\n")
        try { c.git.switch(w, "autre"); fail("checkout over unknown local changes") } catch (e: GitRefused) { assertTrue(e.message!!.contains("écrasées")) }
        assertEquals("travail en cours non commité\n", f(w, "README.md").readText())
        f(w, "README.md").writeText("# Calc\nPetite calculatrice. Lancer les tests : ./gradlew test\n")
        try { c.git.merge(w, "autre", ownerInstructed = false); fail("merge into protected main") } catch (e: GitRefused) { assertTrue(e.message!!.contains("protégée")) }
        try { c.git.commit(w, "   "); fail() } catch (e: GitRefused) { }
        try { c.git.commit(w, "rien"); fail("nothing to commit") } catch (e: GitRefused) { }
        try { c.workspaces.fs(w).resolve(".git/HEAD", forWrite = true); fail() } catch (e: Exception) { }
        try { c.git.clone("file:///etc", "x", null); fail("only https clones") } catch (e: GitRefused) { }
    }

    @Test fun conflictingMergeIsAbortedCleanly() = runBlocking {
        val w = repo()
        c.git.createBranch(w, "a", checkout = true); f(w, "README.md").writeText("A\n"); c.git.commit(w, "a")
        c.git.switch(w, "main"); c.git.createBranch(w, "b", checkout = true); f(w, "README.md").writeText("B\n"); c.git.commit(w, "b")
        val head = c.git.status(w).head
        try { c.git.merge(w, "a", ownerInstructed = false); fail() } catch (e: GitRefused) { assertTrue(e.message!!.contains("Conflits")) }
        assertEquals(head, c.git.status(w).head)
        assertTrue(c.git.status(w).clean)
        assertEquals("B\n", f(w, "README.md").readText())
    }

    private fun decision(taskId: String) = runBlocking { c.db.tasks().toolCalls(taskId) }.single { it.capability == "repo.push" || it.capability == "repo.merge" }
        .let { AppJson.decodeFromString(PolicyDecision.serializer(), it.policyDecisionJson) }

    @Test fun pushToARealRemoteNeedsTheOwnersBiometricApproval() {
        val w = repo()
        Git.open(File(w.rootPath)).use { g -> g.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish("https://example.com/moi/calc.git")).call() }
        val s = session()
        server.enqueue(toolCall("repo_push", """{"workspace":"calc"}"""))
        server.enqueue(text("Poussée refusée."))
        var seen: io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest? = null
        val approver = Thread {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) { c.approvals.pending.value?.let { seen = it; c.approvals.resolve(it.id, ApprovalDecision(false)); return@Thread }; Thread.sleep(20) }
        }.also { it.start() }
        runAndWait(s, "Pousse le projet calc"); approver.join()
        val d = decision(lastTask().id)
        assertEquals(Risk.L3, d.effectiveRisk)
        assertEquals(Requirement.BIOMETRIC, d.requirement)
        assertTrue(seen!!.biometric)
        assertTrue(seen!!.target!!.contains("example.com") && seen!!.target!!.contains("branche main"))
        assertEquals("refused", runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single().outcome)
    }

    @Test fun protectedMergeHappensOnlyAfterPhysicalConfirmation() {
        val w = repo()
        runBlocking { c.git.createBranch(w, "feature", checkout = true); f(w, "README.md").appendText("feature\n"); c.git.commit(w, "feature"); c.git.switch(w, "main") }
        val before = runBlocking { c.git.status(w).head }
        val s = session()
        server.enqueue(toolCall("repo_merge", """{"workspace":"calc","branch":"feature"}"""))
        server.enqueue(text("Fusion faite."))
        runAndWait(s, "Fusionne feature dans main", approve = true)
        val d = decision(lastTask().id)
        assertEquals(Requirement.BIOMETRIC, d.requirement)
        assertNotEquals(before, runBlocking { c.git.status(w).head })
        assertTrue(f(w, "README.md").readText().endsWith("feature\n"))
    }

    @Test fun modelCreatesCommitsAndReadsHistory() {
        val s = session()
        server.enqueue(toolCall("workspace_create", """{"name":"notes"}"""))
        server.enqueue(toolCall("code_patch_apply", """{"workspace":"notes","operations":[{"op":"create","path":"idees.md","content":"# Idées\n"}]}""", id = "c2"))
        server.enqueue(toolCall("repo_commit", """{"workspace":"notes","message":"Ajoute idees.md"}""", id = "c3"))
        server.enqueue(text("Projet notes créé et commité."))
        runAndWait(s, "Crée un projet notes avec un fichier idées et commite-le")
        val t = lastTask()
        assertEquals(listOf("workspace.create", "code.patch.apply", "repo.commit"), runBlocking { c.db.tasks().toolCalls(t.id) }.map { it.capability })
        assertTrue(runBlocking { c.db.tasks().toolCalls(t.id) }.all { it.outcome == "ok" })
        val w = runBlocking { c.workspaces.find("notes")!! }
        assertEquals("Ajoute idees.md", runBlocking { c.git.log(w) }.single().message)
        assertTrue(runBlocking { c.checkpoints.notebookOrNull(t.id) }!!.currentWorkspaceRevision!!.length == 40)
    }
}
