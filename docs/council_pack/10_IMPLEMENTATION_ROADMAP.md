# 10. Roadmap d’implémentation détaillée

Cette extension vient après la baseline Cortana. Ne pas casser les 33 phases existantes.
Nommer les étapes C1..C12.

## C1 — Contracts + feature flag
Créer :
- enums;
- interfaces;
- feature flag OFF;
- config;
- service no-op.

Gate :
- build vert;
- OFF identique à historique.

## C2 — Profile Registry
Créer :
- Strategist;
- Evidence Analyst;
- Solution Engineer;
- Challenger;
- validation;
- préférences modèle.

Tests :
- duplicate profile;
- invalid capability;
- fallback config.

## C3 — Parallel Initial Round
- fan-out/fan-in;
- timeout;
- cancellation;
- quorum;
- 2/4 agents.

Gate :
- round initial réellement indépendant;
- aucune contribution d’un pair dans le premier prompt.

## C4 — Structured Contribution
- JSON schema;
- parser;
- repair borné;
- claim extraction;
- concerns;
- confidence features;
- candidate fingerprint.

## C5 — Decision Engine
- simple majority;
- approval;
- ranked;
- hybrid;
- supermajority;
- unanimity;
- deterministic fixtures.

## C6 — Retention Engine
- novelty;
- disagreement;
- evidence;
- critical;
- redundancy;
- token-aware packing;
- minority protection.

Gate :
- contexte réduit;
- critical jamais perdu.

## C7 — Debate Rounds
- 1-3 rounds;
- topologies;
- adaptive;
- early stop;
- stable decision.

## C8 — Judge + Challenge
- blind judge;
- challenge final;
- mini repair round.

Gate :
- seeded false consensus renversable.

## C9 — Tools + Evidence
- CapabilityMatcher;
- evidence ledger;
- read-only default;
- PolicyEngine bridge;
- taint.

## C10 — UI
- réglages;
- modèles par rôle;
- budget;
- progression;
- summary.

## C11 — Persistence + Telemetry
- Room migrations;
- run summaries;
- metrics;
- diagnostics;
- restart boundaries.

## C12 — Hardening
- 413;
- 429;
- timeout;
- provider down;
- mixed models;
- cancellation;
- load;
- battery;
- security;
- migrations;
- E2E.

## Discipline de chantier
À chaque phase :
1. inspecter code réel;
2. proposer mapping fichiers;
3. coder;
4. tests unitaires;
5. tests intégration;
6. lint/build;
7. résumé précis;
8. ne pas auto-merge.
