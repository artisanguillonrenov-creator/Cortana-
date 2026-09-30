package io.github.artisanguillonrenov.cortana.core.plugins

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.PluginFormat
import io.github.artisanguillonrenov.cortana.contracts.PluginFormatException
import io.github.artisanguillonrenov.cortana.contracts.PluginManifest
import io.github.artisanguillonrenov.cortana.contracts.PluginPackage
import io.github.artisanguillonrenov.cortana.core.a2a.A2aAgentConfig
import io.github.artisanguillonrenov.cortana.core.mcp.McpAdapter
import io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig
import io.github.artisanguillonrenov.cortana.core.memory.PluginDao
import io.github.artisanguillonrenov.cortana.core.memory.PluginEntity
import io.github.artisanguillonrenov.cortana.core.memory.PluginVersionEntity
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

class PluginException(message: String) : Exception(message)

/** What the owner sees before installing: identity, publisher key, permissions, contributions, problems. */
data class PluginPreview(
    val manifest: PluginManifest, val fingerprint: String, val packageSha256: String, val installedVersion: String?,
    val keyChanged: Boolean, val problems: List<String>, val warnings: List<String>,
)

/** Secret store access limited to what plugins need (values never leave it towards the plugin). */
interface PluginSecrets {
    fun put(handle: String, value: String)
    fun remove(handle: String?)
}

/**
 * The one plugin registry (blueprint §21 "packages/plugins is the only extension lifecycle system").
 * Plugins are declarative (see [PluginFormat]): skill packs become disabled candidate skills limited
 * to the tools the plugin declared, MCP servers and A2A agents are added untrusted, documents are
 * read-only. Install, update and removal are journaled (`plugins.state`) and staged on disk so an
 * interruption at any point is finished or undone by [recover] — never a half-installed plugin.
 */
