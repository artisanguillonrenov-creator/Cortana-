package io.github.artisanguillonrenov.cortana.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Cortana plugin package (doc 05 §18, blueprint §21), format 1. A plugin is **declarative**: skill
 * packs, MCP servers, A2A agents and read-only documents. It never carries code for the tablet's
 * process (`entrypoints` must be empty, executable files are refused); anything that runs does so
 * out of process through MCP/A2A, under the same policy as every other tool.
 */
@Serializable
data class PluginManifest(
    val format: String = "cortana.plugin",
    val formatVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String = "",
    /** Plugin API the package targets (compatibility range: [minApi, maxApi] of the app). */
    val apiVersion: Int = 1,
    val minCortanaVersion: String = "0.0.0",
    val capabilities: List<String> = emptyList(),
    val permissions: PluginPermissions = PluginPermissions(),
    val contributions: PluginContributions = PluginContributions(),
    val dependencies: List<PluginDependency> = emptyList(),
    /** Declarative data migrations are not supported in format 1; must stay empty. */
    val migrations: List<String> = emptyList(),
    /** In-process code is never loaded; must stay empty. */
    val entrypoints: Map<String, String> = emptyMap(),
    val configSchema: JsonObject? = null,
    /** Every other file of the package with its SHA-256 (integrity). */
    val files: Map<String, String> = emptyMap(),
)

@Serializable
data class PluginPermissions(
    /** Capabilities the plugin's skills may call (anything else is refused at install). */
    val tools: List<String> = emptyList(),
    /** Hosts its MCP servers and agents may be reached at. */
    val network: List<String> = emptyList(),
    /** Named secrets the owner provides at install (kept in the tablet's secret store). */
    val secrets: List<String> = emptyList(),
)

@Serializable
data class PluginContributions(
    val skills: List<String> = emptyList(),
    val mcpServers: List<PluginMcpServer> = emptyList(),
    val a2aAgents: List<PluginA2aAgent> = emptyList(),
    val documents: List<String> = emptyList(),
)

@Serializable data class PluginMcpServer(val name: String, val url: String, val tokenSecret: String? = null)
@Serializable data class PluginA2aAgent(val name: String, val cardUrl: String, val tokenSecret: String? = null)
@Serializable data class PluginDependency(val id: String, val minVersion: String = "0.0.0")

/** `signature.json`: ECDSA P-256 over the exact bytes of `plugin.json`. */
@Serializable
data class PluginSignature(val algorithm: String = "SHA256withECDSA", val publicKey: String, val signature: String)

class PluginFormatException(message: String) : Exception(message)

/** A verified package read into memory (never extracted before verification). */
class PluginPackage(val manifest: PluginManifest, val manifestBytes: ByteArray, val signature: PluginSignature, val files: Map<String, ByteArray>, val sha256: String) {
    val publisherKeyFingerprint: String get() = PluginFormat.fingerprint(signature.publicKey)
}

