# 11 — CAPABILITY CHECKLIST VNEXT

Statuts autorisés : `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED_EXTERNAL`.

Aucune ligne MUST ne peut disparaître du fichier pour faire paraître le projet terminé.

## Core

- [ ] MUST Task state machine extraite
- [ ] MUST Planner
- [ ] MUST DAG planning
- [ ] MUST Verifier
- [ ] MUST RecoveryEngine
- [ ] MUST Replanner
- [ ] MUST CheckpointService
- [ ] MUST TaskNotebook
- [ ] MUST durable resume
- [ ] MUST cancellation propagation
- [ ] MUST idempotency ledger
- [ ] MUST task events
- [ ] MUST outbox durable effects
- [ ] MUST dynamic tool discovery
- [ ] MUST structured output validation
- [ ] MUST fast path registry

## Models

- [ ] MUST provider abstraction conservée
- [ ] MUST OpenAI-compatible
- [ ] MUST local server
- [ ] MUST fallback
- [ ] MUST model capabilities
- [ ] MUST cost accounting
- [ ] MUST privacy-aware routing
- [ ] MUST coding capability routing
- [ ] MUST vision routing
- [ ] MUST embeddings provider
- [ ] SHOULD native provider adapters where useful
- [ ] MUST circuit breaker / health

## Memory

- [ ] MUST conversation
- [ ] MUST episodic
- [ ] MUST semantic
- [ ] MUST profile/preferences
- [ ] MUST procedural skills
- [ ] MUST FTS
- [ ] MUST vector retrieval
- [ ] MUST hybrid ranking
- [ ] SHOULD graph relations
- [ ] MUST provenance
- [ ] MUST retention
- [ ] MUST export/delete
- [ ] MUST incognito

## Skills

- [ ] MUST skill schema
- [ ] MUST versioning
- [ ] MUST candidate learning
- [ ] MUST parameterization
- [ ] MUST preconditions
- [ ] MUST postconditions
- [ ] MUST safe replay
- [ ] MUST invalidation
- [ ] MUST confidence stats
- [ ] MUST UI management

## Android

- [ ] MUST current accessibility actions preserved
- [ ] MUST robust selectors
- [ ] MUST takeover detection
- [ ] MUST STOP
- [ ] MUST lockscreen safety
- [ ] MUST screenshot capture
- [ ] MUST OCR
- [ ] MUST vision fallback
- [ ] MUST contacts
- [ ] MUST phone capability where hardware permits
- [ ] MUST SMS capability where hardware permits
- [ ] MUST calendar
- [ ] SHOULD notification listener
- [ ] MUST clipboard policy
- [ ] MUST permission health
- [ ] MUST reboot lifecycle

## Voice

- [ ] MUST push-to-talk preserved
- [ ] MUST STT provider abstraction
- [ ] MUST TTS provider abstraction
- [ ] SHOULD wake word
- [ ] SHOULD VAD
- [ ] SHOULD barge-in
- [ ] MUST visible recording state

## Web

- [ ] MUST fetch/search preserved
- [ ] MUST SSRF protections
- [ ] MUST research workflow
- [ ] MUST provenance
- [ ] MUST interactive browser executor
- [ ] MUST downloads
- [ ] MUST upload approval
- [ ] MUST injection defense

## Files/Documents/Data

- [ ] MUST SAF file tools preserved
- [ ] MUST artifact service
- [ ] MUST archive handling safe
- [ ] MUST PDF read/create/edit pipeline
- [ ] MUST text document pipeline
- [ ] MUST spreadsheet pipeline
- [ ] MUST presentation pipeline
- [ ] MUST structured data CSV/JSON
- [ ] MUST provenance

## Coding

- [ ] MUST WorkspaceManager
- [ ] MUST repo import/open
- [ ] MUST clone/init
- [ ] MUST repo intelligence
- [ ] MUST code search
- [ ] MUST symbol provider abstraction
- [ ] MUST patch preview/apply/rollback
- [ ] MUST Git status/diff/log
- [ ] MUST branch/worktree
- [ ] MUST local commit
- [ ] MUST protected push
- [ ] MUST BuildService
- [ ] MUST TestService
- [ ] MUST DiagnosticsService
- [ ] MUST DependencyService
- [ ] MUST ReviewService
- [ ] MUST Software Factory end-to-end
- [ ] MUST artifact export
- [ ] MUST coding resume after crash

