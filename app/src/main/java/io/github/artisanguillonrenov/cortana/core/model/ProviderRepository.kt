package io.github.artisanguillonrenov.cortana.core.model

import android.content.Context
import io.github.artisanguillonrenov.cortana.core.memory.ModelCapEntity
import io.github.artisanguillonrenov.cortana.core.memory.ProviderDao
import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import io.github.artisanguillonrenov.cortana.core.secrets.SecretStore
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val needsKey: Boolean = true,
    val notes: String = "",
    val keyUrl: String? = null,
    val quirks: JsonObject = JsonObject(emptyMap()),
)

@Serializable
private data class PresetFile(val version: Int, val verifiedAt: String? = null, val presets: List<ProviderPreset>)

/** Presets are data (assets/configs/providers.json), not code (§5.2). */
class ProviderPresets(context: Context) {
    val all: List<ProviderPreset> = runCatching {
        context.assets.open("configs/providers.json").bufferedReader().use { r ->
            AppJson.decodeFromString(PresetFile.serializer(), r.readText()).presets
        }
    }.getOrElse { listOf(ProviderPreset("custom", "Personnalisé", "https://")) }

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }
}

@Serializable
private data class CapRule(val match: String, val nativeTools: Boolean, val nativeJson: Boolean, val contextWindow: Int? = null)

@Serializable
private data class CapDefault(val nativeTools: Boolean, val nativeJson: Boolean, val contextWindow: Int)

@Serializable
private data class CapFile(val version: Int, val rules: List<CapRule>, val default: CapDefault)

data class ResolvedCaps(val contextWindow: Int, val nativeTools: Boolean, val nativeJson: Boolean, val vision: Boolean, val source: String)

/** §5.3 — bundled capability table + owner overrides + learned facts. */
class ModelCapabilities(context: Context, private val dao: ProviderDao) {
    private val file: CapFile? = runCatching {
        context.assets.open("configs/model_caps.json").bufferedReader().use { AppJson.decodeFromString(CapFile.serializer(), it.readText()) }
    }.getOrNull()
    private val rules = file?.rules?.mapNotNull { r -> runCatching { Regex(r.match) to r }.getOrNull() } ?: emptyList()

    fun bundled(modelId: String, listedContext: Int? = null): ResolvedCaps {
        val rule = rules.firstOrNull { it.first.containsMatchIn(modelId) }?.second
        val def = file?.default ?: CapDefault(false, false, 16384)
        return ResolvedCaps(
            contextWindow = listedContext ?: rule?.contextWindow ?: def.contextWindow,
            nativeTools = rule?.nativeTools ?: def.nativeTools,
            nativeJson = rule?.nativeJson ?: def.nativeJson,
            vision = false,
            source = if (rule != null) "bundled" else "default",
        )
    }

    suspend fun resolve(providerId: String, modelId: String): ResolvedCaps {
        val b = bundled(modelId)
        val o = dao.cap(providerId, modelId) ?: return b
        return ResolvedCaps(
            contextWindow = o.contextWindow ?: b.contextWindow,
            nativeTools = when (o.nativeTools) { 1 -> true; 0 -> false; else -> b.nativeTools },
            nativeJson = when (o.nativeJson) { 1 -> true; 0 -> false; else -> b.nativeJson },
            vision = o.vision == 1,
            source = o.source,
        )
    }

    suspend fun override(providerId: String, modelId: String): ModelCapEntity? = dao.cap(providerId, modelId)

    suspend fun setToolMode(providerId: String, modelId: String, nativeTools: Int, source: String = "owner") {
        val cur = dao.cap(providerId, modelId)
        dao.upsertCap(
            (cur ?: ModelCapEntity(providerId, modelId, null, -1, -1, -1, source)).copy(nativeTools = nativeTools, source = source)
        )
    }

    suspend fun rememberContext(providerId: String, modelId: String, contextWindow: Int?) {
        if (contextWindow == null) return
        val cur = dao.cap(providerId, modelId)
        dao.upsertCap((cur ?: ModelCapEntity(providerId, modelId, null, -1, -1, -1, "learned")).copy(contextWindow = contextWindow))
    }
}

class ProviderRepository(
    private val dao: ProviderDao,
    private val secrets: SecretStore,
    private val client: OkHttpClient,
    val presets: ProviderPresets,
) {
    private val modelCache = ConcurrentHashMap<String, List<ModelDescriptor>>()
    private val _models = MutableStateFlow<Map<String, List<ModelDescriptor>>>(emptyMap())
    val models: StateFlow<Map<String, List<ModelDescriptor>>> = _models

    fun observe(): Flow<List<ProviderEntity>> = dao.observeAll()
    suspend fun all(): List<ProviderEntity> = dao.all()
    suspend fun get(id: String): ProviderEntity? = dao.get(id)

    suspend fun create(preset: ProviderPreset, displayName: String, baseUrl: String, apiKey: String?): ProviderEntity {
        val handle = apiKey?.takeIf { it.isNotBlank() }?.let { k -> secrets.newHandle().also { secrets.put(it, k.trim()) } }
        val order = (dao.all().maxOfOrNull { it.fallbackOrder } ?: -1) + 1
        val p = ProviderEntity(
            id = Ids.new(), displayName = displayName.ifBlank { preset.displayName }, presetId = preset.id,
            baseUrl = baseUrl.trim().trimEnd('/'), apiKeyHandle = handle,
            quirksJson = preset.quirks.takeIf { it.isNotEmpty() }?.toString(),
            enabled = true, fallbackOrder = order, allowFallback = false, defaultModelId = null,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(p)
        return p
    }

    /** [newApiKey] null = keep the current key; blank = remove it. */
    suspend fun update(p: ProviderEntity, newApiKey: String?) {
        var handle = p.apiKeyHandle
        if (newApiKey != null) {
            if (newApiKey.isBlank()) {
                secrets.remove(handle); handle = null
            } else {
                if (handle == null) handle = secrets.newHandle()
                secrets.put(handle, newApiKey.trim())
            }
        }
        dao.upsert(p.copy(apiKeyHandle = handle, baseUrl = p.baseUrl.trim().trimEnd('/')))
        modelCache.remove(p.id)
    }

    suspend fun delete(id: String) {
        dao.get(id)?.let { secrets.remove(it.apiKeyHandle) }
        dao.delete(id)
        modelCache.remove(id)
        _models.value = _models.value - id
    }

    suspend fun move(id: String, delta: Int) {
        val list = dao.all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val a = list[i]; list[i] = list[j]; list[j] = a
        list.forEachIndexed { idx, p -> if (p.fallbackOrder != idx) dao.upsert(p.copy(fallbackOrder = idx)) }
    }

    fun hasKey(p: ProviderEntity): Boolean = secrets.has(p.apiKeyHandle)

    fun providerFor(p: ProviderEntity): ModelProvider =
        OpenAiCompatibleProvider(p.id, p.baseUrl, { secrets.get(p.apiKeyHandle) }, ProviderQuirks.parse(p.quirksJson), client)

    suspend fun providerFor(id: String): ModelProvider? = dao.get(id)?.let(::providerFor)

    suspend fun listModels(id: String, refresh: Boolean = false): List<ModelDescriptor> {
        if (!refresh) modelCache[id]?.let { return it }
        val p = providerFor(id) ?: return emptyList()
        val list = p.listModels()
        modelCache[id] = list
        _models.value = _models.value + (id to list)
        return list
    }

    fun cachedModels(id: String): List<ModelDescriptor> = modelCache[id] ?: emptyList()
}
