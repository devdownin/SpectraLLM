# Audit du déploiement — démarrage et arrêt

**Périmètre.** Les scripts qui installent, démarrent et arrêtent la stack :
`scripts/{setup,detect-env,start,stop,build,verify,adddoc}.{sh,bat}`, la bibliothèque
`scripts/lib/`, les fichiers Compose de `deploy/docker/`, `.env.example`, et le job E2E de
`.github/workflows/ci.yml`. Objectif : simplifier le chemin d'entrée — une commande pour
démarrer, une pour arrêter — et supprimer les écarts entre ce que les scripts annoncent et
ce qu'ils font.

**Méthode.** Lecture des seize scripts, exécution de `detect-env.sh` sur les quatre états
possibles de `.env`, comparaison des invocations Compose entre scripts, entre plateformes et
avec la CI. Les constats sont classés par ce qu'ils coûtent à l'utilisateur, pas par la
taille du correctif.

Une seconde passe, menée après coup sur le coût réel du build et du démarrage, a ouvert deux
zones que la première n'avait pas regardées : les **contextes de construction Docker** et
l'**exposition réseau**. Elle a produit les constats D10 à D13, dont deux défauts de sécurité.
D14 est venu ensuite, du constat que le job de CI le plus long était le seul à construire sans
cache — voir « Suite de l'audit » en fin de document pour le découpage en cinq PR.

---

## Résumé

Quatorze constats, dont cinq pannes silencieuses et deux défauts de sécurité.

Le fil commun des neuf premiers n'est pas la complexité des scripts : c'est la **duplication
d'une définition** — du fichier `.env`, de l'invocation Compose, de la notion de « service
prêt ». Chaque fois, deux endroits décrivaient la même chose, et un seul faisait autorité.

Les quatre suivants ont un autre fil, plus inquiétant : un réglage **écrit au bon endroit
mais qui n'arrive jamais à destination**. Un `.dockerignore` qui ne couvre pas le contexte
qu'on croit (D10), une clé d'API qui ne franchit pas la frontière du conteneur (D12). Dans
les deux cas la configuration est juste, lisible, et sans effet.

