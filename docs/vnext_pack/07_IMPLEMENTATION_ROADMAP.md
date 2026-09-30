# 07 — ROADMAP D'IMPLÉMENTATION OBLIGATOIRE

## Principe

Ne pas tenter un "big bang". Chaque phase doit laisser Cortana compilable et testable. Cependant, le programme de travail doit se poursuivre jusqu'à couverture complète du dossier ; les phases tardives ne sont pas des options silencieusement reportables.

## Phase 0 — Freeze baseline

- extraire sources ;
- confirmer package/version/signature config ;
- exécuter tests actuels ;
- build debug/release si clé disponible ;
- lint ;
- produire `docs/VNEXT_BASELINE.md` ;
- enregistrer hash des fichiers source ;
- ne rien modifier avant baseline verte ou explication du défaut existant.

**Gate :** baseline reproductible.

## Phase 1 — Contracts + architecture enforcement

Créer les contrats canoniques :

- TaskRequest ;
- Task ;
- TaskEvent ;
- Plan ;
- PlanStep ;
- Observation ;
- VerificationResult ;
- Checkpoint ;
- ToolCall/Result ;
- Artifact ;
- Workspace ;
- WorkerNode ;
- Skill ;
- SpecialistTask/Result.

Ajouter tests empêchant deux propriétaires concurrents.

**Gate :** contrats sérialisables et testés.

## Phase 2 — Database v2+

Ajouter tables runtime durable : task events, plans, checkpoints, approvals, idempotency ledger, artifacts.

Créer migration Room 1→2 avec test de conservation.

**Gate :** base v1 migrée avec données intactes.

## Phase 3 — Extraire StateMachine

Sortir transitions de `Orchestrator.kt`.

- aucune transition sauvage ;
- événements ;
- termination reason ;
- audit.

**Gate :** anciens E2E inchangés + tests transitions.

## Phase 4 — Planner/Verifier/Recovery

Introduire Planner, Verifier, RecoveryEngine, Replanner derrière interfaces.

Commencer avec stratégie simple pour préserver comportement, puis activer plan structuré.

**Gate :** tâche multi-step vérifiée + replan sur échec.

## Phase 5 — Checkpoint/Resume

Remplacer `markInterruptedOnStartup` par logique : interrupted → inspect checkpoint → verify → resume safe ou waiting user.

Conserver possibilité de ne pas reprendre si non sûr.

**Gate :** test kill process simulé + reprise sans double side effect.

## Phase 6 — Context Engine V2

- budget ;
- compaction ;
- tool discovery ;
- notebook ;
- provenance ;
- taint ;
- retrieval.

**Gate :** longue session reste cohérente sous budget.

## Phase 7 — Memory hybrid

- embeddings abstraction ;
- vector index dérivé ;
- hybrid rank ;
- graph relations optionnelles ;
- migration/backfill.

**Gate :** retrieval lexical + semantic avec fallback offline.

## Phase 8 — Skills / procedural memory

- schema ;
- versioning ;
- candidate extraction ;
- replay ;
- pre/postconditions ;
- invalidation ;
- UI Skills.

**Gate :** procédure apprise, rejouée et invalidée proprement après divergence.

## Phase 9 — Developer workspace foundation

- WorkspaceManager ;
- project import ;
- repo detection ;
- code search ;
- patch engine ;
- artifacts.

**Gate :** importer projet, chercher, patch preview/apply/rollback.

## Phase 10 — Git service

- status/diff/log/branch/worktree ;
- commit local ;
- protection destructive ;
- approvals push.

**Gate :** workflow branche isolée sans toucher branche principale.

## Phase 11 — Sandbox + process execution

- ExecutionBackend interface ;
- Android local capabilities ;
- resource limits ;
- worker abstraction ;
- network policy.

**Gate :** commande sandboxée + timeout + kill + logs.

## Phase 12 — Paired worker

- service worker ;
- pairing ;
- capability advertisement ;
- encrypted/authenticated channel ;
- task dispatch ;
- artifact transfer ;
- reconnect.

**Gate :** tablette déclenche une tâche sur PC et récupère résultat vérifié.

## Phase 13 — Build/Test/Diagnostics adapters

