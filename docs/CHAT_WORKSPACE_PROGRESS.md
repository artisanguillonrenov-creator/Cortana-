# Cortana Chat Workspace — avancement par chantier (H1 → H12)

Référence : `docs/chat_workspace_pack/` (paquet CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF), correspondance et décisions dans `docs/CHAT_WORKSPACE_MAPPING.md`, décision D-20260930-068.

Chaque chantier indique : objectif, fichiers, tests, résultat, risques, suite. Les tests cités ont été exécutés dans ce dépôt (JVM + Robolectric). Rien n'a été testé sur un appareil physique.

## H1 — Contrats + non-régression

**Objectif.** Poser les contrats du Workspace (parts de message, événements de flux, modes, préférences) et l'arbre de conversation (schéma v4) sans changer le comportement du chat existant.

**Fichiers.**
- `core/chat/ChatContracts.kt` : `ChatMode`, `AttachmentMode`, `AttachmentRef`, `MessageMeta`, `ChatSessionSettings`, `ChatPrefs`, `ChatStreamEvent` (runId + séquence), `Source`, `MessagePart`.
- `core/chat/Markdown.kt`, `core/chat/TexLite.kt` (+ `SafeLinks`) et `core/chat/ChatTimeline.kt` : adaptateurs ligne → parts, fil, variantes, sources (utilisés dès H4/H5).
- `core/memory/Entities.kt`, `RuntimeEntities.kt`, `ChatEntities.kt` (nouvelles tables), `Daos.kt` (CTE récursives chemin / descente), `CortanaDatabase.kt` (v4), `Migrations.kt` (`MIGRATION_3_4`), `schemas/4.json`.
- `core/memory/ConversationRepository.kt` : arbre (parent, feuille active), chemin actif, réparation des lignes non reliées, instantanés de flux, `branchMap`.
- `core/context/ContextEngine.kt` : l'historique envoyé au modèle est le **chemin actif** ; un résumé n'est réutilisé que sur une branche qui contient son dernier message couvert.
- `ui/chat/ChatViewModel.kt` : l'ancien écran lit le chemin actif (identique à l'ancien ordre pour une conversation linéaire).
- `core/backup/Backup.kt` : `projects`, `chat_drafts`, `chat_pins`, `context_checkpoints` sauvegardées ; `chat_queue` exclue (jamais d'envoi automatique sur une autre instance).
- `app/src/androidTest/assets/regex_patterns.json` régénéré (372 expressions compilées par ICU4C 74.2, 0 refus).

**Tests.**
- `ChatRenderLogicTest` (7) : blocs, code, tableaux, math, diagrammes, inlines, entrées malformées et énormes (4 000 lignes), TexLite, liens assainis, sources.
- `ChatTreeTest` (5) : ordre linéaire conservé, édition et régénération sans perte (variantes « 2/2 »), bascule de branche, message d'une autre conversation refusé, réparation des lignes anciennes, instantané de flux finalisé en place, STOP qui garde le texte, flux interrompu au redémarrage, suppression d'une conversation et de son état Workspace.
- `DatabaseMigrationTest` (+2) : v3 → v4 (chaînage par ordre historique, égalités de date départagées par l'ordre d'insertion, feuille active, colonnes par défaut, tables vides, FTS intact, Room ouvre la base et poursuit la branche) et v1 → v4 en une mise à jour.

**Résultat.** Suite complète : 375 tests, 372 réussis. Deux échecs corrigés ensuite : classement sauvegarde et actif regex. Le troisième, `ReleaseTest.thePublishedManifestIsAnUpgradeOf120SignedByTheReleaseKey`, est le garde-fou de publication. Le manifeste publié (rc3) annonce le schéma 3 alors que le code est en v4 ; il sera levé par la publication rc4, jamais en affaiblissant le test.

**Risques.** Une base restaurée d'une ancienne sauvegarde arrive sans parents ; `healUnlinked` la relie au premier accès. Le chemin est lu par CTE : coût proportionnel à la profondeur, fenêtre de 400 messages pour l'affichage.

**Suite.** H2 (disposition responsive), H3 (composer), H4 (rendu riche), puis H5/H6 côté services.

## H2 — Disposition responsive

**Objectif.** Un seul écran pour tous les usages : barre latérale, fil, panneau de contexte. La tablette est prioritaire.

**Fichiers.**
- `ui/workspace/WorkspaceScreen.kt` : classes de disposition (`layoutFor`) : large ≥ 1100 dp, moyenne 720–1100 dp, compacte < 720 dp.
  - Paysage large : trois colonnes (barre latérale repliable, conversation dominante, panneau ancré).
  - Portrait tablette : barre latérale repliable et panneau en surimpression.
  - Téléphone : barre latérale en tiroir, panneau en feuille du bas, composer fixe au-dessus du clavier (`imePadding`).
  - En-tête (doc 01 §1.5) : titre (renommer), état hors ligne, STOP, réponse en cours, mode, modèle, indicateur de contexte, menu. Mode développeur : fournisseur et modèle.
  - Barre latérale (doc 01 §1.6) : nouvelle discussion, incognito, filtre, recherche globale, épinglées, récentes, archivées. Chaque ligne affiche titre, aperçu, date, mode, réponse en cours et menu (renommer, épingler, archiver, supprimer avec confirmation).
  - Panneau (doc 01 §1.7) : seuls les onglets utiles apparaissent (Contexte, Fichiers, Sources, Branches, Activité, Mémoire, Inspecteur en mode développeur).
- `ui/workspace/WorkspaceTheme.kt` : jetons de surface, rayon, espacement et largeur de lecture ; densités compacte, confort et large ; thèmes système, clair, sombre et contraste élevé ; réduction des animations. Aucun composant n'écrit de couleur en dur ; un statut s'exprime toujours par une icône ou un texte, jamais par la couleur seule.
- `ui/AppNav.kt` : le Workspace est l'interface par défaut ; l'interface classique reste disponible sur les mêmes données (`ChatPrefs.ui`).
- `ui/settings/ChatWorkspaceSettings.kt` : réglages du doc 12.

**Tests.** `WorkspaceUiTest` sous Robolectric, écrans définis par qualificateurs :
- téléphone 400 dp : conversation dominante, barre latérale à la demande ;
- tablette paysage 1280 dp : barre latérale et panneau ancré, composer visible.

**Risques.** Largeurs de panneaux fixes par classe : repliables mais pas redimensionnables à la main ; à reprendre en H11. Pas encore essayé sur une vraie tablette (BLOCKED_EXTERNAL).

## H3 — Composer multimodal

**Objectif.** Simple au repos (`[+] texte [micro] [envoyer]`), puissant à la demande (doc 02).

**Fichiers.**
- `ui/workspace/Composer.kt` :
  - pièces jointes (sélecteur système, 10 au plus) avec mode lire / analyser / référence, taille, état et retrait ;
  - palette `/` (et Ctrl+K) ; mentions `@` (fichiers, discussions) ;
  - dictée par appui court, dont la transcription reste modifiable ; mains libres par appui long (permission micro demandée) ;
  - Entrée envoie, Maj+Entrée ajoute une ligne, Ctrl+Entrée envoie, Échap arrête ;
  - file visible : réordonner, retirer, « Maintenant » (interrompre et envoyer), « Envoyer » pour un message à revalider ;
  - estimation de contexte en mots, jamais en faux pourcentage.
- `core/chat/ChatService.kt` :
  - brouillon persistant par conversation (enregistré 500 ms après la frappe) ;
  - file d'attente : un message écrit pendant une réponse part dans l'ordre quand Cortana est libre ; un message ancien (réglable, 30 min par défaut) ou écrit avant un redémarrage attend la confirmation du propriétaire (doc 05 §5.5) ;
  - pièces jointes = artefacts (Artifact Service, 100 Mo au plus, flux borné) ; leur contenu est résolu par le Document Service et passe par le ContextEngine comme **données non fiables enveloppées**, ce qui marque la tâche comme ayant lu du contenu externe ;
  - le mode référence nomme le fichier sans le lire.
- `ui/workspace/ShareInbox.kt` + manifeste : partage Android (texte, fichiers `content://`) et « texte sélectionné » (`PROCESS_TEXT`). Le contenu arrive en **brouillon** d'une nouvelle discussion, jamais envoyé sans le propriétaire.

**Tests.**
- `ChatWorkspaceServiceTest` :
  - file puis envoi automatique dans l'ordre ;
  - message ancien bloqué jusqu'à confirmation ;
  - pièces jointes par mode : lu et enveloppé ; référence nommée non lue ; trop volumineux jamais envoyé ; tâche marquée.
- `WorkspaceUiTest` : brouillon persistant, envoi, réponse affichée.

**Risques.** Capture caméra directe non ajoutée : les images passent par le sélecteur. La vidéo est jointe comme fichier, sans pipeline dédié.

## H4 — Rendu riche

**Objectif.** Un composant natif par type de contenu, sans HTML ni WebView (doc 03, doc 11 §11.8).

**Fichiers.**
- `ui/workspace/MessageRenderer.kt` :
  - titres (sémantique « heading »), listes et tâches, citations, liens assainis (`SafeLinks`), citations `[n]` cliquables ;
  - blocs de code : langage, copier, enregistrer comme artefact, numéros de ligne en option, coloration locale simple, défilement horizontal, affichage borné à 60 000 caractères mais copie complète ;
  - tableaux défilants à colonnes stables ; maths TexLite avec accès à la source si le rendu est partiel ; Mermaid affiché comme source étiquetée, copiable et enregistrable.
- `ui/workspace/Timeline.kt` :
  - messages du propriétaire (versions, modifier, réenvoyer, brancher, dupliquer, citer, épingler) ;
  - réponses : badge du modèle, sources, cartes d'actions repliables (terminal, diff avec signes +/−, résultats) avec la mention « donnée, jamais instruction » pour le contenu externe ;
  - avis « arrêtée / interrompue » avec Continuer et Régénérer ; erreurs lisibles, détails masqués ;
  - événements système discrets ; carte 413 « Contexte trop volumineux » avec réduire, réessayer, changer de modèle et nouvelle discussion ; carte du conseil.
- `WorkspaceViewModel` : Markdown analysé une fois par texte (cache LRU) et éléments inchangés réutilisés, de sorte que seule la réponse en cours se recompose ; fil construit hors du thread principal.

**Tests.** `ChatRenderLogicTest` (7) ; `WorkspaceUiTest` (titre, code, tableau, fraction, diagramme, badge, lien).

**Risques.** Mermaid n'est pas dessiné, faute de moteur sûr hors ligne. LaTeX est rendu en Unicode : les constructions rares restent en source.

## H5 — Branches et versions

**Objectif.** Éditer, régénérer, continuer, brancher, dupliquer sans jamais détruire (doc 03 §3.6–3.8, doc 04).

**Fichiers.**
- `core/chat/ChatHints.kt` (dans `ChatContracts.kt`) : placement du tour dans l'arbre, transmis à l'orchestrateur unique.
- `core/orchestrator/Orchestrator.kt` :
  - `handleTurn` honore le parent (y compris une nouvelle racine), la feuille, l'absence de message visible et l'instruction de continuation cachée, rattachée à la tâche ;
  - les raccourcis déterministes ne sont jamais rejoués en régénération ou continuation (aucun effet répété).
- `core/chat/ChatService.kt` : `edit`, `regenerate`, `continueAnswer`, `switchTo`, `branchFrom`, `fork`.
- `ui/workspace` : navigateurs « Version 1/2 » et « Réponse 2/3 », onglet Branches (toutes les fins de branche, l'active marquée).

**Tests.**
- `ChatWorkspaceServiceTest` :
  - régénérer : la question n'est pas dupliquée et une variante ne voit jamais sa sœur ;
  - éditer : l'ancienne version n'est pas envoyée, « Version 2/2 », rien n'est supprimé ;
  - continuer : l'instruction cachée est envoyée, le texte fusionné et l'avis d'arrêt retiré de la branche active.
- `ChatTreeTest`.
- `WorkspaceUiTest` : navigation entre variantes.

## H6 — Flux reprenable

**Objectif.** runId, séquence, reprise, STOP qui garde le texte (doc 05).

**Fichiers.**
- `core/chat/ChatStreamHub.kt` :
  - chaque génération a un `runId` ; chaque événement une séquence strictement croissante, numérotée et émise sous le même verrou ;
  - texte vivant regroupé toutes les 60 ms (jamais un rendu par jeton) ; dernier lot publié à la fermeture ;
  - la ligne de réponse existe dès le premier mot (statut `streaming`), puis ses instantanés sont limités à un toutes les 900 ms ;
  - `abort` écrit ce qui a été reçu et marque la ligne `stopped` (STOP) ou `interrupted` (échec, délai, redémarrage) ;
  - `StreamCursor` : un écran qui se reconnecte n'applique rien deux fois.
- `core/orchestrator/StepRunner.kt` :
  - l'identifiant de ligne est ouvert avant chaque appel visible et la ligne finalisée sur place ;
  - modèle et raison de fin en métadonnées ;
  - événements d'outils ;
  - réduction automatique unique sur 413 (fenêtre divisée par deux), puis événement structuré `context_too_large` : jamais de renvoi aveugle.
- `core/model/ModelGateway.kt` : `finishReason`.
- Démarrage : `interruptDanglingStreams` garde les réponses coupées par l'arrêt du processus.
- Multi-appareil : sans objet (pas de synchronisation, L-1). Les deux interfaces lisent la même base.

**Tests.**
- `ChatWorkspaceServiceTest` :
  - STOP pendant un flux lent : texte partiel gardé, statut `stopped`, tâche annulée ;
  - séquences strictement croissantes, dernier delta = texte final, curseur sans doublon ;
  - 413 : exactement deux requêtes puis la carte.
- `ChatTreeTest` : instantané finalisé en place, flux interrompu au démarrage.

**Risques.** Les API compatibles OpenAI ne reprennent pas un flux au milieu. Après une coupure du fournisseur, la réponse partielle est gardée et l'on propose Continuer (nouvelle demande explicite), conformément à la décision 2 de la correspondance.

## H7 — Contexte et mémoire visibles

**Objectif.** Montrer ce que Cortana a réellement en tête ; épingler, compacter, inspecter ; mémoire par discussion (doc 06).

**Fichiers.**
- `core/context/ContextEngine.kt` :
  - éléments épinglés (message, fichier, note) gardés à 15 % au plus du budget ;
  - instructions du projet héritées, sauf si la discussion les ignore ;
  - mémoire désactivable pour une discussion, ou souvenir par souvenir ;
  - « Réduire le contexte » (`compactedUntil`) : ce qui précède la coupure n'est envoyé qu'en résumé ;
  - `compactNow` produit un résumé extractif, sans appel de modèle ;
  - point de compactage écrit à chaque nouveau résumé (20 conservés) ;
  - dernier contexte publié par discussion (`ContextSnapshot` : rapport, souvenirs, épingles, projet ; développeur : jetons par section, réserve de sortie, définitions d'outils).
- `ui/workspace` :
  - jauge en mots (faible, moyen, élevé, compactage proche), jamais de faux pourcentage ;
  - onglet Contexte : ce qui est actif, hérité ou réduit (annulable), épingles et notes, compactages avec résumé, « Dupliquer depuis ce point », « Résumer maintenant » ;
  - ligne discrète « Contexte compacté » dans le fil ;
  - onglet Mémoire : type, source, date, confiance, « Ne pas utiliser ici », « Proposer une correction » (brouillon, les écritures restent au service mémoire).

**Tests.** `ChatWorkspaceFeaturesTest.h7PinsNotesProjectInstructionsAndMemorySwitchShapeTheRealRequest` et `h7CompactNowSummarisesOlderTurnsKeepsTheLastOnesAndRecordsACheckpoint` (requêtes réelles inspectées).

**Risques.** Le rapport de contexte est une estimation de jetons (≈ 3,2 caractères par jeton).

## H8 — Artefacts, approbations, activité

**Objectif.** Artefacts durables à côté du fil, approbations sûres, progression stable (doc 07, doc 09).

**Fichiers.**
- Onglet Artefacts : pièces jointes, productions des tâches et réponses enregistrées de la discussion (`observeArtifactsForSession`).
  - Aperçu ; **Modifier** crée une nouvelle version liée à la précédente, jamais écrasée.
  - Versions précédente / suivante ; **Comparer** : diff par lignes, avec la bibliothèque JGit déjà utilisée par les documents (`TextDiff`).
  - Exporter, Épingler, Message source.
- Barre d'activité au-dessus du composer : état, nombre d'actions, pilotage de l'écran, étapes à la demande, Détails, Arrêter ; aucun raisonnement affiché.
- Carte d'autorisation dans la conversation :
  - elle montre l'action, la cible, le risque, le caractère réversible et le contenu externe lu ;
  - **Refuser** agit tout de suite ;
  - **Examiner et autoriser** rouvre l'écran sécurisé (`ApprovalBroker.reopen`).
- Cartes d'outils (H4) ; onglet Activité (étapes, actions de la discussion).

**Tests.**
- `ChatWorkspaceFeaturesTest.h8ArtifactsOfAConversationAreListedAndVersionsDiffByLine` ;
- `ArchitectureRulesTest.workspaceNeverApprovesAnActionItself`.

**Risques.** Aperçu textuel seulement (PDF et Office par le Document Service) ; pas d'éditeur riche.

## H9 — Comparaison, sélecteur de modèle, pont vers le conseil

**Objectif.** Voir plusieurs réponses (comparaison) ≠ faire délibérer plusieurs agents (conseil) (doc 08).

**Fichiers.**
- `core/orchestrator/CompareRunner.kt` : 2 à 4 modèles en parallèle, sous l'orchestrateur (une tâche, un budget, un STOP) ; sans outils ; réponses sœurs du message ; couloir en échec marqué ; arrêt d'un seul couloir (texte gardé) ; la discussion suit la première réponse.
- `ChatService.mergeCompared` : « Fusionner » ou « Faire comparer par Cortana » est une demande visible ; les réponses sont des données enveloppées ; la majorité n'est jamais une preuve.
- Sélecteur de modèle : recherche, récents, favoris ★, choix par discussion, événement « Modèle changé », case « Comparer plusieurs modèles » (2 à 4).
- Interface : colonnes sur grand écran, onglets sur téléphone ; par couloir : suivre, copier, enregistrer, arrêter.
- Conseil : le mode « Conseil » passe `explicitRequest` au sélecteur (conseil même pour une demande courte). Désactivé → avis discret et réponse habituelle ; jamais de contournement de `enabled=false`, ni d'un conseil imbriqué.
- Modes Recherche, Développement, Agent, Voix : une note interne courte, jamais un changement de politique.

**Tests.**
- `ChatWorkspaceFeaturesTest.h9TwoModelsAnswerSideBySideWithoutToolsAndOneFailureDoesNotSinkTheOthers` : 3 modèles dont un en panne, aucune définition d'outil envoyée, fusion vérifiée.
- `h9FourModelsAndOneLaneStoppedAloneKeepsItsText`.
- `h9CouncilBridgeHonoursAnExplicitRequestButNeverTheDisabledSwitch`.
- `CouncilTest` et `CouncilLogicTest` toujours verts.

## H10 — Recherche, organisation, export

**Fichiers.**
- `ChatService.search` :
  - titres, messages (FTS) et fichiers ou artefacts par nom ;
  - filtres Vous / Cortana / épinglées / avec fichier / projet ;
  - un résultat ouvre la discussion sur le message, en basculant sur sa branche si besoin.
- Projets : barre latérale avec filtre, instructions héritées, déplacement, suppression sans perte de discussion. Étiquettes légères (`#tag` dans le filtre). Épingler et archiver.
- Export Markdown, JSON ou texte : branche visible, sans message caché ni sortie brute d'outil, secrets masqués ; enregistré en artefact puis dans Téléchargements/Cortana.
- Import du JSON Cortana : nouvelle discussion d'une seule branche, marquée « (importée) ».

**Tests.**
- `ChatWorkspaceFeaturesTest.h10ExportKeepsTheVisibleBranchOnlyAndImportRoundTrips` ;
- `h10SearchFindsMessagesAndTitlesWithFiltersAndJumps`.

## H11 — Voix, accessibilité, finitions

**Fichiers.**
- `core/voice/VoiceLoop.interrupt` : interruption au toucher, comme la coupure par la voix.
- **Correctif trouvé par le test** : un micro de coupure impossible à ouvrir (permission retirée, micro occupé) empêchait Cortana de parler en mains libres, alors que l'état affichait « parle ». La réponse est désormais prononcée quand même, et `SpeechDetector` peut retenter l'ouverture.
- `util/Speaker` : lecture par phrases, pause et reprise à la phrase interrompue, vitesse réglable. Android TTS n'a pas de pause native.
- Panneau vocal compact (écoute, transcription, réponse, Interrompre, Revenir au texte, STOP) et barre de lecture.
- Images : miniature, zoom par pincement, Analyser, Enregistrer.
- Clavier : Ctrl+N, Ctrl+F, Ctrl+B (barre latérale), Ctrl+I (panneau), Ctrl+K (palette), Échap (arrêter), Entrée / Maj+Entrée / Ctrl+Entrée.
- Colonnes redimensionnables sur grand écran (poignées nommées pour l'accessibilité).
- Annonces pour le lecteur d'écran : « Cortana répond… », « Réponse terminée. », jamais chaque mot. Titres « Vous » et « Cortana ». Tailles et contraste suivent les réglages.

**Tests.**
- `VoiceTest.touchInterruptSilencesCortanaAndListensAgain` : régression du micro indisponible incluse.
- `WorkspaceUiTest.h11KeyboardShortcutsToggleTheSidebarAndStartANewConversation` et `a11yLargeTextAndHighContrastKeepEveryControl`.
- `ChatRenderLogicTest.h11ReadingIsSplitIntoSentencesSoItCanPauseAndResume`.

**Risques.** TalkBack réel, dynamic type extrême et barge-in acoustique : sur l'appareil (BLOCKED_EXTERNAL).

## H12 — Durcissement

**Fichiers.**
- `ModelGateway.complete(onReset)` : quand un fournisseur tombe après avoir déjà diffusé du texte et qu'un autre prend le relais, le texte vivant repart de zéro. Il n'est jamais dupliqué ; la ligne finale ne garde que la réponse retenue.
- Lois `ArchitectureRulesTest` :
  - WORKSPACE-1 : pas de second backend ;
  - WORKSPACE-2 : aucune approbation depuis le chat ;
  - WORKSPACE-3 : ni HTML, ni raisonnement, ni ligne cachée ;
  - comparaison sans outil, partage sans envoi.
- Documentation : `DECISIONS.md` (D-20260930-068), `DATA_MIGRATIONS.md` (v4), `TEST_MATRIX.md` (§14 septies), `SECURITY.md`, `ARCHITECTURE.md`, `RC_CHECKLIST.md` (§15).

**Tests.** `ChatHardeningTest` :
- coupure réseau en plein flux : texte gardé, « interrompue », aucun renvoi seul ;
- repli après flux partiel sans doublon ;
- événements dupliqués ou désordonnés appliqués une fois ;
- noms de fichiers hostiles confinés ; fichier illisible nommé, pas injecté ;
- 5 000 messages et 100 conversations, mesures ci-dessous.

| Mesure (machine de construction, JVM + Robolectric) | Temps |
|---|---|
| Fenêtre de 400 messages sur 5 000 | 56 ms |
| Carte des branches | 37 ms |
| Fil complet avec Markdown (401 lignes) | 59 ms (28 ms avec le cache) |
| Aperçus de 101 conversations | 62 ms |
| Recherche plein texte | 47 ms |
| Contexte envoyé au modèle (branche de 5 000 messages) | 173 ms, fenêtre budgétée |

**Compléments du pack (doc 03 §3.4, doc 02 §2.2, doc 09 §9.7).**
- « Supprimer ce message et la suite… » : confirmation obligatoire, le message et tout ce qui le suit sur toutes ses branches, le pointeur de branche va sur la branche survivante, les épingles et l'index de recherche suivent (`ConversationRepository.deleteFrom`, CTE récursive bornée). Une modification n'utilise jamais ce chemin : elle ajoute une version.
- « Convertir en tâche » (brouillon relu avant envoi, la planification passe par le planificateur et ses confirmations), « Enregistrer dans Téléchargements », « Comparer avec d'autres modèles… » depuis une réponse.
- Onglet « Outils » du panneau : outils du jeu de la discussion par catégorie et risque ; les permissions restent dans Capacités.
- Composer : « Coller une image ou un fichier copié » (URI `content://` du presse-papiers, sinon le texte copié).
- Tests : `ChatWorkspaceFeaturesTest.h5DeleteRemovesTheMessageAndWhatFollowsOnEveryBranchAndMovesTheLeafAway` et `WorkspaceUiTest.h5DeletingAMessageAsksFirstAndThenRemovesItAndWhatFollows` (annuler ne supprime rien).

**Porte complète après H12** : 410 tests JVM, 1 échec attendu (`ReleaseTest…SignedByTheReleaseKey` : le manifeste publié est encore celui de rc3, schéma 3), lint 0 erreur / 61 avertissements, APK de test instrumenté assemblé. Avec les compléments : 412 tests.

**Porte de la rc4.**
- Manifeste rc4 publié : `ReleaseTest` vert.
- `SupplyChainTest` a signalé une fausse clé `sk-…` en clair dans un nouveau test (fichier devenu suivi par git au commit) : clé construite à l'exécution, garde verte.
- Une exécution complète (avec `--rerun-tasks`) est restée bloquée jusqu'à la limite de 50 min dans une classe de test postérieure à `DocumentTest` (ordre alphabétique), sans trace exploitable. Non reproduit ensuite : 5 exécutions complètes, dont 3 sous charge CPU (2 cœurs saturés sur 4) et la porte finale, toutes vertes (412/412). Cause **inconnue** ; mesure prise : chien de garde dans `CortanaTestBase` (piles de tous les fils sur stderr si un test dépasse 120 s, le plus lent prend ~16 s), vérifié par un test sonde jetable.
- Porte finale : 412 tests, 0 échec (app 399, worker 8, contrats 5), lint 0 erreur / 61 avertissements, APK de test instrumenté assemblé, chien de garde jamais déclenché.

**Non exécutable ici (BLOCKED_EXTERNAL).** Vraie tablette, réseau réellement instable, p95 de rendu et fuites mémoire sur l'appareil, TalkBack réel, batterie. Voir `RC_CHECKLIST.md` §15.
