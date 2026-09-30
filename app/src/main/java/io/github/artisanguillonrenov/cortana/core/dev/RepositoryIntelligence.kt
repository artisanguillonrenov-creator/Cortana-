package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.CodeHit
import io.github.artisanguillonrenov.cortana.contracts.RepositoryProfile
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** VCS facts for the profile; implemented by GitService (phase 10). */
interface VcsProbe {
    data class Snapshot(val vcs: String, val branch: String?, val head: String?, val uncommitted: Int)
    fun snapshot(root: File): Snapshot?
}

/**
 * Repository Intelligence (doc 03 §3): the opening pipeline that must run before a non-trivial
 * change — VCS state, instructions, build systems, languages, manifests, modules, dependencies,
 * tests, CI, scripts, generated/binary files, potential secrets and a compact tree.
 */
class RepositoryIntelligence(private val workspaces: WorkspaceManager, private val vcs: VcsProbe?) {

    suspend fun profile(w: WorkspaceEntity): RepositoryProfile = withContext(Dispatchers.IO) {
        val fs = workspaces.fs(w)
        val root = fs.root
        val files = fs.walk().take(MAX_FILES).toList()
        val rel = files.associateWith { fs.relative(it) }
        val byType = files.groupingBy { ext(it) }.eachCount().toList().sortedByDescending { it.second }.take(20).toMap()
        var lines = 0L
        var binaries = 0
        val secrets = mutableListOf<String>()
        for (f in files) {
            if (f.length() > MAX_TEXT_BYTES || isBinary(f)) { binaries++; continue }
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            lines += text.count { it == '\n' } + 1
            if (secrets.size < 20) SECRET_PATTERNS.firstOrNull { it.second.containsMatchIn(text) }?.let { secrets += "${rel[f]} (${it.first})" }
        }
        val names = rel.values.toSet()
        val build = detectBuildSystems(names)
        val manifests = names.filter { it.substringAfterLast('/') in MANIFESTS }.sorted().take(40)
        val snap = vcs?.snapshot(root)
        RepositoryProfile(
            workspaceId = w.workspaceId,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            totalLines = lines,
            filesByType = byType,
            languages = detectLanguages(byType.keys),
            buildSystems = build,
            manifests = manifests,
            modules = modules(root, names),
            dependencies = dependencies(root, manifests).take(80),
            testDirs = names.mapNotNull { n -> TEST_DIR.find(n)?.let { n.substring(0, it.range.last + 1).trimEnd('/') } }.distinct().sorted().take(20),
            testFiles = names.count { TEST_FILE.containsMatchIn(it.substringAfterLast('/')) || TEST_DIR.containsMatchIn(it) },
            ciFiles = names.filter { it.startsWith(".github/workflows/") || it == ".gitlab-ci.yml" || it.startsWith(".circleci/") || it == "Jenkinsfile" || it == "azure-pipelines.yml" }.sorted(),
            scripts = names.filter { it.endsWith(".sh") || it.endsWith(".ps1") || it.endsWith(".bat") || it == "Makefile" || it == "Taskfile.yml" || it == "gradlew" }.sorted().take(30),
            instructionFiles = names.filter { it.substringAfterLast('/').uppercase() in INSTRUCTIONS }.sorted(),
            generatedDirs = root.listFiles()?.filter { it.isDirectory && it.name in WorkspaceFs.GENERATED_DIRS }?.map { it.name }?.sorted() ?: emptyList(),
            binaryFiles = binaries,
            potentialSecrets = secrets,
            vcs = snap?.vcs, branch = snap?.branch, head = snap?.head, uncommittedChanges = snap?.uncommitted ?: 0,
            tree = tree(root),
            generatedAt = System.currentTimeMillis(),
        )
    }

    /** Runs the pipeline and stores the profile and detected stacks on the workspace. */
    suspend fun inspectAndStore(w: WorkspaceEntity): RepositoryProfile {
        val p = profile(w)
        workspaces.touch(w) {
            it.copy(
                profileJson = AppJson.encodeToString(RepositoryProfile.serializer(), p),
                detectedStacksJson = AppJson.encodeToString(ListSerializer(String.serializer()), p.languages),
                buildSystemsJson = AppJson.encodeToString(ListSerializer(String.serializer()), p.buildSystems),
                vcsType = p.vcs, currentBranch = p.branch, baseRevision = it.baseRevision ?: p.head,
            )
        }
        return p
    }

    private fun tree(root: File, depth: Int = 3, maxEntries: Int = 120): String {
        val sb = StringBuilder()
        var n = 0
        fun walk(d: File, prefix: String, level: Int) {
            val children = d.listFiles()?.filter { it.name != ".git" }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (c in children) {
                if (n++ >= maxEntries) { if (n == maxEntries + 1) sb.append(prefix).append("…\n"); return }
                sb.append(prefix).append(c.name).append(if (c.isDirectory) "/" else "").append('\n')
                if (c.isDirectory && level < depth && c.name !in WorkspaceFs.GENERATED_DIRS) walk(c, "$prefix  ", level + 1)
            }
        }
        walk(root, "", 1)
        return sb.toString()
    }

