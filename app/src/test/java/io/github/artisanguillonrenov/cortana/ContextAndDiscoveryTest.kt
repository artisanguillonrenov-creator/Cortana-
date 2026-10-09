package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.ExpectedOutcome
import io.github.artisanguillonrenov.cortana.contracts.Plan
import io.github.artisanguillonrenov.cortana.contracts.PlanStep
import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.contracts.StepStatus
import io.github.artisanguillonrenov.cortana.contracts.TaskNotebook
import io.github.artisanguillonrenov.cortana.core.context.ContextEngine
import io.github.artisanguillonrenov.cortana.core.context.ContextRequest
import io.github.artisanguillonrenov.cortana.core.context.Tokens
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.model.ChatMessage
import io.github.artisanguillonrenov.cortana.core.model.ToolCall
import io.github.artisanguillonrenov.cortana.core.tools.CapabilityMatcher
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** VNext phase 6 gates: Context Engine v2 (budget, compaction, provenance) and dynamic tool discovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContextAndDiscoveryTest : CortanaTestBase() {

    private val engine get() = c.contextEngine
    private val window = 8192

    /** 150 exchanges; every 10th one is a tool round trip with a large output. */
    private fun longSession(): SessionEntity = runBlocking {
        val s = session()
        for (i in 1..150) {
            c.conversations.addMessage(s.id, Roles.USER, "Question numéro $i : parle-moi du sujet $i avec quelques détails supplémentaires.")
            if (i % 10 == 0) {
                val calls = listOf(ToolCall("c$i", "web_fetch", """{"url":"https://exemple.fr/$i"}"""))
                c.conversations.addMessage(s.id, Roles.ASSISTANT, "", toolCallsJson = AppJson.encodeToString(ListSerializer(ToolCall.serializer()), calls), hidden = true)
                c.conversations.addMessage(s.id, Roles.TOOL, "contenu de page $i ".repeat(400), toolCallsJson = """{"toolCallId":"c$i","name":"web_fetch"}""")
            }
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Réponse $i : voici une explication raisonnablement longue sur le sujet $i, avec plusieurs phrases pour occuper de la place.")
        }
        s
    }

    private fun tools(s: SessionEntity) = engine.fitTools(c.registry.forToolset(s.toolset).map { it.spec() }, window)

    private fun build(s: SessionEntity, objective: String = "Et maintenant le sujet 151 ?", mode: String? = null) = runBlocking {
        mode?.let { m -> c.settings.update { it.copy(contextSummaryMode = m) } }
        engine.build(ContextRequest(session = s, objective = objective, tools = tools(s), contextWindow = window, currentTaskId = null))
    }

    private fun assertPairsIntact(msgs: List<ChatMessage>) {
        msgs.forEachIndexed { i, m ->
            m.toolCalls?.forEach { call -> assertTrue("result for ${call.id}", msgs.drop(i + 1).takeWhile { it.role == "tool" }.any { it.toolCallId == call.id }) }
            if (m.role == "tool") assertTrue("orphan tool result", msgs.take(i).any { p -> p.toolCalls?.any { it.id == m.toolCallId } == true })
        }
    }

    @Test fun longSessionStaysCoherentUnderBudget() {
        val s = longSession()
        val built = build(s)
        val r = built.report
        assertTrue("used ${r.used} > window: $r", r.used <= window - c.settings.current.maxOutputTokens)
        assertEquals(r.used, built.messages.sumOf { Tokens.estimate(it) } + tools(s).sumOf { Tokens.estimate(it) })
        assertTrue(r.droppedMessages > 0)
        val sys = built.messages.first().content!!
        assertTrue(sys.contains("Objectif actuel : Et maintenant le sujet 151 ?"))
        assertTrue(sys.contains("Échanges plus anciens (résumé"))
        assertTrue("oldest turns summarized, not lost", sys.contains("Question numéro 1") || sys.contains("début de conversation omis"))
        assertEquals("user", built.messages[1].role)
        assertTrue(built.messages.last().content!!.startsWith("Réponse 150"))
        assertPairsIntact(built.messages.drop(1))
        // Persisted, incremental compaction.
        val first = runBlocking { c.conversations.summary(s.id)!! }
        assertEquals(ContextEngine.SUMMARY_EXTRACTIVE, first.method)
        assertEquals(first.updatedAt, runBlocking { build(s); c.conversations.summary(s.id)!! }.updatedAt) // nothing new dropped → reused
        runBlocking { repeat(6) { i -> c.conversations.addMessage(s.id, if (i % 2 == 0) Roles.USER else Roles.ASSISTANT, "Suite ${151 + i} " + "x".repeat(300)) } }
        build(s)
        val second = runBlocking { c.conversations.summary(s.id)!! }
        assertTrue(second.coveredCount > first.coveredCount)
    }

    @Test fun currentTaskObservationsSurviveAHugeHistory() = runBlocking {
        val s = longSession()
        c.conversations.addMessage(s.id, Roles.USER, "Analyse ces quatre pages.")
        for (i in 1..4) {
            val calls = listOf(ToolCall("t$i", "web_fetch", """{"url":"https://exemple.fr/p$i"}"""))
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "", taskId = "task-now", toolCallsJson = AppJson.encodeToString(ListSerializer(ToolCall.serializer()), calls), hidden = true)
            c.conversations.addMessage(s.id, Roles.TOOL, "page $i ".repeat(900), taskId = "task-now", toolCallsJson = """{"toolCallId":"t$i","name":"web_fetch"}""")
        }
        val built = engine.build(ContextRequest(session = s, objective = "Analyse ces quatre pages.", tools = emptyList(), contextWindow = window, currentTaskId = "task-now"))
        val msgs = built.messages
        assertTrue("the task's own user turn is in the window: ${built.report}", msgs.any { it.role == "user" && it.content == "Analyse ces quatre pages." })
        assertEquals(4, msgs.count { it.role == "tool" && it.toolCallId?.startsWith("t") == true })
        assertTrue("${built.report}", built.report.used <= window - c.settings.current.maxOutputTokens)
        assertPairsIntact(msgs.drop(1))
    }

    @Test fun provenancePlanAndNotebookAreInTheContext() = runBlocking {
        val s = session()
        c.memory.save("Préfère les réponses courtes", "preference", MemoryStatus.ACTIVE, "explicit")
        val plan = Plan(
            planId = "p", taskId = "t", version = 2, objective = "o", strategy = PlanStrategy.DAG, createdAt = 1,
            steps = listOf(
                PlanStep("s1", 1, "Chercher", "x", expectedOutcome = ExpectedOutcome("ok"), status = StepStatus.SUCCEEDED, resultSummary = "3 sources trouvées"),
                PlanStep("s2", 2, "Rédiger", "y", expectedOutcome = ExpectedOutcome("ok"), status = StepStatus.RUNNING),
            ),
        )
        val nb = TaskNotebook(taskId = "t", objective = "o", openItems = listOf("valider le titre"), nextRecommendedAction = "rédiger l'intro")
        val sys = engine.build(ContextRequest(s, "Rédige la synthèse", emptyList(), window, plan = plan, notebook = nb, taintSources = listOf("web.fetch:exemple.fr"))).messages.first().content!!
        assertTrue(sys.contains("## Provenance") && sys.contains("web.fetch:exemple.fr"))
        assertTrue(sys.contains("(preference · demandé par le propriétaire) Préfère les réponses courtes"))
        assertTrue(sys.contains("[x] s1 Chercher → 3 sources trouvées"))
        assertTrue(sys.contains("[>] s2 Rédiger"))
        assertTrue(sys.contains("En suspens : valider le titre"))
        assertTrue(sys.contains("Prochaine action conseillée : rédiger l'intro"))
    }

    @Test fun incognitoContextCarriesNoMemory() = runBlocking {
        c.memory.save("Habite à Lyon", "profile", MemoryStatus.ACTIVE, "explicit")
        val s = c.conversations.createSession(incognito = true)
        val sys = engine.build(ContextRequest(s, "Où j'habite ?", emptyList(), window)).messages.first().content!!
        assertFalse(sys.contains("Lyon"))
    }

    @Test fun theAdultProfileIsInThePromptAndItsReminderClosesTheSystemMessage() = runBlocking {
        val s = c.conversations.createSession()
        val sys = engine.build(ContextRequest(s, "Bonjour", emptyList(), window)).messages.first().content!!
        assertTrue(sys.contains("Profil de contenu : ADULTE"))
        assertTrue("legal limits stay", sys.contains("rien qui implique des mineurs"))
        assertTrue("no invented internal rules", sys.contains("n'invente jamais un fichier"))
        assertTrue("the reminder comes last", sys.trimEnd().endsWith(engine.adultReminder))
    }

    @Test fun modelSummaryIsUsedWhenChosenAndFallsBackToExtractive() {
        val s = longSession()
        runBlocking { c.settings.update { it.copy(contextSummaryMode = ContextEngine.SUMMARY_MODEL) } }
        val tools = tools(s)
        var calls = 0
        val built = runBlocking {
            engine.build(ContextRequest(s, "Suite", tools, window)) { prev, dropped -> calls++; assertTrue(dropped.contains("Propriétaire")); "RÉSUMÉ-MODÈLE ${prev == null}" }
        }
        assertEquals(1, calls)
        assertTrue(built.messages.first().content!!.contains("RÉSUMÉ-MODÈLE true"))
        assertEquals(ContextEngine.SUMMARY_MODEL, runBlocking { c.conversations.summary(s.id)!!.method })
        runBlocking { c.conversations.deleteSession(s.id) }
        val s2 = longSession()
        val fallback = runBlocking { engine.build(ContextRequest(s2, "Suite", tools, window)) { _, _ -> null } }
        assertEquals(ContextEngine.SUMMARY_EXTRACTIVE, fallback.report.summaryMethod)
    }

    // ---------------------------------------------------------------- dynamic tool discovery

    private fun toolNames(body: String) = AppJson.parseToJsonElement(body).jsonObject["tools"]?.jsonArray?.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content } ?: emptyList()

    @Test fun rankingFindsTheRightToolForFrenchRequests() {
        val pool = c.registry.forToolset(Toolsets.FULL)
        fun top(q: String, n: Int = 3) = c.toolDiscovery.search(q, pool, limit = n).map { it.capability }
        assertEquals(top("règle la luminosité à 50 %").toString(), "android.system.brightness.set", top("règle la luminosité à 50 %").first())
        assertTrue(top("ouvre l'application YouTube").toString(), top("ouvre l'application YouTube").contains("android.app.open"))
        assertTrue(top("cherche sur internet la météo").toString(), top("cherche sur internet la météo").contains("web.search"))
        assertTrue(top("liste les fichiers du dossier").toString(), top("liste les fichiers du dossier").contains("file.list"))
        assertTrue(c.toolDiscovery.search("zzzz qqqq", pool).isEmpty())
    }

    @Test fun matcherOffersCoreRequiredAndCategoryToolsButNeverEverything() {
        val pool = c.registry.forToolset(Toolsets.FULL)
        val m = CapabilityMatcher(c.toolDiscovery)
        val chat = m.select(pool, "Salut", setOf(ToolCategory.SERVICE), emptyList(), emptySet(), PlanStrategy.INTERACTIVE, 24)
        assertEquals(CapabilityMatcher.CORE.toSet(), chat.offered.map { it.capability }.toSet())
        val ui = m.select(pool, "ouvre YouTube et lance une vidéo", setOf(ToolCategory.UI, ToolCategory.SYSTEM), emptyList(), emptySet(), PlanStrategy.INTERACTIVE, 24)
        val caps = ui.offered.map { it.capability }
        assertTrue(caps.containsAll(listOf("android.ui.observe", "android.ui.click", "android.app.open")))
        assertTrue(caps.size <= 24 && caps.size < pool.size)
        val dag = m.select(pool, "x", setOf(ToolCategory.WEB), listOf("web.fetch"), setOf("file.read"), PlanStrategy.DAG, 24)
        assertTrue(dag.strict)
        assertEquals(CapabilityMatcher.CORE.toSet() + setOf("web.fetch", "file.read"), dag.offered.map { it.capability }.toSet())
    }

    @Test fun plainChatSendsASmallToolSubset() {
        val s = session()
        server.enqueue(text("Bonjour !"))
        runAndWait(s, "Salut")
        val names = toolNames(server.takeRequest().body.readUtf8())
        assertTrue("offered: $names", names.containsAll(listOf("tools_discover", "ask_user")))
        assertTrue("offered: $names", names.size < c.registry.forToolset(Toolsets.FULL).size / 2)
    }

    @Test fun discoveredToolsAreOfferedOnTheNextCallAndSurviveInTheNotebook() {
        val s = session()
        server.enqueue(toolCall("tools_discover", """{"query":"régler la luminosité"}"""))
        server.enqueue(toolCall("android_system_brightness_set", """{"percent":40}""", id = "call_2"))
        server.enqueue(text("C'est fait."))
        runAndWait(s, "Il fait trop clair ici")
        val first = toolNames(server.takeRequest().body.readUtf8())
        assertFalse("first: $first", first.contains("android_system_brightness_set"))
        val second = server.takeRequest().body.readUtf8()
        assertTrue("second: ${toolNames(second)}", toolNames(second).contains("android_system_brightness_set"))
        assertTrue(second.contains("Outils disponibles à partir de maintenant"))
        val t = lastTask()
        val calls = runBlocking { c.db.tasks().toolCalls(t.id) }.map { it.capability }
        assertEquals(listOf("tools.discover", "android.system.brightness.set"), calls)
        val nb = runBlocking { c.checkpoints.notebookOrNull(t.id) }
        assertNotNull(nb)
        assertTrue(nb!!.activeCapabilities.contains("android.system.brightness.set"))
    }

    @Test fun discoveryNeverEscapesTheSessionToolset() {
        val s = session(toolset = Toolsets.ASSISTANT)
        server.enqueue(toolCall("tools_discover", """{"query":"cliquer sur un bouton de l'écran"}"""))
        server.enqueue(toolCall("android_ui_click", """{"text":"OK"}""", id = "call_2"))
        server.enqueue(text("Je ne peux pas piloter l'écran dans cette session."))
        runAndWait(s, "Clique sur OK")
        server.takeRequest()
        val afterDiscover = server.takeRequest().body.readUtf8()
        assertFalse(toolNames(afterDiscover).any { it.startsWith("android_ui_") })
        val third = server.takeRequest().body.readUtf8()
        assertTrue(third.contains("Outil inconnu ou indisponible"))
        assertTrue(runBlocking { c.db.tasks().toolCalls(lastTask().id) }.none { it.capability.startsWith("android.ui.") })
    }

    @Test fun toolsetToolThatWasNotOfferedIsImplicitlyDiscoveredAndStillPolicyChecked() {
        val s = session()
        server.enqueue(toolCall("schedule_list", "{}"))
        server.enqueue(text("Aucune planification."))
        runAndWait(s, "Salut")
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("schedule.list", call.capability)
        assertEquals("ok", call.outcome)
        assertTrue(call.policyDecisionJson.contains("\"requirement\""))
    }
}
