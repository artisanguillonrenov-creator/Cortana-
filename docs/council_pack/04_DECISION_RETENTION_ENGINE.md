# 4. Vote, consensus, confiance et rétention

## 4.1 Protocoles de décision
Supporter :
- SIMPLE_MAJORITY;
- APPROVAL;
- RANKED;
- CUMULATIVE;
- MAJORITY_CONSENSUS;
- SUPERMAJORITY;
- UNANIMITY;
- HYBRID;
- JUDGE;
- BLIND_JUDGE_THEN_VOTE.

## 4.2 HYBRID par défaut
1. regrouper candidats équivalents;
2. vote initial;
3. mesurer preuves;
4. détecter préoccupations critiques;
5. éliminer candidat invalidé par Verifier;
6. majorité claire + preuves suffisantes + aucune objection critique => décision;
7. sinon round supplémentaire ou Judge.

## 4.3 Judge
Si utilisé :
- modèle distinct si possible;
- candidats anonymisés;
- ne voit pas le vote avant premier verdict;
- read-only;
- reçoit tâche, candidats, preuves, objections et critères.

## 4.4 Confiance
Ne jamais croire uniquement un score auto-déclaré.

`ConfidenceFeatures` :
- selfReported;
- logprobDerived si disponible;
- crossAgentAgreement;
- evidenceSupport;
- verifierSupport;
- consistencyAcrossRounds;
- toolResultSupport;
- sourceQuality;
- contradictionPenalty.

La confidence finale est une heuristique calibrée, pas une probabilité de vérité.

## 4.5 Retention Engine
Objectif : ne pas retransmettre tout le monde à tout le monde.

Toujours prioriser :
1. objection critique;
2. preuve contradictoire;
3. argument nouveau;
4. désaccord significatif;
5. candidat minoritaire solide;
6. faible confiance nécessitant vérification;
7. soutien redondant seulement si budget.

## 4.6 Scoring initial
```text
retentionScore =
  0.25 novelty
+ 0.25 disagreement
+ 0.20 evidenceStrength
+ 0.15 concernSeverity
+ 0.10 minorityValue
+ 0.05 roleRelevance
- 0.15 redundancy
- 0.10 normalizedTokenCost
```
Poids configurables et à calibrer.

## 4.7 Contraintes
Par destinataire :
- max 6 arguments par défaut;
- max 2500 tokens par défaut;
- max 2 arguments d’un même agent;
- au moins 1 point minoritaire si divergence élevée;
- objections critical toujours conservées.

## 4.8 MinorityReport
Créer si :
- minorité mieux étayée;
- concern high/critical;
- verifier soutient partiellement minorité;
- risque important.

## 4.9 Candidate normalization
Ne pas voter sur texte brut.
Créer `CandidateFingerprint` :
- action principale;
- cible;
- contraintes;
- résultat attendu.

Ne pas fusionner deux candidats si les side effects ou risques diffèrent.
