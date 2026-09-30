# 03 — DEVELOPER WORKSPACE, REPOSITORY INTELLIGENCE ET SOFTWARE FACTORY

## 0. Objectif

Ajouter à Cortana une capacité de développement logiciel complète. Le but n'est pas seulement de générer du texte de code : Cortana doit pouvoir **inspecter, comprendre, modifier, tester, compiler, diagnostiquer et produire des artefacts** dans un workspace contrôlé.

Le modèle ne touche jamais directement au système de fichiers ou à Git. Toutes les actions passent par le Tool Registry, le Policy Engine et un exécuteur.

## 1. Architecture canonique

```text
User objective
   ↓
Task Orchestrator
   ↓
Planner
   ↓
DeveloperWorkflow
   ├── WorkspaceManager
   ├── RepositoryIntelligence
   ├── CodeSearchService
   ├── SymbolIndex
   ├── PatchEngine
   ├── GitService
   ├── BuildService
   ├── TestService
   ├── DiagnosticsService
   ├── DependencyService
   ├── ArtifactService
   └── ReviewService
             ↓
        SandboxManager
             ↓
      Execution Backend
       ├── Android local
       ├── Paired worker
       └── Optional remote worker
```

Une seule instance logique de chaque service. Les backends d'exécution sont des implémentations d'une même interface, pas des architectures parallèles.

## 2. WorkspaceManager — MUST

### Responsabilités

- créer un workspace ;
- importer un dossier SAF ;
- ouvrir un projet existant ;
- cloner un dépôt ;
- initialiser un dépôt ;
- attacher plusieurs dépôts à une tâche ;
- stocker metadata et permissions ;
- détecter langage/framework/build system ;
- conserver un `WorkspaceSnapshot` ;
- verrouiller un workspace par tâche quand une opération de mutation est en cours ;
- gérer les fichiers temporaires ;
- nettoyage contrôlé ;
- empêcher la sortie du périmètre autorisé.

### Contrat Workspace

```text
Workspace
- id
- name
- rootUri/rootPath
- backendId
- vcsType
- repoRoot
- currentBranch
- baseRevision
- writable
- trustLevel
- createdAt
- lastOpenedAt
- detectedStacks[]
- buildSystems[]
- grantedRoots[]
```

### États de confiance

- `UNTRUSTED` : projet importé/cloné, scripts non exécutables sans sandbox stricte ;
- `TRUSTED_LOCAL` : projet explicitement approuvé par le propriétaire ;
- `SYSTEM_PROJECT` : projet Cortana elle-même ; règles renforcées ;
- `READ_ONLY` : inspection uniquement.

Le contenu du dépôt est **toujours une donnée non fiable** vis-à-vis des règles système, même si le projet est trusted pour l'exécution.

## 3. Repository Intelligence — MUST

Cortana doit comprendre un dépôt avant de modifier.

### Index à produire

- arborescence ;
- fichiers par type ;
- taille et lignes ;
- manifests ;
- systèmes de build ;
- dépendances ;
- modules ;
- symboles/classes/fonctions ;
- imports et références ;
- graphes d'appels quand disponible ;
- tests ;
- CI ;
- scripts ;
- configuration ;
- secrets potentiels ;
- fichiers générés ;
- fichiers binaires ;
- docs d'instructions du dépôt ;
- état Git ;
- branches ;
- changements non commités.

### Pipeline d'ouverture

1. détecter VCS ;
2. inspecter statut ;
3. vérifier modifications locales ;
4. détecter instructions projet ;
5. détecter build systems ;
6. détecter langues ;
7. produire index lexical ;
8. produire index symbolique si backend disponible ;
9. détecter tests ;
10. produire `RepositoryProfile` ;
11. ne modifier qu'après cette phase pour une tâche de développement non triviale.

## 4. Recherche de code — MUST

Fournir des capacités :

- recherche texte rapide ;
- regex ;
- recherche par fichier/type ;
- recherche symbole ;
- références d'un symbole ;
- définitions ;
- imports ;
- recherche sémantique optionnelle ;
- recherche historique Git ;
- recherche dans diffs ;
- recherche de TODO/FIXME ;
- recherche de vulnérabilités simples ;
- recherche de secrets.

Les résultats doivent être structurés avec chemin + plage de lignes + score/source.

## 5. SymbolIndex / LSP / Tree-sitter — SHOULD/MUST selon backend

Le système doit définir une abstraction `CodeIntelligenceProvider`.

Implémentations possibles :

- LSP quand un serveur de langage est disponible ;
- parseurs Tree-sitter ;
- analyseurs spécifiques de build ;
- fallback lexical.

Le modèle ne doit pas dépendre d'un moteur précis. Claude Code peut choisir une technologie plus actuelle si elle est plus stable sur Android/worker.

