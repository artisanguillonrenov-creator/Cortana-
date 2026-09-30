# Conseil de réflexion — rapports C1 → C12

Source : `docs/council_pack/` (22 fichiers du paquet `CORTANA_COUNCIL_ENGINE_CLAUDE_HANDOFF`). Correspondance concept → classe et conflits : `COUNCIL_MAPPING.md`. Décision : D-20260929-067. Chaque rapport suit le format demandé par `12_MASTER_EXECUTION_PROMPT_FOR_CLAUDE.md` : objectif, fichiers, changements, tests, résultats, risques, dette, prochaine étape. Chemins sous `app/src/main/java/io/github/artisanguillonrenov/cortana/` sauf mention.

Légende des résultats : **exécuté** = test lancé dans cette conversation et vert ; **écrit, non exécuté** = compilé mais demande un appareil ; **BLOCKED_EXTERNAL** = demande des fournisseurs ou un matériel réels.

## C1 — Contrats et drapeau

- **Objectif.** Types, interfaces, configuration conforme au schéma, drapeau coupé par défaut, service neutre.
- **Fichiers.** `core/council/CouncilContracts.kt`, `core/memory/SettingsRepository.kt` (`council`, `councilPrefs`), `core/tools/ToolDefinition.kt` (`SchemaValidator` : `const`, listes de types, `enum` sans type).
- **Changements.**
  - Énumérations : modes, topologies, 10 protocoles, efforts.
  - Machine d'états de la séance (doc 01 §1.5), configuration `CouncilConfig` sérialisée à l'identique du schéma, préférences du propriétaire.
  - Requêtes, contributions, décisions, résumé, événements.
  - Interfaces `CognitiveCouncilEngine`, `CouncilPolicySelector`, `CouncilBudgetGuard`, etc.
- **Tests (exécutés).**
  - `CouncilLogicTest.c1ConfigMatchesTheSchemaAndTheFlagDefaultsOff` (validation contre `council_engine_config.schema.json`).
  - `c1StateMachineFollowsDoc01`.
  - `schemaValidatorTypeListsConstAndUntypedEnums`.
  - `CouncilTest.c1OffIsTheDefaultAndLeavesTheNormalPathUntouched`.
  - `c1NestedCouncilsAreRefusedAndOffNeverRuns`.
- **Résultats.**
  - OFF est identique à la rc2 : aucun appel du conseil, aucune carte, aucun événement.
  - Une régression a été trouvée par `DocumentTest` et corrigée : une liste de types n'acceptait que le premier type.
- **Risques.** Aucun connu.
- **Dette.** Aucune.
- **Prochaine étape.** C2.

## C2 — Profils et rôles

- **Objectif.** Rôles validés et préréglages (doc 03, doc 16).
- **Fichiers.** `core/council/CouncilProfiles.kt`.
- **Changements.**
  - 4 rôles standard, 24 rôles dynamiques, juge et synthèse internes.
  - 10 préréglages : rapide, éco, équilibré, qualité, approfondi, revue de code, recherche, diagnostic Android, affaires, création.
  - Validation : doublons, capacité inconnue, repli mal formé, limites, juge sans outil.
  - Validation de la configuration des rôles par le propriétaire.
- **Tests (exécutés).** `CouncilLogicTest.c2ProfilesAreValidatedDuplicatesCapabilitiesAndFallbacks`.
- **Résultats.** Vert.
- **Risques.** Aucun.
- **Dette.** Le drapeau `cheapModels` des préréglages a été retiré car jamais lu. Le petit modèle se choisit comme « modèle du juge ».
- **Prochaine étape.** C3.

## C3 — Tour 0 parallèle, multi-modèle et multi-fournisseur

- **Objectif.** Avis indépendants et réellement concurrents, un modèle et un fournisseur par rôle, budget de contexte, 413, 429, délais, quorum.
- **Fichiers.**
  - `core/council/CouncilPlanner.kt` : `ContextBudget`, `RunBudgetGuard`, planificateur.
  - `CouncilAgentCaller.kt`, `CouncilRuntime.kt`.
  - Côté modèle : `core/model/ModelGateway.kt`, `ModelTypes.kt`, `OpenAiCompatibleProvider.kt`.
