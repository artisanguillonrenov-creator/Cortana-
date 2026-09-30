# Cortana — Unified Chat Workspace
## Dossier de création pour Claude Code

### Mission
Créer pour Cortana une interface de conversation moderne, puissante et cohérente, pensée dès le départ
pour un assistant autonome, multi-modèle, multimodal, outillé et capable de tâches longues.

Ce dossier ne demande pas de cloner une interface existante. Il définit **une interface originale Cortana**
qui fusionne les meilleures idées observables dans les interfaces IA modernes : streaming robuste,
branches de conversation, édition/reprise, comparaison multi-modèles, pièces jointes, citations,
artifacts, voix, outils, approbations, contexte visible, recherche, mémoire, projets, multi-appareil,
résumés de contexte, interface développeur et exécution agentique.

### Principe
Le chat n’est pas un simple écran de messages.
Il devient le **centre de commandement conversationnel de Cortana**.

### Règles absolues
- ne pas créer un deuxième backend conversationnel;
- utiliser les services Cortana existants;
- ne pas dupliquer ModelGateway, Memory, Tools, Policy, Artifacts, Voice ou Scheduler;
- ne pas casser les conversations existantes;
- les fonctions avancées sont progressives et non envahissantes;
- le mode simple doit rester simple;
- aucune chaîne de pensée privée ne doit être affichée;
- les actions et outils apparaissent sous forme d’événements structurés;
- toutes les opérations sensibles utilisent PolicyEngine;
- l’interface doit être excellente sur tablette Android, téléphone et grand écran.

### Expérience cible
Au centre : conversation.
À gauche : conversations/projets/recherche.
À droite : panneau contextuel adaptatif.
En bas : composer multimodal puissant.
En haut : modèle/mode/statut/contexte.
Dans les messages : contenu riche, outils, fichiers, citations, artifacts, approbations et actions.

### Modes conversationnels
- Discussion
- Agent
- Recherche
- Conseil
- Comparaison
- Développement
- Voix

Le changement de mode configure le comportement, mais ne crée pas des applications séparées.

### Definition of Done
- streaming fluide;
- reprise réseau;
- messages en file pendant génération;
- edit/resubmit;
- regenerate;
- continue;
- branche/fork;
- recherche historique;
- pièces jointes;
- citations;
- markdown/code/math/diagrammes;
- tool/activity cards;
- approvals;
- artifacts;
- voix;
- multi-modèle;
- comparaison visuelle;
- context drawer;
- mémoire visible/contrôlable;
- responsive;
- accessibilité;
- tests;
- aucune régression sur le chat actuel.
