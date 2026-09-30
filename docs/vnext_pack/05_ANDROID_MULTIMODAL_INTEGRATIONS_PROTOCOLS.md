# 05 — ANDROID AVANCÉ, VISION, VOIX, COMMUNICATIONS, INTÉGRATIONS ET PROTOCOLES

## 1. Android Executor VNext

Conserver l'AccessibilityService actuelle et l'encapsuler derrière un contrat d'exécution plus riche.

### Observation

`UiObservation` doit contenir :

- package ;
- activity si disponible ;
- window id ;
- timestamp ;
- focused node ;
- semantic tree ;
- bounds ;
- text/contentDescription/hint ;
- resource ids ;
- roles/classes ;
- clickable/editable/scrollable ;
- selected/checked/enabled ;
- stable node fingerprints ;
- screenshotRef optionnel ;
- hash/fingerprint écran.

## 2. Sélecteurs UI robustes

Ordre de préférence :

1. resource id ;
2. accessibility id / view id ;
3. texte exact + contexte ;
4. content description ;
5. rôle + voisinage ;
6. sélecteur composite ;
7. vision ;
8. coordonnées.

Chaque sélecteur peut stocker alternatives et confiance.

## 3. Screenshot + Vision fallback — MUST

Lorsque l'arbre accessibilité est insuffisant :

- capture via API Android supportée ;
- redaction éventuelle de zones sensibles ;
- OCR local si possible ;
- multimodal model si configuré ;
- détection de régions ;
- mapping coordonnées ;
- vérification post-action.

La capture d'écran ne doit jamais être activée en permanence sans besoin.

### Vision abstraction

```text
VisionProvider
- analyzeScreen
- detectText
- locateTarget
- compareScreens
- describeRegion
```

Provider local ou distant selon capacités et préférence confidentialité.

## 4. OCR

Prévoir `OcrProvider` local avec fallback. Résultat structuré : texte, confidence, bounding boxes, language.

## 5. Voice VNext — MUST

### Pipeline

```text
Wake/Push-to-talk
 ↓
VAD
 ↓
STT
 ↓
Ingress
 ↓
Cortana
 ↓
TTS
```

### Fonctions

- push-to-talk actuel conservé ;
- wake word optionnel ;
- VAD ;
- streaming STT si provider le permet ;
- TTS streaming ;
- barge-in : parole utilisateur coupe TTS ;
- choix local/distant ;
- choix voix/langue ;
- mode mains libres explicite ;
- indicateur microphone visible ;
- aucune écoute cachée.

## 6. Téléphonie / contacts / SMS — MUST selon matériel

Capabilities :

- `contacts.search`
- `contacts.read`
- `contact.create`
- `phone.call.prepare`
- `phone.call.start`
- `sms.compose`
- `sms.send`

Envoi/appel = L2/L3 selon contexte. Prévisualisation obligatoire pour destinataire et contenu.

## 7. Agenda — MUST

- lister calendriers ;
- rechercher événements ;
- créer/modifier/supprimer avec politique ;
- timezone ;
- recurrence ;
- reminders ;
- détection conflit.

## 8. Notification Listener — SHOULD/MUST si permission accordée

- lire notifications autorisées ;
- filtrer par app ;
- convertir événements en triggers ;
- réponse directe uniquement avec autorisation ;
- données sensibles tainted par défaut ;
- ne jamais exfiltrer notification vers modèle distant sans politique/confidentialité adaptée.

## 9. Clipboard

Lecture contextuelle et écriture contrôlée. Secrets/paste sensible protégés. Nettoyage optionnel après délai pour secrets temporaires.

## 10. Media services

Architecture de providers multimodaux :

- image generation ;
- image editing ;
- vision ;
- OCR ;
- TTS ;
- STT ;
- audio processing ;
- video generation/analysis optionnel si provider disponible.

Le Model Capability Model doit indiquer modalités input/output.

## 11. Browser Executor VNext

La recherche HTTP actuelle reste fast path pour information simple.

Ajouter niveau navigateur interactif quand nécessaire :