- **Changements.**
  - Sémaphores global et par fournisseur.
  - Température différente par rôle quand un même modèle sert plusieurs rôles (0,3 / 0,55 / 0,8 / 1,0).
  - Routes par rôle et replis explicites. En confidentialité locale, un modèle non local est refusé et le propriétaire en est informé.
  - `allowedInput = min(fenêtre, limite apprise d'un 413) − sortie réservée − marge`, plafonné par le rôle.
  - Après un 413 : retrait des schémas d'outils, puis des résultats, puis des détails ; la même charge n'est jamais renvoyée ; la limite est apprise ; une route plus large est prise si elle est permise.
  - Après un 429 : `Retry-After` respecté une fois, puis repli. Le code HTTP et `Retry-After` survivent désormais à un échec réessayable dans la passerelle (défaut trouvé par le test).
  - Budget d'appels par agent : réponse, un appel par tour d'outils, 2 appels de reprise.
  - Quorum 1→1, 2→2, 4→3. Un conseil partiel est annoncé.
- **Tests (exécutés, conteneur réel contre des modèles scriptés).**
  - `c3FourAgentsDeliberateInParallelAndCortanaAnswersOnce` : 4 appels simultanés, tour 0 sans l'avis des autres, une seule réponse de Cortana, spans sans contenu.
  - `c3RolesRunOnTheirOwnModelsAndProviders` : deux serveurs, trois modèles.
  - `c3LocalOnlyPrivacyKeepsEveryCallOnTheDevice`.
  - `c3QuorumCompletesWithOneFailureAndIsPartialWithTwo`.
  - `c3Http413ShrinksTheRequestAndNeverResendsTheSamePayload`.
  - `c3Http429HonoursRetryAfterOnceThenFallsBack`.
  - `c3ASlowAgentTimesOutAndTheCouncilContinues`.
  - `c3PlannerScopesReadOnlyToolsQuorumAndProfiles`.
- **Résultats.** Verts.
- **Risques.**
  - Le nombre réel de requêtes simultanées par fournisseur n'est pas connu (limites RPM/TPM propres à chacun). Le plafond par fournisseur (4) est à régler sur appareil.
  - L'estimation des jetons est approximative (≈ 3,2 caractères par jeton).
- **Dette.** Pas de planification « par vagues » selon les quotas RPM/TPM publiés par chaque fournisseur (doc 05 §5.6) : seuls les 429 sont traités.
- **Prochaine étape.** C4.

## C4 — Contributions structurées

- **Objectif.** Sortie JSON stricte, bornée, assainie, avec empreinte des candidats.
- **Fichiers.** `core/council/ContributionParser.kt`, `CouncilPrompts.kt`.
- **Changements.**
  - Champs connus uniquement : un `toolCall` ou tout autre champ est un rejet, jamais exécuté.
  - Types vérifiés, tailles bornées, caractères de contrôle retirés, secrets masqués.
  - Une réparation sans outil, puis la contribution est invalide.
  - Empreinte déterministe : synonymes et accents normalisés ; effets ou risques différents jamais fusionnés.
- **Tests (exécutés).**
  - `CouncilLogicTest.c4ParserIsStrictBoundedSanitisedAndFingerprinted`.
  - `CouncilTest.c4MalformedOutputIsRepairedOnceAndSmuggledToolCallsAreRejected`.
- **Résultats.** Verts.
- **Risques.** Un modèle qui ne sait pas produire de JSON voit ses contributions invalidées. Le conseil continue avec les autres et le signale.
- **Dette.** Aucune.
- **Prochaine étape.** C5.

## C5 — Décision

