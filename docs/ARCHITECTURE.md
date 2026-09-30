# Architecture Cortana VNext

Tout s'exécute sur la tablette (décision D-20260927-001). Un worker appairé optionnel (phase 12) exécute les calculs lourds sans logique métier.

## Modules Gradle

| Module | Rôle |
|---|---|
| `:contracts` | Kotlin/JVM pur. Contrats versionnés (`schemaVersion`), table des transitions, validation de plan. Partagé par l'app et le worker. |
| `:app` | Application Android (Compose). Runtime, exécuteurs, UI. |
| `:worker` | Worker appairé (JVM, JDK 17+, `cortana-worker.jar`) : exécute des jobs dans un bac à sable, sans logique métier ni appel de modèle (`docs/WORKER_PROTOCOL.md`). |

## Propriétaires uniques (une responsabilité = un propriétaire)

| Responsabilité | Propriétaire | Garde-fou |
|---|---|---|
| Appels aux modèles | `core/model/ModelGateway` | LAW-001 |
| État des tâches et transitions | `core/orchestrator/TaskStateMachine` (appelé par l'orchestrateur) | LAW-002 |
| Boucle d'une tâche, choix de stratégie | `core/orchestrator/Orchestrator` + `StepRunner` | un seul `Orchestrator` dans `AppContainer` |
| Raccourcis déterministes | `core/orchestrator/FastPathRegistry` (analyseurs purs dans `FastPaths`) | passent par le dispatcher |
| Plans | `core/planner/Planner` ; stockage `core/checkpoint/PlanStore` | `PlanValidator` (`:contracts`) |
| Vérification | `core/verifier/Verifier` | déterministe d'abord |
| Récupération / replanification | `core/recovery/RecoveryEngine` (décide), `Planner.replan` (produit) | budget |
| Points de reprise, carnet de tâche | `core/checkpoint/CheckpointService` | |
| Exécution d'un outil | `core/tools/ToolDispatcher` → `ToolDefinition.invokeAuthorized` | LAW-003/004 |
| Catalogue des capacités | `core/tools/ToolRegistry` | LAW-020 (ids uniques) |
| Conseil de réflexion (sous-opération d'une tâche) | `core/council/CouncilRuntime` via `CouncilGate` (sélection `RuleCouncilPolicySelector`, plan `CouncilPlanner`, agents `CouncilAgentCaller`, décision `DefaultDecisionEngine`, rétention `DefaultCouncilRetainer`, persistance `CouncilStore`) | lois du conseil (`ArchitectureRulesTest`), drapeau coupé par défaut |
| Décision de risque | `core/policy/PolicyEngine` | |
| Autorisations permanentes | `core/policy/GrantService` | |
| Approbations du propriétaire | `core/policy/ApprovalBroker` | |
| STOP | `core/policy/KillSwitch` | |
| Journal d'audit chaîné | `core/policy/AuditLog` | |
| Effets durables | `core/outbox/Outbox` | clé de déduplication unique |
| Mémoire et conversations | `core/memory/MemoryRepository`, `ConversationRepository` | LAW-005 |
| Base de données | `core/memory/CortanaDatabase` + `Migrations` | LAW-009 (l'UI n'y touche pas) |
| Planifications (temps) | `core/scheduler/CortanaScheduler` | LAW-006 (ne lance ni outil ni modèle ; enregistre l'exécution dans `schedule_runs` sous sa politique de concurrence) |
| Exécutions planifiées | `core/orchestrator/ScheduledRunner` | exécute la file durable quand l'orchestrateur est libre ; surveillance = lecture par le dispatcher, sans modèle ; tâche = TaskRequest `schedule` |
| Secrets | `core/secrets/SecretStore` (Android Keystore) | handles, jamais en clair dans les journaux |
| Traces | `core/observability/Tracer` (+ `SpanStore`, span racine par tâche, parent par contexte de coroutine) | noms d'attributs OTel GenAI ; aucune clé de contenu ; spans `gen_ai.*` produits par `ModelGateway` |
| Métriques, visualiseur, export OTLP | `core/observability/ObservabilityService` (+ `Otlp`) | export désactivé par défaut, https ou réseau local |
| Lectures pour l'UI | `core/orchestrator/TaskQueries`, `AuditLog.observeRecent`, dépôts | LAW-009 |
| Construction du contexte | `core/context/ContextEngine` | budget par passes de réduction |
| Découverte d'outils | `core/tools/ToolDiscovery` + `CapabilityMatcher` | noyau toujours offert, reste sur demande |
| Index sémantique et graphe de la mémoire | `core/memory/MemoryIndexer` | seul écrivain de `memory_vectors`/`memory_edges` (LAW-005) |
| Procédures (skills) | `core/skills/SkillService` (exécution), `SkillLearner` (proposition) | activation par le propriétaire |
| Propositions d'amélioration, cas de test | `core/improvement/ImprovementService` (analyseurs purs `Analyzers.kt`) | LAW-019 : propose seulement ; application et annulation par le propriétaire (UI) ; changements typés, bornés, réversibles |
| Maintenance au démarrage et périodique | `core/maintenance/Maintenance` | |
| Sauvegarde, restauration, portabilité | `core/backup/BackupService` (+ `CompatibilityManifest`) | toute table classée (incluse ou exclue avec raison) ; secrets seulement chiffrés |
| Diagnostic et réparation de la base | `core/backup/DatabaseDoctor` | contrôles en lecture ; réparations explicites et auditées |
| Administration du worker (CLI) | `worker/WorkerAdmin` + commandes `status`, `devices`, `jobs`, `hooks` | vue sur les magasins de l'API, mêmes contrats ; aucune API locale sur la tablette |
| Capacités et permissions | `core/permissions/CapabilityHealthService` (+ `executors/permissions/AndroidAccessProbe`) | lecture en direct, aucune mise en cache ; révocation des autorisations permanentes |
| Politique de sortie réseau | `core/policy/EgressRules` (moteur de politique) + `EgressGuard` (client HTTP commun) | hôtes bloqués pour tout Cortana ; mode choisi par le propriétaire |
| Inventaire des secrets | `core/secrets/SecretInventory` (valeurs dans `SecretStore`) | lecture des valeurs réservée aux services propriétaires (règle d'architecture) |
| Mises à jour | `core/update/UpdateService` (+ `executors/update` : PackageManager, PackageInstaller) | manifeste signé par la clé de l'APK ; préparation et installation par le propriétaire seulement |
| Projets (copie privée, confinement, verrou) | `core/dev/WorkspaceManager` | `WorkspaceFs.resolve` refuse toute sortie |
| Profil de dépôt, recherche | `core/dev/RepositoryIntelligence`, `CodeSearch` | |
| Symboles, références, impact, renommage | `core/dev/CodeIntelligence` (fournisseurs `CodeIntelligenceProvider`) | lexical obligatoire |
| Modifications de fichiers | `core/dev/PatchEngine` (ChangeSets annulables) | aucun autre écrivain dans un projet |
| Git | `core/dev/GitService` (JGit hermétique) | push protégé L3 |
| Exécution de code | `core/exec/SandboxManager` → `ExecutionBackend` (tablette, worker) | trait EXECUTES_CODE ≥ L2 |
| Workers appairés | `core/worker/WorkerService` | TLS épinglé, requêtes signées |
| Build / tests / lint / dépendances | `core/dev/BuildService`, `DependencyService` | adaptateurs détectés |
| Artefacts | `core/dev/ArtifactService` | empreinte vérifiée avant export |
| Revue des modifications | `core/dev/ReviewService` | vérificateur, pas un orchestrateur |
| Pipeline de développement, porte de fin, contrôle de reprise | `core/dev/SoftwareFactory` (`CompletionGate` du Verifier, `TaskExtension` de l'orchestrateur) | aucune boucle propre |
| Réparations (compteur distinct) | `core/recovery/RecoveryEngine.repair` | bornées, même échec → replanifier puis demander |
| Contacts, agenda, téléphonie, presse-papiers | `executors/comms/CommsTools` sur `ContactsStore`, `CalendarStore`, `Telephony`, `ClipboardAccess` | aperçu + approbation des effets visibles par un tiers |
| Notifications (lecture, réponse, déclencheurs) | `core/comms/NotificationHub` (alimenté par `CortanaNotificationListener`) | liste blanche d'applications ; déclencheurs contaminés |
| Navigateur interactif (pages, formulaires, onglets, téléchargements, envois) | `core/browser/HttpBrowserEngine`, sessions par tâche `executors/browser/BrowserSessions`, outils `BrowserTools` | SSRF à chaque saut ; politique par envoi ; session effacée en fin de tâche |
| Recherche multi-sources et provenance | `core/browser/ResearchService` (+ `SourceRanker`, `GatewayResearchModel`) | citations vérifiées dans la source ; rapport en artefact |
| Défense contre l'injection dans le contenu Web | `core/browser/InjectionGuard` | texte caché et instructions retirés et signalés |
| Règle SSRF (URL et redirections) | `executors/web/SsrfGuard` (`dnsFor`, `redirectGuard`) ; règle d'autorisation `WebExecutor.allowed` | partagée par `web.*`, le navigateur et la recherche |
| Connexions MCP (découverte, normalisation, santé, appels) | `core/mcp/McpManager` (+ `McpClient`, `McpAdapter`, transports `McpHttpTransport` / `McpWorkerTransport`) ; ressources et modèles : `executors/mcp/McpTools` | outils dans le registre unique (groupe par serveur) |
| Serveurs MCP stdio | `worker/McpBridge` (côté worker) | déclarés par le propriétaire du worker |
| Délégation à des agents externes (A2A) | `core/a2a/A2aService` (+ `A2aClient`) ; outils `executors/a2a/A2aTools` | capacité distante bornée, jamais un second orchestrateur |
| Extensions (plugins) | `core/plugins/PluginManager` (format et signature : `contracts/Plugins.kt`) ; outils `executors/plugins/PluginTools` ; écran Réglages → Plugins | seul système d'extension ; déclaratif, signé, journalisé |
| Spécialistes (rôles bornés pour une étape de plan) | `core/orchestrator/SpecialistRegistry` (profils) ; exécutés par `Orchestrator.runStep` avec le `StepRunner` commun | aucune boucle propre ; transitions réservées à l'orchestrateur |
| Formats de documents et de données (DOCX, XLSX, PPTX, PDF, CSV/JSON, HTML, Markdown, archives), conversions, comparaison, provenance des résultats | `core/documents/DocumentService` (+ `Docx`, `Xlsx`, `Pptx`, `Charts`, `Formulas`, `PdfEngine`, `Archives`, `Tabular`, `Html`) ; outils `executors/documents/DocumentTools` | sources jamais modifiées ; résultats en artefacts ; XXE et archives bornées |
| Médias (routage par capacité, analyse, génération et retouche d'images, transformations locales, synthèse en fichier, transcription, vidéo) | `core/media/MediaService` (+ `ImageCodec`, `AudioFormat`, `VideoFormat`, `Modalities` ; moteurs Android `AndroidMediaProbe`, `AndroidSpeechFileSynthesizer`) ; outils `executors/media/MediaTools` ; appels fournisseurs par `ModelGateway` | copies sans métadonnées ; sorties vérifiées ; artefacts avec provenance |
| Connexions externes (types, secrets, OAuth 2.1, santé, débit, révocation) | `core/connections/ConnectionManager` (+ `OAuthClient`, adaptateurs `HttpConnector`, `WebhookOutConnector`, `WebhookInConnector`, `HomeAssistantConnector`, `EmailConnector`, `TelegramConnector`) ; outils `executors/connections/ConnectionTools` ; écran Réglages → Connexions | seule autorité du cycle de vie ; aucun secret hors du coffre |
| Canaux entrants (webhooks, messagerie) → tâches ; réponses | `core/connections/InboundService` (vers `Orchestrator.submitRequest`, réponses par l'`Outbox`) ; hébergement des webhooks : `worker/HookStore` | aucun appel d'outil par un adaptateur |
| Boucle vocale (écoute, lecture, interruption) | `core/voice/VoiceLoop` (moteurs `SttProvider`/`TtsProvider`, micro `VoiceService`) | écoute seulement sur action explicite ; requêtes par l'orchestrateur |
| Conversation (arbre de messages, branche active, statuts de flux, brouillons, file, épingles, projets, points de compactage) | `core/memory/ConversationRepository` (tables v4) | seul écrivain des messages ; migration 3→4 testée |
| Espace de discussion (Chat Workspace) : placement des tours dans l'arbre, file, pièces jointes, export, recherche | `core/chat/ChatService` (façade, génération par `Orchestrator.submitRequest`), état vivant `core/chat/ChatStreamHub`, fil `core/chat/ChatTimeline`, rendu `core/chat/Markdown` + `TexLite` ; écran `ui/workspace/*` | lois WORKSPACE-1 à 3 (`ArchitectureRulesTest`) : pas de second backend, pas d'approbation, pas de HTML ni de raisonnement affiché |
| Comparaison multi-modèle (sous-opération d'une tâche) | `core/orchestrator/CompareRunner` | sans outils, par le `ModelGateway` |
| Capture d'écran, OCR, vision, fusion de sélecteurs | `core/vision/VisualAutomation` (capture : `AccessibilityScreenCapturer`, OCR : `TesseractOcrProvider`, modèle : `ModelVisionProvider`) | `SensitiveScreenPolicy` avant toute capture |

## Cycle d'une demande

```
message → Orchestrator.submit (ou ChatService → submitRequest avec ChatHints : édition, régénération,
          continuation, comparaison, fusion — seul le placement dans l'arbre change)
  ├─ tâche WAITING_USER dans la session (< 24 h) ? → reprise de la même tâche
  ├─ FastPathRegistry.match (≥ 0,8) → ToolDispatcher (politique) → réponse sans modèle
  └─ IntentRouter.classify
        ├─ comparaison (mode Workspace) : CompareRunner → 2 à 4 modèles en parallèle, réponses sœurs
        ├─ conseil de réflexion (si activé et utile) : CouncilGate → CouncilRuntime
        │     tour 0 parallèle → confrontation ciblée → décision → défi → synthèse → vérification
        │     réponse unique ; une action proposée continue par le chemin normal ci-dessous
        └─ Planner
        DIRECT      : pas d'outils
        INTERACTIVE : StepRunner (boucle ReAct, un pas)
        DAG         : plan JSON validé → étapes par couches → Verifier → RecoveryEngine
                      (réessai → replan → question → échec) → synthèse finale
  chaque transition : TaskStateMachine (transaction + task_events)
  chaque effet      : ToolDispatcher (schéma → reprise en main → politique → approbation/grant
                      → ledger `started` → exécution avec délai → ledger `succeeded`/`failed`
                      → audit → point de reprise)
  chaque réponse    : ChatStreamHub (runId, séquence, instantané de la ligne `streaming`,
                      puis `complete`, ou `stopped`/`interrupted` avec le texte reçu)
```

## Reprise après arrêt du processus

Voir D-20260927-008. Démarrage : `recoverOnStartup()` → INTERRUPTED → inspection du point de reprise et du ledger → `reconcile` des effets incertains → reprise automatique d'une tâche, ou attente du propriétaire, ou échec propre.

## Données

Schéma Room v4 (v3 + arbre de conversation et tables du Workspace) : `docs/DATA_MIGRATIONS.md`.
