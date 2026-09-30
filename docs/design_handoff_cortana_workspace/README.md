# Handoff : Cortana Workspace (tablette Android)

## Vue d'ensemble
Interface de l'assistant **Cortana** pour tablette Android. Le cœur est l'écran **Discussion** : un « centre de commandement conversationnel » où Cortana exécute des tâches agentiques (ex. créer, builder, tester et installer une app Android). Autour : **Historique**, **Tâches**, **Mémoire**, **Réglages**. Six autres entrées de menu (Rappels, Fournisseurs, Développement, Worker, MCP, Santé) ne sont pas encore conçues. Elles affichent un écran « Pas encore conçu ».

Ce design complète le dossier de spécification `CORTANA_CHAT_WORKSPACE_CLAUDE_HANDOFF` (docs 00 à 16), dont il respecte les règles : progressive disclosure, statuts = icône + texte, approbations via PolicyEngine, pas de chaîne de pensée affichée, tablette d'abord.

## À propos des fichiers de design
Les fichiers de ce dossier sont des **références de design créées en HTML** : des prototypes qui montrent l'apparence et le comportement attendus. **Ce n'est pas du code de production à copier.** La tâche est de **recréer ces écrans dans l'app Android existante en Kotlin + Jetpack Compose**, avec ses patterns (ViewModel, StateFlow, Room, services existants). Pas de WebView.

## Fidélité
**Haute fidélité (hi-fi).** Couleurs, typographie, espacements, rayons, icônes, textes et interactions sont définitifs. Reproduire au dp près.
- **Conversion :** 1 px CSS du prototype = **1 dp**, et 1 px de police = **1 sp**.
- **Taille de référence :** le prototype est dessiné pour un écran de 1448 × 1086 dp en paysage et 1086 × 1448 dp en portrait.
- **Tailles réelles :** voir « Adaptation aux tailles réelles ».

---

## Design tokens
Tous les tokens sont codés dans `compose/CortanaTokens.kt`. Ne jamais écrire une couleur en dur dans un composant (spec 16.2).

### Couleurs : surfaces
| Token | Hex | Usage |
|---|---|---|
| Background | `#0A0F19` | Fond de l'écran. Halo radial `#101A30` en haut (ellipse 900×520 centrée à 58 % / −8 %, fondu vers le fond à 62 %). |
| Sidebar | `#0D1525` → `#0A111D` | Dégradé vertical. Bordure droite `#152033`. |
| Surface (carte) | `#0D1522` | Cartes. Bordure `#1B2536` (carte Plan : `#1C2638`). |
| Control | `#0E1522` | Pills, chips, boutons secondaires. Bordure `#1F2A3C`. |
| Control hover | `#131D2E` / `#141E30` / `#16213A` | Survol / pression. |
| Input | `#0F1726` | Champ de saisie. Bordure `#1F2A3C`. Focus : bordure `#2F6FD6` + halo 3 dp `rgba(47,139,255,.15)`. |
| Composer | `#0C1320` | Barre de saisie. Bordure `#1B2536`. |
| Sunken | `#0A111D` | Corps du code, boîte d'étape, stats. |
| Console | `#09101A` | Logs. Bordure `#182130`. |
| Code header | `#0E1624` | En-tête de bloc de code. Séparateur bas `#18212F`. |
| Elevated | `#101828` | Menus déroulants. Bordure `#243149`. Ombre `0 24 60 −12 rgba(0,0,0,.75)`. |
| Divider | `#172131` / `#1A2436` | Séparateurs. |
| Badge | `#172133` | Badges de compteur. Bordure `#222E42`. |
| Tile | `#141E2F` | Tuiles d'icône neutres. Bordure `#223047` / `#243047`. |
| Selected segment | `#1F3050` | Segment actif. |

### Couleurs : texte
| Token | Hex | Usage |
|---|---|---|
| TextStrong | `#F2F6FB` | Titres d'écran et de carte |
| TextPrimary | `#EEF3FA` | Libellés forts |
| TextBody | `#DFE7F2` | Texte de Cortana |
| TextControl | `#E6EDF6` | Libellés de boutons |
| TextSecondary | `#A3B0C2` / `#93A1B5` | Sous-titres |
| TextTertiary | `#8391A6` / `#7F8CA0` | Métadonnées |
| TextFaint | `#6E7B8F` | Horodatage des logs |
| Nav inactive | texte `#D3DCE8`, icône `#B7C3D3` | Menu latéral |
| Nav active | texte `#FFFFFF` (600), icône `#5AA7FF` | Menu latéral |

