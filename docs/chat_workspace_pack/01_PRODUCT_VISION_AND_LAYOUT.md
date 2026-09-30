# 1. Vision produit et layout

## 1.1 Concept
Nom fonctionnel : **Cortana Chat Workspace**.

Le même écran doit pouvoir gérer :
- question courte;
- conversation longue;
- analyse de fichiers;
- tâche agentique;
- développement logiciel;
- recherche web;
- conseil multi-agent;
- création d’un artifact;
- contrôle Android;
- voix.

## 1.2 Layout tablette / desktop

```text
┌───────────────┬──────────────────────────────────────────┬───────────────────┐
│ Sidebar       │ Top Bar                                  │ Context Panel     │
│               ├──────────────────────────────────────────┤                   │
│ Nouveau       │ Conversation / model / mode / status     │ Context           │
│ Recherche     ├──────────────────────────────────────────┤ Files             │
│ Projets       │                                          │ Artifacts         │
│ Récents       │               Timeline                   │ Activity          │
│ Épinglés      │                                          │ Memory            │
│ Archivés      │                                          │ Sources           │
│               │                                          │ Inspector         │
│               ├──────────────────────────────────────────┤                   │
│ User/Profile  │ Composer multimodal                      │                   │
└───────────────┴──────────────────────────────────────────┴───────────────────┘
```

Les panneaux gauche et droit sont redimensionnables/collapsibles.

## 1.3 Téléphone
- sidebar devient drawer;
- panneau droit devient bottom sheet / onglet;
- composer reste fixe en bas;
- header compact;
- actions message dans menu contextuel;
- artifacts plein écran au besoin.

## 1.4 Tablette
Priorité absolue.
Portrait :
- sidebar cachable;
- main 70%;
- panneau contextuel overlay ou 30%.

Paysage :
- 3 colonnes possible;
- conversation toujours dominante.

## 1.5 Header
Contient sans surcharge :
- titre conversation;
- statut sync/offline;
- sélecteur modèle;
- mode;
- indicateur contexte;
- menu conversation.

Option développeur :
- provider;
- latence;
- tokens;
- coût;
- route modèle;
- santé.

## 1.6 Sidebar
Sections :
- Nouveau chat;
- Recherche globale;
- Projets;
- Conversations récentes;
- Épinglées;
- Archivées;
- Corbeille si produit le prévoit;
- raccourcis configurables.

Conversation row :
- titre;
- aperçu;
- date;
- badge mode;
- indicateur tâche active;
- pin;
- menu.

## 1.7 Context Panel
Onglets :
- Contexte;
- Fichiers;
- Sources;
- Artifacts;
- Activité;
- Mémoire;
- Outils;
- Inspector.

Il doit être contextuel : n’afficher que les onglets utiles.

## 1.8 Densité
Trois réglages :
- Compact;
- Confort;
- Large.

## 1.9 Thèmes
- système;
- clair;
- sombre;
- contraste élevé.

Prévoir Accent Color Cortana mais ne pas dépendre d’une couleur pour les statuts.
