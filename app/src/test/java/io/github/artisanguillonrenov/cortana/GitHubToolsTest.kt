package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.dev.GitService
import io.github.artisanguillonrenov.cortana.core.planner.IntentRouter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.SymbolicRef
import io.github.artisanguillonrenov.cortana.core.policy.PolicyDecision
import io.github.artisanguillonrenov.cortana.core.policy.Requirement
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.Trait
import io.github.artisanguillonrenov.cortana.core.tools.ToolContext
import io.github.artisanguillonrenov.cortana.core.tools.ToolFamilies
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.executors.web.GitHubTools
import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** GitHub read online without cloning (rc10): the tools, their errors and the method that routes audits to them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubToolsTest : CortanaTestBase() {
    private lateinit var gh: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private var token: String? = null

    @Before fun startServer() {
        gh = MockWebServer()
        gh.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                return when {
                    p == "/repos/o/r" -> json("""{"full_name":"o/r","private":false,"description":"Assistant","default_branch":"main","size":1200,"stargazers_count":3,"forks_count":0,"open_issues_count":2,"license":{"spdx_id":"MIT"},"created_at":"2026-01-01","pushed_at":"2026-10-08"}""")
                    p == "/repos/o/r/languages" -> json("""{"Kotlin":900,"Python":100}""")
                    p.startsWith("/repos/o/r/git/trees/main") -> json("""{"truncated":false,"tree":[{"path":"README.md","type":"blob","size":120},{"path":"app","type":"tree"},{"path":"app/Main.kt","type":"blob","size":42},{"path":"docs/x.md","type":"blob","size":9}]}""")
                    p.startsWith("/repos/o/r/contents/app/Main.kt") -> MockResponse().setBody("fun main() {\n    println(\"Bonjour\")\n}\n")
                    p.startsWith("/repos/o/r/contents/missing") -> MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
                    p.startsWith("/repos/o/r/commits?") -> json("""[{"sha":"abcdef1234","commit":{"message":"Premier commit\n\ndétail","author":{"name":"Moi","date":"2026-10-01T10:00:00Z"}}}]""")
                    p.startsWith("/repos/o/r/actions/runs?") -> json("""{"workflow_runs":[{"id":77,"name":"Android CI","head_branch":"main","head_sha":"abcdef1","status":"completed","conclusion":"failure","created_at":"2026-10-08T12:00"}]}""")
                    request.method == "POST" && p == "/repos/o/r/issues" -> json("""{"number":5,"html_url":"https://github.com/o/r/issues/5"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        gh.start()
    }

    @After fun stopServer() = gh.shutdown()

    private fun tools() = GitHubTools(OkHttpClient(), { token }, gh.url("/").toString().trimEnd('/')).tools()

    private fun run(cap: String, args: String): ToolResult = runBlocking {
        val def = tools().single { it.capability == cap }
        val ctx = object : ToolContext {
            override val taskId = "t-gh"; override val sessionId = "s"; override val tainted = false; override val lastUserText = ""; override val incognito = false
            override val approvedRisk = Risk.L2; override val toolset = "full"
            override fun resolveSecret(handle: String?): String? = null; override fun markUiAutomation() {}
        }
        def.invokeAuthorized(AppJson.parseToJsonElement(args) as JsonObject, ctx, PolicyDecision(cap, def.baseRisk, def.baseRisk, Requirement.ALLOW, emptyList(), false))
    }

    @Test fun repositoryLinksAreUnderstood() {
        assertEquals(GitHubTools.RepoRef("o", "r"), GitHubTools.parseRepo("o/r"))
        assertEquals(GitHubTools.RepoRef("artisanguillonrenov-creator", "Cortana-"), GitHubTools.parseRepo("https://github.com/artisanguillonrenov-creator/Cortana-.git"))
        assertEquals(GitHubTools.RepoRef("o", "r", "dev", "app/src"), GitHubTools.parseRepo("https://github.com/o/r/tree/dev/app/src/"))
        assertEquals(GitHubTools.RepoRef("o", "r", "main", "README.md"), GitHubTools.parseRepo("github.com/o/r/blob/main/README.md"))
        assertNull(GitHubTools.parseRepo("https://github.com/o"))
        assertNull(GitHubTools.parseRepo("../etc/passwd"))
    }

    @Test fun anAuditReadsTheRepositoryOnlineWithoutCloning() {
        val repo = run("github.repo", """{"repo":"https://github.com/o/r"}""")
        assertTrue(repo.text, repo.ok && repo.text.contains("Branche par défaut : main") && repo.text.contains("Kotlin 90 %") && repo.text.contains("Licence : MIT"))
        assertEquals("github:o/r", repo.untrustedSource)

        val tree = run("github.tree", """{"repo":"o/r","path":"app"}""")
        assertTrue(tree.text, tree.text.contains("app/Main.kt  (42 o)") && !tree.text.contains("docs/x.md"))

        val file = run("github.file", """{"repo":"https://github.com/o/r/blob/main/app/Main.kt"}""")
        assertTrue(file.text, file.text.contains("    2      println(\"Bonjour\")"))
        assertTrue("the ref of the link is used", requests.any { it.path == "/repos/o/r/contents/app/Main.kt?ref=main" })

        val part = run("github.file", """{"repo":"o/r","path":"app/Main.kt","start_line":2,"end_line":2}""")
        assertFalse(part.text.contains("fun main"))

        assertTrue(run("github.commits", """{"repo":"o/r"}""").text.contains("abcdef1 2026-10-01 Moi — Premier commit"))
        assertTrue(run("github.actions", """{"repo":"o/r"}""").text.contains("Android CI sur main (abcdef1) : completed failure"))
        assertTrue("anonymous reads send no credentials", requests.none { it.getHeader("Authorization") != null })
    }

    @Test fun errorsSayWhatToDo() {
        val missing = run("github.file", """{"repo":"o/r","path":"missing.txt"}""")
        assertFalse(missing.ok)
        assertTrue(missing.text, missing.text.contains("introuvable") && missing.text.contains("Réglages → Git"))
        assertTrue(run("github.search", """{"repo":"o/r","query":"main"}""").text.contains("jeton github.com"))
        assertTrue(run("github.repo", """{"repo":"pas un dépôt"}""").text.contains("dépôt illisible"))
    }

    @Test fun writesNeedATokenAndTheOwnersConfirmation() {
        val write = tools().single { it.capability == "github.issue.create" }
        assertEquals(Risk.L2, write.baseRisk)
        assertTrue(Trait.USER_VISIBLE_TO_THIRD_PARTY in write.traits)
        assertTrue(tools().filter { it.capability in setOf("github.repo", "github.tree", "github.file") }.all { Trait.READ_ONLY in it.traits })

        assertTrue(run("github.issue.create", """{"repo":"o/r","title":"Bug"}""").text.contains("sans jeton"))
        assertTrue("nothing was sent without a token", requests.none { it.method == "POST" })
        token = "ghp_test"
        val ok = run("github.issue.create", """{"repo":"o/r","title":"Bug","body":"détails"}""")
        assertTrue(ok.text, ok.text.contains("Issue #5 créée"))
        assertEquals("Bearer ghp_test", requests.last().getHeader("Authorization"))
    }

    @Test fun theToolsAreRegisteredInTheGitFamilyAndAuditsUseTheCodingRoute() {
        assertTrue(listOf("github.repo", "github.tree", "github.file", "github.search", "github.commits", "github.pulls", "github.issues", "github.actions")
            .all { c.registry.byCapability(it) != null })
        val def = c.registry.byCapability("github.tree")!!
        assertEquals(ToolFamilies.GIT, ToolFamilies.of(def.capability, def.category))
        val pool = c.registry.all()
        fun offered(text: String) = io.github.artisanguillonrenov.cortana.core.tools.CapabilityMatcher(io.github.artisanguillonrenov.cortana.core.tools.ToolDiscovery())
            .select(pool, text, setOf(io.github.artisanguillonrenov.cortana.core.tools.ToolCategory.WEB), emptyList(), emptySet(), io.github.artisanguillonrenov.cortana.contracts.PlanStrategy.INTERACTIVE, 24).offered.map { it.capability }
        assertTrue(offered("Audite le dépôt https://github.com/o/r").containsAll(listOf("github.repo", "github.tree", "github.file")))
        val general = offered("Le train de nuit Paris-Nice, sans voiture sur place ?")
        assertTrue(general.toString(), "web.fetch" in general && general.none { it.startsWith("github.") })
        assertTrue(IntentRouter().classify("Audite le dépôt https://github.com/o/r", true).coding)
        assertTrue(c.contextEngine.workMethod.contains("Ne clone jamais pour lire"))
    }

    @Test fun aCloneFetchesOneBranchOnly() {
        val a = ObjectId.fromString("1111111111111111111111111111111111111111")
        val b = ObjectId.fromString("2222222222222222222222222222222222222222")
        fun head(name: String, id: ObjectId): Ref = ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, name, id)
        val main = head("refs/heads/main", a)
        val refs = mapOf("refs/heads/main" to main, "refs/heads/release-assets/2.0.0-rc9" to head("refs/heads/release-assets/2.0.0-rc9", b), "refs/heads/dev" to head("refs/heads/dev", a))
        assertEquals("main", GitService.branchToClone(refs + ("HEAD" to SymbolicRef("HEAD", main)), null))
        assertEquals("main", GitService.branchToClone(refs + ("HEAD" to head("HEAD", a)), null))
        assertEquals("dev", GitService.branchToClone(refs, "refs/heads/dev"))
        assertEquals("main", GitService.branchToClone(refs, null))
        assertNull(GitService.branchToClone(emptyMap(), null))
    }
}