| # | Constat | Gravité | État |
|---|---|---|---|
| D1 | `--first-run` annulait toute la détection matérielle | Panne silencieuse | Corrigé |
| D2 | `cp .env.example .env` injectait deux valeurs fausses en conteneur | Panne silencieuse | Corrigé |
| D3 | `detect-env.bat` écrasait le `.env` de l'utilisateur à chaque démarrage | Perte de données | Corrigé |
| D4 | Quatre invocations Compose divergentes ; `stop` ne voyait pas la stack de `start` | Fonctionnel | Corrigé |
| D5 | 120 lignes d'attente maison concurrentes des healthchecks Compose | Simplification | Corrigé |
| D6 | « Spectra est prêt ! » s'affichait sur une stack en panne | Diagnostic | Corrigé |
| D7 | `scripts/adddoc.sh` annoncé par `setup.sh` mais absent du dépôt | Fonctionnel | Corrigé |
| D8 | `build.sh --no-cache` systématique ; nom de JAR codé en dur | Simplification | Corrigé |
| D9 | Aucun moyen de reconstruire au démarrage après une modification du code | Ergonomie | Corrigé |
| D10 | `frontend/.dockerignore` absent : le `node_modules` de l'hôte écrase celui du conteneur | Panne silencieuse | Corrigé (#316) |
| D11 | Aucun `start_interval` : jusqu'à 70 s d'attente pure à chaque démarrage | Simplification | Corrigé (#316) |
| D12 | `SPECTRA_API_KEY` n'atteignait jamais le conteneur — authentification inactivable | Sécurité | Corrigé (#317) |
| D13 | Les neuf ports publiés sur `0.0.0.0`, sans authentification | Sécurité | Corrigé (#317) |
| D14 | Le job CI le plus long était le seul à construire sans cache de couches | Simplification | Corrigé (#325) |

---

## D1 — `--first-run` annulait la détection matérielle

C'est la commande du README, donc le chemin que suit la quasi-totalité des installations :

```bash
./scripts/start.sh --first-run
```

Elle enchaînait `setup.sh` puis `detect-env.sh`. `setup.sh` créait `.env` en copiant
`.env.example` ; `detect-env.sh`, appelé juste après, commençait par :

```bash
if [[ -f "$ENV_FILE" && "$FORCE" -eq 0 ]]; then
    echo "  ✓ .env existant conservé"
    exit 0
fi
```

Le fichier venait d'être créé trois secondes plus tôt. **La détection ne s'exécutait donc
jamais** sur le chemin par défaut : ni profil RAM/CPU, ni dimensionnement du contexte LLM,
ni `SPECTRA_GPU_ENABLED`.

Et comme `start.sh` décide de charger `docker-compose.gpu.yml` en cherchant précisément
cette clé dans `.env`, l'accélération GPU était inatteignable — **y compris pour qui
demandait explicitement `--gpu`**, l'option étant transmise à un script qui sortait avant de
s'en servir. Rien ne le signalait : la stack démarrait, simplement sans GPU et avec un
dimensionnement générique.

**Correctif.** `detect-env.sh` ne sort plus jamais sans écrire. Il maintient un **bloc auto
délimité** en tête de `.env` et laisse intact tout ce qui suit :

```
# === SPECTRA:AUTO:BEGIN - bloc regenere par detect-env, ne pas editer ===
…profil matériel, JVM, contexte LLM, GPU…
# === SPECTRA:AUTO:END - ecrivez vos reglages SOUS cette ligne ===
…vos réglages, jamais touchés…
```

La priorité repose sur une règle que le dépôt appliquait déjà sans en tirer parti : **la
dernière affectation d'une clé l'emporte**, côté Compose comme côté scripts. Le bloc étant
en tête, l'utilisateur gagne toujours — sans qu'aucun script n'ait à distinguer « valeur
détectée » de « valeur choisie ». La détection redevient vivante : brancher un GPU ou
libérer de la RAM se répercute au démarrage suivant.

Quatre états de `.env` sont traités : absent, déjà porteur des bornes, entièrement généré
par une version antérieure (remplacé, avec copie dans `.env.bak`), ou écrit à la main
(intégralement préservé).

## D2 — `cp .env.example .env` injectait deux valeurs fausses en conteneur

`.env.example` fait 19 600 octets et se présente comme un catalogue commenté — mais il y
laisse **39 clés actives**. En copier le fichier entier ne « pré-remplissait » pas une
configuration : cela figeait 39 décisions, dont deux erronées dans le contexte où elles
étaient lues.

| Clé | Valeur copiée | Valeur juste (Compose) | Conséquence |
|---|---|---|---|
| `SPECTRA_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:29092` | L'API cherchait le broker dans son propre conteneur |
| `LLM_PARALLEL` / `LLM_CONTEXT` | `1` / `8192` | calculées d'après RAM et cœurs | Dimensionnement matériel annulé |

La valeur Kafka n'est pas une faute de frappe : `localhost:9092` est correct pour un broker
externe, et c'est bien ce que documente la ligne juste au-dessus. Elle devient fausse par le
seul fait d'être *copiée* dans le fichier que lit le conteneur.

**Correctif.** `setup.sh` et `setup.bat` ne copient plus `.env.example` ; `detect-env` est
le seul créateur de `.env`. L'entête de `.env.example` dit désormais l'inverse de ce qu'il
disait — recopier les lignes utiles, pas le fichier — et `docs/configuration.en.md` explique
les deux zones de `.env`.

## D3 — `detect-env.bat` écrasait le `.env` de l'utilisateur à chaque démarrage

Le jumeau Windows n'avait ni garde d'existence ni `--force` : il finissait par
`) > .env`, une redirection qui remplace le fichier. Comme `start.bat` l'appelle à chaque
lancement, **tout réglage saisi à la main disparaissait au démarrage suivant**, sans
avertissement.

Les deux plateformes traitaient donc le même fichier de deux façons opposées : le shell n'y
touchait jamais (D1), le batch le détruisait à chaque fois. Aucun test ne pouvait le voir —
`test_windows_scripts_parity.py` compare les options annoncées, pas les comportements, et le
dit lui-même dans son entête.

**Correctif.** `detect-env.bat` applique la même logique de bloc que `detect-env.sh`, avec
des bornes en **ASCII pur, identiques à l'octet près** dans les deux scripts : un dépôt
piloté tantôt depuis WSL tantôt depuis PowerShell ne doit pas empiler deux blocs
concurrents. `--force` existe désormais des deux côtés.

## D4 — Quatre invocations Compose divergentes

`docker compose --project-directory . -f deploy/docker/docker-compose.yml` était réécrit
dans `start.sh`, `stop.sh`, `build.sh`, `verify.sh`, les quatre `.bat` correspondants, le job
E2E de la CI et la documentation. Les copies avaient dérivé sur deux points :

- **L'overlay GPU.** `start.sh` ajoutait `docker-compose.gpu.yml` quand `.env` l'annonçait ;
  `stop.sh` non. `down` ne voyait donc pas exactement la stack que `up` avait démarrée.
- **La liste des profils.** `stop.sh` la codait en dur
  (`layout-parser,reranker,kafka,trainer`). Ajouter un profil au fichier Compose sans penser
  à cette ligne produit un service que `stop` laisse tourner — port et mémoire toujours
  pris, et la commande sort en succès.

**Correctif.** `scripts/lib/compose.sh` porte l'unique définition : construction du tableau
`COMPOSE` (overlay GPU compris, pour tous les appelants), lecture d'une clé `.env` selon la
règle de Compose, fusion d'un profil sans écraser les autres. La liste des profils est
**demandée à Compose** (`config --profiles`), seul à en faire autorité. `verify.sh` valide
désormais aussi l'overlay GPU, qui n'était vérifié nulle part — c'est le fichier le moins
exercé de la pile, une erreur de syntaxe y aurait dormi jusqu'au premier démarrage GPU d'un
utilisateur.

## D5 — 120 lignes d'attente maison concurrentes des healthchecks

`start.sh` et `start.bat` contenaient cinq boucles de sondage chacun (~120 lignes cumulées)
interrogeant des URLs codées en dur, alors que **chaque service déclare déjà son
healthcheck** dans `docker-compose.yml`. Deux définitions du « prêt » pour un même service,
dont une seule fait autorité — et la copie ne couvrait que quatre services sur neuf : un
`reranker` ou un `docparser` en échec passait inaperçu jusqu'à la première requête.

**Correctif.** `docker compose up -d --wait --wait-timeout 300`. Le délai couvre le
`start_period` le plus long de la stack (`llm-chat`, 120 s) augmenté du chargement effectif
d'un modèle 7B. Même remplacement dans le job E2E de la CI, qui installait `wait-on` pour
sonder deux URLs — troisième définition concurrente du « prêt ».

## D6 — « Spectra est prêt ! » sur une stack en panne

Un dépassement de délai n'affichait qu'un `✗ timeout` cosmétique en cours de route : le
récapitulatif final s'affichait ensuite en vert, à l'identique, et le navigateur s'ouvrait.
L'utilisateur découvrait la panne dans l'interface, sans savoir quel service manquait.

**Correctif.** L'échec de `--wait` est capturé : le récapitulatif annonce un démarrage
partiel, `docker compose ps` est affiché, la commande de diagnostic est donnée, la cause la
plus fréquente est nommée (un GGUF absent de `data/models/` — `llm-chat` et `llm-embed`
attendent alors le fichier au lieu de servir), le navigateur ne s'ouvre pas et le script
sort en code 1.

## D7 — `scripts/adddoc.sh` n'existait pas

La dernière ligne de `setup.sh` proposait `bash scripts/adddoc.sh examples`, et
`examples/README.md` la reprenait. Le fichier n'était pas dans le dépôt : seul
`adddoc.bat` existait. Le chemin de découverte offert à la fin de l'installation menait donc,
sur Linux et macOS, à un `No such file or directory`.

**Correctif.** `scripts/adddoc.sh` écrit (curl multipart, suivi de la tâche d'ingestion,
extraction du `taskId` sans dépendance à `jq`). Au passage, `adddoc.bat` faisait `cd` vers
`scripts\` et non vers la racine : ses chemins par défaut (`data\documents`, `examples`)
désignaient `scripts\data\documents` et `scripts\examples`, tous deux inexistants.

## D8 — `build.sh` : `--no-cache` systématique

`build.sh` et `build.bat` passaient `--no-cache` sans condition. Chaque appel retéléchargeait
l'intégralité des dépendances Maven et npm — plusieurs minutes pour une virgule changée — et
annulait au passage le cache de dépôt Maven que le `Dockerfile` monte précisément pour éviter
cela.

Le nom du JAR était par ailleurs annoncé en dur (`spectra-api-1.1.0-SNAPSHOT.jar`) : à la
première montée de version, le script désigne un fichier inexistant.

**Correctif.** `--no-cache` devient une option, documentée comme outil de diagnostic. Le nom
du JAR est lu sur le disque.

## D9 — Impossible de reconstruire au démarrage

`start.sh` ne construisait que si l'image était absente. Après une modification du code,
`./scripts/start.sh` redémarrait donc l'**ancienne** image, sans rien dire. Le contournement
supposait de connaître l'invocation Compose complète.

Côté Windows, le test d'existence portait sur `docker image inspect spectra-spectra-api` —
un nom supposant un projet nommé « spectra », alors que Compose le dérive du répertoire
(`spectrallm-spectra-api` pour un clone standard). L'inspection échouait donc **toujours**,
et chaque `start.bat` relançait un build complet dont il n'avait pas besoin : le défaut exact
inverse du précédent, sur la même ligne de code.

**Correctif.** Option `--build` des deux côtés ; côté Windows, l'image est cherchée via
`compose images -q spectra-api`, comme dans le jumeau shell.

---

# Seconde passe — build et exposition réseau

Les quatre constats qui suivent viennent d'une relecture ciblée sur ce que le déploiement
coûte réellement : le temps de construction, le temps de démarrage, et la surface exposée.
Ils sont indépendants des neuf premiers et ont été livrés séparément (#316, #317).

## D10 — Le `node_modules` de l'hôte écrasait celui du conteneur

Le service frontend construit avec `context: ./frontend`, et Docker cherche le
`.dockerignore` **à la racine du contexte**. Celui du dépôt ne s'y appliquait donc pas : ses
lignes `frontend/node_modules` et `frontend/dist` n'ont jamais eu le moindre effet sur cette
image — elles décrivent le contexte de `spectra-api`, dans lequel `frontend/` est de toute
façon exclu en entier.

Sans exclusion, l'ordre du Dockerfile se retournait contre lui-même :

```dockerfile
COPY package*.json ./
RUN npm ci        # produit /app/node_modules — correct, alpine/musl
COPY . .          # ← recopie le node_modules de l'HÔTE par-dessus
RUN npm run build
```

Les binaires natifs (esbuild, rollup) sont liés à la plateforme et à la libc : un
`node_modules` glibc ou macOS atterrissait dans une image **alpine (musl)**.

Ce qui rendait le défaut durable, c'est qu'il était structurellement invisible en CI : le job
`e2e`, celui qui construit l'image, tourne sur un runner distinct du job `frontend`, et son
checkout n'a donc jamais de `node_modules`. En local, c'était l'inverse — `verify.sh` installe
les dépendances dans `frontend/`, et un `build.sh` derrière tombait dessus.

**Correctif.** `frontend/.dockerignore`. Le contexte expédié au démon passe de 268 Mo à
1,6 Mo, mesuré en rejouant les motifs sur l'arborescence réelle.

## D11 — Jusqu'à 70 s d'attente pure au démarrage

Sans `start_interval`, Docker sonde toutes les `interval` secondes, y compris pendant le
`start_period` : un service prêt à t=8 s n'était déclaré sain qu'à t=30 s. L'attente est
*pure* — rien ne se passe pendant ce temps.

Le coût ne s'arrêtait pas à un service, parce que `depends_on: condition: service_healthy`
sérialise la chaîne et fait s'additionner les granularités : chromadb (20 s) → spectra-api
(20 s) → frontend (30 s). C'est autant sur chaque `up --wait`, donc sur chaque `start.sh`
et sur chaque exécution du job E2E.

**Correctif.** `start_interval: 3s` sur huit services, `interval` inchangé en régime établi.
`kafka` en est dépourvu à dessein, son `interval` valant déjà 5 s. Contrepartie assumée : le
champ demande Docker Engine ≥ 25 (janvier 2024), désormais explicite dans les deux README.

**Ce que la mesure a montré, et pas montré.** Rien de mesurable, et c'est une conclusion en
soi. Le job E2E n'a pas raccourci — 3 min 40 contre 3 min 34 avant, puis 4 min 12 une fois
`start_interval` et `up --wait` réunis. L'écart est du bruit, dans les deux sens.

La raison est structurelle : **la CI ne peut pas isoler ce gain**. La durée du job est
dominée par la construction des images, qui variait alors de plusieurs minutes d'un run à
l'autre faute de cache de couches (voir D14) ; quelques dizaines de secondes de sondage s'y
noient. Les runners ne sont pas non plus identiques d'une exécution à l'autre.

**Les 70 s restent donc un calcul** à partir des `interval` déclarés et de la chaîne
`depends_on`, pas une mesure — et il ne faut pas les présenter autrement. La seule façon
honnête de les observer serait de chronométrer `up -d --wait` seul, images déjà construites,
avant/après. Le cache de couches de D14 rend cette mesure possible en stabilisant le temps de
build ; elle n'a pas encore été faite.

## D12 — `SPECTRA_API_KEY` n'atteignait jamais le conteneur

`ApiKeyFilter` lit `@Value("${SPECTRA_API_KEY:}")` et protège `/api/**` dès que la clé est
non vide. Le filtre fonctionne.

Le défaut était en amont : le bloc `environment:` de `spectra-api` ne listait pas la
variable, et le fichier Compose n'a pas d'`env_file:`. Or **Compose n'injecte pas `.env` dans
les conteneurs** — il ne s'en sert que pour interpoler le fichier Compose lui-même.

Renseigner `SPECTRA_API_KEY` dans `.env`, exactement comme `.env.example` l'explique sur dix
lignes, ne faisait donc rien : le filtre restait sur une chaîne vide, journalisait
« authentification API désactivée » et laissait passer toutes les requêtes, pendant que
`scripts/pipeline.sh` envoyait consciencieusement un en-tête `X-API-Key` que personne ne
vérifiait.

Le contrôle est documenté à trois endroits. `docs/process/audit-securite.fr.md` le classait
déjà « non sécurisé par défaut » (S2), en le présentant comme un opt-in qu'on oublie ; le
constat était incomplet, car le défaut n'était pas seulement ouvert mais **irrémédiable**
sans éditer le fichier Compose. Le paragraphe S2 a été mis à jour.

**Correctif.** Une ligne de passe-plat. Vérifié sur la configuration rendue : `""` par
défaut, la valeur transmise quand elle est posée.

## D13 — Neuf ports publiés sur toutes les interfaces, sans authentification

Les mappings étaient de la forme `"8000:8000"`, donc sur `0.0.0.0`. Sur un portable en wifi
ou un serveur, tout le réseau local atteignait, sans la moindre authentification :

| Port | Service | Ce qu'on y fait |
|---|---|---|
| 8000 | ChromaDB | lecture **et écriture** sur l'index vectoriel, donc sur le corpus ingéré |
| 8081 / 8082 | llama.cpp chat / embed | inférence libre — `llama-server` n'a pas d'authentification |
| 8080 | spectra-api | celle de D12, donc aucune en pratique |
| 8002 / 8003 / 8004 / 9092 | reranker, docparser, trainer, kafka | tout |

Pour un projet dont l'argument central est « 100 % local, vos données ne quittent jamais
votre machine », publier au réseau la base qui *contient* les documents méritait d'être une
décision, pas un défaut.

Aucun de ces ports n'est nécessaire au fonctionnement : les services se parlent par le réseau
Compose, sous leurs noms de service. La publication ne sert qu'à l'accès depuis l'**hôte** —
navigateur sur 80, scripts et Swagger sur 8080, diagnostic pour le reste.

**Correctif.** `SPECTRA_BIND_ADDR`, défaut `127.0.0.1`, sur les neuf mappings.
`SPECTRA_BIND_ADDR=0.0.0.0` restaure le comportement précédent pour un déploiement
multi-hôte volontaire. C'est un changement de comportement pour qui consulte Spectra depuis
un autre poste — assumé : l'échec est immédiat et lisible (connexion refusée), pas une
dégradation silencieuse, et la remise en route tient en une variable documentée.

## D14 — Le job le plus long de la CI était le seul à construire sans cache

E2E dure ~4 min et reconstruisait intégralement `spectra-api` (`mvn package`) et le frontend
(`npm ci` + build Vite) à chaque exécution. `profiled-images.yml` alimente pourtant déjà un
cache GitHub Actions pour les images qu'il construit : la stratégie existait dans le dépôt,
elle n'était simplement pas appliquée là où elle coûtait le plus cher.

`type=gha` exige le pilote **docker-container** de buildx, donc une construction hors de
`docker compose build`. Les deux images sont bâties par `docker/build-push-action` avec
`load: true` — contrairement à `profiled-images.yml`, qui passe `load: false` parce qu'il ne
fait que chauffer le cache ; ici l'image doit atterrir dans le magasin local, puisque c'est
Compose qui la démarre.

**Le piège, et pourquoi il existe un fichier de plus.** Compose doit ensuite *retrouver* ces
images. Sans `image:`, il dérive le nom du répertoire du projet (`spectrallm-spectra-api`) —
une convention qu'aucun fichier du dépôt ne déclare, et que `docker compose config` ne rend
même pas pour un service qui n'a qu'un `build:`. Bâtir dessus, c'était accepter que le jour
où elle change, Compose ne trouve rien, **reconstruise sans un mot**, et que le cache devienne
un no-op invisible : CI verte, job aussi lent qu'avant, personne ne le voit. Soit exactement
la famille de défauts que D10 et D12 viennent de refermer.

`deploy/docker/docker-compose.ci.yml` déclare donc les deux noms, une fois, et le workflow
construit exactement ces tags. **`up --no-build` ferme la boucle** : si les noms divergeaient,
Compose échoue franchement au lieu de reconstruire en douce. Ce n'est pas une optimisation,
c'est le garde-fou du cache.

Au passage, `COMPOSE_FILE` et `COMPOSE_PROJECT_NAME` au niveau du job remplacent les `-f`
répétés sur cinq commandes — l'overlay ne peut plus être oublié sur l'une d'elles, où `logs`
et `down` viseraient alors un autre projet que `up`. Et `verify.sh` valide ce nouvel overlay
comme il valide déjà celui du GPU : un fichier que personne ne charge localement verrait son
erreur de syntaxe n'apparaître qu'en CI, sur le job le plus lent à diagnostiquer.

---

## Suite de l'audit — cinq PR

Les correctifs ont été livrés séparément, chacun partant de `main`, pour que la revue puisse
accepter ou écarter un axe sans bloquer les autres. Les cinq sont fusionnées.

| PR | Contenu | Constats |
|---|---|---|
| #315 | Scripts d'installation, de démarrage et d'arrêt | D1 → D9 |
| #316 | Contexte de build frontend, granularité des healthchecks | D10, D11 |
| #317 | Exposition réseau et authentification | D12, D13 |
| #325 | Cache de couches GHA sur le job E2E | D14 |
| #326 | Test de garde sur le passe-plat des variables et la liaison des ports | verrouille D12, D13 |

Le conflit annoncé entre #316 et #317, qui modifient toutes deux `docker-compose.yml`, ne
s'est pas matérialisé : `start_interval` vit dans les blocs `healthcheck:`, le durcissement
dans `ports:` et `environment:`. Git a fusionné sans arbitrage — vérification faite ensuite
sur la configuration *rendue*, et pas seulement sur l'absence de marqueur de conflit.

**#326 est né d'une remarque de la revue de #317** : le correctif de `SPECTRA_API_KEY` tenait
à une ligne, et rien n'empêchait qu'elle disparaisse à nouveau. `scripts/tests/test_compose_env_passthrough.py`
interroge la configuration rendue par `docker compose config` — un `grep` sur le YAML verrait
la ligne sans savoir dans quel service elle vit — et vérifie que toute variable lue par le
backend via `@Value("${SCREAMING_SNAKE}")` figure bien dans l'environnement de `spectra-api`,
et que tout port publié porte `host_ip: 127.0.0.1`. Une troisième assertion garde la garde :
si la regex cesse de correspondre au code Java, le premier contrôle passerait à vide, et il
échoue alors bruyamment plutôt que de rassurer à tort. Les deux régressions ont été rejouées
pour confirmer que le test les attrape réellement.

---

## Ce qui n'a pas été touché

- **La duplication `.sh` / `.bat`** (~1 200 lignes). Elle est irréductible — batch ne source
  pas du bash — et `test_windows_scripts_parity.py` en contient déjà la dérive d'interface.
  Les corrections ci-dessus ont été portées des deux côtés.
- **Le nombre de scripts.** Fusionner `setup.sh` dans `start.sh` était tentant ; ils ont des
  publics distincts (`setup.sh` est aussi la porte d'entrée d'un développeur qui ne veut pas
  démarrer la stack) et `--first-run` couvre déjà le cas combiné.
- **Les 39 clés actives de `.env.example`.** Les commenter serait cohérent avec son rôle de
  catalogue, mais `check-model-defaults.sh` en fait sa source de vérité pour les noms de
  GGUF : le changement demande d'y toucher aussi, et sort du périmètre du démarrage.
- **Le cache npm du build frontend.** Maven et pip ont leur `--mount=type=cache`, npm non.
  Le cache de couches de D14 le masque en partie — tant que `package-lock.json` ne bouge pas,
  la couche `npm ci` est réutilisée telle quelle — mais pas quand une dépendance change.
- **La taille du modèle en E2E.** 4,7 Go de Qwen 7B pour piloter Playwright. Un GGUF
  minuscule suffirait, mais cela touche à ce que le test prétend valider : c'est une décision
  de fond, pas une optimisation.
- **Le reste de la surface de sécurité.** D12 et D13 refermaient deux trous béants du chemin
  de déploiement ; ils ne traitent ni S1 (aucune identité par utilisateur, `?actor=` purement
  déclaratif) ni l'absence d'authentification propre à ChromaDB et à llama-server — seul
  `spectra-api` connaît `SPECTRA_API_KEY`. C'est pourquoi `SPECTRA_BIND_ADDR=0.0.0.0` reste
  une décision à prendre en connaissance de cause, et non un simple interrupteur. Voir
  `docs/process/audit-securite.fr.md`.
- **La mesure réelle du gain de D11.** `scripts/bench-startup.sh` la produit désormais : il
  construit les images hors chronomètre, puis chronomètre `up -d --wait` seul sur plusieurs
  itérations. Il n'a pas pu être exécuté ici (aucun démon Docker dans l'environnement
  d'audit) — **les 70 s restent donc un calcul**, et le resteront jusqu'à ce que quelqu'un
  lance le script sur une machine équipée, une fois avec `start_interval` et une fois sans.

## Vérification

```
scripts/tests/          349 tests                              OK
shellcheck -S error     scripts/*.sh scripts/lib/*.sh          OK
docker compose config   base + overlay GPU + overlay CI        OK
check-model-defaults.sh cohérence des GGUF par défaut          OK
CI                      verte sur les cinq PR, E2E compris
```

Les cinq états de `.env` (absent, avec bornes, legacy shell, legacy batch, copie de
`.env.example`) ont été rejoués manuellement : bloc unique, idempotence sur deux passages
consécutifs, réglages personnels conservés, `.env.bak` produit lors des deux migrations.

La configuration **rendue** par Compose a été vérifiée, et pas seulement sa syntaxe : les
huit `start_interval` présents une fois tous les profils actifs (sans profils, Compose n'en
rend que cinq — les services optionnels ne sont pas instanciés), `host_ip: 127.0.0.1` sur les
neuf ports et `0.0.0.0` sous surcharge, `SPECTRA_API_KEY` transmis quand il est posé, et
l'overlay CI rendant bien `spectra-e2e-api:ci` et `spectra-e2e-frontend:ci`. Ces trois
derniers points ne relèvent plus de la vérification manuelle : `test_compose_env_passthrough.py`
les rejoue à chaque exécution de la suite.

**Ce qui n'a pas pu être vérifié ici**, faute de démon Docker dans l'environnement d'audit :
l'exécution réelle de `up --wait` et `down`, le gain de temps effectif de D11, le 401 renvoyé
par l'API quand la clé est active, le refus de connexion depuis une autre machine, et les
scripts `.bat` (aucun hôte Windows).

Le job E2E de la CI comble une partie de cette liste, et c'est à lui qu'il faut se fier
plutôt qu'aux vérifications locales : il a validé que le durcissement de D13 ne casse pas
l'accès local (la CI interroge la stack depuis l'hôte et passe avec le binding sur la boucle
locale), que l'image frontend se construit avec le `.dockerignore` de D10, que le runner
tourne bien sur un Docker Engine ≥ 25 comme `start_interval` l'exige, et que le montage de
D14 charge effectivement les images sous les tags attendus — `up --no-build` aurait échoué
sinon.

Restent hors de portée de tout ce qui a été fait ici : le 401, le refus de connexion
distante, et les scripts Windows.
