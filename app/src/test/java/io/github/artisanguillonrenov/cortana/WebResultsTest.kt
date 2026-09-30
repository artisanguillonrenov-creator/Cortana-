package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.chat.MessageMeta
import io.github.artisanguillonrenov.cortana.core.chat.Sources
import io.github.artisanguillonrenov.cortana.core.chat.WebResultItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.media.RemoteImages
import io.github.artisanguillonrenov.cortana.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rich web results: validation, classification, persistence format (pure JVM). */
class WebResultsTest {
    private fun img(url: String = "https://site.example/page", thumb: String? = "https://cdn.example/t.jpg", media: String? = "https://cdn.example/full.jpg") =
        WebResultItem(WebResults.IMAGE, "Tour Eiffel", url, "site.example", thumb, media, null, 1200, 800, provider = "SearXNG")

    @Test fun invalidUrlsAreRefused() {
        for (bad in listOf("javascript:alert(1)", "data:text/html,<b>x</b>", "file:///etc/passwd", "content://x/y", "intent://x#Intent;end", "ftp://x.example/a",
            "http://localhost/a", "https://127.0.0.1/a", "http://192.168.1.10/a", "http://10.0.0.1/", "http://169.254.169.254/latest", "http://[::1]/", "https://intranet/",
            "https://user:pass@site.example/", "https://printer.local/", "https://a.example/\u0000x", "   ", "https://")) {
            assertNull(bad, WebResults.link(bad))
        }
        assertEquals("https://fr.wikipedia.org/wiki/Tour_Eiffel", WebResults.link("https://fr.wikipedia.org/wiki/Tour_Eiffel"))
        assertEquals("http://example.org/page", WebResults.link("http://example.org/page"))
        // Pictures and streams Cortana downloads itself: https only.
        assertNull(WebResults.media("http://cdn.example/t.jpg"))
        assertEquals("https://cdn.example/t.jpg", WebResults.media("https://cdn.example/t.jpg"))
    }

    @Test fun sanitizeKeepsOnlyWellFormedResults() {
        val out = WebResults.sanitize(listOf(
            img(),
            img(url = "javascript:alert(1)"), // no safe page
            img(thumb = "http://cdn.example/t.jpg", media = "http://cdn.example/f.jpg"), // image with nothing displayable
            WebResultItem("script", "x", "https://a.example/"), // unknown type
            WebResultItem(WebResults.WEB, "<b>Titre</b>\u0007 avec   balises", "https://a.example/p", null, "https://192.168.0.2/t.png", null, "Extrait <i>court</i>"),
            img(), // duplicate
        ))
        assertEquals(2, out.size)
        assertEquals(WebResults.IMAGE, out[0].type)
        val web = out[1]
        assertEquals("Titre avec balises", web.title)
        assertEquals("a.example", web.source)
        assertNull("private thumbnail dropped", web.thumbnailUrl)
        assertEquals("Extrait court", web.snippet)
        // Bounds.
        val many = (1..60).map { img(url = "https://site.example/p$it", media = "https://cdn.example/$it.jpg") }
        assertEquals(WebResults.MAX_ITEMS, WebResults.sanitize(many).size)
        assertNull(WebResults.sanitize(listOf(img().copy(width = -3, height = 99_999))).single().width)
    }

    @Test fun videosAreClassifiedAndTheKindCannotBeForged() {
        assertEquals(WebResults.DIRECT, WebResults.videoKind("https://site.example/v", "https://cdn.example/clip.mp4?sig=1"))
        assertEquals(WebResults.DIRECT, WebResults.videoKind("https://site.example/v", "https://cdn.example/live/index.m3u8"))
        assertEquals(WebResults.YOUTUBE, WebResults.videoKind("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null))
        assertEquals(WebResults.PAGE, WebResults.videoKind("https://vimeo.com/1234", null))
        assertEquals("dQw4w9WgXcQ", WebResults.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals("dQw4w9WgXcQ", WebResults.youtubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(WebResults.youtubeId("https://evil.example/watch?v=dQw4w9WgXcQ"))
        val forged = WebResults.sanitize(listOf(WebResultItem(WebResults.VIDEO, "V", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", metadata = mapOf("videoKind" to "direct"))))
        assertEquals(WebResults.YOUTUBE, forged.single().metadata["videoKind"])
        // A stream over http is not kept: never played inline.
        assertNull(WebResults.sanitize(listOf(WebResultItem(WebResults.VIDEO, "V", "https://site.example/v", mediaUrl = "http://cdn.example/a.mp4"))).single().mediaUrl)
    }

    @Test fun durations() {
        assertEquals(187, WebResults.parseDuration("3:07"))
        assertEquals(3725, WebResults.parseDuration("1:02:05"))
        assertEquals(187, WebResults.parseDuration("PT3M7S"))
        assertEquals(42, WebResults.parseDuration("42"))
        assertNull(WebResults.parseDuration("bientôt"))
        assertEquals("3:07", WebResults.formatDuration(187))
        assertEquals("1:02:05", WebResults.formatDuration(3725))
    }

    @Test fun theModelTextKeepsTheCitationLayout() {
        val items = WebResults.sanitize(listOf(
            WebResultItem(WebResults.WEB, "Tour Eiffel — Wikipédia", "https://fr.wikipedia.org/wiki/Tour_Eiffel", snippet = "Monument de Paris"),
            img(),
        ))
        val text = WebResults.forModel("tour eiffel", "SearXNG", "mixed", items)
        assertTrue(text, text.contains("Ne recopie pas les adresses"))
        val sources = Sources.from("web.search", """{"query":"tour eiffel"}""", text, 0)
        assertEquals(listOf("https://fr.wikipedia.org/wiki/Tour_Eiffel", "https://site.example/page"), sources.map { it.url })
        assertTrue(sources.last().title.startsWith("Image : "))
    }

    @Test fun resultsPersistInTheMessageMetaAndOldMetaStillReads() {
        val meta = MessageMeta(webResults = WebResults.sanitize(listOf(img(), WebResultItem(WebResults.VIDEO, "Vidéo", "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", durationSeconds = 212))))
        val json = AppJson.encodeToString(MessageMeta.serializer(), meta)
        val back = AppJson.decodeFromString(MessageMeta.serializer(), json)
        assertEquals(meta, back)
        assertEquals(212, back.webResults[1].durationSeconds)
        // A row written before this version (no webResults) decodes to an empty list.
        assertTrue(AppJson.decodeFromString(MessageMeta.serializer(), """{"modelId":"m"}""").webResults.isEmpty())
    }

    @Test fun onlyRasterImagesAreDownloaded() {
        assertTrue(RemoteImages.acceptedType("image/jpeg"))
        assertTrue(RemoteImages.acceptedType("image/webp; charset=binary"))
        assertFalse(RemoteImages.acceptedType("image/svg+xml"))
        assertFalse(RemoteImages.acceptedType("text/html"))
        assertEquals(1, RemoteImages.sampleSize(800, 600, 480))
        // Same rule as the attachment thumbnails: halve until neither side exceeds twice the target (4000 → 500).
        assertEquals(8, RemoteImages.sampleSize(4000, 3000, 480))
    }
}
