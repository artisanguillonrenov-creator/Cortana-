# 5. Streaming, reprise et hors-ligne

## 5.1 Streaming
Le renderer doit accepter des deltas structurés :
- text delta;
- reasoning status sans contenu privé;
- tool event;
- artifact delta;
- citation update;
- usage update;
- completion.

## 5.2 Stable rendering
Ne pas rerender toute la conversation à chaque token.
Virtualiser les longues timelines.
Batcher les updates UI.

## 5.3 Reconnexion
Une réponse en cours possède :
- runId;
- streamCursor;
- lastSequence;
- state.

Après perte réseau :
- reconnect;
- reprendre au dernier sequence;
- dédupliquer.

## 5.4 Multi-tab / multi-device
Si backend sync le supporte :
- message event IDs;
- optimistic UI;
- conflict resolution;
- conversation version.

## 5.5 Offline
Permettre :
- lire historique local;
- rédiger;
- mettre demandes en attente;
- utiliser modèle local si disponible.

Ne pas envoyer automatiquement une action sensible en attente après longue coupure sans revalidation policy.

## 5.6 Stop
STOP génération :
- annule stream;
- conserve texte déjà reçu;
- marque réponse `stopped`;
- propose Continuer/Régénérer.

## 5.7 Errors
Erreur inline avec :
- raison utilisateur compréhensible;
- retry;
- changer modèle si pertinent;
- détails techniques cachés par défaut.

## 5.8 HTTP 413
Chat UI ne doit pas seulement afficher erreur.
Afficher :
« Contexte trop volumineux — réduction automatique / changer modèle / nouveau chat ».

Le backend Context Budget Manager reste responsable du vrai traitement.
