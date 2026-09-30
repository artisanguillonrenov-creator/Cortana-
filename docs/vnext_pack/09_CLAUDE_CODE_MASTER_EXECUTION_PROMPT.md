# 09 — INSTRUCTIONS D'EXÉCUTION À DONNER À CLAUDE CODE

## Mission

Tu dois faire évoluer le projet Cortana fourni vers l'architecture Cortana VNext décrite dans le dossier de production. Tu ne dois pas repartir de zéro ni réduire le périmètre pour livrer plus vite.

## Comportement obligatoire

1. Lis **tout le dossier de production** avant de modifier le code.
2. Ouvre et inspecte réellement les fichiers source concernés avant toute affirmation ou modification.
3. Établis une baseline : tests, lint, build et état Git.
4. Crée/maintiens `docs/VNEXT_PROGRESS.md` avec : phase, état, décisions, tests, blockers, fichiers modifiés, prochaine action.
5. Implémente les phases dans l'ordre de `07_IMPLEMENTATION_ROADMAP.md`, sauf dépendance technique justifiant un changement d'ordre.
6. Ne supprime pas un comportement existant sans équivalent fonctionnel testé.
7. Ne crée jamais deux implémentations actives concurrentes d'une même responsabilité.
8. Toute amélioration technique au plan est autorisée si elle est strictement meilleure, testée, documentée et ne réduit pas le scope.
9. Si tu trouves mieux : implémente le mieux, puis inscris la décision dans `docs/DECISIONS.md` avec comparaison, impacts et migration.
10. Ne reporte pas silencieusement une fonction MUST. Si un blocage environnemental empêche son exécution, implémente ce qui peut l'être, écris le test si possible, documente le blocage et continue les travaux indépendants.
11. Ne prétends jamais qu'un test physique a réussi s'il n'a pas été exécuté.
12. Ne modifie jamais l'identité de package ni la signature attendue sans instruction.
13. N'introduis pas de migration destructive de la base.
14. N'expose jamais les secrets dans logs, rapports ou UI de debug.
15. Pas de push/merge/publication automatique. Préparer les changements ; le propriétaire décide de la publication.

## Stratégie de travail

Pour chaque phase :

### A. Discovery

- fichiers concernés ;
- flux actuels ;
- tests actuels ;
- dépendances ;
- risques ;
- migration.

### B. Design check

Comparer le dossier de production avec la réalité du code. Si un détail est obsolète ou sous-optimal, choisir la solution la plus robuste qui respecte les contrats et documenter.

### C. Implementation

Petits changesets cohérents. Éviter les refactors massifs non nécessaires à la phase.

### D. Validation

- tests ciblés ;
- tests de non-régression ;
- lint ;
- build ;
- migration tests ;
- security tests si pertinent.

### E. Review

Relire diff et vérifier :

- architecture ;
- doublons ;
- secrets ;
- erreurs ;
- TODO ;
- tests ;
- docs.

### F. Progress

Mettre à jour `VNEXT_PROGRESS.md` et `11_CAPABILITY_CHECKLIST.md` copie intégrée dans docs du projet.

## Qualité attendue

- solution générale, pas hardcodée pour tests ;
- interfaces typées ;
- erreurs structurées ;
- cancellation ;
- timeouts ;
- idempotence ;
- tracing ;
- lifecycle Android correct ;
- coroutine scopes structurés ;
- pas de GlobalScope ;
- pas de blocking main thread ;
- Room migrations testées ;
- IO sur Dispatchers.IO ;
- UI state explicite ;
- config versionnée ;
- compatibilité offline.

## Règles coding-agent

Pour toute modification de code par Cortana elle-même, le futur runtime doit appliquer les mêmes principes :

- inspect before edit ;
- reversible local actions by default ;
- sandbox untrusted builds ;
- branch/worktree isolation ;
- test before completion ;
- no destructive Git shortcuts ;
- no secret exposure ;
- checkpoint long tasks ;
- verify final diff.

## Fichiers de documentation à maintenir

- `docs/DECISIONS.md`
- `docs/VNEXT_PROGRESS.md`
- `docs/ARCHITECTURE.md`
- `docs/DATA_MIGRATIONS.md`
- `docs/SECURITY.md`
- `docs/TOOL_CAPABILITIES.md`
- `docs/TEST_MATRIX.md`
- `docs/WORKER_PROTOCOL.md`
- `docs/RELEASE.md`

## Format d'une décision

```text
D-YYYYMMDD-NNN — titre
Status: accepted/replaced/experimental
Problem:
Options considered:
Decision:
Why:
Compatibility impact:
Security impact:
Data migration:
Tests:
Rollback:
```

## Gate finale

Ne déclarer VNext terminée que lorsque :

- toutes les capabilities MUST de `11_CAPABILITY_CHECKLIST.md` sont DONE ou ont un blocker externe réel documenté ;
- aucune régression baseline ;
- DB migrations validées ;
- coding E2E validé ;
- crash/resume validé ;
- policy/security tests validés ;
- APK release signé ;
- source archive reproductible ;
- note d'installation/migration mise à jour ;
- tests non exécutables clairement séparés des tests réussis.
