# Cortana — note d'installation et de mise à jour (Galaxy Tab A11)

Tout se fait au doigt, sur la tablette.

- **La 2.0.0-rc3, rc2 ou rc1 est installée** → installez la rc4 par-dessus (§5.2), sans désinstaller : vos données sont conservées. La rc1 se fermait dès l'ouverture : la rc4 contient aussi ce correctif.
- **Cortana 1.2.0 est déjà installée** → allez directement au **§5 (mise à jour vers 2.0.0-rc4)**. Ne désinstallez pas : vous perdriez vos données.
- **Première installation** → §1 à §4 (5 minutes), puis §5.4 pour les réglages propres à VNext.

Fichier à utiliser : **`cortana-2.0.0-rc4-arm64-v8a.apk`** (Galaxy Tab A11) ; `cortana-2.0.0-rc4-universal.apk` seulement si Android refuse le premier (« application incompatible »). S'ils arrivent zippés (`.apk.zip`), touchez le fichier dans **Mes fichiers** → **Extraire**, puis utilisez l'APK extrait.

> **Version candidate.** Tous les tests exécutables sans appareil passent, mais la vérification complète sur la tablette reste à faire (liste à cocher : `RC_CHECKLIST.md`). Le premier essai réel, celui de la rc1, a révélé un plantage au démarrage que ces tests ne pouvaient pas voir ; il est corrigé et désormais contrôlé avec le moteur d'Android. Gardez vos habitudes de prudence pendant les premiers jours.

## 1. Installer l'application

