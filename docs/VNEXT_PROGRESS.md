# VNext — suivi de progression

Format par phase : état · décisions · tests · blocages · fichiers · prochaine action.
Niveaux de test : U unit · C contract · I integration · R Robolectric · A instrumenté (compilé, non exécuté ici) · P appareil physique (jamais exécuté ici) · S sécurité · E bout en bout.

## Phase 0 — Freeze baseline — DONE
- Dépôt Git local, branche `vnext`. Baseline verte (voir `VNEXT_BASELINE.md`).

## Phase 1 — Contracts + architecture enforcement — DONE
- Décisions : D-20260927-002, D-20260927-011.
- Tests : `ContractsTest` (C, 5) ; `ArchitectureRulesTest` (U, 9) — a détecté et fait corriger une violation LAW-009 (UI → base).
- Fichiers : `contracts/` (Core.kt, Plans.kt, Workspaces.kt), `settings.gradle.kts`, `app/build.gradle.kts`.

## Phase 2 — Database v2 — DONE
- Décision : D-20260927-003. Détail : `DATA_MIGRATIONS.md`.
- Tests : `DatabaseMigrationTest` (R, 2).
- Fichiers : `core/memory/{Entities,RuntimeEntities,Migrations,CortanaDatabase,Daos}.kt`, `app/schemas/.../2.json`.

## Phase 3 — StateMachine — DONE
- Décision : D-20260927-004.
- Tests : `RuntimeTest.stateMachineRejectsIllegalTransitionsAndAuditsEveryChange` (R) ; les 13 E2E 1.2.0 de `OrchestratorEndToEndTest` inchangés et verts (E/R).
- Fichiers : `core/orchestrator/{TaskStateMachine,Orchestrator,TaskQueries}.kt`.

## Phase 4 — Planner / Verifier / Recovery — DONE
- Décisions : D-20260927-005, -006, -007.
- Tests (R/E) : `dagPlanRunsStepsVerifiesThenSynthesizes`, `falseSuccessIsCaughtRetriedThenReplanned`.
- Fichiers : `core/planner/Planner.kt`, `core/verifier/Verifier.kt`, `core/recovery/RecoveryEngine.kt`, `core/orchestrator/StepRunner.kt`, `core/model/ModelGateway.kt` (`completeStructured`).

## Phase 5 — Checkpoint / Resume — DONE
- Décisions : D-20260927-008, -009, -010.
- Tests (R/E) : crash + reprise sans répétition des étapes faites ; effet incertain → propriétaire ; effet durable réconcilié via outbox ; tâche héritée sans point de reprise ; ask_user reprend la même tâche ; autorisation permanente puis révocation ; STOP (rappels OK, actions non) ; appel non idempotent répété ignoré ; outbox (déduplication, backoff, échec définitif).
- Fichiers : `core/checkpoint/CheckpointService.kt`, `core/outbox/Outbox.kt`, `core/policy/GrantService.kt`, `core/tools/ToolDispatcher.kt`.
- Test « kill process » : simulé en Robolectric (bail d'un processus mort + point de reprise). Tuer le vrai processus sur la tablette = niveau P, non exécuté.

**Suite après phase 5 : 70 tests, 0 échec** (`:contracts:test` + `:app:testDebugUnitTest`).

## Phase 6 — Context Engine v2 — DONE
- Décisions : D-20260927-013, -014, -015.
- Tests (R/E) : `ContextAndDiscoveryTest` (11) — session de 150 échanges sous budget avec résumé persistant incrémental, observations de la tâche courante conservées, plan/carnet/provenance injectés, incognito sans mémoire, résumé par modèle + repli ; classement des outils sur requêtes françaises, sélection bornée, sous-ensemble en discussion, `tools.discover` puis appel, confinement à la boîte à outils, découverte implicite contrôlée par la politique.
- Fichiers : `core/context/ContextEngine.kt` (remplace `ContextBuilder.kt`), `core/tools/{ToolDiscovery,CapabilityMatcher,DiscoveryTools}.kt`, `core/orchestrator/StepRunner.kt`, `core/memory/{RuntimeEntities,Daos,Migrations,ConversationRepository}.kt`, `assets/prompts/system_fr.txt` (2.0.0).
- Test 1.2.0 adapté (documenté D-20260927-014) : `plainChatStreamsAndPersists`.

**Suite après phase 6 : 80 tests, 0 échec.**

## Phase 7 — Memory hybrid (+ routage des modèles) — DONE
- Décisions : D-20260927-016 à -020.
- Tests (R/U/E) : `MemoryAndRoutingTest` (14) — embedder local hors ligne, synonymes via modèle d'embeddings distant, repli FTS si panne, réindexation au changement de modèle, relations de graphe, rétention, export/effacement, incognito ; détection des fournisseurs locaux, mode confidentiel, modèle de code, disjoncteur, repli sur circuit ouvert. `DatabaseMigrationTest` étendu (relations remplies, backfill de l'index, recherche hybride sur base migrée).
- Fichiers : `core/memory/{Embeddings,MemoryIndexer,MemoryRepository,RuntimeEntities,Daos,Migrations}.kt`, `core/model/{ModelGateway,ProviderHealth,GatewayEmbedder}.kt`, `core/maintenance/Maintenance.kt`, `ui/settings/ModelMemorySettings.kt`.
- Gate « retrieval lexical + sémantique avec repli hors ligne » : OK (R).

**Suite après phase 7 : 92 tests, 0 échec.**

