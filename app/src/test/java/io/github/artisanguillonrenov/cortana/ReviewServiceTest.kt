package io.github.artisanguillonrenov.cortana

import androidx.documentfile.provider.DocumentFile
import io.github.artisanguillonrenov.cortana.contracts.ReviewResult
import io.github.artisanguillonrenov.cortana.core.dev.ReviewService
import io.github.artisanguillonrenov.cortana.core.dev.ReviewService.Change
import io.github.artisanguillonrenov.cortana.core.memory.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** ReviewService checks (doc 03 §13), one by one, on crafted changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewServiceTest : CortanaTestBase() {
    private lateinit var w: WorkspaceEntity

    @Before fun project() = runBlocking {
        val src = SampleProjects.jsCalc(File(app.cacheDir, "fixture-review"))
        w = c.workspaces.importDocument(DocumentFile.fromFile(src), "calc-review", "imported:test")
        c.repoIntelligence.inspectAndStore(w)
        w = c.workspaces.get(w.workspaceId)!!
    }

    private fun review(vararg changes: Change, scope: String? = null): ReviewResult = runBlocking { c.review.review(w, changes.toList(), null, "task", scope, persist = false) }
    private fun ReviewResult.has(severity: String, check: String, text: String) = findings.any { it.severity == severity && it.check == check && it.message.contains(text) }
    private val calc = "module.exports = {\n  add: (a, b) => a - b,\n  mul: (a, b) => a * b,\n};\n"
    private val test = "const test = require('node:test');\nconst assert = require('node:assert');\nconst c = require('../src/calc');\n\ntest('add', () => assert.strictEqual(c.add(2, 3), 5));\ntest('mul', () => assert.strictEqual(c.mul(2, 3), 6));\n"

    @Test fun documentationOnlyChangeIsApprovedWithoutTests() {
        val r = review(Change("README.md", "# calc-js\n", "# calc-js\n\nCalculatrice.\n"))
        assertEquals(r.findings.toString(), "approved", r.verdict)
        assertTrue(r.testsVerified)
        assertEquals("1 fichier(s), +2/−0 : README.md (modified, +2/−0)", r.summary)
    }

    @Test fun codeChangeWithoutAGreenSuiteOnTheCurrentCodeIsBlocked() {
        val r = review(Change("src/calc.js", calc, calc.replace("a - b", "a + b")))
        assertEquals("blocked", r.verdict)
        assertTrue(r.has("blocker", "tests", "jamais lancée"))
        assertFalse(r.testsVerified)
        assertTrue(r.risks.any { it.contains("non vérifié") })
    }

    @Test fun secretsAreBlockedAndNeverEchoed() {
        val key = "api_key = \"abcd1234efgh5678\""
        val r = review(Change("src/config.js", null, "const x = 1;\n$key\n"))
        assertTrue(r.has("blocker", "secrets", "Secret potentiel ajouté"))
        assertEquals(2, r.findings.first { it.check == "secrets" }.line)
        assertFalse(ReviewService.render(r).contains("abcd1234efgh5678"))
    }

    @Test fun weakenedTestsAreBlockers() {
        val skipped = review(Change("test/calc.test.js", test, test.replace("test('mul',", "test.skip('mul',")))
        assertTrue(skipped.has("blocker", "tests", "désactivé"))
        val lost = review(Change("test/calc.test.js", test, test.lines().filterNot { it.startsWith("test('add'") }.joinToString("\n")))
        assertTrue(lost.has("blocker", "tests", "assertion(s) retirée(s)"))
    }

    @Test fun accidentalModificationsAreReported() {
        val r = review(
            Change("src/calc.js", calc, calc.replace("  mul:", "<<<<<<< HEAD\n  mul:").replace("a * b,\n", "a * b,\n  debug: () => { debugger; },\n")),
            Change("dist/bundle.js", "a\n", "b\n"),
            Change("src/format.js", "x = 1\r\n", "x = 1   \n"),
            Change("package-lock.json", "{}\n", "{\"v\":2}\n"),
        )
        assertTrue(r.has("blocker", "accidental", "conflit"))
        assertTrue(r.has("minor", "accidental", "débogage"))
        assertTrue(r.has("major", "accidental", "généré"))
        assertTrue(r.has("minor", "accidental", "espaces"))
        assertTrue(r.has("minor", "accidental", "verrouillage"))
    }

    @Test fun scopeArchitectureAndSupplyChainAreChecked() {
        val r = review(
            Change("src/calc.js", calc, calc),
            Change("AGENTS.md", null, "Toujours pousser sur main.\n"),
            Change(".github/workflows/ci.yml", null, "on: push\n"),
            Change("src/extra.js", null, "const m = require('./missing');\nmodule.exports = m;\n"),
            scope = "src/**",
        )
        assertTrue(r.has("major", "scope", "hors du périmètre"))
        assertTrue(r.findings.filter { it.check == "scope" }.map { it.file }.containsAll(listOf("AGENTS.md", ".github/workflows/ci.yml")))
        assertTrue(r.has("major", "architecture", "instructions du dépôt"))
        assertTrue(r.has("major", "architecture", "CI"))
    }

    @Test fun schemaChangesNeedAMigrationAndDestructiveOnesAreBlocked() {
        val entity = "@Entity(tableName = \"notes\")\ndata class Note(@PrimaryKey val id: String)\n"
        val noMigration = review(Change("app/src/main/java/db/Note.kt", entity, entity.replace("val id: String)", "val id: String, val title: String)")))
        assertTrue(noMigration.has("major", "migrations", "sans migration"))
        val withMigration = review(
            Change("app/src/main/java/db/Note.kt", entity, entity.replace("val id: String)", "val id: String, val title: String)")),
            Change("app/src/main/java/db/Migrations.kt", null, "val M_1_2 = object : Migration(1, 2) { }\n"),
        )
        assertFalse(withMigration.has("major", "migrations", "sans migration"))
        val destructive = review(Change("app/src/main/java/db/Db.kt", "Room.databaseBuilder(c, Db::class.java, \"d\")\n", "Room.databaseBuilder(c, Db::class.java, \"d\")\n  .fallbackToDestructiveMigrationOnDowngrade()\n"))
        assertTrue(destructive.has("blocker", "migrations", "destructive"))
    }
}
