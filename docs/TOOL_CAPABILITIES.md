# Capacités d'outils (générées depuis le registre)

Ne pas modifier à la main : `ToolCapabilitiesDocTest` compare ce fichier au registre réel (régénérer avec `-Dcortana.regenerateDocs=true`). Nom de fonction exposé au modèle = identifiant avec `_` à la place de `.`.

Risque de base : L0 lecture, L1 réversible local, L2 confirmation du propriétaire, L3 empreinte/biométrie. Le risque effectif n'est jamais inférieur au risque de base : traits, classification des arguments, contenu non fiable et destination peuvent l'élever (`PolicyEngine`).

Total : 179 capacités.

## dev

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `artifact.export` | L2 | reversible | keyed | local |  | Exporter un artefact |
| `artifact.list` | L0 | none | intrinsic | local |  | Lister les artefacts |
| `build.run` | L2 | external | none | local | executes_code | Compiler le projet |
| `code.delete` | L2 | reversible | keyed | local |  | Supprimer un fichier du projet |
| `code.diagnostics` | L0 | none | intrinsic | local |  | Diagnostiquer le code |
| `code.impact` | L0 | none | intrinsic | local |  | Analyser l'impact |
| `code.patch.apply` | L1 | reversible | keyed | local |  | Modifier le code |
| `code.patch.preview` | L0 | none | intrinsic | local |  | Prévisualiser un patch |
| `code.patch.rollback` | L1 | reversible | keyed | local |  | Annuler une modification |
| `code.read` | L0 | none | intrinsic | local |  | Lire du code |
| `code.references` | L0 | none | intrinsic | local |  | Trouver les références |
| `code.rename` | L1 | reversible | keyed | local |  | Renommer un symbole |
| `code.search` | L0 | none | intrinsic | local |  | Chercher dans le code |
| `code.symbols` | L0 | none | intrinsic | local |  | Explorer les symboles |
| `dependency.inspect` | L0 | none | intrinsic | local |  | Inspecter les dépendances |
| `exec.run` | L2 | external | none | local | executes_code | Exécuter une commande |
| `lint.run` | L2 | external | none | local | executes_code | Analyser le code (lint) |
| `repo.blame` | L0 | none | intrinsic | local |  | Blame Git |
| `repo.branch.create` | L1 | reversible | intrinsic | local |  | Créer une branche |
| `repo.branches` | L0 | none | intrinsic | local |  | Branches Git |
| `repo.checkout` | L1 | reversible | intrinsic | local |  | Changer de branche |
| `repo.cherry_pick` | L1 | reversible | keyed | local |  | Cherry-pick |
| `repo.clone` | L1 | reversible | keyed | external | network_egress | Cloner un dépôt |
| `repo.commit` | L1 | reversible | keyed | local |  | Commit local |
| `repo.diff` | L0 | none | intrinsic | local |  | Diff Git |
| `repo.fetch` | L1 | none | intrinsic | external | network_egress | Fetch |
| `repo.init` | L1 | reversible | intrinsic | local |  | Initialiser Git |
| `repo.log` | L0 | none | intrinsic | local |  | Historique Git |
| `repo.merge` | L2 | reversible | keyed | local |  | Fusionner une branche |
| `repo.pull` | L2 | reversible | keyed | external | network_egress | Pull |
| `repo.push` | L1 | external | keyed | external | network_egress, user_visible_to_third_party | Pousser une branche |
| `repo.revert` | L1 | reversible | keyed | local |  | Revert |
| `repo.show` | L0 | none | intrinsic | local |  | Afficher un commit |
| `repo.status` | L0 | none | intrinsic | local |  | État Git |
| `repo.worktree.create` | L1 | reversible | keyed | local |  | Créer un worktree |
| `review.changes` | L0 | none | intrinsic | local |  | Relire les modifications |
| `test.run` | L2 | external | none | local | executes_code | Lancer les tests |
| `workspace.create` | L1 | reversible | keyed | local |  | Créer un projet |
| `workspace.inspect` | L0 | none | intrinsic | local |  | Analyser un projet |
| `workspace.open` | L0 | none | intrinsic | local |  | Ouvrir un projet |

