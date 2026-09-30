package io.github.artisanguillonrenov.cortana

import androidx.room.withTransaction
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentMode
import io.github.artisanguillonrenov.cortana.core.chat.ChatService
import io.github.artisanguillonrenov.cortana.core.chat.ChatStreamEvent
import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.StreamCursor
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.context.ContextRequest
import io.github.artisanguillonrenov.cortana.core.memory.MessageEntity
import io.github.artisanguillonrenov.cortana.core.memory.MessageStatus
import io.github.artisanguillonrenov.cortana.core.memory.PATH_WINDOW
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.TaskStates
import io.github.artisanguillonrenov.cortana.core.tools.Toolsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** H12: unstable network, provider fallback, duplicate/out-of-order events, hostile files, long conversations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatHardeningTest : CortanaTestBase() {
    private val chat get() = c.chat
    private fun timeline(s: SessionEntity) = runBlocking { val p = c.conversations.messages(s.id); ChatTimeline.build(p, c.conversations.branchMap(s.id, p)) }

    private fun chunks(vararg words: String) = words.joinToString("") { w -> "data: " + """{"choices":[{"delta":{"content":${q(w)}}}]}""" + "\n\n" }

    @Test fun h12NetworkDropMidAnswerKeepsWhatArrivedMarkedInterruptedWithoutBlindRetry() {
        val s = session(toolset = Toolsets.CONVERSATION)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(chunks("Début ", "de ", "la ", "réponse ", "qui ", "sera ", "coupée ", "par ", "le ", "réseau ", "ici ", "même."))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        runBlocking { assertEquals(ChatService.SendResult.Sent, chat.send(s.id, "Raconte")) }
        waitIdle()
        assertEquals("one provider, no silent re-send", 1, server.requestCount)
        assertEquals(TaskStates.FAILED, lastTask().state)
        val answer = runBlocking { c.conversations.allMessages(s.id) }.last { it.role == Roles.ASSISTANT }
        assertEquals(MessageStatus.INTERRUPTED, answer.status)
        assertTrue(answer.text, answer.text.startsWith("Début"))
        val cut = timeline(s).filterIsInstance<TimelineItem.Assistant>().last()
        assertTrue(cut.canContinue && cut.parts.any { it is MessagePart.SystemEvent && it.kind == "interrupted" })
    }

    @Test fun h12ProviderFallbackAfterAPartialStreamNeverDuplicatesTheText() {
        val backup = MockWebServer().apply { start() }
        try {
            val s = session(toolset = Toolsets.CONVERSATION)
            runBlocking {
                val preset = c.presets.byId("local")!!
                val p = c.providers.create(preset, "Secours", backup.url("/v1").toString().trimEnd('/'), null)
                c.providers.update(p.copy(defaultModelId = "secours-1", allowFallback = true), null)
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(chunks("Premier ", "fournisseur ", "qui ", "tombe ", "en ", "panne ", "au ", "milieu ", "de ", "sa ", "réponse ", "longue."))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            backup.enqueue(text("Réponse de secours complète."))
            runBlocking { assertEquals(ChatService.SendResult.Sent, chat.send(s.id, "Explique")) }
            waitIdle()
            assertEquals(1, backup.requestCount)
            val answer = runBlocking { c.conversations.allMessages(s.id) }.last { it.role == Roles.ASSISTANT }
            assertEquals(MessageStatus.COMPLETE, answer.status)
            assertEquals("Réponse de secours complète.", answer.text)
            val last = c.chatHub.events.replayCache.filterIsInstance<ChatStreamEvent.StreamDelta>().last { it.sessionId == s.id }
            assertEquals("the live text restarted with the fallback", "Réponse de secours complète.", last.full)
            assertTrue(timeline(s).any { it is TimelineItem.System && it.message.text.contains("Secours") })
        } finally { backup.shutdown() }
    }

    @Test fun h12DuplicateAndOutOfOrderEventsAreAppliedOnce() {
        fun delta(seq: Long, full: String) = ChatStreamEvent.StreamDelta("run1", seq, "s", "m", "", full)
        val cursor = StreamCursor()
        val arrived = listOf(delta(1, "a"), delta(2, "ab"), delta(4, "abcd"), delta(3, "abc"), delta(4, "abcd"), delta(2, "ab"))
        val applied = arrived.filter { cursor.accept(it) }
        assertEquals(listOf(1L, 2L, 4L), applied.map { it.sequence })
        assertEquals("abcd", applied.last().full) // a late event can never roll the text back
        assertTrue("another run is independent", cursor.accept(ChatStreamEvent.StreamDelta("run2", 1, "s", "m2", "x", "x")))
    }

    @Test fun h12HostileFileNamesStayInsideTheStoreAndUnreadableFilesAreNamedNotRead() {
        val s = session(toolset = Toolsets.CONVERSATION)
        val ref = runBlocking { chat.attach("../../../data/data/secrets.txt", "text/plain", 5, s.id) { "hello".byteInputStream() } }
        assertEquals("ready", ref.status)
        val art = runBlocking { c.artifacts.get(ref.artifactId)!! }
        assertFalse(art.name.contains('/') || art.name.contains('\\'))
        assertTrue(c.artifacts.file(art).canonicalPath.startsWith(c.artifacts.root.canonicalPath))
        val bin = runBlocking { chat.attach("image.bin", "application/octet-stream", 4, s.id) { byteArrayOf(0, 1, 2, 3).inputStream() } }
        val ctx = runBlocking { chat.resolve(listOf(bin.copy(mode = AttachmentMode.READ))) }
        assertTrue("an unreadable file is named, never injected", ctx.data.isEmpty() || ctx.data.none { it.content.contains('\u0000') })
        val unsupported = runBlocking { chat.attach("film.mp4", "video/mp4", ChatService.MAX_ATTACHMENT_BYTES + 1) { null } }
        assertEquals("too_large", unsupported.status)
        val broken = runBlocking { chat.attach("perdu.pdf", "application/pdf", 10, s.id) { null } }
        assertEquals("failed", broken.status)
    }

    /**
     * Long conversation (doc 14 "Performance"): 5 000 messages in one conversation and 100 conversations.
     * The screen reads a 400-message window; the model gets a budgeted context. Bounds are generous
     * (JVM + Robolectric); the measured times are printed for the progress report.
     */
    @Test fun h12FiveThousandMessagesAndAHundredConversationsStayFast() {
        val s = session(toolset = Toolsets.CONVERSATION)
        runBlocking {
            c.db.withTransaction {
                var parent: String? = null
                for (i in 0 until 5_000) {
                    val m = MessageEntity("m$i", s.id, if (i % 2 == 0) Roles.USER else Roles.ASSISTANT,
                        if (i % 2 == 0) "Question $i sur **le sujet** numéro ${i / 2}" else "Réponse $i : un paragraphe avec `code`, une liste\n- a\n- b\net un lien https://exemple.fr/$i",
                        1_000L + i, parentId = parent)
                    c.db.messages().insert(m)
                    parent = m.id
                }
                c.db.sessions().setLeafOnly(s.id, parent)
                for (k in 0 until 100) {
                    val other = c.conversations.createSession("Discussion $k")
                    var p: String? = null
                    for (j in 0 until 5) { val m = MessageEntity("o$k-$j", other.id, Roles.USER, "Message $j de la discussion $k", 10L + j, parentId = p); c.db.messages().insert(m); p = m.id }
                    c.db.sessions().setLeafOnly(other.id, p)
                }
            }
        }
        fun <T> timed(label: String, block: () -> T): Pair<T, Long> {
            val t0 = System.nanoTime(); val r = block(); val ms = (System.nanoTime() - t0) / 1_000_000
            println("PERF $label: $ms ms"); return r to ms
        }
        val (window, pathMs) = timed("path window (400 of 5000)") { runBlocking { c.conversations.path(s.id, PATH_WINDOW) } }
        assertEquals(PATH_WINDOW + 1, window.size); assertEquals("m4999", window.last().id)
        val (branches, branchMs) = timed("branch map") { runBlocking { c.conversations.branchMap(s.id, window) } }
        val (items, timelineMs) = timed("timeline build + markdown (401 rows)") { ChatTimeline.build(window, branches) }
        assertTrue(items.size >= 400)
        val (_, rebuildMs) = timed("timeline rebuild, parse cached") {
            val cache = HashMap<String, List<io.github.artisanguillonrenov.cortana.core.chat.MdBlock>>()
            ChatTimeline.build(window, branches, parse = { t -> cache.getOrPut(t) { io.github.artisanguillonrenov.cortana.core.chat.Markdown.parse(t) } })
            ChatTimeline.build(window, branches, parse = { t -> cache.getOrPut(t) { io.github.artisanguillonrenov.cortana.core.chat.Markdown.parse(t) } })
        }
        val (previews, previewMs) = timed("sidebar previews (101 conversations)") { runBlocking { c.conversations.observePreviews().first() } }
        assertEquals(101, previews.size)
        val (hits, searchMs) = timed("full-text search") { runBlocking { chat.search("sujet") } }
        assertTrue(hits.isNotEmpty())
        val session = runBlocking { c.conversations.session(s.id)!! }
        val (built, contextMs) = timed("context build (5000-message branch)") {
            runBlocking { c.contextEngine.build(ContextRequest(session = session, objective = "Et ensuite ?", tools = emptyList(), contextWindow = 8_192)) }
        }
        assertTrue("the model gets a budgeted window, never 5000 messages", built.report.windowMessages < 400 && built.report.droppedMessages > 0)
        assertTrue("path $pathMs ms", pathMs < 2_000)
        assertTrue("branch map $branchMs ms", branchMs < 2_000)
        assertTrue("timeline $timelineMs ms", timelineMs < 3_000)
        assertTrue("rebuild $rebuildMs ms", rebuildMs < 3_000)
        assertTrue("previews $previewMs ms", previewMs < 3_000)
        assertTrue("search $searchMs ms", searchMs < 3_000)
        assertTrue("context $contextMs ms", contextMs < 5_000)
    }
}