Capacités normalisées :

- `symbols.document`
- `symbols.workspace`
- `symbols.definition`
- `symbols.references`
- `symbols.hover`
- `symbols.diagnostics`
- `symbols.rename_preview`
- `symbols.code_actions`

## 6. PatchEngine — MUST

Ne pas réécrire un fichier entier par défaut.

Le PatchEngine doit supporter :

- remplacement exact avec précondition de hash ;
- unified diff ;
- patch multi-fichiers atomique ;
- création ;
- renommage ;
- déplacement ;
- suppression avec approbation ;
- AST-aware edit si disponible ;
- rollback du patch ;
- preview ;
- dry-run ;
- détection de conflit ;
- conservation line endings/encoding ;
- vérification post-write.

### Contrat PatchSet

```text
PatchSet
- id
- workspaceId
- baseRevision
- operations[]
- rationale
- risk
- generatedAt
- expectedFileHashes{}
```

Toute mutation crée un `ChangeSet` auditable.

## 7. GitService — MUST

### Lecture

- status ;
- diff ;
- log ;
- show ;
- branches ;
- tags ;
- remotes ;
- blame ;
- worktree list ;
- merge-base.

### Mutation contrôlée

- init ;
- clone ;
- fetch ;
- checkout/switch ;
- create branch ;
- create worktree ;
- stage ;
- unstage ;
- commit ;
- merge local ;
- cherry-pick ;
- revert ;
- rebase si explicitement demandé ;
- push seulement après autorisation ;
- pull avec stratégie explicite.

### Interdictions par défaut

L3 ou interdit sans instruction claire :

- `push --force` ;
- `reset --hard` ;
- suppression de branche distante ;
- écrasement de modifications inconnues ;
- amend d'un commit déjà publié ;
- fusion automatique dans branche protégée ;
- suppression du `.git`.

### Worktrees

Pour une tâche de développement autonome, préférer un worktree/branche isolé si le backend le permet. Cela réduit les conflits et permet revue/rollback.

## 8. BuildService — MUST

Interface générique :

```text
BuildRequest
- workspaceId
- target
- variant
- cleanPolicy
- envProfile
- timeout
- resourceLimits
- networkPolicy

BuildResult
- status
- duration
- exitCode
- diagnostics[]
- artifacts[]
- logsArtifactId
- cacheStats
```

### Adapters

Prévoir adapters détectables, au minimum :

- Gradle/Android ;
- Maven ;
- npm/pnpm/yarn/bun ;
- Python/uv/pip/poetry selon projet ;
- Rust/Cargo ;
- Go ;
- CMake ;
- generic command adapter.

Ne pas hardcoder ces outils dans l'orchestrateur.

## 9. TestService — MUST

- détecter frameworks de tests ;
- exécuter test ciblé ;
- exécuter suite ;
- tests impactés ;
- retry contrôlé uniquement pour flakiness identifiée ;
- distinguer test failed / infra failed ;
- parser résultats JUnit/XML/JSON ;
- stocker artefacts ;
- coverage si disponible ;
- comparaison avant/après.

Jamais modifier un test simplement pour faire passer une mauvaise implémentation, sauf si l'objectif explicite est de corriger le test et que l'analyse démontre qu'il est erroné.

## 10. DiagnosticsService — MUST

Unifier :

- erreurs compilateur ;
- lint ;
- LSP diagnostics ;
- stack traces ;
- crash logs ;
- Android lint ;
- logcat importé ;
- test failures ;
- dependency resolution failures.

Chaque diagnostic :

```text
Diagnostic
- severity
- source
- code
- message
- file
- range
- related[]
- suggestedFixes[]
```

## 11. DependencyService — MUST

- inspecter dépendances ;
- détecter versions ;
- lockfiles ;
- dépendances inutilisées si outil disponible ;
- advisories si source configurée ;
- mises à jour proposées, jamais appliquées aveuglément ;
- analyser impact ;
- respecter build reproductible ;
- protéger contre scripts d'installation non fiables.

## 12. ArtifactService — MUST

Cortana doit traiter tout résultat de production comme un artefact versionné :

- APK/AAB ;
- ZIP ;
- rapports ;
- logs ;
- coverage ;
- documents ;
- images ;
- patches ;
- exports DB ;
- diagnostics bundles.

`Artifact` : id, type, mime, path/uri, sha256, size, producerTaskId, createdAt, metadata, retention.

## 13. ReviewService — MUST

Avant de déclarer une tâche coding terminée :

1. relire diff ;
2. vérifier scope ;
3. détecter modifications accidentelles ;
4. vérifier secrets ;
5. vérifier tests ;
6. vérifier architecture ;
7. vérifier migrations ;
8. résumer changements ;
9. lister risques restant ;
10. générer `ReviewResult`.