    private fun modules(root: File, names: Set<String>): List<String> {
        val out = mutableListOf<String>()
        listOf("settings.gradle.kts", "settings.gradle").map { File(root, it) }.firstOrNull { it.isFile }?.readText()?.let { s ->
            Regex("""include\s*\(?([^)\n]+)""").findAll(s).forEach { m -> Regex("""["']([^"']+)["']""").findAll(m.groupValues[1]).forEach { out += it.groupValues[1] } }
        }
        File(root, "pom.xml").takeIf { it.isFile }?.readText()?.let { s -> Regex("<module>([^<]+)</module>").findAll(s).forEach { out += it.groupValues[1] } }
        File(root, "Cargo.toml").takeIf { it.isFile }?.readText()?.let { s -> Regex("""members\s*=\s*\[([^\]]*)]""").find(s)?.groupValues?.get(1)?.let { m -> Regex("\"([^\"]+)\"").findAll(m).forEach { out += it.groupValues[1] } } }
        File(root, "package.json").takeIf { it.isFile }?.readText()?.let { s -> Regex(""""workspaces"\s*:\s*\[([^\]]*)]""").find(s)?.groupValues?.get(1)?.let { m -> Regex("\"([^\"]+)\"").findAll(m).forEach { out += it.groupValues[1] } } }
        if (out.isEmpty() && names.any { it.startsWith("app/") } && names.contains("app/build.gradle.kts")) out += ":app"
        return out.distinct()
    }

    private fun dependencies(root: File, manifests: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (m in manifests) {
            val f = File(root, m)
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            when (f.name) {
                "build.gradle", "build.gradle.kts" -> Regex("""(?:implementation|api|testImplementation|kapt|ksp|compileOnly|runtimeOnly)\s*\(?\s*["']([^"']+)["']""").findAll(text).forEach { out += it.groupValues[1] }
                "libs.versions.toml" -> Regex("""module\s*=\s*"([^"]+)"""").findAll(text).forEach { out += it.groupValues[1] }
                "pom.xml" -> Regex("""<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>""").findAll(text).forEach { out += "${it.groupValues[1]}:${it.groupValues[2]}" }
                "package.json" -> Regex(""""(?:dependencies|devDependencies)"\s*:\s*\{([^\}]*)\}""").findAll(text).forEach { block ->
                    Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(block.groupValues[1]).forEach { out += "${it.groupValues[1]}@${it.groupValues[2]}" }
                }
                "requirements.txt" -> text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("-") }.forEach { out += it }
                "pyproject.toml" -> Regex("""dependencies\s*=\s*\[([^\]]*)]""").find(text)?.groupValues?.get(1)?.let { b -> Regex("\"([^\"]+)\"").findAll(b).forEach { out += it.groupValues[1] } }
                "Cargo.toml" -> Regex("""(?m)^\[dependencies]([^\[]*)""").find(text)?.groupValues?.get(1)?.lines()?.mapNotNull { l -> l.substringBefore('=').trim().takeIf { it.isNotEmpty() && !it.startsWith("#") } }?.forEach { out += it }
                "go.mod" -> Regex("""(?m)^\s*([\w./-]+\.[\w./-]+)\s+v[\w.-]+""").findAll(text).forEach { out += it.groupValues[1] }
            }
        }
        return out.toList()
    }

