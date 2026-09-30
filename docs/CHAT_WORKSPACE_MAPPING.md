# Cortana Chat Workspace — correspondance avec l'existant (avant tout code)

Source : `docs/chat_workspace_pack/` (19 fichiers de `CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF`, empreintes vérifiées avec `MANIFEST.json`). Règle directrice du paquet : le Workspace est **une couche de présentation et de contrats UI**. Il consomme les services existants et n'en duplique aucun.

## 1. Services existants réutilisés (aucun doublon)

| Exigence du paquet | Existant | Usage par le Workspace |
|---|---|---|
| ConversationService | `core/memory/ConversationRepository` (sessions, messages, FTS, résumé) | Seul écrivain des messages ; étendu : arbre de messages (parent, feuille active), statuts, métadonnées, brouillons, file, épingles, projets |
| TaskOrchestrator | `core/orchestrator/Orchestrator` + `StepRunner` | Seul lanceur de génération : tour normal, régénération, continuation, comparaison, fusion et conseil passent par `submitRequest` (un seul STOP, un seul budget, une seule trace) |
| ModelGateway | `core/model/ModelGateway` | Tous les appels de modèle, y compris la comparaison (2 à 4 routes) et la fusion |
| ContextEngine | `core/context/ContextEngine` | Construit le contexte sur le **chemin actif** de l'arbre ; ajoute épingles et instructions de projet ; publie le dernier rapport (compteur de contexte) ; enregistre les points de compactage |
| MemoryService | `core/memory/MemoryRepository` | Onglet Mémoire en lecture ; désactivation par conversation lue par le ContextEngine ; écritures seulement par le service mémoire |
| ToolRegistry / PolicyEngine / ApprovalBroker | `core/tools`, `core/policy` | Cartes d'outils et d'approbation construites à partir des messages `tool` et des tables `tool_calls` et `approvals`. L'autorisation reste dans l'écran sécurisé `ApprovalActivity` (voir §3) |
| ArtifactService | `core/dev/ArtifactService` | Panneau Artefacts, versions (métadonnée `previousVersionId`), pièces jointes (artefacts de type `attachment`), export |
| Documents / Médias | `executors/documents`, `core/media` | Lecture des pièces jointes (`artifact:<id>`), analyse d'image |
| VoiceService | `core/voice` (`VoiceLoop`, `Speaker`), `ui/voice/VoiceUi.kt` | Dictée, session mains libres, lecture d'une réponse |
| Cognitive Council | `core/council` (`CouncilGate`, `CouncilProgress`, carte) | Bouton « Conseil » = demande explicite transmise à `runCouncil` |
| Recherche | FTS4 `messages_fts` (`ConversationRepository.search`) | Recherche globale avec filtres et saut au message |
| Observabilité | `Tracer`, `task_events`, `tool_calls` | Rail d'activité, inspecteur (développeur) |

## 2. Lacunes et ce qui est ajouté

| Lacune constatée | Ajout |
|---|---|
| Conversation strictement linéaire (`messages` triés par date) : impossible d'éditer sans détruire, de garder des variantes ou de brancher | Arbre : `messages.parentId`, `sessions.activeLeafId` ; chemin actif lu par CTE récursive ; migration qui chaîne l'historique existant |
| Le texte en cours de génération ne vit qu'en mémoire (`ActiveTaskState.streamingText`) : perdu sur STOP, arrêt du processus ou changement d'écran | `ChatStreamHub` : événements numérotés (`runId`, `sequence`), déduplication, reprise, ligne de message `streaming` écrite périodiquement, puis `complete`, `stopped` ou `interrupted` |
| Pas de pièces jointes dans le chat (`TaskRequest.attachments` défini mais inutilisé) | Pièces jointes en artefacts, trois modes (lire, analyser, référence) transmis au ContextEngine |
| Pas de brouillon persistant ni de file d'attente | Tables `chat_drafts`, `chat_queue` ; file relancée après la tâche, revalidée si elle est ancienne |
| Rendu texte brut | Analyseur Markdown maison (GFM, tableaux, tâches, code, math, Mermaid en source), liens assainis, aucun HTML |
| Pas de projets, d'épingles, de tags, d'archives | Tables `projects`, `chat_pins` ; colonnes `sessions.projectId`, `pinned`, `archived`, `tagsJson`, `mode`, `settingsJson` |
| Compactage invisible | Table `context_checkpoints`, événement système « contexte compacté », inspecteur, validité du résumé liée au chemin |
| Pas de comparaison multi-modèle | `CompareRunner` sous l'orchestrateur : réponses sœurs d'un même message, fusion non majoritaire |

## 3. Conflits et décisions

