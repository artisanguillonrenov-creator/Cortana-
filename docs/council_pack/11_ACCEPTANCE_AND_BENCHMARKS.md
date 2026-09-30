# 11. Acceptation, évaluation et benchmarks

## 11.1 Tests fonctionnels
- OFF non-régressif.
- 2 agents.
- 4 agents.
- même modèle.
- modèles différents.
- providers différents.
- un agent fail.
- quorum.
- vote.
- ranked.
- hybrid.
- judge.
- challenge.
- early stop.
- max rounds.
- minority preserved.

## 11.2 Tests contexte
- hard context cap;
- output reserve;
- tool pruning;
- memory compaction;
- 413 payload smaller;
- no identical retry;
- retained args fit;
- large artifact summarized by ref.

## 11.3 Outils/policy
- read-only child;
- tool discovery;
- L2 policy;
- L3 explicit auth;
- tainted content;
- side effect idempotency;
- STOP.

## 11.4 Sécurité
- no API key in prompt;
- no secret logs;
- no chain-of-thought persistence;
- privacy allowlist;
- local-only;
- malformed output not executable;
- recursive council blocked.

## 11.5 Résilience
- 429;
- timeout;
- provider down;
- model unavailable;
- process restart;
- migration;
- cancel;
- budget exhaustion.

## 11.6 UI
- mode change;
- per-role model;
- fallback indicator;
- consensus summary;
- partial warning;
- accessibility;
- tablet/phone responsive.

## 11.7 Baselines de qualité
B0 modèle principal seul.
B1 4 indépendants + synthèse.
B2 4 indépendants + vote.
B3 4 + un round.
B4 4 + rétention.
B5 4 + rétention + challenge.
B6 4 + judge.
B7 Auto.

## 11.8 Métriques
- task success;
- correctness;
- compile/test rate;
- citation consistency;
- unsafe proposal rate;
- input/output tokens;
- cost;
- p50/p95 latency;
- provider errors;
- verifier rejection;
- consensus accuracy;
- minority overturn.

## 11.9 Ablations
Désactiver séparément :
- confidence;
- vote;
- retention;
- challenger;
- judge;
- mixed models;
- independent initial round.

## 11.10 Golden set
Créer >= 50 tâches Cortana :
- code;
- research;
- Android;
- planning;
- business;
- security;
- ambiguous questions.

Chaque fixture :
- input;
- facts;
- allowed actions;
- forbidden actions;
- oracle quand possible.
