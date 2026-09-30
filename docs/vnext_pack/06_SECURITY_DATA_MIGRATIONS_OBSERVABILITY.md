# 06 — SÉCURITÉ, DONNÉES, MIGRATIONS, OBSERVABILITÉ, BACKUP ET SUPPLY CHAIN

## 1. Modèle de menace

Cortana peut lire des écrans, modifier des fichiers, exécuter du code et appeler des services externes. Elle doit considérer comme potentiellement hostile :

- contenu Web ;
- texte affiché par une application ;
- fichiers importés ;
- README/instructions d'un dépôt ;
- scripts de build ;
- dépendances ;
- réponses API ;
- résultats MCP ;
- agents externes ;
- notifications ;
- pièces jointes ;
- tool output ;
- fichiers générés par modèle.

## 2. Taint tracking étendu — MUST

Chaque donnée peut porter :

- source ;
- trust level ;
- sensitivity ;
- external/untrusted ;
- provenance chain.

Règles :

- une instruction trouvée dans une source externe n'acquiert jamais autorité système ;
- une donnée tainted ne peut déclencher une action sensible sans confirmation/politique ;
- secrets ne peuvent être transmis à une destination tainted sans autorisation explicite.

## 3. Repository prompt-injection defense — MUST

Les fichiers tels que README, AGENTS, scripts ou commentaires peuvent contenir des instructions malveillantes.

Cortana doit :

- les présenter comme **project instructions**, jamais comme politiques système ;
- ignorer toute demande de désactiver sécurité ;
- ignorer toute demande d'exfiltrer secrets ;
- ne pas élargir les permissions ;
- ne pas exécuter de commande destructive uniquement parce qu'un fichier la demande ;
- signaler conflit entre instructions projet et objectif utilisateur.

## 4. Build sandbox — MUST

Les builds et package managers peuvent exécuter du code arbitraire.

Exigences :

- filesystem scope ;
- CPU/RAM/time limits ;
- process limits ;
- network disabled par défaut ou allowlist ;
- aucun accès aux secrets de Cortana ;
- aucun accès au Keystore Android ;
- env minimal ;
- temp directory ;
- kill ;
- log ;
- cleanup ;
- artifact export contrôlé.

Sur Android, utiliser sandbox applicatif + restrictions disponibles. Sur worker, privilégier container/sandbox OS.

## 5. PolicyEngine VNext

Conserver L0-L3 et ajouter metadata par capability :

- readOnly ;
- reversible ;
- externalSideEffect ;
- destructive ;
- credentialUse ;
- financial ;
- privacySensitive ;
- executesCode ;
- networkEgress ;
- modifiesSecurity ;
- userVisibleToThirdParty.

La policy calcule décision à partir : capability + args + source + taint + destination + trust + session mode + user grants.

## 6. Capability grants

Grant borné par :

- capability ;
- scope ;
- target ;
- arguments constraints ;
- duration ;
- task/session ;
- maxUses ;
- expiration.

Aucune autorisation globale implicite parce qu'une action similaire a été approuvée une fois.

## 7. Idempotency ledger

Étendre ledger avec :

- key ;
- taskId ;
- capability ;
- canonical args hash ;
- external receipt ;
- outcome ;
- timestamp ;
- reversible ;
- undo reference.

## 8. Secrets

- Android Keystore pour clés device ;
- secret values chiffrées ;
- handle opaque dans DB ;
- redaction logs ;
- secret scope ;
- rotation ;
- delete/revoke ;
- export désactivé par défaut ;
- pas de secret dans prompt sauf strictement nécessaire à un outil qui l'utilise directement ;
- le modèle ne voit idéalement jamais la valeur brute.

## 9. Network policy

Par executor/tool :

- allowed schemes ;
- allowed hosts ;
- private network policy ;
- DNS rebinding defense ;
- redirects revalidés ;
- IP literal rules ;
- max response size ;
- timeout ;
- TLS ;
- certificate errors fail closed ;
- proxy explicit ;
- audit destination.

Build sandbox réseau plus restrictif que Web research.

## 10. Données et schéma cible

Conserver tables v1 et ajouter progressivement :

### Agent/runtime

- `task_events`
- `plans`
- `plan_steps`
- `checkpoints`
- `task_notebooks`
- `idempotency_ledger`
- `approvals`
- `task_leases`
- `outbox`

### Memory/skills

- `memory_embeddings_meta`
- `memory_edges`
- `skills`
- `skill_versions`
- `skill_runs`
- `skill_failures`

### Workspaces/coding

- `workspaces`
- `repositories`
- `workspace_snapshots`
- `change_sets`
- `build_runs`
- `test_runs`
- `diagnostics`
- `artifacts`
- `artifact_refs`

### Workers/devices

- `devices`
- `worker_nodes`
- `device_capabilities`
- `worker_capabilities`
- `pairings`

### Integrations

