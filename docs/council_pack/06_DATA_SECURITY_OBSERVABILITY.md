# 6. Données, sécurité et observabilité

## 6.1 Persistance
Utiliser la DB canonique existante. Aucune DB parallèle.

Entités proposées :

### council_runs
- id
- parent_task_id
- mode
- status
- created_at
- completed_at
- config_snapshot_json
- termination_reason
- total_tokens
- total_cost_micros
- wall_time_ms

### council_agent_slots
- id
- run_id
- role_profile_id
- model_route_snapshot
- tool_scope_hash
- required
- status

### council_rounds
- id
- run_id
- round_index
- round_type
- divergence_score
- agreement_score
- evidence_coverage_score
- started_at
- ended_at

### council_contributions
- id
- round_id
- agent_slot_id
- candidate_key
- structured_summary_json
- confidence_features_json
- token_input
- token_output
- latency_ms
- error_code

### council_claims
- id
- contribution_id
- normalized_text
- claim_type
- confidence_score
- tainted
- verification_status

### council_evidence_refs
- id
- claim_id
- source_type
- source_ref
- tool_call_id
- trust_class
- observed_at

### council_concerns
- id
- contribution_id
- severity
- target_claim_id
- summary
- resolved
- resolution_ref

### council_votes
- id
- round_id
- agent_slot_id
- protocol
- ballot_json

### council_decisions
- id
- run_id
- round_id
- protocol
- candidate_key
- metrics_json
- minority_report_json
- unresolved_concerns_json

## 6.2 Ne pas stocker
- chain-of-thought;
- hidden reasoning provider;
- API keys;
- prompts contenant secrets;
- credentials.

## 6.3 Migration
- versionnée;
- test upgrade;
- pas de destructive migration silencieuse;
- downgrade géré ou explicitement refusé;
- backup avant migration sensible.

## 6.4 Sécurité
Les enfants héritent :
- permissions user;
- privacy;
- policy;
- tool scope;
- provider allowlist.

Ils peuvent avoir moins de droits, jamais plus.

## 6.5 Niveaux d’action
L0 lecture : possible selon scope.
L1 navigation réversible : policy configurable.
L2 side effect : confirmation/policy.
L3 sensible/irréversible : autorisation explicite forte.

Même 4/4 agents ne peuvent pas autoriser L3.

## 6.6 Secrets
Les agents n’obtiennent que des handles opaques.
SecretStore reste l’unique détenteur des secrets.

## 6.7 Prompt injection
Web/écran/fichier/MCP = données non fiables.
Une claim provenant de contenu non fiable porte `tainted=true`.
Taint se propage à la décision.
Action sensible basée sur taint => vérification renforcée.

## 6.8 STOP
STOP :
- annule jobs;
- annule tool calls cancellables;
- empêche nouveaux appels;
- marque run CANCELLED;
- ignore résultats tardifs.

## 6.9 Observabilité
Spans :
- council.run
- council.plan
- council.round
- council.agent.call
- council.tool.call
- council.retain
- council.vote
- council.decision
- council.challenge
- council.synthesize
- council.verify

Attributs sûrs :
- run id;
- role;
- provider/model;
- round;
- token counts;
- latency;
- status;
- retained count;
- consensus score.

Pas de prompts complets par défaut.

## 6.10 Métriques
- council_runs_total;
- partial_rate;
- avg_agents;
- avg_rounds;
- avg_tokens;
- avg_cost;
- p50/p95 latency;
- early_stop_rate;
- fallback_rate;
- verifier_rejection_rate;
- minority_overturn_rate;
- 413_reduction_events;
- rate_limit_events.