## documents

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `archive.extract` | L1 | reversible | intrinsic | local |  | Extraire une archive |
| `archive.inspect` | L0 | none | intrinsic | none |  | Inspecter une archive |
| `document.compare` | L0 | none | intrinsic | none |  | Comparer deux documents |
| `document.convert` | L1 | reversible | intrinsic | local |  | Convertir un document |
| `document.create` | L1 | reversible | intrinsic | local |  | Créer un document |
| `document.edit` | L1 | reversible | intrinsic | local |  | Modifier un document |
| `document.extract` | L0 | none | intrinsic | none |  | Extraire d'un document |
| `document.read` | L0 | none | intrinsic | none |  | Lire un document |
| `pdf.edit` | L1 | reversible | intrinsic | local |  | Modifier un PDF |
| `pdf.read` | L0 | none | intrinsic | none |  | Lire un PDF |
| `presentation.create` | L1 | reversible | intrinsic | local |  | Créer une présentation |
| `presentation.edit` | L1 | reversible | intrinsic | local |  | Modifier une présentation |
| `spreadsheet.analyze` | L0 | none | intrinsic | none |  | Analyser des données |
| `spreadsheet.read` | L0 | none | intrinsic | none |  | Lire un classeur |
| `spreadsheet.write` | L1 | reversible | intrinsic | local |  | Écrire un classeur |

## files

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `file.delete` | L2 | irreversible | intrinsic | local | destructive | Supprimer un fichier |
| `file.find` | L0 | none | intrinsic | none |  | Chercher des fichiers |
| `file.list` | L0 | none | intrinsic | none |  | Lister des fichiers |
| `file.patch` | L2 | reversible | keyed | local |  | Modifier un fichier |
| `file.read` | L0 | none | intrinsic | none |  | Lire un fichier |
| `file.search` | L0 | none | intrinsic | none |  | Chercher dans les fichiers |
| `file.write` | L2 | reversible | keyed | local |  | Écrire un fichier |

## integrations

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `agent.delegate` | L2 | external | none | external | network_egress, user_visible_to_third_party | Déléguer à un agent externe |
| `agent.task` | L1 | external | intrinsic | external | network_egress | Suivre une tâche déléguée |
| `agents.list` | L0 | none | intrinsic | local |  | Agents externes |
| `connection.check` | L0 | none | intrinsic | external | network_egress | Tester une connexion |
| `connections.list` | L0 | none | intrinsic | none |  | Connexions |
| `email.archive` | L1 | reversible | keyed | external | network_egress | Archiver un e-mail |
| `email.attachment` | L1 | reversible | intrinsic | external | network_egress | Pièce jointe d'e-mail |
| `email.draft` | L1 | reversible | keyed | external | network_egress | Brouillon d'e-mail |
| `email.forward` | L2 | external | keyed | external | network_egress | Transférer un e-mail |
| `email.read` | L0 | none | intrinsic | external | network_egress | Lire un e-mail |
| `email.reply` | L2 | external | keyed | external | network_egress | Répondre à un e-mail |
| `email.search` | L0 | none | intrinsic | external | network_egress | Chercher des e-mails |
| `email.send` | L2 | external | keyed | external | network_egress | Envoyer un e-mail |
| `home.call` | L2 | external | keyed | external | network_egress | Commander la maison |
| `home.states` | L1 | none | intrinsic | external | network_egress | État de la maison |
| `http.request` | L1 | external | intrinsic | external | network_egress | Appeler une API |
| `mcp.prompts` | L1 | none | intrinsic | external | network_egress | Modèles MCP |
| `mcp.resources` | L1 | none | intrinsic | external | network_egress | Ressources MCP |
| `mcp.servers` | L0 | none | intrinsic | local |  | Serveurs MCP |
| `plugin.documents` | L0 | none | intrinsic | local |  | Documents de plugin |
| `plugins.list` | L0 | none | intrinsic | local |  | Plugins installés |
| `webhook.send` | L2 | external | keyed | external | network_egress | Envoyer un webhook |

