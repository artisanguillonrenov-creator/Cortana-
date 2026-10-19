# Audit de réutilisation Jarvis → Cortana (2026-10-19)

## Dépôt cible
`artisanguillonrenov-creator/Cortana-`, branche principale `main`. Application Kotlin Android (version `2.0.0-rc14` dans Gradle). Cette livraison **ne remplace pas** l'architecture VNext et ne change ni le schéma Room, ni les routes réseau, ni les permissions Android.

## Décisions anti-doublons (code réel de Cortana)
| Besoin | Composant existant dans Cortana | Décision |
| --- | --- | --- |
| STT local/distant, Whisper, TTS et interruption | `core/voice/{Voice,RemoteVoice,VoiceLoop}.kt`, `executors/voice/AndroidVoice.kt` | Conserver, étendre uniquement le point d'observation audio |
| Activation vocale / VAD | `WakeWord`, `Vad` et `SpeechDetector` | Conserver ; pas de deuxième pipeline |
| Mémoire persistante / conversations | `core/memory/` (Room v4) | Conserver ; aucun stockage biométrique ajouté sans consentement/migration |
| Orchestration des tâches | `core/orchestrator/`, `core/chat/` | Conserver un seul moteur |
| Outils et autorisations | `core/tools/`, `core/policy/` | Conserver : aucune autorisation issue d'une voix reconnue |
| Android et automatisation d'interface | `executors/accessibility/`, `service/` | Conserver : pas de second client Android |
| MCP, worker distant, modèles, agenda et connecteurs | `core/mcp/`, `core/worker/`, `core/model/`, `core/scheduler/`, `core/connections/` | Conserver leurs propriétaires uniques |
| Identifier plusieurs membres du foyer | Aucun encodeur d'empreinte vocale, appariement par profil ni attribution de voix observé | **Nouveau socle isolé dans cette PR** |
| Micros et haut-parleurs dans plusieurs pièces | `core/voice/` est actuellement centré sur la tablette | Étape future ; étudier `sosoj92/jarvis-assistant-vocal/core/satellite.py` |
| Domotique native Home Assistant | Pas vérifiée comme intégration native complète | Étape future, après modèle d'identité et protocole réseau |

## Code réellement ajouté dans cette PR
1. `core/voice/SpeakerMatcher.kt` : adaptation Kotlin, inspirée de `InterGenJLU/jarvis/core/speaker_id.py`, de la comparaison par similarité cosinus et de l'enrôlement par moyenne d'échantillons. Deux échantillons minimum. Un résultat proche de plusieurs profils ou sous seuil devient **inconnu**. Les vecteurs invalides et incompatibles sont rejetés. Aucun profil n'est privilégié implicitement.
2. `core/voice/RemoteVoice.kt` : seul point de raccordement optionnel au moment où le VAD a terminé une phrase ; reçoit une copie bornée de l'audio PCM 16 bits et la fréquence avant transcription. Le callback court en parallèle du STT. Aucun enregistrement ni envoi supplémentaire par défaut.
3. `SpeakerMatcherTest.kt` et `VoiceTest.kt` : tests de séparation, inconnus, ambiguïté, mauvais vecteurs et récupération de l'énoncé.
4. Attribution de licence MIT d'origine, `docs/third_party/JARVIS_INTERGEN_MIT.txt`.

## Ce qui **n'est pas encore opérationnel**
- **Aucune reconnaissance réelle de personnes à l'usage** : il manque un encodeur de locuteurs (ex. ECAPA-TDNN) utilisable depuis Android ou le worker, un parcours explicite d'enrôlement et un raccordement à l'interface. Le raccordement audio est désactivé tant qu'aucun encodeur n'est explicitement fourni.
- **Aucune séparation des mémoires par utilisateur** : pas de modification de Room ou des permissions tant que l'attribution et ses garanties ne sont pas vérifiées.
- **Aucun contrôle d'accès par voix** : une similarité cosinus ne vaut pas vérification de présence, résistance au rejeu, ni biométrie Android. Une voix d'invité ou inconnue ne devient jamais propriétaire. Les décisions du moteur de sécurité existant restent souveraines.
- Les modèles, seuils réels, performances en bruit ambiant, français, plusieurs micros et personnes sont **à évaluer sur des enregistrements consentis**. Le seuil de 0,70 est provisoire, repris comme valeur indicative du projet source ; ce n'est pas un score de probabilité.
- **Les satellites LAN, l'arbitrage multi-micro, Home Assistant et le client audio distant** ne sont pas installés par cette PR.
- Aucun APK signé ou test matériel n'a été réalisé.

## Sources étudiées
- `https://github.com/InterGenJLU/jarvis`, notamment `core/speaker_id.py` et `core/user_profile.py`, MIT, copyright (c) 2026 JARVIS Contributors.
- `https://github.com/sosoj92/jarvis-assistant-vocal`, notamment `core/satellite.py` et `core/registre.py`, MIT, copyright (c) 2026 sosoj92. Inspiration architecturale seulement, **aucun code de ce dépôt recopié**.
- Autres projets classés dans l'inventaire de recherche, non fusionnés à cette étape afin de préserver les lois VNext et d'éviter les doublons.

## Contrôles pré-fusion
1. Exécuter `./gradlew :app:testDebugUnitTest :app:lintDebug :contracts:test :worker:test`.
2. Vérifier `ArchitectureRulesTest` et `VoiceTest`, puis le build APK debug.
3. Choisir et tester l'encodeur, l'enrôlement et les permissions de profil avant toute activation pour les utilisateurs finaux.
4. Mettre à jour la documentation et les tests de sécurité en cas de futur routage de mémoire par identité.

**Statut : changement sur branche de travail, non fusionné sur `main`.**
