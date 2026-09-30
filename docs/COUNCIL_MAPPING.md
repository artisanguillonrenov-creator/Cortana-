# Cognitive Council Engine — correspondance avec le dépôt réel

Préalable exigé par `council_pack/12_MASTER_EXECUTION_PROMPT_FOR_CLAUDE.md` (« avant tout code ») : chaque concept du dossier est rattaché à la classe qui en est déjà propriétaire. Le Conseil n'ajoute **aucun** second orchestrateur, gateway, registre, moteur de politique, mémoire, planificateur ni STOP.

## 1. Table de correspondance

| Concept du dossier | Existant (fichier) | Rôle pour le Conseil |
|---|---|---|
| TaskOrchestrator | `core/orchestrator/Orchestrator.kt` (+ `TaskStateMachine`) | Seul décideur : après classification et résolution de route, demande au `CouncilPolicySelector` ; si un conseil est retenu, passe la tâche en RUNNING, délègue un `CouncilRunRequest`, reçoit un `CouncilResult`, répond ou poursuit son plan normal. Le Conseil ne transitionne jamais la tâche. |
| ModelGateway | `core/model/ModelGateway.kt` | Seul appelant des modèles (LAW-001). Extensions additives : code HTTP et `Retry-After` dans `GatewayResult`, plafond de sortie et température par appel. |
| Provider adapters | `core/model/OpenAiCompatibleProvider.kt`, `ProviderRepository.kt` | Inchangés, sauf la lecture de `Retry-After` (429). |
| Résolution des modèles, confidentialité | `ModelGateway.routeFor` / `resolveRoute`, `ProviderEntity`, mode `local_only` | Route par rôle (« providerId/modèle ») et replis ; les rôles « local seulement » ne reçoivent qu'un fournisseur local. |
| Capacités des modèles (fenêtre de contexte) | `ModelCapabilities` (`model_caps.json`, appris) | Source de `modelContextLimit` pour le Context Budget Manager. |
| ToolRegistry | `core/tools/ToolRegistry.kt` | Inchangé. |
| CapabilityMatcher | `core/tools/CapabilityMatcher.kt` + `ToolDiscovery` | Sélection de 5 à 15 outils par rôle, **restreinte aux outils sans effet** (lecture seule). |
| `tools.discover` | `core/tools/DiscoveryTools.kt` | Disponible dans le périmètre de lecture seule du rôle ; l'élargissement reste validé par la politique. |
| PolicyEngine / Executor | `core/policy/PolicyEngine.kt` via `core/tools/ToolDispatcher.kt` | Tout appel d'outil d'un agent passe par `ToolDispatcher.dispatch` (schéma, politique, approbation, idempotence, audit). Aucune action L2/L3 n'est autorisée par un vote. |
| ContextEngine | `core/context/ContextEngine.kt` (`Tokens.estimate`, `fitTools`, `retrieveForContext`) | Estimation des jetons et faits mémoire pertinents du TaskBrief ; le Conseil ne lit jamais la base de mémoire directement. |
| Mémoire canonique | `MemoryRepository` | Lecture via ContextEngine ; aucune écriture par le Conseil (LAW-005). |
| Verifier | `core/verifier/Verifier.kt` | `CouncilVerifierBridge` → nouvelle méthode `Verifier.verifyAnswer` (même schéma de verdict que la vérification par modèle). |
| RecoveryEngine | `core/recovery/RecoveryEngine.kt` | Non appelé par le Conseil : un échec du conseil revient à l'orchestrateur, qui applique sa reprise habituelle. |
| Spécialistes (phase 23) | `core/orchestrator/Specialists.kt` | Restent les exécutants d'étapes de plan. Les rôles du Conseil sont des profils cognitifs (contribution structurée, pas d'action) : registre distinct `CouncilProfileRegistry`, sans exécution parallèle dupliquée. |
| Base de données | `CortanaDatabase` (Room, schéma v2 **figé** depuis la rc1) | Tables `council_*` dans un **schéma v3**, `MIGRATION_2_3` additive, `3.json`, tests 2→3 et 1→3. |
| ConfigService | `SettingsRepository` (`AppSettings` en JSON) | Champ `council: CouncilConfig` (conforme à `council_engine_config.schema.json`), désactivé par défaut. |
| UI Réglages | `ui/settings/SettingsScreen.kt` et écrans dédiés | Nouvel écran « Intelligence — Conseil de réflexion ». |
| Progression, carte finale | `ActiveTaskState` (orchestrateur), `ui/chat` | Phase et état des emplacements dans `ActiveTaskState` ; carte « Résumé du conseil » dans la discussion. |
| Observabilité | `Tracer`, `ObservabilityService`, `SpanStore` | Spans `council.*` sans contenu ; métriques calculées depuis les tables du Conseil. |
| Audit | `AuditLog` | Événements de run (création, décision, annulation). |
| STOP | `KillSwitch` → annulation du job de la tâche | Le run est un sous-scope de la coroutine de la tâche : STOP l'annule ; résultats tardifs ignorés ; statut CANCELLED écrit hors annulation. |
| Secrets | `SecretStore` (poignées), `TaskToolContext.resolveSecret = null`, `Redactor` | Les agents n'obtiennent jamais de secret ; prompts et traces passent par le `Redactor`. |
| Injection | `InjectionGuard`, `Envelope` | Résultats d'outils et contributions réinjectées encapsulés comme données ; `tainted` propagé aux affirmations et à la décision. |
| Budgets | Limites par tâche (`maxModelCallsPerTask`…), plafonds de dépense du gateway | `CouncilBudgetGuard` par run, borné en plus par ce qu'il reste à la tâche ; les appels du Conseil comptent dans les compteurs de la tâche. |
| Sauvegarde | `core/backup/Backup.kt` (`INCLUDED`) | Tables `council_*` incluses (historique des décisions). |
| Service de premier plan | `CortanaForegroundService` | Démarré pour un conseil au premier plan, comme pour un plan en plusieurs étapes. |