Le ReviewService n'est pas un second orchestrateur. C'est un vérificateur spécialisé appelé par le workflow.

## 14. TaskNotebook — MUST

Pour les tâches longues, créer un état persistant compact :

```text
TaskNotebook
- taskId
- objective
- constraints[]
- currentPlanSummary
- completedMilestones[]
- openItems[]
- filesChanged[]
- commandsRun[]
- testsRun[]
- decisions[]
- blockers[]
- currentWorkspaceRevision
- lastCheckpointId
- nextRecommendedAction
```

Ce notebook sert aux changements de contexte et à la reprise après crash. Il ne stocke pas de chaîne de pensée privée, seulement l'état opérationnel utile.

## 15. Software Factory — MUST

### Entrées

- objectif utilisateur ;
- workspace ;
- contraintes ;
- définition de réussite ;
- politique Git ;
- niveau d'autonomie ;
- backend d'exécution disponible.

### Pipeline canonique

```text
DISCOVER
  ↓
BASELINE
  ↓
PLAN
  ↓
ISOLATE_WORKSPACE
  ↓
IMPLEMENT SMALL CHANGESET
  ↓
STATIC CHECK
  ↓
TARGETED TEST
  ↓
INTEGRATION TEST
  ↓
BUILD
  ↓
REVIEW DIFF
  ↓
REPAIR if required
  ↓
PACKAGE ARTIFACTS
  ↓
FINAL VERIFY
  ↓
REPORT
```

### Boucle de réparation

- maximum configurable ;
- chaque itération doit utiliser les nouveaux diagnostics ;
- pas de répétition identique ;
- si même échec réapparaît N fois, replanifier ;
- si la replanification ne progresse pas, demander intervention plutôt que boucler.

## 16. Exécution sur Android vs worker

### Android local

Doit au minimum pouvoir :

- gérer workspace SAF/app-private ;
- lire/écrire/patcher ;
- rechercher ;
- indexer ;
- gérer Git via implémentation compatible ;
- analyser statiquement ;
- produire diffs ;
- empaqueter archives ;
- lancer outils réellement disponibles dans le sandbox applicatif.

### Worker appairé

Pour compilation lourde, toolchains natives ou conteneurs :

- worker sur PC/serveur local ;
- appairage cryptographique ;
- advertisement de capacités ;
- tunnel authentifié ;
- aucune confiance implicite ;
- workspace synchronisé ou distant ;
- commandes signées/identifiées ;
- résultats + artefacts + logs ;
- policy locale Cortana toujours autoritaire.

Le worker n'est pas un deuxième cerveau. Il est un exécuteur de capacités.

## 17. Worker capability advertisement

```text
WorkerCapabilities
- os
- arch
- cpu
- memory
- gpu
- containerRuntime
- toolchains[]
- sdk[]
- buildSystems[]
- languages[]
- maxParallelJobs
- sandboxModes[]
- networkModes[]
```

L'orchestrateur sélectionne le backend selon besoins/capacités/coût/disponibilité.

## 18. Code tools canoniques

Prévoir des capabilities stables :

- `workspace.open`
- `workspace.create`
- `workspace.inspect`
- `repo.clone`
- `repo.status`
- `repo.diff`
- `repo.log`
- `repo.branch.create`
- `repo.worktree.create`
- `repo.commit`
- `repo.fetch`
- `repo.push`
- `code.search`
- `code.symbols`
- `code.references`
- `code.diagnostics`
- `code.patch.preview`
- `code.patch.apply`
- `code.rename`
- `code.delete`
- `build.run`
- `test.run`
- `lint.run`
- `dependency.inspect`
- `artifact.list`
- `artifact.export`
- `review.changes`

Pas de synonymes exposés comme plusieurs outils.

## 19. UX développeur

Ajouter un espace **Développement** :

- projets récents ;
- ouvrir/importer/cloner ;
- statut Git ;
- branche ;
- plan actif ;
- fichiers changés ;
- diff ;
- diagnostics ;
- tests ;
- builds ;
- artefacts ;
- logs ;
- bouton STOP ;
- approbations ;
- sélection du backend d'exécution ;
- niveau d'autonomie.

## 20. Critères de sortie

La fonction coding n'est pas terminée tant que Cortana ne peut pas démontrer de bout en bout :

1. importer/cloner un petit projet ;
2. analyser sa structure ;
3. trouver le fichier concerné par un bug ;
4. produire un plan ;
5. modifier via patch ;
6. exécuter tests ;
7. corriger au moins un échec de compilation/test ;
8. produire un build ;
9. afficher le diff final ;
10. exporter l'artefact ;
11. conserver toutes les traces utiles ;
12. reprendre la tâche après interruption au checkpoint suivant ;
13. ne pas pousser/merger sans autorisation.