    companion object {
        const val MAX_FILES = 20_000
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        val MANIFESTS = setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "libs.versions.toml", "pom.xml", "package.json",
            "pnpm-lock.yaml", "yarn.lock", "package-lock.json", "bun.lockb", "requirements.txt", "pyproject.toml", "poetry.lock", "uv.lock",
            "Cargo.toml", "Cargo.lock", "go.mod", "go.sum", "CMakeLists.txt", "Makefile", "gradle.properties", "AndroidManifest.xml",
        )
        val INSTRUCTIONS = setOf("README.MD", "README", "README.TXT", "CLAUDE.MD", "AGENTS.MD", "CONTRIBUTING.MD", ".CURSORRULES", "COPILOT-INSTRUCTIONS.MD")
        private val TEST_DIR = Regex("""(^|/)(src/test|src/androidTest|tests?|__tests__|spec)/""")
        private val TEST_FILE = Regex("""(Test|Tests|Spec)\.(kt|java|scala)$|^test_.*\.py$|_test\.(py|go)$|\.(test|spec)\.(js|ts|jsx|tsx)$""")
        private val LANG = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "ts" to "typescript",
            "tsx" to "typescript", "py" to "python", "rs" to "rust", "go" to "go", "c" to "c", "h" to "c", "cpp" to "c++", "cc" to "c++", "hpp" to "c++",
            "cs" to "c#", "swift" to "swift", "rb" to "ruby", "php" to "php", "dart" to "dart", "scala" to "scala", "sh" to "shell", "sql" to "sql",
            "html" to "html", "css" to "css", "vue" to "vue", "svelte" to "svelte",
        )
        val SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
            "clé privée" to Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
            "jeton GitHub" to Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}"""),
            "clé API" to Regex("""\b(?:sk-[A-Za-z0-9_\-]{20,}|AIza[0-9A-Za-z_\-]{30,}|xox[baprs]-[A-Za-z0-9-]{10,})"""),
            "clé AWS" to Regex("""\bAKIA[0-9A-Z]{16}\b"""),
            "mot de passe en clair" to Regex("""(?i)\b(?:password|passwd|pwd|secret|api[_-]?key)\s*[:=]\s*["'][^"'\s]{8,}["']"""),
        )

        fun ext(f: File): String = f.name.substringAfterLast('.', "").lowercase().ifEmpty { f.name }

        fun isBinary(f: File): Boolean = runCatching {
            f.inputStream().use { s -> val buf = ByteArray(4096); val n = s.read(buf); (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() } }
        }.getOrDefault(true)

        fun detectLanguages(exts: Collection<String>): List<String> = exts.mapNotNull { LANG[it] }.distinct()

        fun detectBuildSystems(names: Set<String>): List<String> {
            val base = names.map { it.substringAfterLast('/') }.toSet()
            return buildList {
                if ("build.gradle" in base || "build.gradle.kts" in base || "settings.gradle.kts" in base) add(if (names.any { it.endsWith("AndroidManifest.xml") }) "gradle-android" else "gradle")
                if ("pom.xml" in base) add("maven")
                if ("package.json" in base) add(when {
                    "pnpm-lock.yaml" in base -> "pnpm"; "yarn.lock" in base -> "yarn"; "bun.lockb" in base || "bun.lock" in base -> "bun"; else -> "npm"
                })
                if ("pyproject.toml" in base || "requirements.txt" in base || "setup.py" in base) add(when {
                    "uv.lock" in base -> "uv"; "poetry.lock" in base -> "poetry"; else -> "python"
                })
                if ("Cargo.toml" in base) add("cargo")
                if ("go.mod" in base) add("go")
                if ("CMakeLists.txt" in base) add("cmake")
                if (isEmpty() && "Makefile" in base) add("make")
            }
        }
    }
}

/**
 * Code search (doc 03 §4): text, regex, files by glob, TODO/FIXME, secrets — structured hits with
 * path + line range + score + source. Symbol search is provided by the CodeIntelligenceProvider.
 */
class CodeSearch(private val workspaces: WorkspaceManager) {
    enum class Mode { TEXT, REGEX, FILES, TODO, SECRETS }

    suspend fun search(w: WorkspaceEntity, query: String, mode: Mode, glob: String? = null, limit: Int = 50, caseSensitive: Boolean = false): List<CodeHit> = withContext(Dispatchers.IO) {
        val fs = workspaces.fs(w)
        val globRe = glob?.let { globToRegex(it) }
        val files = fs.walk().filter { f -> globRe == null || globRe.matches(fs.relative(f)) || globRe.matches(f.name) }
        val hits = mutableListOf<CodeHit>()
        if (mode == Mode.FILES) {
            val q = query.lowercase()
            files.map { fs.relative(it) }.filter { q.isBlank() || it.lowercase().contains(q) }
                .sortedBy { it.length }.take(limit).forEach { hits += CodeHit(it, 1, 1, it, 1.0, "file") }
            return@withContext hits
        }
        val pattern: Regex = when (mode) {
            Mode.REGEX -> runCatching { Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrElse { throw WorkspaceException("Expression régulière invalide : ${it.message}") }
            Mode.TODO -> Regex("""\b(TODO|FIXME|HACK|XXX)\b""")
            Mode.SECRETS -> Regex(RepositoryIntelligence.SECRET_PATTERNS.joinToString("|") { "(?:${it.second.pattern})" })
            else -> Regex(Regex.escape(query), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }
        for (f in files) {
            if (hits.size >= limit) break
            if (f.length() > RepositoryIntelligence.MAX_TEXT_BYTES || RepositoryIntelligence.isBinary(f)) continue
            val path = fs.relative(f)
            f.useLines { seq ->
                seq.forEachIndexed { i, line ->
                    if (hits.size < limit && pattern.containsMatchIn(line)) {
                        val preview = if (mode == Mode.SECRETS) pattern.replace(line, "[SECRET]").trim().take(200) else line.trim().take(240)
                        hits += CodeHit(path, i + 1, i + 1, preview, score(path, line, query), mode.name.lowercase())
                    }
                }
            }
        }
        hits.sortedByDescending { it.score }
    }

    /** Definition-like and source-directory hits rank above tests, docs and generated code. */
    private fun score(path: String, line: String, query: String): Double {
        var s = 1.0
        if (Regex("""\b(fun|class|interface|object|def|function|fn|func|struct|enum|type|val|var|const|let)\s+${Regex.escape(query)}\b""").containsMatchIn(line)) s += 1.0
        if (path.contains("/test") || path.contains("Test")) s -= 0.2
        if (path.endsWith(".md")) s -= 0.3
        return s
    }

    companion object {
        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> { sb.append(".*"); i++; if (i + 1 < glob.length && glob[i + 1] == '/') i++ }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '{' -> sb.append("(")
                    c == '}' -> sb.append(")")
                    c == ',' -> sb.append("|")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(sb.toString())
        }
    }
}
