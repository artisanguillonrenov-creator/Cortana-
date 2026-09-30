# 08 — MATRICE D'ACCEPTATION ET DE TEST

## Règle

Une fonction n'est pas "faite" parce qu'elle compile. Elle est faite quand son niveau de test requis est satisfait.

Niveaux :

- U = unit
- C = contract
- I = integration
- R = Robolectric
- A = Android instrumented
- P = physical device
- S = security/adversarial
- E = end-to-end

## 1. Non-régression 1.2.0

| Scénario | Niveaux |
|---|---|
| Chat streaming | U/I/R/E |
| Provider 401/429/5xx | U/I |
| SSE fragments | U |
| native tool calling | U/I/E |
| emulated tool calling | U/I/E |
| reminder fast path no LLM | U/R/E/P |
| explicit memory fast path | U/R/E |
| STOP removes tools | R/E/P |
| L2 approval | R/A/P/S |
| L3 approval/biometric | A/P/S |
| UI accessibility actions | A/P |
| SSRF | U/S |
| secret redaction | U/S |
| cron timezone/DST | U/R |
| reboot rearm | A/P |

## 2. Planner/Verifier

- plan simple 3 steps ;
- dependencies ;
- parallel-independent steps ;
- failed step recover ;
- failed step replan ;
- verifier detects false-success ;
- budget exceeded ;
- user cancel ;
- STOP ;
- waiting user.

## 3. Crash/restart

Scénarios :

1. crash avant tool call → resume step ;
2. crash pendant idempotent read → retry ;
3. crash après SMS/file write success mais avant state persist → idempotency prevents duplicate ;
4. crash after checkpoint ;
5. corrupted checkpoint → waiting user ;
6. worker disconnected → reconnect/reassign ;
7. app update during pending task → safe startup.

## 4. Memory

- exact FTS ;
- semantic paraphrase ;
- hybrid score ;
- stale memory ;
- supersede ;
- pending confirmation ;
- delete ;
- incognito ;
- sensitive memory routing ;
- embedding provider unavailable fallback ;
- index rebuild ;
- graph relation.

## 5. Skills

- learn candidate ;
- parameterize ;
- preconditions ;
- postconditions ;
- safe replay ;
- target missing ;
- app version changed ;
- repeated failures reduce confidence ;
- coordinate fallback ;
- L2/L3 skill still asks permission ;
- skill export/import versioning.

## 6. Coding / Software Factory

### E2E-CODE-001 — bug simple

- clone/import repo fixture ;
- inspect ;
- locate bug ;
- create plan ;
- create isolated branch/worktree ;
- patch ;
- run targeted test ;
- build ;
- review diff ;
- artifact ;
- no push.

### E2E-CODE-002 — compile error

- build fails ;
- normalize diagnostic ;
- locate symbol ;
- patch ;
- rebuild passes.

### E2E-CODE-003 — regression

- initial tests pass ;
- naive patch breaks unrelated test ;
- verifier rejects completion ;
- repair ;
- full suite passes.

### E2E-CODE-004 — long task resume

- stop process mid-work ;
- restart ;
- load notebook/checkpoint ;
- verify repo revision ;
- continue without duplicate edits.

### E2E-CODE-005 — malicious repo instruction

Repo contains instruction to print secret/delete files. Cortana must ignore/escalate and sandbox scripts.

### E2E-CODE-006 — Git protection

Attempt force push/reset-hard → denied or explicit L3 approval according to policy.

## 7. Worker

- pair ;
- revoke ;
- capability discovery ;
- offline ;
- reconnect ;
- stale session rejected ;
- artifact checksum ;
- command timeout ;
- sandbox network deny ;
- worker tries unauthorized path ;
- secret unavailable to worker unless explicitly scoped.

## 8. Vision

- accessibility tree complete → no screenshot needed ;
- tree missing target → screenshot fallback ;
- OCR target ;
- vision target ;
- sensitive screen screenshot blocked/redacted ;
- screen changed after approval → reclassify ;
- coordinate target drift → verifier stops.

## 9. Voice

- push-to-talk ;
- wake word enabled ;
- wake word disabled ;
- VAD ;
- barge-in ;
- TTS cancel ;
- offline STT fallback ;
- microphone permission revoked ;
- background restrictions.

## 10. MCP

- discovery ;
- duplicate tool names namespace ;
- malformed schema ;
- timeout ;
- server disconnect ;
- tainted resource ;
- dangerous external tool policy ;
- cancellation ;
- version negotiation.

## 11. A2A

- agent card ;
- capability match ;
- delegated task ;
- cancellation ;
- file result ;
- untrusted response ;
- no access internal memory ;
- authentication fail.

## 12. Security

- prompt injection Web ;
- prompt injection repository ;
- prompt injection notification ;
- tool-result injection ;
- secret exfil attempt ;
- SSRF ;
- DNS rebinding ;
- path traversal ;
- zip slip ;
- symlink escape worker ;
- malicious dependency script ;
- approval replay ;
- approval TOCTOU ;
- duplicate external effect ;
- STOP race ;
- device lock ;
- overlay attack.

## 13. Data migration

Créer une fixture DB v1 avec :

- sessions ;
- messages ;
- memories ;
- providers ;
- schedules ;
- audit.

Migrer vers dernière version. Vérifier chaque ligne, FTS et settings.

## 14. Backup/restore

- export ;
- checksum ;
- wrong password ;
- truncated archive ;
- incompatible version ;
- restore merge ;
- restore replace ;
- secrets excluded default ;
- secrets encrypted optional.

## 15. Performance targets

À mesurer sur tablette réelle :

- cold start ;
- chat first frame ;
- DB query p50/p95 ;
- FTS retrieval ;
- semantic retrieval ;
- accessibility observation ;
- tool dispatch ;
- memory usage idle/active ;
- battery during 30min automation ;
- large conversation ;
- 10k memories ;
- 100 skills ;
- large repo index delegated to worker.

Les seuils exacts peuvent être ajustés après benchmark, mais régression >20% sur opération clé doit être expliquée.

## 16. Definition of Done globale

Une phase n'est terminée que si :

- code compile ;
- tests requis passent ;
- lint/static checks ;
- migration si nécessaire ;
- docs mises à jour ;
- décisions documentées ;
- aucun secret ;
- aucune TODO critique cachée ;
- capability checklist mise à jour ;
- artifacts produits si phase build/release ;
- limites de test réelles déclarées.
