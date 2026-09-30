# Protocole worker Cortana — v1

Le worker est un exécuteur de capacités sur un PC ou serveur du propriétaire. Il ne planifie pas, n'appelle aucun modèle et ne décide d'aucune politique ; la tablette reste l'autorité.

Code : module `:worker` (`java -jar cortana-worker.jar`), côté tablette `core/worker/Workers.kt`. Définitions partagées (en-têtes, chaîne canonique, format d'appairage) : `WorkerProtocol` dans `:contracts`, source unique.

## Lancer le worker

```
java -jar cortana-worker.jar serve [--data ~/.cortana-worker] [--port 8765]
java -jar cortana-worker.jar pair        # nouveau code d'appairage (10 min)
java -jar cortana-worker.jar devices     # appareils appairés
java -jar cortana-worker.jar revoke ID   # révocation immédiate
```

JDK 17 ou plus. Dossier de données en 0700 : clé TLS (`worker.p12` + `keystore.pass` 0600), `devices.json`, `pairing-codes.json`, copies des projets, tâches.

## Identité et canal

- Au premier démarrage, le worker crée avec le `keytool` du JDK une paire EC P-256 et un certificat auto-signé. Le mot de passe est lu depuis un fichier 0600, jamais passé en argument.
- HTTPS (TLS 1.3/1.2) avec ce certificat. La tablette **épingle** le SHA-256 du certificat DER reçu à l'appairage : ni autorité de certification, ni confiance par nom d'hôte. Un certificat différent fait échouer la connexion.

## Appairage

1. Le worker affiche `cortana-worker://HOTE:PORT?id=WORKER_ID&fp=SHA256_CERT&code=CODE`. Le code fait 10 caractères, est à usage unique, expire en 10 minutes et se bloque après 5 essais erronés.
2. Le propriétaire colle cette ligne dans Cortana → Appareils → Appairer (le modèle ne peut pas appairer).
3. La tablette ouvre le canal épinglé et envoie `POST /v1/pair` (`PairingRequest`) : code, identifiant d'appareil, nom, clé publique de l'appareil (EC P-256, SPKI base64 ; générée dans l'Android Keystore, non exportable) et nonce aléatoire.
4. Le worker vérifie et consomme le code, enregistre l'appareil et répond `PairingResponse` : identité, capacités et **preuve**, c'est-à-dire la signature ECDSA de `cortana-pair/1\n<nonce>\n<deviceId>` par la clé du certificat.
5. La tablette vérifie que la clé publique annoncée est celle du certificat épinglé, que la preuve est valide et que l'identifiant correspond. Elle enregistre alors le worker.

## Requêtes authentifiées

Toutes les routes sauf `/v1/pair` exigent :

| En-tête | Contenu |
|---|---|
| `X-Cortana-Device` | identifiant d'appareil appairé |
| `X-Cortana-Timestamp` | millisecondes epoch (écart max 5 min) |
| `X-Cortana-Nonce` | aléatoire 16–128 caractères, jamais réutilisé |
| `X-Cortana-Signature` | ECDSA-SHA256 (clé de l'appareil) de la chaîne canonique |

Chaîne canonique : `cortana-worker/1\n<MÉTHODE>\n<chemin?requête>\n<timestamp>\n<nonce>\n<sha256 hex du corps>`.

Réponses d'erreur : 401 (non authentifié, signature, horodatage, rejeu), 403 (révoqué, tâche d'un autre appareil), 400 (requête invalide), 409 (impossible, ex. bac à sable isolé indisponible).

## Routes

| Méthode | Chemin | Rôle |
|---|---|---|
| POST | `/v1/pair` | appairage (non signé, code requis) |
| GET | `/v1/capabilities` | `WorkerCapabilities` : OS, CPU, outils détectés, modes de bac à sable, modes réseau |
| POST | `/v1/workspaces/{id}/manifest` | `SyncManifest` (chemin → sha256) → `SyncPlan` (à envoyer, à supprimer) |
| POST | `/v1/workspaces/{id}/upload` | trame `[4 octets longueur][manifeste JSON][zip]` ; chaque fichier est vérifié par hachage ; protection zip-slip |
| POST | `/v1/jobs` | `WorkerJob` → `JobStatus` ; idempotent par `jobId` |
| GET | `/v1/jobs/{id}` | `JobStatus` (+ résultat final) ; renouvelle le bail |
| GET | `/v1/jobs/{id}/log?offset=N&max=M` | journal incrémental, en-tête `X-Next-Offset` |
| POST | `/v1/jobs/{id}/cancel` | arrêt de toute l'arborescence de processus |
| GET | `/v1/jobs/{id}/artifacts` | liste `ArtifactInfo` (nom, sha256, taille) |
| GET | `/v1/jobs/{id}/artifacts/{nom}` | contenu ; la tablette recalcule le SHA-256 et rejette toute différence |
| POST | `/v1/revoke` | l'appareil se révoque lui-même |
| GET | `/v1/mcp` | noms des serveurs MCP stdio déclarés dans `worker.json` (phase 20) |
| POST | `/v1/mcp/{nom}?timeout=ms` | un message JSON-RPC (requête ou notification) vers ce serveur stdio → la ligne de réponse (200) ou 202 pour une notification |
| PUT | `/v1/hooks/{nom}` | enregistre (ou met à jour) un webhook entrant de l'appareil : `HookRegistration` (secret ≥ 16 octets, débit par minute) → `HookInfo` (identifiant public, chemin) — phase 26 |
| DELETE | `/v1/hooks/{nom}` | supprime le webhook et ses événements en attente |
| GET | `/v1/hooks/events?after=N` | événements en attente de l'appareil (`HookEvents`) ; tout ce qui est ≤ N est acquitté et effacé |
| POST | `/hooks/{hookId}` | **route publique** (sans appairage) : l'expéditeur signe ; voir ci-dessous |

### Webhooks entrants (phase 26)

L'adresse publique `https://<worker>/hooks/{hookId}` n'accepte qu'une requête POST de 256 Kio au plus, signée par le secret du webhook : schéma Cortana (`X-Cortana-Timestamp` + `X-Cortana-Signature: sha256=HMAC(secret, "horodatage.corps")`, horodatage à ± 5 min) ou schéma GitHub (`X-Hub-Signature-256` sur le corps, rejeux détectés par `X-GitHub-Delivery`). Une même signature n'est acceptée qu'une fois ; débit limité par minute ; 500 événements en attente au plus par appareil. Seuls quelques en-têtes descriptifs sont conservés. Webhooks et événements survivent au redémarrage du worker (`hooks/`, fichiers 0600) ; la révocation d'un appareil supprime ses webhooks. Le certificat du worker est auto-signé : l'expéditeur doit l'accepter explicitement (option « ne pas vérifier le certificat » de GitHub ou de Home Assistant) — la signature HMAC, elle, reste vérifiée.

### Serveurs MCP stdio (phase 20)

Déclarés **uniquement** par le propriétaire du worker, dans `worker.json` :

```json
{ "workerId": "…", "name": "atelier",
  "mcpServers": { "fichiers": { "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/home/moi/docs"], "env": {} } } }
```

La tablette ne peut que nommer un serveur : aucune ligne de commande ne circule sur le réseau. Un processus par (appareil appairé, serveur), lancé à la demande avec un environnement minimal (`PATH`, `HOME`, `LANG`, `LC_ALL`, `TMPDIR` + `env` déclaré : les variables du worker ne fuient pas), relancé après un arrêt, arrêté après 10 min d'inactivité (fermeture de stdin puis arrêt forcé). Messages sur une ligne, réponses appariées par `id` ; les requêtes qu'un ancien serveur envoie lui-même (ping, roots, sampling) reçoivent « méthode non prise en charge » ; progrès et journaux ne sont pas relayés ; `stderr` va dans `mcp-logs/`. L'annulation est une notification `notifications/cancelled` envoyée par la même route.

## Exécution sur le worker

- **isolated** (Linux, espaces de noms utilisateur) : `unshare` utilisateur + montage + PID (+ réseau si refusé). Le système entier est remonté en lecture seule. Seuls le projet (`/tmp/ws`) et un `/tmp` privé sont inscriptibles. Le dossier personnel et le dossier de données du worker sont masqués. Les processus de l'hôte sont invisibles. Sans réseau, seule l'interface `lo` existe.
- **process** : processus simple avec environnement vidé, dossier confiné et limites `ulimit`. Utilisé seulement pour les projets de confiance, réseau non isolé.
- La commande n'est jamais passée en argument de processus : elle est écrite en UTF-8 dans un script du dossier de la tâche. Cela évite la corruption des caractères non ASCII selon la locale.
- Délai, annulation et **bail** : une tâche que l'appareil ne suit plus depuis 10 minutes est annulée. Le résultat est persisté (`job.json`) et relisible après redémarrage du worker.

## Reconnexion

Côté tablette, chaque appel au worker est réessayé avec un délai croissant (0,5 s → 15 s) pendant 5 minutes. Pendant ce temps, la tâche continue sur le worker et la tablette reprend le suivi du même `jobId`. Si la tâche Cortana est annulée, la tablette annule le job sur le worker.

## Révocation

Depuis la tablette (Appareils → Révoquer) : la révocation locale est immédiate et définitive, et la révocation distante est faite au mieux (`/v1/revoke`). Depuis le worker : `revoke ID`. Toute requête d'un appareil révoqué reçoit 403.

## Administration et diagnostic (phase 31)

Sur la machine du worker, avec les droits du propriétaire des données :

```
java -jar cortana-worker.jar status  [--data DOSSIER] [--json]   identité, protocole, empreinte TLS, bac à sable, capacités, appareils, tâches, webhooks
java -jar cortana-worker.jar devices [--data DOSSIER] [--json]   appareils appairés (clés publiques seulement)
java -jar cortana-worker.jar jobs    [--data DOSSIER] [--json]   tâches : même contrat JobStatus que GET /v1/jobs/{id}
java -jar cortana-worker.jar hooks   [--data DOSSIER] [--json]   webhooks hébergés : même HookInfo que PUT /v1/hooks/{nom}, jamais le secret
```

`WorkerAdmin` n'est qu'une vue sur les magasins que sert l'API (appareils, tâches, webhooks, capacités) :
aucune logique propre, aucun raccourci réseau, aucun effet (la lecture d'une tâche ne renouvelle pas le
bail de l'appareil). Le mode `--json` produit les contrats de `:contracts` (`WorkerCapabilities`,
`JobStatus`, `HookInfo`) ; `WorkerIntegrationTest.adminClientServesTheSameContractsAsTheApi` vérifie qu'ils
sont égaux à ceux que l'API renvoie à la tablette appairée.

## Ce qui n'est pas vérifié ici

Tous les tests tournent sur une seule machine Linux, en HTTPS réel sur la boucle locale. Ne sont pas testés : un appairage entre deux machines physiques sur un vrai réseau local, un worker Windows ou macOS (mode *process* seulement, pas de bac à sable isolé) et la clé matérielle Android Keystore (Robolectric utilise la clé logicielle de repli). Ce sont des vérifications de niveau P.
