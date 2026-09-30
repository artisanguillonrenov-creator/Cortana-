package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.Diagnostic
import io.github.artisanguillonrenov.cortana.contracts.PatchOperation
import io.github.artisanguillonrenov.cortana.contracts.PatchSet
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.util.Hash
import io.github.artisanguillonrenov.cortana.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class Symbol(val name: String, val kind: String, val file: String, val line: Int, val container: String?, val language: String, val signature: String)
data class Reference(val file: String, val line: Int, val column: Int, val preview: String, val isDefinition: Boolean)

/**
 * Code intelligence abstraction (doc 03 §5): LSP or Tree-sitter backends can implement it; the
 * lexical provider is the mandatory fallback that works everywhere (tablet included).
 */
interface CodeIntelligenceProvider {
    val id: String
    fun supports(language: String): Boolean
    fun symbols(file: String, text: String, language: String): List<Symbol>
    fun imports(text: String, language: String): List<String>
}

/** Regex-based symbols and imports for Kotlin, Java, JavaScript/TypeScript, Python, Go, Rust and C/C++. */
object LexicalProvider : CodeIntelligenceProvider {
    override val id = "lexical"
    private val rules: Map<String, List<Pair<String, Regex>>> = mapOf(
        "kotlin" to listOf(
            "class" to Regex("""^\s*(?:(?:public|private|internal|protected|abstract|open|sealed|data|enum|inner|value|annotation)\s+)*(?:class|interface|object)\s+([A-Za-z_]\w*)"""),
            "function" to Regex("""^\s*(?:(?:public|private|internal|protected|override|suspend|inline|operator|infix|tailrec|abstract|open)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\("""),
            "property" to Regex("""^\s*(?:(?:public|private|internal|protected|override|const|lateinit)\s+)*(?:val|var)\s+([A-Za-z_]\w*)"""),
            "typealias" to Regex("""^\s*typealias\s+([A-Za-z_]\w*)"""),
        ),
        "java" to listOf(
            "class" to Regex("""^\s*(?:(?:public|private|protected|static|final|abstract|sealed)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_]\w*)"""),
            "function" to Regex("""^\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)+[\w<>\[\],.? ]+\s+([a-zA-Z_]\w*)\s*\([^;]*$"""),
        ),
        "javascript" to listOf(
            "function" to Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)"""),
            "class" to Regex("""^\s*(?:export\s+)?(?:default\s+)?class\s+([A-Za-z_$][\w$]*)"""),
            "variable" to Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*="""),
            "property" to Regex("""^\s*([A-Za-z_$][\w$]*)\s*:\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>"""),
            "method" to Regex("""^\s+(?:async\s+)?(?:static\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{"""),
        ),
        "python" to listOf(
            "class" to Regex("""^\s*class\s+([A-Za-z_]\w*)"""),
            "function" to Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_]\w*)"""),
        ),
        "go" to listOf(
            "function" to Regex("""^func\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)\s*[\[(]"""),
            "type" to Regex("""^type\s+([A-Za-z_]\w*)\s+(?:struct|interface|func|\w)"""),
        ),
        "rust" to listOf(
            "function" to Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?fn\s+([A-Za-z_]\w*)"""),
            "type" to Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?(?:struct|enum|trait|type|union)\s+([A-Za-z_]\w*)"""),
        ),
        "c" to listOf(
            "function" to Regex("""^[A-Za-z_][\w\s*]*?\s\**([A-Za-z_]\w*)\s*\([^;]*\)\s*\{?\s*$"""),
            "type" to Regex("""^\s*(?:typedef\s+)?(?:struct|enum|class|union)\s+([A-Za-z_]\w*)"""),
        ),
    )
    private val importRules: Map<String, Regex> = mapOf(
        "kotlin" to Regex("""^\s*import\s+([\w.]+)"""),
        "java" to Regex("""^\s*import\s+(?:static\s+)?([\w.]+)"""),
        "javascript" to Regex("""(?:require\(\s*['"]([^'"]+)['"]\s*\)|^\s*import\s+(?:[^'"]*\s+from\s+)?['"]([^'"]+)['"]|^\s*export\s+[^'"]*\s+from\s+['"]([^'"]+)['"])"""),
        "python" to Regex("""^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))"""),
        "go" to Regex("""^\s*(?:import\s+)?"([\w./-]+)"$"""),
        "rust" to Regex("""^\s*(?:use\s+(crate::[\w:]+)|mod\s+(\w+)\s*;)"""),
        "c" to Regex("""^\s*#include\s+"([^"]+)""""),
    )

    fun language(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "jsx", "mjs", "cjs", "ts", "tsx" -> "javascript"; "py" -> "python"; "go" -> "go"; "rs" -> "rust"
        "c", "h", "cc", "cpp", "hpp", "cxx" -> "c"; else -> null
    }

    override fun supports(language: String) = language in rules

    override fun symbols(file: String, text: String, language: String): List<Symbol> {
        val lang = rules[language] ?: return emptyList()
        val out = mutableListOf<Symbol>()
        var container: String? = null
        var containerIndent = -1
        text.lineSequence().forEachIndexed { i, raw ->
            val line = stripCommentsAndStrings(raw, language)
            val indent = raw.length - raw.trimStart().length
            if (container != null && raw.isNotBlank() && indent <= containerIndent && !raw.trimStart().startsWith("}") && language == "python") container = null
            for ((kind, re) in lang) {
                val m = re.find(line) ?: continue
                val name = m.groupValues[1]
                if (name in KEYWORDS) continue
                out += Symbol(name, kind, file, i + 1, container?.takeIf { it != name }, language, raw.trim().take(160))
                if (kind == "class" || kind == "type") { container = name; containerIndent = indent }
                break
            }
        }
        return out
    }

    override fun imports(text: String, language: String): List<String> {
        val re = importRules[language] ?: return emptyList()
        return text.lineSequence().flatMap { l -> re.findAll(l).map { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty() } }.filter { it.isNotEmpty() }.toList()
    }

    /** Blanks string literals and comments (keeping columns) so references ignore them. */
    fun stripCommentsAndStrings(line: String, language: String): String {
        val sb = StringBuilder(line)
        var i = 0
        var quote: Char? = null
        while (i < sb.length) {
            val c = sb[i]
            if (quote != null) {
                if (c == '\\') { sb.setCharAt(i, ' '); if (i + 1 < sb.length) sb.setCharAt(i + 1, ' '); i += 2; continue }
                if (c == quote) quote = null else sb.setCharAt(i, ' ')
                i++; continue
            }
            val two = if (i + 1 < sb.length) "" + c + sb[i + 1] else ""
            val lineComment = two == "//" && language != "python" || c == '#' && language == "python"
            if (lineComment) { for (j in i until sb.length) sb.setCharAt(j, ' '); break }
            if (c == '"' || c == '\'' || c == '`') quote = c
            i++
        }
        return sb.toString()
    }

    private val KEYWORDS = setOf("if", "for", "while", "switch", "catch", "return", "new", "else", "when", "function", "constructor")
}

/**
 * SymbolIndex (doc 03 §3-5): per-workspace symbols, imports and a file dependency graph, cached by
 * file hash; definitions, references (code only, word boundaries), hover, test impact and rename.
 */
class CodeIntelligence(private val workspaces: WorkspaceManager, private val providers: List<CodeIntelligenceProvider> = listOf(LexicalProvider)) {
    private data class FileIndex(val hash: String, val language: String, val symbols: List<Symbol>, val imports: List<String>)
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, FileIndex>>()

    private fun provider(lang: String) = providers.firstOrNull { it.supports(lang) }

    suspend fun index(w: WorkspaceEntity): Map<String, Pair<String, List<Symbol>>> = withContext(Dispatchers.IO) {
        val fs = workspaces.fs(w)
        val ws = cache.getOrPut(w.workspaceId) { ConcurrentHashMap() }
        val seen = mutableSetOf<String>()
        for (f in fs.walk()) {
            val rel = fs.relative(f)
            val lang = LexicalProvider.language(rel) ?: continue
            if (f.length() > RepositoryIntelligence.MAX_TEXT_BYTES) continue
            seen += rel
            val bytes = f.readBytes()
            val h = Hash.sha256Bytes(bytes)
            if (ws[rel]?.hash == h) continue
            val text = bytes.decodeToString()
            val p = provider(lang) ?: continue
            ws[rel] = FileIndex(h, lang, p.symbols(rel, text, lang), p.imports(text, lang))
        }
        ws.keys.retainAll(seen)
        ws.mapValues { it.value.language to it.value.symbols }
    }

    suspend fun symbols(w: WorkspaceEntity, query: String? = null, file: String? = null, kind: String? = null, limit: Int = 100): List<Symbol> {
        index(w)
        val all = cache[w.workspaceId]!!.values.flatMap { it.symbols }
        return all.asSequence()
            .filter { file == null || it.file == file }
            .filter { kind == null || it.kind == kind }
            .filter { query.isNullOrBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<Symbol>({ if (query != null && it.name == query) 0 else if (query != null && it.name.equals(query, true)) 1 else 2 }, { it.file }, { it.line }))
            .take(limit).toList()
    }

    suspend fun definitions(w: WorkspaceEntity, name: String): List<Symbol> = symbols(w, name, limit = 500).filter { it.name == name }

    /** Word-boundary occurrences in code (strings and comments ignored), definitions flagged. */
    suspend fun references(w: WorkspaceEntity, name: String, limit: Int = 500): List<Reference> = withContext(Dispatchers.IO) {
        val defs = definitions(w, name).map { it.file to it.line }.toSet()
        val fs = workspaces.fs(w)
        val re = Regex("(?<![\\w$])" + Regex.escape(name) + "(?![\\w$])")
        val out = mutableListOf<Reference>()
        for ((rel, idx) in cache[w.workspaceId]!!) {
            val lines = runCatching { fs.resolve(rel).readLines() }.getOrElse { continue }
            lines.forEachIndexed { i, raw ->
                val code = LexicalProvider.stripCommentsAndStrings(raw, idx.language)
                re.findAll(code).forEach { m -> out += Reference(rel, i + 1, m.range.first + 1, raw.trim().take(200), (rel to i + 1) in defs) }
            }
            if (out.size >= limit) break
        }
        out.sortedWith(compareBy({ !it.isDefinition }, { it.file }, { it.line })).take(limit)
    }

    suspend fun hover(w: WorkspaceEntity, name: String): String? {
        val d = definitions(w, name).firstOrNull() ?: return null
        val lines = runCatching { workspaces.fs(w).resolve(d.file).readLines() }.getOrNull() ?: return d.signature
        val doc = generateSequence(d.line - 2) { it - 1 }.takeWhile { it >= 0 && lines[it].trim().let { l -> l.startsWith("*") || l.startsWith("/**") || l.startsWith("//") || l.startsWith("#") || l.startsWith("///") } }
            .map { lines[it].trim() }.toList().reversed()
        return (doc + d.signature).joinToString("\n") + "\n(${d.kind} dans ${d.file}:${d.line})"
    }

    /** File → files it depends on, resolved inside the workspace when possible. */
    suspend fun dependencyGraph(w: WorkspaceEntity): Map<String, Set<String>> {
        index(w)
        val idx = cache[w.workspaceId]!!
        val files = idx.keys
        // Kotlin/Java: fully qualified name → declaring file (package + top-level symbol).
        val fqn = HashMap<String, String>()
        for ((rel, fi) in idx) if (fi.language == "kotlin" || fi.language == "java") {
            val pkg = runCatching { workspaces.fs(w).resolve(rel).useLines { s -> s.firstNotNullOfOrNull { Regex("""^\s*package\s+([\w.]+)""").find(it)?.groupValues?.get(1) } } }.getOrNull()
            fi.symbols.filter { it.container == null }.forEach { s -> fqn[(pkg?.let { "$it." } ?: "") + s.name] = rel }
        }
        return idx.mapValues { (rel, fi) ->
            fi.imports.mapNotNull { imp ->
                when (fi.language) {
                    "kotlin", "java" -> fqn[imp] ?: fqn[imp.substringBeforeLast('.')]
                    "javascript" -> if (imp.startsWith(".")) resolveRelative(rel, imp, files, listOf("", ".js", ".ts", ".jsx", ".tsx", ".mjs", ".cjs", "/index.js", "/index.ts")) else null
                    "python" -> imp.replace('.', '/').let { m -> files.firstOrNull { it == "$m.py" || it.endsWith("/$m.py") || it == "$m/__init__.py" } }
                    "c" -> resolveRelative(rel, "./$imp", files, listOf(""))
                    "rust" -> imp.removePrefix("crate::").split("::").firstOrNull()?.let { m -> files.firstOrNull { it.endsWith("/$m.rs") || it.endsWith("/$m/mod.rs") } }
                    else -> null
                }
            }.filter { it != rel }.toSet()
        }
    }

    /** Tests impacted by changed files: reverse dependency closure ∩ test files, plus tests naming changed symbols. */
    suspend fun impactedTests(w: WorkspaceEntity, changed: Collection<String>): List<String> {
        val graph = dependencyGraph(w)
        val reverse = HashMap<String, MutableSet<String>>()
        graph.forEach { (from, tos) -> tos.forEach { reverse.getOrPut(it) { mutableSetOf() } += from } }
        val impacted = LinkedHashSet<String>(changed)
        val queue = ArrayDeque(changed)
        while (queue.isNotEmpty()) reverse[queue.removeFirst()]?.forEach { if (impacted.add(it)) queue.addLast(it) }
        val changedSymbols = cache[w.workspaceId]!!.filterKeys { it in changed }.values.flatMap { it.symbols.map { s -> s.name } }.filter { it.length > 2 }.toSet()
        val tests = graph.keys.filter { isTest(it) }
        val byName = if (changedSymbols.isEmpty()) emptyList() else tests.filter { t ->
            val text = runCatching { workspaces.fs(w).resolve(t).readText() }.getOrDefault("")
            changedSymbols.any { Regex("(?<![\\w$])" + Regex.escape(it) + "(?![\\w$])").containsMatchIn(text) }
        }
        return (impacted.filter { isTest(it) } + byName).distinct().sorted()
    }

    /** Static checks without running anything: broken relative imports, duplicate top-level definitions. */
    suspend fun staticDiagnostics(w: WorkspaceEntity): List<Diagnostic> {
        index(w)
        val idx = cache[w.workspaceId]!!
        val out = mutableListOf<Diagnostic>()
        for ((rel, fi) in idx) {
            if (fi.language == "javascript") fi.imports.filter { it.startsWith(".") }.forEach { imp ->
                if (resolveRelative(rel, imp, idx.keys, listOf("", ".js", ".ts", ".jsx", ".tsx", ".mjs", ".cjs", ".json", "/index.js", "/index.ts")) == null &&
                    !workspaces.fs(w).resolve(File(File(rel).parent ?: "", imp).path).exists()) out += Diagnostic("error", "imports", null, "module introuvable : $imp", rel)
            }
            fi.symbols.filter { it.container == null && it.kind in setOf("class", "function", "type") }.groupBy { it.name }.filterValues { it.size > 1 }
                .forEach { (name, dup) -> out += Diagnostic("warning", "symbols", null, "« $name » défini ${dup.size} fois", rel, dup[1].line) }
        }
        return out
    }

    data class RenamePlan(val patch: PatchSet, val files: List<String>, val occurrences: Int, val diff: String)

    /** Symbol-aware rename (code occurrences only) as a PatchSet of unified diffs — applied by the PatchEngine. */
    suspend fun renamePlan(w: WorkspaceEntity, name: String, newName: String, scope: String? = null, patchId: String = "rename-" + Ids.new()): RenamePlan = withContext(Dispatchers.IO) {
        if (!IDENT.matches(newName) || newName in RESERVED) throw WorkspaceException("Nouveau nom invalide : $newName")
        if (newName == name) throw WorkspaceException("Le nouveau nom est identique")
        val inScope = scope?.let { CodeSearch.globToRegex(it) }?.let { re -> { f: String -> re.matches(f) } } ?: { _: String -> true }
        if (definitions(w, name).none { inScope(it.file) }) throw WorkspaceException("Symbole « $name » introuvable" + (scope?.let { " dans $it" } ?: ""))
        // Conflict: the new name already exists as a definition or is used anywhere in the files touched.
        if (definitions(w, newName).isNotEmpty()) throw WorkspaceException("« $newName » existe déjà : renommage refusé (conflit)")
        val refs = references(w, name, limit = 5_000).filter { inScope(it.file) }
        val clash = references(w, newName, limit = 50).filter { r -> refs.any { it.file == r.file } }
        if (clash.isNotEmpty()) throw WorkspaceException("« $newName » est déjà utilisé dans ${clash.map { it.file }.distinct().joinToString()} : renommage refusé (conflit)")
        val fs = workspaces.fs(w)
        val ops = mutableListOf<PatchOperation>()
        val diffs = StringBuilder()
        for ((file, rs) in refs.groupBy { it.file }) {
            val old = fs.resolve(file).readText()
            val lines = old.split("\n").toMutableList()
            for ((line, lineRefs) in rs.groupBy { it.line }) {
                val sb = StringBuilder(lines[line - 1])
                lineRefs.sortedByDescending { it.column }.forEach { r -> sb.replace(r.column - 1, r.column - 1 + name.length, newName) }
                lines[line - 1] = sb.toString()
            }
            val new = lines.joinToString("\n")
            val d = Diff.unified(file, file, old.replace("\r\n", "\n"), new.replace("\r\n", "\n"))
            ops += PatchOperation.UnifiedDiff(file, d)
            diffs.append(d)
        }
        RenamePlan(PatchSet(patchId = patchId, workspaceId = w.workspaceId, operations = ops, rationale = "renommage $name → $newName", generatedAt = System.currentTimeMillis()),
            refs.map { it.file }.distinct(), refs.size, diffs.toString())
    }

    fun invalidate(workspaceId: String) { cache.remove(workspaceId) }

    companion object {
        private val IDENT = Regex("[A-Za-z_$][\\w$]*")
        private val RESERVED = setOf("class", "fun", "val", "var", "def", "function", "return", "if", "else", "for", "while", "import", "package", "object", "interface",
            "const", "let", "new", "this", "self", "null", "true", "false", "struct", "fn", "impl", "type", "func", "go", "in", "is", "as")

        fun isTest(path: String) = Regex("""(^|/)(test|tests|__tests__|spec|src/test|src/androidTest)/|(Test|Tests|Spec)\.(kt|java)$|^test_|/test_[^/]+\.py$|_test\.(py|go)$|\.(test|spec)\.(js|ts|jsx|tsx|mjs)$""").containsMatchIn(path)

        fun resolveRelative(from: String, imp: String, files: Set<String>, suffixes: List<String>): String? {
            val base = File(File(from).parent ?: "", imp).normalize().path.replace(File.separatorChar, '/').trimStart('/')
            return suffixes.map { base + it }.firstOrNull { it in files }
        }
    }
}