class PluginManager(
    private val dao: PluginDao,
    private val root: File,
    private val settings: SettingsRepository,
    private val skills: SkillService,
    private val registry: ToolRegistry,
    private val secrets: PluginSecrets,
    private val appVersion: String,
    private val onExternalChange: suspend () -> Unit = {},
    private val audit: suspend (String, String, String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val staging get() = File(root, ".staging")

    fun observe(): Flow<List<PluginEntity>> = dao.observe()
    suspend fun all() = dao.all()
    fun origin(id: String) = "plugin:$id"
    private fun prefix(id: String) = "plg_" + McpAdapter.serverSlug(id).take(12) + "_"
    private fun dir(id: String, version: String) = File(File(root, id), version)
    /** Deterministic handle per (plugin, secret name): stable across updates, rollbacks and recovery. */
    private fun handles(m: PluginManifest) = m.permissions.secrets.associateWith { "secret:plugin:${m.id}:$it" }

    // ---------------------------------------------------------------- inspection

    fun read(bytes: ByteArray): PluginPackage = try { PluginFormat.read(bytes) } catch (e: PluginFormatException) { throw PluginException("Paquet refusé : ${e.message}") }

    suspend fun inspect(bytes: ByteArray): PluginPreview {
        val pkg = read(bytes)
        val m = pkg.manifest
        val existing = dao.get(m.id)
        val problems = mutableListOf<String>(); val warnings = mutableListOf<String>()
        if (PluginFormat.compareVersions(appVersion, m.minCortanaVersion) < 0) problems += "Demande Cortana ${m.minCortanaVersion} ou plus récent (installée : $appVersion)"
        if (existing != null && existing.state != "broken") {
            if (existing.publicKey != pkg.signature.publicKey) problems += "Signé par une autre clé que la version installée (${existing.keyFingerprint}) : mise à jour refusée"
            val cmp = PluginFormat.compareVersions(m.version, existing.activeVersion)
            if (cmp < 0) problems += "Version ${m.version} plus ancienne que l'installée (${existing.activeVersion})"
            if (cmp == 0) problems += "Version ${m.version} déjà installée"
        }
        for (d in m.dependencies) {
            val dep = dao.get(d.id)
            if (dep == null || dep.state != "active" || PluginFormat.compareVersions(dep.activeVersion, d.minVersion) < 0) problems += "Dépend de ${d.id} ≥ ${d.minVersion} (absent ou trop ancien)"
        }
        val unknownTools = m.permissions.tools.filter { registry.byCapability(it) == null }
        if (unknownTools.isNotEmpty()) warnings += "Outils demandés inconnus ici : ${unknownTools.joinToString()}"
        for (path in m.contributions.skills) {
            val caps = runCatching { skills.bundleCapabilities(pkg.files[path]!!.decodeToString()) }.getOrElse { problems += "Compétence illisible : $path"; continue }
            val outside = caps.filter { it !in m.permissions.tools }
            if (outside.isNotEmpty()) problems += "La compétence $path utilise des outils non déclarés : ${outside.joinToString()}"
        }
        return PluginPreview(m, pkg.publisherKeyFingerprint, pkg.sha256, existing?.activeVersion, existing != null && existing.publicKey != pkg.signature.publicKey, problems, warnings)
    }

    // ---------------------------------------------------------------- install / update

    /**
     * Installs after the owner approved exactly this publisher key ([approvedFingerprint]) and gave
     * the declared secrets. Any failure rolls back to the previous state.
     */
    suspend fun install(bytes: ByteArray, approvedFingerprint: String, secretValues: Map<String, String> = emptyMap(), crashAfter: String? = null): PluginEntity = mutex.withLock {
        val preview = inspect(bytes)
        if (preview.problems.isNotEmpty()) throw PluginException(preview.problems.joinToString(" ; "))
        if (preview.fingerprint != approvedFingerprint) throw PluginException("La clé de l'éditeur ne correspond pas à celle que vous avez approuvée")
        val pkg = read(bytes)
        val m = pkg.manifest
        val missing = m.permissions.secrets.filter { secretValues[it].isNullOrBlank() }
        if (missing.isNotEmpty()) throw PluginException("Secrets à fournir : ${missing.joinToString()}")
        val previous = dao.get(m.id)?.takeIf { it.state != "broken" }
        val now = clock()
        // 1. Journal the intent.
        dao.upsert(PluginEntity(m.id, m.name, m.publisher, preview.fingerprint, pkg.signature.publicKey, m.version, "installing",
            pkg.manifestBytes.decodeToString(), previous?.secretHandlesJson ?: "{}", previous?.installedAt ?: now, now, null))
        val handles = handles(m)
        try {
            // 2. Stage and verify on disk, then move into place atomically.
            withContext(Dispatchers.IO) {
                val stage = File(staging, "${m.id}-${m.version}-${Ids.new().take(8)}").apply { mkdirs() }
                File(stage, PluginFormat.MANIFEST).writeBytes(pkg.manifestBytes)
                pkg.files.forEach { (path, b) -> File(stage, path).apply { parentFile?.mkdirs(); writeBytes(b) } }
                File(stage, ".complete").writeText(pkg.sha256)
                val target = dir(m.id, m.version)
                target.parentFile?.mkdirs(); target.deleteRecursively()
                if (!stage.renameTo(target)) throw PluginException("Écriture impossible dans le dossier des plugins")
            }
            if (crashAfter == "files") throw SimulatedCrash()
            // 3. Contributions: previous ones out, new ones in.
            previous?.let { deactivate(it.pluginId, keepSecrets = true, deleteSkills = true) }
            handles.forEach { (name, h) -> secrets.put(h, secretValues[name]!!) }
            activate(m, pkg, handles)
            if (crashAfter == "contributions") throw SimulatedCrash()
            // 4. Commit.
            val done = PluginEntity(m.id, m.name, m.publisher, preview.fingerprint, pkg.signature.publicKey, m.version, "active",
                pkg.manifestBytes.decodeToString(), encode(handles), previous?.installedAt ?: now, clock(), null)
            dao.upsert(done)
            dao.upsertVersion(PluginVersionEntity(m.id, m.version, pkg.sha256, clock()))
            previous?.let { p ->
                if (p.activeVersion != m.version) withContext(Dispatchers.IO) { dir(m.id, p.activeVersion).deleteRecursively() }
                (decode(p.secretHandlesJson).values - handles.values.toSet()).forEach { secrets.remove(it) } // secrets the new version no longer declares
            }
            audit(if (previous == null) "plugin.install" else "plugin.update", m.id, "${previous?.activeVersion ?: "∅"} → ${m.version} (${preview.fingerprint})")
            onExternalChange()
            done
        } catch (e: SimulatedCrash) {
            throw e // tests: leave the journaled state as a process death would
        } catch (e: Exception) {
            // Roll back: new contributions and files out, previous version back.
            runCatching { deactivate(m.id, keepSecrets = true, deleteSkills = true) }
            if (previous == null) handles.values.forEach { secrets.remove(it) }
            withContext(Dispatchers.IO) { if (previous?.activeVersion != m.version) dir(m.id, m.version).deleteRecursively() }
            if (previous != null) {
                val prevPkg = runCatching { loadInstalled(previous) }.getOrNull()
                if (prevPkg != null) runCatching { activate(prevPkg.first, null, decode(previous.secretHandlesJson), prevPkg.second) }
                dao.upsert(previous)
            } else dao.delete(m.id)
            audit("plugin.install", m.id, "échec, état précédent restauré : ${e.message}")
            onExternalChange()
            throw if (e is PluginException) e else PluginException("Installation annulée : ${e.message}")
        }
    }

    class SimulatedCrash : RuntimeException("simulated process death")

    // ---------------------------------------------------------------- contributions

    private suspend fun activate(m: PluginManifest, pkg: PluginPackage?, handles: Map<String, String>, installedDir: File? = null) {
        fun file(path: String): ByteArray = pkg?.files?.get(path) ?: File(installedDir!!, path).readBytes()
        for (path in m.contributions.skills) skills.installBundle(file(path).decodeToString(), origin(m.id))
        val p = prefix(m.id)
        val servers = m.contributions.mcpServers.map { s ->
            McpServerConfig(p + McpAdapter.serverSlug(s.name).take(16), "${s.name} (plugin ${m.name})", "http", url = s.url, authHandle = s.tokenSecret?.let { handles[it] }, trusted = false, createdAt = clock())
        }
        val agents = m.contributions.a2aAgents.map { a ->
            A2aAgentConfig(p + McpAdapter.serverSlug(a.name).take(16), "${a.name} (plugin ${m.name})", a.cardUrl, a.tokenSecret?.let { handles[it] }, createdAt = clock())
        }
        settings.update { st -> st.copy(
            mcpServers = st.mcpServers.filterNot { it.id.startsWith(p) } + servers,
            a2aAgents = st.a2aAgents.filterNot { it.id.startsWith(p) } + agents,
        ) }
    }

    private suspend fun deactivate(id: String, keepSecrets: Boolean, deleteSkills: Boolean) {
        val p = prefix(id)
        settings.update { st -> st.copy(mcpServers = st.mcpServers.filterNot { it.id.startsWith(p) }, a2aAgents = st.a2aAgents.filterNot { it.id.startsWith(p) }) }
        for (s in skills.byOrigin(origin(id))) if (deleteSkills) skills.delete(s.skillId) else skills.disable(s.skillId)
        if (!keepSecrets) dao.get(id)?.let { e ->
            decode(e.secretHandlesJson).values.forEach { secrets.remove(it) }
            runCatching { ContractJson.decodeFromString(PluginManifest.serializer(), e.manifestJson) }.getOrNull()?.let { m -> handles(m).values.forEach { secrets.remove(it) } }
        }
    }

    // ---------------------------------------------------------------- enable / disable / remove

    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val e = dao.get(id) ?: throw PluginException("Plugin inconnu")
        if (enabled && e.state == "disabled") {
            val (m, dir) = loadInstalled(e)
            // Skills stay as they were (the owner re-activates them); servers and agents come back.
            val p = prefix(id); val handles = decode(e.secretHandlesJson)
            val servers = m.contributions.mcpServers.map { s -> McpServerConfig(p + McpAdapter.serverSlug(s.name).take(16), "${s.name} (plugin ${m.name})", "http", url = s.url, authHandle = s.tokenSecret?.let { handles[it] }, createdAt = clock()) }
            val agents = m.contributions.a2aAgents.map { a -> A2aAgentConfig(p + McpAdapter.serverSlug(a.name).take(16), "${a.name} (plugin ${m.name})", a.cardUrl, a.tokenSecret?.let { handles[it] }, createdAt = clock()) }
            settings.update { st -> st.copy(mcpServers = st.mcpServers.filterNot { it.id.startsWith(p) } + servers, a2aAgents = st.a2aAgents.filterNot { it.id.startsWith(p) } + agents) }
            dao.upsert(e.copy(state = "active", updatedAt = clock()))
        } else if (!enabled && e.state == "active") {
            deactivate(id, keepSecrets = true, deleteSkills = false)
            dao.upsert(e.copy(state = "disabled", updatedAt = clock()))
        }
        audit(if (enabled) "plugin.enable" else "plugin.disable", id, "")
        onExternalChange()
    }

    suspend fun uninstall(id: String, crashAfter: String? = null) = mutex.withLock {
        val e = dao.get(id) ?: return@withLock
        dao.upsert(e.copy(state = "removing", updatedAt = clock()))
        if (crashAfter == "journal") throw SimulatedCrash()
        finishRemoval(e)
        audit("plugin.uninstall", id, e.activeVersion)
        onExternalChange()
    }

    private suspend fun finishRemoval(e: PluginEntity) {
        deactivate(e.pluginId, keepSecrets = false, deleteSkills = true)
        withContext(Dispatchers.IO) { File(root, e.pluginId).deleteRecursively() }
        dao.versions(e.pluginId).filter { it.removedAt == null }.forEach { dao.upsertVersion(it.copy(removedAt = clock())) }
        dao.delete(e.pluginId)
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Startup reconciliation: staging leftovers deleted; an interrupted install is completed if its
     * files are complete and verified, otherwise undone (back to the previous version); an
     * interrupted removal is finished; files without a record are deleted; a record without files
     * becomes "broken" with its contributions withdrawn.
     */
    suspend fun recover(): List<String> = mutex.withLock {
        val log = mutableListOf<String>()
        withContext(Dispatchers.IO) { if (staging.exists()) { staging.deleteRecursively(); log += "dossier de préparation nettoyé" } }
        for (e in dao.all()) {
            when (e.state) {
                "removing" -> { finishRemoval(e); log += "${e.pluginId} : suppression terminée" }
                "installing" -> {
                    val m = runCatching { ContractJson.decodeFromString(PluginManifest.serializer(), e.manifestJson) }.getOrNull()
                    val d = dir(e.pluginId, e.activeVersion)
                    val ok = m != null && withContext(Dispatchers.IO) { File(d, ".complete").isFile && m.files.all { (p, h) -> File(d, p).isFile && PluginFormat.sha256(File(d, p).readBytes()) == h } }
                    if (ok) {
                        deactivate(e.pluginId, keepSecrets = true, deleteSkills = true)
                        activate(m!!, null, handles(m), d)
                        dao.upsert(e.copy(state = "active", secretHandlesJson = encode(handles(m)), updatedAt = clock()))
                        dao.upsertVersion(PluginVersionEntity(e.pluginId, e.activeVersion, withContext(Dispatchers.IO) { File(d, ".complete").readText() }, clock()))
                        // An older version left on disk is the one this install replaced.
                        withContext(Dispatchers.IO) { File(root, e.pluginId).listFiles()?.filter { it.name != e.activeVersion }?.forEach { it.deleteRecursively() } }
                        log += "${e.pluginId} ${e.activeVersion} : installation terminée"
                    } else {
                        deactivate(e.pluginId, keepSecrets = true, deleteSkills = true)
                        withContext(Dispatchers.IO) { d.deleteRecursively() }
                        val prev = dao.versions(e.pluginId).filter { it.removedAt == null && it.version != e.activeVersion }.maxByOrNull { it.installedAt }
                        val prevDir = prev?.let { dir(e.pluginId, it.version) }
                        if (prevDir != null && withContext(Dispatchers.IO) { File(prevDir, PluginFormat.MANIFEST).isFile }) {
                            val pm = ContractJson.decodeFromString(PluginManifest.serializer(), withContext(Dispatchers.IO) { File(prevDir, PluginFormat.MANIFEST).readText() })
                            activate(pm, null, handles(pm), prevDir)
                            dao.upsert(e.copy(state = "active", activeVersion = pm.version, manifestJson = File(prevDir, PluginFormat.MANIFEST).readText(), secretHandlesJson = encode(handles(pm)), updatedAt = clock(),
                                lastError = "Mise à jour interrompue : version ${pm.version} rétablie"))
                            log += "${e.pluginId} : mise à jour annulée, ${pm.version} rétablie"
                        } else {
                            m?.let { handles(it).values.forEach(secrets::remove) }
                            dao.delete(e.pluginId); withContext(Dispatchers.IO) { File(root, e.pluginId).deleteRecursively() }
                            log += "${e.pluginId} : installation interrompue annulée"
                        }
                    }
                }
                else -> if (withContext(Dispatchers.IO) { !File(dir(e.pluginId, e.activeVersion), PluginFormat.MANIFEST).isFile } && e.state != "broken") {
                    deactivate(e.pluginId, keepSecrets = true, deleteSkills = false)
                    dao.upsert(e.copy(state = "broken", lastError = "Fichiers du plugin introuvables : réinstallez-le", updatedAt = clock()))
                    log += "${e.pluginId} : fichiers manquants → hors service"
                }
            }
        }
        val known = dao.all().map { it.pluginId }.toSet()
        withContext(Dispatchers.IO) {
            root.listFiles()?.filter { it.isDirectory && it.name != ".staging" && it.name !in known }?.forEach { it.deleteRecursively(); log += "${it.name} : fichiers orphelins supprimés" }
        }
        if (log.isNotEmpty()) { audit("plugin.recover", "plugins", log.joinToString(" ; ")); onExternalChange() }
        log
    }

    // ---------------------------------------------------------------- documents

    suspend fun documents(id: String): List<String> = dao.get(id)?.takeIf { it.state == "active" }?.let { e -> loadInstalled(e).first.contributions.documents }.orEmpty()

    suspend fun document(id: String, path: String): String {
        val e = dao.get(id)?.takeIf { it.state == "active" } ?: throw PluginException("Plugin inconnu ou inactif")
        val (m, dir) = loadInstalled(e)
        if (path !in m.contributions.documents) throw PluginException("Document non déclaré par ce plugin")
        return withContext(Dispatchers.IO) { File(dir, path).readBytes() }.let { b ->
            if (PluginFormat.sha256(b) != m.files[path]) throw PluginException("Document modifié sur le disque : réinstallez le plugin")
            b.decodeToString().take(200_000)
        }
    }

    private suspend fun loadInstalled(e: PluginEntity): Pair<PluginManifest, File> = withContext(Dispatchers.IO) {
        val d = dir(e.pluginId, e.activeVersion)
        ContractJson.decodeFromString(PluginManifest.serializer(), File(d, PluginFormat.MANIFEST).readText()) to d
    }

    private val handlesSer = MapSerializer(String.serializer(), String.serializer())
    private fun encode(m: Map<String, String>) = ContractJson.encodeToString(handlesSer, m)
    private fun decode(s: String): Map<String, String> = runCatching { ContractJson.decodeFromString(handlesSer, s) }.getOrDefault(emptyMap())
}