- **Objectif.** 10 protocoles déterministes. Une majorité n'est pas une preuve.
- **Fichiers.** `core/council/CouncilAssessment.kt`, `CouncilDecisionEngine.kt`.
- **Changements.**
  - Évaluation : regroupement, bulletins, accord, divergence, couverture de preuves, stabilité.
  - Registre de preuves : `TOOL_VERIFIED`, `USER_PROVIDED`, `MODEL_ONLY`, `CONTRADICTED`…
  - Une objection critique non résolue bloque tous les protocoles.
  - Une preuve d'outil qui contredit la majorité la renverse. Une affirmation contredite reste contredite quand son auteur la répète au tour suivant (défaut trouvé en construisant le faux consensus).
  - Égalités jamais inventées.
  - À la décision finale, une majorité claire, non bloquée et non contredite l'emporte, même peu étayée : le consensus est alors annoncé faible (défaut trouvé par le jeu de référence).
- **Tests (exécutés).**
  - `CouncilLogicTest.c5ProtocolsAreDeterministicAndMajorityIsNeverProof`.
  - `c5ToolVerifiedEvidenceOverturnsAFalseConsensus`.
  - `c5TiesAreNeverInventedAndTheJudgeIsBlindToVotes`.
  - `CouncilTest.c5FalseConsensusIsOverturnedByToolVerifiedEvidence` : de bout en bout, trois agents affirment 128 Go, l'analyste lit 64 Go avec `memory.search` et gagne.
  - `c5BudgetAbuseIsDegradedAndNeverExceeded`.
  - `CouncilGoldenSetTest.decisionBenchAndAblations`.
- **Résultats.** Verts. Le banc est détaillé en C12.
- **Risques.** Les poids du score hybride (0,35 vote, 0,25 preuves…) sont ceux du paquet, non calibrés sur de vraies séances.
- **Dette.** Calibration sur données réelles (BLOCKED_EXTERNAL).
- **Prochaine étape.** C6.

## C6 — Rétention sélective

- **Objectif.** Chaque agent ne reçoit que ce qui peut changer son avis.
- **Fichiers.** `core/council/CouncilRetainer.kt`.
- **Changements.**
  - Score : nouveauté, désaccord, preuves, gravité, minorité, pertinence, moins redondance et coût.
  - Topologies : indépendants, tous avec tous, étoile, anneau, ciblée.
  - Objections critiques toujours transmises.
  - Au plus 2 arguments par source.
  - Protection de la minorité quand le conseil est divisé.
  - L'identifiant d'un argument est celui de l'affirmation, pour qu'un agent puisse la viser (défaut trouvé en C5).
- **Tests (exécutés).**
  - `CouncilLogicTest.c6RetentionKeepsCriticalBoundsTrafficAndProtectsTheMinority`.
  - Arguments transmis en tour de confrontation (`CouncilTest.c3FourAgents…`).
- **Résultats.** Verts.
- **Risques.** Aucun connu.
- **Dette.** Aucune.
- **Prochaine étape.** C7.

## C7 — Tours de confrontation

