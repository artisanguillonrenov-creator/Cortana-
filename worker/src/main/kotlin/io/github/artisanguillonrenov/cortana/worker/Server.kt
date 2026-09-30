package io.github.artisanguillonrenov.cortana.worker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.JobStatus
import io.github.artisanguillonrenov.cortana.contracts.PairingRequest
import io.github.artisanguillonrenov.cortana.contracts.PairingResponse
import io.github.artisanguillonrenov.cortana.contracts.SyncManifest
import io.github.artisanguillonrenov.cortana.contracts.SyncPlan
import io.github.artisanguillonrenov.cortana.contracts.WorkerCapabilities
import io.github.artisanguillonrenov.cortana.contracts.WorkerJob
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

@Serializable
data class WorkerConfig(
    val workerId: String, val name: String, val port: Int = 8765, val bind: String = "0.0.0.0",
    /** stdio MCP servers this worker offers to its paired tablets (declared here only, by the worker's owner). */
    val mcpServers: Map<String, McpStdioSpec> = emptyMap(),
)

/**
 * The worker's HTTPS API (protocol v1). Everything except `/v1/pair` requires a paired, non-revoked
 * device's signature. The worker never decides policy: it only executes what the tablet sends,
 * within its own limits (workspace confinement, sandbox, timeouts, leases, size limits).
 */
class WorkerServer(val dataDir: File, private val overridePort: Int? = null) {
    val config: WorkerConfig = loadConfig(dataDir)
    val identity = WorkerIdentity(dataDir)
    val devices = DeviceStore(dataDir)
    val codes = PairingCodes(dataDir)
    private val verifier = RequestVerifier(devices)
    val sandbox = Sandbox(dataDir)
    private val workspaces = WorkspaceStore(dataDir)
    val jobs = JobManager(dataDir, sandbox, workspaces)
    val mcp = McpBridge({ config.mcpServers }, File(dataDir, "mcp-logs"))
    val hooks = HookStore(dataDir)
    val capabilities: WorkerCapabilities by lazy { Capabilities.detect(sandbox, 4) }
    private var server: HttpsServer? = null

    val port: Int get() = server?.address?.port ?: (overridePort ?: config.port)

    fun start(): WorkerServer {
        // A connection cut mid-request (Wi-Fi drop, tablet asleep) must not hold a handler thread
        // forever: requests and responses are bounded (seconds). Read once by the JDK server.
        System.getProperty("sun.net.httpserver.maxReqTime") ?: System.setProperty("sun.net.httpserver.maxReqTime", "300")
        System.getProperty("sun.net.httpserver.maxRspTime") ?: System.setProperty("sun.net.httpserver.maxRspTime", "300")
        val s = HttpsServer.create(InetSocketAddress(config.bind, overridePort ?: config.port), 64)
        val ssl = identity.sslContext()
        s.httpsConfigurator = object : HttpsConfigurator(ssl) {
            override fun configure(params: HttpsParameters) {
                val p = ssl.defaultSSLParameters
                p.protocols = arrayOf("TLSv1.3", "TLSv1.2")
                params.setSSLParameters(p)
            }
        }
        s.executor = Executors.newFixedThreadPool(8) { r -> Thread(r, "cortana-http").apply { isDaemon = true } }
        s.createContext("/v1/") { ex -> handle(ex) }
        s.createContext("/hooks/") { ex -> handleHook(ex) }
        s.start()
        server = s
        return this
    }

    fun stop() { server?.stop(0); jobs.shutdown(); mcp.stopAll() }

    fun pairingString(host: String): String = WorkerProtocol.pairingString(host, port, config.workerId, identity.certificateSha256, codes.issue())

