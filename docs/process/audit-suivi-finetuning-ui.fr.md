# Audit — Suivi du fine-tuning dans l'interface

> Audit du 2026-08-05. Périmètre : **ce que l'utilisateur voit d'un entraînement pendant qu'il
> tourne**, de la soumission à la mise en service. Croisement page `frontend/src/pages/FineTuning.tsx`,
> composants de suivi (`components/charts/LossChart.tsx`, `components/TaskCenter.tsx`,
> `hooks/useSse.ts`, `hooks/useGlobalTasks.ts`), transport (`SseController`,
> `TrainingLogBroadcaster`, `FineTuningController`, `GlobalExceptionHandler`), état serveur
> (`FineTuningJob`, `FineTuningService`, `TaskActivityService`), exécution
> (`ProcessTrainingRunner`, `HttpTrainingRunner`, `services/trainer/app.py`) et émission
> (`scripts/train_host.py`, `scripts/train.sh`, `scripts/export_gguf.py`).
>
> Complémentaire de l'[audit fine-tuning](audit-finetuning.fr.md), qui porte sur la **correction
> de l'entraînement** ; celui-ci ne porte que sur son **observabilité côté utilisateur**.
>
> **Constat général.** Le suivi est bien **câblé** — machine à états explicite, flux SSE avec
> indicateur d'état et reconnexion, sondage tolérant aux erreurs, centre d'activité global avec
> ETA et notifications — mais il est **grossier et, par endroits, faux**. Trois points structurent
> le reste :
>
> 1. **Le signal le plus utile est jeté à 99 %.** `train_host.py` émet une loss à *chaque étape*
>    (`logging_steps=1`) ; la chaîne n'en conserve **qu'un point par époque entière**. Un job
>    nominal de 3 époques produit une courbe de 2 à 3 points — souvent moins que les 2 points
>    minimum exigés par `LossChart`, qui affiche alors « Accumulating data… » pendant la quasi-
>    totalité du run.
> 2. **La granularité de la progression est l'époque entière, tronquée.** L'expression régulière
>    `epoch[= ]*(\d+)` lit `0` dans `epoch=0.97` : pendant toute la première époque — soit le
>    premier tiers d'un run par défaut — la barre de progression, le compteur d'époques et la loss
>    sont **entièrement masqués**, et l'étape courante affiche « Entraînement epoch 0/3 ».
> 3. **La télémétrie n'est conservée nulle part.** Le flux SSE est un canal sans mémoire :
>    rechargement de page, navigation vers un autre écran ou simple coupure réseau effacent
>    définitivement les lignes déjà émises. Pour un travail qui dure des heures, l'historique
>    d'exécution n'existe que dans les logs serveur, invisibles depuis l'UI.
>
> S'y ajoutent deux affichages **inexacts** — un job échoué est toujours signalé à l'étape
> « Import » quelle que soit l'étape réellement fautive (S5), et sélectionner un ancien job dans
> l'historique lui attribue la courbe et les logs du job courant (S9) — ainsi que l'absence de
> bouton d'annulation sur la page, alors que l'API et le centre d'activité global le proposent
> tous deux (S15).
>
> **Aucun constat n'est bloquant** : l'entraînement aboutit et l'utilisateur finit par être
> notifié. Ce sont des défauts de *pilotage* — on ne peut ni juger de l'avancement, ni
> diagnostiquer un échec, ni détecter un sur-apprentissage depuis l'interface.
>
> Aucun test n'existe sur cette page : `FineTuning.tsx` n'a pas de fichier de test, et `LossChart`
> non plus. Seuls les normaliseurs du centre d'activité sont couverts (`useGlobalTasks.test.ts`).

---

## 1. État des lieux — ce que l'UI montre par phase

| Phase (statut) | Affiché | Ce qui manque |
|---|---|---|
| Soumission | Toast « Job submitted » + id court | Le panneau reste vide ~4 s (S17) ; refus 409 illisible (S16) |
| `PENDING` | Étape 1 active | Durée d'attente, position (un seul job à la fois, jamais dit) |
| `EXPORTING_DATASET` | Étape 2 active, 1 ligne SSE | Aucune progression ; `datasetSize` n'arrive qu'à la fin |
| `TRAINING` (époque 0) | Étape 3 active, logs bruts | **Barre, époque et loss masquées** (S3) |
| `TRAINING` (époques ≥ 1) | Barre par paliers de 1/N, loss instantanée, courbe à 2-3 points | Étapes, temps écoulé, ETA, débit, eval_loss fiable (S1, S2, S13) |
| `IMPORTING_MODEL` | Étape 4 active, 1 ligne SSE | Plusieurs minutes de silence total (S14) |
| `COMPLETED` | Toast, `outputPath`, étape « Complete » **non allumée** (S6) | Durée totale, lien vers le déploiement (S19) |
| `FAILED` | Bandeau d'erreur, étape « Import » en rouge **quelle que soit la phase fautive** (S5) | Phase réelle de l'échec, distinction annulation / échec (S7) |

