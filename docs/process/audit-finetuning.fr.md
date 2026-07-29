# Audit — Fine-tuning

> Audit du 2026-07-29. Périmètre : la chaîne complète de fine-tuning, de la soumission d'un job
> à la mise en service du modèle. Croisement code backend (`FineTuningService`,
> `FineTuningController`, `FineTuningRequest`, `BaseModelCatalog`, `DpoGenerationService`,
> `ArticleCommentService`), moteur d'entraînement (`scripts/train.sh`, `scripts/train_host.py`,
> `scripts/export_gguf.py`, `scripts/requirements.txt`), frontend (`pages/FineTuning.tsx`),
> déploiement (`deploy/docker/`, `deploy/k8s/`) et documentation (`docs/tech/technical-doc.fr.md`).
>
> **Constat général.** L'orchestration Java est la partie la plus solide de la chaîne :
> machine à états explicite, verrou d'unicité, annulation coopérative, réconciliation au
> démarrage, traçabilité GED. Les problèmes sont ailleurs, et ils sont structurels. D'une part
> le **moteur d'entraînement n'est physiquement pas joignable** dans le mode de déploiement
> documenté en page d'accueil (`docker compose up`) : ni le script, ni Python ne sont présents
> dans l'image `spectra-api`. D'autre part, quand il est joignable, il **entraîne sur un format
> de conversation qui ne correspond qu'à un seul des quatre modèles de base du catalogue** — pas
> celui configuré par défaut. Le fine-tuning est la fonctionnalité mise en avant comme
> différenciateur dans le README ; à ce stade elle n'est vérifiable ni en déploiement, ni en
> qualité de résultat.
>
> Aucun test automatisé ne couvre le moteur d'entraînement : les deux seuls tests
> (`FineTuningGedTraceTest`, `FineTuningSftExclusionTest`) portent sur des méthodes périphériques
> de `FineTuningService`, et il n'existe aucun test Python pour `train_host.py`.

---

## 1. Blocages de déploiement

### F1 — Le script d'entraînement est absent de l'image `spectra-api` — **bloquant**

`FineTuningService` résout `spectra.fine-tuning.script` (défaut `./scripts/train.sh`) en chemin
absolu dans le constructeur, puis l'exécute via `ProcessBuilder` avec `workDir` comme répertoire
courant. Dans un conteneur, cela donne `/app/scripts/train.sh`. Or :

- `deploy/docker/Dockerfile` — l'étage runtime est `eclipse-temurin:25-jre` et ne copie que
  `app.jar` (plus l'installation de `llmfit`). **Pas de `scripts/`, pas de Python, pas de
  `torch`/`transformers`/`peft`.**
- `deploy/docker/docker-compose.yml` — le service `spectra-api` ne monte que `./data:/app/data`.
  Il n'y a **aucun montage de `./scripts`**.
- `deploy/k8s/base/07-spectra-api.yaml` — mêmes constats : volumes `documents`, `dataset`,
  `fine-tuning`, `models`, aucun volume de scripts.

Conséquence : tout job lancé depuis l'UI passe `PENDING → EXPORTING_DATASET → TRAINING`, puis
`pb.start()` lève une `IOException` (« No such file or directory »), rattrapée par le `catch`
générique de `runAsync`, et le job finit `FAILED` avec un message d'erreur d'`IOException` brut.
La fonctionnalité phare du produit — « QLoRA fine-tuning ✅ » dans le tableau comparatif du
README — n'est **pas exécutable** par le chemin d'installation en une commande que le README
recommande.

La documentation, elle, décrit un montage qui n'existe pas :

- `docs/tech/technical-doc.fr.md:1749` — `- ./scripts:/app/scripts   ← train.sh accessible depuis FineTuningService`
- `docs/tech/c4-level-2-containers.fr.md:42` — « Volume monté : ./scripts »

Le diagramme C4 et le compose réel ne décrivent donc pas le même système. C'est probablement la
raison pour laquelle l'écart n'a pas été détecté.

Recommandation : décider explicitement du modèle d'exécution, puis l'aligner partout.
Deux options cohérentes, à ne pas mélanger :

1. **Entraînement dans le conteneur** — monter `./scripts` dans `spectra-api` et construire une
   image disposant de Python et de la stack d'entraînement (idéalement une image dédiée, l'image
   JRE ne devant pas embarquer `torch`).
