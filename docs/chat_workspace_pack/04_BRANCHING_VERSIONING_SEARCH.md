# 4. Branches, versions et recherche

## 4.1 Modèle
Une conversation est un graphe de messages, pas uniquement une liste linéaire.

Chaque message :
- id;
- parentId;
- branchId;
- versionIndex;
- createdAt;
- author;
- parts.

## 4.2 Branch
Actions :
- Brancher depuis ce message;
- Dupliquer conversation;
- Fork vers nouveau projet.

## 4.3 Visualisation
Par défaut : vue linéaire simple.
Si branches existent :
- badge branche;
- switcher;
- mini arbre dans panneau conversation.

## 4.4 Edit
Edit crée une nouvelle version.
Ne pas effacer l’ancienne sans action explicite.

## 4.5 Search
Recherche globale :
- titres;
- contenu messages;
- fichiers;
- artifacts;
- sources;
- tags.

Filtres :
- date;
- projet;
- modèle;
- type;
- avec fichier;
- avec artifact;
- conversation épinglée.

## 4.6 Jump
Résultat de recherche ouvre conversation au message exact.

## 4.7 Tags / folders
Support léger :
- projets;
- tags;
- pin.
Éviter système de dossiers trop profond.

## 4.8 Import / Export
Importer si formats supportés.
Exporter :
- Markdown;
- JSON;
- texte;
- PDF plus tard si utile;
- capture image pour partage.

Export doit exclure secrets et événements internes non destinés à l’utilisateur.
