package io.github.artisanguillonrenov.cortana.design

import io.github.artisanguillonrenov.cortana.CortanaTestBase
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Design step 4: STOP pauses a planned task (resumable), "Reprendre" continues it from its plan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskPauseTest : CortanaTestBase() {
    private fun until(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timeout: $what", cond())
    }

    private fun scripted(slowSecond: Boolean): AtomicInteger {
        val n = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (n.incrementAndGet()) {
                1 -> text(planJson("""[
                    {"id":"s1","title":"Rassembler","objective":"Rassembler les idées"},
                    {"id":"s2","title":"Rédiger","objective":"Rédiger le résumé","depends_on":["s1"]}]"""))
                2 -> if (slowSecond) text("Idées rassemblées.").setBodyDelay(5, TimeUnit.SECONDS) else text("Idées rassemblées.")
                else -> text("Fait.")
            }
        }
        return n
    }

    @Test fun stopPausesAPlannedTaskAndResumeContinuesFromItsPlan() {
        runBlocking { c.settings.update { it.copy(planningMode = "always") } }
        val s = session()
        val calls = scripted(slowSecond = true)
        assertTrue(c.orchestrator.submit(s.id, "Rassemble mes idées puis rédige un résumé, étape par étape."))
        until("first step running") { calls.get() >= 2 }
        assertTrue(c.orchestrator.pause())
        waitIdle()
        val paused = lastTask()
        assertEquals(TaskState.PAUSED.wire, paused.state)
        val states = events(paused.id).map { it.toState }
        assertTrue(states.toString(), states.takeLast(1) == listOf("paused"))
        assertTrue(messages(s).any { it.role == Roles.SYSTEM && it.text.contains("suspendue") })
        val planBefore = runBlocking { c.planStore.active(paused.id)!! }
        assertTrue("steps remain", planBefore.steps.any { it.status != StepStatus.SUCCEEDED })

        // Reprendre: the same task continues (no new task), and finishes.
        assertTrue(c.orchestrator.resumePaused(paused.id))
        until("resumed task done") { !c.orchestrator.isBusy() && runBlocking { c.db.tasks().get(paused.id)!!.state } != TaskState.PAUSED.wire }
        waitIdle()
        val done = runBlocking { c.db.tasks().get(paused.id)!! }
        assertEquals(TaskState.COMPLETED.wire, done.state)
        assertEquals("no second task", paused.id, lastTask().id)
        val all = events(done.id).map { it.toState }
        assertTrue(all.toString(), all.indexOf("paused") in 0 until all.lastIndexOf("running"))
    }

    @Test fun resumeIsRefusedWhileTheKillSwitchHoldsAndPauseWithoutTaskDoesNothing() {
        assertFalse(c.orchestrator.pause())
        runBlocking { c.settings.update { it.copy(planningMode = "always") } }
        val s = session()
        val calls = scripted(slowSecond = true)
        c.orchestrator.submit(s.id, "Rassemble mes idées puis rédige un résumé, étape par étape.")
        until("first step running") { calls.get() >= 2 }
        c.orchestrator.pause(); waitIdle()
        val paused = lastTask()
        c.killSwitch.halt("test")
        assertFalse("autonomy halted: no resume", c.orchestrator.resumePaused(paused.id))
        c.killSwitch.resume("test")
    }
}
