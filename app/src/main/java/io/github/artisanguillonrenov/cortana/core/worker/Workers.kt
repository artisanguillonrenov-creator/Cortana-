package io.github.artisanguillonrenov.cortana.core.worker

import io.github.artisanguillonrenov.cortana.core.model.await
import io.github.artisanguillonrenov.cortana.contracts.ArtifactInfo
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.JobStatus
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.PairingRequest
import io.github.artisanguillonrenov.cortana.contracts.PairingResponse
import io.github.artisanguillonrenov.cortana.contracts.SyncManifest
import io.github.artisanguillonrenov.cortana.contracts.SyncPlan
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkerJob
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.core.exec.ExecResult
import io.github.artisanguillonrenov.cortana.core.exec.ExecutionBackend
import io.github.artisanguillonrenov.cortana.core.exec.ProcessSpec
import io.github.artisanguillonrenov.cortana.core.exec.SandboxMode
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.WorkerEntity
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class WorkerAuthException(message: String) : Exception(message)

/**
 * TLS client pinned to one worker certificate (SHA-256 of the DER certificate, obtained at
 * pairing): no CA, no hostname trust — the pin is the identity. Every request after pairing is
 * signed by the device key over the canonical string of [WorkerProtocol].
 */
class WorkerClient(
    val baseUrl: String,
    private val pinnedSha256: String,
    private val deviceId: String,
    private val key: DeviceKey?,
    base: OkHttpClient,
) {
    @Volatile var lastServerCertificate: X509Certificate? = null
        private set

    private val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates not accepted")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("aucun certificat")
            val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(sha.toByteArray(), pinnedSha256.lowercase().toByteArray())) throw CertificateException("certificat du worker inattendu (épinglage)")
            lastServerCertificate = leaf
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient = base.newBuilder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory, trust)
        .hostnameVerifier { _, _ -> lastServerCertificate != null } // identity already proven by the pin
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun request(method: String, pathAndQuery: String, body: ByteArray?, type: String, signed: Boolean): Request {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + pathAndQuery)
        if (signed) {
            val k = key ?: throw WorkerAuthException("clé d'appareil indisponible")
            val ts = System.currentTimeMillis()
            val nonce = Ids.new() + Ids.new().take(8)
            val sha = MessageDigest.getInstance("SHA-256").digest(body ?: ByteArray(0)).joinToString("") { "%02x".format(it) }
            b.header(WorkerProtocol.H_DEVICE, deviceId).header(WorkerProtocol.H_TIMESTAMP, ts.toString()).header(WorkerProtocol.H_NONCE, nonce)
                .header(WorkerProtocol.H_SIGNATURE, k.sign(WorkerProtocol.canonical(method, pathAndQuery, ts, nonce, sha)))
        }
        return when (method) {
            "GET" -> b.get().build()
            else -> b.method(method, (body ?: ByteArray(0)).toRequestBody(type.toMediaType())).build()
        }
    }

    private suspend fun exchange(method: String, path: String, body: ByteArray? = null, type: String = "application/json", signed: Boolean = true): Pair<ByteArray, okhttp3.Headers> = withContext(Dispatchers.IO) {
        http.newCall(request(method, path, body, type, signed)).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            when {
                r.code == 401 || r.code == 403 -> throw WorkerAuthException("Worker : accès refusé (${bytes.decodeToString().take(120)})")
                !r.isSuccessful -> throw IOException("Worker HTTP ${r.code} : ${bytes.decodeToString().take(200)}")
            }
            bytes to r.headers
        }
    }

    private suspend fun <T> json(method: String, path: String, body: String?, s: KSerializer<T>, signed: Boolean = true): T =
        ContractJson.decodeFromString(s, exchange(method, path, body?.toByteArray(), signed = signed).first.decodeToString())

    suspend fun pair(req: PairingRequest): PairingResponse = json("POST", "/v1/pair", ContractJson.encodeToString(PairingRequest.serializer(), req), PairingResponse.serializer(), signed = false)
    suspend fun capabilities(): WorkerCapabilities = json("GET", "/v1/capabilities", null, WorkerCapabilities.serializer())
    suspend fun plan(m: SyncManifest): SyncPlan = json("POST", "/v1/workspaces/${m.workspaceId}/manifest", ContractJson.encodeToString(SyncManifest.serializer(), m), SyncPlan.serializer())
    suspend fun upload(m: SyncManifest, zip: ByteArray) {
        val manifest = ContractJson.encodeToString(SyncManifest.serializer(), m).toByteArray()
        val frame = java.nio.ByteBuffer.allocate(4 + manifest.size + zip.size).putInt(manifest.size).put(manifest).put(zip).array()
        exchange("POST", "/v1/workspaces/${m.workspaceId}/upload", frame, "application/octet-stream")
    }
    suspend fun submit(job: WorkerJob): JobStatus = json("POST", "/v1/jobs", ContractJson.encodeToString(WorkerJob.serializer(), job), JobStatus.serializer())
    suspend fun status(id: String): JobStatus = json("GET", "/v1/jobs/$id", null, JobStatus.serializer())
    suspend fun log(id: String, offset: Long): Pair<String, Long> {
        val (bytes, headers) = exchange("GET", "/v1/jobs/$id/log?offset=$offset&max=65536")
        return bytes.decodeToString() to (headers["X-Next-Offset"]?.toLongOrNull() ?: (offset + bytes.size))
    }
    suspend fun cancel(id: String): JobStatus = json("POST", "/v1/jobs/$id/cancel", "", JobStatus.serializer())
    suspend fun artifacts(id: String): List<ArtifactInfo> = json("GET", "/v1/jobs/$id/artifacts", null, ListSerializer(ArtifactInfo.serializer()))
    suspend fun download(id: String, name: String): ByteArray = exchange("GET", "/v1/jobs/$id/artifacts/$name").first
    suspend fun revokeSelf() { exchange("POST", "/v1/revoke", ByteArray(0)) }

    /** stdio MCP servers declared in the worker's own configuration (names only). */
    suspend fun mcpServers(): List<String> = json("GET", "/v1/mcp", null, ListSerializer(kotlinx.serialization.serializer<String>()))

    /** Inbound webhooks hosted by the worker (phase 26). */
    suspend fun registerHook(name: String, reg: io.github.artisanguillonrenov.cortana.contracts.HookRegistration): io.github.artisanguillonrenov.cortana.contracts.HookInfo =
        json("PUT", "/v1/hooks/$name", ContractJson.encodeToString(io.github.artisanguillonrenov.cortana.contracts.HookRegistration.serializer(), reg), io.github.artisanguillonrenov.cortana.contracts.HookInfo.serializer())
    suspend fun deleteHook(name: String) { runCatching { exchange("DELETE", "/v1/hooks/$name") } }
    suspend fun hookEvents(after: Long): io.github.artisanguillonrenov.cortana.contracts.HookEvents =
        json("GET", "/v1/hooks/events?after=$after", null, io.github.artisanguillonrenov.cortana.contracts.HookEvents.serializer())

    /**
     * One JSON-RPC message to a worker-side stdio MCP server: the response line, or null for a
     * notification. Cancelling the coroutine cancels the HTTP exchange.
     */
    suspend fun mcp(name: String, message: String, timeoutMs: Long): String? {
        val t = timeoutMs.coerceIn(1_000, 600_000)
        val req = request("POST", "/v1/mcp/${java.net.URLEncoder.encode(name, "UTF-8")}?timeout=$t", message.toByteArray(), "application/json", true)
        val call = http.newBuilder().readTimeout(t + 10_000, TimeUnit.MILLISECONDS).build().newCall(req)
        val r = call.await()
        return r.use {
            val bytes = it.body?.bytes() ?: ByteArray(0)
            when {
                it.code == 401 || it.code == 403 -> throw WorkerAuthException("Worker : accès refusé")
                !it.isSuccessful -> throw IOException("Worker HTTP ${it.code} : ${bytes.decodeToString().take(200)}")
            }
            bytes.decodeToString().ifEmpty { null }
        }
    }
}

