# Design « Cortana Workspace » : rapports d'étapes

Source : `docs/design_handoff_cortana_workspace/` (README, prototype, tokens, icônes, prompt en 7 étapes). Analyse et décisions : `DESIGN_WORKSPACE_ANALYSIS.md`.

## Étape 1 — Thème (commit `851ad0f`)

**Objectif.** Tokens, polices, `CortanaTheme {}`.

**Fichiers.**
- `ui/theme/CortanaTokens.kt` : palette du design (valeurs du README et du prototype), typographie, formes, dimensions, mouvement.
- `ui/theme/Theme.kt` : `CortanaTheme` (couleurs du design pour toute l'app, typographie Geist, réduction des animations de l'app ou du système, mode développeur).
- `ui/theme/Symbols.kt` : index des 129 icônes Material Symbols (`res/drawable/ms_*.xml`, générées par `tools/import_material_symbols.py`).
- `res/font` : Geist, Geist Mono, Sora (instances statiques, OFL) ; licences dans `assets/licenses`, `docs/LICENSES.md`.

**Risques.** Les écrans non redessinés prennent les couleurs du design par le schéma Material (sombre) ; le thème clair de l'espace de discussion rc4 n'a pas d'équivalent dans le design.

## Étape 2 — Composants

**Objectif.** Les 23 composants de `ui/components`, avec une `@Preview` par état.

**Fichiers** (`ui/components/`) :
- `Primitives.kt` : icône, lueurs CSS (`box-shadow`), anneau Cortana, animations respectant la réduction des mouvements, zone tactile.
- `Controls.kt` : Toggle (3 tailles), HeaderPill (modèle, Recherche, Voix, Tâche), ToolChip, FilterChip, DropChip, SegmentedControl (en-tête, réglages), boutons, StopButton (grand, panneau, rail, carte ; STOP, Reprendre, neutre), StatusChip, ProgressBar, StreamingDots.
- `Thread.kt` : UserBubble, QueuedBubble, message de Cortana, PlanCard et StepRow, CodeBlock (coloration Kotlin du prototype, Copier, Agrandir), ApprovalCard (en attente, autorisée, refusée), chips d'artefacts.
- `Panel.kt` : LogConsole, ContextTabs, TaskSummary, ActionTile, InfoCard (Worker, MCP, Mémoire, Politique), ActivityRail, FileTree.
- `Lists.kt` : marque, NavItem, DevModeCard, ModelMenu, ConversationRow, MemoryCard, SettingsRow (interrupteur, segmenté, valeur), SettingsNavItem, lignes et cartes génériques, journal d'actions, recherche, mini-arbre des branches, jauge de contexte.
- `CortanaComposer.kt` : composer (normal, voix ; Entrée, Maj+Entrée, Échap).
- `ComponentPreviews.kt` (43 aperçus), `DemoData.kt` (données du prototype, aperçus uniquement), `UiModels.kt` (`RunStatus`, `Approval`, `LogLine`, `TaskRunUi`).

**Tests.** `DesignGalleryTest` rend chaque aperçu en PNG sous Robolectric (graphismes natifs, densité 1 : 1 px = 1 dp) dans `app/build/design-shots/components`.
- Comparaison à l'œil avec le prototype rendu dans Chromium (polices locales).
- Deux corrections : la lueur de la barre active du menu débordait sur tout l'élément ; les zones tactiles agrandissaient la mise en page.

**Défaut trouvé par ces tests et corrigé.** La palette, déclarée avec environ 200 couleurs en paramètres de constructeur, dépassait la limite JVM de 255 emplacements par signature : `ClassFormatError` au chargement, donc plantage au lancement de l'app. Elle est désormais une classe à propriétés, avec « Contraste élevé » en sous-classe ; test de non-régression `palettesLoadAndContrastOnlyStrengthensSecondaryTexts`.

**Accessibilité.** Toute cible fait au moins 48 dp.
- La zone tactile déborde du visuel (`TouchTarget` / `touchArea`) au lieu d'agrandir la mise en page : le visuel garde la taille du prototype.
- L'étiquette et le rôle portent sur la zone tactile.

**Risques.** Lueurs, dégradés et flous sont dessinés avec `BlurMaskFilter` : rendu vérifié sous Robolectric, pas encore sur la tablette.

