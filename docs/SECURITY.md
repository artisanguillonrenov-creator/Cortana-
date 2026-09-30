# Sécurité — Cortana VNext

État au terme de la phase 18. Les tests cités sont exécutés dans l'environnement de build (JVM, Robolectric, HTTPS local, espaces de noms Linux réels) ; ce qui n'y est pas exécutable est listé en fin de document, jamais présenté comme vérifié.

## Principes

1. **Le modèle ne commande jamais le système directement.** Il propose des appels d'outils ; tout appel passe par `ToolDispatcher` → `PolicyEngine` → approbation/grant → ledger d'idempotence → exécution bornée → audit chaîné (LAW-003/004, vérifié par `ArchitectureRulesTest`).
2. **Tout contenu externe est une donnée**, jamais une instruction : web, fichiers, dépôts, sorties d'outils, notifications. Il est encapsulé (`données_non_fiables`, enveloppe non fermable de l'intérieur) et **marque la tâche** (taint).
3. **Le risque ne descend jamais sous le risque de base** d'une capacité ; les traits, les arguments, le taint et la destination ne peuvent que l'élever.
4. **Aucun secret dans le code, les journaux ou les archives.**
5. **Aucune publication automatique** : push, fusion vers une branche protégée, publication exigent une demande explicite et l'empreinte du propriétaire.

## Niveaux de risque et approbations

| Niveau | Exemples | Exigence |
|---|---|---|
| L0 | lecture, recherche, revue | aucune |
| L1 | modification locale réversible (patch, renommage sans exécution) | aucune, annulable |
| L2 | exécution de code, envoi, suppression de fichier, export | confirmation du propriétaire (ou grant limité) |
| L3 | paiement, sécurité, identifiants, push distant, commande Git destructrice | biométrie / identifiant de l'appareil |

- Approbation **liée à l'action exacte** (empreinte capacité + arguments canoniques) ; pour l'UI, la cible est reclassée au moment de l'exécution (TOCTOU).
- Grants : portée, durée, nombre d'usages, révocables ; jamais utilisés quand la tâche est influencée par du contenu non fiable et que la destination est nouvelle.
- STOP (`KillSwitch`) : annule les tâches, retire tous les outils, refuse les approbations en attente ; reprise vérifiée par le verrouillage de l'appareil.

Tests : `SecurityPrimitivesTest`, `OrchestratorEndToEndTest` (L2, refus, STOP, taint), `RuntimeTest` (grants, anti-répétition), `GitTest`, `SoftwareFactoryTest` (E2E-CODE-005/006).

## Secrets

