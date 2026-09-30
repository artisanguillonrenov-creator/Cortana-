# 7. Artifacts, fichiers et UI générative

## 7.1 Artifact Workspace
Un artifact est un objet durable séparé du message :
- document;
- code;
- HTML;
- diagramme;
- tableau;
- JSON;
- image;
- rapport;
- patch.

Il possède :
- id;
- type;
- title;
- versions;
- source message;
- project;
- storage ref.

## 7.2 Side panel
Lorsqu’un artifact est créé, ouvrir panneau droit sans quitter conversation.
Actions :
- éditer;
- comparer versions;
- copier;
- exporter;
- enregistrer;
- revenir au message source.

## 7.3 Versioning
Chaque modification crée version.
Diff pour texte/code.

## 7.4 Files
File card :
- nom;
- type;
- taille;
- parsing;
- index status;
- source;
- open/remove.

## 7.5 Generated UI
Les tool results peuvent rendre des composants sûrs :
- météo;
- tableau;
- graphique;
- formulaire d’approbation;
- checklist;
- card fichier;
- diff;
- terminal summary.

Renderer basé sur types autorisés, jamais HTML arbitraire non sandboxé.

## 7.6 Approvals
Une tool action sensible crée `ApprovalCard` :
- action;
- cible;
- arguments principaux;
- risque;
- autoriser/refuser;
- durée de validité.

L’approbation est liée à l’action exacte.

## 7.7 Task progress
Tâche longue :
- une ligne stable d’activité;
- expand pour étapes;
- completed/running/failed;
- STOP;
- pas de chain-of-thought.

## 7.8 Code/terminal
Afficher sortie terminal dans composant pliable.
Ne pas inonder la timeline.
Artifacts lourds restent hors message.
