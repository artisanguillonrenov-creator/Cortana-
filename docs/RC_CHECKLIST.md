# Checklist RC sur appareil physique — Cortana 2.0.0-rc3

**Statut : NON EXÉCUTÉE pour la rc2 ni pour la rc3.** L'environnement de construction n'a ni émulateur ni tablette : aucune ligne ci-dessous n'a été vérifiée sur un appareil par l'équipe de construction, et aucune n'est comptée comme réussie dans `RELEASE.md`. Phase 33 de la feuille de route : **BLOCKED_EXTERNAL** (tablette du propriétaire requise).

**Premier retour réel (rc1, 28/09/2026, rapport de bug du propriétaire)** : ligne 1.2 ❌ — **blocker critique**, plantage au démarrage (`PatternSyntaxException` : expression régulière refusée par le moteur ICU d'Android, acceptée par la JVM des tests). Corrigé en rc2 (D-20260928-066) ; toutes les expressions régulières sont désormais compilées par ICU4C avant chaque release. Les lignes ci-dessous sont à dérouler avec la **rc3** (§14 : Conseil de réflexion, nouveau).

Appareil cible : Samsung Galaxy Tab A11 (arm64-v8a, Android 15 / One UI 7) — APK `cortana-2.0.0-rc3-arm64-v8a.apk` ; sinon l'APK universel.
Chaque ligne se remplit ainsi : `☐` → `✅` (conforme) ou `❌` (écart, avec date, étapes et capture), et la colonne *Réf.* renvoie au test exécuté dans l'environnement de construction qui couvre la même logique.

**Blocker critique** (la RC ne peut pas devenir 2.0.0) : perte ou corruption de données, migration 1→2 ou 2→3 en échec, installation refusée ou identité changée, plantage au démarrage, action L2/L3 exécutée sans approbation, STOP inefficace, secret visible (écran, journal, export, trace). Tout autre écart est noté et classé (majeur / mineur) dans le rapport RC.

## 0. Préparation

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 0.1 | En 1.2.0 : noter le nombre de conversations, de souvenirs, de planifications, les fournisseurs configurés | Relevé écrit | — | ☐ |
| 0.2 | Conserver `cortana-release.apk` 1.2.0 et exporter manuellement les réglages importants | Copies hors de la tablette | — | ☐ |
| 0.3 | Batterie > 50 %, Wi-Fi, verrouillage de l'écran actif (requis pour L3) | — | — | ☐ |
| 0.4 | Vérifier l'empreinte des APK reçus (`sha256sum`) contre `RELEASE.md` §7 | Identiques | — | ☐ |

## 1. Installation par-dessus la 1.2.0 (ou la rc1, la rc2) et migration

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 1.1 | `adb install -r cortana-2.0.0-rc3-arm64-v8a.apk` (ou ouverture du fichier) sans désinstaller, par-dessus la 1.2.0, la rc1 ou la rc2 | « Mise à jour » acceptée par Android (même paquet, même certificat) ; aucune désinstallation demandée | `UpdateTest.releaseHistoryIsMonotoneUnderOneCertificate`, `ReleaseTest` | ☐ |
| 1.2 | Premier lancement | Pas de plantage ; migration 1→2 (déjà faite si la rc1 ou la rc2 a été lancée) puis 2→3 ; fichier `pre-migration` présent dans les sauvegardes | `DatabaseMigrationTest.v1ToV2PreservesEveryRowAndIndexes`, `v2ToV3IsAdditiveAndTheCouncilStoreWorks`, `v1ToV3InOneUpgrade`, `preMigrationBackupIsWrittenForOlderSchema`, `AndroidRegexCompatTest`, `StartupAndRegexEngineTest` (A) | rc1 : ❌ plantage (corrigé) · rc2 : ☐ · rc3 : ☐ |
| 1.3 | Comparer avec 0.1 | Mêmes conversations, souvenirs, planifications, fournisseurs ; recherche plein texte avec accents | idem | ☐ |
| 1.4 | Santé → Diagnostic de la base | Tous les contrôles sains | `BackupTest` (Doctor) | ☐ |
| 1.5 | Rappels 1.2.0 existants | Toujours programmés et délivrés à l'heure | `AndroidLifecycleTest.rebootRearmsCatchesUpAndRecovers` | ☐ |

## 2. Tests instrumentés (niveau A)

| # | Commande | Attendu | Résultat |
|---|---|---|---|
| 2.1 | `./gradlew :app:connectedDebugAndroidTest` (tablette en USB, service d'accessibilité de l'app debug activé) | `StartupAndRegexEngineTest` (4 : démarrage et écran principal, paramètres de procédure, 328 expressions régulières compilées par le moteur d'Android, initialisation de chaque classe dans ART), `AccessibilityFixtureTest` (2) et `VisionFixtureTest` (1) verts | ☐ |

## 3. Non-régression 1.2.0

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 3.1 | « Rappelle-moi dans 2 minutes de boire » en mode avion | Rappel créé sans modèle, notification à l'heure | `OrchestratorEndToEndTest.reminderFastPathNeedsNoModel` | ☐ |
| 3.2 | « Retiens que je préfère le thé » | Souvenir actif immédiatement | `OrchestratorEndToEndTest` | ☐ |
| 3.3 | STOP pendant une tâche qui pilote l'écran | Arrêt immédiat, discussion toujours possible sans outils | `OrchestratorEndToEndTest.killSwitchRemovesToolsButKeepsChat` | ☐ |
| 3.4 | Push-to-talk, lecture à voix haute | Inchangés | — | ☐ |
| 3.5 | Pilotage d'une application réelle (Paramètres, Chrome) par l'accessibilité | Actions correctes ; champs mot de passe jamais lus | `AccessibilityFixtureTest` | ☐ |

## 4. Arrêts brutaux et reprise

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 4.1 | Tâche en plusieurs étapes, puis `adb shell am force-stop` au milieu | Au relancement : reprise au point de reprise, étapes faites non rejouées | `RuntimeTest.crashResumeContinues…` | ☐ |
| 4.2 | Même chose pendant un envoi (SMS/e-mail) | Effet incertain soumis au propriétaire, jamais renvoyé seul | `RuntimeTest.uncertainSideEffectAfterCrash…` | ☐ |
| 4.3 | Redémarrage de la tablette pendant une tâche planifiée | Exécution `interrupted`, tâche close, file reprise | `AutomationTest.restartMarksRunningRunsInterruptedAndResumesTheQueue` | ☐ |
| 4.4 | Tablette éteinte à l'heure d'un rappel et d'une tâche récurrente | Rappel « en retard » délivré une fois ; tâche selon sa politique (`skip` / `catch_up_once`) | `AndroidLifecycleTest.rebootRearmsCatchesUpAndRecovers`, `AutomationTest.missedRunsFollowTheSchedulePolicy` | ☐ |
| 4.5 | Coupure pendant l'installation d'un plugin | Terminée ou annulée au démarrage, sans fichier orphelin | `PluginTest.interruptedOperationsAreFinishedOrUndoneAtStartup` | ☐ |

## 5. Permissions et capacités (One UI)

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 5.1 | Écran Capacités et permissions | États et raisons cohérents avec les autorisations réelles ; bouton « Corriger » ouvre le bon écran système | `AndroidLifecycleTest.capabilitiesShowStateReasonFixLastUseAndRevocation` | ☐ |
| 5.2 | Retirer Contacts dans Paramètres pendant que l'app tourne, puis demander un contact | Refus clair, capacité « indisponible » | `AndroidLifecycleTest.aRevokedPermissionIsSeenLiveAndTheExecutorRefusesClearly` | ☐ |
| 5.3 | Désactiver les alarmes exactes | Rappels « dégradés », signalés | idem | ☐ |
| 5.4 | Désactiver le service d'accessibilité | Pilotage d'écran indisponible, reste de l'app intact | — | ☐ |
| 5.5 | Sans verrouillage d'écran : écran Capacités, puis reprise après STOP | Capacités L3 signalées (besoin « Verrouillage de l'appareil ») ; reprise après STOP avertie « sans vérification » et auditée | `AndroidLifecycleTest.capabilitiesShowStateReasonFixLastUseAndRevocation` | ☐ |
| 5.5 bis | Avec verrouillage : reprise après STOP | Empreinte ou code demandé ; refus → STOP maintenu | — (A/P uniquement) | ☐ |
| 5.6 | Application en superposition pendant une confirmation | Confirmation non validable par la superposition | — (A/P uniquement) | ☐ |

## 6. Communications

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 6.1 | SMS à son propre numéro | Aperçu, approbation L2, envoi réel, trace | `CommsTest` | ☐ |
| 6.2 | Appel vers un numéro inconnu | L3, confirmation forte | `CommsTest.ambiguousShortAndEmergency…` | ☐ |
| 6.3 | Création d'un événement d'agenda Samsung/Google | Événement visible dans l'agenda | `CommsTest` | ☐ |
| 6.4 | Notification entrante contenant « ignore tes instructions » | Tâche contaminée, aucune action sans approbation | `CommsTest.notificationTriggersStartTaintedTasks…` | ☐ |

## 7. Voix

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 7.1 | Mot d'éveil activé, phrase non adressée | Ignorée | `VoiceTest.wakePhraseAndStopPhrase` | ☐ |
| 7.2 | Interruption pendant la synthèse (haut-parleur) | Arrêt de la voix, pas d'auto-déclenchement par l'écho | `VoiceTest` (gate) | ☐ |
| 7.3 | Reconnaissance hors ligne (mode avion) | Transcription locale | `VoiceTest.androidRecognizer…` | ☐ |

## 8. Vision, navigateur, recherche

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 8.1 | Bouton dessiné sans accessibilité (jeu, canvas) | Trouvé par OCR/modèle de vision réel | `VisionTest`, `VisionFixtureTest` | ☐ |
| 8.2 | Recherche sur un sujet d'actualité | Sources réelles classées, citations vérifiées | `BrowserTest.researchRanksReadsVerifiesDeduplicatesAndCites` | ☐ |
| 8.3 | Page avec instructions cachées | Signalée, aucune action | `BrowserTest.injectionGuard…` | ☐ |
| 8.4 | URL vers `http://192.168.x.x` de la box | Bloquée (SSRF) sauf autorisation explicite | `BrowserTest.ssrfGuard…`, `HardeningTest.dnsRebindingNeverReachesAPrivateAddress` | ☐ |

## 9. Développement et worker

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 9.1 | Cloner un petit dépôt, lire, modifier, committer (JGit sur ART) | Correct, aucun push | `GitTest` | ☐ |
| 9.2 | Exécution locale (toybox) avec délai dépassé | Processus tué, sortie bornée | `ExecutionTest` | ☐ |
| 9.3 | Appairer un worker sur un PC du réseau local (QR/code) | HTTPS épinglé, capacités affichées | `WorkerIntegrationTest` | ☐ |
| 9.4 | Scénario de développement complet via le worker (build Gradle réel d'un projet exemple) | Plan, branche, correctif, tests, build, revue, rapport ; aucun push | `SoftwareFactoryTest` | ☐ |
| 9.5 | `java -jar cortana-worker.jar status --json` sur le PC | Mêmes données que la vue d'administration | `WorkerIntegrationTest.adminClientServesTheSameContractsAsTheApi` | ☐ |

## 10. Intégrations

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 10.1 | Connexion OAuth réelle (fournisseur choisi) puis révocation | Jetons refusés après révocation, secrets effacés | `ConnectionTest.gateOAuthConnectorFixtureWithRefreshAndRevocation` | ☐ |
| 10.2 | Boîte e-mail réelle (IMAP/SMTP) : recherche, réponse | Réponse dans le fil, approbation L2 | `ConnectionTest.emailConnectorAgainstARealImapAndSmtpServer` | ☐ |
| 10.3 | Telegram : message du propriétaire puis d'un tiers | Tiers ignoré et audité | `ConnectionTest.telegramOnlyListensToTheOwner…` | ☐ |
| 10.4 | Home Assistant réel : lecture, action sensible | Action sensible en L3 | `ConnectionTest.homeAssistantReads…` | ☐ |
| 10.5 | Webhook entrant envoyé depuis un service externe via le worker | Signature vérifiée, tâche contaminée | `ConnectionTest.inboundWebhooks…` | ☐ |
| 10.6 | Serveur MCP HTTP réel et MCP stdio via le worker | Outils découverts, annulation fonctionnelle | `McpTest` | ☐ |

## 11. Documents et médias

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 11.1 | Export d'un PDF et d'un classeur vers un dossier choisi (SAF) | Fichiers ouvrables dans les apps Samsung/Google | `DocumentTest.gateCreatesEditsAndExportsRepresentativeArtifactsWithProvenance` | ☐ |
| 11.2 | Rendu d'une page PDF | Image correcte (ART) | `DocumentTest.pdfIsCreatedReadEditedAndRendered` | ☐ |
| 11.3 | Synthèse vocale en fichier (moteur Samsung/Google) | Fichier audio lisible | `MediaTest.speechFilesAndTranscriptionsUseTheirOwnRoutes` | ☐ |
| 11.4 | Inspection d'une vidéo de la galerie | Durée, images extraites | `MediaTest.videoInspectionUsesTheLocalProbeAndExtractsFrames` | ☐ |
| 11.5 | Plugin installé depuis le sélecteur de fichiers, puis retiré | Aucune autre donnée touchée | `PluginTest.pluginIsAddedAndRemovedWithoutTouchingAnythingElse` | ☐ |

## 12. Sauvegarde, restauration, mise à jour

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 12.1 | Sauvegarde chiffrée exportée dans Téléchargements | Fichier présent, phrase requise | `BackupTest` | ☐ |
| 12.2 | Restauration sur une seconde tablette (ou après désinstallation/réinstallation) | Données, réglages, secrets et artefacts restaurés ; mauvaise phrase refusée | `BackupTest.gateBackupOfInstanceARestoresOnInstanceB` | ☐ |
| 12.3 | Mise à jour dans l'application : canal https hébergé par le propriétaire (`cortana-update.json` + APK) | Manifeste vérifié, sauvegarde préalable, installateur système, données conservées | `UpdateTest.gateUpgradeKeepsDataAndSignature`, `ReleaseTest` | ☐ |
| 12.4 | Manifeste modifié à la main sur le canal | Refusé (« signature du manifeste invalide ») | `UpdateTest.manifestsNotSignedByCortanaOrIncompatibleAreRefused` | ☐ |

## 13. Observabilité, sécurité, performances

| # | Vérification | Attendu | Réf. | Résultat |
|---|---|---|---|---|
| 13.1 | Trace d'une tâche réelle (fournisseur réel) | Arbre complet, aucun contenu ni secret | `ObservabilityTest.gateTaskShowsModelToolAndVerifierSpansWithoutSecrets` | ☐ |
| 13.2 | Export OTLP vers un collecteur réel (facultatif) | Spans reçus, en-tête depuis le coffre | `ObservabilityTest.otlpExport…` | ☐ |
| 13.3 | Rechercher un secret connu (clé d'API) dans : logcat, export de sauvegarde en clair (inspection), traces, rapport d'échec | Jamais présent | `SupplyChainTest`, `HardeningTest.secretHandles…` | ☐ |
| 13.4 | Démarrage à froid | < 3 s (à mesurer, noter la valeur) | — | ☐ |
| 13.5 | 3 000 messages, 1 000 souvenirs : recherche | < 500 ms (à mesurer) | `HardeningTest.storesStayFastUnderLoad` | ☐ |
| 13.6 | Batterie sur 24 h, mot d'éveil désactivé puis activé | Consommation notée ; aucun réveil anormal (One UI « Utilisation de la batterie ») | — (P uniquement) | ☐ |

## 14. Conseil de réflexion (2.0.0-rc3)

| # | Vérification | Attendu |
|---|---|---|
| 14.1 | Sans rien activer, poser une question complexe | Réponse comme en rc2, aucune carte « Résumé du conseil » (drapeau coupé) |
| 14.2 | Réglages › Intelligence › Conseil de réflexion : activer, mode Conseil 4, poser « Pour les vacances, maison en Bretagne ou appartement à Nice, avec deux enfants, 2000 €, sans voiture ? » | Barre « 4 analyses en cours » puis états par spécialiste en mots ; une seule réponse ; carte « Résumé du conseil » (consensus, accords, risques, x/4, durée) |
| 14.3 | Pendant 14.2, toucher STOP (ou Annuler) | Arrêt immédiat, aucune réponse tardive, tâche annulée |
| 14.4 | Mode Auto : « Bonjour », « Mets le volume à 5 » | Aucun conseil (chemin habituel, sans délai) |
| 14.5 | Par rôle : mettre le Challenger sur un second fournisseur (ex. Groq) et l'Analyste factuel sur un autre modèle | Détails techniques : trois modèles listés ; si un fournisseur échoue, repli annoncé |
| 14.6 | Mode « Local uniquement » avec un rôle réglé sur un modèle cloud | Le rôle passe au modèle local ; avis « confidentialité locale » |
| 14.7 | TalkBack sur la barre de progression et la carte | Phase et état de chaque spécialiste lus en mots ; titres annoncés |
| 14.8 | Tablette en paysage puis portrait ; téléphone si disponible | Puces et réglages repliés sans débordement |
| 14.9 | Plafond quotidien bas (ex. 10 000 jetons) après un conseil | Le conseil suivant ne se lance pas ; réponse habituelle |
| 14.10 | Tuer l'application pendant un conseil puis la rouvrir | Séance close « interrompue », la tâche suit la reprise habituelle, rien n'est rejoué |
| 14.11 | Mesures : durée d'un Conseil 4 (réseau réel), batterie sur 10 conseils, mémoire | Relever les valeurs (base des réglages par défaut) |

## 15. Chat Workspace (2.0.0-rc4)

| # | Vérification | Attendu |
|---|---|---|
| 15.1 | Ouvrir une ancienne conversation après la mise à jour | Messages dans le même ordre qu'en rc3, rien de perdu |
| 15.2 | Tablette paysage, puis portrait, puis téléphone si disponible | 3 colonnes / conversation et panneaux superposés / tiroir et feuille du bas ; composer toujours visible au-dessus du clavier |
| 15.3 | Écrire, fermer l'application, la rouvrir | Brouillon retrouvé |
| 15.4 | Envoyer pendant une réponse, réordonner la file, « Maintenant » | Message en file ; l'ordre est respecté ; « Maintenant » arrête la réponse en cours (texte gardé) puis envoie |
| 15.5 | Joindre un PDF et une image ; modes Lire / Analyser / Référence | Puces avec taille et mode ; réponse qui cite le document ; image affichée en miniature, zoom |
| 15.6 | Réponse longue avec code, tableau, formule et diagramme Mermaid | Copier et Enregistrer fonctionnent ; tableau défilant ; formule lisible ; Mermaid en source étiquetée |
| 15.7 | Modifier un ancien message ; régénérer une réponse ; naviguer « Version 1/2 », « Réponse 2/2 » ; onglet Branches | Aucune perte ; bascule instantanée |
| 15.8 | STOP pendant une réponse, puis Continuer | Texte partiel gardé « arrêtée », suite ajoutée sans répétition |
| 15.9 | Couper le Wi-Fi pendant une réponse | Réponse partielle « interrompue », Continuer / Régénérer proposés ; aucune requête renvoyée seule |
| 15.10 | Tuer l'application pendant une réponse et la rouvrir | Réponse partielle conservée « interrompue » |
| 15.11 | Épingler un message et une note ; « Résumer maintenant » ; voir le résumé | Épingles listées ; point de compactage visible avec son résumé ; ligne « Contexte compacté » dans le fil |
| 15.12 | Sélecteur de modèle › Comparer 2 puis 4 modèles ; arrêter un seul couloir ; Fusionner | Colonnes (tablette) / onglets (téléphone) ; le couloir arrêté garde son texte ; fusion signalant les désaccords |
| 15.13 | Mode Conseil avec le conseil désactivé, puis activé | Avis « désactivé » puis progression du conseil et carte |
| 15.14 | Action à confirmer (ex. « mets la luminosité à 20 % ») | Carte d'autorisation : Refuser fonctionne ; « Examiner et autoriser » ouvre l'écran sécurisé |
| 15.15 | Rechercher (Ctrl+F) un mot, filtrer par projet, ouvrir un résultat | La discussion s'ouvre sur le message (y compris sur une autre branche) |
| 15.16 | Exporter en Markdown ; importer le JSON exporté | Fichier dans Téléchargements/Cortana ; conversation importée identique (branche visible) |
| 15.17 | Partager un texte et un PDF depuis une autre application ; « Demander à Cortana » sur un texte sélectionné | Nouveau brouillon avec le contenu et la pièce jointe ; rien n'est envoyé seul |
| 15.18 | TalkBack : parcourir un fil, envoyer, écouter une réponse | Titres « Vous » / « Cortana » ; boutons nommés ; « Cortana répond… » puis « Réponse terminée. », jamais chaque mot |
| 15.19 | Mains libres (appui long sur le micro) ; « Interrompre » pendant la réponse ; Lire une réponse, Pause, Reprendre | Panneau vocal compact ; interruption immédiate puis écoute ; lecture reprise à la phrase interrompue |
| 15.20 | Densité Large, thème Contraste élevé, Réduire les animations | Tout reste lisible et utilisable ; aucune animation |
| 15.21 | Conversation de plusieurs milliers de messages | Défilement fluide ; ouverture rapide ; relever la mémoire utilisée |
| 15.22 | Menu d'un message : « Supprimer ce message et la suite… » (Annuler, puis Supprimer) ; « Convertir en tâche » ; menu d'une réponse : « Enregistrer dans Téléchargements » ; composer : « Coller une image ou un fichier copié » ; panneau › Outils | Annuler ne supprime rien, Supprimer demande toujours confirmation et garde les autres branches ; tâche en brouillon, jamais envoyée seule ; fichier dans Téléchargements/Cortana ; image copiée jointe ; outils de la discussion listés avec leur risque |

## 16. Design « Cortana Workspace » (2.0.0-rc5)

| # | Vérification | Attendu |
|---|---|---|
| 16.1 | Mettre à jour depuis la rc4 et ouvrir l'application | Nouveau design ; conversations, souvenirs, tâches et réglages intacts ; Réglages › Général › Interface permet de revenir à « Workspace (rc4) » ou « Classique » |
| 16.2 | Tablette en paysage, puis en portrait | Paysage : barre latérale, conversation et panneau « Tâche active » côte à côte. Portrait : tiroir (menu), rail d'activité, pilule « Tâche n/N » qui ouvre le panneau par-dessus |
| 16.3 | Mode développeur désactivé, puis activé (carte en bas de la barre latérale, ou Réglages › Développeur) | Développement, Worker et MCP masqués, puis visibles ; répartition « ≈ » visible dans Mémoire seulement en mode développeur |
| 16.4 | Demander une tâche en plusieurs étapes, STOP depuis la barre latérale, puis Reprendre | Tâche « En pause », plan conservé ; la reprise continue sans refaire les étapes terminées |
| 16.5 | Idem depuis le panneau, le rail (portrait) et la touche Échap (clavier) | Même comportement |
| 16.6 | Action à autoriser : Refuser, puis Reprendre ; recommencer avec « Autoriser une fois » | Pastille « Action refusée », tâche en pause, nouvelle demande à la reprise ; l'écran sécurisé s'ouvre et seul lui accorde ; pastille « Autorisation accordée une fois » |
| 16.7 | Appui long sur le grand STOP | Arrêt d'urgence ; « Reprendre » demande l'empreinte ou le code |
| 16.8 | Réglages › Streaming › Bouton STOP « Annuler la tâche », puis STOP pendant une tâche | La tâche est annulée (pas de Reprendre) |
| 16.9 | Puces d'outils : éteindre Fichiers, demander de lire un fichier | Cortana dit qu'elle n'a pas l'outil ; rallumer la puce le rend disponible |
| 16.10 | Historique : chercher « reunion » pour une conversation « Réunion… », filtres, Ouvrir, Brancher | Résultat trouvé sans accent ; comptes justes ; la conversation s'ouvre ; la copie s'ouvre |
| 16.11 | Tâches : planification activée ou désactivée, message en file (monter, retirer), journal d'une tâche terminée | Changements immédiats et conservés ; journal limité aux actions réellement exécutées |
| 16.12 | Mémoire : Retenir une suggestion, Chat temporaire, Oublier | Souvenir confirmé ; bandeau violet, aucun souvenir écrit pendant le chat temporaire ; souvenir retiré |
| 16.13 | Réglages : chaque section, « Tous les réglages avancés » | Valeurs réelles, modifiées et conservées ; l'écran avancé complet s'ouvre |
| 16.14 | Contraste élevé, Réduire les animations, TalkBack sur la barre latérale, le panneau et les cartes d'autorisation | Tout reste lisible ; aucune animation ; boutons nommés, statuts annoncés avec leur texte |
| 16.15 | Galaxy Tab A11 en paysage (environ 960 dp de large) | Comportement portrait (tiroir, rail), rien de coupé ; le panneau défile |

## Rapport RC

À la fin : nombre de lignes ✅ / ❌ / non faites, liste des blockers critiques (doit être vide), écarts majeurs et mineurs, versions (One UI, build Android), empreinte des APK testés. Le rapport est ajouté à `RELEASE.md` §9 ; la 2.0.0 finale n'est construite (versionCode supérieur à la dernière rc, même clé) qu'après un rapport sans blocker critique.