1. **Approbations dans le fil.** Le paquet demande une `ApprovalCard` avec Autoriser/Refuser. L'écran d'approbation actuel est une activité `FLAG_SECURE`, protégée contre les superpositions et liée à l'action exacte. La carte du fil montre donc l'action, la cible, les arguments, le risque et la durée de validité. Elle propose **Refuser** directement (sans risque) et **Examiner et autoriser**, qui ouvre l'écran sécurisé. Aucun composant du chat n'autorise lui-même une action L2 ou L3 (interdiction du paquet).
2. **« Reprise réseau » d'un flux.** Les API OpenAI-compatibles ne savent pas reprendre une génération à un octet donné. La reprise porte sur la liaison entre l'interface et la génération en cours dans l'application (écran recréé, conversation rouverte) : relecture depuis la dernière séquence reçue, déduplication, ordre. Si le fournisseur coupe, la réponse partielle est conservée (`interrupted`) avec **Continuer** et **Régénérer**. Aucun renvoi aveugle : une continuation est une nouvelle demande explicite. Les actions à effet restent protégées par le registre d'idempotence.
3. **Multi-onglet et multi-appareil.** Il n'y a pas de synchronisation serveur (L-1 : tout sur la tablette). Le paquet le conditionne (« si backend sync le supporte ») : sans objet. La vue est une projection de Room, cohérente entre écrans et après redémarrage.
4. **Mermaid.** Aucun moteur de rendu sûr hors ligne n'est embarqué : le bloc est affiché comme source étiquetée « Diagramme Mermaid », exportable en artefact (« si safe renderer », §3.2). LaTeX : rendu Unicode des constructions courantes (fractions, indices, exposants, grec, opérateurs) ; le reste en source.
5. **Schéma.** v3 est figé depuis la rc3. Toute nouvelle colonne ou table passe par v4 et `MIGRATION_3_4`, additive et testée (3→4, 1→4).
6. **Ancienne interface.** Elle reste disponible (Réglages › Discussion › « Interface classique ») sur les mêmes données. La nouvelle est l'interface par défaut.
7. **Pièces des messages.** Le paquet propose une table `message_parts`. Les parts sont dérivées de façon déterministe du texte, des appels d'outils et d'une colonne `metaJson` (pièces jointes, modèle, groupe de comparaison, continuation) par l'adaptateur `MessageParts`. Une seconde copie du contenu serait source de divergence.
8. **Branches.** Le paquet propose une table `branches`. Les branches sont les chemins de l'arbre ; leur libellé est calculé (date, première différence).

## 4. Modèle de données v4 (additif)

- `messages` :
  - `parentId` (index) ;
  - `status` (`complete`, `streaming`, `stopped`, `interrupted`, `error` ; défaut `complete`) ;
  - `runId` ;
  - `metaJson`.
- `sessions` :
  - `activeLeafId` ;
  - `projectId` ;
  - `mode` (défaut `chat`) ;
  - `pinned` ;
  - `archived` ;
  - `tagsJson` ;
  - `settingsJson`.
- `conversation_summaries` : `coveredUntilMessageId`.
- Nouvelles tables :
  - `projects` ;
  - `chat_drafts` ;
  - `chat_queue` ;
  - `chat_pins` ;
  - `context_checkpoints`.
- Remplissage : chaque message existant reçoit pour parent le précédent de sa session (ordre `createdAt`, `rowid`), et chaque session sa feuille active. Un contrôle au chargement relie aussi les messages restaurés d'une ancienne sauvegarde.

## 5. Plan de fichiers

- `core/chat/ChatContracts.kt` : parts, événements de flux, modes, préférences (H1).
- `core/chat/MessageParts.kt` : adaptateur ligne → parts ; sources et citations (H1, H4).
- `core/chat/Markdown.kt`, `core/chat/TexLite.kt`, `core/chat/SafeLinks.kt` (H4).
- `core/chat/ChatTimeline.kt` : chemin → éléments de fil, variantes, regroupements (H4, H5).
- `core/chat/ChatStreamHub.kt` (H6).
- `core/chat/ChatService.kt` : envoi, édition, régénération, continuation, fork, file, brouillons, épingles, projets, export et import. C'est une façade sur `ConversationRepository` et `Orchestrator`, pas un second orchestrateur (H3, H5, H10).
- `core/orchestrator/CompareRunner.kt` (H9).
- `core/memory/ChatEntities.kt` + migration 3→4 (H1, H5).
- `ui/workspace/` :
  - `WorkspaceScreen.kt` (disposition) ;
  - `Sidebar.kt` ;
  - `Header.kt` ;
  - `ContextPanel.kt` ;
  - `Composer.kt` ;
  - `Timeline.kt` ;
  - `MessageRenderer.kt` ;
  - `ActivityRail.kt` ;
  - `ComparePane.kt` ;
  - `ModelSelector.kt` ;
  - `SearchScreen.kt` ;
  - `WorkspaceViewModel.kt` ;
  - `WorkspaceTheme.kt` (jetons, densité, contraste élevé).
- Modifiés :
  - `Orchestrator` (types de demande, conseil forcé, comparaison, persistance du flux, STOP qui conserve le texte) ;
  - `StepRunner` (identifiant du message en flux) ;
  - `ContextEngine` (chemin, épingles, projet, rapport, points de compactage) ;
  - `ConversationRepository` ;
  - `AppNav` ;
  - `SettingsRepository` (`ChatPrefs`) ;
  - `Backup` (tables) ;
  - `ArchitectureRulesTest` (lois du Workspace).

## 6. Lois ajoutées à `ArchitectureRulesTest`

- `ui/workspace` et `core/chat` n'appellent ni `ModelGateway.complete`, ni `ToolDispatcher`, ni `ApprovalBroker.resolve` avec une décision positive, ni un DAO.
- Aucun rendu de `reasoningJson` ni de message caché.
- Aucun `WebView` ni HTML brut dans le rendu des messages.

## 7. Tests

Contrats, analyseur, fil, arbre, flux et services en JVM ou Robolectric. Interface en Compose sous Robolectric (`ui-test-junit4` déjà dans le cache hors ligne) : téléphone et tablette, champ de saisie, file, sémantique d'accessibilité. Performance : 5 000 messages et 100 conversations. Hors de portée ici (**BLOCKED_EXTERNAL**) : vraie tablette, réseau réellement instable, batterie, TalkBack réel, fuite mémoire sur l'appareil.