## Execution / Sandbox / Workers

- [ ] MUST ExecutionBackend abstraction
- [ ] MUST Android local backend
- [ ] MUST process timeout/kill
- [ ] MUST sandbox filesystem policy
- [ ] MUST sandbox network policy
- [ ] MUST paired worker
- [ ] MUST secure pairing
- [ ] MUST worker capability discovery
- [ ] MUST reconnect
- [ ] MUST artifact transfer integrity
- [ ] MUST worker revocation

## Automation

- [ ] MUST reminder preserved
- [ ] MUST once
- [ ] MUST interval
- [ ] MUST cron
- [ ] MUST timezone/DST
- [ ] MUST catch-up
- [ ] MUST condition watch
- [ ] MUST concurrency policy
- [ ] MUST durable scheduled tasks

## Multi-agent

- [ ] MUST specialist profiles
- [ ] MUST specialist task contracts
- [ ] MUST restricted toolsets
- [ ] MUST parallel independent subtasks
- [ ] MUST one orchestrator authority
- [ ] MUST result merge/review

## Protocols / Plugins

- [ ] MUST MCP client
- [ ] MUST MCP tool normalization
- [ ] MUST MCP policy integration
- [ ] SHOULD A2A adapter
- [ ] MUST plugin manifest
- [ ] MUST plugin signature/integrity
- [ ] MUST plugin capability isolation

## Integrations

- [ ] MUST Connector registry
- [ ] SHOULD email
- [ ] SHOULD webhooks
- [ ] SHOULD generic HTTP
- [ ] SHOULD messaging adapters
- [ ] SHOULD home automation adapter

## Media

- [ ] MUST vision
- [ ] MUST image analysis
- [ ] SHOULD image generation/edit provider
- [ ] MUST speech services
- [ ] SHOULD video provider abstraction

## Security

- [ ] MUST L0-L3 policy preserved
- [ ] MUST taint tracking expanded
- [ ] MUST exact approval binding
- [ ] MUST TOCTOU reclassification
- [ ] MUST secret handles
- [ ] MUST repository prompt injection defense
- [ ] MUST sandbox untrusted builds
- [ ] MUST network egress policy
- [ ] MUST audit chain
- [ ] MUST supply-chain checks
- [ ] MUST dependency verification
- [ ] MUST SBOM
- [ ] MUST license inventory

## Data / Reliability

- [ ] MUST Room migrations from v1
- [ ] MUST migration tests
- [ ] MUST backup
- [ ] MUST restore
- [ ] MUST DB doctor
- [ ] MUST repair
- [ ] MUST export/import
- [ ] MUST compatibility manifest
- [ ] MUST crash recovery

## Observability

- [ ] MUST structured traces
- [ ] MUST metrics
- [ ] MUST model/tool spans
- [ ] MUST local viewer
- [ ] SHOULD OpenTelemetry export
- [ ] MUST secrets redacted

## UI

- [ ] MUST chat preserved
- [ ] MUST history preserved
- [ ] MUST memory preserved
- [ ] MUST schedules preserved
- [ ] MUST providers preserved
- [ ] MUST health preserved
- [ ] MUST audit preserved
- [ ] MUST developer workspace
- [ ] MUST task plan view
- [ ] MUST skills screen
- [ ] MUST worker/devices screen
- [ ] MUST connectors/plugins screen
- [ ] MUST artifacts screen
- [ ] MUST permissions/capabilities screen

## Update / Release

- [ ] MUST signature continuity
- [ ] MUST versionCode monotone
- [ ] MUST signed update manifest
- [ ] MUST APK hash verification
- [ ] MUST install handoff
- [ ] MUST rollback strategy documentation
- [ ] MUST release report
- [ ] MUST source reproducibility check
- [ ] MUST physical-device RC checklist
