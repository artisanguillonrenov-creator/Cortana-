# CORTANA VNEXT — DOSSIER DE PRODUCTION

## Autorité du dossier

Ce dossier est la spécification de production pour faire évoluer **Cortana 1.2.0** vers **Cortana VNext**, sans réécrire arbitrairement ce qui fonctionne déjà et sans créer de systèmes concurrents ou redondants.

Le code existant est la base réelle à faire évoluer. Le présent dossier décrit l'état cible, les responsabilités, les contrats, les migrations, les capacités, les tests et l'ordre d'implémentation. Il doit être lu **avant toute modification**.

## Règles absolues

1. **Une seule architecture.** Une responsabilité fonctionnelle ne doit avoir qu'un propriétaire canonique.
2. **Pas de doublons fonctionnels.** Ne pas créer un deuxième orchestrateur, une deuxième mémoire canonique, un deuxième registre d'outils, un deuxième scheduler ou un deuxième moteur de permissions.
3. **Pas de réécriture totale sans nécessité démontrée.** La base 1.2.0 est fonctionnelle et testée ; elle doit être migrée progressivement.
4. **Aucune régression silencieuse.** Tout comportement déjà fonctionnel doit rester disponible sauf décision explicitement documentée et justifiée.
5. **Aucun report silencieux.** Les fonctions marquées MUST dans ce dossier font partie du périmètre cible. Si l'environnement empêche une implémentation ou un test, documenter le blocage précisément et poursuivre les phases indépendantes. Ne pas présenter une fonction non implémentée comme terminée.
6. **Amélioration autorisée.** Si une conception ou bibliothèque plus récente, plus sûre, plus simple ou plus performante existe, elle peut remplacer le choix proposé à condition de :
   - préserver le comportement demandé ;
   - ne pas réduire le périmètre ;
   - conserver les invariants d'architecture ;
   - ajouter/adapter les tests ;
   - documenter la décision dans `docs/DECISIONS.md` ;
   - documenter toute migration de données ou compatibilité.
7. **Investiguer avant de modifier.** Ouvrir les fichiers concernés, suivre les appels et comprendre les effets avant de changer le code.
8. **Pas de contournement pour faire passer les tests.** Les tests valident le comportement ; ils ne doivent pas être affaiblis pour masquer une erreur.
9. **Base de données préservée.** Toute évolution de Room utilise des migrations explicites. Aucun `fallbackToDestructiveMigration` en production.
10. **Signature Android préservée.** Ne jamais modifier l'identité de package, la chaîne de signature ou les règles de mise à jour sans instruction explicite du propriétaire.
11. **Secrets hors dépôt et hors logs.** Aucune clé API, mot de passe, jeton ou secret ne doit apparaître dans le code, les traces, les captures de tests ou les archives de livraison.
12. **Actions externes réversibles par défaut.** Pas de push, publication, envoi, suppression destructive, paiement, changement de sécurité ou action visible par un tiers sans politique/autorisation adaptée.
13. **Git : pas de fusion automatique.** Si un dépôt Git est utilisé, travailler sur branche dédiée. Ne pas fusionner automatiquement dans la branche principale.
14. **Ne jamais affirmer un test matériel non exécuté.** Compilation, Robolectric, instrumentation compilée et essai réel sont des niveaux distincts.
15. **Le modèle ne commande jamais directement le système.** Tout passe par contrats structurés, registre d'outils, politique, exécuteur et vérification.

## Résultat attendu

À la fin, Cortana doit être un assistant personnel autonome généraliste capable :

- de converser avec des modèles locaux ou distants ;
- de contrôler Android de façon sûre ;
- de gérer une mémoire persistante et recherchable ;
- de planifier et reprendre des tâches longues ;
- de rechercher sur le Web et naviguer ;
- de travailler sur fichiers, documents, données et médias ;
- de cloner, comprendre, modifier, tester, compiler et empaqueter des projets logiciels ;
- d'utiliser des outils locaux, des workers appairés et des protocoles standards ;
- d'apprendre des procédures réutilisables sans exécuter aveuglément d'anciennes coordonnées ;
- de déléguer des sous-tâches à des spécialistes éphémères sous le contrôle d'un orchestrateur unique ;
- de survivre aux redémarrages par checkpoints et reprise vérifiée ;
- d'être observable, auditable, exportable, réparable et mise à jour proprement.

## Ordre de lecture obligatoire

1. `01_CURRENT_BASELINE_AND_NON_REGRESSION.md`
2. `02_FULL_TARGET_BLUEPRINT.md`
3. `03_DEVELOPER_SOFTWARE_FACTORY_SPEC.md`
4. `04_AGENT_RUNTIME_MEMORY_SKILLS_AUTONOMY.md`
5. `05_ANDROID_MULTIMODAL_INTEGRATIONS_PROTOCOLS.md`
6. `06_SECURITY_DATA_MIGRATIONS_OBSERVABILITY.md`
7. `07_IMPLEMENTATION_ROADMAP.md`
8. `08_ACCEPTANCE_AND_TEST_MATRIX.md`
9. `09_CLAUDE_CODE_MASTER_EXECUTION_PROMPT.md`
10. `10_2026_MODERNIZATION_NOTES.md`
11. `11_CAPABILITY_CHECKLIST.md`

## Hiérarchie en cas de conflit

1. Sécurité et intégrité des données.
2. Invariants d'architecture de ce fichier et du blueprint complet.
3. Non-régression de Cortana 1.2.0.
4. Spécifications détaillées de ce dossier.
5. Choix d'implémentation proposés.

Une amélioration technique peut modifier le point 5, parfois le point 4 si elle remplit strictement le même objectif, mais ne peut pas enfreindre les points 1 à 3.
