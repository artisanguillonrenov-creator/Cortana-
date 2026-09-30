package io.github.artisanguillonrenov.cortana.core.update

import io.github.artisanguillonrenov.cortana.core.backup.BackupService
import io.github.artisanguillonrenov.cortana.core.backup.CompatibilityManifest
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What a release says about itself. Signed, as the exact JSON text, by the key that signs the APK. */
@Serializable
data class UpdateManifest(
    val format: String = UpdateService.FORMAT,
    val formatVersion: Int = 1,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    /** https URL of the APK (a relative path resolves against the manifest URL). */
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int,
    /** SHA-256 of the APK signing certificate (lowercase hex, no separators): must be the installed one. */
    val signingCertSha256: String,
    /** Oldest installed versionCode this release upgrades from (older ones must install an intermediate release). */
    val minUpgradeFromVersionCode: Int = 1,
    val compatibility: CompatibilityManifest,
    val releaseNotes: String = "",
    val publishedAt: Long = 0,
)

/** The signed envelope: [manifest] is the JSON text actually signed (no canonicalisation ambiguity). */
@Serializable
data class SignedUpdate(val manifest: String, val signature: String, val algorithm: String = "SHA256withRSA")

/** The running app, as the system sees it (a seam: PackageManager on the device). */
data class InstalledApp(val packageName: String, val versionCode: Long, val sdkInt: Int, val signingKeys: List<PublicKey>, val signingCertSha256: Set<String>)

/** What the system reads from a downloaded APK before installing it. */
data class ApkInfo(val packageName: String, val versionCode: Long, val minSdk: Int, val signingCertSha256: Set<String>)

interface ApkInspector { fun inspect(apk: File): ApkInfo? }

/** Hands a verified APK to the system installer; the owner confirms in the system dialog. */
interface ApkInstaller { suspend fun handOff(apk: File): String }

sealed interface UpdateState {
    data object Idle : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Refused(val reasons: List<String>) : UpdateState
    data class Ready(val manifest: UpdateManifest, val apk: File, val backup: File) : UpdateState
    data class HandedOff(val manifest: UpdateManifest, val detail: String) : UpdateState
}

class UpdateException(message: String) : Exception(message)

/**
 * Update system (phase 30, blueprint §55). Checks the owner's release channel, verifies the
 * signed manifest against the key that signed the installed app (no second key to lose), checks
 * compatibility (version strictly higher, same package, same signing certificate, schema not
 * older, minimum SDK, upgrade path), downloads over https with a bounded size and a SHA-256 check,
 * inspects the APK as the system will, backs everything up, then hands the APK to the system
 * installer — never silently: Android asks the owner. No code is ever loaded by Cortana itself.
 */
