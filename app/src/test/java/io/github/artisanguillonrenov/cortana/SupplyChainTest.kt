package io.github.artisanguillonrenov.cortana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 32 supply chain: no secret or key material in tracked files, every dependency pinned in
 * the version catalog and verified by checksum, no dynamic code download. Plain JVM test.
 */
class SupplyChainTest {
    private val root = File("..").canonicalFile

    private fun tracked(): List<File> {
        val p = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        return out.split('\u0000').filter { it.isNotBlank() }.map { File(root, it) }.filter { it.isFile }
    }

    private val secretPatterns = mapOf(
        "clé privée" to Regex("-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
        "jeton GitHub" to Regex("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
        "clé AWS" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "clé Google" to Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        "jeton Slack" to Regex("\\bxox[baprs]-[0-9A-Za-z-]{10,}\\b"),
        "jeton de bot Telegram" to Regex("\\b\\d{8,10}:AA[0-9A-Za-z_-]{33}\\b"),
        "clé OpenAI/compatible" to Regex("\\bsk-(proj-)?[A-Za-z0-9]{32,}\\b"),
        // A literal value on the same line (reading it from the untracked properties file is fine).
        "mot de passe de magasin" to Regex("(?im)(storePassword|keyPassword)[ \\t]*=[ \\t]*[\"']?[^\\s\"'(){}$]{4,}[\"']?[ \\t]*$"),
    )

    @Test fun noSecretOrKeyMaterialIsTracked() {
        val files = tracked()
        assertTrue("git ls-files returned nothing", files.size > 100)
        // A .pem is key material when it holds a private key; a public certificate (release/signing-cert.pem) is not.
        val keyFiles = files.filter { f ->
            f.name.matches(Regex("(?i).*\\.(jks|p12|pfx|keystore|key)$")) || f.name == "keystore.properties" || f.name == "local.properties" ||
                (f.name.endsWith(".pem", ignoreCase = true) && f.readText().contains("PRIVATE KEY"))
        }
        assertEquals("key material tracked", emptyList<File>(), keyFiles)
        assertTrue("the public signing certificate is tracked, and only that", File(root, "release/signing-cert.pem").readText().let { it.contains("BEGIN CERTIFICATE") && !it.contains("PRIVATE") })
        val findings = files.filter { it.length() < 2_000_000 && it.extension !in setOf("png", "jpg", "webp", "jar", "zip", "apk", "ttf", "traineddata", "so") }.flatMap { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@flatMap emptyList()
            secretPatterns.filter { (_, re) -> re.containsMatchIn(text) }.map { (kind, _) -> "${f.relativeTo(root)} : $kind" }
        }
        assertEquals("possible secrets in tracked files", emptyList<String>(), findings)
    }

    @Test fun everyDependencyIsPinnedAndVerified() {
        val meta = File(root, "gradle/verification-metadata.xml").readText()
        assertTrue(meta.contains("<verify-metadata>true</verify-metadata>"))
        val components = Regex("<component ").findAll(meta).count()
        assertTrue("verified components: $components", components > 400)
        assertEquals("components without a sha256", 0, Regex("<artifact name=\"[^\"]+\">\\s*</artifact>").findAll(meta).count())
        // Every module dependency goes through the version catalog (no inline coordinates).
        listOf("app/build.gradle.kts", "worker/build.gradle.kts", "contracts/build.gradle.kts").forEach { path ->
            val inline = Regex("""(implementation|api|testImplementation|androidTestImplementation|debugImplementation)\s*\(\s*"[^"]+:[^"]+:[^"]+"""").findAll(File(root, path).readText()).map { it.value }.toList()
            assertEquals("$path inline coordinates", emptyList<String>(), inline)
        }
        // One extra repository only, restricted to its single group.
        val settings = File(root, "settings.gradle.kts").readText()
        assertTrue(settings.contains("RepositoriesMode.FAIL_ON_PROJECT_REPOS") && settings.contains("includeGroup(\"com.github.adaptech-cz.Tesseract4Android\")"))
    }

    @Test fun noDynamicCodeIsDownloadedOrLoaded() {
        val sources = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val loaders = sources.filter { Regex("""DexClassLoader|PathClassLoader|InMemoryDexClassLoader|URLClassLoader|System\.loadLibrary\(\s*[a-z]""").containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), loaders)
    }
}
