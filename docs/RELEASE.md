# Cortana — publication, mises à jour et retour arrière

Ce document décrit comment une version de Cortana est construite, signée, publiée, installée par-dessus
la précédente et, si nécessaire, abandonnée. Il est tenu à jour à chaque version (phase 30, D-20260928-060 ;
le rapport de la version candidate est ajouté en phase 33).

## 1. Identité de l'application (ne change jamais)

| Élément | Valeur |
|---|---|
| Paquet | `io.github.artisanguillonrenov.cortana` (debug : suffixe `.debug`) |
| Certificat de signature | SHA-256 `6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33` (RSA 2048, alias `cortana`) |
| Historique | `release/released.json` : chaque version publiée, son versionCode, son schéma de base et son certificat |

Le magasin de clés (`cortana-keystore.jks`) et ses mots de passe ne sont jamais dans le dépôt, les
journaux ni les archives : ils restent chez le propriétaire (`keystore/keystore.properties`, ignoré par Git,
à partir de `keystore.properties.example`).

## 2. Construire une version

1. Monter `versionCode` (strictement supérieur à toutes les versions publiées) et `versionName` dans
   `app/build.gradle.kts`. La tâche `verifyReleaseVersion` (branchée sur `preReleaseBuild`) refuse une
   version non monotone ou un historique dont le certificat varierait.
2. `./gradlew :contracts:test :worker:test :app:testDebugUnitTest :app:lintDebug` : tout doit être vert.
3. Ajouter la version à `release/released.json` (versionName, versionCode, `dbSchema`, `dbIdentityHash` de
   `app/schemas/…/<dbSchema>.json`, certificat). `ReleaseTest.releasedSchemasAreFrozen` interdit ensuite toute
   modification d'un schéma publié.
4. `./gradlew :app:assembleRelease` avec `keystore/keystore.properties` renseigné (le build release est
   refusé sans la clé de publication : jamais de repli sur la clé de debug). Il exige aussi `python3` et la
   bibliothèque ICU4C (`libicu`) : la tâche `androidRegexCheck` compile chaque expression régulière avec le
   moteur d'Android et refuse la release si l'une est rejetée (D-20260928-066).
5. `apksigner verify --print-certs` sur l'APK : le SHA-256 du certificat doit être celui du §1 ;
   `aapt2 dump badging` : paquet et versionCode attendus.
6. Produire le manifeste (§3), le copier dans `release/cortana-update.json` avec le certificat public
   (`apksigner verify --print-certs-pem`, bloc `CERTIFICATE` → `release/signing-cert.pem`), puis relancer
   `ReleaseTest` : l'application doit accepter ce manifeste réel.
7. `./gradlew cortanaSbom` (SBOM et licences), archive source par `git archive` (fichiers suivis seulement),
   contrôle de reproductibilité (§7).

## 3. Publier une mise à jour (canal du propriétaire)

1. `CORTANA_KEYSTORE=… CORTANA_STORE_PASS=… python3 tools/make_update_manifest.py app-release.apk --notes "…"`
   produit `cortana-update.json` : le manifeste (paquet, versionCode, versionName, SHA-256 et taille de l'APK,
   SDK minimal, certificat, version minimale dont on peut monter, `CompatibilityManifest`) signé
   SHA256withRSA par la clé de l'APK. L'outil refuse un APK signé par un autre certificat et vérifie sa
   propre signature ; la clé privée passe d'openssl à openssl par un tube, jamais sur le disque.
2. Déposer `cortana-update.json` et l'APK côte à côte sur un serveur https du propriétaire (l'adresse de
   l'APK dans le manifeste est relative au manifeste).
3. Sur la tablette : Réglages → Mises à jour → adresse du manifeste, puis « Vérifier maintenant » (ou
   vérification quotidienne, qui ne fait que prévenir).

## 4. Ce que Cortana vérifie avant d'installer

- Signature du manifeste avec la clé qui a signé l'application installée (aucune seconde clé à perdre).
- Même paquet, même certificat, versionCode strictement supérieur, schéma de base non plus ancien, SDK
  minimal, chemin de mise à jour (`minUpgradeFromVersionCode`).
- Téléchargement https uniquement (http seulement vers la boucle locale), taille bornée à celle annoncée,
  SHA-256 identique à celui du manifeste signé.
- Lecture de l'APK par Android (`getPackageArchiveInfo`) : paquet, versionCode, signataires, SDK minimal.
- Sauvegarde complète des données (`avant-mise-a-jour-*.cortana-backup`) avant de confier l'APK à
  l'installateur d'Android (`PackageInstaller`), qui demande toujours la confirmation du propriétaire.
- Après l'installation : Santé → Diagnostic de la base (contrôles du §14 de doc 06).

Aucune mise à jour n'est installée silencieusement, aucun code téléchargé n'est chargé par Cortana, et
aucune capacité (donc aucun modèle) ne peut préparer ou installer une mise à jour.

## 5. Retour arrière

Android n'installe jamais une version plus ancienne par-dessus une plus récente sans désinstallation
(et la désinstallation efface les données). La stratégie est donc :

1. **Avant** chaque mise à jour, Cortana sauvegarde tout (`files/backups/avant-mise-a-jour-*.cortana-backup`),
   et, si la base change de schéma, Room garde une copie brute de l'ancienne base
   (`files/backups/pre-migration/cortana.db.v<ancien>-to-v<nouveau>.<date>.db`).
2. **Pour revenir** à une version précédente de même schéma : Réglages → Sauvegarde → copier la sauvegarde
   dans Téléchargements, désinstaller, installer l'APK précédent (même certificat), puis Réglages →
   Sauvegarde → Restaurer depuis un fichier.
3. **Si la version précédente a un schéma plus ancien** (ex. VNext → 1.2.0) : une sauvegarde d'un schéma
   plus récent est refusée par une version plus ancienne (règle de compatibilité), et 1.2.0 n'a pas de
   restauration. Le retour arrière fait alors perdre ce qui a été créé depuis la mise à jour ; les données
   d'avant la mise à jour restent dans la copie `pre-migration` (récupérable par un développeur avec
   `adb`). Recommandation : garder la version précédente installée sur un autre appareil le temps de
   valider la nouvelle, et ne pas désinstaller sans sauvegarde exportée.
4. En cas d'échec d'installation, rien n'est modifié : l'ancienne version reste en place avec ses données.

## 6. Contrôles non exécutés dans l'environnement de construction

