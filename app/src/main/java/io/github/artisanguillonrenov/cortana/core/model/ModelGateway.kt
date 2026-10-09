package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.contracts.PrivacyLevel
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.memory.SessionEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.memory.UsageDao
import io.github.artisanguillonrenov.cortana.core.memory.UsageEntity
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.ZoneId

/** [localOnly]: privacy-constrained route — fallback may only use on-device/LAN providers. */
/** [local]: the provider runs on the tablet or the local network (data does not leave it). */
data class ModelRoute(val providerId: String, val modelId: String, val providerName: String = "", val localOnly: Boolean = false, val local: Boolean = localOnly)

/** What a request needs from a model (doc 02 §models: privacy-aware, coding and vision routing). */
data class RouteNeed(
    val privacy: PrivacyLevel = PrivacyLevel.NORMAL,
    val coding: Boolean = false,
    val vision: Boolean = false,
)

data class GatewayResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
    val route: ModelRoute?,
    val emulated: Boolean,
    /** Emulated reply that looked like a tool call but could not be parsed. */
    val malformed: Boolean = false,
    val reasoning: JsonElement? = null,
    val error: String? = null,
    val fellBackFrom: String? = null,
    /** HTTP status of a failed call (413: the request is too large — never retry it unchanged). */
    val httpCode: Int? = null,
    /** Provider's Retry-After for a 429/503, in ms. */
    val retryAfterMs: Long? = null,
    /** Why the provider ended the answer (stop | length | tool_calls…), when it said so. */
    val finishReason: String? = null,
    /** Something the owner should know about this call (e.g. a pod address updated automatically). */
    val notice: String? = null,
)

/**
 * §5 — the only component that performs inference HTTP calls. Resolves a route (session choice →
 * default provider → fallback order), applies native or emulated tool calling, streams, records
 * usage, and falls back only to providers the owner marked usable.
 */