- `connectors`
- `connector_accounts`
- `plugins`
- `plugin_versions`
- `protocol_connections`

### Observability

- `trace_index`
- `metrics_daily`
- éventuellement stockage fichiers pour traces volumineuses.

Ne pas mettre des blobs lourds dans Room sans raison : stocker fichier + metadata + hash.

## 11. Migrations Room

### Règles

- migrations incrémentales ;
- tests `MigrationTestHelper` ;
- export schemas versionnés ;
- backup préalable si migration sensible ;
- indexes créés explicitement ;
- migration FTS testée ;
- backfill chunké ;
- index embeddings reconstruit hors transaction si nécessaire ;
- migration compatible interruption ;
- ne pas bloquer UI longtemps.

## 12. Backup — MUST

Backup logique chiffrable contenant :

- DB export ;
- settings ;
- memories ;
- sessions ;
- skills ;
- schedules ;
- connector metadata sans secrets par défaut ;
- artifacts sélectionnés ;
- manifest version ;
- checksums.

Secrets exportés uniquement dans mode backup sécurisé explicitement demandé, avec passphrase forte.

## 13. Restore — MUST

- inspect manifest ;
- vérifier hash ;
- vérifier compat ;
- dry-run ;
- backup actuel avant restore ;
- transaction logique ;
- rapport conflit ;
- merge ou replace explicite ;
- rebuild indexes dérivés.

## 14. Database Doctor — MUST

Écran Santé avancé :

- integrity check ;
- WAL checkpoint ;
- schema version ;
- orphan artifacts ;
- FTS consistency ;
- vector index freshness ;
- invalid skills ;
- stuck tasks ;
- stale worker leases ;
- scheduler anomalies ;
- secret handles missing ;
- audit chain integrity ;
- disk usage.

Réparations réversibles quand possible.

## 15. Observability — MUST

Créer un modèle de trace structuré :

```text
Trace
 Task
  ├─ ModelCall
  ├─ ToolCall
  ├─ PolicyDecision
  ├─ ExecutorOperation
  ├─ Verification
  ├─ Retry/Replan
  └─ ArtifactProduction
```

### Champs

- traceId/spanId ;
- taskId ;
- sessionId ;
- operation ;
- start/end/duration ;
- status ;
- provider/model ;
- tool capability ;
- tokens/cost ;
- error type ;
- retry count ;
- redacted metadata.

Prompts/réponses complets ne sont **pas** enregistrés dans télémétrie par défaut.

## 16. OpenTelemetry compatibility — SHOULD

Concevoir noms/attributs de manière compatible avec les conventions GenAI OpenTelemetry modernes, mais garder une couche interne indépendante pour fonctionner offline.

Exporter seulement si l'utilisateur configure un collector.

## 17. Metrics

- task success rate ;
- tool success rate ;
- retry/replan rate ;
- mean task duration ;
- model latency ;
- tokens/cost ;
- retrieval hit rate ;
- skill reuse/success ;
- crash/restart recovery ;
- build success ;
- test failure categories ;
- battery/resource usage.

## 18. Audit log

Conserver hash chain et ajouter :

- actor type ;
- task ;
- capability ;
- authorization ref ;
- external destination ;
- data sensitivity ;
- result ;
- artifact refs.

Export audit signé optionnel.

## 19. Supply chain — MUST

- dependency lock/version catalog ;
- Gradle dependency verification ;
- checksum/signatures quand possible ;
- SBOM release ;
- secret scan ;
- license inventory ;
- lint ;
- static analysis ;
- no dynamic code download into main process without trust/verification ;
- plugin signatures ;
- release provenance ;
- reproducible build autant que possible.

## 20. Release signing

La clé de signature existante reste la clé de continuité. Elle ne doit pas être copiée dans le dépôt.

Ajouter vérification CI/local :

- package name identique ;
- certificate fingerprint attendu ;
- versionCode monotone ;
- APK signature verify ;
- sha256 artifact ;
- universal + arm64 selon politique de release.

## 21. Privacy modes

- local-only ;
- remote-model allowed ;
- per-tool network grants ;
- incognito ;
- no-memory ;
- no-telemetry ;
- redact sensitive context ;
- provider routing by sensitivity.

## 22. Retention

Politiques séparées :

- conversations ;
- task traces ;
- artifacts ;
- build logs ;
- web cache ;
- screenshots ;
- audio ;
- embeddings ;
- audit (longue durée) ;
- memory (user controlled).

## 23. Fuzz / adversarial tests

Tester :

- malformed tool calls ;
- huge JSON ;
- invalid SSE ;
- prompt injection Web ;
- prompt injection repo ;
- malicious filenames ;
- symlink/path traversal worker ;
- zip slip ;
- SSRF ;
- redirect SSRF ;
- dependency scripts ;
- fake UI payment labels ;
- stale approval ;
- race between approval and target change ;
- duplicated side effect ;
- crash after external success before DB commit ;
- restore corrupted backup.
