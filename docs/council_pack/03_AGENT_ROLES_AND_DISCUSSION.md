# 3. Profils d’agents et discussion

## 3.1 CouncilAgentProfile
Un profil contient :
- mission;
- biais utile;
- scope outils;
- format de sortie;
- capacités modèle;
- préférence modèle;
- fallback;
- budget;
- règles de mémoire;
- critères de succès.

## 3.2 Rôles standard

### Strategist
- décompose;
- identifie contraintes;
- produit alternatives;
- détecte dépendances;
- ne doit pas inventer les faits.

### Evidence Analyst
- sépare faits/hypothèses/inférences;
- recherche preuves;
- vérifie fraîcheur;
- évalue qualité des sources;
- signale ce qui reste non vérifié.

### Solution Engineer
- propose solution concrète;
- code/algorithme/plan;
- vérifie faisabilité;
- estime compromis;
- peut demander tests/outils.

### Challenger
- recherche contre-exemples;
- attaque hypothèses fragiles;
- teste scénarios extrêmes;
- signale sécurité/coût/latence;
- n’est pas obligé d’être en désaccord si la solution est solide.

## 3.3 Rôles dynamiques
Selon tâche :
- SecurityReviewer;
- CodeReviewer;
- TestAnalyst;
- DataAnalyst;
- Researcher;
- UXReviewer;
- ProductAnalyst;
- CommercialAnalyst;
- CreativeDirector.

## 3.4 Diversité
Sources de diversité :
- rôle;
- modèle;
- provider;
- paramètres sampling;
- outil scope;
- sous-problème.

Round initial indépendant obligatoire.
La diversité ne doit jamais réduire la sécurité.

## 3.5 Topologies
- `INDEPENDENT` : aucun échange avant décision.
- `FULL_MESH` : arguments retenus accessibles à tous.
- `HUB` : coordinateur logique central.
- `RING` : voisins seulement.
- `SPARSE_DYNAMIC` : liaisons choisies par pertinence.
- `AUTO` : runtime décide.

## 3.6 Paradigmes
- Independent Ensemble;
- Collective Refinement;
- Adversarial Debate;
- Memory Window;
- Report;
- Adaptive.

Recommandation :
commencer indépendant; n’ouvrir un débat que si divergence/incertitude le justifie.

## 3.7 Anti-conformisme
Ne pas révéler la majorité avant les avis initiaux.
Ne pas imposer « convaincs les autres ».
Ne pas présenter le vote comme vérité.
Préserver les minorités qui ont des preuves ou objections fortes.

## 3.8 Sortie structurée
```json
{
  "candidate": "...",
  "claims": [
    {
      "text": "...",
      "type": "fact|inference|assumption|recommendation",
      "confidence": 0.0,
      "evidenceRefs": []
    }
  ],
  "assumptions": [],
  "concerns": [
    {
      "severity": "low|medium|high|critical",
      "text": "...",
      "targetClaimId": null
    }
  ],
  "requestedChecks": [],
  "shortRationale": "..."
}
```

`shortRationale` = justification courte, pas chaîne de pensée.
