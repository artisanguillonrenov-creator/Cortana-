# 18. Migration, compatibilité et release

## 18.1 Feature flag
Version initiale :
`council.enabled=false` par défaut jusqu’à validation.

## 18.2 Backward compatibility
Sessions anciennes :
- aucune obligation de créer CouncilRun;
- historique inchangé;
- paramètres absent => defaults.

## 18.3 DB
Ajouter tables via migration additive.
Index recommandés :
- parent_task_id;
- run_id + round_index;
- claim verification status;
- created_at.

## 18.4 Upgrade test
Tester :
- install version actuelle;
- créer conversations/mémoires;
- upgrade APK;
- DB migration;
- ouvrir anciennes conversations;
- lancer conseil;
- downgrade behavior documenté.

## 18.5 Config migration
Ancien setting « modèle principal » reste.
Si per-role absent :
- inherit main model.
Si provider fallback absent :
- use ModelGateway defaults.

## 18.6 Telemetry migration
Nouveaux spans facultatifs.
Aucune dépendance obligatoire à serveur externe.

## 18.7 Rollback
Feature flag OFF doit neutraliser le CCE sans supprimer données.
Rollback APK doit être testé selon règles Room/schema.

## 18.8 Release gates
- all tests;
- lint;
- unit;
- instrumentation si environnement;
- migration;
- real-device;
- memory leak;
- battery;
- rate-limit;
- 413;
- STOP;
- privacy.

## 18.9 Changelog
Documenter :
- nouveau mode conseil;
- coût potentiel;
- réglages modèles;
- privacy;
- limites.

## 18.10 Support diagnostics
Export diagnostic redacted :
- config snapshot;
- model routes;
- errors;
- token counts;
- decisions;
- no secrets;
- no raw reasoning.
