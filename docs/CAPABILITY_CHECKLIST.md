# 11 — CAPABILITY CHECKLIST VNEXT

Statuts autorisés : `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED_EXTERNAL`.

Aucune ligne MUST ne peut disparaître du fichier pour faire paraître le projet terminé.

## Core

- [x] MUST Task state machine extraite — DONE (phase 3, TaskStateMachine, RuntimeTest)
- [x] MUST Planner — DONE (phase 4, Planner + IntentRouter)
- [x] MUST DAG planning — DONE (phase 4, PlanValidator, dagPlanRunsStepsVerifiesThenSynthesizes)
- [x] MUST Verifier — DONE (phase 4, déterministe puis modèle)
- [x] MUST RecoveryEngine — DONE (phase 4)
- [x] MUST Replanner — DONE (phase 4, Planner.replan, falseSuccessIsCaughtRetriedThenReplanned)
- [x] MUST CheckpointService — DONE (phase 5)
- [x] MUST TaskNotebook — DONE (phase 5 stockage + phase 6 injection dans le contexte)
- [x] MUST durable resume — DONE (phase 5, recoverOnStartup + WAITING_USER)
- [x] MUST cancellation propagation — DONE (tâche + outils (phase 3) ; spécialistes parallèles (phase 23, SpecialistsTest))
- [x] MUST idempotency ledger — DONE (phase 5, ToolDispatcher)
- [x] MUST task events — DONE (phase 3)
- [x] MUST outbox durable effects — DONE (phase 5, Outbox)
- [x] MUST dynamic tool discovery — DONE (phase 6, CapabilityMatcher + tools.discover)
- [x] MUST structured output validation — DONE (phase 4, ModelGateway.completeStructured)
- [x] MUST fast path registry — DONE (phase 4, FastPathRegistry)

## Models

- [x] MUST provider abstraction conservée — DONE (1.2.0 conservé, E2E verts)
- [x] MUST OpenAI-compatible — DONE (1.2.0)
- [x] MUST local server — DONE (1.2.0, préréglage Serveur local)
- [x] MUST fallback — DONE (1.2.0, ordre de repli)
- [x] MUST model capabilities — DONE (1.2.0 + vision/embeddings (phase 7))
- [x] MUST cost accounting — DONE (1.2.0, table usage + plafond quotidien)
- [x] MUST privacy-aware routing — DONE (phase 7, local uniquement)
- [x] MUST coding capability routing — DONE (phase 7, codingRoute)
- [x] MUST vision routing — DONE (phase 7, visionRoute (utilisé en phase 16))
- [x] MUST embeddings provider — DONE (phase 7, HashingEmbedder + GatewayEmbedder)
- [ ] SHOULD native provider adapters where useful — TODO
- [x] MUST circuit breaker / health — DONE (phase 7, ProviderHealth)

## Memory

- [x] MUST conversation — DONE (1.2.0)
- [x] MUST episodic — DONE (1.2.0 + rétention (phase 7))
- [x] MUST semantic — DONE (1.2.0 + index vectoriel)
- [x] MUST profile/preferences — DONE (1.2.0)
- [x] MUST procedural skills — DONE (phase 8)
- [x] MUST FTS — DONE (1.2.0, FTS4)
- [x] MUST vector retrieval — DONE (phase 7)
- [x] MUST hybrid ranking — DONE (phase 7)
- [x] SHOULD graph relations — DONE (phase 7, same_entity + supersedes)
- [x] MUST provenance — DONE (phase 24 : artefacts avec tâche, capacité, opération, sources et empreintes)
- [x] MUST retention — DONE (phase 7)
- [x] MUST export/delete — DONE (phase 7)
- [x] MUST incognito — DONE (1.2.0 + test phase 7)

## Skills

- [x] MUST skill schema — DONE (phase 8)
- [x] MUST versioning — DONE (phase 8, skill_versions)
- [x] MUST candidate learning — DONE (phase 8, SkillLearner)
- [x] MUST parameterization — DONE (phase 8)
- [x] MUST preconditions — DONE (phase 8)
- [x] MUST postconditions — DONE (phase 8)
- [x] MUST safe replay — DONE (phase 8, StepRunner.replaySkill)
- [x] MUST invalidation — DONE (phase 8)
- [x] MUST confidence stats — DONE (phase 8)
- [x] MUST UI management — DONE (phase 8, écran Procédures)

## Android