1. Téléchargez le fichier **`cortana-2.0.0-rc4-arm64-v8a.apk`** sur la tablette (il arrive dans « Téléchargements »).
2. Ouvrez l'application **Mes fichiers** → **Téléchargements** → touchez **`cortana-2.0.0-rc4-arm64-v8a.apk`**.
3. La première fois, Android bloque l'installation : touchez **Paramètres** dans le message, activez **« Autoriser depuis cette source »** pour Mes fichiers, puis revenez avec la touche Retour.
4. Touchez **Installer**. Si Google Play Protect affiche un avertissement, touchez **Plus de détails → Installer quand même** (l'application n'est pas publiée sur le Play Store, c'est normal).
5. Touchez **Ouvrir**.

> À partir de 2027, Android pourra demander une étape supplémentaire pour les applications de développeurs non vérifiés : Options pour les développeurs → autoriser les applications de développeurs non vérifiés, puis attendre 24 h. Ce n'est pas un problème de Cortana.

## 2. L'assistant de configuration (au premier lancement)

1. **Bienvenue** : lisez les garde-fous, touchez **Suivant**.
2. **Fournisseur** : Infermatic est présélectionné. Collez votre **clé API**, touchez **Tester la connexion**, puis touchez le **modèle** que vous voulez utiliser. (Vous pourrez ajouter OpenRouter, Groq, etc. plus tard dans « Fournisseurs ».)
3. **Notifications** : touchez **Autoriser les notifications** → **Autoriser**.
4. **Accessibilité** (contrôle de l'écran) :
   - Touchez **Ouvrir Infos de l'appli** → en haut à droite **⋮** → **Autoriser les paramètres restreints** → confirmez avec votre code/empreinte → Retour.
   - Touchez **Ouvrir Accessibilité** → **Applications installées** → **Cortana — contrôle de l'écran** → activez → **Autoriser** → Retour.
   - Si l'interrupteur est grisé, refaites la première puce (le menu ⋮ n'apparaît parfois qu'après une première tentative).
5. **Batterie** (important sur Samsung) :
   - **Désactiver l'optimisation** → **Autoriser**.
   - **Ouvrir Infos de l'appli** → **Batterie** → **Non restreinte**.
   - Paramètres → **Entretien de l'appareil** → **Batterie** → **Limites d'utilisation en arrière-plan** → **Applis jamais en veille** → **+** → **Cortana**.
6. **Options** (facultatif) : dossier de travail pour vos fichiers, micro, « Modifier les paramètres système » (luminosité directe), alarmes exactes.
7. **STOP** : ajoutez la tuile **« Stop Cortana »** aux réglages rapides (tirez le panneau vers le bas → ✏️ → faites-la glisser). Touchez **Commencer à utiliser Cortana**.

L'écran **Santé** refait toutes ces vérifications à tout moment et propose un bouton « Corriger » pour chacune.

## 3. Vérifications rapides (§19)

- « Rappelle-moi dans 5 minutes de boire de l'eau » → la notification arrive 5 minutes plus tard.
- « Retiens que je préfère des réponses courtes » → visible dans **Mémoire**.
- « Ouvre les paramètres et mets la luminosité au maximum » → Cortana le fait, avec le bandeau « Cortana contrôle l'écran » et son bouton **STOP**.
- Un bouton « Payer » demande toujours votre empreinte ; les banques et gestionnaires de mots de passe sont interdits par défaut.

## 4. La clé de signature (à garder précieusement)

Le fichier **`cortana-keystore.jks`** et ses mots de passe (dans `KEYSTORE_A_CONSERVER.txt`, remis avec la 1.2.0 — ils ne sont jamais recopiés dans les livraisons suivantes) servent à fabriquer les **mises à jour** de Cortana. La 2.0.0-rc3 est signée avec cette même clé. Conservez-les en lieu sûr (par exemple dans votre gestionnaire de mots de passe et une copie sur une clé USB ou un cloud personnel). **Sans eux, une future version ne pourra pas s'installer par-dessus celle-ci** : il faudrait désinstaller Cortana et vous perdriez vos discussions, souvenirs et réglages.

## 5. Mise à jour vers 2.0.0-rc4 (Cortana VNext), depuis la 1.2.0, la rc1, la rc2 ou la rc3

La 2.0.0-rc4 est la **même application** (même nom de paquet, même clé de signature) avec un numéro de version plus élevé : Android l'installe **par-dessus** la 1.2.0, la rc1, la rc2 ou la rc3, et vos discussions, souvenirs, fournisseurs, clés API, rappels et réglages sont conservés. La base de données passe automatiquement au schéma 4 :
- depuis la 1.2.0 : 1 → 2 → 3 → 4 ;
- depuis la rc3 : 3 → 4, qui ajoute l'arbre des conversations et les tables de l'espace de discussion. Chaque conversation existante devient une branche unique, dans le même ordre.

**Si la rc1 est installée** : elle a très probablement déjà migré la base avant de se fermer (la migration a lieu à l'ouverture de la base, avant l'endroit du plantage) et gardé la copie `pre-migration` de l'ancienne. La rc4 ouvre la base migrée et y ajoute ses tables ; rien d'autre à faire que §5.2 puis §5.3.

### 5.1 Avant

1. Notez rapidement ce que vous voulez retrouver (nombre de discussions, deux ou trois souvenirs, vos rappels à venir) : cela sert à vérifier la mise à jour.
2. Vérifiez que vous avez toujours `cortana-keystore.jks` et `KEYSTORE_A_CONSERVER.txt` en lieu sûr (§4).
3. Batterie au-dessus de 50 %.

### 5.2 Installer par-dessus

1. Copiez **`cortana-2.0.0-rc4-arm64-v8a.apk`** dans « Téléchargements », ouvrez-le depuis **Mes fichiers**.
2. Android affiche **« Voulez-vous installer une mise à jour de cette application existante ? »** → **Installer**. Si le message parle d'une nouvelle installation ou d'un conflit de signature, **arrêtez** : ce n'est pas le bon fichier (ne désinstallez jamais Cortana pour « forcer »).
3. Touchez **Ouvrir**.

### 5.3 Premier lancement

- La migration de la base prend quelques secondes. Avant de modifier quoi que ce soit, Cortana copie l'ancienne base dans ses sauvegardes (`pre-migration`) : en cas de problème, elle reste récupérable.
- Vérifiez vos notes du §5.1 : discussions, souvenirs (**Mémoire**), rappels (**Planifications**).
- **Santé → Diagnostic de la base** : tout doit être vert. Si une ligne est orange, le bouton **Réparer** propose la correction (par exemple reconstruire l'index de recherche).
- **Santé → Capacités et permissions** : la liste indique ce qui est disponible, dégradé ou indisponible, pourquoi, et un bouton **Corriger** ouvre le bon écran d'Android.

### 5.4 Ce qui est nouveau à régler (tout est facultatif)

- **Espace de discussion (nouveau en rc4, actif par défaut)** : l'écran Discussion change d'allure.
  - En paysage : la liste des discussions à gauche, la conversation au centre, et le panneau de contexte à droite (bouton ⓘ).
  - Réglages → **Espace de discussion** : densité, thème (dont contraste élevé), Entrée pour envoyer, file d'attente, vitesse de lecture, réduction des animations. L'**Interface classique** de la rc3 y reste disponible, sur les mêmes données.
  - À essayer :
    - toucher **Modifier** sous un de vos messages, ou **Régénérer** sous une réponse (rien n'est effacé, flèches ‹ › pour naviguer) ;
    - toucher **STOP** pendant une réponse, puis **Continuer** ;
    - joindre un fichier avec **+** ; épingler un message au contexte (menu ⋮) ;
    - **Comparer plusieurs modèles** depuis le sélecteur de modèle ;
    - **Rechercher** (Ctrl+F avec un clavier) ;
    - **Partager** un texte ou un PDF depuis une autre application vers Cortana : il arrive en brouillon.

- **Conseil de réflexion (nouveau en rc3, désactivé)** : Réglages → **Intelligence — Conseil de réflexion** → activez l'interrupteur. En mode **Auto**, Cortana ne réunit un conseil que pour une décision, un sujet à risque, une recherche à recouper ou un développement non trivial ; une question simple reste instantanée. Pendant la séance, la barre du bas affiche « 4 analyses en cours » et l'état de chaque spécialiste ; **STOP** ou **Annuler** arrête tout. Sous la réponse, la carte **Résumé du conseil** dit si les spécialistes étaient d'accord, sur quoi, ce qui reste risqué et ce qui a été vérifié. Un conseil coûte plusieurs appels au modèle : fixez si besoin un **plafond de jetons par jour** (section Coûts et limites). Vous pouvez donner à chaque rôle un autre modèle ou fournisseur (section Modèles) ; avec « Local uniquement », seuls vos modèles locaux sont utilisés.

- **Première sauvegarde** : Réglages → **Sauvegarde et restauration** → choisissez une phrase de passe (12 caractères au moins) → Créer, puis **Copier dans Téléchargements** et gardez une copie hors de la tablette. Les secrets (clés API) n'y sont inclus que si vous le demandez, et toujours chiffrés.
- **Lecture visuelle de l'écran** : le service d'accessibilité peut désormais faire des captures pour les applications dont les boutons ne sont pas annoncés. Si « Regarde l'écran » répond « Capture non autorisée », désactivez puis réactivez Cortana dans Paramètres → Accessibilité → Applications installées. Réglage : Réglages → **Modèles spécialisés et confidentialité** → « Lecture visuelle de l'écran ». Dans une application bancaire, la capture reste refusée.
- **Voix mains libres** : bouton 🗣️ à côté de 🎤 (autorisation du micro demandée la première fois). Une barre rouge « Cortana écoute » et une notification restent visibles tant que le micro est ouvert ; « Arrêter l'écoute » ou dire « arrête d'écouter » le ferme. Réglages → **Voix** : moteurs, voix, mot d'éveil « Cortana », interruption.
- **Communications** : Santé → **Communications** pour autoriser contacts, agenda, notifications (et, sur un modèle avec téléphonie, appels et SMS). Sur une tablette Wi-Fi, appels et SMS sont refusés proprement. Chaque envoi vous est montré avant d'être fait.
- **Réseau et secrets** : Réglages → **Réseau** (mode standard ; « confirmer les nouveaux domaines » ou « domaines connus seulement » si vous voulez restreindre Cortana ; liste de blocage) et **Secrets** (clés rangées dans le coffre de la tablette, remplacement, secrets inutilisés).
- **Connexions, MCP, agents externes, plugins, worker** : uniquement si vous vous en servez ; chacun a sa section dans les Réglages et rien n'est actif par défaut.
- **Mises à jour dans l'application** : Réglages → **Mises à jour**. Pour les versions suivantes, vous pouvez déposer `cortana-update.json` et l'APK côte à côte sur un serveur https à vous et indiquer l'adresse du fichier `.json` ; Cortana vérifie la signature, sauvegarde vos données, puis Android vous demande de confirmer. Android demande alors une fois d'**autoriser Cortana à installer des applications** (bouton Corriger dans Capacités et permissions). Rien n'est jamais installé sans vous.

### 5.5 Vérifications rapides après la mise à jour

- « Rappelle-moi dans 2 minutes de boire de l'eau » → la notification arrive, même en mode avion.
- « Retiens que je préfère le thé » → visible dans **Mémoire**.
- Lancez une petite tâche (« Ouvre les paramètres d'affichage ») puis touchez **STOP** : tout s'arrête, la discussion reste possible.
- **Tâches** : la tâche apparaît avec son plan et sa trace (bouton Trace).

La liste complète des vérifications sur la tablette est dans **`RC_CHECKLIST.md`**. Si l'une d'elles échoue, notez l'heure et ce que vous avez fait : ce sera le rapport de la version candidate.

### 5.6 En cas de problème

- **L'installation échoue** : rien n'a changé, la version précédente reste en place avec ses données.
- **Cortana ne démarre plus après la mise à jour** : ne désinstallez pas. La copie `pre-migration` de l'ancienne base est dans les fichiers privés de Cortana ; elle se récupère avec un ordinateur (`adb`) — voir `RELEASE.md` §5.
- **Le conseil vous gêne** : coupez simplement l'interrupteur (Réglages → Intelligence) ; Cortana reprend exactement le comportement de la rc2, sans rien effacer.
- **Le nouvel écran de discussion vous gêne** : Réglages → Espace de discussion → Interface **Classique** (mêmes conversations).
- **Revenir à la 1.2.0 (ou à la rc2)** n'est possible qu'en désinstallant (Android refuse d'installer une version plus ancienne par-dessus) : vous perdriez ce qui a été créé depuis la mise à jour. Exportez d'abord une sauvegarde (§5.4) ; elle se restaurera dans une version VNext ultérieure, pas dans la 1.2.0.
