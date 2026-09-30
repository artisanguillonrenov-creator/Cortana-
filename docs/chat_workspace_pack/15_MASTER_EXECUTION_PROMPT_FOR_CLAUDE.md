# MASTER PROMPT — CLAUDE CODE

Tu dois créer le `Cortana Chat Workspace` décrit dans ce dossier.

## Avant de coder
1. inspecte le code actuel;
2. identifie ConversationService, ViewModels, Room entities, streaming, ModelGateway,
   ContextEngine, MemoryService, ToolRegistry, PolicyEngine, Artifact/Files, Voice;
3. mappe chaque exigence à l’existant;
4. ne recrée pas un service déjà présent;
5. produis un plan de fichiers.

## Priorités
- tablette Android d’abord;
- chat simple excellent;
- fonctions avancées progressives;
- aucune perte de fonction;
- persistance;
- streaming robuste;
- contexte borné;
- policy;
- accessibilité.

## Architecture
Le Workspace est l’UI principale.
Il n’est pas un second orchestrateur.

## Implémentation
Suivre H1 -> H12 dans l’ordre.
À chaque chantier :
- objectif;
- fichiers modifiés;
- code;
- tests;
- résultat build;
- risques;
- prochaine étape.

## Obligatoire
- branches;
- edit/resubmit;
- variantes regenerate;
- continue;
- queue;
- resumable streams;
- attachments;
- rich parts;
- citations;
- context drawer;
- compaction;
- artifacts;
- approvals;
- multi-model compare;
- bridge Cognitive Council;
- search;
- voice;
- responsive;
- accessibility.

## Interdictions
- pas de clone UI pixel pour pixel;
- pas de copier-coller de code tiers;
- pas de second ModelGateway;
- pas de second ToolRegistry;
- pas de second Memory store;
- pas de chain-of-thought UI;
- pas d’approbation implicite L2/L3 par un composant;
- pas de suppression historique destructive lors edit;
- pas de retry stream aveugle sans idempotence;
- pas d’auto-merge.

## Gate finale
- tests;
- lint;
- build release;
- migrations;
- vraie tablette;
- réseau instable;
- très longue conversation;
- gros fichiers;
- 413;
- STOP;
- accessibilité;
- aucun blocker critique.
