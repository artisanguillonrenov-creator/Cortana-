package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.backup.BackupService
import io.github.artisanguillonrenov.cortana.core.backup.SecretAccess
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.update.ApkInspector
import io.github.artisanguillonrenov.cortana.core.update.ApkInstaller
import io.github.artisanguillonrenov.cortana.core.update.InstalledApp
import io.github.artisanguillonrenov.cortana.core.update.UpdateService
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Phase 33: what was published stays true. A released database schema is frozen (any change goes
 * through a new version and migration), and the update manifest produced by
 * `tools/make_update_manifest.py` for the release candidate is accepted by the app's own verifier
 * with the public release certificate — as an upgrade of 1.2.0, and by nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseTest : CortanaTestBase() {
    private val history = AppJson.parseToJsonElement(File("../release/released.json").readText()).jsonObject
    private val releases = history["releases"]!!.jsonArray.map { it.jsonObject }
    private val pinned = history["signingCertSha256"]!!.jsonPrimitive.content

    @Test fun releasedSchemasAreFrozen() {
        val dir = File("schemas/${CortanaDatabase::class.java.name}")
        for (r in releases) {
            val schema = r["dbSchema"]!!.jsonPrimitive.int
            val exported = AppJson.parseToJsonElement(File(dir, "$schema.json").readText()).jsonObject["database"]!!.jsonObject
            assertEquals("schéma v$schema publié en ${r["versionName"]} modifié : passer par une nouvelle version et sa migration",
                r["dbIdentityHash"]!!.jsonPrimitive.content, exported["identityHash"]!!.jsonPrimitive.content)
        }
        assertTrue(CortanaDatabase.VERSION >= releases.maxOf { it["dbSchema"]!!.jsonPrimitive.int })
    }

    @Test fun thePublishedManifestIsAnUpgradeOf120SignedByTheReleaseKey() {
        val cert = File("../release/signing-cert.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
        assertEquals(pinned, Hash.sha256Bytes(cert.encoded))
        val latest = releases.maxBy { it["versionCode"]!!.jsonPrimitive.int }
        fun service(from: Long) = UpdateService(c.settings, c.http, File(app.cacheDir, "upd"),
            { InstalledApp(BuildConfig.APPLICATION_ID.removeSuffix(".debug"), from, 35, listOf(cert.publicKey), setOf(pinned)) },
            object : ApkInspector { override fun inspect(apk: File) = null },
            object : ApkInstaller { override suspend fun handOff(apk: File) = error("jamais appelé") },
            BackupService(c.db, object : SecretAccess { override fun get(handle: String) = null; override fun put(handle: String, value: String) {}; override fun has(handle: String) = false },
                c.artifacts.root, File(app.cacheDir, "bk"), c.compatibility.copy(appVersion = "1.2.0", appVersionCode = 1, dbSchema = 1), c.audit),
            c.compatibility.copy(appVersion = "1.2.0", appVersionCode = 1, dbSchema = 1), c.audit)
        val envelope = File("../release/cortana-update.json").readText()
        val m = service(1).verifyManifest(envelope)
        assertEquals(latest["versionCode"]!!.jsonPrimitive.int, m.versionCode)
        assertEquals(latest["versionName"]!!.jsonPrimitive.content, m.versionName)
        assertEquals(BuildConfig.VERSION_CODE, m.versionCode)
        assertEquals(latest["dbSchema"]!!.jsonPrimitive.int, m.compatibility.dbSchema)
        assertEquals(CortanaDatabase.VERSION, m.compatibility.dbSchema)
        assertEquals(pinned, m.signingCertSha256)
        assertEquals(emptyList<String>(), service(1).compatibilityProblems(m))
        // The same text with one byte changed no longer verifies.
        val tampered = envelope.replace("\\\"versionCode\\\":${m.versionCode}", "\\\"versionCode\\\":${m.versionCode + 1}")
        assertTrue(tampered != envelope)
        assertTrue(runCatching { service(1).verifyManifest(tampered) }.exceptionOrNull()!!.message!!.contains("signature du manifeste invalide"))
    }
}
