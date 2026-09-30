# CORTANA VNEXT — MASTER INDEX

Ce dossier est destiné à être remis avec les sources actuelles de Cortana.

- `00_READ_ME_FIRST.md` — mandat et règles absolues
- `01_CURRENT_BASELINE_AND_NON_REGRESSION.md` — ce qui existe et ne doit pas régresser
- `02_FULL_TARGET_BLUEPRINT.md` — blueprint complet de l'état final
- `03_DEVELOPER_SOFTWARE_FACTORY_SPEC.md` — coding, Git, build, tests, workers
- `04_AGENT_RUNTIME_MEMORY_SKILLS_AUTONOMY.md` — Planner, Verifier, checkpoints, mémoire, skills, subagents
- `05_ANDROID_MULTIMODAL_INTEGRATIONS_PROTOCOLS.md` — Android avancé, vision, voix, communications, MCP/A2A
- `06_SECURITY_DATA_MIGRATIONS_OBSERVABILITY.md` — sécurité, Room, backup, traces, supply chain
- `07_IMPLEMENTATION_ROADMAP.md` — ordre de production
- `08_ACCEPTANCE_AND_TEST_MATRIX.md` — critères d'acceptation
- `09_CLAUDE_CODE_MASTER_EXECUTION_PROMPT.md` — mandat exécutable par Claude Code
- `10_2026_MODERNIZATION_NOTES.md` — standards et pratiques modernes à intégrer
- `11_CAPABILITY_CHECKLIST.md` — checklist exhaustive
- `12_SOURCE_BASELINE_MAP.md` — carte des sources actuelles
- `13_BASELINE_SHA256.txt` — hash baseline des sources/documents actuels

## Usage recommandé

1. Donner à Claude Code le dossier de sources Cortana actuel.
2. Ajouter ce dossier `CORTANA_VNEXT_PRODUCTION_PACK` à côté, sans le fusionner dans l'app avant lecture.
3. Lui demander d'ouvrir `00_READ_ME_FIRST.md`, puis `09_CLAUDE_CODE_MASTER_EXECUTION_PROMPT.md` et de respecter l'ordre de lecture.
4. Laisser Claude Code auditer le code réel et améliorer les choix techniques lorsque cela apporte un gain démontrable, sans réduire le scope.
