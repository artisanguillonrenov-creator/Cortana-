package io.github.artisanguillonrenov.cortana.worker

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

/** Owner-only file (0600) where the platform supports POSIX permissions. */
internal fun File.privateWrite(text: String) {
    parentFile?.mkdirs()
    writeText(text)
    runCatching { Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------")) }
}

/**
 * The worker's TLS identity: an EC P-256 key pair and self-signed certificate created once with the
 * JDK's own `keytool`. The tablet pins the certificate's SHA-256 at pairing; the same key signs the
 * pairing proof.
 */
class WorkerIdentity(private val dataDir: File) {
    private val keystoreFile = File(dataDir, "worker.p12")
    private val passFile = File(dataDir, "keystore.pass")
    private val keyStore: KeyStore
    private val password: CharArray

    init {
        dataDir.mkdirs()
        // Keys, device list, jobs and project copies: owner-only.
        runCatching { Files.setPosixFilePermissions(dataDir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        if (!keystoreFile.isFile || !passFile.isFile) create()
        password = passFile.readText().trim().toCharArray()
        keyStore = KeyStore.getInstance("PKCS12").apply { keystoreFile.inputStream().use { load(it, password) } }
    }

    val certificate: X509Certificate get() = keyStore.getCertificate(ALIAS) as X509Certificate
    val certificateSha256: String get() = sha256Hex(certificate.encoded)
    val publicKeyBase64: String get() = b64(certificate.publicKey.encoded)
    private val privateKey: PrivateKey get() = keyStore.getKey(ALIAS, password) as PrivateKey

    fun sign(message: String): String = b64(Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply { initSign(privateKey); update(message.toByteArray()) }.sign())

    fun sslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    private fun create() {
        val pass = randomToken(32)
        passFile.privateWrite(pass)
        keystoreFile.delete()
        val keytool = File(System.getProperty("java.home"), "bin/" + if (isWindows()) "keytool.exe" else "keytool").takeIf { it.isFile }?.path ?: "keytool"
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-validity", "3650", "-dname", "CN=Cortana Worker", "-keystore", keystoreFile.path, "-storetype", "PKCS12",
            // Password read from the 0600 file, never on the command line (visible to other users in `ps`).
            "-storepass:file", passFile.path, "-keypass:file", passFile.path,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && keystoreFile.isFile) { "keytool failed: $out" }
        runCatching { Files.setPosixFilePermissions(keystoreFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    companion object { const val ALIAS = "cortana-worker" }
}

@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    /** X.509 SubjectPublicKeyInfo, base64. */
    val publicKey: String,
    val pairedAt: Long,
    val lastSeenAt: Long? = null,
    val revoked: Boolean = false,
)

/** Paired devices (devices.json, 0600). Revocation is immediate: every request checks it. */
class DeviceStore(dataDir: File) {
    private val file = File(dataDir, "devices.json")
    private val devices = ConcurrentHashMap<String, PairedDevice>()

    init {
        if (file.isFile) runCatching { ContractJson.decodeFromString(ListSerializer(PairedDevice.serializer()), file.readText()) }.getOrDefault(emptyList()).forEach { devices[it.deviceId] = it }
    }

    fun all(): List<PairedDevice> = devices.values.sortedBy { it.pairedAt }
    fun get(id: String): PairedDevice? = devices[id]
    fun add(d: PairedDevice) { devices[d.deviceId] = d; save() }
    fun revoke(id: String): Boolean { val d = devices[id] ?: return false; devices[id] = d.copy(revoked = true); save(); return true }
    fun touch(id: String) { devices[id]?.let { devices[id] = it.copy(lastSeenAt = System.currentTimeMillis()) } }
    @Synchronized private fun save() = file.privateWrite(ContractJson.encodeToString(ListSerializer(PairedDevice.serializer()), all()))
}

/**
 * One-time pairing codes (10 characters, 10 minutes, 5 attempts). Codes live in a file so the
 * `pair` command of a second process can issue one for the running server.
 */
class PairingCodes(dataDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable private data class Code(val code: String, val expiresAt: Long, val attempts: Int = 0)
    private val file = File(dataDir, "pairing-codes.json")

    @Synchronized fun issue(ttlMs: Long = 10 * 60_000L): String {
        val code = (1..10).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        save(load().filter { it.expiresAt > clock() } + Code(code, clock() + ttlMs))
        return code
    }

    /** Consumes the code if valid; every wrong attempt counts against every live code. */
    @Synchronized fun consume(code: String): Boolean {
        val now = clock()
        val live = load().filter { it.expiresAt > now && it.attempts < MAX_ATTEMPTS }
        val hit = live.firstOrNull { MessageDigest.isEqual(it.code.toByteArray(), code.trim().uppercase().toByteArray()) }
        save(if (hit != null) live - hit else live.map { it.copy(attempts = it.attempts + 1) })
        return hit != null
    }

    private fun load(): List<Code> = if (file.isFile) runCatching { ContractJson.decodeFromString(ListSerializer(Code.serializer()), file.readText()) }.getOrDefault(emptyList()) else emptyList()
    private fun save(codes: List<Code>) = file.privateWrite(ContractJson.encodeToString(ListSerializer(Code.serializer()), codes))

    companion object {
        const val MAX_ATTEMPTS = 5
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
    }
}

class AuthException(val status: Int, message: String) : Exception(message)

/** Verifies the device signature, freshness and single use of every authenticated request. */
class RequestVerifier(private val devices: DeviceStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun verify(method: String, pathAndQuery: String, header: (String) -> String?, body: ByteArray): PairedDevice {
        val deviceId = header(WorkerProtocol.H_DEVICE) ?: throw AuthException(401, "appareil non identifié")
        val device = devices.get(deviceId) ?: throw AuthException(401, "appareil inconnu")
        if (device.revoked) throw AuthException(403, "appareil révoqué")
        val ts = header(WorkerProtocol.H_TIMESTAMP)?.toLongOrNull() ?: throw AuthException(401, "horodatage manquant")
        val now = clock()
        if (kotlin.math.abs(now - ts) > WorkerProtocol.MAX_SKEW_MS) throw AuthException(401, "horodatage hors délai")
        val nonce = header(WorkerProtocol.H_NONCE)?.takeIf { it.length in 16..128 } ?: throw AuthException(401, "nonce manquant")
        val sig = header(WorkerProtocol.H_SIGNATURE) ?: throw AuthException(401, "signature manquante")
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(device.publicKey)))
        val ok = runCatching {
            Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply {
                initVerify(key); update(WorkerProtocol.canonical(method, pathAndQuery, ts, nonce, sha256Hex(body)).toByteArray())
            }.verify(unb64(sig))
        }.getOrDefault(false)
        if (!ok) throw AuthException(401, "signature invalide")
        if (seen.putIfAbsent("$deviceId:$nonce", ts) != null) throw AuthException(401, "requête rejouée")
        if (seen.size > 10_000) seen.entries.removeIf { now - it.value > 2 * WorkerProtocol.MAX_SKEW_MS }
        devices.touch(deviceId)
        return device
    }
}

internal fun randomToken(n: Int): String {
    val r = SecureRandom()
    val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..n).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
}

internal fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
