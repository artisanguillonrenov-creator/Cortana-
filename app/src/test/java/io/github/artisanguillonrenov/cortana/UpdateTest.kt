package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.backup.BackupService
import io.github.artisanguillonrenov.cortana.core.backup.SecretAccess
import io.github.artisanguillonrenov.cortana.core.memory.MemoryStatus
import io.github.artisanguillonrenov.cortana.core.memory.MemoryTypes
import io.github.artisanguillonrenov.cortana.core.update.ApkInfo
import io.github.artisanguillonrenov.cortana.core.update.ApkInspector
import io.github.artisanguillonrenov.cortana.core.update.ApkInstaller
import io.github.artisanguillonrenov.cortana.core.update.InstalledApp
import io.github.artisanguillonrenov.cortana.core.update.SignedUpdate
import io.github.artisanguillonrenov.cortana.core.update.UpdateException
import io.github.artisanguillonrenov.cortana.core.update.UpdateManifest
import io.github.artisanguillonrenov.cortana.core.update.UpdateService
import io.github.artisanguillonrenov.cortana.core.update.UpdateState
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.random.Random

/**
 * Phase 30 gate: an upgrade keeps the data and the signature — a release is accepted only when
 * its manifest is signed by the key of the installed app and its APK carries the same package and
 * signing certificate with a higher version; the data is backed up before the system installer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateTest : CortanaTestBase() {
    private val releaseKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKey: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val cert = "6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33"
    private val pkg = "io.github.artisanguillonrenov.cortana"
    private val apk = Random(7).nextBytes(300_000)
    private lateinit var dir: File
    private var apkInfo: ApkInfo? = null
    private val handedOff = mutableListOf<File>()

    private val installed = InstalledApp(pkg, 1, 35, listOf(releaseKey.public), setOf(cert))

    @Before fun setUpUpdates() { dir = File(app.cacheDir, "upd-${Ids.new()}").apply { mkdirs() } }

    private fun service() = UpdateService(c.settings, c.http, dir, { installed },
        object : ApkInspector { override fun inspect(apk: File) = apkInfo },
        object : ApkInstaller { override suspend fun handOff(apk: File): String { handedOff += apk; return "confiée à Android" } },
        BackupService(c.db, object : SecretAccess { override fun get(handle: String) = null; override fun put(handle: String, value: String) {}; override fun has(handle: String) = false },
            c.artifacts.root, File(dir, "backups"), c.compatibility, c.audit),
        c.compatibility, c.audit)

    private fun manifest(versionCode: Int = 2, certSha: String = cert, schema: Int = c.compatibility.dbSchema, minUpgrade: Int = 1, bytes: ByteArray = apk) = UpdateManifest(
        packageName = pkg, versionCode = versionCode, versionName = "2.0.0-rc1", apkUrl = "cortana-2.apk", sha256 = UpdateService.sha256Hex(bytes), size = bytes.size.toLong(),
        minSdk = 30, signingCertSha256 = certSha, minUpgradeFromVersionCode = minUpgrade, compatibility = c.compatibility.copy(appVersion = "2.0.0-rc1", appVersionCode = versionCode, dbSchema = schema),
        releaseNotes = "Cortana VNext", publishedAt = 1,
    )

    private fun sign(m: UpdateManifest, key: KeyPair = releaseKey, tamper: (String) -> String = { it }): String {
        val text = AppJson.encodeToString(UpdateManifest.serializer(), m)
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key.private); update(text.toByteArray()) }.sign()
        return AppJson.encodeToString(SignedUpdate.serializer(), SignedUpdate(tamper(text), Base64.getEncoder().encodeToString(sig)))
    }

    private fun serve(envelope: String, apkBytes: ByteArray = apk) {
        server.enqueue(MockResponse().setBody(envelope))
        server.enqueue(MockResponse().setBody(Buffer().write(apkBytes)))
        runBlocking { c.settings.update { it.copy(updateManifestUrl = server.url("/channel/cortana-update.json").toString()) } }
    }

    private fun serveApk(bytes: ByteArray) = server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

    private fun rows(table: String) = c.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    @Test fun gateUpgradeKeepsDataAndSignature() {
        runBlocking { c.memory.save("Préfère le thé", MemoryTypes.PREFERENCE, MemoryStatus.ACTIVE, "explicit") }
        runBlocking { c.conversations.createSession(title = "Avant la mise à jour") }
        val before = listOf("memories", "sessions", "settings").associateWith(::rows)
        val m = manifest()
        serve(sign(m))
        apkInfo = ApkInfo(pkg, 2, 30, setOf(cert))
        val u = service()
        val st = runBlocking { u.check() }
        assertTrue("$st", st is UpdateState.Available && st.manifest == m)
        val ready = runBlocking { u.prepare(m) }
        // Downloaded from the path relative to the channel, byte for byte.
        assertEquals("/channel/cortana-update.json", server.takeRequest().path)
        assertEquals("/channel/cortana-2.apk", server.takeRequest().path)
        assertTrue(ready.apk.readBytes().contentEquals(apk))
        // Data backed up before anything is installed, and restorable.
        val insp = runBlocking { BackupService(c.db, object : SecretAccess { override fun get(handle: String) = null; override fun put(handle: String, value: String) {}; override fun has(handle: String) = false },
            c.artifacts.root, File(dir, "backups"), c.compatibility, null).inspect(ready.backup) }
        assertTrue(insp.verified && insp.compatible)
        assertEquals(before["memories"], insp.manifest!!.entries.single { it.name == "data/memories.jsonl" }.rows)
        assertTrue(handedOff.isEmpty()) // nothing installed without the owner
        assertEquals("confiée à Android", runBlocking { u.install(ready) })
        assertEquals(listOf(ready.apk), handedOff)
        // The update path changed no data.
        assertEquals(before["memories"], rows("memories")); assertEquals(before["sessions"], rows("sessions"))
        assertTrue(runBlocking { c.db.audit().allAscending() }.map { it.action }.containsAll(listOf("update.check", "update.prepare", "update.install_handoff")))

        // Signature continuity: an APK signed by any other certificate is never handed off.
        handedOff.clear()
        serveApk(apk)
        apkInfo = ApkInfo(pkg, 2, 30, setOf("0".repeat(64)))
        val e = runCatching { runBlocking { service().prepare(m) } }.exceptionOrNull()
        assertTrue(e is UpdateException && e.message!!.contains("autre certificat"))
        assertFalse(File(dir, "cortana-2.apk").exists())
        assertTrue(handedOff.isEmpty())
    }

    @Test fun manifestsNotSignedByCortanaOrIncompatibleAreRefused() {
        val u = service()
        fun refusedWith(envelope: String, expect: String) {
            server.enqueue(MockResponse().setBody(envelope))
            runBlocking { c.settings.update { it.copy(updateManifestUrl = server.url("/u.json").toString()) } }
            val st = runBlocking { u.check() }
            assertTrue("$st", st is UpdateState.Refused && st.reasons.joinToString().contains(expect))
        }
        refusedWith(sign(manifest(), key = otherKey), "signature du manifeste invalide")
        refusedWith(sign(manifest()) { it.replace("\"versionCode\":2", "\"versionCode\":3") }, "signature du manifeste invalide")
        refusedWith("{pas du json", "illisible")
        refusedWith(sign(manifest(certSha = "a".repeat(64))), "certificat de signature différent")
        refusedWith(sign(manifest(schema = 1)), "schéma de données plus ancien")
        refusedWith(sign(manifest(minUpgrade = 2)), "version intermédiaire")
        refusedWith(sign(manifest().copy(packageName = "com.evil.cortana")), "paquet différent")
        // Same or older version: nothing to do (never a downgrade).
        server.enqueue(MockResponse().setBody(sign(manifest(versionCode = 1))))
        assertTrue(runBlocking { u.check() } is UpdateState.UpToDate)
    }

    @Test fun downloadsAreVerifiedBoundedAndHttpsOnly() {
        val m = manifest()
        // An APK that differs from the signed hash is rejected and not kept.
        serve(sign(m), apk.copyOf().also { it[10] = (it[10] + 1).toByte() })
        apkInfo = ApkInfo(pkg, 2, 30, setOf(cert))
        runBlocking { service().check() }
        assertTrue(runCatching { runBlocking { service().prepare(m) } }.exceptionOrNull()!!.message!!.contains("altéré"))
        assertFalse(File(dir, "cortana-2.apk").exists())
        // Bigger than announced: aborted while reading.
        val small = manifest(bytes = apk.copyOf(1000))
        serveApk(apk)
        assertTrue(runCatching { runBlocking { service().prepare(small) } }.exceptionOrNull()!!.message!!.contains("plus gros"))
        // A wrong APK version is caught by the system inspection.
        serveApk(apk); apkInfo = ApkInfo(pkg, 3, 30, setOf(cert))
        assertTrue(runCatching { runBlocking { service().prepare(m) } }.exceptionOrNull()!!.message!!.contains("versionCode"))
        // Cleartext channels to the internet are refused before any request.
        runBlocking { c.settings.update { it.copy(updateManifestUrl = "http://updates.example.com/cortana-update.json") } }
        val st = runBlocking { service().check() }
        assertTrue(st is UpdateState.Refused && st.reasons.single().contains("https"))
    }

    @Test fun releaseHistoryIsMonotoneUnderOneCertificate() {
        val json = AppJson.parseToJsonElement(File("../release/released.json").readText()).jsonObject
        val releases = json["releases"]!!.jsonArray.map { it.jsonObject }
        val codes = releases.map { it["versionCode"]!!.jsonPrimitive.int }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(BuildConfig.VERSION_CODE >= codes.max())
        val pinned = json["signingCertSha256"]!!.jsonPrimitive.content
        assertTrue(releases.all { it["signingCertSha256"]!!.jsonPrimitive.content == pinned })
        assertEquals("io.github.artisanguillonrenov.cortana", json["packageName"]!!.jsonPrimitive.content)
        assertEquals(json["packageName"]!!.jsonPrimitive.content, BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }
}