Deux surfaces de suivi coexistent et ne disent pas la même chose : la page (barre d'étapes, courbe,
télémétrie) et le **centre d'activité global** (`TaskCenter`, pastille du header), qui affiche une
ETA, une barre de progression et **un bouton d'annulation** que la page n'a pas. Le suivi le plus
complet du fine-tuning se trouve donc aujourd'hui *ailleurs que sur la page de fine-tuning*.

---

## 2. La courbe de perte

### S1 — La loss par étape est agrégée à un point par époque — **élevé**

`train_host.py` fixe `logging_steps=1` sur les trois chemins d'entraînement (lignes 443, 469, 516)
et `ProgressLogger` (`train_host.py:375-383`) imprime une ligne par étape :

```
  epoch=0.33  loss=1.8421
```

Ces lignes sont perdues deux fois :

- **Côté serveur** — `FineTuningJob.withTrainingProgress` (`FineTuningJob.java:54-62`) n'a qu'un
  champ `loss` scalaire : chaque ligne **écrase** la précédente. Le job ne porte aucune série.
- **Côté client** — `FineTuning.tsx:326-336` indexe chaque point sur `currentEpochRef.current`,
  un **entier**, et fusionne dans le point existant quand l'époque n'a pas changé
  (`if (last?.epoch === epoch) return [...prev.slice(0, -1), { ...last, ...point }]`).

Sur un run par défaut (3 époques), la série finale compte **2 à 3 points** — un par époque
franchie, l'époque 0 étant écartée (S3). Or `LossChart:31-37` refuse de tracer en dessous de 2
points. Le comportement observable est donc : « Waiting for loss data… » pendant toute la première
époque, « Accumulating data… » pendant la deuxième, puis une droite entre deux points.

Une courbe de loss à trois points ne permet ni de voir un plateau, ni une divergence, ni un pic —
c'est-à-dire aucune des décisions que l'on prend en regardant une courbe de loss.

> Correctif de fond : porter la série côté job (liste bornée `{step, epoch, loss, evalLoss}`, ou un
> fichier `losses.jsonl` dans le répertoire du job) et indexer le graphe sur l'**époque
> fractionnaire** déjà présente dans la ligne (`epoch=0.33`), pas sur un entier.

### S2 — Le sondage efface l'eval_loss de la courbe — **élevé**

`FineTuning.tsx:379-387`, accumulation depuis le sondage REST :

```ts
if (last?.epoch === epoch) return [...prev.slice(0, -1), { epoch, loss: job.loss! }];
```

Le point existant est **remplacé**, pas fusionné — contrairement au chemin SSE (ligne 334) qui fait
bien `{ ...last, ...point }`. Toute `evalLoss` déjà déposée sur cette époque est donc supprimée au
prochain tick de 4 s. Le bloc ignore par ailleurs complètement `job.evalLoss`, pourtant présent
dans le DTO (`FineTuningJob.java:21`) et correctement renseigné par le backend.

Conséquence : la courbe de validation — la seule qui signale le sur-apprentissage, et la
justification même du curseur `valSplit` livré au constat F9 de l'audit précédent — **clignote puis
disparaît** selon l'ordre d'arrivée SSE / sondage. La fonctionnalité est livrée côté moteur, côté
backend et côté graphe, et se perd sur cette unique ligne.

### S3 — Toute la première époque est invisible — **élevé**

Trois filtres se cumulent sur la même cause, l'entier :

| Emplacement | Code | Effet à l'époque 0 |
|---|---|---|
| `FineTuningService.java:659` | `epoch[= ]*(\d+)` | `epoch=0.97` → `0` |
| `FineTuning.tsx:326` | `if ((trainMatch \|\| evalMatch) && epoch)` | `0` est falsy → **aucun point tracé** |
| `FineTuning.tsx:429-431` | `activeJob?.currentEpoch && …` | `0` est falsy → **bloc de progression masqué** |