    private fun handle(ex: HttpExchange) {
        try {
            val method = ex.requestMethod.uppercase()
            val pathQuery = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val path = ex.requestURI.path
            val limit = if (path.endsWith("/upload")) MAX_UPLOAD else MAX_JSON
            val body = ex.requestBody.use { readLimited(it, limit) }
            if (path == "/v1/pair" && method == "POST") return respond(ex, 200, pair(decode(body, PairingRequest.serializer())), PairingResponse.serializer())
            val device = verifier.verify(method, pathQuery, { ex.requestHeaders.getFirst(it) }, body)
            val q = ex.requestURI.rawQuery?.split('&')?.mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }?.toMap() ?: emptyMap()
            val seg = path.removePrefix("/v1/").split('/')
            when {
                method == "GET" && seg == listOf("capabilities") -> respond(ex, 200, capabilities, WorkerCapabilities.serializer())
                method == "POST" && seg.size == 3 && seg[0] == "workspaces" && seg[2] == "manifest" -> {
                    val m = decode(body, SyncManifest.serializer())
                    require(m.workspaceId == seg[1]) { "projet incohérent" }
                    respond(ex, 200, workspaces.plan(device.deviceId, m), SyncPlan.serializer())
                }
                method == "POST" && seg.size == 3 && seg[0] == "workspaces" && seg[2] == "upload" -> {
                    // Multipart-free framing: [4-byte manifest length][manifest JSON][zip bytes]
                    val len = java.nio.ByteBuffer.wrap(body, 0, 4).int
                    require(len in 2..MAX_JSON) { "trame invalide" }
                    val m = decode(body.copyOfRange(4, 4 + len), SyncManifest.serializer())
                    require(m.workspaceId == seg[1]) { "projet incohérent" }
                    val n = workspaces.apply(device.deviceId, seg[1], m, body.copyOfRange(4 + len, body.size))
                    respondText(ex, 200, "$n")
                }
                method == "POST" && seg == listOf("jobs") -> respond(ex, 200, jobs.submit(device.deviceId, decode(body, WorkerJob.serializer())), JobStatus.serializer())
                method == "GET" && seg.size == 2 && seg[0] == "jobs" -> respond(ex, 200, jobs.status(seg[1], device.deviceId) ?: throw AuthException(404, "tâche inconnue"), JobStatus.serializer())
                method == "GET" && seg.size == 3 && seg[0] == "jobs" && seg[2] == "log" -> {
                    val (bytes, next) = jobs.log(seg[1], device.deviceId, q["offset"]?.toLongOrNull() ?: 0, (q["max"]?.toIntOrNull() ?: 65_536).coerceIn(1, 1_000_000))
                    ex.responseHeaders.add("X-Next-Offset", next.toString())
                    respondBytes(ex, 200, bytes, "text/plain; charset=utf-8")
                }
                method == "POST" && seg.size == 3 && seg[0] == "jobs" && seg[2] == "cancel" -> respond(ex, 200, jobs.cancel(seg[1], device.deviceId) ?: throw AuthException(404, "tâche inconnue"), JobStatus.serializer())
                method == "GET" && seg.size == 3 && seg[0] == "jobs" && seg[2] == "artifacts" ->
                    respond(ex, 200, jobs.artifacts(seg[1], device.deviceId), ListSerializer(io.github.artisanguillonrenov.cortana.contracts.ArtifactInfo.serializer()))
                method == "GET" && seg.size == 4 && seg[0] == "jobs" && seg[2] == "artifacts" -> {
                    val f = jobs.artifactFile(seg[1], device.deviceId, seg[3])
                    ex.responseHeaders.add("X-Sha256", sha256Hex(f.readBytes()))
                    respondBytes(ex, 200, f.readBytes(), "application/octet-stream")
                }
                method == "GET" && seg == listOf("mcp") -> respond(ex, 200, mcp.names(), ListSerializer(kotlinx.serialization.serializer<String>()))
                method == "POST" && seg.size == 2 && seg[0] == "mcp" -> {
                    val out = mcp.send(device.deviceId, java.net.URLDecoder.decode(seg[1], "UTF-8"), body.decodeToString(), q["timeout"]?.toLongOrNull() ?: 60_000)
                    if (out == null) respondBytes(ex, 202, ByteArray(0), "application/json") else respondBytes(ex, 200, out.toByteArray(), "application/json")
                }
                method == "PUT" && seg.size == 2 && seg[0] == "hooks" ->
                    respond(ex, 200, hooks.register(device.deviceId, seg[1], decode(body, io.github.artisanguillonrenov.cortana.contracts.HookRegistration.serializer())), io.github.artisanguillonrenov.cortana.contracts.HookInfo.serializer())
                method == "DELETE" && seg.size == 2 && seg[0] == "hooks" -> respondText(ex, if (hooks.delete(device.deviceId, seg[1])) 200 else 404, "")
                method == "GET" && seg == listOf("hooks", "events") ->
                    respond(ex, 200, hooks.collect(device.deviceId, q["after"]?.toLongOrNull() ?: 0), io.github.artisanguillonrenov.cortana.contracts.HookEvents.serializer())
                method == "POST" && seg == listOf("revoke") -> { devices.revoke(device.deviceId); hooks.dropDevice(device.deviceId); respondText(ex, 200, "révoqué") }
                else -> respondText(ex, 404, "inconnu")
            }
        } catch (e: AuthException) {
            respondText(ex, e.status, e.message ?: "refusé")
        } catch (e: IllegalArgumentException) {
            respondText(ex, 400, e.message ?: "requête invalide")
        } catch (e: IllegalStateException) {
            respondText(ex, 409, e.message ?: "impossible")
        } catch (e: Exception) {
            respondText(ex, 500, "erreur interne")
        } finally {
            ex.close()
        }
    }

    /** Public webhook endpoint: no pairing, the hook's own signature decides (see [HookStore]). */
    private fun handleHook(ex: HttpExchange) {
        try {
            if (ex.requestMethod.uppercase() != "POST") return respondText(ex, 405, "POST uniquement")
            val id = ex.requestURI.path.removePrefix("/hooks/").trim('/')
            if (!id.matches(Regex("h-[A-Za-z0-9]{24}"))) return respondText(ex, 404, "inconnu")
            val body = ex.requestBody.use { readLimited(it, io.github.artisanguillonrenov.cortana.contracts.WebhookSignature.MAX_BODY) }
            val (code, msg) = hooks.receive(id, { ex.requestHeaders.getFirst(it) }, body)
            respondText(ex, code, msg)
        } catch (e: IllegalArgumentException) {
            respondText(ex, 413, e.message ?: "refusé")
        } catch (e: Exception) {
            respondText(ex, 500, "erreur interne")
        } finally { ex.close() }
    }

    private fun pair(req: PairingRequest): PairingResponse {
        if (!codes.consume(req.pairingCode)) throw AuthException(403, "code d'appairage invalide ou expiré")
        require(req.deviceId.matches(Regex("[A-Za-z0-9._-]{8,80}"))) { "identifiant d'appareil invalide" }
        require(req.nonce.length in 16..128) { "nonce invalide" }
        java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(unb64(req.devicePublicKey))) // must parse
        devices.add(PairedDevice(req.deviceId, req.deviceName.take(80), req.devicePublicKey, System.currentTimeMillis()))
        return PairingResponse(
            workerId = config.workerId, workerName = config.name, workerPublicKey = identity.publicKeyBase64, capabilities = capabilities,
            proof = identity.sign(WorkerProtocol.proofMessage(req.nonce, req.deviceId)),
        )
    }

    private fun <T> decode(body: ByteArray, s: KSerializer<T>): T = try { ContractJson.decodeFromString(s, body.decodeToString()) } catch (e: Exception) { throw IllegalArgumentException("JSON invalide") }
    private fun <T> respond(ex: HttpExchange, code: Int, v: T, s: KSerializer<T>) = respondBytes(ex, code, ContractJson.encodeToString(s, v).toByteArray(), "application/json")
    private fun respondText(ex: HttpExchange, code: Int, t: String) = respondBytes(ex, code, t.toByteArray(), "text/plain; charset=utf-8")
    private fun respondBytes(ex: HttpExchange, code: Int, b: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }
    }

    companion object {
        const val MAX_JSON = 2 * 1024 * 1024
        const val MAX_UPLOAD = 512 * 1024 * 1024

        fun readLimited(s: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw IllegalArgumentException("corps trop volumineux") }
            return out.toByteArray()
        }

        fun loadConfig(dataDir: File): WorkerConfig {
            val f = File(dataDir, "worker.json")
            if (f.isFile) runCatching { return ContractJson.decodeFromString(WorkerConfig.serializer(), f.readText()) }
            val c = WorkerConfig(workerId = "w-" + randomToken(12), name = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("worker"))
            f.privateWrite(ContractJson.encodeToString(WorkerConfig.serializer(), c))
            return c
        }
    }
}
