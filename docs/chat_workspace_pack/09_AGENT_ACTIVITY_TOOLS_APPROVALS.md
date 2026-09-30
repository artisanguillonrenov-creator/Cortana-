# 9. Activité agentique, outils et approbations

## 9.1 Activity rail
Un run agentique a un rail compact :
- statut;
- étape actuelle;
- durée;
- outils actifs;
- nombre d’étapes;
- STOP.

Expand :
- événements structurés;
- tool calls;
- vérifications;
- erreurs;
- artifacts produits.

## 9.2 Ne pas afficher
- chain-of-thought;
- hidden reasoning;
- prompts système;
- secrets.

## 9.3 Tool cards
Avant exécution si nécessaire :
- outil;
- action;
- cible;
- risque;
- approval.

Après :
- succès/échec;
- résumé;
- artifact/source ref;
- durée.

## 9.4 Background tasks
Si l’utilisateur quitte la conversation :
- task continue si autorisée;
- badge activité sidebar;
- notification selon settings;
- retour ouvre état courant.

## 9.5 Checklists
Plan visible sous forme de checklist est autorisé si ce sont des étapes opérationnelles,
pas une reproduction du raisonnement privé.

## 9.6 Worker
Si une tâche part au worker PC/serveur :
- badge backend;
- état connexion;
- logs résumés;
- artifacts;
- cancel.

## 9.7 MCP / plugins
Le chat n’expose pas tous les MCP tools dans le composer.
Tools panel :
- disponibles;
- actifs;
- permissions;
- santé.

## 9.8 Action history
Inspector peut montrer quelles actions ont réellement été exécutées.
Cela doit être auditable.