/**
 * The one owner of paired workers: pairing (proof of possession of the pinned key), capability
 * refresh, revocation, and registration of each live worker as an ExecutionBackend.
 */
class WorkerService(
    private val context: android.content.Context,
    private val db: CortanaDatabase,
    private val http: OkHttpClient,
    private val audit: AuditLog,
    private val workspaces: WorkspaceManager,
    private val artifacts: ArtifactService,
    private val backends: MutableList<ExecutionBackend>,
) {
    private val dao get() = db.workers()
    val deviceKey: DeviceKey by lazy { DeviceKey(context) }
    val deviceId: String by lazy {
        val f = File(context.filesDir, "device-id")
        f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: ("tab-" + Ids.new().take(16)).also { f.writeText(it) }
    }

    fun observe(): Flow<List<WorkerEntity>> = dao.observe()
    suspend fun all(): List<WorkerEntity> = dao.all()

    /** Registers every paired, non-revoked worker as an execution backend (startup). */
    suspend fun registerAll() { dao.all().filter { !it.revoked }.forEach { register(it) } }

    private fun register(w: WorkerEntity) {
        backends.removeAll { it.id == w.workerId }
        val caps = runCatching { ContractJson.decodeFromString(WorkerCapabilities.serializer(), w.capabilitiesJson) }.getOrNull() ?: return
        backends += WorkerBackend(w, client(w), caps, workspaces, artifacts)
    }

    private fun client(w: WorkerEntity) = WorkerClient(w.baseUrl, w.certificateSha256, deviceId, deviceKey, http)

    suspend fun pair(pairing: String, deviceName: String = android.os.Build.MODEL ?: "tablette"): WorkerEntity {
        val info = WorkerProtocol.parsePairingString(pairing) ?: throw IllegalArgumentException("Code d'appairage illisible (cortana-worker://…)")
        val c = WorkerClient(info.baseUrl, info.certificateSha256, deviceId, deviceKey, http)
        val nonce = Ids.new() + Ids.new()
        val resp = c.pair(PairingRequest(pairingCode = info.code, deviceId = deviceId, deviceName = deviceName, devicePublicKey = deviceKey.publicKeyBase64, nonce = nonce))
        // Proof of possession: the pinned certificate's key signed our nonce.
        val cert = c.lastServerCertificate ?: throw WorkerAuthException("certificat non vérifié")
        val certKey = Base64.getEncoder().encodeToString(cert.publicKey.encoded)
        if (certKey != resp.workerPublicKey) throw WorkerAuthException("clé du worker différente du certificat épinglé")
        val ok = Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply {
            initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(resp.workerPublicKey))))
            update(WorkerProtocol.proofMessage(nonce, deviceId).toByteArray())
        }.verify(Base64.getDecoder().decode(resp.proof))
        if (!ok || resp.workerId != info.workerId) throw WorkerAuthException("preuve d'appairage invalide")
        val now = System.currentTimeMillis()
        val w = WorkerEntity(resp.workerId, resp.workerName, info.baseUrl, info.certificateSha256, resp.workerPublicKey,
            ContractJson.encodeToString(WorkerCapabilities.serializer(), resp.capabilities), now, lastSeenAt = now)
        dao.upsert(w)
        register(w)
        audit.record("owner", "worker.pair", resp.workerName, "ok", """{"worker":"${resp.workerId}","hardwareKey":${deviceKey.hardwareBacked}}""")
        return w
    }

    suspend fun refresh(workerId: String): WorkerEntity? {
        val w = dao.get(workerId) ?: return null
        if (w.revoked) return w
        val upd = runCatching { client(w).capabilities() }.fold(
            { caps -> w.copy(capabilitiesJson = ContractJson.encodeToString(WorkerCapabilities.serializer(), caps), lastSeenAt = System.currentTimeMillis(), lastError = null) },
            { e -> if (e is WorkerAuthException) w.copy(revoked = true, lastError = e.message) else w.copy(lastError = Redactor.redact(e.message ?: e.javaClass.simpleName)) },
        )
        dao.upsert(upd)
        if (upd.revoked) backends.removeAll { it.id == workerId } else register(upd)
        return upd
    }

    /** Revokes on both sides (best effort remotely); the local revocation is immediate and final. */
    suspend fun revoke(workerId: String) {
        val w = dao.get(workerId) ?: return
        runCatching { client(w).revokeSelf() }
        dao.upsert(w.copy(revoked = true))
        backends.removeAll { it.id == workerId }
        audit.record("owner", "worker.revoke", w.name, "ok", """{"worker":"$workerId"}""")
    }

    suspend fun forget(workerId: String) { revoke(workerId); dao.delete(workerId) }

    private suspend fun active(workerId: String) = dao.get(workerId)?.takeIf { !it.revoked } ?: throw WorkerAuthException("Worker inconnu ou révoqué")
    suspend fun mcpServers(workerId: String): List<String> = client(active(workerId)).mcpServers()
    suspend fun mcp(workerId: String, name: String, message: String, timeoutMs: Long): String? = client(active(workerId)).mcp(name, message, timeoutMs)
    suspend fun registerHook(workerId: String, name: String, reg: io.github.artisanguillonrenov.cortana.contracts.HookRegistration) = client(active(workerId)).registerHook(name, reg)
    suspend fun deleteHook(workerId: String, name: String) = client(active(workerId)).deleteHook(name)
    suspend fun hookEvents(workerId: String, after: Long) = client(active(workerId)).hookEvents(after)
    /** The address external senders use: the worker's base URL (its certificate is self-signed and pinned by Cortana only). */
    suspend fun baseUrl(workerId: String): String = active(workerId).baseUrl
}

