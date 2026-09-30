package io.github.artisanguillonrenov.cortana.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.pressKey
import io.github.artisanguillonrenov.cortana.CortanaTestBase
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.ui.AppNav
import io.github.artisanguillonrenov.cortana.ui.approval.ApprovalActivity
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Design steps 3–4 on the real app (AppNav, the design's shell, the one runtime): the task's approval
 * is granted only on the secure screen and the task ends; a refusal pauses it and "Reprendre" asks
 * again; STOP and "Reprendre" work from the sidebar, the panel and the rail, and Échap stops.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignDiscussionUiTest : CortanaTestBase() {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val resumeAsked = AtomicInteger()

    @Before fun calm() = runBlocking {
        c.settings.update { it.copy(planningMode = "always", chat = it.chat.copy(reduceMotion = true)) }
        Unit
    }

    private fun show(s: SessionEntity) {
        rule.setContent {
            CompositionLocalProvider(LocalContainer provides c, LocalResumeAutonomy provides { resumeAsked.incrementAndGet(); Unit }) {
                CortanaTheme(reduceMotion = true) { AppNav(startOnboarding = false, openSession = s.id, onSessionConsumed = {}) }
            }
        }
        rule.waitForIdle()
    }

    private fun until(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS); Thread.sleep(20) }
        assertTrue("timeout: $what", cond())
    }

    private fun shown(text: String, tag: String? = null): Boolean {
        val m = if (tag == null) hasText(text, substring = true) else hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag))
        return rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitShown(text: String, tag: String? = null) = until("« $text »${tag?.let { " in $it" } ?: ""}") { shown(text, tag) }

    /** Clicks the text's own node (a container such as the rail may merge its children). */
    private fun click(text: String, tag: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performClick()

    /** The thread's approval card is up (the policy card's own "Approbation requise pour…" does not count). */
    private fun waitApprovalCard() = until("approval card") { c.approvals.pending.value != null && shown("Refuser") && shown("Autoriser une fois") }

    private fun send(text: String) {
        rule.onNodeWithContentDescription("Message pour Cortana").performTextInput(text)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Envoyer").performClick()
    }

    /** The real screen at density 1 (1 px = 1 dp), for the visual check: build/design-shots/live. */
    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        java.io.File("build/design-shots/live").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun task() = runBlocking { c.tasksFlow.first().first() }
    /** The latest task's state, or null before the first task exists. */
    private fun state(): String? = runBlocking { c.tasksFlow.first().firstOrNull()?.state }

    /** A two-step plan: step 1 forgets a memory (approval required), step 2 confirms; then the synthesis. */
    private fun forgetScript() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = request.body.readUtf8()
                return when {
                    "depends_on" in b -> text(planJson("""[
                        {"id":"s1","title":"Oublier le thé","objective":"Oublie la préférence thé","capabilities":["memory.forget"]},
                        {"id":"s2","title":"Confirmer","objective":"Confirme au propriétaire","depends_on":["s1"]}]"""))
                    "compte rendu final" in b -> text("Préférence oubliée.")
                    // Each attempt of step 1 asks for the tool; its answer comes once the tool result is the last message.
                    // A model that redoes an action the runtime reports as never run (after a pause), and answers once it ran.
                    "l'étape 1/2" in b -> if (lastRole(b) == "tool" && "Action non exécutée" !in lastContent(b)) text("Oublié.")
                        else toolCall("memory_forget", """{"query":"thé"}""", id = "call_${request.sequenceNumber}")
                    else -> text("Confirmé.")
                }
            }
        }
    }

    private fun last(body: String, key: String): String? = runCatching {
        io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.last().jsonObject[key]!!.jsonPrimitive.content
    }.getOrNull()
    private fun lastRole(body: String) = last(body, "role")
    private fun lastContent(body: String) = last(body, "content").orEmpty()

    /** A two-step plan whose first step answers slowly: time to press STOP while it runs. */
    private fun slowScript() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = request.body.readUtf8()
                return when {
                    "depends_on" in b -> text(planJson("""[
                        {"id":"s1","title":"Rassembler","objective":"Rassembler les idées"},
                        {"id":"s2","title":"Rédiger","objective":"Rédiger le résumé","depends_on":["s1"]}]"""))
                    "l'étape 1/2" in b -> text("Idées rassemblées.").setBodyDelay(4, TimeUnit.SECONDS)
                    else -> text("Fait.")
                }
            }
        }
    }

    private fun seedTea(): SessionEntity {
        runBlocking { c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "t") }
        return session()
    }

    @Config(qualifiers = "w1448dp-h1086dp-land-mdpi")
    @Test fun approvalIsGrantedOnlyOnTheSecureScreenThenTheTaskEnds() {
        val s = seedTea()
        forgetScript()
        show(s)
        send("Oublie ma préférence pour le thé, étape par étape.")
        waitApprovalCard()
        waitShown("Approbation", "task-panel")
        waitShown("STOP", "sidebar")
        shot("approval_landscape")
        val pending = c.approvals.pending.value
        assertNotNull(pending)

        // "Autoriser une fois" opens the secure screen; the chat component approves nothing itself.
        val app = shadowOf(RuntimeEnvironment.getApplication())
        while (app.nextStartedActivity != null) Unit
        rule.onNode(hasContentDescription("Autoriser une fois")).performClick()
        rule.waitForIdle()
        assertEquals(ApprovalActivity::class.java.name, app.nextStartedActivity?.component?.className)
        assertEquals("still pending until the secure screen decides", pending!!.id, c.approvals.pending.value?.id)

        c.approvals.resolve(pending.id, ApprovalDecision(true))
        until("task done") { !c.orchestrator.isBusy() && state() == TaskState.COMPLETED.wire }
        waitShown("Terminée", "task-panel")
        waitShown("Autorisation accordée une fois")
        waitShown("Préférence oubliée.")
        shot("done_landscape")
        assertEquals("approved", runBlocking { c.db.runtime().approvalsForTask(task().id) }.single().status)
        assertTrue(runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.none { it.text.contains("thé") })
    }

    @Config(qualifiers = "w1448dp-h1086dp-land-mdpi")
    @Test fun refusalPausesTheTaskAndResumeFromThePanelAsksAgain() {
        val s = seedTea()
        forgetScript()
        show(s)
        send("Oublie ma préférence pour le thé, étape par étape.")
        waitApprovalCard()
        rule.onNode(hasContentDescription("Refuser")).performClick()
        until("task paused") { !c.orchestrator.isBusy() && state() == TaskState.PAUSED.wire }
        waitShown("En pause", "task-panel")
        waitShown("Action refusée")
        waitShown("Reprendre", "sidebar")
        shot("refused_paused_landscape")
        val first = runBlocking { c.db.runtime().approvalsForTask(task().id) }.single()
        assertTrue(first.status, first.status in setOf("refused", "cancelled"))
        // Never left "pending", and the audit log keeps the outcome.
        assertTrue(runBlocking { c.db.audit().allAscending() }.any { it.action == "approval.memory.forget" && it.outcome in setOf("refused", "cancelled") })
        assertTrue("the memory is kept", runBlocking { c.memory.observe(MemoryStatus.ACTIVE).first() }.any { it.text.contains("thé") })

        // Reprendre (panel): the same task continues from its plan and asks again.
        val id = task().id
        click("Reprendre", "task-panel")
        waitApprovalCard()
        assertEquals("same task", id, task().id)
        assertEquals(2, runBlocking { c.db.runtime().approvalsForTask(id) }.size)

        c.approvals.resolve(c.approvals.pending.value!!.id, ApprovalDecision(true))
        until("task done") { !c.orchestrator.isBusy() && state() == TaskState.COMPLETED.wire }
        waitShown("Terminée", "task-panel")
    }

    @Config(qualifiers = "w1448dp-h1086dp-land-mdpi")
    @Test fun stopAndResumeFromTheSidebar() {
        val s = session()
        slowScript()
        show(s)
        send("Rassemble mes idées puis rédige un résumé, étape par étape.")
        waitShown("STOP", "sidebar")
        until("first step running") { state() == TaskState.RUNNING.wire && c.orchestrator.isBusy() }
        click("STOP", "sidebar")
        until("paused") { !c.orchestrator.isBusy() && state() == TaskState.PAUSED.wire }
        waitShown("Reprendre", "sidebar")
        waitShown("En pause", "task-panel")
        click("Reprendre", "sidebar")
        until("done") { !c.orchestrator.isBusy() && state() == TaskState.COMPLETED.wire }
        waitShown("Terminée", "task-panel")
    }

    @Config(qualifiers = "w1086dp-h1448dp-mdpi")
    @Test fun portraitRailStopsAndResumesAndEscapeStops() {
        val s = session()
        slowScript()
        show(s)
        send("Rassemble mes idées puis rédige un résumé, étape par étape.")
        waitShown("STOP", "activity-rail")
        until("first step running") { state() == TaskState.RUNNING.wire && c.orchestrator.isBusy() }
        click("STOP", "activity-rail")
        until("paused") { !c.orchestrator.isBusy() && state() == TaskState.PAUSED.wire }
        waitShown("Reprendre", "activity-rail")
        waitShown("Tâche en pause")
        shot("paused_portrait")
        val id = task().id
        click("Reprendre", "activity-rail")
        until("resumed") { c.orchestrator.isBusy() }
        until("first step running again") { state() == TaskState.RUNNING.wire }
        // Échap in the composer: STOP.
        rule.onNodeWithContentDescription("Message pour Cortana").performKeyInput { pressKey(Key.Escape) }
        until("paused by Échap") { !c.orchestrator.isBusy() && state() == TaskState.PAUSED.wire }
        assertEquals(id, task().id)
    }

    @Config(qualifiers = "w1448dp-h1086dp-land-mdpi")
    @Test fun emergencyStopHaltsAndReprendreAsksTheOwnersCredential() {
        val s = session()
        slowScript()
        show(s)
        send("Rassemble mes idées puis rédige un résumé, étape par étape.")
        until("running") { c.orchestrator.isBusy() && state() == TaskState.RUNNING.wire }
        rule.onNode(hasText("STOP") and hasAnyAncestor(hasTestTag("sidebar")), useUnmergedTree = true).performTouchInput { longClick() }
        until("halted") { c.killSwitch.isHalted() && !c.orchestrator.isBusy() }
        assertEquals(TaskState.HALTED.wire, task().state)
        waitShown("Reprendre", "sidebar")
        click("Reprendre", "sidebar")
        rule.waitForIdle()
        assertEquals("resume goes through the owner's credential", 1, resumeAsked.get())
        assertTrue("still halted until the credential is given", c.killSwitch.isHalted())
        c.killSwitch.resume("test")
    }

    @Config(qualifiers = "w1448dp-h1086dp-land-mdpi")
    @Test fun developerModeHidesDevelopmentWorkerAndMcp() {
        val s = session()
        show(s)
        waitShown("Discussion", "sidebar")
        assertTrue(!shown("Développement", "sidebar") && !shown("Worker", "sidebar") && !shown("MCP", "sidebar"))
        rule.onNode(hasContentDescription("Mode développeur")).performClick()
        until("developer mode on") { c.settings.current.chat.developer }
        waitShown("Développement", "sidebar")
        waitShown("Worker", "sidebar")
        waitShown("MCP", "sidebar")
    }
}
