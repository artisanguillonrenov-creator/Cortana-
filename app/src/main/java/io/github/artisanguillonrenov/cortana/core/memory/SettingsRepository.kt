package io.github.artisanguillonrenov.cortana.core.memory

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class AppSettings(
    /** active | halted — global kill switch state (§9.4). */
    val autonomyState: String = "active",
    val defaultProviderId: String? = null,
    /** How extracted memories are stored: confirm (pending) | auto */
    val memoryWriteMode: String = "confirm",
    val maxToolCallsPerTask: Int = 30,
    val maxModelCallsPerTask: Int = 20,
    val maxTaskMinutes: Int = 15,
    val dailySpendCapUsd: Double? = null,
    val maxOutputTokens: Int = 2048,
    val temperature: Double = 0.4,
    /** Owner-edited UI-risk patterns (JSON). Null = bundled defaults. */
    val uiRiskPatternsJson: String? = null,
    /** Sensitive apps the owner explicitly allowed for automation (package names). */
    val sensitiveAllowlist: List<String> = emptyList(),
    /** Capabilities the owner granted "always allow" for L2 (never L3). */
    val grants: List<String> = emptyList(),
    /** Destinations (hosts, share targets) already used/approved by the owner (§9.3). */
    val knownDestinations: List<String> = emptyList(),
    /** duckduckgo | brave | searxng */
    val searchProvider: String = "duckduckgo",
    val searchBaseUrl: String? = null,
    val searchKeyHandle: String? = null,
    val workingFolderUri: String? = null,
    val ttsEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val locale: String = "fr-FR",
    val allowPrivateNetworkFetch: Boolean = false,
    val defaultToolset: String = "full",
    // ---- VNext runtime ----
    /** auto (DAG only for multi-step objectives) | interactive (never DAG) | always */
    val planningMode: String = "auto",
    /** Dynamic tool discovery: at most this many tool definitions are sent to the model at first. */
    val maxToolsOffered: Int = 24,
    /** Compaction of turns that leave the context window: "extractive" (no model call) or "model". */
    val contextSummaryMode: String = "extractive",
    /** "standard" or "local_only" (only on-device/LAN model providers are used). */
    val privacyMode: String = "standard",
    /** "providerId/modelId" preferred for coding tasks, vision, and embeddings (null = default / local embeddings). */
    val codingRoute: String? = null,
    val visionRoute: String? = null,
    val embeddingRoute: String? = null,
    /** Media (phase 25): "providerId/modelId" for image generation/editing and for video generation (null = unavailable). */
    val imageRoute: String? = null,
    val videoRoute: String? = null,
    /** Memory retention (doc 04 §10): episodic records and unconfirmed facts are purged after these delays. */
    val episodicRetentionDays: Int = 90,
    val pendingRetentionDays: Int = 30,
    /** Git: branches that never receive a merge or push without the owner's biometric confirmation. */
    val protectedBranches: List<String> = listOf("main", "master"),
    /** Git credentials per host: host → "username|secretHandle" (the token itself lives in the SecretStore). */
    val gitCredentials: Map<String, String> = emptyMap(),
    /** Identity used for Cortana's local commits. */
    val gitAuthorName: String = "Cortana",
    val gitAuthorEmail: String = "cortana@localhost",
    /** Software Factory repair loop (doc 03 §15): max repairs per task, and how often the same failure may recur before replanning/asking. */
    val maxRepairIterations: Int = 3,
    val repairSameFailureLimit: Int = 2,
    /** Default execution backend for build/test/exec when the call names none: auto | android-local | worker id. */
    val devBackend: String = "auto",
    /** Vision fallback for screen automation (phase 16): off | local (capture + on-device OCR) | remote (+ multimodal model on masked captures). */
    val visionFallback: String = "local",
    /** Voice VNext (phase 17): engines (android = on the tablet | remote = provider route), voice, wake phrase, hands-free limits. */
    val voiceLanguage: String = "fr-FR",
    val sttMode: String = "android",
    val sttRoute: String? = null,
    val ttsMode: String = "android",
    val ttsRoute: String? = null,
    val ttsVoice: String? = null,
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = "Cortana",
    val handsFreeTimeoutSec: Int = 120,
    val bargeIn: Boolean = true,
    /** Communications (phase 18): apps whose notifications Cortana may see, content to a non-local model, triggers, clipboard clearing. */
    val notificationApps: List<String> = emptyList(),
    val notificationContentToModel: Boolean = false,
    val notificationTriggers: List<io.github.artisanguillonrenov.cortana.core.comms.NotificationTriggerSpec> = emptyList(),
    /** MCP servers the owner added (tokens as secret handles only) — phase 20. */
    val mcpServers: List<io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig> = emptyList(),
    /** External agents (A2A 1.0) the owner added — phase 21. */
    val a2aAgents: List<io.github.artisanguillonrenov.cortana.core.a2a.A2aAgentConfig> = emptyList(),
    val clipboardClearSec: Int = 60,
    val maxPlanSteps: Int = 8,
    val maxReplans: Int = 2,
    /** Resume safe interrupted tasks automatically after a restart (uncertain ones always wait). */
    val autoResumeTasks: Boolean = true,
    /** Allow a model-based verification when no deterministic evidence exists (DAG steps). */
    val modelVerification: Boolean = true,
    /** Owner-approved shortcuts from improvement proposals (phase 27): exact phrase → one read-only call. */
    val ownerShortcuts: List<io.github.artisanguillonrenov.cortana.core.improvement.OwnerShortcut> = emptyList(),
    /** Phase 28: optional OTLP/HTTP trace export to the owner's collector (off by default). */
    val otlpEnabled: Boolean = false,
    val otlpEndpoint: String? = null,
    /** Secret handle holding one "Name: value" header (e.g. an API key for the collector). */
    val otlpHeaderHandle: String? = null,
    val spanRetentionDays: Int = 7,
    /** Phase 30: the owner's release channel (signed manifest URL) and whether to check it daily. */
    val updateManifestUrl: String? = null,
    val updateAutoCheck: Boolean = false,
    /** Phase 32 network egress policy: standard (taint rule) | confirm_new | known_only; hosts never reached. */
    val egressMode: String = "standard",
    val egressBlockedHosts: List<String> = emptyList(),
    /** Cognitive Council Engine (D-20260929-067): off by default until validated on the tablet. */
    val council: io.github.artisanguillonrenov.cortana.core.council.CouncilConfig = io.github.artisanguillonrenov.cortana.core.council.CouncilConfig(),
    val councilPrefs: io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs = io.github.artisanguillonrenov.cortana.core.council.CouncilPrefs(),
    /** Chat Workspace preferences (D-20260930-068): simple by default, advanced options on demand. */
    val chat: io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs = io.github.artisanguillonrenov.cortana.core.chat.ChatPrefs(),
    /** Last version of the preconfigured RunPod pod applied ([io.github.artisanguillonrenov.cortana.core.model.PreconfiguredPod]); 0 = never. */
    val preconfiguredPodVersion: Int = 0,
)

