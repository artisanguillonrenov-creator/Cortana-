package io.github.artisanguillonrenov.cortana

import android.content.Intent
import android.net.Uri
import io.github.artisanguillonrenov.cortana.core.chat.ChatMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatService
import io.github.artisanguillonrenov.cortana.core.chat.ChatSessionSettings
import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.EXPORT_FORMAT
import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.chat.TextDiff
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.council.CouncilConfig
import io.github.artisanguillonrenov.cortana.core.council.CouncilMode
import io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs
import io.github.artisanguillonrenov.cortana.core.council.CouncilSelectionInput
import io.github.artisanguillonrenov.cortana.core.council.RuleCouncilPolicySelector
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.core.memory.ProjectEntity
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskStates
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import io.github.artisanguillonrenov.cortana.ui.workspace.ShareInbox
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.TimeUnit

/** H7–H10: context (pins, memory, project, compaction), comparison and merge, council bridge, artifacts, export/import/search, sharing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatWorkspaceFeaturesTest : CortanaTestBase() {
    private val chat get() = c.chat
    private fun sent(r: ChatService.SendResult) = assertEquals(ChatService.SendResult.Sent, r)
    private fun timeline(s: SessionEntity) = runBlocking { val p = c.conversations.messages(s.id); ChatTimeline.build(p, c.conversations.branchMap(s.id, p)) }
    private fun system(body: String) = AppJson.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
    private fun until(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue("timeout: $what", cond())
    }

    // ---------------------------------------------------------------- H7 context

    @Test fun h7PinsNotesProjectInstructionsAndMemorySwitchShapeTheRealRequest() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.memory.save("Le propriétaire préfère le thé vert", "preference", MemoryStatus.ACTIVE, "explicit")
            val q = c.conversations.addMessage(s.id, Roles.USER, "Le code du portail est 1789")
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Noté.")
            c.conversations.pin(s.id, "message", q.id, "code du portail")
            c.conversations.pin(s.id, "note", "n1", "Budget", "Budget maximal : 300 €")
            val p = ProjectEntity("proj1", "Maison", "Réponds toujours avec des listes à puces.", null, 1, 1)
            c.conversations.saveProject(p)
            c.conversations.updateSession(c.conversations.session(s.id)!!.copy(projectId = "proj1"))
        }
        server.enqueue(text("D'accord."))
        runBlocking { sent(chat.send(s.id, "Que boire ce soir avec le thé ?")) }
        waitIdle()
        val sys = system(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertTrue("pinned message", sys.contains("Éléments épinglés") && sys.contains("1789"))
        assertTrue("pinned note", sys.contains("Budget maximal : 300 €"))
        assertTrue("project instructions", sys.contains("Instructions du projet « Maison »") && sys.contains("listes à puces"))
        assertTrue("memory used", sys.contains("thé vert"))
        val snap = c.contextEngine.snapshots.value[s.id]
        assertNotNull(snap); assertEquals(2, snap!!.pins); assertEquals("Maison", snap.project); assertTrue(snap.memoryIds.isNotEmpty())

        // switched off here: no memory, no project instructions
        runBlocking {
            val cur = c.conversations.session(s.id)!!
            c.conversations.updateSession(cur.copy(settingsJson = AppJson.encodeToString(ChatSessionSettings.serializer(), ChatSessionSettings(memoryOff = true, inheritProject = false))))
        }
        server.enqueue(text("Très bien."))
        runBlocking { sent(chat.send(s.id, "Et avec le thé demain ?")) }
        waitIdle()
        val sys2 = system(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertFalse(sys2.contains("thé vert")); assertFalse(sys2.contains("Instructions du projet"))
    }

    @Test fun h7CompactNowSummarisesOlderTurnsKeepsTheLastOnesAndRecordsACheckpoint() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            (1..6).forEach { i ->
                c.conversations.addMessage(s.id, Roles.USER, "Question numéro $i sur le jardin")
                c.conversations.addMessage(s.id, Roles.ASSISTANT, "Réponse numéro $i : arroser le soir.")
            }
            assertTrue(c.contextEngine.compactNow(c.conversations.session(s.id)!!) > 0)
            assertEquals(1, c.db.chat().checkpoints(s.id).size)
        }
        server.enqueue(text("Voilà."))
        runBlocking { sent(chat.send(s.id, "Et la suite ?")) }
        waitIdle()
        val body = AppJson.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject["messages"]!!.jsonArray
        val verbatim = body.drop(1).joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty() }
        assertFalse("older turns only as a summary", verbatim.contains("Question numéro 1 sur"))
        assertTrue("last turns verbatim", verbatim.contains("Question numéro 6 sur le jardin"))
        assertTrue("summary in the system message", system(body.toString().let { """{"messages":$it}""" }).contains("résumé"))
        // the conversation itself is untouched
        assertEquals("12 earlier messages + question + answer, none deleted", 14, runBlocking { c.conversations.allMessages(s.id) }.count { it.role != Roles.SYSTEM && !it.hidden })
    }

    // ---------------------------------------------------------------- H9 comparison

    /** Answers by model name; "lent" streams slowly, "panne" fails. */
    private fun modelServer(texts: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val model = runCatching { AppJson.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content }.getOrDefault("")
                bodies += model to body
                if (model.contains("panne")) return MockResponse().setResponseCode(500).setBody("""{"error":{"message":"indisponible"}}""")
                val t = texts[model] ?: "Réponse de $model."
                val chunks = t.split(" ").joinToString("") { w -> "data: " + """{"choices":[{"delta":{"content":${q("$w ")}}}]}""" + "\n\n" } + "data: [DONE]\n\n"
                val r = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(chunks)
                return if (model.contains("lent")) r.throttleBody(40, 300, TimeUnit.MILLISECONDS) else r
            }
        }
    }
    private val bodies: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private fun compareSession(vararg models: String): SessionEntity {
        val s = session(model = models.first(), toolset = Toolsets.FULL)
        runBlocking { chat.setCompareRoutes(s.id, models.map { "${s.providerId}/$it" }) }
        return runBlocking { c.conversations.session(s.id)!! }
    }

    @Test fun h9TwoModelsAnswerSideBySideWithoutToolsAndOneFailureDoesNotSinkTheOthers() {
        modelServer(mapOf("modele-a" to "Paris est la capitale.", "modele-b" to "La capitale est Paris."))
        val s = compareSession("modele-a", "modele-b", "modele-panne")
        assertEquals(ChatMode.COMPARE.wire, s.mode)
        runBlocking { sent(chat.send(s.id, "Quelle est la capitale de la France ?", mode = ChatMode.COMPARE)) }
        waitIdle()
        assertEquals(TaskStates.COMPLETED, lastTask().state)
        assertEquals(3, bodies.size)
        bodies.forEach { (_, b) -> assertNull("a comparison offers no tool", AppJson.parseToJsonElement(b).jsonObject["tools"]) }
        val user = timeline(s).filterIsInstance<TimelineItem.User>().single().message
        val lanes = runBlocking { c.conversations.childrenOf(listOf(user.id)) }.map { runBlocking { c.conversations.message(it.id)!! } }
        assertEquals(3, lanes.size)
        assertEquals(listOf(1, 2, 3), lanes.map { ChatTimeline.meta(it).lane })
        assertEquals(1, lanes.map { ChatTimeline.meta(it).compareGroup }.distinct().size)
        assertEquals(listOf(MessageStatus.COMPLETE, MessageStatus.COMPLETE, MessageStatus.ERROR), lanes.map { it.status })
        assertEquals("Paris est la capitale.", lanes[0].text.trim())
        val cmp = timeline(s).filterIsInstance<TimelineItem.Compare>().single()
        assertEquals(3, cmp.lanes.size); assertEquals(lanes[0].id, cmp.selected)

        // Merge: a visible request after the followed answer; answers travel as data, majority never a proof.
        bodies.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { bodies += "merge" to request.body.readUtf8(); return text("Synthèse : Paris.") }
        }
        runBlocking { sent(chat.mergeCompared(s.id, lanes.take(2).map { it.id }, analyse = false)) }
        waitIdle()
        val merge = bodies.single().second
        assertTrue(merge.contains("Paris est la capitale.") && merge.contains("La capitale est Paris.") && merge.contains("majorité"))
        val items = timeline(s)
        assertTrue(items[1] is TimelineItem.Compare)
        assertEquals("Fusionne les 2 réponses", (items[2] as TimelineItem.User).message.text)
        assertEquals("Synthèse : Paris.", (items[3] as TimelineItem.Assistant).text)
        assertEquals("merge", (items[3] as TimelineItem.Assistant).meta.kind)
    }

    @Test fun h9FourModelsAndOneLaneStoppedAloneKeepsItsText() {
        modelServer(mapOf("lent" to (1..40).joinToString(" ") { "mot$it" }))
        val s = compareSession("m1", "m2", "m3", "lent")
        runBlocking { sent(chat.send(s.id, "Décris la mer", mode = ChatMode.COMPARE)) }
        until("slow lane streaming") { c.chatHub.live.value.values.any { it.lane == 4 && it.text.contains("mot2") } }
        val run = c.chatHub.runsBySession.value[s.id]!!.runId
        assertTrue(chat.stopLane(run, 4))
        waitIdle()
        val user = timeline(s).filterIsInstance<TimelineItem.User>().single().message
        val lanes = runBlocking { c.conversations.childrenOf(listOf(user.id)).map { c.conversations.message(it.id)!! } }.sortedBy { ChatTimeline.meta(it).lane }
        assertEquals(4, lanes.size)
        assertEquals(List(3) { MessageStatus.COMPLETE } + MessageStatus.STOPPED, lanes.map { it.status })
        assertTrue(lanes[3].text.startsWith("mot1")); assertFalse(lanes[3].text.contains("mot40"))
        assertEquals(TaskStates.COMPLETED, lastTask().state)
    }

    @Test fun h9CouncilBridgeHonoursAnExplicitRequestButNeverTheDisabledSwitch() {
        val sel = RuleCouncilPolicySelector()
        val input = CouncilSelectionInput("Bonjour", coding = false, multiStep = false, toolsAvailable = true, fastPath = false, source = "chat")
        assertEquals(CouncilMode.OFF, sel.select(input, CouncilConfig(enabled = true), CouncilPrefs()).mode)
        assertEquals(CouncilMode.COUNCIL_4, sel.select(input.copy(explicitRequest = true), CouncilConfig(enabled = true), CouncilPrefs()).mode)
        assertEquals(CouncilMode.OFF, sel.select(input.copy(explicitRequest = true), CouncilConfig(enabled = false), CouncilPrefs()).mode)
        assertEquals(CouncilMode.OFF, sel.select(input.copy(explicitRequest = true, insideCouncil = true), CouncilConfig(enabled = true), CouncilPrefs()).mode)

        // In the conversation, with the council off: a discreet event, then the usual answer.
        val s = session(toolset = Toolsets.CONVERSATION)
        server.enqueue(text("Réponse habituelle."))
        runBlocking { sent(chat.send(s.id, "Analyse ce choix difficile", mode = ChatMode.COUNCIL)) }
        waitIdle()
        val items = timeline(s)
        assertTrue(items.any { it is TimelineItem.System && it.message.text.contains("conseil de réflexion est désactivé") })
        assertEquals("Réponse habituelle.", items.filterIsInstance<TimelineItem.Assistant>().last().text)
    }

    // ---------------------------------------------------------------- H8 artifacts

    @Test fun h8ArtifactsOfAConversationAreListedAndVersionsDiffByLine() {
        val s = session(toolset = Toolsets.CONVERSATION)
        val other = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            chat.attach("notes.txt", "text/plain", 5, s.id) { "salut".byteInputStream() }
            c.artifacts.registerText("ailleurs", "document", "autre.md", metadata = mapOf("sessionId" to other.id))
        }
        val list = runBlocking { chat.observeArtifacts(s.id).first() }
        assertEquals(listOf("notes.txt"), list.map { it.name })
        val d = TextDiff.unified("un\ndeux\ntrois\n", "un\nDEUX\ntrois\nquatre\n")
        assertEquals(2, d.added); assertEquals(1, d.removed)
        assertTrue(d.text.contains("- deux") && d.text.contains("+ DEUX") && d.text.contains("+ quatre"))
        assertEquals("Aucune différence.", TextDiff.unified("a", "a").text)
    }

    // ---------------------------------------------------------------- H10 export, import, search

    @Test fun h10ExportKeepsTheVisibleBranchOnlyAndImportRoundTrips() {
        val s = session(toolset = Toolsets.CONVERSATION)
        // Built at run time: a literal key in a tracked file would (rightly) trip SupplyChainTest.
        val fakeKey = "sk" + "-" + ('a'..'z').joinToString("") + "0123456789"
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Ma clé est $fakeKey ne la montre pas")
            c.conversations.addMessage(s.id, Roles.USER, "[Système] consigne interne", hidden = true)
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Je ne la montrerai pas.", metaJson = AppJson.encodeToString(MessageMeta.serializer(), MessageMeta(modelId = "m-x")))
            c.conversations.addMessage(s.id, Roles.TOOL, "résultat brut de l'outil", toolCallsJson = """{"capability":"web.search","ok":true}""")
        }
        val (name, md) = runBlocking { chat.export(s.id, "md") }
        assertTrue(name.endsWith(".md"))
        assertFalse("never a secret", md.contains(fakeKey))
        assertFalse("never hidden rows", md.contains("consigne interne"))
        assertFalse("never raw tool output", md.contains("résultat brut"))
        assertTrue(md.contains("web.search : réussie") && md.contains("**Cortana** (m-x)"))
        val (_, json) = runBlocking { chat.export(s.id, "json") }
        assertTrue(json.contains(EXPORT_FORMAT))
        val imported = runBlocking { chat.import(json) }
        val rows = runBlocking { c.conversations.messages(imported.id) }
        assertEquals(listOf(Roles.USER, Roles.ASSISTANT), rows.map { it.role })
        assertEquals("Je ne la montrerai pas.", rows[1].text)
        assertEquals(rows[0].id, rows[1].parentId)
        assertTrue(runCatching { runBlocking { chat.import("""{"format":"autre"}""") } }.isFailure)
    }

    @Test fun h10SearchFindsMessagesAndTitlesWithFiltersAndJumps() {
        val a = session(toolset = Toolsets.CONVERSATION)
        val b = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.conversations.updateSession(c.conversations.session(a.id)!!.copy(title = "Voyage en Bretagne", pinned = true))
            c.conversations.addMessage(a.id, Roles.USER, "Quels crêpes goûter à Quimper ?")
            c.conversations.addMessage(a.id, Roles.ASSISTANT, "Les crêpes au sarrasin.")
            c.conversations.addMessage(b.id, Roles.USER, "Recette de crêpes sucrées",
                metaJson = AppJson.encodeToString(MessageMeta.serializer(), MessageMeta(attachments = listOf(io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef("x", "r.txt", "text/plain", 3)))))
        }
        val all = runBlocking { chat.search("crêpes") }
        assertEquals(3, all.size)
        assertEquals(1, runBlocking { chat.search("crêpes", role = Roles.ASSISTANT) }.size)
        assertEquals(setOf(a.id), runBlocking { chat.search("crêpes", pinnedOnly = true) }.map { it.sessionId }.toSet())
        assertEquals(setOf(b.id), runBlocking { chat.search("crêpes", withFiles = true) }.map { it.sessionId }.toSet())
        assertEquals("titre", runBlocking { chat.search("Bretagne") }.single().role)
        assertTrue(runBlocking { chat.search("c") }.isEmpty())
    }

    // ---------------------------------------------------------------- H3 sharing

    @Test fun h5DeleteRemovesTheMessageAndWhatFollowsOnEveryBranchAndMovesTheLeafAway() {
        val s = session(toolset = Toolsets.CONVERSATION)
        val r = c.conversations
        val (a1, q2b, a2) = runBlocking {
            r.addMessage(s.id, Roles.USER, "Première question")
            val a1 = r.addMessage(s.id, Roles.ASSISTANT, "Première réponse")
            r.addMessage(s.id, Roles.USER, "Deuxième question")
            val a2 = r.addMessage(s.id, Roles.ASSISTANT, "Deuxième réponse")
            val q2b = r.addMessage(s.id, Roles.USER, "Deuxième question reformulée zanzibar", parentId = a1.id)
            r.addMessage(s.id, Roles.ASSISTANT, "Réponse à la reformulation")
            r.addMessage(s.id, Roles.USER, "Suite de la reformulation")
            r.pin(s.id, "message", q2b.id, "reformulée")
            Triple(a1, q2b, a2)
        }
        assertEquals(7, runBlocking { r.allMessages(s.id) }.size)
        assertEquals(3, runBlocking { r.deleteFrom(s.id, q2b.id) })
        val left = runBlocking { r.allMessages(s.id) }
        assertEquals(4, left.size)
        assertEquals("the leaf moved to the surviving branch", a2.id, runBlocking { r.session(s.id)!!.activeLeafId })
        assertEquals(listOf("Première question", "Première réponse", "Deuxième question", "Deuxième réponse"), runBlocking { r.messages(s.id) }.map { it.text })
        assertTrue("pins of deleted messages go too", runBlocking { r.pins(s.id) }.isEmpty())
        assertTrue("and the search index follows", runBlocking { chat.search("zanzibar") }.none { it.sessionId == s.id })
        assertEquals(1, timeline(s).count { it is TimelineItem.Assistant && it.last.id == a2.id })
        assertEquals("a message of another conversation is never touched", 0, runBlocking { r.deleteFrom(session(toolset = Toolsets.CONVERSATION).id, a1.id) })

        // Deleting a first message that has another version keeps that version, unlinked (never chained to others).
        val t = session(toolset = Toolsets.CONVERSATION)
        val (v1, v2, v3) = runBlocking {
            val v1 = r.addMessage(t.id, Roles.USER, "Version 1")
            r.addMessage(t.id, Roles.ASSISTANT, "Réponse 1")
            val v2 = r.addMessage(t.id, Roles.USER, "Version 2", root = true)
            val v3 = r.addMessage(t.id, Roles.USER, "Version 3", root = true)
            Triple(v1, v2, v3)
        }
        assertEquals(2, runBlocking { r.deleteFrom(t.id, v1.id) })
        runBlocking { r.messages(t.id) } // reading the conversation must not relink the survivors
        val survivors = runBlocking { r.allMessages(t.id) }
        assertEquals(setOf(v2.id, v3.id), survivors.map { it.id }.toSet())
        assertTrue(survivors.all { it.parentId == null })
        assertEquals(v3.id, runBlocking { r.session(t.id)!!.activeLeafId })
    }

    @Test fun h3SharedTextAndFilesBecomeADraftAndUnsafeUrisAreRefused() {
        val text = ShareInbox.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Article à résumer").putExtra(Intent.EXTRA_SUBJECT, "Sujet"))
        assertEquals("Article à résumer", text!!.text); assertEquals("Sujet", text.subject)
        val file = ShareInbox.parse(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/42")))
        assertEquals(listOf(Uri.parse("content://docs/42")), file!!.uris)
        assertNull("file:// from another app is refused", ShareInbox.parse(Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/x/secret"))))
        val selected = ShareInbox.parse(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "texte sélectionné"))
        assertEquals("texte sélectionné", selected!!.text)
        assertNull(ShareInbox.parse(Intent(Intent.ACTION_VIEW)))
    }
}
