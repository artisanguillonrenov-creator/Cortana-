package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.WorkerJob
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import io.github.artisanguillonrenov.cortana.core.exec.ProcessSpec
import io.github.artisanguillonrenov.cortana.core.exec.SandboxMode
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.core.worker.WorkerAuthException
import io.github.artisanguillonrenov.cortana.core.worker.WorkerBackend
import io.github.artisanguillonrenov.cortana.core.worker.WorkerClient
import io.github.artisanguillonrenov.cortana.worker.WorkerServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * VNext phase 12 gate: the tablet pairs with a worker, triggers a sandboxed job and gets back a
 * verified result — real HTTPS on loopback, real pinning, real signatures, real Linux namespaces.
 * (Across two physical machines = level P, not run here.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerIntegrationTest : CortanaTestBase() {
    private lateinit var worker: WorkerServer
    private lateinit var workerDir: File

    @Before fun startWorker() {
        workerDir = Files.createTempDirectory("cortana-worker").toFile()
        worker = WorkerServer(workerDir, overridePort = 0).start()
    }

    @After fun stopWorker() { worker.stop(); workerDir.deleteRecursively() }

    private fun untrustedProject(): WorkspaceEntity = runBlocking {
        val src = SampleProjects.kotlinCalc(File(app.cacheDir, "fixture-worker"))
        c.workspaces.importDocument(DocumentFile.fromFile(src), "calc", "imported:test")
    }

    private fun backend() = c.executionBackends.filterIsInstance<WorkerBackend>().single()

    /** Phase 31 gate: the administration client shows exactly the contracts the paired API serves. */
    @Test fun adminClientServesTheSameContractsAsTheApi() = runBlocking {
        val w = c.workers.pair(worker.pairingString("127.0.0.1"))
        val api = WorkerClient(w.baseUrl, w.certificateSha256, c.workers.deviceId, c.workers.deviceKey, c.http)
        val caps = api.capabilities()
        api.submit(WorkerJob(jobId = "job-admin-0001", workspaceId = "ws-admin01", kind = "exec", command = listOf("sh", "-c", "echo bonjour"), timeoutMs = 20_000, sandbox = "process"))
        val deadline = System.currentTimeMillis() + 20_000
        var st = api.status("job-admin-0001")
        while (st.result == null && System.currentTimeMillis() < deadline) { Thread.sleep(50); st = api.status("job-admin-0001") }
        val secret = java.util.Base64.getEncoder().encodeToString(ByteArray(24) { it.toByte() })
        val hook = c.workers.registerHook(w.workerId, "maison", io.github.artisanguillonrenov.cortana.contracts.HookRegistration(secret, 20, "Domotique"))

        // In-process view over the running server's stores.
        val admin = io.github.artisanguillonrenov.cortana.worker.WorkerAdmin(worker)
        assertEquals(caps, admin.status().capabilities)
        assertEquals(st, admin.jobs().single { it.status.jobId == "job-admin-0001" }.status)
        assertEquals(c.workers.deviceId, admin.jobs().single().deviceId)
        assertEquals(hook, admin.hooks().single().info)

        // The command line, as a separate reader of the worker's data: same JSON contracts.
        fun cli(vararg args: String): String {
            val out = java.io.ByteArrayOutputStream()
            io.github.artisanguillonrenov.cortana.worker.cli(arrayOf(*args, "--data", workerDir.path, "--json"), java.io.PrintStream(out, true, "UTF-8"))
            return out.toString("UTF-8").trim()
        }
        val json = io.github.artisanguillonrenov.cortana.contracts.ContractJson
        val status = json.decodeFromString(io.github.artisanguillonrenov.cortana.worker.WorkerAdmin.Status.serializer(), cli("status"))
        assertEquals(caps, status.capabilities)
        assertEquals(worker.identity.certificateSha256, status.tlsCertificateSha256)
        assertEquals(1 to 1, status.activeDevices to status.hooks)
        val jobs = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(io.github.artisanguillonrenov.cortana.worker.WorkerAdmin.JobLine.serializer()), cli("jobs"))
        assertEquals(st, jobs.single().status)
        assertEquals("bonjour", jobs.single().status.result!!.stdout.trim())
        val hooksJson = cli("hooks")
        assertEquals(hook, json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(io.github.artisanguillonrenov.cortana.worker.HookSummary.serializer()), hooksJson).single().info)
        assertFalse("never the hook secret", hooksJson.contains(secret))
        assertEquals(c.workers.deviceId, json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(io.github.artisanguillonrenov.cortana.worker.PairedDevice.serializer()), cli("devices")).single().deviceId)
    }

    @Test fun pairingPinsTheCertificateAndCodesAreSingleUse() = runBlocking {
        val good = worker.pairingString("127.0.0.1")
        val forged = good.replace(Regex("fp=[0-9a-f]{64}"), "fp=" + "0".repeat(64))
        try { c.workers.pair(forged); fail("wrong pin must fail") } catch (e: Exception) { }
        assertTrue("nothing registered on the worker", worker.devices.all().isEmpty())
        val w = c.workers.pair(good)
        assertEquals(worker.config.workerId, w.workerId)
        assertEquals(worker.identity.certificateSha256, w.certificateSha256)
        assertEquals(c.workers.deviceId, worker.devices.all().single().deviceId)
        try { c.workers.pair(good); fail("a pairing code is single use") } catch (e: Exception) { }
        assertTrue(backend().capabilities().sandboxModes.contains("process"))
    }

    @Test fun untrustedProjectRunsIsolatedOnTheWorkerWithVerifiedArtifacts() {
        assumeTrue("user namespaces unavailable", worker.sandbox.isolatedAvailable)
        runBlocking { c.workers.pair(worker.pairingString("127.0.0.1")) }
        val w = untrustedProject()
        val s = session()
        server.enqueue(toolCall("exec_run", """{"workspace":"calc","command":"ls app/src/main/kotlin/calc > out.txt; mkdir -p out && echo rapport > out/rapport.txt; (touch /usr/pirate 2>/dev/null && echo SYS) || echo ro","artifacts":["out/*.txt"]}"""))
        server.enqueue(text("Fait sur le worker."))
        runAndWait(s, "Exécute l'inventaire du projet calc", approve = true)
        val call = runBlocking { c.db.tasks().toolCalls(lastTask().id) }.single()
        assertEquals(call.outputRef, "ok", call.outcome)
        assertTrue(call.outputRef!!.contains("isolated+no-network"))
        assertTrue(call.outputRef!!.contains("ro"))
        val art = runBlocking { c.artifacts.forTask(lastTask().id) }.single()
        assertEquals("out_rapport.txt", art.name)
        assertTrue(runBlocking { c.artifacts.verify(art.artifactId) })
        assertEquals("rapport\n", c.artifacts.file(art).readText())
        assertFalse("the tablet copy is untouched", File(w.rootPath, "out").exists())
    }

    /** A TCP relay that can drop every connection for a while (network outage). */
    private class FlakyRelay(private val target: Int) : AutoCloseable {
        private val ss = ServerSocket(0)
        val port get() = ss.localPort
        @Volatile var down = false
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        init {
            thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    if (down) { client.close(); continue }
                    val upstream = Socket("127.0.0.1", target)
                    sockets += client; sockets += upstream
                    fun pump(a: Socket, b: Socket) = thread(isDaemon = true) { runCatching { a.getInputStream().copyTo(b.getOutputStream()) }; runCatching { a.close(); b.close() } }
                    pump(client, upstream); pump(upstream, client)
                }
            }
        }
        fun cut() { down = true; synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() } }
        fun restore() { down = false }
        override fun close() { ss.close(); cut() }
    }

    @Test fun jobSurvivesANetworkOutageAndResumesPolling() = runBlocking {
        FlakyRelay(worker.port).use { relay ->
            c.workers.pair(worker.pairingString("127.0.0.1").replace(":${worker.port}?", ":${relay.port}?"))
            val w = runBlocking { c.workspaces.create("reseau") }
            val cut = thread(isDaemon = true) { Thread.sleep(600); relay.cut(); Thread.sleep(2_000); relay.restore() }
            val r = backend().run(w, ProcessSpec("sleep 2; echo terminé", sandbox = SandboxMode.PROCESS, network = NetworkMode.ALLOW, timeoutMs = 30_000)) { }
            cut.join()
            assertEquals(r.stderr, "succeeded", r.status)
            assertTrue(r.toString(), r.stdout.contains("terminé"))
        }
    }

    @Test fun tamperedArtifactIsRejected() = runBlocking {
        c.workers.pair(worker.pairingString("127.0.0.1"))
        val w = c.workspaces.create("integrite")
        val client = WorkerClient(c.workers.all().single().baseUrl, worker.identity.certificateSha256, c.workers.deviceId, c.workers.deviceKey, c.http)
        backend().run(w, ProcessSpec("true", sandbox = SandboxMode.PROCESS, network = NetworkMode.ALLOW)) { } // syncs the workspace
        client.submit(WorkerJob(jobId = "job-integrity-1", workspaceId = w.workspaceId, kind = "build", command = listOf("sh", "-c", "echo original > a.bin"), sandbox = "process", artifactGlobs = listOf("a.bin")))
        while (client.status("job-integrity-1").result == null) Thread.sleep(50)
        File(workerDir, "jobs/job-integrity-1/artifacts/a.bin").writeText("falsifié")
        try { backend().fetchArtifacts("job-integrity-1", w, null); fail("tampered artifact accepted") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("Intégrité")) }
    }

    @Test fun revocationIsImmediateOnBothSides() = runBlocking {
        val paired = c.workers.pair(worker.pairingString("127.0.0.1"))
        val client = WorkerClient(paired.baseUrl, paired.certificateSha256, c.workers.deviceId, c.workers.deviceKey, c.http)
        client.capabilities()
        c.workers.revoke(paired.workerId)
        assertTrue(worker.devices.all().single().revoked)
        assertTrue(c.executionBackends.none { it is WorkerBackend })
        try { client.capabilities(); fail() } catch (e: WorkerAuthException) { }
        assertTrue(c.workers.all().single().revoked)
    }

    @Test fun requestsWithoutAValidDeviceSignatureAreRejected() = runBlocking {
        val paired = c.workers.pair(worker.pairingString("127.0.0.1"))
        val stranger = WorkerClient(paired.baseUrl, paired.certificateSha256, "intrus-0000001", c.workers.deviceKey, c.http)
        try { stranger.capabilities(); fail() } catch (e: WorkerAuthException) { }
        assertTrue(WorkerProtocol.parsePairingString(worker.pairingString("127.0.0.1")) != null)
    }
}
