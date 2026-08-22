# Publier Spectra sur Docker Hub

> Comment les images du projet sont poussées vers le registre Docker Hub, et comment
> démarrer la pile **sans rien construire** une fois qu'elles y sont.

Toutes les images **tierces** de la pile viennent déjà d'un registre : `llama.cpp`,
`chromadb`, `kafka`. Les deux images que Spectra **construit** — le backend Java et le
frontend — ne venaient de nulle part : chaque utilisateur les bâtissait chez lui. C'est
plusieurs minutes de `mvn package` et de build Vite au premier démarrage, et un échec net
derrière un réseau d'entreprise qui filtre Maven Central ou le registre npm — pour un
résultat que la CI produit déjà à chaque version.

Ce document décrit les deux moitiés de la réponse : **publier** (côté mainteneur) et
**consommer** (côté utilisateur).

---

## 1. Ce qui est publié

| Image | Contenu | Architectures | Publiée |
|---|---|---|---|
| `<ns>/spectrallm` | Backend Java 25 / Spring Boot : API, pipeline RAG, GED | `amd64`, `arm64` | à chaque version |
| `<ns>/spectrallm-frontend` | Interface React servie par Nginx | `amd64`, `arm64` | à chaque version |
| `<ns>/spectrallm-docparser` | Parsing de mise en page (profil `layout-parser`) | `amd64` | sur demande |
| `<ns>/spectrallm-reranker` | Cross-Encoder de reranking (profil `reranker`) | `amd64` | sur demande |
| `<ns>/spectrallm-trainer` | Fine-tuning QLoRA/DPO, torch inclus (profil `trainer`) | `amd64` | sur demande |

`<ns>` est l'espace de noms Docker Hub du projet — `compagnonsdudev` par défaut, surchargeable
partout (voir §5).

**Pourquoi les trois derniers ne partent pas à chaque version.** Ils pèsent plusieurs
gigaoctets (torch), ne concernent que qui active le profil correspondant, et leur
construction `arm64` sous émulation QEMU prendrait des heures. Qui en a besoin les
construit localement, nativement, ce que la pile fait déjà par défaut. Le drapeau
`include_profiled` du workflow les publie quand c'est vraiment voulu.

### Tags posés

Sur le tag git `v0.7.1`, chaque image reçoit :

- `0.7.1` — la version exacte, celle qu'on épingle en production ;
- `0.7` — la série : les correctifs sans les surprises d'une montée de version ;
- `latest` — la dernière version publiée.

Une **pré-version** (`v1.0.0-rc1`) est publiée telle quelle, **sans** `latest` : une
release candidate ne doit pas devenir le défaut de ceux qui n'épinglent rien. Un
déclenchement manuel sans version produit `edge-<sha>`, également sans `latest`.

---

## 2. Configuration, une fois pour toutes

### 2.1 Côté Docker Hub

1. Créer le compte (ou l'organisation) qui portera les images.
2. Créer un **jeton d'accès** : *Account Settings → Personal access tokens*, portée
   **Read & Write**.

   Jamais le mot de passe du compte : un jeton se révoque seul, sans toucher au compte ni
   aux autres automatisations.
3. Les dépôts (`spectrallm`, `spectrallm-frontend`, …) sont **créés automatiquement à la
   première poussée**. Vérifiez ensuite leur visibilité dans l'interface : un dépôt privé
   se tire avec un `docker login`, ce qui n'est pas ce qu'on veut d'une image destinée aux
   utilisateurs.

### 2.2 Côté dépôt GitHub

*Settings → Secrets and variables → Actions* :

| Nom | Type | Rôle |
|---|---|---|
| `DOCKERHUB_USERNAME` | secret | compte qui pousse |
| `DOCKERHUB_TOKEN` | secret | le jeton créé ci-dessus |
| `DOCKERHUB_NAMESPACE` | **variable** *(recommandé)* | espace de noms visé — `compagnonsdudev` |

Sans les deux secrets, une exécution en publication **échoue** en nommant celui qui manque —
plutôt que de se terminer en vert sans avoir rien poussé. Le mode `dry_run` (§3.2) exerce
toute la chaîne sans aucun identifiant.

**Pourquoi renseigner l'espace de noms en `variable` et non le laisser dériver du secret.**
Les deux fonctionnent : à défaut de variable, l'espace de noms est celui de
`DOCKERHUB_USERNAME`. Mais un secret est **masqué dans les journaux**, et les tags s'y
affichent alors en `***/spectrallm:0.7.1` — illisibles au moment précis où l'on veut
vérifier ce qui a été publié. Un espace de noms Docker Hub n'est pas un secret : il est
public, il est dans le nom que tirent les utilisateurs.

> **Piège associé, corrigé mais utile à connaître.** Actions refuse de propager une *sortie
> de job* dont la valeur est celle d'un secret : elle arrive vide dans le job suivant, avec
> pour seule trace un `Skip output '…' since it may contain secret` au milieu du journal.
> La première publication a échoué ainsi, sur un `invalid tag "/spectrallm-frontend:edge-…"`.
> Chaque job de publication résout donc l'espace de noms lui-même depuis `env:`, où le
> masquage n'existe qu'à l'affichage. `scripts/tests/test_docker_hub_images.py` empêche le
> retour en arrière.

---

## 3. Publier

### 3.1 Le chemin normal : un tag de version

```bash
git tag -a v0.7.1 -m "SpectraLLM v0.7.1" && git push origin v0.7.1
```

Le workflow [`docker-publish.yml`](../../.github/workflows/docker-publish.yml) se déclenche
sur `v*`, construit `spectrallm` et `spectrallm-frontend` en `amd64` + `arm64`, et les pousse
avec les trois tags du §1. Le récapitulatif du job affiche la commande `docker pull`
correspondante.

Ce workflow est **indépendant** de `release.yml`, qui crée la release GitHub sur le même
tag : une publication d'images ratée ne prive pas le dépôt de sa release, et
réciproquement. Les deux se relancent séparément.

**Après la publication, alignez le défaut de l'overlay** (`SPECTRA_IMAGE_TAG` dans
`docker-compose.hub.yml`) sur la version qui vient de partir. C'est ce défaut que reçoit
quiconque ne configure rien : laissé sur une version périmée, il la sert en silence, sans
que personne s'en aperçoive. `scripts/tests/test_docker_hub_images.py` compare ce défaut à
la dernière version de `.github/release-notes/` et fait échouer la CI s'il a dérivé — la
valeur `latest` restant acceptée comme un choix explicite, celui d'une vitrine qui suit
toujours la dernière version.

