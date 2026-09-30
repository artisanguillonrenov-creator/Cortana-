# VNext — baseline figée (Phase 0)

Date : 2026-09-27. Branche locale `vnext` créée depuis le commit `5f0b596` (« Baseline Cortana 1.2.0 »). Dépôt Git **local uniquement** (aucun remote, aucun push — consigne du propriétaire).

## Identité (inchangée)

- applicationId : `io.github.artisanguillonrenov.cortana`
- versionCode 1 / versionName 1.2.0
- certificat de signature SHA-256 : `6d98375a1ea959ee53922439bf07810cc4d1fb8f3f3ed2f4a6d772b849eeda33`
- base Room version 1 (schéma exporté : `app/schemas/.../1.json`)

## Vérification des sources

Les 81 empreintes de `13_BASELINE_SHA256.txt` du dossier de production correspondent aux sources (`sha256sum -c` : 81 OK, 0 échec).

## Résultats de la baseline

| Contrôle | Résultat |
|---|---|
| `testDebugUnitTest` | 39/39 réussis (SseAndEmulation 7, SchemaCronFastPath 8, SecurityPrimitives 7, ProviderHttp 4, OrchestratorEndToEnd 13) |
| `lintDebug` | 0 erreur, 57 avertissements, 1 indice |
| `assembleDebugAndroidTest` | compile (AccessibilityFixtureTest non exécuté : pas d'émulateur, pas de KVM) |
| `assembleRelease` | APK arm64-v8a + universel signés, vérifiés par apksigner |

## Environnement de build

JDK 21.0.10, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, SDK 36 / build-tools 36.0.0. Pas de KVM (pas d'émulateur). Docker client sans démon ; `unshare` (user + net namespaces) disponible ; Python 3.11, Git 2.43, Node 22.

## Constat : reproductibilité

Deux builds release successifs des mêmes sources ont produit des APK de SHA-256 différents (1.2.0 livré vs baseline). À traiter en phase release (reproductibilité).
