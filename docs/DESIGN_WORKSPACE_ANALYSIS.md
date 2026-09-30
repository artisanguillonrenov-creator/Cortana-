# Analyse du paquet de design « Cortana Workspace » (29/09/2026)

Paquet reçu : `design_handoff_cortana_workspace/`.
- `README.md` (504 lignes) ;
- prototype `Cortana Workspace.dc.html` et son runtime `support.js` ;
- `compose/CortanaTokens.kt` ;
- `ICONS.md` (environ 150 icônes) ;
- `PROMPT_CLAUDE_CODE.md` : 7 étapes, un commit par étape ;
- `reference/maquette-originale.png`.

Point de départ : la **2.0.0-rc4** (espace de discussion H1→H12, schéma v4).

## 1. Ce que le design demande

Une refonte **visuelle et structurelle** de l'app autour d'un shell à trois colonnes :

- **Colonne gauche** : menu latéral avec marque, 11 entrées, carte « Mode développeur » et grand STOP.
- **Colonne centrale** : discussion avec plan d'exécution, blocs de code, carte d'approbation, chips d'outils et composer.
- **Colonne droite** : panneau « Tâche active », composé de :
  - la progression ;
  - des onglets Logs, Fichiers et Aperçu ;
  - les actions Git, Build, Tests et Artefacts ;
  - des cartes d'état Worker, MCP, Mémoire et Politique.

Écrans détaillés : Discussion (paysage et portrait), Historique, Tâches, Mémoire, Réglages (11 sections).
Six écrans sont marqués « Pas encore conçu ».

Exigences transverses :
- haute fidélité au dp près ;
- tokens centralisés, et aucune `Color(0x…)` hors du fichier de tokens ;
- polices Geist, Geist Mono et Sora ;
- icônes Material Symbols Rounded (graisse 300) ;
- animations précises, désactivées si l'utilisateur réduit les animations ;
- cibles tactiles de 48 dp ;
- pas de second backend.

## 2. Faisabilité des ressources

| Ressource | État |
|---|---|
| Geist, Geist Mono, Sora | Licence OFL, TTF variables disponibles (`google/fonts`, accessible ici). À embarquer dans `res/font`, sans dépendance réseau à l'exécution. |
| Material Symbols Rounded, graisse 300, remplie ou non | SVG accessibles ici (`fonts.gstatic.com`). Conversion scriptée en vector drawables (environ 150 icônes, plus les variantes remplies). L'app n'embarque aujourd'hui que `material-icons-core`. |
| Tokens | `CortanaTokens.kt` est complet : couleurs, dégradés, typographie, formes, dimensions, mouvement. Le package est à adapter. |

**Aucun blocage technique.** Le poids de l'APK augmente d'environ 1 Mo (polices).

## 3. Écart majeur : la taille réelle de la tablette

- **Le prototype** : dessiné pour 1448 × 1086 dp.
- **La Galaxy Tab A11** (8,7 pouces, 1340 × 800 px) : environ 900 à 1000 × 530 à 600 dp en paysage, selon la densité One UI.
- **Conséquence** : d'après les règles du README (moins de 1200 dp → comportement portrait), **la tablette du propriétaire affichera toujours la variante portrait**, y compris en paysage :
  - menu en tiroir ;
  - panneau en surimpression ;
  - rail d'activité.
- **Hauteur** : avec environ 560 dp au lieu de 1086, le menu (environ 1000 dp de contenu) et le panneau « Tâche active » doivent défiler.
- **Portée** : la vérification « au dp près » (étape 7) porte sur les aperçus Compose en 1448 × 1086. Sur l'appareil, c'est l'adaptation qui compte.

## 4. Correspondance avec l'existant (rc4)

| Design | Existant | Travail |
|---|---|---|
| Menu latéral | `AppNav` : tiroir actuel, routes history, tasks, memory, schedules, providers, dev, devices, health, settings, capabilities… | Nouveau shell ; les routes restent |
| Discussion : fil, bulles, code, composer, file d'attente | `ui/workspace` (Timeline, Composer, file H3) | Refonte visuelle ; comportements conservés |
| Plan d'exécution (6 étapes) | Plans de l'orchestrateur (`plans.active`) | Carte à brancher sur le plan réel |
| Logs en direct | Événements d'exécution et d'outils (`ChatStreamEvent`, trace) | Projection en lignes run, ok, dir, stop, play et wait |
| Carte d'approbation | `ApprovalBroker` ; loi WORKSPACE-2 (pas d'autorisation depuis le fil) | **Décision 1** |
| STOP / Reprendre | STOP = annulation (CANCELLED/HALTED) ; `resume()` existe pour WAITING_USER/INTERRUPTED | **Décision 3** |
| Chips d'outils (Outils, Système, Fichiers, Terminal, Git, Navigateur) | Jeux d'outils par session (Discussion, Assistant, Complet) et `ToolCategory` | Filtre de catégories par discussion (la politique reste inchangée) |
| Sélecteur de modèle (4 modèles fictifs) | Fournisseurs et modèles réels (ModelGateway) | Données réelles ; la liste du prototype sert aux aperçus |
| Recherche / Voix (en-tête) | `ChatMode.RESEARCH` ; mains libres (H11) | Branchement |
| Worker, MCP, Mémoire, Politique (cartes) | Devices/Worker, MCP, mémoire, politique | Données réelles |
| Historique | `HistoryScreen` ; recherche H10, branches, projets | Refonte ; mini-arbre des branches |
| Tâches (en cours, file, planifiées, terminées, journal) | `TasksScreen`, `SchedulesScreen`, audit | Refonte, avec fusion de la vue Planifiées |
| Mémoire (suggestions, catégories, chat temporaire, contexte) | Mémoire hybride (active et à confirmer), `memoryOff` et épingles H7 | Refonte ; « chat temporaire » = mémoire coupée pour la discussion |
| Réglages (11 sections) | `SettingsScreen` et réglages de l'espace de discussion | Refonte ; les sections existantes s'y répartissent |
| Rappels, Fournisseurs, Développement, Worker, MCP, Santé : « Pas encore conçu » | **Des écrans fonctionnels existent** pour chacun | **Décision 2** |

