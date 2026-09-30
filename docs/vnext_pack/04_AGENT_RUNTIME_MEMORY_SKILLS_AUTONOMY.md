# 04 — RUNTIME AGENT, PLANIFICATION, VÉRIFICATION, MÉMOIRE, SKILLS ET AUTONOMIE

## 1. But

Faire évoluer la boucle agent actuelle vers un runtime durable, vérifiable et extensible, tout en gardant un **orchestrateur unique**.

## 2. Décomposition canonique

```text
Ingress
  ↓
IntentRouter / FastPaths
  ↓
TaskOrchestrator
  ├── TaskStateMachine
  ├── Planner
  ├── PolicyEngine
  ├── ContextEngine
  ├── ModelGateway
  ├── ToolRegistry
  ├── ExecutorRouter
  ├── Verifier
  ├── RecoveryEngine
  ├── Replanner
  ├── CheckpointService
  └── TaskNotebook
```

Aucun composant autre que `TaskOrchestrator` ne modifie l'état canonique de la tâche.

## 3. Machine d'état durable

États recommandés :

- `RECEIVED`
- `CLASSIFIED`
- `PLANNING`
- `READY`
- `WAITING_AUTHORIZATION`
- `RUNNING`
- `WAITING_TOOL`
- `WAITING_USER`
- `VERIFYING`
- `RECOVERING`
- `REPLANNING`
- `PAUSED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`
- `TIMED_OUT`
- `HALTED`
- `INTERRUPTED`

`terminationReason` reste séparé de l'état.

Toute transition est validée par une table explicite et auditée.

## 4. Planner — MUST

### Entrées

- objectif ;
- contexte utilisateur ;
- capacités disponibles ;
- contraintes ;
- état monde pertinent ;
- mémoire utile ;
- niveau d'autonomie ;
- politique ;
- budget temps/coût ;
- historique des tentatives.

### Sortie

Un `Plan` versionné avec `PlanStep` structurés.

`PlanStep` :

- id ;
- intent ;
- requiredCapabilities ;
- dependencies ;
- preconditions ;
- expectedOutcome ;
- verificationStrategy ;
- retryPolicy ;
- riskEstimate ;
- canParallelize ;
- checkpointAfter ;
- status.

### Mode DAG

Le plan peut être un DAG, mais l'orchestrateur reste l'autorité. Les branches indépendantes peuvent être parallélisées selon ressources et risques.

## 5. Verifier — MUST

Après toute action importante, décider :

- objectif de l'étape atteint ;
- partiellement atteint ;
- état inconnu ;
- échec récupérable ;
- échec non récupérable ;
- divergence dangereuse.

Le Verifier doit préférer preuves déterministes :

- code retour ;
- fichier existant + hash ;
- test pass/fail ;
- UI state ;
- réponse API structurée ;
- état Git ;
- artifact signature ;
- base de données ;
- observation écran.

Le modèle peut aider à interpréter, mais une simple affirmation du modèle ne suffit pas pour un effet externe sensible.

## 6. RecoveryEngine — MUST

Niveaux :

1. retry exact si erreur transitoire et action idempotente ;
2. retry modifié selon diagnostic ;
3. recovery local exécuteur ;
4. re-observation ;
5. replanification ;
6. demande utilisateur ;
7. arrêt propre.

Pas de boucle infinie. Compteurs distincts : retries, repairs, replans.

## 7. Checkpoints et reprise — MUST

Créer un checkpoint :

- avant action L3 ;
- après effet externe réussi ;
- après milestone de plan ;
- avant changement de workspace/branche ;
- avant build long ;
- périodiquement pour tâches longues ;
- avant compaction de contexte.

`Checkpoint` contient :

- taskId ;
- planVersion ;
- completedSteps ;
- pendingSteps ;
- notebookSnapshot ;
- workspace revisions ;
- idempotency ledger ;
- relevant external state fingerprints ;
- createdAt.

### Reprise après crash

Au démarrage :

1. trouver tâches non terminales ;
2. ne jamais répéter aveuglément ;
3. charger dernier checkpoint ;
4. vérifier état externe ;
5. comparer idempotency ledger ;
6. reprendre uniquement les étapes sûres ;
7. sinon `WAITING_USER` avec rapport clair.

## 8. ContextEngine — MUST

Le `ContextBuilder` actuel devient un ContextEngine capable de :

- budget tokens ;
- hiérarchie de priorités ;
- retrieval mémoire ;
- état tâche ;
- plan compact ;
- notebook ;
- extraits repo ;
- tool definitions dynamiques ;
- compression conversation ;
- pièces jointes ;
- taint labels ;
- provenance ;
- résumé des tool outputs ;
- fenêtre glissante.

### Ordre de priorité contexte

1. politiques système ;
2. objectif/contraintes utilisateur ;
3. état tâche/plan ;
4. observations récentes ;
5. données strictement requises ;
6. mémoire pertinente ;
7. historique récent ;
8. résumés plus anciens.

## 9. Dynamic Tool Discovery — MUST

Ne pas injecter tous les outils à tous les modèles.

Pipeline :

