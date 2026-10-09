package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.PlanStrategy
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentMode
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ChatTimeline
import io.github.artisanguillonrenov.cortana.core.chat.ImageIntent
import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.chat.MessagePart
import io.github.artisanguillonrenov.cortana.core.chat.TimelineItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.memory.Roles
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathContext
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathRegistry
import io.github.artisanguillonrenov.cortana.core.tools.CapabilityMatcher
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDiscovery
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Cherche-moi une image sur Internet" shows real pictures in the answer (rc11): an explicit request runs
 * web.search in images mode directly, the model's forgotten mode is corrected, a search is never answered
 * with a generated picture, and pictures Cortana produces are shown as pictures — again after a reload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageSearchTest : CortanaTestBase() {
    private lateinit var searx: MockWebServer
    private val categories = CopyOnWriteArrayList<String>()
    private val queries = CopyOnWriteArrayList<String>()
    private val safe = CopyOnWriteArrayList<String>()
    private var empty = false

    private val images = """{"results":[
        {"title":"La tour de nuit","url":"https://photos.example/nuit","img_src":"https://cdn.photos.example/nuit.jpg","thumbnail_src":"https://cdn.photos.example/nuit-t.jpg","resolution":"1920 x 1080"},
        {"title":"Vue du Trocadéro","url":"https://photos.example/troca","img_src":"https://cdn.photos.example/troca.jpg","resolution":"1600x1200"},
        {"title":"Au printemps","url":"https://photos.example/printemps","img_src":"https://cdn.photos.example/printemps.jpg"}]}"""

    @Before fun startSearx() {
        searx = MockWebServer()
        searx.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                categories += request.requestUrl!!.queryParameter("categories") ?: "general"
                queries += request.requestUrl!!.queryParameter("q").orEmpty()
                safe += request.requestUrl!!.queryParameter("safesearch") ?: "absent"
                val body = if (empty) """{"results":[]}""" else images
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        searx.start()
        runBlocking { c.settings.update { it.copy(searchProvider = "searxng", searchBaseUrl = searx.url("/sx").toString(), allowPrivateNetworkFetch = false) } }
    }

    @After fun stopSearx() = searx.shutdown()

    private fun timeline(s: SessionEntity) = runBlocking { val p = c.conversations.messages(s.id); ChatTimeline.build(p, c.conversations.branchMap(s.id, p)) }
    private fun answer(s: SessionEntity) = timeline(s).filterIsInstance<TimelineItem.Assistant>().last()
    private fun calls() = runBlocking { c.db.tasks().toolCalls(lastTask().id) }

    @Test fun explicitRequestsAreRecognisedAndGenerationIsNeverASearch() {
        assertEquals(ImageIntent.Search("tour Eiffel", "images"), ImageIntent.search("Trouve-moi une photo de la tour Eiffel sur Internet"))
        assertEquals(ImageIntent.Search("dragons", "images"), ImageIntent.search("Montre-moi des images de dragons"))
        assertEquals(ImageIntent.Search("chats roux", "images"), ImageIntent.search("Peux-tu me chercher des photos de chats roux stp ?"))
        assertEquals(ImageIntent.Search("construction du viaduc de Millau", "videos"), ImageIntent.search("Cherche une vidéo de la construction du viaduc de Millau"))
        // No subject: left to the model (which may ask what to look for), but still a web image search.
        assertNull(ImageIntent.search("Cherche une image sur le web"))
        assertTrue(ImageIntent.isWebImageSearch("Cherche une image sur le web"))
        // Generation, retouching, follow-up actions: never the direct search.
        for (t in listOf("Génère une image de dragon", "Dessine-moi un chat", "Crée une image de la tour Eiffel", "Retouche cette photo",
            "Trouve une photo de la tour Eiffel puis envoie-la à Paul")) assertNull(t, ImageIntent.search(t))
        assertTrue(ImageIntent.isGeneration("Génère une image de dragon"))
        assertTrue(ImageIntent.isGeneration("Dessine-moi un chat"))
        assertFalse(ImageIntent.isGeneration("Trouve-moi une photo de la tour Eiffel sur Internet"))
        // The fast path only for the explicit search.
        val reg = FastPathRegistry()
        val ctx = FastPathContext(System.currentTimeMillis(), ZoneId.of("Europe/Paris"), false, setOf("web.search", "media.image.generate"))
        assertEquals("web.images", reg.match("Trouve-moi une photo de la tour Eiffel sur Internet", ctx)?.pathId)
        assertNull(reg.match("Dessine-moi un dragon", ctx))
    }

    @Test fun anExplicitImageSearchShowsPicturesInTheAnswerWithoutAModelAndAfterReload() {
        val s = session()
        runAndWait(s, "Trouve-moi une photo de la tour Eiffel sur Internet")
        assertEquals("the model is never asked", 0, server.requestCount)
        assertEquals(listOf("images"), categories.toList())
        assertEquals(listOf("tour Eiffel"), queries.toList())
        assertEquals("SafeSearch off on SearXNG", listOf("0"), safe.toList())
        val call = calls().single()
        assertEquals("web.search" to "ok", call.capability to call.outcome)
        assertTrue(call.inputJson, call.inputJson.contains("\"mode\":\"images\""))
        assertEquals("completed", lastTask().state)

        val a = answer(s)
        assertTrue(a.text, a.text.startsWith("Voici des images de « tour Eiffel » trouvées sur le web (SearXNG)"))
        val web = a.parts.filterIsInstance<MessagePart.WebResults>().single().items
        assertEquals(3, web.size)
        assertTrue(web.all { it.type == WebResults.IMAGE && it.mediaUrl!!.startsWith("https://cdn.photos.example/") })
        assertTrue("no file chip, no artifact", a.parts.none { it is MessagePart.Artifact || it is MessagePart.File })
        // Read back from the database (reopening the conversation): the same pictures.
        assertEquals(web, timeline(s).filterIsInstance<TimelineItem.Assistant>().last().parts.filterIsInstance<MessagePart.WebResults>().single().items)
    }

    @Test fun anEmptySearchSaysSoInsteadOfInventingAResult() {
        empty = true
        val s = session()
        runAndWait(s, "Montre-moi des images de dragons")
        assertEquals(0, server.requestCount)
        val a = answer(s)
        assertTrue(a.text, a.text.startsWith("Je n'ai trouvé aucune de ces images pour « dragons »"))
        assertTrue(a.parts.none { it is MessagePart.WebResults || it is MessagePart.Artifact })
    }

    @Test fun theModelsForgottenModeIsCorrectedForAnExplicitWebImageRequest() {
        val s = session()
        server.enqueue(toolCall("web_search", """{"query":"chats roux"}"""))
        server.enqueue(text("Voici des chats roux."))
        runAndWait(s, "J'aimerais voir quelques photos de chats roux trouvées sur internet")
        assertEquals(listOf("images"), categories.toList())
        assertTrue(answer(s).parts.filterIsInstance<MessagePart.WebResults>().single().items.all { it.type == WebResults.IMAGE })
    }

    @Test fun aWebImageSearchIsNeverOfferedGenerationAndGenerationIsNotASearch() {
        val pool = c.registry.all()
        fun offered(text: String) = CapabilityMatcher(ToolDiscovery())
            .select(pool, text, setOf(ToolCategory.WEB, ToolCategory.MEDIA), emptyList(), emptySet(), PlanStrategy.INTERACTIVE, 24).offered.map { it.capability }
        val search = offered("J'aimerais voir quelques photos de chats roux trouvées sur internet")
        assertTrue(search.toString(), "web.search" in search && "media.image.generate" !in search && "media.image.edit" !in search)
        assertTrue("media.image.generate" in offered("Génère une image de dragon sur fond rouge"))

        // Even if a model calls it anyway, the generation tool refuses a web image search.
        val s = session()
        server.enqueue(toolCall("media_image_generate", """{"prompt":"chats roux"}"""))
        server.enqueue(text("Désolé."))
        runAndWait(s, "J'aimerais voir quelques photos de chats roux trouvées sur internet")
        assertTrue(calls().none { it.capability == "media.image.generate" && it.outcome == "ok" })
        assertTrue(categories.isEmpty() || categories.all { it == "images" })
    }

    @Test fun picturesCortanaProducedAreShownAsPicturesAndOnlyFromMediaTools() {
        val s = session()
        val png = AttachmentRef("a1b2c3d4-0000-4000-8000-000000000001", "dragon.png", "image/png", 2048, AttachmentMode.REFERENCE, note = "image générée")
        val pdf = AttachmentRef("a1b2c3d4-0000-4000-8000-000000000002", "doc.pdf", "application/pdf", 9000, AttachmentMode.REFERENCE)
        val svg = AttachmentRef("a1b2c3d4-0000-4000-8000-000000000003", "x.svg", "image/svg+xml", 10, AttachmentMode.REFERENCE)
        fun meta(vararg r: AttachmentRef) = AppJson.encodeToString(MessageMeta.serializer(), MessageMeta(images = r.toList()))
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Dessine un dragon")
            c.conversations.addMessage(s.id, Roles.TOOL, "1 image(s) générée(s)", toolCallsJson = """{"toolCallId":"a","name":"media_image_generate","capability":"media.image.generate","ok":true}""", metaJson = meta(png, pdf, svg))
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Voici ton dragon.")
        }
        val shown = answer(s).parts.filterIsInstance<MessagePart.Image>().map { it.attachment }
        assertEquals("only bitmaps, never a PDF or an SVG", listOf(png), shown)
        assertTrue(answer(s).parts.none { it is MessagePart.Artifact })
        runBlocking {
            c.conversations.addMessage(s.id, Roles.USER, "Encore")
            // Another tool's row with the same metadata shape shows nothing.
            c.conversations.addMessage(s.id, Roles.TOOL, "x", toolCallsJson = """{"toolCallId":"b","name":"mcp_x","capability":"mcp.x","ok":true}""", metaJson = meta(png))
            c.conversations.addMessage(s.id, Roles.ASSISTANT, "Fait.")
        }
        assertTrue(answer(s).parts.none { it is MessagePart.Image })
    }

    @Test fun duckDuckGoImagesWorkAndItsFailuresSayWhatToDo() = runBlocking {
        val ddg = MockWebServer()
        var page = "<script>vqd=\"4-123456789012345678901234567890\";</script>"
        var api: () -> MockResponse = { MockResponse().setBody("""{"results":[{"title":"Tour","url":"https://ex.example/p","image":"https://ex.example/t.jpg","thumbnail":"https://ex.example/t-s.jpg","width":800,"height":600,"source":"Bing"}]}""") }
        ddg.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/i.js")) {
                    assertEquals("4-123456789012345678901234567890", request.requestUrl!!.queryParameter("vqd"))
                    // SafeSearch off: p=-1 (p=1 turned the filter on) and kp=-2.
                    assertEquals("-1", request.requestUrl!!.queryParameter("p")); assertEquals("-2", request.requestUrl!!.queryParameter("kp")); api()
                } else MockResponse().setBody(page)
        }
        ddg.start()
        try {
            val search = io.github.artisanguillonrenov.cortana.executors.web.WebExecutor.DuckDuckGoHtmlSearch(okhttp3.OkHttpClient(), "test", ddg.url("/").toString())
            val found = search.images("tour eiffel", 6)
            assertEquals(listOf("https://ex.example/t.jpg"), found.map { it.mediaUrl })
            api = { MockResponse().setResponseCode(429) }
            assertTrue(runCatching { search.images("x", 6) }.exceptionOrNull()!!.message!!.contains("limite les recherches"))
            api = { MockResponse().setBody("<html>captcha</html>") }
            assertTrue(runCatching { search.images("x", 6) }.exceptionOrNull()!!.message!!.contains("illisible"))
            page = "<html>rien</html>"
            assertTrue(runCatching { search.images("x", 6) }.exceptionOrNull()!!.message!!.contains("jeton de recherche"))
        } finally { ddg.shutdown() }
    }

    @Test fun aToolLessConversationExplainsInsteadOfPretending() {
        val s = session(toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.CONVERSATION)
        runAndWait(s, "Trouve-moi une photo de la tour Eiffel sur Internet")
        assertEquals(0, server.requestCount)
        assertTrue(categories.isEmpty())
        assertTrue(answer(s).text, answer(s).text.contains("mode « Discussion »") && answer(s).text.contains("Navigateur"))
    }
}