Pendant le premier tiers d'un run à 3 époques — plusieurs heures sur CPU — l'utilisateur ne voit
donc **ni pourcentage, ni compteur d'époque, ni loss, ni courbe**. Le seul champ renseigné,
« Current Step », affiche littéralement `Entraînement epoch 0/3`, qui se lit comme un blocage.

C'est le moment où l'utilisateur a le plus besoin d'être rassuré (« est-ce que ça avance ? ») et
c'est celui où l'interface en dit le moins.

### S4 — L'axe des abscisses ne montre pas la progression — **faible**

`LossChart.tsx:48-55` passe `domain={[1, totalEpochs]}` et `tickCount` à un `XAxis` **sans
`type="number"`**. Le type par défaut de recharts est `category` : `domain` et `tickCount` sont
alors **inertes**. L'axe liste les époques déjà collectées et s'étire pour les remplir — la courbe
occupe toujours toute la largeur, quel que soit l'avancement. L'intention (« voir où l'on en est
sur les N époques prévues ») n'est pas réalisée.

Corollaire du même choix : la `ReferenceLine` « min » (ligne 64-71) est calculée sur la seule loss
d'entraînement, jamais sur l'eval_loss — le minimum affiché n'est donc pas celui qui compte.

---

## 3. Barre d'étapes et statut

### S5 — Un échec est toujours attribué à l'étape « Import » — **élevé**

`FineTuning.tsx:63-65` :

```ts
const current = job.status === 'FAILED'
  ? stepIndex('COMPLETED') - 1     // = 3 → IMPORTING_MODEL, toujours
  : stepIndex(job.status);
```

L'index retenu pour un job échoué est **constant**. Quelle que soit la phase réellement fautive —
dataset vide au filtrage, trainer injoignable, code de sortie non nul, adaptateur absent — la barre
allume `IMPORTING_MODEL` en rouge et laisse les trois étapes précédentes en gris, c'est-à-dire
*non franchies*. L'utilisateur lit donc l'inverse de ce qui s'est passé : « l'entraînement a
réussi, c'est l'import qui a échoué ».

La cause est double, et la moitié serveur est la plus coûteuse : `FineTuningJob.failed()`
(`FineTuningJob.java:80-86`) écrase `currentStep` par la chaîne `"Échoué"`. **La phase de l'échec
n'est conservée nulle part** — ni le statut, ni l'étape, ni l'entité JPA ne la portent. Même en
corrigeant le composant, l'information n'existe plus.

> Correctif : conserver le statut atteint avant l'échec (champ `failedAt` / `lastStatus` sur le
> DTO et l'entité), et dériver `current` de ce champ dans `StepBar`.

### S6 — L'étape « Complete » n'est jamais allumée — **faible**

`FineTuning.tsx:70-72` : pour un job `COMPLETED`, `current = 4`, `isDone = i < current` (donc faux
en 4) et `isActive = i === current && job.status !== 'COMPLETED'` (faux par construction). Le
dernier jalon reste dans le style neutre `border-outline-variant/30 text-outline` — un run réussi
affiche quatre étapes vertes suivies d'une pastille grise. La fin du processus n'est jamais
marquée comme atteinte.

### S7 — Une annulation est présentée comme un échec — **moyen**

`cancelJob` (`FineTuningService.java:255`) écrit `job.failed("Annulé par l'utilisateur")` : le
statut devient `FAILED`, badge rouge, toast d'erreur, et `toStatus()` du centre d'activité
(`useGlobalTasks.ts:82`) reclasse `CANCELLED` → `failed` — un mapping qui prévoit un statut que le
backend n'émet jamais. Même traitement pour la réconciliation au démarrage
(`FineTuningService.java:155`, « Interrompu par un redémarrage du serveur »).

Trois évènements de nature différente — le job a planté / l'utilisateur l'a arrêté / le serveur a
redémarré — sont donc rendus de façon identique. L'énumération `Status` supporterait un
`CANCELLED` sans autre changement de contrat ; le frontend le gère déjà.

---

## 4. Flux de télémétrie

### S8 — Les logs ne survivent ni au rechargement, ni à la navigation — **élevé**

Aucun maillon de la chaîne ne conserve les lignes :

- `TrainingLogBroadcaster.java:32-33` — `Sinks.many().multicast().onBackpressureBuffer(500, false)`
  ne rejoue son tampon qu'au **premier** abonné. Un abonné ultérieur (rechargement de page)
  ne reçoit que ce qui est émis *après* sa connexion.
