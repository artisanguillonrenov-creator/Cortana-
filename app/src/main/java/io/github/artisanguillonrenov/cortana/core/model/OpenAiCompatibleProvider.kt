package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Redactor
import io.github.artisanguillonrenov.cortana.util.truncateBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class ProviderQuirks(
    val minTemperature: Double? = null,
    val forceN1: Boolean = false,
    val noLogprobs: Boolean = false,
    val noStreamUsage: Boolean = false,
    val usageInclude: Boolean = false,
    val noToolChoice: Boolean = false,
    val allowPrivateNetwork: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Champs ajoutés tels quels au corps de /chat/completions (ex. « lora » de llama-server). */
    val extraBody: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun parse(json: String?): ProviderQuirks =
            if (json.isNullOrBlank()) ProviderQuirks() else runCatching { AppJson.decodeFromString(serializer(), json) }.getOrElse { ProviderQuirks() }
    }
}

/**
 * One connector for every OpenAI-compatible endpoint (§5.2): POST /chat/completions (SSE when
 * stream=true), GET /models, POST /embeddings, Authorization: Bearer <key>.
 */
class OpenAiCompatibleProvider(
    override val id: String,
    baseUrl: String,
    private val apiKey: () -> String?,
    private val quirks: ProviderQuirks,
    private val client: OkHttpClient,
) : ModelProvider {

    private val base = baseUrl.trim().trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url("$base$path")
        apiKey()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        quirks.extraHeaders.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    override suspend fun listModels(): List<ModelDescriptor> {
        val resp = client.newCall(request("/models").get().build()).await()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, body)
            return parseModels(body)
        }
    }

    override fun chatStream(req: ModelRequest): Flow<ModelStreamEvent> = callbackFlow {
        val call = client.newCall(
            request("/chat/completions")
                .header("Accept", "text/event-stream")
                .post(buildBody(req.copy(stream = true)).toString().toRequestBody(jsonType))
                .build()
        )
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val e = httpError(resp.code, body, resp.header("Retry-After"))
                        send(ModelStreamEvent.Error(e.message ?: "HTTP ${resp.code}", resp.code, e.retryable, e.retryAfterMs))
                        return@use
                    }
                    val contentType = resp.header("Content-Type").orEmpty()
                    val source = resp.body?.source()
                    if (source == null) {
                        send(ModelStreamEvent.Error("Réponse vide", resp.code, true))
                        return@use
                    }
                    if (!contentType.contains("event-stream")) {
                        // Provider ignored stream=true: parse a regular completion.
                        val parsed = SseParser.parseCompletion(source.readUtf8())
                        if (parsed.text.isNotEmpty()) send(ModelStreamEvent.TextDelta(parsed.text))
                        parsed.toolCalls.forEachIndexed { i, tc -> send(ModelStreamEvent.ToolCallDelta(i, tc.id, tc.name, tc.arguments)) }
                        parsed.reasoningDetails?.let { send(ModelStreamEvent.Reasoning(it)) }
                        parsed.usage?.let { send(ModelStreamEvent.UsageEvent(it)) }
                        send(ModelStreamEvent.Done(parsed.finishReason))
                        return@use
                    }
                    var sawDone = false
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val events = SseParser.parseLine(line) ?: continue
                        for (ev in events) {
                            send(ev)
                            if (ev is ModelStreamEvent.Done && line.contains(SseParser.DONE)) sawDone = true
                        }
                        if (sawDone) break
                    }
                    if (!sawDone) send(ModelStreamEvent.Done(null))
                }
            } catch (e: InterruptedIOException) {
                if (!call.isCanceled()) send(ModelStreamEvent.Error("Délai dépassé : ${e.message}", null, true))
            } catch (e: IOException) {
                if (!call.isCanceled()) send(ModelStreamEvent.Error("Erreur réseau : ${e.message}", null, true))
            } catch (e: Exception) {
                if (!call.isCanceled()) send(ModelStreamEvent.Error("Erreur : ${e.message}", null, false))
            }
            close()
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    override suspend fun chat(req: ModelRequest): ModelResponse {
        val resp = client.newCall(
            request("/chat/completions").post(buildBody(req.copy(stream = false)).toString().toRequestBody(jsonType)).build()
        ).await()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, body)
            return SseParser.parseCompletion(body)
        }
    }

    override suspend fun embed(req: EmbeddingRequest): EmbeddingResponse {
        val body = buildJsonObject {
            put("model", req.model)
            putJsonArray("input") { req.input.forEach { add(it) } }
        }
        val resp = client.newCall(request("/embeddings").post(body.toString().toRequestBody(jsonType)).build()).await()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, text)
            val data = AppJson.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
            return EmbeddingResponse(data.map { d ->
                d.jsonObject["embedding"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() } ?: emptyList()
            })
        }
    }

    override suspend fun transcribe(model: String, wav: ByteArray, language: String?): String {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { language?.let { addFormDataPart("language", it.substringBefore('-')) } }
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val resp = client.newCall(request("/audio/transcriptions").post(form).build()).await()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, text)
            return (AppJson.parseToJsonElement(text).jsonObject["text"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: throw ModelException("Réponse de transcription sans texte")
        }
    }

    override suspend fun speech(model: String, text: String, voice: String?): ByteArray {
        val body = buildJsonObject { put("model", model); put("input", text); put("voice", voice ?: "alloy"); put("response_format", "wav") }
        val resp = client.newCall(request("/audio/speech").post(body.toString().toRequestBody(jsonType)).build()).await()
        resp.use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.body?.string().orEmpty())
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun transcribeFile(model: String, audio: ByteArray, fileName: String, mime: String, language: String?): String {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { language?.let { addFormDataPart("language", it.substringBefore('-')) } }
            .addFormDataPart("file", fileName, audio.toRequestBody(mime.toMediaType()))
            .build()
        val resp = client.newCall(request("/audio/transcriptions").post(form).build()).await()
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw httpError(r.code, text)
            return (AppJson.parseToJsonElement(text).jsonObject["text"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: throw ModelException("Réponse de transcription sans texte")
        }
    }

    private fun images(text: String): List<GeneratedImage> {
        val data = runCatching { AppJson.parseToJsonElement(text).jsonObject["data"]?.jsonArray }.getOrNull() ?: throw ModelException("Réponse d'images illisible")
        return data.map { d ->
            val o = d.jsonObject
            GeneratedImage(
                (o["b64_json"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrElse { throw ModelException("Image mal encodée") } },
                (o["url"] as? JsonPrimitive)?.contentOrNull, (o["revised_prompt"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** gpt-image models always answer in base64 and reject `response_format`; older ones need it. */
    private fun wantsB64(model: String) = !model.startsWith("gpt-image")

    override suspend fun generateImages(model: String, req: ImageRequest): List<GeneratedImage> {
        val body = buildJsonObject {
            put("model", model); put("prompt", req.prompt); put("n", req.n)
            if (req.size != "auto") put("size", req.size)
            if (req.transparent) put("background", "transparent")
            req.quality?.let { put("quality", it) }
            if (wantsB64(model)) put("response_format", "b64_json")
        }
        val resp = client.newCall(request("/images/generations").post(body.toString().toRequestBody(jsonType)).build()).await()
        resp.use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw httpError(r.code, t); return images(t) }
    }

    override suspend fun editImages(model: String, req: ImageEditRequest): List<GeneratedImage> {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("prompt", req.prompt).addFormDataPart("n", req.n.toString())
            .apply {
                if (req.size != "auto") addFormDataPart("size", req.size)
                if (wantsB64(model)) addFormDataPart("response_format", "b64_json")
                val field = if (req.images.size > 1) "image[]" else "image"
                req.images.forEach { (name, b) -> addFormDataPart(field, name, b.toRequestBody((if (name.endsWith(".png")) "image/png" else "image/jpeg").toMediaType())) }
                req.mask?.let { addFormDataPart("mask", "mask.png", it.toRequestBody("image/png".toMediaType())) }
            }.build()
        val resp = client.newCall(request("/images/edits").post(form).build()).await()
        resp.use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw httpError(r.code, t); return images(t) }
    }

    private fun job(text: String): VideoJob {
        val o = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw ModelException("Réponse vidéo illisible")
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: throw ModelException("Réponse vidéo sans identifiant")
        val err = (o["error"] as? JsonObject)?.let { (it["message"] as? JsonPrimitive)?.contentOrNull } ?: (o["error"] as? JsonPrimitive)?.contentOrNull
        return VideoJob(id, (o["status"] as? JsonPrimitive)?.contentOrNull ?: "queued", (o["progress"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt(), err)
    }

    override suspend fun createVideo(model: String, req: VideoRequest): VideoJob {
        val form = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("prompt", req.prompt).addFormDataPart("seconds", req.seconds.toString())
            .apply {
                req.size?.let { addFormDataPart("size", it) }
                req.reference?.let { addFormDataPart("input_reference", if (req.referenceMime == "image/png") "reference.png" else "reference.jpg", it.toRequestBody(req.referenceMime.toMediaType())) }
            }.build()
        val resp = client.newCall(request("/videos").post(form).build()).await()
        resp.use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw httpError(r.code, t); return job(t) }
    }

    override suspend fun videoJob(id: String): VideoJob {
        val resp = client.newCall(request("/videos/" + java.net.URLEncoder.encode(id, "UTF-8")).get().build()).await()
        resp.use { r -> val t = r.body?.string().orEmpty(); if (!r.isSuccessful) throw httpError(r.code, t); return job(t) }
    }

    override suspend fun videoContent(id: String): ByteArray {
        val resp = client.newCall(request("/videos/" + java.net.URLEncoder.encode(id, "UTF-8") + "/content").get().build()).await()
        resp.use { r ->
            if (!r.isSuccessful) throw httpError(r.code, r.body?.string().orEmpty())
            val body = r.body ?: throw ModelException("Vidéo vide")
            if (body.contentLength() > MAX_VIDEO) throw ModelException("Vidéo trop volumineuse")
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            body.byteStream().use { input -> while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_VIDEO) throw ModelException("Vidéo trop volumineuse") } }
            return out.toByteArray()
        }
    }

    fun buildBody(req: ModelRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        putJsonArray("messages") { req.messages.forEach { add(encodeMessage(it)) } }
        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                req.tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    })
                }
            }
            if (!quirks.noToolChoice) req.toolChoice?.let { put("tool_choice", it) }
        }
        if (req.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        req.maxTokens?.let { put("max_tokens", it) }
        req.temperature?.let { t ->
            val min = quirks.minTemperature
            put("temperature", if (min != null && t < min) min else t)
        }
        req.reasoningEffort?.let { put("reasoning_effort", it) }
        if (quirks.forceN1) put("n", 1)
        put("stream", req.stream)
        if (req.stream && !quirks.noStreamUsage) putJsonObject("stream_options") { put("include_usage", true) }
        if (quirks.usageInclude) putJsonObject("usage") { put("include", true) }
        quirks.extraBody.forEach { (k, v) -> put(k, v) }
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        if (!m.images.isNullOrEmpty()) {
            // OpenAI-compatible multimodal content: text part then image parts.
            put("content", buildJsonArray {
                m.content?.let { t -> add(buildJsonObject { put("type", "text"); put("text", t) }) }
                m.images.forEach { url -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }) }
            })
        } else if (m.content != null) put("content", m.content) else put("content", JsonPrimitive(null as String?))
        if (!m.toolCalls.isNullOrEmpty()) {
            put("tool_calls", buildJsonArray {
                m.toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        }
                    })
                }
            })
        }
        m.toolCallId?.let { put("tool_call_id", it) }
        m.name?.let { put("name", it) }
        m.reasoningDetails?.let { put("reasoning_details", it) }
    }

    private fun httpError(code: Int, body: String, retryAfter: String? = null): ModelException {
        val msg = runCatching {
            val o = AppJson.parseToJsonElement(body).jsonObject
            val err = o["error"]
            when (err) {
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> (o["message"] as? JsonPrimitive)?.contentOrNull ?: (o["detail"] as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull() ?: body.truncateBytes(300, "…")
        val text = when (code) {
            401, 403 -> "Clé refusée par le fournisseur (HTTP $code) : $msg"
            404 -> "Point d'accès ou modèle introuvable (HTTP 404) : $msg"
            429 -> "Trop de requêtes ou quota atteint (HTTP 429) : $msg"
            502, 504 -> "Serveur injoignable derrière la passerelle (HTTP $code) : serveur ou pod arrêté, ou en démarrage"
            503 -> "Serveur momentanément indisponible (HTTP 503) : modèle en cours de chargement ou serveur occupé"
            else -> "Erreur du fournisseur (HTTP $code) : $msg"
        }
        // Retry-After in seconds (the HTTP-date form is rare for model APIs and is ignored).
        val after = retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 0..3_600 }?.let { it * 1_000 }
        return ModelException(Redactor.redact(text), code, retryable = code == 429 || code >= 500 || code == 408, retryAfterMs = after)
    }

    companion object {
        const val MAX_VIDEO = 200L * 1024 * 1024

        fun parseModels(body: String): List<ModelDescriptor> {
            val root = AppJson.parseToJsonElement(body)
            val arr = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val ctx = listOf("context_length", "context_window", "max_model_len", "max_context_length")
                    .firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.intOrNull }
                ModelDescriptor(id, (o["owned_by"] as? JsonPrimitive)?.contentOrNull, ctx)
            }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(ModelException("Erreur réseau : ${e.message}", null, true))
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
