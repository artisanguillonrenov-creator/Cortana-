package io.github.artisanguillonrenov.cortana.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.artisanguillonrenov.cortana.CortanaTestBase
import io.github.artisanguillonrenov.cortana.contracts.TaskState
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleAction
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleKinds
import io.github.artisanguillonrenov.cortana.core.scheduler.ScheduleSpec
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.cortana.HistoryRoute
import io.github.artisanguillonrenov.cortana.ui.cortana.MemoryRoute
import io.github.artisanguillonrenov.cortana.ui.cortana.SettingsDesignRoute
import io.github.artisanguillonrenov.cortana.ui.cortana.TasksRoute
import io.github.artisanguillonrenov.cortana.ui.cortana.fold
import io.github.artisanguillonrenov.cortana.ui.cortana.stopTask
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Design step 6: History, Tasks, Memory and Settings on real data (services, not a second backend). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1448dp-h1086dp-land")
class DesignScreensRoutesTest : CortanaTestBase() {
    @get:Rule val rule = createComposeRule()

    @Before fun calm() = runBlocking { c.settings.update { it.copy(chat = it.chat.copy(reduceMotion = true)) }; Unit }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { CompositionLocalProvider(LocalContainer provides c) { CortanaTheme(reduceMotion = true) { content() } } }
        rule.waitForIdle()
    }

    private fun until(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS); Thread.sleep(20) }
        assertTrue("timeout: $what", cond())
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun historyFiltersSearchesWithoutAccentsAndOpens() {
        val a = session(); val b = session()
        runBlocking {
            c.conversations.updateSession(c.conversations.session(a.id)!!.copy(title = "Réunion équipe produit", pinned = true))
            c.conversations.updateSession(c.conversations.session(b.id)!!.copy(title = "Dîner du samedi"))
            c.conversations.addMessage(a.id, Roles.USER, "Résume la réunion")
            c.conversations.addMessage(b.id, Roles.USER, "Menu sans gluten")
        }
        var opened: String? = null
        show { HistoryRoute { id, _ -> opened = id } }
        until("rows") { shown("Réunion équipe produit") && shown("Dîner du samedi") }
        rule.onNode(hasText("Épinglées", substring = true), useUnmergedTree = true).performClick()
        until("pinned only") { shown("Réunion équipe produit") && !shown("Dîner du samedi") }
        rule.onNode(hasText("Tout", substring = false), useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("Rechercher titres, messages, fichiers…").performTextInput("reunion")
        until("accent-insensitive search") { shown("Réunion équipe produit") && !shown("Dîner du samedi") }
        rule.onNode(hasContentDescription("Ouvrir")).performClick()
        until("opened") { opened == a.id }
        assertEquals("reunion equipe", fold("Réunion Équipe"))
    }

    @Test fun tasksShowSchedulesQueueAndRecentTasksAndToggleASchedule() {
        val s = session()
        val sched = runBlocking {
            c.scheduler.create("Sauvegarde du NAS", ScheduleKinds.INTERVAL, ScheduleSpec(everyMinutes = 60, startAt = System.currentTimeMillis() + 3_600_000),
                ScheduleAction("task", objective = "Sauvegarde le NAS"))
        }
        server.enqueue(text("Voilà."))
        runAndWait(s, "Bonjour Cortana")
        // A message left in the queue that needs the owner again (never sent on its own).
        runBlocking {
            c.db.chat().enqueue(io.github.artisanguillonrenov.cortana.core.memory.ChatQueueEntity("q1", s.id, "Générer les icônes de l'application", "[]", 1, System.currentTimeMillis(), status = "confirm"))
        }
        show { TasksRoute { } }
        until("sections") { shown("Sauvegarde du NAS") && shown("Générer les icônes") && shown("Réussie") }
        rule.onNode(hasContentDescription("Activer « Sauvegarde du NAS »")).performClick()
        until("schedule disabled") { runBlocking { c.scheduler.get(sched.id)!!.enabled } == false }
        rule.onNode(hasContentDescription("Retirer de la file")).performClick()
        until("queue emptied") { runBlocking { c.chat.observeAllQueued().first() }.isEmpty() }
    }

    @Test fun memorySuggestionIsKeptTemporaryChatAndForget() {
        val s = session()
        val (pending, active) = runBlocking {
            c.memory.save("Préfère Kotlin et Compose", "preference", MemoryStatus.PENDING, "t").memory to c.memory.save("Aime le thé", "preference", MemoryStatus.ACTIVE, "t").memory
        }
        show { MemoryRoute() }
        until("suggestion and memory") { shown("Cortana propose de retenir") && shown("Aime le thé") }
        rule.onNode(hasContentDescription("Retenir")).performClick()
        until("confirmed") { runBlocking { c.memory.get(pending.id)!!.status } == MemoryStatus.ACTIVE }
        rule.onNode(hasText("Chat temporaire")).performClick()
        until("incognito") { runBlocking { c.conversations.session(s.id)!!.incognito } }
        until("banner") { shown("Chat temporaire activé") }
        rule.onAllNodes(hasContentDescription("Oublier", substring = true)).fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        runBlocking { c.memory.forget(active.id) }
        until("forgotten") { !shown("Aime le thé") }
    }

    @Test fun settingsRowsWriteTheRealSettings() {
        show { SettingsDesignRoute { } }
        until("general") { shown("Densité") && shown("Envoyer avec Entrée") }
        rule.onNode(hasContentDescription("Envoyer avec Entrée")).performClick()
        until("enter to send off") { !c.settings.current.chat.enterToSend }
        rule.onNode(hasText("Streaming")).performClick()
        until("streaming section") { shown("Bouton STOP") }
        rule.onNode(hasContentDescription("Annuler la tâche")).performClick()
        until("stop cancels") { c.settings.current.chat.stopAction == "cancel" }
        rule.onNode(hasText("Développeur")).performClick()
        rule.onNode(hasContentDescription("Mode développeur")).performClick()
        until("developer mode") { c.settings.current.chat.developer }
    }

    @Test fun stopFollowsTheOwnersChoicePauseOrCancel() {
        runBlocking { c.settings.update { it.copy(planningMode = "always", chat = it.chat.copy(stopAction = "cancel")) } }
        val s = session()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = request.body.readUtf8()
                return if ("depends_on" in b) text(planJson("""[{"id":"s1","title":"Un","objective":"Premier"},{"id":"s2","title":"Deux","objective":"Second","depends_on":["s1"]}]"""))
                else text("Fait.").setBodyDelay(3, TimeUnit.SECONDS)
            }
        }
        assertTrue(c.orchestrator.submit(s.id, "Fais deux choses, étape par étape."))
        until("running") { runBlocking { c.tasksFlow.first().firstOrNull()?.state } == TaskState.RUNNING.wire }
        assertTrue(stopTask(c))
        waitIdle()
        assertEquals(TaskState.CANCELLED.wire, lastTask().state)
    }
}