- ouvrir page ;
- DOM/accessibility snapshot ;
- click/type/select ;
- navigate/back ;
- download ;
- upload après approbation ;
- cookies/session isolés ;
- tab management ;
- wait condition ;
- screenshot ;
- export page text ;
- provenance sources.

Sur Android, possibilité d'utiliser navigateur contrôlé par accessibility. Sur worker, navigateur headless/sandbox. Même interface canonique.

## 12. Research Service

Workflow :

1. formuler requêtes ;
2. chercher plusieurs sources ;
3. classer fiabilité ;
4. ouvrir sources ;
5. extraire faits ;
6. dédupliquer ;
7. conserver provenance ;
8. produire synthèse ;
9. citer URLs/source refs dans artefact final.

Aucune source Web ne peut injecter des instructions système.

## 13. Email / messagerie

Architecture adapter :

- accounts ;
- search ;
- read ;
- draft ;
- send ;
- reply ;
- attachments ;
- archive/labels si disponible.

Créer/draft peut être L1/L2 ; envoyer = L2 ; suppression destructive = L3 selon configuration.

## 14. Generic Connectors

Définir `Connector` :

- id ;
- auth scheme ;
- capabilities ;
- health ;
- rate limits ;
- scopes ;
- risk metadata.

Types : REST, webhook, websocket, local LAN, custom plugin.

## 15. Home automation

Support optionnel via connector générique : entities, state read, service call. Les actions physiques sensibles peuvent être L2/L3.

## 16. MCP — MUST

Cortana doit devenir **client MCP** via une couche d'adaptation, sans faire de MCP une deuxième Tool Registry.

Pipeline :

```text
MCP server
  ↓ discovery
McpAdapter
  ↓ normalize
Cortana ToolDefinition
  ↓
ToolRegistry
```

Exigences :

- tools/resources/prompts selon spec supportée ;
- capability discovery ;
- auth ;
- transport abstraction ;
- HTTP/streaming transport ;
- stdio via worker quand applicable ;
- timeout ;
- cancellation ;
- namespacing ;
- collision detection ;
- policy metadata local ;
- taint sur ressources externes ;
- health/reconnect ;
- version negotiation ;
- aucun outil externe ne contourne PolicyEngine.

## 17. A2A / interop agents — SHOULD

Ajouter un adapter optionnel pour interop avec agents externes standards.

Important : un agent externe est une **capacité distante**. Il n'obtient pas la mémoire interne complète ni le contrôle de l'orchestrateur.

Support :

- discovery via agent card ;
- capability negotiation ;
- task delegation ;
- files/structured data ;
- authentication ;
- cancellation ;
- result provenance ;
- policy ;
- rate limiting.

## 18. Plugins

Plugin manifest signé :

```text
PluginManifest
- id
- version
- publisher
- signature
- minCortanaVersion
- capabilities[]
- permissions[]
- entrypoints
- transports
- configSchema
- checksum
```

Plugins ne chargent jamais du code arbitraire dans le process principal sans isolation. Préférer protocol adapters/process isolé/worker.

## 19. Device pairing / worker pairing

- QR ou code court ;
- échange de clés ;
- pinning ;
- device identity ;
- revocation ;
- rotation ;
- capability registration ;
- heartbeat ;
- offline state ;
- no shared plaintext secret.

## 20. Update system

### Android

- télécharger manifeste signé ;
- vérifier version/compatibilité ;
- vérifier SHA-256 ;
- vérifier signature attendue ;
- télécharger APK ;
- lancer installation système avec confirmation utilisateur ;
- aucune promesse d'installation silencieuse sans privilèges appareil adaptés.

### Core/worker/plugins

- version compatibility manifest ;
- staged updates ;
- rollback ;
- health check après update.

## 21. UX permissions

Écran unique **Capacités & permissions** :

- capability ;
- état ;
- raison ;
- niveau de risque ;
- action Corriger ;
- dernière utilisation ;
- révocation ;
- dépendances.

## 22. Tests matériels obligatoires

Sur vraie tablette :

- accessibilité ;
- screenshot ;
- biométrie ;
- notification listener ;
- voice ;
- batterie One UI ;
- reboot ;
- scheduler ;
- STOP ;
- install update ;
- permissions restreintes ;
- background restrictions.