## Étapes 3 et 4 — Discussion et machine d'état de la tâche

**Objectif.** L'écran Discussion du prototype (3 colonnes en paysage) sur les vraies données, et la tâche qui passe de « En cours » à « Approbation », « En pause » puis « Terminée », avec STOP partout.

**Fichiers (interface, `ui/cortana/`).**
- `CortanaShell.kt` : coque de l'app (fond et halo, barre latérale repliable, tiroir en portrait, mode développeur, grand STOP) et règles de taille (≥ 1400 dp, 1200–1400 dp, portrait ou < 1200 dp).
- `DiscussionScreen.kt` : en-tête, rail d'activité en portrait, fil, puces d'outils, composer, panneau « Tâche active » en 3ᵉ colonne ou par-dessus la conversation, menu ⋮ du fil.
- `DesignTimeline.kt` : le fil au style du design. Toutes les fonctions rc4 restent accessibles depuis les menus des messages : variantes, modification, branches, continuer, comparaison, file d'attente.
- `DiscussionRoute.kt` : relie l'écran aux services existants (`WorkspaceViewModel`, orchestrateur, approbations, voix), sans second backend.
- `TaskPanelViewModel.kt` et `TaskRunProjection.kt` : panneau projeté depuis les enregistrements durables (tâche, plan, appels d'outils, transitions, approbations). Le temps écoulé réel remplace l'estimation « ~ 2-3 min restantes ».
- `DesignPreviews.kt` : l'état initial du prototype (étape 3/6) pour les aperçus.
- `ui/AppNav.kt` : la coque du design devient la navigation par défaut ; nouvel écran « MCP » ; Procédures et Capacités restent accessibles depuis Réglages (pas d'entrée dans la barre latérale du design).

**Fichiers (runtime).**
- `Orchestrator.pause()` / `resumePaused()` : STOP suspend une tâche planifiée (état PAUSED, plan conservé), « Reprendre » la continue sans refaire les étapes terminées. Une tâche sans plan est annulée.
- `ToolDispatcher` : une approbation interrompue par STOP est enregistrée « cancelled », jamais laissée « pending » ; la décision est enregistrée même si STOP arrive au même instant.
- `StepRunner` : à la reprise, le modèle apprend que l'action attendait l'accord et n'a **pas** été exécutée (au lieu de « aucun résultat »). S'il la redemande, l'accord est de nouveau requis.
- `ToolFamilies` : les six puces (Outils, Système, Fichiers, Terminal, Git, Navigateur) suivent le jeu d'outils de la conversation. Tout éteindre repasse en mode direct.
- `ChatPrefs.ui` : "cortana" par défaut. L'ancienne valeur "workspace" (le défaut rc4, jamais choisi) ouvre le design ; un choix explicite dans Réglages › Espace de discussion › Interface est respecté (`uiChosen`).

**Machine d'état (vérifiée par les tests).**

| Depuis | Action | Résultat |
|---|---|---|
| En cours | STOP (barre latérale, panneau, rail, Échap) | En pause, plan conservé ; « Reprendre » partout |
| En pause | Reprendre | En cours, même tâche, étapes faites gardées |
| En cours | l'outil demande l'accord | carte « Approbation requise » dans le fil, statut « Approbation » |
| Approbation | Autoriser une fois | ouvre l'écran sécurisé, seul à accorder (loi WORKSPACE-2) ; puis En cours → Terminée, pastille « Autorisation accordée une fois » |
| Approbation | Refuser | En pause, pastille « Action refusée », souvenir intact |
| En pause (après refus) | Reprendre | nouvelle demande d'approbation (2ᵉ enregistrement) |
| En cours | appui long sur le grand STOP | arrêt d'urgence (tâche HALTED) ; « Reprendre » demande l'empreinte ou le code |

**Tests.**
- `DesignDiscussionUiTest` (6, sur l'app réelle : AppNav, coque, runtime, serveur de modèle simulé) : approbation accordée seulement par l'écran sécurisé ; refus puis reprise depuis le panneau ; STOP et Reprendre depuis la barre latérale ; rail et Échap en portrait ; arrêt d'urgence ; mode développeur (Développement, Worker, MCP masqués puis visibles).
- `DesignLogicTest` (4) : puces d'outils, interface choisie, tailles de fichiers.
- `TaskPauseTest` (2), `TaskRunProjectionTest` (3), `DesignScreensTest` (3 aperçus d'écran).
- Captures de l'écran réel à densité 1 pendant ces tests : `app/build/design-shots/live` (approbation, refus en pause, terminé, pause en portrait).

**Défauts trouvés et corrigés.**
- Ouverture d'une conversation depuis une notification (ou un partage) au démarrage : la navigation partait avant que le graphe soit prêt (`IllegalArgumentException`). Elle attend maintenant le graphe. Le défaut existait déjà en rc4.
- Titre des en-têtes : Compose ne réduit pas une ligne sous la hauteur de la police (35 dp au lieu des 27 du CSS `line-height:1`), ce qui coupait le sous-titre. Nouveau `Modifier.lineBox`.
- Une réponse vide (tour qui n'a fait qu'appeler un outil) apparaissait comme un message sans contenu : elle est masquée, sauf si elle porte le plan ou la dernière décision.
- Planificateur : deux réarmements simultanés (démarrage de la tablette et maintenance de démarrage) pouvaient rattraper deux fois une tâche manquée. Le réarmement est maintenant sérialisé ; test `concurrentRearmsCatchAMissedRunUpOnce`.
- Restes de l'étape 2 : l'inventaire des expressions régulières vérifiées par ICU est régénéré (376 compilées, 0 refusée) ; la règle LAW-009 porte sur le code, pas sur le texte de démonstration affiché (`noteDao()`).

**Vérification.** 420 tests JVM, lint et construction des tests instrumentés : verts.

**Limites.**
- Dans les tests, le modèle est simulé : c'est le script qui redemande l'action après la reprise. Un vrai modèle décide lui-même, avec l'information exacte que l'action n'a pas été exécutée.
- Rien n'a été essayé sur la tablette physique.
- La comparaison au pixel près avec le prototype reste l'étape 7.

## Étape 5 — Portrait et tailles réelles

**Objectif.** Tiroir, panneau en surimpression, rail d'activité et règles de taille (≥ 1400 dp, 1200–1400 dp, portrait ou < 1200 dp).

- `CortanaShell` choisit la disposition d'après la largeur et l'orientation réelles (`shellLayoutFor`). La Galaxy Tab A11 (environ 960 × 600 dp) prend le comportement « portrait », comme décidé.
- Discussion en portrait : pilule « Tâche n/N » dans l'en-tête, rail d'activité sous l'en-tête, panneau « Tâche active » par-dessus la conversation. Vérifié à 1086 × 1448 (captures `discussion_portrait`, `paused_portrait`).
- Écrans 3 à 6 : ils gardent leurs 2 colonnes en portrait (elles tiennent dans 1054 dp). Sous 760 dp, la colonne secondaire passe sous la principale (`DesignPage`).
- Écrans non redessinés : ils s'affichent dans la coque ; leur bouton de menu ouvre le tiroir ou réaffiche la barre latérale.

## Étape 6 — Historique, Tâches, Mémoire, Réglages

Chaque écran a une version sans état, avec des aperçus sur les données du prototype, et une route qui la relie aux services existants. Aucun second backend.

- **Historique** (`HistoryDesign.kt`) : recherche sans tenir compte des accents ni de la casse, plus la recherche plein texte des messages ; filtres comptés (Tout, Épinglées, Avec fichiers, Avec artefacts, Tâches agent) ; projet, modèle, période ; conversations par date ; aperçu avec messages, branches, artefacts, « Ouvrir », « Brancher » (copie de la conversation) et menu (épingler, renommer, archiver, exporter, supprimer après confirmation). Les comptes viennent de requêtes en lecture ajoutées à `ChatService.sessionFacts()`, sans changement de schéma.
- **Tâches** (`TasksDesign.kt`) : tâche en cours ou en pause (projection du runtime, STOP ou Reprendre) ; messages en file de toutes les conversations (monter, descendre, retirer, confirmer) ; planifications du planificateur avec leur interrupteur ; tâches terminées récemment. En colonne, le journal d'actions réellement exécutées de la tâche choisie, l'exécution et les artefacts produits ; en mode développeur, la trace.
- **Mémoire** (`MemoryDesign.kt`) : souvenirs confirmés et suggérés (« Retenir » confirme, « Ignorer » rejette), catégories réelles (Profil, Préférences, Faits), actif ou ignoré dans la conversation, corriger, oublier (audité), ajouter. « Chat temporaire » rend la conversation temporaire : rien n'est écrit en mémoire durable et aucun souvenir n'est lu. En colonne : niveau de contexte sans pourcentage (répartition « ≈ » en mode développeur), épingles, dernier compactage (inspecter, restaurer), projet.
- **Réglages** (`SettingsDesign.kt`) : les onze sections du prototype, sur les vrais réglages. Nouveau réglage « Bouton STOP : Mettre en pause ou Annuler la tâche » (`ChatPrefs.stopAction`), appliqué partout. Tout ce que le design ne montre pas reste dans « Tous les réglages avancés » (l'écran précédent, complet).
- Rappels, Fournisseurs, Développement, Worker, MCP et Santé ouvrent les écrans existants, comme décidé.

**Écarts assumés, documentés.**
- Catégories de mémoire : le runtime connaît Profil, Préférences et Faits ; « Projets » et « Appareils » ne sont pas inventés.
- Période de l'Historique : « Tout l'historique » par défaut, pour ne cacher aucune conversation ancienne. Le prototype affiche « 30 derniers jours ».
- Les lignes de réglage du prototype qui n'ont pas d'équivalent dans le runtime (copie enrichie, routage automatique, synchronisation…) ne sont pas affichées : pas d'interrupteur factice.

**Tests.** `DesignScreensRoutesTest` (5, données réelles) :
- l'Historique filtre les épinglées, cherche « reunion » et trouve « Réunion », puis ouvre la conversation ;
- les Tâches affichent planification, file et tâche réussie, désactivent une planification et retirent un message de la file ;
- la Mémoire confirme une suggestion et active le chat temporaire ;
- les Réglages écrivent les vrais réglages ;
- STOP réglé sur « Annuler » annule la tâche au lieu de la suspendre.

## Étape 7 — Vérification visuelle et couleurs

**Comparaison au prototype**, à densité 1 (1 px = 1 dp), côte à côte avec Chromium qui rend le prototype avec les mêmes polices, en paysage 1448 × 1086 et en portrait 1086 × 1448. Écarts corrigés :
- en-tête : boîte du titre à 27 dp (`Modifier.lineBox`), le sous-titre n'est plus coupé ;
- contrôle segmenté des réglages à 40 dp : en CSS la bordure s'ajoute à la hauteur, en Compose elle est dessinée dedans ;
- lignes de réglage : la bordure du haut compte dans la hauteur, comme dans le prototype. Hauteurs mesurées : 65, 65, 62, 64, 62, 62, 61 contre 66, 65, 62, 64, 62, 62, 62 ;
- Tâches et Mémoire : la colonne secondaire commence au niveau de la première section.

Écart restant sous 2 dp : la rangée de filtres de l'Historique passe à la ligne quelques pixels plus tard que dans Chromium (largeurs de texte).

**Couleurs.** `grep -r "Color(0x" ui/` ne trouve plus que `ui/theme/CortanaTokens.kt`, et le test `designColorsLiveOnlyInTheTokens` l'impose.
- Les verts, rouges et orange Material des anciens écrans sont remplacés par les couleurs sémantiques du design (succès, danger, avertissement, info).
- Les palettes de l'écran rc4, toujours sélectionnable, sont regroupées dans `Rc4WorkspaceColors`.

**Défaut trouvé par la suite complète et corrigé.**
- Au clic sur « Refuser », l'approbation était refusée avant l'arrêt de la tâche. Si l'outil reprenait la main entre les deux, le modèle lisait « refusé, ne réessaie pas » au lieu de « action non exécutée », et « Reprendre » ne redemandait pas l'accord.
- `Orchestrator.pause()` arrête maintenant la tâche avant de fermer l'approbation. `refuseAndPause()` enregistre la décision comme « refused », et non « cancelled », dans l'historique et l'audit.
- Vérifié 3 fois, puis 2 fois sous charge CPU.
