# Cortana — journal des décisions

Les décisions VNext suivent le format imposé par `docs/vnext_pack/09` (D-YYYYMMDD-NNN).
L'historique des décisions de la version 1.2.0 est conservé en bas de fichier.

## VNext

### D-20260927-001 — Runtime VNext en Kotlin sur la tablette (pas de Core Node/TypeScript)
Status: accepted
Problem: `02_FULL_TARGET_BLUEPRINT.md` §2.1 décrit un Core Node.js/TypeScript/Fastify/Kysely avec Room réduit à un cache client. La décision verrouillée L-1 de la v1.2 (tout s'exécute sur la Galaxy Tab A11, un seul APK, aucun serveur) et les documents 00, 01, 03–07 demandent au contraire de faire évoluer l'app Kotlin existante sans réécriture.
Options considered: (a) Core Node/TS sur un PC + app Android réduite à un client ; (b) Node embarqué dans l'APK (nodejs-mobile) ; (c) garder le runtime Kotlin sur l'appareil et y appliquer les invariants du blueprint.
Decision: (c). Le runtime (orchestrateur, mémoire, politique, planificateur…) reste dans l'app Kotlin ; les invariants (propriétaire unique, contrats versionnés, machine d'état, ledger, outbox, lois d'architecture) sont repris tels quels. Les calculs lourds (build, tests, git lourd) vont vers un worker appairé (phase 12) qui ne contient aucune logique métier.
Why: la clause « unless a platform requirement makes a component impossible » s'applique : (a) casse L-1 et la non-régression (hiérarchie §3 > §4 de `00`) ; (b) ajoute ~40 Mo, un second runtime et un second propriétaire de l'état. (c) conserve 1.2.0 et respecte « pas de réécriture totale ».
Compatibility impact: aucun ; même package, même signature, même base (migrée).
Security impact: aucune surface réseau entrante sur la tablette ; le worker est un pair authentifié explicitement.
Data migration: Room reste la base canonique (migration 1→2, D-20260927-003).
Tests: suite 1.2.0 inchangée et verte ; lois d'architecture en tests (D-20260927-011).
Rollback: sans objet (aucun composant supprimé).

### D-20260927-002 — Contrats canoniques dans un module Kotlin pur `:contracts`
Status: accepted
Problem: le blueprint prévoit TypeBox/Ajv. Il faut une source unique de contrats partagée par l'app et le worker.
Options considered: JSON Schema écrits à la main ; TypeBox côté worker + copie Kotlin ; module Kotlin/JVM `kotlinx.serialization`.
Decision: module `:contracts` (Kotlin/JVM, sans dépendance Android), `schemaVersion` obligatoire, décodage strict (`contract.malformed`, `contract.version_missing`, `contract.version_unsupported`, `contract.invalid`), aller-retour testé.
Why: une seule définition compilée, utilisée par l'app et par le worker JVM ; pas de dérive entre deux copies.
Compatibility impact: aucun ; les anciens JSON internes restent lisibles (champs par défaut).
Security impact: rejet des charges sans version ou de version inconnue.
Data migration: aucune.
Tests: `ContractsTest` (5).
Rollback: supprimer la dépendance `:contracts` (aucune donnée persistée n'en dépend).

### D-20260927-003 — Migration Room 1→2 explicite, avec sauvegarde préalable et traduction des états hérités
Status: accepted
Problem: la phase 2 ajoute les tables du runtime durable ; certains états v1 (`limit`, `waiting_user`, `running`) n'ont pas d'équivalent exact.
Options considered: migration automatique Room (`AutoMigration`) ; migration SQL manuelle ; destructive (interdite).
Decision: `Migrations.MIGRATION_1_2` écrite à la main, SQL identique au schéma exporté `2.json` ; copie de la base avant migration (`files/backups/pre-migration`, 3 dernières). Correspondance : `limit` → `failed` (`budget_exhausted:`), `waiting_user` → `completed` (`question_asked_v1:`, la question a été posée et la conversation continue), `running` → `interrupted` ; un événement synthétique `mig-` par tâche ; les appels d'outils OK avec clé sont copiés dans le ledger.
Why: une AutoMigration ne sait ni traduire les états ni remplir le ledger ; la sauvegarde permet un retour arrière manuel.
Compatibility impact: toutes les lignes v1 conservées ; un v1 `waiting_user` ne reprend pas automatiquement (la v1 n'enregistrait pas de point de reprise).
Security impact: la sauvegarde reste dans le stockage privé de l'app.
Data migration: voir `docs/DATA_MIGRATIONS.md`.
Tests: `DatabaseMigrationTest` (2) — base v1 réaliste migrée et validée contre le schéma exporté, FTS reconstruit.
Rollback: restaurer la copie `pre-migration` avec la 1.2.0 (même signature).

### D-20260927-004 — TaskStateMachine propriétaire unique des transitions
Status: accepted
Problem: la 1.2.0 écrivait `task.state` à plusieurs endroits de `Orchestrator.kt`.
Options considered: garder les écritures directes ; machine d'état dans le paquet orchestrateur, table de transitions dans `:contracts`.
Decision: `TaskStateMachine.transition()` est le seul chemin (transaction Room + `task_events` + raison de fin) ; table `TaskTransitions` dans `:contracts` ; bail (`leaseOwner`, 120 s) pour détecter les tâches orphelines d'un processus mort.
Why: invariant « aucune transition sauvage » ; historique complet exploitable par la reprise.
Compatibility impact: `limit` hérité affiché « limite atteinte ».
Security impact: aucun.
Data migration: colonnes v2 (D-20260927-003).
Tests: `RuntimeTest.stateMachineRejectsIllegalTransitionsAndAuditsEveryChange`, LAW-002.
Rollback: revenir au commit précédent (la base v2 reste lisible).

### D-20260927-005 — Stratégies de plan : INTERACTIVE par défaut, DAG sur détection multi-étapes, synthèse finale
Status: accepted
Problem: un plan structuré systématique coûterait un appel modèle de plus pour chaque question simple.
Options considered: toujours planifier ; ne jamais planifier ; routage déterministe (IntentRouter) + mode réglable.
Decision: `IntentRouter` (règles déterministes) choisit FAST_PATH, DIRECT (pas d'outils), INTERACTIVE (boucle ReAct, un pas) ou DAG (plan JSON validé contre `PLAN_SCHEMA`, `PlanValidator` : ids uniques, dépendances connues, pas de cycle, ≤ `maxPlanSteps`). Un plan DAG se termine par un appel de synthèse. Réglage `planningMode` = auto | always | never.
Why: même comportement que 1.2.0 pour les demandes simples (E2E 1.2.0 inchangés), planification réelle pour les demandes composées.
Compatibility impact: aucun pour les demandes simples.
Security impact: le plan ne donne aucun droit ; chaque étape passe par le dispatcher.
Data migration: table `plans` (versionnée).
Tests: `RuntimeTest.dagPlanRunsStepsVerifiesThenSynthesizes`.
Rollback: `planningMode = never`.

### D-20260927-006 — Vérificateur déterministe d'abord, jugement du modèle ensuite (optionnel)
Status: accepted
Problem: « le modèle dit que c'est fait » n'est pas une preuve.
Options considered: vérification par le modèle seul ; contrôles déterministes seuls ; les deux, déterministe prioritaire.
Decision: `Verifier.verifyStep` évalue d'abord les `OutcomeCheck` (tool_succeeded, text_contains, …) ; si aucun contrôle ne tranche et que `modelVerification` est actif, jugement structuré validé par schéma (`completeStructured`, réparation bornée).
Why: le faux succès est rattrapé sans dépendre de la qualité du modèle.
Compatibility impact: aucun.
Security impact: la sortie du modèle vérificateur est validée par schéma.
Data migration: aucune.
Tests: `RuntimeTest.falseSuccessIsCaughtRetriedThenReplanned`.
Rollback: `modelVerification = false`.

### D-20260927-007 — Échelle de récupération bornée avec détection de répétition
Status: accepted
Problem: éviter les boucles infinies et les réessais aveugles.
Options considered: nombre fixe de réessais ; échelle retry → replan → ask user → fail.
Decision: `RecoveryEngine.decide` : réessai si la politique de l'étape le permet et que l'erreur n'est pas identique à la précédente (signature d'erreur), puis replanification (≤ `maxReplans`), puis question au propriétaire pour les erreurs d'autorisation, sinon échec structuré.
Why: conforme à doc 04 §6 ; borné par budget.
Compatibility impact: aucun.
Security impact: jamais de réessai d'un effet non idempotent sans ledger.
Data migration: aucune.
Tests: `RuntimeTest.falseSuccessIsCaughtRetriedThenReplanned`.
Rollback: `maxReplans = 0`.

### D-20260927-008 — Reprise au démarrage : une seule reprise automatique, effets incertains → propriétaire
Status: accepted
Problem: `markInterruptedOnStartup` (1.2.0) marquait tout en échec ; doc 04 §7 exige une reprise vérifiée.
Options considered: tout reprendre ; ne rien reprendre ; inspection point de reprise + ledger.
Decision: au démarrage, chaque tâche orpheline passe INTERRUPTED puis : pas de point de reprise → FAILED `interrupted_no_checkpoint` ; point illisible → WAITING_USER ; entrée du ledger `started`/`unknown` → `reconcile` de l'outil (vrai = effet avéré, faux = jamais eu lieu, null = inconnu) et, si inconnu, WAITING_USER avec message explicite ; STOP actif → HALTED ; sinon reprise automatique (une tâche, `autoResumeTasks`), les autres attendent « continue ». WAITING_USER reprend sur le message suivant de la même session (fenêtre 24 h).
Why: jamais de double effet, jamais de relance aveugle.
Compatibility impact: plus de perte silencieuse de tâche.
Security impact: STOP respecté au redémarrage.
Data migration: aucune.
Tests: `RuntimeTest` crash/resume, effet incertain, effet durable réconcilié, tâche héritée sans point de reprise, ask_user.
Rollback: `autoResumeTasks = false`.

### D-20260927-009 — Autorisations permanentes dans une table `grants` (portée, révocation, usage)
Status: accepted
Problem: la 1.2.0 stockait les « toujours autoriser » comme liste de capacités dans les réglages, sans portée ni compteur.
Options considered: garder la liste ; table dédiée.
Decision: `GrantService` + table `grants` (capacité, portée/destination, expiration, usages, révocation) ; migration unique des anciennes entrées au démarrage (`migrateLegacySettingGrants`) ; la décision de politique porte `grantId` ; les autorisations L2 non contaminées seulement.
Why: portée précise et révocation auditée (doc 06 §6).
Compatibility impact: les anciennes autorisations sont reprises à l'identique.
Security impact: jamais d'autorisation permanente pour L3 ni en tâche contaminée.
Data migration: réglage `grants` → table, puis réglage vidé.
Tests: `RuntimeTest.scopedGrantSkipsApprovalUntilRevoked`.
Rollback: révoquer depuis Réglages.

### D-20260927-010 — Outbox pour les effets durables (notifications au propriétaire)
Status: accepted
Problem: un crash entre l'exécution d'un effet et l'écriture du ledger rend l'effet incertain.
Options considered: réessayer (risque de doublon) ; demander systématiquement au propriétaire ; outbox transactionnelle.
Decision: `Outbox` (table `outbox`, clé de déduplication unique = clé du ledger) ; `notify.owner` enregistre puis livre ; livraison réessayée avec backoff exponentiel (30 s → …, 6 tentatives), échec audité ; `reconcile` = « la ligne existe dans l'outbox ». Vidage au démarrage et dans la maintenance périodique.
Why: l'effet devient vérifiable après crash ; aucune notification en double.
Compatibility impact: la notification est identique pour le propriétaire.
Security impact: la charge utile est expurgée dans les erreurs.
Data migration: table créée par la migration 1→2.
Tests: `RuntimeTest.outboxDeduplicatesRetriesWithBackoffThenGivesUp`, `durableEffectRecordedBeforeCrashIsReconciledNotRepeated`, `repeatedNonIdempotentCallInOneTaskIsSkipped`.
Rollback: revenir à l'appel direct (sans perte de données).

### D-20260927-011 — Lois d'architecture vérifiées par des tests qui lisent les sources
Status: accepted
Problem: « un seul propriétaire par responsabilité » doit être exécutoire, pas seulement documenté.
Options considered: ArchUnit ; règles Detekt personnalisées ; tests JUnit qui analysent les sources.
Decision: `ArchitectureRulesTest` : LAW-001 (seul `core/model` appelle les modèles), LAW-002 (seul `core/orchestrator` change l'état des tâches), LAW-003/004 (seul le dispatcher exécute un outil), LAW-005 (seul `core/memory` écrit la mémoire), LAW-006 (le scheduler n'exécute ni outil ni modèle), LAW-009 (l'UI ne touche pas la base), pas de GlobalScope, pas de migration destructive, `runBlocking` limité au préchauffage documenté des réglages, identifiants de capacités uniques (LAW-020).
Why: aucune dépendance supplémentaire, exécuté à chaque build de test ; a déjà trouvé une vraie violation (UI → `c.db`).
Compatibility impact: aucun.
Security impact: empêche le contournement du dispatcher.
Data migration: aucune.
Tests: `ArchitectureRulesTest` (9).
Rollback: sans objet.

### D-20260927-012 — Injection manuelle conservée ; `runBlocking` unique au préchauffage des réglages
Status: accepted
Problem: le pack demande « pas de blocage du thread principal ». `SettingsRepository.loadBlocking()` lit les réglages une fois à la création du conteneur.
Options considered: Hilt ; chargement asynchrone avec valeurs par défaut temporaires ; préchauffage bloquant unique.
Decision: garder l'injection manuelle (`AppContainer`, D-DI) et le seul `runBlocking` de préchauffage (une petite table, avant tout écran), contrôlé par un test d'architecture.
Why: des réglages par défaut temporaires pourraient désactiver STOP ou la politique pendant quelques millisecondes ; c'est plus dangereux qu'une lecture de quelques Ko.
Compatibility impact: aucun.
Security impact: STOP et la politique sont toujours chargés avant la première action.
Data migration: aucune.
Tests: `ArchitectureRulesTest.runBlockingOnlyForTheDocumentedSettingsWarmUp`.
Rollback: sans objet.

### D-20260927-013 — Un seul schéma v2 pour la publication VNext
Status: accepted
Problem: les phases 6 à 30 ajoutent des tables. Une version Room par phase imposerait une chaîne de migrations et plusieurs sauvegardes à chaque mise à jour, pour des versions jamais publiées.
Options considered: une version par phase (v2, v3…) ; un seul schéma v2 étendu tant que VNext n'est pas publiée.
Decision: la seule version publiée est v1 (1.2.0). Tant que VNext n'est pas publiée, chaque table nouvelle est ajoutée à `MIGRATION_1_2` avec le SQL exact du schéma exporté `2.json` ; `DatabaseMigrationTest` valide le tout (`runMigrationsAndValidate`). Dès la publication, v2 est figée et toute évolution passe par v3.
Why: une seule migration et une seule sauvegarde pour le propriétaire ; la validation par schéma exporté détecte tout écart.
Compatibility impact: aucun pour la 1.2.0 → VNext.
Security impact: aucun.
Data migration: `docs/DATA_MIGRATIONS.md` (liste tenue à jour).
Tests: `DatabaseMigrationTest`.
Rollback: copie `pre-migration`.
Suite : v2 figé à la publication de la 2.0.0-rc1 (D-20260928-064, `ReleaseTest.releasedSchemasAreFrozen`).

### D-20260927-014 — Découverte dynamique des outils ; la boîte à outils de session reste la frontière
Status: accepted
Problem: envoyer ~45 définitions d'outils à chaque appel coûte du contexte (et beaucoup plus avec les phases suivantes), et le pack demande de ne pas injecter tous les outils.
Options considered: tout envoyer ; sous-ensemble strict (tout outil non proposé refusé) ; sous-ensemble + `tools.discover` + découverte implicite dans la boîte à outils de la session.
Decision: `CapabilityMatcher` propose un noyau (`ask_user`, `tools.discover`, `memory.search`, `memory.save`), les capacités requises par l'étape, les capacités déjà découvertes, les meilleures correspondances lexicales (`ToolDiscovery`, racinisation française légère, tags) et les catégories détectées par `IntentRouter`, dans la limite de `maxToolsOffered` (24) et de 30 % de la fenêtre utile (`ContextEngine.fitTools`). Le modèle peut appeler `tools.discover` ; les outils trouvés sont proposés dès l'appel suivant et conservés dans le carnet (`activeCapabilities`) pour la reprise. Hors étapes DAG à capacités déclarées (strictes), l'appel d'un outil de la boîte à outils non proposé est une découverte implicite : il passe par la politique complète. Rien n'est jamais accessible hors de la boîte à outils de la session.
Why: la boîte à outils choisie par le propriétaire est la frontière de sécurité ; le sous-ensemble est une optimisation de contexte. Refuser un outil légitime coûterait deux allers-retours et casserait des conversations 1.2.0.
Compatibility impact: le test 1.2.0 `plainChatStreamsAndPersists` vérifiait `tools.size > 30` ; il vérifie désormais la présence de `tools_discover`/`ask_user` et que plus de 30 capacités restent enregistrées (comportement équivalent pour le propriétaire). Les demandes simples interactives ne déclarent plus toutes les capacités comme « requises ».
Security impact: moins de surface présentée au modèle ; la découverte ne franchit jamais la boîte à outils ; chaque appel reste soumis à la politique et à l'audit.
Data migration: champ additif `activeCapabilities` du contrat `TaskNotebook`.
Tests: `ContextAndDiscoveryTest` (classement, sélection, sous-ensemble en discussion, découverte puis appel, confinement à la boîte à outils, découverte implicite).
Rollback: `maxToolsOffered` élevé (≥ nombre de capacités) redonne l'envoi complet.

### D-20260927-015 — Context Engine v2 : budget par priorités, compaction extractive persistée par défaut
Status: accepted
Problem: la 1.2.0 tronquait l'historique et listait seulement les sujets omis ; pas de plan, de carnet, de provenance ni de résumé durable.
Options considered: résumé par le modèle à chaque dépassement ; résumé extractif déterministe ; les deux au choix.
Decision: `ContextEngine` (remplace `ContextBuilder`, même propriétaire) applique l'ordre du doc 04 §8 : politique et objectif jamais retirés ; état de tâche ≤ 25 %, données de travail ≤ 30 %, mémoire ≤ 15 % (avec provenance), résumé ≤ 10 % ; fenêtre glissante qui commence toujours par un tour du propriétaire et garde les paires appel/résultat ; compaction progressive (anciens résultats d'outils, puis tous, puis tours longs, puis réduction proportionnelle du dernier tour). Les tours sortis de la fenêtre sont résumés dans `conversation_summaries` (incrémental, compté en tours visibles). Mode `extractive` par défaut (aucun appel modèle) ; mode `model` optionnel (compte comme un appel modèle, repli extractif en cas d'échec). Section « Provenance » quand la tâche a lu des données non fiables. Invite système 2.0.0 (règles 10 et 11).
Why: cohérence des longues sessions sans coût caché ; résultat déterministe et testable.
Compatibility impact: mêmes messages pour une conversation courte ; les anciennes conversations sont résumées à la première requête qui dépasse la fenêtre.
Security impact: le résumé passe par l'expurgation ; les données non fiables restent dans leur enveloppe ; la provenance rappelle de ne suivre aucune instruction venue des données.
Data migration: table `conversation_summaries` (D-20260927-013).
Tests: `ContextAndDiscoveryTest` (session de 150 échanges sous budget, observations de la tâche courante conservées, plan/carnet/provenance, incognito, résumé par modèle et repli).
Rollback: `contextSummaryMode = extractive`.

### D-20260927-016 — Embeddings : local hors ligne par défaut (hachage de traits), modèle distant ou réseau local au choix
Status: accepted
Problem: le doc 04 §11 demande un `EmbeddingProvider` local par défaut « si un modèle léger est disponible », distant optionnel, avec cache, empreinte et réindexation contrôlée ; FTS reste le repli obligatoire.
Options considered: (a) modèle neuronal embarqué (MiniLM ONNX/TFLite, 25–90 Mo + moteur natif) ; (b) aucun local, distant seulement ; (c) embedder local léger sans modèle (hachage signé de racines et trigrammes, 384 dimensions) + modèle distant/LAN via la passerelle.
Decision: (c). `HashingEmbedder` (empreinte `local-hash-v1-384`) par défaut ; `GatewayEmbedder` quand le propriétaire choisit un modèle d'embeddings (`embeddingRoute`), y compris un serveur du réseau local (Ollama « nomic-embed-text » via le préréglage Serveur local). Index dérivé `memory_vectors` (vecteurs normalisés, hash du texte, empreinte) ; changement d'empreinte → suppression des anciens vecteurs et réindexation par lots de 32 ; cache des requêtes (64). Aucun échec d'embedding n'empêche la recherche : FTS seul.
Why: (a) alourdit l'APK et ajoute du code natif non vérifiable ici ; (c) fonctionne hors ligne sur la tablette dès l'installation, capte flexions, accents et fautes de frappe, et laisse la sémantique fine à un vrai modèle quand le propriétaire en a un.
Compatibility impact: aucun ; la recherche 1.2.0 (FTS) reste le socle.
Security impact: en mode « local uniquement », un modèle d'embeddings distant est refusé par la passerelle.
Data migration: tables `memory_vectors` (vide, remplie au démarrage) et `memory_edges` (remplie depuis `supersedesId` par la migration).
Tests: `MemoryAndRoutingTest` (flexions hors ligne, synonymes via modèle distant, repli FTS si le modèle est en panne, réindexation au changement de modèle), `DatabaseMigrationTest` (backfill).
Rollback: `embeddingRoute = null`.

### D-20260927-017 — Classement hybride et relations de mémoire
Status: accepted
Problem: combiner FTS, similarité, récence, importance, type, provenance et graphe (doc 04 §11).
Options considered: fusion par rangs (RRF) ; somme pondérée de signaux normalisés.
Decision: somme pondérée explicable : 0,45 vecteur + 0,35 FTS (rang) + 0,08 récence (demi-vie ~60 j) + 0,07 importance + 0,15 × voisinage de graphe, ± type (profil/préférence +0,05, épisode −0,05), +0,03 si demandé explicitement ; seuil cosinus 0,25 pour une correspondance purement vectorielle ; un saut de graphe depuis les 3 meilleurs résultats (relations `same_entity` : même nom propre ou nombre ≥ 4 chiffres ; `supersedes`). Les profils et préférences restent toujours fournis en tête (comportement 1.2.0). Les signaux sont exposés (`Scored.signals`) pour le débogage.
Why: poids lisibles et testables ; RRF masque l'intensité de la similarité.
Compatibility impact: plus de rappels pertinents, mêmes souvenirs de base.
Security impact: seuls les souvenirs actifs sont renvoyés.
Data migration: aucune au-delà de D-016.
Tests: `MemoryAndRoutingTest.relatedMemoriesComeAlongThroughTheGraph`, `semanticRetrievalFindsWhatLexicalSearchCannot`.
Rollback: sans objet (FTS reste une composante).

### D-20260927-018 — Rétention, export et effacement de la mémoire
Status: accepted
Problem: « MUST retention » et « MUST export/delete ».
Options considered: suppression logique seulement ; purge physique selon des durées réglables.
Decision: purge physique (avec vecteurs et relations) des épisodes après `episodicRetentionDays` (90), des souvenirs non confirmés après `pendingRetentionDays` (30), des souvenirs oubliés après 30 j, des versions remplacées après 365 j ; au démarrage et dans la maintenance périodique, auditée. Export JSON complet avec provenance (Réglages → Données de mémoire → Exporter, via le sélecteur de documents Android). « Tout effacer » avec confirmation, audité.
Why: minimisation des données ; l'historique des éditions reste consultable un an.
Compatibility impact: les souvenirs actifs ne sont jamais purgés automatiquement.
Security impact: effacement réel (plus de copie dans l'index dérivé).
Data migration: aucune.
Tests: `retentionPurgesExpiredRecordsButKeepsFacts`, `exportCarriesProvenanceAndEraseRemovesEverything`, `incognitoSessionsLeaveNoMemoryAtAll`.
Rollback: augmenter les durées.

### D-20260927-019 — Routage des modèles (confidentialité, code, vision) et disjoncteur par fournisseur
Status: accepted
Problem: checklist Models : routage selon la confidentialité, le code et la vision ; disjoncteur/santé.
Options considered: routeur séparé ; routage dans `ModelGateway` (déjà propriétaire de la résolution de route).
Decision: `ModelGateway.resolveRoute(session, RouteNeed)` : filtre « local uniquement » (réglage global `privacyMode` ou `PrivacyLevel.LOCAL_ONLY` d'une tâche) qui ne garde que les fournisseurs locaux (préréglage Serveur local, loopback, IP privées/CGNAT/ULA/lien local, `.local`/`.lan`/`.home.arpa`/`.internal`) — y compris pour le repli ; `codingRoute` pour les tâches de code (avant le modèle de la session, car une nouvelle session recopie toujours le modèle par défaut) ; `visionRoute` ou premier modèle déclaré « vision » quand la vision est nécessaire. `ProviderHealth` : 3 échecs réessayables consécutifs → circuit ouvert 60 s, puis un essai (semi-ouvert) ; les erreurs 4xx n'ouvrent pas le circuit ; un fournisseur au circuit ouvert est évité tant qu'une alternative existe ; état visible dans Réglages.
Why: un seul propriétaire de la résolution de route ; aucune fuite vers un fournisseur distant en mode confidentiel.
Compatibility impact: sans réglage, même route qu'en 1.2.0.
Security impact: le mode confidentiel est appliqué dans la passerelle même (refus explicite d'une route distante).
Data migration: nouveaux réglages avec valeurs par défaut.
Tests: `localProviderDetection`, `localOnlyPrivacyNeverRoutesToARemoteProvider`, `codingTasksUseTheOwnersCodingModel`, `circuitBreakerOpensThenProbes`, `openCircuitIsSkippedInFavourOfTheFallback`.
Rollback: `privacyMode = standard`, routes vides.

### D-20260927-020 — Maintenance regroupée hors du scheduler
Status: accepted
Problem: la maintenance périodique (réarmement, outbox, rétention, index) vivait dans `MaintenanceWorker` du paquet scheduler ; l'indexation peut appeler un modèle d'embeddings, ce que LAW-006 interdit au scheduler.
Options considered: laisser dans le worker ; déplacer le worker (casse la tâche WorkManager déjà enregistrée par la 1.2.0) ; garder le nom de classe et déléguer.
Decision: `core/maintenance/Maintenance` (`onStartup`, `periodic`, `retention`) ; `MaintenanceWorker` garde son nom et son paquet (enregistrés par WorkManager en 1.2.0) et délègue.
Why: aucune tâche périodique orpheline après mise à jour ; LAW-006 respectée.
Compatibility impact: aucun.
Security impact: aucun.
Data migration: aucune.
Tests: suite complète ; `retentionPurgesExpiredRecordsButKeepsFacts` passe par `Maintenance.retention`.
Rollback: sans objet.

### D-20260927-021 — Skills : apprentissage de candidats, activation par le propriétaire, rejeu pas à pas par le StepRunner
Status: accepted
Problem: doc 04 §12–15 et blueprint §17 : procédures versionnées, paramétrées, pré/postconditions, rejeu sûr, invalidation, statistiques, écran, import/export ; ne jamais rejouer aveuglément de coordonnées ; le modèle ne commande jamais directement le système.
Options considered: (a) skill = macro exécutée par un exécuteur dédié ; (b) skill exposée comme outil unique qui agit elle-même ; (c) `skill.run` prépare seulement le rejeu, le StepRunner exécute chaque étape par le ToolDispatcher.
Decision: (c). `SkillService` (propriétaire unique : stockage `skills`/`skill_versions`/`skill_runs`, cycle de vie candidate → tested → active → degraded → retired, validation à blanc typée, conditions déterministes, statistiques de Laplace, 3 échecs consécutifs → dégradée et désactivée, import/export `cortana.skill` v1). `SkillLearner` (observateur de fin de tâche) : trajectoire des étapes à effet d'une tâche propre ; au 2e succès de la même suite de capacités, candidat paramétré (valeurs différentes → paramètres, secrets masqués → paramètres, numéros d'éléments d'écran → sélecteurs stables id/texte/description lus dans la dernière observation, application au premier plan → précondition) ; jamais appris d'une tâche ayant lu du web ou d'autres données non fiables (la lecture d'écran est admise) ; notification via l'outbox. Rejeu : préconditions (application au premier plan, version, capacité, contenu d'écran via `android.ui.find`) → étape via le dispatcher (politique, approbation, ledger, audit) → postconditions ; tout écart arrête le rejeu et rend la main au modèle. Les compétences actives pertinentes sont proposées dans le contexte. Import : toujours candidat désactivé, capacités vérifiées, risque recalculé.
Why: une seule boucle d'exécution (budgets, taint, autorisations) ; aucune action hors dispatcher ; l'humain décide de l'activation.
Compatibility impact: aucun comportement 1.2.0 modifié ; nouvel écran « Procédures ».
Security impact: les étapes L2/L3 demandent toujours l'autorisation au rejeu ; secrets jamais stockés ; import inerte jusqu'à validation + activation.
Data migration: tables `skills`, `skill_versions`, `skill_runs`, `skill_trajectories` (D-20260927-013).
Tests: `SkillsTest` (8) : apprentissage + paramètres + rejeu + statistiques, cible absente et dégradation après échecs répétés, application absente/mise à jour, étape sensible refusée, paramètres requis et coordonnées seules non validables, export/import versionné, sélecteurs sémantiques depuis l'écran, analyseur d'observation.
Rollback: désactiver/retirer les procédures ; aucune n'est active par défaut.

### D-20260927-022 — Workspaces dans le stockage privé de l'app ; import SAF par copie
Status: accepted
Problem: Git, patchs atomiques, builds et index exigent de vrais fichiers ; le SAF (`content://`) n'offre ni chemins, ni renommage atomique, ni liens.
Options considered: travailler directement dans l'arbre SAF ; copier le projet dans `files/workspaces/<id>` ; stocker les projets sur le worker seulement.
Decision: `WorkspaceManager` copie un dossier SAF dans le stockage privé (répertoires générés ignorés, 200 Mo max), crée ou clone (phase 10) directement dans ce stockage ; confiance `untrusted` par défaut pour tout import ou clone ; confinement de chemin (`WorkspaceFs` : canonicalisation, refus de `..`, des liens sortants et de l'écriture dans `.git`) ; verrou de mutation par tâche, expirant. Le dossier d'origine n'est jamais modifié ; l'export passe par les artefacts.
Why: seule façon d'avoir des opérations atomiques et Git fiable sur Android ; l'original de l'utilisateur est protégé.
Compatibility impact: les outils `file.*` (dossier de travail SAF 1.2.0) sont inchangés.
Security impact: le contenu d'un dépôt est une donnée non fiable (taint `workspace:<id>`), jamais une instruction.
Data migration: tables `workspaces`, `changesets`.
Tests: `DevWorkspaceTest.importedProjectIsProfiledBeforeAnyChange`, `pathsCannotEscapeTheWorkspace`.
Rollback: supprimer le projet (copie de travail uniquement).

### D-20260927-023 — PatchEngine : calcul en mémoire, application atomique multi-fichiers, ChangeSet annulable
Status: accepted
Problem: doc 03 §6 (pas de réécriture complète par défaut, préconditions de hash, diff unifié, multi-fichiers atomique, aperçu, conflits, fins de ligne/encodage, vérification, rollback).
Options considered: écrire fichier par fichier ; bibliothèque de diff externe ; moteur maison (Myers + applicateur strict).
Decision: calcul complet en mémoire (remplacement exact avec nombre d'occurrences attendu, création, écriture, renommage, diff unifié avec recherche de décalage ±40 lignes, hash attendus) ; au moindre conflit rien n'est écrit ; sinon sauvegarde hors du projet, écriture par fichier temporaire + renommage, relecture de contrôle, restauration automatique en cas d'échec ; `ChangeSet` (hash avant/après, diff exact) ; rollback exact refusé si un fichier a changé depuis (sauf forçage explicite du propriétaire). CRLF et BOM UTF-8 conservés. La suppression passe par `code.delete` (L2, approbation).
Why: aucune dépendance, comportement déterministe et entièrement testé.
Compatibility impact: aucun.
Security impact: aucune écriture hors du projet ni dans `.git` ; toute modification est auditée.
Data migration: aucune (D-013).
Tests: `DevWorkspaceTest` (aperçu/application/rollback, conflits sans effet, fins de ligne et BOM, diff unifié aller-retour et décalage, refus de rollback après travail ultérieur).
Rollback: `code.patch.rollback` / bouton « Annuler ».

### D-20260927-024 — Outils développeur canoniques, lecture de code et effets sur le carnet
Status: accepted
Problem: doc 03 §18 liste les capacités canoniques sans lecture de fichier ; il faut aussi tenir le TaskNotebook à jour (fichiers modifiés, commandes, tests).
Options considered: réutiliser `file.read` (SAF) ; ajouter `code.read` ; outils synonymes.
Decision: capacités `workspace.create|open|inspect`, `code.read|search|patch.preview|patch.apply|patch.rollback|delete`, `artifact.list|export` (catégorie DEV, boîte à outils « Complet »). `code.read` renvoie les lignes numérotées et le sha256 à utiliser comme précondition. Les outils peuvent renvoyer un objet `notebook` (fichiers, commandes, tests, révision) que le StepRunner transmet à l'orchestrateur, seul à écrire le carnet. `artifact.export` copie dans Téléchargements/Cortana via MediaStore (L2) et revérifie le hash.
Why: une seule façon de faire chaque chose ; le carnet reflète l'état réel pour la reprise.
Compatibility impact: aucun.
Security impact: `code.patch.apply` L1 (local, annulable), `code.delete` et `artifact.export` L2.
Data migration: aucune.
Tests: `DevWorkspaceTest.modelDrivenFixUpdatesTheNotebook`, `artifactsAreHashedAndVerified`.
Rollback: sans objet.

### D-20260927-025 — Git par JGit 5.13 (Java 8), hermétique, sans JMX ; worktrees par clone local lié
Status: accepted
Problem: doc 03 §7 exige lecture complète, mutations contrôlées, interdictions par défaut et worktrees ; Android n'a pas de binaire `git` et une application ne peut pas exécuter un binaire qu'elle a écrit (W^X, API 29+).
Options considered: embarquer un binaire git (impossible à exécuter depuis le stockage de l'app) ; déléguer Git au worker (indisponible hors ligne) ; JGit 6.x (Java 11, API Android incomplètes) ; JGit 5.13 (Java 8).
Decision: JGit 5.13.3 (+ JavaEWAH, slf4j-api). `GitService` : statut, diff (copie de travail, index, entre révisions), log, show, branches/tags/distants, blame, merge-base ; init, clone (https seulement, adresse privée refusée sauf réglage), branche, checkout (refusé s'il écraserait des modifications), commit (jamais d'amend), fusion locale (annulée proprement en cas de conflit ; branche protégée → L3), revert, cherry-pick, fetch, pull à stratégie explicite (ff-only par défaut), push jamais forcé. Non proposés : push --force, reset --hard, suppression de branche distante, amend, suppression de `.git`. Worktree = clone local du dépôt sur une nouvelle branche (nouveau projet lié) ; `repo.push` y renvoie la branche au projet parent (local, L1). Tout push distant : classificateur → L3 (empreinte) avec URL, branche et nombre de commits affichés. JGit est rendu hermétique (`HermeticSystemReader` : aucune config Git globale/système) et l'exposition JMX est désactivée (`WindowCacheConfig.setExposeStatsViaJmx(false)`). Identifiants par hôte : jeton dans le SecretStore, jamais vu par le modèle.
Why: seule option fonctionnant sur la tablette hors ligne ; la config hôte a déjà cassé les tests (`gpg.format=ssh`) — preuve que l'isolement est nécessaire.
Compatibility impact: APK + ~3 Mo.
Security impact: aucun raccourci destructif exposé ; publication = empreinte ; lint signale un `TrustAllX509TrustManager` dans le code de JGit (non utilisé : Cortana ne désactive jamais la vérification TLS).
Data migration: réglages `protectedBranches`, `gitCredentials`, `gitAuthorName`, `gitAuthorEmail`.
Tests: `GitTest` (7) : worktree isolé sans toucher main, lectures, refus des raccourcis destructifs, fusion en conflit annulée, push distant → L3 refusé, fusion protégée après confirmation physique, commits pilotés par le modèle. Non exécuté ici : JGit sur un vrai appareil Android (niveau P).
Rollback: retirer la dépendance JGit (les projets restent des dossiers).

### D-20260927-026 — Exécution : une interface, un gestionnaire de bac à sable qui ne promet que ce qu'il tient
Status: accepted
Problem: doc 03 §16, doc 06 §8 : backend Android local, workers, délai/arrêt, politique de fichiers et de réseau ; Android interdit d'exécuter un binaire écrit par l'app et n'offre pas d'isolation réseau par processus.
Options considered: exécuter n'importe quoi localement ; processus « isolatedProcess » Android (pas d'accès aux fichiers du projet) ; tout confier au worker ; politique par niveau de confiance.
Decision: `ExecutionBackend` (tablette, workers) + `SandboxManager` : projet en lecture seule → aucune exécution ; projet non fiable ou système → uniquement mode ISOLATED (fichiers + réseau) sur un moteur qui l'offre réellement (worker avec bubblewrap/unshare/conteneur, phase 12), sinon refus expliqué ; projet de confiance → processus simple, isolement préféré quand disponible et réseau refusé, sinon exécution signalée « réseau non isolé ». `LocalProcessBackend` : `/system/bin/sh` + toybox, dossier confiné au projet, environnement vidé (PATH/HOME/TMPDIR/LANG), `ulimit` CPU et taille de fichier, délai, arrêt de toute l'arborescence (PID écrit par le shell, descendants via /proc, `kill` intégré du shell, parent d'abord), arrêt immédiat à l'annulation de la tâche, sortie bornée à la fin. `exec.run` : trait « exécute du code » (≥ L2), journal long en artefact, commande inscrite au carnet.
Why: aucune fausse promesse d'isolement ; le propriétaire décide de la confiance.
Compatibility impact: aucun.
Security impact: un processus local d'un projet de confiance peut lire les fichiers privés de l'app : c'est pourquoi un projet non fiable n'y est jamais exécuté.
Data migration: aucune.
Tests: `ExecutionTest` (8) dont le délai tue l'arborescence (fichier témoin), l'annulation tue immédiatement, la sortie bornée, le confinement du dossier, le choix du bac à sable, `exec.run` après approbation, refus du projet non fiable. Deux défauts réels trouvés par ces tests et corrigés : ordre des `kill` et attente des lecteurs de flux à l'annulation.
Rollback: sans objet.

### D-20260927-027 — Worker appairé en Kotlin/JVM : TLS épinglé, requêtes signées par clé d'appareil, bac à sable par espaces de noms
Status: accepted
Problem: doc 03 §16-17 et doc 05 §19 : worker PC/serveur, appairage cryptographique, capacités, canal authentifié chiffré, tâches, artefacts, reconnexion, révocation — sans second cerveau.
Options considered: worker Node/TypeScript (blueprint §2) ; jeton porteur partagé ; TLS + épinglage + signature par requête ; bubblewrap/conteneur obligatoires.
Decision: module `:worker` Kotlin/JVM (JDK 17+), un jar autonome, sans dépendance hors `:contracts` (qui définit une seule fois le protocole). Identité TLS EC P-256 créée par `keytool` ; épinglage du SHA-256 du certificat ; appairage par code à usage unique (10 min, 5 essais) + preuve de possession de la clé épinglée ; chaque requête signée ECDSA par la clé de l'appareil (Android Keystore, repli logiciel signalé), horodatée, nonce anti-rejeu. Bac à sable « isolated » construit avec `unshare` (utilisateur/montage/PID/réseau) : système en lecture seule, seul le projet et un `/tmp` privé inscriptibles, dossier personnel et données du worker masqués ; « process » pour les projets de confiance. Commande transmise par script UTF-8 (jamais en argument). Synchronisation différentielle par manifeste de hachages, zip-slip bloqué, sorties de build du worker préservées. Tâches durables (résultat persisté), bail de 10 min, annulation de l'arborescence, artefacts vérifiés par SHA-256 côté tablette. Reconnexion : réessais 5 min avec délai croissant sur le même job. Révocation immédiate des deux côtés.
Why: une seule base de contrats et de langage ; pas de secret partagé rejouable ; la clé de l'appareil ne quitte pas le matériel ; isolement réel sans dépendance à installer (bubblewrap absent ici, et souvent sur les postes).
Compatibility impact: nouveau livrable `cortana-worker.jar` (+ scripts de lancement).
Security impact: voir `docs/WORKER_PROTOCOL.md` ; un worker Windows/macOS n'offre que le mode « process » → les projets non fiables y sont refusés.
Data migration: table `workers`.
Tests: `WorkerTest` (8 : identité, codes, signatures/rejeu/révocation, synchro + zip-slip + hachage, délai/annulation, artefacts, bac à sable isolé réel, format d'appairage) ; `WorkerIntegrationTest` (6 : épinglage et code à usage unique, projet non fiable exécuté isolé avec artefact vérifié, coupure réseau et reprise, artefact falsifié rejeté, révocation, appareil inconnu refusé). Défaut réel trouvé et corrigé : arguments non ASCII corrompus selon la locale → script UTF-8.
Rollback: « Oublier » le worker ; l'exécution locale reste disponible pour les projets de confiance.

### D-20260927-028 — Build, tests, diagnostics et dépendances par adaptateurs détectés ; rapports toujours rapatriés
Status: accepted
Problem: doc 03 §8-11 : adaptateurs détectables (Gradle/Android, Maven, npm/pnpm/yarn/bun, Python, Cargo, Go, CMake, générique), tests ciblés, instabilité identifiée, échec de test ≠ échec d'infrastructure, JUnit, comparaison avant/après, diagnostics unifiés, dépendances inspectées sans mise à jour aveugle.
Options considered: commandes codées dans l'orchestrateur ; adaptateurs choisis d'après le profil du dépôt.
Decision: `BuildAdapters.detect(profile)` → commande de build/test/lint + motifs d'artefacts et de rapports ; exécution par le `SandboxManager` (tablette ou worker, isolé pour les projets non fiables) ; `Diagnostics` normalise kotlinc, javac, Maven, Gradle, tsc, ESLint, gcc/clang, go, rustc, pytest, traces JVM/Python/Node, et lit le JUnit XML sans DTD (XXE refusé) ; `test` relance une seule fois les seuls tests échoués pour *identifier* l'instabilité (signalée, jamais comptée comme réussite), distingue l'échec d'infrastructure (pas de résultat) et compare avec le passage précédent (corrigés / nouveaux échecs) ; `DependencyService` : manifestes, verrous, doublons, versions non épinglées, aucune modification. Réseau coupé par défaut ; `network=allow` visible dans l'approbation. Commandes npm sans appels réseau parasites.
Why: rien de codé en dur dans l'orchestrateur ; résultats vérifiables.
Compatibility impact: aucun.
Security impact: build/test = exécution de code du projet (L2) ; les rapports d'échec sont rapatriés comme artefacts hachés.
Data migration: aucune.
Tests: `BuildTestDiagnosticsTest` (5) dont le gate : projet JavaScript non fiable réparé par le modèle sur le worker isolé sans réseau (tests en échec → correctif → tests verts avec « corrigés » → paquet vérifié). Défaut réel trouvé et corrigé : le worker ne renvoyait les artefacts (donc les rapports JUnit) qu'en cas de succès.
Rollback: sans objet.

### D-20260927-029 — Intelligence de code par fournisseurs interchangeables ; fournisseur lexical obligatoire ; renommage vérifié avant/après
Status: accepted
Problem: doc 03 §3-5 : symboles, définitions, références, graphe de dépendances, impact des changements sur les tests et refactorisation sûre, sur la tablette (pas de serveur de langage disponible sur Android) comme sur un worker.
Options considered: serveurs LSP embarqués (inexistants sur ART, lourds) ; Tree-sitter natif (JNI par langage, taille de l'APK) ; abstraction `CodeIntelligenceProvider` avec un fournisseur lexical toujours présent, LSP/Tree-sitter branchables plus tard sans changer les outils.
Decision: `CodeIntelligenceProvider` (symboles + imports par langage) ; `LexicalProvider` (Kotlin, Java, JS/TS, Python, Go, Rust, C/C++) ; `CodeIntelligence` indexe par empreinte de fichier (pas d'invalidation manuelle), références par frontière de mot **hors chaînes et commentaires**, graphe de dépendances (FQN Kotlin/Java, imports relatifs JS, modules Python, includes C, `mod`/`use crate::` Rust), tests impactés (fermeture inverse du graphe + tests qui nomment les symboles modifiés, car les tests Kotlin du même paquet n'importent rien), diagnostics statiques (imports relatifs cassés, définitions en double). Outils : `code.symbols`, `code.references`, `code.impact`, `code.diagnostics` (L0) et `code.rename` : aperçu (L1), application réversible par le `PatchEngine` (L1), ou avec `verify=tests|build` → L2 car le code du projet est exécuté avant ET après ; toute régression (nouvelle erreur statique, échec nouveau) annule automatiquement la modification ; les mentions restées dans des chaînes/commentaires sont signalées, jamais réécrites. Conflits refusés : nom existant, nom déjà utilisé dans un fichier touché, mot réservé, identifiant invalide.
Why: fonctionne partout sans dépendance native ; le renommage lexical est honnêtement limité (accès dynamiques, homonymes) — d'où la portée optionnelle (`scope`), la vérification par les tests et l'annulation automatique.
Compatibility impact: aucun (nouveaux outils ; index en mémoire, aucune table).
Security impact: lecture seule sauf `code.rename` appliqué ; l'exécution de vérification suit la politique d'exécution (worker isolé pour un projet non fiable) et demande l'approbation L2.
Data migration: aucune.
Tests: `CodeIntelligenceTest` (6) dont le gate : via l'orchestrateur, aperçu sans approbation, renommage `mul → multiply` avec tests avant/après sur le worker isolé (3 ok → 3 ok), puis renommage `add → sum` qui casse un accès dynamique `c['add']` → régression détectée, modification annulée, mention signalée.
Rollback: retirer `CodeTools` du registre.

### D-20260927-030 — Verrou de projet repris dès que la tâche qui le tient est terminée
Status: accepted
Problem: défaut trouvé en phase 14 : le verrou de modification d'un projet (doc 03 §2) n'était jamais relâché à la fin d'une tâche ; un autre travail sur le même projet était refusé pendant 30 min (expiration).
Options considered: relâcher dans l'orchestrateur à chaque fin de tâche (couple l'orchestrateur aux projets, ne couvre pas un arrêt brutal) ; reprise du verrou quand la tâche détentrice est terminale ou absente.
Decision: `WorkspaceManager.lock` reprend le verrou si la tâche détentrice est terminée (completed/failed/cancelled…) ou n'existe plus ; une tâche en cours le garde ; l'expiration reste en dernier recours.
Why: juste après une tâche terminée, le projet est libre ; après un plantage, la tâche est reprise ou terminée par la reprise sur incident.
Compatibility impact: aucun.
Security impact: deux tâches actives ne modifient toujours jamais le même projet en même temps.
Data migration: aucune.
Tests: `CodeIntelligenceTest.aProjectLockIsReleasedWhenItsTaskHasEnded`.
Rollback: revenir à l'expiration seule.

### D-20260927-031 — Liens d'entités de la mémoire indépendants de l'ordre d'indexation
Status: accepted
Problem: défaut réel trouvé par un échec intermittent de `relatedMemoriesComeAlongThroughTheGraph` : un nom propre en tête de phrase n'est pas une entité (« Julie adore… »), et le lien n'était cherché que depuis la mémoire nouvellement indexée ; si la première mémoire (« Ma sœur Julie… ») était indexée en arrière-plan avant que la seconde existe, le lien n'était jamais créé.
Options considered: réindexer tout à chaque passage (coûteux) ; traiter les mots capitalisés en tête de phrase comme entités (faux liens « Est », « Semble »…) ; lien bidirectionnel au moment de l'indexation.
Decision: à chaque passage, les entités nommées par les mémoires déjà indexées sont collectées ; une nouvelle mémoire qui les mentionne est liée à leurs détentrices (en plus du sens existant).
Why: le graphe ne dépend plus de l'ordre ni de la vitesse de l'indexation en arrière-plan.
Compatibility impact: aucun (même table `memory_edges`).
Security impact: aucun.
Data migration: aucune (les liens manquants apparaissent à la prochaine indexation des mémoires concernées).
Tests: `MemoryAndRoutingTest.entityEdgesDoNotDependOnIndexingOrder` (échoue sans le correctif, passe avec).
Rollback: revenir au lien à sens unique.

### D-20260927-032 — Fabrique logicielle : pipeline canonique, revue déterministe et porte de fin de tâche
Status: accepted
Problem: doc 03 §13-15 et 08 §6 : une tâche de développement ne doit être déclarée finie qu'après relecture du diff, vérification des tests, des secrets, de l'architecture et des migrations ; la réparation doit être bornée et ne pas boucler ; la fabrique n'est pas un second orchestrateur.
Options considered: (a) un orchestrateur « fabrique » dédié avec ses propres étapes codées ; (b) une étape de revue par le modèle ; (c) le pipeline comme consignes de planification + un vérificateur déterministe branché sur le Verifier existant + une réparation comptée par le RecoveryEngine existant.
Decision: (c). `ReviewService` (10 vérifications de doc 03 §13, verdict approved/changes_requested/blocked, rapport conservé comme artefact « review-… », lignes secrètes jamais recopiées) ; `SoftwareFactory` implémente `CompletionGate` (Verifier : sur l'étape finale d'un plan, une tâche ayant modifié du code n'est terminée que sans bloquant, dont « suite de tests complète verte **sur la révision exacte du code actuel** » grâce aux `RunRecord` de `BuildService` et à `WorkspaceManager.revision`) et `TaskExtension` (consignes du pipeline pour les demandes de développement quand un projet existe ; contrôle de reprise). `RecoveryEngine.repair` : réparations comptées à part (`maxRepairIterations`, défaut 3), chaque itération reçoit les nouveaux diagnostics, le même échec (`failureSignature`) `repairSameFailureLimit` fois → replanification (DAG, une fois) puis question au propriétaire ; la réponse explicite « termine sans vérification » accepte le travail en l'état avec les points non vérifiés dans le rapport (jamais déductible par le modèle, qui n'écrit pas les messages du propriétaire). Rapport de fin (📋) ajouté à la conversation. Outil `review.changes` (L0 ; base tâche ou Git).
Why: une seule autorité (orchestrateur), un seul vérificateur, un seul moteur de reprise ; la preuve (tests sur le code exact) prime sur l'affirmation du modèle.
Compatibility impact: une tâche qui modifie du code sans relancer les tests n'est plus « terminée » : elle est réparée puis, à défaut, attend le propriétaire (`DevWorkspaceTest.modelDrivenFixUpdatesTheNotebook` mis à jour en conséquence). Les tâches sans modification de code ne changent pas.
Security impact: bloque les secrets ajoutés, les tests affaiblis (assertions retirées, tests ignorés), les marqueurs de conflit, les migrations destructives ; signale fichiers d'instructions, CI, fichiers générés, travail sur branche protégée.
Data migration: aucune (réglages avec défaut, `VerificationResult.failureSignature` optionnel).
Tests: `SoftwareFactoryTest` (9 : E2E-CODE-001 à 006), `ReviewServiceTest` (7).
Rollback: retirer `softwareFactory` de `Verifier.gates` et d'`Orchestrator.extensions`.

### D-20260927-033 — Reprise d'une tâche de développement après crash : empreintes des fichiers, jamais d'édition en double
Status: accepted
Problem: 08 E2E-CODE-004 : après un arrêt en cours de travail, reprendre au point de reprise, vérifier l'état du dépôt, ne pas refaire une modification.
Options considered: comparer la révision complète du projet (tout changement externe bloque, même hors des fichiers de la tâche) ; comparer les fichiers touchés par la tâche aux empreintes « après » de ses ChangeSets.
Decision: la seconde. `SoftwareFactory.onResume` : fichiers identiques → notes pour le modèle (modifications déjà appliquées à ne pas refaire, révision actuelle, tests à relancer) ; fichier modifié depuis → après un crash, attente du propriétaire avec la liste ; s'il répond « continue », la reprise se fait avec la consigne de relire ces fichiers. Le ledger (`started`) est réconcilié par le ChangeSet (patchId dérivé de la clé d'idempotence) : l'effet est reconnu, jamais rejoué.
Why: ce sont les fichiers de la tâche dont dépend la suite ; un autre fichier modifié ne rend pas la reprise dangereuse.
Compatibility impact: aucun pour les tâches sans code.
Security impact: aucune écriture silencieuse sur un fichier modifié par le propriétaire pendant l'arrêt.
Data migration: aucune.
Tests: `SoftwareFactoryTest.e2eCode004ResumeAfterCrash…`, `e2eCode004ExternalChangeAfterCrash…`.
Rollback: retirer `onResume`.

### D-20260927-034 — Git par le shell soumis à la politique Git
Status: accepted
Problem: 08 E2E-CODE-006 : les outils `repo.*` n'offrent ni push forcé ni reset --hard, mais `exec.run` permettait de les contourner par le shell.
Options considered: interdire `git` dans `exec.run` (trop large : status/diff/log sont utiles) ; analyser la commande.
Decision: `GitCommandGuard` dans la classification de risque d'`exec.run` : `git push` (toute forme) et réécriture d'historique (`filter-branch`, `filter-repo`, `update-ref -d`) refusés avec renvoi vers `repo_push` ; `reset --hard/--merge/--keep`, `clean -f`, `branch -D`, abandon de modifications (`checkout -- …`, `restore`), `rebase`, `reflog expire`, `gc --prune`, `stash drop/clear` → L3 (empreinte).
Why: la protection des branches et l'interdiction du push forcé ne dépendent plus de l'outil choisi par le modèle.
Compatibility impact: aucun pour les commandes Git de lecture.
Security impact: ferme un contournement réel.
Data migration: aucune.
Tests: `SoftwareFactoryTest.gitCommandsThroughTheShellCannotBypassTheGitPolicy`, `e2eCode006…`.
Rollback: retirer l'appel au garde.

### D-20260927-035 — Classification « développement » en français, journaux et exécutions visibles, moteur par défaut
Status: accepted
Problem: le routeur ne reconnaissait pas « teste », « diff », « patch », « du code »… (une demande de développement longue n'obtenait pas de plan) ; l'espace Développement ne montrait ni tâche, ni plan, ni journaux, ni tests/builds, ni choix du moteur (doc 03 §19).
Options considered: classification par le modèle (appel supplémentaire à chaque demande) ; vocabulaire élargi mais sans termes ambigus (« code postal », « paquet »).
Decision: vocabulaire élargi (`teste[rz]`, `compilation`, `diff`, `patch`, `code source`, `du code`, `coder`, `refactori…`, `npm`, langages) ; `BuildService` publie un journal en direct et des `RunRecord` (type, statut, échecs, moteur, révision) ; onglet « Activité » : tâche en cours et son plan, Annuler, STOP, moteur par défaut (`devBackend`, sans jamais contourner l'isolation d'un projet non fiable), nombre de réparations automatiques, journal, exécutions récentes ; bouton « Relire les modifications non commitées » par projet Git.
Why: rendre la fabrique pilotable et vérifiable depuis la tablette.
Compatibility impact: davantage de demandes de développement longues sont planifiées en DAG.
Security impact: le moteur par défaut n'affaiblit pas `SandboxManager` (l'isolation par confiance reste prioritaire).
Data migration: aucune (réglages avec défaut).
Tests: `SoftwareFactoryTest.e2eCode001…` (plan DAG), suite complète ; l'interface n'est pas testée par instrumentation ici (A non exécuté).
Rollback: revenir au vocabulaire précédent.

### D-20260927-036 — OCR sur l'appareil : Tesseract (tessdata_fast fra+eng) plutôt que ML Kit
Status: accepted
Problem: doc 05 §4 « OcrProvider local avec repli » ; les captures d'écran sont les données les plus sensibles que Cortana manipule, et le mode « Local uniquement » promet qu'aucune donnée ne quitte le réseau local.
Options considered: ML Kit Text Recognition (modèle embarqué, précis, rapide) ; Tesseract4Android (Apache-2.0) ; OCR uniquement par un modèle de vision distant.
Decision: Tesseract4Android 4.9.0 (JitPack, restreint à ce seul groupe par `exclusiveContent`) avec les modèles `tessdata_fast` français et anglais (Apache-2.0) embarqués et **vérifiés par SHA-256** avant la première utilisation (copie dans le stockage privé). ML Kit a été essayé puis retiré : il embarque `datatransport` + `transport-backend-cct` (télémétrie Clearcut vers Google) et les bibliothèques Play Services, incompatibles avec la promesse « Local uniquement » et non auditables.
Why: aucune connexion réseau, aucune permission, code ouvert ; la précision de Tesseract suffit pour le texte d'interface (mode « texte épars »).
Compatibility impact: APK plus lourd d'environ 9,7 Mo (arm64 : bibliothèques natives 7 Mo, modèles 2,7 Mo compressés).
Security impact: pas de télémétrie ; modèles épinglés par empreinte (un modèle altéré désactive l'OCR, jamais de chargement silencieux).
Data migration: aucune.
Tests: la reconnaissance réelle ne s'exécute que sur l'appareil (bibliothèque native Android) : `VisionFixtureTest` (instrumenté, écrit et compilé, **non exécuté**). La logique autour de l'OCR est testée avec un OCR de fixture (`VisionTest`).
Rollback: `ocrProvider = null` (lecture visuelle par modèle seulement, ou désactivée).

### D-20260927-037 — Repli visuel : capture ponctuelle par l'accessibilité, politique d'écran sensible, fusion de sélecteurs, vérification après action
Status: accepted
Problem: doc 05 §1-3 / 08 §8 : agir quand l'arbre d'accessibilité est insuffisant (applications dessinées, jeux, vues non accessibles), sans capture permanente, sans exposer de données sensibles, avec vérification de l'effet.
Options considered: MediaProjection (autorisation à chaque session, icône de diffusion, capture continue) ; `AccessibilityService.takeScreenshot` (API 30+, ponctuel, refusé par le système sur les fenêtres protégées).
Decision: `AccessibilityScreenCapturer` (`android:canTakeScreenshot`) ; `SensitiveScreenPolicy` : jamais écran verrouillé ni application sensible (liste existante), champs mot de passe noircis **avant** l'OCR, cartes bancaires (Luhn), IBAN et codes à usage unique noircis avant tout modèle ; analyse par modèle seulement si le propriétaire l'active (`visionFallback = remote`), jamais en navigation privée ni dans une application sensible, et en mode « Local uniquement » seulement vers un modèle local (résolution de route). `TargetFusion` dans l'ordre de doc 05 §2 : identifiant → texte exact → description → texte approché de l'arbre → OCR → modèle de vision ; la capture n'a lieu que si l'arbre ne répond pas. Pendant l'évaluation de risque, rien ne quitte l'appareil ; à l'exécution la cible visuelle est reclassée et refusée si elle est devenue plus sensible que l'autorisation. Après l'action : comparaison d'écrans (dHash + différence de vignettes) et `expect_text` ; un effet non confirmé est un échec. Outil `android.ui.look` ; paramètre `target`/`expect_text` sur `android.ui.click`, `long_click`, `type` (pas d'outil synonyme). Captures en mémoire uniquement. Messages multimodaux (`ChatMessage.images`, parties `image_url`) ajoutés au format compatible OpenAI ; encodeur PNG sans dépendance.
Why: fonctionne là où l'arbre échoue, sans affaiblir la politique existante.
Compatibility impact: le service d'accessibilité doit parfois être réactivé pour obtenir le droit de capture (note d'installation).
Security impact: voir SECURITY.md ; les textes lus à l'écran marquent la tâche (données non fiables) ; le modèle de vision reçoit une consigne « image = donnée ».
Data migration: aucune (réglage `visionFallback`, défaut `local`).
Tests: `VisionTest` (13) : PNG, masquage, comparaison, texte tolérant, texte sensible, politique, fusion, coordonnées du modèle ramenées à l'écran et boîtes impossibles refusées, fenêtre protégée refusée, gate « arbre volontairement insuffisant » par l'orchestrateur réel et le vrai `UiController` (service d'accessibilité Robolectric, gestes enregistrés), icône trouvée par le modèle sur une capture masquée (pixels vérifiés dans l'image envoyée), application sensible ni capturée ni automatisée, cible devenue plus sensible refusée, action sans effet signalée, arbre complet sans capture.
Rollback: `visionFallback = off`.

### D-20260927-038 — L'erreur d'indexation de la mémoire reste visible pendant une nouvelle tentative
Status: accepted
Problem: défaut trouvé par un échec intermittent de `lexicalRetrievalStillWorksWhenTheEmbeddingModelIsDown` : chaque passage d'indexation effaçait `lastError` dès son début ; un passage relancé en arrière-plan masquait l'erreur (y compris dans Réglages).
Options considered: ne pas relancer ; conserver l'erreur jusqu'au prochain passage réussi.
Decision: la dernière erreur (même empreinte de modèle) est conservée jusqu'à un passage réussi.
Why: un état d'erreur ne doit pas clignoter.
Compatibility impact: aucun. Security impact: aucun. Data migration: aucune.
Tests: `MemoryAndRoutingTest.anIndexingErrorStaysVisibleWhileARetryIsRunning` (échoue sans le correctif, passe avec).
Rollback: sans objet.

### D-20260927-039 — Voix VNext : boucle vocale explicite et visible, moteurs interchangeables, lecture en continu, interruption
Status: accepted
Problem: doc 05 §5 : push-to-talk conservé ; mot d'éveil optionnel ; VAD ; STT en continu si possible ; TTS en continu ; interruption par la parole ; choix local/distant, voix, langue ; mode mains libres explicite ; indicateur micro visible ; aucune écoute cachée.
Options considered: moteur de mot d'éveil dédié (Porcupine : clé et licence propriétaires ; openWakeWord : runtime ONNX et modèles supplémentaires) ; écoute permanente en arrière-plan ; mot d'éveil reconnu sur les transcriptions pendant un mode mains libres visible.
Decision: `SttProvider`/`TtsProvider` avec moteurs tablette (`SpeechRecognizer` sur l'appareil quand Android l'offre, résultats partiels ; `TextToSpeech`, voix hors ligne préférée) et fournisseur (transcription de type Whisper et synthèse `/audio/speech`, par le `ModelGateway` : LAW-001, route choisie par le propriétaire, « Local uniquement » respecté). `Vad` à plancher de bruit adaptatif ; `RemoteSttProvider` n'envoie que l'énoncé (pré-roll + parole + fin), jamais conservé. `SentenceChunker` : la réponse est lue phrase par phrase pendant qu'elle est produite (markdown et code non lus). `VoiceLoop` : n'écoute que sur action explicite (🎤, 🗣️ mains libres) ; mot d'éveil uniquement dans ce mode ; phrase d'arrêt (« arrête d'écouter ») jamais envoyée au modèle ; interruption : pendant la lecture en mains libres, un détecteur de parole à seuil relevé (écho) coupe la voix et rouvre l'écoute ; STOP, inactivité (réglable) et notification « Arrêter l'écoute » ferment le micro. État visible sur tous les écrans (barre rouge micro ouvert) ; service de premier plan de type `microphone` pour toute la session (notification) ; journal d'audit (début, interruption, fin). La sortie vocale est liée à l'identifiant exact de la requête : une réponse interrompue n'est jamais relue ; la requête suivante attend jusqu'à 15 s que la précédente se termine plutôt que d'être perdue. Les tâches vocales ne sont pas relues une seconde fois par la lecture 1.2.0.
Why: mains libres utile sans écoute cachée ni moteur propriétaire ; un seul orchestrateur.
Compatibility impact: bouton 🎤 (reconnaissance système) inchangé ; réglage « Lire les réponses » inchangé pour le texte.
Security impact: micro jamais ouvert sans action et indicateur ; audio distant seulement si le propriétaire le choisit ; permission `FOREGROUND_SERVICE_MICROPHONE`.
Data migration: aucune (réglages avec défaut).
Tests: `VoiceTest` (9) : VAD (début, fin, écho), WAV, découpage en phrases, mot d'éveil et phrase d'arrêt, transcription distante (seul l'énoncé est envoyé, micro refermé, délai sans parole), synthèse distante (ordre, arrêt vide la file), reconnaissance Android (partiels puis texte, via le shadow Robolectric), gate mains libres par l'orchestrateur réel (rien avant l'action, phrase non adressée ignorée, lecture en continu, interruption par un vrai VAD, question suivante, arrêt vocal, STOP, pas d'écoute sous STOP), arrêt sur inactivité.
Rollback: retirer le bouton 🗣️ ; moteurs « Tablette ».

### D-20260927-040 — Communications : lectures libres, tout effet visible par un tiers prévisualisé et approuvé, matériel vérifié
Status: accepted
Problem: doc 05 §6-9 / roadmap phase 18 : contacts, téléphone, SMS « selon matériel », agenda (fuseaux, répétitions, rappels, conflits), notifications (SHOULD), presse-papiers ; gate « lectures + approbations des effets ».
Options considered: passer par l'automatisation d'écran des applications (fragile, non vérifiable) ; fournisseurs de contenu Android derrière des interfaces, avec une politique par capacité.
Decision: `ContactsStore`, `CalendarStore`, `Telephony`, `ClipboardAccess` (implémentations `ContactsContract`, `CalendarContract` — DURATION pour les répétitions, journée entière en UTC, rappels, invités —, `SmsManager`, `ClipboardManager`). Capacités (noms de doc 05) : `contacts.search/read` (L0, données personnelles), `contact.create` (L2), `phone.call.prepare` et `sms.compose` (L1 : le propriétaire appuie lui-même), `phone.call.start` et `sms.send` (L2 vers un contact, L3 vers un numéro inconnu, refus des numéros courts et d'urgence, refus sans téléphonie, nom → un seul contact sinon refus « précise »), aperçu destinataire + texte dans l'approbation (liée aux arguments exacts) ; `calendar.list`, `calendar.events.search` (L0), `calendar.event.create` (L1, L2 avec invités, conflits signalés), `update`/`delete` (L2, copie conservée en artefact) ; RRULE validée, dates locales DST-correctes ; `clipboard.read` (jamais un contenu marqué sensible ou ressemblant à un secret ; Android ne le permet qu'au premier plan) et `clipboard.write` (secret marqué sensible, effacé après 60 s s'il n'a pas été remplacé). Téléphonie déclarée facultative (`uses-feature required=false`) : l'application s'installe sur une tablette Wi-Fi. Permissions demandées une à une depuis Santé → Communications.
Why: fiable, vérifiable, politique uniforme.
Compatibility impact: nouvelles permissions à l'exécution (aucune accordée automatiquement).
Security impact: aucun envoi, appel ou réponse sans aperçu et accord ; numéros inconnus en L3 ; pas de SMS surtaxés ni d'appel d'urgence automatiques.
Data migration: aucune (réglages avec défaut).
Tests: `CommsTest` (8), dont le gate : lectures sans approbation, événement réversible sans approbation avec conflit signalé, SMS à un contact approuvé avec aperçu, SMS à un inconnu en L3 refusé, appel refusé faute de téléphonie ; destinataires ambigus, courts et d'urgence refusés avant tout effet.
Rollback: retirer `CommsTools` du registre.

### D-20260927-041 — Notifications : liste blanche d'applications, contenu seulement vers un modèle local (ou sur autorisation), déclencheurs contaminés
Status: accepted
Problem: doc 05 §8 : lire les notifications autorisées, filtrer par application, convertir en déclencheurs, réponse directe seulement sur autorisation, contenu marqué non fiable, jamais vers un modèle distant sans politique adaptée.
Options considered: tout lire et filtrer à la demande ; ne garder que les applications autorisées dès l'arrivée.
Decision: `NotificationHub` : seules les notifications des applications cochées par le propriétaire sont gardées (en mémoire, 200 dernières) ; les autres sont ignorées à l'arrivée (seul le nom de l'application est noté pour la liste de Réglages). `notifications.list` renvoie le texte seulement si le modèle de la tâche est local (tablette/réseau local) ou si le propriétaire l'a autorisé ; codes à usage unique, cartes et IBAN masqués dans tous les cas ; résultat marqué non fiable. `notifications.reply` (L2, aperçu, action de réponse de la notification). Déclencheurs (`notification.trigger.*`) : au plus un par minute ; la tâche lancée est contaminée dès le départ et reçoit le contenu dans une enveloppe de données (`untrusted_content`), partagé selon la même règle (route de la tâche résolue). Les notifications de Cortana elle-même sont ignorées (pas de boucle).
Why: utile sans exfiltration ni injection.
Compatibility impact: aucun. Security impact: voir ci-dessus. Data migration: aucune.
Tests: `CommsTest.notificationHub…`, `notificationTriggersStartTaintedTasks…` (injection dans une notification : tâche contaminée, contenu dans l'enveloppe), `notificationContentIsHiddenFromANonLocalModel…` (masquage, réponse approuvée avec aperçu). Défaut trouvé : un code suivi d'une ponctuation échappait au masquage mot à mot et faisait masquer tout le message — remplacé par un masquage ciblé (`SensitiveText.mask`).
Rollback: vider la liste blanche.

### D-20260927-042 — Navigateur interactif : moteur HTTP avec vrai analyseur HTML, session isolée par tâche, politique par envoi
Status: accepted
Problem: doc 05 §11 / roadmap phase 19 : ouvrir une page, instantané DOM, clic/saisie/sélection, navigation/retour, téléchargement, envoi de fichier après approbation, cookies/session isolés, onglets, condition d'attente, capture, export du texte, provenance ; gate « formulaire non sensible automatisé ». La recherche HTTP existante (`web.search`/`web.fetch`) reste la voie rapide.
Options considered: (a) WebView cachée pilotée par JavaScript (exécute le code des sites dans le processus de Cortana, non testable hors appareil) ; (b) Chrome piloté par l'accessibilité (existe déjà via `android_ui_*` + repli visuel de la phase 16, lent et fragile pour des formulaires) ; (c) moteur HTTP sans JavaScript avec un vrai analyseur HTML (jsoup 1.23.2), formulaires GET/POST/multipart, cookies, historique, onglets.
Decision: (c) `HttpBrowserEngine` pour tout ce qui se fait sans JavaScript ; les pages dynamiques sont signalées (« page probablement dynamique ») et renvoyées vers (b) — c'est aussi la voie de la capture d'écran (`android_ui_look`). Une session par tâche (`BrowserSessions`, 4 au plus, LRU), jamais persistée, effacée à la fin de la tâche (`TaskExtension.onTaskEnd`) : cookies, onglets et historique ne fuient pas d'une tâche à l'autre. Éléments numérotés [e1]…, formulaires [f1]…, champs sensibles (mot de passe, carte, code, `autocomplete=cc-*`/`one-time-code`) et personnels (e-mail, téléphone, nom, adresse) détectés. Capacités : `browser.navigate`, `browser.back`, `browser.wait` (actualise une page obtenue par GET, jamais un envoi), `browser.click` (L1), `browser.tabs`, `browser.extract` (L0, `save` → artefact avec URL, date et empreinte), `browser.type` (L1, local jusqu'à l'envoi), `browser.upload` (joint un artefact, local), `browser.download` (L1, L2 si exécutable/installable ; stocké comme artefact, jamais ouvert ni exécuté, taille plafonnée, nom assaini). Envoi d'un formulaire : GET avec valeurs ordinaires vers le site en cours = L1 (automatique) ; POST, formulaire de données personnelles, valeur ressemblant à une donnée personnelle (e-mail, téléphone, carte, IBAN), destination sur un autre site, fichier joint, ou long texte saisi dans une tâche contaminée = L2 avec l'aperçu de chaque valeur envoyée (mots de passe masqués) ; formulaire sensible (mot de passe, paiement, code) et saisie dans un champ sensible = refus. L'exécution reclasse la page vivante et refuse si elle est devenue plus risquée que l'autorisation. Le navigateur côté worker (headless) n'est pas réalisé dans cette phase : l'interface canonique est celle des outils ci-dessus ; le worker exécute aujourd'hui des commandes de build, pas un navigateur.
Why: testable de bout en bout (MockWebServer), aucun code tiers exécuté dans Cortana, politique vérifiable champ par champ.
Compatibility impact: nouvelle dépendance jsoup (MIT, sans dépendance transitive) ; `web.*` inchangés.
Security impact: voir ci-dessus et D-20260927-044 ; contenu des pages toujours non fiable (tâche contaminée), passages d'injection retirés (D-20260927-043).
Data migration: aucune (sessions en mémoire).
Tests: `BrowserTest` — `pagesExposeNumberedElementsFormsAndIsolatedCookies`, `downloadsAreCappedNamedSafelyAndFlagged`, gate `nonSensitiveGetFormIsFilledAndSubmittedWithoutAskingTheOwner` (orchestrateur, aucune approbation, requête GET vérifiée côté site, session effacée en fin de tâche), `postWithFileNeedsApprovalShowingEveryValueAndPasswordFormsAreRefused`, `refusedSubmissionSendsNothingAndDownloadsBecomeArtifacts`.
Rollback: retirer `BrowserTools` du registre.

### D-20260927-043 — Recherche multi-sources avec citations vérifiées et défense contre l'injection
Status: accepted
Problem: doc 05 §12 : formuler des requêtes, chercher plusieurs sources, classer la fiabilité, ouvrir, extraire des faits, dédupliquer, conserver la provenance, synthétiser, citer ; « aucune source Web ne peut injecter des instructions système ».
Options considered: laisser le modèle de la tâche lire les pages et répondre (aucune garantie de provenance, injection directe dans la boucle d'agent) ; service dédié dont chaque sortie du modèle est vérifiée.
Decision: `ResearchService` + `research.run` (L1, destination « recherche web ») : requêtes (modèle, + la question elle-même), recherche via le fournisseur configuré, `SourceRanker` déterministe et explicable (rang, https, sources institutionnelles et de référence favorisées, réseaux sociaux et forums pénalisés, bonus si trouvée par plusieurs requêtes, 2 sources par site au plus, URL canonisée sans paramètres de suivi), lecture dans une session de navigateur isolée, extraction des faits par le modèle avec le document dans une enveloppe « données uniquement » ; un fait n'est gardé que si sa citation figure mot pour mot dans le texte récupéré (casse, accents, ponctuation normalisés) et qu'il n'est pas suspect ; dédoublonnage (même citation, ou même énoncé avec les mêmes chiffres) ; divergences chiffrées signalées ; synthèse du modèle gardée seulement si elle cite, et ne cite que des sources de faits vérifiés, sinon liste des faits. Rapport Markdown en artefact : synthèse, faits + citations, divergences, sources (URL, date, empreinte sha256, fiabilité, passages suspects retirés), sources écartées et pourquoi. Sans modèle disponible : repli heuristique (phrases pertinentes, citations exactes par construction). `InjectionGuard` : texte caché (display:none, hidden, aria-hidden, taille 0, opacité 0) retiré et signalé s'il contient des instructions ; phrases qui s'adressent à l'assistant (ignorer les consignes, changer de rôle, sonder l'invite, appeler un outil, nom d'outil, exfiltrer) remplacées par un marqueur visible, pour `browser.*` comme pour la recherche.
Why: provenance vérifiable ; une page ne peut ni faire inventer une citation ni faire exécuter quoi que ce soit (le sous-modèle ne dispose d'aucun outil, ses sorties sont des données contrôlées).
Compatibility impact: aucun. Security impact: positif. Data migration: aucune.
Tests: `BrowserTest.injectionGuard…`, `researchRanksReadsVerifiesDeduplicatesAndCites` (citation inventée et injection rejetées, fait confirmé par deux sources, divergence 25/35, source 404 écartée, synthèse citant une source inexistante remplacée), `sourceRankingPrefersInstitutionalAndDiverseSources`, gate `researchRunsEndToEndThroughTheOrchestratorAndStoresACitedReport` (SearXNG simulé, 2 sources, rapport cité, texte caché absent de la requête d'extraction).
Rollback: retirer `research.run`.

### D-20260927-044 — Protection SSRF à chaque redirection, avant toute connexion (correctif de `web.fetch`)
Status: accepted
Problem: en écrivant le navigateur : OkHttp 4.12 se connecte aux adresses IP littérales sans consulter le `Dns` (vérifié dans `RouteSelector`), et `web.fetch` suivait les redirections automatiquement. Une page publique redirigeant vers `http://169.254.169.254/` ou `http://10.0.0.1/` était donc lue malgré la protection (défaut présent depuis 1.2.0). Un intercepteur réseau ne suffit pas : il s'exécute après l'ouverture de la connexion.
Options considered: intercepteur réseau (trop tard) ; `EventListener` (ne peut pas bloquer proprement) ; suivre les redirections soi-même dans un intercepteur applicatif.
Decision: `SsrfGuard.redirectGuard` : le client ne suit plus les redirections (`followRedirects(false)`) ; l'intercepteur applicatif vérifie chaque saut avec la même règle avant de le demander, refuse https → http, ne transmet pas `Authorization` à un autre hôte, 10 sauts au plus ; l'erreur est une `IOException` (sans plantage des appels asynchrones). Appliqué à `web.*`, au navigateur et à la recherche. L'instance SearXNG configurée par le propriétaire (souvent sur le réseau local) est exemptée par son nom d'hôte, rien d'autre.
Why: fermer le contournement sans changer le comportement visible.
Compatibility impact: les redirections ordinaires fonctionnent comme avant ; une adresse SearXNG en IP locale reste utilisable (elle l'était déjà par accident) et un nom d'hôte local l'est désormais aussi.
Security impact: correctif de sécurité.
Data migration: aucune.
Tests: `BrowserTest.ssrfGuardChecksEveryRedirectHopBeforeConnecting` (redirection vers 169.254.169.254 refusée en moins de 5 s sans tentative de connexion, pour le navigateur et pour `web_fetch` ; les redirections ordinaires passent) ; `SecurityPrimitivesTest` inchangé.
Rollback: remettre `followRedirects(true)` (réintroduit le défaut — déconseillé).

### D-20260927-045 — Contrat : destination résolue sur l'état réel, fin de tâche notifiée aux extensions
Status: accepted
Problem: la règle « tâche contaminée + nouvelle destination → L2 » utilise `destinationOf(args)` ; pour un clic, la destination (hôte du lien ou de l'action du formulaire) n'est pas dans les arguments mais dans la page. Sans elle, tout clic d'une tâche qui a lu une page serait L2, et le gate « formulaire non sensible automatisé » serait impossible — ou il faudrait affaiblir la règle.
Options considered: exempter les outils du navigateur de la règle (affaiblit la politique) ; mettre l'URL dans les arguments (le modèle pourrait mentir) ; résoudre la destination sur l'état réel au moment de la décision.
Decision: `ToolDefinition.destinationResolver(args, PolicyContext)` optionnel, prioritaire sur `destinationOf` ; utilisé par `browser.click/back/wait/download`. La destination d'un envoi est l'hôte de l'action du formulaire, pas celui de la page. `TaskExtension.onTaskEnd(taskId)` appelé une fois l'état terminal atteint (nettoyage des sessions du navigateur).
Why: la règle de contamination reste entière et s'applique à la vraie destination.
Compatibility impact: paramètres facultatifs ; aucun outil existant modifié.
Security impact: neutre à positif (destination exacte au lieu d'une valeur fournie par le modèle).
Data migration: aucune.
Tests: gate GET (destination connue → aucune approbation), `refusedSubmission…` (destination résolue, valeur personnelle → L2), `ArchitectureRulesTest`.
Rollback: retirer le paramètre (les outils du navigateur retomberaient en L2 dans une tâche contaminée).

### D-20260927-046 — Client MCP à deux époques : 2026-07-28 (sans état) d'abord, `initialize` en repli
Status: accepted
Problem: doc 05 §16 / roadmap phase 20 : client MCP, découverte, transport HTTP en flux, stdio via le worker, négociation de version, délai, annulation, santé/reconnexion. Le pack (doc 14) demande la dernière spécification stable ; vérifiée en ligne pendant la phase : 2026-07-28, qui supprime la poignée de main `initialize` et les sessions, ajoute `server/discover`, met version et capacités dans `_meta` à chaque requête et ajoute les en-têtes `Mcp-Method`/`Mcp-Name`/`Mcp-Param-*`. Beaucoup de serveurs parlent encore 2025-xx.
Options considered: SDK Kotlin officiel (dépendance lourde, version 2026-07-28 non confirmée sur Android, objets du SDK dans le domaine — contraire à doc 14 « adapters + contrats Cortana ») ; client maison limité au sous-ensemble utile, derrière une interface de transport.
Decision: `McpClient` maison, double époque, selon les règles de détection de la spécification : sonde `server/discover` en moderne ; erreur moderne reconnue (`UnsupportedProtocolVersion` −32022, ou −32004 avec `supported`) → rester moderne et choisir une version commune, jamais de repli ; toute autre erreur, un 4xx sans erreur moderne ou un délai (stdio : 8 s) → `initialize` (2025-11-25) + `notifications/initialized`, en-tête `Mcp-Session-Id`, réinitialisation unique si la session expire (404). `resultType: input_required` refusé (Cortana n'annonce ni échantillonnage, ni élicitation, ni racines). Pagination `nextCursor` (20 pages au plus), cache `ttlMs`. Délai par requête ; à l'expiration ou à l'annulation : fermeture du flux (HTTP moderne) ou `notifications/cancelled` (stdio et ancien HTTP). Transports : `McpHttpTransport` (POST, réponse JSON ou SSE propre à la requête, commentaires de maintien ignorés, requêtes serveur des anciens serveurs refusées poliment, en-têtes encodés en Base64 si nécessaire, redirections sous la garde SSRF, https obligatoire hors réseau local) et `McpWorkerTransport` (stdio via le worker, D-048). `McpManager` : un client par serveur, synchronisation qui remplace le groupe d'outils du serveur dans le registre, état (ère, version, outils, rejetés, suspects, masqués, erreur), serveur en échec → outils retirés, appel jamais rejoué, reconnexion avec délai croissant (30 s → 10 min) par une boucle du conteneur.
Why: suit la spécification courante sans enfermer le domaine dans un SDK ; interopère avec les serveurs existants.
Compatibility impact: nouveau réglage `mcpServers` (défaut vide) ; aucune migration.
Security impact: voir D-047.
Data migration: aucune.
Tests: `McpTest` (7) : découverte/normalisation, deux serveurs (moderne + ancien par repli, session expirée), négociation sans version commune, gate orchestrateur, politique, délai/annulation/perte du serveur, stdio via worker.
Rollback: vider `mcpServers` (les outils disparaissent du registre).

### D-20260927-047 — Normalisation MCP dans le registre unique et politique locale
Status: accepted
Problem: « sans faire de MCP une deuxième Tool Registry », espaces de noms, détection des collisions, métadonnées de politique locales, contamination des ressources externes, « aucun outil externe ne contourne PolicyEngine ». Les annotations d'outils sont des indications non fiables (spécification) et les descriptions sont un vecteur d'injection connu (« tool poisoning »).
Options considered: exposer un outil générique `mcp.call(server, tool, args)` (le modèle ne voit pas les schémas, la politique ne distingue pas les outils) ; un `ToolDefinition` par outil externe.
Decision: `McpAdapter` crée un `ToolDefinition` par outil, capacité `mcp.<serveur>.<outil>` (nom de fonction ≤ 64 caractères, suffixe de hachage si deux noms se normalisent pareil ou en cas de collision avec un outil existant — jamais d'écrasement), catégorie `INTEGRATIONS`, groupe dynamique du registre (`ToolRegistry.replaceGroup`, nouvelle vérification d'unicité des noms de fonction). Schéma : objet obligatoire, taille et profondeur bornées, `x-mcp-header` valides (chaîne de `properties`, primitif, jeton HTTP, unique) sinon outil rejeté et signalé, les autres gardés. Risque : réglage du propriétaire par outil (L0…L3 ou masqué) ; sinon L1 si lecture seule **et** serveur marqué fiable, L3 si l'outil se dit destructeur, L2 sinon. Idempotence seulement sur indication d'un serveur fiable (sinon reprise après plantage = question au propriétaire). Description nettoyée par `InjectionGuard` (outil signalé si suspect), préfixée « outil MCP externe, résultat non fiable ». Résultats, ressources, modèles : données non fiables, passages suspects retirés, tâche contaminée ; destination = hôte du serveur (règle de contamination). Outils génériques `mcp.servers` (L0), `mcp.resources` et `mcp.prompts` (L1). Réglages → Serveurs MCP : ajout HTTP (jeton dans le coffre) ou stdio sur un worker, état, outils rejetés/suspects, confiance, politique par outil, suppression (jeton effacé).
Why: la politique existante s'applique entière et outil par outil ; le modèle voit de vrais schémas.
Compatibility impact: `ToolDiscovery` indexe par identité de définition (un outil re-synchronisé est ré-indexé).
Security impact: positif, voir `SECURITY.md` § MCP.
Data migration: aucune.
Tests: `McpTest.modernServerIsDiscovered…`, `externalToolsNeverBypassThePolicyEngine` (L2 approuvé, L3 refusé → jamais exécuté côté serveur, ressource contaminante nettoyée), `fixtureServerToolIsUsed…` (serveur fiable : L1 sans question, en-têtes `Mcp-Name`/`Mcp-Param-Region` vérifiés par le serveur, tâche contaminée).
Rollback: retirer `McpTools` et l'enregistrement du gestionnaire.

### D-20260927-048 — Serveurs MCP stdio exécutés par le worker appairé
Status: accepted
Problem: « stdio via worker quand applicable » ; Android ne peut pas lancer la plupart des serveurs stdio (Node, Python, binaires).
Options considered: lancer des processus sur la tablette (pas d'environnement, surface d'attaque) ; laisser la tablette envoyer une commande au worker (exécution arbitraire à distance) ; serveurs déclarés par le propriétaire du worker, nommés par la tablette.
Decision: `WorkerConfig.mcpServers` (commande, dossier, variables) dans `worker.json` ; `GET /v1/mcp` (noms) et `POST /v1/mcp/{nom}` (un message JSON-RPC signé comme toute requête du worker). `McpBridge` : un processus par (appareil, serveur), environnement minimal, relance après arrêt, arrêt après inactivité, appariement des réponses par `id`, réponses « non pris en charge » aux requêtes d'un ancien serveur, `stderr` journalisé. Le client MCP de la tablette reste l'unique client (détection d'époque, délais, annulation). Voir `WORKER_PROTOCOL.md`.
Why: aucune commande ne traverse le réseau ; le worker garde la main sur ce qu'il exécute.
Compatibility impact: champ facultatif dans `worker.json` ; routes nouvelles, les anciennes inchangées.
Security impact: variables du worker non transmises (test) ; canal signé et épinglé existant.
Data migration: aucune.
Tests: `McpTest.stdioServerRunsOnThePairedWorkerWithCancellation` (vrai worker HTTPS en processus, vrai sous-processus, serveur moderne et ancien, appel par l'orchestrateur, annulation reçue par le serveur, environnement minimal).
Rollback: retirer `mcpServers` du `worker.json`.

### D-20260927-049 — Authentification MCP : jeton maintenant, OAuth 2.1 avec les connecteurs (phase 26)
Status: accepted
Problem: doc 05 §16 cite « auth ». La spécification MCP prévoit OAuth 2.1 (PKCE, métadonnées de ressource protégée, documents de métadonnées client) ; la feuille de route place « connector registry ; auth » en phase 26.
Options considered: implémenter OAuth ici puis le dupliquer pour les connecteurs ; un seul module d'authentification des connecteurs (phase 26) réutilisé par MCP.
Decision: phase 20 : jeton Bearer (clé d'API ou jeton d'accès personnel) chiffré dans le coffre, jamais journalisé ; un 401/403 avec `WWW-Authenticate: …resource_metadata` est signalé au propriétaire (« le serveur demande une connexion OAuth »). OAuth 2.1 interactif : réalisé en phase 26 dans le module d'authentification des connecteurs, puis branché sur `McpHttpTransport` (le fournisseur de jeton est déjà une fonction). Ce n'est pas un report silencieux : l'élément reste ouvert dans `CAPABILITY_CHECKLIST.md` jusqu'à la phase 26.
Why: une seule implémentation d'OAuth, testée une fois.
Compatibility impact: aucun. Security impact: jetons chiffrés ; http refusé hors réseau local.
Data migration: aucune.
Tests: couverture de l'en-tête `Authorization` indirecte (fournisseur de jeton) ; OAuth : phase 26.
Rollback: sans objet.

### D-20260927-050 — Délégation A2A 1.0 : l'agent externe est une capacité distante bornée
Status: accepted
Problem: doc 05 §17 / roadmap phase 21 : découverte par carte d'agent, négociation, délégation, fichiers/données structurées, authentification, annulation, provenance, politique, limitation de débit ; « un agent externe n'obtient pas la mémoire interne complète ni le contrôle de l'orchestrateur » ; gate « sous-tâche distante simulée sans fuite de mémoire interne ». Spécification vérifiée en ligne pendant la phase : A2A 1.0.0 (méthodes JSON-RPC `SendMessage`/`GetTask`/`CancelTask`, `A2A-Version: 1.0`, carte `/.well-known/agent-card.json` avec `supportedInterfaces`, états `TASK_STATE_*`, parties `text`/`raw`/`url`/`data`).
Options considered: SDK A2A (objets externes dans le domaine, contraire à doc 14) ; exposer Cortana comme serveur A2A (hors périmètre : doc 05 demande la délégation sortante) ; client JSON-RPC 1.0 minimal.
Decision: `A2aService` + `A2aClient` (liaison JSON-RPC 1.x seulement ; une carte sans interface JSON-RPC 1.x est refusée avec la liste de ses interfaces, sans repli silencieux sur 0.3 — spec §3.6.3). Carte cherchée sous le chemin donné puis à la racine du domaine ; mise en cache 1 h ; signatures de carte signalées mais non vérifiées (affiché dans Réglages). Outils : `agents.list` (L0), `agent.delegate` (L2, visible par un tiers, aperçu exact du message et des fichiers, refus si un secret enregistré y figure), `agent.task` (état/annulation, L1). Message sortant construit **uniquement** depuis les arguments (`objective`, `context`, artefacts joints) : ni mémoire, ni historique, ni invite. Choix de l'agent par correspondance avec les compétences de la carte (mots et étiquettes) ; ambiguïté → question. Appel bloquant puis sondage `GetTask` jusqu'à un état final ou interrompu ; délai dépassé ou tâche Cortana annulée → `CancelTask`. `INPUT_REQUIRED` rendu au modèle avec `task_id`/`context_id` pour poursuivre ; `AUTH_REQUIRED`, échec, refus → erreur. Réponse : texte et données non fiables (passages suspects retirés, tâche contaminée), fichiers (`raw` ou `url` téléchargée sous la garde SSRF) → artefacts plafonnés à 20 Mo, noms assainis, jamais ouverts. 6 délégations par minute et par agent. Jeton Bearer dans le coffre ; https obligatoire hors réseau local ; redirections sous la garde SSRF. Réglages → Agents externes.
Why: délégation utile sans rien céder : l'agent distant ne voit que ce que l'accord montre.
Compatibility impact: nouveau réglage `a2aAgents` (défaut vide).
Security impact: aucun accès entrant ; sortie limitée à l'aperçu approuvé ; secrets bloqués.
Data migration: aucune.
Tests: `A2aTest` (5) — cartes et correspondance de compétences (interface 0.3 refusée), gate orchestrateur sans fuite (souvenirs, demande du propriétaire et invite absents de la requête ; aperçu exact ; injection retirée ; deux fichiers en artefacts), secret refusé et authentification refusée, précision demandée puis poursuite de la même tâche, délai/annulation → `CancelTask`, limitation de débit.
Rollback: vider `a2aAgents`.

### D-20260927-051 — Plugins déclaratifs, signés, installés de façon journalisée
Status: accepted
Problem: roadmap phase 22 (manifestes, signatures, cycle de vie, isolation, gestionnaire) ; doc 05 §18 (« Plugins ne chargent jamais du code arbitraire dans le process principal sans isolation ») ; blueprint §21 (un seul système d'extension ; ne jamais contourner registre, politique, secrets, état des tâches, mémoire) ; doc 06 (tables `plugins`, `plugin_versions`) ; gate « plugin test ajouté/retiré sans corruption ».
Options considered: charger du code (dex) dans un chargeur isolé (surface d'attaque, interdit par doc 05 sans isolation forte, non vérifiable) ; APK séparés avec services liés (lourd, permissions Android) ; plugins **déclaratifs** dont toute partie exécutable tourne hors processus via MCP/A2A.
Decision: format `cortana.plugin` v1 (dans `:contracts`, partagé tablette/worker) : `plugin.json` (id, version semver, éditeur, API de plugin 1, version minimale de Cortana, permissions `tools`/`network`/`secrets`, contributions `skills`/`mcpServers`/`a2aAgents`/`documents`, dépendances, empreintes SHA-256 de tous les fichiers), `signature.json` (ECDSA P-256 sur les octets exacts du manifeste). Refus : paquet non signé ou modifié, fichier manquant ou non déclaré, chemin sortant, fichier exécutable (dex, jar, so, apk, scripts…), `entrypoints` ou `migrations` non vides, hôte réseau non déclaré, http, secret non déclaré, compétence utilisant un outil non déclaré, dépendance absente, Cortana trop ancienne. Confiance : l'empreinte de la clé de l'éditeur est montrée et doit être approuvée ; les mises à jour doivent être signées par la même clé et ne jamais revenir en arrière. Cycle de vie journalisé : état `installing` écrit d'abord, fichiers préparés dans `.staging` puis renommés atomiquement, contributions activées, état `active` enfin ; tout échec restaure l'état précédent ; `recover()` au démarrage termine ou annule une opération interrompue, supprime les restes et orphelins, met hors service un plugin dont les fichiers ont disparu. Contributions : compétences installées comme candidates désactivées (validation et activation par le propriétaire, cycle existant), serveurs MCP et agents A2A ajoutés **non fiables** sous un préfixe propre au plugin, secrets saisis par le propriétaire rangés dans le coffre sous `secret:plugin:<id>:<nom>` (le plugin ne les lit jamais), documents en lecture seule vérifiés par empreinte à chaque lecture (`plugin.documents`, contenu tiers → tâche contaminée). Désactivation : serveurs et agents retirés, compétences désactivées. Désinstallation : tout retiré, historique `plugin_versions` conservé (`removedAt`). LAW-015 (test d'architecture) : aucun chargeur de classes, bibliothèque native ni processus dans le code des plugins. Outil éditeur : `cortana-worker.jar plugin-keygen` / `plugin-pack`.
Why: extensibilité sans code tiers dans le processus ; aucune extension ne contourne la politique ; opérations sûres en cas de coupure.
Compatibility impact: tables `plugins`, `plugin_versions` ajoutées au schéma v2 (encore ouvert, D-013) et à la migration 1→2.
Security impact: voir `SECURITY.md` § Plugins.
Data migration: `DATA_MIGRATIONS.md` (tables, fichiers, journal).
Tests: `PluginTest` (5) — vérification du format (modification, signature, non signé, chemin, fichier non déclaré, code, réseau, http, secret), gate ajout/retrait sans toucher aux données du propriétaire, mises à jour (même clé, pas de retour arrière, empreinte approuvée), isolation (outil non déclaré, secrets requis, version de Cortana, désactivation), reprises après coupure (fichiers écrits, mise à jour incomplète, suppression interrompue, fichiers disparus) ; `ArchitectureRulesTest.law015…` ; `DatabaseMigrationTest` (validation contre `2.json`). CLI du worker exécutée à la main (clé 600, refus d'écraser, paquet vérifié).
Rollback: désinstaller les plugins ; tables vides sans effet.

### D-20260927-052 — Spécialistes : travailleurs éphémères de l'orchestrateur unique, parallélisme en lecture seule
Status: accepted
Problem: doc 04 §17-18 / roadmap phase 23 : profils, tâches de spécialiste, outils limités, parallélisme, fusion des résultats ; « les spécialistes sont des workers cognitifs éphémères, pas des orchestrateurs concurrents » ; l'orchestrateur crée la sous-tâche, fournit un contexte minimal, limite les outils, reçoit `SpecialistResult`, décide d'intégrer, reste seul responsable des transitions ; parallélisme seulement pour des sous-tâches indépendantes, sans deux écrivains sur le même espace de travail, avec budgets et propagation de l'annulation ; gate « tâche de code avec analyste + implémenteur + relecteur sans double orchestrateur ».
Options considered: un agent par rôle avec sa propre boucle (deux autorités, états concurrents) ; tâches enfants dans la table `tasks` (transitions multiples, reprise compliquée) ; exécuter une étape du plan « en tant que » spécialiste avec le `StepRunner` existant.
Decision: `PlanStep.specialist` (champ facultatif du contrat, rétrocompatible) ; le planificateur reçoit les profils et la règle « analyse → modification → revue » pour le code. `SpecialistRegistry` : `researcher`, `code_analyst`, `implementer`, `reviewer`, `test_analyst`, `security_reviewer`, `document_analyst`, `planner_specialist`, chacun avec un filtre d'outils fondé sur les métadonnées du registre (catégorie, effet de bord) et des budgets propres (appels d'outils, appels au modèle, minutes). Pour une étape de spécialiste, l'orchestrateur construit un `TaskRun` enfant : mêmes identifiant de tâche et route, compteurs partant de ceux de la tâche avec un plafond propre, contexte isolé (`ContextRequest.isolatedSince` : ni mémoire, ni conversation, ni carnet ; seulement l'objectif de l'étape, les résultats des étapes dont elle dépend et ses propres échanges), outils filtrés (pas de découverte d'outils hors rôle ; un appel hors rôle est refusé par le répartiteur). Les compteurs, la contamination, les effets et la route sont fusionnés dans la tâche sous verrou. Un dépassement de budget ou de délai du spécialiste fait échouer l'étape (la reprise décide), pas la tâche. `SpecialistTask` et `SpecialistResult` sont enregistrés comme événements sans changement d'état (`TaskStateMachine.record`) ; vérification, reprise, replanification et transitions restent dans l'orchestrateur. Parallélisme : jusqu'à 3 étapes prêtes, marquées `parallel`, toutes confiées à des profils en lecture seule ; les écrivains (implémenteur, relecteur et analyste de tests qui lancent des compilations) passent toujours seuls ; l'intégration des résultats reste séquentielle (résultats réussis d'abord) ; `coroutineScope` propage l'annulation.
Why: une seule autorité, des rôles bornés, un gain de temps sans risque d'écritures concurrentes.
Compatibility impact: champ de contrat avec défaut ; plans existants inchangés ; boucle de plan découpée en `runStep`/`handle` sans changement de comportement pour les étapes sans spécialiste (suite complète verte).
Security impact: moindre privilège par rôle ; aucune fuite de mémoire ou de conversation vers un spécialiste.
Data migration: aucune (événements existants).
Tests: `SpecialistsTest` (3) — gate code analyste → implémenteur → relecteur par l'orchestrateur réel (écriture de l'analyste refusée, contexte de l'analyste sans souvenir ni conversation, relecteur recevant le résultat dont il dépend, une seule tâche, six événements de spécialiste sans transition), deux étapes en lecture seule réellement simultanées, annulation de la tâche pendant deux spécialistes en cours (< 5 s).
Rollback: ne plus proposer de profils au planificateur (les étapes redeviennent celles de l'agent principal).

### D-20260927-053 — Document & Data Workbench : OOXML natif, PdfBox, un seul propriétaire, résultats en artefacts
Status: accepted
Problem: roadmap phase 24 (texte/documents, PDF, tableurs, présentations, extraction structurée, transformations, artefacts ; gate « créer / modifier / exporter des artefacts représentatifs de bout en bout ») ; blueprint §1.13 et §34 (capacités structurées plutôt qu'un agent documentaire ; lecture, création, édition, conversion, comparaison, modèles ; PDF : texte, rendu de page, métadonnées, fusion/découpe, inspection visuelle ; tableurs : classeur, feuilles/plages, cellules, formules, tableaux, graphiques simples, analyse, export ; présentations : création, ajout/modification de diapositives, dispositions, images/graphiques, export ; provenance de chaque fichier produit) ; checklist « archive handling safe », « structured data CSV/JSON », « provenance » ; tout contenu de document est une donnée non fiable.
Options considered: Apache POI (très lourd sur Android : dépendances XML/AWT, nombre de méthodes, démarrage) ; docx4j (JAXB, inadapté) ; LibreOffice sur le worker (dépendance externe, indisponible sans worker) ; OOXML écrit et lu directement (ZIP + DOM) et PdfBox-android pour le PDF.
Decision: `core/documents` est le propriétaire unique des formats. Modèles neutres : `Doc`/`Block` (titres, paragraphes avec gras/italique, listes à niveaux, listes numérotées, tableaux, sauts de page), `DataTable`, `Sheet`/`Cell` (texte, nombre, booléen, formule avec valeur en cache, date), `SlideSpec` (dispositions title, content, two_column, image, chart), `ChartSpec` (colonnes, barres, courbes, secteurs). OOXML sans bibliothèque (`Ooxml.kt`) : DOCX (styles Titre 1-3, listes, tableaux, A4 ; lecture des styles de titre quelle que soit la langue, des numérotations, des contrôles de contenu ; remplacement de champs même éclatés entre plusieurs « runs », en-têtes et pieds compris ; ajout avant la section finale), XLSX (chaînes partagées ou en ligne, formules, dates selon le format de cellule et le calendrier 1904, en-tête gras figé et filtre automatique, édition en place qui conserve styles, autres feuilles et graphiques, ajout de feuille, graphiques natifs liés aux cellules), PPTX 16:9 (dispositions, images au bon rapport, graphiques natifs, ajout/insertion, remplacement, réordonnancement et suppression de diapositives avec purge des médias orphelins, remplacement de texte). Lecteurs tolérants, écrivains minimaux ; toute DTD refusée (XXE), archives lues avec limites. Formules : Cortana calcule un sous-ensemble (références, + - * / ^, SOMME/SUM, MOYENNE/AVERAGE, MIN, MAX, NB/COUNT, ARRONDI/ROUND, ABS) pour écrire des valeurs en cache ; le reste est laissé au tableur, qui recalcule à l'ouverture (`fullCalcOnLoad`). PDF (`PdfEngine`, pdfbox-android 2.0.27.0, Apache-2.0, **sans BouncyCastle** : les PDF chiffrés par certificat ne sont pas lisibles) : texte par page, informations (métadonnées, champs de formulaire, pièces jointes comptées jamais ouvertes), rendu PNG d'une page, création A4 (police Liberation Sans intégrée en sous-ensemble — SIL OFL 1.1, fournie par la bibliothèque — gras simulé, italique incliné, puces, tableaux, images, numéros de page), fusion, extraction/réordonnancement, rotation, suppression, tampon/filigrane, numérotation, métadonnées ; un PDF chiffré peut être lu si ses permissions l'autorisent mais n'est **jamais** modifié (aucune protection retirée). `DocumentService` : sources = artefacts (`artifact:<id>`, empreinte revérifiée) ou fichiers du dossier de travail (SAF, 50 Mo maximum) ; format détecté par le contenu ; conversions DOCX/PDF/Markdown/texte/HTML/PPTX et XLSX/CSV/JSON (+ tableaux d'un document) ; comparaison ligne à ligne (HistogramDiff de JGit) ou cellule par cellule ; résultats **toujours** enregistrés comme nouveaux artefacts avec provenance (tâche, capacité, opération, sources et leurs SHA-256) ; copie dans le dossier de travail seulement sur demande (`save_to`). 15 capacités (catégorie DOCUMENTS) : `document.read/create/edit/convert/compare/extract`, `pdf.read/edit`, `spreadsheet.read/write/analyze`, `presentation.create/edit`, `archive.inspect/extract`. Résumer = `document.read` puis le modèle (pas d'outil dédié qui court-circuiterait la lecture). Politique : lectures L0 sans effet de bord (utilisables par l'analyste de documents), production d'un artefact L1, écriture dans le dossier du propriétaire L2 comme `file.write` (le remplacement d'un fichier existant est annoncé) ; tout texte lu contamine la tâche (`document:<nom>`) et passe par `InjectionGuard`. Correctif transversal : `FileExecutor.createNamed` renomme le fichier quand un fournisseur SAF ajoute l'extension du type MIME (« devis.pdf.pdf »).
Why: fonctions de bureau complètes sur la tablette, sans serveur ni bibliothèque surdimensionnée ; fichiers ouverts sans erreur par LibreOffice et relus par python-docx/openpyxl/python-pptx/pypdf (vérifié) ; le modèle ne manipule jamais d'octets.
Compatibility impact: aucune modification du schéma (les colonnes de provenance de `artifacts` existaient depuis la phase 9) ; nouvelle dépendance pdfbox-android (+ polices et tables CMap en assets, taille d'APK accrue) ; formats ODF (odt/ods/odp) non pris en charge en lecture — à convertir en OOXML/PDF.

### D-20260927-054 — Services médias : routage par capacité via la passerelle unique, traitement local sûr
Status: accepted
Problem: roadmap phase 25 (génération/retouche d'images, vision, audio, interfaces de fournisseurs vidéo, métadonnées d'artefacts ; gate « routage des capacités par fournisseur + manipulation locale sûre des artefacts ») ; blueprint §34A (`media.image.generate` via une abstraction de fournisseur, jamais un appel direct du planificateur ; `media.image.analyze` routé vers un modèle de vision, les opérations déterministes restant des outils ; `media.tts.synthesize` produit un artefact audio sans seconde boucle vocale) ; doc 05 §10 (le modèle de capacités indique les modalités) ; checklist « image analysis » (MUST), « image generation/edit provider », « video provider abstraction » (SHOULD).
Options considered: clients propres à chaque éditeur dans des outils (contourne LAW-001, secrets et politique dispersés) ; un « agent média » (boucle concurrente) ; des méthodes média dans l'interface `ModelProvider` appelées seulement par la `ModelGateway`, orchestrées par un service média unique.
Decision: `ModelProvider` gagne `generateImages`, `editImages`, `transcribeFile`, `createVideo`/`videoJob`/`videoContent` (adaptateur compatible OpenAI : `/images/generations`, `/images/edits`, `/audio/transcriptions`, `/videos`) ; la `ModelGateway` les expose avec les mêmes règles que le dialogue (mode « Local uniquement », santé du fournisseur, plafond de dépense, usage enregistré) ; LAW-001 est étendue à ces points d'accès. `core/media/MediaService` est le propriétaire unique : routage par capacité (`MediaCapability` : analyse, génération, retouche, transformation locale, synthèse en fichier, transcription, génération et inspection vidéo) sur les routes du propriétaire (`imageRoute`, `videoRoute` nouvelles ; `visionRoute`, `sttRoute`, `ttsRoute` existantes), rapport lisible (`media.providers`, Réglages) et modalités devinées d'après l'identifiant du modèle (`Modalities`, informatif, la route du propriétaire prime). Analyse : métadonnées et OCR toujours sur la tablette, description par le modèle de vision s'il existe. Sécurité locale (`MediaFormats`) : type déterminé par le contenu (jamais par le nom), dimensions lues avant tout décodage (plus de 60 mégapixels refusés), décodage sous-échantillonné, orientation EXIF appliquée ; toute image envoyée à un fournisseur est une copie réencodée sans métadonnées (GPS, appareil, logiciel) ; la position GPS n'est montrée au modèle que sur demande explicite et n'est jamais envoyée ; les réponses des fournisseurs sont vérifiées (vraie image, vrai audio, vraie vidéo, tailles bornées) ; une URL renvoyée par un fournisseur n'est téléchargée qu'en https et sous la règle SSRF. Transformations locales déterministes (redimensionner, recadrer, pivoter, retourner, niveaux de gris, format). Synthèse en fichier : fournisseur si la voix est « distante », sinon moteur Android `synthesizeToFile` ; jamais de lecture ni de seconde boucle vocale. Transcription de fichiers : route de transcription seulement (la reconnaissance Android ne lit pas de fichiers). Vidéo : tâche asynchrone (création, suivi, téléchargement) et inspection locale (`MediaMetadataRetriever` : durée, dimensions, images extraites). Résultats : artefacts de type `image`, `audio`, `video` avec provenance et métadonnées du fournisseur (modèle, consigne, consigne révisée, dimensions, durée) via `DocumentService.emit`. Politique : analyses et inspections L0 dont le résultat contamine la tâche (`image:`, `audio:`, `video:`) ; productions L1 ; copie dans le dossier de travail L2.
Why: une seule autorité d'inférence, des coûts et une confidentialité maîtrisés, des fichiers vérifiés avant d'exister dans Cortana.
Compatibility impact: réglages `imageRoute` et `videoRoute` (défaut : aucun) ; aucune modification du schéma ; types MIME audio/vidéo ajoutés à l'`ArtifactService`.

### D-20260927-055 — Gestionnaire de connexions unique, OAuth 2.1 natif, canaux entrants par TaskRequest
Status: accepted
Problem: roadmap phase 26 (registre de connecteurs, authentification, santé, normalisation des outils ; gate « connecteur de test + révocation ») ; blueprint §38 (une seule autorité du cycle de vie des comptes et points d'accès externes : connexion, authentification, rafraîchissement, santé, déconnexion, portées, références de secrets), §35-37 (messagerie normalisée en `GatewayMessage` sans appel d'outil par un adaptateur, e-mail dédié, Home Assistant, webhooks authentifiés et limités, `http.request` avec injection de secrets et liste d'hôtes), doc 05 §13-15 (brouillon L1/L2, envoi L2, suppression L3, actions physiques sensibles L2/L3), doc 06 (tables `connectors`/`connection_health_events`) ; D-049 (OAuth 2.1 partagé avec MCP).
Options considered: une bibliothèque par service (clients Gmail, Slack…) avec leur propre gestion de jetons ; un gestionnaire par type de connecteur ; un seul `ConnectionManager` avec des adaptateurs par type. Pour l'e-mail : Jakarta Mail (lourd, dépendances d'activation) ou un client IMAP/SMTP minimal écrit pour Cortana. Pour les webhooks entrants : un serveur sur la tablette (arrière-plan Android, pas d'adresse publique) ou le worker appairé.
Decision: `core/connections/ConnectionManager` est l'unique autorité : types déclarés (`ConnectorKinds` : API HTTP, webhook sortant, webhook entrant, Home Assistant, e-mail IMAP/SMTP, Telegram), champs validés (un secret ne va jamais dans la configuration), secrets par poignées dans le coffre (interface `SecretVault`), états `active`/`disabled`/`pending_auth`/`revoked`, santé sondée avec recul exponentiel (dégradée puis hors service, jusqu'à 6 h), limite de débit par connexion, journal `connection_events` (sans secret, purge à 30 jours), audit. OAuth 2.1 pour application native (`OAuthClient`) : découverte RFC 8414/OIDC ou depuis une ressource protégée RFC 9728 (serveurs MCP), enregistrement dynamique RFC 7591 en client public, code d'autorisation + PKCE S256 uniquement, état secret vérifié en temps constant et à usage unique, URI de retour privée `io.github.artisanguillonrenov.cortana://oauth2redirect` (`OAuthRedirectActivity`), https exigé hors réseau local, rafraîchissement avec rotation sous verrou, `invalid_grant` → « à autoriser », révocation RFC 7009 à la déconnexion. Révocation : jetons révoqués chez le fournisseur quand c'est possible, adaptateur arrêté (webhook retiré du worker), secrets effacés, ligne conservée en « révoquée » avec son historique ; « supprimer » efface tout. MCP : `McpServerConfig.connection` fournit le jeton OAuth au transport (D-049 soldée). Adaptateurs : `HttpConnector` (hôte et chemin de base fixés, méthodes permises, en-têtes d'authentification réservés, redirections jamais suivies, réponses bornées), `WebhookOutConnector` (JSON signé HMAC-SHA256, clé d'idempotence), `WebhookInConnector` + route publique du worker (`HookStore` : signature, fraîcheur, rejeu, débit, file persistée), `HomeAssistantConnector` (domaines permis, domaines sensibles), `EmailConnector` sur un client IMAP (littéraux, STARTTLS, MOVE/UIDPLUS, UTF-7 modifié) et SMTP (AUTH PLAIN/LOGIN, STARTTLS) écrits pour Cortana et un MIME minimal (RFC 2045-2049, 2047, 2231) — clair refusé hors réseau local, rien n'est jamais supprimé —, `TelegramConnector` (Bot API). `InboundService` : événements validés, enregistrés avec un identifiant de déduplication, transformés en `TaskRequest` ordinaires (webhook : tâche contaminée, contenu enveloppé et nettoyé ; Telegram : seuls les identifiants de discussion autorisés sont écoutés, les autres rejetés et audités), réponses renvoyées par l'outbox (une seule fois, réessayées). 16 capacités (catégorie INTEGRATIONS) : `connections.list`, `connection.check`, `http.request` (GET L1, écriture L2 avec la requête exacte, DELETE L3), `webhook.send` (L2), `home.states` (L1), `home.call` (L2, domaines sensibles L3, domaines non permis refusés), `email.search/read` (L0, contenu non fiable), `email.attachment/draft/archive` (L1), `email.send/reply/forward` (L2, message exact prévisualisé, destination = domaines des destinataires). `InjectionGuard.scrubAny` nettoie le JSON valeur par valeur. Écran Réglages → Connexions (ajout par type, autorisation, test, historique, activation, révocation, suppression) ; option OAuth dans l'ajout d'un serveur MCP. Autres messageries (Discord, Slack, WhatsApp, Signal) : non implémentées ; l'interface (`GatewayMessage` + `InboundService`) est prête.
Why: une seule façon d'atteindre l'extérieur avec des secrets, contrôlable et révocable ; pas de bibliothèque lourde sur Android ; les canaux entrants ne contournent jamais l'orchestrateur ni la politique.
Compatibility impact: tables `connections` et `connection_events` ajoutées au schéma v2 (encore ouvert, D-013) et à la migration 1→2 ; champ facultatif `McpServerConfig.connection` ; routes `/v1/hooks*` et `/hooks/` du worker ; dépendance de test GreenMail (serveur IMAP/SMTP réel pour la fixture).

### D-20260928-056 — Exécutions planifiées durables, politique de concurrence, surveillance conditionnelle
Status: accepted
Problem: checklist « Automation » (MUST condition watch, concurrency policy, durable scheduled tasks) ; blueprint §39.2-39.5 (une exécution planifiée est enregistrée avant d'agir, survit à un redémarrage, applique une politique quand la précédente n'est pas finie ; une surveillance vérifie une condition sans appel de modèle et ne lance la tâche que si elle est remplie) ; LAW-006 (le planificateur ne lance ni outil ni modèle). En 1.2.0, `Orchestrator.runScheduled` attendait jusqu'à 10 minutes que l'orchestrateur se libère puis abandonnait : une tâche due pendant une longue tâche, ou pendant un arrêt du processus, était perdue.
Options considered: garder l'attente en mémoire (perdue au redémarrage) ; une file WorkManager par exécution (contraintes Android, pas de lien avec l'état de la tâche) ; une table `schedule_runs` écrite par le planificateur et exécutée côté orchestrateur. Pour la surveillance : une tâche complète à chaque vérification (un appel de modèle toutes les N minutes) ou une lecture déterministe par le dispatcher.
Decision: table `schedule_runs` (exécution, échéance, mise en file, statut `queued`/`running`/`succeeded`/`failed`/`cancelled`/`skipped`/`condition_not_met`/`waiting`/`interrupted`, tâche, détail, retard) et colonne `schedules.concurrencyPolicy` (`skip` par défaut = comportement 1.2.0 ; `queue` = une seule en attente derrière celle en cours ; `replace` = celles en attente annulées et celle en cours arrêtée ; `allow`). `CortanaScheduler` (temps uniquement) enregistre l'exécution sous sa politique puis signale ; il n'exécute rien. `core/orchestrator/ScheduledRunner` exécute la file dans l'ordre quand l'orchestrateur est libre (déclenché à la mise en file, à la fin de chaque tâche, au démarrage et par la maintenance) ; une exécution laissée en file pendant qu'une autre tâche tourne n'est ni perdue ni bloquante. Au démarrage, une exécution `running` d'un processus mort devient `interrupted` (sa tâche est reprise ou close par `recoverOnStartup`) ; les exécutions terminées sont purgées à 60 jours. `Orchestrator.runScheduledRun` remplace `runScheduled` : TaskRequest `schedule` ordinaire (indices `scheduleId`, `scheduleRunId`), prise de l'orchestrateur atomique. Surveillance (`condition_watch`) : `ConditionSpec` (capacité, arguments, test `contains`/`not_contains`/`regex`/`above`/`below`/`changed`, valeur) validée à la création ; seule une capacité sans effet, ≤ L1 et hors pilotage d'écran est acceptée (`ScheduledRunner.watchRefusal`) ; la lecture passe par le `ToolDispatcher` (même politique, trace dans `tool_calls` sous `watch:<run>`), aucun modèle n'est appelé ; condition non remplie → `condition_not_met`, rien d'autre ; remplie → tâche contaminée, le résultat lu enveloppé comme donnée (`surveillance:<capacité>`). `changed` compare l'empreinte SHA-256 (16 hex) de la dernière vérification (`fp=` en tête du détail) : la première vérification sert de référence. STOP : les exécutions en file sont annulées ; désactiver ou supprimer une planification annule ou efface ses exécutions. Outils : `schedule.create` (type `condition_watch`, `concurrency`, `missed`, `watch`), `schedule.list` (politique, condition, dernier résultat), nouveau `schedule.runs` (L0). Écran Planifications : politique à la création d'une tâche récurrente, historique des exécutions.
Why: aucune exécution due n'est perdue ni dupliquée silencieusement, la politique est explicite et visible ; une surveillance fréquente ne coûte aucun appel de modèle tant que rien ne change ; LAW-006 et le point de passage unique du dispatcher sont conservés.
Compatibility impact: colonne `schedules.concurrencyPolicy` (défaut `skip`, identique à 1.2.0) et table `schedule_runs` ajoutées au schéma v2 (encore ouvert, D-013) et à la migration 1→2 ; `Orchestrator.runScheduled` supprimée (seul appelant : le conteneur).

### D-20260928-057 — Service d'amélioration : propositions versionnées, application par le propriétaire uniquement
Status: accepted
Problem: roadmap phase 27 (analyse des échecs, propositions de procédures, de raccourcis et de cas de régression ; gate « propose une amélioration sans s'auto-modifier silencieusement ») ; doc 04 §16 (échecs récurrents, meilleure procédure, raccourci, routage, nouveau test, doublons d'outils, outils inutilisés, requêtes trop longues, coûts anormaux, régressions) ; blueprint §18 (propositions versionnées, attribuables, réversibles, testables ; jamais de modification silencieuse du code, de la politique de sécurité, des permissions ou des secrets) ; LAW-019.
Options considered: laisser le modèle ajuster ses propres réglages par un outil (contraire à « le modèle ne commande jamais le système ») ; appliquer automatiquement les changements « sûrs » (modification silencieuse) ; un service qui ne fait qu'enregistrer des propositions typées et que seul le propriétaire applique.
Decision: `core/improvement/ImprovementService` + 9 analyseurs purs sur un instantané borné (30 jours) : `failures@1` (même code de fin ≥ 3 fois en 14 jours, même erreur d'outil ≥ 3 fois), `fastpath@1` (même demande résolue ≥ 3 fois par un seul appel identique à faible risque), `skills@1` (procédure validée à activer, procédure active défaillante à désactiver), `routing@1` (fournisseur par défaut en échec, alternative prouvée), `evals@1` (échec puis réussite → cas de test), `regressions@1` (cas de test qui n'aboutit plus comme prévu, outil dont le taux de réussite chute de 30 points), `tools@1` (descriptions presque identiques, outils inutilisés 30 jours), `prompts@1` (requêtes médianes > 60 % de la fenêtre), `cost@1` (jour > 3× la médiane). Table `improvement_proposals` : empreinte unique (même constat mis à jour, jamais dupliqué ; version incrémentée quand la preuve change ; analyseur@version enregistré), statut `open`/`applied`/`rejected`/`rolled_back`/`obsolete`, preuve (tâches, comptes), changement typé ou aucun (observation). Changements possibles, tous bornés et réversibles : réglage parmi une liste fermée non sensible (`maxToolCallsPerTask` ≤ 100, `maxModelCallsPerTask` ≤ 60, `maxTaskMinutes`, `maxToolsOffered` ≥ 12, `maxPlanSteps`, `maxReplans`, `dailySpendCapUsd`, `defaultProviderId` existant) ; raccourci propriétaire (réglage `ownerShortcuts` : phrase exacte → un appel à une capacité sans effet externe ≤ L1, hors écran, exécuté par le dispatcher sous la politique, `OwnerShortcutPath`) ; cas de test (`eval_cases`) ; activation/désactivation d'une procédure. Application uniquement par le propriétaire (Réglages → Améliorations) : nouvelle validation, valeur remplacée gardée (`previousJson`), audit `improvement.apply` ; annulation refusée si la valeur a été rechangée depuis (le choix du propriétaire n'est jamais écrasé) ; refus et obsolescence jamais reproposés tant que la ligne existe (purge à 30 jours). Le modèle n'a qu'une capacité de lecture (`improvement.list`, L0). Aucune proposition ne modifie du code : le rationnel renvoie vers la fabrique logicielle (revue + approbation). Déclenchement : maintenance périodique et, au plus toutes les 30 minutes, après une tâche terminée. LAW-019 vérifiée par `ArchitectureRulesTest`.
Why: apprendre de l'usage sans jamais changer Cortana dans le dos du propriétaire ; chaque changement est explicable (preuve), attribuable, borné et annulable.
Compatibility impact: tables `improvement_proposals` et `eval_cases` ajoutées au schéma v2 (encore ouvert, D-013) et à la migration 1→2 ; réglage `ownerShortcuts` (vide par défaut, sans migration) ; `FastPathRegistry` reçoit un chemin supplémentaire `owner.shortcut`.

### D-20260928-058 — Observabilité locale : arbre de spans par tâche, métriques, export OTLP facultatif
Status: accepted
Problem: roadmap phase 28 (traces, métriques, visualiseur local, export OTLP facultatif, expurgation ; gate « une tâche affiche spans modèle/outils/vérificateur sans secrets ») ; doc 06 §15 (Task → ModelCall / ToolCall / Policy / Verification, conventions OpenTelemetry GenAI, jamais de contenu) ; checklist « Observability ». Existant : `Tracer` en mémoire (500 spans), spans sans parent, aucun span côté passerelle modèle, rien de persistant.
Options considered: SDK OpenTelemetry Java (lourd, dépendances, exportateurs non nécessaires hors option) ; journal texte ; spans maison persistés dans Room + encodage OTLP/HTTP JSON écrit pour Cortana.
Decision: `Tracer` propage le span courant par le contexte de coroutine (`SpanContext`) : un span démarré dans un autre en devient l'enfant ; les spans de premier niveau d'une tâche pendent sous un span racine « task » à identifiant déterministe (`Tracer.rootSpanId`), enregistré à l'état terminal (`TaskStateMachine.onTerminal`) avec état, mode, source, contamination et compteurs. `ModelGateway` produit un span `gen_ai.chat` par tentative (fournisseur, modèle, rôle, outils offerts, jetons, coût, émulation, erreur), `gen_ai.embeddings` et `gen_ai.<média>` ; la synthèse finale est tracée (`task.synthesize`). Attributs : expurgés par le `Redactor`, bornés à 300 caractères, et toute clé porteuse de contenu (prompt, completion, content, messages, input, output, arguments, body, text, objective, query, reply) est ignorée — un span dit ce qui s'est passé, jamais ce qui a été dit. `SpanStore` persiste hors du fil appelant (table `spans`, file bornée à 5 000, lots) ; rétention réglable (7 jours par défaut, 50 000 lignes au plus) dans la maintenance. `ObservabilityService` : arbre d'une tâche, métriques sur une fenêtre (tâches par état et durée p50/p95, outils et modèles : appels, erreurs, latence p50/p95, jetons ; vérifications ; coût). Visualiseur local : écran Tâches → « Trace » (arbre, barres de chronologie, attributs) et carte « Métriques (24 h) ». Capacités L0 `observability.metrics` et `observability.trace`. Export OTLP/HTTP JSON (`/v1/traces`, ids hexadécimaux 32/16, `resource` service.name=cortana) : désactivé par défaut, adresse https (http seulement vers l'appareil ou le réseau local, pas d'identifiants dans l'URL), en-tête facultatif conservé dans le coffre, redirections refusées, chaque span envoyé une seule fois (colonne `exported`), échec → réessai à la maintenance suivante, audit des refus.
Why: diagnostiquer une tâche lente ou en échec sur la tablette même, sans dépendance lourde et sans jamais exposer de contenu ni de secret ; l'export reste un choix explicite du propriétaire vers son propre collecteur.
Compatibility impact: table `spans` ajoutée au schéma v2 (encore ouvert, D-013) et à la migration 1→2 ; réglages `otlpEnabled` (non), `otlpEndpoint`, `otlpHeaderHandle`, `spanRetentionDays` (7) sans migration ; identifiants de span désormais hexadécimaux (16) ; constructeur de `ModelGateway` : traceur facultatif.

### D-20260928-059 — Sauvegarde logique portable et chiffrable, restauration vérifiée, diagnostic de base
Status: accepted
Problem: roadmap phase 29 (export chiffré, restauration, réparation, contrôles d'intégrité, portabilité ; gate « backup sur instance A restauré sur instance B compatible ») ; doc 06 §12-14 (contenu d'une sauvegarde, secrets seulement en mode sécurisé explicite avec phrase de passe forte, restauration : manifeste, empreintes, compatibilité, simulation, sauvegarde préalable, transaction, rapport de conflits, fusion ou remplacement explicite, reconstruction des index dérivés ; Database Doctor et réparations réversibles) ; checklist « Data / Reliability ». Existant : copie `pre-migration` avant une migration seulement.
Options considered: copie brute du fichier SQLite (non portable entre schémas, secrets et index dérivés embarqués, pas de fusion) ; Android Auto Backup (hors du contrôle du propriétaire, secrets du Keystore non restaurables) ; export logique par table et par nom de colonne.
Decision: `core/backup/BackupService` écrit un zip `.cortana-backup` : `manifest.json` (format 1, `CompatibilityManifest` : version et code de l'application, schéma de base, version des contrats, protocole du worker, format de sauvegarde, schéma minimal restaurable ; tables ; empreinte SHA-256 et taille de chaque entrée ; nombre de lignes), un fichier JSON lines par table de données du propriétaire (23 tables : réglages, fournisseurs, capacités de modèles, autorisations, connexions, discussions, messages, résumés, mémoire et liens, procédures et leurs versions, exécutions et trajectoires, planifications, historique des tâches, étapes, appels d'outils, consommation, artefacts choisis, propositions d'amélioration, cas de test), les fichiers des artefacts choisis, et `secrets.json` (les seules valeurs des poignées `secret:` référencées) uniquement sur demande. Chiffrement facultatif, obligatoire avec les secrets : PBKDF2-HMAC-SHA256 (210 000 itérations, sel aléatoire) → AES-256-GCM par entrée (nonce aléatoire, nom de l'entrée en données associées : entrées non permutables), contrôle de clé, HMAC du manifeste ; phrase de passe de 12 caractères au moins. Les 18 autres tables restent sur l'instance, avec leur raison (index dérivés reconstruits, audit local non fusionnable, effets en attente jamais rejoués, états de reprise, projets et plugins sur disque, appairages, télémétrie) ; un test impose que toute table soit classée. Restauration : lecture bornée (noms d'entrées validés, 1,5 Go décompressés au plus, entrées non déclarées ou manquantes refusées), empreintes, déchiffrement, compatibilité (schéma plus récent refusé), simulation (fusion et remplacement), puis sauvegarde automatique de l'état actuel, écriture en une transaction par nom de colonne (colonnes inconnues ignorées et signalées — portabilité entre schémas), fusion = les lignes locales gagnent et les conflits sont comptés, remplacement explicite ; artefacts écrits après vérification de leur empreinte ; tâches non terminées de la sauvegarde closes (« restored ») ; index plein texte reconstruits, réglages rechargés, alarmes réarmées, index sémantique relancé ; secrets absents signalés, jamais inventés. `DatabaseDoctor` : 13 contrôles (quick_check — dont l'index FTS4, réparable par reconstruction —, WAL, version du schéma, sondage de l'index plein texte, fraîcheur des vecteurs, artefacts sans fichier et dossiers orphelins, procédures actives sans définition, tâches sans propriétaire, verrous de projets périmés, planificateur, secrets référencés absents, chaîne d'audit, espace disque) et réparations explicites, auditées, réversibles quand c'est possible (dossiers orphelins mis en quarantaine, artefacts perdus seulement marqués ; la chaîne d'audit n'est jamais « réparée »). Interface : Réglages → Sauvegarde et restauration (création, copie vérifiée dans Téléchargements, import par le sélecteur de documents, simulation puis fusion ou remplacement confirmé) ; Santé → Diagnostic de la base. Capacité L0 `doctor.check` ; aucune capacité ne sauvegarde, restaure ou répare.
Why: le propriétaire peut changer de tablette ou revenir en arrière sans perdre ses données ni exposer ses secrets, et sans dépendre d'un service externe ; toute restauration est vérifiable, simulable et annulable (état précédent sauvegardé).
Compatibility impact: aucun changement de schéma ; nouveau format de fichier `.cortana-backup` (format 1) ; `Hash.hex` rendu public ; `SettingsRepository.reload()`.

### D-20260928-060 — Mises à jour : manifeste signé par la clé de l'APK, vérifications système, installateur Android
Status: accepted
Problem: roadmap phase 30 (manifeste signé, téléchargement, empreinte et signature, remise à l'installateur, compatibilité ; gate « upgrade test conserve DB et signature ») ; blueprint §55 (vérification de version, métadonnées signées, empreinte, préflight de migration, sauvegarde avant mise à jour, instructions de retour arrière, diagnostic après ; APK signé, canal de confiance, aucun code exécutable téléchargé) ; checklist « Update / Release » ; règles : identité et signature de l'application inchangées, aucune publication automatique.
Options considered: une seconde clé de signature des manifestes (une clé de plus à conserver, à épingler dans le code) ; se fier au seul HTTPS (le serveur devient l'autorité) ; manifeste signé par la clé qui signe déjà l'APK, vérifié avec le certificat de l'application installée.
Decision: `core/update/UpdateService` : `SignedUpdate` (texte JSON exact du manifeste + signature SHA256withRSA) vérifié avec les clés publiques des certificats qui ont signé l'application installée (`PackageManager`, `GET_SIGNING_CERTIFICATES`) ; `UpdateManifest` (paquet, versionCode, versionName, adresse relative ou absolue de l'APK, SHA-256, taille, SDK minimal, SHA-256 du certificat, version minimale d'origine, `CompatibilityManifest`, notes). Refus : autre clé, texte modifié, autre paquet, autre certificat, schéma plus ancien, version intermédiaire requise, SDK insuffisant ; version égale ou inférieure = « à jour ». Préparation (action du propriétaire) : sauvegarde complète d'abord, téléchargement https (http seulement vers la boucle locale), redirections suivies à la main sous la même règle, taille bornée à celle annoncée, SHA-256 comparé, puis lecture de l'APK par Android (`getPackageArchiveInfo` : paquet, versionCode, signataires exactement le certificat attendu, SDK minimal) ; tout écart supprime l'APK. Installation (action du propriétaire) : empreinte revérifiée, `PackageInstaller` avec action utilisateur exigée (`InstallResultReceiver` affiche la confirmation d'Android, signale les échecs), permission `REQUEST_INSTALL_PACKAGES` (réglage « sources inconnues » ouvert si nécessaire). Vérification quotidienne facultative qui ne fait que prévenir. Aucune capacité ne prépare ni n'installe (règle d'architecture). Publication : `tools/make_update_manifest.py` (secrets par variables d'environnement, clé privée par tube, refus si l'APK n'a pas le certificat de Cortana, auto-vérification de la signature) ; `release/released.json` (historique monotone sous un seul certificat) et tâche Gradle `verifyReleaseVersion` sur `preReleaseBuild`. Stratégie de retour arrière documentée (`docs/RELEASE.md` §5).
Why: aucune clé nouvelle à perdre ; une mise à jour ne peut ni changer l'identité ni la signature de Cortana, ni venir d'un serveur compromis, ni s'installer sans le propriétaire ; les données sont toujours sauvegardées avant.
Compatibility impact: permission `REQUEST_INSTALL_PACKAGES` et récepteur `InstallResultReceiver` (non exporté) ajoutés au manifeste Android ; réglages `updateManifestUrl`, `updateAutoCheck` (sans migration) ; fichiers `release/released.json`, `tools/make_update_manifest.py`, `docs/RELEASE.md`.

### D-20260928-061 — Clients optionnels : administration du worker en ligne de commande, pas d'API locale sur la tablette
Status: accepted
Problem: roadmap phase 31 (« si le runtime worker/core expose une API locale : clients d'administration et diagnostic, sans logique métier dupliquée » ; gate « mêmes contrats/API ») ; blueprint §2.4-2.5 (web, CLI, TUI sans accès direct aux internes).
Options considered: exposer une API HTTP d'administration sur la tablette (nouvelle surface d'attaque sur un appareil personnel, contraire au choix « le cœur n'écoute pas le réseau ») ; une interface web servie par le worker (serveur et authentification supplémentaires pour un usage rare) ; des commandes d'administration du worker qui lisent les mêmes magasins que son API et rendent ses contrats.
Decision: le cœur Cortana (tablette) n'expose aucune API locale : son administration reste dans l'application (écrans Tâches, Santé, Réglages, Appareils), ce qui rend la condition de la phase sans objet côté cœur. Côté worker, qui expose déjà l'API appairée, `WorkerAdmin` (vue sans logique propre sur `DeviceStore`, `JobManager.list`, `HookStore.list`, capacités détectées) et les commandes `status`, `devices`, `jobs`, `hooks` (texte ou `--json` avec les contrats de `:contracts`). La liste des tâches ne renouvelle pas le bail des appareils ; les secrets des webhooks ne sont jamais affichés. Pas d'interface web ni TUI : la CLI suffit au diagnostic et n'ajoute aucun port.
Why: diagnostic du worker sans nouvelle surface réseau ni seconde implémentation ; le cœur reste sans port ouvert.
Compatibility impact: `WorkerServer.capabilities` devient public ; `JobManager.list()`, `HookStore.list()`, `HookSummary`, `WorkerAdmin` ; `cli(args, out)` extrait de `main` (mêmes commandes qu'avant, plus quatre).

### D-20260928-062 — Durcissement : sortie réseau, injection par dépôt, poignées de secrets, chaîne d'approvisionnement
Status: accepted
Problem: roadmap phase 32 (fuzz, sécurité, charge, batterie, crash, concurrence, révocation de permissions, pannes réseau, dépôt/build malveillant) ; checklist « Security » (secret handles, repository prompt injection defense, network egress policy, supply-chain checks, dependency verification, SBOM, license inventory) ; doc 06 §5, §8, §19, §23.
Options considered: filtrage réseau au seul niveau des outils (les services — fournisseurs, connecteurs, workers — y échapperaient) ; nettoyage du code lu dans un dépôt comme pour le web (il corromprait les lectures et les correctifs à empreinte) ; plugin CycloneDX Gradle (indisponible hors ligne, dépendance de build supplémentaire).
Decision: (1) Sortie réseau : `EgressRules` et réglages `egressMode` (`standard` = règle de contamination existante, `confirm_new` = toute destination nouvelle confirmée et jamais couverte par une autorisation permanente, `known_only` = destinations inconnues refusées) et `egressBlockedHosts` (suffixes) ; refus dans le moteur de politique pour chaque capacité à sortie externe, et `EgressGuard` sur le client HTTP partagé pour que rien — outils, fournisseurs, connecteurs, workers, OTLP, mises à jour — ne joigne un hôte bloqué ; écran Réglages → Réseau. (2) Injection par dépôt : le contenu d'un dépôt reste intact (enveloppé, tâche contaminée comme avant) mais `InjectionGuard.suspiciousLines` signale au modèle les lignes qui s'adressent à un assistant (numéros du fichier) et ajoute une source `injection:` ; dès lors aucune autorisation permanente n'est utilisée et toute action qui exécute du code, sort du réseau ou a un effet externe demande confirmation. (3) Poignées de secrets : aucun contexte de tâche ne résout une poignée (`resolveSecret` rend toujours null ; règle d'architecture), seuls les services propriétaires lisent les valeurs (règle d'architecture), `SecretInventory` liste les usages (fournisseurs, connexions, réglages), signale les valeurs manquantes et orphelines, remplace une valeur sous la même poignée (audité, valeur jamais journalisée) et purge les orphelines ; écran Réglages → Secrets. (4) Chaîne d'approvisionnement : `gradle/verification-metadata.xml` (SHA-256 de 525 composants, vérification à chaque build), tâche `cortanaSbom` (CycloneDX 1.5 de l'application et du worker, empreintes issues des métadonnées vérifiées, licences lues dans les POM du cache et de leurs parents, une correction documentée), `docs/LICENSES.md`, `SupplyChainTest` (aucune clé ni secret dans les fichiers suivis, coordonnées uniquement par le catalogue de versions, dépôt JitPack restreint à un groupe, aucun chargeur de code dynamique). (5) Concurrence : la prise de l'orchestrateur par une entrée est atomique (deux soumissions simultanées ne démarrent jamais deux tâches). (6) Fuzz et charge : `HardeningTest` (analyseurs de données externes : schémas, SSE, cron, MIME, HTML, manifeste de mise à jour, garde d'injection, sauvegardes ; appels d'outils malformés ou énormes ; absence de retour arrière catastrophique des expressions ; recherche et indexation sur 3 000 messages et 1 000 souvenirs).
Why: les trois voies par lesquelles un contenu hostile pourrait faire agir Cortana (destination, instruction, secret) sont fermées par une règle centrale et testée ; la chaîne de dépendances est vérifiable et inventoriée.
Compatibility impact: réglages `egressMode`, `egressBlockedHosts` (sans migration) ; `SecretAccess.handles()/remove()` ; `SecretStore.handles()` ; `gradle/verification-metadata.xml` (toute nouvelle dépendance doit y être ajoutée : `./gradlew --write-verification-metadata sha256 …`) ; tâche `cortanaSbom`.

### D-20260928-063 — Capacités et permissions, vue du plan, cycle de redémarrage, pannes réseau du worker
Status: accepted
Problem: checklist « Android » (permission health, reboot lifecycle) et « UI » (task plan view, permissions/capabilities screen) ; doc 05 §21 (écran unique Capacités & permissions : capacité, état, raison, risque, Corriger, dernière utilisation, révocation, dépendances) ; phase 32 (révocation de permissions, pannes réseau, crash).
Options considered: étendre l'écran Santé (liste d'accès sans lien avec les capacités) ; retirer du modèle les outils dont la permission manque (changerait silencieusement l'offre d'outils) ; un service qui croise registre, accès Android, autorisations permanentes et historique d'appels.
Decision: `core/permissions/CapabilityHealthService` : pour chaque capacité enregistrée, besoins d'accès Android (bloquants ou seulement conseillés : accessibilité, écoute des notifications, notifications, contacts, agenda, téléphone, SMS, alarmes exactes, paramètres système, dossier de travail, verrouillage de l'appareil pour L3/financier/identifiants/sécurité, installation, batterie), état (disponible, dégradée, indisponible, arrêtée par STOP) et raisons, risque, dernière utilisation (appels enregistrés), autorisations permanentes actives et leur révocation (auditée) ; `AccessProbe` lit Android à chaque fois (`AndroidAccessProbe`, aucune mise en cache : une révocation est vue au retour dans l'application) et donne l'écran système qui corrige. Écran « Capacités et permissions » (depuis Santé). Les exécuteurs refusent déjà avec un message clair quand une permission a été retirée (vérifié). Vue du plan : étapes du plan actif (statut, essais, dépendances, spécialiste, résultat), nombre de versions (replanifications) dans l'écran Tâches, étapes en direct dans la carte de la tâche active. Redémarrage : `BootReceiver` réarme et applique la politique de rattrapage, le démarrage du processus (`Maintenance.onStartup`) clôt les tâches et exécutions planifiées coupées — vérifié bout à bout. Worker : délais de requête et de réponse bornés côté serveur (300 s) pour qu'une connexion coupée en plein échange ne retienne jamais un fil du serveur.
Why: le propriétaire voit en un endroit ce que Cortana peut faire, pourquoi elle ne le peut pas, et comment corriger ou retirer une autorisation ; aucun état n'est deviné ni mis en cache.
Compatibility impact: aucune migration (requête `capabilityUses` sur `tool_calls`) ; route `capabilities` ; `HealthScreen(onCapabilities)` ; propriétés `sun.net.httpserver.maxReqTime/maxRspTime` fixées par le worker s'il ne les reçoit pas.

### D-20260928-064 — Version candidate 2.0.0-rc1 : même identité, schéma v2 figé, garde de signature, manifeste vérifié par l'application
Status: accepted
Problem: phase 33 et gate finale (APK release signé avec la même clé et un versionCode supérieur, archive source reproductible sans secret, note d'installation et de migration, tests non exécutables séparés) ; D-20260927-013 (v2 figé dès la publication) ; en 1.2.0, `assembleRelease` sans `keystore/keystore.properties` se repliait silencieusement sur la clé de debug : un APK ainsi signé ne s'installerait jamais par-dessus la version du propriétaire.
Options considered: publier directement « 2.0.0 » (affirmerait une validation sur tablette qui n'a pas eu lieu) ou une version candidate ; figer le schéma par une simple consigne ou par un test ; vérifier le manifeste produit par l'outil à la main ou par le vérificateur réel de l'application.
Decision: versionCode 2, versionName `2.0.0-rc1` ; `release/released.json` reçoit la version avec son schéma (`dbSchema` 2) et l'`identityHash` Room de chaque schéma publié (`dbIdentityHash`) ; `ReleaseTest.releasedSchemasAreFrozen` échoue si `1.json` ou `2.json` change : toute évolution passe par v3 et `MIGRATION_2_3`. `verifyReleaseVersion` refuse désormais un build release sans la clé de publication. Le certificat public (`release/signing-cert.pem`, extrait de l'APK) et le manifeste signé de la version (`release/cortana-update.json`, produit par `tools/make_update_manifest.py`) sont versionnés : `ReleaseTest.thePublishedManifestIsAnUpgradeOf120SignedByTheReleaseKey` le fait vérifier par `UpdateService.verifyManifest` et `compatibilityProblems` depuis une 1.2.0 installée, et refuse la même enveloppe modifiée d'un octet (la 1.2.0 n'ayant pas de mise à jour intégrée, le passage 1.2.0 → 2.0.0-rc1 se fait par installation manuelle par-dessus ; le canal intégré sert aux versions suivantes, et ce test garantit que le format produit par l'outil est bien celui que l'application accepte). Reproductibilité : deux constructions depuis `git archive` du même commit, dans deux répertoires distincts et sans cache de build, comparées octet par octet (la signature RSA PKCS#1 v1.5 est déterministe). Le premier contrôle a montré une seule entrée différente : `META-INF/version-control-info.textproto`, où AGP inscrit la révision Git du répertoire de build — absente d'une archive (pas de `.git`), et fausse pour un build de modifications non committées (le premier APK rc1 annonçait `4c8f9cf`, commit de la phase 32). Les builds release n'inscrivent donc plus cette information (`vcsInfo.include = false`) : la provenance est donnée par le rapport de publication et l'archive source. Les deux builds depuis l'archive différaient encore, uniquement dans le bloc de signature : la signature v2 était identique, mais AGP y ajoute pour Google Play un bloc « dependency info » (ID `0x504b4453`) chiffré avec une clé aléatoire à chaque build. Cortana n'étant pas distribuée par Play et le SBOM listant les dépendances, ce bloc est désactivé (`dependenciesInfo.includeInApk/includeInBundle = false`) ; l'archive source reconstruit alors l'APK à l'identique (`RELEASE.md` §7.4). La 2.0.0 finale (versionCode supérieur, même clé) n'est construite qu'après un rapport RC sans blocker critique (`docs/RC_CHECKLIST.md`).
Why: l'identité (paquet, certificat, monotonie) et le schéma publié sont protégés par des contrôles qui échouent, pas par de la discipline ; le chemin de mise à jour est prouvé avec l'artefact réel.
Compatibility impact: installation par-dessus la 1.2.0 (`minUpgradeFromVersionCode` 1) ; aucune donnée perdue (migration 1→2 validée, sauvegarde `pre-migration`).
Security impact: le magasin de clés et ses mots de passe restent hors du dépôt (`keystore/`, ignoré) et hors des livraisons ; l'outil de manifeste lit le mot de passe dans l'environnement ; l'archive source ne contient que les fichiers suivis par Git, vérifiée sans clé ni mot de passe.
Data migration: schéma v2 figé (`DATA_MIGRATIONS.md`).
Tests: `ReleaseTest` (2), `UpdateTest.releaseHistoryIsMonotoneUnderOneCertificate`.
Rollback: `RELEASE.md` §5.

### D-20260928-065 — DNS rebinding : l'adresse filtrée est celle de la connexion
Status: accepted
Problem: doc 06 (« DNS rebinding defense ») et doc 08 (scénario « DNS rebinding ») ; la ligne correspondante de `TEST_MATRIX.md` était restée « À venir (phase 32) » — relevée à la revue de la version candidate. Un nom qui répond une adresse publique puis une adresse privée contourne toute vérification faite avant la connexion.
Options considered: résoudre et vérifier avant la requête (fenêtre entre vérification et connexion) ; épingler l'adresse résolue par requête ; filtrer dans le `Dns` d'OkHttp, dont les adresses renvoyées sont exactement celles auxquelles il se connecte.
Decision: filtrage dans le `Dns` (déjà le cas depuis 1.2.0 pour les clients web), désormais une classe nommée `SsrfGuard.GuardedDns` avec résolveur amont injectable. Tout client qui lit des données extérieures l'utilise : outils web, navigateur, recherche, médias et fichiers A2A par `WebExecutor.client` ; MCP, A2A et connecteurs par `dnsFor`, où seule l'adresse configurée par le propriétaire est exemptée, par nom exact (un autre nom ne peut pas l'obtenir). Les adresses privées cachées dans des formes IPv6 (IPv4 mappée, compatible, préfixe NAT64 `64:ff9b::/96`) sont refusées. Le worker (HTTPS à certificat épinglé, requêtes signées) n'est pas exposé à un navigateur rebindé.
Why: sans seconde résolution, il n'y a pas de fenêtre à exploiter.
Compatibility impact: aucun (même comportement pour les adresses publiques).
Security impact: ferme le scénario ; ligne de la matrice exécutée.
Data migration: aucune.
Tests: `HardeningTest.dnsRebindingNeverReachesAPrivateAddress` (réponse publique puis privée, réponse mixte, formes IPv6, client web réel).
Rollback: aucun nécessaire.

### D-20260928-066 — Expressions régulières compilées par le moteur d'Android (ICU4C), pas seulement par la JVM
Status: accepted
Problem: la 2.0.0-rc1 plante au démarrage sur la tablette (rapport de bug du propriétaire) : `ExceptionInInitializerError` dans `AppContainer.<init>` (`CortanaApp.kt:366`), causée par `PatternSyntaxException` à l'initialisation statique de `SkillService` — le motif `\{\{([a-zA-Z0-9_]+)}}` se termine par des accolades non échappées. La JVM des tests les accepte comme littéraux ; sur Android, `java.util.regex.Pattern` est adossé à ICU4C (`RegexPattern::compile`, avec `UREGEX_ERROR_ON_UNKNOWN_ESCAPES`), qui les refuse. Les 317 tests exécutés tournent tous sur la JVM et ne pouvaient pas le voir ; seule la matrice physique (`RC_CHECKLIST.md` §1.2, non exécutée) l'aurait vu.
Options considered: corriger ce seul motif ; relire les ~330 appels `Regex(...)` à la main ; imiter les règles d'ICU dans un test JVM (heuristique, divergences inévitables) ; compiler chaque motif avec la vraie bibliothèque ICU4C.
Decision: motif corrigé (`\{\{([a-zA-Z0-9_]+)\}\}`). `tools/check_android_regex.py` extrait chaque expression régulière écrite dans `app/src/main` et `contracts/src/main` (littéraux simples et bruts, échappements Kotlin, concaténations ; gabarits remplacés par un littéral neutre et marqués « dynamiques ») et la compile avec ICU4C (`uregex_open`, mêmes options qu'Android : CASE_INSENSITIVE, COMMENTS, MULTILINE, DOTALL, UNIX_LINES + erreur sur échappement inconnu). Il tourne dans les tests unitaires (`AndroidRegexCompatTest`) et avant toute construction release (tâche `androidRegexCheck` sur `preReleaseBuild`) : aucune release ne peut plus embarquer un motif refusé par ICU. Il écrit aussi la liste des motifs dans `app/src/androidTest/assets/regex_patterns.json`, que le test instrumenté `StartupAndRegexEngineTest` compile sur l'appareil avec le moteur d'Android, à côté du démarrage réel de l'application (`CortanaApp.onCreate`, écran principal) et de l'initialisation de chaque classe de l'application dans ART.
Second défaut de processus trouvé en préparant la rc2 : Gradle jugeait les tâches de test « à jour » quand seuls changeaient des fichiers que les tests lisent sans les déclarer (`release/`, arbre Git) ; il ne les relançait pas. Le dernier « 317 tests, 0 échec » de la rc1 reposait sur de tels résultats : à l'état final de la rc1, `SupplyChainTest` échouait (le certificat public `release/signing-cert.pem` était pris pour du matériel de clé — faux positif, mais un échec). Désormais toute tâche `Test` s'exécute à chaque fois (`upToDateWhen { false }`, pas de cache), et le contrôle des clés distingue un certificat public d'une clé privée.
Why: seul le moteur réel dit ce qu'Android acceptera ; la vérification tourne à chaque test et bloque la release, sans dépendre d'une relecture. Un résultat de test annoncé doit provenir d'une exécution réelle.
Compatibility impact: aucun pour les données ; 2.0.0-rc2 (versionCode 3) s'installe par-dessus la rc1 et la 1.2.0. La construction release exige désormais `python3` et la bibliothèque ICU4C (`libicu`) sur la machine de build.
Security impact: aucun changement de politique. Différence de sémantique connue, non couverte par la compilation : sous ICU, `\w`, `\d`, `\s`, `\b` et `(?i)` sont Unicode (la JVM les limite à l'ASCII) — les motifs de sécurité de Cortana utilisent des classes explicites ou détectent plus largement ; voir `SECURITY.md`.
Data migration: aucune (schéma v2 inchangé, figé).
Tests: `AndroidRegexCompatTest` (ICU4C sur tous les motifs, syntaxe des paramètres de procédure) ; `SupplyChainTest.noSecretOrKeyMaterialIsTracked` (certificat public admis, clé privée refusée) ; `StartupAndRegexEngineTest` (A : démarrage, motifs, initialisation des classes — écrit et compilé, non exécuté ici).
Rollback: aucun ; la rc1 est inutilisable (plantage au démarrage).

### D-20260929-067 — Conseil de réflexion (Cognitive Council Engine) : une sous-opération de la tâche, pas un second cerveau
Status: accepted
Problem: `CORTANA_COUNCIL_ENGINE_CLAUDE_HANDOFF` (docs/council_pack, 22 fichiers) demande qu'une demande complexe puisse être analysée par plusieurs spécialistes temporaires en parallèle (rôles, modèles et fournisseurs différents), qui confrontent leurs arguments avant une réponse unique — sans second orchestrateur, second accès aux modèles, second registre d'outils, seconde politique ni seconde mémoire, sans chaîne de pensée persistée, sans secret dans une invite, sans effet de bord hors politique, désactivé par défaut. Voir `docs/COUNCIL_MAPPING.md` (correspondance concept → classe, conflits) et `docs/COUNCIL_PROGRESS.md` (rapports C1→C12).
Options considered: (a) un framework multi-agents externe — interdit par le pack ; (b) des tâches enfants par agent, écrites dans la conversation (comme les spécialistes, phase 23) — viole l'indépendance du tour 0 et multiplie les tâches ; (c) un moteur `core/council` appelé par l'orchestrateur, qui raisonne en mémoire et rend un résultat structuré.
Decision: (c).
- Sélection : `RuleCouncilPolicySelector` choisit Désactivé / Rapide / Renforcé / Conseil 4 / Approfondi / Personnalisé ; il ne choisit rien si le drapeau est coupé (défaut), pour une demande simple, un raccourci, à l'intérieur d'un conseil, sous budget insuffisant ou plafond quotidien atteint ; batterie faible et budget restant dégradent.
- Planification : `CouncilPlanner` fixe préréglage (10), rôles (4 standard, 24 dynamiques, juge et synthèse internes), routes de modèle par rôle avec replis, outils en lecture seule seulement (aucun effet, ≤ L1, ni écran, ni question au propriétaire, ni écriture mémoire), budgets et quorum, et dégrade dans l'ordre documenté (tours, arguments, sorties, juge, 4 → 2, un seul).
- Exécution : tour 0 indépendant et réellement parallèle (sémaphores global et par fournisseur), puis tours de confrontation où chaque agent ne reçoit que les arguments retenus pour lui (topologies, objections critiques toujours transmises, protection de la minorité).
- Décision déterministe (10 protocoles) : une majorité n'est jamais une preuve. Une objection critique non résolue bloque, et une preuve d'outil qui contredit la majorité la renverse. Une majorité claire, non bloquée et non contredite l'emporte à la décision finale même faiblement étayée (consensus annoncé faible) ; les égalités restent des égalités.
- Fin de séance : défi final avec mini-tour de réparation, synthèse unique, vérification (une réparation au plus).
- Tous les appels passent par le `ModelGateway` (`allowFallback = false` pour les agents, replis par rôle explicites) et le `ToolDispatcher` (`interactive = false` : une action à confirmer est refusée, jamais demandée).
- 413 : réduction avant envoi (budget de contexte), jamais la même charge deux fois, limite apprise, route plus large si permise. 429 : `Retry-After` respecté une fois, puis repli.
- Sortie JSON stricte avec une réparation, sans outil ; un appel d'outil glissé dans la sortie est un rejet.
- `CouncilMarker` interdit la récursion ; STOP annule chaque agent (séance CANCELLED, résultats tardifs ignorés).
- L'orchestrateur garde la tâche, ses états, ses compteurs et toute action : une décision qui implique une action repasse par le chemin normal, sous la politique et ses confirmations. Quatre agents d'accord n'autorisent rien.
Why: seul (c) respecte « un orchestrateur, une passerelle, un registre, une politique, une mémoire » et l'indépendance du tour 0 ; les invariants existants (budgets de tâche, taint, audit, spans, STOP) s'appliquent sans duplication.
Compatibility impact: drapeau `council.enabled = false` par défaut : sans action du propriétaire, le comportement est celui de la rc2 (test « OFF non régressif »). Extensions additives : `GatewayResult.httpCode/retryAfterMs` (y compris après un échec réessayable), plafond de sortie, température et `reasoning_effort` par appel ; `DispatchRequest.interactive` ; `Verifier.verifyAnswer` ; `SchemaValidator` (`const`, listes de types, `enum` sans type).
Security impact: pas de secret pour les agents (`resolveSecret` → null), pas d'outil à effet, pas de conversation écrite par un agent, contenu d'outil non fiable enveloppé et propagé en taint jusqu'à la tâche, pas de raisonnement ni d'invite persistés (tables v3 : résumés structurés expurgés), lois vérifiées par `ArchitectureRulesTest` (subordination, pas de persistance de raisonnement, pas de récursion, pas de framework multi-agents).
Data migration: schéma v3, migration additive `MIGRATION_2_3` (9 tables `council_*` et leurs index), testée 2→3 et 1→3 ; sauvegardes : tables incluses ; au démarrage, un conseil interrompu est clos (jamais repris : il n'avait aucun effet à rejouer).
Tests: `CouncilLogicTest` (10), `CouncilTest` (23, conteneur réel contre des modèles scriptés : parallélisme, multi-modèle et multi-fournisseur, quorum et partiel, 413, 429, délai, JSON invalide, STOP, repli, faux consensus, injection, secrets, récursion, confidentialité locale, budgets, juge anonyme, persistance, métriques), `CouncilGoldenSetTest` (52 tâches, banc déterministe et ablations), `DatabaseMigrationTest` (v3), `ArchitectureRulesTest` (lois du conseil). Qualité avec de vrais modèles (B0–B7) : BLOCKED_EXTERNAL.
Rollback: couper le drapeau neutralise le moteur sans supprimer de données ; un retour à la rc2 (schéma v2) est refusé par Room (pas de migration descendante) : sauvegarder puis réinstaller, comme documenté dans `DATA_MIGRATIONS.md`.

### D-20260930-068 — Chat Workspace : une couche de présentation et de contrats sur les services existants
Status: accepted
Problem: `CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF` (docs/chat_workspace_pack, 19 fichiers) demande un espace de discussion moderne sans second backend conversationnel. Ses exigences : branches, édition et variantes, continuation, file d'attente, flux reprenables, pièces jointes, rendu riche, citations, contexte visible, compactage, artefacts, approbations, comparaison multi-modèle, pont vers le conseil, recherche, voix, disposition responsive et accessibilité. Ses interdits : pas de second ModelGateway, ToolRegistry ou store mémoire, pas de chaîne de pensée à l'écran, pas d'approbation implicite, pas de suppression lors d'une édition, pas de réessai aveugle. Correspondance et conflits : `docs/CHAT_WORKSPACE_MAPPING.md` ; rapports H1→H12 : `docs/CHAT_WORKSPACE_PROGRESS.md`.
Options considered:
- (a) un nouvel écran et un nouveau service de conversation à côté de l'existant — interdit (second backend) ;
- (b) les tables proposées par le paquet (`message_parts`, `branches`) — deux copies du contenu, source de divergence ;
- (c) un arbre dans le store existant (parent, feuille active), des parts dérivées, et une façade `ChatService` qui ne fait que placer le tour dans l'arbre et confier la génération à l'orchestrateur unique.
Decision: (c).
- Schéma v4 additif : parent et feuille active ; statut (`complete`, `streaming`, `stopped`, `interrupted`, `error`) ; métadonnées ; tables projets, brouillons, file, épingles et points de compactage. Le modèle ne reçoit que le chemin actif.
- `ChatStreamHub` : runId, séquence, lots de 60 ms, instantané crash-safe. STOP garde le texte. Un repli de fournisseur remet le texte vivant à zéro.
- Édition, régénération, continuation, fusion et comparaison passent par `TaskRequest.contextHints` (`ChatHints`) → `Orchestrator.handleTurn`. Les raccourcis déterministes ne sont jamais rejoués.
- 413 : une seule réduction automatique (fenêtre divisée par deux), puis un événement structuré avec réduire, changer de modèle ou nouvelle discussion.
- Comparaison : `CompareRunner`, sous-opération de l'orchestrateur. 2 à 4 modèles en parallèle, sans outils, réponses sœurs du message, arrêt par couloir. La fusion est une demande visible : les réponses y sont des données, et la majorité n'est jamais une preuve.
- Conseil : le mode « Conseil » est une demande explicite transmise au `CouncilGate`. Il ne contourne jamais `council.enabled = false`.
- Approbations : la carte du fil peut refuser, ou rouvrir l'écran sécurisé (FLAG_SECURE). Seul cet écran autorise.
- Pièces jointes = artefacts. Leur contenu entre dans le contexte comme données non fiables enveloppées, et la tâche est marquée.
- Rendu natif Compose (Markdown maison, TexLite, Mermaid en source) : aucun HTML ni WebView ; liens assainis.
- L'interface classique reste disponible (Réglages › Espace de discussion).
Why: seul (c) garde « un orchestrateur, une passerelle, un registre, une politique, une mémoire ». Budgets, STOP, taint, audit et traces s'appliquent sans duplication. Une conversation linéaire existante se lit exactement comme avant.
Compatibility impact: migration 3→4 additive ; chaque session existante devient une branche unique dans l'ordre historique. `GatewayResult.finishReason` et `ModelGateway.complete(onReset)` sont des ajouts. `CouncilSelectionInput.explicitRequest` aussi. `Speaker` lit désormais par phrases, avec pause et reprise ; `VoiceLoop.interrupt` est ajouté. L'interface par défaut devient le Workspace.
Security impact:
- Aucun composant du chat n'approuve.
- Pas de HTML, pas de raisonnement affiché, messages cachés jamais rendus.
- Partage Android : brouillon seulement, `file://` d'une autre application refusé.
- Noms de fichiers assainis ; export sans message caché, sans sortie brute d'outil, secrets masqués.
- File revalidée après délai ou redémarrage.
- Lois vérifiées par `ArchitectureRulesTest` : WORKSPACE-1 à 3, comparaison sans outil, partage sans envoi.
Data migration: v4, `MIGRATION_3_4`, testée 3→4 et 1→4 ; sauvegardes : projets, brouillons, épingles et points de compactage inclus ; la file est exclue (jamais envoyée sur une autre instance) ; une base restaurée sans parents est reliée au premier accès.
Tests:
- `ChatRenderLogicTest` (8), `ChatTreeTest` (5), `ChatWorkspaceServiceTest` (7), `ChatWorkspaceFeaturesTest` (10), `ChatHardeningTest` (5), `WorkspaceUiTest` (9, Compose sous Robolectric : téléphone, tablette, accessibilité, clavier).
- `DatabaseMigrationTest` (+2), `VoiceTest` (+1), `ArchitectureRulesTest` (+4).
- Vraie tablette, TalkBack réel, réseau réellement instable, batterie et fuites mémoire : BLOCKED_EXTERNAL.
Rollback: Réglages › Espace de discussion › Interface classique (mêmes données). Un retour à la rc3 (schéma v3) est refusé par Room : sauvegarder puis réinstaller (`DATA_MIGRATIONS.md`).

## Historique 1.2.0 (format libre d'origine, conservé tel quel)

Recorded during the one-pass build (2026-09-27). Each entry: decision, reason, blueprint section.

## Toolchain (pinned, not changed mid-build) — §2

| Item | Version |
|---|---|
| Gradle wrapper | 8.14.3 |
| Android Gradle Plugin | 8.13.2 (latest 8.x, as required by §2; AGP 9 exists but is out of scope) |
| Kotlin / Compose compiler plugin | 2.2.21 |
| KSP | 2.2.21-2.0.5 |
| JDK used to build | OpenJDK 21.0.10 (bytecode target 17) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 30 |
| Android SDK build-tools | 36.0.0 (35.0.0 also installed) |
| Compose BOM | 2025.10.01 |
| AndroidX core-ktx / activity-compose / lifecycle / navigation | 1.17.0 / 1.11.0 / 2.9.4 / 2.9.5 |
| Room / WorkManager / Biometric / DocumentFile | 2.8.3 / 2.10.5 / 1.1.0 / 1.1.0 |
| Fragment (pinned explicitly) | 1.8.9 — biometric 1.1.0 would otherwise pull fragment 1.2.5, far older than activity 1.11 |
| Resolved Compose artifacts | ui/foundation/runtime 1.9.4, material3 1.4.0 |
| kotlinx-serialization-json / kotlinx-coroutines | 1.9.0 / 1.10.2 |
| OkHttp (+ MockWebServer for tests) | 4.12.0 |
| JUnit / Robolectric / androidx.test | 4.13.2 / 4.16 / core 1.7.0, runner 1.7.0, ext-junit 1.3.0 |

Library versions were chosen from the late-2025 line that is known to support compileSdk 36 with AGP 8.13; newer 2026 AndroidX releases may require compileSdk 37 / AGP 9.

## Decisions

- **D-ID — application id** `io.github.artisanguillonrenov.cortana` (reverse domain derived from the owner's GitHub account; hyphen removed because package names cannot contain one). Debug builds use the suffix `.debug` so both can coexist.
- **D-DI — manual constructor injection** instead of Hilt (§2 allows it). One `AppContainer` built in `CortanaApp.onCreate`. Fewer annotation processors, faster and more reliable single-pass build.
- **D-SECRETS — own Keystore wrapper** (AES-256-GCM key in AndroidKeyStore, ciphertext in a private SharedPreferences file) instead of the deprecated `androidx.security:security-crypto`. Same guarantees as §9.5; values are registered with the redactor at startup.
- **D-MODULE — single `:app` module** with the package layout of §3 (`core/`, `executors/`, `service/`, `ui/`, `util/`).
- **D-FUNCNAME — tool function names**: OpenAI function names cannot contain dots, so capability `web.fetch` is exposed to the model as `web_fetch`; the registry maps both ways. Emulated calls with dotted names are accepted and normalised.
- **D-TOOLSETS — per-session toolsets** “Discussion” (no tools → direct mode), “Assistant” (service, web, files, system) and “Complet” (+ screen control). Default: Complet.
- **D-STOP-CHAT — STOP keeps conversation available**: while halted, new turns run in direct mode with no tool offered; running tasks are cancelled immediately; scheduled *tasks* are skipped and recorded. Scheduled *reminders* (plain notifications, no model, no autonomy) are still delivered — the owner asked for them and they cannot act on anything.
- **D-FASTPATH — deterministic fast paths** for the two most common requests: “Rappelle-moi dans/à … de …” creates the reminder with **no model call at all** (§12), and “Retiens que …” saves the memory immediately (explicit write, §11) and tells the model it is done. This makes the acceptance checks of §19 independent of model quality.
- **D-EXPLICIT — explicit memory check**: `memory.save(explicit=true)` only writes an *active* memory if the owner's message actually contains an explicit phrase (retiens, souviens-toi, mémorise, n'oublie pas, garde en mémoire, note que, remember). Tainted tasks always write *pending*.
- **D-FGS — foreground service `specialUse`** started only when Android allows it: while the app is in the foreground, or within 8 s of an exact-alarm fire. Otherwise the task runs without it (starting an FGS that cannot call `startForeground` would crash the app on Android 12+).
- **D-VOICE — push-to-talk via the system recogniser** (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, fr-FR, `EXTRA_PREFER_OFFLINE`) and `TextToSpeech` for output. Capture happens only while Cortana is visible, in the system recogniser, so no microphone foreground service is needed (§13's `microphone` FGS is only required for background capture, which v1.2 does not do).
- **D-CLEARTEXT — `usesCleartextTraffic=true`** so the “Serveur local” preset (Ollama/LM Studio over `http://` on the LAN) works. Web tools still pass through the SSRF guard, which blocks private/loopback/link-local/CGNAT/ULA addresses unless the owner enables “réseau local” in Réglages.
- **D-TAKEOVER — user takeover detection** uses `TYPE_VIEW_CLICKED`, `TYPE_VIEW_LONG_CLICKED` and `TYPE_TOUCH_INTERACTION_START` events not caused by Cortana within a 2 s correlation window. Scrolling/text events are excluded to avoid false positives from apps updating themselves.
- **D-APPROVAL-BINDING — approvals bound to the exact action**: the approval carries a hash of capability + canonical arguments; for UI actions the executor re-classifies the live target at execution and refuses if it became riskier than what was approved.
- **D-IDEMPOTENCY — anti-repeat** applies to non-idempotent “keyed” tools (alarm, timer, share, file write/patch, notify, schedule create): the same call with the same arguments succeeds only once per task. UI taps are not keyed (tapping “Suivant” twice is legitimate).
- **D-RESTRICTED — restricted-settings detection** uses the app-op `android:access_restricted_settings` (best effort; reported as “unknown” if unreadable) plus step-by-step French guidance.
- **D-AAPM — Advanced Protection** (Android 16) is read by reflection (`AdvancedProtectionManager`) and only reported; never required.
- **D-RESUME-NOLOCK — resume without a device lock**: if the tablet has no screen lock there is nothing to verify; resume is allowed, audited as `kill_switch.resume_unverified`, and the owner is told to configure a lock. L3 actions are still always refused without a lock.
- **D-I18N — French strings in code**: owner-facing strings are French and written directly in the Compose code (single locale, faster one-pass build). The system prompt is a versioned asset (`assets/prompts/system_fr.txt`, prompt-version 1.2.0).
- **D-ABI — arm64-v8a APK + universal fallback** (§2.1) via Gradle ABI splits. The only native code is Compose's tiny `libandroidx.graphics.path.so`, so both APKs are almost the same size; either installs on the Galaxy Tab A11 (arm64).
- **D-MINIFY — R8 off** for the first APK (§18.2).
- **D-SEARCH — web search providers**: DuckDuckGo HTML (no key, default), Brave Search (key), SearXNG (owner URL), behind a `SearchProvider` interface (§8.5).
- **D-TESTS — no emulator in the build environment** (no `/dev/kvm`): instrumented tests against the fixture Activity are written and compiled but not run. Logic is covered by JVM + Robolectric tests, including end-to-end orchestrator tests against a scripted OpenAI-compatible server.

## Deferred to Extensions (§20)

Planned/DAG mode, screenshot + vision fallback, telephony/contacts/notification listener/calendar, embeddings retrieval, native Anthropic/Gemini adapters (OpenRouter covers them), wake word, MCP client, software-factory tools, R8 minification.