2. **Entraînement sur l'hôte** — assumer que `train.sh` s'exécute hors conteneur, et alors
   documenter que le fine-tuning n'est pas disponible en mode Docker, ou introduire un service
   `spectra-trainer` séparé que `FineTuningService` appelle par HTTP plutôt que par
   `ProcessBuilder`.

Tant que ce n'est pas tranché, la voie la moins coûteuse est de **détecter l'absence du script au
démarrage** et de refuser les soumissions avec un message actionnable (HTTP 503 + explication),
au lieu de laisser chaque job échouer à mi-course sur une `IOException`.

### F2 — `scripts/train.sh` n'a pas le bit exécutable — **bloquant (mode hôte)**

`git ls-files -s scripts/train.sh` renvoie le mode **`100644`**. `FineTuningService.runProcess`
construit pourtant `ProcessBuilder(List.of(trainingScript, …))`, c'est-à-dire une **exécution
directe du fichier**, pas une invocation par un interpréteur. Sur un clone frais Linux/macOS, le
premier job échoue donc sur `error=13, Permission denied` — y compris dans le seul mode où le
script est présent.

Le dépôt sait pourtant gérer ce point ailleurs : `deploy/docker/Dockerfile.llama:26` fait
explicitement `chmod +x` sur ses entrypoints, et `gke-*.sh`, `llm-chat-entrypoint.sh`,
`check-doc-links.py` sont bien en `100755`. `train.sh` (comme `start.sh`, `setup.sh`,
`pipeline.sh`) est resté en 644, et rien nulle part ne le `chmod`.

Recommandation : `git update-index --chmod=+x scripts/train.sh` ; et, par robustesse, invoquer
le script via un interpréteur explicite côté Java (`bash <script> …`) plutôt que de dépendre du
bit de permission — ce qui règle du même coup l'impossibilité d'exécuter un `.sh` sous Windows,
alors que le README annonce un support Windows (`scripts\start.bat`).

---

## 2. Correction de l'entraînement

### F3 — Le gabarit de conversation est codé en dur et ne correspond qu'à 1 modèle sur 4 — **critique**

`train_host.py` construit les exemples avec un gabarit littéral
(`ConversationDataset._encode`, lignes 143-155, et `PackedDataset._format`, lignes 246-255) :

```
<|system|>\n{content}</s>\n
<|user|>\n{content}</s>\n<|assistant|>\n
{content}</s>\n
```

C'est le gabarit **Zephyr**, celui de `TinyLlama-1.1B-Chat-v1.0`. Confronté au catalogue
`scripts/base_models.json` :

| Alias | Gabarit réel du modèle | Verdict |
|---|---|---|
| `tinyllama` | `<|system|>…</s>` (Zephyr) | ✅ correspond |
| `phi3` (**défaut**, `application.yml:172`) | `<|system|>…<|end|>` — terminateur `<\|end\|>`, pas `</s>` | ❌ |
| `mistral` | `[INST] … [/INST]` — `<\|user\|>` n'existe pas dans le vocabulaire | ❌ |
| `llama3` | `<\|start_header_id\|>…<\|eot_id\|>` — `</s>` n'existe pas dans le vocabulaire | ❌ |

Le service, lui, passe par `LlamaCppChatClient` → `/v1/chat/completions`, endpoint qui applique
le **gabarit embarqué dans le GGUF** (hérité du `tokenizer_config.json` du modèle fusionné).
Aucune surcharge `--chat-template` n'existe dans le dépôt (`grep` sur `chat-template`,
`chat_template`, `--jinja` : zéro occurrence). Entraînement et service divergent donc pour trois
des quatre bases supportées — dont celle configurée par défaut.

Les conséquences ne sont pas cosmétiques :

- **Sur `phi3`**, le modèle apprend à terminer ses réponses par `</s>` alors que llama-server
  s'arrête sur `<|end|>`. Le symptôme attendu est une **génération qui ne s'arrête pas**, le
  modèle enchaînant sur des tours inventés.
