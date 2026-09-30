# 15. Algorithmes et pseudocode de référence

## 15.1 runCouncil

```text
runCouncil(request):
    guard feature enabled
    state = createRun(request)

    plan = planner.plan(request)
    persist plan
    budgetGuard.validate(plan)

    contexts = prepareInitialContexts(plan)
    round0 = parallelMap(plan.slots):
        executeAgent(slot, contexts[slot], round=0)

    valid = validateContributions(round0)
    if valid.count < plan.quorum:
        recoverOrPartial()

    assessment = assess(valid, round=0)

    while shouldContinue(assessment, state, budget):
        retentionPlan = retainer.select(
            contributions=valid,
            assessment=assessment,
            targetSlots=plan.slots
        )

        contexts = buildCritiqueContexts(retentionPlan)
        nextRound = parallelMap(activeSlots):
            executeAgent(slot, contexts[slot], round=n)

        valid = validateContributions(nextRound)
        assessment = assess(valid, round=n)

    decision = decisionEngine.decide(assessment)

    if shouldChallenge(decision, request):
        challenge = challenger.run(decision)
        if challenge.hasCriticalValidatedIssue:
            decision = repairDecision(decision, challenge)

    draft = synthesizer.synthesize(decision)
    verification = verifierBridge.verify(draft, decision)

    if verification.failed and budgetGuard.canRepair():
        draft = boundedRepair(draft, verification)

    result = finalize(draft, decision, verification)
    persist result
    return result
```

## 15.2 assess

```text
assess(contributions, round):
    candidates = normalizeCandidates(contributions)
    claims = normalizeClaims(contributions)
    evidence = evidenceLedger.merge(claims)
    concerns = normalizeConcerns(contributions)

    ballots = preVote(candidates, contributions)
    voteStats = aggregate(ballots)

    divergence = computeDivergence(
        candidateEntropy,
        voteDispersion,
        semanticDistance,
        evidenceConflict,
        concernSeverity
    )

    evidenceCoverage = computeCoverage(claims, evidence)
    critical = unresolvedCritical(concerns)

    return Assessment(...)
```

## 15.3 shouldContinue

```text
if STOP: false
if round >= maxRounds: false
if deadline reached: false
if budget insufficient: false

if critical concern: true
if verifier contradiction: true
if divergence > highThreshold: true
if evidenceCoverage < minEvidence and tools possible: true

if agreement > strongThreshold
   and noCritical
   and decisionStable:
       false

return expectedMarginalGain > minGain
```

## 15.4 Candidate canonicalization

Étapes :
1. extraire verbe/action;
2. extraire objet/target;
3. extraire paramètres structurants;
4. extraire effets;
5. normaliser ordre et synonymes;
6. générer fingerprint;
7. calculer similarité;
8. ne fusionner qu’après vérification de compatibilité des side effects.

## 15.5 Voting
Pour chaque slot valide :
- produire ballot selon protocole;
- validation;
- égalité de poids par défaut.

Tie-break :
1. evidence strength;
2. verifier support;
3. fewer critical concerns;
4. judge optional;
5. sinon unresolved/tie, ne pas inventer gagnant.

## 15.6 Retention scoring

Pour chaque argument `a` et target `t` :

```text
score(a,t) =
  Wn * novelty(a)
+ Wd * disagreement(a)
+ We * evidenceStrength(a)
+ Ws * severity(a)
+ Wm * minorityValue(a)
+ Wr * roleRelevance(a,t)
- Wdup * redundancy(a)
- Wtok * tokenCost(a)
```

Puis :
```text
mandatory = critical + verifiedContradictions
selected = mandatory
for arg in sort(score desc):
    if fitsBudget and not duplicate:
        selected += arg
```

## 15.7 Divergence
Exemple :
```text
divergence =
  .30 * normalizedVoteEntropy
+ .20 * candidateSemanticSpread
+ .20 * evidenceConflict
+ .15 * concernDisagreement
+ .15 * confidenceSpread
```

## 15.8 Evidence coverage
```text
importantClaims = claims weighted by importance
supported = sum(weight for status in SUPPORTED, TOOL_VERIFIED, USER_PROVIDED)
coverage = supported / totalImportantWeight
```

## 15.9 Early stop stability
Decision stable si :
- même candidate key sur deux assessments;
- agreement score ne baisse pas significativement;
- aucune nouvelle critical concern;
- evidence coverage non décroissante.

## 15.10 Budget projection
Avant round suivant :
```text
projected =
  sum(estimatedInput(slot) + reservedOutput(slot))
  + judgeReserve
  + synthesisReserve

if projected > remaining:
    degrade()
```

## 15.11 degrade()
Ordre recommandé :
1. skip optional judge;
2. max retained args -25%;
3. cap output -20%;
4. skip next debate round;
5. reduce active agents via contribution quality;
6. synthesize partial.

Ne jamais supprimer le Challenger uniquement parce qu’il est minoritaire.