### Couleurs : accent et statuts
| Token | Valeur | Usage |
|---|---|---|
| Accent | `#2F8BFF` | Interrupteur actif, anneau du logo |
| Accent button | dégradé vertical `#3A8DFF` → `#1F6BE8` | Envoyer, Autoriser, Retenir, Reprendre |
| Accent text | `#4D9FFF` | Nom « Cortana », icône Voix |
| Accent icon | `#5AA7FF` | Icônes actives |
| Accent link | `#6CB6FF` | Liens, types dans le code |
| Accent soft | `#8CC2FF` | Icône d'onglet actif |
| Sélection | fond `rgba(47,139,255,.16)`, bordure `rgba(90,167,255,.5)` | Chips et onglets actifs |
| Nav active bg | dégradé horizontal `rgba(47,139,255,.24)` → `rgba(47,139,255,.07)`, liseré intérieur `rgba(90,167,255,.16)` | Élément de menu actif. Barre gauche 3 dp `#3D8FFF` avec lueur 10 dp. |
| Progress | dégradé horizontal `#1F6BE8` → `#4D9FFF`, lueur 10 dp `rgba(77,159,255,.7)` | Barres de progression |
| Bulle utilisateur | dégradé vertical `#10254A` → `#0C1C3A`, bordure `#214274`, reflet haut `rgba(140,190,255,.08)` | Messages de l'utilisateur |
| Success | point `#22C55E`, texte `#4ADE80`, teinte `rgba(34,197,94,.12)`, bordure `.4` | En cours, OK |
| Chip outil actif | fond `rgba(34,197,94,.13)`, bordure `rgba(52,211,120,.55)`, texte `#E9F8EF`, icône `#34D27A` | Chips d'outils |
| Danger (STOP) | dégradé vertical `#E5463F` → `#C7302B`, bordure `rgba(255,140,128,.45)`, lueur `0 10 30 −8 rgba(229,70,63,.6)` | Boutons STOP |
| Danger text | `#F87171` / `#FCA5A5` | Erreurs, refus |
| Warning (approbation) | `#F5A524` / `#FBBF24`, texte `#FDE8B8` / `#FCD68A`, fond dégradé `rgba(245,165,36,.10)` → `.03`, bordure `.45` | Approbations, pause |
| Purple | `#8B5CF6`, texte `#C4B5FD` / `#D3C4FF`, teinte `.16`, bordure `.45` | Tag Développement, Mémoire, chat temporaire |
| Cyan | `#5FD4E8`, teinte `rgba(34,190,220,.13)` | Mode Recherche |
| Amber | `#F5B544` | Mode Conseil, dossier dans les logs |

### Coloration syntaxique (bloc de code)
| Élément | Couleur |
|---|---|
| Texte normal | `#D3DBE7` |
| Mot-clé (`abstract class fun companion object private var val return synchronized`) | `#C792EA` |
| Type (`[A-Z][a-z]\w*`) | `#6CB6FF` |
| Annotation (`@\w+`) | `#E8A95B` |
| Chaîne | `#B9E08C` |
| Littéral (`null this it` et nombres) | `#F78C6C` |
| Numéros de ligne | `#46546B` |

Les constantes tout en majuscules, comme `INSTANCE`, restent en texte normal.

### Couleurs des logs
Chaque ligne affiche l'heure en `#6E7B8F`, puis une icône de 15 sp, puis le texte.

| Type | Icône | Couleur icône | Couleur texte |
|---|---|---|---|
| run | `chevron_right` | `#7D8AA0` | `#CDD6E2` |
| ok | `check` | `#4ADE80` | `#5EE08F` |
| dir | `folder`, rempli | `#F5B544` | `#CDD6E2` |
| stop | `stop`, rempli | `#F87171` | `#FCA5A5` |
| play | `play_arrow`, rempli | `#6CB6FF` | `#A9CFFF` |
| wait | `front_hand` | `#FBBF24` | `#FCD34D` |

### Typographie
- **Polices :** **Geist** pour l'interface, **Geist Mono** pour le code et les logs, **Sora** uniquement pour le logotype. Elles sont disponibles sur Google Fonts.
- **Intégration :** polices téléchargeables, ou TTF dans `res/font`.
- **Chiffres :** tabulaires (`tnum`) pour les compteurs et les durées.

