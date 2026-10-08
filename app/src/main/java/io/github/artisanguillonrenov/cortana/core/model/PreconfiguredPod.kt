package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository

/**
 * The owner's RunPod pods, configured by the updates that introduced them (owner's requests):
 * - version 1 (2.0.0-rc8), pod "elyndor-5090": llama.cpp on port 8000 (chat, `cydonia-24b-elyndor`)
 *   and an OpenAI-compatible media server on port 7860 (`bge-m3` embeddings, `lustify-sdxl-v4`
 *   images). The chat provider becomes the default one, image and embedding routes point to the
 *   media server.
 * - version 2 (2.0.0-rc9), pod "cortana-code-vision": llama.cpp on port 8080 serving the multimodal
 *   `qwen3.6-27b` for both the coding and the vision routes. This server requires an API key, which
 *   is never shipped: the owner enters it once in the provider's settings.
 *
 * Each version's step runs at most once, from the version already applied: providers are created
 * (or reused when the same address already exists) and the routes of that step are set. Nothing
 * else changes: other providers, conversations locked to their model and every policy stay as they
 * were, and whatever the owner changes afterwards (deleting a provider, another route) is never
 * undone; a coding or vision route the owner had already chosen is kept. Only configuration: no network call here.
 */
class PreconfiguredPod(private val providers: ProviderRepository, private val settings: SettingsRepository, private val onEmbeddingsChanged: () -> Unit = {}) {
    /** What this run configured; a provider id is null when its step was already applied. */
    data class Result(val chatProviderId: String?, val mediaProviderId: String?, val codeVisionProviderId: String?)

    /** Applies the steps never applied; null when there is nothing to do. */
    suspend fun apply(): Result? {
        val from = settings.current.preconfiguredPodVersion
        if (from >= VERSION) return null
        val all = providers.all()
        val preset = providers.presets.byId("custom") ?: providers.presets.all.first()
        fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
        suspend fun provider(name: String, baseUrl: String, model: String): ProviderEntity {
            val existing = all.firstOrNull { same(it.baseUrl, baseUrl) }
            val p = existing ?: providers.create(preset, name, baseUrl, null)
            // A provider the owner already had for this address keeps the model they chose.
            providers.update(p.copy(enabled = true, defaultModelId = existing?.defaultModelId ?: model), null)
            return p
        }
        var chat: ProviderEntity? = null
        var media: ProviderEntity? = null
        if (from < 1) {
            chat = provider(CHAT_NAME, CHAT_BASE_URL, CHAT_MODEL)
            media = provider(MEDIA_NAME, MEDIA_BASE_URL, IMAGE_MODEL)
        }
        val codeVision = if (from < 2) provider(CODE_VISION_NAME, CODE_VISION_BASE_URL, CODE_VISION_MODEL) else null
        settings.update {
            var s = it.copy(preconfiguredPodVersion = VERSION)
            if (chat != null && media != null) {
                s = s.copy(defaultProviderId = chat.id, imageRoute = "${media.id}/$IMAGE_MODEL", embeddingRoute = "${media.id}/$EMBEDDING_MODEL")
            }
            if (codeVision != null) {
                // Routes the owner already chose (towards a provider that still exists) are kept.
                fun custom(r: String?) = r != null && all.any { p -> p.id == r.substringBefore('/') }
                val route = "${codeVision.id}/$CODE_VISION_MODEL"
                s = s.copy(codingRoute = if (custom(s.codingRoute)) s.codingRoute else route, visionRoute = if (custom(s.visionRoute)) s.visionRoute else route)
            }
            s
        }
        if (media != null) onEmbeddingsChanged()
        return Result(chat?.id, media?.id, codeVision?.id)
    }

    companion object {
        const val VERSION = 2
        const val POD_ID = "36w1us6m7ogo2b"
        const val CHAT_NAME = "RunPod · elyndor-5090"
        const val MEDIA_NAME = "RunPod · elyndor-5090 (médias)"
        const val CHAT_BASE_URL = "https://$POD_ID-8000.proxy.runpod.net/v1"
        const val MEDIA_BASE_URL = "https://$POD_ID-7860.proxy.runpod.net/v1"
        const val CHAT_MODEL = "cydonia-24b-elyndor"
        const val IMAGE_MODEL = "lustify-sdxl-v4"
        const val EMBEDDING_MODEL = "bge-m3"
        const val CODE_VISION_POD_ID = "dfq6g338899rau"
        const val CODE_VISION_NAME = "RunPod · code & vision"
        const val CODE_VISION_BASE_URL = "https://$CODE_VISION_POD_ID-8080.proxy.runpod.net/v1"
        const val CODE_VISION_MODEL = "qwen3.6-27b"
    }
}
