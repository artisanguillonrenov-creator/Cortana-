# 01 — BASELINE CORTANA 1.2.0 ET CONTRAT DE NON-RÉGRESSION

## 1. Baseline à conserver

La base actuelle est une application Android native Kotlin + Jetpack Compose, package :

`io.github.artisanguillonrenov.cortana`

Toolchain observée dans les sources livrées :

- Gradle 8.14.3
- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- KSP 2.2.21-2.0.5
- JDK de build 21, bytecode cible 17
- compileSdk 36
- targetSdk 36
- minSdk 30
- Room 2.8.3
- WorkManager 2.10.5
- Jetpack Compose / Material 3
- OkHttp 4.12.0
- kotlinx serialization / coroutines

Le projet actuel est un module Android `:app`, avec injection manuelle centralisée dans `AppContainer`.

## 2. Architecture actuelle réelle

Arborescence fonctionnelle actuelle :

```text
app/src/main/java/io/github/artisanguillonrenov/cortana/
├── CortanaApp.kt
├── core/
│   ├── context/
│   │   └── ContextBuilder.kt
│   ├── memory/
│   │   ├── ConversationRepository.kt
│   │   ├── CortanaDatabase.kt
│   │   ├── Daos.kt
│   │   ├── Entities.kt
│   │   ├── MemoryRepository.kt
│   │   └── SettingsRepository.kt
│   ├── model/
│   │   ├── ModelGateway.kt
│   │   ├── ModelTypes.kt
│   │   ├── OpenAiCompatibleProvider.kt
│   │   ├── ProviderRepository.kt
│   │   ├── SseParser.kt
│   │   └── ToolCallEmulation.kt
│   ├── orchestrator/
│   │   ├── FastPaths.kt
│   │   └── Orchestrator.kt
│   ├── policy/
│   │   ├── ApprovalBroker.kt
│   │   ├── AuditLog.kt
│   │   ├── KillSwitch.kt
│   │   ├── PolicyEngine.kt
│   │   ├── Risk.kt
│   │   └── UiRiskClassifier.kt
│   ├── scheduler/
│   │   ├── CortanaScheduler.kt
│   │   └── CronExpression.kt
│   ├── secrets/
│   │   └── SecretStore.kt
│   └── tools/
│       ├── ToolDefinition.kt
│       └── ToolRegistry.kt
├── executors/
│   ├── accessibility/
│   ├── files/
│   ├── internal/
│   ├── system/
│   └── web/
├── service/
└── ui/
```

## 3. Fonctions qui doivent absolument rester opérationnelles

### 3.1 Conversation

- sessions persistantes ;
- streaming ;
- historique ;
- recherche plein texte ;
- sessions incognito ;
- choix fournisseur/modèle par session ;
- toolsets conversation / assistant / complet ;
- TTS optionnel ;
- dictée vocale push-to-talk.

### 3.2 Passerelle modèles

- fournisseurs OpenAI-compatibles ;
- presets ;
- test de connexion ;
- découverte/liste des modèles ;
- fallback configurable ;
- plafonds de dépense ;
- usage/cost accounting ;
- streaming SSE ;
- tool calling natif ;
- tool calling émulé ;
- apprentissage de particularités fournisseur telles que refus de `stream_options` ;
- déduplication des identifiants de modèles ;
- aucun raisonnement interne affiché comme réponse utilisateur.

### 3.3 Boucle d'agent

- tâche bornée en durée ;
- bornes d'appels modèle/outils ;
- annulation ;
- état actif visible ;
- anti-répétition ;
- refus des appels malformés ;
- STOP qui coupe les outils sans empêcher la conversation ;
- actions sensibles soumises au PolicyEngine ;
- audit des décisions.

### 3.4 Fast paths déterministes

Ils doivent être conservés et étendus, jamais supprimés au profit du modèle :

- `Rappelle-moi...` crée un rappel sans appel LLM ;
- `Retiens que...` écrit la mémoire explicite sans dépendre d'un tool call du modèle.

Le moteur de fast paths doit devenir extensible et versionné.

### 3.5 Mémoire

- Room persistant ;
- mémoires profile / preference / semantic / episodic ;
- statuts active / pending_confirmation / superseded / deleted ;
- provenance ;
- recherche FTS ;
- injection au contexte ;
- confirmations pour écritures sensibles/ambiguës ;
- aucune destruction de la mémoire lors d'une mise à jour.