## Phase 8 — Skills / procedural memory — DONE
- Décision : D-20260927-021.
- Tests (R/E) : `SkillsTest` (8) — gate « procédure apprise, rejouée et invalidée proprement après divergence » : OK.
- Fichiers : `core/skills/{SkillService,SkillLearner,SkillTools}.kt`, `executors/system/AndroidSkillEnvironment.kt`, `core/orchestrator/{StepRunner,Orchestrator}.kt` (rejeu, observateurs de fin de tâche), `core/context/ContextEngine.kt` (procédures suggérées), `ui/skills/SkillsScreen.kt`, `ui/AppNav.kt`.
- Non exécuté ici : rejeu sur une vraie application Android (pilotage d'écran) — niveau P.
- Contrôles : lint 0 erreur ; `assembleDebug` OK.

**Suite après phase 8 : 100 tests, 0 échec.**

## Phase 9 — Developer workspace foundation — DONE
- Décisions : D-20260927-022, -023, -024.
- Tests (R/E) : `DevWorkspaceTest` (10) — gate « importer projet, chercher, patch preview/apply/rollback » : OK.
- Fichiers : `core/dev/{WorkspaceManager,RepositoryIntelligence,Diff,PatchEngine,ArtifactService}.kt`, `executors/dev/DevTools.kt`, `ui/dev/DevScreen.kt`, `contracts/Workspaces.kt` (RepositoryProfile, CodeHit, ChangeSet).

**Suite après phase 9 : 110 tests, 0 échec.**

## Phase 10 — Git service — DONE
- Décision : D-20260927-025.
- Tests (R/E) : `GitTest` (7) — gate « workflow branche isolée sans toucher branche principale » : OK.
- Fichiers : `core/dev/GitService.kt`, `executors/dev/GitTools.kt`, `ui/dev/DevScreen.kt` (panneau Git), `ui/settings/ModelMemorySettings.kt` (section Git).
- Risque connu, non vérifiable ici : chargement de JGit sur ART (Android réel) — mitigé (pas de JMX, config hermétique), à confirmer au niveau P.
- Contrôles : lint 0 erreur (erreur BOM littéral corrigée) ; `assembleDebug` OK.

**Suite après phase 10 : 117 tests, 0 échec.**

## Phase 11 — Sandbox + process execution — DONE
- Décision : D-20260927-026.
- Tests (R/E) : `ExecutionTest` (8) — gate « commande sandboxée + timeout + kill + logs » : OK (sur l'hôte Linux ; le shell Android mksh n'est pas exécuté ici — niveau P).
- Correctif annexe : course entre indexation mémoire et effacement/rétention (écriture sous verrou, vérification avant écriture du vecteur) — trouvée par un échec intermittent, stable sur 3 exécutions répétées.
- Fichiers : `core/exec/Execution.kt`, `executors/dev/ExecTools.kt`, `core/memory/{MemoryIndexer,MemoryRepository}.kt`.

**Suite après phase 11 : 125 tests, 0 échec.**

## Phase 12 — Paired worker — DONE
- Décision : D-20260927-027. Protocole : `WORKER_PROTOCOL.md`.
- Tests : `WorkerTest` (U/I, 8) et `WorkerIntegrationTest` (I/E, 6) — gate « tablette déclenche une tâche sur PC et récupère résultat vérifié » : OK en HTTPS réel sur boucle locale, même machine. Deux machines physiques, worker Windows/macOS et clé matérielle Android : niveau P, non exécuté.
- Livrable : `worker/build/libs/cortana-worker.jar` (jar autonome ; démarrage vérifié en ligne de commande, 401 sur requête non signée).
- Fichiers : `worker/` (Security, Jobs, Server, Main), `core/worker/{DeviceKey,Workers}.kt`, `ui/devices/DevicesScreen.kt`, `contracts/Workspaces.kt` (protocole).

**Suite après phase 12 : 139 tests (dont 8 worker), 0 échec.**

## Phase 13 — Build/Test/Diagnostics adapters — DONE
- Décision : D-20260927-028.
- Tests (U/I/E) : `BuildTestDiagnosticsTest` (5) — gate « réparer projet exemple, tests, artefact produit » : OK (projet Node sans dépendance, worker isolé sans réseau). Build Gradle/Android réel d'un projet exemple : non exécuté ici (durée et caches Gradle masqués par le bac à sable) — commandes et motifs d'artefacts testés.
- Fichiers : `core/dev/{Diagnostics,BuildService}.kt`, `executors/dev/BuildTools.kt`, `core/exec/Execution.kt` (artefacts locaux), `core/worker/Workers.kt`, `worker/Jobs.kt`.

**Suite après phase 13 : 144 tests, 0 échec.**

## Phase 14 — Repository Intelligence advanced — DONE
- Décisions : D-20260927-029 (intelligence de code, renommage vérifié), D-20260927-030 (verrou de projet), D-20260927-031 (liens de mémoire).
- Tests (U/I/E) : `CodeIntelligenceTest` (6) — gate « renommage avec diagnostics avant/après » : OK (outil appelé par le modèle via l'orchestrateur, tests avant/après sur le worker isolé, régression → annulation automatique). `MemoryAndRoutingTest` +1 (reproduction déterministe d'un défaut d'ordre d'indexation trouvé par un échec intermittent).
- Limite assumée : fournisseur lexical (pas de résolution de types) ; LSP/Tree-sitter restent branchables derrière `CodeIntelligenceProvider`.
- Fichiers : `core/dev/CodeIntelligence.kt`, `executors/dev/CodeTools.kt`, `core/dev/WorkspaceManager.kt` (verrou), `core/memory/MemoryIndexer.kt` (liens).

**Suite après phase 14 : 151 tests, 0 échec ; lint sans erreur.**

## Phase 15 — Software Factory complete — DONE
- Décisions : D-20260927-032 (pipeline, revue, porte de fin, réparation bornée), D-20260927-033 (reprise après crash), D-20260927-034 (Git par le shell), D-20260927-035 (classification, espace Développement).
- Gate « scénario de développement complet » : OK — `SoftwareFactoryTest` (9) couvre E2E-CODE-001 à 006 via l'orchestrateur réel, la politique, les outils et le worker isolé réel (sans réseau) : plan DAG, branche dédiée, état initial des tests, patch, test ciblé, suite, build, revue, artefact vérifié, rapport, aucun push ; erreur de build diagnostiquée → symbole → correctif ; régression refusée à la fin puis réparée ; reprise après crash sans édition en double ; instructions malveillantes du dépôt ignorées et scripts isolés ; push forcé refusé, reset --hard L3.
- Autres tests : `ReviewServiceTest` (7), `ToolCapabilitiesDocTest` (documentation générée depuis le registre). `DevWorkspaceTest.modelDrivenFixUpdatesTheNotebook` adapté au nouveau contrat (tests requis avant la fin).
- Défauts réels trouvés : classification « développement » aveugle au français courant ; `exec.run` permettait de contourner la politique Git ; diagnostics Node (erreurs au chargement) non normalisés.
- Documentation : `SECURITY.md`, `TEST_MATRIX.md`, `TOOL_CAPABILITIES.md` (générée) créés ; `DATA_MIGRATIONS.md`, `ARCHITECTURE.md` mis à jour.
- Non exécuté ici : interface Développement sur appareil (A/P), build Gradle/Android réel d'un projet exemple, JGit sur ART.
- Fichiers : `core/dev/{ReviewService,SoftwareFactory}.kt`, `core/orchestrator/TaskExtension.kt`, `core/verifier/Verifier.kt`, `core/recovery/RecoveryEngine.kt`, `core/orchestrator/Orchestrator.kt`, `core/dev/{BuildService,PatchEngine,WorkspaceManager,GitService,Diagnostics}.kt`, `core/exec/Execution.kt`, `executors/dev/{CodeTools,ExecTools}.kt`, `core/planner/Planner.kt`, `ui/dev/{DevScreen,DevActivity}.kt`.

**Suite après phase 15 : 168 tests, 0 échec ; lint sans erreur.**

## Phase 16 — Android vision fallback — DONE
- Décisions : D-20260927-036 (OCR Tesseract, ML Kit écarté pour sa télémétrie), D-20260927-037 (capture, politique d'écran sensible, fusion, vérification), D-20260927-038 (état d'erreur de l'indexation).
- Gate « automatisation sur fixture à l'arbre d'accessibilité volontairement insuffisant » : OK au niveau R — orchestrateur, politique et `UiController` réels, service d'accessibilité Robolectric sans aucun nœud utile ; `android_ui_look` puis `android_ui_click` avec `target` : cible trouvée par l'OCR, geste sur le bouton, vérification « l'écran a changé, « Envoyé » visible ». Variante modèle de vision : capture masquée (pixels vérifiés), coordonnées ramenées à l'écran.
- Non exécuté ici (A/P) : capture réelle `takeScreenshot` (le shadow renvoie un tampon vide), reconnaissance Tesseract réelle, geste réel — `VisionFixtureTest` et l'activité `CanvasFixtureActivity` (debug) sont écrits et compilés pour la tablette.
- Défaut réel trouvé : l'état d'erreur de l'indexation mémoire disparaissait pendant une nouvelle tentative (D-038).
- Fichiers : `core/vision/{Vision,ScreenVision,VisualAutomation}.kt`, `executors/accessibility/{ScreenCapture,UiTools}.kt`, `core/model/{ModelTypes,OpenAiCompatibleProvider,ModelGateway,ToolCallEmulation}.kt`, `res/xml/accessibility_service_config.xml`, `assets/tessdata/`, `ui/settings/ModelMemorySettings.kt`, `src/debug/.../CanvasFixtureActivity.kt`, `androidTest/.../VisionFixtureTest.kt`.

**Suite après phase 16 : 182 tests, 0 échec ; lint sans erreur ; APK debug assemblés (OCR embarqué).**

## Phase 17 — Voice VNext — DONE
- Décision : D-20260927-039.
- Gate « boucle mains libres contrôlée et visible » : OK au niveau R — `VoiceTest.handsFreeLoopIsExplicitVisibleInterruptibleAndStoppable` (orchestrateur et modèle scriptés réels, reconnaissance et synthèse simulées, VAD réel pour l'interruption).
- Défauts trouvés en écrivant le gate : une requête vocale juste après une interruption était refusée (« occupée ») et la réponse interrompue était relue ; corrigés (attente bornée, sortie liée à l'identifiant de requête, soumission hors verrou pour que STOP reste immédiat).
- Non exécuté ici (A/P) : micro et haut-parleur réels, annulation d'écho de l'appareil, reconnaissance sur l'appareil, service de premier plan micro en arrière-plan.
- Fichiers : `core/voice/{Voice,VoiceLoop,RemoteVoice}.kt`, `executors/voice/AndroidVoice.kt`, `service/VoiceService.kt`, `core/model/{ModelTypes,OpenAiCompatibleProvider,ModelGateway}.kt`, `core/orchestrator/{Orchestrator,StepRunner}.kt`, `ui/voice/VoiceUi.kt`, `ui/AppNav.kt`, `ui/chat/ChatScreen.kt`, `ui/settings/{ModelMemorySettings,SettingsScreen}.kt`, `AndroidManifest.xml`.

**Suite après phase 17 : 191 tests, 0 échec ; lint sans erreur.**

## Phase 18 — Contacts/phone/SMS/calendar/notifications — DONE
- Décisions : D-20260927-040, D-20260927-041.
- Gate « lectures + approbations des effets » : OK au niveau R — `CommsTest.readsAreFreeAndEveryThirdPartyEffectIsPreviewedAndApproved` par l'orchestrateur et la politique réels (magasins Android remplacés par des doublures en mémoire).
- Non exécuté ici (A/P) : fournisseurs de contenu Android réels (contacts, agenda), envoi SMS et appel réels (la Galaxy Tab A11 Wi-Fi n'a pas de téléphonie : capacités refusées proprement), service d'écoute des notifications réel, presse-papiers en arrière-plan.
- Fichiers : `core/comms/{Comms,NotificationHub}.kt`, `executors/comms/{AndroidComms,CommsTools}.kt`, `service/CortanaNotificationListener.kt`, `core/orchestrator/Orchestrator.kt` (contenu externe en enveloppe), `core/model/ModelGateway.kt` (`ModelRoute.local`), `core/tools/ToolDefinition.kt` (`ToolContext.modelLocal`), `ui/health/CommsCard.kt`, `ui/settings/…`, `AndroidManifest.xml`.

**Suite après phase 18 : 199 tests, 0 échec ; lint sans erreur.**


## Phase 19 — Browser interactive + Research — DONE
- Décisions : D-20260927-042, D-20260927-043, D-20260927-044, D-20260927-045.
- Gate « recherche multi-source + formulaire non sensible automatisé » : OK au niveau R/E —
  `BrowserTest.researchRunsEndToEndThroughTheOrchestratorAndStoresACitedReport` (orchestrateur, gateway et politique réels, SearXNG et sites simulés par MockWebServer, rapport cité en artefact) et
  `BrowserTest.nonSensitiveGetFormIsFilledAndSubmittedWithoutAskingTheOwner` (navigation, saisie, sélection et envoi GET sans approbation, requête vérifiée côté site).
- Défaut de sécurité trouvé et corrigé : `web.fetch` suivait les redirections vers des adresses IP privées littérales (OkHttp ne consulte pas le DNS pour une IP) — D-20260927-044.
- Pas réalisé dans cette phase, documenté (D-20260927-042) : navigateur headless côté worker ; pages JavaScript et capture d'écran passent par Chrome + accessibilité/vision (phases 6 et 16).
- Non exécuté ici (A/P) : sites réels sur l'appareil, fournisseurs de recherche réels (DuckDuckGo/Brave/SearXNG réels), pages dynamiques via Chrome.
- Fichiers : `core/browser/{Browser,InjectionGuard,Research}.kt`, `executors/browser/BrowserTools.kt`, `executors/web/WebTools.kt` (`SsrfGuard.redirectGuard`), `core/tools/ToolDefinition.kt` (`destinationResolver`), `core/policy/PolicyEngine.kt`, `core/orchestrator/{TaskExtension,Orchestrator}.kt` (`onTaskEnd`), `CortanaApp.kt`, `gradle/libs.versions.toml` + `app/build.gradle.kts` (jsoup 1.23.2).

**Suite après phase 19 : 209 tests, 0 échec ; lint sans erreur.**

## Phase 20 — MCP — DONE
- Décisions : D-20260927-046, D-20260927-047, D-20260927-048, D-20260927-049.
- Spécification suivie : MCP 2026-07-28 (vérifiée en ligne pendant la phase : sans état, `_meta` par requête, `server/discover`, en-têtes `Mcp-Method`/`Mcp-Name`/`Mcp-Param-*`), avec repli sur les versions à `initialize` (2025-03-26 à 2025-11-25).
- Gate « serveur MCP de test expose un outil, Cortana l'utilise via le registre » : OK au niveau R/E — `McpTest.fixtureServerToolIsUsedThroughTheRegistryAndPolicy` (orchestrateur, politique et registre réels, serveur MCP simulé par MockWebServer, réponse en flux SSE).
- stdio via worker : OK au niveau I/E — vrai worker en processus, vrai sous-processus serveur MCP, annulation reçue par le serveur, environnement minimal vérifié.
- Réalisé mais avec une limite documentée : authentification par jeton (Bearer) ; OAuth 2.1 des serveurs MCP → phase 26 (authentification des connecteurs), D-20260927-049.
- Non exécuté ici (A/P) : serveurs MCP publics réels, worker sur une seconde machine.
- Fichiers : `core/mcp/{Mcp,McpTransports,McpManager}.kt`, `executors/mcp/McpTools.kt`, `ui/settings/McpSettings.kt`, `core/tools/{ToolRegistry,ToolDiscovery}.kt`, `core/worker/Workers.kt`, `core/memory/SettingsRepository.kt` (`mcpServers`), `CortanaApp.kt`, `worker/{McpBridge,Server}.kt`.

**Suite après phase 20 : 216 tests, 0 échec ; lint sans erreur.**

## Phase 21 — A2A interoperability — DONE
- Décision : D-20260927-050. Spécification A2A 1.0.0 vérifiée en ligne pendant la phase.
- Gate « sous-tâche distante simulée sans fuite de mémoire interne » : OK au niveau R/E/S — `A2aTest.delegatedSubtaskCarriesOnlyWhatTheOwnerApprovedAndNothingFromMemory` (orchestrateur, politique et approbation réels ; agent simulé par MockWebServer ; la requête reçue ne contient ni souvenir, ni demande du propriétaire, ni invite).
- Limites documentées : liaison JSON-RPC 1.x seulement (pas de gRPC ni HTTP+JSON, pas de 0.3) ; signatures de carte non vérifiées ; pas de notifications push (sondage) ; OAuth des agents avec les connecteurs (phase 26, comme D-20260927-049).
- Non exécuté ici (P) : agents A2A réels.
- Fichiers : `core/a2a/A2a.kt`, `executors/a2a/A2aTools.kt`, `ui/settings/A2aSettings.kt`, `core/memory/SettingsRepository.kt` (`a2aAgents`), `CortanaApp.kt`.

**Suite après phase 21 : 221 tests, 0 échec ; lint sans erreur.**

## Phase 22 — Plugins — DONE
- Décision : D-20260927-051.
- Gate « plugin test ajouté/retiré sans corruption » : OK au niveau R/I — `PluginTest.pluginIsAddedAndRemovedWithoutTouchingAnythingElse`, et reprises après coupure `PluginTest.interruptedOperationsAreFinishedOrUndoneAtStartup`.
- Base : tables `plugins`, `plugin_versions` ajoutées au schéma v2 et à la migration 1→2 (`DatabaseMigrationTest` vert).
- Outil éditeur exécuté à la main : `cortana-worker.jar plugin-keygen` et `plugin-pack`.
- Limites documentées : plugins déclaratifs (pas de fournisseurs de modèles, déclencheurs ni extensions d'interface dans le format 1) ; installation depuis le sélecteur de fichiers Android non exécutée ici (A).
- Fichiers : `contracts/Plugins.kt`, `core/plugins/PluginManager.kt`, `executors/plugins/PluginTools.kt`, `ui/settings/PluginSettings.kt`, `core/memory/{RuntimeEntities,Daos,CortanaDatabase,Migrations}.kt`, `core/skills/SkillService.kt`, `CortanaApp.kt`, `worker/Main.kt`.

**Suite après phase 22 : 227 tests, 0 échec ; lint sans erreur.**

## Phase 23 — Multi-agent specialists — DONE
- Décision : D-20260927-052.
- Gate « tâche de code avec analyste + implémenteur + relecteur sans double orchestrateur » : OK au niveau R/E/I — `SpecialistsTest.codingTaskUsesAnalystImplementerAndReviewerUnderTheOneOrchestrator` (orchestrateur, politique, outils et worker isolé réels ; modèle scripté).
- Parallélisme (lecture seule) et annulation propagée : `SpecialistsTest` (2 tests).
- Refonte contrôlée : la boucle de plan est découpée en `runStep` / `handle` ; comportement inchangé pour les étapes sans spécialiste (suite complète verte).
- Fichiers : `core/orchestrator/{Specialists,Orchestrator,StepRunner,TaskStateMachine}.kt`, `core/context/ContextEngine.kt` (`isolatedSince`), `core/planner/Planner.kt`, `contracts/Plans.kt` (`PlanStep.specialist`), `CortanaApp.kt`.

**Suite après phase 23 : 230 tests, 0 échec ; lint sans erreur.**

## Phase 24 — Document & Data Workbench — DONE
- Décision : D-20260927-053.
- Gate « créer / modifier / exporter des artefacts représentatifs de bout en bout » : OK au niveau R/E — `DocumentTest.gateCreatesEditsAndExportsRepresentativeArtifactsWithProvenance` (orchestrateur, politique, approbation, dossier de travail et artefacts réels ; modèle scripté) : lecture d'un CSV (tâche contaminée), classeur avec formules et graphique, contrat DOCX rempli depuis un modèle, conversion en PDF copiée dans le dossier après approbation (L2), présentation avec graphique ; provenance vérifiée (sources, empreintes, opération, tâche).
- Interopérabilité : `DocumentTest.libreOfficeOpensEveryGeneratedFormat` — DOCX, XLSX (formules recalculées) et PPTX générés ouverts et convertis par LibreOffice 24.2 ; un DOCX réenregistré par LibreOffice est relu. Les modules Writer/Calc/Impress ont été installés dans le conteneur de build pour ce test (le test est ignoré, jamais compté réussi, quand LibreOffice est absent). Contrôle croisé ponctuel avec python-docx, openpyxl, python-pptx et pypdf, et contrôle visuel des rendus : conformes.
- Autres tests : `DocumentTest` (16 au total) — structure DOCX/HTML/Markdown, modèles, XXE, formules, dates, classeurs, CSV/JSON, présentations, PDF (création, édition, rendu natif, chiffrés), archives (zip slip, bombe, liens, noms longs), outils et politique, injection dans un document.
- Fichiers : `core/documents/{DocModel,Ooxml,Charts,Pdf,DocumentService}.kt`, `executors/documents/DocumentTools.kt`, `executors/files/FileTools.kt` (octets, `createNamed`), `core/dev/ArtifactService.kt` (types MIME), `CortanaApp.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`.

**Suite après phase 24 : 246 tests, 0 échec ; lint sans erreur.**

## Phase 25 — Media — DONE
- Décision : D-20260927-054.
- Gate « routage des capacités par fournisseur + manipulation locale sûre des artefacts » : OK au niveau R/E/S — `MediaTest.capabilityRoutingIsReportedAndHonoursThePrivacyMode`, `MediaTest.gateImagesAreGeneratedThroughTheGatewayVerifiedStoredAndAnalysed` (orchestrateur, passerelle, politique et artefacts réels ; fournisseur compatible OpenAI simulé), `MediaTest.imagesLeaveTheTabletOnlyAsCopiesWithoutMetadata`, `MediaTest.providerOutputIsVerifiedBeforeBecomingAnArtifact`, `MediaTest.disguisedFilesAndDecompressionBombsAreRefusedBeforeDecoding`.
- Autres tests : transformations locales, synthèse en fichier et transcription, génération vidéo (suivi, téléchargement, vérification), inspection vidéo (lecteur simulé) — `MediaTest` (9) ; LAW-001 étendue (`ArchitectureRulesTest`).
- Non exécuté (A/P) : moteur Android `synthesizeToFile` et `MediaMetadataRetriever` réels (remplacés par des doubles sous Robolectric) ; fournisseurs réels (OpenAI, serveurs locaux) — seul le protocole est vérifié contre un serveur simulé.
- Fichiers : `core/media/{MediaFormats,MediaService}.kt`, `executors/media/{MediaTools,AndroidMedia}.kt`, `core/model/{ModelTypes,OpenAiCompatibleProvider,ModelGateway}.kt`, `core/memory/SettingsRepository.kt`, `core/documents/DocumentService.kt` (type d'artefact), `core/dev/ArtifactService.kt`, `ui/settings/ModelMemorySettings.kt`, `CortanaApp.kt`.

**Suite après phase 25 : 255 tests, 0 échec ; lint sans erreur.**

## Phase 26 — Integrations/email/webhooks/home — DONE
- Décision : D-20260927-055 (solde D-049 : OAuth 2.1 pour MCP).
- Gate « connecteur de test + révocation » : OK au niveau R/E/S — `ConnectionTest.gateOAuthConnectorFixtureWithRefreshAndRevocation` (gestionnaire, coffre, outils et politique réels ; serveur OAuth + API simulés).
- Autres tests : `ConnectionTest` (8 au total) dont webhooks entrants à travers un worker réel et e-mail contre un vrai serveur IMAP/SMTP (GreenMail) ; migration v1→v2 revalidée.
- Non exécuté (A/P) : retour OAuth depuis un vrai navigateur sur la tablette, fournisseurs réels (Google, Microsoft, Home Assistant, Telegram, messageries), expéditeurs de webhooks réels.
- Non implémenté (SHOULD) : adaptateurs Discord, Slack, WhatsApp, Signal (interface commune prête).
- Fichiers : `core/connections/{Connections,OAuth,Adapters,Mail,EmailConnector,Inbound}.kt`, `executors/connections/ConnectionTools.kt`, `ui/settings/ConnectionSettings.kt`, `ui/connections/OAuthRedirectActivity.kt`, `contracts/Hooks.kt`, `worker/Hooks.kt`, `worker/Server.kt`, `core/worker/Workers.kt`, `core/memory/{RuntimeEntities,Daos,CortanaDatabase,Migrations}.kt`, `core/mcp/Mcp.kt`, `ui/settings/McpSettings.kt`, `core/browser/InjectionGuard.kt` (`scrubAny`), `core/maintenance/Maintenance.kt`, `CortanaApp.kt`, `AndroidManifest.xml`.

**Suite après phase 26 : 263 tests, 0 échec ; lint sans erreur.**

## Phase 27 (1/2) — Automatisation durable (checklist « Automation ») — DONE
- Décision : D-20260928-056.
- MUST soldés : condition watch, concurrency policy, durable scheduled tasks.
- Tests : `AutomationTest` (9) — politiques skip/queue/allow/replace, file durable pendant une tâche occupée, reprise au redémarrage, rattrapage, surveillance sans modèle puis tâche contaminée, `changed` avec référence, validation des outils, STOP et suppression ; `DatabaseMigrationTest` (colonne `concurrencyPolicy`, table `schedule_runs`) ; `ArchitectureRulesTest` (LAW-006 inchangée).
- Non exécuté (A/P) : alarme exacte et arrêt réel du processus sur la tablette (Robolectric remplace AlarmManager et le cycle de vie du processus).
- Fichiers : `core/scheduler/{CortanaScheduler,CronExpression}.kt`, `core/orchestrator/{ScheduledRuns,Orchestrator}.kt`, `core/memory/{Entities,RuntimeEntities,Daos,CortanaDatabase,Migrations}.kt`, `core/maintenance/Maintenance.kt`, `executors/internal/ServiceTools.kt`, `ui/schedules/SchedulesScreen.kt`, `CortanaApp.kt`.

**Suite après l'automatisation : 272 tests, 0 échec ; lint sans erreur.**

## Phase 27 (2/2) — Improvement Service — DONE
- Décision : D-20260928-057 (LAW-019).
- Gate « propose une amélioration sans s'auto-modifier silencieusement » : OK au niveau R/S — `ImprovementTest.gateAnalysisProposesButNeverChangesAnything` (analyse sur l'historique réel du conteneur : propositions créées et versionnées, réglages, tâches et modèle intacts, aucune capacité d'application) ; rollback testé : `ImprovementTest.ownerAppliesAndRollsBackBoundedSettingChanges`, `approvedShortcutAnswersWithoutTheModelAndRollsBack`, `failThenSuccessBecomesARegressionCaseThatDetectsRegressions`, `skillProposalsActivateAndRollBack`.
- Autres tests : `ImprovementTest` (8 au total), `ArchitectureRulesTest.law019_improvementOnlyProposesAndNeverExecutes`, `DatabaseMigrationTest` (tables `improvement_proposals`, `eval_cases`).
- Fichiers : `core/improvement/{Analyzers,ImprovementService}.kt`, `executors/internal/ImprovementTools.kt`, `ui/settings/ImprovementSettings.kt`, `core/memory/{RuntimeEntities,Daos,CortanaDatabase,Migrations,SettingsRepository}.kt`, `core/maintenance/Maintenance.kt`, `CortanaApp.kt`.

**Suite après la phase 27 : 281 tests, 0 échec ; lint sans erreur.**

## Phase 28 — Observability/OTel export — DONE
- Décision : D-20260928-058.
- Gate « une tâche affiche spans modèle/outils/verifier sans secrets » : OK au niveau R/E/S — `ObservabilityTest.gateTaskShowsModelToolAndVerifierSpansWithoutSecrets` (tâche DAG réelle, modèle scripté, secret enregistré présent dans les arguments d'outil : arbre complet sous le span « task », `gen_ai.chat` dans chaque tour, outils dans leur étape, vérifications, synthèse ; aucun secret dans les lignes, le visualiseur ni l'outil ; métriques cohérentes).
- MUST soldés : structured traces, metrics, model/tool spans, local viewer ; SHOULD OpenTelemetry export soldé (OTLP/HTTP JSON, désactivé par défaut).
- Autres tests : `ObservabilityTest` (5), `DatabaseMigrationTest` (table `spans`).
- Non exécuté (A/P) : collecteur OpenTelemetry réel (le format est vérifié contre un serveur simulé), visualiseur sur la tablette.
- Fichiers : `core/observability/{Tracer,Observability}.kt`, `core/model/ModelGateway.kt`, `core/orchestrator/{TaskStateMachine,Orchestrator}.kt`, `executors/internal/ObservabilityTools.kt`, `ui/tasks/{TasksScreen,TraceView}.kt`, `ui/settings/ObservabilitySettings.kt`, `core/memory/{RuntimeEntities,Daos,CortanaDatabase,Migrations,SettingsRepository}.kt`, `core/maintenance/Maintenance.kt`, `CortanaApp.kt`.

**Suite après la phase 28 : 286 tests, 0 échec ; lint sans erreur.**

## Phase 29 — Backup/restore/doctor — DONE
- Décision : D-20260928-059.
- Gate « backup sur instance A restauré sur instance B compatible » : OK au niveau R/I/S — `BackupTest.gateBackupOfInstanceARestoresOnInstanceB` (instance A : conteneur réel ; instance B : seconde base Room, coffre et dossiers indépendants ; sauvegarde chiffrée avec secrets et artefact ; tables identiques ligne à ligne, index reconstruits, réglages, secrets et artefact restaurés, diagnostic de B sain).
- MUST soldés : backup, restore, DB doctor, repair, export/import, compatibility manifest.
- Autres tests : `BackupTest` (6 au total), `ArchitectureRulesTest.restoreAndRepairAreOwnerOnly`.
- Non exécuté (A/P) : restauration entre deux tablettes réelles, copie dans Téléchargements et sélecteur de documents sur l'appareil.
- Fichiers : `core/backup/{Backup,Doctor}.kt`, `executors/internal/DoctorTools.kt`, `ui/settings/BackupSettings.kt`, `ui/health/{DoctorCard,HealthScreen}.kt`, `core/memory/SettingsRepository.kt`, `util/Util.kt`, `CortanaApp.kt`.

**Suite après la phase 29 : 293 tests, 0 échec ; lint sans erreur.**

## Phase 30 — Update system — DONE
- Décision : D-20260928-060 ; procédure de publication et de retour arrière : `docs/RELEASE.md`.
- Gate « upgrade test conserve DB et signature » : OK au niveau R/S — `UpdateTest.gateUpgradeKeepsDataAndSignature` (manifeste signé par la clé de l'application installée, sauvegarde vérifiable avant tout, APK vérifié octet pour octet, rien d'installé sans le propriétaire, données inchangées ; APK d'un autre certificat refusé et supprimé).
- MUST soldés : versionCode monotone, signed update manifest, APK hash verification, install handoff, rollback strategy documentation.
- Autres tests : `UpdateTest` (4 au total), `ArchitectureRulesTest.restoreAndRepairAreOwnerOnly` (préparation et installation par l'UI seulement) ; tâche Gradle `verifyReleaseVersion`.
- Non exécuté (A/P) : installation réelle par `PackageInstaller` et mise à jour 1.2.0 → VNext sur la tablette (à faire lors de la RC physique) ; `tools/make_update_manifest.py` sera exécuté sur l'APK de la version candidate (phase 33).
- Fichiers : `core/update/UpdateService.kt`, `executors/update/AndroidUpdate.kt`, `ui/settings/UpdateSettings.kt`, `core/maintenance/Maintenance.kt`, `core/memory/SettingsRepository.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `release/released.json`, `tools/make_update_manifest.py`, `docs/RELEASE.md`, `CortanaApp.kt`.

**Suite après la phase 30 : 297 tests, 0 échec ; lint sans erreur.**

## Phase 31 — Web/CLI/TUI optional clients — DONE
- Décision : D-20260928-061 (le cœur sur la tablette n'expose aucune API locale : son administration reste dans l'application ; le worker, qui expose l'API appairée, reçoit un client d'administration en ligne de commande).
- Gate « mêmes contrats/API » : OK au niveau R/I — `WorkerIntegrationTest.adminClientServesTheSameContractsAsTheApi` (appairage réel, tâche et webhook créés par l'API ; `WorkerAdmin` et la CLI `--json`, lecteur séparé des données, rendent les mêmes `WorkerCapabilities`, `JobStatus`, `HookInfo` ; aucun secret affiché).
- Non exécuté (A/P) : CLI sur une autre machine que celle des tests.
- Fichiers : `worker/{Admin,Main,Jobs,Hooks,Server}.kt`, `app/src/test/.../WorkerIntegrationTest.kt`, `docs/WORKER_PROTOCOL.md`.

**Suite après la phase 31 : 298 tests, 0 échec ; lint sans erreur.**

## Phase 32 — Hardening — DONE
- Décisions : D-20260928-062 (sortie réseau, injection par dépôt, poignées de secrets, chaîne d'approvisionnement, concurrence, fuzz), D-20260928-063 (capacités et permissions, vue du plan, redémarrage, délais du serveur worker).
- MUST soldés : secret handles, repository prompt injection defense, network egress policy, supply-chain checks, dependency verification, SBOM, license inventory, permission health, reboot lifecycle, task plan view, permissions/capabilities screen.
- Tests : `HardeningTest` (9 : politique réseau, hôte bloqué pour tout composant, dépôt hostile neutralisant une autorisation permanente, secrets, 16 soumissions simultanées, fuzz des analyseurs et des appels d'outils, absence de retour arrière catastrophique, charge), `SupplyChainTest` (3), `AndroidLifecycleTest` (3), `ArchitectureRulesTest.secretValuesAreReadOnlyByTheirOwningServices` ; tâche `cortanaSbom` (143 composants, 0 sans licence).
- Défauts trouvés et corrigés pendant la phase : soumission non atomique (deux tâches possibles en même temps) ; contextes de tâche capables de résoudre une poignée de secret ; serveur du worker sans délai de requête (un fil pouvait rester bloqué après une coupure) ; tas de 512 Mo du JVM de test épuisé par la suite complète (erreur mémoire, et un échec intermittent du test de coupure réseau attribué à cette pression mémoire — non reproduit depuis la correction : tas de 2 Go, JVM renouvelé toutes les 60 classes).
- Non exécuté (A/P) : batterie One UI, retrait réel de permissions dans les paramètres Samsung, redémarrage réel, charge et latences mesurées sur la tablette, collecteur/serveurs externes réels.
- Fichiers : `core/policy/PolicyEngine.kt` (`EgressRules`, `EgressGuard`), `core/browser/InjectionGuard.kt`, `core/orchestrator/{StepRunner,ScheduledRuns,Orchestrator}.kt`, `core/secrets/{SecretStore,SecretInventory}.kt`, `core/permissions/CapabilityHealth.kt`, `executors/permissions/AndroidAccessProbe.kt`, `ui/capabilities/CapabilitiesScreen.kt`, `ui/settings/NetworkSecretsSettings.kt`, `ui/tasks/{TasksScreen,TraceView}.kt`, `ui/health/HealthScreen.kt`, `ui/AppNav.kt`, `worker/Server.kt`, `gradle/verification-metadata.xml`, `build.gradle.kts` (`cortanaSbom`), `app/build.gradle.kts` (tests), `docs/LICENSES.md`.

**Suite après la phase 32 : 314 tests, 0 échec ; lint sans erreur ; dépendances vérifiées par empreinte.**

## Phase 33 — Physical device RC — livrables DONE ; matrice sur tablette BLOCKED_EXTERNAL
- Décisions : D-20260928-064 (version candidate, schéma v2 figé, garde de signature, manifeste vérifié par l'application), D-20260928-065 (DNS rebinding).
- Version : `2.0.0-rc1`, versionCode 2, même paquet et même certificat (SHA-256 `6d98…da33`) que la 1.2.0, schéma v2 figé (`ReleaseTest.releasedSchemasAreFrozen`).
- Livrables : APK release signés (arm64-v8a et universel) vérifiés par `apksigner`/`aapt2` ; manifeste de mise à jour signé par l'outil et vérifié par l'application (`ReleaseTest`) ; archive source par `git archive` (sans clé ni mot de passe, contrôlé) ; contrôle de reproductibilité (deux builds depuis l'archive, `RELEASE.md` §7) ; SBOM et licences régénérés ; `CHANGELOG.md` ; note d'installation et de mise à jour 1.2.0 → 2.0.0-rc1 ; rapport de publication (`RELEASE.md` §7) ; `RC_CHECKLIST.md` (matrice physique).
- Revue de la version candidate : la ligne « DNS rebinding » de la matrice était restée « À venir » — traitée (D-065, test) plutôt que reportée ; le README décrivait encore la 1.2.0 et un repli sur la clé de debug — corrigé, et ce repli est désormais refusé par le build ; la tâche SBOM se croyait à jour après le changement de version — régénérée à chaque exécution ; le contrôle de reproductibilité a d'abord échoué (révision Git inscrite dans l'APK, bloc « dependency info » de Play chiffré aléatoirement) — les deux sont désactivés et deux builds depuis l'archive sont désormais identiques octet pour octet.
- Tests : `ReleaseTest` (2), `HardeningTest.dnsRebindingNeverReachesAPrivateAddress`.
- **Bloqué (externe)** : exécution de la matrice sur la Galaxy Tab (`RC_CHECKLIST.md`) et des tests instrumentés (`AccessibilityFixtureTest`, `VisionFixtureTest`) — aucun appareil ni émulateur dans l'environnement de construction. La gate « aucun blocker critique » ne peut être prononcée que sur l'appareil ; la 2.0.0 finale (versionCode 4, même clé) attend ce rapport.
- Capacités : 190 MUST — 189 DONE, 1 BLOCKED_EXTERNAL (exécution de la checklist RC sur la tablette).
- Fichiers : `app/build.gradle.kts`, `build.gradle.kts`, `release/{released.json,signing-cert.pem,cortana-update.json}`, `executors/web/WebTools.kt`, `CHANGELOG.md`, `README.md`, `docs/{RC_CHECKLIST,RELEASE,NOTE_INSTALLATION,DECISIONS,SECURITY,DATA_MIGRATIONS,TEST_MATRIX}.md`.

**Suite au terme de la phase 33 : 317 tests, 0 échec (app 304, worker 8, contrats 5) ; lint 0 erreur ; 143 composants au SBOM, 0 sans licence.**

### Phase 33 — 2.0.0-rc2 : premier retour de la tablette
- **Retour réel (rc1)** : plantage au démarrage sur la Galaxy Tab (rapport de bug du propriétaire) — `PatternSyntaxException` dans l'initialisation de `SkillService` : motif `\{\{([a-zA-Z0-9_]+)}}` accepté par la JVM des tests, refusé par ICU4C, le moteur de `java.util.regex` sur Android. Aucun des tests exécutés ne tournait sur ce moteur ; la ligne 1.2 de `RC_CHECKLIST.md` (non exécutée) l'aurait vu.
- Décision : D-20260928-066. Correctif du motif ; `tools/check_android_regex.py` compile chaque expression régulière de l'app, des contrats et de `model_caps.json` (341) avec la bibliothèque ICU4C réelle et les options d'Android ; exécuté par `AndroidRegexCompatTest` et par la tâche `androidRegexCheck` avant toute release. Sur les sources de la rc1, il trouve exactement le défaut signalé (position 20) et un second (`RepositoryIntelligence`, analyse des `package.json`), corrigé. Revue des motifs construits : identifiant de relation PowerPoint désormais échappé (`Ooxml`).
- Tests instrumentés ajoutés (A, compilés, non exécutés ici) : `StartupAndRegexEngineTest` (démarrage réel et écran principal, 328 motifs compilés par le moteur d'Android, initialisation de chaque classe dans ART, paramètres de procédure).
- Revue de la même famille de risque (vert sur la JVM, faux sur l'appareil) : SQL sans fonctionnalité postérieure à SQLite 3.28 ; lint sans alerte sur les drapeaux de récepteurs, les `PendingIntent`, les alarmes exactes et les services de premier plan.
- Version : `2.0.0-rc2`, versionCode 3, même clé et même schéma v2 ; s'installe par-dessus la rc1 et la 1.2.0.

### Après la phase 33 — 2.0.0-rc3 : Conseil de réflexion (Cognitive Council Engine)
- Paquet `CORTANA_COUNCIL_ENGINE_CLAUDE_HANDOFF` (copie : `docs/council_pack/`), correspondance et conflits : `COUNCIL_MAPPING.md`, décision D-20260929-067, rapports C1→C12 : `COUNCIL_PROGRESS.md`.
- Moteur `core/council/` subordonné à l'orchestrateur (une passerelle, un registre, une politique, une mémoire), désactivé par défaut ; interface Réglages › Intelligence, progression et carte « Résumé du conseil » ; schéma v3 (migration additive 2→3), métriques et spans.
- Version : `2.0.0-rc3`, versionCode 4, même clé ; s'installe par-dessus la rc2, la rc1 et la 1.2.0. Résultats et reproductibilité : `RELEASE.md` §10.