- Même fichier, lignes 43-44 — le handler d'émission ne retente que sur `FAIL_NON_SERIALIZED`.
  Sans abonné, `emitNext` renvoie `FAIL_ZERO_SUBSCRIBER` et l'évènement est **jeté silencieusement**.
  Tout ce qui est émis onglet fermé est perdu.
- `FineTuning.tsx:168` — `logs` est un `useState` local à la page. Naviguer vers Chat et revenir
  vide la liste.
- Aucun endpoint ne rend les logs d'un job : `FineTuningController` n'expose que `GET /{jobId}` et
  la liste. Le répertoire du job ne contient pas de fichier de log.

Les lignes n'existent en réalité qu'à un seul endroit durable : les logs serveur
(`ProcessTrainingRunner.java:161`, `HttpTrainingRunner.java:232`), inaccessibles depuis l'UI. Pour
un travail de plusieurs heures, c'est le mode de perte le plus banal — rafraîchir la page — qui
détruit l'intégralité de la trace.

> Correctif proportionné : écrire la sortie dans `data/fine-tuning/<jobId>/train.log` (le
> répertoire existe déjà, `FineTuningService.java:305`) et exposer
> `GET /api/fine-tuning/{jobId}/logs?tail=N`, que la page charge au montage avant de brancher le
> SSE. Bénéfice secondaire : les logs d'un job **terminé** deviennent consultables, ce qu'aucun
> chemin ne permet aujourd'hui.

### S9 — Le flux n'est rattaché à aucun job — **moyen**

L'évènement SSE porte `{level, message, timestamp}` (`TrainingLogBroadcaster.java:36-39`) : **pas
de `jobId`**. La page consomme donc un canal global et l'affiche comme la télémétrie du job
sélectionné. Trois conséquences observables :

- cliquer sur une ligne de l'historique (`FineTuning.tsx:786`) fait `setActiveJob(job)` **sans**
  réinitialiser `logs` ni `lossHistory` : un job terminé la semaine dernière s'affiche avec la
  courbe et les logs du job en cours ;
- les lignes de l'export GGUF (`onProcessLine(jobId, "export", …)`) et les messages de traçabilité
  GED se mêlent aux lignes d'entraînement sans distinction ;
- le ré-entraînement automatique déclenché par les commentaires (`ArticleCommentService`) émet dans
  le même canal, sans que rien n'indique qu'il s'agit d'un autre job.

Ajouter `jobId` à l'évènement et filtrer côté page est un changement de deux lignes de chaque côté.

### S10 — Le hook SSE ne garantit pas la livraison — **moyen**

`useSse.ts:39-45` expose **le dernier message** via `useState`. La page en dérive son historique
(`FineTuning.tsx:317-319`) en s'abonnant aux changements de cette valeur. Une file consommée par
un `useState` scalaire n'a aucune propriété de livraison : deux évènements traités avant que React
n'ait rendu la mise à jour intermédiaire ne laissent qu'un seul message observable. Sous rafale —
et une barre de progression `tqdm` en produit des centaines par seconde (S12) — des lignes
disparaissent, sans compteur ni marqueur de trou. Le compteur « N events » de l'en-tête
(`FineTuning.tsx:725`) affirme donc une complétude qu'il ne peut pas vérifier.

> Correctif : passer `useSse` à une API par **callback** (`onMessage`) ou à une file drainée, de
> sorte que le consommateur voie chaque évènement une fois et une seule.

### S11 — Tout est de niveau INFO, y compris les erreurs — **moyen**

`FineTuningService.java:583` publie **chaque** ligne du processus via `broadcaster.info(line)`. Or
`stderr` est fusionné dans `stdout` en amont (`ProcessTrainingRunner.java:147` `redirectErrorStream(true)`,
`services/trainer/app.py:136` `stderr=subprocess.STDOUT`). Une trace Python, un avertissement CUDA,
un « ERREUR conversion GGUF » d'`export_gguf.py` arrivent donc taggés `INFO` et rendus en bleu
comme le reste (`FineTuning.tsx:743` ne colore en rouge que `level === 'ERROR'`). Le seul
`broadcaster.error` du parcours est celui de l'exception Java finale
(`FineTuningService.java:384`).

