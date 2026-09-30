package io.github.artisanguillonrenov.cortana.core.dev

import io.github.artisanguillonrenov.cortana.contracts.ContractJson
import io.github.artisanguillonrenov.cortana.contracts.ReviewFileStat
import io.github.artisanguillonrenov.cortana.contracts.ReviewFinding
import io.github.artisanguillonrenov.cortana.contracts.ReviewResult
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ReviewService (doc 03 §13): a specialised, deterministic verifier — not a second orchestrator.
 * Before a coding task may be declared done it re-reads the net diff and checks scope, accidental
 * modifications, secrets, tests (run on the exact current code), architecture, migrations; then
 * summarises the change, lists remaining risks and returns a [ReviewResult] with a verdict.
 */
class ReviewService(
    private val workspaces: WorkspaceManager,
    private val patches: PatchEngine,
    private val git: GitService,
    private val intel: CodeIntelligence,
    private val builds: BuildService,
    private val artifacts: ArtifactService,
    private val repo: RepositoryIntelligence,
    private val protectedBranches: () -> Set<String> = { setOf("main", "master") },
) {
    data class Change(val path: String, val before: String?, val after: String?)

    /** Reviews the net changes of a task, one result per workspace it modified. */
    suspend fun reviewTask(taskId: String, scope: String? = null, persist: Boolean = true): List<ReviewResult> =
        patches.netChanges(taskId).mapNotNull { (wsId, changes) ->
            val w = workspaces.get(wsId) ?: return@mapNotNull null
            review(w, changes.map { Change(it.path, it.before, it.after) }, taskId, "task", scope, persist)
        }

    /** Reviews the uncommitted changes of a Git workspace (working tree vs HEAD). */
    suspend fun reviewUncommitted(w: WorkspaceEntity, scope: String? = null, taskId: String? = null, persist: Boolean = true): ReviewResult {
        val st = git.status(w)
        val paths = (st.added + st.changed + st.modified + st.missing + st.untracked + st.removed).toSortedSet()
        val fs = workspaces.fs(w)
        val changes = paths.map { p -> Change(p, git.fileAt(w, p), fs.resolve(p).takeIf { it.isFile && !RepositoryIntelligence.isBinary(it) }?.readText()) }
        return review(w, changes, taskId, "git", scope, persist)
    }

    suspend fun review(w: WorkspaceEntity, changes: List<Change>, taskId: String?, basis: String, scope: String?, persist: Boolean): ReviewResult = withContext(Dispatchers.IO) {
        val findings = mutableListOf<ReviewFinding>()
        val stats = mutableListOf<ReviewFileStat>()
        val added = HashMap<String, List<Pair<Int, String>>>()
        val removed = HashMap<String, List<String>>()
        // 1. Re-read the diff.
        for (c in changes) {
            val edits = Diff.edits(Diff.lines(c.before.orEmpty()), Diff.lines(c.after.orEmpty()))
            var n = 0
            val ins = mutableListOf<Pair<Int, String>>(); val del = mutableListOf<String>()
            for (e in edits) when (e) {
                is Diff.Keep -> n++
                is Diff.Ins -> { n++; ins += n to e.line }
                is Diff.Del -> del += e.line
            }
            added[c.path] = ins; removed[c.path] = del
            stats += ReviewFileStat(c.path, ins.size, del.size, when { c.before == null -> "added"; c.after == null -> "deleted"; else -> "modified" })
        }
        // 2. Scope.
        scope?.let { glob ->
            val re = CodeSearch.globToRegex(glob)
            changes.filterNot { re.matches(it.path) }.forEach { findings += ReviewFinding("major", "scope", "Modifié hors du périmètre demandé ($glob)", it.path) }
        }
        // 3. Accidental modifications.
        for (c in changes) {
            val p = c.path
            val ins = added[p].orEmpty(); val del = removed[p].orEmpty()
            if (p.split('/').any { it in WorkspaceFs.GENERATED_DIRS }) findings += ReviewFinding("major", "accidental", "Fichier généré/de build modifié", p)
            if (c.before != null && c.after != null && c.before != c.after && normalize(c.before) == normalize(c.after)) findings += ReviewFinding("minor", "accidental", "Changement d'espaces ou de fins de ligne uniquement", p)
            ins.firstOrNull { (_, l) -> CONFLICT.matches(l) }?.let { (n, _) -> findings += ReviewFinding("blocker", "accidental", "Marqueur de conflit de fusion", p, n) }
            if (!CodeIntelligence.isTest(p)) ins.firstOrNull { (_, l) -> DEBUG.containsMatchIn(l) }?.let { (n, l) -> findings += ReviewFinding("minor", "accidental", "Trace de débogage ajoutée : ${l.trim().take(80)}", p, n) }
            if (c.after == null) findings += ReviewFinding("major", "accidental", "Fichier supprimé", p)
            else if (c.before != null && Diff.lines(c.before).size >= 20 && del.size > Diff.lines(c.before).size * 0.8) findings += ReviewFinding("major", "accidental", "Suppression massive (${del.size} lignes retirées)", p)
            // Tests weakened to get green: skips added, assertions removed.
            if (CodeIntelligence.isTest(p)) {
                ins.firstOrNull { (_, l) -> SKIP.containsMatchIn(l) }?.let { (n, _) -> findings += ReviewFinding("blocker", "tests", "Test désactivé ou ignoré par la modification", p, n) }
                val lostAsserts = del.count { ASSERT.containsMatchIn(it) } - ins.count { (_, l) -> ASSERT.containsMatchIn(l) }
                if (lostAsserts > 0) findings += ReviewFinding("blocker", "tests", "$lostAsserts assertion(s) retirée(s) d'un test", p)
            }
        }
        val names = changes.map { it.path.substringAfterLast('/') }.toSet()
        if (names.any { it in LOCKFILES } && names.none { it in MANIFESTS }) findings += ReviewFinding("minor", "accidental", "Fichier de verrouillage modifié sans changement du manifeste")
        // 4. Secrets in added lines (known values and patterns); the line itself is never echoed.
        for ((p, ins) in added) for ((n, l) in ins) {
            val hit = RepositoryIntelligence.SECRET_PATTERNS.firstOrNull { it.second.containsMatchIn(l) }?.first ?: if (Redactor.redact(l) != l) "secret connu" else null
            if (hit != null) { findings += ReviewFinding("blocker", "secrets", "Secret potentiel ajouté ($hit)", p, n); break }
        }
        // 5. Tests: the last full-suite run must be on this exact code and green.
        val revision = workspaces.revision(w)
        val profile = runCatching { repo.profile(w) }.getOrNull()
        val hasTests = (profile?.testFiles ?: 0) > 0 || changes.any { CodeIntelligence.isTest(it.path) }
        val runs = builds.runsFor(w.workspaceId).filter { it.kind == "test" && it.filter == null }
        val current = runs.lastOrNull { it.revision == revision }
        val codeChanged = changes.any { LexicalProvider.language(it.path) != null || it.path.substringAfterLast('/') in MANIFESTS }
        var testsVerified = false
        when {
            !codeChanged -> testsVerified = true
            !hasTests -> findings += ReviewFinding("major", "tests", "Le projet n'a aucun test : le changement n'est pas vérifié par des tests")
            current == null -> findings += ReviewFinding("blocker", "tests",
                if (runs.isEmpty()) "Suite de tests jamais lancée sur ce changement (test_run sans filtre)" else "Suite de tests non relancée depuis la dernière modification (test_run sans filtre)")
            current.status != "passed" -> findings += ReviewFinding("blocker", "tests", "Suite de tests en échec sur le code actuel : ${current.failing.take(10).joinToString().ifEmpty { current.summary }}")
            else -> {
                testsVerified = true
                current.failing.filter { it.startsWith("instable:") }.forEach { findings += ReviewFinding("major", "tests", "Test instable : ${it.removePrefix("instable:")}") }
            }
        }
        val builds = builds.runsFor(w.workspaceId).filter { it.kind == "build" && (taskId == null || it.taskId == taskId) }
        builds.lastOrNull()?.let { b ->
            if (b.revision != revision && codeChanged) findings += ReviewFinding("major", "build", "Build antérieur à la dernière modification : relancer build_run")
            else if (b.status != "succeeded") findings += ReviewFinding("blocker", "build", "Build en échec sur le code actuel : ${b.summary}")
        }
        // 6. Architecture: static diagnostics of the changed files, repository rules, CI.
        val touched = changes.map { it.path }.toSet()
        intel.staticDiagnostics(w).filter { it.file in touched }.forEach { d ->
            findings += ReviewFinding(if (d.severity == "error") "blocker" else "minor", "architecture", d.message, d.file, d.line)
        }
        val graph = runCatching { intel.dependencyGraph(w) }.getOrDefault(emptyMap())
        cycleThrough(graph, touched)?.let { findings += ReviewFinding("major", "architecture", "Dépendance circulaire : ${it.joinToString(" → ")}") }
        changes.filter { it.path.substringAfterLast('/').uppercase() in INSTRUCTION_FILES }.forEach { findings += ReviewFinding("major", "architecture", "Fichier d'instructions du dépôt modifié", it.path) }
        changes.filter { c -> CI.containsMatchIn(c.path) }.forEach { findings += ReviewFinding("major", "architecture", "Configuration CI modifiée (chaîne d'approvisionnement)", it.path) }
        if (basis == "task" && runCatching { git.status(w).branch }.getOrNull()?.let { it in protectedBranches() } == true) {
            findings += ReviewFinding("major", "architecture", "Travail sur une branche protégée : créer une branche dédiée (repo_branch_create) avant de committer")
        }
        // 7. Migrations.
        val schemaChanged = changes.filter { c -> SCHEMA.any { it.containsMatchIn(c.after.orEmpty()) || it.containsMatchIn(c.before.orEmpty()) } && !MIGRATION_PATH.containsMatchIn(c.path) && !CodeIntelligence.isTest(c.path) }
            .filter { c -> added[c.path].orEmpty().isNotEmpty() || removed[c.path].orEmpty().isNotEmpty() }
        if (schemaChanged.isNotEmpty() && changes.none { MIGRATION_PATH.containsMatchIn(it.path) || MIGRATION_CODE.containsMatchIn(it.after.orEmpty()) && it !in schemaChanged }) {
            findings += ReviewFinding("major", "migrations", "Schéma de données modifié sans migration : ${schemaChanged.joinToString { it.path }}")
        }
        for ((p, ins) in added) {
            ins.firstOrNull { (_, l) -> DESTRUCTIVE_MIGRATION.containsMatchIn(l) }?.let { (n, _) -> findings += ReviewFinding("blocker", "migrations", "Migration destructive activée (perte des données)", p, n) }
            ins.firstOrNull { (_, l) -> Regex("(?i)\\bDROP\\s+(TABLE|COLUMN)\\b").containsMatchIn(l) }?.let { (n, _) -> findings += ReviewFinding("major", "migrations", "Suppression de table/colonne", p, n) }
        }
        // 8-10. Summary, remaining risks, verdict.
        val verdict = when { findings.any { it.severity == "blocker" } -> "blocked"; findings.any { it.severity == "major" } -> "changes_requested"; else -> "approved" }
        val risks = buildList {
            if (!testsVerified && codeChanged) add("Changement non vérifié par une suite de tests verte sur le code actuel")
            if (changes.any { LexicalProvider.language(it.path) != null }) add("Analyse lexicale : pas de résolution de types (accès dynamiques, homonymes)")
            findings.filter { it.severity == "major" }.forEach { add(it.message + (it.file?.let { f -> " ($f)" } ?: "")) }
        }.distinct()
        val summary = "${changes.size} fichier(s), +${stats.sumOf { it.added }}/−${stats.sumOf { it.removed }} : " +
            stats.joinToString { "${it.path} (${it.status}, +${it.added}/−${it.removed})" }
        val result = ReviewResult(reviewId = Ids.new(), workspaceId = w.workspaceId, taskId = taskId, basis = basis, verdict = verdict, summary = summary,
            files = stats, findings = findings.distinct(), risks = risks, testsVerified = testsVerified, revision = revision, createdAt = System.currentTimeMillis())
        if (persist) runCatching {
            val diff = changes.joinToString("") { Diff.unified(it.path, it.path, it.before, it.after) }
            artifacts.registerText(ContractJson.encodeToString(ReviewResult.serializer(), result) + "\n\n" + Redactor.redact(diff), "report", "review-${result.reviewId.take(8)}.txt", taskId, "review.changes")
        }
        result
    }

    private fun normalize(s: String) = s.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.trimEnd()

    /** A dependency cycle that goes through one of [touched], if any. */
    private fun cycleThrough(graph: Map<String, Set<String>>, touched: Set<String>): List<String>? {
        for (start in touched.filter { it in graph }) {
            val stack = ArrayDeque(listOf(listOf(start)))
            val seen = HashSet<String>()
            while (stack.isNotEmpty()) {
                val path = stack.removeLast()
                for (next in graph[path.last()].orEmpty()) {
                    if (next == start) return path + start
                    if (seen.add(next) && path.size < 12) stack.addLast(path + next)
                }
            }
        }
        return null
    }

    companion object {
        fun render(r: ReviewResult) = buildString {
            append(when (r.verdict) { "approved" -> "✅ Revue : approuvé"; "changes_requested" -> "⚠️ Revue : corrections recommandées"; else -> "⛔ Revue : bloqué" })
            append(" — ").append(r.summary).append('\n')
            append("Tests : ").append(if (r.testsVerified) "suite verte sur le code actuel" else "non vérifiés").append('\n')
            r.findings.sortedBy { SEVERITY.indexOf(it.severity) }.take(30).forEach { f ->
                append("- [${f.severity}] ${f.check} : ${f.message}${f.file?.let { " ($it${f.line?.let { l -> ":$l" } ?: ""})" } ?: ""}\n")
            }
            if (r.risks.isNotEmpty()) append("Risques restants : ").append(r.risks.joinToString("; ")).append('\n')
        }

        private val SEVERITY = listOf("blocker", "major", "minor", "info")
        private val CONFLICT = Regex("""^(<{7}|>{7})( .*)?$|^={7}$""")
        private val DEBUG = Regex("""\bconsole\.log\(|\bdebugger;|\bSystem\.out\.println\(|\bprintStackTrace\(\)|\bdbg!\(|\bpdb\.set_trace\(\)|\bbreakpoint\(\)""")
        private val SKIP = Regex("""@Ignore\b|@Disabled\b|\.skip\(|\bxit\(|\bxdescribe\(|pytest\.mark\.skip|unittest\.skip|#\[ignore]|\bt\.Skip\(|\btest\.todo\(""")
        private val ASSERT = Regex("""\bassert|\bexpect\(|\bshould\b|\brequire\.\w+\(|\bt\.(Error|Fatal)""")
        private val LOCKFILES = setOf("package-lock.json", "pnpm-lock.yaml", "yarn.lock", "poetry.lock", "uv.lock", "Cargo.lock", "go.sum", "gradle.lockfile")
        private val MANIFESTS = setOf("package.json", "pyproject.toml", "requirements.txt", "Cargo.toml", "go.mod", "build.gradle", "build.gradle.kts", "pom.xml", "libs.versions.toml")
        private val INSTRUCTION_FILES = setOf("AGENTS.MD", "CLAUDE.MD", "GEMINI.MD", ".CURSORRULES", "COPILOT-INSTRUCTIONS.MD", "CONTRIBUTING.MD")
        private val CI = Regex("""^\.github/workflows/|^\.gitlab-ci\.yml$|^\.circleci/|^Jenkinsfile$|^azure-pipelines\.yml$""")
        private val SCHEMA = listOf(Regex("""@Entity\b"""), Regex("""(?i)\bCREATE\s+TABLE\b"""), Regex("""\bmodels\.Model\b"""), Regex("""(?m)^model \w+ \{"""), Regex("""(?i)\bALTER\s+TABLE\b"""))
        private val MIGRATION_PATH = Regex("""(?i)(^|/)(migrations?|db/migrate|alembic)/|Migration[s]?\.(kt|java|py|ts|js)$|schemas/.+\.json$""")
        /** Room's destructive fallbacks (all variants: OnDowngrade, From…). */
        private val DESTRUCTIVE_MIGRATION = Regex("""\bfallbackTo\w*Destructive\w*Migration""")
        private val MIGRATION_CODE = Regex("""\bMigration\(|\bAutoMigration\b|\bmigrations\.\w+\(|op\.(add|drop|alter)_""")
    }
}
