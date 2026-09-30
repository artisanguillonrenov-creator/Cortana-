# 5. Modèles, contexte, outils et budgets

## 5.1 Routage
Tout passe par `ModelGateway`.

Modes :
- SAME_MODEL;
- PER_ROLE;
- AUTO_CAPABILITY;
- HYBRID.

Par rôle l’utilisateur peut choisir :
- provider;
- modèle;
- fallback;
- local-only;
- cloud allowed;
- reasoning effort;
- max input;
- max output;
- timeout.

## 5.2 Budget global
`CouncilBudget` :
- maxInputTokens;
- maxOutputTokens;
- maxTotalTokens;
- maxCost;
- maxWallTimeMs;
- maxProviderCalls;
- maxToolCalls;
- maxConcurrentAgents;
- maxRounds.

## 5.3 Dégradation
Si budget insuffisant :
1. réduire rounds;
2. réduire retained arguments;
3. réduire output;
4. supprimer Judge;
5. passer 4 -> 2;
6. single-agent fallback.

## 5.4 Context Budget Manager
Avant chaque appel :
```text
allowedInput =
  min(modelContextLimit, providerRequestLimit)
  - reservedOutput
  - safetyMargin
```

Puis :
- sélectionner mémoire pertinente;
- supprimer outils inutiles;
- compacter historique;
- packer arguments retenus;
- estimer tokens;
- seulement ensuite appeler le provider.

## 5.5 HTTP 413
Interdiction de retry identique.
Réaction :
- retirer tool schemas inutiles;
- compacter mémoire;
- réduire retained arguments;
- réduire historique;
- rerouter vers modèle contexte plus grand si autorisé.

## 5.6 Rate limits
ModelGateway fournit :
- RPM;
- TPM/ITPM;
- concurrency;
- cooldown;
- retry-after.

CouncilRuntime planifie par vagues.

## 5.7 Tool scoping
Sous-agents read-only par défaut.

`CapabilityMatcher` sélectionne 5-15 outils utiles, pas tout le catalogue.

Exemple tablette optimisation :
- android.observe;
- android.settings;
- battery;
- storage;
- apps;
- diagnostics;
- web.search si nécessaire.

Pas Git/MCP/SoftwareFactory/SMS si inutiles.

## 5.8 Tool discovery
Agent peut demander `tools.discover`.
Le registry retourne une liste compacte.
PolicyEngine valide l’élargissement.

## 5.9 Side effects
Un agent propose une action.
TaskOrchestrator + PolicyEngine l’autorisent.
Consensus ne remplace jamais autorisation.

## 5.10 Mémoire
Le conseil lit via ContextEngine.
Après run, persister seulement :
- décision;
- faits confirmés;
- preuves;
- artefacts;
- résumé utile.

Pas de chain-of-thought.