/**
 * Settings live in Room (`SettingRow(key, valueJson)`, one row per field) with an in-memory
 * cache so the policy engine can read them synchronously.
 */
class SettingsRepository(private val dao: SettingDao) {
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value
    private val mutex = Mutex()

    fun loadBlocking() {
        runBlocking(Dispatchers.IO) {
            val rows = dao.all()
            if (rows.isEmpty()) return@runBlocking
            val map = rows.associate { it.key to AppJson.parseToJsonElement(it.valueJson) }
            _state.value = runCatching { AppJson.decodeFromJsonElement(AppSettings.serializer(), JsonObject(map)) }
                .getOrElse { AppSettings() }
        }
    }

    /** Re-reads every row (after a restore replaced them). */
    suspend fun reload() = mutex.withLock {
        val rows = dao.all()
        val map = rows.associate { it.key to AppJson.parseToJsonElement(it.valueJson) }
        _state.value = runCatching { AppJson.decodeFromJsonElement(AppSettings.serializer(), JsonObject(map)) }.getOrElse { AppSettings() }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val old = _state.value
        val new = transform(old)
        if (new == old) return@withLock new
        val oldJson = AppJson.encodeToJsonElement(AppSettings.serializer(), old).jsonObject
        val newJson = AppJson.encodeToJsonElement(AppSettings.serializer(), new).jsonObject
        // Null fields are omitted by the encoder: write an explicit JSON null so clearing a value persists.
        for (k in oldJson.keys + newJson.keys) {
            val v = newJson[k] ?: JsonNull
            if ((oldJson[k] ?: JsonNull) != v) dao.upsert(SettingEntity(k, v.toString()))
        }
        _state.value = new
        new
    }
}
