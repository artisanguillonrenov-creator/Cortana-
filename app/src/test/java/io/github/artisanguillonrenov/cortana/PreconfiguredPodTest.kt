package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.model.PreconfiguredPod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's RunPod pod is configured once by the update, never twice, never against the owner's later choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreconfiguredPodTest : CortanaTestBase() {

    @Test fun notAppliedInUnitTestsByItself() = runBlocking {
        // Application start never configures the real pod under Robolectric.
        assertEquals(0, c.settings.current.preconfiguredPodVersion)
        assertTrue(c.providers.all().none { it.baseUrl.contains("proxy.runpod.net") })
    }

    @Test fun createsChatAndMediaProvidersAndRoutesOnce() = runBlocking {
        val previous = c.providers.create(c.presets.byId("infermatic")!!, "Infermatic", "https://api.totalgpt.ai/v1", null)
        c.settings.update { it.copy(defaultProviderId = previous.id) }
        var reindexed = 0
        val pod = PreconfiguredPod(c.providers, c.settings) { reindexed++ }
        val r = pod.apply()!!
        val chat = c.providers.get(r.chatProviderId!!)!!
        val media = c.providers.get(r.mediaProviderId!!)!!
        assertEquals("https://u0nb7hefflw2rg-8000.proxy.runpod.net/v1", chat.baseUrl)
        assertEquals("cydonia-24b-elyndor", chat.defaultModelId)
        assertEquals("https://u0nb7hefflw2rg-7860.proxy.runpod.net/v1", media.baseUrl)
        assertTrue(chat.enabled && media.enabled)
        assertNull("the pod has no API key", chat.apiKeyHandle)
        val s = c.settings.current
        assertEquals(chat.id, s.defaultProviderId)
        assertEquals("${media.id}/lustify-sdxl-v4", s.imageRoute)
        assertEquals("${media.id}/bge-m3", s.embeddingRoute)
        assertEquals(PreconfiguredPod.VERSION, s.preconfiguredPodVersion)
        assertEquals(1, reindexed)
        val cv = c.providers.get(r.codeVisionProviderId!!)!!
        assertEquals("https://biiby2y7jd3kf7-8080.proxy.runpod.net/v1", cv.baseUrl)
        assertNull("the API key is entered by the owner, never shipped", cv.apiKeyHandle)
        assertEquals("${cv.id}/qwen3.6-27b", s.codingRoute)
        assertEquals("${cv.id}/qwen3.6-27b", s.visionRoute)
        // The previous provider is kept as it was (keys need the Android keystore, absent under Robolectric).
        assertEquals(previous, c.providers.get(previous.id))

        // A second start does nothing; the owner's later choices are never undone.
        c.settings.update { it.copy(defaultProviderId = previous.id) }
        c.providers.delete(media.id)
        assertNull(pod.apply())
        assertEquals(previous.id, c.settings.current.defaultProviderId)
        assertNull(c.providers.get(media.id))
        assertEquals(1, c.providers.all().count { it.baseUrl.contains("-8000.proxy.runpod.net") })
    }

    @Test fun reusesAProviderTheOwnerAlreadyCreatedForThePod() = runBlocking {
        val mine = c.providers.create(c.presets.byId("custom")!!, "Mon pod", "https://u0nb7hefflw2rg-8000.proxy.runpod.net/v1/", null)
        val r = PreconfiguredPod(c.providers, c.settings).apply()!!
        assertEquals(mine.id, r.chatProviderId)
        assertEquals("Mon pod", c.providers.get(mine.id)!!.displayName)
        assertEquals(1, c.providers.all().count { it.baseUrl.contains("-8000.proxy.runpod.net") })
    }

    @Test fun upgradeFromRc8OnlyAddsCodeAndVision() = runBlocking {
        val mine = c.providers.create(c.presets.byId("custom")!!, "Mon choix", "https://example.com/v1", null)
        c.settings.update { it.copy(preconfiguredPodVersion = 1, defaultProviderId = mine.id, imageRoute = null, embeddingRoute = null) }
        var reindexed = 0
        val r = PreconfiguredPod(c.providers, c.settings) { reindexed++ }.apply()!!
        assertNull(r.chatProviderId)
        assertNull(r.mediaProviderId)
        val s = c.settings.current
        assertEquals("the rc8 step is not replayed", mine.id, s.defaultProviderId)
        assertNull(s.imageRoute)
        assertNull(s.embeddingRoute)
        assertEquals(0, reindexed)
        assertTrue(c.providers.all().none { it.baseUrl.contains("36w1us6m7ogo2b") })
        assertEquals("${r.codeVisionProviderId}/qwen3.6-27b", s.codingRoute)
        assertEquals("${r.codeVisionProviderId}/qwen3.6-27b", s.visionRoute)
        assertEquals(PreconfiguredPod.VERSION, s.preconfiguredPodVersion)
        assertNull(PreconfiguredPod(c.providers, c.settings).apply())
    }

    @Test fun theCodeAndVisionModelUsesNativeToolsAndItsServerContext() {
        // llama.cpp started with --jinja and -c 32768.
        val caps = c.capabilities.bundled("qwen3.6-27b")
        assertTrue(caps.nativeTools)
        assertTrue(caps.nativeJson)
        assertEquals(32768, caps.contextWindow)
    }

    @Test fun theChatModelUsesEmulatedToolsAndItsRealContext() {
        // llama.cpp started without --jinja ignores native tools: Cortana's emulated tool calling is used.
        val caps = c.capabilities.bundled("cydonia-24b-elyndor")
        assertFalse(caps.nativeTools)
        assertEquals(24576, caps.contextWindow)
    }

    @Test fun routesTheOwnerAlreadyChoseAreKept() = runBlocking {
        val mine = c.providers.create(c.presets.byId("custom")!!, "Mon serveur de code", "https://code.example/v1", null)
        c.settings.update { it.copy(preconfiguredPodVersion = 1, codingRoute = "${mine.id}/mon-codeur", visionRoute = "disparu/x") }
        val r = PreconfiguredPod(c.providers, c.settings).apply()!!
        assertEquals("${mine.id}/mon-codeur", c.settings.current.codingRoute)
        assertEquals("a route towards a deleted provider is replaced", "${r.codeVisionProviderId}/qwen3.6-27b", c.settings.current.visionRoute)
    }

    @Test fun anUpgradeMovesTheProvidersOfTheOldPodsToTheNewOnesAndLinksTheirNames() = runBlocking {
        val chat = c.providers.create(c.presets.byId("custom")!!, "RunPod · elyndor-5090", "https://36w1us6m7ogo2b-8000.proxy.runpod.net/v1", null)
        val media = c.providers.create(c.presets.byId("custom")!!, "RunPod · elyndor-5090 (médias)", "https://36w1us6m7ogo2b-7860.proxy.runpod.net/v1", null)
        val cv = c.providers.create(c.presets.byId("custom")!!, "RunPod · code & vision", "https://dfq6g338899rau-8080.proxy.runpod.net/v1", null)
        val other = c.providers.create(c.presets.byId("custom")!!, "Autre pod", "https://zzzother1-8000.proxy.runpod.net/v1", null)
        c.settings.update { it.copy(preconfiguredPodVersion = 2, defaultProviderId = chat.id, codingRoute = "${cv.id}/qwen3.6-27b", imageRoute = "${media.id}/lustify-sdxl-v4") }
        val r = PreconfiguredPod(c.providers, c.settings).apply()!!
        assertEquals(setOf(chat.id, media.id, cv.id), r.moved.toSet())
        assertEquals("https://u0nb7hefflw2rg-8000.proxy.runpod.net/v1", c.providers.get(chat.id)!!.baseUrl)
        assertEquals("https://u0nb7hefflw2rg-7860.proxy.runpod.net/v1", c.providers.get(media.id)!!.baseUrl)
        assertEquals("https://biiby2y7jd3kf7-8080.proxy.runpod.net/v1", c.providers.get(cv.id)!!.baseUrl)
        assertEquals("another pod is never touched", "https://zzzother1-8000.proxy.runpod.net/v1", c.providers.get(other.id)!!.baseUrl)
        // Same provider ids: the default model, the routes and the conversations keep working.
        val s = c.settings.current
        assertEquals(chat.id, s.defaultProviderId); assertEquals("${cv.id}/qwen3.6-27b", s.codingRoute)
        assertEquals(1, c.providers.all().count { it.baseUrl.contains("-8000.proxy.runpod.net") && it.baseUrl.contains("u0nb7hefflw2rg") })
        assertEquals("elyndor-5090-ro", s.runpodPodNames[chat.id]); assertEquals("cortana-code-vision", s.runpodPodNames[cv.id])
        assertEquals(3, s.preconfiguredPodVersion)
        assertNull(PreconfiguredPod(c.providers, c.settings).apply())
    }
}
