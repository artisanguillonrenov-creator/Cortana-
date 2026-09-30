package io.github.artisanguillonrenov.cortana.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- developer workspace (doc 03 §2, §6)

@Serializable
enum class WorkspaceTrust {
    /** Imported/cloned: scripts only run in a strict sandbox. */
    @SerialName("untrusted") UNTRUSTED,
    @SerialName("trusted_local") TRUSTED_LOCAL,
    /** Cortana's own project: reinforced rules. */
    @SerialName("system_project") SYSTEM_PROJECT,
    @SerialName("read_only") READ_ONLY,
}

@Serializable
data class Workspace(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workspaceId: String,
    val name: String,
    /** "app:" (app-private directory) or "saf:" (granted tree) or "worker:<id>:" prefix + path. */
    val root: String,
    val backendId: String = "android-local",
    val vcsType: String? = null,
    val currentBranch: String? = null,
    val baseRevision: String? = null,
    val writable: Boolean = true,
    val trust: WorkspaceTrust = WorkspaceTrust.UNTRUSTED,
    val detectedStacks: List<String> = emptyList(),
    val buildSystems: List<String> = emptyList(),
    val createdAt: Long,
    val lastOpenedAt: Long,
)

@Serializable
sealed interface PatchOperation {
    val path: String

    /** Replace [find] (must match exactly [expectedOccurrences] times) — precondition on file hash if given. */
    @Serializable @SerialName("replace")
    data class Replace(override val path: String, val find: String, val replace: String, val expectedOccurrences: Int = 1) : PatchOperation

    @Serializable @SerialName("create")
    data class Create(override val path: String, val content: String) : PatchOperation

    @Serializable @SerialName("write")
    data class Write(override val path: String, val content: String) : PatchOperation

    @Serializable @SerialName("delete")
    data class Delete(override val path: String) : PatchOperation

    @Serializable @SerialName("rename")
    data class Rename(override val path: String, val newPath: String) : PatchOperation

    /** Standard unified diff for a single file. */
    @Serializable @SerialName("unified_diff")
    data class UnifiedDiff(override val path: String, val diff: String) : PatchOperation
}

@Serializable
data class PatchSet(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val patchId: String,
    val workspaceId: String,
    val baseRevision: String? = null,
    val operations: List<PatchOperation>,
    val rationale: String = "",
    val expectedFileHashes: Map<String, String> = emptyMap(),
    val generatedAt: Long,
)

/** Result of the opening pipeline (doc 03 §3): what Cortana knows about a repository before changing it. */
@Serializable
data class RepositoryProfile(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workspaceId: String,
    val fileCount: Int,
    val totalBytes: Long,
    val totalLines: Long,
    val filesByType: Map<String, Int> = emptyMap(),
    val languages: List<String> = emptyList(),
    val buildSystems: List<String> = emptyList(),
    val manifests: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val testDirs: List<String> = emptyList(),
    val testFiles: Int = 0,
    val ciFiles: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
    val instructionFiles: List<String> = emptyList(),
    val generatedDirs: List<String> = emptyList(),
    val binaryFiles: Int = 0,
    val potentialSecrets: List<String> = emptyList(),
    val vcs: String? = null,
    val branch: String? = null,
    val head: String? = null,
    val uncommittedChanges: Int = 0,
    val tree: String = "",
    val generatedAt: Long,
)

/** A located search result (doc 03 §4): path + line range + score + source. */
@Serializable
data class CodeHit(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val preview: String,
    val score: Double = 1.0,
    /** text | regex | file | symbol | todo | secret | git */
    val source: String,
)

/** Auditable record of an applied patch (doc 03 §6): backups allow exact rollback. */
@Serializable
data class ChangeSet(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val changeSetId: String,
    val workspaceId: String,
    val patchId: String,
    val taskId: String? = null,
    val files: List<String>,
    val beforeHashes: Map<String, String?>,
    val afterHashes: Map<String, String?>,
    val diff: String,
    val status: String,
    val createdAt: Long,
)

@Serializable
data class Diagnostic(
    val severity: String,
    val source: String,
    val code: String? = null,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val suggestedFixes: List<String> = emptyList(),
)

/** One ReviewService finding (doc 03 §13). severity: blocker | major | minor | info. */
@Serializable
data class ReviewFinding(
    val severity: String,
    val check: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
)

@Serializable
data class ReviewFileStat(val path: String, val added: Int, val removed: Int, val status: String)

/** Output of the ReviewService: verdict approved | changes_requested | blocked. */
@Serializable
data class ReviewResult(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val reviewId: String,
    val workspaceId: String,
    val taskId: String? = null,
    val basis: String,
    val verdict: String,
    val summary: String,
    val files: List<ReviewFileStat>,
    val findings: List<ReviewFinding>,
    val risks: List<String>,
    val testsVerified: Boolean,
    val revision: String,
    val createdAt: Long,
) {
    val blockers get() = findings.filter { it.severity == "blocker" }
}

@Serializable
data class BuildRequest(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workspaceId: String,
    /** build | test | lint | custom */
    val kind: String,
    val target: String? = null,
    val variant: String? = null,
    val command: List<String>? = null,
    val timeoutMs: Long = 600_000,
    val network: NetworkMode = NetworkMode.DENY,
)

@Serializable
data class BuildResult(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val status: String,
    val exitCode: Int?,
    val durationMs: Long,
    val diagnostics: List<Diagnostic> = emptyList(),
    val testsPassed: Int? = null,
    val testsFailed: Int? = null,
    val failedTests: List<String> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val logTail: String = "",
    val backend: String,
)