| Style | Police | Taille / interligne | Graisse | Autre |
|---|---|---|---|---|
| Wordmark « Cortana » | Sora | 30 / 30 | 600 | tracking +0.01em |
| Titre d'écran | Geist | 27 / 27 | 700 | tracking −0.01em |
| Titre de panneau (« Tâche active ») | Geist | 19 | 600 | |
| Titre de section (« Plan d'exécution ») | Geist | 16.5 | 600 | |
| Titre d'élément | Geist | 15–17 | 600 | |
| Menu latéral | Geist | 16 | 500, 600 si actif | |
| Texte de Cortana | Geist | 15 / 25 | 400 | |
| Bulle utilisateur | Geist | 14.5 / 24 | 400 | |
| Étape du plan | Geist | 14.5, ligne de 26.6 dp | 400 | |
| Libellé de contrôle | Geist | 13.5–14 | 500 | |
| Secondaire | Geist | 13–13.5 | 400 | |
| Légende | Geist | 12–12.5 | 400 | |
| Surtitre de section | Geist | 11.5 | 600 | MAJUSCULES, tracking 0.1em |
| Code | Geist Mono | 13 / 19.4 | 400 | |
| Logs | Geist Mono | 11.5 / 18.6 | 400 | |

### Espacements et rayons
- **Grille :** 4/8 dp (spec 16.4). Écarts courants : 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22.
- **Rayons normalisés :**

| Taille | Rayon | Usage |
|---|---|---|
| XS | **6** | Tags 22–26 dp, `kbd` |
| S | **8–10** | Boutons-icônes 30–34 dp, chips 36–40 dp, onglets |
| M | **12** | Boutons 44–48 dp, pills d'en-tête, tuiles |
| M+ | **13** | Champ 52 dp, bouton Envoyer |
| L | **14** | Cartes de liste, carte Plan, lignes de conversation |
| XL | **16** | Cartes principales, composer, grand STOP |
| Bulle | **18** | Bulle utilisateur |
| Écran | 26 | Écran du prototype uniquement (le bord de l'appareil n'est pas à reproduire) |

### Mouvement
- **Courbe standard :** `CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)`.
- **Transitions d'interface :** 150 ms pour les chips et le survol, 180 ms pour les interrupteurs, 220 ms pour le repli de la sidebar, 240 ms pour le drawer et le panneau en overlay.
- **Barre de progression :** largeur animée sur 600 ms avec la courbe standard.
- **Entrée d'un élément :** 250–300 ms en ease-out, opacité 0 → 1 et translation Y de 6 dp → 0. Concerne les lignes de log, les cartes d'approbation, les messages en file et les artefacts.
- **Scrim :** fondu de 200 ms.
- **Spinner d'étape :** anneau de 17 dp avec un trait de 2 dp `#3D8FFF` et une lueur de 8 dp. Un arc supérieur `#CFE6FF` tourne en 0,9 s, linéaire, en boucle. L'icône `progress_activity` tourne en 1,1 s.
- **Points de streaming :** 3 points de 7 dp `#3D8FFF`, cycle de 1,2 s, décalés de 0 / 150 / 300 ms. À 40 % du cycle, opacité 0.25 → 1 et translation Y 0 → −3 dp.
- **Pastille « En cours » :** point vert de 9 dp avec un anneau pulsé de 0 à 7 dp qui s'efface, cycle de 1,8 s.
- **Lueur du logo :** 3,2 s en ease-in-out pour la sidebar. L'avatar Cortana du fil ne pulse (2,4 s) **que pendant l'exécution**.
- **Autres animations :** curseur de console clignotant (1 s, en paliers), onde vocale (4 barres, scaleY 0.3 → 1, 0,9 s, décalage de 150 ms), flash du plan (bordure `#3D8FFF`, 1,4 s).
- **Réduction des animations :** si l'utilisateur l'a activée, **tout désactiver** (spec 16.6). Aucun clignotement pendant le streaming.

---

## Écrans

### 1. Discussion (paysage, 1448 × 1086)
**Rôle :** converser avec Cortana et piloter une tâche agentique en direct.

**Disposition :** `Row` à trois colonnes.

**Sidebar : 288 dp fixe.** Padding : 44 en haut (sous la barre d'état), 12 sur les côtés, 54 en bas.
- **Marque (hauteur 66, padding gauche 8) :**
  - Logo : anneau de 62 dp, trait de 4 dp `#2F8BFF`, fond radial `#081224` (55 %) → `#0D2A55`, lueur intérieure et extérieure.
  - Écart de 14, puis le texte : « Cortana » en Sora 30/600, et dessous « Votre alliée IA au quotidien » (13.5, `#A1AFC3`, une seule ligne).
- **Menu :** commence 38 dp sous la marque. Éléments de 48 dp, rayon 12, padding 0 10 0 16, écart icône–texte 18, icône 25 sp.
  - **Groupe 1 (écart 12) :**
    - Discussion `forum`
    - Historique `history`
    - Tâches `event_available`, badge « 3 » (passe à « 2 » quand la tâche est terminée)
    - Mémoire `layers`
    - Rappels `notifications`
  - **Séparateur :** 1 dp `#1A2436`, marge verticale de 16, retrait horizontal de 12.
  - **Groupe 2 (écart 14) :**
    - Fournisseurs `deployed_code`
    - Développement `code` *
    - Worker `memory` *, point vert de 10 dp avec lueur
    - MCP `cable` *, badge « 4 »
    - Santé `monitor_heart`
    - Réglages `settings`
  - `*` = visible seulement en mode développeur.
  - **Badge :** 32 × 32 minimum, rayon 9, fond `#172133`, bordure `#222E42`, texte 14/600 `#D4DDE9`.
- **Bas de la sidebar, collé en bas (écart 22) :**
  - **Carte « Mode développeur » :** hauteur 68, rayon 12, fond `#0E1626`, bordure `#1C2739`, padding horizontal 12.
    - Icône `auto_fix_high` remplie, `#5AA7FF`.
    - Titre 14/600 sur une ligne, sous-titre 13 `#98A6BA` sur une ligne : « Outils avancés activés » ou « Outils avancés masqués ».
    - Interrupteur 44 × 26 (bouton de 20 dp, décalage de 18 dp quand il est actif).
  - **Grand bouton STOP :** hauteur 84, rayon 16, dégradé Danger.
    - À gauche, un carré blanc de 30 dp (rayon 9) contient un carré de 12 dp `#D73A34` (rayon 3).
    - Texte « STOP » 18/600, tracking 0.08em.
    - **États :**
      - En pause : bouton bleu « Reprendre » avec l'icône `play_arrow`.
      - Terminé : bouton neutre `#0F1828`, bordure `#1E2A3D`, « Aucune tâche en cours » et l'icône `check_circle`.

**Centre (reste de la largeur).** Padding 40 / 14 / 24 / 18.
- **En-tête (56 dp, aligné en haut) :**
  - **Bouton de repli de la sidebar :** 40 × 40, rayon 10, icône `left_panel_close` ou `left_panel_open`. En portrait, l'icône devient `menu`.
  - **Titre :** « Discussion » (27/700), puis 9 dp plus bas le sous-titre « Discutez, créez, automatisez, tout est possible. » (12, `#93A1B5`, une ligne).
  - **Contrôles à droite (hauteur 48, rayon 12, écart 8) :**
    - **Sélecteur de modèle :** tuile de 28 dp (rayon 8) avec l'initiale du fournisseur (fond = couleur du fournisseur à 13 % d'opacité). À côté, le nom du modèle (14/600) sur le fournisseur (11), puis `expand_more`.
    - **« Recherche » :** icône `language`, puis un interrupteur 34 × 20 (bouton de 16 dp, décalage de 14).
    - **« Voix » :** icône `graphic_eq` `#4D9FFF`. Actif : fond `rgba(47,139,255,.16)`, bordure `rgba(90,167,255,.55)`.
  - **Menu du fil :** `more_vert` 20 sp à droite, 100 dp sous le haut de la colonne.
- **Fil de discussion :** défile. Marge haute 18, padding droit 14 (place de la barre de défilement).
  - **Message utilisateur :** aligné à droite, largeur max 540, padding 12 / 16 / 8 / 12, rayon 18.
    - Avatar rond de 30 dp, fond `#CFD9E8`, icône `person` remplie `#4E5F7A`.
    - Texte 14.5/24.
    - Pied de message : « 14:22 » (11.5 `#8B9AB0`) et `done_all` `#5AA7FF`.
  - **Message de Cortana :** 22 dp sous le message précédent. Avatar anneau de 44 dp (trait de 3,5 dp), écart 20, contenu de 640 dp de large maximum.
    - **Nom :** « Cortana » (14/600 `#4D9FFF`) et « 14:22 » (12 `#7F8CA0`).
    - **Texte :** 15/25.
    - **Carte « Plan d'exécution » :** marge haute 12, rayon 14, padding 9 / 16 / 12 / 18.
      - En-tête de 36 dp : `list_alt` et le titre 16.5/600. À droite : `schedule` avec le statut (« En cours… », « En attente », « En pause » ou « Terminé »), l'estimation (« ~ 2-3 min ») et `more_vert`.
      - 6 étapes, une ligne de 26.6 dp chacune, écart 12. Icône d'état selon l'étape :
        - faite : `check_circle` rempli `#22C55E` ;
        - en cours : spinner ;
        - en attente ou en pause : anneau `#F5A524` ;
        - à venir : anneau de 1,5 dp `#3C4960`.
    - **Paragraphe :** marge haute 12.
    - **Bloc de code :** rayon 12.
      - En-tête de 40 dp :
        - une tuile de 20 dp en dégradé `#7F52FF` → `#3D8FFF` avec l'icône `data_object` ;
        - le nom « NoteDatabase.kt » (14.5/600) ;
        - le chemin « src/main/…/data/ » en mono 12.5 ;
        - une chip « Kotlin » ;
        - un bouton « Copier » : `content_copy`, qui passe à `check` vert pendant 1,6 s ;
        - un bouton « Agrandir » : `open_in_full` / `close_fullscreen`.
      - Corps : hauteur max 232 dp (360 dp agrandi), colonne de numéros de ligne de 44 dp alignée à droite.
    - **Carte d'approbation :** apparaît le moment venu (voir « Interactions »).
    - **Ligne de statut en direct :** 15/25, suivie des 3 points animés quand la tâche tourne.
    - **À la fin de la tâche :** trois chips d'artefacts de 46 dp :
      - `app-debug.apk · 6,4 Mo`
      - `Rapport de tests · 14/14`
      - « Ouvrir sur le Pixel 8 »
  - **Messages en file :** bulle à droite (largeur max 460, rayon 16 16 6 16), puis l'étiquette « En file · envoyé après la tâche en cours » avec `schedule` et un lien « Retirer ». Une fois la tâche finie, l'étiquette devient « Transmis à Cortana » avec `done_all`.
- **Chips d'outils :** marge haute 10, hauteur 38, rayon 10, écart 10, icône 19. Six chips : Outils `handyman`, Système `hexagon`, Fichiers `folder`, Terminal `terminal`, Git `commit`, Navigateur `language`. « Système » est actif par défaut. Chip active : icône remplie, vert.
- **Composer :** marge haute 10, hauteur 66, rayon 16, padding 7 / 8 / 7 / 6.
  - Bouton « Joindre » 48 × 48 : `attach_file` tourné de 45°.
  - Champ : 52 dp, rayon 13, texte 15, placeholder « Posez votre question ou donnez une instruction… ». En mode voix, il devient « Je vous écoute… parlez naturellement ».
  - Micro 40 × 40 dans le champ, à droite.
  - Bouton Envoyer 52 × 52 : rayon 13, dégradé accent, `send` rempli, lueur `0 8 22 −8 rgba(47,139,255,.8)`.

**Panneau contextuel : 400 dp fixe.** Padding 44 / 12 / 28 / 0, écart 11.
- **Carte « Tâche active » :** prend toute la hauteur restante. Rayon 16, padding 14 / 16 / 12.
  - **En-tête (34 dp) :**
    - `assignment` rempli `#4D9FFF` et le titre 19/600 ;
    - le statut (voir « États de statut ») ;
    - un bouton de 32 dp `open_in_full` qui ouvre l'écran Tâches. En portrait, c'est `close`, qui ferme le panneau.
  - **Infos (marge haute 16) :**
    - Tuile de 78 dp (rayon 16, dégradé `#1A2740` → `#121B2C`) avec `sticky_note_2` rempli de 42 sp.
    - Titre « Application Android - Notes » (17/600) et sous-titre « Créer, builder, tester et installer ».
    - Tags de 26 dp (rayon 7) : Développement (violet), Android (vert), Local (bleu).
  - **Progression :** barre de 6 dp (rayon 3) 24 dp plus bas, avec le compteur « 3/6 » à droite. Dessous (marge 6), le temps restant : « ~ 2-3 min restantes ».
  - **Boutons (marge 16) :** grille de 2 colonnes, écart 12, hauteur 48, rayon 12.
    - « Voir le plan » `description` : fait défiler le fil jusqu'au plan et le fait clignoter.
    - « STOP » `stop_circle`, qui devient « Reprendre », puis « Rejouer la démo » quand la tâche est terminée.
  - **Onglets (marge 16) :** 3 colonnes, écart 10, hauteur 40, rayon 10, texte 13/500 : Logs en direct `article`, Fichiers `file_copy`, Aperçu `preview`.
  - **Contenu de l'onglet :** prend la hauteur restante, marge 12.
    - **Logs :** console avec défilement automatique vers le bas. Curseur clignotant de 7 × 13 dp `#6CB6FF` pendant l'exécution.
    - **Fichiers :** arborescence en mono 12 et diff `+n` / `−n`. Le fichier ouvert est surligné en `rgba(47,139,255,.1)`.
    - **Aperçu :** mini-téléphone de 98 × 188 et ses infos (« Aperçu en direct », appareil, écran, build, « Capture », « Miroir »).
  - **Actions (marge 14) :** grille de 2 × 2, écart 11, hauteur 46 : Review Git `call_split`, Build `hardware`, Tests `science`, Artefacts `inventory_2`.
- **Cartes d'état :** rayon 14, padding environ 13–15 / 16 / 14. Tuile de 46 dp (Worker) ou de 40 dp.
  - **Worker appairé :** `smart_toy`, statut « ● ACTIF », puis « Pixel 8 (Android 14) » et « ADB connecté • 1 app en cours ». Une fois la tâche finie : « Notes installée ».
  - **MCP connectés :** `hub` vert, « ● 4/4 », puis « Filesystem • GitHub • Play Store • Web ».
  - **Mémoire active :** `notes` violet, « ● N items » (le nombre de souvenirs actifs). Un tap ouvre l'écran Mémoire.
  - **Politique sécurité :** `verified_user` vert, « ● Sécurisée », puis « Approbation requise : installation, accès système ». Pendant une approbation : contour ambre et « ✋ 1 demande ».

### 2. Discussion (portrait, 1086 × 1448)
Spec 16.10 : en portrait, le chat et des panneaux en overlay.
- **Sidebar :** devient un **drawer modal** de 288 dp (translation X −300 → 0, 240 ms, ombre `30 0 60 rgba(0,0,0,.55)`). Scrim `rgba(3,6,12,.55)`. Taper un élément du menu ferme le drawer.
- **Panneau contextuel :** devient un **overlay à droite** de 424 dp (translation X 440 → 0), fond `#0A0F19`, bordure gauche `#1B2536`, padding 44 / 12 / 28 / 12.
- **En-tête :** une pill supplémentaire « Tâche 3/6 » (`assignment`) ouvre le panneau.
- **Rail d'activité (spec 9.1) :** sous l'en-tête, marge 14, hauteur 54, rayon 14. Il contient :
  - le statut ;
  - un séparateur ;
  - « Étape n/6 · nom » avec une barre de progression de 4 dp ;
  - le temps restant ;
  - « Détails › » (ouvre le panneau) ;
  - STOP ou Reprendre (38 dp).
- **Barre d'état :** reste toujours au-dessus des overlays.
- **Autres écrans :** ils gardent leurs 2 colonnes, qui tiennent dans 1054 dp.

### 3. Historique
**Rôle :** chercher et rouvrir une conversation (spec 4.5–4.8).
- **Mise en page de base (commune aux écrans 3 à 7) :**
  - Padding 40 / 12 / 24 / 18.
  - En-tête identique à Discussion.
  - Corps en 2 colonnes : principale flexible et secondaire de 388 dp, écart 16.
- **En-tête :** « Historique » / « Retrouvez vos conversations, fichiers et artefacts. ».
  - **Recherche :** 380 × 48, icône `search`, placeholder « Rechercher titres, messages, fichiers… ». Filtre en direct sur le titre et l'aperçu, sans tenir compte de la casse ni des accents.
  - **« Exporter » :** `download`.
- **Filtres :** marge 18, chips de 36 dp avec compteur : Tout, Épinglées `push_pin`, Avec fichiers `attach_file`, Avec artefacts `inventory_2`, Tâches agent `smart_toy`. Viennent ensuite un séparateur et trois sélecteurs : « Projet : tous », « Modèle : tous », « 30 derniers jours ».
- **Liste :** groupée par date (Aujourd'hui, Hier, Cette semaine), surtitre 11.5/600.
  - **Ligne :** padding 12 / 14, rayon 14.
    - Tuile de 42 dp colorée selon le mode : Discussion `forum` bleu, Agent/Dev `code` violet, Recherche `travel_explore` cyan, Conseil `groups` ambre, Voix `graphic_eq` vert.
    - Titre 15/600, avec `push_pin` si la conversation est épinglée, puis l'aperçu (13, une ligne).
    - À droite : l'heure, et selon le cas l'indicateur de tâche en direct, le nombre de branches `call_split` et un badge de mode.
  - **Ligne sélectionnée :** fond `rgba(47,139,255,.1)`, bordure `rgba(90,167,255,.45)`.
  - **Aucun résultat :** `search_off` et « Aucune conversation ne correspond. ».
- **Colonne d'aperçu :**
  - **Carte principale :** tuile de 52 dp, titre 17/600, méta « mode · modèle · projet ».
    - 3 stats : messages, branches, artefacts.
    - Boutons : « Ouvrir » (accent, ouvre la conversation), « Brancher », `more_horiz`.
  - **Carte « Branches » :** mini-arbre. Branche actuelle : point plein `#3D8FFF`. Autres branches : point creux avec un coude.
  - **Carte « Fichiers et artefacts » :** chaque ligne a une tuile de 34 dp, un nom, une méta et `download`.

### 4. Tâches
**Rôle :** suivre, planifier et auditer (spec 9.4, 9.8).
- **En-tête :** segments « Toutes · En cours · Planifiées · Terminées » (48 dp) et bouton « Nouvelle tâche » (accent, `add`).
- **Colonne principale :** sections avec surtitre et compteur.
  - **En cours :** grande carte en direct.
    - Tuile de 64 dp, titre, puis « Démarrée à 14:22 · Worker Pixel 8 · {modèle} », et le statut.
    - Barre de progression de 8 dp avec « n/6 ».
    - Boîte d'étape (48 dp, fond Sunken) : icône d'état animée, « Étape n/6 · nom », temps restant.
    - Boutons « Discussion » et STOP / Reprendre / Rejouer la démo.
  - **En file :**
    - « Générer les icônes de l'application », avec monter, descendre et retirer.
    - Si des messages attendent dans le chat, la ligne « N message(s) en file » et « Voir ».
  - **Planifiées :**
    - Sauvegarde du NAS, « Chaque nuit · 02:00 »
    - Résumé de mes e-mails, « Demain · 08:00 »
    - Vérifier les mises à jour Play Store, « Vendredi · 10:00 », désactivée
    - Chaque ligne a un interrupteur 44 × 26. Une ligne désactivée est à 50 % d'opacité.
  - **Terminées récemment :** chaque ligne a un statut « ✓ Réussie » (vert) ou « ⚠ Partielle » (ambre).
- **Colonne secondaire :**
  - **« Journal d'actions » (auditable) :** timeline verticale, une icône d'état par action.
    - Contenu : titre, détail, heure en mono 11 et durée à droite.
    - Mise à jour en direct : étapes, approbation, pause, vérification.
  - **« Exécution » :** modèle, appareil, outils, politique.
  - **« Artefacts produits » :** NoteDatabase.kt, puis app-debug.apk après le build, puis le rapport de tests.

### 5. Mémoire
**Rôle :** voir et contrôler ce que Cortana retient (spec 6.1–6.8).
- **En-tête :** recherche (280 dp) et pill « Chat temporaire » avec un interrupteur violet. Quand il est actif :
  - un bandeau violet s'affiche : « Chat temporaire activé : Cortana n'écrit rien en mémoire durable… » ;
  - les suggestions de mémoire sont masquées.
- **Suggestion :** carte bleue « Cortana propose de retenir », avec la citation, la source, et les boutons « Ignorer » et « Retenir ». « Retenir » ajoute le souvenir en tête de liste.
- **Catégories :** chips avec compteur : Tout, Profil (bleu), Préférences (violet), Projets (vert), Appareils (ambre).
- **Grille :** 2 colonnes, écart 12. Chaque carte contient :
  - un tag de catégorie et un statut (« Confirmé » avec `check_circle` vert, ou « Suggéré » avec `help` ambre) ;
  - le fait (14.5/21) ;
  - la provenance (icône et source) ;
  - un séparateur ;
  - un interrupteur 38 × 22 « Actif dans cette conversation » (sinon « Ignoré… », carte à 55 % d'opacité) ;
  - les actions « Corriger » `edit` et « Oublier » `delete`, qui retire la carte.
  - Un souvenir verrouillé par la politique de sécurité affiche `lock` et « Géré par la politique de sécurité ».
- **Colonne secondaire :**
  - **« Contexte de la conversation » :** niveau « Moyen » (22/600 `#6CB6FF`) et jauge en 4 segments (Faible · Moyen · Élevé · Compactage). **Aucun faux pourcentage.**
    - En mode développeur seulement : répartition estimée « ≈ » (Historique, Fichiers épinglés, Schémas d'outils, Mémoire, Réserve de sortie).
  - **« Épinglé au contexte » :** 3 éléments, chacun avec `close` pour le désépingler.
  - **« Dernier compactage » :** « 14:02 », résumé, boutons « Inspecter » et « Restaurer ».
  - **« Projet Cortana Labs » :** ce qui est « Hérité » du projet et ce qui est propre à « Cette conversation » (spec 6.8).

### 6. Réglages
- **Sous-menu :** 256 dp, carte avec des éléments de 44 dp. Onze sections, reprises de la spec 12 :

| Section | Icône |
|---|---|
| Général | `tune` |
| Messages | `chat` |
| Streaming | `stream` |
| Contexte | `data_usage` |
| Modèles | `psychology` |
| Outils | `handyman` |
| Voix | `graphic_eq` |
| Fichiers | `folder` |
| Mémoire | `layers` |
| Développeur | `code` |
| Confidentialité | `lock` |

- **Contenu :** 760 dp de large maximum. En-tête de section : tuile de 48 dp, titre 21/600 et description.
- **Lignes :** carte, lignes de 62 dp minimum (padding 12 / 18), séparateur `#172131`. Trois types de contrôle :
  - interrupteur 44 × 26 ;
  - contrôle segmenté (32 dp, segment actif `#1F3050` avec liseré `rgba(90,167,255,.45)`) ;
  - valeur suivie de `chevron_right`.
- **Liaisons :** « Développeur › Mode développeur » est lié au même état que la carte de la sidebar. Toutes les valeurs et tous les libellés sont dans `setDef`, dans le fichier HTML.

### 7. Écrans à concevoir
Rappels, Fournisseurs, Développement, Worker, MCP, Santé. En-tête standard avec le sous-titre « Écran à concevoir », puis un bloc centré :
- tuile de 76 dp avec l'icône de la section ;
- « Pas encore conçu » ;
- une phrase qui décrit le contenu prévu ;
- le bouton « Revenir à la discussion ».

---

## Interactions et comportement
- **Machine d'état de la tâche** (voir « Gestion de l'état ») :
  - **En cours :** une ligne de log arrive environ toutes les 1,5 s, les étapes 3 → 6 avancent, ainsi que la progression et le texte de statut.
  - **Étape 6 :** `ApprovalRequired` fait apparaître la carte d'approbation dans le fil :
    - titre « Approbation requise » et « Installer « Notes » sur Pixel 8 (Android 14) » ;
    - Outil `adb install` · Cible `com.cortana.notes` · Risque « Modéré » ;
    - boutons « Refuser » et « Autoriser une fois ».
  - **« Autoriser » :** la carte se réduit à une puce verte « Installation autorisée une fois · adb install » et la tâche reprend.
  - **« Refuser » :** puce rouge « Installation refusée », la tâche passe en pause, et le texte devient « Installation annulée. L'APK reste disponible dans les artefacts. ». « Reprendre » redemande l'approbation.
- **STOP** (sidebar, panneau, rail et touche Échap dans le composer) :
  - met la tâche en pause et ajoute le log « Arrêt demandé · tâche suspendue » ;
  - le texte de Cortana devient « Tâche suspendue à votre demande. Reprenez quand vous voulez. » ;
  - **« Reprendre »** ajoute le log « Reprise de la tâche ».
- **Fin de tâche :**
  - statut « Terminée » ;
  - progression 6/6, « Terminée en 4 min 38 s » ;
  - artefacts affichés, badge Tâches à 2, Worker « Notes installée » ;
  - le grand STOP devient neutre.
- **Composer :**
  - Entrée envoie, Maj+Entrée va à la ligne.
  - Pendant une tâche, le message est **mis en file** (spec 2.6) et peut être retiré.
  - Le fil défile en douceur vers le bas à chaque nouvel événement.
- **Mode développeur désactivé :** masque Développement, Worker et MCP dans la sidebar, ainsi que la répartition des tokens dans Mémoire (progressive disclosure, spec 16.9).
- **Sélecteur de modèle :**
  - Menu de 310 dp à 56 dp sous la pill, surtitre « MODÈLE ».
  - Quatre modèles : GPT-4.1 · OpenAI, Claude Sonnet 4.5 · Anthropic, Gemini 2.5 Pro · Google, Qwen 2.5 Coder · Local · Worker.
  - Le modèle actif a un `check`. En bas : « Gérer les fournisseurs → ».
  - Un tap en dehors ferme le menu.
- **« Voir le plan » :** fait défiler le fil jusqu'au plan (16 dp au-dessus) et fait clignoter sa bordure pendant 1,4 s.
- **« Copier » (bloc de code) :** copie le code dans le presse-papiers et affiche `check` vert pendant 1,6 s.
- **Navigation :** revenir à Discussion recolle le fil et les logs en bas.
- **Survol et pression :** fond Control hover, ou `filter: brightness(1.08)` sur les boutons pleins. Pression : `scale(.985)` sur le grand STOP, `.96` sur Envoyer.

## Gestion de l'état
Tout est exposé par un `StateFlow<UiState>` dans le ViewModel. **Ne pas créer de second backend** (spec 00) : consommer TaskOrchestrator, PolicyEngine, MemoryService, ModelGateway, etc.

```kotlin
enum class RunStatus { Running, AwaitingApproval, Paused, Done }
enum class Approval { None, Pending, Granted, Refused }
data class TaskRunUi(
  val status: RunStatus, val step: Int /* 0..5 */, val progress: Float /* 0..1 */,
  val logs: List<LogLine>, val approval: Approval, val liveMessage: String, val eta: String
)
data class LogLine(val time: String, val kind: LogKind, val text: String) // run, ok, dir, stop, play, wait
```

**Correspondance avec les événements de la spec 11.3 :**
- ToolStarted / ToolCompleted → lignes de log et étapes.
- ApprovalRequired → `AwaitingApproval` + carte d'approbation.
- GenerationStopped → `Paused`.
- GenerationCompleted → `Done`.
- MessageQueued → file du composer.
- ArtifactCreated → chips et liste d'artefacts.

**État d'interface :**
- **Commun :**
  - écran sélectionné ;
  - `devMode` ;
  - `searchEnabled` ;
  - `voiceListening` ;
  - `sidebarCollapsed` (paysage) ;
  - `drawerOpen` et `panelOpen` (portrait) ;
  - modèle actif et menu ouvert ;
  - chips d'outils actives ;
  - onglet du panneau ;
  - brouillon (persistant, spec 2.7) ;
  - file de messages ;
  - `codeExpanded`.
- **Historique :** requête, filtre, conversation sélectionnée.
- **Tâches :** filtre, tâches planifiées actives.
- **Mémoire :** catégorie, requête, activation par conversation, souvenirs oubliés, suggestion, chat temporaire.
- **Réglages :** section et valeurs.

## Adaptation aux tailles réelles
Le prototype fait 1448 dp de large. Une tablette de 2560 × 1600 px fait environ 1280 × 800 dp. Utiliser `WindowSizeClass` / `currentWindowAdaptiveInfo()` :
- **Largeur ≥ 1400 dp :** 3 colonnes, sidebar 288, panneau 400 (comme le prototype).
- **1200–1400 dp :** 3 colonnes, sidebar 264, panneau 360. Le reste ne change pas.
- **Moins de 1200 dp, ou portrait :** comportement **portrait** (drawer, panneau en overlay, rail d'activité).
- **Téléphone :** spec 1.3 (panneau en bottom sheet). Hors du périmètre de ce prototype.

**Accessibilité :**
- Cibles tactiles **≥ 48 dp** via `Modifier.minimumInteractiveComponentSize()`, même si le visuel est plus petit (boutons-icônes de 32 dp, chips de 36–38 dp).
- Statuts toujours affichés avec une icône et un texte (spec 16.7).
- `contentDescription` en français.

## Assets
- **Icônes : Material Symbols Rounded**, graisse 300, taille optique 24, grade 0. Version remplie (`FILL 1`) là où c'est indiqué.
  - Exporter les icônes en vector drawables depuis fonts.google.com/icons avec ces réglages, ou embarquer la police variable Material Symbols Rounded et l'utiliser en ligatures.
  - Liste complète : voir `ICONS.md`.
- **Logo Cortana :** simple anneau, sans image. À dessiner en `Canvas` :
  - cercle de 62 dp (44 dp dans le fil), trait de 4 dp (3,5 dp) `#2F8BFF` ;
  - fond radial `#081224` → `#0D2A55` ;
  - lueur extérieure 18 dp `rgba(47,139,255,.6)`, lueur intérieure 12 dp `rgba(47,139,255,.7)`, liseré 1 dp `rgba(120,190,255,.3)`.
- **Aucune photo ni illustration.** Le mini-téléphone de l'onglet Aperçu est une simple mise en forme (l'app réelle affichera une capture du worker).
- **Logos de marques :** pas de logo de fournisseur. Chaque modèle est représenté par une tuile avec son initiale (O, A, G, L) :

| Fournisseur | Initiale | Couleur de l'initiale |
|---|---|---|
| OpenAI | O | `#E6EBF2` |
| Anthropic | A | `#F0A27A` |
| Google | G | `#7FB2FF` |
| Local | L | `#B69CFF` |

La tuile prend la même couleur, à 13 % d'opacité.

## Fichiers
| Fichier | Rôle |
|---|---|
| `Cortana Workspace.dc.html` | Prototype complet : tous les écrans, états et textes exacts. À ouvrir dans Chrome, avec `support.js` dans le même dossier (Internet nécessaire pour les polices et les icônes). La logique (données, machine d'état, textes) est dans la classe `Component`, en bas du fichier. |
| `support.js` | Runtime du prototype (sans rapport avec le code Android). |
| `compose/CortanaTokens.kt` | Tokens prêts à intégrer : couleurs, dégradés, typographie, dimensions, formes, mouvement. |
| `ICONS.md` | Liste des icônes Material Symbols utilisées, par écran. |
| `PROMPT_CLAUDE_CODE.md` | Prompt à donner à Claude Code pour lancer l'implémentation. |
| `reference/maquette-originale.png` | Image d'origine qui a servi de point de départ. **Le prototype HTML fait foi** en cas d'écart. |

**Dans le prototype :**
- Le bas de l'écran propose une bascule Paysage / Portrait.
- Le panneau Tweaks permet de couper la simulation, d'en régler la vitesse et d'afficher ou masquer le cadre de la tablette.
