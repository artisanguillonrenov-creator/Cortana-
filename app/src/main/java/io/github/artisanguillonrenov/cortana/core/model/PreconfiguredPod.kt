package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository

/**
 * The owner's RunPod pod "elyndor-5090", configured once by the update (owner's request, 2.0.0-rc8):
 * llama.cpp on port 8000 (chat, `cydonia-24b-elyndor`) and an OpenAI-compatible media server on port
 * 7860 (`bge-m3` embeddings, `lustify-sdxl-v4` images), reached through the RunPod HTTPS proxy.
 *
 * Applied at most once per [VERSION]: providers are created (or reused when the same address already
 * exists), the chat provider becomes the default one, and the image and embedding routes point to the
 * media server. Nothing else changes: other providers, conversations locked to their model and every
 * policy stay as they were, and whatever the owner changes afterwards (deleting a provider, another
 * default) is never undone. Only configuration: no network call here.
 */
class PreconfiguredPod(private val providers: ProviderRepository, private val settings: SettingsRepository, private val onEmbeddingsChanged: () -> Unit = {}) {
    data class Result(val chatProviderId: String, val mediaProviderId: String)

    /** Applies the configuration if this version was never applied; null when there is nothing to do. */
    suspend fun apply(): Result? {
        if (settings.current.preconfiguredPodVersion >= VERSION) return null
        val all = providers.all()
        val preset = providers.presets.byId("custom") ?: providers.presets.all.first()
        fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
        val chat = all.firstOrNull { same(it.baseUrl, CHAT_BASE_URL) } ?: providers.create(preset, CHAT_NAME, CHAT_BASE_URL, null)
        providers.update(chat.copy(enabled = true, defaultModelId = CHAT_MODEL), null)
        val media = all.firstOrNull { same(it.baseUrl, MEDIA_BASE_URL) } ?: providers.create(preset, MEDIA_NAME, MEDIA_BASE_URL, null)
        providers.update(media.copy(enabled = true, defaultModelId = IMAGE_MODEL), null)
        settings.update {
            it.copy(
                defaultProviderId = chat.id,
                imageRoute = "${media.id}/$IMAGE_MODEL",
                embeddingRoute = "${media.id}/$EMBEDDING_MODEL",
                preconfiguredPodVersion = VERSION,
            )
        }
        onEmbeddingsChanged()
        return Result(chat.id, media.id)
    }

    companion object {
        const val VERSION = 1
        const val POD_ID = "36w1us6m7ogo2b"
        const val CHAT_NAME = "RunPod · elyndor-5090"
        const val MEDIA_NAME = "RunPod · elyndor-5090 (médias)"
        const val CHAT_BASE_URL = "https://$POD_ID-8000.proxy.runpod.net/v1"
        const val MEDIA_BASE_URL = "https://$POD_ID-7860.proxy.runpod.net/v1"
        const val CHAT_MODEL = "cydonia-24b-elyndor"
        const val IMAGE_MODEL = "lustify-sdxl-v4"
        const val EMBEDDING_MODEL = "bge-m3"
    }
}
