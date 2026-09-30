# 16. Presets par défaut

## 16.1 ECO
But : gain qualité avec coût faible.
- agents: 2
- rounds: 0
- topology: independent
- decision: hybrid light
- judge: off
- challenge: off
- retention: non applicable
- model: small/cheap compatible
- synthesis: main model
- max concurrent: 2

## 16.2 BALANCED
- agents: 4
- rounds: 1
- topology: sparse_dynamic
- decision: hybrid
- challenge: on
- judge: off
- retention: on
- max args/target: 6
- max concurrent: 4

## 16.3 QUALITY
- agents: 4
- rounds: 2
- topology: adaptive
- decision: hybrid
- challenge: on
- judge: auto
- retention: on
- evidence verification: elevated

## 16.4 DEEP
- agents: 4 à 8 selon budget
- rounds: 3
- topology: adaptive
- judge: blind judge
- challenge: on
- verifier: strict
- minimum evidence coverage plus élevé
- confirmation utilisateur si coût prévisionnel > seuil

## 16.5 CODE_REVIEW
Rôles :
- Architect;
- Implementer;
- Test Analyst;
- Security Reviewer.
Tools :
- repo read;
- files;
- shell sandbox;
- test/build;
- static analysis.
Side effects : aucun merge.

## 16.6 RESEARCH
Rôles :
- Query Planner;
- Source Researcher;
- Fact Checker;
- Skeptic.
Tools :
- search;
- web read;
- document read.
Décision :
- evidence-weighted hybrid.
Règle :
- claim importante sans preuve => explicitement non vérifiée.

## 16.7 ANDROID_DIAGNOSTIC
Rôles :
- Device Analyst;
- Performance Analyst;
- Safety/Permissions Reviewer;
- Challenger.
Tools :
- observe;
- battery;
- storage;
- apps;
- settings read;
- diagnostics.
Actions modification : repassent par PolicyEngine.

## 16.8 BUSINESS
Rôles :
- Strategist;
- Market/Evidence;
- Financial/Operational;
- Risk Challenger.
Décision :
- ranked/hybrid.
Sortie :
- options + contraintes + risques.

## 16.9 CREATIVE
Rôles :
- Creative Director;
- Concept Generator;
- Style/Consistency Reviewer;
- Audience Challenger.
Mode :
- diversité prioritaire;
- consensus faible acceptable;
- sélection ranked;
- ne pas forcer convergence trop tôt.

## 16.10 AUTO
Sélection initiale :
```text
risk low + simple -> OFF/FAST
compare/review -> REINFORCED
code/research/architecture -> BALANCED
high risk -> QUALITY
explicit deep -> DEEP
```

Ces règles sont point de départ, à calibrer.
