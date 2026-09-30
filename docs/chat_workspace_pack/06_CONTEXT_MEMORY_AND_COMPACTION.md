# 6. Contexte, mémoire et compactage visible

## 6.1 Context Drawer
Afficher une vue synthétique des éléments réellement actifs :
- instructions de conversation;
- mémoire pertinente;
- messages récents;
- messages épinglés;
- fichiers;
- sources;
- tools;
- skills;
- artifacts;
- modèle.

## 6.2 Context Meter
Montrer :
- faible;
- moyen;
- élevé;
- compactage proche.

En mode développeur :
- estimation tokens;
- réserve sortie;
- tool schema tokens;
- mémoire;
- historique.

## 6.3 Pin to context
L’utilisateur peut épingler :
- message;
- fichier;
- artifact;
- note.
L’élément reste prioritaire tant qu’il est compatible budget.

## 6.4 Conversation compaction
Quand longue :
1. générer résumé structuré;
2. préserver décisions/faits ouverts;
3. garder messages récents;
4. enregistrer checkpoint;
5. afficher événement discret « contexte compacté ».

## 6.5 Inspect compaction
L’utilisateur peut ouvrir :
- résumé conservé;
- éléments exclus;
- date;
- modèle ayant résumé;
- restaurer/fork depuis checkpoint si possible.

## 6.6 Memory UI
Onglet Mémoire :
- faits/profil/préférences utiles;
- provenance;
- date;
- confiance/statut;
- activer/désactiver pour cette conversation;
- proposer correction.

La mémoire permanente reste gérée par le service mémoire canonique.

## 6.7 Ephemeral chat
Mode temporaire :
- pas d’écriture mémoire durable;
- pas de sync si configuré ainsi;
- indicateur visible.

## 6.8 Project context
Conversation dans un projet peut hériter :
- instructions;
- fichiers;
- artifacts;
- skills;
- modèles préférés.

Toujours afficher ce qui est hérité vs spécifique à la conversation.
