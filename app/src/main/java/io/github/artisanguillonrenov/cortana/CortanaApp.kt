package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.model.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.artisanguillonrenov.cortana.core.context.ContextEngine
import io.github.artisanguillonrenov.cortana.core.checkpoint.CheckpointService
import io.github.artisanguillonrenov.cortana.core.checkpoint.PlanStore
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import io.github.artisanguillonrenov.cortana.core.dev.CodeSearch
import io.github.artisanguillonrenov.cortana.core.dev.BuildService
import io.github.artisanguillonrenov.cortana.core.dev.DependencyService
import io.github.artisanguillonrenov.cortana.executors.dev.BuildTools
import io.github.artisanguillonrenov.cortana.executors.dev.CodeTools
import io.github.artisanguillonrenov.cortana.core.dev.GitService
import io.github.artisanguillonrenov.cortana.executors.dev.GitTools
import io.github.artisanguillonrenov.cortana.executors.dev.ExecTools
import io.github.artisanguillonrenov.cortana.core.exec.LocalProcessBackend
import io.github.artisanguillonrenov.cortana.core.exec.SandboxManager
import io.github.artisanguillonrenov.cortana.core.worker.WorkerService
import io.github.artisanguillonrenov.cortana.core.dev.PatchEngine
import io.github.artisanguillonrenov.cortana.core.dev.RepositoryIntelligence
import io.github.artisanguillonrenov.cortana.core.dev.WorkspaceManager
import io.github.artisanguillonrenov.cortana.executors.dev.DevTools
import io.github.artisanguillonrenov.cortana.core.maintenance.Maintenance
import io.github.artisanguillonrenov.cortana.core.observability.Tracer
import io.github.artisanguillonrenov.cortana.core.outbox.Outbox
import io.github.artisanguillonrenov.cortana.core.outbox.OutboxRetryException
import io.github.artisanguillonrenov.cortana.util.str
import io.github.artisanguillonrenov.cortana.core.orchestrator.FastPathRegistry
import io.github.artisanguillonrenov.cortana.core.orchestrator.StepRunner
import io.github.artisanguillonrenov.cortana.core.orchestrator.TaskQueries
import io.github.artisanguillonrenov.cortana.core.orchestrator.TaskStateMachine
import io.github.artisanguillonrenov.cortana.core.planner.IntentRouter
import io.github.artisanguillonrenov.cortana.core.planner.Planner
import io.github.artisanguillonrenov.cortana.core.policy.GrantService
import io.github.artisanguillonrenov.cortana.core.recovery.RecoveryEngine
import io.github.artisanguillonrenov.cortana.core.tools.CapabilityMatcher
import io.github.artisanguillonrenov.cortana.core.tools.DiscoveryTools
import io.github.artisanguillonrenov.cortana.core.tools.ToolDiscovery
import io.github.artisanguillonrenov.cortana.core.tools.ToolDispatcher
import io.github.artisanguillonrenov.cortana.core.verifier.Verifier
import io.github.artisanguillonrenov.cortana.core.memory.ConversationRepository
import io.github.artisanguillonrenov.cortana.core.memory.CortanaDatabase
import io.github.artisanguillonrenov.cortana.core.memory.HashingEmbedder
import io.github.artisanguillonrenov.cortana.core.memory.MemoryIndexer
import io.github.artisanguillonrenov.cortana.core.memory.MemoryRepository
import io.github.artisanguillonrenov.cortana.core.model.GatewayEmbedder
import io.github.artisanguillonrenov.cortana.core.model.ModelRoute
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.model.ModelCapabilities
import io.github.artisanguillonrenov.cortana.core.model.ModelGateway
import io.github.artisanguillonrenov.cortana.core.model.ProviderPresets
import io.github.artisanguillonrenov.cortana.core.model.ProviderRepository
import io.github.artisanguillonrenov.cortana.core.orchestrator.Orchestrator
import io.github.artisanguillonrenov.cortana.core.orchestrator.TaskCompletionObserver
import io.github.artisanguillonrenov.cortana.core.skills.SkillLearner
import io.github.artisanguillonrenov.cortana.core.skills.SkillService
import io.github.artisanguillonrenov.cortana.core.skills.SkillTools
import io.github.artisanguillonrenov.cortana.executors.system.AndroidSkillEnvironment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalBroker
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.core.policy.KillSwitch
import io.github.artisanguillonrenov.cortana.core.policy.PolicyEngine
import io.github.artisanguillonrenov.cortana.core.policy.UiPatternsStore
import io.github.artisanguillonrenov.cortana.core.policy.UiRiskClassifier
import io.github.artisanguillonrenov.cortana.core.scheduler.CortanaScheduler
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.core.secrets.SecretStore
import io.github.artisanguillonrenov.cortana.core.tools.ToolRegistry
import io.github.artisanguillonrenov.cortana.executors.accessibility.UiController
import io.github.artisanguillonrenov.cortana.executors.accessibility.UiTools
import io.github.artisanguillonrenov.cortana.executors.files.FileExecutor
import io.github.artisanguillonrenov.cortana.executors.internal.ServiceTools
import io.github.artisanguillonrenov.cortana.executors.system.SystemExecutor
import io.github.artisanguillonrenov.cortana.executors.web.WebExecutor
import io.github.artisanguillonrenov.cortana.service.Notifications
import io.github.artisanguillonrenov.cortana.ui.health.HealthChecker
import io.github.artisanguillonrenov.cortana.util.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual constructor injection (decision D-DI): one graph, built once in Application.onCreate. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = CortanaDatabase.build(context)
    val settings = SettingsRepository(db.settings()).also { it.loadBlocking() }
    val secrets = SecretStore(context)
    val audit = AuditLog(db.audit())
    val notifications = Notifications(context)
    val speaker = Speaker(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(io.github.artisanguillonrenov.cortana.core.policy.EgressGuard { settings.current.egressBlockedHosts })
        .build()

    val conversations = ConversationRepository(db)
    val memory = MemoryRepository(db)
    private val localEmbedder = HashingEmbedder()
    /** Owner-selected embedding model ("providerId/modelId"), else the offline local embedder. */
    val memoryIndexer = MemoryIndexer(db, appScope) {
        settings.current.embeddingRoute?.split('/', limit = 2)?.takeIf { it.size == 2 }
            ?.let { (p, m) -> GatewayEmbedder(gateway, ModelRoute(p, m, localOnly = settings.current.privacyMode == ModelGateway.PRIVACY_LOCAL_ONLY)) }
            ?: localEmbedder
    }.also { memory.indexer = it }
    val presets = ProviderPresets(context)
    val providers = ProviderRepository(db.providers(), secrets, http, presets)
    val capabilities = ModelCapabilities(context, db.providers())
    val tracer = Tracer()
    val gateway = ModelGateway(providers, capabilities, db.usage(), settings, tracer = tracer)
    /** The owner's RunPod pod, configured once by the update (chat, images, embeddings). */
    val preconfiguredPod = io.github.artisanguillonrenov.cortana.core.model.PreconfiguredPod(providers, settings) { memoryIndexer.request() }

    val killSwitch = KillSwitch(settings, audit, appScope)
    val uiPatterns = UiPatternsStore(context, settings)
    val uiClassifier = UiRiskClassifier { uiPatterns.current() }
    val grants = GrantService(db, settings, audit)
    val policy = PolicyEngine(settings, killSwitch, grants)
    val approvals = ApprovalBroker(context)
    /** Observability (phase 28): spans persisted off-thread, metrics, local viewer, optional OTLP export. */
    val spanStore = io.github.artisanguillonrenov.cortana.core.observability.SpanStore(db, appScope).also { tracer.addSink(it) }
    val observability = io.github.artisanguillonrenov.cortana.core.observability.ObservabilityService(db, spanStore, settings, secrets, audit, http, BuildConfig.VERSION_NAME)

    val outbox = Outbox(db, audit).apply {
        register(Outbox.KIND_NOTIFY_OWNER) { p ->
            if (!notifications.canPost()) throw OutboxRetryException("notifications non autorisées")
            notifications.owner(p.str("title") ?: "Cortana", p.str("message") ?: "", p.str("sessionId"))
        }
    }
    val scheduler = CortanaScheduler(context, db.schedules(), killSwitch, audit, notifications)
    val ui = UiController(context) { killSwitch.isHalted() }
    /** Screen capture + on-device OCR + optional vision model (phase 16). Replaceable in tests. */
    var screenCapturer: io.github.artisanguillonrenov.cortana.core.vision.ScreenCapturer = io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityScreenCapturer()
    var ocrProvider: io.github.artisanguillonrenov.cortana.core.vision.OcrProvider? = io.github.artisanguillonrenov.cortana.executors.accessibility.TesseractOcrProvider(context)
    val visionModel = io.github.artisanguillonrenov.cortana.core.vision.ModelVisionProvider(gateway, {
        gateway.resolveRoute(null, io.github.artisanguillonrenov.cortana.core.model.RouteNeed(vision = true))
    }, null)
    val visual = io.github.artisanguillonrenov.cortana.core.vision.VisualAutomation(
        { screenCapturer.capture() },
        object : io.github.artisanguillonrenov.cortana.core.vision.OcrProvider {
            override val id get() = ocrProvider?.id ?: "none"
            override suspend fun recognize(img: io.github.artisanguillonrenov.cortana.core.vision.ScreenImage) =
                ocrProvider?.recognize(img) ?: io.github.artisanguillonrenov.cortana.core.vision.OcrResult(emptyList(), "?", "none")
        },
        visionModel,
        mode = { io.github.artisanguillonrenov.cortana.core.vision.VisionMode.of(settings.current.visionFallback) },
        privacyLocalOnly = { settings.current.privacyMode == io.github.artisanguillonrenov.cortana.core.model.ModelGateway.PRIVACY_LOCAL_ONLY },
    )
    val web = WebExecutor(http, settings) { secrets.get(it) }
    /** Pictures of web results in the conversation (guarded web client, https, bounded disk cache). */
    val remoteImages = io.github.artisanguillonrenov.cortana.core.media.RemoteImages(web.client, java.io.File(context.cacheDir, "web-media"))
    val files = FileExecutor(context, settings)
    val system = SystemExecutor(context, settings, uiClassifier)

    val workspaces = WorkspaceManager(context, db, audit)
    val artifacts = ArtifactService(context, db, audit)
    val git = GitService(
        workspaces, audit,
        credentials = { host -> settings.current.gitCredentials[host]?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (u, h) -> secrets.get(h)?.let { u to it } } },
        identity = { GitService.defaultIdentity(settings.current.gitAuthorName, settings.current.gitAuthorEmail) },
        protectedBranches = { settings.current.protectedBranches.toSet() },
        configDir = java.io.File(context.filesDir, "git-config"),
    )
    val repoIntelligence = RepositoryIntelligence(workspaces, git)
    val localBackend = LocalProcessBackend(workspaces, java.io.File(context.cacheDir, "exec"), artifacts = artifacts)
    /** Execution backends: the tablet plus paired workers (phase 12). */
    val executionBackends = java.util.concurrent.CopyOnWriteArrayList<io.github.artisanguillonrenov.cortana.core.exec.ExecutionBackend>(listOf(localBackend))
    val sandbox = SandboxManager({ settings.current.devBackend }) { executionBackends.toList() }
    val workers = WorkerService(context, db, http, audit, workspaces, artifacts, executionBackends)
    val builds = BuildService(workspaces, repoIntelligence, sandbox, artifacts)
    val dependencies = DependencyService(repoIntelligence)
    val codeSearch = CodeSearch(workspaces)
    val patchEngine = PatchEngine(db, workspaces, audit, java.io.File(context.filesDir, "workspace-backups"))
    val codeIntel = io.github.artisanguillonrenov.cortana.core.dev.CodeIntelligence(workspaces)
    val review = io.github.artisanguillonrenov.cortana.core.dev.ReviewService(workspaces, patchEngine, git, codeIntel, builds, artifacts, repoIntelligence) { settings.current.protectedBranches.toSet() }
    val softwareFactory = io.github.artisanguillonrenov.cortana.core.dev.SoftwareFactory(patchEngine, review, workspaces)

    // ---- Communications (phase 18): stores behind interfaces (replaceable in tests), notification hub.
    var contactsStore: io.github.artisanguillonrenov.cortana.core.comms.ContactsStore = io.github.artisanguillonrenov.cortana.executors.comms.AndroidContactsStore(context)
    var calendarStore: io.github.artisanguillonrenov.cortana.core.comms.CalendarStore = io.github.artisanguillonrenov.cortana.executors.comms.AndroidCalendarStore(context)
    var telephony: io.github.artisanguillonrenov.cortana.core.comms.Telephony = io.github.artisanguillonrenov.cortana.executors.comms.AndroidTelephony(context)
    var clipboardAccess: io.github.artisanguillonrenov.cortana.executors.comms.ClipboardAccess = io.github.artisanguillonrenov.cortana.executors.comms.AndroidClipboard(context, appScope)
    @Volatile private var notificationSessionId: String? = null
    val notificationHub = io.github.artisanguillonrenov.cortana.core.comms.NotificationHub(
        allowedApps = { settings.current.notificationApps.toSet() },
        triggers = { settings.current.notificationTriggers },
        fire = { t, n -> fireNotificationTrigger(t, n) },
        scope = appScope,
    )

    /** A matching notification starts a task: tainted from the start, its content wrapped as data. */
    private suspend fun fireNotificationTrigger(t: io.github.artisanguillonrenov.cortana.core.comms.NotificationTriggerSpec, n: io.github.artisanguillonrenov.cortana.core.comms.NotificationItem) {
        val s = settings.current
        val sid = notificationSessionId?.takeIf { conversations.session(it) != null }
            ?: conversations.createSession(title = "🔔 Déclencheurs de notifications", toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL).id.also { notificationSessionId = it }
        // Content goes to the model only if that model is local (tablet/LAN) or the owner allowed it.
        val route = gateway.resolveRoute(conversations.session(sid), io.github.artisanguillonrenov.cortana.core.model.RouteNeed())
        val shareContent = s.notificationContentToModel || route?.local == true
        val content = "Application : ${n.appLabel} (${n.packageName})\n" + if (shareContent) "Titre : ${n.title.orEmpty()}\nTexte : ${io.github.artisanguillonrenov.cortana.core.comms.NotificationHub.masked(n.text).orEmpty()}"
            else "Contenu masqué (partage du contenu des notifications avec le modèle non autorisé)."
        val req = io.github.artisanguillonrenov.cortana.contracts.TaskRequest(
            requestId = io.github.artisanguillonrenov.cortana.util.Ids.new(), sessionId = sid, source = io.github.artisanguillonrenov.cortana.contracts.TaskSource.ANDROID,
            objective = t.objective, constraints = io.github.artisanguillonrenov.cortana.contracts.TaskConstraints(toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL),
            createdAt = System.currentTimeMillis(),
            contextHints = mapOf("untrusted_source" to "notification:${n.packageName}", "untrusted_content" to content, "trigger" to t.id),
        )
        var ok = orchestrator.submitRequest(sid, req); var waited = 0
        while (!ok && waited < 60_000) { kotlinx.coroutines.delay(1_000); waited += 1_000; ok = orchestrator.submitRequest(sid, req) }
        audit.record("cortana", "notification.trigger.fire", t.id, if (ok) "ok" else "skipped", """{"app":"${n.packageName}"}""")
        if (!ok) notifications.owner("Déclencheur ignoré", "Une notification de ${n.appLabel} n'a pas pu être traitée : Cortana était occupée.")
    }

    /** Interactive browser (one isolated session per task) and research service (phase 19). */
    val browserSessions = io.github.artisanguillonrenov.cortana.executors.browser.BrowserSessions({
        io.github.artisanguillonrenov.cortana.core.browser.HttpBrowserEngine(web.client, web::allowed)
    })
    val research = io.github.artisanguillonrenov.cortana.core.browser.ResearchService({ q, n -> web.searchProvider().search(q, n) }, {
        io.github.artisanguillonrenov.cortana.core.browser.HttpBrowserEngine(web.client, web::allowed)
    })

    /** Document & Data Workbench (phase 24): the one owner of document formats; results are artifacts with provenance. */
    val pdfEngine = io.github.artisanguillonrenov.cortana.core.documents.PdfEngine(context)
    val documents = io.github.artisanguillonrenov.cortana.core.documents.DocumentService(pdfEngine, artifacts, files, context.cacheDir)

    /** Connections (phase 26): the one lifecycle authority for external accounts; secrets through a replaceable vault (tests). */
    var connectionVault: io.github.artisanguillonrenov.cortana.core.connections.SecretVault = object : io.github.artisanguillonrenov.cortana.core.connections.SecretVault {
        override fun newHandle() = secrets.newHandle()
        override fun put(handle: String, value: String) = secrets.put(handle, value)
        override fun get(handle: String?) = secrets.get(handle)
        override fun remove(handle: String?) = secrets.remove(handle)
    }
    val connections = io.github.artisanguillonrenov.cortana.core.connections.ConnectionManager(db.connections(), { connectionVault }, io.github.artisanguillonrenov.cortana.core.connections.OAuthClient(http), audit)
    private val connectorHttp = io.github.artisanguillonrenov.cortana.core.connections.ConnectorHttp { base ->
        http.newBuilder().dns(io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard.dnsFor { h -> h.equals(base.host, true) || settings.current.allowPrivateNetworkFetch }).build()
    }
    val httpConnector = io.github.artisanguillonrenov.cortana.core.connections.HttpConnector(connections, connectorHttp).also { connections.register("http", it) }
    val webhookOut = io.github.artisanguillonrenov.cortana.core.connections.WebhookOutConnector(connections, connectorHttp).also { connections.register("webhook_out", it) }
    val webhookIn = io.github.artisanguillonrenov.cortana.core.connections.WebhookInConnector(connections, object : io.github.artisanguillonrenov.cortana.core.connections.WebhookInConnector.WorkerHooks {
        override suspend fun register(workerId: String, name: String, reg: io.github.artisanguillonrenov.cortana.contracts.HookRegistration) = workers.registerHook(workerId, name, reg)
        override suspend fun delete(workerId: String, name: String) = workers.deleteHook(workerId, name)
        override suspend fun events(workerId: String, after: Long) = workers.hookEvents(workerId, after)
        override suspend fun baseUrl(workerId: String) = workers.baseUrl(workerId)
    }).also { connections.register("webhook_in", it) }
    val homeAssistant = io.github.artisanguillonrenov.cortana.core.connections.HomeAssistantConnector(connections, connectorHttp).also { connections.register("home_assistant", it) }
    val emailConnector = io.github.artisanguillonrenov.cortana.core.connections.EmailConnector(connections).also { connections.register("email", it) }
    val telegram = io.github.artisanguillonrenov.cortana.core.connections.TelegramConnector(connections, connectorHttp).also { connections.register("telegram", it) }

    /** Media services (phase 25): provider work through the gateway on the owner's routes, local work on the tablet. Engines replaceable in tests. */
    var mediaProbe: io.github.artisanguillonrenov.cortana.core.media.MediaProbe? = io.github.artisanguillonrenov.cortana.executors.media.AndroidMediaProbe()
    var speechFileSynthesizer: io.github.artisanguillonrenov.cortana.core.media.SpeechFileSynthesizer? =
        io.github.artisanguillonrenov.cortana.executors.media.AndroidSpeechFileSynthesizer(context, context.cacheDir)
    val media = io.github.artisanguillonrenov.cortana.core.media.MediaService(gateway, settings, documents, { ocrProvider }, { mediaProbe }, { speechFileSynthesizer },
        { url, max -> mediaDownload(url, max) }, context.cacheDir)

    /** Provider-returned media URLs: SSRF rule on every hop (web client), https, bounded size. */
    private suspend fun mediaDownload(url: String, max: Long): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val u = okhttp3.HttpUrl.Companion.run { url.toHttpUrlOrNull() } ?: throw IllegalArgumentException("URL invalide")
        if (!u.isHttps) throw IllegalArgumentException("https exigé")
        if (!web.allowed(u)) throw IllegalArgumentException("adresse refusée (réseau local ou interne)")
        web.client.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalArgumentException("HTTP ${r.code}")
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
            r.body?.byteStream()?.use { input -> while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw IllegalArgumentException("fichier trop volumineux") } }
            out.toByteArray()
        }
    }

    val registry = ToolRegistry().apply {
        registerAll(io.github.artisanguillonrenov.cortana.executors.internal.ObservabilityTools(observability).tools())
        registerAll(ServiceTools(memory, scheduler, outbox, settings, watchCheck = { cap ->
            byCapability(cap).let { d -> if (d == null) "capacité inconnue : $cap" else io.github.artisanguillonrenov.cortana.core.orchestrator.ScheduledRunner.watchRefusal(d) }
        }).tools())
        registerAll(web.tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.web.GitHubTools(web.client, {
            settings.current.gitCredentials["github.com"]?.split('|', limit = 2)?.getOrNull(1)?.let { secrets.get(it) }
        }).tools())
        registerAll(files.tools())
        registerAll(system.tools())
        registerAll(UiTools(ui, uiClassifier, settings, visual) { context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }.tools())
        registerAll(DevTools(workspaces, repoIntelligence, codeSearch, patchEngine, artifacts, git).tools())
        registerAll(GitTools(git, workspaces, settings).tools())
        registerAll(ExecTools(workspaces, sandbox, artifacts).tools())
        registerAll(BuildTools(workspaces, builds, dependencies).tools())
        registerAll(CodeTools(workspaces, codeIntel, patchEngine, builds, review).tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.comms.CommsTools({ contactsStore }, { calendarStore }, { telephony }, notificationHub, object : io.github.artisanguillonrenov.cortana.executors.comms.ClipboardAccess {
            override fun read() = clipboardAccess.read()
            override fun write(text: String, sensitive: Boolean, clearAfterSec: Int?) = clipboardAccess.write(text, sensitive, clearAfterSec)
        }, settings) { name, text, taskId -> runCatching { artifacts.registerText(text, "snapshot", "$name.txt", taskId, "calendar").artifactId }.getOrNull() }.tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.browser.BrowserTools(browserSessions, research, { sid ->
            io.github.artisanguillonrenov.cortana.core.browser.GatewayResearchModel(gateway) { gateway.resolveRoute(conversations.session(sid)) }
        }, artifacts).tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.documents.DocumentTools(documents, pdfEngine).tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.media.MediaTools(media, documents).tools())
        registerAll(io.github.artisanguillonrenov.cortana.executors.connections.ConnectionTools(connections, httpConnector, webhookOut, homeAssistant, emailConnector, artifacts, documents).tools())
    }
    /** MCP client (phase 20): external tools normalized into the one registry, never a second one. */
    val mcp = io.github.artisanguillonrenov.cortana.core.mcp.McpManager(settings, registry, { cfg -> mcpTransport(cfg) }, { action, target, detail ->
        appScope.launch { audit.record("cortana", action, target, "ok", """{"detail":${kotlinx.serialization.json.JsonPrimitive(detail)}}""") }
    }).also { registry.registerAll(io.github.artisanguillonrenov.cortana.executors.mcp.McpTools(it).tools()) }

    private fun mcpTransport(cfg: io.github.artisanguillonrenov.cortana.core.mcp.McpServerConfig): io.github.artisanguillonrenov.cortana.core.mcp.McpTransport = when (cfg.transport) {
        "worker" -> io.github.artisanguillonrenov.cortana.core.mcp.McpWorkerTransport { msg, t ->
            workers.mcp(cfg.workerId ?: throw io.github.artisanguillonrenov.cortana.core.mcp.McpException(null, "Worker manquant"), cfg.stdioName ?: throw io.github.artisanguillonrenov.cortana.core.mcp.McpException(null, "Serveur stdio manquant"), msg, t)
        }
        else -> {
            val u = io.github.artisanguillonrenov.cortana.core.mcp.McpEndpoints.check(cfg.url)
            // The owner-configured endpoint may be on the LAN; anything it redirects to follows the normal SSRF rule.
            val client = http.newBuilder().dns(io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard.dnsFor { h -> h.equals(u.host, true) || settings.current.allowPrivateNetworkFetch }).build()
            // OAuth-protected servers use a connection (phase 26): its access token, refreshed when needed.
            val token: suspend () -> String? = cfg.connection?.let { name -> suspend { connections.tokenFor(name) } } ?: { secrets.get(cfg.authHandle) }
            io.github.artisanguillonrenov.cortana.core.mcp.McpHttpTransport(client, u, token, { x -> (x.host == u.host && x.port == u.port) || web.allowed(x) })
        }
    }

    /** A2A delegation (phase 21): external agents as bounded remote capabilities. */
    private fun a2aHttp(c: io.github.artisanguillonrenov.cortana.core.a2a.A2aAgentConfig): okhttp3.OkHttpClient {
        val card = io.github.artisanguillonrenov.cortana.core.a2a.A2aProtocol.cardUrl(c.cardUrl)
        if (!card.isHttps && !io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard.isBlockedLiteral(card.host)) throw io.github.artisanguillonrenov.cortana.core.a2a.A2aException(null, "Un agent sur Internet doit utiliser https")
        return http.newBuilder().dns(io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard.dnsFor { h -> h.equals(card.host, true) || settings.current.allowPrivateNetworkFetch })
            .followRedirects(false).addInterceptor(io.github.artisanguillonrenov.cortana.executors.web.SsrfGuard.redirectGuard({ x: okhttp3.HttpUrl -> x.host == card.host || web.allowed(x) }))
            .readTimeout(c.timeoutSec.coerceIn(10, 1_800).toLong() + 30, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Token of an external agent (secret store; replaceable in tests, where AndroidKeyStore is absent). */
    var a2aToken: (io.github.artisanguillonrenov.cortana.core.a2a.A2aAgentConfig) -> String? = { c -> secrets.get(c.authHandle) }

    val a2a = io.github.artisanguillonrenov.cortana.core.a2a.A2aService(settings,
        { c -> io.github.artisanguillonrenov.cortana.core.a2a.A2aClient(a2aHttp(c)) { a2aToken(c) } },
        { bytes, name, _, taskId, meta ->
            val tmp = java.io.File.createTempFile("a2a-file", ".part", context.cacheDir)
            tmp.writeBytes(bytes)
            artifacts.register(tmp, "a2a", name, taskId, "agent.delegate", meta, listOfNotNull(meta["source"]), move = true).artifactId
        },
        { c, url, max ->
            // A file linked by the agent is fetched like any Web resource: SSRF rule, size cap.
            val u = url.toHttpUrlOrNull() ?: throw io.github.artisanguillonrenov.cortana.core.a2a.A2aException(null, "URL de fichier invalide")
            if (!web.allowed(u)) throw io.github.artisanguillonrenov.cortana.core.a2a.A2aException(null, "Adresse de fichier bloquée (protection SSRF)")
            web.client.newCall(okhttp3.Request.Builder().url(u).get().build()).await().use { r ->
                if (!r.isSuccessful) throw io.github.artisanguillonrenov.cortana.core.a2a.A2aException(r.code, "Fichier inaccessible (HTTP ${r.code})")
                val src = r.body!!.source(); src.request(max + 1)
                if (src.buffer.size > max) throw io.github.artisanguillonrenov.cortana.core.a2a.A2aException(null, "fichier trop volumineux")
                src.buffer.readByteArray() to r.header("Content-Type").orEmpty()
            }
        },
    ).also { registry.registerAll(io.github.artisanguillonrenov.cortana.executors.a2a.A2aTools(it, artifacts).tools()) }

    /** Connects the configured MCP servers, then retries failed ones with backoff. */
    suspend fun mcpLoop() {
        runCatching { plugins.recover() }
        kotlinx.coroutines.delay(2_000)
        runCatching { mcp.syncAll() }
        runCatching { a2a.refreshAll() }
        while (true) { kotlinx.coroutines.delay(60_000); runCatching { mcp.healthCheck() } }
    }

    val toolDiscovery = ToolDiscovery().also { registry.registerAll(DiscoveryTools(registry, it).tools()) }
    val skills = SkillService(db, registry, AndroidSkillEnvironment(context), audit, toolDiscovery).also { registry.registerAll(SkillTools(it, registry).tools()) }
    /** The one plugin registry (phase 22): declarative, signed, journaled install/removal. */
    val plugins = io.github.artisanguillonrenov.cortana.core.plugins.PluginManager(db.plugins(), java.io.File(context.filesDir, "plugins"), settings, skills, registry,
        object : io.github.artisanguillonrenov.cortana.core.plugins.PluginSecrets {
            override fun put(handle: String, value: String) = secrets.put(handle, value)
            override fun remove(handle: String?) = secrets.remove(handle)
        }, BuildConfig.VERSION_NAME.substringBefore('-'),
        onExternalChange = { runCatching { mcp.syncAll() }; runCatching { a2a.refreshAll() } },
        audit = { action, target, detail -> audit.record("owner", action, target, "ok", """{"detail":${kotlinx.serialization.json.JsonPrimitive(detail)}}""") },
    ).also { registry.registerAll(io.github.artisanguillonrenov.cortana.executors.plugins.PluginTools(it).tools()) }

    val skillLearner = SkillLearner(db, registry, skills)
    /** Improvement Service (phase 27): proposals only; the owner applies them (LAW-019). */
    val improvements = io.github.artisanguillonrenov.cortana.core.improvement.ImprovementService(db, registry, settings, skills, audit)
        .also { registry.registerAll(io.github.artisanguillonrenov.cortana.executors.internal.ImprovementTools(it).tools()) }
    val dispatcher = ToolDispatcher(registry, policy, approvals, grants, killSwitch, audit, settings, db, tracer)

    val contextEngine = ContextEngine(context, conversations, memory, settings)
    val stateMachine = TaskStateMachine(db).also { sm -> sm.onTerminal = { t -> tracer.recordTask(t) } }
    val planStore = PlanStore(db)
    val checkpoints = CheckpointService(db)
    val planner = Planner(gateway, settings)
    /** Specialist profiles (phase 23): the planner may assign them, the one orchestrator runs them. */
    val specialists = io.github.artisanguillonrenov.cortana.core.orchestrator.SpecialistRegistry().also { reg -> planner.specialists = { reg.profiles().map { it.profileId to it.description } } }
    val verifier = Verifier(gateway, gates = listOf(softwareFactory))
    val stepRunner = StepRunner(gateway, contextEngine, conversations, dispatcher, secrets, tracer, checkpoints, skills)
    /** Cognitive Council Engine (D-20260929-067): profiles, planner, agent caller and runtime; off until the owner enables it. */
    val councilProfiles = io.github.artisanguillonrenov.cortana.core.council.DefaultCouncilProfileRegistry()
    /** The council's rows live in the canonical database (schema v3): structured summaries only. */
    val councilStore = io.github.artisanguillonrenov.cortana.core.council.CouncilStore(db.council())
    val councilRecorder: io.github.artisanguillonrenov.cortana.core.council.CouncilRecorder = councilStore
    val council = io.github.artisanguillonrenov.cortana.core.council.CouncilRuntime(
        io.github.artisanguillonrenov.cortana.core.council.CouncilPlanner(councilProfiles, gateway, registry, CapabilityMatcher(toolDiscovery)),
        councilProfiles, io.github.artisanguillonrenov.cortana.core.council.CouncilAgentCaller(gateway, dispatcher, tracer), verifier, tracer, settings, audit, councilRecorder,
    )
    val councilGate = io.github.artisanguillonrenov.cortana.core.council.CouncilGate(
        io.github.artisanguillonrenov.cortana.core.council.RuleCouncilPolicySelector(), council,
        facts = { session, objective -> contextEngine.councilFacts(session, objective) },
        battery = { runCatching { context.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } }.getOrNull() },
        roleLabel = { id -> councilProfiles.get(id)?.role ?: id },
        tokensToday = { councilStore.tokensToday() },
    )
    /** Chat Workspace live state (D-20260930-068): reported by the orchestrator, observed by the screens. */
    val chatHub = io.github.artisanguillonrenov.cortana.core.chat.ChatStreamHub(appScope, conversations)
    val orchestrator: Orchestrator = Orchestrator(
        context, appScope, db, conversations, memory, gateway, registry, dispatcher, approvals, killSwitch, settings,
        notifications, secrets, speaker, tracer, stateMachine, planner, IntentRouter(), verifier, RecoveryEngine(),
        checkpoints, planStore, stepRunner,
        FastPathRegistry(FastPathRegistry.defaults() + io.github.artisanguillonrenov.cortana.core.improvement.OwnerShortcutPath { settings.current.ownerShortcuts }),
        CapabilityMatcher(toolDiscovery),
        specialists = specialists,
        council = councilGate,
        hub = chatHub,
        attachmentResolver = { refs -> chat.resolve(refs) },
        compareRunner = io.github.artisanguillonrenov.cortana.core.orchestrator.CompareRunner(gateway, contextEngine, conversations, chatHub, tracer),
        workspaceNames = { workspaces.list().map { it.name } },
        extensions = listOf(softwareFactory, object : io.github.artisanguillonrenov.cortana.core.orchestrator.TaskExtension {
            override suspend fun onTaskEnd(taskId: String) = browserSessions.end(taskId)
        }),
        completionObservers = listOf(
            TaskCompletionObserver { taskId, objective, sources ->
                skillLearner.observe(taskId, objective, sources)?.let { skill ->
                    outbox.enqueue(Outbox.KIND_NOTIFY_OWNER, buildJsonObject {
                        put("title", "Nouvelle procédure apprise")
                        put("message", "« ${skill.name} » peut être rejouée. Vérifiez-la et activez-la dans Procédures si vous le souhaitez.")
                    }, "skill-candidate:${skill.skillId}")
                    outbox.drain()
                }
            },
            TaskCompletionObserver { _, _, _ -> improvements.afterTask() },
        ),
    )
    val taskQueries = TaskQueries(db, planStore, checkpoints)
    /** Attachments are artifacts (Artifact Service); their text comes from the Document Service. */
    private val attachmentStore = object : io.github.artisanguillonrenov.cortana.core.chat.AttachmentStore {
        override suspend fun store(name: String, mime: String?, sessionId: String?, open: () -> java.io.InputStream?): Triple<String, String, Long> =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tmp = java.io.File(context.cacheDir, "attach-${io.github.artisanguillonrenov.cortana.util.Ids.new()}")
                try {
                    (open() ?: throw java.io.IOException("fichier inaccessible")).use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > io.github.artisanguillonrenov.cortana.core.chat.ChatService.MAX_ATTACHMENT_BYTES) throw java.io.IOException("fichier trop volumineux")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val a = artifacts.register(tmp, "attachment", name, metadata = mapOf("origin" to "chat") + (sessionId?.let { mapOf("sessionId" to it) } ?: emptyMap()), move = true)
                    Triple(a.artifactId, mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: a.mime, a.sizeBytes)
                } finally { tmp.delete() }
            }

        override suspend fun text(artifactId: String, maxChars: Int): String = documents.render(documents.load("artifact:$artifactId"), maxChars = maxChars)
    }
    /** Chat Workspace façade: every generation still goes through the one orchestrator. */
    val chat: io.github.artisanguillonrenov.cortana.core.chat.ChatService = io.github.artisanguillonrenov.cortana.core.chat.ChatService(appScope, db, conversations, orchestrator, settings, chatHub, attachmentStore)

    // ---- Voice VNext (phase 17): engines are chosen per turn from the settings; the loop owns no task logic.
    val mic = io.github.artisanguillonrenov.cortana.executors.voice.MicAudioSource(context)
    var sttAndroid: io.github.artisanguillonrenov.cortana.core.voice.SttProvider = io.github.artisanguillonrenov.cortana.executors.voice.AndroidSttProvider(context)
    var ttsAndroid: io.github.artisanguillonrenov.cortana.core.voice.TtsProvider =
        io.github.artisanguillonrenov.cortana.executors.voice.AndroidTtsProvider(context, { settings.current.ttsVoice }, { settings.current.voiceLanguage })
    private suspend fun audioRoute(ref: String?, what: String) = gateway.routeFor(ref)
        ?: throw io.github.artisanguillonrenov.cortana.core.model.ModelException("Aucun fournisseur autorisé pour $what (Réglages → Voix ; en mode « Local uniquement », un serveur local)")
    var sttRemote: io.github.artisanguillonrenov.cortana.core.voice.SttProvider = io.github.artisanguillonrenov.cortana.core.voice.RemoteSttProvider(appScope, mic,
        { wav, lang -> gateway.transcribe(audioRoute(settings.current.sttRoute, "la transcription"), wav, lang) })
    var ttsRemote: io.github.artisanguillonrenov.cortana.core.voice.TtsProvider = io.github.artisanguillonrenov.cortana.core.voice.RemoteTtsProvider(appScope,
        { text -> gateway.speech(audioRoute(settings.current.ttsRoute, "la synthèse vocale"), text, settings.current.ttsVoice) },
        io.github.artisanguillonrenov.cortana.executors.voice.AudioTrackPlayer())
    var bargeInDetector: io.github.artisanguillonrenov.cortana.core.voice.SpeechDetector? = io.github.artisanguillonrenov.cortana.core.voice.SpeechDetector(mic)
    @Volatile private var voiceSessionId: String? = null

    /** The conversation that receives voice turns (created on first use, visible in the chat list). */
    suspend fun voiceSession(): String {
        voiceSessionId?.let { id -> if (conversations.session(id) != null) return id }
        return conversations.createSession(title = "🎙️ Conversation vocale", toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL).id.also { voiceSessionId = it }
    }

    val voice = io.github.artisanguillonrenov.cortana.core.voice.VoiceLoop(
        appScope,
        stt = { if (settings.current.sttMode == "remote") sttRemote else sttAndroid },
        tts = { if (settings.current.ttsMode == "remote") ttsRemote else ttsAndroid },
        bargeInMic = { bargeInDetector },
        ingress = { text ->
            val sid = voiceSession()
            val req = io.github.artisanguillonrenov.cortana.contracts.TaskRequest(
                requestId = io.github.artisanguillonrenov.cortana.util.Ids.new(), sessionId = sid, source = io.github.artisanguillonrenov.cortana.contracts.TaskSource.VOICE,
                objective = text, constraints = io.github.artisanguillonrenov.cortana.contracts.TaskConstraints(toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL),
                createdAt = System.currentTimeMillis())
            // After a barge-in the interrupted answer may still be finishing: wait for it rather than drop the new request.
            var ok = orchestrator.submitRequest(sid, req)
            var waited = 0
            while (!ok && waited < 15_000) { kotlinx.coroutines.delay(200); waited += 200; ok = orchestrator.submitRequest(sid, req) }
            if (ok) req.requestId else null
        },
        output = orchestrator.voiceOutput.map { it.second },
        config = {
            val s = settings.current
            io.github.artisanguillonrenov.cortana.core.voice.VoiceConfig(s.voiceLanguage, s.wakeWordEnabled, s.wakePhrase, s.handsFreeTimeoutSec * 1000L, s.bargeIn)
        },
        isHalted = { killSwitch.isHalted() },
        audit = { action, detail -> appScope.launch { audit.record("owner", action, "voix", "ok", """{"detail":${kotlinx.serialization.json.JsonPrimitive(detail)}}""") } },
        onSessionActive = { on -> if (on) io.github.artisanguillonrenov.cortana.service.VoiceService.start(context) else io.github.artisanguillonrenov.cortana.service.VoiceService.stop(context) },
    ).also { loop -> killSwitch.addListener { loop.stop("Autonomie arrêtée (STOP)") } }
    /** Inbound channels (phase 26): webhooks collected from workers and Telegram messages become ordinary TaskRequests. */
    val inbound = io.github.artisanguillonrenov.cortana.core.connections.InboundService(connections, db.connections(), webhookIn, telegram, { sid, req -> orchestrator.submitRequest(sid, req) },
        { existing, title -> existing?.takeIf { conversations.session(it) != null } ?: conversations.createSession(title = title, toolset = io.github.artisanguillonrenov.cortana.core.tools.Toolsets.FULL).id },
        outbox, audit)

    /** Backup, restore and database doctor (phase 29). */
    val secretAccess = object : io.github.artisanguillonrenov.cortana.core.backup.SecretAccess {
        override fun get(handle: String) = secrets.get(handle)
        override fun put(handle: String, value: String) = secrets.put(handle, value)
        override fun has(handle: String) = secrets.has(handle)
        override fun handles() = secrets.handles()
        override fun remove(handle: String) = secrets.remove(handle)
    }
    /** Secret inventory (phase 32): who uses which handle, missing and orphan values, rotation. */
    val secretInventory = io.github.artisanguillonrenov.cortana.core.secrets.SecretInventory(db, secretAccess, audit)
    val compatibility = io.github.artisanguillonrenov.cortana.core.backup.CompatibilityManifest(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, CortanaDatabase.VERSION)
    val backups = io.github.artisanguillonrenov.cortana.core.backup.BackupService(db, secretAccess, artifacts.root, java.io.File(context.filesDir, "backups"), compatibility, audit).also { b ->
        b.afterRestore = {
            settings.reload()
            scheduler.rearmAll(catchUp = false)
            memoryIndexer.request()
        }
    }
    /** Capabilities & permissions (phase 32): live state, fixes, last use, grants. */
    val capabilityHealth = io.github.artisanguillonrenov.cortana.core.permissions.CapabilityHealthService(
        registry, db, io.github.artisanguillonrenov.cortana.executors.permissions.AndroidAccessProbe(context, settings), grants, killSwitch, audit)
    /** Update system (phase 30): signed manifest, verified APK, system installer; never silent. */
    val updates = io.github.artisanguillonrenov.cortana.core.update.UpdateService(
        settings, http, java.io.File(context.cacheDir, "updates"), { io.github.artisanguillonrenov.cortana.executors.update.installedApp(context) },
        io.github.artisanguillonrenov.cortana.executors.update.AndroidApkInspector(context), io.github.artisanguillonrenov.cortana.executors.update.AndroidApkInstaller(context),
        backups, compatibility, audit,
    )
    val doctor = io.github.artisanguillonrenov.cortana.core.backup.DatabaseDoctor(db, { context.getDatabasePath(CortanaDatabase.NAME) }, context.filesDir, artifacts.root, secretAccess, audit,
        object : io.github.artisanguillonrenov.cortana.core.backup.DatabaseDoctor.Hooks {
            override fun busy() = orchestrator.isBusy()
            override suspend fun recoverTasks() { councilStore.recoverInterrupted(); orchestrator.recoverOnStartup() }
            override suspend fun reindexMemories() = memoryIndexer.sync()
            override suspend fun rearmSchedules() { scheduler.rearmAll(catchUp = true); if (!orchestrator.isBusy()) scheduler.recoverRuns() }
            override suspend fun embedderFingerprint() = memoryIndexer.status.value.fingerprint.ifEmpty { null }
        }).also { registry.registerAll(io.github.artisanguillonrenov.cortana.executors.internal.DoctorTools(it).tools()) }
    val scheduledRuns = io.github.artisanguillonrenov.cortana.core.orchestrator.ScheduledRunner(db.schedules(), orchestrator, dispatcher, registry, secrets, audit, killSwitch)
    val maintenance = Maintenance(grants, orchestrator, outbox, scheduler, memory, memoryIndexer, settings, audit, workers, connections, improvements, observability, updates, councilStore)
    val health = HealthChecker(context, this)
    val tasksFlow = taskQueries.recent(100)

    init {
        // Durable scheduled runs (§39): recorded by the scheduler, executed here when the orchestrator is free.
        scheduler.onRunQueued = { appScope.launch { runCatching { scheduledRuns.drain() }.onFailure { CLog.e("scheduled runs drain failed", it) } } }
        scheduler.cancelRunning = { r -> scheduledRuns.cancel(r, "remplacée ou supprimée") }
        // Chat Workspace send queue: messages written while Cortana was busy leave in order when it is idle.
        chat.start()
        appScope.launch {
            orchestrator.active.collect { a -> if (a == null) runCatching { scheduledRuns.drain() }.onFailure { CLog.e("scheduled runs drain failed", it) } }
        }
        // Replies to messaging conversations leave through the outbox when their task ends.
        appScope.launch {
            orchestrator.voiceOutput.collect { (sid, o) ->
                if (o is io.github.artisanguillonrenov.cortana.core.voice.VoiceOutput.Done) runCatching { inbound.onTaskEnd(sid, o.finalText, o.requestId) }
            }
        }
    }
}

class CortanaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { container.orchestrator.appInForeground = true }
            override fun onStop(owner: LifecycleOwner) { container.orchestrator.appInForeground = false }
        })
        container.appScope.launch { container.maintenance.onStartup() }
        // Unit tests (Robolectric) keep their scripted providers: the real pod is never configured there.
        if (android.os.Build.FINGERPRINT != "robolectric") container.appScope.launch {
            runCatching { container.preconfiguredPod.apply() }.onFailure { io.github.artisanguillonrenov.cortana.util.CLog.e("preconfigured pod failed", it) }
        }
        container.appScope.launch { container.mcpLoop() }
        container.inbound.start(container.appScope)
    }
}