Installation réelle par `PackageInstaller`, confirmation système, mise à jour 1.2.0 → VNext sur la Galaxy
Tab (conservation des données sur l'appareil) : à faire sur la tablette (niveau P de la matrice de tests),
avec l'ensemble de la matrice physique : `docs/RC_CHECKLIST.md`.

## 7. Rapport de la version candidate 2.0.0-rc1 (28/09/2026) — remplacée par la rc2

> **La rc1 plante au démarrage sur Android** (rapport de bug du propriétaire ; voir §9 et D-20260928-066).
> Ce rapport est conservé tel quel, avec deux rectifications :
> 1. aucun de ses contrôles ne tournait sur le moteur d'expressions régulières d'Android ;
> 2. **le « 317 tests, 0 échec » du §7.3 n'était pas une exécution de l'état final** : Gradle a jugé les tâches
>    de test à jour après des changements limités à `release/` et `docs/` et a restitué les résultats
>    précédents. À l'état final de la rc1 (`9f24a71`), `SupplyChainTest.noSecretOrKeyMaterialIsTracked`
>    échouait (certificat public pris pour une clé — faux positif), et `ReleaseTest` n'a vraisemblablement pas
>    été relancé sur le manifeste re-signé (sa signature avait été vérifiée par l'outil avec openssl). Corrigé
>    en rc2 : les tests s'exécutent à chaque fois (D-20260928-066).

### 7.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc1-arm64-v8a.apk` (Galaxy Tab A11) | 48 855 118 o | `fe42f2c57c0339c64837a0e94000937e538cdef718448dd2a8cd7584679fda07` |
| `cortana-2.0.0-rc1-universal.apk` (secours) | 70 290 425 o | `92f8e638f1b146b8a847b8d2b0ae40c0165d56eae3931d353c2e85732ca23b43` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 202 o | `afd8c6cd5765d5005683aecd9c881bd96a601fa31823368fb677e9f5bf9ca277` |
| `cortana-2.0.0-rc1-source.zip` (`git archive` du commit de livraison) | voir le rapport de livraison | idem |
| `cortana-app.cdx.json`, `cortana-worker.cdx.json`, `LICENSES.md` (`./gradlew cortanaSbom`) | — | — |

### 7.2 Identité (vérifiée sur les deux APK)

`aapt2 dump badging` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **2**, versionName **2.0.0-rc1**,
minSdk 30, targetSdk 36. `apksigner verify --print-certs -v` : schéma de signature v2, **un seul signataire**,
certificat `CN=Cortana, OU=Owner, O=Cortana, L=Paris, C=FR`, SHA-256
**`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** — identique à la 1.2.0 et à
`release/released.json`. L'APK s'installe donc par-dessus la 1.2.0 (même paquet, même certificat, version supérieure).

### 7.3 Contrôles exécutés dans l'environnement de construction

| Contrôle | Résultat |
|---|---|
| `./gradlew :contracts:test :worker:test :app:testDebugUnitTest` | **317 tests, 0 échec** (app 304, worker 8, contrats 5) |
| `./gradlew :app:lintDebug` | 0 erreur ; 77 avertissements, surtout des suggestions d'écriture Kotlin (`UseKtx`), des versions plus récentes de dépendances (`GradleDependency`, `NewerVersionAvailable`, AGP — versions épinglées volontairement) et des API récentes protégées par un test de version (`InlinedApi`) ; aucun ne concerne la sécurité ou les données |
| Vérification des dépendances (`gradle/verification-metadata.xml`) | active pour tous les builds, aucun écart |
| SBOM (`./gradlew cortanaSbom`) | 143 composants (app 142 dont un commun, worker 7), 0 sans licence |
| Recherche des mots de passe du magasin de clés dans les 313 fichiers suivis | 0 occurrence ; aucun `.jks` ni `keystore.properties` suivi |
| Manifeste réel vérifié par l'application (`ReleaseTest`) | accepté ; copie modifiée refusée |
| Schémas publiés figés (`ReleaseTest`) | v1 `4cf277f7…`, v2 `824dbfa8…` |
| Migration 1→2 (`DatabaseMigrationTest`) | toutes les lignes conservées, index et FTS reconstruits, sauvegarde `pre-migration` |
| Développement de bout en bout (`SoftwareFactoryTest`, E2E-CODE-001 à 006) | vert |
| Arrêt brutal et reprise (`RuntimeTest`, `AutomationTest`, `AndroidLifecycleTest`) | vert (simulé en Robolectric) |
| Politique et sécurité (`SecurityPrimitivesTest`, `HardeningTest`, `BrowserTest`, `ArchitectureRulesTest`…) | vert |

### 7.4 Reproductibilité

Méthode : `git archive` du commit `dee224b` extrait dans deux répertoires distincts ; seuls y sont ajoutés
`local.properties` (chemin du SDK) et la clé de publication (fichiers ignorés par Git) ; dans chacun,
`./gradlew :app:assembleRelease --offline --no-build-cache` (JDK 21.0.10, Gradle 8.14.3, AGP 8.13.2,
build-tools 36.0.0), démons arrêtés entre les deux.

| Build | Durée | arm64-v8a | universel |
|---|---|---|---|
| A | 201 s | `fe42f2c57c0339c64837a0e94000937e538cdef718448dd2a8cd7584679fda07` | `92f8e638f1b146b8a847b8d2b0ae40c0165d56eae3931d353c2e85732ca23b43` |
| B | 192 s | `fe42f2c57c0339c64837a0e94000937e538cdef718448dd2a8cd7584679fda07` | `92f8e638f1b146b8a847b8d2b0ae40c0165d56eae3931d353c2e85732ca23b43` |

**Identiques octet pour octet** ; les APK livrés sont ceux du build A. Le premier essai (commit `3ada10a`)
avait échoué et révélé deux sources de non-déterminisme, corrigées dans `dee224b` (D-20260928-064) :
l'entrée `META-INF/version-control-info.textproto` (révision Git du répertoire de build — et le premier
APK construit depuis des modifications non committées annonçait à tort `4c8f9cf`) et le bloc
« dependency info » de Google Play, chiffré avec une clé aléatoire à chaque build. Tout le reste
(entrées, ordre, signature v2) était déjà identique. Les commits suivants ne modifient que `docs/`,
`release/` et `CHANGELOG.md`, qui n'entrent pas dans l'APK ; la reconstruction depuis l'archive source
livrée elle-même est rapportée dans le rapport de livraison (`BUILD_REPORT.md`).

### 7.5 État de la liste des capacités

190 capacités MUST : **189 DONE**, **1 BLOCKED_EXTERNAL** — « physical-device RC checklist » : la liste est
écrite (`RC_CHECKLIST.md`), son exécution exige la Galaxy Tab. Aucune MUST en TODO ou IN_PROGRESS.

SHOULD non livrés (déclarés, non reportés en silence) : adaptateurs natifs de fournisseurs (TODO — les
modèles Anthropic et Gemini restent accessibles par les fournisseurs compatibles OpenAI) ; adaptateurs de
messagerie Discord, Slack, WhatsApp et Signal (IN_PROGRESS — Telegram livré, interface commune prête).
Les 13 autres SHOULD sont DONE.

### 7.6 Non exécuté (jamais compté comme réussi)

- **Tests instrumentés (A)**, écrits et compilés : `AccessibilityFixtureTest` (2), `VisionFixtureTest` (1) — pas
  d'émulateur (pas de KVM) dans l'environnement de construction.
- **Appareil physique (P)** : toute la matrice de `RC_CHECKLIST.md` (installation par-dessus la 1.2.0 sur la
  tablette, migration réelle, permissions One UI, redémarrage et coupure réels, voix, vision, SMS/appels,
  intégrations réelles, worker sur une autre machine, sauvegarde entre deux appareils, mise à jour par le
  canal intégré, batterie et performances). Chaque ligne P de `TEST_MATRIX.md` y figure.
- Aucun serveur extérieur réel (fournisseurs de modèles, OAuth, e-mail, Telegram, Home Assistant, MCP, A2A,
  collecteur OTLP) n'a été contacté : les tests utilisent des serveurs locaux fidèles (MockWebServer,
  GreenMail, worker réel en boucle locale).

### 7.7 Gate de la phase 33 et gate finale

| Critère | État |
|---|---|
| APK release signé, même clé, versionCode incrémenté | ✅ (§7.2) |
| Sources (archive reproductible, sans secret) | ✅ (§7.4, rapport de livraison) |
| Tests (réussis et non exécutés séparés) | ✅ (§7.3, §7.6, `TEST_MATRIX.md`) |
| Notes de migration et d'installation | ✅ (`NOTE_INSTALLATION.md` §5, `DATA_MIGRATIONS.md`) |
| Changelog | ✅ (`CHANGELOG.md`) |
| MUST DONE ou blocage externe documenté | voir §7.5 |
| Migrations validées, E2E développement, crash/reprise, politique/sécurité | ✅ dans l'environnement de construction (§7.3) |
| Aucune régression de la référence 1.2.0 | ✅ dans l'environnement de construction : les 13 E2E 1.2.0 de `OrchestratorEndToEndTest` sont verts (un seul adapté, `plainChatStreamsAndPersists`, à la nouvelle invite système — D-20260927-014) ; comportement sur l'appareil : `RC_CHECKLIST.md` §3 |
| **Aucun blocker critique sur la Galaxy Tab (rapport RC)** | **BLOCKED_EXTERNAL** — tablette requise (`RC_CHECKLIST.md`) |

La 2.0.0-rc1 est donc une **version candidate** : complète et vérifiée partout où l'environnement le permet,
non validée sur l'appareil. La 2.0.0 finale (versionCode 3, même clé, même schéma v2) ne sera construite
qu'après un rapport RC sans blocker critique.

## 8. Rapport de la version candidate 2.0.0-rc2 (28/09/2026)

**Pourquoi une rc2** : la rc1 plante au démarrage sur la tablette (§9, D-20260928-066). Seules différences avec la
rc1 : les correctifs d'expressions régulières (`SkillService`, `RepositoryIntelligence`, `Ooxml`), le contrôle
ICU4C, les tests (dont `StartupAndRegexEngineTest`, instrumenté), la version. Même clé, même schéma v2 figé.

### 8.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc2-arm64-v8a.apk` (Galaxy Tab A11) | 48 855 122 o | `47320a3d2ee28e57883a94f872b1f9cff8a2262ff17e74aea14440b07ed7b344` |
| `cortana-2.0.0-rc2-universal.apk` (secours) | 70 290 429 o | `bda027d381c06dcd9562c65416467fd0046d383a47d079e9a090d02c56a96067` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 208 o | `9fdf9e6a939fb227c53f8478cbdb4e81db6807d14a823fab2496856f8a03af0b` |
| `cortana-2.0.0-rc2-source.zip` (`git archive` du commit de livraison) | voir le rapport de livraison | idem |

### 8.2 Identité

`aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **3**, versionName **2.0.0-rc2**, minSdk 30,
targetSdk 36. `apksigner` : schéma v2, un seul signataire, certificat SHA-256
**`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et à la rc1).
S'installe par-dessus la 1.2.0 et la rc1. Le dex livré contient le motif corrigé `\{\{([a-zA-Z0-9_]+)\}\}` et
plus celui de la rc1.

### 8.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, **tâches de test réellement exécutées** (plus de résultat « à jour » restitué) | **319 tests, 0 échec** (app 306, worker 8, contrats 5) — `:contracts:test`, `:worker:test`, `:app:testDebugUnitTest` exécutées (journal : aucune « UP-TO-DATE ») |
| `./gradlew :app:lintDebug` | 0 erreur, 77 avertissements (mêmes catégories qu'en rc1, §7.3) |
| Tests instrumentés : compilation et empaquetage (`assembleDebugAndroidTest`, liste `regex_patterns.json` incluse) | ✅ (exécution : non, pas d'appareil) |
| Expressions régulières compilées par ICU4C 74.2 (moteur d'Android) : app, contrats, `model_caps.json` | 341 compilées, **0 refusée** ; 8 construites à l'exécution (saisies du modèle ou du propriétaire, validées sur l'appareil) |
| Même contrôle sur les sources de la rc1 | 2 refusées, dont **exactement le plantage signalé** (position 20) |
| Garde de release `androidRegexCheck` exécutée dans chaque build release | ✅ (journaux des builds A et B) |
| Recherche des mots de passe du magasin de clés dans les fichiers suivis et l'archive | voir le rapport de livraison |

### 8.4 Reproductibilité

Deux builds depuis `git archive` du commit `ceff9b2` (répertoires distincts, sans cache, `--offline`) :

| Build | Durée | arm64-v8a | universel |
|---|---|---|---|
| A | 200 s | `47320a3d…7b344` | `bda027d3…96067` |
| B | 193 s | `47320a3d…7b344` | `bda027d3…96067` |

Identiques octet pour octet ; les APK livrés sont ceux du build A. Les commits suivants ne modifient que
`docs/`, `release/` et `CHANGELOG.md`. La reconstruction depuis l'archive livrée est dans le rapport de livraison.

### 8.5 Capacités, non exécuté, gates

Inchangés par rapport au §7.5 à 7.7 : 190 MUST — 189 DONE, 1 BLOCKED_EXTERNAL (exécution de `RC_CHECKLIST.md`
sur la tablette). S'y ajoute, non exécuté (A), `StartupAndRegexEngineTest` (4). La gate « aucun blocker critique sur
la Galaxy Tab » reste **BLOCKED_EXTERNAL** : le seul retour réel à ce jour (rc1) était un blocker critique, corrigé ici ;
la rc2 n'a pas encore été essayée sur l'appareil. La 2.0.0 finale (même clé, versionCode supérieur à celui de la
dernière version candidate — 4 depuis la rc3) attend un rapport RC sans blocker critique.

## 10. Rapport de la version candidate 2.0.0-rc3 (29/09/2026)

**Contenu** : le Conseil de réflexion (Cognitive Council Engine, D-20260929-067), **désactivé par défaut** ; schéma
v3 (migration additive 2→3) ; trois correctifs trouvés par les nouveaux tests (code HTTP et `Retry-After` perdus
sur un échec réessayable dans la passerelle, liste de types du validateur de schémas, attente trop courte d'un
test de procédures). Même clé, même paquet. Rapports C1→C12 : `COUNCIL_PROGRESS.md`.

### 10.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc3-arm64-v8a.apk` (Galaxy Tab A11) | 49 789 010 o | `ef6c128c2780ad1ba04c5fca9be329a402bde6a3668f6da455c46273772bb83d` |
| `cortana-2.0.0-rc3-universal.apk` (secours) | 71 224 317 o | `981dc625918d5bff5398637fb4d03d949e0da9ca77c583bb5243a4757d302d75` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 240 o | `92bd6d3f9d7cdcc0eade6045da1d111fb431ca7f2728722c5fed119725e671af` |
| `cortana-2.0.0-rc3-source.zip` (`git archive` du commit de livraison) | voir le rapport de livraison | idem |

### 10.2 Identité

`aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **4**, versionName **2.0.0-rc3**.
`apksigner` : schéma v2, un seul signataire, certificat SHA-256
**`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0, la rc1 et la rc2).
S'installe par-dessus la 1.2.0, la rc1 et la rc2. Manifeste : schéma 3, `minRestorableSchema` 2, montée possible
depuis le versionCode 1 ; accepté par le code de l'application (`ReleaseTest`). Schéma v3 enregistré dans
`release/released.json` (`2ff68b15…`) : figé désormais comme v1 et v2.

### 10.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, tâches de test réellement exécutées | **361 tests, 0 échec** (app 348, worker 8, contrats 5) — dont 42 nouveaux : `CouncilLogicTest` 10, `CouncilTest` 23, `CouncilGoldenSetTest` 3, `DatabaseMigrationTest` +2, `ArchitectureRulesTest` +4 lois, validateur +1 |
| Stabilité des tests dépendant du temps | `CouncilTest` et `CouncilGoldenSetTest` relancés 3 fois, `SkillsTest` 4 fois : verts à chaque passage |
| `./gradlew :app:lintDebug` | 0 erreur, 61 avertissements |
| Tests instrumentés : compilation et empaquetage (`assembleDebugAndroidTest`, `regex_patterns.json` à jour) | ✅ (exécution : non, pas d'appareil) |
| Expressions régulières compilées par ICU4C 74.2 | 360 compilées, **0 refusée** ; 8 construites à l'exécution |
| Banc déterministe de la couche de décision (52 tâches, 14 avec avis scriptés) | moteur livré 14/14, 0 décision dangereuse ; majorité simple 9/14 et 3 dangereuses (détail : `COUNCIL_PROGRESS.md` C12) |
| Mots de passe du magasin de clés et fichiers de clé dans les fichiers suivis, l'archive et la livraison | voir le rapport de livraison |

### 10.4 Reproductibilité

Deux builds release depuis `git archive` du commit `fc7f0d9` (répertoires distincts, sans cache, `--offline`) :

| Build | Durée | arm64-v8a | universel |
|---|---|---|---|
| A | 148 s | `ef6c128c…72bb83d` | `981dc625…d302d75` |
| B | 155 s | `ef6c128c…72bb83d` | `981dc625…d302d75` |

Identiques octet pour octet ; les APK livrés sont ceux du build A ; la garde `androidRegexCheck` a tourné dans
chacun. Le commit suivant ne modifie que `docs/`, `release/` et `CHANGELOG.md`. La reconstruction depuis l'archive
livrée est dans le rapport de livraison.

### 10.5 Non exécuté, gates

- Tests instrumentés (A) : `StartupAndRegexEngineTest` (4), `AccessibilityFixtureTest` (2), `VisionFixtureTest` (1).
  Aucun test instrumenté Compose des écrans du conseil.
- Matrice physique `RC_CHECKLIST.md`, dont le §14 (Conseil) : non exécutée.
- Qualité avec de vrais modèles (baselines B0–B7, ablations juge et modèles mixtes), latence, batterie et mémoire
  d'un conseil sur la tablette : **BLOCKED_EXTERNAL**.
- Gate « aucun blocker critique sur la Galaxy Tab » : **BLOCKED_EXTERNAL** (la rc2 et la rc3 n'ont pas encore
  été essayées sur l'appareil).

## 11. Rapport de la version candidate 2.0.0-rc4 (29/09/2026)

**Contenu** : l'espace de discussion (Chat Workspace, D-20260930-068) selon le paquet
`CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF`, chantiers H1→H12 (rapports : `CHAT_WORKSPACE_PROGRESS.md`) ; schéma v4
(migration additive 3→4 : chaque conversation existante devient une branche unique dans son ordre historique).
Aucun second backend : `ChatService` passe par l'orchestrateur, la passerelle de modèles, le registre d'outils, la
politique, la mémoire et les artefacts existants (lois WORKSPACE-1 à 3). Deux défauts trouvés par les nouveaux tests
et corrigés : mains libres muet quand le micro de coupure ne s'ouvrait pas ; texte vivant dupliqué lors d'un repli
de fournisseur après un flux partiel. Même clé, même paquet.

### 11.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc4-arm64-v8a.apk` (Galaxy Tab A11) | 51 624 240 o | `d19507a0eb14b046f4dee363cff0d87738b0c44d0869e159b1f715c0cd90dca7` |
| `cortana-2.0.0-rc4-universal.apk` (secours) | 73 059 547 o | `3e22638cb0cc2666e1b0d56df246ef1466cc68016d5d7fa57a1533243eafdb8b` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 255 o | `98a9d9a6a0aee15e0aa6598d123ff644dc3ab7d220761b707cd67535f4aa8d56` |
| `cortana-2.0.0-rc4-source.zip` (`git archive` du commit de livraison) | voir le rapport de livraison | idem |

### 11.2 Identité

`aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **5**, versionName **2.0.0-rc4**.
`apksigner` : schéma v2, un seul signataire, certificat SHA-256
**`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc3).
S'installe par-dessus la 1.2.0 et les rc1 à rc3. Manifeste : schéma 4, `minRestorableSchema` 2, montée possible
depuis le versionCode 1, signature vérifiée par l'outil puis acceptée par le code de l'application (`ReleaseTest`).
Schéma v4 enregistré dans `release/released.json` (`9abbf3f6…`) : figé désormais comme v1 à v3. Le code de
l'espace de discussion est présent dans le dex livré.

### 11.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, tâches de test réellement exécutées | **412 tests, 0 échec** (app 399, worker 8, contrats 5) — dont 51 nouveaux : `ChatWorkspaceFeaturesTest` 10, `WorkspaceUiTest` 9 (Compose sous Robolectric, écrans téléphone et tablette), `ChatRenderLogicTest` 8, `ChatWorkspaceServiceTest` 7, `ChatTreeTest` 5, `ChatHardeningTest` 5, `DatabaseMigrationTest` +2, `ArchitectureRulesTest` +4 lois, `VoiceTest` +1 |
| Stabilité | 6 exécutions complètes depuis la correction de la fausse clé : 5 vertes (dont 3 sous charge CPU, 2 cœurs saturés sur 4, et la porte finale) ; **une** est restée bloquée jusqu'à la limite de 50 min dans une classe postérieure à `DocumentTest`, sans trace. Cause inconnue, non reproduite ; chien de garde ajouté aux tests (piles de tous les fils si un test dépasse 120 s), vérifié par un test sonde |
| `./gradlew :app:lintDebug` | 0 erreur, 61 avertissements (inchangé depuis la rc3) |
| Tests instrumentés : compilation et empaquetage (`assembleDebugAndroidTest`, `regex_patterns.json` à jour) | ✅ (exécution : non, pas d'appareil) |
| Expressions régulières compilées par ICU4C 74.2 | 375 compilées (360 statiques, 15 dynamiques), **0 refusée** ; 8 construites à l'exécution |
| Garde de la chaîne d'approvisionnement | a signalé une fausse clé `sk-…` écrite en clair dans un nouveau test ; la clé est désormais construite à l'exécution, garde verte |
| Mesures sur 5 000 messages et 100 conversations (JVM + Robolectric, `ChatHardeningTest`) | fenêtre 56 ms, branches 37 ms, fil 59 ms (28 ms en cache), aperçus 62 ms, recherche 47 ms, contexte 173 ms |
| Mots de passe du magasin de clés et fichiers de clé dans les fichiers suivis, l'archive et la livraison | voir le rapport de livraison |

**Gate finale du paquet (doc 15)** :

| Critère | État | Preuve |
|---|---|---|
| Tests | ✅ | ci-dessus |
| Lint | ✅ | 0 erreur |
| Build release | ✅ | deux builds identiques (§11.4) |
| Migrations | ✅ (JVM) | `DatabaseMigrationTest.v3ToV4ChainsEachConversationInItsHistoricalOrder`, `v1ToV4InOneUpgrade` ; sur l'appareil : `RC_CHECKLIST.md` 15.1 |
| Vraie tablette | **BLOCKED_EXTERNAL** | `RC_CHECKLIST.md` §15 (22 lignes) non exécuté |
| Réseau instable | ✅ simulé / **BLOCKED_EXTERNAL** réel | `ChatHardeningTest.h12NetworkDrop…`, `h12ProviderFallback…`, `h12DuplicateAndOutOfOrder…` ; 15.9 |
| Très longue conversation | ✅ simulé / p95 et fuites sur l'appareil : **BLOCKED_EXTERNAL** | `h12FiveThousandMessagesAndAHundredConversationsStayFast` ; 15.21 |
| Gros fichiers, fichiers hostiles | ✅ | `h12HostileFileNames…` (plafond de 100 Mo, noms confinés, fichier illisible nommé) ; 15.5 |
| 413 | ✅ | `ChatWorkspaceServiceTest.h6ContextTooLargeIsReducedOnceThenExplained` |
| STOP | ✅ | `h6StopKeepsThePartialAnswerAndContinueExtendsIt`, `h9FourModelsAndOneLaneStopped…` |
| Accessibilité | ✅ sémantique / TalkBack réel : **BLOCKED_EXTERNAL** | `WorkspaceUiTest.a11y…`, `h11KeyboardShortcuts…` ; 15.18 à 15.20 |
| Aucun blocker critique | **BLOCKED_EXTERNAL** | aucune version VNext encore essayée sur la tablette (§9) |

### 11.4 Reproductibilité

Deux builds release depuis `git archive` du commit `02c5749` (répertoires distincts, sans cache, `--offline`) :

| Build | Durée | arm64-v8a | universel |
|---|---|---|---|
| A | 255 s | `d19507a0…c0cd90dca7` | `3e22638c…43eafdb8b` |
| B | 234 s | `d19507a0…c0cd90dca7` | `3e22638c…43eafdb8b` |

Identiques octet pour octet ; les APK livrés sont ceux du build A ; la garde `androidRegexCheck` a tourné dans
chacun. Le commit suivant ne modifie que `docs/`, `release/`, `CHANGELOG.md` et deux fichiers de test (la fausse clé
et le chien de garde ci-dessus) : rien de ce qui entre dans l'APK. La reconstruction depuis l'archive livrée est dans le rapport de
livraison.

### 11.5 Non exécuté, gates

- Tests instrumentés (A) : `StartupAndRegexEngineTest` (4), `AccessibilityFixtureTest` (2), `VisionFixtureTest` (1).
  Les écrans de l'espace de discussion sont testés sous Robolectric (`WorkspaceUiTest`, 9), pas sur un appareil.
- Matrice physique `RC_CHECKLIST.md`, dont le §15 (espace de discussion, 22 lignes) : non exécutée.
- Vraie tablette en paysage et portrait, réseau réellement instable, TalkBack réel, barge-in acoustique, p95 de
  rendu, mémoire et batterie avec une très longue conversation, export réel dans Téléchargements, collage d'une
  image copiée : **BLOCKED_EXTERNAL**.
- Gate « aucun blocker critique sur la Galaxy Tab » : **BLOCKED_EXTERNAL**.

## 12. Rapport de la version candidate 2.0.0-rc5 (29/09/2026)

**Contenu** : le design « Cortana Workspace » (paquet `design_handoff_cortana_workspace`, 7 étapes du prompt, rapports : `DESIGN_WORKSPACE_PROGRESS.md`) devient l'interface de toute l'application.
- Thème, 23 composants, Discussion en 3 colonnes, tâches suspendues et reprises, STOP partout, portrait.
- Écrans Historique, Tâches, Mémoire et Réglages, vérification visuelle contre le prototype.
- Schéma **inchangé (v4)** : aucune migration depuis la rc4. Aucun second backend : les écrans lisent et commandent l'orchestrateur, la politique, la mémoire, le planificateur et les artefacts existants.
- Les écrans rc4 et rc3 restent sélectionnables. Même clé, même paquet.

### 12.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc5-arm64-v8a.apk` (Galaxy Tab A11) | 53 840 861 o | `ead985925ea99e0c6b16759d5f5d01fdc481dd8f9e2a045ee3cc744dd7b826ab` |
| `cortana-2.0.0-rc5-universal.apk` (secours) | 75 276 168 o | `ef1729002fc39dc12eae558123d9862df05420fab61abf828dcd1fdeeb97a9d1` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 223 o | `f0d8f23c34bb99931cb245fdc619ca29c6d1434e853ed04b99087db2bf345029` |
| `cortana-2.0.0-rc5-source.zip` (`git archive` du commit de livraison) | voir le rapport de livraison | idem |

### 12.2 Identité

- `aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **6**, versionName **2.0.0-rc5**.
- `apksigner` : schéma v2, un seul signataire, certificat SHA-256 **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc4).
- S'installe par-dessus la 1.2.0 et les rc1 à rc4.
- Manifeste : signature vérifiée par l'outil, puis acceptée par le code de l'application (`ReleaseTest`, vert avec le manifeste rc5).
- Entrée rc5 de `release/released.json` : schéma 4, `9abbf3f6…`, inchangé.

### 12.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, tâches de test réellement exécutées | **445 tests, 0 échec** (app 432, worker 8, contrats 5), dont 33 nouveaux depuis la rc4. Détail ci-dessous. |
| Rendus | Chaque aperçu de composant et 9 écrans complets rendus en PNG à densité 1 (`DesignGalleryTest`, `DesignScreensTest`). L'écran réel est aussi capturé pendant les parcours (approbation, refus en pause, terminé, pause en portrait). |
| Comparaison au prototype | Côte à côte avec Chromium (mêmes polices), 1448 × 1086 et 1086 × 1448 : Discussion, Historique, Tâches, Mémoire, Réglages. Écarts supérieurs à 2 dp corrigés (`DESIGN_WORKSPACE_PROGRESS.md`, étape 7). |
| Couleurs | `grep -r "Color(0x" ui/` ne trouve plus que `ui/theme/CortanaTokens.kt`, imposé par une loi d'architecture |
| `./gradlew :app:lintDebug` | 0 erreur, 71 avertissements |
| Tests instrumentés : compilation et empaquetage (`assembleDebugAndroidTest`, `regex_patterns.json` à jour) | ✅ (exécution : non, pas d'appareil) |
| Expressions régulières compilées par ICU4C 74.2 | 377 compilées (362 statiques, 15 dynamiques), **0 refusée** ; 8 construites à l'exécution |
| Mots de passe du magasin de clés et fichiers de clé dans les fichiers suivis, l'archive et la livraison | voir le rapport de livraison |

Détail des 33 nouveaux tests :
- `DesignDiscussionUiTest` (6, sur l'application réelle) :
  - approbation accordée seulement par l'écran sécurisé ;
  - refus puis « Reprendre » avec nouvelle demande d'accord ;
  - STOP et Reprendre depuis la barre latérale ;
  - rail et Échap en portrait ;
  - arrêt d'urgence et reprise par l'empreinte ;
  - mode développeur.
- `DesignScreensRoutesTest` (5) : les quatre écrans sur données réelles, et le réglage STOP « Annuler ».
- `DesignScreensTest` (+6 écrans), `DesignLogicTest` (4), `TaskPauseTest` (2), `TaskRunProjectionTest` (3).
- `ArchitectureRulesTest` (+1 loi, couleurs), `AutomationTest` (+1, rattrapage unique).

**Défauts trouvés et corrigés pendant ce chantier** (détails dans `DESIGN_WORKSPACE_PROGRESS.md`) :
- palette de plus de 255 couleurs en paramètres de constructeur : plantage au lancement évité ;
- navigation lancée avant que le graphe soit prêt (ouverture depuis une notification) : défaut déjà présent en rc4 ;
- autorisation interrompue laissée « en attente » dans l'historique ;
- refus d'une autorisation lu par le modèle avant la pause ;
- double rattrapage d'une planification manquée au redémarrage.

**Incident de la porte (signalé, pas masqué)** :
- Une exécution complète s'est arrêtée parce que le système a tué le démon Gradle : 7,6 Go de mémoire, après 43 builds dans la même session (`Memory cgroup out of memory`). Ce n'est pas un défaut du code.
- La porte a été relancée avec des démons neufs : verte.

### 12.4 Reproductibilité

Deux builds release depuis `git archive` du commit `986a4b8` (répertoires distincts, sans cache, `--offline`) :

| Build | Durée | arm64-v8a | universel |
|---|---|---|---|
| A | 206 s | `ead98592…dd7b826ab` | `ef172900…eb97a9d1` |
| B | 157 s | `ead98592…dd7b826ab` | `ef172900…eb97a9d1` |

- Les deux builds sont identiques octet pour octet ; les APK livrés sont ceux du build A.
- Les commits suivants ne modifient que `release/cortana-update.json` et `docs/` : rien de ce qui entre dans l'APK.
- La reconstruction depuis l'archive livrée est dans le rapport de livraison.

### 12.5 Non exécuté, gates

- Tests instrumentés : `StartupAndRegexEngineTest` (4), `AccessibilityFixtureTest` (2), `VisionFixtureTest` (1). Les écrans du design sont testés sous Robolectric, pas sur un appareil.
- Matrice physique `RC_CHECKLIST.md`, dont les §15 et §16 (design, 15 lignes) : non exécutée.
- Rendu réel sur la Galaxy Tab A11 (lueurs, flous, polices), TalkBack réel, fluidité des animations : **BLOCKED_EXTERNAL**.
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL** (aucune version VNext encore essayée sur l'appareil).

## 13. Rapport de la version candidate 2.0.0-rc6 (30/09/2026)

**Contenu** : exécution directe des commandes de développement explicites (Git, tests, build, inspection) par le système de raccourcis déterministes existant, et garde anti-répétition dans la boucle modèle ↔ outils (voir `CHANGELOG.md`).
- Toutes les actions passent toujours par `ToolDispatcher` et `PolicyEngine` : approbations, idempotence et audit inchangés.
- Schéma **inchangé (v4)** : aucune migration depuis la rc4. Même clé, même paquet.
- Le fichier `gradle/verification-metadata.xml` a reçu 8 empreintes de métadonnées manquantes pour une construction sur cache vide, chacune contrôlée contre la somme publiée par Maven Central. La vérification reste stricte.

### 13.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc6-arm64-v8a.apk` (Galaxy Tab A11) | 53 873 629 o | `6c8f09d76e8c0afc2a09b2949b12bbaa5592106ae9dfa837216e3cd79ea083f2` |
| `cortana-2.0.0-rc6-universal.apk` (secours) | 75 308 936 o | `9e18432da48216b3cb85379e3093ed93e6ed26858cd6ab6fdcbd02a7857bd581` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | 1 320 o | `2d88c4678a88be32364b0c4306cae170dec1376aab48f19fabd62b1b89085992` |

### 13.2 Identité

- `aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **7**, versionName **2.0.0-rc6**, non débogable.
- `apksigner` : schéma v2, un seul signataire, certificat SHA-256 **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc5 ; `release/signing-cert.pem` inchangé).
- S'installe par-dessus la 1.2.0 et les rc1 à rc5.
- Manifeste : signature vérifiée par l'outil, puis acceptée par le code de l'application (`ReleaseTest`, vert avec le manifeste rc6).
- Entrée rc6 de `release/released.json` : schéma 4, `9abbf3f6…`, inchangé.

### 13.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, tâches de test réellement exécutées | **467 tests, 0 échec, 0 ignoré** (app 454, worker 8, contrats 5), dont 22 nouveaux depuis la rc5 |
| `verifyReleaseVersion` et `androidRegexCheck` (avant la construction release) | OK ; ICU4C 74.2 : 389 expressions compilées, 0 refusée |
| `./gradlew :app:lintDebug` | 0 erreur, 89 avertissements (aucun dans les fichiers modifiés pour la rc6) |
| Construction sur cache vide (GitHub Actions, `.github/workflows/android.yml`) | APK debug et tests verts |

### 13.4 Non exécuté, gates

- Tests instrumentés et matrice physique `RC_CHECKLIST.md` : non exécutés.
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL**.

## 14. Rapport de la version candidate 2.0.0-rc7 (30/09/2026)

**Contenu** : résultats web enrichis dans la conversation (images, vidéos, cartes de pages), par l'outil `web.search` existant (paramètre `mode`), enregistrés avec la discussion (`MessageMeta.webResults`, sans changement de schéma). Voir `CHANGELOG.md`.
- Toutes les actions passent toujours par `ToolDispatcher` et `PolicyEngine`. Nouvelle dépendance : Media3 1.8.0 (et Guava 33.3.1-android), empreintes contrôlées contre Google Maven et Maven Central, licences Apache 2.0 (`docs/LICENSES.md`).
- Schéma **inchangé (v4)**. Même clé, même paquet.

### 14.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc7-arm64-v8a.apk` (Galaxy Tab A11) | 59 748 747 o | `e7a5062b4b38c837780fd1daaf02332e1bc0f39a3515f9a7bfca2b69e922d927` |
| `cortana-2.0.0-rc7-universal.apk` (secours) | 81 184 054 o | `b83dc60b27abbcd2d09d8cd7a78b6d792036bf450766e51a0bca423bd56259d1` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | voir la Release | `4f3120cedd3aca7db93f8b52733105a1cd9ecb207785a4e9f05dbad3be49aa8a` |

### 14.2 Identité

- `aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **8**, versionName **2.0.0-rc7**, non débogable.
- `apksigner` : schéma v2, un seul signataire, certificat SHA-256 **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc6).
- S'installe par-dessus la 1.2.0 et les rc1 à rc6. Entrée rc7 de `release/released.json` : schéma 4, `9abbf3f6…`, inchangé.

### 14.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète, tâches de test réellement exécutées | **483 tests, 0 échec, 0 ignoré** (app 470, worker 8, contrats 5), dont 16 nouveaux depuis la rc6 ; `ReleaseTest` vert avec le manifeste rc7 |
| `./gradlew :app:lintDebug` | 0 erreur, 93 avertissements |
| `verifyReleaseVersion` et `androidRegexCheck` | OK ; ICU4C 74.2 : 398 expressions compilées, 0 refusée |
| Construction sur cache vide (GitHub Actions) | verte sur le commit des résultats web enrichis |

### 14.4 Non exécuté, gates

- Recherche réelle contre DuckDuckGo, Brave et SearXNG (tests sur un SearXNG simulé) ; lecture Media3 et rendu sur l'appareil : non exécutés.
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL**.

## 15. Rapport de la version candidate 2.0.0-rc8 (08/10/2026)

**Contenu** : configuration unique du pod RunPod du propriétaire (`PreconfiguredPod`, drapeau `preconfiguredPodVersion` dans les réglages), règle `cydonia` dans `model_caps.json`. Voir `CHANGELOG.md`.
- Schéma **inchangé (v4)**. Même clé, même paquet. Aucune nouvelle dépendance.

### 15.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc8-arm64-v8a.apk` (Galaxy Tab A11) | 59 765 147 o | `d6c2678399dac40920a13823307866b4588084fa25285a5ce25eef4433f16009` |
| `cortana-2.0.0-rc8-universal.apk` (secours) | 81 200 454 o | `4b37c3cf666ff7f5b92d76962513caae1da44b34c03cbe2c23e450958958212a` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | voir la Release | `3d0ef542538bf8b2fee6060a3c17537193f0ed3bf0311c30d0b3b79e9b098600` |

### 15.2 Identité

- `aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **9**, versionName **2.0.0-rc8**.
- `apksigner` : schéma v2, un seul signataire, certificat SHA-256 **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc7).

### 15.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète (avant le changement de version) | **487 tests, 0 échec, 0 ignoré** (app 474, worker 8, contrats 5), dont 4 nouveaux (`PreconfiguredPodTest`) |
| `verifyReleaseVersion` et `androidRegexCheck` | OK ; ICU4C 74.2 : 399 expressions compilées, 0 refusée |
| Pod réel (sonde HTTP) | `/v1/models`, discussion et flux sur le port 8000 ; embeddings `bge-m3` sur le port 7860 ; format d'outils émulé compris par le modèle |

### 15.4 Non exécuté, gates

- `ReleaseTest` avec le manifeste rc8 et `lintDebug` : non relancés localement (à vérifier par la CI).
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL**.

## 16. Rapport de la version candidate 2.0.0-rc9 (08/10/2026)

**Contenu** : étape 2 de `PreconfiguredPod` (fournisseur « RunPod · code & vision », routes `codingRoute` et `visionRoute` vers `qwen3.6-27b`, clé d'API saisie par le propriétaire). Aucune autre modification. Voir `CHANGELOG.md`.
- Schéma **inchangé (v4)**. Même clé, même paquet. Aucune nouvelle dépendance.

### 16.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc9-arm64-v8a.apk` (Galaxy Tab A11) | 59 765 151 o | `09088cad71d3ce6c5351c9cf96bc09314b026c1ad2442f46c0fa058ff5f20058` |
| `cortana-2.0.0-rc9-universal.apk` (secours) | 81 200 458 o | `b305ec9057267e8041cdbd48640cc5ed50fb3348430cf495cf0681ae8d1d29a7` |
| `cortana-update.json` (manifeste signé, APK arm64-v8a, = `release/cortana-update.json`) | voir la Release | `6b7dd327fcf595ace004f372cf343c11c02c4da42c934c2e721371c035080927` |

### 16.2 Identité

- `aapt2` : paquet `io.github.artisanguillonrenov.cortana`, versionCode **10**, versionName **2.0.0-rc9**.
- `apksigner` : un seul signataire, certificat SHA-256 **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc8).

### 16.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète avec le manifeste rc9 | **489 tests, 0 échec, 0 ignoré** (app 476, worker 8, contrats 5), dont 2 nouveaux (`PreconfiguredPodTest`) ; `ReleaseTest` vert |
| `verifyReleaseVersion` et `androidRegexCheck` | OK ; ICU4C 74.2 : 399 expressions compilées, 0 refusée |
| Pod réel `cortana-code-vision` (RTX 3090, llama.cpp, `qwen3.6-27b` Q4_K_M + projecteur de vision) | 401 sans clé ; appel d'outils natif (`finish_reason: tool_calls`) ; description exacte d'une capture d'écran (titre, réseau, boutons, positions, couleurs) ; ~41 jetons/s en génération |

### 16.4 Non exécuté, gates

- `lintDebug` : non relancé. Rendu sur l'appareil : non exécuté.
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL**.

## 17. Rapport de la version candidate 2.0.0-rc10 (08/10/2026)

**Contenu** : outils `github.*` (`executors/web/GitHubTools.kt`, client web protégé, API REST GitHub), méthode de travail (`prompts/method_fr.txt`, envoyée seulement quand des outils sont proposés), outils GitHub proposés seulement pour une demande qui parle d'un dépôt, audits routés vers le modèle de code, clonage d'une seule branche (`GitService.branchToClone`) et lecture en flux des objets de plus de 8 Mio. Voir `CHANGELOG.md`.
- Schéma **inchangé (v4)**. Même clé, même paquet. Aucune nouvelle dépendance.

### 17.1 Artefacts

| Fichier | Taille | SHA-256 |
|---|---|---|
| `cortana-2.0.0-rc10-arm64-v8a.apk` (Galaxy Tab A11) | 59 831 645 o | `4123245ecef6a0c40ef1c342e7fd11f08fe0df2e6e5e908274ed51013d6ccc35` |
| `cortana-2.0.0-rc10-universal.apk` (secours) | 81 266 952 o | `1aff24f4cb28300b6745ca7e97ec1b31cae1ca44a9144c11c0b3b8a78d09f433` |
| `cortana-update.json` (manifeste signé, = `release/cortana-update.json`) | voir la Release | `812b6d12630dd863743e9c30281149e1a65241278398fe81b709f50639a09c30` |

### 17.2 Identité

- `aapt2` : versionCode **11**, versionName **2.0.0-rc10** ; `apksigner` : certificat **`6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`** (identique à la 1.2.0 et aux rc1 à rc9).

### 17.3 Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Suite complète avec le manifeste rc10 | **495 tests, 0 échec, 0 ignoré** (app 482, worker 8, contrats 5), dont 7 nouveaux (`GitHubToolsTest`) |
| Régressions trouvées et corrigées pendant la préparation | `CouncilTest.c9` (outils GitHub proposés pour une question web générale) ; `ChatWorkspaceServiceTest.h6` (méthode envoyée inutilement en mode discussion) |
| `verifyReleaseVersion` et `androidRegexCheck` | OK ; ICU4C 74.2 : 401 expressions compilées, 0 refusée |

### 17.4 Non exécuté, gates

- Appels réels à api.github.com depuis la tablette, `lintDebug` : non exécutés.
- Gate « aucun blocker critique sur la tablette » : **BLOCKED_EXTERNAL**.

## 9. Rapport RC sur la tablette

| Version | Date | Source | Résultat |
|---|---|---|---|
| 2.0.0-rc1 | 28/09/2026 | rapport de bug Android du propriétaire | ❌ **blocker critique** : plantage au démarrage (`ExceptionInInitializerError` dans `AppContainer.<init>` ← `PatternSyntaxException` à l'initialisation de `SkillService`, motif `\{\{([a-zA-Z0-9_]+)}}` refusé par ICU « near index 20 »). Corrigé en rc2. |
| 2.0.0-rc2 | — | `RC_CHECKLIST.md` | **Non exécuté à ce jour.** |
| 2.0.0-rc3 | — | `RC_CHECKLIST.md` (dont §14) | **Non exécuté à ce jour.** |
| 2.0.0-rc4 | — | `RC_CHECKLIST.md` (dont §14 et §15) | **Non exécuté à ce jour.** |
| 2.0.0-rc5 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |
| 2.0.0-rc6 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |
| 2.0.0-rc7 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |
| 2.0.0-rc8 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |
| 2.0.0-rc9 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |
| 2.0.0-rc10 | — | `RC_CHECKLIST.md` (dont §14 à §16) | **Non exécuté à ce jour.** |

À remplir par le propriétaire avec `RC_CHECKLIST.md` (date, version de One UI, lignes ✅ / ❌, blockers
critiques, écarts).