Fonctions de la rc4 que le design ne montre pas, et qui doivent rester accessibles (règle « sans régression ») :
- variantes et versions ;
- comparaison multi-modèle ;
- visionneuse d'artefacts ;
- export et import ;
- partage entrant ;
- panneau vocal ;
- inspecteur de contexte ;
- projets et étiquettes.

Elles restent atteignables par les menus existants (⋮ du fil, du message, de la réponse), sans nouvelle surface.

## 5. Conflits avec les règles déjà établies

1. **Autorisation dans le fil.**
   - Le prototype a un bouton « Autoriser une fois » dans la carte.
   - Aujourd'hui, pour la sécurité (loi WORKSPACE-2), le fil ne peut que refuser : autoriser passe par l'écran sécurisé (FLAG_SECURE, lié à l'action, biométrie pour L3).
2. **Écrans « Pas encore conçu ».** Les remplacer par un écran vide supprimerait des fonctions qui marchent.
3. **STOP met « en pause ».**
   - Dans le prototype, STOP suspend et « Reprendre » continue.
   - Aujourd'hui, STOP annule la tâche.
4. **Thèmes.** Le design est uniquement sombre. L'app propose aussi Clair et Contraste élevé. Faute de valeurs dans le design, et comme il ne faut rien inventer :
   - le design devient le thème par défaut ;
   - les thèmes existants restent disponibles tels quels.
5. **Données de démonstration.** Pixel 8, NoteDatabase.kt, tâches planifiées « NAS », souvenirs, GPT-4.1… ne servent qu'aux aperçus et aux tests. L'app affiche les données réelles.
6. **Couleurs en dur.**
   - Le critère `grep "Color(0x" ui/` ne doit trouver que le fichier de tokens.
   - Il y en a aujourd'hui dans 18 fichiers `ui/`, dont les écrans non redessinés (thème, approbation, voix…).
   - Leurs couleurs passeront aussi par des tokens, sans changement visuel.

## 6. Plan (suivant `PROMPT_CLAUDE_CODE.md`, un commit par étape)

0. Ressources : polices dans `res/font`, script SVG → vector drawables, `CortanaTokens` dans `ui/theme`, `CortanaTheme {}`.
1. Composants de `ui/components` (23), avec une `@Preview` par état.
2. Discussion en paysage : trois colonnes, état initial du prototype en aperçu (source de données factice).
3. Machine d'état de la tâche, réelle : projection des événements de l'orchestrateur vers `TaskRunUi`, file de messages, STOP partout, Échap.
4. Discussion en portrait et adaptation : `WindowSizeClass` (≥ 1400, 1200–1400, < 1200), tiroir, panneau en surimpression, rail d'activité.
5. Écrans Tâches, Historique, Mémoire, Réglages, puis les autres entrées.
6. Vérification :
   - aperçus en 1448 × 1086 et 1086 × 1448, rendus en images sous Robolectric et comparés au prototype rendu dans Chromium (disponible ici) ;
   - écarts supérieurs à 2 dp corrigés.
7. Porte : tests (dont `WorkspaceUiTest` mis à jour), lint, loi « aucune couleur en dur », build release, puis une 2.0.0-rc5 si vous le souhaitez.

**Ampleur** : au moins aussi gros que les chantiers H1→H12 côté interface. Aucune migration de base n'est prévue.

## 7. Décisions appliquées (29/09/2026, modifiables par le propriétaire)

1. **« Autoriser une fois »** ouvre l'écran d'autorisation sécurisé existant ; « Refuser » agit dans le fil. La loi WORKSPACE-2 est inchangée.
2. **Rappels, Fournisseurs, Développement, Worker, MCP, Santé** ouvrent les écrans existants, dans le nouveau thème.
3. **STOP** reste l'arrêt d'urgence : toutes les actions s'arrêtent et la tâche passe **en pause**.
   - « Reprendre » exige la biométrie (§9.4, inchangé), puis relance la tâche depuis son plan ; les étapes faites sont gardées.
4. **Thème** : le design devient le thème de toute l'app ; « Contraste élevé » en garde la mise en page avec des textes et bordures renforcés, sans nouvelle couleur.
5. **Données** : réelles dans l'app ; celles du prototype ne servent qu'aux aperçus et aux tests visuels.