### 3.6 Android

- AccessibilityService ;
- observation UI ;
- recherche d'éléments ;
- clic texte / coordonnées ;
- long press ;
- saisie / effacement / collage / validation ;
- scroll / swipe ;
- back / home / récents ;
- lancement d'app ;
- ouverture d'URL/intents ;
- attente d'un élément / stabilisation ;
- détection de reprise en main par l'utilisateur ;
- refus sur écran verrouillé ;
- indicateur visible d'automatisation ;
- STOP depuis UI, notification et tuile rapide.

### 3.7 Système

- alarmes ;
- minuteurs ;
- volume ;
- luminosité ;
- partage ;
- ouverture des réglages nécessaires ;
- permission handling et écran Santé.

### 3.8 Web et fichiers

- `web.fetch` ;
- `web.search` ;
- protections SSRF ;
- moteurs de recherche configurables ;
- accès fichiers limité à un dossier SAF choisi ;
- lister/lire/chercher/écrire/modifier/supprimer dans le périmètre autorisé.

### 3.9 Scheduler

- reminder ;
- once ;
- interval ;
- cron ;
- timezone ;
- rattrapage après redémarrage ;
- STOP : rappel passif autorisé, tâche autonome bloquée.

### 3.10 Sécurité

- SecretStore Android Keystore ;
- clés chiffrées ;
- redaction des secrets ;
- PolicyEngine L0-L3 ;
- classification UI FR/EN ;
- approbations liées au hash exact de l'action ;
- reclassification de la cible UI au moment de l'exécution ;
- audit chaîné ;
- taint tracking ;
- anti-overlay / FLAG_SECURE pour approbations ;
- kill switch ;
- idempotence des effets non répétables.

## 4. Modèle de données actuel à migrer, jamais recréer destructivement

Tables actuelles :

- sessions
- messages + messages_fts
- tasks
- steps
- tool_calls
- memories + memories_fts
- schedules
- audit
- providers
- model_caps
- usage
- settings

Database version actuelle : **1**.

Toute nouvelle version doit fournir :

- migration Room explicite ;
- test de migration ;
- conservation des données ;
- export de sauvegarde avant migration majeure ;
- rollback logique si possible ;
- pas de `fallbackToDestructiveMigration`.

## 5. Tests actuels à conserver

Les classes de test existantes doivent continuer à passer :

- `SseAndEmulationTest`
- `SchemaCronFastPathTest`
- `SecurityPrimitivesTest`
- `ProviderHttpTest`
- `OrchestratorEndToEndTest`
- `AccessibilityFixtureTest` (instrumenté)

Avant toute phase : lancer les tests applicables.
Après toute phase : relancer les tests + nouveaux tests.

## 6. Points actuellement simplifiés qui doivent être remplacés progressivement

Les éléments suivants ne doivent pas devenir des limitations structurelles permanentes :

- un seul gros `Orchestrator.kt` ;
- pas de Planner explicite ;
- pas de Verifier explicite ;
- pas de Replanner/Recovery global explicite ;
- tâche interrompue au redémarrage plutôt que reprise contrôlée ;
- recherche mémoire essentiellement FTS ;
- pas de mémoire procédurale/skills ;
- pas de coding workspace ;
- pas de shell/process executor ;
- pas de sandbox de build ;
- pas de Repository Intelligence ;
- pas de Software Factory ;
- pas de vision screenshot fallback ;
- pas de MCP ;
- pas d'interop agent standard ;
- pas de wake word ;
- pas de téléphonie/contacts/agenda/notification listener ;
- pas de document/data workbench avancé ;
- pas de worker node appairé ;
- pas de checkpoints durables de tâches longues ;
- pas de skill learning ;
- pas d'observabilité structurée complète.

Ces éléments sont l'objet du présent dossier.

## 7. Règle de migration structurelle

La migration peut introduire plusieurs modules Gradle et packages, mais elle doit rester **un seul produit Cortana avec un seul domaine métier**.

Un découpage en modules n'autorise pas plusieurs cerveaux concurrents. Le propriétaire canonique reste unique pour chaque responsabilité.
