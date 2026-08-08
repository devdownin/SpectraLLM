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

---

## Résumé

Neuf constats, dont trois pannes silencieuses. Le fil commun n'est pas la complexité des
scripts : c'est la **duplication d'une définition** — du fichier `.env`, de l'invocation
Compose, de la notion de « service prêt ». Chaque fois, deux endroits décrivaient la même
chose, et un seul faisait autorité.

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

## Vérification

```
scripts/tests/          346 tests   OK
shellcheck -S error     scripts/*.sh scripts/lib/*.sh   OK
docker compose config   base + overlay GPU              OK
```

Les quatre états de `.env` (absent, avec bornes, legacy shell, legacy batch, copie de
`.env.example`) ont été rejoués manuellement : bloc unique, idempotence sur deux passages
consécutifs, réglages personnels conservés, `.env.bak` produit lors des deux migrations.
