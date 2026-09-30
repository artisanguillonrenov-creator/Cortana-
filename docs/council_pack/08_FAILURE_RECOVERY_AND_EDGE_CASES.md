# 8. Échecs, récupération et cas limites

## 8.1 Agent timeout
- slot TIMED_OUT;
- quorum atteint => continuer;
- sinon fallback;
- sinon PARTIAL/FAILED.

## 8.2 429
- respecter retry-after;
- autres providers peuvent continuer;
- aucune boucle agressive.

## 8.3 413
- compacter;
- réduire tool schemas;
- réduire retained arguments;
- rerouter si autorisé;
- jamais retry identique.

## 8.4 JSON invalide
- parser strict;
- une tentative repair bornée;
- sinon contribution invalidée;
- jamais exécuter un tool à partir d’un payload invalide.

## 8.5 Faux consensus
Le Challenger/Verifier peut renverser la majorité.
La majorité n’est jamais une preuve.

## 8.6 Boucle infinie
- maxRounds hard cap;
- stable decision detection;
- early stop;
- budget guard.

## 8.7 Provider down
Fallback si privacy l’autorise.
Sinon local ou PARTIAL.

## 8.8 App process death
Reprise uniquement à frontière sûre :
- après round;
- après décision;
- pas au milieu d’un side effect.

Ne pas rejouer side effects.

## 8.9 Modèle disparu
Le ModelGateway résout fallback.
UI indique modèle préféré indisponible.

## 8.10 Coût inconnu
Token budget reste obligatoire.
Cost = unknown si prix non connu.

## 8.11 Un seul agent disponible
Mode dégradé single-agent.
Ne pas prétendre « conseil ».

## 8.12 Quatre agents même modèle
Autorisé.
Diversité par rôle/sampling/contexte.
Ne pas surévaluer l’indépendance.

## 8.13 Quatre modèles différents
Parser modèle-agnostique.
Normalize outputs.
Gérer capacités/outils différentes.

## 8.14 Recursive council
Interdit par défaut.
Un sous-agent ne peut pas créer un autre CouncilRun.

## 8.15 Duplicate tools
Shared deterministic cache.
Dédupliquer recherches identiques.
Side effects toujours idempotents.

## 8.16 Réseau perdu
Suspend/retry borné.
Si offline local disponible, fallback.
Sinon PARTIAL.

## 8.17 Batterie faible
Mode Auto peut réduire :
- agents;
- tours;
- modèles locaux lourds;
selon politique utilisateur.