- IntentRouter détermine catégories ;
- CapabilityMatcher sélectionne un sous-ensemble ;
- ModelGateway reçoit seulement les définitions utiles ;
- l'agent peut demander `tools.discover` si nécessaire ;
- ToolRegistry reste la source canonique.

Bénéfices : coût, contexte, sécurité, précision.

## 10. Mémoire VNext

Couches :

### Working
État éphémère de tâche, persistant via checkpoints mais pas comme souvenir utilisateur.

### Conversation
Messages et résumés de sessions.

### Episodic
Événements/tâches réalisés, résultats, décisions opérationnelles.

### Semantic
Faits et connaissances stables.

### Profile/Preference
Préférences explicites, paramètres comportementaux.

### Procedural
Skills/procédures réutilisables.

### Knowledge graph optionnel
Relations entre entités mémorisées.

## 11. Retrieval hybride — MUST

Combiner :

- FTS lexical existant ;
- filtres structurés ;
- récence ;
- importance ;
- provenance ;
- score sémantique embeddings ;
- graphe si utile.

FTS reste fallback obligatoire si l'index vectoriel est indisponible.

### Embeddings

Prévoir `EmbeddingProvider` :

- local par défaut si modèle léger disponible ;
- distant optionnel ;
- batch ;
- cache ;
- model/version fingerprint ;
- réindexation contrôlée.

L'index vectoriel est dérivé. La base Room reste source de vérité.

## 12. Skill Engine — MUST

### Définition

Un skill est une procédure structurée versionnée, jamais une chaîne brute de coordonnées.

```text
Skill
- id
- name
- version
- description
- triggerHints[]
- parametersSchema
- requiredCapabilities[]
- preconditions[]
- steps[]
- verification[]
- safetyLevel
- environmentConstraints
- app/package constraints
- version constraints
- successCount
- failureCount
- confidence
- createdFrom
- createdAt
- updatedAt
```

## 13. Apprentissage d'un skill

Après une tâche réussie et répétable :

1. détecter trajectoire candidate ;
2. retirer données spécifiques ;
3. paramétrer variables ;
4. remplacer coordonnées par sélecteurs sémantiques quand possible ;
5. générer préconditions ;
6. générer postconditions ;
7. classer risque ;
8. valider par simulation/replay sûr ;
9. demander confirmation si le skill peut avoir des effets externes ;
10. versionner.

## 14. Replay sûr

Avant chaque step :

- vérifier environnement ;
- vérifier package/version ;
- vérifier écran/état attendu ;
- exécuter ;
- observer ;
- vérifier postcondition ;
- abandonner ou replanifier si divergence.

Coordonnées = fallback dernier recours.

## 15. Skill invalidation

Invalider/réduire confiance si :

- app mise à jour ;
- UI diverge ;
- N échecs ;
- permission manquante ;
- policy change ;
- selector absent ;
- backend capability change.

## 16. Improvement Service — MUST

Service d'analyse post-tâche, sans modification autonome silencieuse :

- repérer échecs récurrents ;
- proposer meilleur skill ;
- proposer fast path ;
- proposer meilleure règle de routing ;
- proposer nouveau test ;
- détecter doublons outils ;
- détecter outils inutilisés ;
- détecter prompts trop longs ;
- détecter coûts anormaux ;
- détecter régressions.

Toute modification du code de Cortana par Cortana elle-même passe par le même Software Factory + review + approbation.

## 17. Multi-agent interne — MUST mais subordonné

Les spécialistes sont des **workers cognitifs éphémères**, pas des orchestrateurs concurrents.

Exemples de rôles :

- researcher ;
- code analyst ;
- implementer ;
- reviewer ;
- test analyst ;
- security reviewer ;
- document analyst ;
- planner specialist.

Le TaskOrchestrator :

- crée sous-tâche ;
- fournit contexte minimal ;
- limite outils ;
- reçoit `SpecialistResult` ;
- décide d'intégrer ;
- reste seul responsable des transitions de tâche.

## 18. Parallelisme

Autorisé seulement pour sous-tâches indépendantes.

Garde-fous :

- pas de deux writers sur le même fichier/workspace sans coordination ;
- locks ;
- budgets ;
- cancellation propagation ;
- résultats déterministes quand possible ;
- merge des conclusions par orchestrateur.

## 19. FastPath Engine extensible

Transformer les deux fast paths actuels en registre :

```text
FastPath
- id
- matcher
- confidenceThreshold
- parametersExtractor
- policy
- executor
- responseRenderer
- tests
```

Candidats :

- rappel ;
- mémoire explicite ;
- ouvrir application ;
- régler volume/luminosité ;
- minuteur ;
- action déterministe sûre ;
- commande locale fréquente.

Un fast path ne contourne jamais la politique.

## 20. Critères de sortie runtime

- Planner réel ;
- plan versionné ;
- Verifier réel ;
- Replanner ;
- checkpoints ;
- reprise après kill process ;
- mémoire hybride ;
- skills ;
- tool discovery dynamique ;
- subagents spécialisés ;
- tests de crash/reprise ;
- absence de double orchestrateur.