- **Sur `mistral`/`llama3`**, les marqueurs de rôle sont tokenisés comme du texte ordinaire : le
  modèle n'apprend aucune structure de dialogue exploitable au service, et l'EOS n'est jamais
  supervisé.

Recommandation : remplacer le gabarit littéral par `tokenizer.apply_chat_template()`, et dériver
le masque de supervision de la position réelle du segment assistant (par exemple en templatisant
deux fois, avec et sans le dernier tour, et en supervisant le delta). C'est la seule construction
qui reste correcte quand le modèle de base change — ce que le catalogue autorise explicitement.

### F4 — Le mode packing désactive silencieusement le masquage du prompt — **critique**

`ConversationDataset` masque soigneusement le prompt à `-100` (le commentaire de classe explique,
à juste titre, que sans cela « le modèle apprend à régénérer la question au lieu de seulement
répondre »). Mais `PackedDataset._flush` (ligne 237-241) écrit :

```python
"labels": torch.tensor(list(buffer), dtype=torch.long),
```

soit **la séquence entière**, tours système et utilisateur compris. Cocher « packing » dans l'UI
change donc l'objectif d'apprentissage — de « apprendre à répondre » à « apprendre à reproduire
toute la conversation » — sans avertissement nulle part, alors que la case est présentée comme
une simple optimisation de débit (« 20-40 % de réduction du nombre d'étapes »).

Écart secondaire du même ordre : `PackedDataset` tokenise avec `add_special_tokens=True`,
`ConversationDataset` avec `add_special_tokens=False`. Le BOS est donc présent dans un mode,
absent dans l'autre, et présent au service — troisième variante de distribution.

Recommandation : conserver les labels masqués lors du packing (concaténer les paires
`(input_ids, labels)` déjà calculées par `ConversationDataset` plutôt que de re-tokeniser du
texte brut), et harmoniser `add_special_tokens` entre les deux chemins.

### F5 — Le chemin GPU (Unsloth) ignore `--max-length` — **moyen**

`train_host.py:302-306` :

```python
model, tokenizer = FastLanguageModel.from_pretrained(
    model_name=hf_model, max_seq_length=512, load_in_4bit=True,
)
```

`512` est en dur, alors que `MAX_SEQ_LENGTH = args.max_length` est respecté partout ailleurs.
Sur GPU — c'est-à-dire dans le seul cas où allonger le contexte a un intérêt pratique —
`SPECTRA_TRAIN_MAX_LENGTH` n'a donc aucun effet.

### F6 — `loraAlpha` et `learningRate` ne sont jamais envoyés par l'UI — **moyen**

`FineTuning.tsx` ne soumet que `modelName`, `baseModel`, `epochs`, `loraRank`, `minConfidence` et
les drapeaux (`onFormSubmit`, ligne 399-402 : `fineTuningApi.createJob(data)` avec `data` = les
champs du formulaire). Le formulaire n'expose ni `loraAlpha`, ni `learningRate`, ni `orpoEnabled`.

`FineTuningRequest` applique alors ses défauts : `loraAlpha = 128`, quel que soit le rang choisi.
Le facteur d'échelle LoRA (`alpha / r`) dérive donc avec le rang :

| Rang choisi dans l'UI | alpha appliqué | Échelle effective |
|---|---|---|
| 64 (défaut) | 128 | 2 — conventionnel |
| 16 | 128 | 8 |
| 8 | 128 | 16 |
| 4 (minimum autorisé) | 128 | 32 |

Baisser le rang pour « entraîner plus léger » multiplie donc l'amplitude effective de
l'adaptation par 16, ce qui est le contraire de l'intention et une cause classique
d'instabilité. Le même écran fait pourtant le bon calcul ailleurs :
`exportRecipe` (ligne 277) envoie `loraAlpha: formValues.loraRank * 2`. Les deux chemins de la
même page ne sont pas d'accord entre eux.

Recommandation : soit exposer `loraAlpha` dans le formulaire, soit — plus simple et plus sûr —
dériver le défaut de `loraRank` côté backend (`loraAlpha = 2 * loraRank` dans le compact
constructor de `FineTuningRequest`) plutôt que de le figer à 128.

---

## 3. Câblage des fonctionnalités

### F7 — L'apprentissage continu à partir des commentaires approuvés n'entraîne pas sur ces commentaires — **élevé**

`ArticleCommentService.triggerRetraining` (ligne 189-205) :

1. exporte les paires DPO issues des commentaires vers `data/dataset/comments_dpo.jsonl` ;
2. soumet un job `dpoEnabled = true`.

Or `FineTuningService.exportDpoDataset` ne lit pas ce fichier : il appelle
`dpoGenerator.getAllPairs()`, qui agrège `dpo_pairs.jsonl` (génération DPO) et
`dpo_preference_pairs.jsonl` (préférences A/B du Playground). **`comments_dpo.jsonl` n'est lu par
personne** dans tout le backend.

Le job entraîne donc sur des paires sans rapport avec les commentaires qui l'ont déclenché — et
si les deux autres fichiers sont vides, il échoue sur « Aucune paire de préférence disponible ».
La boucle « DPO / continuous learning » annoncée dans le tableau comparatif du README ne se
referme pas.

Deux défauts aggravants sur le même bloc :

- **Le retour `null` de `submit()` n'est pas traité.** `submit()` renvoie `null` quand un
  entraînement tourne déjà (contrat documenté, traduit en 409 par le contrôleur). Ici le résultat
  est directement journalisé : `log.info("Re-entraînement DPO automatique soumis : … jobId={}")`
  affiche `jobId=null` comme un succès. `BatchService:96-99` gère correctement ce cas — la
  divergence est locale à `ArticleCommentService`.
- **`exportGguf` vaut `false`.** L'appel passe `null` en dernier argument, donc le défaut `false`.
  Même en cas de succès, le ré-entraînement automatique produit un adaptateur PEFT jamais
  fusionné, jamais converti, jamais enregistré — donc jamais servi. Un cycle d'apprentissage
  continu qui ne met rien en service n'a pas d'effet observable.

### F8 — Trois formats de prompt différents entre SFT, DPO et service — **élevé**

| Étape | Format du prompt |
|---|---|
| SFT (`train_host.py`) | `<\|system\|>\n…</s>\n<\|user\|>\n…</s>\n<\|assistant\|>\n` |
| DPO (`DpoGenerationService:273`) | `system + "\n\n" + user` — texte brut, aucun marqueur |
| Service (`LlamaCppChatClient`) | gabarit embarqué dans le GGUF |

TRL n'applique aucun gabarit à un `prompt` déjà sous forme de chaîne : la phase DPO optimise donc
sur une mise en forme qui n'est **ni celle du SFT, ni celle du service**. Un enchaînement
SFT → DPO sur le même adaptateur enseigne successivement deux conventions incompatibles.

Recommandation : centraliser la mise en forme (une seule fonction, dérivée du tokenizer, partagée
par la génération de dataset SFT, la génération DPO et l'entraînement) — c'est le même correctif
de fond que F3.

### F9 — Les meilleurs réglages de `train_host.py` sont inaccessibles depuis l'application — **élevé**

`train_host.py` expose `--val-split`, `--lora-target`, `--neftune-alpha`, `--warmup-ratio` et
`--resume-adapter`. `train.sh` n'en transmet **aucun** : il ne relaie que `--packing`, `--dpo`,
`--orpo` et `--max-length`. Et `FineTuningService.runTrainingProcess` envoie une liste positionnelle
figée de 10 arguments. Ces options ne sont donc atteignables qu'en lançant le script à la main.

Le plus coûteux est `--val-split` : **sans split de validation, il n'existe aucun signal de
sur-apprentissage**. Le seul indicateur remonté à l'UI est la *training loss*, qui décroît par
construction. Sur un dataset synthétique de quelques centaines de paires, avec `epochs` par défaut
à 3 et un maximum autorisé de 50, le sur-apprentissage est le mode de défaillance le plus probable
de toute la chaîne — et c'est précisément celui qu'on ne mesure pas.

`--resume-adapter` mérite aussi d'être signalé : l'entraînement incrémental fait l'objet d'un cas
de test documenté (`docs/process/test-plan-ingestion.fr.md:363`) mais n'est pas exposé par l'API.
Chaque job repart de zéro.

Recommandation : ajouter au minimum `valSplit` (défaut ~0.1) à `FineTuningRequest`, le relayer
jusqu'au script, et afficher `eval_loss` à côté de la loss d'entraînement dans `LossChart` — le
parseur de sortie (`parseTrainingOutput`) ne reconnaît d'ailleurs aujourd'hui que `loss`, alors
que `ProgressLogger` imprime déjà `eval_loss` quand une évaluation existe.

### F10 — Passage d'arguments positionnel et fragile — **faible**

`runTrainingProcess` construit une liste de 10 arguments positionnels, que `train.sh` relit en
`$1…${10}` avec des défauts (`PACKING="${8:-false}"`). Insérer un paramètre au milieu décale
silencieusement tous les drapeaux booléens suivants, sans erreur ni au build ni au runtime. La
liste étant déjà construite programmatiquement côté Java, passer à des options nommées
(`--dataset`, `--lora-rank`, …) supprimerait la classe entière de bug, et rendrait F9 trivial à
corriger.

---

## 4. Environnement d'exécution & chaîne d'approvisionnement

### F11 — `scripts/requirements.txt` : bornes ouvertes et dépendances manquantes — **moyen**

```
unsloth>=2026.5.2   trl>=1.4.0   transformers>=5.8.1   datasets>=4.8.5
bitsandbytes>=0.49.2   accelerate>=1.13.0
```

- **Aucune borne supérieure.** `train_host.py` utilise des API qui ont déjà été renommées dans
  ces bibliothèques (`processing_class`, `eval_strategy`, `neftune_noise_alpha`). Une
  installation faite six mois après l'autre n'entraîne pas le même code — et rien ne le
  détecterait, faute de test (voir F13).
- **`torch` et `peft` sont importés directement** par `train_host.py` (`import torch`,
  `from peft import get_peft_model, LoraConfig, TaskType, PeftModel`) mais **absents du fichier**.
  Ils n'arrivent que par transitivité d'`unsloth`. Le commentaire d'en-tête conseille pourtant aux
  utilisateurs CPU de « remplacer unsloth par transformers + peft » — ce qui, appliqué
  littéralement, produit un environnement sans `peft` installé et un `ImportError`.

Recommandation : épingler des bornes (`>=x,<y`), ajouter `torch` et `peft`, et fournir un second
fichier `requirements-cpu.txt` cohérent avec le chemin CPU réellement emprunté par le script.

### F12 — `export_gguf.py` télécharge du code distant non épinglé à l'exécution — **moyen**

`export_gguf.py:68-83` : si `convert_hf_to_gguf.py` n'est pas trouvé localement, le script le
télécharge depuis `raw.githubusercontent.com/ggerganov/llama.cpp/master/…` et l'exécute
immédiatement. Deux problèmes distincts :

- **Contradiction avec la promesse produit.** Le README annonce « 100 % local · No cloud » et un
  usage « même air-gapped ». L'export GGUF — l'étape qui rend le modèle fine-tuné déployable —
  exige en pratique un accès Internet sortant.
- **Approvisionnement non épinglé.** `master` n'est pas une référence stable : le script exécuté
  n'est pas le même d'un jour à l'autre, et rien n'en vérifie l'intégrité.

Autres remarques sur le même fichier :

- `subprocess.run(…, capture_output=True)` (ligne 89-93) **avale toute la sortie** de la
  conversion. L'étape dure plusieurs minutes et n'affiche rien dans le flux SSE, alors que
  `runProcess` côté Java est conçu pour diffuser ligne à ligne — le mécanisme existe, il est
  neutralisé ici.
- La persona est **réécrite en dur** ligne 116 (`"Tu es un assistant spécialisé dans
  l'exploitation autoroutière."`), juste sous un commentaire expliquant qu'elle doit correspondre
  à `AssistantPersona.SYSTEM_PROMPT`. Les deux sont identiques aujourd'hui par coïncidence ;
  modifier la constante Java désynchronisera silencieusement l'instruction imprimée.

### F13 — Aucune couverture de test du moteur d'entraînement — **moyen**

- Côté Java, `FineTuningGedTraceTest` et `FineTuningSftExclusionTest` testent la traçabilité GED
  et le filtre de catégories — deux méthodes périphériques, atteintes par réflexion. Ni la
  machine à états, ni le verrou d'unicité (`trainingRunning`), ni l'annulation, ni la
  construction de la ligne de commande ne sont couverts.
- Côté Python, il n'existe **aucun test** : les seuls `test_*.py` du dépôt sont dans
  `services/reranker` et `services/docparser`. `ci.yml` ne lance rien sur `scripts/`, et
  `shellcheck.yml` ne mentionne pas `train.sh`.

Les défauts F3, F4, F5 sont tous des invariants testables sans GPU ni téléchargement de modèle
(un tokenizer factice suffit à vérifier que les labels du prompt valent `-100`, que le gabarit
appliqué provient bien du tokenizer, que `max_length` est respecté). C'est le levier le moins
cher pour éviter la réapparition de cette classe de bugs.

---

## 5. Cohérence entraînement / service

### F14 — Le modèle est entraîné sur une distribution qu'il ne voit jamais en production — **moyen (conception)**

`TrainingPair.of` construit systématiquement `system = AssistantPersona.SYSTEM_PROMPT`,
`user = instruction`, sans contexte documentaire. Au service, `RagService` compose
`system = persona + "\n" + "Contexte:\n%s"` (`SYSTEM_PROMPT_TEMPLATE`, ligne 79-80) avec les
chunks récupérés, et `user = question`.

Le modèle est donc affiné sur des prompts courts sans contexte, puis servi avec des prompts longs
saturés de contexte. L'intention est saine — le commentaire d'`AssistantPersona` explique
correctement pourquoi la persona doit rester identique — mais la cohérence s'arrête à la persona
et ne couvre pas la **forme** du prompt.

Recommandation : générer une part du dataset SFT sous la forme réellement servie (persona +
contexte extrait + question → réponse ancrée). C'est aussi le seul moyen d'apprendre au modèle
à *utiliser* le contexte plutôt qu'à répondre de mémoire — c'est-à-dire d'obtenir le bénéfice
attendu du couplage RAG + fine-tuning revendiqué par le produit.

---

## 6. Documentation désynchronisée

`docs/tech/technical-doc.fr.md` §5 décrit un système qui n'est plus celui du code :

- **« Simulation »** — le tableau des modes annonce un repli « Python absent → simulation :
  `adapter/training_complete.json` + logs epoch simulés ». Ce repli n'existe plus : `train.sh`
  fait `exec python3 train_host.py` et échoue si Python est absent. Cette ligne de documentation
  masque directement F1 : elle laisse croire qu'un environnement sans Python dégrade
  gracieusement, alors qu'il échoue.
- **« Rapport : génère `REPORT.md` dans le dossier du job »** — `FineTuningService` n'écrit aucun
  rapport. Le champ `FineTuningJob.reportPath` est **toujours `null`** et pourtant propagé dans
  le DTO, l'entité JPA (`FineTuningJobEntity:35`), toutes les méthodes `with*`, et jusqu'au type
  TypeScript du frontend (`FineTuning.tsx:31`). Soit la fonctionnalité est à implémenter, soit le
  champ est à supprimer — en l'état il documente une capacité inexistante.
- **Montage `./scripts`** (ligne 1749) et **diagramme C4** (`c4-level-2-containers.fr.md:42`) —
  voir F1.
- `docs/tech/llama-cpp.fr.md:308` mentionne encore « simulation disponible dans
  `scripts/train.sh` ».

---

## 7. Points mineurs

- **Annulation puis resoumission → 409.** `cancelJob` positionne `FAILED` et tue le process, mais
  ne libère pas `trainingRunning` — c'est le `finally` du thread asynchrone qui s'en charge.
  L'utilisateur qui annule et relance immédiatement reçoit un 409 transitoire, sans explication.
- **Réponse de création mal typée côté frontend.** `POST /api/fine-tuning` renvoie
  `{jobId, status}` (une `Map<String,String>`), mais `onFormSubmit` la traite comme un
  `FineTuningJob` complet et la passe à `setActiveJob`. `status`, `modelName`, `totalEpochs` sont
  `undefined` jusqu'au prochain sondage ; `stepIndex(undefined)` renvoie `-1` et la barre d'étapes
  s'affiche vide dans l'intervalle.
- **Message d'erreur 409 non affiché.** Le contrôleur renvoie `{"error": "Un entraînement est
  déjà en cours"}` ; le frontend lit `err.response.data.detail` et retombe sur
  `err.message` (« Request failed with status code 409 »). Le message utile est perdu.
- **ORPO sans interface.** ORPO est implémenté de bout en bout (DTO, service, `train.sh`,
  `train_host.py`) mais n'a aucun contrôle dans `FineTuning.tsx` — il n'est atteignable que par
  appel API direct.
- **Divergence de validation du nom de modèle.** Le schéma zod du frontend impose
  `/^[a-z0-9-_]+$/` (minuscules uniquement) là où `FineTuningRequest` accepte
  `[A-Za-z0-9][\w./@:-]*`. Sans conséquence (le frontend est plus strict), mais un nom accepté par
  l'API est refusé par l'UI.
- **Volume des logs SSE.** `runProcess` republie **chaque ligne** de sortie du process dans le
  sink (buffer 500). Avec `logging_steps=1`, une époque sur quelques centaines d'exemples suffit à
  faire tourner tout le buffer. Un échantillonnage, ou un filtrage sur les lignes de progression,
  serait plus lisible.
- **`parseTrainingOutput` recompile deux regex par ligne** de sortie et déclenche sur toute ligne
  contenant `epoch`. Sans gravité, mais les motifs gagneraient à être des constantes `Pattern`.

---

## 8. Priorisation

| # | Constat | Gravité | Effort |
|---|---|---|---|
| F1 | Script/Python absents de l'image `spectra-api` (fine-tuning inopérant en Docker) | Bloquant | Élevé — décision d'architecture |
| F2 | `train.sh` sans bit exécutable | Bloquant | Trivial |
| F3 | Gabarit de conversation en dur, faux pour 3 bases sur 4 (dont le défaut `phi3`) | Critique | Moyen |
| F4 | Le packing supprime le masquage du prompt | Critique | Faible |
| F7 | Ré-entraînement automatique branché sur le mauvais jeu de données, et jamais déployé | Élevé | Faible |
| F8 | Trois formats de prompt SFT / DPO / service | Élevé | Moyen (même correctif que F3) |
| F9 | Pas de split de validation → aucun signal de sur-apprentissage | Élevé | Faible |
| F6 | `loraAlpha` figé à 128 quel que soit le rang | Moyen | Trivial |
| F5 | `--max-length` ignoré sur GPU | Moyen | Trivial |
| F11 | Dépendances non bornées, `torch`/`peft` manquants | Moyen | Trivial |
| F12 | Téléchargement non épinglé de `convert_hf_to_gguf.py` | Moyen | Faible |
| F13 | Aucun test du moteur d'entraînement | Moyen | Moyen |
| F14 | Distribution d'entraînement ≠ distribution de service | Moyen | Moyen (conception) |
| F10 | Arguments positionnels fragiles | Faible | Faible |
| §6 | Documentation décrivant un système différent du code | Faible | Faible |

**Ordre suggéré.** F2 puis F6/F5/F11 (corrections triviales, sans risque). Ensuite F4, F7 et F9 —
trois corrections localisées à fort effet sur la qualité du modèle produit. Puis F3/F8 ensemble,
en centralisant la mise en forme des conversations, accompagnés des tests de F13 qui les
verrouillent. F1 en parallèle, car c'est une décision d'architecture, pas un correctif : tant
qu'elle n'est pas prise, ajouter un contrôle de disponibilité au démarrage évite au moins que
chaque job échoue silencieusement à mi-course.
