package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.SyncManifest
import io.github.artisanguillonrenov.cortana.contracts.WorkerJob
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun deviceKey() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test fun identityIsCreatedOnceAndSignsVerifiably() {
        val dir = tmp.newFolder("w")
        val id = WorkerIdentity(dir)
        val fp = id.certificateSha256
        assertEquals(fp, WorkerIdentity(dir).certificateSha256) // stable across restarts
        val sig = id.sign("bonjour")
        assertTrue(Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply { initVerify(id.certificate.publicKey); update("bonjour".toByteArray()) }.verify(unb64(sig)))
        assertFalse(File(dir, "keystore.pass").readText().isBlank())
    }

    @Test fun pairingCodesAreSingleUseExpiringAndAttemptLimited() {
        var now = 0L
        val codes = PairingCodes(tmp.newFolder("c")) { now }
        val c1 = codes.issue()
        assertTrue(codes.consume(c1.lowercase()))
        assertFalse("single use", codes.consume(c1))
        val c2 = codes.issue()
        repeat(PairingCodes.MAX_ATTEMPTS) { assertFalse(codes.consume("MAUVAIS")) }
        assertFalse("locked after too many wrong attempts", codes.consume(c2))
        val c3 = codes.issue(ttlMs = 1000)
        now = 2000
        assertFalse("expired", codes.consume(c3))
    }

    @Test fun requestsMustBeSignedFreshAndNeverReplayed() {
        val dir = tmp.newFolder("d")
        val devices = DeviceStore(dir)
        val kp = deviceKey()
        devices.add(PairedDevice("tablette-01", "Tab", b64(kp.public.encoded), 0))
        var now = 1_000_000L
        val v = RequestVerifier(devices) { now }
        fun headers(path: String, body: ByteArray, ts: Long = now, nonce: String = "n".repeat(20) + System.nanoTime()): Map<String, String> {
            val sig = Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply { initSign(kp.private); update(WorkerProtocol.canonical("POST", path, ts, nonce, sha256Hex(body)).toByteArray()) }.sign()
            return mapOf(WorkerProtocol.H_DEVICE to "tablette-01", WorkerProtocol.H_TIMESTAMP to ts.toString(), WorkerProtocol.H_NONCE to nonce, WorkerProtocol.H_SIGNATURE to b64(sig))
        }
        val body = "{}".toByteArray()
        val h = headers("/v1/jobs", body)
        assertEquals("tablette-01", v.verify("POST", "/v1/jobs", { h[it] }, body).deviceId)
        fun rejected(block: () -> Unit, code: Int) = try { block(); false } catch (e: AuthException) { e.status == code }
        assertTrue("replay", rejected({ v.verify("POST", "/v1/jobs", { h[it] }, body) }, 401))
        val h2 = headers("/v1/jobs", body)
        assertTrue("tampered body", rejected({ v.verify("POST", "/v1/jobs", { h2[it] }, "{\"x\":1}".toByteArray()) }, 401))
        val h3 = headers("/v1/jobs", body)
        assertTrue("other path", rejected({ v.verify("POST", "/v1/revoke", { h3[it] }, body) }, 401))
        val h4 = headers("/v1/jobs", body, ts = now - WorkerProtocol.MAX_SKEW_MS - 1)
        assertTrue("stale", rejected({ v.verify("POST", "/v1/jobs", { h4[it] }, body) }, 401))
        devices.revoke("tablette-01")
        val h5 = headers("/v1/jobs", body)
        assertTrue("revoked", rejected({ v.verify("POST", "/v1/jobs", { h5[it] }, body) }, 403))
    }

    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (p, c) -> z.putNextEntry(ZipEntry(p)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun deltaSyncAndZipSlipProtection() {
        val store = WorkspaceStore(tmp.newFolder("s"))
        val files = mapOf("a.txt" to "A", "src/b.txt" to "B")
        val m = SyncManifest(workspaceId = "ws-000001", files = files.mapValues { sha256Hex(it.value.toByteArray()) })
        assertEquals(listOf("a.txt", "src/b.txt"), store.plan("dev-00001", m).need)
        assertEquals(2, store.apply("dev-00001", "ws-000001", m, zip(files)))
        File(store.dir("dev-00001", "ws-000001"), "build/out.bin").apply { parentFile.mkdirs(); writeText("cache") }
        val m2 = SyncManifest(workspaceId = "ws-000001", files = mapOf("a.txt" to sha256Hex("A2".toByteArray())))
        val plan = store.plan("dev-00001", m2)
        assertEquals(listOf("a.txt"), plan.need); assertEquals(listOf("src/b.txt"), plan.delete)
        store.apply("dev-00001", "ws-000001", m2, zip(mapOf("a.txt" to "A2")))
        assertFalse(File(store.dir("dev-00001", "ws-000001"), "src/b.txt").exists())
        assertTrue("worker build outputs survive a sync", File(store.dir("dev-00001", "ws-000001"), "build/out.bin").exists())
        val evil = SyncManifest(workspaceId = "ws-000001", files = mapOf("../../evil.txt" to sha256Hex("x".toByteArray())))
        assertTrue(runCatching { store.apply("dev-00001", "ws-000001", evil, zip(mapOf("../../evil.txt" to "x"))) }.isFailure)
        assertTrue(runCatching { store.apply("dev-00001", "ws-000001", m2, zip(mapOf("a.txt" to "falsifié"))) }.isFailure) // hash mismatch
    }

    private fun waitDone(jobs: JobManager, id: String, dev: String = "dev-00001", timeoutMs: Long = 20_000): io.github.artisanguillonrenov.cortana.contracts.WorkerJobResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { jobs.status(id, dev)?.result?.let { return it }; Thread.sleep(50) }
        throw AssertionError("job $id not finished")
    }

    @Test fun processJobsTimeOutAndCancelKillingTheTree() {
        val dir = tmp.newFolder("j")
        val sandbox = Sandbox(dir); val store = WorkspaceStore(dir)
        val jobs = JobManager(dir, sandbox, store)
        val ws = store.dir("dev-00001", "ws-000001")
        jobs.submit("dev-00001", WorkerJob(jobId = "job-timeout-1", workspaceId = "ws-000001", kind = "exec", command = listOf("sh", "-c", "(sleep 3; echo tard > late.txt) & sleep 30"), timeoutMs = 700, sandbox = "process"))
        assertEquals("timed_out", waitDone(jobs, "job-timeout-1").status)
        jobs.submit("dev-00001", WorkerJob(jobId = "job-cancel-01", workspaceId = "ws-000001", kind = "exec", command = listOf("sh", "-c", "(sleep 3; echo tard > late2.txt) & sleep 30"), timeoutMs = 60_000, sandbox = "process"))
        Thread.sleep(400)
        jobs.cancel("job-cancel-01", "dev-00001")
        assertEquals("cancelled", waitDone(jobs, "job-cancel-01").status)
        Thread.sleep(3_500)
        assertFalse(File(ws, "late.txt").exists()); assertFalse(File(ws, "late2.txt").exists())
        assertTrue("jobs belong to their device", runCatching { jobs.status("job-cancel-01", "autre-appareil") }.isFailure)
        jobs.shutdown()
    }

    @Test fun artifactsAreCollectedAndHashed() {
        val dir = tmp.newFolder("a")
        val store = WorkspaceStore(dir)
        val jobs = JobManager(dir, Sandbox(dir), store)
        jobs.submit("dev-00001", WorkerJob(jobId = "job-artifact1", workspaceId = "ws-000001", kind = "build", command = listOf("sh", "-c", "mkdir -p out && echo binaire > out/app.bin"),
            sandbox = "process", artifactGlobs = listOf("out/*.bin")))
        val r = waitDone(jobs, "job-artifact1")
        assertEquals("succeeded", r.status)
        val a = jobs.artifacts("job-artifact1", "dev-00001").single()
        assertEquals("out_app.bin", a.name)
        assertEquals(sha256Hex("binaire\n".toByteArray()), a.sha256)
        assertEquals("binaire\n", jobs.artifactFile("job-artifact1", "dev-00001", "out_app.bin").readText())
        jobs.shutdown()
    }

    @Test fun isolatedSandboxConfinesFilesystemNetworkAndProcesses() {
        val dir = tmp.newFolder("i")
        val sandbox = Sandbox(dir)
        assumeTrue("user namespaces unavailable on this host", sandbox.isolatedAvailable)
        val store = WorkspaceStore(dir)
        val jobs = JobManager(dir, sandbox, store)
        File(dir, "secret-worker.txt").writeText("clé du worker")
        val script = """
            echo ok > dans-le-projet.txt
            (touch /usr/pirate 2>/dev/null && echo SYS_WRITABLE) || echo sys_ro
            (cat ${dir.absolutePath}/secret-worker.txt 2>/dev/null && echo DATA_VISIBLE) || echo data_hidden
            (ls ${System.getProperty("user.home")} 2>/dev/null | grep -q . && echo HOME_VISIBLE) || echo home_hidden
            n=${'$'}(tail -n +3 /proc/net/dev | grep -vc '^ *lo:'); [ "${'$'}n" = "0" ] && echo net_none || echo NET_PRESENT
            p=${'$'}(ls /proc | grep -c '^[0-9]'); [ "${'$'}p" -lt 10 ] && echo pid_isolated || echo PIDS_VISIBLE
            pwd
        """.trimIndent()
        jobs.submit("dev-00001", WorkerJob(jobId = "job-isolated1", workspaceId = "ws-000001", kind = "exec", command = listOf("sh", "-c", script), sandbox = "isolated", network = NetworkMode.DENY))
        val r = waitDone(jobs, "job-isolated1")
        assertEquals(r.stdout + r.stderr, "succeeded", r.status)
        for (expected in listOf("sys_ro", "data_hidden", "home_hidden", "net_none", "pid_isolated", "/tmp/ws")) assertTrue("$expected in ${r.stdout}", r.stdout.contains(expected))
        assertEquals("ok", File(store.dir("dev-00001", "ws-000001"), "dans-le-projet.txt").readText().trim())
        assertEquals("isolated+no-network", r.sandbox)
        jobs.shutdown()
    }

    @Test fun pairingStringRoundTrips() {
        val s = WorkerProtocol.pairingString("192.168.1.20", 8765, "w-abc", "a".repeat(64), "ABCD2345EF")
        val p = WorkerProtocol.parsePairingString(s)!!
        assertEquals("https://192.168.1.20:8765", p.baseUrl); assertEquals("w-abc", p.workerId); assertEquals("ABCD2345EF", p.code)
        assertEquals(null, WorkerProtocol.parsePairingString("cortana-worker://h:1?id=x&fp=court&code=y"))
    }
}