## media

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `media.audio.transcribe` | L0 | none | intrinsic | local |  | Transcrire un fichier audio |
| `media.image.analyze` | L0 | none | intrinsic | local |  | Analyser une image |
| `media.image.edit` | L1 | reversible | intrinsic | external | network_egress | Retoucher une image |
| `media.image.generate` | L1 | reversible | intrinsic | external | network_egress | Générer une image |
| `media.image.transform` | L1 | reversible | intrinsic | local |  | Transformer une image |
| `media.providers` | L0 | none | intrinsic | none |  | Capacités média |
| `media.tts.synthesize` | L1 | reversible | intrinsic | local |  | Synthèse vocale en fichier |
| `media.video.generate` | L1 | reversible | intrinsic | external | network_egress | Générer une vidéo |
| `media.video.inspect` | L0 | none | intrinsic | none |  | Inspecter une vidéo |
| `media.video.status` | L1 | reversible | intrinsic | external | network_egress | Suivre une vidéo |

## service

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `ask_user` | L0 | none | intrinsic | none |  | Question au propriétaire |
| `improvement.list` | L0 | none | intrinsic | none |  | Propositions d'amélioration |
| `memory.forget` | L2 | reversible | intrinsic | local |  | Oublier un souvenir |
| `memory.save` | L1 | reversible | intrinsic | local |  | Enregistrer un souvenir |
| `memory.search` | L0 | none | intrinsic | none |  | Chercher dans la mémoire |
| `notify.owner` | L1 | external | keyed | local |  | Notifier le propriétaire |
| `observability.metrics` | L0 | none | intrinsic | none |  | Métriques |
| `observability.trace` | L0 | none | intrinsic | none |  | Trace d'une tâche |
| `schedule.create` | L1 | reversible | keyed | local |  | Planifier |
| `schedule.delete` | L2 | irreversible | intrinsic | local | destructive | Supprimer une planification |
| `schedule.list` | L0 | none | intrinsic | none |  | Lister les planifications |
| `schedule.runs` | L0 | none | intrinsic | none |  | Historique d'une planification |
| `schedule.update` | L1 | reversible | intrinsic | local |  | Modifier une planification |
| `skill.list` | L0 | none | intrinsic | none |  | Lister les procédures |
| `skill.run` | L0 | none | intrinsic | none |  | Rejouer une procédure |
| `tools.discover` | L0 | none | intrinsic | none |  | Chercher un outil |

## system

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `android.alarm.create` | L2 | reversible | keyed | local |  | Créer une alarme |
| `android.settings.open` | L1 | reversible | intrinsic | none |  | Ouvrir les paramètres |
| `android.share` | L2 | external | keyed | external | network_egress | Partager un texte |
| `android.system.brightness.set` | L1 | reversible | intrinsic | none |  | Régler la luminosité |
| `android.system.volume.set` | L1 | reversible | intrinsic | none |  | Régler le volume |
| `android.timer.create` | L1 | reversible | keyed | local |  | Lancer un minuteur |
| `calendar.event.create` | L1 | reversible | keyed | local |  | Créer un événement |
| `calendar.event.delete` | L2 | reversible | keyed | local |  | Supprimer un événement |
| `calendar.event.update` | L2 | reversible | keyed | local |  | Modifier un événement |
| `calendar.events.search` | L0 | none | intrinsic | local | privacy_sensitive | Consulter l'agenda |
| `calendar.list` | L0 | none | intrinsic | local | privacy_sensitive | Lister les agendas |
| `clipboard.read` | L1 | none | intrinsic | local | privacy_sensitive | Lire le presse-papiers |
| `clipboard.write` | L1 | reversible | none | local |  | Copier dans le presse-papiers |
| `contact.create` | L2 | reversible | keyed | local | privacy_sensitive | Créer un contact |
| `contacts.read` | L0 | none | intrinsic | local | privacy_sensitive | Lire un contact |
| `contacts.search` | L0 | none | intrinsic | local | privacy_sensitive | Chercher un contact |
| `doctor.check` | L0 | none | intrinsic | none |  | Diagnostic de la base |
| `notification.trigger.create` | L1 | reversible | keyed | local |  | Créer un déclencheur de notification |
| `notification.trigger.delete` | L1 | reversible | keyed | local |  | Supprimer un déclencheur |
| `notification.trigger.list` | L0 | none | intrinsic | local | privacy_sensitive | Lister les déclencheurs |
| `notifications.list` | L0 | none | intrinsic | local | privacy_sensitive | Lire les notifications |
| `notifications.reply` | L2 | external | keyed | external | network_egress, user_visible_to_third_party | Répondre à une notification |
| `phone.call.prepare` | L1 | reversible | none | local |  | Préparer un appel |
| `phone.call.start` | L2 | external | keyed | external | network_egress, user_visible_to_third_party | Passer un appel |
| `sms.compose` | L1 | reversible | none | local |  | Préparer un SMS |
| `sms.send` | L2 | external | keyed | external | network_egress, user_visible_to_third_party | Envoyer un SMS |