/**
 * A paired worker as an ExecutionBackend: delta-syncs the workspace, submits the job, streams its
 * log, survives network drops (reconnects and resumes polling the same job), cancels it when the
 * task is cancelled, and brings back artifacts only after SHA-256 verification.
 */
class WorkerBackend(
    private val entity: WorkerEntity,
    private val client: WorkerClient,
    private val caps: WorkerCapabilities,
    private val workspaces: WorkspaceManager,
    private val artifacts: ArtifactService,
    private val reconnectWindowMs: Long = 5 * 60_000L,
) : ExecutionBackend {
    override val id: String = entity.workerId
    override val label: String = "worker ${entity.name}"
    override suspend fun capabilities() = caps

    override fun supports(mode: SandboxMode, network: NetworkMode): Boolean = when (mode) {
        SandboxMode.ISOLATED -> "isolated" in caps.sandboxModes && network in caps.networkModes
        SandboxMode.PROCESS -> "process" in caps.sandboxModes && network == NetworkMode.ALLOW
    }

    override suspend fun run(w: WorkspaceEntity, spec: ProcessSpec, onLog: (String) -> Unit): ExecResult {
        val start = System.currentTimeMillis()
        sync(w)
        val jobId = "job-" + Ids.new()
        val job = WorkerJob(
            jobId = jobId, workspaceId = w.workspaceId, kind = "exec", command = listOf("sh", "-c", spec.command), cwd = spec.cwd, env = spec.env,
            timeoutMs = spec.timeoutMs, network = spec.network, maxOutputBytes = spec.maxOutputBytes, sandbox = spec.sandbox.wire, artifactGlobs = spec.artifactGlobs,
        )
        retrying { client.submit(job) }
        var offset = 0L
        val partial = StringBuilder()
        try {
            while (true) {
                val (chunk, next) = retrying { client.log(jobId, offset) }
                if (chunk.isNotEmpty()) { partial.append(chunk); chunk.lines().filter { it.isNotBlank() }.forEach(onLog) }
                offset = next
                val st = retrying { client.status(jobId) }
                val res = st.result
                if (res != null) {
                    // Requested files come back whatever the outcome: failing runs are exactly when test reports matter.
                    val ids = if (spec.artifactGlobs.isNotEmpty() && res.status != "cancelled") fetchArtifacts(jobId, w, spec.taskId) else emptyList()
                    return ExecResult(res.status, res.exitCode, Redactor.redact(res.stdout), Redactor.redact(res.stderr), System.currentTimeMillis() - start, id, res.sandbox, artifactIds = ids)
                }
                if (System.currentTimeMillis() - start > spec.timeoutMs + 60_000) {
                    retrying { client.cancel(jobId) }
                    return ExecResult("timed_out", null, partial.takeLast(spec.maxOutputBytes).toString(), "", System.currentTimeMillis() - start, id, spec.sandbox.wire, "Délai côté tablette dépassé : tâche annulée sur le worker.")
                }
                delay(POLL_MS)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { client.cancel(jobId) } }
            throw e
        }
    }

    /** Network drops are retried with backoff inside the reconnect window; auth errors are final. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + reconnectWindowMs
        var wait = 500L
        while (true) {
            try { return block() } catch (e: IOException) {
                if (System.currentTimeMillis() + wait > deadline) throw IOException("Worker injoignable depuis ${reconnectWindowMs / 1000} s : ${e.message}", e)
                delay(wait); wait = (wait * 2).coerceAtMost(15_000)
            }
        }
    }

    private suspend fun sync(w: WorkspaceEntity) {
        val fs = workspaces.fs(w)
        val files = withContext(Dispatchers.IO) { fs.walk().associate { fs.relative(it) to Hash.sha256File(it) } }
        val m = SyncManifest(workspaceId = w.workspaceId, files = files)
        val plan = retrying { client.plan(m) }
        val zip = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { z -> plan.need.forEach { p -> z.putNextEntry(ZipEntry(p)); fs.resolve(p).inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            }.toByteArray()
        }
        if (plan.need.isNotEmpty() || plan.delete.isNotEmpty()) retrying { client.upload(m, zip) }
    }

    internal suspend fun fetchArtifacts(jobId: String, w: WorkspaceEntity, taskId: String?): List<String> = retrying { client.artifacts(jobId) }.map { info ->
        val bytes = retrying { client.download(jobId, info.name) }
        val sha = Hash.sha256Bytes(bytes)
        if (sha != info.sha256) throw IOException("Intégrité de l'artefact ${info.name} non vérifiée (empreinte différente) : rejeté")
        val tmp = withContext(Dispatchers.IO) { File.createTempFile("worker-", ".bin").apply { writeBytes(bytes) } }
        artifacts.register(tmp, "build", info.name, taskId = taskId, capability = "worker.job", metadata = mapOf("worker" to entity.name, "workspace" to w.name, "sha256.worker" to info.sha256), move = true).artifactId
    }

    companion object { const val POLL_MS = 400L }
}
