package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * web.search with a mode, end to end: model → orchestrator → dispatcher (policy, taint) → SearXNG →
 * validated rich results stored with the conversation → timeline part, again after reading the
 * conversation back from the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebResultsEndToEndTest : CortanaTestBase() {
    private lateinit var searx: MockWebServer
    private val categories = CopyOnWriteArrayList<String>()

    private val general = """{"results":[
        {"title":"Tour Eiffel — Wikipédia","url":"https://fr.wikipedia.org/wiki/Tour_Eiffel","content":"Monument de fer puddlé de 330 m","thumbnail":"https://upload.wikimedia.org/eiffel.jpg"},
        {"title":"Piège","url":"javascript:alert(document.cookie)","content":"x"},
        {"title":"Intranet","url":"http://192.168.1.20/admin","content":"x"},
        {"title":"Horaires","url":"https://www.toureiffel.paris/fr","content":"Horaires et tarifs"}]}"""
    private val images = """{"results":[
        {"title":"La tour de nuit","url":"https://photos.example/nuit","img_src":"https://cdn.photos.example/nuit.jpg","thumbnail_src":"https://cdn.photos.example/nuit-t.jpg","resolution":"1920 x 1080","engine":"bing images"},
        {"title":"Vue du Trocadéro","url":"https://photos.example/troca","img_src":"https://cdn.photos.example/troca.jpg","resolution":"1600x1200"},
        {"title":"Image http","url":"https://photos.example/http","img_src":"http://cdn.photos.example/insecure.jpg"}]}"""
    private val videos = """{"results":[
        {"title":"Construction de la tour Eiffel","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","thumbnail":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","length":"3:07","author":"Archives"},
        {"title":"Illuminations","url":"https://videos.example/illum","thumbnail":"https://videos.example/illum.jpg","video_src":"https://videos.example/illum.mp4","length":"0:45"}]}"""

    @Before fun startSearx() {
        searx = MockWebServer()
        searx.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cat = request.requestUrl!!.queryParameter("categories") ?: "general"
                categories += cat
                val body = when (cat) { "images" -> images; "videos" -> videos; else -> general }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        searx.start()
        runBlocking { c.settings.update { it.copy(searchProvider = "searxng", searchBaseUrl = searx.url("/sx").toString(), allowPrivateNetworkFetch = false) } }
    }

    @After fun stopSearx() { searx.shutdown() }

    private fun timeline(s: SessionEntity) = runBlocking { val p = c.conversations.messages(s.id); ChatTimeline.build(p, c.conversations.branchMap(s.id, p)) }
    private fun webPart(s: SessionEntity) = timeline(s).filterIsInstance<TimelineItem.Assistant>().last().parts.filterIsInstance<MessagePart.WebResults>().singleOrNull()

    @Test fun mixedSearchShowsImagesVideosAndCardsInTheAnswerAndSurvivesReload() {
        val s = session()
        server.enqueue(toolCall("web_search", """{"query":"tour eiffel","mode":"mixed","count":4}"""))
        server.enqueue(text("Voici la tour Eiffel en images et en vidéo."))
        runAndWait(s, "Montre-moi la tour Eiffel")
        assertEquals(listOf("general", "images", "videos"), categories.toList())

        // Through the dispatcher, recorded, and the task is marked as influenced by external content.
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals("web.search" to "ok", call.capability to call.outcome)
        assertTrue(lastTask().tainted)

        // Stored with the conversation: the tool row carries the validated results.
        val row = runBlocking { c.conversations.messages(s.id) }.single { it.role == Roles.TOOL }
        val stored = ChatTimeline.meta(row).webResults
        assertEquals(listOf(WebResults.WEB, WebResults.WEB, WebResults.IMAGE, WebResults.IMAGE, WebResults.VIDEO, WebResults.VIDEO), stored.map { it.type })
        assertTrue("invalid pages never stored", stored.none { it.url.startsWith("javascript") || it.url.contains("192.168") })
        assertTrue("image without https picture dropped", stored.none { it.title == "Image http" })
        assertEquals(1920, stored.first { it.title == "La tour de nuit" }.width)
        val yt = stored.first { it.url.contains("youtube") }
        assertEquals(WebResults.YOUTUBE, yt.metadata["videoKind"]); assertEquals(187, yt.durationSeconds)
        val mp4 = stored.first { it.title == "Illuminations" }
        assertEquals(WebResults.DIRECT, mp4.metadata["videoKind"]); assertEquals("https://videos.example/illum.mp4", mp4.mediaUrl)

        // The answer shows them as a typed part (not Markdown), after the text, with the sources list.
        val answer = timeline(s).filterIsInstance<TimelineItem.Assistant>().last()
        val kinds = answer.parts.map { it::class.simpleName }
        assertTrue(kinds.toString(), kinds.indexOf("WebResults") > kinds.indexOf("Markdown") && kinds.indexOf("Citations") > kinds.indexOf("WebResults"))
        assertEquals(stored, (answer.parts.single { it is MessagePart.WebResults } as MessagePart.WebResults).items)
        assertTrue("no picture address in the text", !answer.text.contains("cdn.photos.example"))

        // The model read a text version, never raw JSON.
        val toModel = row.text
        assertTrue(toModel, toModel.contains("Image : La tour de nuit") && toModel.contains("Vidéo : Construction") && !toModel.contains("\"img_src\""))

        // Reopening the conversation later: the same rows read back from the database give the same part.
        val reread = runBlocking { c.db.messages().forSession(s.id) }
        assertTrue(reread.any { it.metaJson?.contains("\"webResults\"") == true })
        assertEquals(stored, webPart(s)!!.items)
    }

    @Test fun webModeShowsPageCardsWithoutMedia() {
        val s = session()
        server.enqueue(toolCall("web_search", """{"query":"horaires tour eiffel"}"""))
        server.enqueue(text("La tour est ouverte tous les jours."))
        runAndWait(s, "Quels sont les horaires de la tour Eiffel ?")
        assertEquals(listOf("general"), categories.toList())
        val items = webPart(s)!!.items
        assertTrue(items.all { it.type == WebResults.WEB })
        assertEquals(listOf("https://fr.wikipedia.org/wiki/Tour_Eiffel", "https://www.toureiffel.paris/fr"), items.map { it.url })
        assertEquals("https://upload.wikimedia.org/eiffel.jpg", items[0].thumbnailUrl)
        assertNull(items[1].thumbnailUrl)
    }

    @Test fun onlyWebSearchRowsCanCarryRichResultsAndTheyAreValidatedAgainOnDisplay() {
        val s = session()
        val forged = io.github.artisanguillonrenov.cortana.util.AppJson.encodeToString(io.github.artisanguillonrenov.cortana.core.chat.MessageMeta.serializer(),
            io.github.artisanguillonrenov.cortana.core.chat.MessageMeta(webResults = listOf(
                io.github.artisanguillonrenov.cortana.core.chat.WebResultItem(WebResults.IMAGE, "Faux", "https://evil.example/", thumbnailUrl = "https://evil.example/x.jpg"),
                io.github.artisanguillonrenov.cortana.core.chat.WebResultItem(WebResults.WEB, "Piège", "javascript:alert(1)"))))
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Cherche")
            // A plugin/MCP tool row with the same metadata shape shows nothing.
            c.conversations.addMessage(s.id, Roles.TOOL, "résultat", toolCallsJson = """{"toolCallId":"a","name":"mcp_x","capability":"mcp.x","ok":true}""", metaJson = forged)
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Réponse")
        }
        assertNull(webPart(s))
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Cherche encore")
            // A web.search row written with an unsafe entry (older version, or edited): the entry is dropped on display.
            c.conversations.addMessage(s.id, Roles.TOOL, "résultat", toolCallsJson = """{"toolCallId":"b","name":"web_search","capability":"web.search","ok":true}""", metaJson = forged)
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Réponse")
        }
        assertEquals(listOf("https://evil.example/"), webPart(s)!!.items.map { it.url })
    }

    @Test fun anAnswerWithoutWebSearchHasNoWebResults() {
        val s = session()
        server.enqueue(text("Bonjour !"))
        runAndWait(s, "Salut")
        assertNull(webPart(s))
    }
}
