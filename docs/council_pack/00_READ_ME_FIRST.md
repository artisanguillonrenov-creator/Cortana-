# Cortana — Cognitive Council Engine
## Dossier de création et d’intégration pour Claude Code

### Objet
Ce dossier définit une extension native de Cortana appelée **Cognitive Council Engine** (CCE).
Le CCE ajoute un mode de raisonnement multi-agent configurable où plusieurs spécialistes cognitifs
analysent en parallèle un même problème, confrontent leurs conclusions de manière contrôlée,
éliminent le bruit, votent ou recherchent un consensus, puis produisent une réponse finale unique.

Le CCE n’est pas une seconde architecture autonome. Il reste un sous-système du
`TaskOrchestrator` existant.

### Règle d’architecture absolue
Cortana conserve :
- un seul `TaskOrchestrator`;
- un seul `ModelGateway`;
- un seul `ToolRegistry`;
- un seul `PolicyEngine`;
- un seul `ContextEngine`;
- une seule mémoire canonique;
- un seul scheduler;
- un seul STOP global.

Le CCE crée uniquement des workers cognitifs enfants, bornés, éphémères et audités.

### Modes utilisateur
- OFF : pipeline normal.
- Rapide : 1 spécialiste.
- Renforcé : 2 spécialistes indépendants + comparaison.
- Conseil : 4 spécialistes + vote + confrontation ciblée + synthèse.
- Approfondi : 4 spécialistes + 2/3 tours + challenge + juge facultatif.
- Auto : Cortana adapte le nombre d’agents et les tours.
- Personnalisé : contrôle complet.

### Fonctions obligatoires
- modèle identique ou différent par rôle;
- provider différent par rôle;
- modèles préférés + fallbacks;
- parallélisme;
- diversité de rôles;
- premier tour indépendant;
- vote;
- consensus;
- juge optionnel;
- conservation sélective des arguments utiles;
- protection des objections critiques;
- budget de tokens/coût/latence;
- Context Budget Manager avant chaque appel;
- tool scoping par rôle;
- sécurité/policy identique au reste de Cortana;
- synthèse finale unique;
- pas de chaîne de pensée brute dans l’UI ni la DB.

### Interdictions
Claude Code ne doit pas :
- importer un framework multi-agent complet;
- créer un second orchestrateur;
- créer une seconde DB;
- dupliquer les providers;
- contourner `ModelGateway`;
- contourner `PolicyEngine`;
- donner tous les outils à tous les agents;
- faire un retry identique après HTTP 413;
- persister le reasoning privé;
- autoriser une action sensible parce que 4 agents sont d’accord;
- créer des agents récursifs;
- auto-merge ou auto-release.

### Definition of Done
Le CCE est terminé uniquement si :
- OFF ne change rien au comportement historique;
- 2 et 4 agents fonctionnent réellement en parallèle;
- multi-model et multi-provider fonctionnent;
- le quota de contexte est respecté avant chaque appel;
- la rétention réduit le trafic inter-agent;
- aucune objection critique n’est éliminée par le filtre;
- le quorum gère un agent en erreur;
- STOP annule tout proprement;
- les secrets n’apparaissent pas dans les prompts/logs;
- les migrations DB passent;
- les tests unitaires/intégration/E2E passent;
- le build release passe.