object PluginFormat {
    const val MANIFEST = "plugin.json"
    const val SIGNATURE = "signature.json"
    const val API_VERSION = 1
    const val MAX_FILES = 500
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private val ID = Regex("^[a-z0-9]+([._-][a-z0-9]+)*$")
    private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")
    private val EXECUTABLE = Regex("(?i)\\.(dex|jar|apk|aab|so|class|exe|dll|dylib|sh|bat|cmd|ps1|js|mjs|py|rb|pl|bin)$")

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun fingerprint(publicKeyB64: String): String = sha256(Base64.getDecoder().decode(publicKeyB64)).chunked(4).take(8).joinToString(":")

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    fun privateKey(b64: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
    fun publicKey(b64: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    /** Compares dotted versions numerically (pre-release suffixes ignored). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }

    /** Builds a signed package from a folder holding `plugin.json` and the files it lists (publisher side). */
    fun pack(dir: File, keys: KeyPair): ByteArray {
        val raw = ContractJson.decodeFromString(PluginManifest.serializer(), File(dir, MANIFEST).readText())
        val files = dir.walkTopDown().filter { it.isFile && it.name != MANIFEST && it.name != SIGNATURE }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to sha256(it.readBytes()) }.toSortedMap()
        val manifestBytes = ContractJson.encodeToString(PluginManifest.serializer(), raw.copy(files = files)).toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(manifestBytes) }.sign()
        val signature = PluginSignature(publicKey = Base64.getEncoder().encodeToString(keys.public.encoded), signature = Base64.getEncoder().encodeToString(sig))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, b: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
            put(MANIFEST, manifestBytes)
            put(SIGNATURE, ContractJson.encodeToString(PluginSignature.serializer(), signature).toByteArray())
            files.keys.forEach { put(it, File(dir, it).readBytes()) }
        }
        return out.toByteArray()
    }

    /**
     * Reads and verifies a package: bounded size and entry count, no path escaping, signature over
     * the manifest, every listed file present with its hash and nothing unlisted, no executable,
     * well-formed manifest. Throws [PluginFormatException] with a readable reason.
     */
    fun read(bytes: ByteArray): PluginPackage {
        if (bytes.size > MAX_TOTAL_BYTES) throw PluginFormatException("paquet trop volumineux")
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() } || name.contains(':')) throw PluginFormatException("chemin interdit dans le paquet : $name")
                if (entries.size >= MAX_FILES) throw PluginFormatException("trop de fichiers")
                val buf = ByteArrayOutputStream(); val tmp = ByteArray(64 * 1024)
                while (true) { val n = z.read(tmp); if (n < 0) break; buf.write(tmp, 0, n); total += n; if (total > MAX_TOTAL_BYTES) throw PluginFormatException("contenu décompressé trop volumineux") }
                if (entries.put(name, buf.toByteArray()) != null) throw PluginFormatException("fichier en double : $name")
            }
        }
        val manifestBytes = entries.remove(MANIFEST) ?: throw PluginFormatException("plugin.json absent")
        val sigBytes = entries.remove(SIGNATURE) ?: throw PluginFormatException("paquet non signé (signature.json absent)")
        val signature = runCatching { ContractJson.decodeFromString(PluginSignature.serializer(), sigBytes.decodeToString()) }.getOrElse { throw PluginFormatException("signature illisible") }
        if (signature.algorithm != "SHA256withECDSA") throw PluginFormatException("algorithme de signature non pris en charge : ${signature.algorithm}")
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey(signature.publicKey)); update(manifestBytes) }.verify(Base64.getDecoder().decode(signature.signature))
        }.getOrDefault(false)
        if (!ok) throw PluginFormatException("signature invalide : le paquet a été modifié ou n'est pas signé par cette clé")
        val m = runCatching { ContractJson.decodeFromString(PluginManifest.serializer(), manifestBytes.decodeToString()) }.getOrElse { throw PluginFormatException("plugin.json invalide : ${it.message}") }
        validate(m)
        for ((path, hash) in m.files) {
            val b = entries[path] ?: throw PluginFormatException("fichier manquant : $path")
            if (sha256(b) != hash.lowercase()) throw PluginFormatException("empreinte différente pour $path")
        }
        (entries.keys - m.files.keys).firstOrNull()?.let { throw PluginFormatException("fichier non déclaré dans le manifeste : $it") }
        return PluginPackage(m, manifestBytes, signature, entries, sha256(bytes))
    }

    fun validate(m: PluginManifest) {
        if (m.format != "cortana.plugin" || m.formatVersion != 1) throw PluginFormatException("format ${m.format} v${m.formatVersion} non pris en charge")
        if (!ID.matches(m.id) || m.id.length > 64) throw PluginFormatException("identifiant invalide : ${m.id}")
        if (!SEMVER.matches(m.version)) throw PluginFormatException("version invalide : ${m.version}")
        if (m.apiVersion != API_VERSION) throw PluginFormatException("API de plugin ${m.apiVersion} non prise en charge (attendue : $API_VERSION)")
        if (m.entrypoints.isNotEmpty()) throw PluginFormatException("code exécutable refusé : les plugins sont déclaratifs (entrypoints doit être vide)")
        if (m.migrations.isNotEmpty()) throw PluginFormatException("migrations non prises en charge au format 1")
        m.files.keys.firstOrNull { EXECUTABLE.containsMatchIn(it) }?.let { throw PluginFormatException("fichier exécutable refusé : $it") }
        val listed = m.contributions.skills + m.contributions.documents
        listed.firstOrNull { it !in m.files }?.let { throw PluginFormatException("contribution absente du paquet : $it") }
        val hosts = m.permissions.network.map { it.lowercase() }.toSet()
        (m.contributions.mcpServers.map { it.name to it.url } + m.contributions.a2aAgents.map { it.name to it.cardUrl }).forEach { (n, url) ->
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: throw PluginFormatException("adresse invalide pour $n")
            if (u.scheme != "https") throw PluginFormatException("$n : https obligatoire pour un plugin")
            if (u.host?.lowercase() !in hosts) throw PluginFormatException("$n : l'hôte ${u.host} n'est pas déclaré dans permissions.network")
        }
        (m.contributions.mcpServers.mapNotNull { it.tokenSecret } + m.contributions.a2aAgents.mapNotNull { it.tokenSecret }).firstOrNull { it !in m.permissions.secrets }
            ?.let { throw PluginFormatException("secret non déclaré dans permissions.secrets : $it") }
    }
}