// ---------------------------------------------------------------- execution / worker protocol (doc 03 §16-17, doc 05 §19)

@Serializable
enum class NetworkMode { @SerialName("deny") DENY, @SerialName("allowlist") ALLOWLIST, @SerialName("allow") ALLOW }

@Serializable
data class WorkerCapabilities(
    val os: String,
    val arch: String,
    val cpus: Int,
    val memoryMb: Long,
    val gpu: String? = null,
    val containerRuntime: String? = null,
    val toolchains: List<String> = emptyList(),
    val buildSystems: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val maxParallelJobs: Int = 1,
    val sandboxModes: List<String> = emptyList(),
    val networkModes: List<NetworkMode> = listOf(NetworkMode.DENY),
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class WorkerNode(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workerId: String,
    val name: String,
    val baseUrl: String,
    /** SHA-256 of the worker's TLS certificate, pinned at pairing. */
    val certificateSha256: String,
    val capabilities: WorkerCapabilities? = null,
    val pairedAt: Long,
    val lastSeenAt: Long? = null,
    val revoked: Boolean = false,
)

/** Pairing (doc 05 §19): short one-time code → key exchange → pinning. */
@Serializable
data class PairingRequest(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val pairingCode: String,
    val deviceId: String,
    val deviceName: String,
    /** Device's public key (X.509/SPKI, base64) used to authenticate its later requests. */
    val devicePublicKey: String,
    val nonce: String,
)

@Serializable
data class PairingResponse(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val workerId: String,
    val workerName: String,
    val workerPublicKey: String,
    val capabilities: WorkerCapabilities,
    /** Signature by the worker key over (nonce + deviceId), proving possession. */
    val proof: String,
)

@Serializable
data class WorkerJob(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val jobId: String,
    val workspaceId: String,
    /** exec | build | test | git | sync */
    val kind: String,
    val command: List<String> = emptyList(),
    val cwd: String = ".",
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 300_000,
    val network: NetworkMode = NetworkMode.DENY,
    val maxOutputBytes: Int = 200_000,
    val idempotencyKey: String? = null,
    /** "process" or "isolated" (read-only system, writable workspace only, network per [network]). */
    val sandbox: String = "isolated",
    /** Glob patterns (relative to the workspace) of files returned as verified artifacts. */
    val artifactGlobs: List<String> = emptyList(),
)

@Serializable
data class WorkerJobResult(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val jobId: String,
    val status: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val durationMs: Long = 0,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val artifacts: List<Artifact> = emptyList(),
    val sandbox: String = "",
    val error: StructuredError? = null,
)

// ---------------------------------------------------------------- worker wire protocol v1 (doc 03 §16-17, doc 05 §19)

/** Workspace delta sync: the tablet sends its manifest, the worker answers what it needs. */
@Serializable
data class SyncManifest(val schemaVersion: String = CONTRACTS_SCHEMA_VERSION, val workspaceId: String, val files: Map<String, String>)

@Serializable
data class SyncPlan(val schemaVersion: String = CONTRACTS_SCHEMA_VERSION, val need: List<String>, val delete: List<String>)

@Serializable
data class JobStatus(
    val schemaVersion: String = CONTRACTS_SCHEMA_VERSION,
    val jobId: String,
    /** queued | running | succeeded | failed | timed_out | cancelled | error */
    val status: String,
    val logSize: Long = 0,
    val result: WorkerJobResult? = null,
)

@Serializable
data class ArtifactInfo(val name: String, val sha256: String, val sizeBytes: Long)

/** Pairing string shown by the worker: cortana-worker://host:port?id=…&fp=…&code=… */
data class PairingInfo(val baseUrl: String, val workerId: String, val certificateSha256: String, val code: String)

/**
 * Request authentication shared by the tablet and the worker (single definition): TLS with the
 * worker certificate pinned at pairing + every request signed by the device key (ECDSA P-256)
 * over a canonical string, with timestamp and nonce against replay.
 */
object WorkerProtocol {
    const val VERSION = "1"
    const val H_DEVICE = "X-Cortana-Device"
    const val H_TIMESTAMP = "X-Cortana-Timestamp"
    const val H_NONCE = "X-Cortana-Nonce"
    const val H_SIGNATURE = "X-Cortana-Signature"
    const val MAX_SKEW_MS = 5 * 60_000L
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    fun canonical(method: String, pathAndQuery: String, timestamp: Long, nonce: String, bodySha256: String): String =
        "cortana-worker/$VERSION\n${method.uppercase()}\n$pathAndQuery\n$timestamp\n$nonce\n$bodySha256"

    fun proofMessage(nonce: String, deviceId: String): String = "cortana-pair/$VERSION\n$nonce\n$deviceId"

    fun pairingString(host: String, port: Int, workerId: String, certificateSha256: String, code: String): String =
        "cortana-worker://$host:$port?id=$workerId&fp=$certificateSha256&code=$code"

    fun parsePairingString(s: String): PairingInfo? {
        val m = Regex("""^cortana-worker://([^/?#]+)\?(.+)$""").find(s.trim()) ?: return null
        val q = m.groupValues[2].split('&').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val id = q["id"] ?: return null
        val fp = q["fp"]?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val code = q["code"] ?: return null
        return PairingInfo("https://${m.groupValues[1]}", id, fp, code)
    }
}
