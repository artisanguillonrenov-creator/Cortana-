package io.github.artisanguillonrenov.cortana

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.artisanguillonrenov.cortana.core.chat.WebResultItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.ui.components.LocalWebMedia
import io.github.artisanguillonrenov.cortana.ui.components.WebMediaAccess
import io.github.artisanguillonrenov.cortana.ui.components.WebResultsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The typed views of web results: gallery and zoom, video card, page card, external-content label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebResultsUiTest {
    @get:Rule val rule = createComposeRule()

    private val items = WebResults.sanitize(listOf(
        WebResultItem(WebResults.IMAGE, "La tour de nuit", "https://photos.example/nuit", "photos.example", "https://cdn.example/nuit-t.jpg", "https://cdn.example/nuit.jpg", width = 1920, height = 1080, provider = "SearXNG"),
        WebResultItem(WebResults.IMAGE, "Vue du Trocadéro", "https://photos.example/troca", "photos.example", "https://cdn.example/troca-t.jpg", provider = "SearXNG"),
        WebResultItem(WebResults.VIDEO, "Construction de la tour", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Archives", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg", durationSeconds = 187, provider = "SearXNG"),
        WebResultItem(WebResults.WEB, "Tour Eiffel — Wikipédia", "https://fr.wikipedia.org/wiki/Tour_Eiffel", snippet = "Monument de fer puddlé", provider = "SearXNG"),
    ))
    private val opened = CopyOnWriteArrayList<String>()
    private val loaded = CopyOnWriteArrayList<String>()

    private fun show(list: List<WebResultItem> = items) {
        val picture = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            CompositionLocalProvider(LocalWebMedia provides WebMediaAccess(imageOverride = { url, _ -> loaded += url; picture })) {
                // In the app the answer sits in the scrolling conversation; here a scrolling column plays that part.
                Column(Modifier.verticalScroll(rememberScrollState())) { WebResultsView(list, onOpen = { opened += it }) }
            }
        }
        rule.waitForIdle()
    }

    @Test fun galleryVideoAndPageCardsRender() {
        show()
        rule.onNodeWithContentDescription("Galerie de 2 images").assertExists()
        rule.onNodeWithContentDescription("Image 1 sur 2 : La tour de nuit. Touchez pour agrandir.").assertExists()
        rule.onNodeWithContentDescription("Vidéo : Construction de la tour").assertExists()
        rule.onNodeWithText("3:07").assertExists()
        rule.onNodeWithText("Tour Eiffel — Wikipédia").assertExists()
        rule.onNodeWithText("fr.wikipedia.org").assertExists()
        rule.onNodeWithText("Contenu web externe, non vérifié", substring = true).assertExists()
        // Thumbnails are loaded, never the full pictures until asked.
        assertTrue(loaded.toString(), loaded.containsAll(listOf("https://cdn.example/nuit-t.jpg", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg")) && "https://cdn.example/nuit.jpg" !in loaded)
    }

    @Test fun anImageOpensLargerThenItsSource() {
        show()
        rule.onNodeWithContentDescription("Image 1 sur 2 : La tour de nuit. Touchez pour agrandir.").performClick()
        rule.waitForIdle()
        assertTrue("the full picture is loaded in the viewer", "https://cdn.example/nuit.jpg" in loaded)
        rule.onNodeWithText("Ouvrir la source").performClick()
        assertEquals(listOf("https://photos.example/nuit"), opened.toList())
    }

    @Test fun aYouTubeVideoAndAPageOpenTheirSiteNeverAnEmbeddedPage() {
        show()
        rule.onAllNodesWithContentDescription("Vidéo : Construction de la tour")[0].assertExists()
        rule.onNodeWithText("Construction de la tour").performScrollTo().performClick()
        rule.onNodeWithText("Tour Eiffel — Wikipédia").performScrollTo().performClick()
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://fr.wikipedia.org/wiki/Tour_Eiffel"), opened.toList())
    }

    @Test fun aSingleImageIsShownLarge() {
        show(items.take(1))
        rule.onNodeWithContentDescription("Image : La tour de nuit. Touchez pour agrandir.").assertExists()
    }

    @Test fun aDirectVideoWithoutAStreamClientOpensItsPage() {
        val direct = WebResults.sanitize(listOf(WebResultItem(WebResults.VIDEO, "Illuminations", "https://videos.example/illum", mediaUrl = "https://videos.example/illum.mp4")))
        show(direct)
        rule.onNodeWithText("Illuminations").performClick()
        assertEquals(listOf("https://videos.example/illum"), opened.toList())
    }
}
