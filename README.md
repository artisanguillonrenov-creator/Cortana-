# Cortana VNext (2.0.0-rc3)

Native Android assistant (Kotlin + Jetpack Compose) for the owner's Galaxy Tab, evolved from the
on-device 1.2.0 (blueprint v1.2) along the VNext production pack (`docs/vnext_pack/`). Everything runs
on the tablet; model requests go over HTTPS to the providers the owner configures, and heavy developer
work can run on an optional paired worker (`worker/`).

Same application id (`io.github.artisanguillonrenov.cortana`) and signing certificate as 1.2.0: the
release installs over it and keeps the data (Room schema 1 → 2 → 3, explicit migrations).

2.0.0-rc3 adds the Cognitive Council Engine (`core/council/`, off by default): several temporary specialists
analyse a hard request in parallel, confront their retained arguments and Cortana answers once — through the
one model gateway, tool dispatcher, policy and memory (`docs/COUNCIL_MAPPING.md`, `docs/COUNCIL_PROGRESS.md`).

## Documentation

| Topic | File |
|---|---|
| Owner guide: install, upgrade 1.2.0 → VNext (French) | `docs/NOTE_INSTALLATION.md` |
| What changed | `CHANGELOG.md` |
| Build, sign, publish, update, roll back; release report | `docs/RELEASE.md` |
| Physical-device RC checklist (not executed yet) | `docs/RC_CHECKLIST.md` |
| Architecture, single owners, laws | `docs/ARCHITECTURE.md` |
| Decisions (D-YYYYMMDD-NNN) | `docs/DECISIONS.md` |
| Progress per roadmap phase | `docs/VNEXT_PROGRESS.md` |
| Cognitive Council: mapping, C1–C12 reports, spec pack | `docs/COUNCIL_MAPPING.md`, `docs/COUNCIL_PROGRESS.md`, `docs/council_pack/` |
| Capability checklist (MUST/SHOULD status) | `docs/CAPABILITY_CHECKLIST.md` |
| Tests: executed vs not executed | `docs/TEST_MATRIX.md` |
| Security model | `docs/SECURITY.md` |
| Data migrations | `docs/DATA_MIGRATIONS.md` |
| Tool capabilities (generated) | `docs/TOOL_CAPABILITIES.md` |
| Worker protocol | `docs/WORKER_PROTOCOL.md` |
| Third-party licenses | `docs/LICENSES.md` |

## Modules

```
contracts/   versioned contracts shared by the app and the worker (pure Kotlin/JVM)
app/         the Android application (core/, executors/, service/, ui/, util/ — see ARCHITECTURE.md)
worker/      optional paired worker (pinned HTTPS, signed requests, isolated jobs, admin CLI)
release/     published release history, public signing certificate, signed update manifest
tools/       make_update_manifest.py (signs the update manifest with the APK key),
             check_android_regex.py (compiles every regex with ICU4C, Android's engine)
```

## Build

Requirements: JDK 17 or 21, Android SDK with `platforms;android-36` and `build-tools;36.0.0`
(`local.properties` → `sdk.dir=...`), and for release builds `python3` with the ICU4C library
(`libicu`): every regular expression is compiled with Android's regex engine before a release
(`tools/check_android_regex.py`, D-20260928-066). Dependencies are verified by SHA-256
(`gradle/verification-metadata.xml`).

```
./gradlew :contracts:test :worker:test :app:testDebugUnitTest :app:lintDebug   # full suite
./gradlew :app:assembleDebug          # debug APK (id suffix .debug)
./gradlew :app:assembleRelease        # signed release APKs (arm64-v8a + universal)
./gradlew cortanaSbom                 # CycloneDX SBOMs + license inventory in build/sbom
```

Release signing reads `keystore/keystore.properties` (ignored by Git, see
`keystore/keystore.properties.example`):

```
storeFile=keystore/cortana-keystore.jks
storePassword=...
keyAlias=cortana
keyPassword=...
```

A release build without the release key is refused (`verifyReleaseVersion`): an APK signed by any
other key could never update the owner's installation. The keystore and its passwords stay with the
owner — never in the repository, the logs or an archive. Full procedure: `docs/RELEASE.md`.