## 2. Conflits identifiés et résolution

1. **Indépendance du tour 0 contre `StepRunner`.** `StepRunner` écrit chaque échange dans la conversation, et une exécution isolée relit les messages de la session depuis son début : des agents parallèles se verraient. → Les tours d'agents du Conseil sont des appels bornés tenus en mémoire (`CouncilAgentCaller`) : messages construits par code, outils en lecture seule via le même `ToolDispatcher`, aucune écriture dans la conversation, aucun état de tâche. Ce n'est pas une seconde boucle d'orchestration (pas de plan, pas de transition, pas d'effet).
2. **Schéma v2 figé.** → Schéma v3 ; `ReleaseTest` continue de figer v1 et v2.
3. **Gateway.** Pas de code HTTP (413), pas de `Retry-After` (429), plafond de sortie et température globaux seulement. → Extensions additives du gateway existant.
4. **Budget de tâche.** 20 appels de modèle par défaut par tâche ; un conseil de 4 avec un tour de critique, un défi et une synthèse en demande plus de 10. → Le Conseil réserve dans ce qu'il reste à la tâche et se dégrade (moins de tours, puis 4 → 2, puis un seul agent) plutôt que dépasser.
5. **Verifier orienté étapes.** → `verifyAnswer` ajouté au Verifier (propriétaire unique de la vérification).
6. **Pas de section « Intelligence » dans les Réglages.** → Entrée « Intelligence — Conseil de réflexion » ouvrant un écran dédié.
7. **Mode Auto.** Ne doit jamais se déclencher sur les raccourcis, salutations, minuteurs ou commandes courtes ; désactivé (OFF) = comportement historique à l'identique.

## 3. Fichiers

Nouveaux (`core/council/`) : `CouncilContracts.kt` (types, énumérations, interfaces, configuration), `CouncilProfiles.kt` (registre et préréglages), `CouncilPolicySelector.kt`, `CouncilPlanner.kt` (plan, routes, outils, budgets, Context Budget Manager), `CouncilAgentCaller.kt` (prompts construits par code, appels, outils en lecture seule, analyse et réparation, 413/429), `ContributionParser.kt` (schéma strict, limites, empreinte des candidats), `CouncilDecisionEngine.kt`, `CouncilRetainer.kt`, `EvidenceLedger.kt`, `CouncilRuntime.kt` (machine d'état, parallélisme, quorum, tours, défi, juge, synthèse, vérification, STOP), `CouncilStore.kt` (persistance), `CouncilTelemetry.kt` ; UI `ui/settings/CouncilSettings.kt`, carte de résumé dans la discussion.

Modifiés : `Orchestrator.kt` (sélection, délégation, progression), `ModelGateway.kt`, `ModelTypes.kt`, `OpenAiCompatibleProvider.kt` (extensions), `Verifier.kt` (`verifyAnswer`), `ContextEngine.kt` (faits pour le TaskBrief), `SettingsRepository.kt` (`council`), `CortanaDatabase.kt`, `Migrations.kt`, `RuntimeEntities.kt`, `Daos.kt` (v3), `Backup.kt`, `CortanaApp.kt` (assemblage), `ArchitectureRulesTest.kt` (lois du Conseil), documentation.

## 4. Lois vérifiées par test (ajoutées à `ArchitectureRulesTest`)

- Le paquet `core/council` n'appelle ni `invokeAuthorized`, ni les exécuteurs, ni les DAO de tâches, conversations ou mémoire ; il n'utilise ni `OpenAiCompatibleProvider` ni `OkHttp`.
- Aucun champ de chaîne de pensée (`reasoning`, `reasoningDetails`) n'est persisté par le Conseil.
- Un agent du Conseil ne peut pas lancer un autre conseil (récursion interdite).