class ModelGateway(
    private val providers: ProviderRepository,
    val capabilities: ModelCapabilities,
    private val usageDao: UsageDao,
    private val settings: SettingsRepository,
    val health: ProviderHealth = ProviderHealth(),
    /** Every model call is a `gen_ai.*` span (phase 28): model, provider, role, tokens, outcome — never content. */
    private val tracer: io.github.artisanguillonrenov.cortana.core.observability.Tracer = io.github.artisanguillonrenov.cortana.core.observability.Tracer(),
) {
    /** Finds a migrated RunPod pod again (set by the container once the resolver exists): new provider + what changed. */
    var addressRecovery: (suspend (ProviderEntity) -> Pair<ProviderEntity, RunPodResolver.Change>?)? = null

    private fun genAi(op: String, p: ProviderEntity, model: String, role: String) = mapOf(
        "gen_ai.operation.name" to op, "gen_ai.provider.name" to p.presetId, "gen_ai.request.model" to model, "cortana.role" to role, "cortana.provider" to p.displayName,
    )

    /**
     * Route resolution, in order: privacy filter (LOCAL_ONLY or the owner's "local only" mode keeps
     * only on-device/LAN providers) → the owner's coding route for coding work → the session's model
     * (new sessions copy the default) if it can do what is needed → the vision route or any
     * vision-capable model when vision is needed → default provider → first enabled provider.
     */
    suspend fun resolveRoute(session: SessionEntity?, need: RouteNeed = RouteNeed()): ModelRoute? {
        val s = settings.current
        val localOnly = need.privacy == PrivacyLevel.LOCAL_ONLY || s.privacyMode == PRIVACY_LOCAL_ONLY
        val all = providers.all().filter { it.enabled && (!localOnly || isLocal(it)) }
        fun route(p: ProviderEntity, model: String) = ModelRoute(p.id, model, p.displayName, localOnly, localOnly || isLocal(p))
        suspend fun ok(p: ProviderEntity, model: String) = !need.vision || capabilities.resolve(p.id, model).vision
        fun ref(r: String?): Pair<ProviderEntity, String>? {
            val (pid, model) = r?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
            return all.firstOrNull { it.id == pid }?.let { it to model }
        }
        if (need.coding && !need.vision) ref(s.codingRoute)?.let { (p, m) -> return route(p, m) }
        session?.providerId?.let { pid ->
            val p = all.firstOrNull { it.id == pid }
            val model = session.modelId ?: p?.defaultModelId
            if (p != null && model != null && ok(p, model)) return route(p, model)
        }
        if (need.vision) {
            ref(s.visionRoute)?.let { (p, m) -> return route(p, m) }
            for (p in all) { val m = p.defaultModelId ?: continue; if (capabilities.resolve(p.id, m).vision) return route(p, m) }
            return null // no vision-capable model: callers fall back to text-only paths (OCR)
        }
        val def = s.defaultProviderId?.let { id -> all.firstOrNull { it.id == id } }
        if (def?.defaultModelId != null) return route(def, def.defaultModelId)
        val first = all.firstOrNull { it.defaultModelId != null } ?: return null
        return route(first, first.defaultModelId!!)
    }

    /** An explicit "providerId/model" route chosen by the owner (voice engines), filtered by the privacy mode. */
    suspend fun routeFor(ref: String?): ModelRoute? {
        val (pid, model) = ref?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val localOnly = settings.current.privacyMode == PRIVACY_LOCAL_ONLY
        val p = providers.all().firstOrNull { it.id == pid && it.enabled && (!localOnly || isLocal(it)) } ?: return null
        return ModelRoute(p.id, model, p.displayName, localOnly, localOnly || isLocal(p))
    }

    /** Embeddings through the one inference gateway (LAW-001). Throws [ModelException] on failure. */
    suspend fun embed(route: ModelRoute, texts: List<String>): List<FloatArray> {
        val p = providers.all().firstOrNull { it.id == route.providerId } ?: throw ModelException("Fournisseur d'embeddings introuvable")
        if (route.localOnly && !isLocal(p)) throw ModelException("Fournisseur non local refusé (mode confidentiel)")
        if (!health.allow(p.id)) throw ModelException("${p.displayName} temporairement indisponible", retryable = true)
        return tracer.span("gen_ai.embeddings", null, genAi("embeddings", p, route.modelId, "embedding") + ("cortana.inputs" to texts.size.toString())) { embedCall(p, route, texts) }
    }

    private suspend fun embedCall(p: ProviderEntity, route: ModelRoute, texts: List<String>): List<FloatArray> {
        return try {
            val res = providers.providerFor(p).embed(EmbeddingRequest(route.modelId, texts))
            health.onSuccess(p.id)
            recordUsage(route, "embedding", null, texts.map { ChatMessage("user", it) }, 0)
            res.vectors.map { it.toFloatArray() }
        } catch (e: ModelException) {
            health.onFailure(p.id, e.message, e.retryable); throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health.onFailure(p.id, e.message, true); throw ModelException(e.message ?: "embeddings", retryable = true)
        }
    }

    /** Speech-to-text through the one gateway (LAW-001); privacy is enforced by the route. */
    suspend fun transcribe(route: ModelRoute, wav: ByteArray, language: String?): String = media(route, "stt") { it.transcribe(route.modelId, wav, language) }

    /** Text-to-speech through the one gateway (LAW-001). */
    suspend fun speech(route: ModelRoute, text: String, voice: String?): ByteArray = media(route, "tts") { it.speech(route.modelId, text, voice) }

    /** Media services (phase 25) through the one gateway: same privacy, health and spending rules as chat. */
    suspend fun transcribeFile(route: ModelRoute, audio: ByteArray, fileName: String, mime: String, language: String?): String =
        media(route, "stt") { it.transcribeFile(route.modelId, audio, fileName, mime, language) }
    suspend fun generateImages(route: ModelRoute, req: ImageRequest): List<GeneratedImage> = media(route, "image") { it.generateImages(route.modelId, req) }
    suspend fun editImages(route: ModelRoute, req: ImageEditRequest): List<GeneratedImage> = media(route, "image") { it.editImages(route.modelId, req) }
    suspend fun createVideo(route: ModelRoute, req: VideoRequest): VideoJob = media(route, "video") { it.createVideo(route.modelId, req) }
    suspend fun videoJob(route: ModelRoute, id: String): VideoJob = media(route, "video.status", record = false) { it.videoJob(id) }
    suspend fun videoContent(route: ModelRoute, id: String): ByteArray = media(route, "video.content", record = false) { it.videoContent(id) }

    private suspend fun <T> media(route: ModelRoute, role: String, record: Boolean = true, call: suspend (ModelProvider) -> T): T {
        val p = providers.all().firstOrNull { it.id == route.providerId && it.enabled } ?: throw ModelException("Fournisseur introuvable ou désactivé")
        if ((route.localOnly || settings.current.privacyMode == PRIVACY_LOCAL_ONLY) && !isLocal(p)) throw ModelException("Fournisseur non local refusé (mode confidentiel)")
        if (!health.allow(p.id)) throw ModelException("${p.displayName} temporairement indisponible", retryable = true)
        if (record && overProviderCap(p)) throw ModelException("Plafond de dépense atteint pour ${p.displayName}.")
        return tracer.span("gen_ai.$role", null, genAi(role, p, route.modelId, role)) { mediaCall(p, route, role, record, call) }
    }

    private suspend fun <T> mediaCall(p: ProviderEntity, route: ModelRoute, role: String, record: Boolean, call: suspend (ModelProvider) -> T): T {
        return try {
            call(providers.providerFor(p)).also { health.onSuccess(p.id); if (record) recordUsage(route, role, null, emptyList(), 0) }
        } catch (e: ModelException) {
            health.onFailure(p.id, e.message, e.retryable); throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health.onFailure(p.id, e.message, true); throw ModelException(e.message ?: role, retryable = true)
        }
    }

    suspend fun spentToday(): Double = usageDao.costSince(startOfToday())

    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun complete(
        route: ModelRoute,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
        role: String = "agent",
        allowFallback: Boolean = true,
        jsonMode: Boolean = false,
        /** Per-call caps (council agents): null = the owner's global settings. */
        maxTokens: Int? = null,
        temperature: Double? = null,
        /** Per-role reasoning effort (low|medium|high|xhigh); null = not sent. */
        reasoningEffort: String? = null,
        /**
         * Called when a failed attempt had already streamed text and another provider takes over: the
         * text shown so far belongs to an answer that will not be kept (no duplicated live text).
         */
        onReset: () -> Unit = {},
    ): GatewayResult {
        val all = providers.all()
        val primary = all.firstOrNull { it.id == route.providerId }
            ?: return GatewayResult("", emptyList(), null, route, false, error = "Fournisseur introuvable. Configurez-en un dans « Fournisseurs ».")
        if (route.localOnly && !isLocal(primary)) {
            return GatewayResult("", emptyList(), null, route, false, error = "Mode confidentiel : ${primary.displayName} n'est pas un fournisseur local.")
        }
        val candidates = mutableListOf(primary to route.modelId)
        if (allowFallback) {
            all.filter { it.id != primary.id && it.enabled && it.allowFallback && it.defaultModelId != null && (!route.localOnly || isLocal(it)) }
                .sortedBy { it.fallbackOrder }
                .forEach { candidates += it to it.defaultModelId!! }
        }
        // Open circuits are skipped while another candidate exists; a lone provider is still tried.
        val usable = candidates.filter { health.allow(it.first.id) }.ifEmpty { candidates }
        var lastError: String? = null
        var lastFailure: GatewayResult? = null
        var emitted = false
        val tracked: (String) -> Unit = { d -> if (d.isNotEmpty()) emitted = true; onDelta(d) }
        for ((p, model) in usable) {
            if (emitted) { onReset(); emitted = false }
            if (overProviderCap(p)) {
                lastError = "Plafond de dépense atteint pour ${p.displayName}."
                continue
            }
            val r = ModelRoute(p.id, model, p.displayName, route.localOnly)
            var res = attempt(p, r, messages, tools, tracked, role, jsonMode, maxTokens, temperature, reasoningEffort)
            var notice: String? = null
            // A RunPod pod migrated to another GPU has a new address: found again by its name, then retried once.
            if (res.error != null && !emitted && res.result.httpCode != 401 && res.result.httpCode != 403 && res.result.httpCode != 413) {
                addressRecovery?.let { recover -> runCatching { recover(p) }.getOrNull() }?.let { (moved, change) ->
                    notice = "Le pod « ${change.podName} » a changé d'adresse : ${p.displayName} mis à jour automatiquement."
                    res = attempt(moved, r, messages, tools, tracked, role, jsonMode, maxTokens, temperature, reasoningEffort)
                }
            }
            if (res.error == null) {
                health.onSuccess(p.id)
                val ok = if (p.id != primary.id) res.result.copy(fellBackFrom = primary.displayName) else res.result
                return if (notice != null) ok.copy(notice = notice) else ok
            }
            health.onFailure(p.id, res.error, res.retryable)
            lastError = res.error
            lastFailure = res.result
            if (!res.retryable) return res.result
            CLog.w("provider ${p.displayName} failed, trying fallback: ${res.error}")
        }
        // The last failure's HTTP status and Retry-After stay visible to callers that recover themselves (council 429/413).
        return GatewayResult("", emptyList(), null, route, false, error = lastError ?: "Aucun fournisseur disponible.",
            httpCode = lastFailure?.httpCode, retryAfterMs = lastFailure?.retryAfterMs)
    }

    companion object {
        const val PRIVACY_STANDARD = "standard"
        const val PRIVACY_LOCAL_ONLY = "local_only"

        /** On-device or LAN endpoint: the "Serveur local" preset, loopback, private/link-local IPs, .local/.lan/.home.arpa names. */
        fun isLocal(p: ProviderEntity): Boolean = p.presetId == "local" || isLocalUrl(p.baseUrl)

        /** A loopback, private, link-local or .local/.lan/.home.arpa/.internal address. */
        fun isLocalUrl(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal")) return true
            val literal = host.matches(Regex("[0-9.]+")) || host.contains(':')
            if (!literal) return false
            val a = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
            return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
                (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||
                (a is java.net.Inet4Address && (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xc0) == 64)
        }
    }

    private data class Attempt(val result: GatewayResult, val retryable: Boolean) {
        val error get() = result.error
    }

    private suspend fun overProviderCap(p: ProviderEntity): Boolean {
        val cap = p.spendCapUsd ?: return false
        return usageDao.costSinceForProvider(startOfToday(), p.id) >= cap
    }

    private suspend fun attempt(
        p: ProviderEntity,
        route: ModelRoute,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
        role: String,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null,
    ): Attempt = tracer.span("gen_ai.chat", null, genAi("chat", p, route.modelId, role) + ("gen_ai.request.tools" to tools.size.toString())) { span ->
        attemptCall(p, route, messages, tools, onDelta, role, jsonMode, maxTokens, temperature, reasoningEffort).also { a ->
            a.result.usage?.inputTokens?.let { span.attr("gen_ai.usage.input_tokens", it.toString()) }
            a.result.usage?.outputTokens?.let { span.attr("gen_ai.usage.output_tokens", it.toString()) }
            a.result.usage?.costUsd?.let { span.attr("cortana.cost_usd", "%.6f".format(java.util.Locale.ROOT, it)) }
            if (a.result.emulated) span.attr("cortana.tools_emulated", "true")
            if (a.result.toolCalls.isNotEmpty()) span.attr("cortana.tool_calls", a.result.toolCalls.size.toString())
            a.result.httpCode?.let { span.attr("http.response.status_code", it.toString()) }
            if (a.error != null) span.error(if (a.retryable) "provider.retryable" else "provider")
        }
    }

    private suspend fun attemptCall(
        p: ProviderEntity,
        route: ModelRoute,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit,
        role: String,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null,
    ): Attempt {
        val caps = capabilities.resolve(p.id, route.modelId)
        var provider = providers.providerFor(p)
        val native = tools.isEmpty() || caps.nativeTools
        val json = jsonMode && caps.nativeJson
        var first = stream(provider, route, messages, tools, emulate = !native, onDelta, role, json, maxTokens, temperature, reasoningEffort)
        // Some OpenAI-compatible servers reject stream_options: learn it once and retry without.
        if (first.httpCode == 400 && first.result.error?.contains("stream_options", ignoreCase = true) == true) {
            val q = ProviderQuirks.parse(p.quirksJson).copy(noStreamUsage = true)
            val updated = p.copy(quirksJson = AppJson.encodeToString(ProviderQuirks.serializer(), q))
            providers.update(updated, null)
            provider = providers.providerFor(updated)
            first = stream(provider, route, messages, tools, emulate = !native, onDelta, role, json, maxTokens, temperature, reasoningEffort)
        }
        // Native tools rejected by the server → learn it and retry once with emulation.
        if (native && tools.isNotEmpty() && first.result.error != null && first.httpCode == 400 &&
            first.result.error.contains("tool", ignoreCase = true)
        ) {
            capabilities.setToolMode(p.id, route.modelId, 0, source = "learned")
            return stream(provider, route, messages, tools, emulate = true, onDelta, role, json, maxTokens, temperature, reasoningEffort).let { Attempt(it.result, it.retryable) }
        }
        return Attempt(first.result, first.retryable)
    }

    private data class StreamOutcome(val result: GatewayResult, val retryable: Boolean, val httpCode: Int?)

    private suspend fun stream(
        provider: ModelProvider,
        route: ModelRoute,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        emulate: Boolean,
        onDelta: (String) -> Unit,
        role: String,
        jsonMode: Boolean = false,
        maxTokens: Int? = null,
        temperature: Double? = null,
        reasoningEffort: String? = null,
    ): StreamOutcome {
        val s = settings.current
        val outCap = maxTokens ?: s.maxOutputTokens
        val temp = temperature ?: s.temperature
        val req = if (emulate && tools.isNotEmpty()) {
            ModelRequest(route.modelId, ToolCallEmulation.transform(messages, tools), maxTokens = outCap, temperature = temp, reasoningEffort = reasoningEffort)
        } else {
            ModelRequest(route.modelId, messages, tools = tools, toolChoice = if (tools.isNotEmpty()) "auto" else null, jsonMode = jsonMode, maxTokens = outCap, temperature = temp, reasoningEffort = reasoningEffort)
        }
        val text = StringBuilder()
        val acc = ToolCallAccumulator()
        var usage: Usage? = null
        var finish: String? = null
        var reasoning: MutableList<JsonElement>? = null
        var holding = false
        var flushed = 0
        try {
            provider.chatStream(req).collect { ev ->
                when (ev) {
                    is ModelStreamEvent.TextDelta -> {
                        text.append(ev.text)
                        if (emulate && tools.isNotEmpty()) {
                            // Don't stream what is probably a JSON tool call to the owner.
                            if (!holding && ToolCallEmulation.looksLikeToolJson(text.toString())) holding = true
                            if (!holding) {
                                onDelta(text.substring(flushed)); flushed = text.length
                            }
                        } else onDelta(ev.text)
                    }
                    is ModelStreamEvent.ToolCallDelta -> acc.add(ev)
                    is ModelStreamEvent.Reasoning -> {
                        val list = reasoning ?: mutableListOf<JsonElement>().also { reasoning = it }
                        if (ev.raw is JsonArray) list.addAll(ev.raw) else list.add(ev.raw)
                    }
                    is ModelStreamEvent.UsageEvent -> usage = ev.usage
                    is ModelStreamEvent.Done -> if (ev.finishReason != null) finish = ev.finishReason
                    is ModelStreamEvent.Error -> throw ModelException(ev.message, ev.httpCode, ev.retryable, ev.retryAfterMs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            return StreamOutcome(GatewayResult("", emptyList(), null, route, emulate, error = e.message, httpCode = e.httpCode, retryAfterMs = e.retryAfterMs), e.retryable, e.httpCode)
        } catch (e: Exception) {
            return StreamOutcome(GatewayResult("", emptyList(), null, route, emulate, error = "Erreur : ${e.message}"), true, null)
        }
        recordUsage(route, role, usage, messages, text.length)
        val reasoningEl = reasoning?.let { JsonArray(it) }
        if (emulate && tools.isNotEmpty()) {
            val parsed = ToolCallEmulation.parse(text.toString(), tools.map { it.name }.toSet())
            if (parsed.calls.isEmpty() && holding && !parsed.malformed) onDelta(text.substring(flushed))
            return StreamOutcome(
                GatewayResult(
                    text = if (parsed.calls.isNotEmpty()) parsed.preamble else text.toString(),
                    toolCalls = parsed.calls, usage = usage, route = route, emulated = true,
                    malformed = parsed.malformed, reasoning = reasoningEl, finishReason = finish,
                ), false, null
            )
        }
        return StreamOutcome(GatewayResult(text.toString(), acc.build(), usage, route, false, reasoning = reasoningEl, finishReason = finish), false, null)
    }

    data class StructuredResult(val json: kotlinx.serialization.json.JsonObject?, val error: String?, val attempts: Int, val route: ModelRoute?, val usage: Usage? = null)

    /**
     * Structured output everywhere (doc 10 §6): asks for JSON, validates it with [validator]
     * (JSON-schema + domain checks) and repairs within [maxRepairs]. Invalid output is never used.
     */
    suspend fun completeStructured(
        route: ModelRoute,
        system: String,
        user: String,
        schemaText: String,
        validator: (kotlinx.serialization.json.JsonObject) -> List<String>,
        role: String,
        maxRepairs: Int = 2,
        images: List<String> = emptyList(),
    ): StructuredResult {
        val msgs = mutableListOf(
            ChatMessage("system", system + "\n\nRéponds UNIQUEMENT avec un objet JSON valide conforme à ce schéma, sans texte autour :\n" + schemaText),
            ChatMessage("user", user, images = images.ifEmpty { null }),
        )
        var lastError = "aucune réponse"
        var usedRoute: ModelRoute? = route
        for (attempt in 0..maxRepairs) {
            val res = complete(route, msgs, emptyList(), onDelta = {}, role = role, jsonMode = true)
            usedRoute = res.route ?: usedRoute
            if (res.error != null) return StructuredResult(null, res.error, attempt + 1, usedRoute)
            val obj = ToolCallEmulation.extractJsonObjects(res.text).firstNotNullOfOrNull { (_, j) ->
                runCatching { io.github.artisanguillonrenov.cortana.util.AppJson.parseToJsonElement(j) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            }
            val errors = if (obj == null) listOf("aucun objet JSON trouvé") else validator(obj)
            if (obj != null && errors.isEmpty()) return StructuredResult(obj, null, attempt + 1, usedRoute, res.usage)
            lastError = errors.joinToString("; ")
            msgs += ChatMessage("assistant", res.text.take(4000))
            msgs += ChatMessage("user", "Ta réponse est invalide : $lastError. Renvoie uniquement l'objet JSON corrigé.")
        }
        return StructuredResult(null, "sortie structurée invalide après ${maxRepairs + 1} essais : $lastError", maxRepairs + 1, usedRoute)
    }

    private suspend fun recordUsage(route: ModelRoute, role: String, usage: Usage?, messages: List<ChatMessage>, outChars: Int) {
        runCatching {
            val estimated = usage?.inputTokens == null
            val inTok = usage?.inputTokens ?: (messages.sumOf { (it.content?.length ?: 0) + 20 } / 4)
            val outTok = usage?.outputTokens ?: (outChars / 4)
            usageDao.insert(
                UsageEntity(Ids.new(), System.currentTimeMillis(), route.providerId, route.modelId, role, inTok, outTok, usage?.costUsd, estimated)
            )
        }
    }
}