## ui

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `android.app.open` | L1 | reversible | intrinsic | none |  | Ouvrir une application |
| `android.intent.open` | L1 | external | intrinsic | external | network_egress | Ouvrir un lien |
| `android.nav.back` | L1 | reversible | none | none |  | Retour |
| `android.nav.home` | L1 | reversible | none | none |  | Accueil |
| `android.nav.recents` | L1 | reversible | none | none |  | Applications récentes |
| `android.ui.clear` | L1 | reversible | none | none |  | Vider un champ |
| `android.ui.click` | L1 | reversible | none | none |  | Toucher un élément |
| `android.ui.click_point` | L1 | reversible | none | none |  | Toucher des coordonnées |
| `android.ui.find` | L0 | none | none | none |  | Chercher un élément |
| `android.ui.long_click` | L1 | reversible | none | none |  | Appui long |
| `android.ui.look` | L0 | none | none | none |  | Regarder l'écran |
| `android.ui.observe` | L0 | none | none | none |  | Observer l'écran |
| `android.ui.paste` | L1 | reversible | none | none |  | Coller du texte |
| `android.ui.scroll` | L1 | reversible | none | none |  | Faire défiler |
| `android.ui.submit` | L1 | reversible | none | none |  | Valider la saisie |
| `android.ui.swipe` | L1 | reversible | none | none |  | Balayer l'écran |
| `android.ui.type` | L1 | reversible | none | none |  | Saisir du texte |
| `android.ui.wait_for` | L0 | none | none | none |  | Attendre un élément |

## web

| Capacité | Risque | Effet | Idempotence | Sortie de données | Traits | Libellé |
|---|---|---|---|---|---|---|
| `browser.back` | L1 | none | intrinsic | external | network_egress | Page précédente |
| `browser.click` | L1 | external | none | external | network_egress | Cliquer / envoyer un formulaire |
| `browser.download` | L1 | reversible | intrinsic | external | network_egress | Télécharger un fichier |
| `browser.extract` | L0 | none | intrinsic | local |  | Lire la page |
| `browser.navigate` | L1 | none | intrinsic | external | network_egress | Naviguer sur un site |
| `browser.tabs` | L0 | none | intrinsic | local |  | Onglets du navigateur |
| `browser.type` | L1 | reversible | intrinsic | local |  | Remplir un champ |
| `browser.upload` | L1 | reversible | intrinsic | local |  | Joindre un fichier |
| `browser.wait` | L1 | none | intrinsic | external | network_egress | Attendre sur une page |
| `github.actions` | L1 | none | intrinsic | external | network_egress | GitHub : intégration continue |
| `github.branches` | L1 | none | intrinsic | external | network_egress | GitHub : branches |
| `github.comment` | L2 | external | none | external | network_egress, user_visible_to_third_party | GitHub : commenter |
| `github.commits` | L1 | none | intrinsic | external | network_egress | GitHub : historique |
| `github.file` | L1 | none | intrinsic | external | network_egress | GitHub : lire un fichier |
| `github.issue.create` | L2 | external | none | external | network_egress, user_visible_to_third_party | GitHub : créer une issue |
| `github.issues` | L1 | none | intrinsic | external | network_egress | GitHub : issues |
| `github.pull.create` | L2 | external | none | external | network_egress, user_visible_to_third_party | GitHub : ouvrir une pull request |
| `github.pulls` | L1 | none | intrinsic | external | network_egress | GitHub : pull requests |
| `github.releases` | L1 | none | intrinsic | external | network_egress | GitHub : versions publiées |
| `github.repo` | L1 | none | intrinsic | external | network_egress | GitHub : dépôt |
| `github.search` | L1 | none | intrinsic | external | network_egress | GitHub : chercher dans le code |
| `github.tree` | L1 | none | intrinsic | external | network_egress | GitHub : arborescence |
| `research.run` | L1 | none | intrinsic | external | network_egress | Recherche approfondie |
| `web.fetch` | L1 | none | intrinsic | external | network_egress | Lecture d'une page web |
| `web.search` | L1 | none | intrinsic | external | network_egress | Recherche web |

