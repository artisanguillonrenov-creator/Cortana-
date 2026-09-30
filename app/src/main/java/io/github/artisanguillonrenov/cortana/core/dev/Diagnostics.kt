package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.Diagnostic
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DiagnosticsService (doc 03 §10): one normalized [Diagnostic] shape for compiler errors, lint,
 * test failures, stack traces and build-tool failures, whatever the language. Paths are made
 * relative to the workspace (local root, or the worker's /tmp/ws).
 */
object Diagnostics {
    private data class Rule(val source: String, val regex: Regex, val build: (MatchResult) -> Diagnostic)

    private fun sev(s: String) = when (s.lowercase()) { "e", "error", "fatal error", "fatal" -> "error"; "w", "warning" -> "warning"; else -> "info" }

    private val rules = listOf(
        // Kotlin: e: file:///path/Calc.kt:4:40 Unresolved reference 'x'.
        Rule("kotlinc", Regex("""^([ew]): (?:file://)?(/?[^\s:]+\.kts?):(\d+):(\d+) (.+)$""")) { m ->
            Diagnostic(sev(m.groupValues[1]), "kotlinc", null, m.groupValues[5], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt())
        },
        // javac: src/A.java:12: error: cannot find symbol
        Rule("javac", Regex("""^(/?[^\s:]+\.java):(\d+): (error|warning): (.+)$""")) { m ->
            Diagnostic(sev(m.groupValues[3]), "javac", null, m.groupValues[4], m.groupValues[1], m.groupValues[2].toInt())
        },
        // tsc: src/a.ts(3,7): error TS2322: Type 'string' is not assignable…
        Rule("tsc", Regex("""^([^\s(]+\.(?:ts|tsx|js|jsx))\((\d+),(\d+)\): (error|warning) (TS\d+): (.+)$""")) { m ->
            Diagnostic(sev(m.groupValues[4]), "tsc", m.groupValues[5], m.groupValues[6], m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
        },
        // gcc/clang/go/eslint(unix)/rustc-short: path:line:col: error: msg
        Rule("cc", Regex("""^(/?[^\s:]+\.(?:c|h|cc|cpp|hpp|m|go|js|jsx|ts|tsx|py|rs|swift)):(\d+):(\d+): (?:(error|warning|note|fatal error): )?(.+)$""")) { m ->
            val file = m.groupValues[1]
            val src = when { file.endsWith(".go") -> "go"; file.endsWith(".rs") -> "rustc"; file.matches(Regex(".*\\.(js|jsx|ts|tsx)$")) -> "eslint"; file.endsWith(".py") -> "python"; else -> "cc" }
            Diagnostic(sev(m.groupValues[4].ifEmpty { "error" }), src, null, m.groupValues[5], file, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        },
        // pytest summary: FAILED tests/test_a.py::test_add - assert 2 == 3
        Rule("pytest", Regex("""^FAILED ([^\s:]+\.py)::(\S+)(?: - (.+))?$""")) { m ->
            Diagnostic("error", "pytest", m.groupValues[2], m.groupValues[3].ifEmpty { "échec du test ${m.groupValues[2]}" }, m.groupValues[1])
        },
        // Python traceback frame: File "/x/a.py", line 3, in f
        Rule("python", Regex("""^\s*File "([^"]+\.py)", line (\d+), in (.+)$""")) { m ->
            Diagnostic("info", "python-trace", null, "dans ${m.groupValues[3]}", m.groupValues[1], m.groupValues[2].toInt())
        },
        // JVM stack frame: at calc.Calc.add(Calc.kt:4)
        Rule("jvm", Regex("""^\s*at ([\w$.<>]+)\(([\w$]+\.(?:kt|java|scala|groovy)):(\d+)\)$""")) { m ->
            Diagnostic("info", "jvm-trace", null, "dans ${m.groupValues[1]}", m.groupValues[2], m.groupValues[3].toInt())
        },
        // Gradle: * What went wrong:\nExecution failed for task ':app:compileKotlin'. (first line after the header, handled below)
        Rule("maven", Regex("""^\[(ERROR|WARNING)] (/?[^\s:]+\.(?:java|kt)):\[(\d+),(\d+)] (.+)$""")) { m ->
            Diagnostic(sev(m.groupValues[1]), "maven", null, m.groupValues[5], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt())
        },
        // node:test / jest assertion location: at … (/tmp/ws/test/a.test.js:3:14)
        Rule("node", Regex("""^\s*at .*\(((?:/|file://)[^():]+\.(?:js|mjs|cjs|ts)):(\d+):(\d+)\)$""")) { m ->
            Diagnostic("info", "node-trace", null, "pile d'appels", m.groupValues[1].removePrefix("file://"), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        },
    )

    private val rustError = Regex("""^(error|warning)(?:\[(E\d+)])?: (.+)$""")
    private val rustLoc = Regex("""^\s*--> ([^\s:]+):(\d+):(\d+)$""")
    // Node load/syntax errors: "/path/a.js:2" header, source + caret, then "ReferenceError: x is not defined".
    private val nodeHeader = Regex("""^((?:file://)?/?[^\s:]+\.(?:js|mjs|cjs|ts)):(\d+)$""")
    private val nodeError = Regex("""^(\w*(?:Error|Exception)): (.+)$""")

    fun parse(output: String, roots: List<String> = emptyList(), max: Int = 200): List<Diagnostic> {
        val out = LinkedHashSet<Diagnostic>()
        val lines = output.lineSequence().toList()
        var pendingRust: Pair<String, MatchResult>? = null
        var pendingNode: Pair<MatchResult, Int>? = null
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            nodeHeader.find(line)?.let { pendingNode = it to i }
            pendingNode?.let { (h, at) ->
                if (i - at > 6) pendingNode = null
                else nodeError.find(line)?.let { e ->
                    out += Diagnostic("error", "node", e.groupValues[1], e.groupValues[2], h.groupValues[1].removePrefix("file://"), h.groupValues[2].toInt())
                    pendingNode = null
                }
            }
            rustError.find(line)?.let { pendingRust = line to it; return@let }
            rustLoc.find(line)?.let { loc ->
                pendingRust?.let { (_, m) ->
                    out += Diagnostic(sev(m.groupValues[1]), "rustc", m.groupValues[2].ifEmpty { null }, m.groupValues[3], loc.groupValues[1], loc.groupValues[2].toInt(), loc.groupValues[3].toInt())
                }
                pendingRust = null
            }
            if (line.startsWith("* What went wrong:")) lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Diagnostic("error", "gradle", null, it) }
            for (r in rules) { r.regex.find(line)?.let { out += r.build(it); break } }
            if (out.size >= max) break
        }
        return out.map { d -> d.copy(file = d.file?.let { relativize(it, roots) }) }.distinct().take(max)
    }

    fun relativize(path: String, roots: List<String>): String {
        var p = path.removePrefix("file://")
        for (r in roots + listOf("/tmp/ws")) { val root = r.trimEnd('/') + "/"; if (p.startsWith(root)) { p = p.removePrefix(root); break } }
        return p
    }

    data class FailedTest(val name: String, val suite: String?, val message: String, val file: String? = null, val line: Int? = null)
    data class JUnitSummary(val tests: Int, val failures: Int, val errors: Int, val skipped: Int, val failed: List<FailedTest>, val passed: List<String>)

    /** JUnit XML (Gradle, Maven Surefire, pytest --junitxml, node --test junit reporter, …). */
    fun junit(xml: String): JUnitSummary {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } // no XXE
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(xml.byteInputStream())
        val cases = doc.getElementsByTagName("testcase")
        val failed = mutableListOf<FailedTest>(); val passed = mutableListOf<String>()
        var skipped = 0; var errors = 0
        for (i in 0 until cases.length) {
            val tc = cases.item(i) as Element
            val name = tc.getAttribute("name"); val cls = tc.getAttribute("classname").ifEmpty { null }
            val fail = (tc.getElementsByTagName("failure").item(0) ?: tc.getElementsByTagName("error").item(0)) as Element?
            when {
                fail != null -> {
                    if (fail.tagName == "error") errors++
                    val msg = fail.getAttribute("message").ifEmpty { fail.textContent.trim().lineSequence().firstOrNull().orEmpty() }
                    val loc = Regex("""\(?(/?[\w./-]+\.(?:kt|java|js|ts|py)):(\d+)""").find(fail.textContent)
                    failed += FailedTest(name, cls, msg.take(500), loc?.groupValues?.get(1), loc?.groupValues?.get(2)?.toIntOrNull())
                }
                tc.getElementsByTagName("skipped").length > 0 -> skipped++
                else -> passed += listOfNotNull(cls, name).joinToString(".")
            }
        }
        return JUnitSummary(cases.length, failed.size - errors, errors, skipped, failed, passed)
    }
}