*(Le contrôle s'appuie sur les notes de release et non sur le CHANGELOG : les tags d'image
dérivent du tag git, que ce répertoire indexe. Le CHANGELOG suit une autre numérotation.)*

### 3.2 Déclenchement manuel

*Actions → Publish images (Docker Hub) → Run workflow* :

| Entrée | Effet |
|---|---|
| `version` | `v0.7.1` pour republier une version ; vide pour un tag `edge-<sha>` |
| `dry_run` | construit tout, ne pousse rien, n'exige aucun secret |
| `include_profiled` | ajoute docparser, reranker et trainer (comptez une heure) |
| `descriptions_only` | ne met à jour que les pages de présentation (§3.4), sans rien construire |

### 3.3 Depuis un poste

[`scripts/publish-images.sh`](../../scripts/publish-images.sh) fait la même chose hors
GitHub Actions — utile quand la CI n'est pas encore configurée, ou pour vérifier avant de
tagger que tout se construit et se pousse vraiment :

```bash
docker login -u <compte>                       # jeton d'accès, pas le mot de passe
./scripts/publish-images.sh --dry-run          # tout construire, ne rien pousser
./scripts/publish-images.sh --tag v0.7.1       # publier
./scripts/publish-images.sh --tag v0.7.1 --include-profiled
```

Le script crée au besoin un constructeur `buildx` dédié : le pilote `docker` par défaut ne
sait pas produire de manifeste multi-architecture, et l'échec surviendrait sinon *après* la
construction, sur un message qui n'oriente pas vers la cause.

### 3.4 La page de présentation Docker Hub

La description longue d'un dépôt Docker Hub est la **vitrine** du projet : c'est ce que voit
quelqu'un qui le découvre par le registre plutôt que par GitHub. Laissée vide, elle affiche
« No overview available » sous un nom d'image — et l'image la mieux construite du monde n'y
répond à aucune des deux questions que se pose ce visiteur : qu'est-ce que c'est, et comment
je la démarre.

Ces pages vivent donc **dans le dépôt**, versionnées et relues comme le reste :

| Fichier | Dépôt Docker Hub |
|---|---|
| `deploy/docker/hub/spectrallm.md` | `<ns>/spectrallm` — la page du projet |
| `deploy/docker/hub/spectrallm-frontend.md` | `<ns>/spectrallm-frontend` — plus courte : c'est un composant, pas un produit |

Le nom du fichier **est** le nom du dépôt : le job `describe` du workflow en dérive l'URL de
l'API. La description courte (100 caractères max) est lue dans la page elle-même, en
commentaire HTML `<!-- short: … -->` — invisible au rendu, et un seul texte au lieu de deux
qui divergeraient.

**N'éditez jamais la description dans l'interface Docker Hub.** La publication suivante
l'écraserait, et l'écart ne se verrait qu'après coup. C'est délibéré : deux sources pour un
même texte, c'est une divergence qu'on découvre trop tard.

Corriger une coquille sans reconstruire trois gigaoctets d'images :

*Actions → Publish images (Docker Hub) → Run workflow*, avec `descriptions_only` coché.

Deux contraintes que `scripts/tests/test_docker_hub_images.py` fait respecter avant la
publication, parce qu'aucune des deux ne se voit à l'écriture :

