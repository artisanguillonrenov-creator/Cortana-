# Cortana 1.2.0 — rapport de build (27/09/2026)

Construit en une seule passe, dans l'environnement de Claude Code, sans GitHub ni serveur.

## Ce qui est livré

| Fichier | Contenu |
|---|---|
| `cortana-release.apk` | APK **signé** (clé Cortana, schéma v2), arm64-v8a — à installer sur la Galaxy Tab A11 |
| `cortana-release-universal.apk` | Même application, toutes architectures (secours) |
| `cortana-source.zip` | Projet Gradle complet (sans les sorties de build ni la clé) |
| `cortana-keystore.jks` + `KEYSTORE_A_CONSERVER.txt` | Clé de signature et ses mots de passe — **indispensables pour les mises à jour** |
| `NOTE_INSTALLATION.md` | Guide d'installation et de configuration pas à pas |

Empreintes SHA-256 des APK :
- `cortana-release.apk` (arm64-v8a) : `ceab912a984a7cbdf78f69236eeb16d4261823b9d2b8069cf92d2a2319c4ee5f`
- `cortana-release-universal.apk` : `39ef491c7b82b736c93a583d57fad3c8dbbe50f6f9d80f73b24c9193c8ea7b73`

Certificat de signature (SHA-256) : `6d:98:37:5a:1e:a9:59:ee:53:92:24:39:bf:07:81:0c:c4:d1:fb:8f:3f:3e:d2:f4:a6:d7:72:b8:49:ee:da:33`
Identifiant : `io.github.artisanguillonrenov.cortana` · versionCode 1 · minSdk 30 · targetSdk 36.

## Ce qui a été compilé et vérifié

- `assembleRelease` : ✅ signé et vérifié avec `apksigner verify`.
- `assembleDebug` et `assembleDebugAndroidTest` : ✅ (les tests instrumentés compilent).
- Android Lint : ✅ 0 erreur (57 avertissements, surtout « nouvelle version disponible » et suggestions de style).
- Tests automatiques : ✅ **39/39 réussis** (JVM + Robolectric) :
  - `SseAndEmulationTest` (7) — flux SSE, fragments d'appels d'outils, erreurs, émulation JSON des outils (appels valides, inconnus, malformés).
  - `SchemaCronFastPathTest` (8) — validation des arguments, cron (dont changement d'heure), rappels en français (« dans 5 minutes », « à 18h15 », « demain à 8h », « une demi-heure »), « retiens que… ».
  - `SecurityPrimitivesTest` (7) — classification des risques (Payer/Supprimer → L3, Envoyer → L2, applis bancaires interdites), protection SSRF, masquage des clés, enveloppe « données non fiables ».
  - `ProviderHttpTest` (4) — client OpenAI-compatible contre un faux serveur (streaming, clé Bearer, particularités Groq, erreurs 401, serveur sans streaming).
  - `OrchestratorEndToEndTest` (13) — l'application réelle (base Room, registre d'outils, politique, orchestrateur) contre un faux fournisseur : discussion en streaming, **rappel sans aucun appel au modèle**, **« Retiens que je préfère des réponses courtes » enregistré**, boucle d'outils native, émulation pour les modèles sans outils natifs, appel malformé jamais exécuté, action L2 exécutée seulement après approbation / bloquée après refus (journal d'audit chaîné vérifié), STOP qui retire les outils sans couper la discussion, taint → confirmation, paramètres effacés bien persistés, apprentissage d'un serveur qui refuse `stream_options`.

## Ce qui n'a PAS pu être exécuté

- **Tests instrumentés d'accessibilité** (`AccessibilityFixtureTest`, contre l'écran de test intégré à la version debug) : écrits et compilés, **non exécutés** — l'environnement de build n'a pas d'émulateur (pas de KVM).
- **Aucun essai sur une vraie tablette.** Le pilotage de l'écran, l'indicateur « Cortana contrôle l'écran », l'empreinte, les notifications et le comportement batterie de One UI n'ont été vérifiés que par compilation, lint et relecture. La liste de contrôle de la note d'installation (§19) est le premier vrai test.

## Contenu fonctionnel (ensemble « Core » §0.4)

1. Discussion : sessions, streaming, historique, recherche plein texte (FTS4), sessions incognito.
2. Passerelle multi-fournisseurs OpenAI-compatibles : préréglages Infermatic, OpenRouter, Groq, DeepInfra, Together, serveur local, personnalisé ; test de connexion, liste des modèles, modèle par discussion, ordre de repli, plafonds de dépense, appels d'outils natifs **ou émulés** (bascule automatique).
3. Boucle d'agent interactive bornée (appels modèle/outils/durée), annulation, indicateur d'activité.
4. Registre d'outils + moteur de politique : niveaux L0–L3, classificateur des actions à l'écran (FR/EN, modifiable), taint, quotas, approbation par empreinte (écran FLAG_SECURE, anti-superposition), STOP global, secrets chiffrés et masqués, journal d'audit chaîné.
5. Contrôle d'Android par accessibilité : observer, chercher, toucher, appui long, toucher par coordonnées, saisir, vider, coller, valider, défiler, balayer, retour/accueil/récents, ouvrir une appli, ouvrir un lien, attendre un élément ; détection de reprise en main ; refus sur écran verrouillé.
6. Outils système : alarme, minuteur, volume, luminosité, partage, écrans de paramètres.
7. Mémoire : profil/préférences/faits avec confirmation, remplacement sans écrasement, recherche FTS injectée dans le contexte.
8. Planification : rappels sans IA (AlarmManager exact), tâches ponctuelles/intervalle/cron, rattrapage après redémarrage.
9. Web : `web.fetch` et `web.search` (DuckDuckGo sans clé, Brave, SearXNG) avec garde SSRF.
10. Fichiers limités à un dossier choisi (SAF) : lister, lire, chercher, écrire, modifier, supprimer.
11. Assistant de configuration (7 étapes) + écran Santé + Réglages.
12. Sécurité : tuile « Stop Cortana », STOP dans la notification et sur l'indicateur, écran d'approbation biométrique.
13. Voix (optionnelle, incluse) : dictée par le reconnaisseur système, lecture à voix haute.

## Reporté (Extensions §20)

Mode planifié/DAG, repli capture d'écran + vision, téléphonie/contacts/notifications/agenda, recherche par embeddings, adaptateurs natifs Anthropic/Gemini (accessibles via OpenRouter), mot d'éveil, client MCP, minification R8.

## Versions résolues

AGP 8.13.2 · Gradle 8.14.3 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 · Compose UI 1.9.4 / Material3 1.4.0 (BOM 2025.10.01) · core-ktx 1.17.0 · activity-compose 1.11.0 · fragment 1.8.9 · lifecycle 2.9.4 · navigation-compose 2.9.5 · Room 2.8.3 · WorkManager 2.10.5 · Biometric 1.1.0 · DocumentFile 1.1.0 · OkHttp 4.12.0 · kotlinx-serialization 1.9.0 · coroutines 1.10.2 · JDK 21 (cible 17) · SDK plateforme 36, build-tools 36.0.0. Détails et justifications : `docs/DECISIONS.md`.

Code : 64 fichiers Kotlin (≈ 8 550 lignes d'application, ≈ 640 lignes de tests).
