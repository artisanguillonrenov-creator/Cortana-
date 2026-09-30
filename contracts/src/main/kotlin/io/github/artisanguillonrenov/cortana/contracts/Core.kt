package io.github.artisanguillonrenov.cortana.contracts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Version of the canonical contract set. Every persisted/exchanged payload carries it. */
const val CONTRACTS_SCHEMA_VERSION = "1.0"

/** Major versions this build can read. A payload with another major version is rejected. */
val SUPPORTED_SCHEMA_MAJORS = setOf("1")

val ContractJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "kind"
}

class ContractException(val error: StructuredError) : Exception(error.publicMessage)

/**
 * Decodes a versioned contract, rejecting missing or unsupported `schemaVersion` values
 * (Phase 1 gate: "version field required", "invalid payload rejection").
 */
object Contracts {
    fun <T> decode(serializer: KSerializer<T>, json: String): T {
        val obj = try {
            ContractJson.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            throw ContractException(StructuredError.validation("contract.malformed", "Contrat JSON illisible : ${e.message}"))
        }
        checkVersion(obj)
        return try {
            ContractJson.decodeFromJsonElement(serializer, obj)
        } catch (e: Exception) {
            throw ContractException(StructuredError.validation("contract.invalid", "Contrat invalide : ${e.message}"))
        }
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String = ContractJson.encodeToString(serializer, value)

    fun checkVersion(obj: JsonObject) {
        val v = (obj["schemaVersion"] as? JsonPrimitive)?.content
            ?: throw ContractException(StructuredError.validation("contract.version_missing", "schemaVersion manquant"))
        if (v.substringBefore('.') !in SUPPORTED_SCHEMA_MAJORS) {
            throw ContractException(StructuredError.validation("contract.version_unsupported", "schemaVersion $v non supportée"))
        }
    }

    fun <T> roundTrip(serializer: KSerializer<T>, value: T): T = decode(serializer, encode(serializer, value))
}

// ---------------------------------------------------------------- errors (§64)

@Serializable
enum class ErrorCategory {
    @SerialName("validation") VALIDATION,
    @SerialName("authorization") AUTHORIZATION,
    @SerialName("policy") POLICY,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("conflict") CONFLICT,
    @SerialName("timeout") TIMEOUT,
    @SerialName("cancelled") CANCELLED,
    @SerialName("provider") PROVIDER,
    @SerialName("executor") EXECUTOR,
    @SerialName("device") DEVICE,
    @SerialName("integration") INTEGRATION,
    @SerialName("persistence") PERSISTENCE,
    @SerialName("sandbox") SANDBOX,
    @SerialName("security") SECURITY,
    @SerialName("budget") BUDGET,
    @SerialName("internal") INTERNAL,
}

/** Structured error: control flow uses [code]/[category], never the human message. */
@Serializable
data class StructuredError(
    val code: String,
    val category: ErrorCategory,
    val retryable: Boolean = false,
    val publicMessage: String,
    val detail: String? = null,
    val cause: StructuredError? = null,
    val metadata: Map<String, String> = emptyMap(),
    val traceId: String? = null,
) {
    companion object {
        fun validation(code: String, msg: String) = StructuredError(code, ErrorCategory.VALIDATION, false, msg)
        fun policy(code: String, msg: String) = StructuredError(code, ErrorCategory.POLICY, false, msg)
        fun executor(code: String, msg: String, retryable: Boolean = false) = StructuredError(code, ErrorCategory.EXECUTOR, retryable, msg)
        fun timeout(code: String, msg: String) = StructuredError(code, ErrorCategory.TIMEOUT, true, msg)
        fun budget(code: String, msg: String) = StructuredError(code, ErrorCategory.BUDGET, false, msg)
        fun internal(code: String, msg: String) = StructuredError(code, ErrorCategory.INTERNAL, false, msg)
    }
}

// ---------------------------------------------------------------- tasks (§6.1, §6.2, §7, doc 04 §3)

@Serializable
enum class TaskSource {
    @SerialName("chat") CHAT, @SerialName("voice") VOICE, @SerialName("android") ANDROID, @SerialName("schedule") SCHEDULE,
    @SerialName("webhook") WEBHOOK, @SerialName("messaging") MESSAGING, @SerialName("email") EMAIL, @SerialName("api") API,
    @SerialName("worker") WORKER, @SerialName("mcp") MCP, @SerialName("a2a") A2A, @SerialName("system") SYSTEM,
    @SerialName("specialist") SPECIALIST,
}

@Serializable
data class TaskConstraints(
    val maxSteps: Int? = null,
    val maxReplans: Int? = null,
    val maxToolCalls: Int? = null,
    val maxModelCalls: Int? = null,
    val deadlineMs: Long? = null,
    val toolset: String? = null,
    val allowedCapabilities: List<String>? = null,
    val privacy: PrivacyLevel = PrivacyLevel.NORMAL,
)

@Serializable
enum class PrivacyLevel { @SerialName("normal") NORMAL, @SerialName("sensitive") SENSITIVE, @SerialName("local_only") LOCAL_ONLY }

@Serializable
data class TaskRequest(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val requestId: String,
    val sessionId: String? = null,
    val source: TaskSource,
    val objective: String,
    val attachments: List<ArtifactRef> = emptyList(),
    val contextHints: Map<String, String> = emptyMap(),
    val constraints: TaskConstraints = TaskConstraints(),
    val authorizationGrantId: String? = null,
    val parentTaskId: String? = null,
    val idempotencyKey: String? = null,
    val createdAt: Long,
)

/** Durable task states (doc 04 §3). Stored lowercase, as in database v1. */
@Serializable
enum class TaskState(val wire: String, val terminal: Boolean) {
    @SerialName("received") RECEIVED("received", false),
    @SerialName("classified") CLASSIFIED("classified", false),
    @SerialName("planning") PLANNING("planning", false),
    @SerialName("ready") READY("ready", false),
    @SerialName("waiting_authorization") WAITING_AUTHORIZATION("waiting_authorization", false),
    @SerialName("running") RUNNING("running", false),
    @SerialName("waiting_tool") WAITING_TOOL("waiting_tool", false),
    @SerialName("waiting_user") WAITING_USER("waiting_user", false),
    @SerialName("verifying") VERIFYING("verifying", false),
    @SerialName("recovering") RECOVERING("recovering", false),
    @SerialName("replanning") REPLANNING("replanning", false),
    @SerialName("paused") PAUSED("paused", false),
    @SerialName("interrupted") INTERRUPTED("interrupted", false),
    @SerialName("completed") COMPLETED("completed", true),
    @SerialName("failed") FAILED("failed", true),
    @SerialName("cancelled") CANCELLED("cancelled", true),
    @SerialName("timed_out") TIMED_OUT("timed_out", true),
    @SerialName("halted") HALTED("halted", true);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(s: String): TaskState = byWire[s] ?: throw ContractException(StructuredError.validation("task.state_unknown", "État de tâche inconnu : $s"))
        fun fromWireOrNull(s: String): TaskState? = byWire[s]
    }
}

