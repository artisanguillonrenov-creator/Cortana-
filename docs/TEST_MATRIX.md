# Matrice de test — Cortana VNext

Correspondance entre `vnext_pack/08_ACCEPTANCE_AND_TEST_MATRIX.md` et les tests réels. Niveaux : U unitaire, C contrat, I intégration, R Robolectric, A instrumenté Android, P appareil physique, S sécurité/adversarial, E bout en bout.

**Exécuté** = le test tourne et passe dans l'environnement de build (`./gradlew :contracts:test :worker:test :app:testDebugUnitTest`). **Non exécuté** = écrit ou planifié mais impossible ici (pas d'émulateur ni de tablette) ; jamais compté comme réussi. **À venir** = phase ultérieure de la feuille de route.

Suite en 2.0.0-rc3 : **361 tests exécutés, 0 échec** (app 348, worker 8, contrats 5), tâches de test réellement exécutées à chaque fois (D-20260928-066). Nouveaux en rc3 : Conseil de réflexion (§14 sexies). En rc2 : 319 (app 306). Tests instrumentés écrits et compilés, non exécutés : `StartupAndRegexEngineTest` (4), `AccessibilityFixtureTest` (2), `VisionFixtureTest` (1) (A). Matrice physique : `RC_CHECKLIST.md`, non exécutée avec la rc2 ni la rc3 (la rc1 y a échoué au démarrage).

## 1. Non-régression 1.2.0

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Chat en streaming | U/I/R/E | `OrchestratorEndToEndTest.plainChatStreamsAndPersists`, `SseAndEmulationTest`, `ProviderHttpTest` | — |
| Fournisseur 401/429/5xx | U/I | `ProviderHttpTest.listsModelsAndMapsAuthErrors`, `MemoryAndRoutingTest.circuitBreaker…` | — |
| Fragments SSE | U | `SseAndEmulationTest` | — |
| Appel d'outils natif / émulé | U/I/E | `OrchestratorEndToEndTest.nativeToolLoop…`, `emulatedToolCalling…`, `malformedEmulatedCall…` | — |
| Rappel sans modèle | U/R/E | `OrchestratorEndToEndTest.reminderFastPathNeedsNoModel`, `SchemaCronFastPathTest` | P |
| Mémoire explicite | U/R/E | `OrchestratorEndToEndTest.explicitMemoryIsSavedAndConfirmed` | — |
| STOP retire les outils | R/E | `OrchestratorEndToEndTest.killSwitchRemovesToolsButKeepsChat`, `RuntimeTest.underStop…` | P |
| Approbation L2 | R/S | `OrchestratorEndToEndTest.l2ActionWaitsForOwnerApproval`, `refusedApprovalBlocksAction` | A/P |
| Approbation L3 / biométrie | R (exigence calculée) | `GitTest.pushToARealRemote…`, `SoftwareFactoryTest.e2eCode006…` | A/P (invite biométrique réelle) |
| Actions d'accessibilité | — | `AccessibilityFixtureTest` (écrit) | A/P |
| SSRF, masquage des secrets | U/S | `SecurityPrimitivesTest` | — |
| Cron fuseau/DST | U/R | `SchemaCronFastPathTest.cronStepsAndDst` | — |
| Réarmement au redémarrage | — | — | A/P |

## 2. Planificateur / vérificateur

| Scénario | Exécuté | Tests |
|---|---|---|
| Plan à plusieurs étapes, dépendances | U/R/E | `RuntimeTest.dagPlanRunsStepsVerifiesThenSynthesizes`, `ContractsTest.planValidation…`, `readySteps…` |
| Étape en échec → nouvel essai → replanification | R/E | `RuntimeTest.falseSuccessIsCaughtRetriedThenReplanned` |
| Faux succès détecté | R/E | idem ; `SoftwareFactoryTest.e2eCode003…` (fin refusée par la revue) |
| Budget dépassé, annulation, STOP, attente du propriétaire | R/E | `RuntimeTest.askUserWaits…`, `repeatedNonIdempotentCall…`, `OrchestratorEndToEndTest.killSwitch…` |
| Étapes indépendantes en parallèle | — | À venir (phase 24, spécialistes) |

## 3. Crash / redémarrage

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Reprise au point de reprise, étapes faites non rejouées | R/E | `RuntimeTest.crashResumeContinues…` | P (arrêt réel du processus) |
| Effet incertain → attente du propriétaire | R | `RuntimeTest.uncertainSideEffectAfterCrash…` | P |
| Effet durable enregistré → réconcilié, pas répété | R | `RuntimeTest.durableEffectRecorded…`, `SoftwareFactoryTest.e2eCode004Resume…` | P |
| Point de reprise corrompu / absent | R | `RuntimeTest.legacyTaskWithoutCheckpointFailsSafely` | — |
| Worker déconnecté → reprise | I | `WorkerIntegrationTest.jobSurvivesANetworkOutage…` | deux machines réelles |
| Mise à jour de l'app pendant une tâche | — | — | À venir (phase 30) |

## 4. Mémoire

| Scénario | Exécuté | Tests |
|---|---|---|
| FTS exact, paraphrase sémantique, score hybride | U/R | `MemoryAndRoutingTest.semanticRetrieval…`, `localEmbedder…` |
| Supersession, en attente, suppression, incognito | R | `MemoryAndRoutingTest.retention…`, `exportCarriesProvenance…`, `incognitoSessions…`, `OrchestratorEndToEndTest.taintedTask…` |
| Routage des données sensibles (local uniquement) | R | `MemoryAndRoutingTest.localOnlyPrivacy…` |
| Fournisseur d'embeddings indisponible → lexical | R | `MemoryAndRoutingTest.lexicalRetrievalStillWorks…` |
| Reconstruction d'index | R | `MemoryAndRoutingTest.changingTheEmbeddingModelReindexes…` |
| Relations (graphe), indépendantes de l'ordre | R | `MemoryAndRoutingTest.relatedMemories…`, `entityEdgesDoNotDependOnIndexingOrder` |

## 5. Procédures (skills)