class UpdateService(
    private val settings: SettingsRepository,
    http: OkHttpClient,
    private val dir: File,
    private val installed: () -> InstalledApp,
    private val inspector: ApkInspector,
    private val installer: ApkInstaller,
    private val backups: BackupService,
    private val compatibility: CompatibilityManifest,
    private val audit: AuditLog,
) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build()
    private val lock = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Fetches and verifies the manifest; never downloads the APK. */
    suspend fun check(): UpdateState = lock.withLock {
        val url = settings.current.updateManifestUrl ?: throw UpdateException("aucune source de mise à jour configurée")
        val next = runCatching {
            val body = get(url, MAX_MANIFEST)
            val m = verifyManifest(String(body))
            val problems = compatibilityProblems(m)
            when {
                m.versionCode.toLong() <= installed().versionCode -> UpdateState.UpToDate(System.currentTimeMillis())
                problems.isNotEmpty() -> UpdateState.Refused(problems)
                else -> UpdateState.Available(m)
            }
        }.getOrElse { UpdateState.Refused(listOf(it.message ?: it.javaClass.simpleName)) }
        audit.record("system", "update.check", null, if (next is UpdateState.Refused) "error" else "ok", """{"state":"${next.javaClass.simpleName}"}""")
        _state.value = next
        next
    }

    /** The signature is checked against every key that signed the installed app; the JSON is parsed only after. */
    fun verifyManifest(envelopeJson: String): UpdateManifest {
        val env = runCatching { AppJson.decodeFromString(SignedUpdate.serializer(), envelopeJson) }.getOrElse { throw UpdateException("manifeste illisible") }
        if (env.algorithm !in setOf("SHA256withRSA", "SHA256withECDSA")) throw UpdateException("algorithme de signature non pris en charge : ${env.algorithm}")
        val sig = runCatching { Base64.getDecoder().decode(env.signature) }.getOrElse { throw UpdateException("signature illisible") }
        val app = installed()
        val ok = app.signingKeys.any { key ->
            runCatching { Signature.getInstance(env.algorithm).apply { initVerify(key); update(env.manifest.toByteArray()) }.verify(sig) }.getOrDefault(false)
        }
        if (!ok) throw UpdateException("signature du manifeste invalide : il n'a pas été signé par la clé de Cortana")
        val m = runCatching { AppJson.decodeFromString(UpdateManifest.serializer(), env.manifest) }.getOrElse { throw UpdateException("manifeste signé mais invalide") }
        if (m.format != FORMAT || m.formatVersion > 1) throw UpdateException("format de mise à jour inconnu")
        return m
    }

    fun compatibilityProblems(m: UpdateManifest): List<String> {
        val app = installed()
        return buildList {
            if (m.packageName != app.packageName) add("paquet différent (${m.packageName}) : ce n'est pas une mise à jour de Cortana")
            if (m.signingCertSha256.lowercase() !in app.signingCertSha256) add("certificat de signature différent : l'installation remplacerait l'identité de l'application")
            if (app.versionCode < m.minUpgradeFromVersionCode) add("installez d'abord une version intermédiaire (depuis ${m.minUpgradeFromVersionCode})")
            if (m.compatibility.dbSchema < compatibility.dbSchema) add("schéma de données plus ancien (v${m.compatibility.dbSchema} < v${compatibility.dbSchema}) : ce serait une rétrogradation")
            if (m.minSdk > app.sdkInt) add("Android trop ancien pour cette version (API ${m.minSdk} requise)")
            if (!HEX64.matches(m.sha256.lowercase()) || m.size !in 1..MAX_APK) add("empreinte ou taille de l'APK invalide")
        }
    }

    /** Backs up, downloads and verifies the APK of an available update. */
    suspend fun prepare(m: UpdateManifest): UpdateState.Ready = lock.withLock {
        val problems = compatibilityProblems(m)
        if (m.versionCode.toLong() <= installed().versionCode) throw UpdateException("version déjà installée ou plus ancienne")
        if (problems.isNotEmpty()) throw UpdateException(problems.joinToString(" ; "))
        val backup = backups.newFile("avant-mise-a-jour").also { backups.create(it) }
        val base = settings.current.updateManifestUrl!!.toHttpUrlOrNull() ?: throw UpdateException("source invalide")
        val apkUrl = base.resolve(m.apkUrl)?.toString() ?: throw UpdateException("adresse de l'APK invalide")
        val apk = File(dir.apply { mkdirs() }, "cortana-${m.versionCode}.apk")
        withContext(Dispatchers.IO) {
            val bytes = get(apkUrl, m.size)
            if (bytes.size.toLong() != m.size || Hash.sha256Bytes(bytes) != m.sha256.lowercase()) throw UpdateException("APK altéré : empreinte ou taille différente du manifeste signé")
            apk.writeBytes(bytes)
        }
        val info = inspector.inspect(apk)
        val apkProblems = buildList {
            if (info == null) add("APK illisible par le système")
            else {
                if (info.packageName != installed().packageName) add("paquet de l'APK différent")
                if (info.versionCode != m.versionCode.toLong()) add("versionCode de l'APK différent du manifeste")
                if (info.signingCertSha256.map { it.lowercase() }.toSet() != setOf(m.signingCertSha256.lowercase())) add("APK signé par un autre certificat")
                if (info.minSdk > installed().sdkInt) add("Android trop ancien pour cet APK")
            }
        }
        if (apkProblems.isNotEmpty()) {
            apk.delete()
            audit.record("system", "update.verify", m.versionName, "error", """{"problems":${apkProblems.size}}""")
            throw UpdateException(apkProblems.joinToString(" ; "))
        }
        audit.record("system", "update.prepare", m.versionName, "ok", """{"versionCode":${m.versionCode},"backup":"${backup.name}"}""")
        UpdateState.Ready(m, apk, backup).also { _state.value = it }
    }

    /** Owner action: hands the verified APK to the system installer (which asks for confirmation). */
    suspend fun install(ready: UpdateState.Ready): String = lock.withLock {
        if (Hash.sha256File(ready.apk) != ready.manifest.sha256.lowercase()) throw UpdateException("APK modifié depuis sa vérification")
        val detail = installer.handOff(ready.apk)
        audit.record("owner", "update.install_handoff", ready.manifest.versionName, "ok", """{"versionCode":${ready.manifest.versionCode}}""")
        _state.value = UpdateState.HandedOff(ready.manifest, detail)
        detail
    }

    private suspend fun get(url: String, max: Long): ByteArray = withContext(Dispatchers.IO) { fetch(url, max, 0) }

    /** https only (plain http only to this device's loopback); redirects followed by hand under the same rule. */
    private fun fetch(url: String, max: Long, hops: Int): ByteArray {
        if (hops > 5) throw UpdateException("trop de redirections")
        val u = url.toHttpUrlOrNull() ?: throw UpdateException("adresse invalide")
        if (!u.isHttps && u.host !in LOOPBACK) throw UpdateException("https obligatoire pour les mises à jour")
        return client.newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (r.isRedirect) {
                val loc = r.header("Location")?.let { u.resolve(it) } ?: throw UpdateException("redirection invalide")
                return fetch(loc.toString(), max, hops + 1)
            }
            if (!r.isSuccessful) throw UpdateException("source injoignable (HTTP ${r.code})")
            read(r, max)
        }
    }

    private fun read(r: okhttp3.Response, max: Long): ByteArray {
        val body = r.body ?: throw UpdateException("réponse vide")
        if (body.contentLength() > max) throw UpdateException("fichier plus gros qu'annoncé")
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(64 * 1024); var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > max) throw UpdateException("fichier plus gros qu'annoncé"); out.write(buf, 0, n) }
        }
        return out.toByteArray()
    }

    companion object {
        const val FORMAT = "cortana-update"
        const val MAX_MANIFEST = 64 * 1024L
        const val MAX_APK = 300L * 1024 * 1024
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