/**
 * The explicit transition table (doc 04 §3: "toute transition est validée par une table explicite").
 * Terminal states are immutable except through explicit administrative repair.
 */
object TaskTransitions {
    private val S = TaskState.entries.toSet()
    private val nonTerminal = S.filterNot { it.terminal }.toSet()
    private val allowed: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.RECEIVED to setOf(TaskState.CLASSIFIED, TaskState.PLANNING, TaskState.RUNNING),
        TaskState.CLASSIFIED to setOf(TaskState.PLANNING, TaskState.RUNNING, TaskState.WAITING_USER),
        TaskState.PLANNING to setOf(TaskState.READY, TaskState.RUNNING, TaskState.WAITING_USER, TaskState.WAITING_AUTHORIZATION),
        TaskState.READY to setOf(TaskState.RUNNING, TaskState.WAITING_AUTHORIZATION),
        TaskState.WAITING_AUTHORIZATION to setOf(TaskState.RUNNING, TaskState.READY, TaskState.PLANNING),
        TaskState.RUNNING to setOf(
            TaskState.WAITING_TOOL, TaskState.WAITING_AUTHORIZATION, TaskState.WAITING_USER, TaskState.VERIFYING,
            TaskState.RECOVERING, TaskState.REPLANNING, TaskState.PAUSED,
        ),
        TaskState.WAITING_TOOL to setOf(TaskState.RUNNING, TaskState.VERIFYING, TaskState.RECOVERING),
        TaskState.WAITING_USER to setOf(TaskState.RUNNING, TaskState.PLANNING, TaskState.REPLANNING),
        TaskState.VERIFYING to setOf(TaskState.RUNNING, TaskState.RECOVERING, TaskState.REPLANNING, TaskState.WAITING_USER),
        TaskState.RECOVERING to setOf(TaskState.RUNNING, TaskState.REPLANNING, TaskState.WAITING_USER, TaskState.WAITING_AUTHORIZATION),
        TaskState.REPLANNING to setOf(TaskState.READY, TaskState.PLANNING, TaskState.RUNNING, TaskState.WAITING_USER),
        TaskState.PAUSED to setOf(TaskState.RUNNING, TaskState.RECOVERING),
        TaskState.INTERRUPTED to setOf(TaskState.RECOVERING, TaskState.RUNNING, TaskState.WAITING_USER),
    )

    fun isAllowed(from: TaskState, to: TaskState): Boolean {
        if (from.terminal) return false
        if (to.terminal) return true // any live task may end (complete/fail/cancel/timeout/halt)
        if (to == TaskState.INTERRUPTED) return from in nonTerminal // process death detected at startup
        return to in (allowed[from] ?: emptySet())
    }
}

