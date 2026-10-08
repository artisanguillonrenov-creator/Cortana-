# Journal des versions — Cortana

## 2.0.0-rc9 (versionCode 10) — Modèle de code et de vision sur RunPod

S'installe par-dessus la **2.0.0-rc8** et toutes les versions précédentes, sans désinstaller : même paquet, même certificat. Schéma de base inchangé (**v4**) : aucune migration.

### Nouveau
- **Nouveau fournisseur « RunPod · code & vision »** (pod `cortana-code-vision`, RTX 3090) : modèle multimodal `qwen3.6-27b`, appel d'outils natif, contexte de 32 768 jetons.
  - Il devient le **modèle pour le code** (atelier logiciel) et le **modèle pour la vision** (photos, captures d'écran).
  - Le modèle de discussion, les images et la mémoire restent sur elyndor-5090.
- **Clé d'accès** : ce serveur est protégé. Saisissez sa clé une fois dans Réglages → Modèles → « RunPod · code & vision ». Elle n'est jamais incluse dans l'application ni dans le dépôt.
- Appliqué une seule fois. Une installation venant de la rc8 ne rejoue pas la configuration d'elyndor-5090 : vos réglages restent tels quels.

## 2.0.0-rc8 (versionCode 9) — Connexion au pod RunPod elyndor-5090

S'installe par-dessus la **2.0.0-rc7** et toutes les versions précédentes, sans désinstaller : même paquet, même certificat. Schéma de base inchangé (**v4**) : aucune migration.

### Nouveau
- **Connexion automatique au pod RunPod « elyndor-5090 »** au premier démarrage de cette version :
  - discussion : `cydonia-24b-elyndor` (llama.cpp, port 8000), devient le fournisseur par défaut ;
  - images : `lustify-sdxl-v4` et mémoire (embeddings) : `bge-m3` (serveur médias, port 7860) ; la mémoire est ré-indexée.
- Appliqué une seule fois : vos choix ultérieurs (autre fournisseur par défaut, fournisseur supprimé) ne sont jamais annulés. Les autres fournisseurs et les discussions liées à un modèle restent inchangés.
- Les modèles `cydonia` utilisent l'appel d'outils émulé de Cortana (contexte 24 576).

## 2.0.0-rc7 (versionCode 8) — Résultats web enrichis dans la conversation

S'installe par-dessus la **2.0.0-rc6** et toutes les versions précédentes, sans désinstaller : même paquet, même certificat. Schéma de base inchangé (**v4**) : aucune migration.

### Nouveau
- **Images, vidéos et cartes de pages directement dans la réponse** quand Cortana cherche sur le web. La recherche a un mode : pages (défaut), images, vidéos ou mixte ; Cortana choisit images ou vidéos quand un visuel aide vraiment.
  - Images : une grande image, ou une galerie à faire défiler ; un appui l'agrandit (zoom) et donne accès à la source.
  - Vidéos : miniature, durée, titre et source. Les vidéos directes (mp4, webm, HLS) se lisent dans la conversation ; YouTube et les autres plateformes s'ouvrent dans leur application ou leur site.
  - Pages : carte avec titre, site, extrait et image.
- Ces résultats restent dans la discussion et réapparaissent quand vous la rouvrez.

### Sécurité
- Contenu externe signalé comme tel et jamais exécuté : pas de page web intégrée, adresses vérifiées (https pour les images et vidéos, jamais d'adresse locale), images seulement (jamais SVG), taille limitée. Les images passent par le même client protégé que les outils web.
- La lecture vidéo intégrée utilise Media3 (Apache 2.0).

## 2.0.0-rc6 (versionCode 7) — Exécution directe des commandes de développement

S'installe par-dessus la **2.0.0-rc5** et toutes les versions précédentes (1.2.0, rc1 à rc4), sans désinstaller : même paquet, même certificat. Schéma de base inchangé (**v4**) : aucune migration.

### Nouveau
- **Commandes de développement explicites exécutées directement**, sans boucle du modèle : « git status », « git fetch », « git pull », « crée la branche X », « pousse la branche X », « crée la branche X et pousse-la », « lance les tests », « lance le build », « inspecte le projet X », « liste mes projets ». Seulement si la demande et ses paramètres sont sans ambiguïté (projet nommé ou unique, nom de branche valide) ; sinon la demande passe par le planificateur comme avant.
- Chaque action passe toujours par la politique : un push vers un dépôt distant demande l'empreinte (L3), un pull une confirmation. Un échec ou un refus termine la tâche avec son motif, sans relancer le modèle.

### Corrigé
- Cortana ne répète plus une action déjà en échec : un appel identique n'est relancé qu'après un changement d'état utile (fichier modifié, état Git changé, nouvelle autorisation…). La première répétition est signalée au modèle, la suivante arrête l'étape proprement au lieu d'épuiser la limite d'appels. Une action refusée n'est jamais redemandée dans la même étape. Lancer les tests, corriger, relancer les tests reste possible.

## 2.0.0-rc5 (versionCode 6) — Design « Cortana Workspace »

S'installe par-dessus la **2.0.0-rc4**, les rc précédentes et la **1.2.0**, sans désinstaller : mêmes paquet et certificat. Schéma de base inchangé (**v4**) : aucune migration. Les écrans précédents restent disponibles : Réglages › Général › Interface « Workspace (rc4) » ou « Classique ».

### Nouveau : l'application redessinée
- **Un seul thème pour toute l'application** : sombre, polices Geist et Sora, icônes Material Symbols. « Contraste élevé » renforce les textes secondaires et les bordures.
- **Barre latérale** : Discussion, Historique, Tâches, Mémoire, Rappels, Fournisseurs, Santé, Réglages. En mode développeur seulement : Développement, Worker, MCP.
  - Carte « Mode développeur ».
  - Grand bouton **STOP**. Un appui long déclenche l'arrêt d'urgence de toute l'autonomie ; la reprise demande l'empreinte ou le code.
  - En portrait et sous 1200 dp de large, la barre latérale devient un tiroir.
- **Discussion en 3 colonnes.**
  - La conversation affiche le plan de la tâche, les blocs de code du design et l'état en direct.
  - À droite, le panneau « Tâche active » : progression, étape en cours, journaux en direct, fichiers modifiés, et les cartes Worker, MCP, Mémoire et Politique.
  - En portrait : rail d'activité sous l'en-tête et panneau par-dessus la conversation.
- **Tâches suspendues.**
  - STOP (barre latérale, panneau, rail, touche Échap) suspend la tâche sans perdre son plan ; **Reprendre** la continue là où elle s'était arrêtée.
  - Réglages › Streaming › Bouton STOP permet de choisir « Annuler la tâche » à la place.
- **Autorisations dans le fil.**
  - « Refuser » suspend la tâche ; « Reprendre » redemande l'accord.
  - « Autoriser une fois » ouvre l'écran sécurisé, qui reste le seul à pouvoir accorder.
  - La décision reste visible dans le fil.
- **Puces d'outils** (Outils, Système, Fichiers, Terminal, Git, Navigateur) : choisissez ce que Cortana peut utiliser dans chaque discussion.
- **Historique** : recherche sans tenir compte des accents, filtres comptés (épinglées, avec fichiers, avec artefacts, tâches agent), projet, modèle et période ; aperçu avec branches, fichiers et artefacts ; ouvrir, brancher, épingler, renommer, archiver, exporter, supprimer.
- **Tâches** : tâche en cours avec STOP ou Reprendre, messages en file, planifications activables, tâches terminées, journal des actions réellement exécutées, artefacts produits.
- **Mémoire** : suggestions à confirmer, souvenirs par catégorie, actif ou ignoré dans la discussion, corriger, oublier, ajouter ; **chat temporaire** (rien n'est écrit en mémoire durable) ; contexte de la discussion, épingles, dernier compactage, projet.
- **Réglages** en onze sections. Tous les réglages précédents restent dans « Tous les réglages avancés ».

### Corrigé
- Ouvrir une discussion depuis une notification au démarrage pouvait fermer l'application.
- Une autorisation interrompue par STOP restait « en attente » dans l'historique : elle est maintenant enregistrée et auditée.
- Au redémarrage de la tablette, une tâche planifiée manquée pouvait être rattrapée deux fois.

## 2.0.0-rc4 (versionCode 5) — Espace de discussion (Chat Workspace)

S'installe par-dessus la **2.0.0-rc3**, la rc2, la rc1 et la **1.2.0**, sans désinstaller : mêmes paquet et certificat. Schéma de base **v4** : migration 3→4 automatique et additive, précédée d'une sauvegarde. Chaque conversation existante devient une branche unique, dans le même ordre qu'avant. Rien n'est supprimé. L'ancienne interface reste disponible : Réglages › Espace de discussion › Interface « Classique ».

### Nouveau : l'espace de discussion
- **Un écran pour tout, pensé pour la tablette.**
  - En paysage : discussions, conversation et panneau de contexte côte à côte, colonnes redimensionnables.
  - En portrait : panneaux par-dessus la conversation. Sur téléphone : tiroir et feuille du bas.
  - Densité compacte / confort / large ; thèmes système, clair, sombre, contraste élevé ; animations réductibles.
- **Composer.**
  - Brouillon conservé même après fermeture.
  - Pièces jointes : lire, analyser ou simple référence.
  - Messages écrits pendant une réponse mis en file : réordonner, retirer, « Maintenant ». Un message ancien ou écrit avant un redémarrage attend votre confirmation.
  - Commandes `/` et palette Ctrl+K, mentions `@`.
  - Dictée par appui court, mains libres par appui long.
  - Partage depuis une autre application et « Demander à Cortana » sur un texte sélectionné : le contenu devient un brouillon, jamais envoyé sans vous.
- **Réponses riches.** Mise en forme, code (copier, enregistrer, numéros de ligne), tableaux défilants, formules lisibles (source à un toucher), diagrammes Mermaid en source, sources citées cliquables, cartes d'actions repliables, images avec zoom. Jamais de HTML, jamais le raisonnement privé d'un modèle.
- **Rien ne se perd.**
  - Modifier un message crée une nouvelle version (« Version 1/2 ») ; Régénérer garde les variantes (« Réponse 2/3 ») ; Brancher depuis un message ; Dupliquer une discussion ; onglet Branches.
  - STOP garde le texte déjà reçu : **Continuer** reprend là où la réponse s'est arrêtée.
  - Une réponse coupée par le réseau, un délai ou la fermeture de l'application est conservée « interrompue ».
  - Supprimer n'existe que sur demande explicite (« Supprimer ce message et la suite… »), après confirmation.
- **Depuis un message** : Convertir en tâche (brouillon relu avant envoi), Enregistrer dans Téléchargements, Comparer avec d'autres modèles ; « Coller une image ou un fichier copié » dans le composer ; onglet **Outils** du panneau (outils de la discussion et leur niveau de risque).
- **Contexte visible.** Jauge (faible → compactage proche), éléments épinglés (message, fichier, note), « Résumer maintenant » et points de compactage consultables, mémoire utilisable ou non dans chaque discussion, instructions héritées d'un projet.
- **Contexte trop volumineux (413)** : une réduction automatique, puis une carte claire : réduire, réessayer, changer de modèle, nouvelle discussion.
- **Comparer 2 à 4 modèles** sur le même message : colonnes ou onglets, arrêt d'un seul modèle, « Suivre », « Fusionner » ou « Faire comparer par Cortana ». Sans outils : une comparaison ne multiplie aucune action.
- **Mode Conseil** : demande explicite au Conseil de réflexion ; il reste désactivé tant que vous ne l'activez pas.
- **Artefacts** de la discussion : versions (une modification ne remplace jamais), comparaison, export, retour au message source.
- **Autorisations dans la conversation** : Refuser directement ; autoriser seulement sur l'écran sécurisé.
- **Organisation** : projets avec instructions, étiquettes, épinglées, archivées ; recherche dans les titres, messages et fichiers avec filtres (Ctrl+F) ; export Markdown, JSON ou texte ; import d'un export Cortana.
- **Voix et accessibilité** : panneau vocal (Interrompre, Revenir au texte, STOP) ; lecture d'une réponse avec pause, reprise et vitesse ; annonces « Cortana répond… » et « Réponse terminée. » pour le lecteur d'écran, jamais mot à mot ; raccourcis clavier (Ctrl+N, Ctrl+F, Ctrl+B, Ctrl+I, Échap).

### Corrigé
- Mains libres : si le micro de coupure ne pouvait pas s'ouvrir (autorisation retirée, micro occupé), Cortana restait muette tout en affichant « je réponds ». La réponse est désormais toujours prononcée.
- Repli de fournisseur au milieu d'une réponse : le texte affiché repart de zéro au lieu de s'ajouter à la réponse abandonnée.

## 2.0.0-rc3 (versionCode 4) — Conseil de réflexion

S'installe par-dessus la **2.0.0-rc2**, la rc1 et la **1.2.0**, sans désinstaller : mêmes paquet et certificat. Schéma de base **v3** (migration 2→3 automatique et purement additive : neuf tables ajoutées, rien de modifié). **Nouveauté désactivée par défaut** : sans action de votre part, Cortana se comporte comme la rc2.

### Nouveau : le Conseil de réflexion (Réglages › Intelligence › Conseil de réflexion)
- Pour une décision, un sujet à risque, une recherche à recouper ou un développement non trivial, plusieurs spécialistes temporaires (stratège, analyste factuel, ingénieur solution, challenger, ou un bureau adapté : revue de code, recherche, diagnostic Android, affaires, création) analysent la demande **en parallèle et indépendamment**, confrontent leurs meilleurs arguments, puis Cortana répond **une seule fois**.
- Une majorité n'est jamais une preuve : une objection critique bloque, une vérification par un outil peut renverser un faux consensus, les égalités ne sont pas tranchées au hasard ; un défi final cherche la faille avant de répondre.
- Modes : Auto, Rapide, Renforcé, Conseil 4, Approfondi, Personnalisé. Modèle et fournisseur par rôle (avec repli), effort de raisonnement, local uniquement, jetons, délai ; débat (tours, topologie, protocole, juge anonyme, arrêt anticipé) ; coûts (jetons, coût, appels, durée, plafond quotidien, réduction en mode Auto, batterie faible).
- Pendant la séance : « 4 analyses en cours », état de chaque spécialiste, STOP. Sous la réponse : carte « Résumé du conseil » (consensus, accords, risques, incertitudes, preuves, durée ; détails techniques sur demande).
- Sécurité : les spécialistes ne font que proposer — outils en lecture seule, jamais de secret, jamais d'action ; toute action repasse par vos règles et confirmations. Rien de leur raisonnement n'est enregistré, seulement des résumés structurés.

### Corrigé
- Passerelle de modèles : le code HTTP et `Retry-After` d'un échec réessayable n'étaient pas transmis à l'appelant.
- Validation des schémas d'outils : une liste de types n'acceptait que le premier (trouvé par les tests ; aucun outil publié n'en dépendait avant cette version).
- Test des procédures rendu robuste (il pouvait lire la procédure entre son écriture et sa validation).

## 2.0.0-rc2 (versionCode 3) — correctif du plantage au démarrage de la rc1

S'installe par-dessus la **2.0.0-rc1** comme par-dessus la **1.2.0**, sans désinstaller : mêmes paquet, certificat et schéma de base (v2). Aucune autre modification fonctionnelle que les correctifs ci-dessous.

### Corrigé
- **Plantage au démarrage de la rc1** (rapport de bug du propriétaire) : une expression régulière des procédures (`{{paramètre}}`) contenait des accolades non échappées, acceptées par la JVM des tests mais refusées par le moteur d'Android (ICU). L'application s'arrêtait dès son lancement.
- Même défaut dans la lecture des dépendances d'un `package.json` (outils de développement) : aurait fait échouer l'analyse d'un projet JavaScript.
- Suppression de diapositives d'une présentation : l'identifiant lu dans le fichier est désormais échappé (un fichier forgé ne peut plus faire échouer l'opération).

### Nouveau contrôle
- Chaque expression régulière de l'application (341, dont les motifs de `model_caps.json`) est compilée par la **vraie bibliothèque ICU4C** — le moteur d'Android — à chaque exécution des tests et avant chaque construction release, qui échoue si l'une est refusée (`tools/check_android_regex.py`). Sur le code de la rc1, ce contrôle trouve exactement le plantage signalé.
- Tests instrumentés ajoutés (à lancer sur la tablette ou un émulateur) : démarrage réel de l'application et de l'écran principal, compilation de chaque motif par le moteur d'Android, initialisation de chaque classe dans ART.

## 2.0.0-rc1 (versionCode 2) — Cortana VNext, version candidate

**Plante au démarrage sur Android (corrigé en 2.0.0-rc2) — ne pas utiliser.**

Même application (`io.github.artisanguillonrenov.cortana`), même certificat de signature (SHA-256 `6d98375a…49eeda33`) : s'installe **par-dessus** la 1.2.0 et conserve toutes les données. Schéma de base v2 (migration 1→2 explicite, sauvegarde automatique avant migration). Détail des décisions : `docs/DECISIONS.md` ; progression par phase : `docs/VNEXT_PROGRESS.md` ; tests : `docs/TEST_MATRIX.md` ; publication : `docs/RELEASE.md`.

**Version candidate** : tous les tests exécutables dans l'environnement de construction passent (voir `docs/RELEASE.md` §7) ; la matrice sur tablette réelle (`docs/RC_CHECKLIST.md`) **n'a pas été exécutée**. Ne pas la considérer comme validée sur l'appareil tant que cette liste n'est pas cochée.

### Moteur de tâches
- Contrats versionnés partagés (`contracts/`) et règles d'architecture vérifiées par des tests (un seul appelant des modèles, un seul propriétaire de l'état des tâches, un seul chemin d'exécution des outils, l'interface ne touche jamais la base).
- Machine d'état des tâches auditée ; planificateur (plans en graphe), vérificateur (déterministe puis par modèle), moteur de reprise (réessai, re-planification, demande au propriétaire).
- Points de reprise : après un arrêt brutal, la tâche reprend où elle en était sans rejouer les étapes faites ; un effet incertain est soumis au propriétaire ; boîte d'envoi idempotente.
- Contexte v2 : résumé persistant des longues conversations, carnet de tâche, découverte d'outils bornée (`tools.discover`).
- Mémoire hybride (plein texte + sémantique, repli hors ligne), relations, rétention, export/effacement ; routage des modèles (local/confidentiel/code, disjoncteur).
- Procédures apprises (skills) versionnées, rejouées, invalidées en cas de divergence.
- Spécialistes (analyste, implémenteur, relecteur) sous l'unique orchestrateur.

### Développement logiciel
- Espace de travail de code (aperçu, application, annulation de correctifs), Git (JGit : worktrees, fusion, push protégé), exécution locale confinée, adaptateurs build/test/diagnostics, intelligence du dépôt, fabrique logicielle de bout en bout (plan → branche dédiée → correctif → tests ciblés puis suite → build → revue → rapport ; aucun push automatique, push forcé refusé).
- Worker appairé (HTTPS épinglé, requêtes signées) pour les builds lourds ; client d'administration (vue et CLI `--json`) sur les mêmes contrats.

### Android et multimodal
- Repli par vision quand l'accessibilité ne suffit pas ; voix (mot d'éveil, VAD, interruption, STT/TTS locaux ou distants).
- Contacts, téléphone, SMS, agenda, notifications avec aperçu et niveaux de risque.
- Navigateur interactif et recherche multi-sources avec citations vérifiées.
- Documents et données (CSV, classeurs, graphiques, modèles, PDF, présentations) avec provenance ; médias (images, synthèse vocale, transcription, vidéo).

### Interopérabilité
- MCP (HTTP et stdio via le worker), A2A, plugins déclaratifs signés (jamais de code exécuté), connexions OAuth 2.1 (PKCE, révocation), e-mail IMAP/SMTP, webhooks entrants via le worker, Home Assistant, Telegram (propriétaire seul).

### Automatisation et amélioration
- Exécutions planifiées durables (file, politique de concurrence, rattrapage après arrêt), surveillances conditionnelles sans appel de modèle.
- Service d'amélioration : propositions versionnées (raccourcis, réglages, cas de régression, procédures) appliquées et annulées par le propriétaire uniquement.

### Exploitation
- Observabilité : arbre de spans par tâche, métriques, visualiseur local, export OTLP facultatif — sans contenu ni secret.
- Sauvegarde portable chiffrée (PBKDF2 + AES-GCM), restauration vérifiée, diagnostic de base (Doctor) avec réparations.
- Mises à jour : manifeste signé par la clé de l'APK, téléchargement https vérifié, sauvegarde préalable, installateur système (le propriétaire confirme).

### Sécurité
- Politique de sortie réseau (standard, confirmation des nouveaux domaines, domaines connus seulement, liste de blocage appliquée jusque dans le client HTTP).
- Contenu suspect d'injection (web, dépôts, e-mails) : signalé au modèle avec les numéros de ligne, et toute action à effet ou à sortie réseau passe en L2 sans autorisation permanente.
- Inventaire des secrets par poignée (références, orphelins, rotation) ; les exécuteurs ne lisent jamais un secret.
- Écran « Capacités et permissions » : état, raison, correctif, dernière utilisation, révocation des autorisations.
- Chaîne d'approvisionnement : vérification des empreintes de toutes les dépendances (`gradle/verification-metadata.xml`), SBOM CycloneDX et inventaire des licences.

### Changements visibles pour le propriétaire
- Nouveaux écrans : Tâches (plan, trace), Procédures, Planifications (historique), Capacités et permissions ; réglages Réseau et secrets, Observabilité, Sauvegarde, Mises à jour, Amélioration.
- Comportements 1.2.0 conservés : rappels sans modèle, « Retiens que… », STOP retire les outils mais garde la discussion (tests de non-régression inchangés).

## 1.2.0 (versionCode 1)
Version de référence (`docs/VNEXT_BASELINE.md`).
