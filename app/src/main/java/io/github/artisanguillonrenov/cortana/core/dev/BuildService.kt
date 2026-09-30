package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.BuildResult
import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.Diagnostic
import io.github.artisanguillonrenov.cortana.contracts.NetworkMode
import io.github.artisanguillonrenov.cortana.contracts.RepositoryProfile
import io.github.artisanguillonrenov.cortana.core.exec.ExecResult
import io.github.artisanguillonrenov.cortana.core.exec.ProcessSpec
import io.github.artisanguillonrenov.cortana.core.exec.SandboxManager
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A concrete command for one kind of work, with the files it produces. */
data class BuildCommand(val command: String, val artifactGlobs: List<String> = emptyList(), val reportGlobs: List<String> = emptyList())

/**
 * Build-system adapters (doc 03 §8): detected from the repository profile, never hard-coded in the
 * orchestrator. Each knows how to build, test (optionally filtered), lint, and where outputs land.
 */
interface BuildAdapter {
    val id: String
    fun build(root: File, target: String?): BuildCommand
    fun test(root: File, filter: String?): BuildCommand
    fun lint(root: File): BuildCommand? = null
}

object BuildAdapters {
    private const val REPORTS = ".cortana/test-results"

    class Gradle(private val android: Boolean) : BuildAdapter {
        override val id = if (android) "gradle-android" else "gradle"
        private fun gradle(root: File) = if (File(root, "gradlew").isFile) "sh ./gradlew" else "gradle"
        override fun build(root: File, target: String?) = BuildCommand(
            "${gradle(root)} --no-daemon --console=plain ${target ?: if (android) "assembleDebug" else "build -x test"}",
            artifactGlobs = if (android) listOf("**/build/outputs/apk/**/*.apk", "**/build/outputs/bundle/**/*.aab") else listOf("**/build/libs/*.jar"),
        )
        override fun test(root: File, filter: String?) = BuildCommand(
            "${gradle(root)} --no-daemon --console=plain ${if (android) "testDebugUnitTest" else "test"}" + (filter?.let { " --tests '${it.replace("'", "")}'" } ?: ""),
            reportGlobs = listOf("**/build/test-results/**/*.xml"),
        )
        override fun lint(root: File) = BuildCommand("${gradle(root)} --no-daemon --console=plain ${if (android) "lintDebug" else "check -x test"}", reportGlobs = listOf("**/build/reports/lint-results*.xml"))
    }

    object Maven : BuildAdapter {
        override val id = "maven"
        override fun build(root: File, target: String?) = BuildCommand("mvn -B -q ${target ?: "package -DskipTests"}", artifactGlobs = listOf("**/target/*.jar", "**/target/*.war"))
        override fun test(root: File, filter: String?) = BuildCommand("mvn -B test" + (filter?.let { " -Dtest='${it.replace("'", "")}'" } ?: ""), reportGlobs = listOf("**/target/surefire-reports/*.xml"))
    }

    class Node(private val pm: String) : BuildAdapter {
        override val id = pm
        /** No update-notifier/fund/audit network chatter: builds must work with the network off. */
        private val quiet = "npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false"
        private fun run(script: String) = "$quiet " + when (pm) { "yarn" -> "yarn $script"; "bun" -> "bun run $script"; "pnpm" -> "pnpm run $script"; else -> "npm run $script" }
        private fun hasScript(root: File, s: String) = File(root, "package.json").takeIf { it.isFile }?.readText()?.contains("\"$s\"") == true
        override fun build(root: File, target: String?) = BuildCommand(
            if (target != null) run(target) else if (hasScript(root, "build")) run("build") else "mkdir -p dist && $quiet npm pack --pack-destination dist",
            artifactGlobs = listOf("dist/**", "*.tgz"),
        )
        override fun test(root: File, filter: String?): BuildCommand {
            val junit = "--test-reporter=spec --test-reporter-destination=stdout --test-reporter=junit --test-reporter-destination=$REPORTS/junit.xml"
            val cmd = if (hasScript(root, "test") && filter == null) "mkdir -p $REPORTS && ${run("test")}"
            else "mkdir -p $REPORTS && node --test $junit" + (filter?.let { " --test-name-pattern='${it.replace("'", "")}'" } ?: "")
            return BuildCommand(cmd, reportGlobs = listOf("$REPORTS/*.xml", "**/junit*.xml"))
        }
        override fun lint(root: File) = if (hasScript(root, "lint")) BuildCommand(run("lint")) else null
    }

    class Python(private val runner: String) : BuildAdapter {
        override val id = runner
        private val prefix = when (runner) { "uv" -> "uv run "; "poetry" -> "poetry run "; else -> "" }
        override fun build(root: File, target: String?) = BuildCommand("${prefix}python3 -m build" + (target?.let { " $it" } ?: ""), artifactGlobs = listOf("dist/*"))
        override fun test(root: File, filter: String?) = BuildCommand(
            "mkdir -p $REPORTS && (${prefix}python3 -m pytest -q --junitxml=$REPORTS/junit.xml" + (filter?.let { " -k '${it.replace("'", "")}'" } ?: "") +
                " || { s=${'$'}?; [ ${'$'}s -eq 5 ] && exit 5; python3 -c 'import pytest' 2>/dev/null && exit ${'$'}s; ${prefix}python3 -m unittest discover -v; })",
            reportGlobs = listOf("$REPORTS/*.xml"),
        )
        override fun lint(root: File) = BuildCommand("${prefix}python3 -m pyflakes . || ${prefix}python3 -m compileall -q .")
    }

    object Cargo : BuildAdapter {
        override val id = "cargo"
        override fun build(root: File, target: String?) = BuildCommand("cargo build --release" + (target?.let { " --bin $it" } ?: ""), artifactGlobs = listOf("target/release/*"))
        override fun test(root: File, filter: String?) = BuildCommand("cargo test" + (filter?.let { " '${it.replace("'", "")}'" } ?: ""))
        override fun lint(root: File) = BuildCommand("cargo clippy --message-format=short")
    }

    object Go : BuildAdapter {
        override val id = "go"
        override fun build(root: File, target: String?) = BuildCommand("mkdir -p dist && go build -o dist/ ${target ?: "./..."}", artifactGlobs = listOf("dist/*"))
        override fun test(root: File, filter: String?) = BuildCommand("go test ./..." + (filter?.let { " -run '${it.replace("'", "")}'" } ?: "") + " -v")
        override fun lint(root: File) = BuildCommand("go vet ./...")
    }

    object CMake : BuildAdapter {
        override val id = "cmake"
        override fun build(root: File, target: String?) = BuildCommand("cmake -S . -B build && cmake --build build" + (target?.let { " --target $it" } ?: ""), artifactGlobs = listOf("build/*.a", "build/*.so", "build/bin/*"))
        override fun test(root: File, filter: String?) = BuildCommand("cmake -S . -B build && cmake --build build && ctest --test-dir build --output-junit junit.xml" + (filter?.let { " -R '${it.replace("'", "")}'" } ?: ""), reportGlobs = listOf("build/junit.xml"))
    }

    object Make : BuildAdapter {
        override val id = "make"
        override fun build(root: File, target: String?) = BuildCommand("make ${target ?: ""}".trim())
        override fun test(root: File, filter: String?) = BuildCommand("make test")
    }

    /** The profile's first detected build system, or null (then only exec.run with an explicit command). */
    fun detect(profile: RepositoryProfile?): BuildAdapter? = when (profile?.buildSystems?.firstOrNull()) {
        "gradle-android" -> Gradle(true); "gradle" -> Gradle(false); "maven" -> Maven
        "npm", "pnpm", "yarn", "bun" -> Node(profile.buildSystems.first())
        "python", "uv", "poetry" -> Python(profile.buildSystems.first())
        "cargo" -> Cargo; "go" -> Go; "cmake" -> CMake; "make" -> Make
        else -> null
    }
}

/**
 * BuildService + TestService (doc 03 §8-9): run through the SandboxManager (tablet or worker),
 * normalize diagnostics, parse JUnit results, retry only failed tests once to *identify* flakiness
 * (a test that fails then passes is reported flaky, never silently green), tell a test failure
 * from an infrastructure/compilation failure, and compare with the previous run.
 */
class BuildService(
    private val workspaces: WorkspaceManager,
    private val intelligence: RepositoryIntelligence,
    private val sandbox: SandboxManager,
    private val artifacts: ArtifactService,
) {
    data class TestReport(
        val status: String,
        val passed: Int, val failed: Int, val skipped: Int,
        val failedTests: List<Diagnostics.FailedTest>,
        val flaky: List<String>,
        val infraFailure: String?,
        val diagnostics: List<Diagnostic>,
        val fixed: List<String>, val newlyFailing: List<String>,
        val exec: ExecResult,
    ) {
        fun render(): String = buildString {
            append(when (status) { "passed" -> "✅ Tests réussis"; "failed" -> "❌ Tests en échec"; else -> "⚠️ Échec d'infrastructure" })
            append(" — $passed réussi(s), $failed échoué(s), $skipped ignoré(s) [${exec.backend} · ${exec.sandbox}]\n")
            infraFailure?.let { append("Cause : ").append(it).append('\n') }
            failedTests.take(20).forEach { append("- ${listOfNotNull(it.suite, it.name).joinToString(".")} : ${it.message.take(300)}${it.file?.let { f -> " ($f:${it.line ?: "?"})" } ?: ""}\n") }
            if (flaky.isNotEmpty()) append("Instables (échec puis réussite au 2e essai, à examiner) : ${flaky.joinToString()}\n")
            if (fixed.isNotEmpty()) append("Corrigés depuis le dernier passage : ${fixed.joinToString()}\n")
            if (newlyFailing.isNotEmpty()) append("Nouveaux échecs : ${newlyFailing.joinToString()}\n")
            diagnostics.filter { it.severity == "error" }.take(20).forEach { append("• ${it.source}: ${it.file ?: ""}${it.line?.let { l -> ":$l" } ?: ""} ${it.message.take(240)}\n") }
        }
    }

    private val lastFailures = ConcurrentHashMap<String, Set<String>>()

    /**
     * One build/test/lint run, with the workspace revision it ran against: evidence for the
     * completion gate (tests are only "verified" for the exact code they ran on) and the Dev screen.
     */
    data class RunRecord(
        val kind: String, val workspaceId: String, val taskId: String?, val filter: String?, val status: String,
        val summary: String, val failing: List<String>, val revision: String, val backend: String, val startedAt: Long, val durationMs: Long,
    )
    data class LiveLog(val workspaceId: String, val kind: String, val lines: List<String>, val running: Boolean)

    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs
    private val _live = MutableStateFlow<LiveLog?>(null)
    val live: StateFlow<LiveLog?> = _live

    fun runsFor(workspaceId: String? = null, taskId: String? = null) = _runs.value.filter { (workspaceId == null || it.workspaceId == workspaceId) && (taskId == null || it.taskId == taskId) }

    private fun record(r: RunRecord) { _runs.update { (it + r).takeLast(200) } }

    private fun logSink(w: WorkspaceEntity, kind: String, onLog: (String) -> Unit): (String) -> Unit {
        _live.value = LiveLog(w.workspaceId, kind, emptyList(), running = true)
        return { line -> _live.update { l -> l?.copy(lines = (l.lines + line.take(400)).takeLast(300)) }; onLog(line) }
    }
    private fun logDone() { _live.update { it?.copy(running = false) } }

    private suspend fun adapter(w: WorkspaceEntity): Pair<BuildAdapter, File> {
        val profile = w.profileJson?.let { runCatching { ContractJson.decodeFromString(RepositoryProfile.serializer(), it) }.getOrNull() } ?: intelligence.inspectAndStore(w)
        val a = BuildAdapters.detect(profile) ?: throw WorkspaceException("Aucun système de build reconnu dans « ${w.name} » : utilise exec_run avec une commande explicite.")
        return a to workspaces.fs(w).root
    }

    private suspend fun exec(w: WorkspaceEntity, cmd: BuildCommand, network: NetworkMode, timeoutMs: Long, taskId: String?, backend: String?, onLog: (String) -> Unit): ExecResult {
        val choice = sandbox.choose(w, ProcessSpec(cmd.command, timeoutMs = timeoutMs, network = network, maxOutputBytes = 400_000, artifactGlobs = cmd.artifactGlobs + cmd.reportGlobs, taskId = taskId), backend)
        return choice.backend.run(w, choice.spec, onLog).let { r -> if (choice.note != null) r.copy(note = listOfNotNull(r.note, choice.note).joinToString(" ")) else r }
    }

    suspend fun build(w: WorkspaceEntity, target: String?, network: NetworkMode, taskId: String?, backend: String? = null, timeoutMs: Long = 20 * 60_000L, onLog: (String) -> Unit = {}): BuildResult {
        val (a, root) = adapter(w)
        val cmd = a.build(root, target)
        val revision = workspaces.revision(w)
        val started = System.currentTimeMillis()
        val r = try { exec(w, cmd, network, timeoutMs, taskId, backend, logSink(w, "build", onLog)) } finally { logDone() }
        val produced = r.artifactIds.mapNotNull { artifacts.get(it) }.filterNot { it.name.endsWith(".xml") && it.name.contains("test-results") }
        val result = BuildResult(
            status = r.status, exitCode = r.exitCode, durationMs = r.durationMs,
            diagnostics = Diagnostics.parse(r.stdout + "\n" + r.stderr, listOf(root.absolutePath)),
            artifacts = produced.map { artifacts.toContract(it) }, logTail = (r.stdout + "\n" + r.stderr).takeLast(6_000), backend = "${r.backend} · ${r.sandbox}",
        )
        record(RunRecord("build", w.workspaceId, taskId, target, result.status, "${result.status}, ${result.diagnostics.count { it.severity == "error" }} erreur(s), ${produced.size} artefact(s)",
            result.diagnostics.filter { it.severity == "error" }.map { "${it.file ?: ""}:${it.line ?: ""} ${it.message.take(120)}" }, revision, result.backend, started, r.durationMs))
        return result
    }

    suspend fun test(w: WorkspaceEntity, filter: String?, network: NetworkMode, taskId: String?, backend: String? = null, retryFlaky: Boolean = true, timeoutMs: Long = 20 * 60_000L, onLog: (String) -> Unit = {}): TestReport {
        val revision = workspaces.revision(w)
        val started = System.currentTimeMillis()
        val sink = logSink(w, "test", onLog)
        val report = try { testInner(w, filter, network, taskId, backend, retryFlaky, timeoutMs, sink) } finally { logDone() }
        record(RunRecord("test", w.workspaceId, taskId, filter, report.status, "${report.status} (${report.passed} ok, ${report.failed} ko${if (report.flaky.isNotEmpty()) ", ${report.flaky.size} instable(s)" else ""})",
            report.failedTests.map { listOfNotNull(it.suite, it.name).joinToString(".") } + report.flaky.map { "instable:$it" } + listOfNotNull(report.infraFailure?.let { "infra" }),
            revision, "${report.exec.backend} · ${report.exec.sandbox}", started, System.currentTimeMillis() - started))
        return report
    }

    private suspend fun testInner(w: WorkspaceEntity, filter: String?, network: NetworkMode, taskId: String?, backend: String?, retryFlaky: Boolean, timeoutMs: Long, onLog: (String) -> Unit): TestReport {
        val (a, root) = adapter(w)
        val first = runOnce(w, a, root, filter, network, taskId, backend, timeoutMs, onLog)
        var report = first
        if (retryFlaky && first.status == "failed" && first.failedTests.isNotEmpty() && first.failedTests.size <= 10) {
            val names = first.failedTests.map { it.name }
            val rerun = runOnce(w, a, root, names.joinToString("|") { Regex.escape(it) }.takeIf { names.size > 1 } ?: names.single(), network, taskId, backend, timeoutMs, onLog)
            if (rerun.infraFailure == null) {
                val stillFailing = rerun.failedTests.map { it.name }.toSet()
                val flaky = names.filter { it !in stillFailing && rerun.passedNames.any { p -> p.endsWith(it) } }
                if (flaky.isNotEmpty()) report = first.copy(flaky = flaky)
            }
        }
        val key = w.workspaceId + ":" + (filter ?: "*")
        val prev = lastFailures[key]
        val now = report.failedTests.map { listOfNotNull(it.suite, it.name).joinToString(".") }.toSet()
        lastFailures[key] = now
        return report.copy(fixed = prev?.let { (it - now).sorted() } ?: emptyList(), newlyFailing = prev?.let { (now - it).sorted() } ?: emptyList()).toReport()
    }

    private data class Run(val status: String, val passed: Int, val failed: Int, val skipped: Int, val failedTests: List<Diagnostics.FailedTest>, val passedNames: List<String>,
                           val infraFailure: String?, val diagnostics: List<Diagnostic>, val exec: ExecResult,
                           val flaky: List<String> = emptyList(), val fixed: List<String> = emptyList(), val newlyFailing: List<String> = emptyList()) {
        fun toReport() = TestReport(status, passed, failed, skipped, failedTests, flaky, infraFailure, diagnostics, fixed, newlyFailing, exec)
    }

    private suspend fun runOnce(w: WorkspaceEntity, a: BuildAdapter, root: File, filter: String?, network: NetworkMode, taskId: String?, backend: String?, timeoutMs: Long, onLog: (String) -> Unit): Run {
        val cmd = a.test(root, filter)
        val r = exec(w, cmd, network, timeoutMs, taskId, backend, onLog)
        val xmls = r.artifactIds.mapNotNull { artifacts.get(it) }.filter { it.name.endsWith(".xml") }.mapNotNull { runCatching { artifacts.file(it).readText() }.getOrNull() }
        val summaries = xmls.mapNotNull { runCatching { Diagnostics.junit(it) }.getOrNull() }
        val text = r.stdout + "\n" + r.stderr
        val diags = Diagnostics.parse(text, listOf(root.absolutePath))
        var failedTests = summaries.flatMap { it.failed }.map { it.copy(file = it.file?.let { f -> Diagnostics.relativize(f, listOf(root.absolutePath)) }) }
        var passedNames = summaries.flatMap { it.passed }
        var passed = passedNames.size
        var skipped = summaries.sumOf { it.skipped }
        if (summaries.isEmpty()) {
            // No structured report: read the common text summaries (cargo, go, unittest).
            Regex("""test result: \w+\. (\d+) passed; (\d+) failed; (\d+) ignored""").find(text)?.let { m -> passed = m.groupValues[1].toInt(); skipped = m.groupValues[3].toInt() }
            failedTests = Regex("""(?m)^(?:--- FAIL: (\S+)|FAIL: (\S+) \((\S+)\)|test (\S+) \.\.\. FAILED)""").findAll(text).map { m ->
                Diagnostics.FailedTest(m.groupValues.drop(1).first { it.isNotEmpty() }, m.groupValues[3].ifEmpty { null }, "échec")
            }.toList()
            Regex("""Ran (\d+) tests?""").find(text)?.let { passed = it.groupValues[1].toInt() - failedTests.size }
            Regex("""(?m)^--- PASS: (\S+)""").findAll(text).forEach { passedNames = passedNames + it.groupValues[1] }
            if (passedNames.isEmpty() && passed == 0) passed = Regex("""(?m)^ok\s""").findAll(text).count()
        }
        val status = when {
            r.status == "timed_out" -> "infra"
            failedTests.isNotEmpty() -> "failed"
            r.ok -> "passed"
            else -> "infra"
        }
        val infra = if (status == "infra") (diags.firstOrNull { it.severity == "error" }?.message ?: "la commande de test a échoué (${r.status}, code ${r.exitCode}) sans résultat de test : compilation, dépendances ou environnement") else null
        return Run(status, passed, failedTests.size, skipped, failedTests, passedNames, infra, diags, r)
    }

    suspend fun lint(w: WorkspaceEntity, network: NetworkMode, taskId: String?, backend: String? = null, onLog: (String) -> Unit = {}): Pair<ExecResult, List<Diagnostic>> {
        val (a, root) = adapter(w)
        val cmd = a.lint(root) ?: throw WorkspaceException("Pas de commande de lint connue pour ${a.id} : utilise exec_run.")
        val revision = workspaces.revision(w)
        val started = System.currentTimeMillis()
        val r = try { exec(w, cmd, network, 10 * 60_000L, taskId, backend, logSink(w, "lint", onLog)) } finally { logDone() }
        val diags = Diagnostics.parse(r.stdout + "\n" + r.stderr, listOf(root.absolutePath))
        record(RunRecord("lint", w.workspaceId, taskId, null, r.status, "${r.status}, ${diags.count { it.severity == "error" }} erreur(s)", diags.filter { it.severity == "error" }.map { "${it.file ?: ""}:${it.line ?: ""}" },
            revision, "${r.backend} · ${r.sandbox}", started, r.durationMs))
        return r to diags
    }
}

/** DependencyService (doc 03 §11): inspect, never apply blindly. Update proposals are made by the model and applied only as patches. */
class DependencyService(private val intelligence: RepositoryIntelligence) {
    data class Report(val manifests: List<String>, val lockfiles: List<String>, val dependencies: List<String>, val duplicates: List<String>, val unpinned: List<String>) {
        fun render() = buildString {
            append("Manifestes : ${manifests.joinToString().ifEmpty { "aucun" }}\nVerrous : ${lockfiles.joinToString().ifEmpty { "aucun (build non reproductible)" }}\n")
            append("Dépendances (${dependencies.size}) :\n"); dependencies.take(80).forEach { append("- $it\n") }
            if (duplicates.isNotEmpty()) append("Versions multiples : ${duplicates.joinToString()}\n")
            if (unpinned.isNotEmpty()) append("Non épinglées (plage ou dernière version) : ${unpinned.joinToString()}\n")
            append("Aucune mise à jour n'est appliquée automatiquement ; toute proposition passe par un patch relu.")
        }
    }

    suspend fun inspect(w: WorkspaceEntity): Report {
        val p = intelligence.profile(w)
        val locks = p.manifests.filter { it.substringAfterLast('/') in setOf("package-lock.json", "pnpm-lock.yaml", "yarn.lock", "poetry.lock", "uv.lock", "Cargo.lock", "go.sum", "gradle.lockfile") }
        val names = p.dependencies.map { it.substringBeforeLast(':').substringBeforeLast('@') }
        val dup = names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        val unpinned = p.dependencies.filter { d -> Regex("""[@:]?[\^~*]|latest|\+$|>=""").containsMatchIn(d.substringAfter('@', d.substringAfterLast(':'))) }
        return Report(p.manifests, locks, p.dependencies, dup, unpinned)
    }
}
