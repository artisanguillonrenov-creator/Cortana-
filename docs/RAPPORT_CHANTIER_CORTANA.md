# Rapport du chantier Cortana — stabilisation et extensions (2.0.0-rc11)

Date : 08/10/2026. Mission : `MISSION_CLAUDE_CODE_CORTANA_PLAN_COMPLET_2026-10-08.md`.

## 1. Base, branche, commits

| Élément | Valeur |
|---|---|
| Version de départ | `v2.0.0-rc10` (versionCode 11), commit `ce2d958ecd3655ddbcdae0cfed147293764ad959` — dernière Release publiée au démarrage |
| `main` au démarrage | `ab66f9f2b175d9289ff457d2631e7640f8f265e2` |
| Écart `main` / rc10 | `main` avait 1 commit absent de rc10 : `ff74026` (préréglage Elyndor Cloud + `ProviderQuirks.extraBody`). rc10 avait 10 commits absents de `main`. |
| Branche de chantier | `claude/cortana-stabilisation-extensions-rc10`, créée depuis le tag `v2.0.0-rc10` (le tag n'est pas modifié) |
| Repris de `main` | `ff74026` par cherry-pick (→ `184c920`), après revue : la LoRA n'est envoyée que par ce préréglage (test ajouté). Rien n'a été écarté. |
| `main` | **non modifiée** (aucune fusion) |

## 2. Porte de signature (GATE 0)

Clé privée d'origine disponible localement, hors Git (`keystore/` ignoré par `.gitignore`, fichiers en 0600, jamais affichés ni publiés). Certificat de l'alias `cortana` vérifié avant toute modification : SHA-256 `6d98375a…eeda33`, identique à `release/released.json`. Outils : Gradle 8.14.3, JDK 21, SDK 36, build-tools 36.0.0 (`apksigner`, `aapt2`). **GO.**

## 3. Fichiers modifiés et raison

| Fichier | Raison |
|---|---|
| `core/chat/ImageIntent.kt` (nouveau) | distinguer recherche d'images sur le web, génération, demande ambiguë |
| `core/orchestrator/FastPathRegistry.kt` | raccourci `web.images` : `web.search` mode images (ou vidéos) pour une demande explicite, réponse rédigée sans modèle |
| `core/orchestrator/Orchestrator.kt` | ligne d'outil enregistrée pour un raccourci à données riches ; message clair en mode « Discussion » |
| `core/orchestrator/StepRunner.kt` | métadonnées riches : résultats web et images produites conservés avec la ligne d'outil |
| `core/chat/ChatContracts.kt`, `ChatTimeline.kt` | `MessageMeta.images` + `ProducedImages` (images bitmap seulement, outils média seulement) → parties `Image` |
| `executors/media/MediaTools.kt` | images générées/retouchées/transformées transmises en données typées ; génération refusée pour une recherche web |
| `executors/web/WebTools.kt` | mode `images` imposé quand le propriétaire l'a demandé ; DuckDuckGo : variantes du jeton, un nouvel essai, messages exploitables |
| `core/tools/CapabilityMatcher.kt` | génération d'images non proposée pour une recherche ; `web.search` toujours proposé |
| `ui/workspace/Timeline.kt`, `WorkspaceViewModel.kt`, `ui/components/ArtifactImages.kt` (nouveau) | vignettes partagées et bornées ; `MessagePart.Image` affichée comme image (MIME réel vérifié) |
| `ui/chat/ChatScreen.kt` | écran classique : résultats web et images produites affichés |
| `core/model/ProviderHealthCheck.kt` (nouveau), `ProviderRepository.kt`, `ui/providers/ProvidersScreen.kt` | diagnostic différencié de « Tester la connexion » |
| `core/model/OpenAiCompatibleProvider.kt`, `ModelGateway.kt` | messages 502/503/504 ; clé jamais envoyée en http vers Internet |
| `core/model/PreconfiguredPod.kt` | routes code/vision et modèle déjà choisis par le propriétaire conservés |
| `.github/workflows/android.yml` | CI aussi sur `main` et les PR vers `main`, avec lint (permissions `contents: read`) |
| `app/build.gradle.kts`, `release/released.json`, `release/cortana-update.json` | version 2.0.0-rc11 / 12, historique, manifeste signé |
| `app/src/androidTest/assets/regex_patterns.json` | liste ICU régénérée (outil du dépôt) |
| Tests : `ImageSearchTest.kt` (nouveau), `ProviderHttpTest.kt`, `PreconfiguredPodTest.kt` | voir §4 |
| `docs/CORTANA_CATALOGUE_EXTENSIONS.md` (nouveau), `CHANGELOG.md`, `docs/RELEASE.md`, ce rapport | documentation |

## 4. Problème → correction → test → résultat

| Problème initial | Correction | Test | Résultat |
|---|---|---|---|
| « Cherche une image sur Internet » donnait un fichier ou du texte | raccourci `web.images` + mode imposé + rendu `WebResults` | `ImageSearchTest` (demande explicite, réouverture, mode oublié) | ✅ |
| Recherche et génération confondues | `ImageIntent`, exclusion de la génération, refus dans l'outil | `aWebImageSearchIsNeverOfferedGeneration…`, `explicitRequests…` | ✅ |
| Images générées affichées « artefact <id> » | `ProducedImages` → `MessagePart.Image` | `picturesCortanaProduced…` (PNG affiché, PDF/SVG refusés, autre outil ignoré) | ✅ |
| Images envoyées en simple puce | `ImageAttachment` (vignette bornée, MIME réel) | rendu Robolectric existant (`WorkspaceUiTest`) | ✅ |
| Écran classique sans images | `WebResultsView` + vignettes dans `ToolRow` | compilation + tests d'écran existants | ✅ (non vérifié visuellement sur tablette) |
| DuckDuckGo fragile (jeton `vqd`) | variantes, nouvel essai, messages | `duckDuckGoImagesWork…` (OK, 429, page illisible, jeton absent) | ✅ |
| Recherche vide → résultat inventé possible | message « aucune image… » | `anEmptySearchSaysSo…` | ✅ |
| Mode « Discussion » : réponse à côté | message pour réactiver les outils | `aToolLessConversationExplains…` | ✅ |
| Panne de fournisseur peu compréhensible | `ProviderHealthCheck` | `theConnectionCheckTellsWhatToDo` | ✅ |
| Préconfiguration écrasant des choix | routes et modèle conservés | `routesTheOwnerAlreadyChoseAreKept` | ✅ |
| LoRA potentiellement envoyée partout | vérifiée : seulement `elyndor-cloud` | `theLoraModuleIsSentOnlyByTheElyndorCloudPreset` | ✅ |
| Clé envoyée en http vers Internet possible | refus côté client | `aKeyIsNeverSentInClearTextOverTheInternet` | ✅ |
| CI seulement sur `claude/**` | `main` + PR + lint | — (s'exécutera sur GitHub) | à confirmer par la CI |

## 5. Contrôles

- Suite complète : **508 tests, 0 échec, 0 ignoré** (app 495, worker 8, contrats 5), exécutée avec le manifeste rc11 (`ReleaseTest` vert).
- `lintDebug` : **0 erreur**, 93 avertissements, 8 suggestions.
- `androidRegexCheck` : 414 expressions compilées par ICU4C 74.2, 0 refusée. `verifyReleaseVersion` : OK.
- Signature : `apksigner verify --verbose --print-certs` → v2, 1 signataire, **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** sur les deux APK. `aapt2` : `io.github.artisanguillonrenov.cortana`, versionCode **12** (> 11 publié), non débogable.
- Manifeste `cortana-update.json` signé avec la clé historique ; signature vérifiée par l'outil et par `ReleaseTest` (validation côté Cortana).

## 6. Sécurité : risques

**Confirmés et corrigés** : envoi possible d'une clé de fournisseur en clair vers un hôte Internet (corrigé).
**Revus, sans faille démontrée** : protection SSRF (adresses privées, rebinding DNS, redirections), https exigé hors LAN pour MCP, connexions et OAuth, plugins sans code exécutable, MCP/plugins/A2A soumis à `ToolDispatcher`/`PolicyEngine`/STOP, sauvegarde sans secrets (Keystore non exportable, à ressaisir après restauration).
**Conservé volontairement** : `usesCleartextTraffic="true"` (serveurs du réseau local en http) ; la règle applicative ci-dessus empêche la fuite de clés.
**Non traité ici** : contenu des pods RunPod (hors périmètre), mode adulte/LoRA (reporté).

## 7. Données préservées (par conception)

Même paquet, même certificat, versionCode supérieur : Android installe par-dessus sans désinstaller. Schéma Room inchangé (v4) ; les nouvelles métadonnées (`images`) sont un champ JSON facultatif, lu par défaut vide sur les anciens messages. Aucune donnée supprimée. **Non vérifié sur l'appareil** (pas de tablette connectée).

## 8. À faire uniquement sur la tablette

1. Installer `cortana-2.0.0-rc11-arm64-v8a.apk` par-dessus la rc10 ; vérifier version, conversations, réglages, mémoire.
2. Écran Workspace et écran classique : « Trouve-moi une photo de la tour Eiffel sur Internet » → images visibles, agrandissables, encore présentes après réouverture.
3. Générer une image (si un fournisseur d'images est configuré) → l'image s'affiche.
4. Fournisseurs → « Tester la connexion » sur chaque pod (allumé, puis arrêté) → messages attendus.

## 9. État et retour arrière

- Rien n'est fusionné dans `main` ; le tag `v2.0.0-rc10` est intact.
- Retour arrière : réinstaller l'APK rc10 n'est pas possible par-dessus (versionCode inférieur) ; en cas de problème, une rc12 corrective est la voie normale. Les données restent compatibles (schéma inchangé).
