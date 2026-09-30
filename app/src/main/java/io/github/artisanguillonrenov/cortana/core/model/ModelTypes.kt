package io.github.artisanguillonrenov.cortana.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    /** Opaque provider reasoning blocks, echoed back verbatim during tool use, never shown. */
    val reasoningDetails: JsonElement? = null,
    /** Images for vision models, as data URIs (`data:image/png;base64,…`); sent as content parts. */
    val images: List<String>? = null,
)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class ModelRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    /** auto | none | required */
    val toolChoice: String? = null,
    val jsonMode: Boolean = false,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = true,
    /** OpenAI-style `reasoning_effort`, only when the owner set one for a council role. */
    val reasoningEffort: String? = null,
)

@Serializable
data class Usage(val inputTokens: Int? = null, val outputTokens: Int? = null, val costUsd: Double? = null)

sealed interface ModelStreamEvent {
    data class TextDelta(val text: String) : ModelStreamEvent
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String?) : ModelStreamEvent
    data class Reasoning(val raw: JsonElement) : ModelStreamEvent
    data class UsageEvent(val usage: Usage) : ModelStreamEvent
    data class Done(val finishReason: String?) : ModelStreamEvent
    /** [retryAfterMs]: the provider's Retry-After (HTTP 429/503), when it sent one. */
    data class Error(val message: String, val httpCode: Int? = null, val retryable: Boolean = false, val retryAfterMs: Long? = null) : ModelStreamEvent
}

data class ModelResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
    val finishReason: String?,
    val reasoningDetails: JsonElement? = null,
)

data class ModelDescriptor(val id: String, val ownedBy: String? = null, val contextWindow: Int? = null)

data class EmbeddingRequest(val model: String, val input: List<String>)
data class EmbeddingResponse(val vectors: List<List<Float>>)

class ModelException(message: String, val httpCode: Int? = null, val retryable: Boolean = false, val retryAfterMs: Long? = null) : Exception(message)

/** §5.1 — every connected provider exposes this surface. */
interface ModelProvider {
    val id: String
    suspend fun listModels(): List<ModelDescriptor>
    fun chatStream(req: ModelRequest): Flow<ModelStreamEvent>
    suspend fun chat(req: ModelRequest): ModelResponse
    suspend fun embed(req: EmbeddingRequest): EmbeddingResponse
    /** Speech-to-text (OpenAI-compatible `/audio/transcriptions`, Whisper-style): WAV in, text out. */
    suspend fun transcribe(model: String, wav: ByteArray, language: String?): String = throw ModelException("Transcription non prise en charge par ce fournisseur")
    /** Text-to-speech (OpenAI-compatible `/audio/speech`): text in, WAV audio out. */
    suspend fun speech(model: String, text: String, voice: String?): ByteArray = throw ModelException("Synthèse vocale non prise en charge par ce fournisseur")
    /** Speech-to-text of an audio file of any format the provider accepts (mp3, m4a, ogg, webm, flac, wav…). */
    suspend fun transcribeFile(model: String, audio: ByteArray, fileName: String, mime: String, language: String?): String =
        if (mime == "audio/wav") transcribe(model, audio, language) else throw ModelException("Transcription de fichiers non prise en charge par ce fournisseur")
    /** Image generation (OpenAI-compatible `/images/generations`). */
    suspend fun generateImages(model: String, req: ImageRequest): List<GeneratedImage> = throw ModelException("Génération d'images non prise en charge par ce fournisseur")
    /** Image editing from reference images and an optional mask (OpenAI-compatible `/images/edits`). */
    suspend fun editImages(model: String, req: ImageEditRequest): List<GeneratedImage> = throw ModelException("Retouche d'images non prise en charge par ce fournisseur")
    /** Video generation job (OpenAI-compatible `/videos`): create, poll, download. */
    suspend fun createVideo(model: String, req: VideoRequest): VideoJob = throw ModelException("Génération vidéo non prise en charge par ce fournisseur")
    suspend fun videoJob(id: String): VideoJob = throw ModelException("Génération vidéo non prise en charge par ce fournisseur")
    suspend fun videoContent(id: String): ByteArray = throw ModelException("Génération vidéo non prise en charge par ce fournisseur")
}

/** Media requests (phase 25). Sizes are "WIDTHxHEIGHT" or "auto". */
data class ImageRequest(val prompt: String, val size: String = "auto", val n: Int = 1, val transparent: Boolean = false, val quality: String? = null)
data class ImageEditRequest(val prompt: String, val images: List<Pair<String, ByteArray>>, val mask: ByteArray? = null, val size: String = "auto", val n: Int = 1)
/** Either inline bytes or a URL the caller must fetch under the SSRF rule. */
data class GeneratedImage(val bytes: ByteArray?, val url: String?, val revisedPrompt: String? = null)
data class VideoRequest(val prompt: String, val seconds: Int = 4, val size: String? = null, val reference: ByteArray? = null, val referenceMime: String = "image/png")
data class VideoJob(val id: String, val status: String, val progress: Int? = null, val error: String? = null) {
    val done get() = status == "completed" || status == "succeeded"
    val failed get() = status == "failed" || status == "cancelled" || status == "canceled"
}
