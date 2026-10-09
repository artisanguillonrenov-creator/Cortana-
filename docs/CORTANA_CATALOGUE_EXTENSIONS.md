# Catalogue des extensions de Cortana (08/10/2026)

Base : branche de chantier `claude/cortana-stabilisation-extensions-rc10` (depuis le tag `v2.0.0-rc10`).
Aucune extension externe n'a été installée : ce document recense l'existant et recommande, sans rien activer.

## 1. Inventaire de ce que Cortana sait déjà faire

Source : le registre réel des outils (`docs/TOOL_CAPABILITIES.md`, régénéré et comparé au registre par `ToolCapabilitiesDocTest`). **179 capacités natives.**

| Catégorie | Capacités | Exemples | État |
|---|---|---|---|
| Développement (`dev`) | 40 | lecture/recherche/patch de code, symboles, Git complet (clone d'une seule branche, commit, push confirmé…), build, tests, lint, worker | native, fonctionnelle |
| Web (`web`) | 25 | `web.search` (pages, **images**, vidéos, mixte), `web.fetch`, navigateur HTTP, recherche approfondie, **13 outils `github.*`** (lecture en ligne + issue/commentaire/PR confirmés) | native ; images corrigées dans ce chantier |
| Système Android (`system`) | 26 | applications, alarmes, minuteurs, volume, luminosité, presse-papiers, partage | native |
| Pilotage de l'écran (`ui`) | 18 | observer, cliquer, saisir, faire défiler, attendre | native (accessibilité) |
| Intégrations (`integrations`) | 22 | e-mail IMAP/SMTP, agenda Android, contacts, SMS, appels préparés, Home Assistant, webhooks, API HTTP, **MCP**, **Skills**, **plugins**, **agents A2A** | native ; extensible |
| Documents (`documents`) | 15 | PDF, Word, tableur, présentation, archives, conversion | native |
| Médias (`media`) | 10 | analyse/génération/retouche d'images, vidéo, transcription, synthèse vocale | native ; affichage des images produites corrigé |
| Services (`service`) | 16 | mémoire, rappels et planification, observabilité, diagnostics | native |
| Fichiers (`files`) | 7 | lister, lire, chercher, écrire, patcher, supprimer (confirmé) | native |

### Voies d'extension vérifiées dans le code

| Voie | Ce qui existe | Contraintes de sécurité vérifiées |
|---|---|---|
| **MCP HTTP** | `core/mcp/` : outils, ressources, prompts ; jeton en poignée secrète ; OAuth via une connexion (phase 26) | https exigé hors réseau local (`McpTransports.kt`) ; chaque outil MCP passe par `ToolDispatcher` + `PolicyEngine` (L2 par défaut, L1 seulement si le serveur est marqué de confiance et l'outil annoncé en lecture seule) |
| **MCP stdio** | uniquement via un **worker appairé** (`worker.json` déclaré par le propriétaire du worker) | aucune ligne de commande ne circule ; rien n'est exécuté sur la tablette |
| **Skills** | `core/skills/` : recettes JSON importées, validées, activées par le propriétaire | n'utilisent que des outils existants, sous la même politique |
| **Plugins Cortana** | `core/plugins/`, `contracts/Plugins.kt` : paquet `cortana.plugin` signé ECDSA P-256 par l'éditeur (clé distincte de la clé APK) | aucun code exécutable (pas de DEX/JAR/natif) ; réseau limité aux hôtes déclarés |
| **A2A** | `core/a2a/` : agents externes déclarés par le propriétaire | résultats non fiables, fichiers reçus jamais ouverts automatiquement |
| **Module Android natif** | nouvelle fonctionnalité compilée dans l'APK | exige une nouvelle version signée (comme ce chantier) |

## 2. Candidats étudiés

Sources consultées le 08/10/2026 (pages officielles et annuaires ; les licences données par des annuaires tiers sont à reconfirmer dans le fichier LICENSE du dépôt officiel avant toute installation).

| Nom | Source vérifiée | Capacité nouvelle | Doublon ? | Mode | Coût | Dépendances | Sécurité | APK nécessaire ? | Décision |
|---|---|---|---|---|---|---|---|---|---|
| **Playwright MCP** (Microsoft) | `@playwright/mcp` (npm), Apache-2.0 selon Homebrew et les annuaires, maintenu (v0.0.77 en juin 2026) | navigation **avec JavaScript** (sites dynamiques, formulaires, captures), que le navigateur HTTP intégré ne rend pas | partiel : `browser.*` couvre les pages statiques | MCP stdio **via le worker** | gratuit ; tourne sur le worker | Node.js 18+ et navigateurs Playwright sur le worker | outils en L2 par défaut ; le navigateur tourne hors tablette | non | **Recommandé n°1** (sites modernes) |
| **Context7** (Upstash) | serveur distant `https://mcp.context7.com/mcp` (clé API ou OAuth) ou `npx -y @upstash/context7-mcp` ; licence du dépôt à confirmer | documentation **à jour** des bibliothèques pour le modèle de code | non | MCP HTTP (distant) ou stdio via worker | gratuit avec limites ; clé API possible | aucune (distant) | lecture seule ; contenu externe non fiable | non | **Recommandé n°2** (qualité du code produit) |
| **Notion** (officiel) | serveur hébergé `https://mcp.notion.com/mcp` (OAuth) ; le paquet local `makenotion/notion-mcp-server` n'est plus maintenu | pages et bases Notion | non (aucun outil Notion) | MCP HTTP + OAuth (connexion) | gratuit avec un compte Notion | compte Notion | écritures en L2 ; OAuth géré par les connexions | non | **Seulement si vous utilisez Notion** |
| **GitHub MCP** (officiel) | `github/github-mcp-server`, MIT selon plusieurs annuaires ; distant `https://api.githubcopilot.com/mcp/` (OAuth) | revues de PR ligne à ligne, sécurité (Dependabot, code scanning), notifications | **largement** : 13 outils `github.*` natifs | MCP HTTP + OAuth ou jeton | gratuit | compte GitHub | écritures en L2 | non | **Plus tard**, seulement pour les revues de code avancées |
| Filesystem / Memory / Fetch MCP | modelcontextprotocol/servers | — | **oui** (`file.*`, `memory.*`, `web.fetch`) | — | — | — | — | — | **Écarté** (doublon) |
| Google Agenda / Gmail MCP | divers | — | **oui** pour l'essentiel (agenda Android, e-mail IMAP/SMTP natifs) | — | — | — | — | — | **Écarté** sauf besoin propre à Google (partage d'agenda) |
| Home Assistant MCP | divers | — | **oui** (connecteur Home Assistant natif : `home.states`, `home.call`) | — | — | — | — | — | **Écarté** |

## 3. Ordre conseillé (aucun n'est installé)

1. **Playwright MCP sur le worker RunPod** : `worker.json` → `mcpServers.playwright = { command: ["npx","-y","@playwright/mcp@latest","--headless"] }`, puis Cortana → MCP → ajouter le serveur stdio du worker. À tester d'abord sur un site simple.
2. **Context7** en MCP HTTP distant (`https://mcp.context7.com/mcp`, clé API facultative en poignée secrète), réservé aux tâches de code.
3. **Notion**, uniquement si vous l'utilisez (connexion OAuth puis MCP HTTP).

Chaque ajout reste désactivable d'un geste (Réglages → MCP), passe par la même politique d'autorisations et n'exige aucune nouvelle version de l'APK.

## Sources

- Playwright MCP : [Homebrew](https://formulae.brew.sh/formula/playwright-mcp), [Mintlify — installation](https://www.mintlify.com/microsoft/playwright-mcp/installation), [dev.co](https://dev.co/ai/mcp/playwright-mcp)
- Context7 : [Context7 — clients](https://context7.com/docs/resources/all-clients), [Mintlify — configuration](https://www.mintlify.com/upstash/context7/mcp/configuration)
- Notion : [Notion — serveur MCP open source](https://developers.notion.com/guides/mcp/hosting-open-source-mcp.md), [Stacklok — Notion distant](https://docs.stacklok.com/toolhive/guides-mcp/notion-remote)
- GitHub MCP : [mcpservers.org — GitHub distant](https://mcpservers.org/remote-mcp-servers/github), [dev.co](https://dev.co/ai/mcp/github-mcp-server)
