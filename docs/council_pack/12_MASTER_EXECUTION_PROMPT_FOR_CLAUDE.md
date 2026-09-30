# MASTER EXECUTION PROMPT — CLAUDE CODE

Tu dois intégrer dans Cortana un nouveau sous-système `CognitiveCouncilEngine` conformément
à l’intégralité de ce dossier.

## Mission
Créer un moteur de réflexion multi-agent natif, configurable, borné et auditable, sans introduire
de deuxième architecture concurrente.

## Avant tout code
1. inspecte le dépôt réel;
2. localise TaskOrchestrator, ModelGateway, ToolRegistry, PolicyEngine, ContextEngine,
   Verifier, RecoveryEngine, DB, ConfigService et UI Settings;
3. produis une table de mapping « concept du dossier -> classe/fichier existant »;
4. identifie les conflits;
5. propose les fichiers à modifier/créer;
6. ne code pas un doublon d’un service existant.

## Lois absolues
- un seul orchestrateur;
- un seul gateway modèle;
- un seul registry outils;
- une seule policy;
- une seule mémoire canonique;
- tous les enfants sont subordonnés;
- aucun framework multi-agent externe complet;
- aucune chain-of-thought persistée;
- aucun secret dans prompt/log;
- aucun side effect sans policy;
- aucun auto-merge.

## Exécution 4 agents
Round 0 :
- quatre analyses parallèles;
- zéro contamination inter-agent;
- sortie structurée.

Assessment :
- candidate normalization;
- claims;
- confidence features;
- evidence;
- vote;
- divergence;
- concerns.

Retention :
- transmettre uniquement arguments utiles;
- protéger critical/minority/evidence;
- respecter budget.

Round critique :
- maximum configuré;
- early stop.

Decision :
- HYBRID par défaut;
- majorité non suffisante si critical concern;
- verifier peut invalider majorité.

Challenge :
- optionnel;
- mini repair round borné.

Synthesis :
- réponse utilisateur unique;
- pas de reasoning privé.

## Modèles
Supporter SAME_MODEL / PER_ROLE / AUTO_CAPABILITY / HYBRID.
Permettre modèle différent pour :
- Strategist;
- Evidence;
- Engineer;
- Challenger;
- Synthesizer;
- Judge.

## Contexte
Context Budget Manager avant CHAQUE appel.
Ne jamais dépasser hard cap.
Ne jamais retry 413 identique.
Ne jamais envoyer le catalogue complet d’outils.

## Outils
Read-only par défaut.
CapabilityMatcher fournit uniquement sous-ensemble utile.
Toute action L2/L3 repasse par PolicyEngine.

## Persistence
Utiliser DB existante.
Migrations non destructives.
Pas de second stockage canonique.

## Tests
Implémenter toute la matrice du dossier.
Créer fake providers/scripted models pour erreurs 413/429/timeouts/malformed JSON.
Créer seeded false consensus.

## Étapes
Implémenter C1 -> C12 dans l’ordre.
Après chaque Cx :
- objectif;
- fichiers;
- changements;
- tests;
- résultats;
- risques;
- dette;
- prochaine étape.

## Gate finale
Ne pas déclarer fini avant :
- tests verts;
- build release;
- OFF non-régressif;
- 4 agents multi-model;
- multi-provider;
- retention prouvée;
- critical preservation;
- STOP;
- migrations;
- no secrets;
- no critical blocker.