Le rendu aggrave la lecture : chaque ligne est contrainte à 24 px et `truncate`
(`FineTuning.tsx:741-744`) — une trace d'exception est coupée à droite, sans repli, sans info-bulle,
sans sélection multi-lignes praticable, et sans bouton de copie ou d'export. Le diagnostic exige de
sortir de l'application.

> Correctif minimal : détecter le niveau à la source (préfixe `ERREUR`/`Traceback`/`WARNING`) ou
> publier `stderr` sur un canal `error` distinct, et rendre les lignes dépliables + copiables.

### S12 — Le flux est haché : silence puis rafale — **moyen**

Deux effets de tampon opposés se combinent, et aucun n'est neutralisé :

- **Silence.** Aucun `PYTHONUNBUFFERED` ni `python -u` nulle part (`scripts/train.sh:70`,
  `services/trainer/Dockerfile`, `docker-compose.yml`). Sur un `stdout` redirigé vers un tube,
  Python bufférise par blocs ; seul `ProgressLogger` appelle `sys.stdout.flush()`
  (`train_host.py:383`). Tous les `print` **antérieurs** au premier pas d'entraînement —
  téléchargement HuggingFace du modèle de base, tokenisation, split de validation — restent donc
  coincés dans le tampon jusqu'au premier flush. C'est exactement la phase la plus longue et la
  plus silencieuse du run.
- **Rafale.** Les barres de progression `tqdm` écrivent des mises à jour terminées par `\r`.
  `BufferedReader.readLine()` (Java) et l'itération ligne à ligne en mode texte (Python) traitent
  `\r` comme une fin de ligne : chaque rafraîchissement de barre devient **un évènement SSE**. Le
  tampon de 500 du sink est retourné en quelques secondes, et l'utile est chassé par le décoratif.

Un filtre à la source (ignorer les lignes purement `\r`, ou n'en garder qu'une par seconde) et un
`PYTHONUNBUFFERED=1` dans l'image du trainer suffisent à rendre le flux lisible.

---

## 5. Progression, durée, phases muettes

### S13 — Ni temps écoulé, ni temps restant sur la page — **moyen**

`createdAt` n'est utilisé que par l'historique, et seulement en date
(`FineTuning.tsx:802`, `toLocaleDateString` — **sans l'heure**, donc deux jobs du même jour sont
indiscernables). Le moniteur actif n'affiche ni heure de démarrage, ni durée écoulée, ni estimation.
Un job COMPLETED n'affiche pas sa durée totale, alors que `completedAt` et `createdAt` sont tous
deux présents dans le DTO.

Le centre d'activité, lui, calcule une ETA (`useGlobalTasks.ts:290-301`) — mais par extrapolation
linéaire depuis `createdAt`, qui **inclut l'export du dataset et le téléchargement du modèle de
base**, et à partir d'une progression en paliers d'époque entière. L'estimation est donc
pessimiste au début puis saute par tiers. Repartir de l'instant du passage en `TRAINING` et d'une
époque fractionnaire la rendrait exploitable — et permettrait de l'afficher aussi sur la page.

### S14 — Deux phases sans aucun signal de vie — **moyen**

- `EXPORTING_DATASET` : une seule ligne SSE au début (`FineTuningService.java:302`), puis rien
  jusqu'à `withDatasetSize`. Sur un corpus important, le filtrage et l'écriture JSONL prennent du
  temps sans que rien ne bouge.
- `IMPORTING_MODEL` : c'est le pire cas. La fusion LoRA puis la conversion GGUF durent plusieurs
  minutes, et `export_gguf.py:76-79` lance la conversion avec `capture_output=True` —
  **toute la sortie est avalée** et n'est réémise qu'en cas d'échec. Le mécanisme de diffusion
  ligne à ligne existe des deux côtés et se trouve neutralisé ici (déjà relevé au constat F12 de
  l'audit fine-tuning, sous l'angle approvisionnement).

Dans les deux cas, l'anneau animé de la barre d'étapes est le **seul** indice que l'application
n'est pas figée.

---

## 6. Pilotage depuis la page

### S15 — Aucun bouton d'annulation sur la page de fine-tuning — **élevé**

`DELETE /api/fine-tuning/{jobId}` existe (`FineTuningController.java:135-146`), `fineTuningApi.cancelJob`
existe (`services/api.ts:103`), et **`TaskCenter` l'utilise** avec confirmation
(`TaskCenter.tsx:55`, 411-424). `FineTuning.tsx` ne l'appelle **jamais**. L'écran dédié au
fine-tuning est donc le seul endroit de l'application d'où l'on ne peut pas arrêter un
entraînement : il faut ouvrir la pastille du header. Pour un travail qui monopolise CPU/GPU pendant
des heures et interdit tout autre job (verrou `trainingRunning`), c'est l'action de pilotage la
plus attendue.

