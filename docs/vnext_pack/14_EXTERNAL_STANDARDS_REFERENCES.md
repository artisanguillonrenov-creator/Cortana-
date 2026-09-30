# 14 — RÉFÉRENCES STANDARDS EXTERNES À REVÉRIFIER AVANT IMPLÉMENTATION

Ce fichier sert à orienter l'implémentation vers des standards actuels. Claude Code doit vérifier la version stable disponible au moment où chaque phase est implémentée.

## MCP

- Site/specification : https://modelcontextprotocol.io/
- SDK TypeScript v2 : https://ts.sdk.modelcontextprotocol.io/v2/
- La documentation v2 indique une ligne stable implémentant la spécification 2026-07-28.

Cortana doit conserver une couche d'adaptation afin que la version de protocole puisse évoluer sans modifier le domaine métier.

## Agent2Agent (A2A)

- Site officiel : https://a2a-protocol.org/
- Spécification : https://a2a-protocol.org/dev/specification/
- A2A v1.0 a été annoncé comme version stable/production en mars 2026.

A2A est un protocole d'interop entre agents ; il ne remplace pas l'orchestrateur interne.

## OpenTelemetry

- Semantic conventions : https://opentelemetry.io/docs/specs/semconv/
- GenAI conventions : vérifier le dépôt/documentation GenAI actuel depuis l'index OpenTelemetry.

Les conventions GenAI couvrent notamment les opérations agent, modèle, retrieval et tool execution. L'export reste optionnel et les contenus sensibles doivent rester redacted par défaut.

## Règle de dépendance

Ces standards externes sont des interfaces de bordure. Le cœur Cortana ne doit jamais dépendre directement de leurs objets internes : utiliser adapters + contrats Cortana.