- **Aucun lien relatif.** Docker Hub ne résout rien : un `docs/assets/x.png` recopié depuis
  le README y donne une image cassée. La page s'affiche pourtant parfaitement dans un
  éditeur et dans GitHub — le défaut n'apparaît que sur la vitrine publique.
- **Les limites du service** : 100 caractères pour la description courte, 25 000 pour la
  page, au-delà desquels Docker Hub tronque.

Un dépôt Docker Hub naît de la **première poussée d'image** : tant qu'une image n'a pas été
publiée, sa page ne peut pas être écrite, et le job le dit explicitement (404) au lieu de
laisser chercher.

### 3.5 Ce qui rend la construction multi-architecture supportable

Les stages de build des deux Dockerfiles portent `--platform=$BUILDPLATFORM` : ils tournent
sur l'architecture du **constructeur**, jamais sur celle de la cible. Ils ne produisent
qu'un `.jar` (du bytecode) et des fichiers statiques — identiques pour `amd64` et `arm64`.
Seuls les stages d'exécution (JRE, Nginx) sont bâtis par architecture. Sans cela, chaque
publication rejouerait `mvn package` et `npm ci` sous émulation QEMU pour un résultat
rigoureusement inchangé.

---

## 4. Vérifier une publication

```bash
docker buildx imagetools inspect compagnonsdudev/spectrallm:0.7.1
```

La sortie doit montrer un **index** listant `linux/amd64` et `linux/arm64`, et les
attestations de provenance et de SBOM attachées par le workflow — c'est ce qui rend
vérifiable la promesse « cette image correspond à ce commit du dépôt ».

---

## 5. Consommer : démarrer sans rien construire

L'overlay [`docker-compose.hub.yml`](../../deploy/docker/docker-compose.hub.yml) remplace
la construction des deux images par un tirage :

```bash
docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml pull

docker compose --project-directory . \
  -f deploy/docker/docker-compose.yml -f deploy/docker/docker-compose.hub.yml \
  up -d --wait --no-build
```

Les modèles GGUF restent à télécharger comme d'habitude (`./scripts/setup.sh
--download-embed --download-chat`) : ce sont des données, pas des couches d'image.

**Pourquoi `pull` puis `up --no-build`, et pas un `pull_policy: always`.** Le tirage reste
une étape explicite, que l'on saute une fois les images en cache local : un déploiement
hors ligne — la promesse centrale de ce projet — ne doit pas dépendre d'un registre à
chaque démarrage. Et `--no-build` est le garde-fou : si un nom d'image ne correspondait à
rien, Compose reconstruirait **en silence** et l'overlay deviendrait un no-op invisible.
Avec ce drapeau, l'écart échoue franchement.

### Choisir l'espace de noms et la version

Dans `.env` (ou dans l'environnement du shell, qui l'emporte) :

```bash
SPECTRA_IMAGE_NAMESPACE=compagnonsdudev   # votre compte, ou un miroir : registry.interne/equipe
SPECTRA_IMAGE_TAG=0.7.1                  # « latest » suit la dernière version publiée
```

Épinglez une version en production. Le raisonnement est celui qui vaut déjà pour
`LLAMA_CPP_IMAGE_TAG` et `CHROMADB_IMAGE_TAG`, avec un précédent concret dans ce dépôt : le
tag `latest` de ChromaDB a déplacé son chemin de persistance sans un message, et l'index
vectoriel repartait de zéro à chaque `docker compose down`.

### Ce que l'overlay ne couvre pas

Les services profilés (`docparser`, `reranker`, `trainer`) continuent de se construire
localement, même sous l'overlay. Les y épingler ferait échouer au tirage tout utilisateur
d'un profil sur une version où ils n'ont pas été publiés — un échec là où la construction
locale, elle, fonctionne.

---

## 6. Dépannage

| Symptôme | Cause probable |
|---|---|
| `denied: requested access to the resource is denied` | `SPECTRA_IMAGE_NAMESPACE` ne désigne pas l'espace de noms réellement publié, ou le dépôt est privé et le `docker login` manque. |
| `manifest unknown` sur `up` | La version demandée (`SPECTRA_IMAGE_TAG`) n'a pas été publiée. `docker buildx imagetools inspect <ns>/spectrallm:latest` dit ce qui existe. |
| Compose reconstruit malgré l'overlay | Le `--no-build` a été oublié, ou l'un des deux `-f` manque sur la commande. |
| `multiple platforms feature is currently not supported` | Constructeur `buildx` au pilote `docker`. `scripts/publish-images.sh` en crée un adapté ; en manuel : `docker buildx create --driver docker-container --use`. |
| `toomanyrequests` au `pull` | Quota de tirage anonyme de Docker Hub atteint. Un `docker login`, même avec un compte gratuit, relève la limite. |

---

**Voir aussi :** [Getting Started](../getting-started.en.md) ·
[Configuration](../configuration.en.md) · [Fiabilité](../process/reliability.fr.md)