À noter, un effet de bord documenté dans l'audit précédent et toujours présent : annuler puis
resoumettre immédiatement renvoie un 409 transitoire, `trainingRunning` n'étant libéré que par le
`finally` du thread asynchrone (`FineTuningService.java:386-389`).

### S16 — Le motif du refus 409 n'est jamais affiché — **moyen**

`FineTuningController.java:90-92` renvoie `{"error": "Un entraînement est déjà en cours"}` (un
`Map`), tandis que la page lit `err?.response?.data?.detail` (`FineTuning.tsx:422`) — le format
`ProblemDetail` utilisé partout ailleurs. Le message utile est perdu et remplacé par
« Request failed with status code 409 ». Le contraste est net avec le 503 « entraînement
indisponible », qui passe bien par `ProblemDetail` (`GlobalExceptionHandler.java:91-100`) et
s'affiche correctement — c'est l'un des rares messages d'erreur vraiment actionnables du produit.

`TaskCenter.tsx:178` gère déjà les deux formes (`data.detail ?? data.error ?? message`) ; il suffit
d'aligner la page, ou mieux, de faire renvoyer un `ProblemDetail` au contrôleur.

### S17 — La réponse de création n'est pas un job — **faible**

`POST /api/fine-tuning` renvoie `{jobId, status}` (`FineTuningController.java:93`), que
`onFormSubmit` type et stocke comme un `FineTuningJob` complet (`FineTuning.tsx:415-417`). Pendant
les ~4 s qui séparent la soumission du premier sondage, le panneau affiche un titre de modèle vide,
« from  » sans base, aucun dataset et aucune progression. La barre d'étapes fonctionne (le champ
`status` vaut bien `"PENDING"`), mais le reste du panneau est creux. Renvoyer le `FineTuningJob`
complet supprime la fenêtre.

### S18 — L'indisponibilité de l'entraînement n'est connue qu'après coup — **faible**

`TrainingRunner.isAvailable()` est consulté à la soumission (`FineTuningService.java:186`) et le
motif renvoyé est excellent — il nomme la commande à lancer. Mais rien n'est interrogeable *avant* :
la page ne dispose d'aucun indicateur, et le formulaire s'affiche identique que le trainer soit
démarré ou non. L'utilisateur remplit un formulaire, choisit ses hyperparamètres, lance, et
découvre alors que le profil `trainer` n'est pas actif. Exposer l'état du runner (par exemple dans
`/api/fine-tuning/base-models` ou un `GET /api/fine-tuning/availability`) permettrait d'afficher le
motif en tête de formulaire et de désactiver le bouton.

### S19 — Le job COMPLETED ne mène nulle part — **faible**

Fin de parcours nominale (sans `exportGguf`) : le panneau affiche le chemin de l'adaptateur, et le
toast explique qu'il faut « exporter en GGUF puis enregistrer pour le déployer »
(`FineTuning.tsx:394-396`). Aucun bouton ne fait cette action, aucun lien ne mène à l'écran qui la
propose. Le suivi s'arrête juste avant la mise en service, qui est pourtant l'objet du parcours.

---

## 7. Cohérence d'affichage, i18n, accessibilité

### S20 — Vocabulaire mélangé et textes serveur non traduits — **moyen**

- **Statut brut.** Le badge affiche l'énumération telle quelle : `activeJob.status`
  (`FineTuning.tsx:642`) et `job.status` dans l'historique (ligne 799). En français comme en
  anglais on lit donc `EXPORTING_DATASET` à côté d'une étape traduite « Export » / « Export ». Les
  clés `fineTuning.steps.*` existent et pourraient servir aux deux.
- **Étape courante en français dans l'UI anglaise.** `currentStep` est produit par le backend en
  français (« En attente », « Export du dataset... », « Entraînement epoch 2/3 », « Terminé »,
  « Échoué ») et rendu verbatim (`FineTuning.tsx:648`). Idem pour tous les messages d'erreur, y
  compris ceux affichés dans le bandeau rouge et dans les toasts. Un utilisateur anglophone lit un
  suivi bilingue.