- **Objectif.** Débat borné et arrêt anticipé.
- **Fichiers.** `core/council/CouncilRuntime.kt`.
- **Changements.**
  - On continue si : objection critique, forte divergence, ou preuves insuffisantes alors que des outils sont disponibles.
  - Arrêt anticipé si l'accord est fort, étayé et stable, ou si le gain attendu est faible.
  - Dégradation entre les tours (moins d'arguments) quand la projection ne tient plus.
  - Arrêt sur le temps ou le budget, annoncé.
- **Tests (exécutés).**
  - `CouncilTest.c7EarlyStopSkipsTheDebateWhenAgreementIsStrongAndSupported`.
  - Un tour de confrontation dans `c3FourAgents…`.
  - Plafond de 3 tours dans `c5BudgetAbuse…`.
- **Résultats.** Verts.
- **Risques.** Aucun connu.
- **Dette.** Aucune.
- **Prochaine étape.** C8.

## C8 — Juge, défi final, STOP

- **Objectif.** Juge anonyme, défi final avec mini-réparation, annulation propre.
- **Fichiers.** `core/council/CouncilRuntime.kt`, `CouncilPrompts.kt`, `core/orchestrator/Orchestrator.kt`.
- **Changements.**
  - Le juge voit des candidats A, B… triés par clé : ni auteurs, ni votes, ni modèles.
  - Défi final par le challenger. Une objection critique déclenche un tour de réparation si le budget le permet.
  - STOP : tous les agents sont annulés, la séance passe à CANCELLED, rien n'arrive en retard.
  - Un défaut du conseil n'échoue jamais la tâche : elle reprend le chemin habituel.
- **Tests (exécutés).**
  - `CouncilTest.c8TheJudgeSeesAnonymousCandidatesWithoutAuthorsOrVotes`.
  - `c8StopCancelsEveryAgentWithoutALateAnswer`.
  - `c8AFailedCouncilFallsBackToTheNormalPath`.
- **Résultats.** Verts. Deux défauts d'intégration ont été corrigés :
  - la transition PLANNING→PLANNING après un conseil non concluant ;
  - la réponse du conseil lue deux fois à voix haute.
- **Risques.** Aucun connu.
- **Dette.** Aucune.
- **Prochaine étape.** C9.

## C9 — Outils, preuves, sécurité

- **Objectif.** Outils en lecture seule, preuves, taint, secrets, actions.
- **Fichiers.**
  - `core/council/CouncilAgentCaller.kt` : `CouncilToolContext` sans secret.
  - `core/tools/ToolDispatcher.kt` : `interactive = false`.
  - `core/verifier/Verifier.kt` : `verifyAnswer`.
  - `core/context/ContextEngine.kt` : `councilFacts`.
- **Changements.**
  - Outils de la séance : aucun effet, ≤ L1, ni écran, ni question au propriétaire, ni écriture mémoire. Découverte limitée à ce périmètre.
  - Résultat non fiable enveloppé, lignes suspectes signalées, taint propagé à la décision puis à la tâche.
  - Une décision qui implique une action repasse par le chemin normal, sous la politique et ses confirmations.
  - Aucun secret dans une invite.
- **Tests (exécutés).**
  - `CouncilTest.c9InjectedWebContentIsDataAndTaintsTheTask`.
  - `c9ProposedActionsGoThroughTheNormalPathAndItsPolicy`.
  - `c9SecretsNeverReachAModel`.
- **Résultats.** Verts.
- **Risques.** Une page web lue par un outil compte comme preuve d'outil (`TOOL_VERIFIED`), mais reste marquée non fiable et contamine la décision. Une action fondée sur elle subit la vérification renforcée de la politique.
- **Dette.** Aucune.
- **Prochaine étape.** C10.

## C10 — Interface

- **Objectif.** Réglages › Intelligence › Conseil de réflexion, progression, carte « Résumé du conseil », accessibilité (doc 07).
- **Fichiers.** `ui/council/CouncilUi.kt`, `ui/settings/SettingsScreen.kt`, `ui/chat/ChatScreen.kt`.
- **Changements.** Réglages :
  - interrupteur, mode, profil de budget ;
  - routage des modèles (même modèle, par rôle, automatique, hybride) ;
  - modèles de synthèse et du juge ;
  - par rôle : modèle, repli, effort, local uniquement, jetons, délai ; membres en mode Personnalisé ;
  - débat : tours, topologie, protocole, défi, juge, quorum, arrêt anticipé ;
  - coûts : jetons, coût, appels, durée, parallélisme, plafond quotidien, seuil Auto, batterie ;
  - affichage, dont les détails techniques.

  Chaque réglage est appliqué. Plafond quotidien, seuil Auto, confirmation du mode Approfondi (80 000 jetons) et `reasoning_effort` par rôle ont été branchés avant d'être affichés.

  Pendant la séance : « 4 analyses en cours », état de chaque spécialiste en mots, STOP. Carte finale : consensus, accords, risques restants, incertitudes, preuves, x/y spécialistes, durée, avis, détails techniques sur demande.

  Accessibilité : états dits en mots, jamais par la seule couleur ; titres ; régions dynamiques polies ; `stateDescription` des puces ; cibles Material 3 ; disposition en flux (tablette et téléphone).
- **Tests.**
  - Exécutés : `CouncilTest.c10ProgressAndSummaryAreSaidInWordsNotColours`, `c10TheDailyCapKeepsTheCouncilOffAndTheAutoThresholdReducesIt`, `c10ARoleReasoningEffortIsSentOnlyWhenTheOwnerSetsOne`.
  - Écrit, non exécuté : rendu Compose et TalkBack réels sur la tablette (`RC_CHECKLIST.md`).
- **Résultats.** Verts. Lint : voir la gate finale.
- **Risques.** `reasoning_effort` est envoyé seulement si le propriétaire le règle. Un serveur qui le refuse renvoie une erreur : le rôle bascule alors sur son repli.
- **Dette.** Pas de test instrumenté Compose de ces écrans. Vérification manuelle prévue dans `RC_CHECKLIST.md` §C.
- **Prochaine étape.** C11.

## C11 — Persistance, télémétrie

- **Objectif.** Tables du doc 06 dans la base canonique, migration testée, métriques, diagnostic expurgé, reprise.
- **Fichiers.**
  - `core/memory/CouncilEntities.kt` (9 entités, DAO).
  - `CortanaDatabase.kt` (v3), `Migrations.kt` (`MIGRATION_2_3`), `app/schemas/…/3.json`.
  - `core/council/CouncilStore.kt`.
  - `core/backup/Backup.kt`, `core/observability/Observability.kt`, `core/maintenance/Maintenance.kt`, `CortanaApp.kt`.
- **Changements.**
  - Enregistreur : lignes structurées seulement.
  - Tables incluses dans les sauvegardes.
  - Séance interrompue close au démarrage (jamais reprise).
  - Métriques du doc 06 §6.10 dans `observability.metrics`.
  - Span `council.vote` ajouté.
  - Diagnostic d'une séance expurgé.
- **Tests (exécutés).**
  - `DatabaseMigrationTest.v2ToV3IsAdditiveAndTheCouncilStoreWorks`, `v1ToV3InOneUpgrade`.
  - `BackupTest` : une séance fait l'aller-retour de sauvegarde ; le test du « schéma plus récent » est désormais relatif à la version courante.
  - `CouncilTest` : lignes d'une vraie séance, aucune invite ni raisonnement dans le diagnostic, séance annulée `cancelled`, métriques.
- **Résultats.** Verts.
- **Risques.** Aucune purge dédiée : les lignes du conseil suivent la vie de leurs tâches, comme `task_events`.
- **Dette.** Rétention propre aux tables du conseil, si leur volume l'exige.
- **Prochaine étape.** C12.

## C12 — Durcissement

- **Objectif.** 413, 429, délais, fournisseur en panne, modèles mixtes, annulation, charge, batterie, sécurité, migrations, bout en bout (doc 10 §C12, doc 11).
- **Fichiers.**
  - `app/src/test/…/ArchitectureRulesTest.kt` (4 lois du conseil).
  - `app/src/test/resources/council/golden.json` (52 tâches), `CouncilGoldenSetTest.kt`.
  - `core/council/CouncilPolicySelector.kt`.
  - `tools/check_android_regex.py` (asset régénéré).
- **Changements.**
  - Lois du conseil, vérifiées sur le code :
    - subordination : ni exécuteur, ni DAO de tâches, de conversations ou de mémoire, ni client HTTP ;
    - appels d'outils non interactifs, sans secret, sans repli silencieux ;
    - aucun raisonnement ni invite persistés ;
    - pas de récursion ;
    - aucun framework multi-agents dans le build.
  - Jeu de référence : 52 tâches (code, recherche, Android, planification, affaires, sécurité, ambiguës), chacune avec entrée, faits, actions permises et interdites, et attendu.
  - Sélecteur rendu identique sur la JVM et sous ICU (bornes de mots explicites, texte mis en minuscules). Sur la JVM, `\b`, `\w` et `(?i)` ne gèrent que l'ASCII : « études » ne correspondait pas.
  - Sélecteur complété grâce au jeu de référence : « supprime définitivement », diagnostic Android à plusieurs symptômes, « vaut-il mieux » avec contraintes, vocabulaire du recrutement.
- **Tests (exécutés).**
  - `ArchitectureRulesTest` (17).
  - `CouncilGoldenSetTest` (3).
  - `AndroidRegexCompatTest` : 360 expressions compilées par ICU4C, 0 refus.
- **Résultats.** Banc déterministe de la couche de décision, 14 cas avec avis scriptés :

| Configuration | Exactes | Dangereuses | Inventées |
|---|---|---|---|
| B4 hybride livré (votes + preuves + objections) | 14/14 | 0 | 0 |
| B2 majorité simple | 9/14 | 3 | 0 |
| Ablation sans registre de preuves | 13/14 | 0 | 0 |
| Ablation sans objections | 11/14 | 3 | 0 |
| Ablation sans vote | 10/14 | 2 | 1 |

  Limite à lire avec ce tableau : les attendus du jeu de référence ont été écrits ici, et le sélecteur a été ajusté pour les atteindre. Le jeu mesure la cohérence du moteur avec ses règles, pas la qualité de vrais modèles.
- **Risques.** Les seuils du sélecteur (longueur, nombre de contraintes) sont des heuristiques. Une demande réelle peut déclencher trop ou pas assez de conseils en mode Auto. Le mode explicite et le drapeau restent au propriétaire.
- **Dette.**
  - BLOCKED_EXTERNAL : baselines de qualité B0–B7 et ablations « juge » et « modèles mixtes » avec de vrais modèles ; latence, batterie et mémoire sur la tablette ; TalkBack.
  - Divergence JVM/ICU restante hors du conseil (non bloquante) : dans `InjectionGuard`, le motif d'exfiltration « clé » se termine par une lettre accentuée. Il correspond sur l'appareil (ICU) mais pas dans les tests JVM : la détection réelle est plus large que la détection testée.
- **Prochaine étape.** Gate finale et 2.0.0-rc3 (`RELEASE.md` §10).

## Gate finale (doc 12)

| Critère | Résultat |
|---|---|
| Tests verts | **361 exécutés, 0 échec** (app 348, worker 8, contrats 5) ; lint 0 erreur |
| Build release | 2.0.0-rc3, versionCode 4, même clé ; deux builds indépendants identiques (`RELEASE.md` §10) |
| OFF non régressif | `CouncilTest.c1OffIsTheDefaultAndLeavesTheNormalPathUntouched` |
| 4 agents, multi-modèle | `c3FourAgentsDeliberateInParallelAndCortanaAnswersOnce` (4 appels simultanés), `c3RolesRunOnTheirOwnModelsAndProviders` (3 modèles) |
| Multi-fournisseur | `c3RolesRunOnTheirOwnModelsAndProviders`, `c3Http429HonoursRetryAfterOnceThenFallsBack` (deux serveurs) |
| Rétention prouvée | `c6RetentionKeepsCriticalBoundsTrafficAndProtectsTheMinority`, arguments croisés en confrontation |
| Préservation des objections critiques | `c6Retention…` (toujours transmises), `c5Protocols…` (bloquent tout protocole), jeu de référence (`security-02`, `security-06`, `code-08`) |
| STOP | `c8StopCancelsEveryAgentWithoutALateAnswer` |
| Migrations | `DatabaseMigrationTest` 2→3 et 1→3, `BackupTest` |
| Aucun secret | `c9SecretsNeverReachAModel`, lois d'architecture, contrôle des livraisons |
| Aucun blocker critique | dans l'environnement de construction : aucun connu ; **sur la tablette : BLOCKED_EXTERNAL** (`RC_CHECKLIST.md` §14 à dérouler) |
