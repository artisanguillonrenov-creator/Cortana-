# 2. Composer multimodal

## 2.1 Objectif
Le composer doit être simple au repos et puissant à la demande.

État minimal :
```text
[ + ]  Écrivez à Cortana...                 [micro] [envoyer]
```

État étendu :
- pièce jointe;
- caméra/image;
- voix;
- outils;
- web/recherche;
- mode conseil;
- modèle;
- slash commands;
- mentions;
- options génération.

## 2.2 Entrées
Support :
- texte;
- collage riche;
- fichiers;
- images;
- audio;
- vidéo si pipeline disponible;
- capture écran;
- caméra;
- partage Android;
- texte sélectionné d’une autre app.

## 2.3 Pièces jointes
Avant envoi :
- miniatures;
- taille;
- type;
- état parsing;
- possibilité retirer;
- possibilité choisir mode : lire / analyser / joindre comme référence.

Drag/drop desktop; picker Android; collage image.

## 2.4 Slash Commands
Exemples :
- `/model`
- `/mode`
- `/web`
- `/tools`
- `/file`
- `/project`
- `/remember`
- `/context`
- `/compare`
- `/council`
- `/voice`
- `/new`

La palette est recherchable.

## 2.5 Mentions
`@` peut cibler :
- fichier;
- artifact;
- projet;
- skill;
- outil;
- agent/bureau;
- source;
- conversation précédente si policy le permet.

## 2.6 Message Queue
Pendant génération, l’utilisateur peut :
- écrire;
- envoyer un message en file;
- réordonner;
- supprimer de la file;
- choisir « interrompre et envoyer maintenant ».

## 2.7 Draft persistence
Le brouillon est persistant par conversation.
Fermeture/restart ne doit pas effacer le texte non envoyé.

## 2.8 Keyboard
- Enter envoyer;
- Shift+Enter nouvelle ligne;
- Ctrl/Cmd+Enter configurable;
- Esc arrêter;
- Ctrl/Cmd+K palette;
- raccourcis accessibles et personnalisables.

## 2.9 Voice
Appui court :
- dictée.
Appui long / bouton mode :
- boucle vocale complète.

Transcription éditable avant envoi selon réglage.

## 2.10 Context estimate
Avant envoi, le composer peut afficher :
- contexte OK;
- contexte élevé;
- compactage nécessaire.

Ne jamais afficher un faux pourcentage de précision.