- **`steps.FAILED` n'existe pas** dans les deux fichiers de langue — cohérent avec S5, où l'échec
  n'a pas d'étape propre.
- **`LossChart` n'est pas traduit du tout** : « Waiting for loss data… », « Accumulating data… »,
  « Epoch », « Eval loss », « Loss » sont écrits en dur (`LossChart.tsx:20-27`, 33-35), alors que
  les clés `fineTuning.evalLoss` et `fineTuning.trainLoss` existent dans `fr.json`/`en.json` et
  **ne sont utilisées nulle part**. La légende du graphe est par ailleurs absente : deux courbes,
  aucune légende, la distinction repose sur la couleur et le pointillé.

### S21 — Le suivi n'est pas accessible — **moyen**

`FineTuning.tsx` ne contient qu'un seul attribut ARIA sur toute la page (un `aria-hidden` sur
l'icône du bouton de soumission). En particulier :

- la barre de progression (lignes 664-666) est un `div` sans `role="progressbar"` ni
  `aria-valuenow/min/max` — invisible pour un lecteur d'écran ;
- le flux de télémétrie n'est pas une région live (`aria-live="polite"`) : les nouvelles lignes ne
  sont jamais annoncées ;
- l'état des étapes est porté par la seule couleur (bordure/texte), sans `aria-current` ni texte
  alternatif ; l'état SSE combine une pastille colorée et un libellé, ce qui est le bon modèle et
  gagnerait à être généralisé ;
- les icônes Material Symbols de `StepBar` ne sont pas masquées aux lecteurs d'écran, qui
  vocalisent leur ligature (`model_training`, `hourglass_empty`).

### S22 — L'historique ne porte pas les informations de suivi — **faible**

Le tableau (`FineTuning.tsx:768-807`) affiche id, modèle, base, dataset, époques, statut, date.
Manquent : la **durée** (`completedAt - createdAt`, tous deux disponibles), la **loss finale** et
l'**eval_loss**, l'`outputPath`, et le **motif d'échec** — visible uniquement en cliquant la ligne,
ce que rien n'indique. La colonne Date n'a pas l'heure (S13). Le tri est figé sur `createdAt`
décroissant, sans pagination ni filtre par statut ; `loadJobs` n'est appelé qu'au montage, à la fin
d'un job et sur clic « Refresh » — la table ne reflète donc pas l'avancement du job en cours.

---

## 8. Écarts documentation ↔ interface

- **`docs/user/user-manual.fr.md:655`** décrit une case « **Alignement ORPO** » dans le formulaire
  (« DPO et ORPO sont exclusifs ; ORPO a priorité si les deux sont cochés »). **Cette case n'existe
  pas** : `FineTuning.tsx:567-584` n'expose que Multipacking, DPO et Export GGUF. ORPO n'est
  atteignable que par appel API direct (déjà relevé au §7 de l'audit fine-tuning, ici confirmé côté
  documentation).
- Le même passage **omet** deux contrôles réellement présents : le curseur **Validation split**
  (livré avec F9) et la case **Export GGUF & register**. Le premier est précisément celui qui
  conditionne la détection du sur-apprentissage.
- La liste des étapes du manuel (`[QUEUED] → [EXPORT] → [TRAINING] → [IMPORT] → [COMPLETE]`) est
  exacte, mais rien n'y explique ce que l'utilisateur doit surveiller pendant `TRAINING`, ni ce
  qu'il peut faire (annuler — voir S15).
- **`reportPath`** reste typé côté frontend (`FineTuning.tsx:32`) et propagé de bout en bout alors
  qu'il vaut **toujours `null`** : aucun rapport n'est produit. Le champ documente une capacité
  inexistante — constat déjà ouvert au §6 de l'audit fine-tuning, reconduit ici parce qu'il pollue
  le type même du suivi.

---

## 9. Couverture de test

| Élément | Test | État |
|---|---|---|
| `FineTuning.tsx` | — | **aucun** |
| `LossChart.tsx` | — | **aucun** |
| `useSse.ts` | — | **aucun** |
| `useGlobalTasks` (normaliseurs, ETA) | `useGlobalTasks.test.ts` | ✅ |
| `TaskCenter` (relance) | `TaskCenter.retry.test.tsx` | ✅ partiel |
| `parseTrainingOutput` (backend) | — | **aucun** |

Les défauts S1, S2, S3, S5 et S6 sont tous des invariants vérifiables sans navigateur réel, par de
simples tests de rendu (Testing Library, déjà utilisé dans le dépôt) ou de fonction pure :

