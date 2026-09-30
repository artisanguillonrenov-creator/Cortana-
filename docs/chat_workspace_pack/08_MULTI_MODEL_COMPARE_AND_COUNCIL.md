# 8. Multi-modèle, Comparaison et Conseil

## 8.1 Différence
**Comparaison** = l’utilisateur voit plusieurs réponses.
**Conseil** = plusieurs agents travaillent en interne puis Cortana donne une réponse.

Ne pas mélanger les deux.

## 8.2 Compare Mode
Sélectionner 2 à 4 modèles.
Même prompt envoyé en parallèle.
UI :
- tabs sur téléphone;
- colonnes sur tablette paysage/desktop;
- indicateurs provider/modèle;
- STOP global/individuel.

Actions :
- choisir une réponse;
- fusionner;
- demander à Cortana de comparer;
- sauvegarder gagnant;
- continuer avec un modèle.

## 8.3 Merge
Le merge reçoit réponses sélectionnées et produit synthèse.
Il ne doit pas automatiquement prendre la majorité comme vérité.

## 8.4 Conseil
Bouton `Conseil` utilise CognitiveCouncilEngine.
Timeline affiche :
- « Conseil en cours »;
- 4 analyses;
- confrontation;
- vérification;
- synthèse.
Pas les reasoning privés.

## 8.5 Sélecteur modèle
Recherche :
- provider;
- nom;
- local/cloud;
- capacité;
- contexte;
- outils;
- vision;
- reasoning.

Favoris et récents.

## 8.6 Auto route
Option « Automatique » :
ModelGateway choisit.
UI peut montrer ensuite quel modèle a été utilisé.

## 8.7 Per-chat lock
Conversation peut :
- suivre modèle global;
- verrouiller un modèle;
- suivre preset/projet.

## 8.8 Model switch mid-chat
Autorisé.
Créer événement système discret.
ContextEngine normalise formats.