Toutes les lignes de §5 : R/E via `SkillsTest` (8 tests : apprentissage, paramètres, pré/postconditions, cible absente, version d'app, dégradation, repli par coordonnées, L2/L3 toujours demandés, export/import versionné). Rejeu sur de vraies applications : A/P non exécuté.

## 6. Développement / fabrique logicielle

| Scénario | Exécuté | Tests |
|---|---|---|
| E2E-CODE-001 bug simple (inspecter, localiser, plan DAG, branche isolée, patch, test ciblé, suite, build, revue, artefact, pas de push) | I/R/E (worker isolé réel) | `SoftwareFactoryTest.e2eCode001…` |
| E2E-CODE-002 erreur de build → diagnostic normalisé → symbole → patch → build vert | I/R/E | `SoftwareFactoryTest.e2eCode002…`, `nodeLoadErrorsBecomeDiagnostics` |
| E2E-CODE-003 régression → fin refusée → réparation → suite verte | R/E | `SoftwareFactoryTest.e2eCode003…` |
| E2E-CODE-004 reprise après crash (révision vérifiée, pas d'édition en double ; changement externe → attente) | R/E | `SoftwareFactoryTest.e2eCode004…` (2 tests) |
| E2E-CODE-005 instructions malveillantes du dépôt | R/E/S | `SoftwareFactoryTest.e2eCode005…` |
| E2E-CODE-006 protection Git (push forcé refusé, reset --hard L3) | U/R/S | `SoftwareFactoryTest.gitCommands…`, `e2eCode006…`, `GitTest.destructiveShortcutsAreRefused` |
| Espace de travail, recherche, patchs atomiques, fins de ligne, conflits | U/R | `DevWorkspaceTest` (10) |
| Git (worktree, lecture, fusion, push protégé) | I/R | `GitTest` (7) — JGit sur ART : non exécuté |
| Exécution locale (délai, arbre de processus, sortie bornée, confinement) | I | `ExecutionTest` (8) — shell/toybox Android : non exécuté |
| Build/test/diagnostics, instabilité, échec d'infrastructure | U/I/E | `BuildTestDiagnosticsTest` (5) — build Gradle/Android réel d'un exemple : non exécuté |
| Intelligence de code, renommage vérifié avant/après | U/R/E | `CodeIntelligenceTest` (6) |
| Revue (périmètre, modifications accidentelles, secrets, tests affaiblis, architecture/CI, migrations, verdict) | R/S | `ReviewServiceTest` (7) ; porte de fin : `SoftwareFactoryTest` |
| Documentation des capacités synchronisée avec le registre | R | `ToolCapabilitiesDocTest` |

## 7. Worker

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Appairage, révocation, requêtes signées, anti-rejeu | U/I/S | `WorkerTest`, `WorkerIntegrationTest` | worker sur une autre machine |
| Découverte des capacités | I | `WorkerIntegrationTest.pairing…` | — |
| Hors ligne / reconnexion | I | `WorkerIntegrationTest.jobSurvivesANetworkOutage…` | — |
| Empreinte des artefacts | I/S | `WorkerIntegrationTest.tamperedArtifactIsRejected`, `WorkerTest.artifacts…` | — |
| Délai de commande, réseau coupé, chemin non autorisé | I/S | `WorkerTest.processJobsTimeOut…`, `isolatedSandboxConfines…` | Windows/macOS |
| Secret indisponible pour le worker | S | `SoftwareFactoryTest.e2eCode005…` | — |

## 8. Vision

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Arbre complet → aucune capture | U | `VisionTest.aCompleteAccessibilityTreeNeverTriggersACapture`, `fusionPrefersTheAccessibilityTree…` | — |
| Cible absente de l'arbre → capture + OCR | R/E | `VisionTest.automationSucceedsOnAScreenWhoseAccessibilityTreeIsInsufficient` | A/P : `VisionFixtureTest` (capture et Tesseract réels) |
| Cible trouvée par le modèle de vision | R/E | `VisionTest.visionModelFindsAnIconOnAMaskedCapture…`, `modelVisionMapsCoordinates…` | P (modèle réel) |
| Écran sensible bloqué / masqué | U/R/S | `VisionTest.sensitiveAppsAreNeitherCapturedNorAutomated`, `protectedWindowsAreNeverCaptured`, `sensitiveScreenPolicy`, `sensitiveText…`, pixels masqués vérifiés | A/P (fenêtre FLAG_SECURE réelle) |
| Écran changé après approbation → reclassement | R/S | `VisionTest.aTargetThatBecameRiskierAfterApprovalIsRefused` | — |
| Dérive de la cible → la vérification arrête | R | `VisionTest.anActionWithoutVisibleEffectIsReportedAsNotConfirmed` | — |

## 9. Voix

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Push-to-talk (reconnaissance système) | — | inchangé depuis 1.2.0 | P |
| Mot d'éveil activé / désactivé | U/R/E | `VoiceTest.wakePhraseAndStopPhrase`, gate (phrase non adressée ignorée) | P |
| VAD | U | `VoiceTest.vadFindsSpeech…` | P (micro réel) |
| Interruption (barge-in) | R/E | gate (VAD réel sur un micro simulé) | P (écho du haut-parleur) |
| Arrêt de la synthèse | U/R | `VoiceTest.remoteTtsPlays…` (arrêt vide la file), gate | P |
| STT hors ligne / de repli | R | `VoiceTest.androidRecognizer…` (shadow), `remoteStt…` | P |
| Permission micro révoquée | U | message d'erreur non récupérable (`AndroidSttProvider`) | A/P |
| Restrictions d'arrière-plan | — | service de premier plan `microphone` | A/P |

## 9 bis. Communications (phase 18)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Lectures contacts/agenda sans approbation | R/E | `CommsTest.readsAreFree…` | A/P (fournisseurs réels) |
| SMS/appel : aperçu, L2 contact, L3 inconnu, refus court/urgence/ambigu, sans téléphonie | R/E/S | `CommsTest.readsAreFree…`, `ambiguousShortAndEmergency…` | P (envoi réel) |
| Agenda : fuseau, DST, RRULE, conflits | U/R | `CommsTest.calendarRules…` | A/P |
| Notifications : liste blanche, masquage, modèle non local, réponse approuvée | U/R/S | `CommsTest.notificationHub…`, `notificationContentIsHidden…` | A/P (service réel) |
| Déclencheur de notification contaminé | R/E/S | `CommsTest.notificationTriggers…` | — |
| Presse-papiers : secret jamais lu, copie sensible effacée | R | `CommsTest.clipboard…` | A/P |

## 9 ter. Navigateur et recherche (phase 19)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Éléments numérotés, formulaires, cookies isolés par session, retour | R | `BrowserTest.pagesExposeNumberedElementsFormsAndIsolatedCookies` | P (sites réels) |
| Formulaire GET non sensible rempli et envoyé sans approbation (gate) | R/E | `BrowserTest.nonSensitiveGetFormIsFilledAndSubmittedWithoutAskingTheOwner` | P |
| POST + fichier : approbation avec chaque valeur ; mot de passe refusé | R/E/S | `BrowserTest.postWithFileNeedsApprovalShowingEveryValueAndPasswordFormsAreRefused` | — |
| Envoi refusé = rien envoyé ; téléchargement en artefact ; exécutable en L2 | R/E/S | `BrowserTest.refusedSubmissionSendsNothingAndDownloadsBecomeArtifacts`, `downloadsAreCappedNamedSafelyAndFlagged` | — |
| Recherche multi-sources : classement, citations vérifiées, dédoublonnage, divergences, provenance | R | `BrowserTest.researchRanksReadsVerifiesDeduplicatesAndCites`, `sourceRankingPrefers…` | P (moteurs réels) |
| Recherche de bout en bout par l'orchestrateur, rapport cité (gate) | R/E | `BrowserTest.researchRunsEndToEndThroughTheOrchestratorAndStoresACitedReport` | — |
| Pages JavaScript via Chrome + accessibilité/vision | — | — | A/P non exécuté |

## 10. MCP (phase 20)

| Scénario (doc 08 §10) | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Découverte (`server/discover`), pagination, normalisation dans le registre | R/I | `McpTest.modernServerIsDiscovered…` | P (serveurs réels) |
| Noms en double : espaces de noms par serveur, collisions après normalisation | R | `McpTest.twoServersWithTheSameToolName…`, `modernServerIsDiscovered…` | — |
| Schéma invalide, `x-mcp-header` invalide : outil rejeté, les autres gardés | R | `McpTest.modernServerIsDiscovered…` | — |
| Délai dépassé, annulation (HTTP : fermeture du flux ; stdio : `notifications/cancelled`) | R/I | `McpTest.timeoutCancellationAndServerLoss…`, `stdioServerRunsOnThePairedWorker…` | — |
| Perte du serveur : outils retirés, appel non rejoué, reconnexion avec délai croissant | R | `McpTest.timeoutCancellationAndServerLoss…` | — |
| Ressource contaminante (injection retirée, tâche contaminée) | R/E/S | `McpTest.externalToolsNeverBypassThePolicyEngine` | — |
| Outil externe dangereux : L3, refus → jamais exécuté ; indications non fiables ignorées | R/E/S | `McpTest.externalToolsNeverBypassThePolicyEngine`, `modernServerIsDiscovered…` | — |
| Négociation de version : moderne sans version commune (pas de repli), ancien par repli `initialize`, session expirée | R | `McpTest.versionNegotiation…`, `twoServersWithTheSameToolName…` | — |
| Gate : outil d'un serveur de test utilisé via le registre et la politique | R/E | `McpTest.fixtureServerToolIsUsedThroughTheRegistryAndPolicy` | — |
| stdio via le worker (vrai sous-processus, environnement minimal) | I/E | `McpTest.stdioServerRunsOnThePairedWorkerWithCancellation` | P (deux machines) |
| OAuth 2.1 des serveurs MCP | — | — | À venir (phase 26, D-20260927-049) |

## 11. A2A (phase 21)

| Scénario (doc 08 §11) | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Carte d'agent, interface JSON-RPC 1.x, interface 0.3 refusée | R | `A2aTest.agentCardsAreReadAndSkillsMatchTheObjective` | P (agents réels) |
| Correspondance de compétences | R | idem | — |
| Tâche déléguée (gate), aucune fuite de mémoire interne | R/E/S | `A2aTest.delegatedSubtaskCarriesOnlyWhatTheOwnerApprovedAndNothingFromMemory` | — |
| Résultat fichier (inline et par URL) → artefacts | R/E | idem | — |
| Réponse non fiable (injection retirée, tâche contaminée) | R/E/S | idem | — |
| Annulation (délai, tâche Cortana annulée) → `CancelTask` | R | `A2aTest.lateOrCancelledRemoteTasks…` | — |
| Échec d'authentification ; secret jamais transmis | R/E/S | `A2aTest.secretsAreNeverSentAndAuthenticationFailuresAreClear` | — |
| Précision demandée, poursuite de la même tâche | R | `A2aTest.inputRequiredIsSurfaced…` | — |

## 10 bis. Spécialistes (phase 23)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Gate : tâche de code analyste → implémenteur → relecteur, un seul orchestrateur | R/E/I | `SpecialistsTest.codingTaskUsesAnalystImplementerAndReviewerUnderTheOneOrchestrator` | P |
| Outils limités au rôle (écriture refusée à l'analyste), contexte minimal (ni mémoire ni conversation) | R/E/S | idem | — |
| Étapes indépendantes en lecture seule simultanées, fusion par l'orchestrateur | R/E | `SpecialistsTest.independentReadOnlyStepsRunInParallelAndMergeInTheOrchestrator` | — |
| Annulation propagée aux spécialistes en cours | R/E | `SpecialistsTest.cancellingTheTaskCancelsParallelSpecialists` | — |

## 11 bis. Plugins (phase 22)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Paquet modifié, non signé, chemin sortant, fichier non déclaré, code, réseau/https/secret non déclarés | U/S | `PluginTest.packagesAreVerifiedBeforeAnythingIsWritten` | — |
| Gate : plugin ajouté puis retiré sans corruption des autres données | R/I | `PluginTest.pluginIsAddedAndRemovedWithoutTouchingAnythingElse` | A (installation depuis le sélecteur de fichiers) |
| Mise à jour : même clé, pas de retour arrière, empreinte approuvée | R/S | `PluginTest.updatesKeepThePublisherKeyAndNeverGoBackwards` | — |
| Isolation des capacités, secrets requis, compatibilité, désactivation | R/S | `PluginTest.capabilitiesAreIsolatedToWhatThePluginDeclared` | — |
| Coupure pendant installation / mise à jour / suppression ; fichiers disparus | R | `PluginTest.interruptedOperationsAreFinishedOrUndoneAtStartup` | P (vraie coupure d'alimentation) |
| Aucun chargement de code | U | `ArchitectureRulesTest.law015_pluginsNeverLoadOrRunCode` | — |

## 11 ter. Documents et données (phase 24)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Gate : créer, modifier, exporter des artefacts représentatifs (CSV lu, classeur + graphique, contrat depuis modèle, PDF copié après approbation, présentation) avec provenance | R/E | `DocumentTest.gateCreatesEditsAndExportsRepresentativeArtifactsWithProvenance` | P (dossier SAF réel de la tablette) |
| Fichiers ouverts par LibreOffice (DOCX, XLSX recalculé, PPTX) et DOCX de LibreOffice relu | I | `DocumentTest.libreOfficeOpensEveryGeneratedFormat` (ignoré si LibreOffice absent) | Microsoft Office (non disponible ici) |
| Structure DOCX/HTML/Markdown (titres, listes numérotées, tableaux, sauts de page) | U/R | `DocumentTest.markupDocxHtmlAndMarkdownRoundTripKeepStructure` | — |
| Modèles : champs éclatés entre « runs », en-têtes, suppressions ignorées ; ajout avant la section | U | `DocumentTest.templateReplacementWorksAcrossWordRunsHeadersAndAppendKeepsSection` | — |
| DOCTYPE refusé (XXE) | S | `DocumentTest.xmlWithADoctypeIsRefused` | — |
| Formules (sous-ensemble, cycles, fonctions inconnues laissées au tableur) | U | `DocumentTest.formulasAreEvaluatedForCachedValues` | — |
| Classeur : écriture, édition en place (autres feuilles intactes), nouvelle feuille, graphiques liés aux cellules, plages | U/R | `DocumentTest.workbookWriteEditChartAndInspectKeepOtherSheets` | — |
| Dates (ISO, JJ/MM/AAAA, format personnalisé, style ajouté si absent) | U | `DocumentTest.datesAreRealDatesInWorkbooks` | — |
| CSV/JSON : délimiteur, guillemets, décimales françaises, analyse | U | `DocumentTest.csvJsonParsingAndAnalysis` | — |
| Présentation : dispositions, image au bon rapport, graphique, insertion, remplacement, ordre, suppression avec purge des médias | U/R | `DocumentTest.presentationLayoutsImagesChartsAndEdits` | — |
| PDF : création (accents, tableaux, image, pagination), texte, fusion, extraction, rotation, suppression, tampon, métadonnées, rendu d'une page (graphismes natifs) | R | `DocumentTest.pdfIsCreatedReadEditedAndRendered` | P (rendu sur ART) |
| PDF chiffré lisible mais jamais modifié ; mot de passe requis refusé | S | `DocumentTest.encryptedPdfsAreReadWhenAllowedButNeverModified` | — |
| Archives : zip slip, chemins absolus, bombe, trop d'entrées, liens TAR, noms longs GNU, TGZ/GZ | S | `DocumentTest.archivesAreListedAndExtractedSafely` | — |
| Outils : 15 capacités, lectures L0 sans effet, production L1, `save_to` L2 (remplacement annoncé), analyste de documents | R/S | `DocumentTest.toolsAreStructuredReadOnlyReadsAndAssistantToolset` | — |
| Comparaison (texte, cellules), extraction de tableaux, conversions, analyse, archives vers le dossier, chemins refusés | R | `DocumentTest.conversionsCompareExtractAndArchiveToolsRunDirectly` | — |
| Injection dans un document retirée, tâche contaminée | R/E/S | `DocumentTest.documentTextIsDataAndInstructionsInsideAreRemoved` | — |

## 11 quater. Médias (phase 25)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Gate : routage par capacité (rapport, route du propriétaire, exclusion par « Local uniquement ») | R/S | `MediaTest.capabilityRoutingIsReportedAndHonoursThePrivacyMode` | — |
| Gate : image générée via la passerelle, vérifiée, stockée avec provenance puis analysée par le modèle de vision, de bout en bout | R/E | `MediaTest.gateImagesAreGeneratedThroughTheGatewayVerifiedStoredAndAnalysed` | P (fournisseur réel) |
| Sorties fournisseur vérifiées (pas une image, URL http, URL privée, réponse vide, taille invalide) | R/S | `MediaTest.providerOutputIsVerifiedBeforeBecomingAnArtifact` | — |
| Copies sans métadonnées (GPS, appareil), orientation appliquée, position seulement sur demande | R/S | `MediaTest.imagesLeaveTheTabletOnlyAsCopiesWithoutMetadata` | — |
| Fichiers déguisés et bombes de décompression refusés avant décodage | S | `MediaTest.disguisedFilesAndDecompressionBombsAreRefusedBeforeDecoding` | — |
| Transformations locales (redimensionner, recadrer, pivoter, gris, format), copie L2 | R | `MediaTest.localTransformsAreDeterministicAndReencoded` | — |
| Synthèse en fichier (locale, distante), transcription (routes, type, injection retirée) | R/S | `MediaTest.speechFilesAndTranscriptionsUseTheirOwnRoutes` | A (moteur Android `synthesizeToFile`) |
| Vidéo : tâche suivie, téléchargée, vérifiée ; échec signalé | R | `MediaTest.videoJobsArePolledDownloadedAndVerified` | P (fournisseur réel) |
| Inspection vidéo et images extraites | R | `MediaTest.videoInspectionUsesTheLocalProbeAndExtractsFrames` | A (`MediaMetadataRetriever` réel) |
| Appels média réservés à la passerelle (LAW-001 étendue) | U | `ArchitectureRulesTest.law001_onlyModelGatewayCallsModels` | — |

## 11 quinquies. Connexions et intégrations (phase 26)

| Scénario | Exécuté | Tests | Non exécuté |
|---|---|---|---|
| Gate : connecteur de test OAuth 2.1 (découverte, enregistrement, PKCE, état falsifié ou réutilisé refusé, rafraîchissement avec rotation), appels, puis révocation (jetons révoqués chez le fournisseur, secrets effacés, plus rien d'utilisable) | R/E/S | `ConnectionTest.gateOAuthConnectorFixtureWithRefreshAndRevocation` | P (fournisseurs réels, navigateur) |
| Serveur MCP protégé : serveur d'autorisation trouvé par la ressource (RFC 9728), https exigé hors réseau local | R/S | `ConnectionTest.mcpServersFindTheirAuthorizationServerThroughTheResource` | — |
| Validation, désactivation, limite de débit, santé dégradée → hors service, recul | R | `ConnectionTest.lifecycleValidationRateLimitAndHealthBackoff` | — |
| Webhooks entrants via un worker réel : signature, fraîcheur, rejeu, débit, collecte, tâches contaminées, révocation | R/I/E/S | `ConnectionTest.inboundWebhooksAreVerifiedRateLimitedAndBecomeTaintedTasks` | P (expéditeur externe réel) |
| Webhook sortant signé, idempotent, prévisualisé | R/S | `ConnectionTest.outboundWebhooksAreSignedIdempotentAndPreviewed` | — |
| Home Assistant : états, domaines permis, sensibles en L3, jeton invalide | R/S | `ConnectionTest.homeAssistantReadsAndCommandsWithinAllowedDomains` | P (instance réelle) |
| E-mail contre un vrai serveur IMAP/SMTP (GreenMail) : recherche, lecture (injection retirée), pièce jointe, réponse dans le fil + copie envoyée, transfert, brouillon, archive, en-têtes protégés, mauvais mot de passe, clair refusé hors réseau local | R/I/S | `ConnectionTest.emailConnectorAgainstARealImapAndSmtpServer` | P (Gmail, Outlook… réels) |
| Telegram : seul le propriétaire est écouté, rejet audité, décalage persistant, réponse par l'outbox | R/E/S | `ConnectionTest.telegramOnlyListensToTheOwnerAndRepliesThroughTheOutbox` | P (API Telegram réelle) |
| Tables de connexions dans la migration 1→2 | R | `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` | — |

## 11 sexies. Automatisation (phase 27, D-20260928-056)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Politiques de concurrence skip / queue / allow / replace contre les exécutions ouvertes ; politique inconnue refusée ; désactivation → exécutions en attente annulées | R | `AutomationTest.concurrencyPoliciesDecideAgainstOpenRuns` | — |
| File durable : exécution due pendant une tâche en attente d'approbation → reste en file, puis s'exécute dès que l'orchestrateur est libre (tâche `schedule`) | R/E | `AutomationTest.queuedRunWaitsForTheBusyOrchestratorThenExecutes` | — |
| Redémarrage : exécution `running` → `interrupted`, file reprise, purge à 60 jours | R/E | `AutomationTest.restartMarksRunningRunsInterruptedAndResumesTheQueue` | P (arrêt réel du processus sur la tablette) |
| Rattrapage : `catch_up_once` → une exécution en retard ; `skip` → rien | R | `AutomationTest.missedRunsFollowTheSchedulePolicy` | P (tablette éteinte puis redémarrée) |
| Surveillance : condition non remplie → aucun modèle, aucune tâche, lecture tracée par le dispatcher ; remplie → tâche contaminée, contenu enveloppé | R/E/S | `AutomationTest.conditionWatchChecksWithoutModelAndRunsATaintedTaskOnlyWhenMet` | — |
| Surveillance `changed` : référence, inchangé, changé | R/E | `AutomationTest.changedWatchNeedsABaselineThenFiresOnChange` | — |
| Tests de condition déterministes et validation | U | `AutomationTest.conditionTestsAreDeterministic` | — |
| Outils : surveillance refusée sur une capacité à effet ou inconnue, acceptée en lecture ; liste et historique | R/S | `AutomationTest.scheduleToolsCreateWatchesOnlyOnReadOnlyCapabilities` | — |
| STOP annule la file ; suppression efface les exécutions | R/S | `AutomationTest.stopCancelsQueuedRunsAndDeleteRemovesThem` | — |
| Colonne `concurrencyPolicy` (défaut `skip`) et table `schedule_runs` dans la migration 1→2 | R | `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` | — |
| LAW-006 : le planificateur ne référence ni dispatcher, ni registre, ni passerelle | U | `ArchitectureRulesTest.law006_schedulerNeverExecutesToolsOrCallsModels` | — |

## 11 septies. Service d'amélioration (phase 27, D-20260928-057)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Gate : l'analyse propose (échecs répétés, raccourci, coût) sans rien modifier — réglages, tâches, modèle intacts ; même preuve = même version, preuve nouvelle = version suivante ; aucune capacité n'applique | R/S | `ImprovementTest.gateAnalysisProposesButNeverChangesAnything` | — |
| Application par le propriétaire puis annulation ; annulation refusée si la valeur a été rechangée ; réglage de sécurité et valeur hors bornes refusés ; audit | R/S | `ImprovementTest.ownerAppliesAndRollsBackBoundedSettingChanges` | — |
| Raccourci approuvé : réponse sans modèle par le dispatcher, retiré par l'annulation ; action à effet externe refusée comme raccourci | R/E/S | `ImprovementTest.approvedShortcutAnswersWithoutTheModelAndRollsBack` | — |
| Échec puis réussite → cas de test ; chemin différent ensuite → régression signalée ; annulation → cas retiré | R | `ImprovementTest.failThenSuccessBecomesARegressionCaseThatDetectsRegressions` | — |
| Procédure validée à activer, procédure défaillante à désactiver ; annulations | R | `ImprovementTest.skillProposalsActivateAndRollBack` | — |
| Proposition refusée jamais reproposée ; proposition sans preuve → obsolète | R | `ImprovementTest.obsoleteAndRejectedFindingsAreNotReproposed` | — |
| Routage (fournisseur défaillant / alternative prouvée), requêtes trop longues, outils en double | U | `ImprovementTest.routingProposesAProviderThatWorks`, `ImprovementTest.longPromptsAndDuplicateToolsAreDetected` | — |
| LAW-019 : le service n'exécute ni outil ni modèle, ne touche ni fichiers ni dépôts ; seule l'UI applique | U | `ArchitectureRulesTest.law019_improvementOnlyProposesAndNeverExecutes` | — |
| Tables `improvement_proposals` et `eval_cases` dans la migration 1→2 | R | `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` | — |

## 11 octies. Observabilité (phase 28, D-20260928-058)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Gate : tâche DAG réelle (modèle scripté) → un seul arbre sous le span « task » : plan, étapes, tours du modèle et `gen_ai.chat`, outils, vérifications (déterministe et par modèle), synthèse ; aucun secret dans les lignes, le visualiseur, l'outil ; métriques cohérentes | R/E/S | `ObservabilityTest.gateTaskShowsModelToolAndVerifierSpansWithoutSecrets` | P (tablette, fournisseur réel) |
| Attributs expurgés, clés de contenu ignorées, parent racine déterministe | R/S | `ObservabilityTest.spanAttributesAreRedactedAndContentKeysDropped` | — |
| Export OTLP/HTTP JSON : échec → réessai, en-tête du coffre, ids hexadécimaux, arbre conservé, envoi unique, sans secret | R/S | `ObservabilityTest.otlpExportSendsValidJsonOnceWithoutSecretsAndRetriesOnFailure` | P (collecteur réel) |
| Export désactivé par défaut ; http public et identifiants dans l'URL refusés | R/S | `ObservabilityTest.exportIsOffByDefaultAndRefusesCleartextToTheInternet` | — |
| Rétention par âge et par nombre | R | `ObservabilityTest.retentionKeepsTheConfiguredWindowAndSize` | — |
| Table `spans` dans la migration 1→2 | R | `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` | — |

## 11 nonies. Durcissement (phase 32, D-20260928-062)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Politique de sortie : blocage (suffixes), confirmation des nouvelles destinations, destinations connues seulement | R/S | `HardeningTest.egressPolicyBlocksConfirmsOrRestrictsDestinations` | — |
| Hôte bloqué jamais joint, quel que soit le composant (intercepteur du client commun) | R/S | `HardeningTest.blockedHostsAreNeverReachedByAnyComponent` | — |
| Dépôt hostile : ligne signalée au modèle (numéro du fichier), code intact, autorisation permanente d'exécution neutralisée → confirmation (refusée) ; dépôt sain : autorisation utilisée | R/E/S | `HardeningTest.repositoryInjectionRevokesStandingGrantsForTheTask` | — |
| Poignées de secrets : inventaire (fournisseur, connexion, réglage), manquantes, orphelines, rotation auditée sans valeur, purge ; un contexte de tâche ne résout jamais une poignée | R/E/S | `HardeningTest.secretHandlesAreInventoriedRotatedAndNeverResolvedByTasks`, `ArchitectureRulesTest.secretValuesAreReadOnlyByTheirOwningServices` | — |
| 16 soumissions simultanées → une seule tâche | R | `HardeningTest.simultaneousIngressesStartExactlyOneTask` | — |
| Fuzz des analyseurs de données externes (schéma, SSE, cron, MIME, HTML, manifeste, garde, sauvegarde) ; appels d'outils malformés et énormes ; expressions sans retour arrière catastrophique | U/R | `HardeningTest.parsersOfOutsideDataNeverCrash`, `malformedModelToolCallsAreRefusedNotCrashing`, `injectionGuardHasNoCatastrophicBacktracking` | — |
| Charge : 3 000 messages, 1 000 souvenirs — recherche et indexation bornées | R | `HardeningTest.storesStayFastUnderLoad` | P (tablette : mesures réelles) |
| Chaîne d'approvisionnement : pas de secret ni de clé suivis, dépendances par le catalogue et vérifiées par empreinte, pas de chargeur dynamique | U | `SupplyChainTest` (3) + tâche `cortanaSbom` | — |

## 11 decies. Capacités, permissions, redémarrage (phase 32, D-20260928-063)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Écran Capacités et permissions : indisponible (accès bloquant manquant, raison), dégradée (accès conseillé), L3 dépend du verrouillage, dernière utilisation, autorisations comptées puis révoquées, STOP | R | `AndroidLifecycleTest.capabilitiesShowStateReasonFixLastUseAndRevocation` | P (écrans système sur la tablette) |
| Permission retirée : vue à la lecture suivante, exécuteur refusant avec un message clair, capacité indisponible | R/S | `AndroidLifecycleTest.aRevokedPermissionIsSeenLiveAndTheExecutorRefusesClearly` | P (retrait réel dans les paramètres One UI) |
| Redémarrage : BOOT_COMPLETED → rappel manqué délivré une fois en retard, tâche manquée ignorée selon sa politique et réarmée, alarmes reprogrammées ; tâche et exécution coupées closes sans rejeu | R/E | `AndroidLifecycleTest.rebootRearmsCatchesUpAndRecovers` | P (redémarrage réel de la tablette) |
| Batterie (One UI) | — | — | P uniquement (non mesurable dans l'environnement de construction) |

## 12. Sécurité

| Scénario | Exécuté | Tests |
|---|---|---|
| Injection via le web / un dépôt / un résultat d'outil | R/E/S | `OrchestratorEndToEndTest.taintedTask…`, `SoftwareFactoryTest.e2eCode005…`, `SecurityPrimitivesTest.envelopeCannotBeClosedFromInside` |
| Injection via une notification | R/E/S | `CommsTest.notificationTriggersStartTaintedTasks…` |
| Injection via une page (texte caché, instructions à l'assistant, citation inventée) | R/E/S | `BrowserTest.injectionGuard…`, `researchRanksReads…`, `researchRunsEndToEnd…` |
| Exfiltration de secret, SSRF | U/S | `SecurityPrimitivesTest` ; revue de fin (secrets ajoutés) |
| SSRF par redirection vers une IP privée (navigateur et `web_fetch`) | R/S | `BrowserTest.ssrfGuardChecksEveryRedirectHopBeforeConnecting` |
| Fuite de mémoire interne vers un agent externe | R/E/S | `A2aTest.delegatedSubtaskCarries…` |
| DNS rebinding (réponse publique puis privée, réponse mixte, IPv4 privée en IPv6 mappée/compatible/NAT64) | U/R/S | `HardeningTest.dnsRebindingNeverReachesAPrivateAddress` (D-20260928-065) |
| Traversée de chemin, zip slip, lien symbolique | U/I/S | `DevWorkspaceTest.pathsCannotEscape…`, `WorkerTest.deltaSyncAndZipSlip…` |
| Script de dépendance malveillant | I/S | exécution isolée sans réseau (`WorkerTest.isolatedSandbox…`) |
| Rejeu d'approbation, TOCTOU, effet externe dupliqué | R/S | approbation liée aux arguments ; `RuntimeTest.repeatedNonIdempotentCall…` |
| Course STOP | R | `OrchestratorEndToEndTest.killSwitch…` — P non exécuté |
| Verrouillage de l'appareil, superposition | — | A/P non exécuté |

## 13. Migration de données

`DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` (fixture v1 : sessions, messages, mémoires, fournisseurs, planifications, audit ; vérification ligne à ligne, FTS, réglages) et `preMigrationBackupIsWrittenForOlderSchema` — exécutés (R).

## 14. Sauvegarde / restauration (phase 29, D-20260928-059)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Gate : sauvegarde chiffrée de l'instance A (conteneur réel, données représentatives, secrets, artefact) restaurée sur une instance B indépendante (seconde base Room, coffre et dossiers distincts) : inspection, phrase requise ou fausse refusée, simulation sans écriture, remplacement ; tables identiques ligne à ligne, tâche en cours close, recherche plein texte (accents) et réglages rechargés, secrets et artefact (empreinte) restaurés, état précédent sauvegardé, diagnostic de B sain | R/I/S | `BackupTest.gateBackupOfInstanceARestoresOnInstanceB` | P (deux tablettes réelles) |
| Secrets jamais écrits sans chiffrement ; phrase faible refusée ; restauration sans secrets → secrets manquants signalés | R/S | `BackupTest.secretsNeverLeaveUnencryptedAndWeakPassphrasesAreRefused` | — |
| Sauvegarde corrompue, entrée injectée (traversée), entrée non déclarée, manifeste chiffré modifié, fichier étranger, schéma plus récent : refus sans aucune écriture | R/S | `BackupTest.corruptedTamperedForeignOrNewerBackupsAreRefused` | — |
| Fusion (local gagnant, conflits comptés, lignes identiques reconnues) puis remplacement explicite | R | `BackupTest.mergeKeepsLocalRowsAndReportsConflicts` | — |
| Toute table classée incluse ou exclue | U | `BackupTest.everyTableIsClassifiedForBackup` | — |
| Diagnostic : index FTS désynchronisé (vu aussi par quick_check), artefacts perdus et orphelins, verrou périmé, procédure sans définition, secret manquant, audit falsifié, tâche sans propriétaire → réparations (quarantaine, marquage, reconstruction), audit jamais « réparé » | R/S | `BackupTest.doctorFindsAndRepairsReversibly` | — |

À venir (phase 29).

## 14 ter. Clients d'administration (phase 31, D-20260928-061)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Gate : après appairage réel (HTTPS épinglé, requêtes signées), une tâche soumise et un webhook enregistré par l'API, la vue d'administration et la CLI `--json` (lecteur séparé des données) rendent les mêmes `WorkerCapabilities`, `JobStatus`, `HookInfo` que l'API ; aucun secret de webhook affiché | R/I | `WorkerIntegrationTest.adminClientServesTheSameContractsAsTheApi` | P (worker sur une autre machine) |

## 14 bis. Mises à jour (phase 30, D-20260928-060)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Gate : manifeste signé par la clé de l'application → disponible ; préparation : sauvegarde vérifiable d'abord, APK téléchargé au chemin relatif du canal, octet pour octet ; rien d'installé sans le propriétaire ; remise à l'installateur ; données inchangées ; APK signé par un autre certificat refusé et supprimé | R/S | `UpdateTest.gateUpgradeKeepsDataAndSignature` | P (installation réelle 1.2.0 → VNext sur la tablette) |
| Autre clé, texte signé modifié, JSON invalide, autre certificat, schéma plus ancien, version intermédiaire requise, autre paquet refusés ; même version = à jour | R/S | `UpdateTest.manifestsNotSignedByCortanaOrIncompatibleAreRefused` | — |
| APK altéré, plus gros qu'annoncé, versionCode incohérent refusés ; canal http public refusé | R/S | `UpdateTest.downloadsAreVerifiedBoundedAndHttpsOnly` | — |
| Historique des versions monotone sous un seul certificat, paquet constant | U | `UpdateTest.releaseHistoryIsMonotoneUnderOneCertificate` (+ tâche `verifyReleaseVersion`) | — |
| Aucune capacité ne prépare ni n'installe | U | `ArchitectureRulesTest.restoreAndRepairAreOwnerOnly` | — |

## 14 quater. Publication de la version candidate (phase 33, D-20260928-064)

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Schémas publiés figés : `identityHash` de `1.json` et `2.json` égaux à ceux enregistrés dans `release/released.json` | U | `ReleaseTest.releasedSchemasAreFrozen` | — |
| Manifeste réel de la 2.0.0-rc1 (produit par `tools/make_update_manifest.py`) vérifié par `UpdateService` avec le certificat public extrait de l'APK, comme mise à jour d'une 1.2.0 ; même enveloppe modifiée d'un octet refusée | R/S | `ReleaseTest.thePublishedManifestIsAnUpgradeOf120SignedByTheReleaseKey` | P (canal https réel, installation) |
| APK release : même certificat (SHA-256 `6d98…da33`), paquet, versionCode 2 | outil | `apksigner verify --print-certs`, `aapt2 dump badging` (`RELEASE.md` §7) | — |
| Build release refusé sans clé de publication | outil | `verifyReleaseVersion` | — |
| Archive source reproductible | outil | deux builds depuis `git archive`, comparaison octet par octet (`RELEASE.md` §7) | — |
| Matrice complète sur la Galaxy Tab | — | `RC_CHECKLIST.md` | **P non exécuté (BLOCKED_EXTERNAL)** |

## 14 quinquies. Moteur d'Android : expressions régulières et démarrage (2.0.0-rc2, D-20260928-066)

La rc1 a planté au démarrage sur la tablette : un motif accepté par la JVM des tests est refusé par ICU4C, le moteur de `java.util.regex` sur Android.

| Scénario | Exécuté | Tests | Reste |
|---|---|---|---|
| Chaque expression régulière écrite dans l'app et les contrats (341, dont les constantes résolues et les 9 motifs de `model_caps.json`) compilée par la bibliothèque ICU4C réelle avec les options d'Android ; liste embarquée pour l'appareil à jour | U/S | `AndroidRegexCompatTest.everyRegexOfTheAppCompilesWithIcuAsOnAndroid` (+ tâche `androidRegexCheck` avant toute release) | — |
| Le contrôle retrouve le défaut de la rc1 (même motif, même position 20 que le rapport de bug) et un second motif fautif (`package.json`) | outil | `tools/check_android_regex.py` sur les sources de la rc1 (2 refus) | — |
| Paramètres de procédure `{{nom}}` : extraction, substitution, accolades isolées, paramètre manquant | U | `AndroidRegexCompatTest.skillPlaceholders`, `SkillsTest` | — |
| Démarrage réel (`CortanaApp.onCreate`, conteneur, écran principal RESUMED) | A | `StartupAndRegexEngineTest.applicationStartsAndTheMainScreenOpens` | **A non exécuté** (pas d'émulateur) |
| Les 328 motifs compilés par le moteur d'Android sur l'appareil ; paramètres de procédure sur ART | A | `StartupAndRegexEngineTest.everyRegexOfTheAppCompilesWithAndroidsEngine`, `skillPlaceholdersCompileAndSubstituteOnAndroid` | **A non exécuté** |
| Initialisation statique de chaque classe de l'application dans ART | A | `StartupAndRegexEngineTest.everyClassOfTheAppInitialisesOnAndroid` | **A non exécuté** |
| Sémantique des motifs sous ICU (`\w`, `\d`, `\s`, `\b`, `(?i)` Unicode) | revue | `SECURITY.md` § Publication | P (comportement sur la tablette) |

## 14 sexies. Conseil de réflexion (2.0.0-rc3, D-20260929-067)

Correspondance avec `council_pack/11_ACCEPTANCE_AND_BENCHMARKS.md`. Tests Robolectric sur le conteneur réel (orchestrateur, passerelle, dispatcher, politique, Room) contre des modèles OpenAI-compatibles scriptés (`CouncilTest`) ; logique pure (`CouncilLogicTest`) ; jeu de référence (`CouncilGoldenSetTest`).

| Exigence (doc 11) | Niveau | Tests | Reste |
|---|---|---|---|
| §11.1 OFF non régressif ; demande simple sans conseil | R/E | `c1OffIsTheDefaultAndLeavesTheNormalPathUntouched` | — |
| §11.1 2 agents, 4 agents, même modèle (températures variées), modèles et fournisseurs différents | R/E | `c3FourAgentsDeliberateInParallelAndCortanaAnswersOnce`, `c3RolesRunOnTheirOwnModelsAndProviders`, `c10TheDailyCap…` (Auto réduit) | P |
| §11.1 un agent en échec, quorum, partiel | R | `c3QuorumCompletesWithOneFailureAndIsPartialWithTwo`, `c8AFailedCouncilFallsBackToTheNormalPath` | — |
| §11.1 vote, classement, hybride, juge, défi, arrêt anticipé, tours max, minorité | U/R | `c5ProtocolsAreDeterministicAndMajorityIsNeverProof`, `c5TiesAreNeverInventedAndTheJudgeIsBlindToVotes`, `c6RetentionKeepsCriticalBoundsTrafficAndProtectsTheMinority`, `c8TheJudgeSeesAnonymousCandidatesWithoutAuthorsOrVotes`, `c7EarlyStopSkipsTheDebate…`, `c5BudgetAbuseIsDegradedAndNeverExceeded` | — |
| §11.2 plafond de contexte, sortie réservée, élagage des outils, compactage, 413 plus petit, jamais identique, arguments retenus bornés | U/R | `c3Http413ShrinksTheRequestAndNeverResendsTheSamePayload`, `c6Retention…`, `c3PlannerScopesReadOnlyToolsQuorumAndProfiles` | P (limites réelles des fournisseurs) |
| §11.3 agents en lecture seule, découverte bornée, L2/L3 jamais autorisées par le conseil, contenu non fiable, STOP | R/S | `c3PlannerScopes…`, `c9ProposedActionsGoThroughTheNormalPathAndItsPolicy`, `c9InjectedWebContentIsDataAndTaintsTheTask`, `c8StopCancelsEveryAgentWithoutALateAnswer`, `c5FalseConsensusIsOverturnedByToolVerifiedEvidence` | — |
| §11.4 aucune clé dans une invite, aucun raisonnement persisté, local uniquement, sortie invalide jamais exécutée, récursion bloquée | S | `c9SecretsNeverReachAModel`, `ArchitectureRulesTest.councilNeverPersistsReasoningOrPrompts`, `c3LocalOnlyPrivacyKeepsEveryCallOnTheDevice`, `c4MalformedOutputIsRepairedOnceAndSmuggledToolCallsAreRejected`, `c1NestedCouncilsAreRefusedAndOffNeverRuns`, `ArchitectureRulesTest.councilsCannotStartCouncils` | — |
| §11.5 429, délai, fournisseur en panne, modèle indisponible, redémarrage, migration, annulation, budget épuisé | R | `c3Http429HonoursRetryAfterOnceThenFallsBack`, `c3ASlowAgentTimesOutAndTheCouncilContinues`, `c8AFailedCouncil…`, `c3LocalOnly…` (repli), `DatabaseMigrationTest.v2ToV3IsAdditiveAndTheCouncilStoreWorks` (séance interrompue close), `v1ToV3InOneUpgrade`, `c5BudgetAbuse…` | P (réseau réel) |
| §11.6 changement de mode, modèle par rôle, repli signalé, résumé, avertissement partiel, accessibilité | U/R | `c10ProgressAndSummaryAreSaidInWordsNotColours`, `c10ARoleReasoningEffortIsSentOnlyWhenTheOwnerSetsOne`, `c3RolesRun…`, `c3QuorumCompletes…` | **P** : rendu Compose, TalkBack, tablette et téléphone (`RC_CHECKLIST.md` §14) |
| §11.7–11.9 baselines et ablations | U | `CouncilGoldenSetTest.decisionBenchAndAblations` (couche de décision, déterministe) | **BLOCKED_EXTERNAL** : B0–B7 avec de vrais modèles |
| §11.10 jeu de référence ≥ 50 tâches | U | `CouncilGoldenSetTest` (52 tâches, sélecteur et décisions) | — |
| Persistance, sauvegarde, métriques, spans | R | `c3FourAgents…` (lignes, diagnostic sans invite, métriques, spans), `BackupTest`, `c8Stop…` (séance `cancelled`) | — |
| Lois d'architecture du conseil | S | `ArchitectureRulesTest` (4 lois du conseil) | — |

## 14 septies. Chat Workspace (2.0.0-rc4, D-20260930-068)

Correspondance avec `chat_workspace_pack/14_ACCEPTANCE_TEST_MATRIX.md`. Niveaux : U unitaire JVM, R Robolectric sur le conteneur réel contre un fournisseur scripté, C Compose sous Robolectric, S loi statique, P appareil.

| Domaine (doc 14) | Niveau | Tests | Reste |
|---|---|---|---|
| Conversation : créer, renommer, archiver, épingler, supprimer, persistance | R/C | `ChatTreeTest.h1DeletingAConversationRemovesItsWorkspaceState`, `ChatWorkspaceFeaturesTest.h5Delete…` et `WorkspaceUiTest.h5Deleting…` (message et suite, confirmation), `WorkspaceUiTest`, `ChatWorkspaceFeaturesTest.h10Search…` (épinglées) | P |
| Composer : brouillon, pièces jointes multiples, file, réordonner, annuler, dictée | R/C | `WorkspaceUiTest.h3ComposerSendsKeepsTheDraftAndShowsTheAnswer`, `ChatWorkspaceServiceTest.h3…` (file, revalidation, pièces jointes) | P : collage d'image, dictée réelle |
| Messages : Markdown, code long, tableaux, maths, Mermaid, citations, malformé, énorme | U/C | `ChatRenderLogicTest` (8), `WorkspaceUiTest.h4RichAnswer…` | — |
| Versions : éditer = branche, ancienne branche gardée, variantes, continuer, dupliquer | R/C | `ChatTreeTest.h5…`, `ChatWorkspaceServiceTest.h5…`, `h6StopKeepsThePartialAnswerAndContinueExtendsIt`, `WorkspaceUiTest.h5…` | — |
| Flux : coupure réseau, reprise, événement dupliqué, désordonné, STOP, redémarrage, délai | R/U | `ChatHardeningTest.h12NetworkDrop…`, `h12ProviderFallback…`, `h12DuplicateAndOutOfOrder…`, `ChatWorkspaceServiceTest.h6…`, `ChatTreeTest.h6…` | P : réseau réellement instable |
| Contexte : épingle, compactage, résumé inspectable, plafond, reprise 413, élagage des outils | R | `ChatWorkspaceFeaturesTest.h7…`, `ChatWorkspaceServiceTest.h6ContextTooLarge…`, `ChatHardeningTest.h12FiveThousand…` (fenêtre budgétée) | — |
| Fichiers : envoi, échec de lecture, retrait, non pris en charge, énorme, nom ou contenu malveillant | R | `ChatWorkspaceServiceTest.h3Attachments…`, `ChatHardeningTest.h12HostileFileNames…` | — |
| Outils : événement, approbation L2, refus, L3, injection, STOP d'outil | R/S | tests existants de l'orchestrateur, `ArchitectureRulesTest.workspaceNeverApprovesAnActionItself` | P : carte d'approbation sur l'appareil |
| Artefacts : créer, mettre à jour, versionner, comparer, exporter | R/U | `ChatWorkspaceFeaturesTest.h8…`, `h10Export…` | P : export réel |
| Comparaison : 2 modèles, 4 modèles, un échec, fusion, choix, onglets téléphone | R/C | `ChatWorkspaceFeaturesTest.h9TwoModels…`, `h9FourModelsAndOneLaneStopped…` | P : onglets sur téléphone réel |
| Conseil : pont, progression, réponse, annulation | U/R | `ChatWorkspaceFeaturesTest.h9CouncilBridge…`, `CouncilTest` | — |
| Recherche : message, fichier, filtre projet, saut au message | R | `ChatWorkspaceFeaturesTest.h10SearchFindsMessagesAndTitlesWithFiltersAndJumps` | — |
| Accessibilité : TalkBack, focus, texte dynamique, contraste, clavier | C | `WorkspaceUiTest.a11y…` (titres, libellés, grande taille, contraste), `h11KeyboardShortcuts…` | **P** : TalkBack réel |
| Voix : interruption tactile, micro indisponible | R | `VoiceTest.touchInterruptSilencesCortanaAndListensAgain` | P |
| Performance : 5 000 messages, 100 conversations, défilement virtualisé, rendu p95, fuite mémoire | R | `ChatHardeningTest.h12FiveThousandMessagesAndAHundredConversationsStayFast` (fenêtre 56 ms, fil 59 ms, aperçus 62 ms, recherche 47 ms, contexte 173 ms sur la machine de construction) | **P** : p95 de rendu et fuite mémoire sur l'appareil |
| Sécurité : HTML brut, URI JavaScript, injection par un outil, secrets, `file://` | U/R/S | `ChatRenderLogicTest.h4LinksAreSanitised`, `h4Inlines…`, `ChatWorkspaceFeaturesTest.h10Export…`, `h3SharedText…`, `ArchitectureRulesTest` (WORKSPACE-1 à 3) | — |

## 15. Performances

Toutes les mesures exigent la tablette : **non exécuté**.
