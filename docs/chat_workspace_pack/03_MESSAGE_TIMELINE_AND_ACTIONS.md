# 3. Timeline, messages et actions

## 3.1 Message parts
Un message est composé de parts typées :
- TextPart;
- MarkdownPart;
- CodePart;
- MathPart;
- DiagramPart;
- TablePart;
- ImagePart;
- AudioPart;
- VideoPart;
- FilePart;
- CitationPart;
- ToolCallPart;
- ToolResultPart;
- ApprovalPart;
- TaskProgressPart;
- ArtifactPart;
- DiffPart;
- TerminalPart;
- ErrorPart;
- SystemEventPart.

Le renderer choisit le composant approprié.

## 3.2 Markdown
Support :
- GFM;
- tableaux;
- listes;
- citations;
- tâches;
- liens;
- syntax highlighting;
- LaTeX;
- diagrammes Mermaid si safe renderer;
- ancres de titres.

## 3.3 Code
Bloc code :
- langage;
- copier;
- télécharger/save artifact;
- envoyer au workspace;
- exécuter si outil autorisé;
- appliquer patch si contexte dev;
- numéros de lignes facultatifs.

## 3.4 Actions message utilisateur
- copier;
- éditer;
- réenvoyer;
- brancher depuis ici;
- citer;
- épingler;
- supprimer;
- convertir en tâche;
- ajouter au contexte.

## 3.5 Actions réponse Cortana
- copier texte;
- copier riche;
- lire à voix haute;
- régénérer;
- continuer;
- brancher;
- comparer;
- créer artifact;
- enregistrer fichier;
- épingler;
- signaler erreur;
- voir sources;
- détails techniques.

## 3.6 Edit / Resubmit
Éditer un ancien message ne détruit pas silencieusement l’ancienne suite.
Créer une branche/version et demander :
- continuer nouvelle branche;
- remplacer vue active;
- garder les deux.

## 3.7 Regenerate
Conserver les variantes.
L’utilisateur peut naviguer :
`Réponse 1/3`.

## 3.8 Continue
Si sortie coupée :
- bouton Continuer;
- contexte de continuation minimal;
- pas de duplication du début.

## 3.9 Citations
Les citations sont des objets, pas du texte bricolé.
Hover/tap :
- source;
- titre;
- date;
- extrait;
- provenance;
- ouvrir.

## 3.10 Réactions
Optionnelles :
- utile/pas utile;
- servent à évaluation locale;
- ne doivent pas envoyer de données sans réglage explicite.

## 3.11 System events
Afficher de façon discrète :
- modèle changé;
- compactage effectué;
- fichier ajouté;
- task créée;
- tool permission demandée;
- connexion reprise.

Pas de faux message « assistant » pour les événements système.
