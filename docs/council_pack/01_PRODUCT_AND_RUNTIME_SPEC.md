# 1. Spécification produit et runtime

## 1.1 But
Le Conseil de réflexion augmente la robustesse d’une réponse en combinant :
1. indépendance initiale;
2. diversité cognitive;
3. preuves et outils;
4. critique contradictoire;
5. décision structurée;
6. synthèse vérifiée.

Il ne garantit jamais la vérité. Il réduit les erreurs corrélées et aide à détecter les failles.

## 1.2 Conseil standard 4
Par défaut :
- Agent A : Stratège.
- Agent B : Analyste factuel.
- Agent C : Ingénieur / Solveur.
- Agent D : Challenger.
- Synthèse : Cortana via modèle principal ou modèle configuré.

Le synthétiseur n’est pas nécessairement un cinquième agent permanent. Il peut être une étape
du modèle principal après agrégation structurée.

## 1.3 Déclenchement Auto
Déclencher ou proposer le conseil lorsque :
- plusieurs contraintes;
- architecture logicielle;
- debugging non trivial;
- revue de sécurité;
- recherche avec sources contradictoires;
- décision multi-critères;
- plan long;
- besoin de contre-vérification.

Ne pas l’utiliser automatiquement pour :
- salutations;
- conversions;
- minuteurs;
- navigation simple;
- réglages déterministes;
- commandes Android courtes.

## 1.4 Contrat de sortie
L’utilisateur reçoit une seule réponse finale.

Une carte optionnelle « Résumé du conseil » peut montrer :
- consensus fort/moyen/faible;
- points d’accord;
- objections importantes;
- incertitudes;
- preuves/outils utilisés;
- modèles participants;
- coût/tokens en mode développeur.

Ne pas montrer :
- chaînes de pensée;
- hidden reasoning;
- prompts système;
- secrets.

## 1.5 State machine
```text
CREATED
 -> PLANNING
 -> RESOLVING_MODELS
 -> PREPARING_CONTEXT
 -> ROUND_INITIAL
 -> ASSESSING
 -> [ROUND_CRITIQUE -> ASSESSING]*
 -> DECIDING
 -> CHALLENGING? 
 -> SYNTHESIZING
 -> VERIFYING
 -> COMPLETED

Terminal:
CANCELLED
FAILED
TIMED_OUT
BUDGET_EXHAUSTED
PARTIAL
```

## 1.6 Invariants
- `COMPLETED` exige une décision structurée.
- `PARTIAL` possible si quorum atteint.
- un agent en panne n’implique pas l’échec global.
- STOP => CANCELLED.
- budget épuisé avec résultats exploitables => synthèse dégradée.
- budget épuisé sans quorum => BUDGET_EXHAUSTED.

## 1.7 Quorum
Valeurs initiales :
- 1 agent => 1;
- 2 agents => 2;
- 4 agents => 3;
- N => ceil(0.67*N).

Un rôle obligatoire peut avoir fallback de modèle.

## 1.8 Round 0 : indépendance
Chaque agent reçoit :
- le même TaskBrief canonique;
- son rôle;
- le contexte pertinent;
- son sous-ensemble d’outils;
- aucun avis d’un autre agent.

Sortie structurée :
- candidat;
- claims;
- hypothèses;
- préoccupations;
- confiance;
- preuves;
- vérifications demandées;
- résumé de justification.

## 1.9 Assessment
Après chaque round :
- normaliser les candidats;
- regrouper équivalents;
- calculer vote;
- mesurer divergence;
- mesurer qualité de preuve;
- détecter objection critique;
- décider stop/continue.

## 1.10 Round critique
Chaque agent reçoit seulement :
- son avis précédent résumé;
- candidats concurrents utiles;
- objections ciblées;
- preuves nouvelles;
- décision provisoire si utile.

Pas de transcript complet automatique.

## 1.11 Early stop
Arrêter si :
- consensus fort;
- aucune objection critique;
- décision stable sur deux évaluations;
- gain marginal estimé faible;
- budget insuffisant;
- utilisateur annule.

Continuer si :
- divergence forte;
- preuve contradictoire;
- verifier invalide majorité;
- objection critique non résolue.

## 1.12 Challenge final
Optionnel et conseillé pour risque élevé.
Le Challenger tente :
- contre-exemple;
- contrainte oubliée;
- erreur de sécurité;
- hypothèse fragile;
- meilleure alternative.

Si objection critique confirmée : mini-round de réparation.