- Gradle/Android first ;
- generic command ;
- autres adapters par détection ;
- diagnostics normalized.

**Gate :** réparer projet exemple, tests, APK/artefact produit.

## Phase 14 — Repository Intelligence advanced

- symbols ;
- LSP/Tree-sitter abstraction ;
- references ;
- dependency graph ;
- test impact.

**Gate :** rename/refactor avec diagnostics avant/après.

## Phase 15 — Software Factory complete

Pipeline discover→plan→implement→test→build→review→package.

**Gate :** acceptance coding scenario complet.

## Phase 16 — Android vision fallback

- screenshot ;
- OCR ;
- vision provider ;
- selector fusion ;
- sensitive screen policy.

**Gate :** automation sur fixture où arbre accessibility est volontairement insuffisant.

## Phase 17 — Voice VNext

- wake word optional ;
- VAD ;
- barge-in ;
- streaming providers ;
- local preference.

**Gate :** hands-free loop contrôlé et visible.

## Phase 18 — Contacts/phone/SMS/calendar/notifications

- permissions ;
- capabilities ;
- UI ;
- policy ;
- tests.

**Gate :** read operations + side effect approvals.

## Phase 19 — Browser interactive + Research

- interactive browser executor ;
- download/upload ;
- source provenance ;
- prompt injection defense.

**Gate :** recherche multi-source + formulaire non sensible automatisé.

## Phase 20 — MCP

- client ;
- discovery ;
- HTTP transport ;
- stdio through worker ;
- normalize tools/resources ;
- policy.

**Gate :** serveur MCP fixture expose outil, Cortana l'utilise via registry.

## Phase 21 — A2A interoperability

- agent discovery ;
- delegation adapter ;
- file/structured exchange ;
- policy.

**Gate :** sous-tâche distante simulée sans fuite de mémoire interne.

## Phase 22 — Plugins

- manifests ;
- signatures ;
- lifecycle ;
- isolation ;
- UI manager.

**Gate :** plugin test ajouté/retiré sans corruption.

## Phase 23 — Multi-agent specialists

- profiles ;
- specialist tasks ;
- limited tools ;
- parallelism ;
- merge results.

**Gate :** coding task utilise analyst + implementer + reviewer sans double orchestrator.

## Phase 24 — Document & Data Workbench

- text/docs ;
- PDF ;
- spreadsheet ;
- presentations ;
- structured extraction ;
- transformations ;
- artifacts.

**Gate :** end-to-end create/edit/export representative artifacts.

## Phase 25 — Media

- image generation/edit ;
- vision ;
- audio ;
- video provider interfaces ;
- artifact metadata.

**Gate :** provider capability routing + safe local artifact handling.

## Phase 26 — Integrations/email/webhooks/home

- connector registry ;
- auth ;
- health ;
- tool normalization.

**Gate :** connector fixture + revocation.

## Phase 27 — Improvement Service

- analyze failures ;
- skill proposals ;
- fast path proposals ;
- eval regression proposals.

**Gate :** propose improvement sans auto-modifier silencieusement.

## Phase 28 — Observability/OTel export

- traces ;
- metrics ;
- local viewer ;
- optional OTLP export ;
- privacy redaction.

**Gate :** une tâche affiche spans modèle/outils/verifier sans secrets.

## Phase 29 — Backup/restore/doctor

- encrypted export ;
- restore ;
- repair ;
- integrity checks ;
- portability.

**Gate :** backup sur instance A restauré sur instance B compatible.

## Phase 30 — Update system

- manifest signé ;
- download ;
- hash/signature ;
- install handoff ;
- compatibility.

**Gate :** upgrade test conserve DB et signature.

## Phase 31 — Web/CLI/TUI optional clients

Si le runtime worker/core expose API locale : clients d'administration et diagnostic, sans créer de logique métier dupliquée.

**Gate :** même contrats/API.

## Phase 32 — Hardening

- fuzz ;
- security ;
- load ;
- battery ;
- crash ;
- concurrency ;
- permission revocation ;
- network failures ;
- malicious repo/build.

## Phase 33 — Physical device RC

Sur Galaxy Tab réelle : matrice complète.

**Gate :** aucun blocker critique, rapport RC, APK signé, sources, tests, migration notes, changelog.
