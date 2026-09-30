# 2. Architecture d’intégration

## 2.1 Position
```text
User / Voice / Automation
          |
    TaskOrchestrator
          |
   +------+------+
   |             |
Normal path   CognitiveCouncilEngine
                    |
       +------------+------------+
       |            |            |
 CouncilPlanner CouncilRuntime DecisionEngine
       |            |            |
       +------- ModelGateway -----+
                    |
             provider adapters

Tools:
CouncilRuntime
 -> CapabilityMatcher
 -> ToolRegistry
 -> PolicyEngine
 -> Executor

Memory:
CouncilRuntime -> ContextEngine -> Memory Service

Audit:
all -> Observability/Audit
```

## 2.2 Autorité
Le `TaskOrchestrator` :
1. reçoit TaskRequest;
2. choisit conseil ou pipeline normal;
3. crée `CouncilRunRequest`;
4. délègue;
5. reçoit `CouncilResult`;
6. poursuit la state machine globale.

Le CCE ne peut pas :
- terminer directement la Task globale;
- faire une action externe hors PolicyEngine;
- accéder aux secrets en clair;
- écrire dans une mémoire parallèle;
- faire merge/release.

## 2.3 Composants

### CouncilPolicySelector
Décide OFF/FAST/REINFORCED/COUNCIL4/DEEP/AUTO/CUSTOM.

### CouncilProfileRegistry
Profils cognitifs validés.

### CouncilPlanner
Produit :
- slots;
- rôles;
- modèles;
- topologie;
- rounds;
- protocoles;
- tool scopes;
- budgets;
- critères d’arrêt.

### CouncilRuntime
Parallélisme, rounds, cancellation, quorum.

### CouncilMessageRetainer
Sélectionne les arguments transférés.

### CouncilDecisionEngine
Vote, consensus, classement, juge.

### CouncilEvidenceLedger
Claims, preuves, contradictions, fraîcheur.

### CouncilSynthesizer
Réponse finale à partir d’objets structurés.

### CouncilVerifierBridge
Connexion au Verifier existant.

### CouncilBudgetGuard
Tokens, coût, latence, appels, concurrence.

## 2.4 Flux canonique
1. TaskOrchestrator prépare tâche.
2. PolicySelector choisit mode.
3. Planner crée CouncilPlan.
4. ModelGateway résout modèles.
5. CapabilityMatcher sélectionne outils.
6. ContextEngine prépare contexte.
7. Round 0 parallèle et indépendant.
8. Normalisation.
9. Vote initial.
10. Divergence + evidence check.
11. Rétention ciblée.
12. Round critique si nécessaire.
13. Décision.
14. Challenge facultatif.
15. Synthèse.
16. Verification.
17. Repair borné si nécessaire.
18. retour au TaskOrchestrator.

## 2.5 Concurrence
- coroutine fan-out/fan-in;
- cancellation structurée;
- timeout par agent;
- timeout global;
- semaphore par provider;
- rate limiter;
- partial result tolerance.

Round 0 parallèle.
Rounds suivants parallèles par vague.

## 2.6 Atomicité
Un CouncilRun est une sous-opération.
Persister seulement :
- config nettoyée;
- slots;
- résumés structurés;
- claims;
- votes;
- décision;
- métriques.

Pas de hidden reasoning.

## 2.7 Extension Bureaux
Les futurs bureaux Créatif/Concept/Commercial/Logiciel/Recherche pourront définir des
`CouncilPreset`. Le runtime reste unique.
