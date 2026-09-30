# Migrations de données

Règles (doc 06 §9) : migrations Room explicites, jamais `fallbackToDestructiveMigration` (interdit par test d'architecture), schéma exporté dans `app/schemas/`, test de conservation pour chaque version, copie de sauvegarde avant migration.

## Sauvegarde automatique avant migration

`PreMigrationBackup.runIfNeeded()` (dans `core/memory/Migrations.kt`) est appelé par `CortanaDatabase.build()` avant l'ouverture Room :

1. `PRAGMA wal_checkpoint(TRUNCATE)` puis lecture de la version du fichier existant ;
2. si elle est inférieure à la version cible : copie vers `files/backups/pre-migration/cortana.db.v<ancienne>-to-v<cible>.<horodatage>.db` ;
3. conserve les 3 dernières copies.

Retour arrière manuel : réinstaller la version précédente (même signature) et remplacer `databases/cortana.db` par la copie (possible via `adb` en debug, ou via la restauration de la phase 29).

## Version 1 → 2 (VNext)

**v2 est figée depuis la 2.0.0-rc1** (D-20260927-013, D-20260928-064) : `release/released.json` enregistre l'`identityHash` Room de chaque schéma publié (v1 `4cf277f7…`, v2 `824dbfa8…`) et `ReleaseTest.releasedSchemasAreFrozen` échoue si `1.json` ou `2.json` change. Toute évolution passe par `CortanaDatabase.VERSION = 3`, un `MIGRATION_2_3` explicite, `3.json` et un test de conservation 2→3 (et 1→3 par la chaîne). Tables de v2, par phase :

| Élément | Changement |
|---|---|
| `tasks` | colonnes ajoutées avec défaut : `source`, `parentTaskId`, `planId`, `currentStepId`, `traceId`, `stepCount`, `replanCount`, `errorJson`, `requestJson`, `updatedAt`, `leaseOwner`, `leaseExpiresAt` ; index `sessionId`, `state`, `parentTaskId` |
| nouvelles tables | `task_events`, `plans`, `checkpoints`, `task_notebooks`, `idempotency_ledger`, `approvals`, `grants`, `outbox` (clé de déduplication unique), `artifacts`, `conversation_summaries` (phase 6), `memory_vectors` (index dérivé, rempli au démarrage), `memory_edges` (phase 7), `skills`, `skill_versions`, `skill_runs`, `skill_trajectories` (phase 8), `workspaces`, `changesets` (phase 9), `workers` (phase 12), `plugins`, `plugin_versions` (phase 22) |
| remplissage | `traceId` = identifiant de la tâche, `updatedAt` = `endedAt` ou `createdAt` |
| états hérités | `limit` → `failed` (raison préfixée `budget_exhausted:`) · `waiting_user` → `completed` (raison préfixée `question_asked_v1:`) · `running` → `interrupted` |
| événements | un événement `mig-<taskId>` par tâche (`toState` = état après traduction, `actor` = `migration`) |
| relations | une relation `supersedes` par souvenir ayant `supersedesId` (phase 7) |
| ledger | chaque `tool_calls` avec `idempotencyKey` et `outcome = ok` devient une entrée `succeeded` |
| réglages | la liste `grants` des réglages est convertie en lignes `grants` au premier démarrage (`GrantService.migrateLegacySettingGrants`), puis vidée |

Test : `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes` crée une base v1 réaliste (sessions, messages avec accents, mémoires actives et remplacées, fournisseur, planification, audit, réglages, 4 tâches dans chaque état, appel d'outil avec clé), migre avec `MigrationTestHelper.runMigrationsAndValidate` (validation contre `2.json`), vérifie chaque ligne, la traduction des états, les événements synthétiques, le ledger et la recherche plein texte. `DatabaseMigrationTest.preMigrationBackupIsWrittenForOlderSchema` vérifie la copie.

Compatibilité descendante : une 1.2.0 ne peut pas ouvrir une base v2 (Room refuse une version supérieure) → le retour arrière passe par la copie `pre-migration`.

## Version 2 → 3 (Conseil de réflexion, 2.0.0-rc3)

**Migration purement additive** (D-20260929-067, doc 06 §6.1, doc 18 §18.3) : `MIGRATION_2_3` crée neuf tables et leurs index, sans toucher à une table ni à une ligne existante. Identité du schéma v3 : `2ff68b15f0073a3cbe24c19815d91718` (`app/schemas/…/3.json`), enregistrée dans `release/released.json` à la publication de la rc3 et figée ensuite comme v1 et v2.

| Table | Contenu (résumés structurés uniquement) | Index |
|---|---|---|
| `council_runs` | séance : tâche parente, mode, préréglage, statut, dates, configuration (expurgée), raison de fin, jetons, coût (null si inconnu), durée, résumé (carte), métriques | `parentTaskId`, `createdAt`, `status` |
| `council_agent_slots` | rôle, « fournisseur · modèle » (+ replis), empreinte de la portée d'outils, statut | `runId` |
| `council_rounds` | tour (initial, confrontation, défi, réparation), accord, divergence, couverture de preuves, dates | `runId` + `roundIndex` |
| `council_contributions` | candidat, hypothèses, vérifications demandées, justification courte (≤ 300 caractères), confiance, jetons, latence, code d'erreur | `roundId`, `agentSlotId` |
| `council_claims` | affirmation (nettoyée, expurgée), type, confiance, taint, statut de preuve | `contributionId`, `verificationStatus` |
| `council_evidence_refs` | référence (`tool:N`, `user`, URL…), type de source, confiance (fiable, non fiable, non vérifiée) | `claimId` |
| `council_concerns` | objection : gravité, cible, résumé | `contributionId` |
| `council_votes` | bulletin (classement, approbations, points) | `roundId` |
| `council_decisions` | protocole, candidat retenu, scores, rapport de minorité, objections non résolues | `runId` |

Jamais stockés (loi vérifiée par `ArchitectureRulesTest.councilNeverPersistsReasoningOrPrompts`) : invites, sorties brutes des modèles, raisonnement privé, blocs de raisonnement des fournisseurs, secrets.

Tests : `DatabaseMigrationTest.v2ToV3IsAdditiveAndTheCouncilStoreWorks` (base v1 réaliste migrée en v2, puis en v3 validée contre `3.json` : mêmes comptes dans chaque table, neuf tables vides, index présents ; Room ouvre le fichier et `CouncilStore` fonctionne), `v1ToV3InOneUpgrade` (1.2.0 → v3 en une mise à jour), `CouncilTest` (lignes écrites par une vraie séance, séance annulée marquée `cancelled`).

Reprise après arrêt du processus (doc 08 §8.8) : au démarrage, `CouncilStore.recoverInterrupted()` clôt toute séance non terminée (`failed`, « interrompu : processus arrêté ») ; une séance n'est jamais reprise au milieu — elle n'avait aucun effet à rejouer ; la tâche suit la reprise habituelle de l'orchestrateur.

Sauvegardes : les neuf tables sont incluses (`BackupService.INCLUDED`) ; une sauvegarde v2 se restaure dans v3 (tables du conseil laissées vides) ; une sauvegarde v3 est refusée par une application v2 (« schéma plus récent »).

Retour arrière : Android refuse d'installer une version plus ancienne (versionCode 4 → 3) sans désinstallation, et une application v2 ne peut pas ouvrir une base v3 (Room n'a pas de migration descendante). Couper le conseil (Réglages › Intelligence) suffit à le neutraliser sans perdre de données ; revenir réellement à la rc2 demande de désinstaller puis de restaurer une sauvegarde faite sous la rc2 ou la copie `pre-migration` (`cortana.db.v2-to-v3.*.db`).

## Version 3 → 4 (Chat Workspace, 2.0.0-rc4)

**Migration additive** (D-20260930-068) : `MIGRATION_3_4` ajoute des colonnes avec valeur par défaut, cinq tables et leurs index, puis relie l'historique existant. Aucune ligne n'est supprimée ni modifiée dans son contenu. Identité du schéma v4 : `9abbf3f6376ca6789cd7631f2cd4cdd7` (`app/schemas/…/4.json`), enregistrée dans `release/released.json` à la publication de la rc4.

| Table | Ajout | Rôle |
|---|---|---|
| `messages` | `parentId` (index), `status` (défaut `complete`), `runId`, `metaJson` | arbre de conversation (branches, versions, variantes), état d'une réponse (`streaming`, `stopped`, `interrupted`, `error`), métadonnées (pièces jointes, modèle, comparaison, continuation) |
| `sessions` | `activeLeafId`, `projectId`, `mode` (défaut `chat`), `pinned`, `archived`, `tagsJson` (`[]`), `settingsJson` (`{}`) | branche affichée, organisation, réglages par discussion (modèle, mémoire, héritage du projet, comparaison, réduction du contexte) |
| `conversation_summaries` | `coveredUntilMessageId` | un résumé n'est réutilisé que sur une branche qui contient son dernier message couvert |
| `projects` (nouvelle) | nom, instructions héritées, modèle préféré | index `updatedAt` |
| `chat_drafts` (nouvelle) | brouillon et pièces jointes par discussion | — |
| `chat_queue` (nouvelle) | messages en file, position, état (`queued`, `confirm`, `confirmed`, `sending`) | index `sessionId`, `position` |
| `chat_pins` (nouvelle) | message, fichier ou note épinglés au contexte | index `sessionId` |
| `context_checkpoints` (nouvelle) | points de compactage visibles (résumé, couverture, méthode) | index `sessionId`, `createdAt` |

Remplissage : chaque message reçoit pour parent le précédent de sa discussion (ordre `createdAt`, puis ordre d'insertion), et chaque discussion sa feuille active = son dernier message. Une conversation se lit donc exactement comme avant. Une base restaurée d'une ancienne sauvegarde (messages sans parent, discussion sans feuille) est reliée de la même façon au premier accès (`ConversationRepository.healUnlinked`).

Tests :
- `DatabaseMigrationTest.v3ToV4ChainsEachConversationInItsHistoricalOrder` : parents, feuilles, égalités de date, valeurs par défaut, tables vides, FTS intact ; Room ouvre la base, lit le chemin et poursuit la branche.
- `v1ToV4InOneUpgrade` : 1.2.0 → v4.
- `ChatTreeTest` : arbre, réparation, instantanés.

Sauvegardes :
- incluses : `projects`, `chat_drafts`, `chat_pins`, `context_checkpoints` ;
- exclue : `chat_queue`, dont un message ne doit jamais partir automatiquement sur une autre instance ;
- une sauvegarde v3 se restaure dans v4 (messages reliés au premier accès) ; une sauvegarde v4 est refusée par une application v3.

Retour arrière : l'interface classique reste disponible sur les mêmes données. Revenir à la rc3 demande de désinstaller puis de restaurer une sauvegarde faite sous la rc3, ou la copie `pre-migration` (`cortana.db.v3-to-v4.*.db`).

## Réglages et contrats ajoutés sans migration

Les réglages sont stockés une ligne par champ ; un champ absent prend sa valeur par défaut, donc aucun ajout ne demande de migration :

| Phase | Réglages ajoutés (défaut) |
|---|---|
| 6-7 | `maxToolsOffered` (24), `contextSummaryMode`, `privacyMode`, `codingRoute`, `visionRoute`, `embeddingRoute`, `episodicRetentionDays` (90), `pendingRetentionDays` (30) |
| 10 | `protectedBranches` (main, master), `gitCredentials`, `gitAuthorName`, `gitAuthorEmail` |
| 15 | `maxRepairIterations` (3), `repairSameFailureLimit` (2), `devBackend` (auto) |
| 16 | `visionFallback` (local) |
| 17 | `voiceLanguage` (fr-FR), `sttMode` / `ttsMode` (android), `sttRoute`, `ttsRoute`, `ttsVoice`, `wakeWordEnabled` (non), `wakePhrase` (Cortana), `handsFreeTimeoutSec` (120), `bargeIn` (oui) |
| 18 | `notificationApps` (vide), `notificationContentToModel` (non), `notificationTriggers` (vide), `clipboardClearSec` (60) |
| 20 | `mcpServers` (vide) — jetons uniquement sous forme de références au coffre |
| 21 | `a2aAgents` (vide) — idem |
| 25 | `imageRoute` (aucun), `videoRoute` (aucun) |
| Conseil (rc3) | `council` : `enabled` (**non**), `mode` (auto), `maxAgents` (4), `maxRounds` (1), `quorumRatio` (0,67), `topology` (auto), `decisionProtocol` (auto), `challengeFinal` (oui), `showCouncilSummary` (oui), `budget` (60 000 jetons, 16 appels, 12 outils, 240 s, 4 en parallèle), `retention`, `roles` (vide = modèle de la discussion) — forme de `council_engine_config.schema.json` ; `councilPrefs` : profil de budget (équilibré), routage (même modèle), modèles de synthèse et du juge, juge (si nécessaire), arrêt anticipé (oui), plafond quotidien (aucun), seuil Auto (40 000), batterie faible (20 %), détails techniques (non) |

Fichiers de plugins (phase 22) : `files/plugins/<id>/<version>/` (manifeste + fichiers vérifiés), préparation dans `files/plugins/.staging/` puis renommage atomique ; la colonne `plugins.state` (`installing`, `removing`) sert de journal : `PluginManager.recover()` termine ou annule au démarrage toute opération interrompue (`PluginTest.interruptedOperationsAreFinishedOrUndoneAtStartup`).

Contrats (`:contracts`) : les champs ajoutés ont une valeur par défaut (ex. `VerificationResult.failureSignature`, phase 15) et les nouveaux types (`ReviewResult`, `ReviewFinding`, `ReviewFileStat`, phase 15) ne sont pas persistés en base : un contrat ancien reste lisible (`ContractsTest.roundTripsEveryVersionedContract`).

Documents (phase 24) : aucune modification du schéma. Les résultats sont des lignes de `artifacts` (colonnes existantes `producerTaskId`, `producerCapability`, `sourceIdsJson` = sources `artifact:<id>` ou `fichier:<chemin>`, `metadataJson` = opération, format et SHA-256 de chaque source) ; les fichiers restent sous `files/artifacts/<id>/`.

Sauvegarde et restauration (phase 29, D-20260928-059) : aucun changement de schéma. Une sauvegarde `.cortana-backup` porte son `CompatibilityManifest` (schéma v2, format 1) ; la restauration accepte un schéma entre `minRestorableSchema` (2) et le schéma courant, écrit par nom de colonne (colonnes inconnues ignorées et signalées, colonnes absentes à leur valeur par défaut) et refuse un schéma plus récent. Avant toute restauration, l'état actuel est sauvegardé dans `files/backups/avant-restauration-*.cortana-backup`. Index dérivés (plein texte, vecteurs) reconstruits après restauration.

Observabilité (phase 28, D-20260928-058) : table `spans` (index `taskId`, `startMs`, `exported`), ajoutée au schéma v2 et à la migration 1→2 (`DatabaseMigrationTest`) ; purge par la maintenance (rétention réglable, 7 jours par défaut, 50 000 lignes au plus). Réglages `otlpEnabled`, `otlpEndpoint`, `otlpHeaderHandle` (poignée du coffre), `spanRetentionDays` : valeurs par défaut, sans migration.

Amélioration (phase 27, D-20260928-057) : tables `improvement_proposals` (empreinte unique, index `status`) et `eval_cases` (clé de demande unique), ajoutées au schéma v2 et à la migration 1→2 (`DatabaseMigrationTest`). Réglage `ownerShortcuts` (liste vide par défaut, une ligne de réglages comme les autres : aucune migration). Les propositions refusées, obsolètes ou annulées sont purgées après 30 jours.

Automatisation (phase 27, D-20260928-056) : colonne `schedules.concurrencyPolicy TEXT NOT NULL DEFAULT 'skip'` (une planification 1.2.0 garde son comportement : jamais deux exécutions à la fois) et table `schedule_runs` (index `scheduleId`, `status`), ajoutées au schéma v2 et à la migration 1→2 (`DatabaseMigrationTest` vérifie la valeur par défaut sur la planification migrée). La spécification d'une surveillance (`ScheduleSpec.condition`) est un champ JSON facultatif de `specJson` : une spécification 1.2.0 se lit sans changement.

Connexions (phase 26) : tables `connections` (une ligne par connexion : type, nom unique, schéma d'authentification, configuration sans secret, poignées de secrets, portées, état, santé, échecs) et `connection_events` (santé, autorisation, rafraîchissement, révocation, événements entrants avec leur contenu jusqu'à traitement, envois ; purge à 30 jours hors événements en attente), ajoutées au schéma v2 et à la migration 1→2 (`DatabaseMigrationTest`). `McpServerConfig.connection` (facultatif) dans le réglage `mcpServers`. Worker : `hooks/hooks.json` et `hooks/events.json` (0600).