- [x] MUST current accessibility actions preserved — DONE (1.2.0)
- [x] MUST robust selectors — DONE (phases 8 et 16 (fusion arbre → OCR → vision))
- [x] MUST takeover detection — DONE (1.2.0)
- [x] MUST STOP — DONE (1.2.0 + STOP au redémarrage (phase 5))
- [x] MUST lockscreen safety — DONE (1.2.0 (aucune action écran verrouillé) + phase 16 (aucune capture) ; P non exécuté)
- [x] MUST screenshot capture — DONE (phase 16, takeScreenshot ponctuel ; A/P non exécuté)
- [x] MUST OCR — DONE (phase 16, Tesseract sur l'appareil ; reconnaissance réelle A/P non exécutée)
- [x] MUST vision fallback — DONE (phase 16)
- [x] MUST contacts — DONE (phase 18 ; fournisseur réel A/P non exécuté)
- [x] MUST phone capability where hardware permits — DONE (phase 18, refus propre sans téléphonie ; P non exécuté)
- [x] MUST SMS capability where hardware permits — DONE (phase 18, aperçu + L2/L3 ; P non exécuté)
- [x] MUST calendar — DONE (phase 18, fuseaux, RRULE, rappels, conflits)
- [x] SHOULD notification listener — DONE (phase 18, liste blanche, masquage, déclencheurs)
- [x] MUST clipboard policy — DONE (phase 18)
- [x] MUST permission health — DONE (phase 32, CapabilityHealthService + AndroidAccessProbe)
- [x] MUST reboot lifecycle — DONE (phase 32, AndroidLifecycleTest (niveau P non exécuté))

## Voice

- [x] MUST push-to-talk preserved — DONE (1.2.0)
- [x] MUST STT provider abstraction — DONE (phase 17, tablette + fournisseur)
- [x] MUST TTS provider abstraction — DONE (phase 17, tablette + fournisseur, lecture en continu)
- [x] SHOULD wake word — DONE (phase 17, en mode mains libres visible uniquement)
- [x] SHOULD VAD — DONE (phase 17)
- [x] SHOULD barge-in — DONE (phase 17 ; écho réel P non exécuté)
- [x] MUST visible recording state — DONE (phase 17, barre + notification + audit)

## Web

- [x] MUST fetch/search preserved — DONE (1.2.0)
- [x] MUST SSRF protections — DONE (1.2.0 + chaque redirection vérifiée avant connexion, D-044)
- [x] MUST research workflow — DONE (phase 19, ResearchService + research.run, D-043)
- [x] MUST provenance — DONE (phase 24 : artefacts avec tâche, capacité, opération, sources et empreintes)
- [x] MUST interactive browser executor — DONE (HttpBrowserEngine + browser.*, JS via Chrome/accessibilité, D-042)
- [x] MUST downloads — DONE (artefacts plafonnés, jamais exécutés)
- [x] MUST upload approval — DONE (L2 avec aperçu au moment de l envoi)
- [x] MUST injection defense — DONE (InjectionGuard + citations vérifiées)

## Files/Documents/Data

- [x] MUST SAF file tools preserved — DONE (1.2.0)
- [x] MUST artifact service — DONE (phase 9)
- [x] MUST archive handling safe — DONE (phase 24 : limites, zip slip, liens, bombes — DocumentTest)
- [x] MUST PDF read/create/edit pipeline — DONE (phase 24 : PdfBox — texte, rendu, création, fusion, pages, tampon, métadonnées)
- [x] MUST text document pipeline — DONE (phase 24 : DOCX/Markdown/texte/HTML, modèles, conversions, comparaison)
- [x] MUST spreadsheet pipeline — DONE (phase 24 : XLSX lecture/plages/cellules/formules/dates/graphiques/analyse/export)
- [x] MUST presentation pipeline — DONE (phase 24 : PPTX dispositions, images, graphiques, édition, export PDF)
- [x] MUST structured data CSV/JSON — DONE (phase 24 : Tabular, spreadsheet.analyze, conversions)
- [x] MUST provenance — DONE (phase 24 : artefacts avec tâche, capacité, opération, sources et empreintes)

## Coding

- [x] MUST WorkspaceManager — DONE (phase 9)
- [x] MUST repo import/open — DONE (phase 9, import SAF + ouverture)
- [x] MUST clone/init — DONE (phase 10)
- [x] MUST repo intelligence — DONE (profil (phase 9), symboles/références/graphe/impact (phase 14))
- [x] MUST code search — DONE (phase 9)
- [x] MUST symbol provider abstraction — DONE (phase 14, CodeIntelligenceProvider + fournisseur lexical)
- [x] MUST patch preview/apply/rollback — DONE (phase 9)
- [x] MUST Git status/diff/log — DONE (phase 10)
- [x] MUST branch/worktree — DONE (phase 10, worktree = clone local lié)
- [x] MUST local commit — DONE (phase 10)
- [x] MUST protected push — DONE (phase 10, L3 + jamais forcé)
- [x] MUST BuildService — DONE (phase 13)
- [x] MUST TestService — DONE (phase 13, JUnit + instabilité + avant/après)
- [x] MUST DiagnosticsService — DONE (phase 13)
- [x] MUST DependencyService — DONE (phase 13, inspection sans mise à jour aveugle)
- [x] MUST ReviewService — DONE (phase 15)
- [x] MUST Software Factory end-to-end — DONE (phase 15, E2E-CODE-001 à 006)
- [x] MUST artifact export — DONE (phase 9, SAF + Téléchargements)
- [x] MUST coding resume after crash — DONE (phase 15 (Robolectric ; P non exécuté))

## Execution / Sandbox / Workers

- [x] MUST ExecutionBackend abstraction — DONE (phase 11)
- [x] MUST Android local backend — DONE (phase 11, sh + toybox)
- [x] MUST process timeout/kill — DONE (phase 11, arborescence)
- [x] MUST sandbox filesystem policy — DONE (phases 11-12, isolé par espaces de noms sur le worker)
- [x] MUST sandbox network policy — DONE (phases 11-12, réseau coupé en mode isolé)
- [x] MUST paired worker — DONE (phase 12)
- [x] MUST secure pairing — DONE (phase 12, épinglage + preuve + signatures)
- [x] MUST worker capability discovery — DONE (phase 12)
- [x] MUST reconnect — DONE (phase 12, reprise du même job)
- [x] MUST artifact transfer integrity — DONE (phase 12, SHA-256 vérifié)
- [x] MUST worker revocation — DONE (phase 12)

## Automation

- [x] MUST reminder preserved — DONE (1.2.0)
- [x] MUST once — DONE (1.2.0)
- [x] MUST interval — DONE (1.2.0)
- [x] MUST cron — DONE (1.2.0, CronExpression)
- [x] MUST timezone/DST — DONE (1.2.0, tests cron DST)
- [x] MUST catch-up — DONE (1.2.0)
- [x] MUST condition watch — DONE (phase 27, D-056, surveillance sans modèle)
- [x] MUST concurrency policy — DONE (phase 27, skip/queue/replace/allow)
- [x] MUST durable scheduled tasks — DONE (phase 27, table schedule_runs, reprise au démarrage)

## Multi-agent

- [x] MUST specialist profiles — DONE (SpecialistRegistry, 8 profils)
- [x] MUST specialist task contracts — DONE (SpecialistTask/SpecialistResult enregistrés)
- [x] MUST restricted toolsets — DONE (filtre par rôle, découverte exclue)
- [x] MUST parallel independent subtasks — DONE (≤3 étapes lecture seule, coroutineScope)
- [x] MUST one orchestrator authority — DONE (transitions réservées, événements sans état)
- [x] MUST result merge/review — DONE (intégration séquentielle + vérification)

## Protocols / Plugins

- [x] MUST MCP client — DONE (2026-07-28 + repli initialize, HTTP et stdio via worker, D-046/048 ; OAuth 2.1 par connexion (phase 26, D-055))
- [x] MUST MCP tool normalization — DONE (McpAdapter, espaces de noms, collisions, schémas, D-047)
- [x] MUST MCP policy integration — DONE (registre unique, risque local, contamination, D-047)
- [x] SHOULD A2A adapter — DONE (A2A 1.0 JSON-RPC, délégation bornée, D-050)
- [x] MUST plugin manifest — DONE (contracts/Plugins.kt, D-051)
- [x] MUST plugin signature/integrity — DONE (ECDSA P-256 + empreintes, clé épinglée)
- [x] MUST plugin capability isolation — DONE (outils/réseau/secrets déclarés, LAW-015)

## Integrations

- [x] MUST Connector registry — DONE (phase 26 : ConnectionManager, OAuth 2.1 (PKCE, RFC 8414/9728/7591/7009), santé, révocation ; D-055)
- [x] SHOULD email — DONE (phase 26 : IMAP/SMTP (GreenMail))
- [x] SHOULD webhooks — DONE (phase 26 : entrants via worker (HMAC, rejeu, débit), sortants signés)
- [x] SHOULD generic HTTP — DONE (phase 26 : http.request)
- [ ] SHOULD messaging adapters — IN_PROGRESS (Telegram fait ; Discord/Slack/WhatsApp/Signal non implémentés (interface prête))
- [x] SHOULD home automation adapter — DONE (phase 26 : Home Assistant)

## Media

- [x] MUST vision — DONE (phase 16, VisionProvider local/modèle)
- [x] MUST image analysis — DONE (phase 25 : métadonnées + OCR local + modèle de vision ; MediaTest)
- [x] SHOULD image generation/edit provider — DONE (phase 25 : /images/generations et /images/edits via la passerelle)
- [x] MUST speech services — DONE (phase 17)
- [x] SHOULD video provider abstraction — DONE (phase 25 : création/suivi/téléchargement via la passerelle ; inspection locale)

## Security

- [x] MUST L0-L3 policy preserved — DONE (1.2.0)
- [x] MUST taint tracking expanded — DONE (web (19), écran (16), notifications (18), dépôts (9-15), MCP (20), A2A (21), plugins (22), documents (24))
- [x] MUST exact approval binding — DONE (1.2.0, hash capacité+arguments)
- [x] MUST TOCTOU reclassification — DONE (1.2.0, approvedRisk revérifié par les exécuteurs UI)
- [x] MUST secret handles — DONE (phase 32, SecretInventory + règle d’architecture)
- [x] MUST repository prompt injection defense — DONE (phase 32, lignes signalées, autorisations neutralisées)
- [x] MUST sandbox untrusted builds — DONE (phases 11-13, worker isolé)
- [x] MUST network egress policy — DONE (phase 32, EgressRules + EgressGuard)
- [x] MUST audit chain — DONE (1.2.0)
- [x] MUST supply-chain checks — DONE (phase 32, SupplyChainTest (secrets, catalogue, code dynamique))
- [x] MUST dependency verification — DONE (phase 32, verification-metadata.xml SHA-256)
- [x] MUST SBOM — DONE (phase 32, cortanaSbom CycloneDX 1.5)
- [x] MUST license inventory — DONE (phase 32, docs/LICENSES.md)

## Data / Reliability

- [x] MUST Room migrations from v1 — DONE (phase 2, MIGRATION_1_2)
- [x] MUST migration tests — DONE (DatabaseMigrationTest)
- [x] MUST backup — DONE (phase 29, .cortana-backup, chiffrement facultatif)
- [x] MUST restore — DONE (phase 29, simulation, fusion/remplacement, état précédent sauvegardé)
- [x] MUST DB doctor — DONE (phase 29, 13 contrôles)
- [x] MUST repair — DONE (phase 29, réparations explicites, auditées, réversibles)
- [x] MUST export/import — DONE (phase 29, Téléchargements + sélecteur de documents)
- [x] MUST compatibility manifest — DONE (phase 29, CompatibilityManifest (aussi pour les mises à jour))
- [x] MUST crash recovery — DONE (phase 5 (Robolectric ; niveau P non exécuté))

## Observability

- [x] MUST structured traces — DONE (phase 28, span racine par tâche, arbre par contexte de coroutine)
- [x] MUST metrics — DONE (phase 28, ObservabilityService.metrics)
- [x] MUST model/tool spans — DONE (phase 28, gen_ai.* dans ModelGateway, tool.execute)
- [x] MUST local viewer — DONE (phase 28, écran Tâches → Trace + Métriques (24 h))
- [x] SHOULD OpenTelemetry export — DONE (phase 28, OTLP/HTTP JSON facultatif, désactivé par défaut)
- [x] MUST secrets redacted — DONE (1.2.0 Redactor ; spans expurgés)

## UI

- [x] MUST chat preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST history preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST memory preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST schedules preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST providers preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST health preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST audit preserved — DONE (1.2.0 conservé, E2E verts)
- [x] MUST developer workspace — DONE (phases 9-15 : projets, Git, plan actif, diff, revue, tests/builds, journaux, STOP, moteur, réparations ; affichage A/P non exécuté)
- [x] MUST task plan view — DONE (phase 32, écran Tâches (PlanView))
- [x] MUST skills screen — DONE (phase 8)
- [x] MUST worker/devices screen — DONE (écran Appareils)
- [x] MUST connectors/plugins screen — DONE (Réglages → Connexions (phase 26) et Réglages → Plugins (phase 22))
- [x] MUST artifacts screen — DONE (onglet Artefacts de Développement)
- [x] MUST permissions/capabilities screen — DONE (phase 32, écran Capacités et permissions)

## Update / Release

- [x] MUST signature continuity — DONE (même clé, vérifié à chaque livraison)
- [x] MUST versionCode monotone — DONE (phase 30, release/released.json + verifyReleaseVersion)
- [x] MUST signed update manifest — DONE (phase 30, signé par la clé de l’APK)
- [x] MUST APK hash verification — DONE (phase 30, SHA-256 + taille + lecture système)
- [x] MUST install handoff — DONE (phase 30, PackageInstaller, confirmation du propriétaire)
- [x] MUST rollback strategy documentation — DONE (phase 30, docs/RELEASE.md §5)
- [x] MUST release report — DONE (phase 33, docs/RELEASE.md §7 et rapport de livraison)
- [x] MUST source reproducibility check — DONE (phase 33, deux builds depuis git archive identiques octet pour octet ; deux sources de non-déterminisme trouvées et corrigées, D-20260928-064)
- [ ] MUST physical-device RC checklist — BLOCKED_EXTERNAL (liste écrite : docs/RC_CHECKLIST.md ; exécution sur la Galaxy Tab impossible sans l’appareil)
