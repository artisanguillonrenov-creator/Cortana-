# 10 — MODERNISATION 2026 ET CHOIX "DERNIER CRI"

Ce document ajoute des orientations modernes au blueprint. Elles ne doivent pas créer de dépendance rigide à un fournisseur.

## 1. MCP moderne

En 2026, MCP est un standard majeur pour connecter applications IA à des outils, ressources et prompts. L'architecture Cortana doit intégrer un **MCP client adapter** et non dupliquer le Tool Registry.

Design :

`MCP → adapter → ToolDefinition canonical → PolicyEngine → Executor/remote call`.

Prévoir version negotiation et transports modernes, notamment HTTP streaming. Le support stdio peut être fourni par le worker.

## 2. A2A v1.0

A2A est désormais un standard stable pour l'interop agent-à-agent. Cortana doit le traiter comme une couche horizontale de délégation externe, distincte de MCP qui connecte surtout aux outils/données.

A2A reste optionnel dans le runtime initial mais l'interface doit être prévue pour éviter un futur refactor majeur.

## 3. Observabilité GenAI standardisée

Le modèle de traces interne doit pouvoir être exporté suivant les conventions OpenTelemetry GenAI modernes :

- model request/response metadata ;
- tool execution ;
- agent/workflow invocation ;
- tokens ;
- errors ;
- latency.

Le contenu complet de prompts/réponses reste opt-in pour confidentialité.

## 4. Agentic coding moderne

Le coding engine doit adopter :

- état de tâche persistant ;
- petits changesets ;
- worktrees/branches isolés ;
- recherche code avant modification ;
- diagnostics structurés ;
- test ciblé puis suite ;
- self-review séparée ;
- checkpoints ;
- subagents spécialisés pour tâches parallélisables ;
- contexte compacté via notebook ;
- pas de boucles non bornées.

## 5. Tool discovery dynamique

Avec des dizaines/centaines de tools, injecter tout le catalogue au modèle est coûteux et dégrade le choix. Cortana doit faire du routing de capabilities et exposer un sous-ensemble par étape, avec discovery à la demande.

## 6. Structured outputs partout

Quand le modèle produit :

- plan ;
- tool args ;
- verification ;
- code review ;
- specialist result ;

utiliser schémas structurés validés. Si provider ne supporte pas structured output natif, utiliser émulation + parser strict + réparation bornée.

## 7. Multi-model routing

Le ModelGateway doit router selon :

- tool calling ;
- context ;
- vision ;
- coding ;
- structured output ;
- local availability ;
- latency ;
- privacy ;
- cost ;
- reliability.

Un modèle léger/local peut classifier ou résumer ; un modèle plus fort peut planifier/coder. L'orchestrateur garde l'autorité.

## 8. Semantic cache

Ajouter cache prudent pour :

- embeddings ;
- deterministic retrieval ;
- model metadata ;
- repository indexing ;
- Web fetch avec TTL.

Ne pas cacher aveuglément les réponses d'actions ou données temporelles.

## 9. Event sourcing partiel

Sans transformer toute la DB en event store, conserver `task_events` append-only pour reconstruire l'historique opérationnel d'une tâche et faciliter crash recovery/audit.

## 10. Durable execution

Les tâches longues doivent ressembler à des workflows durables : checkpoints, idempotence, leases, outbox, resume, cancellation. Cette logique doit fonctionner même si Android tue le process.

## 11. Local-first

Le système doit continuer à fonctionner en mode dégradé sans serveur :

- chat avec modèle local si configuré ;
- mémoire ;
- fast paths ;
- scheduler ;
- Android tools ;
- fichiers ;
- skills ;
- Git/code inspection basique.

Les capacités lourdes de build peuvent être routées vers un worker quand disponible.

## 12. Worker as capability node

Éviter architecture client-serveur rigide. Le worker publie des capabilities. Le Core demande une capability avec contrat typed. Cela permet ajouter plus tard : PC Windows/Linux, serveur GPU, autre appareil.

## 13. Safe self-improvement

Cortana peut analyser ses erreurs et proposer/implémenter une amélioration **seulement** via le même pipeline coding : branche isolée, tests, build, review, approbation. Aucun `self-modifying code` direct.

## 14. Compatibility manifests

Conserver un manifest de compatibilité :

- app version ;
- DB schema ;
- protocol versions ;
- worker min/max ;
- plugin ABI/API ;
- skill schema ;
- model capability schema.

## 15. Références de standard à vérifier au moment de l'implémentation

- MCP : utiliser la spécification stable la plus récente disponible au moment du développement.
- A2A : utiliser v1.x stable ou ultérieure compatible.
- OpenTelemetry : utiliser les conventions GenAI stables/actuelles, en gardant l'export optionnel.

Ne jamais épingler dans l'architecture métier une version externe sans couche d'adaptation.
