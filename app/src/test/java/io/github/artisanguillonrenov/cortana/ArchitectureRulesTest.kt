package io.github.artisanguillonrenov.cortana

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture laws (production pack §101, doc 00 §Règles absolues) enforced as failing tests,
 * not as documentation. Each rule scans the real main sources.
 */
class ArchitectureRulesTest {
    private val root = File("src/main/java/io/github/artisanguillonrenov/cortana")
    private val sources: Map<String, String> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(root).path.replace('\\', '/') to stripComments(it.readText()) }

    private fun stripComments(s: String): String =
        s.replace(Regex("(?s)/\\*.*?\\*/"), "").lines().joinToString("\n") { line -> line.replace(Regex("^\\s*//.*$"), "") }

    private fun violations(pattern: Regex, allowed: (String) -> Boolean): List<String> =
        sources.filter { (path, text) -> !allowed(path) && pattern.containsMatchIn(text) }.keys.sorted()

    private fun assertNone(law: String, v: List<String>) = assertTrue("$law violated in: $v", v.isEmpty())

    @Test fun law001_onlyModelGatewayCallsModels() {
        assertNone("LAW-001", violations(Regex("""\.chatStream\(|OpenAiCompatibleProvider\(|"/chat/completions"|"/embeddings"|"/images/|"/videos|"/audio/"""")) { it.startsWith("core/model/") })
    }

    @Test fun law002_onlyOrchestratorMutatesTaskState() {
        assertNone("LAW-002", violations(Regex("""tasks\(\)\.upsert\(|taskDao\.upsert\(|\.transition\(""")) { it.startsWith("core/orchestrator/") })
    }

    @Test fun law003_004_toolsRunOnlyThroughPolicyGatedDispatcher() {
        assertNone("LAW-003/004", violations(Regex("""\.invokeAuthorized\(""")) { it == "core/tools/ToolDispatcher.kt" })
    }

    @Test fun law005_memoryServiceIsTheOnlyMemoryWriter() {
        assertNone("LAW-005", violations(Regex("""\.memories\(\)|MemoryDao\b""")) { it.startsWith("core/memory/") })
    }

    @Test fun law015_pluginsNeverLoadOrRunCode() {
        // Plugins are declarative: no class loading, native libraries or processes in the plugin system.
        assertNone("LAW-015", violations(Regex("""ClassLoader|System\.load|Runtime\.getRuntime\(\)\.exec|ProcessBuilder|loadLibrary""")) { !(it.startsWith("core/plugins/") || it.startsWith("executors/plugins/") || it.startsWith("ui/settings/Plugin")) })
    }

    @Test fun law006_schedulerNeverExecutesToolsOrCallsModels() {
        val sched = sources.filterKeys { it.startsWith("core/scheduler/") }
        val bad = sched.filter { (_, t) -> Regex("""ToolRegistry|ToolDispatcher|invokeAuthorized|ModelGateway|\.complete\(""").containsMatchIn(t) }.keys
        assertNone("LAW-006", bad.toList())
    }

    @Test fun law019_improvementOnlyProposesAndNeverExecutes() {
        // The Improvement Service writes proposals and owner-approved typed changes; it never runs a
        // tool, calls a model, starts a task or touches files and repositories (code changes go through the Software Factory).
        val imp = sources.filterKeys { it.startsWith("core/improvement/") }
        val bad = imp.filter { (_, t) -> Regex("""ToolDispatcher|invokeAuthorized|ModelGateway|\.complete\(|Orchestrator\b|submitRequest|WorkspaceService|GitService|PatchService|java\.io\.File|ProcessBuilder""").containsMatchIn(t) }.keys
        assertNone("LAW-019", bad.toList())
        // Applying is the owner's decision: no capability applies, rejects or rolls back a proposal.
        assertNone("LAW-019 tools", violations(Regex("""improvements?\.(apply|rollback|reject)\(""")) { it.startsWith("ui/") })
    }

    @Test fun restoreAndRepairAreOwnerOnly() {
        // Backups, restores and repairs are started from the owner's screens, never by a capability or the model.
        // The one exception: the automatic pre-update backup, inside UpdateService.prepare (itself owner-only, below).
        assertNone("backup/doctor", violations(Regex("""backups\.(restore|create)\(|doctor\.repair\(""")) { it.startsWith("ui/") || it == "core/update/UpdateService.kt" })
        // Updates are prepared and handed to the system installer only from the owner's screen.
        assertNone("update", violations(Regex("""updates\.(prepare|install)\(""")) { it.startsWith("ui/") })
    }

    @Test fun secretValuesAreReadOnlyByTheirOwningServices() {
        // Values behind handles are read by the service that owns them (wiring, providers, OTLP header, encrypted backup)…
        val owners = setOf("CortanaApp.kt", "core/model/ProviderRepository.kt", "core/observability/Observability.kt", "core/backup/Backup.kt")
        assertNone("secret readers", violations(Regex("""\bsecrets\.get\(""")) { it in owners })
        // …and never resolved for a task (a handle the model copied must stay useless).
        assertNone("resolveSecret", violations(Regex("""\.resolveSecret\(""")) { false })
    }

    @Test fun law009_uiNeverTouchesTheDatabase() {
        // Code only: a text shown on screen (the design's sample "abstract fun noteDao()") touches nothing.
        val pattern = Regex("""\.db\.|CortanaDatabase|\bDao\b|Dao\(\)""")
        val v = sources.filter { (path, text) -> path.startsWith("ui/") && pattern.containsMatchIn(withoutStrings(text)) }.keys.sorted()
        assertNone("LAW-009", v)
    }

    /** The source without its string literals (raw strings first, then quoted ones). */
    private fun withoutStrings(s: String): String = s.replace(Regex("(?s)\"\"\".*?\"\"\""), "\"\"").replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")

    @Test fun designColorsLiveOnlyInTheTokens() {
        // Cortana Workspace design: "aucune couleur en dur dans les composants" (grep -r "Color(0x" ui/ → tokens only).
        assertNone("design tokens", violations(Regex("""Color\(0x""")) { !it.startsWith("ui/") || it == "ui/theme/CortanaTokens.kt" })
    }

    @Test fun noGlobalScopeAndNoDestructiveMigration() {
        assertNone("GlobalScope", violations(Regex("""GlobalScope""")) { false })
        assertNone("destructive migration", violations(Regex("""fallbackToDestructiveMigration""")) { false })
    }

    @Test fun runBlockingOnlyForTheDocumentedSettingsWarmUp() {
        assertNone("runBlocking", violations(Regex("""\brunBlocking\b""")) { it == "core/memory/SettingsRepository.kt" })
    }

    @Test fun capabilityIdsAreUniqueAcrossExecutors() {
        val found = sources.flatMap { (_, t) -> Regex("""ToolDefinition\(\s*(?:capability\s*=\s*)?"([a-z_.]+)"""").findAll(t).map { it.groupValues[1] } } +
            sources.flatMap { (_, t) -> Regex("""def\(\s*"([a-z_.]+)"""").findAll(t).map { it.groupValues[1] } }
        val dupes = found.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("LAW-020 duplicate capability ids: $dupes", dupes.isEmpty())
        assertTrue("capability scan found nothing", found.size > 30)
    }

    // ------------------------------------------------------------------ Cognitive Council laws (D-20260929-067, doc 00, doc 12)

    private val council get() = sources.filterKeys { it.startsWith("core/council/") }

    @Test fun councilIsSubordinateNeverASecondOrchestratorGatewayRegistryOrMemory() {
        // One orchestrator, one gateway, one registry, one policy, one memory: the council only uses them.
        val bad = council.filter { (_, t) ->
            Regex("""invokeAuthorized|executors\.|OpenAiCompatibleProvider|okhttp3|OkHttpClient|\.tasks\(\)|\.messages\(\)|\.memories\(\)|ConversationRepository|MemoryRepository|\.addMessage\(|\.transition\(|TaskStateMachine|Orchestrator\b|ToolRegistry\(\)|ModelGateway\(|PolicyEngine\(|Room\.databaseBuilder""").containsMatchIn(t)
        }.keys
        assertNone("COUNCIL-1 subordination", bad.toList())
        // Every tool call of a council agent goes through the dispatcher, non-interactively (never asks the owner, never gets a secret).
        val caller = council.getValue("core/council/CouncilAgentCaller.kt")
        assertTrue(Regex("""dispatcher\.dispatch\(DispatchRequest\(""").containsMatchIn(caller) && caller.contains("interactive = false"))
        assertTrue(caller.contains("override fun resolveSecret(handle: String?): String? = null"))
        assertTrue("council model calls never fall back silently to another provider", caller.contains("allowFallback = false"))
    }

    @Test fun councilNeverPersistsReasoningOrPrompts() {
        val persisted = sources.filterKeys { it == "core/council/CouncilStore.kt" || it == "core/memory/CouncilEntities.kt" }
        assertTrue(persisted.size == 2)
        val bad = persisted.filter { (_, t) -> Regex("""(?i)\breasoning(Json|Details|Content)?\b|chainOfThought|\bprompt\b|rawOutput|\bmessages\b""").containsMatchIn(t.replace("reasoningEffort", "")) }.keys
        assertNone("COUNCIL-2 no reasoning/prompt persisted", bad.toList())
    }

    @Test fun councilsCannotStartCouncils() {
        val runtime = council.getValue("core/council/CouncilRuntime.kt")
        assertTrue("the runtime refuses to run inside a council", runtime.contains("coroutineContext[CouncilMarker] != null"))
        val bad = council.filterKeys { it != "core/council/CouncilRuntime.kt" && it != "core/council/CouncilGate.kt" && it != "core/council/CouncilContracts.kt" }
            .filter { (_, t) -> Regex("""CognitiveCouncilEngine|CouncilRuntime\b|\.run\(CouncilRunRequest|submitRequest|agent\.delegate""").containsMatchIn(t) }.keys
        assertNone("COUNCIL-3 recursion", bad.toList())
    }

    // ------------------------------------------------------------------ Chat Workspace laws (D-20260930-068, pack doc 00 and 15)

    private val workspace get() = sources.filterKeys { it.startsWith("ui/workspace/") || it.startsWith("core/chat/") }

    @Test fun workspaceIsAPresentationLayerNeverASecondBackend() {
        assertTrue("workspace sources found", workspace.size >= 10)
        // No second ModelGateway, ToolRegistry, Memory store, orchestrator or database: it only uses them.
        val bad = workspace.filter { (_, t) ->
            Regex("""\.complete\(|chatStream|ToolDispatcher|dispatcher\.|invokeAuthorized|executors\.|ModelGateway\(|ToolRegistry\(|PolicyEngine\(|Orchestrator\(|MemoryRepository\(|memory\.save\(|Room\.databaseBuilder|OkHttpClient""").containsMatchIn(t)
        }.keys
        assertNone("WORKSPACE-1 no second backend", bad.toList())
        // Every generation goes to the one orchestrator.
        val service = sources.getValue("core/chat/ChatService.kt")
        assertTrue(service.contains("orchestrator.submitRequest("))
    }

    @Test fun workspaceNeverApprovesAnActionItself() {
        // A chat component may refuse, or reopen the secure approval screen; it never approves (no implicit L2/L3 approval).
        val bad = workspace.filter { (_, t) -> Regex("""ApprovalDecision\(\s*(true|approved\s*=\s*true)""").containsMatchIn(t) }.keys
        assertNone("WORKSPACE-2 no approval from the chat", bad.toList())
        val vm = sources.getValue("ui/workspace/WorkspaceViewModel.kt")
        assertTrue(vm.contains("ApprovalDecision(false") && vm.contains("approvals.reopen("))
    }

    @Test fun workspaceRendersNoHtmlNoReasoningNoHiddenRows() {
        val bad = workspace.filter { (_, t) -> Regex("""WebView|Html\.fromHtml|AndroidView|loadData|evaluateJavascript|reasoningJson|reasoningDetails|chainOfThought""").containsMatchIn(t) }.keys
        assertNone("WORKSPACE-3 no HTML, no reasoning", bad.toList())
        val timeline = sources.getValue("core/chat/ChatTimeline.kt")
        assertTrue("hidden rows never become visible items", timeline.contains("m.role == Roles.USER && !m.hidden") && timeline.contains("if (!m.hidden)"))
        assertTrue("links are sanitised by the parser", sources.getValue("core/chat/Markdown.kt").contains("SafeLinks.sanitize("))
    }

    @Test fun comparisonNeverOffersToolsAndSharesNeverSend() {
        val compare = sources.getValue("core/orchestrator/CompareRunner.kt")
        assertTrue(Regex("""gateway\.complete\(route, built\.messages, emptyList\(\)""").containsMatchIn(compare))
        assertTrue(!Regex("""dispatcher|ToolRegistry|registry\.""").containsMatchIn(compare))
        val share = sources.getValue("ui/workspace/ShareInbox.kt")
        assertTrue("shared content is only parsed, never sent", !Regex("""send\(|submit""").containsMatchIn(share))
    }

    @Test fun noExternalMultiAgentFramework() {
        val build = listOf(File("build.gradle.kts"), File("../gradle/libs.versions.toml"), File("../build.gradle.kts")).filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(build.isNotBlank())
        assertTrue("no multi-agent framework dependency", !Regex("""(?i)langchain|autogen|crewai|koog|agentscope|semantic-kernel|llamaindex|langgraph""").containsMatchIn(build))
    }
}