@Serializable
data class TerminationReason(val code: String, val message: String)

@Serializable
data class TaskEvent(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val eventId: String,
    val taskId: String,
    val from: TaskState?,
    val to: TaskState,
    val actor: String,
    val reason: String,
    val at: Long,
    val traceId: String,
    val stepId: String? = null,
    val toolCallId: String? = null,
)

// ---------------------------------------------------------------- observations & verification (§6.6, §6.7)

@Serializable
enum class TrustLevel { @SerialName("system") SYSTEM, @SerialName("tool") TOOL, @SerialName("external-untrusted") EXTERNAL_UNTRUSTED }

@Serializable
data class Observation(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val observationId: String,
    val sourceType: String,
    val sourceId: String? = null,
    val capturedAt: Long,
    val trustLevel: TrustLevel,
    val contentType: String = "text/plain",
    val data: String,
    val hash: String? = null,
)

@Serializable
enum class VerificationStatus { @SerialName("passed") PASSED, @SerialName("partial") PARTIAL, @SerialName("failed") FAILED, @SerialName("uncertain") UNCERTAIN }

@Serializable
enum class RecommendedAction {
    @SerialName("continue") CONTINUE, @SerialName("retry") RETRY, @SerialName("replan") REPLAN,
    @SerialName("ask-user") ASK_USER, @SerialName("fail") FAIL, @SerialName("complete") COMPLETE,
}

@Serializable
data class VerificationEvidence(val kind: String, val detail: String, val deterministic: Boolean)

@Serializable
data class VerificationResult(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val verificationId: String,
    val taskId: String,
    val stepId: String? = null,
    val status: VerificationStatus,
    val confidence: Double,
    val evidence: List<VerificationEvidence>,
    val reason: String,
    val recommendedAction: RecommendedAction,
    /** Stable identity of the failure, so recovery can tell "the same failure again" from progress. */
    val failureSignature: String? = null,
)

// ---------------------------------------------------------------- tool results (§6.5)

@Serializable
data class ToolResultContract(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val toolCallId: String,
    val capability: String,
    val success: Boolean,
    val output: String? = null,
    val observations: List<Observation> = emptyList(),
    val artifactRefs: List<ArtifactRef> = emptyList(),
    val error: StructuredError? = null,
    val retryable: Boolean = false,
    val completedAt: Long,
)

// ---------------------------------------------------------------- artifacts (doc 03 §12, §34.5)

@Serializable
data class ArtifactRef(val artifactId: String, val mime: String? = null, val name: String? = null)

@Serializable
data class Artifact(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val artifactId: String,
    val type: String,
    val mime: String,
    val name: String,
    val uri: String,
    val sha256: String,
    val sizeBytes: Long,
    val producerTaskId: String? = null,
    val producerCapability: String? = null,
    val sourceArtifactIds: List<String> = emptyList(),
    val createdAt: Long,
    val metadata: Map<String, String> = emptyMap(),
    val retention: String = "keep",
)

@Serializable
data class JsonEnvelope(val schemaVersion: String = CONTRACTS_SCHEMA_VERSION, val type: String, val payload: JsonElement)
