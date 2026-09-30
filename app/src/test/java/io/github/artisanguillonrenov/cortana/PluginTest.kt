package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.PluginContributions
import io.github.artisanguillonrenov.cortana.contracts.PluginFormat
import io.github.artisanguillonrenov.cortana.contracts.PluginFormatException
import io.github.artisanguillonrenov.cortana.contracts.PluginManifest
import io.github.artisanguillonrenov.cortana.contracts.PluginMcpServer
import io.github.artisanguillonrenov.cortana.contracts.PluginPermissions
import io.github.artisanguillonrenov.cortana.contracts.SkillBundle
import io.github.artisanguillonrenov.cortana.contracts.SkillDefinition
import io.github.artisanguillonrenov.cortana.contracts.SkillStep
import io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig
import io.github.artisanguillonrenov.cortana.core.plugins.PluginException
import io.github.artisanguillonrenov.cortana.core.plugins.PluginManager
import io.github.artisanguillonrenov.cortana.core.plugins.PluginSecrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** VNext phase 22: signed declarative plugins — format, lifecycle, isolation, crash-safe add/remove. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginTest : CortanaTestBase() {
    private val keys: KeyPair = PluginFormat.newKeyPair()
    private val otherKeys: KeyPair = PluginFormat.newKeyPair()
    private lateinit var root: File
    private lateinit var mgr: PluginManager
    private val vault = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Before fun setUpManager() {
        root = Files.createTempDirectory("plugins").toFile()
        // In-memory vault: the real secret store needs AndroidKeyStore (absent under Robolectric).
        mgr = PluginManager(c.db.plugins(), root, c.settings, c.skills, c.registry, object : PluginSecrets {
            override fun put(handle: String, value: String) { vault[handle] = value }
            override fun remove(handle: String?) { handle?.let { vault.remove(it) } }
        }, "2.0.0")
    }

    private fun skill(cap: String) = ContractJson.encodeToString(SkillBundle.serializer(), SkillBundle(
        skill = SkillDefinition(skillId = "s", name = "Météo du jour", version = 1, description = "Cherche la météo", parameters = mapOf("ville" to "Ville"),
            steps = listOf(SkillStep(1, cap, mapOf("query" to "météo {{ville}}"))), createdAt = 0, updatedAt = 0), exportedAt = 0))

    private fun manifest(version: String = "1.0.0", tools: List<String> = listOf("web.search"), mutate: (PluginManifest) -> PluginManifest = { it }) = mutate(PluginManifest(
        id = "fr.exemple.meteo", name = "Météo Plus", version = version, publisher = "Exemple SARL", description = "Météo et guide",
        minCortanaVersion = "1.2.0", capabilities = listOf("skills", "mcp", "documents"),
        permissions = PluginPermissions(tools = tools, network = listOf("mcp.exemple.fr"), secrets = listOf("cle_api")),
        contributions = PluginContributions(skills = listOf("skills/meteo.json"), mcpServers = listOf(PluginMcpServer("meteo", "https://mcp.exemple.fr/mcp", "cle_api")),
            documents = listOf("docs/guide.md")),
    ))

    private fun build(m: PluginManifest = manifest(), k: KeyPair = keys, skillCap: String = "web.search", extra: Map<String, String> = emptyMap()): ByteArray {
        val dir = Files.createTempDirectory("plugin-src").toFile()
        File(dir, "plugin.json").writeText(ContractJson.encodeToString(PluginManifest.serializer(), m))
        File(dir, "skills").mkdirs(); File(dir, "skills/meteo.json").writeText(skill(skillCap))
        File(dir, "docs").mkdirs(); File(dir, "docs/guide.md").writeText("# Guide\nDemandez « météo à Lyon ». Version ${m.version}.")
        extra.forEach { (p, t) -> File(dir, p).apply { parentFile.mkdirs(); writeText(t) } }
        return PluginFormat.pack(dir, k).also { dir.deleteRecursively() }
    }

    private fun fp(k: KeyPair = keys) = PluginFormat.fingerprint(java.util.Base64.getEncoder().encodeToString(k.public.encoded))

    /** Rewrites one entry of a package (tampering). */
    private fun rewrite(pkg: ByteArray, name: String, content: ByteArray?, add: Pair<String, ByteArray>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(pkg.inputStream()).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    val b = zi.readBytes()
                    if (e.name == name) { if (content != null) { z.putNextEntry(ZipEntry(e.name)); z.write(content); z.closeEntry() } }
                    else { z.putNextEntry(ZipEntry(e.name)); z.write(b); z.closeEntry() }
                }
            }
            add?.let { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun refused(bytes: ByteArray): String = (runCatching { PluginFormat.read(bytes) }.exceptionOrNull() as? PluginFormatException)?.message ?: "accepté"

    // ---------------------------------------------------------------- format and integrity

    @Test fun packagesAreVerifiedBeforeAnythingIsWritten() {
        val good = build()
        assertEquals("fr.exemple.meteo", PluginFormat.read(good).manifest.id)
        assertTrue(refused(rewrite(good, "docs/guide.md", "Guide modifié".toByteArray())).contains("empreinte différente"))
        val m = String(ZipInputStream(good.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name == "plugin.json" }; z.readBytes() })
        assertTrue(refused(rewrite(good, "plugin.json", m.replace("Météo Plus", "Météo Pirate").toByteArray())).contains("signature invalide"))
        assertTrue(refused(rewrite(good, "signature.json", null)).contains("non signé"))
        assertTrue(refused(rewrite(good, "x", null, "../evil.txt" to "x".toByteArray())).contains("chemin interdit"))
        assertTrue(refused(rewrite(good, "x", null, "extra.txt" to "x".toByteArray())).contains("non déclaré"))
        assertTrue(refused(build(manifest { it.copy(entrypoints = mapOf("main" to "classes.dex")) })).contains("code exécutable refusé"))
        assertTrue(refused(build(extra = mapOf("lib/payload.dex" to "dex"))).contains("exécutable refusé"))
        assertTrue(refused(build(manifest { it.copy(permissions = it.permissions.copy(network = listOf("autre.fr"))) })).contains("permissions.network"))
        assertTrue(refused(build(manifest { it.copy(contributions = it.contributions.copy(mcpServers = listOf(PluginMcpServer("m", "http://mcp.exemple.fr/mcp")))) })).contains("https"))
        assertTrue(refused(build(manifest { it.copy(permissions = it.permissions.copy(secrets = emptyList())) })).contains("secret non déclaré"))
    }

    // ---------------------------------------------------------------- gate: added and removed without corruption

    @Test fun pluginIsAddedAndRemovedWithoutTouchingAnythingElse() = runBlocking {
        // Owner data that must survive untouched.
        c.settings.update { it.copy(mcpServers = listOf(McpServerConfig("perso", "Perso", url = "https://perso.example/mcp"))) }
        val ownSkill = c.skills.installBundle(skill("web.search"), "owner").skillId
        val before = c.settings.current

        val preview = mgr.inspect(build())
        assertTrue(preview.problems.toString(), preview.problems.isEmpty())
        assertEquals(fp(), preview.fingerprint)
        val p = mgr.install(build(), preview.fingerprint, mapOf("cle_api" to "k-123"))
        assertEquals("active", p.state)
        val skills = c.skills.byOrigin("plugin:fr.exemple.meteo")
        assertEquals(1, skills.size); assertFalse(skills.single().enabled) // candidate: the owner validates and activates it
        val server = c.settings.current.mcpServers.single { it.id.startsWith("plg_") }
        assertEquals("https://mcp.exemple.fr/mcp", server.url); assertFalse(server.trusted)
        assertEquals("k-123", vault[server.authHandle])
        assertTrue(mgr.document("fr.exemple.meteo", "docs/guide.md").contains("météo à Lyon"))
        assertTrue(runCatching { mgr.document("fr.exemple.meteo", "../plugin.json") }.isFailure)
        assertTrue(File(root, "fr.exemple.meteo/1.0.0/plugin.json").isFile)

        mgr.uninstall("fr.exemple.meteo")
        assertNull(c.db.plugins().get("fr.exemple.meteo"))
        assertTrue(c.skills.byOrigin("plugin:fr.exemple.meteo").isEmpty())
        assertTrue(vault.isEmpty())
        assertFalse(File(root, "fr.exemple.meteo").exists())
        assertNotNull(c.db.plugins().versions("fr.exemple.meteo").single().removedAt)
        // Everything else exactly as before.
        assertEquals(before.mcpServers, c.settings.current.mcpServers)
        assertEquals(before.a2aAgents, c.settings.current.a2aAgents)
        assertNotNull(c.skills.get(ownSkill))
    }

    @Test fun updatesKeepThePublisherKeyAndNeverGoBackwards() = runBlocking {
        mgr.install(build(), fp(), mapOf("cle_api" to "k-1"))
        val foreign = mgr.inspect(build(manifest("1.1.0"), k = otherKeys))
        assertTrue(foreign.keyChanged && foreign.problems.any { it.contains("autre clé") })
        assertTrue(runCatching { mgr.install(build(manifest("1.1.0"), k = otherKeys), fp(otherKeys), mapOf("cle_api" to "x")) }.exceptionOrNull() is PluginException)
        assertTrue(mgr.inspect(build(manifest("0.9.0"))).problems.any { it.contains("plus ancienne") })
        assertTrue(runCatching { mgr.install(build(manifest("1.1.0")), fp(otherKeys), mapOf("cle_api" to "x")) }.exceptionOrNull()!!.message!!.contains("approuvée"))
        val up = mgr.install(build(manifest("1.1.0")), fp(), mapOf("cle_api" to "k-2"))
        assertEquals("1.1.0", up.activeVersion)
        assertEquals(listOf("1.1.0"), root.resolve("fr.exemple.meteo").list()!!.toList())
        assertEquals(1, c.skills.byOrigin("plugin:fr.exemple.meteo").size)
        assertEquals("k-2", vault["secret:plugin:fr.exemple.meteo:cle_api"])
        assertEquals(listOf("1.0.0", "1.1.0"), c.db.plugins().versions("fr.exemple.meteo").map { it.version })
    }

    @Test fun capabilitiesAreIsolatedToWhatThePluginDeclared() = runBlocking {
        // A skill calling a tool the plugin did not declare is refused before anything is installed.
        val sneaky = mgr.inspect(build(skillCap = "sms.send"))
        assertTrue(sneaky.problems.toString(), sneaky.problems.any { it.contains("non déclarés") && it.contains("sms.send") })
        assertTrue(runCatching { mgr.install(build(skillCap = "sms.send"), fp(), mapOf("cle_api" to "k")) }.isFailure)
        assertTrue(runCatching { mgr.install(build(), fp(), emptyMap()) }.exceptionOrNull()!!.message!!.contains("Secrets à fournir"))
        assertTrue(mgr.inspect(build(manifest { it.copy(minCortanaVersion = "9.0.0") })).problems.any { it.contains("Cortana 9.0.0") })
        assertNull(c.db.plugins().get("fr.exemple.meteo"))
        assertTrue(c.settings.current.mcpServers.none { it.id.startsWith("plg_") })
        // Disabling withdraws servers and skills stay disabled; enabling brings servers back.
        mgr.install(build(), fp(), mapOf("cle_api" to "k"))
        mgr.setEnabled("fr.exemple.meteo", false)
        assertTrue(c.settings.current.mcpServers.none { it.id.startsWith("plg_") })
        assertEquals("disabled", c.db.plugins().get("fr.exemple.meteo")!!.state)
        mgr.setEnabled("fr.exemple.meteo", true)
        assertEquals(1, c.settings.current.mcpServers.count { it.id.startsWith("plg_") })
    }

    @Test fun interruptedOperationsAreFinishedOrUndoneAtStartup() = runBlocking {
        // Process death after the files were written: the install is completed.
        runCatching { mgr.install(build(), fp(), mapOf("cle_api" to "k"), crashAfter = "files") }
        assertEquals("installing", c.db.plugins().get("fr.exemple.meteo")!!.state)
        File(root, ".staging/left-over").mkdirs()
        File(root, "orphelin/1.0.0").mkdirs()
        val log = mgr.recover()
        assertTrue(log.toString(), log.any { it.contains("installation terminée") } && log.any { it.contains("orphelins") })
        assertEquals("active", c.db.plugins().get("fr.exemple.meteo")!!.state)
        assertEquals(1, c.skills.byOrigin("plugin:fr.exemple.meteo").size)
        assertFalse(File(root, ".staging").exists() || File(root, "orphelin").exists())

        // Death during an update whose files are incomplete: back to the previous version.
        runCatching { mgr.install(build(manifest("1.1.0")), fp(), mapOf("cle_api" to "k"), crashAfter = "files") }
        File(root, "fr.exemple.meteo/1.1.0/.complete").delete()
        mgr.recover()
        val back = c.db.plugins().get("fr.exemple.meteo")!!
        assertEquals("active" to "1.0.0", back.state to back.activeVersion)
        assertFalse(File(root, "fr.exemple.meteo/1.1.0").exists())
        assertEquals(1, c.skills.byOrigin("plugin:fr.exemple.meteo").size)
        assertEquals(1, c.settings.current.mcpServers.count { it.id.startsWith("plg_") })

        // Death in the middle of a removal: finished at startup.
        runCatching { mgr.uninstall("fr.exemple.meteo", crashAfter = "journal") }
        assertEquals("removing", c.db.plugins().get("fr.exemple.meteo")!!.state)
        mgr.recover()
        assertNull(c.db.plugins().get("fr.exemple.meteo"))
        assertTrue(c.skills.byOrigin("plugin:fr.exemple.meteo").isEmpty() && c.settings.current.mcpServers.none { it.id.startsWith("plg_") } && vault.isEmpty())

        // Files deleted behind Cortana's back: the plugin is put out of service, not half-used.
        mgr.install(build(), fp(), mapOf("cle_api" to "k"))
        File(root, "fr.exemple.meteo").deleteRecursively()
        mgr.recover()
        assertEquals("broken", c.db.plugins().get("fr.exemple.meteo")!!.state)
        assertTrue(c.settings.current.mcpServers.none { it.id.startsWith("plg_") })
    }
}