- Android Keystore (AES-256-GCM, clé non exportable) ; seuls des *handles* circulent (`SecretStore`).
- `Redactor` : valeurs connues + formes connues (`sk-…`, `ghp_…`, `Bearer …`) masquées dans journaux, audit et sorties d'outils.
- Les secrets ne sont jamais transmis au worker ni à un bac à sable ; l'environnement d'exécution est reconstruit depuis zéro (`ExecutionTest.commandRunsConfinedWithScrubbedEnvironmentAndLogs`, `SoftwareFactoryTest.e2eCode005…` : ni `JAVA_TOOL_OPTIONS`, ni proxy, ni `HOME` de l'hôte dans le bac à sable).
- La revue de fin de tâche bloque tout secret potentiel ajouté au code (motifs + valeurs connues), sans jamais recopier la ligne.
- Identifiants Git : hôte → utilisateur + handle ; le jeton reste dans le Keystore.

## Réseau

- Garde SSRF : adresses privées, boucle locale, lien local, CGNAT, ULA refusées (sauf réseau local autorisé explicitement) — `SecurityPrimitivesTest.ssrfGuardBlocksPrivateRanges`.
- Redirections : suivies par `SsrfGuard.redirectGuard`, chaque saut vérifié **avant** la connexion (OkHttp se connecte aux IP littérales sans DNS) ; https → http refusé ; `Authorization` jamais transmis à un autre hôte (D-20260927-044, correctif d'un défaut présent depuis 1.2.0) — `BrowserTest.ssrfGuardChecksEveryRedirectHopBeforeConnecting`. Seule exemption : l'hôte SearXNG configuré par le propriétaire.
- DNS rebinding : le filtrage se fait dans le résolveur d'OkHttp (`SsrfGuard.GuardedDns`), dont les adresses sont exactement celles de la connexion — aucune seconde résolution, donc aucune fenêtre ; un nom qui répond public puis privé, ou les deux à la fois, n'atteint jamais l'adresse privée ; IPv4 privées cachées dans des formes IPv6 (mappée, compatible, NAT64) refusées ; exemptions par nom exact de l'hôte configuré par le propriétaire (D-20260928-065, `HardeningTest.dnsRebindingNeverReachesAPrivateAddress`).
- Worker : TLS épinglé sur l'empreinte du certificat (appairage), requêtes signées ECDSA par la clé d'appareil, horodatage + nonce anti-rejeu, codes d'appairage à usage unique (`WORKER_PROTOCOL.md`, `WorkerTest`, `WorkerIntegrationTest`).

## Développement (projets, exécution, Git)

| Menace | Contrôle | Test |
|---|---|---|
| Sortie du projet (`..`, lien symbolique, `.git`) | `WorkspaceFs.resolve` canonique, refus | `DevWorkspaceTest.pathsCannotEscapeTheWorkspace`, `ExecutionTest.cwdCannotEscapeTheProject` |
| Zip slip / chemins de synchronisation | validation côté worker | `WorkerTest.deltaSyncAndZipSlipProtection` |
| Scripts d'un dépôt non fiable | exécution **uniquement** en bac à sable isolé (espaces de noms : fichiers, réseau coupé, processus) sur un worker ; sinon refus | `WorkerTest.isolatedSandboxConfinesFilesystemNetworkAndProcesses`, `ExecutionTest.untrustedProjectCommandIsDeniedBeforeAnything` |
| Instructions malveillantes dans le dépôt | contenu = données, tâche marquée, suppression L2 refusable, exécution isolée | `SoftwareFactoryTest.e2eCode005…` |
| Processus orphelins | arrêt de l'arborescence complète (délai, annulation) | `ExecutionTest.timeoutKillsTheWholeProcessTree` |
| XXE dans les rapports JUnit | DTD interdite | `BuildTestDiagnosticsTest.junitReportsAreParsedAndXxeIsRefused` |
| Artefact altéré | empreinte SHA-256 vérifiée avant export et au rapatriement | `WorkerIntegrationTest.tamperedArtifactIsRejected` |
| Push forcé, reset --hard, réécriture d'historique | non proposés par les outils Git ; par le shell : push/réécriture refusés, commandes destructrices L3 (`GitCommandGuard`) | `GitTest.destructiveShortcutsAreRefused`, `SoftwareFactoryTest.e2eCode006…` |
| Push/fusion non souhaités | push distant L3, jamais forcé ; fusion vers branche protégée seulement sur instruction explicite + empreinte | `GitTest` |
| Configuration Git de l'hôte (signature, hooks) | `HermeticSystemReader` : configuration propre à Cortana | `GitTest` |
| Tâche déclarée finie sans preuve | porte de fin `SoftwareFactory` : revue sans bloquant, suite de tests verte **sur la révision exacte du code** | `SoftwareFactoryTest.e2eCode003…` |
| Tests affaiblis pour « passer au vert » | assertions retirées / tests désactivés = bloquant | `ReviewService` (vérification 3) |
| Reprise après crash sur des fichiers modifiés entre-temps | contrôle des empreintes des fichiers de la tâche ; sinon attente du propriétaire | `SoftwareFactoryTest.e2eCode004ExternalChange…` |
| Effet rejoué après crash | ledger + `reconcile` (le ChangeSet prouve l'effet) | `SoftwareFactoryTest.e2eCode004Resume…`, `RuntimeTest` |

## Lecture visuelle de l'écran (phase 16)

- Capture **ponctuelle** (`AccessibilityService.takeScreenshot`), uniquement quand l'arbre d'accessibilité ne permet pas d'agir ; jamais continue, jamais écrite sur disque (dernière capture en mémoire pour comparaison).
- Refusée : écran verrouillé, application sensible (banque, paiement, mots de passe), fenêtre protégée (le système refuse, Cortana le signale comme sensible), réglage « désactivée ».
- Champs mot de passe noircis **avant** la lecture du texte ; cartes bancaires (Luhn), IBAN, codes à usage unique noircis avant tout modèle.
- Modèle de vision : seulement si le propriétaire l'active, jamais en navigation privée ni dans une application sensible, et uniquement un modèle local en mode « Local uniquement ». Pendant l'évaluation du risque, aucune image ne quitte l'appareil.
- La cible visuelle est reclassée à l'exécution (« Valider » devenu « Valider le paiement » → refus) ; un effet non constaté après l'action est un échec, pas un succès.
- OCR Tesseract embarqué : aucune permission, aucun réseau, modèles vérifiés par SHA-256 ; ML Kit écarté (télémétrie).
- Tests : `VisionTest` (13). Capture et OCR réels : A/P non exécutés.

## Voix (phase 17)

- Micro ouvert **uniquement** sur action du propriétaire (🎤 ou 🗣️ mains libres) ; barre rouge sur tous les écrans et notification « Cortana écoute » (service de premier plan `microphone`) tant qu'une session vocale existe ; « Arrêter l'écoute » partout ; STOP et inactivité ferment le micro ; aucune écoute sous STOP.
- Mot d'éveil reconnu seulement pendant le mode mains libres visible (pas de moteur d'écoute permanent). Phrase d'arrêt jamais envoyée au modèle.
- Audio distant (transcription/synthèse) seulement si le propriétaire choisit « Fournisseur » ; en « Local uniquement », seulement un serveur local ; seul l'énoncé détecté est envoyé, rien n'est enregistré.
- Journal d'audit : début, interruption, fin d'écoute.
- Tests : `VoiceTest` (9). Micro, haut-parleur, écho et service en arrière-plan réels : A/P non exécutés.

## Communications (phase 18)

- Lectures (contacts, agenda) : données personnelles, sans approbation mais élevées en L2 dans une tâche influencée par du contenu externe (trait PRIVACY_SENSITIVE).
- SMS, appel, réponse à une notification, ajout de contact : aperçu exact (destinataire, texte) et accord ; numéro inconnu des contacts → L3 (empreinte) ; numéros courts et d'urgence jamais automatiques ; destinataire ambigu refusé.
- Notifications : seulement les applications cochées ; texte visible par le modèle seulement s'il est local ou sur autorisation ; codes, cartes, IBAN masqués ; déclencheurs = tâches contaminées avec le contenu en enveloppe de données.
- Presse-papiers : jamais de lecture d'un contenu sensible ou ressemblant à un secret ; copie sensible marquée et effacée après 60 s.
- Tests : `CommsTest` (8).

## Navigateur et recherche (phase 19)

- Une session de navigateur par tâche, en mémoire seulement, effacée à la fin de la tâche : cookies, onglets et historique ne passent jamais d'une tâche à l'autre.
- Aucun JavaScript des sites n'est exécuté dans Cortana ; les pages dynamiques passent par Chrome (accessibilité/vision) avec leurs propres règles.
- Tout contenu de page est non fiable (tâche contaminée) ; le texte caché et les phrases qui s'adressent à l'assistant sont retirés et signalés (`InjectionGuard`).
- Envoi de formulaire : GET ordinaire sur le site en cours automatique (L1) ; POST, données personnelles, autre site, fichier joint, long texte en tâche contaminée → L2 avec chaque valeur montrée (mots de passe masqués) ; formulaires et champs sensibles (mot de passe, paiement, carte, code) refusés ; numéros de carte, IBAN et codes jamais saisis. Page revérifiée à l'exécution.
- Destination réelle (hôte du lien ou de l'action du formulaire) utilisée par la règle de contamination (`destinationResolver`, D-20260927-045).
- Téléchargements : artefacts plafonnés, nom assaini, jamais ouverts ni exécutés ; exécutables/installables en L2. Envoi de fichier : uniquement un artefact, au plus 20 Mo, parti seulement après l'accord de l'envoi du formulaire.
- Recherche : un fait n'est gardé que si sa citation figure mot pour mot dans la page récupérée ; la synthèse ne peut citer que des sources vérifiées ; le sous-modèle d'extraction n'a aucun outil.

## MCP (phase 20)

- Aucun second registre : chaque outil MCP devient une définition canonique du registre unique et passe par la politique, les approbations, le registre des effets, les délais et l'audit.
- Risque décidé localement : L2 par défaut ; L3 si le serveur déclare l'outil destructeur ; L1 pour un outil en lecture seule **seulement** si le propriétaire a marqué le serveur comme fiable ; réglage par outil (L1…L3 ou masqué). Les indications d'un serveur non fiable ne baissent jamais le risque.
- Descriptions d'outils nettoyées (`InjectionGuard`) et signalées si suspectes ; résultats, ressources et modèles de message sont des données non fiables (tâche contaminée, passages suspects retirés).
- Destination = hôte du serveur : la règle « tâche contaminée + nouvelle destination → L2 » s'applique.
- Transport HTTP : https obligatoire hors réseau local ; jeton dans le coffre chiffré (jamais dans les réglages ni les journaux) ; redirections vérifiées par la garde SSRF ; seul l'hôte configuré est exempté de la règle « réseau local ».
- stdio uniquement via un worker appairé, serveurs déclarés par le propriétaire du worker, environnement minimal.
- Cortana n'annonce ni échantillonnage, ni élicitation, ni racines : un serveur qui demande une saisie reçoit un refus.

## Agents externes A2A (phase 21)

- Délégation sortante uniquement : Cortana n'expose aucun point d'entrée A2A ; un agent externe ne peut ni appeler ses outils ni lire sa mémoire.
- Le message envoyé est exactement l'aperçu approuvé (objectif, contexte explicite, artefacts joints) ; un secret enregistré dans le message → refus.
- Réponses non fiables (injection retirée, tâche contaminée) ; fichiers reçus → artefacts plafonnés, jamais ouverts ; URL de fichier soumises à la garde SSRF.
- Délai ou annulation → la tâche distante est annulée ; 6 délégations par minute et par agent ; https hors réseau local ; jeton chiffré.

## Plugins (phase 22)

- Aucun code de plugin n'est chargé ni exécuté dans Cortana (format déclaratif ; LAW-015) ; ce qui s'exécute passe par MCP/A2A hors processus, sous la politique commune.
- Paquet signé (ECDSA P-256), toutes les empreintes vérifiées avant la moindre écriture ; clé de l'éditeur approuvée par le propriétaire puis épinglée pour les mises à jour ; pas de retour à une version plus ancienne.
- Isolation : compétences limitées aux outils déclarés et installées désactivées ; serveurs/agents limités aux hôtes déclarés, en https, non fiables ; secrets fournis par le propriétaire et jamais lisibles par le plugin ; documents vérifiés à chaque lecture et traités comme contenu tiers.
- Installation, mise à jour et suppression journalisées et reprises au démarrage : jamais d'état à moitié installé.

## Documents et données (phase 24)

- Tout texte extrait d'un document (DOCX, PDF, tableur, présentation, HTML, archive) est une donnée : la tâche est contaminée (`document:<nom>`) et les passages qui s'adressent à l'assistant sont retirés et signalés (`InjectionGuard`).
- XML : toute déclaration DOCTYPE est refusée (XXE) ; entités externes jamais résolues.
- Archives : chemins sortants (`..`, absolus, lecteurs), liens symboliques ou physiques et fichiers spéciaux refusés ; nombre d'entrées, taille par fichier et taille totale décompressée bornés (bombes) ; archives imbriquées listées, jamais développées ; fichiers exécutables signalés, jamais ouverts ni installés.
- HTML : scripts, styles, formulaires, cadres et objets supprimés avant lecture.
- PDF : aucun script, action ou pièce jointe n'est exécuté ni ouvert ; un PDF chiffré n'est jamais modifié (aucune protection retirée).
- Les sources ne sont jamais modifiées en place : chaque résultat est un nouvel artefact haché avec sa provenance ; écrire dans le dossier du propriétaire demande son accord (L2) et annonce le remplacement d'un fichier existant.

## Médias (phase 25)

- Tous les appels aux fournisseurs d'images, d'audio et de vidéo passent par la passerelle (LAW-001 étendue) : mode « Local uniquement », santé et plafonds de dépense s'appliquent.
- Le type d'un fichier est déterminé par son contenu ; une image annonçant plus de 60 mégapixels est refusée avant tout décodage ; le décodage est sous-échantillonné.
- Une image ne quitte la tablette que réencodée, sans métadonnées (position GPS, appareil, logiciel) ; la position n'est donnée au modèle que sur demande explicite et n'est jamais envoyée à un fournisseur.
- Les réponses des fournisseurs sont vérifiées (vraie image, audio ou vidéo, tailles bornées) avant de devenir des artefacts ; une URL renvoyée n'est téléchargée qu'en https et sous la règle SSRF ; rien n'est ouvert, lu ni exécuté automatiquement.
- Analyses, transcriptions et inspections sont des données non fiables : la tâche est contaminée et les passages qui s'adressent à l'assistant sont retirés.

## Connexions et canaux (phase 26)

- Un seul gestionnaire de connexions ; les outils et canaux n'atteignent une connexion que par son nom, jamais un secret : les secrets sont des poignées du coffre, injectés en en-tête au dernier moment, absents des résultats, des journaux et des événements.
- OAuth 2.1 : PKCE S256 seulement, état secret à usage unique vérifié en temps constant, URI de retour exacte, https hors réseau local ; la révocation révoque les jetons chez le fournisseur et efface tout localement.
- API HTTP : hôte, schéma, port et chemin de base fixés par le propriétaire ; méthodes permises ; `Authorization`/`Cookie` réservés ; redirections non suivies ; réponses bornées et non fiables ; écriture L2, suppression L3.
- Webhooks entrants : signature HMAC, fraîcheur ± 5 min, rejeu refusé, débit borné, file bornée, tâches contaminées et contenu nettoyé ; webhooks sortants signés, avec clé d'idempotence, en L2.
- Maison : domaines permis choisis par le propriétaire ; serrures, alarme, volets et vannes en L3.
- E-mail : TLS ou STARTTLS (clair seulement sur le réseau local) ; lecture non fiable ; envoi, réponse et transfert en L2 avec le message exact ; en-têtes protégés contre l'injection ; rien n'est supprimé.
- Messagerie : seules les discussions autorisées sont écoutées ; les autres sont rejetées et auditées ; aucun adaptateur n'appelle un outil ; les réponses passent par l'outbox.

## Amélioration (phase 27)

- Le service d'amélioration n'exécute aucun outil, n'appelle aucun modèle et ne touche ni fichiers ni dépôts (LAW-019, `ArchitectureRulesTest`).
- Rien n'est appliqué sans le propriétaire ; le modèle ne voit les propositions qu'en lecture (`improvement.list`, L0).
- Réglages modifiables : liste fermée et bornée (limites de tâche, outils proposés, plafond de dépense, fournisseur par défaut existant). Politique, autorisations, destinations connues, confidentialité, secrets et connexions en sont exclus.
- Un raccourci ne peut viser qu'une capacité sans effet externe, ≤ L1, hors pilotage de l'écran ; il passe par le dispatcher et la politique comme toute action.
- Chaque application garde la valeur remplacée ; l'annulation est refusée si le propriétaire a modifié la valeur depuis. Toute décision est auditée.

## Observabilité (phase 28)

- Les spans ne portent aucun contenu : les clés porteuses de contenu (prompt, completion, content, messages, input, output, arguments, body, text, objective, query, reply) sont ignorées ; les valeurs sont expurgées par le `Redactor` et bornées.
- Les traces restent sur la tablette ; l'export OTLP est désactivé par défaut, n'accepte que https (http uniquement vers l'appareil ou le réseau local), refuse les identifiants dans l'adresse et les redirections ; l'en-tête d'authentification est une poignée du coffre. Test : `ObservabilityTest` (secret enregistré absent des lignes, du visualiseur, de l'outil et de la charge OTLP).

## Sauvegarde, restauration, diagnostic (phase 29)

- Les secrets ne quittent jamais la tablette en clair : ils ne sont inclus que sur demande explicite, et alors la sauvegarde entière est chiffrée (PBKDF2-HMAC-SHA256 210 000 itérations → AES-256-GCM par entrée, nom en données associées, HMAC du manifeste ; phrase de passe ≥ 12 caractères). Sans phrase de passe, aucune valeur secrète n'est écrite (seules les poignées) — vérifié par `BackupTest`.
- Une sauvegarde est une donnée non fiable : noms d'entrées validés (pas de traversée de chemin), taille bornée, entrées non déclarées refusées, empreintes vérifiées avant toute écriture, manifeste authentifié si chiffré, schéma plus récent refusé ; aucun effet en attente (outbox), registre anti-répétition, approbation, audit ou état de reprise n'est importé.
- Restauration uniquement par le propriétaire dans l'application, jamais par une capacité ; simulation d'abord ; état courant sauvegardé avant ; remplacement confirmé explicitement.
- Diagnostic : lecture seule ; réparations explicites, auditées, réversibles quand c'est possible ; la chaîne d'audit rompue est signalée et conservée comme preuve.

## Mises à jour (phase 30)

- Manifeste signé par la clé qui signe l'APK et vérifié avec le certificat de l'application installée ; APK accepté seulement s'il a le même paquet, exactement le même certificat, une version supérieure, l'empreinte et la taille du manifeste signé ; https obligatoire (boucle locale exceptée).
- Aucune installation silencieuse : préparation et installation sont des actions du propriétaire, et Android demande sa confirmation ; aucune capacité ne peut les déclencher. Aucun code téléchargé n'est chargé par Cortana.
- Sauvegarde complète avant toute installation ; secrets de publication jamais dans le code (variables d'environnement, clé privée par tube).

## Durcissement (phase 32)

- Sortie réseau : hôtes bloqués jamais joints (politique + intercepteur HTTP commun) ; mode « confirmer les nouvelles destinations » ou « destinations connues seulement » au choix du propriétaire ; SSRF inchangé.
- Injection par dépôt : lignes suspectes signalées au modèle comme données ; après une tentative détectée, plus aucune autorisation permanente et confirmation de toute action sensible.
- Secrets : valeurs lues uniquement par leur service propriétaire, jamais par un contexte de tâche ; inventaire, rotation sous la même poignée, purge des valeurs orphelines ; rien de tout cela ne révèle une valeur.
- Chaîne d'approvisionnement : empreintes SHA-256 vérifiées pour chaque dépendance (`gradle/verification-metadata.xml`), SBOM CycloneDX et inventaire des licences (`./gradlew cortanaSbom`, `docs/LICENSES.md`), recherche de secrets et de clés dans les fichiers suivis (`SupplyChainTest`), aucun chargement de code dynamique. Signatures PGP des dépendances non vérifiées (clés publiques non disponibles hors ligne) : les empreintes SHA-256 les remplacent.

## Publication (phase 33)

- Identité figée : paquet et certificat de signature vérifiés à chaque build release (`verifyReleaseVersion` refuse une version non monotone, un historique à certificat variable et **l'absence de la clé de publication** — plus de repli silencieux sur la clé de debug) ; certificat public et manifeste signé versionnés dans `release/`, vérifiés par le code de l'application (`ReleaseTest`).
- Expressions régulières : compilées par ICU4C (moteur d'Android) avant chaque release (D-20260928-066). Leur sémantique diffère de la JVM des tests : sous ICU, `\w`, `\d`, `\s`, `\b` et `(?i)` sont Unicode. Les motifs de détection (injection, commandes Git destructrices, classification des risques) détectent donc au moins autant sur l'appareil ; les validateurs d'identifiants et de secrets utilisent des classes explicites (`[A-Za-z0-9_-]`) au comportement identique. Seule nuance relevée : dans `Redactor`, un `\b` devant un préfixe de clé (`sk-`, `ghp_`…) collé à une lettre accentuée ne délimite plus le mot sous ICU ; le masquage des valeurs de secrets connues, indépendant des motifs, reste appliqué.
- Le magasin de clés et ses mots de passe ne sont ni dans le dépôt, ni dans l'archive source (`git archive` des seuls fichiers suivis, contrôlée), ni dans les livraisons postérieures à la 1.2.0 ; l'outil de manifeste lit le mot de passe dans l'environnement.

## Conseil de réflexion (2.0.0-rc3, D-20260929-067)

- Désactivé par défaut ; le propriétaire l'active dans Réglages › Intelligence. Couper l'interrupteur neutralise le moteur sans effacer de données.
- Subordonné : aucun agent n'écrit dans la conversation, la mémoire ou l'état de la tâche ; tout appel de modèle passe par le `ModelGateway`, tout appel d'outil par le `ToolDispatcher` (politique, audit, ledger). Lois vérifiées par `ArchitectureRulesTest` (subordination, pas de raisonnement persisté, pas de récursion, pas de framework multi-agents).
- Outils des agents : lecture seule stricte (aucun effet, niveau ≤ L1, ni contrôle de l'écran, ni question au propriétaire, ni écriture mémoire, ni procédure), appels non interactifs — une action qui demanderait une confirmation est refusée, jamais demandée ; aucun secret (`resolveSecret` rend toujours null) ; découverte d'outils limitée à ce périmètre.
- Actions : un agent ne fait que proposer. Une décision qui implique une action repasse par le chemin normal (planificateur, politique, confirmations, biométrie) ; même quatre agents d'accord n'autorisent pas une action L2/L3 (`CouncilTest.c9ProposedActionsGoThroughTheNormalPathAndItsPolicy`, jeu de référence `security-02`, `security-06`, `code-08`).
- Contenu non fiable (web, fichiers, MCP…) : enveloppé comme données, lignes qui s'adressent à un assistant signalées, taint propagé de l'affirmation à la décision puis à la tâche (qui applique ensuite sa vérification renforcée).
- Sortie des modèles : JSON strict ; un champ inconnu ou un « appel d'outil » glissé dans la réponse est un rejet, jamais exécuté.
- Invites : construites par le code à partir de parties typées ; l'objectif et les faits sont expurgés (`Redactor`) ; aucune clé d'API dans un corps de requête (`CouncilTest.c9SecretsNeverReachAModel`).
- Juge : candidats anonymisés (A, B…), sans auteurs, modèles ni votes.
- Persistance : résumés structurés seulement (tables `council_*`, v3) ; jamais d'invite, de sortie brute, de raisonnement privé ou de bloc de raisonnement fournisseur.
- Confidentialité : « Local uniquement » (réglage global ou contrainte de la demande) refuse tout modèle non local pour chaque rôle, repli compris ; « local uniquement » par rôle aussi.
- Coûts : budget par séance (jetons, coût si connu, appels, outils, durée, parallélisme), jamais supérieur à ce qui reste du budget de la tâche ; plafond quotidien ; seuil de réduction en mode Auto ; dégradation annoncée.
- STOP et annulation : chaque agent est annulé, les résultats tardifs ignorés, la séance marquée annulée ; un conseil ne peut pas lancer un conseil.

## Chat Workspace (2.0.0-rc4, D-20260930-068)

- Couche de présentation : aucun appel de modèle, d'outil ou d'approbation hors des services existants ; toute génération passe par l'orchestrateur. Lois vérifiées par `ArchitectureRulesTest` (WORKSPACE-1 à 3, comparaison sans outil, partage sans envoi).
- Approbations : la carte du fil montre l'action, la cible, le risque et le caractère réversible. Elle peut **refuser**, ou rouvrir l'écran sécurisé (FLAG_SECURE, lié à l'action exacte) ; elle n'autorise jamais elle-même une action L2 ou L3.
- Rendu : Markdown maison vers composants natifs ; HTML affiché comme texte ; aucun WebView ; liens limités à `https`, `http`, `mailto`, `tel`, `artifact:` ; `javascript:`, `data:`, `file:`, `content:`, `intent:` et les liens relatifs sont retirés.
- Jamais affichés : raisonnement privé, blocs de raisonnement des fournisseurs, invites système, messages cachés (instructions de réparation ou de continuation).
- Pièces jointes : stockées comme artefacts (nom assaini, 100 Mo au plus, flux borné). Le contenu lu entre dans le contexte comme **données non fiables enveloppées** et marque la tâche, qui applique ensuite sa vérification renforcée. Le mode « référence » ne lit rien.
- Partage Android et texte sélectionné : deviennent un brouillon d'une nouvelle discussion, jamais envoyé sans le propriétaire ; les `file://` venant d'une autre application sont refusés.
- File d'attente : un message ancien, ou écrit avant un redémarrage, attend une nouvelle confirmation. La file n'est pas sauvegardée : jamais d'envoi automatique sur une autre instance.
- Régénérer ou continuer ne rejoue jamais un raccourci déterministe (pas d'effet répété) ; continuer est une nouvelle demande explicite, pas un renvoi aveugle ; 413 : une seule réduction, jamais la même charge deux fois.
- Comparaison : aucun outil offert, donc aucune action multipliée. La fusion reçoit les réponses comme données ; la majorité n'est jamais une preuve.
- Conseil : le mode « Conseil » ne contourne jamais l'interrupteur `council.enabled`.
- Export : branche visible seulement, sans message caché ni sortie brute d'outil, secrets masqués (`Redactor`).
- Retour d'expérience « signaler un problème » : enregistré dans l'audit local, rien ne quitte la tablette.

## Données

- Migrations Room explicites et testées, jamais destructives (`ArchitectureRulesTest.noGlobalScopeAndNoDestructiveMigration`, `DatabaseMigrationTest`) ; la revue signale aussi toute migration destructive ajoutée à un projet.
- Sauvegarde automatique de la base avant migration d'un ancien schéma.
- Mode incognito : aucune mémoire, aucun épisode, aucune procédure apprise.
- Mémoire : export avec provenance, effacement complet, rétention (épisodes 90 j, non confirmés 30 j).

## Non vérifié ici (niveaux A/P)

- Android Keystore matériel, biométrie réelle, verrouillage de l'appareil (Robolectric n'a pas d'AndroidKeyStore).
- Attaque par superposition d'écran, service d'accessibilité réel, restrictions « paramètres restreints ».
- JGit sur ART, worker sur deux machines distinctes, worker Windows/macOS.
- Performances et consommation sur la tablette.

Ils exigent la tablette physique (ou un émulateur, absent de cet environnement). Ils restent marqués « non exécuté » dans `TEST_MATRIX.md` ; la procédure de vérification sur l'appareil est `RC_CHECKLIST.md`.
