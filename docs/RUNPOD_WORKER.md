# Worker Cortana sur un pod RunPod

Le worker Cortana (`cortana-worker.jar`, voir `WORKER_PROTOCOL.md`) peut tourner sur un pod RunPod, par exemple à
côté du modèle sur le pod `elyndor-5090` : il n'utilise pas la carte graphique. La tablette lui envoie ses projets,
il compile, lance les tests et renvoie les résultats. Il ne planifie rien et n'appelle aucun modèle : la tablette
reste l'autorité.

Tout est préparé par un seul script, `tools/runpod/cortana-worker-runpod.sh`, publié avec la version 2.0.0-rc8.

## 1. Exposer le port TCP 8765 (une seule fois)

RunPod → **Pods** → le pod → **Edit Pod** → **Expose TCP Ports** : ajoutez `8765`, puis enregistrez.

- C'est bien un port **TCP**, pas un port HTTP : la tablette épingle le certificat du worker, et le proxy HTTPS de
  RunPod (`*.proxy.runpod.net`) le remplacerait par le sien. La connexion serait refusée.
- L'enregistrement redémarre le pod. Le volume `/workspace` est conservé. Vérifiez ensuite que le modèle
  (port 8000) et le serveur médias (port 7860) sont bien repartis.

## 2. Installer et démarrer le worker

Dans le terminal du pod (**Connect** → **Web Terminal**, ou Jupyter → Terminal) :

```
cd /workspace && curl -fsSLO https://github.com/artisanguillonrenov-creator/Cortana-/releases/download/v2.0.0-rc8/cortana-worker-runpod.sh && bash cortana-worker-runpod.sh
```

Le script :
- installe Java 17 si besoin ;
- télécharge le worker et vérifie son empreinte SHA-256 (un fichier différent est rejeté) ;
- le démarre en arrière-plan avec un superviseur, qui le relance s'il s'arrête et le garde actif après la
  fermeture du terminal ;
- place tout dans `/workspace/cortana-worker` (clé TLS, appareils appairés, copies des projets) ;
- affiche la ligne d'appairage, avec l'adresse IP publique et le port externe RunPod déjà mis dedans.

## 3. Appairer la tablette

Dans Cortana → **Appareils** → **Appairer**, collez la ligne `cortana-worker://IP:PORT?id=…&fp=…&code=…`.
Elle est valable 10 minutes et ne sert qu'une fois. Pour en obtenir une nouvelle :

```
bash /workspace/cortana-worker/bin/cortana-worker-runpod.sh pair
```

## Au quotidien

| Commande | Effet |
|---|---|
| `bash /workspace/cortana-worker/bin/cortana-worker-runpod.sh` | démarre le worker (à relancer après chaque redémarrage du pod) |
| `… pair` | nouvelle ligne d'appairage |
| `… status` | état, empreinte TLS, bac à sable, appareils, adresse publique |
| `… logs` | dernières lignes du journal |
| `… stop` | arrête le worker |

- **Redémarrage du pod** : le disque du conteneur est effacé, mais pas `/workspace`. Relancez le script : il
  réinstalle Java et redémarre le worker avec la même clé, et la tablette reste appairée.
- **Changement d'adresse** : si RunPod attribue une autre IP ou un autre port externe (pod recréé, ou parfois après
  un redémarrage), faites `… pair` et appairez de nouveau avec la nouvelle ligne.
- **Révoquer la tablette** : `java -jar /workspace/cortana-worker/bin/cortana-worker.jar revoke ID --data /workspace/cortana-worker/data`.
  L'identifiant est donné par `… status`.

## Sécurité et limites

- Le port est public, mais rien n'est accessible sans appairage. Le code est à usage unique, expire en 10 minutes
  et se bloque après 5 essais. Chaque requête est ensuite signée par la clé de la tablette, qui reste dans
  l'Android Keystore. Seule la route des webhooks entrants est publique, et elle exige leur signature HMAC.
- **Bac à sable** : le mode isolé (espaces de noms Linux) est souvent refusé dans un conteneur RunPod. `… status`
  indique les modes disponibles. Sans lui, seul le mode *processus* reste, pour les projets de confiance
  uniquement.
- Les tâches n'ont que les outils installés sur le pod (git, python, etc.). Un outil installé avec `apt` disparaît
  au redémarrage du pod.
- Testé sur une machine Linux avec une adresse RunPod simulée : démarrage, appairage, relance automatique, arrêt,
  conservation de la clé et des appareils. Pas encore testé sur un vrai pod RunPod.