- une ligne `epoch=0.97 loss=1.2` doit produire un point traçable ;
- un `job.evalLoss` reçu par sondage ne doit pas effacer le point existant ;
- un job `FAILED` pendant `EXPORTING_DATASET` doit allumer l'étape 2, pas l'étape 4 ;
- un job `COMPLETED` doit allumer les cinq étapes ;
- `parseTrainingOutput` doit conserver l'époque fractionnaire.

C'est le levier le moins cher : ces cinq assertions figent l'essentiel des constats élevés.

---

## 10. Priorisation

| # | Constat | Gravité | Effort |
|---|---|---|---|
| S1 | Loss par étape agrégée à un point par époque — courbe à 2-3 points | Élevé | Moyen |
| S3 | Première époque entièrement invisible (entier tronqué) | Élevé | Faible |
| S5 | Un échec est toujours attribué à l'étape « Import » ; la phase fautive n'est pas conservée | Élevé | Faible (UI) + Moyen (DTO) |
| S8 | Logs perdus au rechargement, à la navigation et hors connexion | Élevé | Moyen |
| S15 | Pas d'annulation depuis la page de fine-tuning | Élevé | Trivial |
| S2 | Le sondage efface l'eval_loss (annule le bénéfice de `valSplit`) | Élevé | Trivial |
| S9 | Flux SSE sans `jobId` — logs et courbe attribués au mauvais job | Moyen | Faible |
| S10 | Hook SSE sans garantie de livraison (dernier message seulement) | Moyen | Faible |
| S11 | Tout en INFO, erreurs incluses ; lignes tronquées, non copiables | Moyen | Faible |
| S12 | Silence puis rafale (buffering Python, `\r` de tqdm) | Moyen | Trivial |
| S13 | Ni durée écoulée, ni ETA sur la page ; ETA globale biaisée | Moyen | Faible |
| S14 | `EXPORTING_DATASET` et `IMPORTING_MODEL` sans signal de vie | Moyen | Faible |
| S16 | Motif du 409 jamais affiché | Moyen | Trivial |
| S20 | Statuts bruts, `currentStep` et erreurs en français dans l'UI anglaise | Moyen | Moyen |
| S21 | Aucun attribut ARIA sur les indicateurs de progression | Moyen | Faible |
| S7 | Annulation, échec et redémarrage rendus à l'identique | Moyen | Faible |
| S4 | Axe X catégoriel : `domain`/`tickCount` inertes | Faible | Trivial |
| S6 | L'étape « Complete » n'est jamais allumée | Faible | Trivial |
| S17 | Réponse de création typée comme un job (panneau creux ~4 s) | Faible | Trivial |
| S18 | Indisponibilité du trainer connue seulement à la soumission | Faible | Faible |
| S19 | Job terminé sans chemin vers la mise en service | Faible | Faible |
| S22 | Historique sans durée, loss, ni motif d'échec | Faible | Faible |
| §8 | Manuel décrivant une case ORPO absente, omettant `valSplit` et l'export GGUF | Faible | Trivial |

**Ordre suggéré.**

1. **Le lot trivial à fort rendement** — S2, S3, S6, S15, S16, S4, S12. Une demi-journée : la
   première époque devient visible, la courbe de validation cesse de disparaître, la fin de
   parcours est marquée, l'annulation revient sur la page et les refus s'expliquent. Six des
   sept correctifs tiennent en moins de dix lignes chacun.
2. **La véracité du suivi** — S5 (conserver la phase de l'échec, côté DTO et entité) puis S9
   (`jobId` dans l'évènement SSE, réinitialisation à la sélection d'un job). Ce sont les deux
   endroits où l'interface affirme aujourd'hui quelque chose de faux.
3. **La substance du signal** — S1 (série de loss portée par le job ou par un fichier), puis S8
   (persistance des logs et endpoint de relecture). Les deux partagent la même infrastructure : le
   répertoire de job existe déjà, il ne sert qu'à l'adaptateur.
4. **Le reste** — durée/ETA (S13), phases muettes (S14), niveaux de log (S11), i18n (S20),
   accessibilité (S21), historique (S22), et l'alignement du manuel (§8).

Les tests du §9 sont à écrire **avec** le lot 1, pas après : sans eux, S3 et S6 sont exactement le
genre de régression qui revient au prochain remaniement de la page.
